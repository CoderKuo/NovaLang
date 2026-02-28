package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.NovaClass;

import java.util.*;

/**
 * 虚方法分派器。
 *
 * <p>处理 INVOKE_VIRTUAL / INVOKE_INTERFACE / INVOKE_SPECIAL 指令中
 * 实际的方法查找与调用链，从 MirCallDispatcher 拆分而来。</p>
 */
final class VirtualMethodDispatcher {

    // ===== JVM 类名 =====
    private static final String JAVA_LINKED_HASH_SET = "java/util/LinkedHashSet";
    private static final String MARKER_SUPER = "$super$";

    private final Interpreter interp;
    private final MemberResolver resolver;
    private final MirCallDispatcher dispatcher;

    VirtualMethodDispatcher(Interpreter interp, MemberResolver resolver, MirCallDispatcher dispatcher) {
        this.interp = interp;
        this.resolver = resolver;
        this.dispatcher = dispatcher;
    }

    // ============ 虚方法分派主链 ============

    NovaValue invokeVirtualMethod(NovaValue receiver, String methodName,
                                   String owner, List<NovaValue> args) {
        // 作用域函数 / Lambda body 内引用外部函数: MirCallable 重定向
        if (receiver instanceof MirCallable) {
            if (dispatcher.scopeReceiver != null) {
                receiver = dispatcher.scopeReceiver;
            } else if (!"invoke".equals(methodName)) {
                NovaValue envVal = interp.getEnvironment().tryGet(methodName);
                if (envVal instanceof NovaCallable) return ((NovaCallable) envVal).call(interp, args);
            }
        }

        // NovaObject 快速路径（最常见的接收者类型，前置避免 8 层 instanceof 检查）
        if (receiver instanceof NovaObject) {
            NovaObject obj = (NovaObject) receiver;
            if ("getClass".equals(methodName)) return obj.getNovaClass();
            if (owner != null && owner.startsWith(MARKER_SUPER)) {
                return invokeSuper(obj, methodName, args);
            }
            NovaValue r = tryInvokeOnObject(obj, methodName, owner, args);
            if (r != null) return r;
            // method not found on object → fall through to generic fallbacks
        }

        // 按接收者类型分派（其余类型）
        if (receiver instanceof NovaExternalObject) {
            NovaValue r = invokeOnExternal((NovaExternalObject) receiver, methodName, args);
            if (r != null) return r;
        }
        if (receiver instanceof NovaClass) {
            NovaValue r = invokeOnClass((NovaClass) receiver, methodName, owner, args);
            if (r != null) return r;
        }
        NovaValue r = tryResultProtocol(receiver, methodName, args);
        if (r != null) return r;
        r = tryIteratorProtocol(receiver, methodName);
        if (r != null) return r;
        if (receiver instanceof NovaEnumEntry) {
            r = invokeOnEnumEntry((NovaEnumEntry) receiver, methodName, args);
            if (r != null) return r;
        }
        r = trySpecialTypes(receiver, methodName, owner, args);
        if (r != null) return r;
        return invokeGenericFallback(receiver, methodName, args);
    }

    /** 处理带命名参数的方法调用 */
    NovaValue invokeWithNamedArgs(NovaValue receiver, String methodName,
                                   String owner, List<NovaValue> allArgs, String namedInfo) {
        // namedInfo 格式: "named:positionalCount:key1,key2"
        String[] parts = namedInfo.split(":", 3);
        int positionalCount = Integer.parseInt(parts[1]);
        String[] namedKeys = parts.length > 2 && !parts[2].isEmpty() ? parts[2].split(",") : new String[0];

        List<NovaValue> positionalArgs = allArgs.subList(0, positionalCount);
        Map<String, NovaValue> namedArgs = new LinkedHashMap<>();
        for (int i = 0; i < namedKeys.length; i++) {
            namedArgs.put(namedKeys[i], allArgs.get(positionalCount + i));
        }

        // 解析目标方法
        NovaValue callable = resolver.resolveMemberOnValue(receiver, methodName, null);
        if (callable instanceof NovaCallable) {
            NovaCallable func = (NovaCallable) callable;
            if (func.supportsNamedArgs()) {
                return func.callWithNamed(interp, positionalArgs, namedArgs);
            }
        }
        // fallback: 忽略命名参数，使用全部参数作为位置参数
        return invokeVirtualMethod(receiver, methodName, owner, allArgs);
    }

    // ============ 按接收者类型分派 ============

