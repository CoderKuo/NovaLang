package nova.runtime.interpreter;

import com.novalang.ir.mir.*;
import nova.runtime.*;
import nova.runtime.types.NovaClass;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import nova.runtime.stdlib.StdlibRegistry;

import java.util.*;

/**
 * 静态方法分派器。
 *
 * <p>处理 INVOKE_STATIC 指令中的方法查找与调用链，
 * 包括 $ScopeCall、$PartialApplication、$ENV、$PipeCall、
 * Nova 运行时类分派、Java 静态方法等。从 MirCallDispatcher 拆分而来。</p>
 */
final class StaticMethodDispatcher {

    // ===== MIR 特殊标记 =====
    private static final String MARKER_SCOPE_CALL = "$ScopeCall";
    private static final String MARKER_PARTIAL_APP = "$PartialApplication|";
    private static final String MARKER_ENV = "$ENV|";
    private static final String MARKER_SCRIPT_CTX = "nova/runtime/NovaScriptContext|";
    private static final String MARKER_BIND_METHOD = "$BIND_METHOD";
    private static final String MARKER_PIPE_CALL = "$PipeCall";
    private static final String MARKER_RANGE = "$RANGE";
    private static final String MARKER_MODULE = "$Module";
    private static final String MARKER_LAMBDA = "$Lambda$";
    private static final String MARKER_METHOD_REF = "$MethodRef$";

    // ===== 特殊方法名 =====
    private static final String SPECIAL_INIT = "<init>";
    private static final String SPECIAL_CLINIT = "<clinit>";

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
    private final MirCallDispatcher dispatcher;
    private final VirtualMethodDispatcher virtualDispatcher;
    private final MirInterpreter mirInterp;
    private final Map<String, MirCallable> mirFunctions;
    private final Map<String, MirInterpreter.MirClassInfo> mirClasses;

    StaticMethodDispatcher(Interpreter interp, MemberResolver resolver,
                           MirCallDispatcher dispatcher, VirtualMethodDispatcher virtualDispatcher,
                           MirInterpreter mirInterp,
                           Map<String, MirCallable> mirFunctions,
                           Map<String, MirInterpreter.MirClassInfo> mirClasses) {
        this.interp = interp;
        this.resolver = resolver;
        this.dispatcher = dispatcher;
        this.virtualDispatcher = virtualDispatcher;
        this.mirInterp = mirInterp;
        this.mirFunctions = mirFunctions;
        this.mirClasses = mirClasses;
    }

    // ============ INVOKE_STATIC 入口 ============

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

    // ============ 特殊标记处理 ============

    /** $ScopeCall — receiver.block() 中 block 是局部变量 callable，以 receiver 为 scopeReceiver 调用 */
    private void executeScopeCall(MirFrame frame, MirInst inst) {
        int[] ops = inst.getOperands();
        NovaValue callable = frame.get(ops[0]);
        NovaValue receiver = frame.get(ops[1]);
        List<NovaValue> callArgs = new ArrayList<>();
        for (int i = 2; i < ops.length; i++) {
            callArgs.add(frame.get(ops[i]));
        }
        NovaCallable extracted = dispatcher.extractCallable(callable);
        if (extracted != null) {
            NovaValue result = dispatcher.withScopeReceiver(receiver, () -> extracted.call(interp, callArgs));
            if (inst.getDest() >= 0) frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
            return;
        }
        // callable 不可调用 → 尝试作为普通方法调用
        NovaValue result = virtualDispatcher.invokeVirtualMethod(receiver, callable != null ? callable.asString() : "invoke", null, callArgs);
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
        NovaCallable calleeCallable = dispatcher.extractCallable(callee);
        if (calleeCallable == null) {
            throw new NovaRuntimeException("Partial application requires a callable, got " + callee.getTypeName());
        }
        NovaValue result = new NovaPartialApplication(calleeCallable, partialArgs);
        if (inst.getDest() >= 0) frame.locals[inst.getDest()] = result;
    }

