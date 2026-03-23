package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import com.novalang.runtime.resolution.MethodNameCanonicalizer;
import com.novalang.runtime.types.NovaClass;

import java.util.*;

/**
 * 铏氭柟娉曞垎娲惧櫒銆?
 *
 * <p>澶勭悊 INVOKE_VIRTUAL / INVOKE_INTERFACE / INVOKE_SPECIAL 鎸囦护涓?
 * 瀹為檯鐨勬柟娉曟煡鎵句笌璋冪敤閾撅紝浠?MirCallDispatcher 鎷嗗垎鑰屾潵銆?/p>
 */
final class VirtualMethodDispatcher {

    // ===== JVM 绫诲悕 =====
    private static final String JAVA_LINKED_HASH_SET = "java/util/LinkedHashSet";
    private static final String MARKER_SUPER = "$super$";
    private static final String MARKER_LAMBDA = "$Lambda$";

    private final Interpreter interp;
    private final MemberResolver resolver;
    private final MirCallDispatcher dispatcher;
    private final List<VirtualDispatchRule> dispatchRules;

    VirtualMethodDispatcher(Interpreter interp, MemberResolver resolver, MirCallDispatcher dispatcher) {
        this.interp = interp;
        this.resolver = resolver;
        this.dispatcher = dispatcher;
        this.dispatchRules = Arrays.<VirtualDispatchRule>asList(
                this::tryNovaObjectDispatch,
                this::tryExternalDispatch,
                this::tryClassDispatch,
                this::tryResultProtocolDispatch,
                this::tryIteratorProtocolDispatch,
                this::tryEnumEntryDispatch,
                this::trySpecialTypeDispatch,
                this::tryGenericFallbackDispatch
        );
    }

    // ============ 铏氭柟娉曞垎娲句富閾?============

    NovaValue invokeVirtualMethod(NovaValue receiver, String methodName,
                                   String owner, List<NovaValue> args) {
        PreparedVirtualDispatch prepared = preprocessMirCallableReceiver(receiver, methodName, args);
        if (prepared.hasImmediateResult()) {
            return prepared.getImmediateResult();
        }
        receiver = prepared.getReceiver();

        List<String> candidates = MethodNameCanonicalizer.lookupCandidates(methodName);
        for (int i = 0; i < candidates.size(); i++) {
            NovaValue resolved = dispatchVirtual(new VirtualCall(receiver, candidates.get(i), owner, args));
            if (resolved != null) return resolved;
        }
        throw new NovaRuntimeException("Method not found: "
                + receiver.getTypeName() + "." + methodName + " with " + args.size() + " argument(s)");
    }

    private PreparedVirtualDispatch preprocessMirCallableReceiver(NovaValue receiver, String methodName, List<NovaValue> args) {
        if (!(receiver instanceof MirCallable)) {
            return PreparedVirtualDispatch.continueWith(receiver);
        }
        if (dispatcher.scopeReceiver != null) {
            NovaValue scoped = dispatcher.scopeReceiver;
            if (scoped instanceof ScalarizedNovaObject) {
                scoped = ((ScalarizedNovaObject) scoped).materialize();
            }
            return PreparedVirtualDispatch.continueWith(scoped);
        }
        if (!"invoke".equals(methodName)) {
            NovaValue envVal = interp.getEnvironment().tryGet(methodName);
            if (envVal instanceof NovaCallable) {
                return PreparedVirtualDispatch.shortCircuit(((NovaCallable) envVal).call(interp, args));
            }
        }
        return PreparedVirtualDispatch.continueWith(receiver);
    }

    private NovaValue dispatchVirtual(VirtualCall call) {
        for (int i = 0; i < dispatchRules.size(); i++) {
            NovaValue result = dispatchRules.get(i).tryDispatch(call);
            if (result != null) return result;
        }
        return null;
    }

    private NovaValue tryNovaObjectDispatch(VirtualCall call) {
        if (!(call.receiver instanceof NovaObject)) {
            return null;
        }
        NovaObject obj = (NovaObject) call.receiver;
        if ("getClass".equals(call.methodName)) return obj.getNovaClass();
        if (call.owner != null && call.owner.startsWith(MARKER_SUPER)) {
            return invokeSuper(obj, call.methodName, call.owner, call.args);
        }
        if (obj.getNovaClass().getName().contains(MARKER_LAMBDA) && obj.getMethod(call.methodName) == null) {
            if (dispatcher.scopeReceiver != null) {
                return invokeVirtualMethod(dispatcher.scopeReceiver, call.methodName, call.owner, call.args);
            }
            NovaValue envVal = interp.getEnvironment().tryGet(call.methodName);
            if (envVal instanceof NovaCallable) return ((NovaCallable) envVal).call(interp, call.args);
        }
        return tryInvokeOnObject(obj, call.methodName, call.owner, call.args);
    }