    /** NovaExternalObject: 安全策略检查 + PrintStream/Iterator/Java 反射 */
    private NovaValue invokeOnExternal(NovaExternalObject receiver, String methodName, List<NovaValue> args) {
        Object javaObj = receiver.toJavaValue();
        if (javaObj != null) {
            String className = javaObj.getClass().getName();
            if (!interp.getSecurityPolicy().isMethodAllowed(className, methodName)) {
                throw NovaSecurityPolicy.denied("Cannot call method '" + methodName + "' on " + className);
            }
        }
        if (javaObj instanceof java.io.PrintStream) {
            java.io.PrintStream ps = (java.io.PrintStream) javaObj;
            if ("println".equals(methodName)) {
                if (args.isEmpty()) ps.println();
                else {
                    NovaValue arg = args.get(0);
                    ps.println(arg == null || arg instanceof NovaNull ? "null" : arg.asString());
                }
                return NovaNull.UNIT;
            }
            if ("print".equals(methodName)) {
                if (!args.isEmpty()) {
                    NovaValue arg = args.get(0);
                    ps.print(arg == null || arg instanceof NovaNull ? "null" : arg.asString());
                }
                return NovaNull.UNIT;
            }
        }
        if (javaObj instanceof java.util.Iterator) {
            java.util.Iterator<?> it = (java.util.Iterator<?>) javaObj;
            if ("hasNext".equals(methodName)) return NovaBoolean.of(it.hasNext());
            if ("next".equals(methodName)) {
                Object val = it.next();
                return val instanceof NovaValue ? (NovaValue) val : AbstractNovaValue.fromJava(val);
            }
        }
        NovaValue extMember = resolver.resolveMemberOnExternal(receiver, methodName, null);
        if (extMember instanceof NovaCallable) return ((NovaCallable) extMember).call(interp, args);
        if (extMember != null && args.isEmpty()) return extMember;
        return null;
    }

    /** NovaClass: 枚举静态方法 / 单例委托 / 静态方法 */
    private NovaValue invokeOnClass(NovaClass cls, String methodName, String owner, List<NovaValue> args) {
        NovaValue enumVal = interp.getEnvironment().tryGet(cls.getName());
        if (enumVal instanceof NovaEnum) {
            NovaEnum enumType = (NovaEnum) enumVal;
            if ("values".equals(methodName)) return new NovaList(new ArrayList<>(enumType.values()));
            if ("valueOf".equals(methodName) && args.size() == 1) {
                NovaEnumEntry entry = enumType.getEntry(args.get(0).asString());
                if (entry != null) return entry;
                throw new NovaRuntimeException("No enum constant " + args.get(0).asString());
            }
        }
        NovaValue instance = cls.getStaticField("INSTANCE");
        if (instance != null && !(instance instanceof NovaNull)) {
            return invokeVirtualMethod(instance, methodName, owner, args);
        }
        NovaCallable staticMethod = cls.findMethod(methodName);
        if (staticMethod != null) return staticMethod.call(interp, args);
        return null;
    }

    /** NovaResult 成员 + 非 Result 的 isErr/isOk 回退 */
    private NovaValue tryResultProtocol(NovaValue receiver, String methodName, List<NovaValue> args) {
        if (receiver instanceof NovaResult) {
            String resolverName = methodName;
            if ("getValue".equals(methodName)) resolverName = "value";
            else if ("getError".equals(methodName)) resolverName = "error";
            NovaValue member = resolver.resolveMemberOnValue(receiver, resolverName, null);
            if (member instanceof NovaCallable) return ((NovaCallable) member).call(interp, args);
            if (member != null) return member;
        } else {
            if ("isErr".equals(methodName)) return NovaBoolean.FALSE;
            if ("isOk".equals(methodName)) return NovaBoolean.TRUE;
            if ("getValue".equals(methodName)
                    && !(receiver instanceof NovaObject && ((NovaObject) receiver).getMethod("getValue") != null)) {
                return receiver;
            }
        }
        return null;
    }

    /** Iterator 协议: iterator() / hasNext() / next() */
    private NovaValue tryIteratorProtocol(NovaValue receiver, String methodName) {
        if ("iterator".equals(methodName)) {
            // NovaMap 优先检查自定义 iterator 方法（如 Channel 的迭代器）
            if (receiver instanceof NovaMap) {
                NovaValue entry = ((NovaMap) receiver).get(NovaString.of("iterator"));
                if (entry instanceof NovaCallable) return ((NovaCallable) entry).call(interp, java.util.Collections.emptyList());
                // 无自定义 iterator → 走 Iterable 默认路径
            }
            if (receiver instanceof Iterable) {
                return new NovaExternalObject(((Iterable<?>) receiver).iterator());
            }
            if (receiver instanceof NovaExternalObject) {
                Object jObj = receiver.toJavaValue();
                if (jObj instanceof Iterable) return new NovaExternalObject(((Iterable<?>) jObj).iterator());
            }
        }
        if (("hasNext".equals(methodName) || "next".equals(methodName))
                && receiver instanceof NovaExternalObject) {
            Object jObj = receiver.toJavaValue();
            if (jObj instanceof java.util.Iterator) {
                java.util.Iterator<?> it = (java.util.Iterator<?>) jObj;
                if ("hasNext".equals(methodName)) return NovaBoolean.of(it.hasNext());
                Object val = it.next();
                return val instanceof NovaValue ? (NovaValue) val : AbstractNovaValue.fromJava(val);
            }
        }
        return null;
    }