    /** $ENV|op 或 nova/runtime/NovaScriptContext|op — 环境变量访问 */
    void executeEnvAccess(MirFrame frame, MirInst inst) {
        String extra = inst.extraAs();
        int[] ops = inst.getOperands();
        if (extra.contains("|get|") && ops != null && ops.length > 0) {
            // NovaScriptContext.get(name) → Environment.tryGet(name)
            NovaValue nameVal = frame.locals[ops[0]];
            String name = nameVal != null ? nameVal.asString() : null;
            NovaValue value = name != null ? interp.getEnvironment().tryGet(name) : null;
            // 作用域函数: 从接收者对象读取字段
            if (value == null && name != null && dispatcher.scopeReceiver instanceof NovaObject) {
                NovaObject obj = (NovaObject) dispatcher.scopeReceiver;
                if (obj.hasField(name)) {
                    value = obj.getField(name);
                }
            }
            if (value == null && "this".equals(name) && dispatcher.scopeReceiver != null) {
                value = dispatcher.scopeReceiver;
            }
            // 作用域成员解析: receiver lambda / 作用域函数中，解析 scopeReceiver 的成员方法
            if (value == null && name != null && dispatcher.scopeReceiver != null) {
                try {
                    value = resolver.resolveMemberOnValue(dispatcher.scopeReceiver, name, null);
                } catch (NovaRuntimeException ignored) {}
            }
            // 通配符 Java 导入回退（import java java.util.* 等）
            if (value == null && name != null) {
                value = dispatcher.resolveWildcardJavaImport(name);
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
                    MirInst prev = dispatcher.envVarDefinedBy.get(name);
                    if (prev != null && prev != inst) {
                        // 不同 BasicBlock 允许重定义（when/if 块级作用域）
                        Integer prevBlock = dispatcher.envVarDefinedInBlock.get(name);
                        if (prevBlock != null && prevBlock == frame.currentBlockId) {
                            throw new NovaRuntimeException("Variable already defined: " + name);
                        }
                    }
                    dispatcher.envVarDefinedBy.put(name, inst);
                    dispatcher.envVarDefinedInBlock.put(name, frame.currentBlockId);
                }
                interp.getEnvironment().redefine(name, value, false);
            }
        } else if (extra.contains("|defineVar|") && ops != null && ops.length > 1) {
            NovaValue nameVal = frame.locals[ops[0]];
            NovaValue value = frame.get(ops[1]);
            String name = nameVal != null ? nameVal.asString() : null;
            if (name != null && value != null) {
                if (!interp.isReplMode()) {
                    MirInst prev = dispatcher.envVarDefinedBy.get(name);
                    if (prev != null && prev != inst) {
                        Integer prevBlock = dispatcher.envVarDefinedInBlock.get(name);
                        if (prevBlock != null && prevBlock == frame.currentBlockId) {
                            throw new NovaRuntimeException("Variable already defined: " + name);
                        }
                    }
                    dispatcher.envVarDefinedBy.put(name, inst);
                    dispatcher.envVarDefinedInBlock.put(name, frame.currentBlockId);
                }
                interp.getEnvironment().redefine(name, value, true);
            }
        } else if (extra.contains("|set|") && ops != null && ops.length > 1) {
            NovaValue nameVal = frame.locals[ops[0]];
            NovaValue value = frame.get(ops[1]);
            String name = nameVal != null ? nameVal.asString() : null;
            if (name != null && value != null) {
                if (dispatcher.scopeReceiver instanceof NovaObject
                        && ((NovaObject) dispatcher.scopeReceiver).hasField(name)) {
                    ((NovaObject) dispatcher.scopeReceiver).setField(name, value);
                } else if (!interp.getEnvironment().tryAssign(name, value)) {
                    interp.getEnvironment().redefine(name, value, true);
                }
            }
        }
    }

    // ============ 静态方法分派主链 ============

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
        NovaValue javaClassVal = dispatcher.resolveWildcardJavaImport(methodName);
        if (javaClassVal instanceof NovaCallable) {
            return ((NovaCallable) javaClassVal).call(interp, args);
        }
        // 4. 回退: args[0].methodName(args[1:])
        if (!args.isEmpty()) {
            NovaValue target = args.get(0);
            List<NovaValue> methodArgs = args.size() > 1 ? args.subList(1, args.size()) : Collections.emptyList();
            return virtualDispatcher.invokeVirtualMethod(target, methodName, null, methodArgs);
        }
        throw new NovaRuntimeException("Undefined function: " + methodName);
    }

    // ============ Nova 运行时类分派 ============

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
                return virtualDispatcher.invokeVirtualMethod(target, dynMethodName, null, dynArgs);
            }
            if ("getMember".equals(methodName) && args.size() == 2) {
                NovaValue target = args.get(0);
                String memberName2 = args.get(1).asString();
                if (target instanceof MirCallable) {
                    NovaValue fieldVal = ((MirCallable) target).getCaptureField(memberName2);
                    if (fieldVal != null) return fieldVal;
                    if (dispatcher.scopeReceiver != null) {
                        NovaValue resolved = dispatcher.resolveFieldOnValue(dispatcher.scopeReceiver, memberName2);
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
                    } else if (dispatcher.scopeReceiver instanceof NovaObject
                            && ((NovaObject) dispatcher.scopeReceiver).hasField(memberName2)) {
                        ((NovaObject) dispatcher.scopeReceiver).setField(memberName2, args.get(2));
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
            NovaCallable fnCallable = dispatcher.extractCallable(fn);
            if (fnCallable != null) return fnCallable.call(interp, fnArgs);
            return virtualDispatcher.invokeVirtualMethod(fn, "invoke", null, fnArgs);
        }
        // AsyncHelper.run
        if (NOVA_ASYNC_HELPER.equals(owner) && "run".equals(methodName) && args.size() == 1) {
            NovaCallable asyncCallable = dispatcher.extractCallable(args.get(0));
            if (asyncCallable != null) return new NovaFuture(asyncCallable, interp);
        }
        // NovaScopeFunctions
        if (NOVA_SCOPE_FUNCTIONS.equals(owner) && args.size() >= 2) {
            NovaValue self = args.get(0);
            NovaCallable lambda = dispatcher.extractCallable(args.get(1));
            if (lambda != null) {
                NovaValue scopeResult = dispatcher.tryExecuteScopeFunction(methodName, self, lambda);
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
            return AbstractNovaValue.fromJava(nfInfo.impl.apply(javaArgs));
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
                    NovaCallable callable = dispatcher.extractCallable(methodArgs.get(lambdaIdx));
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
                    NovaCallable callable = dispatcher.extractCallable(methodArgs.get(lambdaIdx));
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
                return dispatcher.bindAndExecute(receiver, userExt, methodArgs);
            }
            NovaValue stdlibMethod = resolver.tryStdlibFallback(receiver, methodName);
            if (stdlibMethod instanceof NovaCallable) return ((NovaCallable) stdlibMethod).call(interp, methodArgs);
            return virtualDispatcher.invokeVirtualMethod(receiver, methodName, null, methodArgs);
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

    // ============ 环境 / 类 / 枚举 / Java 静态 ============

    /** 从全局环境查找函数/callable */
    private NovaValue tryEnvironmentLookup(String methodName, List<NovaValue> args) {
        NovaValue funcVal = interp.getEnvironment().tryGet(methodName);
        if (funcVal instanceof NovaCallable) return ((NovaCallable) funcVal).call(interp, args);
        if (funcVal != null) {
            NovaCallable extracted = dispatcher.extractCallable(funcVal);
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
        // Java 静态方法调用（通过 MethodHandleCache 缓存）
        try {
            Class<?> javaClass = Class.forName(toJavaDotName(owner));
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) javaArgs[i] = args.get(i).toJavaValue();
            Object result = MethodHandleCache.getInstance().invokeStatic(javaClass, methodName, javaArgs);
            return AbstractNovaValue.fromJava(result);
        } catch (NovaRuntimeException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("Static method not found:")) {
                // method not found → fall through
            } else {
                throw e;
            }
        } catch (ClassNotFoundException e) {
            // class not found → fall through
        } catch (Throwable e) {
            // 方法执行时的真实异常，包装后重抛
            throw new NovaRuntimeException(
                    owner + "." + methodName + " invocation failed: " + e.getMessage(), e);
        }
        return null;
    }

    // ============ 辅助方法 ============

    /** componentN 解构操作 */
    private NovaValue handleComponentN(NovaValue target, int n) {
        if (target instanceof NovaPair) {
            Object val = n == 1 ? ((NovaPair) target).getFirst() : ((NovaPair) target).getSecond();
            return val instanceof NovaValue ? (NovaValue) val : AbstractNovaValue.fromJava(val);
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
}