    private NovaValue tryExternalDispatch(VirtualCall call) {
        return call.receiver instanceof NovaExternalObject
                ? invokeOnExternal((NovaExternalObject) call.receiver, call.methodName, call.args)
                : null;
    }

    private NovaValue tryClassDispatch(VirtualCall call) {
        return call.receiver instanceof NovaClass
                ? invokeOnClass((NovaClass) call.receiver, call.methodName, call.owner, call.args)
                : null;
    }

    private NovaValue tryResultProtocolDispatch(VirtualCall call) {
        return tryResultProtocol(call.receiver, call.methodName, call.args);
    }

    private NovaValue tryIteratorProtocolDispatch(VirtualCall call) {
        return tryIteratorProtocol(call.receiver, call.methodName);
    }

    private NovaValue tryEnumEntryDispatch(VirtualCall call) {
        return call.receiver instanceof NovaEnumEntry
                ? invokeOnEnumEntry((NovaEnumEntry) call.receiver, call.methodName, call.args)
                : null;
    }

    private NovaValue trySpecialTypeDispatch(VirtualCall call) {
        return trySpecialTypes(call.receiver, call.methodName, call.owner, call.args);
    }

    private NovaValue tryGenericFallbackDispatch(VirtualCall call) {
        return tryGenericFallback(call.receiver, call.methodName, call.args);
    }

    /** 澶勭悊甯﹀懡鍚嶅弬鏁扮殑鏂规硶璋冪敤 */
    NovaValue invokeWithNamedArgs(NovaValue receiver, String methodName,
                                   String owner, List<NovaValue> allArgs, String namedInfo) {
        // namedInfo 鏍煎紡: "named:positionalCount:key1,key2"
        String[] parts = namedInfo.split(":", 3);
        int positionalCount = Integer.parseInt(parts[1]);
        String[] namedKeys = parts.length > 2 && !parts[2].isEmpty() ? parts[2].split(",") : new String[0];

        List<NovaValue> positionalArgs = allArgs.subList(0, positionalCount);
        Map<String, NovaValue> namedArgs = new LinkedHashMap<>();
        for (int i = 0; i < namedKeys.length; i++) {
            namedArgs.put(namedKeys[i], allArgs.get(positionalCount + i));
        }

        // 瑙ｆ瀽鐩爣鏂规硶
        NovaValue callable = resolver.resolveMemberOnValue(receiver, methodName, null);
        if (callable instanceof NovaCallable) {
            NovaCallable func = (NovaCallable) callable;
            if (func.supportsNamedArgs()) {
                return func.callWithNamed(interp, positionalArgs, namedArgs);
            }
        }
        // fallback: 蹇界暐鍛藉悕鍙傛暟锛屼娇鐢ㄥ叏閮ㄥ弬鏁颁綔涓轰綅缃弬鏁?
        return invokeVirtualMethod(receiver, methodName, owner, allArgs);
    }

    // ============ 鎸夋帴鏀惰€呯被鍨嬪垎娲?============