    /** NovaEnumEntry: name/ordinal + 方法调用 */
    private NovaValue invokeOnEnumEntry(NovaEnumEntry entry, String methodName, List<NovaValue> args) {
        if ("name".equals(methodName)) return NovaString.of(entry.name());
        if ("ordinal".equals(methodName)) return NovaInt.of(entry.ordinal());
        NovaCallable method = entry.getMethod(methodName);
        if (method != null) return dispatcher.bindAndExecute(entry, method, args);
        NovaValue member = resolver.resolveMemberOnValue(entry, methodName, null);
        if (member instanceof NovaCallable) return ((NovaCallable) member).call(interp, args);
        if (member != null && args.isEmpty()) return member;
        return null;
    }

    /** super.method() — 父类方法分派 */
    private NovaValue invokeSuper(NovaObject obj, String methodName, List<NovaValue> args) {
        NovaClass superClass = obj.getNovaClass().getSuperclass();
        if (superClass != null) {
            NovaCallable method = superClass.findCallableMethod(methodName);
            if (method != null) return dispatcher.bindAndExecute(obj, method, args);
        }
        Object javaDelegate = obj.getJavaDelegate();
        if (javaDelegate != null) {
            try {
                NovaExternalObject ext = new NovaExternalObject(javaDelegate);
                NovaValue member = resolver.resolveMemberOnExternal(ext, methodName, null);
                if (member instanceof NovaCallable) return ((NovaCallable) member).call(interp, args);
                if (member != null && args.isEmpty()) return member;
            } catch (Exception e) { /* fall through */ }
        }
        throw new NovaRuntimeException("super method '" + methodName + "' not found");
    }

    /** NovaObject: 可见性检查 + 方法查找 */
    private NovaValue tryInvokeOnObject(NovaObject obj, String methodName, String owner, List<NovaValue> args) {
        // <init> 调用（次级构造器委托主构造器）
        if ("<init>".equals(methodName)) {
            for (NovaCallable ctor : obj.getNovaClass().getHirConstructors()) {
                if (ctor.getArity() == args.size() || ctor.getArity() == -1) {
                    return dispatcher.bindAndExecute(obj, ctor, args);
                }
            }
        }
        dispatcher.checkMethodVisibility(obj.getNovaClass(), methodName, owner);
        NovaCallable method = obj.getMethod(methodName);
        if (method != null) return dispatcher.bindAndExecute(obj, method, args);
        return null;
    }

