package nova.runtime.interpreter;

import com.novalang.ir.mir.*;
import com.novalang.compiler.ast.Modifier;
import nova.runtime.*;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import nova.runtime.stdlib.StdlibRegistry;

import java.util.*;

/**
 * MIR 方法分派器。
 *
 * <p>从 MirInterpreter 提取的方法分派逻辑，负责：
 * <ul>
 *   <li>INVOKE_VIRTUAL / INVOKE_INTERFACE / INVOKE_SPECIAL 指令执行</li>
 *   <li>INVOKE_STATIC 指令执行（包括 $PipeCall/$ScopeCall/$PartialApplication/$ENV 等特殊标记）</li>
 *   <li>作用域函数（let/also/run/apply/takeIf/takeUnless）</li>
 *   <li>通配符 Java 导入解析</li>
 *   <li>方法可见性检查</li>
 * </ul>
 * </p>
 */
final class MirCallDispatcher {

    // ===== MIR 特殊标记 =====
    private static final String MARKER_SCOPE_CALL = "$ScopeCall";
    private static final String MARKER_PARTIAL_APP = "$PartialApplication|";
    private static final String MARKER_ENV = "$ENV|";
    private static final String MARKER_SCRIPT_CTX = "nova/runtime/NovaScriptContext|";
    private static final String MARKER_BIND_METHOD = "$BIND_METHOD";
    private static final String MARKER_PIPE_CALL = "$PipeCall";
    private static final String MARKER_RANGE = "$RANGE";
    private static final String MARKER_SUPER = "$super$";
    private static final String MARKER_MODULE = "$Module";
    private static final String MARKER_LAMBDA = "$Lambda$";
    private static final String MARKER_METHOD_REF = "$MethodRef$";

    // ===== 特殊方法名 =====
    private static final String SPECIAL_INIT = "<init>";
    private static final String SPECIAL_CLINIT = "<clinit>";

    // ===== JVM 类名 =====
    private static final String JAVA_LINKED_HASH_SET = "java/util/LinkedHashSet";

    // ===== Nova 运行时类名 =====
    private static final String NOVA_PAIR = "nova/runtime/NovaPair";
    private static final String NOVA_DYNAMIC = "nova/runtime/NovaDynamic";
    private static final String NOVA_SCOPE_FUNCTIONS = "nova/runtime/stdlib/NovaScopeFunctions";
    private static final String NOVA_LAMBDA_INVOKER = "nova/runtime/stdlib/LambdaInvoker";
    private static final String NOVA_ASYNC_HELPER = "nova/runtime/stdlib/AsyncHelper";
    private static final String NOVA_COLLECTIONS = "nova/runtime/NovaCollections";
    private static final String NOVA_CLASS_INFO = "nova/runtime/interpreter/reflect/NovaClassInfo";

    /** JVM 内部名 → Java 点分名: "java/lang/String" → "java.lang.String" */
    private static String toJavaDotName(String internalName) {
        return internalName.replace("/", ".");
    }

    private final Interpreter interp;
    private final MemberResolver resolver;
    private final MirInterpreter mirInterp;
    private final Map<String, MirCallable> mirFunctions;
    private final Map<String, MirInterpreter.MirClassInfo> mirClasses;

    // ===== 自有状态 =====

    /** 作用域函数的当前接收者（let/also/run/apply 等） */
    NovaValue scopeReceiver;  // package-private，MirInterpreter 的 GET_FIELD/SET_FIELD 需要访问

    /** 环境变量重复定义检测（防止同一 block 内重复 val 定义） */
    final Map<String, MirInst> envVarDefinedBy = new HashMap<>();
    final Map<String, Integer> envVarDefinedInBlock = new HashMap<>();

    MirCallDispatcher(Interpreter interp, MemberResolver resolver, MirInterpreter mirInterp,
                      Map<String, MirCallable> mirFunctions,
                      Map<String, MirInterpreter.MirClassInfo> mirClasses) {
        this.interp = interp;
        this.resolver = resolver;
        this.mirInterp = mirInterp;
        this.mirFunctions = mirFunctions;
        this.mirClasses = mirClasses;
    }

    /** 重置每次 executeModule 的临时状态 */
    void resetState() {
        envVarDefinedBy.clear();
        envVarDefinedInBlock.clear();
    }

    // ============ 字段解析辅助 ============

    /**
     * 从任意 NovaValue 上解析字段/属性值（用于 scopeReceiver 回退）。
     * 支持 NovaObject 字段、NovaEnumEntry 字段、以及 MemberResolver 内置类型成员。
     */
    NovaValue resolveFieldOnValue(NovaValue target, String fieldName) {
        if (target instanceof NovaObject) {
            NovaObject obj = (NovaObject) target;
            if (obj.hasField(fieldName)) return obj.getField(fieldName);
            NovaCallable method = obj.getMethod(fieldName);
            if (method != null) return new NovaBoundMethod(obj, method);
        }
        if (target instanceof NovaEnumEntry) {
            NovaEnumEntry entry = (NovaEnumEntry) target;
            if (entry.hasField(fieldName)) return entry.getField(fieldName);
        }
        // 内置类型成员（String.length, List.size 等）
        try {
            NovaValue resolved = resolver.resolveMemberOnValue(target, fieldName, null);
            if (resolved != null && !(resolved instanceof NovaNull)) return resolved;
        } catch (NovaRuntimeException ignored) {}
        return null;
    }

    // ============ INVOKE_VIRTUAL / INVOKE_INTERFACE / INVOKE_SPECIAL ============