    /** NovaExternalObject: 瀹夊叏绛栫暐妫€鏌?+ PrintStream/Iterator/Java 鍙嶅皠 */
    private NovaValue invokeOnExternal(NovaExternalObject receiver, String methodName, List<NovaValue> args) {
        Object javaObj = receiver.toJavaValue();
        // Iterator/PrintStream 鏄瑷€鍩虹璁炬柦锛屽湪瀹夊叏绛栫暐妫€鏌ヤ箣鍓嶆斁琛?
        if (javaObj instanceof java.util.Iterator) {
            java.util.Iterator<?> it = (java.util.Iterator<?>) javaObj;
            if ("hasNext".equals(methodName)) return NovaBoolean.of(it.hasNext());
            if ("next".equals(methodName)) {
                Object val = it.next();
                return val instanceof NovaValue ? (NovaValue) val : AbstractNovaValue.fromJava(val);
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
        // 瀹夊叏绛栫暐妫€鏌ワ紙瀵归潪鍩虹璁炬柦鐨?Java 鏂规硶璋冪敤锛?
        if (javaObj != null) {
            String className = javaObj.getClass().getName();
            if (!interp.getSecurityPolicy().isMethodAllowed(className, methodName)) {
                throw NovaSecurityPolicy.denied("Cannot call method '" + methodName + "' on " + className);
            }
        }
        NovaValue extMember = resolver.resolveMemberOnExternal(receiver, methodName, null);
        if (extMember instanceof NovaCallable) return ((NovaCallable) extMember).call(interp, args);
        if (extMember != null && args.isEmpty()) return extMember;
        return null;
    }

    /** NovaClass: 鏋氫妇闈欐€佹柟娉?/ 鍗曚緥濮旀墭 / 闈欐€佹柟娉?*/
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

    /** NovaResult 鎴愬憳 + 闈?Result 鐨?isErr/isOk 鍥為€€ */
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

    /** Iterator 鍗忚: iterator() / hasNext() / next() */
    private NovaValue tryIteratorProtocol(NovaValue receiver, String methodName) {
        if ("iterator".equals(methodName)) {
            // NovaMap 浼樺厛妫€鏌ヨ嚜瀹氫箟 iterator 鏂规硶锛堝 Channel 鐨勮凯浠ｅ櫒锛?
            if (receiver instanceof NovaMap) {
                NovaValue entry = ((NovaMap) receiver).get(NovaString.of("iterator"));
                if (entry instanceof NovaCallable) return ((NovaCallable) entry).call(interp, java.util.Collections.emptyList());
                // 鏃犺嚜瀹氫箟 iterator 鈫?璧?Iterable 榛樿璺緞
            }
            if (receiver instanceof Iterable) {
                return new NovaExternalObject(((Iterable<?>) receiver).iterator());
            }
            // String → 逐字符迭代
            if (receiver instanceof NovaString) {
                String s = ((NovaString) receiver).getValue();
                java.util.List<NovaValue> chars = new java.util.ArrayList<>(s.length());
                for (int i = 0; i < s.length(); i++) chars.add(NovaString.of(String.valueOf(s.charAt(i))));
                return new NovaExternalObject(chars.iterator());
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

    /** NovaEnumEntry: name/ordinal + 鏂规硶璋冪敤 */
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

    /** super.method() — 父类方法分派（根据 owner 中编码的目标超类） */
    private NovaValue invokeSuper(NovaObject obj, String methodName, String owner, List<NovaValue> args) {
        // owner 格式: "$super$ClassName" — 从编译时确定的目标超类查找方法
        NovaClass targetSuper = null;
        if (owner != null && owner.length() > MARKER_SUPER.length()) {
            String targetName = owner.substring(MARKER_SUPER.length());
            // 沿继承链查找名称匹配的超类
            NovaClass cur = obj.getNovaClass().getSuperclass();
            while (cur != null) {
                if (cur.getName().equals(targetName)) {
                    targetSuper = cur;
                    break;
                }
                cur = cur.getSuperclass();
            }
        }
        // 如果没有编码目标名称或没找到，回退到直接超类
        if (targetSuper == null) {
            targetSuper = obj.getNovaClass().getSuperclass();
        }
        if (targetSuper != null) {
            NovaCallable method = targetSuper.findCallableMethod(methodName);
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

    /** NovaObject: 鍙鎬ф鏌?+ 鏂规硶鏌ユ壘 */
    private NovaValue tryInvokeOnObject(NovaObject obj, String methodName, String owner, List<NovaValue> args) {
        // <init> 璋冪敤锛堟绾ф瀯閫犲櫒濮旀墭涓绘瀯閫犲櫒锛?
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

    /** 鐗规畩绫诲瀷: Set/List/Map 瀛楅潰閲忔瀯寤恒€丯ovaFuture銆乮nvoke銆佷綔鐢ㄥ煙鍑芥暟 */
    private NovaValue trySpecialTypes(NovaValue receiver, String methodName, String owner, List<NovaValue> args) {
        // Set 鎿嶄綔
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
        // NovaScope 鈥?缁撴瀯鍖栧苟鍙戜綔鐢ㄥ煙
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
        // NovaDeferred 鈥?async 杩斿洖鍊?
        if (receiver instanceof NovaDeferred) {
            NovaDeferred d = (NovaDeferred) receiver;
            switch (methodName) {
                case "await": case "get": return d.await(interp);
                case "cancel":      return NovaBoolean.of(d.cancel());
                case "isDone":      return NovaBoolean.of(d.isDone());
                case "isCancelled": return NovaBoolean.of(d.isCancelled());
            }
        }
        // NovaJob 鈥?launch 杩斿洖鍊?
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
        // NovaTask 鈥?schedule/scheduleRepeat 杩斿洖鍊?
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
        // 浣滅敤鍩熷嚱鏁?
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

    /** 閫氱敤鍥為€€: 鎵╁睍鍑芥暟 鈫?stdlib 鈫?MemberResolver 鈫?JavaBean getter */
    private NovaValue tryGenericFallback(NovaValue receiver, String methodName, List<NovaValue> args) {
        NovaCallable userExt = interp.findExtension(receiver, methodName);
        if (userExt != null) return dispatcher.bindAndExecute(receiver, userExt, args);

        NovaValue stdlibMember = resolver.tryStdlibFallback(receiver, methodName);
        if (stdlibMember instanceof NovaCallable) return ((NovaCallable) stdlibMember).call(interp, args);

        try {
            NovaValue member = resolver.resolveMemberOnValue(receiver, methodName, null);
            if (member instanceof NovaCallable) return ((NovaCallable) member).call(interp, args);
            if (member != null && args.isEmpty()) return member;
        } catch (NovaRuntimeException e) {
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
            if (!LookupMissDetector.isLookupMiss(e)) {
                throw e;
            }
        }
        return null;
    }
}