    /** 特殊类型: Set/List/Map 字面量构建、NovaFuture、invoke、作用域函数 */
    private NovaValue trySpecialTypes(NovaValue receiver, String methodName, String owner, List<NovaValue> args) {
        // Set 操作
        if (receiver instanceof NovaList && JAVA_LINKED_HASH_SET.equals(owner) && "add".equals(methodName)) {
            NovaList set = (NovaList) receiver;
            if (!set.contains(args.get(0))) set.add(args.get(0));
            return NovaBoolean.TRUE;
        }
        // NovaList add/addAll
        if (receiver instanceof NovaList) {
            if ("add".equals(methodName)) { ((NovaList) receiver).add(args.get(0)); return NovaBoolean.TRUE; }
            if ("addAll".equals(methodName) && args.get(0) instanceof NovaList) {
                for (NovaValue v : ((NovaList) args.get(0)).getElements()) ((NovaList) receiver).add(v);
                return NovaBoolean.TRUE;
            }
        }
        // NovaMap put
        if (receiver instanceof NovaMap && "put".equals(methodName)) {
            ((NovaMap) receiver).put(args.get(0), args.get(1));
            return NovaNull.UNIT;
        }
        // NovaFuture
        if (receiver instanceof NovaFuture) {
            NovaFuture fut = (NovaFuture) receiver;
            if ("join".equals(methodName) || "get".equals(methodName) || "await".equals(methodName)) return fut.get(interp);
            if ("cancel".equals(methodName)) return NovaBoolean.of(fut.cancel());
            if ("isDone".equals(methodName)) return NovaBoolean.of(fut.isDone());
            if ("isCancelled".equals(methodName)) return NovaBoolean.of(fut.isCancelled());
            if ("getWithTimeout".equals(methodName) && args.size() == 1) return fut.getWithTimeout(interp, args.get(0).asLong());
        }
        // NovaScope — 结构化并发作用域
        if (receiver instanceof NovaScope) {
            NovaScope scope = (NovaScope) receiver;
            switch (methodName) {
                case "async":  return scope.async(dispatcher.extractCallable(args.get(0)));
                case "launch": return scope.launch(dispatcher.extractCallable(args.get(0)));
                case "cancel": scope.cancel(); return NovaNull.UNIT;
                case "isActive":    return NovaBoolean.of(scope.isActive());
                case "isCancelled": return NovaBoolean.of(scope.isCancelled());
            }
        }
        // NovaDeferred — async 返回值
        if (receiver instanceof NovaDeferred) {
            NovaDeferred d = (NovaDeferred) receiver;
            switch (methodName) {
                case "await": case "get": return d.await(interp);
                case "cancel":      return NovaBoolean.of(d.cancel());
                case "isDone":      return NovaBoolean.of(d.isDone());
                case "isCancelled": return NovaBoolean.of(d.isCancelled());
            }
        }
        // NovaJob — launch 返回值
        if (receiver instanceof NovaJob) {
            NovaJob j = (NovaJob) receiver;
            switch (methodName) {
                case "join":        j.join(); return NovaNull.UNIT;
                case "cancel":      return NovaBoolean.of(j.cancel());
                case "isActive":    return NovaBoolean.of(j.isActive());
                case "isCompleted": return NovaBoolean.of(j.isCompleted());
                case "isCancelled": return NovaBoolean.of(j.isCancelled());
            }
        }
        // NovaTask — schedule/scheduleRepeat 返回值
        if (receiver instanceof NovaTask) {
            NovaTask t = (NovaTask) receiver;
            switch (methodName) {
                case "cancel":      t.cancel(); return NovaNull.UNIT;
                case "isCancelled": return NovaBoolean.of(t.isCancelled());
            }
        }
        if ("join".equals(methodName) && args.isEmpty() && !(receiver instanceof NovaFuture)) return receiver;
        // invoke
        if ("invoke".equals(methodName)) {
            NovaCallable invokable = dispatcher.extractCallable(receiver);
            if (invokable != null) return invokable.call(interp, args);
        }
        // 作用域函数
        if (args.size() == 1) {
            NovaCallable lambda = dispatcher.extractCallable(args.get(0));
            if (lambda != null) {
                NovaValue scopeResult = dispatcher.tryExecuteScopeFunction(methodName, receiver, lambda);
                if (scopeResult != null) return scopeResult;
            }
        }
        // NovaMap callable entry
        if (receiver instanceof NovaMap) {
            NovaValue entry = ((NovaMap) receiver).get(NovaString.of(methodName));
            if (entry instanceof NovaCallable) return ((NovaCallable) entry).call(interp, args);
        }
        return null;
    }

    /** 通用回退: 扩展函数 → stdlib → MemberResolver → JavaBean getter */
    private NovaValue invokeGenericFallback(NovaValue receiver, String methodName, List<NovaValue> args) {
        // 用户定义扩展函数
        NovaCallable userExt = interp.findExtension(receiver, methodName);
        if (userExt != null) return dispatcher.bindAndExecute(receiver, userExt, args);
        // StdlibRegistry 扩展方法
        NovaValue stdlibMember = resolver.tryStdlibFallback(receiver, methodName);
        if (stdlibMember instanceof NovaCallable) return ((NovaCallable) stdlibMember).call(interp, args);
        // MemberResolver
        try {
            NovaValue member = resolver.resolveMemberOnValue(receiver, methodName, null);
            if (member instanceof NovaCallable) return ((NovaCallable) member).call(interp, args);
            if (member != null && args.isEmpty()) return member;
        } catch (NovaRuntimeException e) {
            // JavaBean getter 回退: getXxx() → xxx
            if (args.isEmpty() && methodName.length() > 3 && methodName.startsWith("get")
                    && Character.isUpperCase(methodName.charAt(3))) {
                String propName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
                NovaValue stdlibProp = resolver.tryStdlibFallback(receiver, propName);
                if (stdlibProp instanceof NovaCallable) return ((NovaCallable) stdlibProp).call(interp, Collections.emptyList());
                if (stdlibProp != null) return stdlibProp;
                try {
                    NovaValue prop = resolver.resolveMemberOnValue(receiver, propName, null);
                    if (prop instanceof NovaCallable) return ((NovaCallable) prop).call(interp, Collections.emptyList());
                    if (prop != null) return prop;
                } catch (NovaRuntimeException ignored) {}
            }
            throw e;
        }
        throw new NovaRuntimeException("Method '" + methodName + "' not found on " + receiver.getTypeName());
    }
}
