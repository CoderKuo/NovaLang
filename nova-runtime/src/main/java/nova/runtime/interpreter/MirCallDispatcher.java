package nova.runtime.interpreter;

import com.novalang.ir.mir.*;
import nova.runtime.*;
import nova.runtime.types.NovaClass;

import java.util.*;

/**
 * MIR 方法分派器（协调器）。
 *
 * <p>从 MirInterpreter 提取的方法分派逻辑入口，负责：
 * <ul>
 *   <li>INVOKE_VIRTUAL / INVOKE_INTERFACE / INVOKE_SPECIAL 指令 → 委托 VirtualMethodDispatcher</li>
 *   <li>INVOKE_STATIC 指令 → 委托 StaticMethodDispatcher</li>
 *   <li>共享状态管理（scopeReceiver、环境变量定义检测）</li>
 *   <li>共享工具方法（bindAndExecute、extractCallable、作用域函数等）</li>
 * </ul>
 * </p>
 */
final class MirCallDispatcher {

    // ===== MIR 特殊标记 =====
    private static final String MARKER_SUPER = "$super$";

    // ===== 特殊方法名 =====
    private static final String SPECIAL_INIT = "<init>";

    private final Interpreter interp;
    private final MemberResolver resolver;
    private final MirInterpreter mirInterp;

    /** 虚方法分派器 */
    final VirtualMethodDispatcher virtualDispatcher;
    /** 静态方法分派器 */
    final StaticMethodDispatcher staticDispatcher;

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
        this.virtualDispatcher = new VirtualMethodDispatcher(interp, resolver, this);
        this.staticDispatcher = new StaticMethodDispatcher(
                interp, resolver, this, virtualDispatcher, mirInterp, mirFunctions, mirClasses);
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
                List<NovaValue> args = collectArgs(frame, ops);
                NovaValue result = bindAndExecute(obj, method, args);
                if (inst.getDest() >= 0) {
                    frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
                }
                return;
            }
            // method not found → fall through to full dispatch (extensions, stdlib, etc.)
        }

        // ===== 常规路径（非 NovaObject / 命名参数 / super 调用 / 方法未找到） =====
        List<NovaValue> allArgs = collectArgs(frame, ops);

        NovaValue result;
        if (cs.namedInfo != null && cs.namedInfo.startsWith("named:")) {
            result = virtualDispatcher.invokeWithNamedArgs(receiver, cs.methodName, cs.owner, allArgs, cs.namedInfo);
        } else {
            result = virtualDispatcher.invokeVirtualMethod(receiver, cs.methodName, cs.owner, allArgs);
        }
        if (inst.getDest() >= 0) {
            frame.locals[inst.getDest()] = result != null ? result : NovaNull.UNIT;
        }
    }

    // ============ INVOKE_STATIC ============

    void executeInvokeStatic(MirFrame frame, MirInst inst) {
        staticDispatcher.executeInvokeStatic(frame, inst);
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

    // ============ 共享工具方法 ============

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

    /** 方法可见性检查（供内联缓存 cache miss 路径复用） */
    void checkMethodVisibility(NovaClass receiverClass, String methodName, String owner) {
        nova.runtime.types.Modifier vis = receiverClass.getMethodVisibility(methodName);
        if (vis == nova.runtime.types.Modifier.PRIVATE) {
            if (owner == null || !owner.equals(receiverClass.getName())) {
                throw new NovaRuntimeException(
                        "Cannot access private method '" + methodName + "' from outside " + receiverClass.getName());
            }
        }
        if (vis == nova.runtime.types.Modifier.PROTECTED) {
            boolean allowed = owner != null && owner.equals(receiverClass.getName());
            if (!allowed && owner != null) {
                MirInterpreter.MirClassInfo ownerInfo = mirInterp.mirClasses.get(owner);
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

    boolean isTruthy(NovaValue value) {
        if (value == null) return false;
        if (value instanceof NovaNull) return !value.isNull();
        return value.isTruthy();
    }

    /** 从 frame 中收集方法参数，对常见小参数数量避免数组分配 */
    List<NovaValue> collectArgs(MirFrame frame, int[] ops) {
        int argCount = ops.length - 1;
        switch (argCount) {
            case 0: return Collections.emptyList();
            case 1: return Collections.singletonList(frame.get(ops[1]));
            case 2: return List.of(frame.get(ops[1]), frame.get(ops[2]));
            case 3: return List.of(frame.get(ops[1]), frame.get(ops[2]), frame.get(ops[3]));
            default:
                NovaValue[] arr = new NovaValue[argCount];
                for (int i = 0; i < argCount; i++) arr[i] = frame.get(ops[i + 1]);
                return Arrays.asList(arr);
        }
    }
}