    void executeInvokeVirtual(MirFrame frame, MirInst inst) {
        // 惰性解析调用站点（首次解析后缓存在 inst.cache）
        MirCallSite cs;
        Object cached = inst.cache;
        if (cached instanceof MirCallSite) {
            cs = (MirCallSite) cached;
        } else {
            cs = MirCallSite.parseVirtual(inst.extraAs());
            inst.cache = cs;
        }
        int[] ops = inst.getOperands();
        NovaValue receiver = frame.get(ops[0]);

        // ===== 内联缓存 + NovaObject 快速路径 =====
        // 条件：无命名参数、接收者为 NovaObject、非 super 调用
        if (cs.namedInfo == null && receiver instanceof NovaObject
                && (cs.owner == null || !cs.owner.startsWith(MARKER_SUPER))) {
            NovaObject obj = (NovaObject) receiver;
            NovaClass cls = obj.getNovaClass();
            NovaCallable method;

            if (cls == cs.cachedClass && cs.cachedMethod != null) {
                // 缓存命中 — 跳过方法查找和可见性检查
                method = cs.cachedMethod;
            } else {
                // 缓存未命中 — 方法查找
                if ("getClass".equals(cs.methodName)) {
                    if (inst.getDest() >= 0) frame.locals[inst.getDest()] = cls;
                    return;
                }
                method = obj.getMethod(cs.methodName);
                if (method != null) {
                    // 可见性检查（仅 cache miss 时执行）
                    checkMethodVisibility(cls, cs.methodName, cs.owner);
                    // 更新缓存
                    cs.cachedClass = cls;
                    cs.cachedMethod = method;
                }
            }

            if (method != null) {
                int argCount = ops.length - 1;
                NovaValue[] argsArr = new NovaValue[argCount];
                for (int i = 0; i < argCount; i++) argsArr[i] = frame.get(ops[i + 1]);
                NovaValue result = bindAndExecute(obj, method, Arrays.asList(argsArr));
                if (inst.getDest() >= 0) {
                    frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
                }
                return;
            }
            // method not found → fall through to full dispatch (extensions, stdlib, etc.)
        }

        // ===== 常规路径（非 NovaObject / 命名参数 / super 调用 / 方法未找到） =====
        int argCount = ops.length - 1;
        NovaValue[] argsArr = new NovaValue[argCount];
        for (int i = 0; i < argCount; i++) argsArr[i] = frame.get(ops[i + 1]);
        List<NovaValue> allArgs = Arrays.asList(argsArr);

        NovaValue result;
        if (cs.namedInfo != null && cs.namedInfo.startsWith("named:")) {
            result = invokeWithNamedArgs(receiver, cs.methodName, cs.owner, allArgs, cs.namedInfo);
        } else {
            result = invokeVirtualMethod(receiver, cs.methodName, cs.owner, allArgs);
        }
        if (inst.getDest() >= 0) {
            frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
        }
    }

    /** 处理带命名参数的方法调用 */
    private NovaValue invokeWithNamedArgs(NovaValue receiver, String methodName,
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

    private NovaValue invokeVirtualMethod(NovaValue receiver, String methodName,
                                           String owner, List<NovaValue> args) {
        // 作用域函数 / Lambda body 内引用外部函数: MirCallable 重定向
        if (receiver instanceof MirCallable) {
            if (scopeReceiver != null) {
                receiver = scopeReceiver;
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
                return val instanceof NovaValue ? (NovaValue) val : NovaValue.fromJava(val);
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
                return val instanceof NovaValue ? (NovaValue) val : NovaValue.fromJava(val);
            }
        }
        return null;
    }

    /** NovaEnumEntry: name/ordinal + 方法调用 */
    private NovaValue invokeOnEnumEntry(NovaEnumEntry entry, String methodName, List<NovaValue> args) {
        if ("name".equals(methodName)) return NovaString.of(entry.name());
        if ("ordinal".equals(methodName)) return NovaInt.of(entry.ordinal());
        NovaCallable method = entry.getMethod(methodName);
        if (method != null) return bindAndExecute(entry, method, args);
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
            if (method != null) return bindAndExecute(obj, method, args);
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
        if (SPECIAL_INIT.equals(methodName)) {
            for (NovaCallable ctor : obj.getNovaClass().getHirConstructors()) {
                if (ctor.getArity() == args.size() || ctor.getArity() == -1) {
                    return bindAndExecute(obj, ctor, args);
                }
            }
        }
        checkMethodVisibility(obj.getNovaClass(), methodName, owner);
        NovaCallable method = obj.getMethod(methodName);
        if (method != null) return bindAndExecute(obj, method, args);
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
                case "async":  return scope.async(extractCallable(args.get(0)));
                case "launch": return scope.launch(extractCallable(args.get(0)));
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
            NovaCallable invokable = extractCallable(receiver);
            if (invokable != null) return invokable.call(interp, args);
        }
        // 作用域函数
        if (args.size() == 1) {
            NovaCallable lambda = extractCallable(args.get(0));
            if (lambda != null) {
                NovaValue scopeResult = tryExecuteScopeFunction(methodName, receiver, lambda);
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
        if (userExt != null) return bindAndExecute(receiver, userExt, args);
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

    // ============ INVOKE_STATIC ============

    /** $ScopeCall — receiver.block() 中 block 是局部变量 callable，以 receiver 为 scopeReceiver 调用 */
    private void executeScopeCall(MirFrame frame, MirInst inst) {
        int[] ops = inst.getOperands();
        NovaValue callable = frame.get(ops[0]);
        NovaValue receiver = frame.get(ops[1]);
        List<NovaValue> callArgs = new ArrayList<>();
        for (int i = 2; i < ops.length; i++) {
            callArgs.add(frame.get(ops[i]));
        }
        NovaCallable extracted = extractCallable(callable);
        if (extracted != null) {
            NovaValue result = withScopeReceiver(receiver, () -> extracted.call(interp, callArgs));
            if (inst.getDest() >= 0) frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
            return;
        }
        // callable 不可调用 → 尝试作为普通方法调用
        NovaValue result = invokeVirtualMethod(receiver, callable != null ? callable.asString() : "invoke", null, callArgs);
        if (inst.getDest() >= 0) frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
    }

    /** $PartialApplication|mask → 创建 NovaPartialApplication */
    private void executePartialApp(MirFrame frame, MirInst inst) {
        String extra = inst.extraAs();
        int mask = Integer.parseInt(extra.substring(MARKER_PARTIAL_APP.length()));
        int[] ops = inst.getOperands();
        NovaValue callee = frame.get(ops[0]);
        List<Object> partialArgs = new ArrayList<>();
        for (int i = 1; i < ops.length; i++) {
            if ((mask & (1 << (i - 1))) != 0) {
                partialArgs.add(NovaPartialApplication.PLACEHOLDER);
            } else {
                partialArgs.add(frame.get(ops[i]));
            }
        }
        NovaCallable calleeCallable = extractCallable(callee);
        if (calleeCallable == null) {
            throw new NovaRuntimeException("Partial application requires a callable, got " + callee.getTypeName());
        }
        NovaValue result = new NovaPartialApplication(calleeCallable, partialArgs);
        if (inst.getDest() >= 0) frame.locals[inst.getDest()] = result;
    }

    /** $ENV|op 或 nova/runtime/NovaScriptContext|op — 环境变量访问 */
    private void executeEnvAccess(MirFrame frame, MirInst inst) {
        String extra = inst.extraAs();
        int[] ops = inst.getOperands();
        if (extra.contains("|get|") && ops != null && ops.length > 0) {
            // NovaScriptContext.get(name) → Environment.tryGet(name)
            NovaValue nameVal = frame.locals[ops[0]];
            String name = nameVal != null ? nameVal.asString() : null;
            NovaValue value = name != null ? interp.getEnvironment().tryGet(name) : null;
            // 作用域函数: 从接收者对象读取字段
            if (value == null && name != null && scopeReceiver instanceof NovaObject) {
                NovaObject obj = (NovaObject) scopeReceiver;
                if (obj.hasField(name)) {
                    value = obj.getField(name);
                }
            }
            if (value == null && "this".equals(name) && scopeReceiver != null) {
                value = scopeReceiver;
            }
            // 作用域成员解析: receiver lambda / 作用域函数中，解析 scopeReceiver 的成员方法
            if (value == null && name != null && scopeReceiver != null) {
                try {
                    value = resolver.resolveMemberOnValue(scopeReceiver, name, null);
                } catch (NovaRuntimeException ignored) {}
            }
            // 通配符 Java 导入回退（import java java.util.* 等）
            if (value == null && name != null) {
                value = resolveWildcardJavaImport(name);
            }
            if (value == null && name != null && !interp.getEnvironment().contains(name)) {
                throw new NovaRuntimeException("Undefined variable: " + name);
            }
            if (inst.getDest() >= 0) {
                frame.locals[inst.getDest()] = value != null ? value : NovaNull.NULL;
            }
        } else if (extra.contains("|defineVal|") && ops != null && ops.length > 1) {
            NovaValue nameVal = frame.locals[ops[0]];
            NovaValue value = frame.get(ops[1]);
            String name = nameVal != null ? nameVal.asString() : null;
            if (name != null && value != null) {
                if (!interp.isReplMode()) {
                    MirInst prev = envVarDefinedBy.get(name);
                    if (prev != null && prev != inst) {
                        // 不同 BasicBlock 允许重定义（when/if 块级作用域）
                        Integer prevBlock = envVarDefinedInBlock.get(name);
                        if (prevBlock != null && prevBlock == frame.currentBlockId) {
                            throw new NovaRuntimeException("Variable already defined: " + name);
                        }
                    }
                    envVarDefinedBy.put(name, inst);
                    envVarDefinedInBlock.put(name, frame.currentBlockId);
                }
                interp.getEnvironment().redefine(name, value, false);
            }
        } else if (extra.contains("|defineVar|") && ops != null && ops.length > 1) {
            NovaValue nameVal = frame.locals[ops[0]];
            NovaValue value = frame.get(ops[1]);
            String name = nameVal != null ? nameVal.asString() : null;
            if (name != null && value != null) {
                if (!interp.isReplMode()) {
                    MirInst prev = envVarDefinedBy.get(name);
                    if (prev != null && prev != inst) {
                        Integer prevBlock = envVarDefinedInBlock.get(name);
                        if (prevBlock != null && prevBlock == frame.currentBlockId) {
                            throw new NovaRuntimeException("Variable already defined: " + name);
                        }
                    }
                    envVarDefinedBy.put(name, inst);
                    envVarDefinedInBlock.put(name, frame.currentBlockId);
                }
                interp.getEnvironment().redefine(name, value, true);
            }
        } else if (extra.contains("|set|") && ops != null && ops.length > 1) {
            NovaValue nameVal = frame.locals[ops[0]];
            NovaValue value = frame.get(ops[1]);
            String name = nameVal != null ? nameVal.asString() : null;
            if (name != null && value != null) {
                if (scopeReceiver instanceof NovaObject
                        && ((NovaObject) scopeReceiver).hasField(name)) {
                    ((NovaObject) scopeReceiver).setField(name, value);
                } else if (!interp.getEnvironment().tryAssign(name, value)) {
                    interp.getEnvironment().redefine(name, value, true);
                }
            }
        }
    }

    void executeInvokeStatic(MirFrame frame, MirInst inst) {
        // 特殊标记快速分派（编译期分类，避免运行时字符串匹配）
        switch (inst.specialKind) {
            case MirInst.SK_SCOPE_CALL:
                executeScopeCall(frame, inst);
                return;
            case MirInst.SK_PARTIAL_APP:
                executePartialApp(frame, inst);
                return;
            case MirInst.SK_ENV_ACCESS:
                executeEnvAccess(frame, inst);
                return;
            default:
                break;
        }

        // SK_NORMAL: 惰性解析调用站点（首次解析后缓存在 inst.cache）
        // 防御性回退：MIR Pass 重建指令可能丢失 specialKind，这里惰性修复
        String extra = inst.extraAs();
        if (extra.length() > 0 && extra.charAt(0) == '$') {
            if (MARKER_SCOPE_CALL.equals(extra)) { inst.specialKind = MirInst.SK_SCOPE_CALL; executeScopeCall(frame, inst); return; }
            if (extra.startsWith(MARKER_PARTIAL_APP)) { inst.specialKind = MirInst.SK_PARTIAL_APP; executePartialApp(frame, inst); return; }
            if (extra.startsWith(MARKER_ENV)) { inst.specialKind = MirInst.SK_ENV_ACCESS; executeEnvAccess(frame, inst); return; }
        } else if (extra.startsWith(MARKER_SCRIPT_CTX)) {
            inst.specialKind = MirInst.SK_ENV_ACCESS; executeEnvAccess(frame, inst); return;
        }
        MirCallSite cs;
        Object cached = inst.cache;
        if (cached instanceof MirCallSite) {
            cs = (MirCallSite) cached;
        } else {
            cs = MirCallSite.parseStatic(extra);
            inst.cache = cs;
        }
        String owner = cs.owner;
        String methodName = cs.methodName;

        // 热路径：$PipeCall 或模块内函数调用（缓存 MirCallable + fastCall 快速路径）
        if (owner != null && methodName.indexOf('%') < 0 && methodName.indexOf('#') < 0
                && (MARKER_PIPE_CALL.equals(owner) || owner.endsWith(MARKER_MODULE))) {
            MirCallable resolved = cs.resolvedCallable;
            if (resolved == null) {
                resolved = mirFunctions.get(methodName);
                if (resolved != null) {
                    cs.resolvedCallable = resolved;
                    MirFunction fn = resolved.getFunction();
                    String fnName = fn.getName();
                    boolean noThis = fn.getLocals().isEmpty()
                            || !"this".equals(fn.getLocals().get(0).getName());
                    cs.fastCallEligible = noThis
                            && !SPECIAL_INIT.equals(fnName) && !SPECIAL_CLINIT.equals(fnName);
                }
            }
            if (resolved != null) {
                NovaValue result;
                if (cs.fastCallEligible && mirInterp.pendingReifiedTypeArgs == null) {
                    result = mirInterp.fastCall(frame, resolved.getFunction(), inst);
                } else {
                    NovaValue[] argsArray = mirInterp.collectArgsArray(frame, inst.getOperands());
                    result = resolved.callDirect(interp, argsArray);
                }
                if (inst.getDest() >= 0) frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
                return;
            }
        }

        NovaValue[] argsArray = mirInterp.collectArgsArray(frame, inst.getOperands());
        List<NovaValue> args = Arrays.asList(argsArray);
        NovaValue result = invokeStaticMethod(owner, methodName, args);
        if (inst.getDest() >= 0) {
            frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
        }
    }

    private NovaValue invokeStaticMethod(String owner, String methodName, List<NovaValue> args) {
        if (MARKER_BIND_METHOD.equals(owner)) return handleBindMethod(methodName, args);
        if (MARKER_PIPE_CALL.equals(owner))   return handlePipeCall(methodName, args);
        if (MARKER_RANGE.equals(owner) && "create".equals(methodName) && args.size() == 3) {
            return new NovaRange(args.get(0).asInt(), args.get(1).asInt(), args.get(2).asBoolean());
        }
        if (owner != null && owner.endsWith(MARKER_MODULE)) {
            MirCallable func = mirFunctions.get(methodName);
            if (func != null) return func.call(interp, args);
        }
        NovaValue r;
        if (owner != null && (r = tryNovaRuntimeDispatch(owner, methodName, args)) != null) return r;
        if ((r = tryEnvironmentLookup(methodName, args)) != null) return r;
        if (owner != null && (r = tryClassStaticDispatch(owner, methodName, args)) != null) return r;
        if (owner != null && (r = tryEnumOrJavaStatic(owner, methodName, args)) != null) return r;
        throw new NovaRuntimeException("Static method not found: "
                + (owner != null ? owner + "." : "") + methodName);
    }

    /** $BIND_METHOD — interpreterMode 下实例方法引用 (obj::method) */
    private NovaValue handleBindMethod(String methodName, List<NovaValue> args) {
        if (!"bind".equals(methodName) || args.size() != 2) {
            throw new NovaRuntimeException("Invalid $BIND_METHOD call");
        }
        NovaValue target = args.get(0);
        String name = args.get(1).asString();
        if (target instanceof NovaObject) {
            NovaCallable method = ((NovaObject) target).getMethod(name);
            if (method != null) return new NovaBoundMethod(target, method);
        }
        if (target instanceof NovaEnumEntry) {
            NovaCallable method = ((NovaEnumEntry) target).getMethod(name);
            if (method != null) return new NovaBoundMethod(target, method);
        }
        NovaValue member = resolver.resolveMemberOnValue(target, name, null);
        if (member instanceof NovaCallable) return member;
        throw new NovaRuntimeException("Cannot bind method '" + name + "' on " + target.getTypeName());
    }

    /** $PipeCall — 管道操作符: 解析 spread/reified，先查函数再尝试方法调用 */
    private NovaValue handlePipeCall(String methodName, List<NovaValue> args) {
        // 解析 spread 标记: methodName%spread:0,2
        Set<Integer> spreadIndices = null;
        int pctIdx = methodName.indexOf('%');
        if (pctIdx >= 0) {
            String spreadPart = methodName.substring(pctIdx + 1);
            methodName = methodName.substring(0, pctIdx);
            if (spreadPart.startsWith("spread:")) {
                spreadIndices = new HashSet<>();
                for (String s : spreadPart.substring(7).split(",")) {
                    spreadIndices.add(Integer.parseInt(s.trim()));
                }
            }
        }
        // 解析 reified 类型参数: methodName#TypeArg1,TypeArg2
        String[] reifiedTypeArgs = null;
        int hashIdx = methodName.indexOf('#');
        if (hashIdx >= 0) {
            reifiedTypeArgs = methodName.substring(hashIdx + 1).split(",");
            methodName = methodName.substring(0, hashIdx);
        }
        // spread 展开
        if (spreadIndices != null && !spreadIndices.isEmpty()) {
            List<NovaValue> expanded = new ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                if (spreadIndices.contains(i) && args.get(i) instanceof NovaList) {
                    NovaList list = (NovaList) args.get(i);
                    for (int j = 0; j < list.size(); j++) expanded.add(list.get(j));
                } else {
                    expanded.add(args.get(i));
                }
            }
            args = expanded;
        }
        // 1. 查 mirFunctions
        MirCallable func = mirFunctions.get(methodName);
        if (func != null) {
            if (reifiedTypeArgs != null) mirInterp.pendingReifiedTypeArgs = reifiedTypeArgs;
            return func.call(interp, args);
        }
        // 2. 查 Environment
        NovaValue envVal = interp.getEnvironment().tryGet(methodName);
        if (envVal instanceof NovaCallable) {
            if (reifiedTypeArgs != null) mirInterp.pendingReifiedTypeArgs = reifiedTypeArgs;
            return ((NovaCallable) envVal).call(interp, args);
        }
        if (envVal instanceof NovaObject) {
            NovaCallable invokeMethod = ((NovaObject) envVal).getMethod("invoke");
            if (invokeMethod != null) {
                List<NovaValue> invokeArgs = new ArrayList<>();
                invokeArgs.add(envVal);
                invokeArgs.addAll(args);
                return invokeMethod.call(interp, invokeArgs);
            }
        }
        if (envVal instanceof NovaClass) {
            return ((NovaClass) envVal).call(interp, args);
        }
        // 3. 查通配符 Java 导入 (java.lang 等)
        NovaValue javaClassVal = resolveWildcardJavaImport(methodName);
        if (javaClassVal instanceof NovaCallable) {
            return ((NovaCallable) javaClassVal).call(interp, args);
        }
        // 4. 回退: args[0].methodName(args[1:])
        if (!args.isEmpty()) {
            NovaValue target = args.get(0);
            List<NovaValue> methodArgs = args.size() > 1 ? args.subList(1, args.size()) : Collections.emptyList();
            return invokeVirtualMethod(target, methodName, null, methodArgs);
        }
        throw new NovaRuntimeException("Undefined function: " + methodName);
    }

    /** Nova 运行时类分派: NovaPair/NovaDynamic/LambdaInvoker/AsyncHelper/ScopeFunctions/Collections/ClassInfo */
    private NovaValue tryNovaRuntimeDispatch(String owner, String methodName, List<NovaValue> args) {
        // NovaPair.of
        if (NOVA_PAIR.equals(owner) && "of".equals(methodName) && args.size() == 2) {
            return NovaPair.of(args.get(0), args.get(1));
        }
        // NovaDynamic
        if (NOVA_DYNAMIC.equals(owner)) {
            if (methodName.startsWith("invoke") && args.size() >= 2) {
                NovaValue target = args.get(0);
                String dynMethodName = args.get(1).asString();
                List<NovaValue> dynArgs = args.size() > 2 ? args.subList(2, args.size()) : Collections.emptyList();
                return invokeVirtualMethod(target, dynMethodName, null, dynArgs);
            }
            if ("getMember".equals(methodName) && args.size() == 2) {
                NovaValue target = args.get(0);
                String memberName2 = args.get(1).asString();
                if (target instanceof MirCallable) {
                    NovaValue fieldVal = ((MirCallable) target).getCaptureField(memberName2);
                    if (fieldVal != null) return fieldVal;
                    if (scopeReceiver != null) {
                        NovaValue resolved = resolveFieldOnValue(scopeReceiver, memberName2);
                        if (resolved != null) return resolved;
                    }
                    NovaValue envVal = interp.getEnvironment().tryGet(memberName2);
                    return envVal != null ? envVal : NovaNull.NULL;
                }
                NovaValue member = resolver.resolveMemberOnValue(target, memberName2, null);
                return member != null ? member : NovaNull.NULL;
            }
            if ("setMember".equals(methodName) && args.size() == 3) {
                NovaValue target = args.get(0);
                String memberName2 = args.get(1).asString();
                if (target instanceof NovaObject) {
                    ((NovaObject) target).setField(memberName2, args.get(2));
                } else if (target instanceof MirCallable) {
                    if (((MirCallable) target).getCaptureField(memberName2) != null) {
                        ((MirCallable) target).setCaptureField(memberName2, args.get(2));
                    } else if (scopeReceiver instanceof NovaObject
                            && ((NovaObject) scopeReceiver).hasField(memberName2)) {
                        ((NovaObject) scopeReceiver).setField(memberName2, args.get(2));
                    } else {
                        interp.getEnvironment().redefine(memberName2, args.get(2), true);
                    }
                }
                return NovaNull.UNIT;
            }
        }
        // LambdaInvoker.invokeN
        if (NOVA_LAMBDA_INVOKER.equals(owner) && methodName.startsWith("invoke") && !args.isEmpty()) {
            NovaValue fn = args.get(0);
            List<NovaValue> fnArgs = args.subList(1, args.size());
            NovaCallable fnCallable = extractCallable(fn);
            if (fnCallable != null) return fnCallable.call(interp, fnArgs);
            return invokeVirtualMethod(fn, "invoke", null, fnArgs);
        }
        // AsyncHelper.run
        if (NOVA_ASYNC_HELPER.equals(owner) && "run".equals(methodName) && args.size() == 1) {
            NovaCallable asyncCallable = extractCallable(args.get(0));
            if (asyncCallable != null) return new NovaFuture(asyncCallable, interp);
        }
        // NovaScopeFunctions
        if (NOVA_SCOPE_FUNCTIONS.equals(owner) && args.size() >= 2) {
            NovaValue self = args.get(0);
            NovaCallable lambda = extractCallable(args.get(1));
            if (lambda != null) {
                NovaValue scopeResult = tryExecuteScopeFunction(methodName, self, lambda);
                if (scopeResult != null) return scopeResult;
            }
        }
        // StdlibRegistry varargs 函数
        StdlibRegistry.NativeFunctionInfo nfInfo = StdlibRegistry.getNativeFunction(methodName);
        if (nfInfo != null && nfInfo.arity == -1 && args.size() == 1 && args.get(0) instanceof NovaList) {
            List<NovaValue> actualArgs = ((NovaList) args.get(0)).getElements();
            Object[] javaArgs = new Object[actualArgs.size()];
            for (int i = 0; i < actualArgs.size(); i++) {
                NovaValue arg = actualArgs.get(i);
                if (arg instanceof NovaList) {
                    NovaList list = (NovaList) arg;
                    Object[] arr = new Object[list.size()];
                    for (int j = 0; j < list.size(); j++) arr[j] = list.get(j).toJavaValue();
                    javaArgs[i] = arr;
                } else {
                    javaArgs[i] = arg.toJavaValue();
                }
            }
            return NovaValue.fromJava(nfInfo.impl.apply(javaArgs));
        }
        // Stdlib 扩展方法 / CollectionOps
        if (owner.startsWith("nova/runtime/stdlib/")
                && (owner.endsWith("Extensions") || owner.endsWith("CollectionOps"))
                && !args.isEmpty()) {
            NovaValue receiver = args.get(0);
            List<NovaValue> methodArgs = args.size() > 1 ? args.subList(1, args.size()) : Collections.emptyList();
            // NovaList 常用方法快速路径：绕过 Java stdlib bridge
            if (receiver instanceof NovaList) {
                NovaList novaList = (NovaList) receiver;
                if ("add".equals(methodName) && methodArgs.size() == 1) {
                    novaList.add(methodArgs.get(0));
                    return NovaBoolean.TRUE;
                }
                // HOF 批量快速路径：最后一个参数是 MirCallable 时尝试帧复用
                int lambdaIdx = methodArgs.size() - 1;
                if (lambdaIdx >= 0) {
                    NovaCallable callable = extractCallable(methodArgs.get(lambdaIdx));
                    if (callable instanceof MirCallable) {
                        NovaValue extra = lambdaIdx > 0 ? methodArgs.get(0) : null;
                        NovaValue r = mirInterp.batchExec(
                                novaList, methodName, extra, (MirCallable) callable);
                        if (r != null) return r;
                    }
                }
            }
            // NovaMap HOF 批量快速路径
            if (receiver instanceof NovaMap) {
                int lambdaIdx = methodArgs.size() - 1;
                if (lambdaIdx >= 0) {
                    NovaCallable callable = extractCallable(methodArgs.get(lambdaIdx));
                    if (callable instanceof MirCallable) {
                        NovaValue extra = lambdaIdx > 0 ? methodArgs.get(0) : null;
                        NovaValue r = mirInterp.batchExecMap(
                                (NovaMap) receiver, methodName, extra, (MirCallable) callable);
                        if (r != null) return r;
                    }
                }
            }
            NovaCallable userExt = interp.findExtension(receiver, methodName);
            if (userExt != null) {
                return bindAndExecute(receiver, userExt, methodArgs);
            }
            NovaValue stdlibMethod = resolver.tryStdlibFallback(receiver, methodName);
            if (stdlibMethod instanceof NovaCallable) return ((NovaCallable) stdlibMethod).call(interp, methodArgs);
            return invokeVirtualMethod(receiver, methodName, null, methodArgs);
        }
        // NovaCollections 拦截
        if (NOVA_COLLECTIONS.equals(owner)) {
            if ("createRange".equals(methodName) && args.size() == 3) {
                return new NovaRange(args.get(0).asInt(), args.get(1).asInt(), args.get(2).asBoolean());
            }
            if ("toIterable".equals(methodName) && args.size() == 1) return args.get(0);
            if ("componentN".equals(methodName) && args.size() == 2) {
                return handleComponentN(args.get(0), args.get(1).asInt());
            }
        }
        // NovaClassInfo.fromJavaClass — 反射 API 拦截
        if (NOVA_CLASS_INFO.equals(owner) && "fromJavaClass".equals(methodName) && args.size() == 1) {
            return handleClassInfoFromJavaClass(args.get(0));
        }
        return null;
    }

    /** componentN 解构操作 */
    private NovaValue handleComponentN(NovaValue target, int n) {
        if (target instanceof NovaPair) {
            Object val = n == 1 ? ((NovaPair) target).getFirst() : ((NovaPair) target).getSecond();
            return val instanceof NovaValue ? (NovaValue) val : NovaValue.fromJava(val);
        }
        if (target instanceof NovaList) return ((NovaList) target).get(n - 1);
        if (target instanceof NovaObject) {
            NovaObject obj = (NovaObject) target;
            List<String> fieldOrder = obj.getNovaClass().getDataFieldOrder();
            if (fieldOrder != null && n >= 1 && n <= fieldOrder.size()) {
                return obj.getField(fieldOrder.get(n - 1));
            }
            String compMethodName = "component" + n;
            NovaCallable compMethod = obj.getMethod(compMethodName);
            if (compMethod != null) return compMethod.call(interp, Collections.singletonList(obj));
        }
        throw new NovaRuntimeException("Cannot destructure: " + target.getTypeName());
    }

    /** classOf() → NovaClassInfo */
    private NovaValue handleClassInfoFromJavaClass(NovaValue arg) {
        if (arg instanceof NovaClass) {
            NovaClass cls = (NovaClass) arg;
            Object cached = cls.getCachedClassInfo();
            if (cached instanceof NovaValue) return (NovaValue) cached;
            NovaClassInfo info = NovaClassInfo.fromNovaClass(cls);
            cls.setCachedClassInfo(info);
            return info;
        }
        if (arg instanceof NovaObject) {
            NovaClass cls = ((NovaObject) arg).getNovaClass();
            Object cached = cls.getCachedClassInfo();
            if (cached instanceof NovaValue) return (NovaValue) cached;
            NovaClassInfo info = NovaClassInfo.fromNovaClass(cls);
            cls.setCachedClassInfo(info);
            return info;
        }
        if (arg instanceof NovaExternalObject) {
            Object javaVal = arg.toJavaValue();
            if (javaVal instanceof Class) return NovaClassInfo.fromJavaClass((Class<?>) javaVal);
        }
        return NovaNull.NULL;
    }

    /** 从全局环境查找函数/callable */
    private NovaValue tryEnvironmentLookup(String methodName, List<NovaValue> args) {
        NovaValue funcVal = interp.getEnvironment().tryGet(methodName);
        if (funcVal instanceof NovaCallable) return ((NovaCallable) funcVal).call(interp, args);
        if (funcVal != null) {
            NovaCallable extracted = extractCallable(funcVal);
            if (extracted != null) return extracted.call(interp, args);
        }
        return null;
    }

    /** Nova 类的静态方法/字段/构造器 */
    private NovaValue tryClassStaticDispatch(String owner, String methodName, List<NovaValue> args) {
        MirInterpreter.MirClassInfo classInfo = mirClasses.get(owner);
        if (classInfo != null && classInfo.novaClass != null) {
            NovaCallable method = classInfo.novaClass.findMethod(methodName);
            if (method != null) return method.call(interp, args);
            NovaValue staticField = classInfo.novaClass.getStaticField(methodName);
            if (staticField instanceof NovaCallable) return ((NovaCallable) staticField).call(interp, args);
        }
        // 从环境查找类
        String normalizedOwner = toJavaDotName(owner);
        NovaValue classVal = interp.getEnvironment().tryGet(normalizedOwner);
        if (classVal == null) classVal = interp.getEnvironment().tryGet(owner);
        if (classVal instanceof NovaClass) {
            NovaClass cls = (NovaClass) classVal;
            NovaCallable method = cls.findMethod(methodName);
            if (method != null) return method.call(interp, args);
            NovaValue staticField = cls.getStaticField(methodName);
            if (staticField instanceof NovaCallable) return ((NovaCallable) staticField).call(interp, args);
        }
        // 构造器调用
        if (classInfo != null && SPECIAL_INIT.equals(methodName)) {
            return classInfo.novaClass.call(interp, args);
        }
        return null;
    }

    /** 枚举静态方法 + Java 反射静态方法 */
    private NovaValue tryEnumOrJavaStatic(String owner, String methodName, List<NovaValue> args) {
        // 枚举 values/valueOf
        if ("values".equals(methodName) || "valueOf".equals(methodName)) {
            String normalizedOwner = toJavaDotName(owner);
            NovaValue classVal = interp.getEnvironment().tryGet(normalizedOwner);
            if (classVal == null) classVal = interp.getEnvironment().tryGet(owner);
            if (classVal instanceof NovaEnum) {
                NovaEnum enumType = (NovaEnum) classVal;
                if ("values".equals(methodName)) return new NovaList(new ArrayList<>(enumType.values()));
                if ("valueOf".equals(methodName) && args.size() == 1) {
                    NovaEnumEntry entry = enumType.getEntry(args.get(0).asString());
                    if (entry != null) return entry;
                    throw new NovaRuntimeException("No enum constant " + args.get(0).asString());
                }
            }
        }
        // Java 静态方法反射调用
        try {
            Class<?> javaClass = Class.forName(toJavaDotName(owner));
            for (java.lang.reflect.Method m : javaClass.getMethods()) {
                if (m.getName().equals(methodName)
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == args.size()) {
                    Object[] javaArgs = new Object[args.size()];
                    for (int i = 0; i < args.size(); i++) javaArgs[i] = args.get(i).toJavaValue();
                    Object result = m.invoke(null, javaArgs);
                    return NovaValue.fromJava(result);
                }
            }
        } catch (Exception e) {
            // fall through
        }
        return null;
    }

    // ============ 作用域函数 ============

    /** scopeReceiver 安全切换：执行 action 期间临时替换 scopeReceiver，完成后恢复 */
    NovaValue withScopeReceiver(NovaValue newReceiver, java.util.function.Supplier<NovaValue> action) {
        NovaValue prev = scopeReceiver;
        scopeReceiver = newReceiver;
        try {
            return action.get();
        } finally {
            scopeReceiver = prev;
        }
    }

    /** 作用域函数统一入口：let/also/run/apply/takeIf/takeUnless，非作用域函数返回 null */
    NovaValue tryExecuteScopeFunction(String name, NovaValue receiver, NovaCallable lambda) {
        switch (name) {
            case "let":
                return lambda.call(interp, Collections.singletonList(receiver));
            case "also":
                lambda.call(interp, Collections.singletonList(receiver));
                return receiver;
            case "run":
                return withScopeReceiver(receiver, () -> lambda.call(interp, Collections.emptyList()));
            case "apply":
                withScopeReceiver(receiver, () -> lambda.call(interp, Collections.emptyList()));
                return receiver;
            case "takeIf":
                return isTruthy(lambda.call(interp, Collections.singletonList(receiver))) ? receiver : NovaNull.NULL;
            case "takeUnless":
                return isTruthy(lambda.call(interp, Collections.singletonList(receiver))) ? NovaNull.NULL : receiver;
            default:
                return null;
        }
    }

    // ============ 通配符 Java 导入 ============

    /**
     * 通配符 Java 导入回退解析：尝试通过 wildcardJavaImports 包前缀查找 Java 类。
     * 找到后注册到环境中（与 HirEvaluator.visitIdentifier 逻辑一致）。
     */
    NovaValue resolveWildcardJavaImport(String name) {
        for (String pkg : interp.wildcardJavaImports) {
            String fullName = pkg + "." + name;
            if (interp.typeResolver.classNotFoundCache.contains(fullName)) continue;
            Class<?> cached = interp.typeResolver.resolvedClassCache.get(fullName);
            if (cached != null) {
                if (interp.getSecurityPolicy().isClassAllowed(cached.getName())) {
                    JavaInterop.NovaJavaClass javaClass = new JavaInterop.NovaJavaClass(cached);
                    interp.getEnvironment().redefine(name, javaClass, false);
                    return javaClass;
                }
                continue;
            }
            try {
                Class<?> clazz = Class.forName(fullName);
                interp.typeResolver.resolvedClassCache.put(fullName, clazz);
                if (interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                    JavaInterop.NovaJavaClass javaClass = new JavaInterop.NovaJavaClass(clazz);
                    interp.getEnvironment().redefine(name, javaClass, false);
                    return javaClass;
                }
            } catch (ClassNotFoundException e) {
                interp.typeResolver.classNotFoundCache.add(fullName);
            }
        }
        return null;
    }

    // ============ 辅助方法 ============

    /** 绑定 receiver 到方法并执行（MirCallable 快速路径跳过 BoundMethod 分配） */
    NovaValue bindAndExecute(NovaValue receiver, NovaCallable method, List<NovaValue> args) {
        if (method instanceof MirCallable) {
            int size = args.size();
            NovaValue[] argsArray = new NovaValue[1 + size];
            argsArray[0] = receiver;
            for (int i = 0; i < size; i++) {
                argsArray[i + 1] = args.get(i);
            }
            return ((MirCallable) method).callDirect(interp, argsArray);
        }
        return interp.executeBoundMethod(new NovaBoundMethod(receiver, method), args, null);
    }

    /** 方法可见性检查（提取自 tryInvokeOnObject，供内联缓存 cache miss 路径复用） */
    void checkMethodVisibility(NovaClass receiverClass, String methodName, String owner) {
        Modifier vis = receiverClass.getMethodVisibility(methodName);
        if (vis == Modifier.PRIVATE) {
            if (owner == null || !owner.equals(receiverClass.getName())) {
                throw new NovaRuntimeException(
                        "Cannot access private method '" + methodName + "' from outside " + receiverClass.getName());
            }
        }
        if (vis == Modifier.PROTECTED) {
            boolean allowed = owner != null && owner.equals(receiverClass.getName());
            if (!allowed && owner != null) {
                MirInterpreter.MirClassInfo ownerInfo = mirClasses.get(owner);
                if (ownerInfo != null && ownerInfo.novaClass != null) {
                    NovaClass sc = ownerInfo.novaClass.getSuperclass();
                    while (sc != null) {
                        if (sc.getName().equals(receiverClass.getName())) { allowed = true; break; }
                        sc = sc.getSuperclass();
                    }
                }
            }
            if (!allowed) {
                throw new NovaRuntimeException(
                        "Cannot access protected method '" + methodName + "' from outside " + receiverClass.getName());
            }
        }
    }

    /**
     * 从 NovaValue 提取 NovaCallable：直接 NovaCallable 或 MIR lambda (NovaObject with invoke)。
     */
    NovaCallable extractCallable(NovaValue value) {
        if (value instanceof NovaCallable) return (NovaCallable) value;
        if (value instanceof NovaObject) {
            NovaBoundMethod bound = ((NovaObject) value).getBoundMethod("invoke");
            if (bound != null) return bound;
        }
        return null;
    }

    private boolean isTruthy(NovaValue value) {
        if (value == null || value instanceof NovaNull) return !value.isNull();
        return value.isTruthy();
    }
}
