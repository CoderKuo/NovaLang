package com.novalang.runtime.interpreter;

import com.novalang.ir.mir.*;
import com.novalang.ir.hir.ClassKind;
import com.novalang.runtime.*;
import com.novalang.runtime.types.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MIR 字节码解释器。
 *
 * <p>直接解释执行 MIR 指令（while + switch 循环），替代 HirEvaluator 的树遍历模型。
 * 复用现有 Interpreter 的全局状态和 MemberResolver 进行成员解析。</p>
 */
final class MirInterpreter {

    // ===== MIR 特殊标记 =====
    private static final String MARKER_LAMBDA = "$Lambda$";
    private static final String MARKER_METHOD_REF = "$MethodRef$";

    // ===== 特殊方法名 =====
    private static final String SPECIAL_CLINIT = "<clinit>";

    // ===== JVM 类名 =====
    private static final String JAVA_ARRAY_LIST = "java/util/ArrayList";
    private static final String JAVA_HASH_MAP = "java/util/HashMap";
    private static final String JAVA_LINKED_HASH_SET = "java/util/LinkedHashSet";
    private static final String JAVA_SYSTEM = "java/lang/System";

    /** GET_STATIC 缓存哨兵：表示已解析但字段/类不存在 */
    private static final Object STATIC_FIELD_MISS = new Object();

    /** JVM 内部名 → Java 点分名: "java/lang/String" → "java.lang.String" */
    private static String toJavaDotName(String internalName) {
        return internalName.replace("/", ".");
    }

    final Interpreter interp;
    final MemberResolver resolver;

    /** reified 类型参数传递（$PipeCall → executeFunction） */
    String[] pendingReifiedTypeArgs;

    /** MirFrame 对象池（避免递归调用时重复分配数组） */
    private final MirFrame[] framePool = new MirFrame[32];
    private int framePoolTop = 0;

    /** 缓存的最大递归深度（避免每次调用走虚方法链） */
    private final int cachedMaxRecursionDepth;

    /** executeFrame 异常路径设置的 TCE 计数，供 fastCall 读取 */
    int lastTceCount;

    /** 模块级注册：函数名 → MirCallable */
    final Map<String, MirCallable> mirFunctions = new HashMap<>();
    /** 模块级注册：类名 → MirClassInfo */
    final Map<String, MirClassInfo> mirClasses = new HashMap<>();

    /** 类注册器 */
    final MirClassRegistrar classRegistrar;
    /** 方法分派器 */
    final MirCallDispatcher callDispatcher;
    private final Map<String, NovaCallFrame> emptyCallFrameCache = new HashMap<>();
    private static final Object NO_TAIL_INT_LOOP_PLAN = new Object();
    private static final AtomicLong STRING_ACCUM_LOOP_FAST_HITS = new AtomicLong();
    private static final AtomicLong STRING_ACCUM_LOOP_PLAN_HITS = new AtomicLong();
    private static final Object NO_TAIL_INT_LOOP_PLAN3 = new Object();
    private static final Object NO_STRING_ACCUM_LOOP_PLAN = new Object();
    private final IdentityHashMap<MirFunction, Object> tailIntLoopPlanCache = new IdentityHashMap<>();
    private final IdentityHashMap<MirFunction, Object> tailIntLoopPlan3Cache = new IdentityHashMap<>();
    private final IdentityHashMap<MirFunction, Object> stringAccumLoopPlanCache = new IdentityHashMap<>();

    private static final class TailIntLoopPlan {
        static final byte ACC_RHS_OLD_COUNTER = 0;
        static final byte ACC_RHS_NEW_COUNTER = 1;
        static final byte ACC_RHS_CONST = 2;

        final int entryBlockId;
        final BinaryOp exitCompareOp;
        final boolean exitOnTrue;
        final int stopValue;
        final int returnLocal;
        final int counterDelta;
        final BinaryOp accUpdateOp;
        final byte accRhsKind;
        final int accConst;

        TailIntLoopPlan(int entryBlockId, BinaryOp exitCompareOp, boolean exitOnTrue,
                        int stopValue, int returnLocal, int counterDelta,
                        BinaryOp accUpdateOp, byte accRhsKind, int accConst) {
            this.entryBlockId = entryBlockId;
            this.exitCompareOp = exitCompareOp;
            this.exitOnTrue = exitOnTrue;
            this.stopValue = stopValue;
            this.returnLocal = returnLocal;
            this.counterDelta = counterDelta;
            this.accUpdateOp = accUpdateOp;
            this.accRhsKind = accRhsKind;
            this.accConst = accConst;
        }
    }

    private static final class TailAccSpec {
        final BinaryOp op;
        final byte rhsKind;
        final int rhsConst;

        TailAccSpec(BinaryOp op, byte rhsKind, int rhsConst) {
            this.op = op;
            this.rhsKind = rhsKind;
            this.rhsConst = rhsConst;
        }
    }

    private static final class IntExpr {
        static final byte PARAM = 0;
        static final byte CONST = 1;
        static final byte BINARY = 2;

        final byte kind;
        final int value;
        final BinaryOp op;
        final IntExpr left;
        final IntExpr right;

        private IntExpr(byte kind, int value, BinaryOp op, IntExpr left, IntExpr right) {
            this.kind = kind;
            this.value = value;
            this.op = op;
            this.left = left;
            this.right = right;
        }

        static IntExpr param(int index) {
            return new IntExpr(PARAM, index, null, null, null);
        }

        static IntExpr constant(int value) {
            return new IntExpr(CONST, value, null, null, null);
        }

        static IntExpr binary(BinaryOp op, IntExpr left, IntExpr right) {
            return new IntExpr(BINARY, 0, op, left, right);
        }
    }

    private static final class TailIntLoopPlan3 {
        final int entryBlockId;
        final BinaryOp exitCompareOp;
        final boolean exitOnTrue;
        final int stopValue;
        final int returnLocal;
        final int counterDelta;
        final IntExpr update1;
        final IntExpr update2;

        TailIntLoopPlan3(int entryBlockId, BinaryOp exitCompareOp, boolean exitOnTrue,
                         int stopValue, int returnLocal, int counterDelta,
                         IntExpr update1, IntExpr update2) {
            this.entryBlockId = entryBlockId;
            this.exitCompareOp = exitCompareOp;
            this.exitOnTrue = exitOnTrue;
            this.stopValue = stopValue;
            this.returnLocal = returnLocal;
            this.counterDelta = counterDelta;
            this.update1 = update1;
            this.update2 = update2;
        }
    }

    /** MIR 类的注册信息 */
    static final class MirClassInfo {
        final MirClass mirClass;
        final NovaClass novaClass;
        MirClassInfo(MirClass mirClass, NovaClass novaClass) {
            this.mirClass = mirClass;
            this.novaClass = novaClass;
        }
    }

    MirInterpreter(Interpreter interp) {
        this.interp = interp;
        this.resolver = interp.memberResolver;
        this.cachedMaxRecursionDepth = interp.getSecurityPolicy().getMaxRecursionDepth();
        this.classRegistrar = new MirClassRegistrar(interp, mirClasses, mirFunctions, this);
        this.callDispatcher = new MirCallDispatcher(interp, resolver, this, mirFunctions, mirClasses);
    }

    /**
     * 子 MirInterpreter（async 线程用）。
     * 共享父级的函数/类注册表，拥有独立的可变执行状态（callStack、callDepth 等）。
     */
    MirInterpreter(Interpreter childInterp, MirInterpreter parent) {
        this.interp = childInterp;
        this.resolver = childInterp.memberResolver;
        this.cachedMaxRecursionDepth = childInterp.getSecurityPolicy().getMaxRecursionDepth();
        this.mirFunctions.putAll(parent.mirFunctions);
        this.mirClasses.putAll(parent.mirClasses);
        this.moduleStaticFields = parent.moduleStaticFields; // 共享引用（线程安全 ConcurrentHashMap）
        this.classRegistrar = new MirClassRegistrar(childInterp, mirClasses, mirFunctions, this, parent.classRegistrar);
        this.callDispatcher = new MirCallDispatcher(childInterp, childInterp.memberResolver, this, mirFunctions, mirClasses);
    }

    /**
     * 重置模块级注册状态（每次 executeModule 前调用）。
     */
    void resetState() {
        mirFunctions.clear();
        // 保留 lambda 匿名类（跨 evalRepl 调用的闭包仍需要它们）
        mirClasses.entrySet().removeIf(e -> !e.getKey().contains(MARKER_LAMBDA));
        // moduleStaticFields 跨 eval 持久化（REPL 模式下顶层变量跨调用共享）
        resetExecutionState();
        tailIntLoopPlanCache.clear();
        tailIntLoopPlan3Cache.clear();
        stringAccumLoopPlanCache.clear();
    }

    void resetExecutionState() {
        pendingReifiedTypeArgs = null;
        callDispatcher.resetState();
    }

    static void resetStringAccumLoopFastHits() {
        STRING_ACCUM_LOOP_FAST_HITS.set(0L);
        STRING_ACCUM_LOOP_PLAN_HITS.set(0L);
    }

    static long getStringAccumLoopFastHits() {
        return STRING_ACCUM_LOOP_FAST_HITS.get();
    }

    static long getStringAccumLoopPlanHits() {
        return STRING_ACCUM_LOOP_PLAN_HITS.get();
    }

    /** 返回所有已注册的 Nova 类/接口名称（供下次 evalRepl 编译使用） */
    Set<String> getKnownClassNames() {
        return classRegistrar.getKnownClassNames();
    }

    /** 返回已知的接口名（供跨 evalRepl 的 HirToMirLowering 识别接口类型） */
    Set<String> getKnownInterfaceNames() {
        return classRegistrar.getKnownInterfaceNames();
    }

    NovaCallFrame getEmptyMirCallFrame(String displayName) {
        NovaCallFrame frame = emptyCallFrameCache.get(displayName);
        if (frame == null) {
            frame = NovaCallFrame.emptyMirCallable(displayName);
            emptyCallFrameCache.put(displayName, frame);
        }
        return frame;
    }

    // ============ 模块执行 ============

    private static final byte EXPORT_MUT_DYNAMIC = 0;
    private static final byte EXPORT_MUT_VAL = 1;
    private static final byte EXPORT_MUT_VAR = 2;

    static final class PreparedModule {
        final MirFunction mainFunc;
        final ExportSlot[] exportSlots;

        PreparedModule(MirFunction mainFunc, ExportSlot[] exportSlots) {
            this.mainFunc = mainFunc;
            this.exportSlots = exportSlots;
        }
    }

    static final class ExportSlot {
        final int localIndex;
        final String name;
        final byte mutability;

        ExportSlot(int localIndex, String name, byte mutability) {
            this.localIndex = localIndex;
            this.name = name;
            this.mutability = mutability;
        }
    }

    PreparedModule prepareModule(MirModule module) {
        for (Map.Entry<String, String> entry : module.getJavaImports().entrySet()) {
            String simpleName = entry.getKey();
            String qualifiedName = entry.getValue();
            Class<?> clazz = interp.resolveJavaClass(qualifiedName);
            if (clazz == null) {
                throw new NovaRuntimeException("Java class not found: " + qualifiedName);
            }
            if (interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                interp.getEnvironment().redefine(simpleName,
                        new JavaInterop.NovaJavaClass(clazz), false);
            } else {
                throw NovaSecurityPolicy.denied("Cannot access class: " + clazz.getName());
            }
        }
        for (Map.Entry<String, String> entry : module.getStaticImports().entrySet()) {
            String memberName = entry.getKey();
            String qualifiedName = entry.getValue();
            int lastDot = qualifiedName.lastIndexOf('.');
            if (lastDot > 0) {
                String className = qualifiedName.substring(0, lastDot);
                String fieldName = qualifiedName.substring(lastDot + 1);
                Class<?> clazz = interp.resolveJavaClass(className);
                if (clazz != null) {
                    if (!interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                        throw NovaSecurityPolicy.denied("Cannot access class: " + clazz.getName());
                    }
                    try {
                        java.lang.reflect.Field field = clazz.getField(fieldName);
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                            interp.getEnvironment().redefine(memberName,
                                    AbstractNovaValue.fromJava(field.get(null)), false);
                        }
                    } catch (NoSuchFieldException | IllegalAccessException ignored) {}
                }
            }
        }
        for (String wildcardPkg : module.getWildcardJavaImports()) {
            interp.wildcardJavaImports.add(wildcardPkg);
        }

        Set<String> moduleClassNames = new HashSet<String>();
        for (MirClass cls : module.getClasses()) {
            moduleClassNames.add(cls.getName());
        }
        classRegistrar.setCurrentModuleClassNames(moduleClassNames);
        for (MirClass cls : module.getClasses()) {
            classRegistrar.registerClass(cls);
        }
        classRegistrar.clearCurrentModuleClassNames();

        MirFunction mainFunc = null;
        for (MirFunction func : module.getTopLevelFunctions()) {
            if ("main".equals(func.getName())) {
                mainFunc = func;
            } else {
                MirCallable callable = new MirCallable(this, func, null);
                mirFunctions.put(func.getName(), callable);
                interp.getEnvironment().defineVal(func.getName(), callable);
            }
        }

        for (MirModule.NovaImportInfo imp : module.getNovaImports()) {
            String qn = imp.qualifiedName;
            String[] parts = qn.split("\\.");
            String builtinModule = BuiltinModuleRegistry.resolveModuleName(java.util.Arrays.asList(parts));
            if (builtinModule != null) {
                BuiltinModuleRegistry.load(builtinModule, interp.getEnvironment(), interp);
                continue;
            }

            Class<?> clazz = interp.resolveJavaClass(qn);
            if (clazz != null) {
                if (interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                    String simpleName = imp.alias != null ? imp.alias : clazz.getSimpleName();
                    interp.getEnvironment().redefine(simpleName,
                            new JavaInterop.NovaJavaClass(clazz), false);
                }
                continue;
            }

            if (interp.moduleLoader == null) {
                throw new NovaRuntimeException("Cannot resolve module: " + qn + " (no module loader)");
            }
            java.util.List<String> pathParts;
            String symbolName;
            if (imp.wildcard) {
                pathParts = new java.util.ArrayList<String>(java.util.Arrays.asList(parts));
                symbolName = null;
            } else {
                pathParts = new java.util.ArrayList<String>();
                for (int i = 0; i < parts.length - 1; i++) {
                    pathParts.add(parts[i]);
                }
                symbolName = parts[parts.length - 1];
            }
            java.nio.file.Path modulePath = interp.moduleLoader.resolveModulePath(pathParts);
            if (modulePath == null) {
                throw new NovaRuntimeException("Module not found: " + qn);
            }
            Environment moduleEnv = interp.moduleLoader.loadModule(modulePath, interp);
            if (symbolName == null) {
                moduleEnv.exportAll(interp.getEnvironment());
            } else {
                NovaValue value = moduleEnv.tryGet(symbolName);
                if (value == null) {
                    throw new NovaRuntimeException("Symbol '" + symbolName + "' not found in module");
                }
                String localName = imp.alias != null ? imp.alias : symbolName;
                interp.getEnvironment().redefine(localName, value, false);
            }
        }

        for (MirModule.ExtensionPropertyInfo epInfo : module.getExtensionProperties()) {
            MirCallable getter = mirFunctions.get(epInfo.getterFuncName);
            if (getter != null) {
                String novaTypeName = com.novalang.compiler.NovaTypeNames.fromBoxedInternalName(epInfo.receiverType);
                if (novaTypeName == null) {
                    novaTypeName = toJavaDotName(epInfo.receiverType);
                }
                interp.registerExtensionProperty(novaTypeName, epInfo.propertyName, getter);
            }
        }

        for (MirModule.ExtensionFunctionInfo efInfo : module.getExtensionFunctions()) {
            MirCallable func = mirFunctions.get(efInfo.functionName);
            if (func != null) {
                String novaTypeName = com.novalang.compiler.NovaTypeNames.fromBoxedInternalName(efInfo.receiverType);
                if (novaTypeName == null) {
                    novaTypeName = toJavaDotName(efInfo.receiverType);
                }
                interp.registerNovaExtension(novaTypeName, efInfo.functionName, func);
            }
        }

        for (MirClass cls : module.getClasses()) {
            for (MirFunction method : cls.getMethods()) {
                if (SPECIAL_CLINIT.equals(method.getName())) {
                    executeFunction(method, new NovaValue[0]);
                }
            }
        }

        for (MirClass cls : module.getClasses()) {
            if (cls.getKind() == ClassKind.ENUM) {
                classRegistrar.finalizeEnumClass(cls);
            }
        }

        for (MirClass cls : module.getClasses()) {
            if (cls.getKind() == ClassKind.OBJECT) {
                MirClassInfo info = mirClasses.get(cls.getName());
                if (info != null && info.novaClass != null) {
                    NovaValue instance = info.novaClass.getStaticField("INSTANCE");
                    if (instance != null) {
                        interp.getEnvironment().redefine(cls.getName(), instance, false);
                    }
                }
            }
        }

        return new PreparedModule(mainFunc, buildExportSlots(mainFunc));
    }

    private ExportSlot[] buildExportSlots(MirFunction mainFunc) {
        if (mainFunc == null) {
            return new ExportSlot[0];
        }
        Map<String, Byte> mutabilityByName = new HashMap<String, Byte>();
        Map<Integer, String> constStrings = new HashMap<Integer, String>();
        for (BasicBlock block : mainFunc.getBlocks()) {
            for (MirInst inst : block.getInstArray()) {
                if (inst.getOp() == MirOp.CONST_STRING && inst.getExtra() instanceof String) {
                    constStrings.put(Integer.valueOf(inst.getDest()), (String) inst.getExtra());
                    continue;
                }
                if (inst.getOp() != MirOp.INVOKE_STATIC) {
                    continue;
                }
                Object extra = inst.getExtra();
                if (!(extra instanceof String)) {
                    continue;
                }
                String extraStr = (String) extra;
                byte mutability;
                if (extraStr.contains("|defineVar|")) {
                    mutability = EXPORT_MUT_VAR;
                } else if (extraStr.contains("|defineVal|")) {
                    mutability = EXPORT_MUT_VAL;
                } else {
                    continue;
                }
                int[] ops = inst.getOperands();
                if (ops == null || ops.length == 0) {
                    continue;
                }
                String name = constStrings.get(Integer.valueOf(ops[0]));
                if (name != null) {
                    mutabilityByName.put(name, Byte.valueOf(mutability));
                }
            }
        }

        List<ExportSlot> exports = new ArrayList<ExportSlot>();
        for (MirLocal local : mainFunc.getLocals()) {
            String name = local.getName();
            if (name == null || name.startsWith("$")) {
                continue;
            }
            Byte mutability = mutabilityByName.get(name);
            exports.add(new ExportSlot(local.getIndex(), name,
                    mutability != null ? mutability.byteValue() : EXPORT_MUT_DYNAMIC));
        }
        return exports.toArray(new ExportSlot[0]);
    }

    NovaValue executePreparedModule(PreparedModule prepared) {
        if (prepared == null || prepared.mainFunc == null) {
            return NovaNull.UNIT;
        }
        MirFrame frame = acquireFrame(prepared.mainFunc);
        try {
            NovaValue result = executeFrame(frame, -1);
            Environment env = interp.getEnvironment();
            for (ExportSlot slot : prepared.exportSlots) {
                if (slot.localIndex >= frame.locals.length) {
                    continue;
                }
                NovaValue value = frame.getOrNull(slot.localIndex);
                if (value == null) {
                    continue;
                }
                switch (slot.mutability) {
                    case EXPORT_MUT_VAR:
                        env.redefine(slot.name, value, true);
                        break;
                    case EXPORT_MUT_VAL:
                        env.redefine(slot.name, value, false);
                        break;
                    default:
                        if (env.contains(slot.name)) {
                            env.redefine(slot.name, value, !env.isVal(slot.name));
                        } else {
                            env.redefine(slot.name, value, false);
                        }
                        break;
                }
            }
            return result;
        } finally {
            releaseFrame(frame);
        }
    }

    NovaValue executeModule(MirModule module) {
        return executePreparedModule(prepareModule(module));
    }

    // ============ 帧池化 ============

    private MirFrame acquireFrame(MirFunction func) {
        int needed = func.getFrameSize();
        if (framePoolTop > 0) {
            MirFrame f = framePool[--framePoolTop];
            if (f.locals.length >= needed) {
                f.reset(func);
                return f;
            }
        }
        return new MirFrame(func);
    }

    private void releaseFrame(MirFrame frame) {
        if (framePoolTop < framePool.length) {
            framePool[framePoolTop++] = frame;
        }
    }

    /**
     * 快速调用路径：跳过 MirCallable.callDirect 的所有间接开销。
     * 仅用于无 this/无捕获/非 init 的简单函数（如顶层函数递归）。
     * <ul>
     *   <li>直接从调用者帧复制参数（传播 RAW_INT_MARKER，零装箱）</li>
     *   <li>帧池化（消除 NovaValue[] + long[] 分配）</li>
     *   <li>callStack 轻量推入（空参帧，异常时惰性补充参数值）</li>
     *   <li>使用缓存的 maxRecursionDepth（避免虚方法链）</li>
     * </ul>
     */
    NovaValue fastCall(MirFrame callerFrame, MirFunction targetFunc, MirInst inst) {
        if (cachedMaxRecursionDepth > 0 && interp.callDepth >= cachedMaxRecursionDepth) {
            throw new NovaRuntimeException("Maximum recursion depth exceeded (" + cachedMaxRecursionDepth + ")");
        }
        interp.callDepth++;
        int[] ops = inst.getOperands();
        int argCount = ops != null ? ops.length : 0;
        MirFrame calleeFrame = null;
        String displayName = targetFunc.getName();
        if ("invoke".equals(displayName)) displayName = "<lambda>";
        interp.callStack.push(getEmptyMirCallFrame(displayName));
        try {
            switch (argCount) {
                case 0:
                    return executeFunction(targetFunc, EMPTY_ARGS);
                case 1:
                    int op0 = ops[0];
                    if (isSingleIntFunction(targetFunc) && callerFrame.locals[op0] == MirFrame.RAW_INT_MARKER) {
                        return executeFunction1RawInt(targetFunc, (int) callerFrame.rawLocals[op0]);
                    }
                    return executeFunction1(targetFunc, callerFrame.get(op0));
                case 2:
                    return executeFunction2(targetFunc, callerFrame.get(ops[0]), callerFrame.get(ops[1]));
                case 3:
                    return executeFunction3(targetFunc, callerFrame.get(ops[0]), callerFrame.get(ops[1]), callerFrame.get(ops[2]));
                default:
                    calleeFrame = acquireFrame(targetFunc);
                    for (int i = 0; i < ops.length; i++) {
                        int srcIdx = ops[i];
                        calleeFrame.locals[i] = callerFrame.locals[srcIdx];
                        if (callerFrame.locals[srcIdx] == MirFrame.RAW_INT_MARKER) {
                            calleeFrame.rawLocals[i] = callerFrame.rawLocals[srcIdx];
                        }
                    }
                    return executeFrame(calleeFrame, -1);
            }
        } catch (NovaRuntimeException e) {
            if (e.getNovaStackTrace() == null) {
                // Replace empty frame with full one (with params) for diagnostics
                interp.callStack.pop();
                int paramCount = targetFunc.getParams().size();
                List<NovaValue> paramVals = new ArrayList<>(paramCount);
                if (argCount == 0) {
                    interp.callStack.push(getEmptyMirCallFrame(displayName));
                } else {
                    for (int i = 0; i < paramCount && i < argCount; i++) {
                        paramVals.add(callerFrame.get(ops[i]));
                    }
                    interp.callStack.push(NovaCallFrame.fromMirCallable(displayName, paramVals));
                }
                String trace = interp.captureStackTraceString();
                int tce = calleeFrame != null ? calleeFrame.tceCount : lastTceCount;
                if (tce > 0) {
                    trace += "  ... " + tce + " tail-call frames omitted ...\n";
                    lastTceCount = 0;
                }
                e.setNovaStackTrace(trace);
            }
            throw e;
        } finally {
            interp.callStack.pop();
            if (calleeFrame != null) {
                releaseFrame(calleeFrame);
            }
            interp.callDepth--;
        }
    }

    @FunctionalInterface
    interface BatchOp {
        NovaValue exec(List<NovaValue> elems, int size, BatchCtx c);
    }

    /** 帧复用调用上下文：封装 lambda 调用的底层细节 */
    static final class BatchCtx {
        private final MirFrame frame;
        private final int slot;
        private final MirInterpreter mi;
        final NovaValue extraArg;
        /** lambda 的实际参数数量（不含 this），用于 Map 双参分派 */
        final int lambdaParamCount;

        BatchCtx(MirFrame frame, int slot, MirInterpreter mi, NovaValue extraArg, int lambdaParamCount) {
            this.frame = frame;
            this.slot = slot;
            this.mi = mi;
            this.extraArg = extraArg;
            this.lambdaParamCount = lambdaParamCount;
        }

        /** 单参调用 */
        NovaValue call1(NovaValue arg) {
            frame.locals[slot] = arg;
            frame.currentBlockId = 0;
            frame.pc = 0;
            return mi.executeFrame(frame, -1);
        }

        /** 双参调用 */
        NovaValue call2(NovaValue arg1, NovaValue arg2) {
            frame.locals[slot] = arg1;
            frame.locals[slot + 1] = arg2;
            frame.currentBlockId = 0;
            frame.pc = 0;
            return mi.executeFrame(frame, -1);
        }

        static boolean isTrue(NovaValue v) {
            return v instanceof NovaBoolean && ((NovaBoolean) v).getValue();
        }
    }

    /** 已注册的 NovaList 批量操作（添加新 HOF 只需在此注册一行） */
    private static final Map<String, BatchOp> BATCH_OPS = new HashMap<>();
    static { registerBatchOps(); }

    @SuppressWarnings("unchecked")
    private static void registerBatchOps() {
        // ---- transform ----
        BATCH_OPS.put("map", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>(n);
            for (int i = 0; i < n; i++) r.add(c.call1(elems.get(i)));
            return new NovaList(r);
        });
        BATCH_OPS.put("flatMap", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                NovaValue v = c.call1(elems.get(i));
                if (v instanceof NovaList) r.addAll(((NovaList) v).getElements());
            }
            return new NovaList(r);
        });
        BATCH_OPS.put("mapNotNull", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                NovaValue v = c.call1(elems.get(i));
                if (!v.isNull()) r.add(v);
            }
            return new NovaList(r);
        });
        // ---- predicate-filter ----
        BATCH_OPS.put("filter", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); if (BatchCtx.isTrue(c.call1(e))) r.add(e); }
            return new NovaList(r);
        });
        BATCH_OPS.put("filterNot", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); if (!BatchCtx.isTrue(c.call1(e))) r.add(e); }
            return new NovaList(r);
        });
        BATCH_OPS.put("takeWhile", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); if (!BatchCtx.isTrue(c.call1(e))) break; r.add(e); }
            return new NovaList(r);
        });
        BATCH_OPS.put("dropWhile", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>(); boolean d = true;
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); if (d && BatchCtx.isTrue(c.call1(e))) continue; d = false; r.add(e); }
            return new NovaList(r);
        });
        BATCH_OPS.put("partition", (elems, n, c) -> {
            List<NovaValue> a = new ArrayList<>(), b = new ArrayList<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); (BatchCtx.isTrue(c.call1(e)) ? a : b).add(e); }
            return new NovaPair(new NovaList(a), new NovaList(b));
        });
        // ---- predicate-check (short-circuit) ----
        BATCH_OPS.put("any", (elems, n, c) -> {
            for (int i = 0; i < n; i++) if (BatchCtx.isTrue(c.call1(elems.get(i)))) return NovaBoolean.TRUE;
            return NovaBoolean.FALSE;
        });
        BATCH_OPS.put("all", (elems, n, c) -> {
            for (int i = 0; i < n; i++) if (!BatchCtx.isTrue(c.call1(elems.get(i)))) return NovaBoolean.FALSE;
            return NovaBoolean.TRUE;
        });
        BATCH_OPS.put("none", (elems, n, c) -> {
            for (int i = 0; i < n; i++) if (BatchCtx.isTrue(c.call1(elems.get(i)))) return NovaBoolean.FALSE;
            return NovaBoolean.TRUE;
        });
        // ---- predicate-find (short-circuit) ----
        BATCH_OPS.put("find", (elems, n, c) -> {
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); if (BatchCtx.isTrue(c.call1(e))) return e; }
            return NovaNull.NULL;
        });
        BATCH_OPS.put("findLast", (elems, n, c) -> {
            for (int i = n - 1; i >= 0; i--) { NovaValue e = elems.get(i); if (BatchCtx.isTrue(c.call1(e))) return e; }
            return NovaNull.NULL;
        });
        // ---- aggregate ----
        BATCH_OPS.put("count", (elems, n, c) -> {
            int cnt = 0; for (int i = 0; i < n; i++) if (BatchCtx.isTrue(c.call1(elems.get(i)))) cnt++;
            return NovaInt.of(cnt);
        });
        BATCH_OPS.put("sumBy", (elems, n, c) -> {
            long sum = 0; for (int i = 0; i < n; i++) sum += c.call1(elems.get(i)).asInt();
            return NovaInt.of((int) sum);
        });
        // ---- action ----
        BATCH_OPS.put("forEach", (elems, n, c) -> {
            for (int i = 0; i < n; i++) {
                try {
                    c.call1(elems.get(i));
                } catch (LoopSignal sig) {
                    if (sig == LoopSignal.BREAK) break;
                    // CONTINUE: 跳过当前，继续下一次
                }
            }
            return NovaNull.UNIT;
        });
        // ---- key-based ----
        BATCH_OPS.put("sortedBy", (elems, n, c) -> {
            NovaValue[] keys = new NovaValue[n];
            for (int i = 0; i < n; i++) keys[i] = c.call1(elems.get(i));
            Integer[] idx = new Integer[n];
            for (int i = 0; i < n; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> ((Comparable) keys[a].toJavaValue()).compareTo(keys[b].toJavaValue()));
            List<NovaValue> r = new ArrayList<>(n);
            for (int j : idx) r.add(elems.get(j));
            return new NovaList(r);
        });
        BATCH_OPS.put("groupBy", (elems, n, c) -> {
            Map<NovaValue, List<NovaValue>> groups = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); groups.computeIfAbsent(c.call1(e), k -> new ArrayList<>()).add(e); }
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (Map.Entry<NovaValue, List<NovaValue>> entry : groups.entrySet()) r.put(entry.getKey(), new NovaList(entry.getValue()));
            return new NovaMap(r);
        });
        BATCH_OPS.put("associateBy", (elems, n, c) -> {
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); r.put(c.call1(e), e); }
            return new NovaMap(r);
        });
        BATCH_OPS.put("associateWith", (elems, n, c) -> {
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); r.put(e, c.call1(e)); }
            return new NovaMap(r);
        });
        BATCH_OPS.put("maxBy", (elems, n, c) -> {
            if (n == 0) return NovaNull.NULL;
            NovaValue best = elems.get(0), bestKey = c.call1(best);
            for (int i = 1; i < n; i++) { NovaValue e = elems.get(i), k = c.call1(e); if (((Comparable) k.toJavaValue()).compareTo(bestKey.toJavaValue()) > 0) { best = e; bestKey = k; } }
            return best;
        });
        BATCH_OPS.put("minBy", (elems, n, c) -> {
            if (n == 0) return NovaNull.NULL;
            NovaValue best = elems.get(0), bestKey = c.call1(best);
            for (int i = 1; i < n; i++) { NovaValue e = elems.get(i), k = c.call1(e); if (((Comparable) k.toJavaValue()).compareTo(bestKey.toJavaValue()) < 0) { best = e; bestKey = k; } }
            return best;
        });
        // ---- Function2: fold/reduce ----
        BATCH_OPS.put("fold", (elems, n, c) -> {
            NovaValue acc = c.extraArg; for (int i = 0; i < n; i++) acc = c.call2(acc, elems.get(i)); return acc;
        });
        BATCH_OPS.put("reduce", (elems, n, c) -> {
            NovaValue acc = c.extraArg; for (int i = 0; i < n; i++) acc = c.call2(acc, elems.get(i)); return acc;
        });
        BATCH_OPS.put("foldRight", (elems, n, c) -> {
            NovaValue acc = c.extraArg; for (int i = n - 1; i >= 0; i--) acc = c.call2(elems.get(i), acc); return acc;
        });
        // ---- Function2: indexed ----
        BATCH_OPS.put("mapIndexed", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>(n);
            for (int i = 0; i < n; i++) r.add(c.call2(NovaInt.of(i), elems.get(i)));
            return new NovaList(r);
        });
        BATCH_OPS.put("filterIndexed", (elems, n, c) -> {
            List<NovaValue> r = new ArrayList<>();
            for (int i = 0; i < n; i++) { NovaValue e = elems.get(i); if (BatchCtx.isTrue(c.call2(NovaInt.of(i), e))) r.add(e); }
            return new NovaList(r);
        });
        BATCH_OPS.put("forEachIndexed", (elems, n, c) -> {
            for (int i = 0; i < n; i++) {
                try {
                    c.call2(NovaInt.of(i), elems.get(i));
                } catch (LoopSignal sig) {
                    if (sig == LoopSignal.BREAK) break;
                }
            }
            return NovaNull.UNIT;
        });
    }

    // ==================== Map 批量操作 ====================

    /** Map 批量操作函数式接口：直接操作 NovaMap，各 op 自行迭代 */
    @FunctionalInterface
    interface MapBatchOp {
        /** @return 结果，或 null 表示此 op 不适用（例如参数数量不匹配）→ 回退到 stdlib */
        NovaValue exec(NovaMap map, BatchCtx c);
    }

    private static final Map<String, MapBatchOp> MAP_BATCH_OPS = new HashMap<>();
    static { registerMapBatchOps(); }

    @SuppressWarnings("unchecked")
    private static void registerMapBatchOps() {
        // ---- 双参方法（2-param only, 1-param 回退到 stdlib bridge 处理 entry/implicit-it） ----
        MAP_BATCH_OPS.put("forEach", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet()) {
                try {
                    c.call2(e.getKey(), e.getValue());
                } catch (LoopSignal sig) {
                    if (sig == LoopSignal.BREAK) break;
                }
            }
            return NovaNull.UNIT;
        });
        MAP_BATCH_OPS.put("filter", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (BatchCtx.isTrue(c.call2(e.getKey(), e.getValue()))) r.put(e.getKey(), e.getValue());
            return new NovaMap(r);
        });
        MAP_BATCH_OPS.put("map", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            List<NovaValue> r = new ArrayList<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet()) r.add(c.call2(e.getKey(), e.getValue()));
            return new NovaList(r);
        });
        MAP_BATCH_OPS.put("flatMap", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            List<NovaValue> r = new ArrayList<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet()) {
                NovaValue v = c.call2(e.getKey(), e.getValue());
                if (v instanceof NovaList) r.addAll(((NovaList) v).getElements());
            }
            return new NovaList(r);
        });
        MAP_BATCH_OPS.put("any", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (BatchCtx.isTrue(c.call2(e.getKey(), e.getValue()))) return NovaBoolean.TRUE;
            return NovaBoolean.FALSE;
        });
        MAP_BATCH_OPS.put("all", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (!BatchCtx.isTrue(c.call2(e.getKey(), e.getValue()))) return NovaBoolean.FALSE;
            return NovaBoolean.TRUE;
        });
        MAP_BATCH_OPS.put("none", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (BatchCtx.isTrue(c.call2(e.getKey(), e.getValue()))) return NovaBoolean.FALSE;
            return NovaBoolean.TRUE;
        });
        MAP_BATCH_OPS.put("count", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            int cnt = 0;
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (BatchCtx.isTrue(c.call2(e.getKey(), e.getValue()))) cnt++;
            return NovaInt.of(cnt);
        });
        MAP_BATCH_OPS.put("mapKeys", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                r.put(c.call2(e.getKey(), e.getValue()), e.getValue());
            return new NovaMap(r);
        });
        MAP_BATCH_OPS.put("mapValues", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                r.put(e.getKey(), c.call2(e.getKey(), e.getValue()));
            return new NovaMap(r);
        });
        // ---- 单参方法（任意参数数量均可） ----
        MAP_BATCH_OPS.put("filterKeys", (map, c) -> {
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (BatchCtx.isTrue(c.call1(e.getKey()))) r.put(e.getKey(), e.getValue());
            return new NovaMap(r);
        });
        MAP_BATCH_OPS.put("filterValues", (map, c) -> {
            Map<NovaValue, NovaValue> r = new java.util.LinkedHashMap<>();
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet())
                if (BatchCtx.isTrue(c.call1(e.getValue()))) r.put(e.getKey(), e.getValue());
            return new NovaMap(r);
        });
    }

    /**
     * NovaMap 高阶方法帧复用批量执行。
     * <p>双参方法（forEach/filter/map/...）仅处理 2-param lambda，1-param 回退到 stdlib bridge。
     * 单参方法（filterKeys/filterValues）直接处理。
     *
     * @return 结果，或 null（未注册/参数不匹配）
     */
    NovaValue batchExecMap(NovaMap map, String methodName, NovaValue extraArg, MirCallable lambda) {
        MapBatchOp op = MAP_BATCH_OPS.get(methodName);
        if (op == null) return null;

        MirFunction func = lambda.getFunction();
        List<MirLocal> funcLocals = func.getLocals();
        boolean hasThis = !funcLocals.isEmpty() && "this".equals(funcLocals.get(0).getName());
        int slot = hasThis ? 1 : 0;

        MirFrame frame = acquireFrame(func);
        if (hasThis) frame.locals[0] = lambda;

        String funcName = func.getName();
        String displayName = "invoke".equals(funcName) ? "<lambda>" : funcName;
        interp.callStack.push(getEmptyMirCallFrame(displayName));
        interp.callDepth++;

        try {
            BatchCtx ctx = new BatchCtx(frame, slot, this, extraArg, func.getParams().size());
            return op.exec(map, ctx);
        } catch (NovaRuntimeException e) {
            e.setNovaStackTrace(interp.captureStackTraceString());
            throw e;
        } finally {
            interp.callDepth--;
            interp.callStack.pop();
            releaseFrame(frame);
        }
    }

    /**
     * NovaList 高阶方法帧复用批量执行（统一入口）。
     * <p>根据 BATCH_OPS 注册表分派。添加新 HOF 只需注册一行。
     *
     * @param extraArg fold/reduce 的初始值，其他方法传 null
     * @return 操作结果，或 null 如果 methodName 未注册
     */
    NovaValue batchExec(NovaList list, String methodName, NovaValue extraArg, MirCallable lambda) {
        BatchOp op = BATCH_OPS.get(methodName);
        if (op == null) return null;

        MirFunction func = lambda.getFunction();
        List<MirLocal> funcLocals = func.getLocals();
        boolean hasThis = !funcLocals.isEmpty() && "this".equals(funcLocals.get(0).getName());
        int slot = hasThis ? 1 : 0;

        MirFrame frame = acquireFrame(func);
        if (hasThis) frame.locals[0] = lambda;

        String funcName = func.getName();
        String displayName = "invoke".equals(funcName) ? "<lambda>" : funcName;
        interp.callStack.push(getEmptyMirCallFrame(displayName));
        interp.callDepth++;

        try {
            return op.exec(list.getElements(), list.getElements().size(),
                    new BatchCtx(frame, slot, this, extraArg, func.getParams().size()));
        } catch (NovaRuntimeException e) {
            e.setNovaStackTrace(interp.captureStackTraceString());
            throw e;
        } finally {
            interp.callDepth--;
            interp.callStack.pop();
            releaseFrame(frame);
        }
    }

    // ============ 函数执行 ============

    private NovaValue getMemoizedResult(MirFunction func, MemoKey key) {
        if (!func.isMemoized()) {
            return null;
        }
        Object cached = func.getMemoCache().get(key);
        return cached instanceof NovaValue ? (NovaValue) cached : null;
    }

    private void putMemoizedResult(MirFunction func, MemoKey key, NovaValue result) {
        if (func.isMemoized() && result != null) {
            func.getMemoCache().put(key, result);
        }
    }

    private boolean isSingleIntFunction(MirFunction func) {
        return func.getParams().size() == 1
                && func.getParams().get(0).getType().getKind() == MirType.Kind.INT;
    }

    private NovaValue getMemoizedIntResult(MirFunction func, int arg) {
        if (!func.isMemoized() || !isSingleIntFunction(func)) {
            return null;
        }
        Object cached = func.getIntMemoized(arg);
        return cached instanceof NovaValue ? (NovaValue) cached : null;
    }

    private void putMemoizedIntResult(MirFunction func, int arg, NovaValue result) {
        if (func.isMemoized() && isSingleIntFunction(func) && result != null) {
            func.putIntMemoized(arg, result);
        }
    }

    private NovaValue executeFunction1RawInt(MirFunction func, int value) {
        NovaValue cached = getMemoizedIntResult(func, value);
        if (cached != null) {
            return cached;
        }
        MirFrame frame = acquireFrame(func);
        frame.locals[0] = MirFrame.RAW_INT_MARKER;
        frame.rawLocals[0] = value;
        try {
            NovaValue result = executeFrame(frame, -1);
            putMemoizedIntResult(func, value, result);
            return result;
        } finally {
            releaseFrame(frame);
        }
    }

    NovaValue executeFunction1(MirFunction func, NovaValue a0) {
        if (isSingleIntFunction(func) && a0 != null && a0.isInt()) {
            return executeFunction1RawInt(func, a0.asInt());
        }
        MemoKey memoKey = func.isMemoized() ? MemoKey.of1(a0) : null;
        NovaValue cached = memoKey != null ? getMemoizedResult(func, memoKey) : null;
        if (cached != null) return cached;
        MirFrame frame = acquireFrame(func);
        frame.locals[0] = a0;
        try {
            NovaValue result = executeFrame(frame, -1);
            if (memoKey != null) putMemoizedResult(func, memoKey, result);
            return result;
        } finally {
            releaseFrame(frame);
        }
    }

    NovaValue executeFunction2(MirFunction func, NovaValue a0, NovaValue a1) {
        MemoKey memoKey = func.isMemoized() ? MemoKey.of2(a0, a1) : null;
        NovaValue cached = memoKey != null ? getMemoizedResult(func, memoKey) : null;
        if (cached != null) return cached;
        MirFrame frame = acquireFrame(func);
        frame.locals[0] = a0;
        frame.locals[1] = a1;
        try {
            NovaValue result = executeFrame(frame, -1);
            if (memoKey != null) putMemoizedResult(func, memoKey, result);
            return result;
        } finally {
            releaseFrame(frame);
        }
    }

    NovaValue executeFunction3(MirFunction func, NovaValue a0, NovaValue a1, NovaValue a2) {
        MemoKey memoKey = func.isMemoized() ? MemoKey.of3(a0, a1, a2) : null;
        NovaValue cached = memoKey != null ? getMemoizedResult(func, memoKey) : null;
        if (cached != null) return cached;
        MirFrame frame = acquireFrame(func);
        frame.locals[0] = a0;
        frame.locals[1] = a1;
        frame.locals[2] = a2;
        try {
            NovaValue result = executeFrame(frame, -1);
            if (memoKey != null) putMemoizedResult(func, memoKey, result);
            return result;
        } finally {
            releaseFrame(frame);
        }
    }

    NovaValue executeFunction(MirFunction func, NovaValue[] args) {
        MemoKey memoKey = func.isMemoized() ? MemoKey.ofArray(args) : null;
        NovaValue cached = memoKey != null ? getMemoizedResult(func, memoKey) : null;
        if (cached != null) return cached;
        MirFrame frame = acquireFrame(func);
        // 参数绑定：前 N 个 locals = args
        for (int i = 0; i < args.length && i < frame.locals.length; i++) {
            frame.locals[i] = args[i];
        }
        try {
        // 绑定 reified 类型参数到栈帧
        if (pendingReifiedTypeArgs != null) {
            List<String> typeParams = func.getTypeParams();
            if (!typeParams.isEmpty()) {
                Map<String, String> reifiedMap = new HashMap<>();
                for (int i = 0; i < Math.min(pendingReifiedTypeArgs.length, typeParams.size()); i++) {
                    reifiedMap.put(typeParams.get(i), pendingReifiedTypeArgs[i]);
                }
                frame.reifiedTypes = reifiedMap;
                // 绑定 __reified_T 局部变量（供 T::class 引用使用）
                for (Map.Entry<String, String> entry : reifiedMap.entrySet()) {
                    String localName = "__reified_" + entry.getKey();
                    for (MirLocal local : func.getLocals()) {
                        if (localName.equals(local.getName()) && local.getIndex() < frame.locals.length) {
                            frame.locals[local.getIndex()] = NovaString.of(entry.getValue());
                            break;
                        }
                    }
                }
            }
            pendingReifiedTypeArgs = null;
        }

        // 次级构造器委托：delegation 信息作为元数据存储在 MirFunction 中
        if (func.hasDelegation()) {
            // block 0 = delegation args 专用块（由 lowerConstructor 重排到 position 0）
            // block 1+ = 构造器 body（可能为空）
            List<BasicBlock> blocks = func.getBlocks();
            if (!blocks.isEmpty()) {
                for (MirInst inst : blocks.get(0).getInstructions()) {
                    executeInst(frame, inst);
                }
            }
            // 读取委托参数
            int[] delegLocals = func.getDelegationArgLocals();
            NovaValue thisObj = frame.locals[0];
            NovaValue[] delegArgs = new NovaValue[delegLocals.length];
            for (int i = 0; i < delegLocals.length; i++) {
                delegArgs[i] = delegLocals[i] < frame.locals.length ? frame.get(delegLocals[i]) : NovaNull.NULL;
            }
            // 调用目标构造器（主构造器或其他次级构造器）
            if (thisObj instanceof NovaObject) {
                NovaClass cls = ((NovaObject) thisObj).getNovaClass();
                NovaCallable ctor = cls.getConstructorByArity(delegArgs.length);
                // 跳过自身（避免递归）
                if (ctor instanceof MirCallable && ((MirCallable) ctor).getFunction() == func) {
                    ctor = null;
                    for (NovaCallable c : cls.getHirConstructors()) {
                        if (c instanceof MirCallable && ((MirCallable) c).getFunction() == func) continue;
                        if (c.getArity() == delegArgs.length || c.getArity() == -1) { ctor = c; break; }
                    }
                }
                if (ctor != null) {
                    if (ctor instanceof MirCallable) {
                        NovaValue[] allArgs = new NovaValue[1 + delegArgs.length];
                        allArgs[0] = thisObj;
                        System.arraycopy(delegArgs, 0, allArgs, 1, delegArgs.length);
                        ((MirCallable) ctor).callDirect(interp, allArgs);
                    } else {
                        NovaBoundMethod bound = new NovaBoundMethod(thisObj, ctor);
                        interp.executeBoundMethod(bound, Arrays.asList(delegArgs), null);
                    }
                }
            }
            // 执行委托后的构造器 body（block 1+）
            if (blocks.size() > 1) {
                executeFrame(frame, blocks.get(1).getId());
            }
            return thisObj;
        }

        // 超类构造器调用：superInitArgLocals 作为元数据存储在 MirFunction 中
        if (func.hasSuperInitArgs()) {
            // 执行 entry block 的 CONST 指令来初始化合成局部变量
            List<BasicBlock> blocks = func.getBlocks();
            int[] superLocals = func.getSuperInitArgLocals();
            if (!blocks.isEmpty()) {
                int paramCount = func.getParams().size() + 1;
                Set<Integer> needed = new java.util.HashSet<>();
                for (int l : superLocals) {
                    if (l >= paramCount) needed.add(l);
                }
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (MirInst inst : blocks.get(0).getInstructions()) {
                        if (needed.contains(inst.getDest()) && inst.getOperands() != null) {
                            for (int op : inst.getOperands()) {
                                if (op >= paramCount && needed.add(op)) changed = true;
                            }
                        }
                    }
                }
                for (MirInst inst : blocks.get(0).getInstructions()) {
                    if (needed.contains(inst.getDest())) {
                        executeInst(frame, inst);
                    }
                }
            }
            NovaValue thisObj = frame.locals[0];
            List<NovaValue> superArgs = new ArrayList<>(superLocals.length);
            for (int localIdx : superLocals) {
                superArgs.add(localIdx < frame.locals.length ? frame.get(localIdx) : NovaNull.NULL);
            }
            if (thisObj instanceof NovaObject) {
                // 使用 MirFunction 上记录的超类名（而非 thisObj.getNovaClass()），
                // 避免继承链中 B→A 查找时误用具体类 C 的超类导致无限递归
                NovaClass superclass = null;
                String superName = func.getSuperClassName();
                if (superName != null) {
                    NovaValue superVal = interp.getEnvironment().tryGet(superName);
                    if (superVal instanceof NovaClass) {
                        superclass = (NovaClass) superVal;
                    }
                }
                if (superclass == null) {
                    // 回退：从运行时对象获取（仅在无 superClassName 元数据时）
                    superclass = ((NovaObject) thisObj).getNovaClass().getSuperclass();
                }
                if (superclass != null) {
                    for (NovaCallable ctor : superclass.getHirConstructors()) {
                        if (ctor.getArity() == superArgs.size() || ctor.getArity() == -1) {
                            NovaBoundMethod bound = new NovaBoundMethod(thisObj, ctor);
                            interp.executeBoundMethod(bound, superArgs, null);
                            break;
                        }
                    }
                }
            }
            // 继续执行当前构造器体（SET_FIELD 等）
        }

        NovaValue result = executeFrame(frame, -1);
        if (memoKey != null) putMemoizedResult(func, memoKey, result);
        return result;
        } finally {
            releaseFrame(frame);
        }
    }

    private boolean isTailIntLoopCandidate(MirFunction func) {
        return func.getParams().size() == 2
                && func.getReturnType().getKind() == MirType.Kind.INT
                && func.getParams().get(0).getType().getKind() == MirType.Kind.INT
                && func.getParams().get(1).getType().getKind() == MirType.Kind.INT
                && func.getTryCatchEntries().isEmpty();
    }

    private TailIntLoopPlan resolveTailIntLoopPlan(MirFunction func, BasicBlock[] blockArr) {
        Object cached = tailIntLoopPlanCache.get(func);
        if (cached == NO_TAIL_INT_LOOP_PLAN) {
            return null;
        }
        if (cached instanceof TailIntLoopPlan) {
            return (TailIntLoopPlan) cached;
        }
        TailIntLoopPlan plan = detectTailIntLoopPlan(func, blockArr);
        tailIntLoopPlanCache.put(func, plan != null ? plan : NO_TAIL_INT_LOOP_PLAN);
        return plan;
    }

    private TailIntLoopPlan detectTailIntLoopPlan(MirFunction func, BasicBlock[] blockArr) {
        if (func.getParams().size() != 2
                || func.getReturnType().getKind() != MirType.Kind.INT
                || func.getParams().get(0).getType().getKind() != MirType.Kind.INT
                || func.getParams().get(1).getType().getKind() != MirType.Kind.INT
                || !func.getTryCatchEntries().isEmpty()) {
            return null;
        }
        int entryBlockId = func.getBodyStartBlockId() > 0
                ? func.getBodyStartBlockId()
                : func.getBlocks().get(0).getId();
        if (entryBlockId < 0 || entryBlockId >= blockArr.length) {
            return null;
        }
        BasicBlock entryBlock = blockArr[entryBlockId];
        if (entryBlock == null || !(entryBlock.getTerminator() instanceof MirTerminator.Branch)) {
            return null;
        }
        MirTerminator.Branch branch = (MirTerminator.Branch) entryBlock.getTerminator();
        BinaryOp exitCompareOp = branch.getFusedCmpOp();
        if (exitCompareOp == null) {
            return null;
        }
        int constLocal;
        if (branch.getFusedLeft() == 0) {
            constLocal = branch.getFusedRight();
        } else if (branch.getFusedRight() == 0) {
            constLocal = branch.getFusedLeft();
            exitCompareOp = reverseCompare(exitCompareOp);
        } else {
            return null;
        }
        Integer stopValue = findConstInt(entryBlock.getInstArray(), constLocal);
        if (stopValue == null) {
            return null;
        }
        TailIntLoopPlan plan = buildTailIntLoopPlan(entryBlockId, exitCompareOp, true,
                stopValue.intValue(), branch.getThenBlock(), branch.getElseBlock(), blockArr);
        if (plan != null) {
            return plan;
        }
        return buildTailIntLoopPlan(entryBlockId, exitCompareOp, false,
                stopValue.intValue(), branch.getElseBlock(), branch.getThenBlock(), blockArr);
    }

    private TailIntLoopPlan buildTailIntLoopPlan(int entryBlockId, BinaryOp exitCompareOp,
                                                 boolean exitOnTrue, int stopValue,
                                                 int exitBlockId, int bodyBlockId,
                                                 BasicBlock[] blockArr) {
        if (exitBlockId < 0 || exitBlockId >= blockArr.length
                || bodyBlockId < 0 || bodyBlockId >= blockArr.length) {
            return null;
        }
        BasicBlock exitBlock = blockArr[exitBlockId];
        BasicBlock bodyBlock = blockArr[bodyBlockId];
        if (exitBlock == null || bodyBlock == null) {
            return null;
        }
        Integer returnLocal = resolveTailReturnLocal(exitBlock, 1);
        if (returnLocal == null) {
            return null;
        }
        TailAccSpec accSpec = resolveTailAccSpec(bodyBlock, entryBlockId);
        if (accSpec == null) {
            return null;
        }
        Integer counterDelta = resolveTailCounterDelta(bodyBlock);
        if (counterDelta == null) {
            return null;
        }
        return new TailIntLoopPlan(entryBlockId, exitCompareOp, exitOnTrue, stopValue,
                returnLocal.intValue(), counterDelta.intValue(), accSpec.op,
                accSpec.rhsKind, accSpec.rhsConst);
    }

    private Integer resolveTailReturnLocal(BasicBlock exitBlock, int maxParamIndex) {
        MirTerminator term = exitBlock.getTerminator();
        if (!(term instanceof MirTerminator.Return)) {
            return null;
        }
        int returnLocal = ((MirTerminator.Return) term).getValueLocal();
        MirInst[] insts = exitBlock.getInstArray();
        if (insts.length == 0) {
            return returnLocal >= 0 && returnLocal <= maxParamIndex ? Integer.valueOf(returnLocal) : null;
        }
        if (insts.length == 1 && insts[0].getOp() == MirOp.MOVE && insts[0].getDest() == returnLocal) {
            int src = insts[0].operand(0);
            return src >= 0 && src <= maxParamIndex ? Integer.valueOf(src) : null;
        }
        return null;
    }

    private Integer resolveTailCounterDelta(BasicBlock bodyBlock) {
        MirInst[] insts = bodyBlock.getInstArray();
        Map<Integer, Integer> constInts = collectConstInts(insts);
        MirInst counterBinary = findCounterUpdate(insts, constInts);
        if (counterBinary == null || !writesLocal(insts, counterBinary.getDest(), 0)) {
            return null;
        }
        return extractCounterDelta(counterBinary, constInts);
    }

    private TailAccSpec resolveTailAccSpec(BasicBlock bodyBlock, int entryBlockId) {
        MirTerminator term = bodyBlock.getTerminator();
        if (!(term instanceof MirTerminator.TailCall)
                || ((MirTerminator.TailCall) term).getEntryBlockId() != entryBlockId) {
            return null;
        }
        MirInst[] insts = bodyBlock.getInstArray();
        Map<Integer, Integer> constInts = collectConstInts(insts);
        MirInst counterBinary = findCounterUpdate(insts, constInts);
        if (counterBinary == null) {
            return null;
        }
        MirInst accBinary = null;
        for (MirInst inst : insts) {
            if (inst == counterBinary || inst.getOp() != MirOp.BINARY) {
                continue;
            }
            TailAccSpec spec = extractAccSpec(inst, constInts, counterBinary.getDest());
            if (spec != null) {
                if (accBinary != null) {
                    return null;
                }
                accBinary = inst;
            }
        }
        if (accBinary == null || !writesLocal(insts, accBinary.getDest(), 1)) {
            return null;
        }
        TailAccSpec accSpec = extractAccSpec(accBinary, constInts, counterBinary.getDest());
        if (accSpec == null) {
            return null;
        }
        for (MirInst inst : insts) {
            MirOp op = inst.getOp();
            if (op == MirOp.CONST_INT) {
                continue;
            }
            if (inst == counterBinary || inst == accBinary) {
                continue;
            }
            if (op == MirOp.MOVE) {
                int src = inst.operand(0);
                if (inst.getDest() == 0 && src == counterBinary.getDest()) {
                    continue;
                }
                if (inst.getDest() == 1 && src == accBinary.getDest()) {
                    continue;
                }
            }
            return null;
        }
        return accSpec;
    }

    private Map<Integer, Integer> collectConstInts(MirInst[] insts) {
        Map<Integer, Integer> constInts = new HashMap<Integer, Integer>();
        for (MirInst inst : insts) {
            if (inst.getOp() == MirOp.CONST_INT) {
                constInts.put(Integer.valueOf(inst.getDest()), Integer.valueOf(inst.extraInt));
            }
        }
        return constInts;
    }

    private MirInst findCounterUpdate(MirInst[] insts, Map<Integer, Integer> constInts) {
        MirInst counterBinary = null;
        for (MirInst inst : insts) {
            if (inst.getOp() != MirOp.BINARY) {
                continue;
            }
            if (extractCounterDelta(inst, constInts) != null) {
                if (counterBinary != null) {
                    return null;
                }
                counterBinary = inst;
            }
        }
        return counterBinary;
    }

    private Integer extractCounterDelta(MirInst inst, Map<Integer, Integer> constInts) {
        BinaryOp op = inst.extraAs();
        if (op != BinaryOp.ADD && op != BinaryOp.SUB) {
            return null;
        }
        int left = inst.operand(0);
        int right = inst.operand(1);
        Integer rightConst = constInts.get(Integer.valueOf(right));
        if (left == 0 && rightConst != null) {
            return Integer.valueOf(op == BinaryOp.ADD ? rightConst.intValue() : -rightConst.intValue());
        }
        Integer leftConst = constInts.get(Integer.valueOf(left));
        if (right == 0 && leftConst != null && op == BinaryOp.ADD) {
            return leftConst;
        }
        return null;
    }

    private TailAccSpec extractAccSpec(MirInst inst, Map<Integer, Integer> constInts, int counterTempLocal) {
        BinaryOp op = inst.extraAs();
        if ((op != BinaryOp.ADD && op != BinaryOp.SUB) || inst.operand(0) != 1) {
            return null;
        }
        int rhs = inst.operand(1);
        if (rhs == 0) {
            return new TailAccSpec(op, TailIntLoopPlan.ACC_RHS_OLD_COUNTER, 0);
        }
        if (rhs == counterTempLocal) {
            return new TailAccSpec(op, TailIntLoopPlan.ACC_RHS_NEW_COUNTER, 0);
        }
        Integer rhsConst = constInts.get(Integer.valueOf(rhs));
        if (rhsConst != null) {
            return new TailAccSpec(op, TailIntLoopPlan.ACC_RHS_CONST, rhsConst.intValue());
        }
        return null;
    }

    private boolean writesLocal(MirInst[] insts, int sourceLocal, int targetLocal) {
        if (sourceLocal == targetLocal) {
            return true;
        }
        for (MirInst inst : insts) {
            if (inst.getOp() == MirOp.MOVE && inst.getDest() == targetLocal && inst.operand(0) == sourceLocal) {
                return true;
            }
        }
        return false;
    }

    private Integer findConstInt(MirInst[] insts, int local) {
        for (MirInst inst : insts) {
            if (inst.getOp() == MirOp.CONST_INT && inst.getDest() == local) {
                return Integer.valueOf(inst.extraInt);
            }
        }
        return null;
    }

    private BinaryOp reverseCompare(BinaryOp op) {
        switch (op) {
            case LT:
                return BinaryOp.GT;
            case GT:
                return BinaryOp.LT;
            case LE:
                return BinaryOp.GE;
            case GE:
                return BinaryOp.LE;
            default:
                return op;
        }
    }

    private NovaValue executeTailIntLoopFast(MirFrame frame, TailIntLoopPlan plan) {
        NovaValue[] locals = frame.locals;
        long[] rawLocals = frame.rawLocals;

        long counter;
        NovaValue counterVal = locals[0];
        if (counterVal == MirFrame.RAW_INT_MARKER) {
            counter = rawLocals[0];
        } else if (counterVal instanceof NovaInt) {
            counter = ((NovaInt) counterVal).getValue();
        } else {
            return null;
        }

        long acc;
        NovaValue accVal = locals[1];
        if (accVal == MirFrame.RAW_INT_MARKER) {
            acc = rawLocals[1];
        } else if (accVal instanceof NovaInt) {
            acc = ((NovaInt) accVal).getValue();
        } else {
            return null;
        }

        int tceCount = frame.tceCount;
        while (true) {
            boolean exit = compareTailInt(counter, plan.exitCompareOp, plan.stopValue);
            if (exit == plan.exitOnTrue) {
                rawLocals[0] = counter;
                locals[0] = MirFrame.RAW_INT_MARKER;
                rawLocals[1] = acc;
                locals[1] = MirFrame.RAW_INT_MARKER;
                frame.tceCount = tceCount;
                long result = plan.returnLocal == 0 ? counter : acc;
                return result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE
                        ? NovaInt.of((int) result) : NovaLong.of(result);
            }

            long nextCounter = counter + plan.counterDelta;
            long rhs;
            switch (plan.accRhsKind) {
                case TailIntLoopPlan.ACC_RHS_NEW_COUNTER:
                    rhs = nextCounter;
                    break;
                case TailIntLoopPlan.ACC_RHS_CONST:
                    rhs = plan.accConst;
                    break;
                default:
                    rhs = counter;
                    break;
            }
            long nextAcc = applyTailIntBinary(acc, rhs, plan.accUpdateOp);

            counter = nextCounter;
            acc = nextAcc;
            rawLocals[0] = counter;
            locals[0] = MirFrame.RAW_INT_MARKER;
            rawLocals[1] = acc;
            locals[1] = MirFrame.RAW_INT_MARKER;

            tceCount++;
            frame.tceCount = tceCount;
            if (cachedMaxRecursionDepth > 0 && tceCount >= cachedMaxRecursionDepth) {
                throw new NovaRuntimeException("Maximum recursion depth exceeded (" + cachedMaxRecursionDepth + ")");
            }
            if (interp.hasSecurityLimits) {
                interp.checkLoopLimits();
            }
        }
    }

    private StringAccumLoopPlan resolveStringAccumLoopPlan(MirFunction func) {
        Object cached = stringAccumLoopPlanCache.get(func);
        if (cached == NO_STRING_ACCUM_LOOP_PLAN) {
            return null;
        }
        if (cached instanceof StringAccumLoopPlan) {
            return (StringAccumLoopPlan) cached;
        }
        StringAccumLoopPlan plan = StringAccumLoopPlan.detect(func);
        if (plan != null) {
            STRING_ACCUM_LOOP_PLAN_HITS.incrementAndGet();
        }
        stringAccumLoopPlanCache.put(func, plan != null ? plan : NO_STRING_ACCUM_LOOP_PLAN);
        return plan;
    }

    private int readIntLocal(MirFrame frame, int localIndex) {
        NovaValue slot = frame.locals[localIndex];
        if (slot == MirFrame.RAW_INT_MARKER) {
            return (int) frame.rawLocals[localIndex];
        }
        if (slot instanceof NovaInt) {
            return ((NovaInt) slot).getValue();
        }
        return frame.get(localIndex).asInt();
    }

    private boolean compareStringLoopInt(int left, BinaryOp op, int right) {
        switch (op) {
            case LT:
                return left < right;
            case LE:
                return left <= right;
            case GT:
                return left > right;
            case GE:
                return left >= right;
            case EQ:
                return left == right;
            case NE:
                return left != right;
            default:
                return false;
        }
    }

    private NovaValue executeStringAccumLoopFast(MirFrame frame, StringAccumLoopPlan plan) {
        STRING_ACCUM_LOOP_FAST_HITS.incrementAndGet();
        NovaValue currentString = frame.locals[plan.stringLocal];
        String initial;
        if (currentString == null || currentString == NovaNull.NULL) {
            initial = "";
        } else if (currentString instanceof NovaString) {
            initial = ((NovaString) currentString).getValue();
        } else {
            initial = frame.get(plan.stringLocal).asString();
        }
        StringBuilder sb = new StringBuilder(initial);
        int counter = readIntLocal(frame, plan.counterLocal);
        int limit = readIntLocal(frame, plan.limitLocal);

        while (compareStringLoopInt(counter, plan.compareOp, limit) == plan.loopOnTrue) {
            if (interp.hasSecurityLimits) {
                interp.checkLoopLimits();
            }
            for (StringAccumLoopPlan.AppendPart part : plan.appendParts) {
                switch (part.kind) {
                    case STRING_LITERAL:
                        sb.append(part.stringValue);
                        break;
                    case INT_LOCAL:
                        if (part.localIndex == plan.counterLocal) {
                            sb.append(counter);
                        } else {
                            sb.append(readIntLocal(frame, part.localIndex));
                        }
                        break;
                    case INT_CONST:
                        sb.append(part.intValue);
                        break;
                    case VALUE_LOCAL:
                        sb.append(frame.get(part.localIndex).asString());
                        break;
                    default:
                        throw new NovaRuntimeException("Unsupported string append part: " + part.kind);
                }
            }
            counter += plan.stepValue;
        }

        NovaString finalString = NovaString.of(sb.toString());
        frame.locals[plan.stringLocal] = finalString;
        frame.locals[plan.counterLocal] = MirFrame.RAW_INT_MARKER;
        frame.rawLocals[plan.counterLocal] = counter;
        if (plan.returnKind == StringAccumLoopPlan.ReturnKind.LENGTH) {
            return NovaInt.of(finalString.length());
        }
        return finalString;
    }

    private boolean compareTailInt(long left, BinaryOp op, int right) {
        switch (op) {
            case EQ:
                return left == right;
            case NE:
                return left != right;
            case LT:
                return left < right;
            case GT:
                return left > right;
            case LE:
                return left <= right;
            case GE:
                return left >= right;
            default:
                return false;
        }
    }

    private long applyTailIntBinary(long left, long right, BinaryOp op) {
        switch (op) {
            case ADD:
                return left + right;
            case SUB:
                return left - right;
            default:
                throw new NovaRuntimeException("Unsupported tail int op: " + op);
        }
    }

    private boolean isTailIntLoopCandidate3(MirFunction func) {
        return func.getParams().size() == 3
                && func.getReturnType().getKind() == MirType.Kind.INT
                && func.getParams().get(0).getType().getKind() == MirType.Kind.INT
                && func.getParams().get(1).getType().getKind() == MirType.Kind.INT
                && func.getParams().get(2).getType().getKind() == MirType.Kind.INT
                && func.getTryCatchEntries().isEmpty();
    }

    private TailIntLoopPlan3 resolveTailIntLoopPlan3(MirFunction func, BasicBlock[] blockArr) {
        Object cached = tailIntLoopPlan3Cache.get(func);
        if (cached == NO_TAIL_INT_LOOP_PLAN3) {
            return null;
        }
        if (cached instanceof TailIntLoopPlan3) {
            return (TailIntLoopPlan3) cached;
        }
        TailIntLoopPlan3 plan = detectTailIntLoopPlan3(func, blockArr);
        tailIntLoopPlan3Cache.put(func, plan != null ? plan : NO_TAIL_INT_LOOP_PLAN3);
        return plan;
    }

    private TailIntLoopPlan3 detectTailIntLoopPlan3(MirFunction func, BasicBlock[] blockArr) {
        if (!isTailIntLoopCandidate3(func)) {
            return null;
        }
        int entryBlockId = func.getBodyStartBlockId() > 0
                ? func.getBodyStartBlockId()
                : func.getBlocks().get(0).getId();
        if (entryBlockId < 0 || entryBlockId >= blockArr.length) {
            return null;
        }
        BasicBlock entryBlock = blockArr[entryBlockId];
        if (entryBlock == null || !(entryBlock.getTerminator() instanceof MirTerminator.Branch)) {
            return null;
        }
        MirTerminator.Branch branch = (MirTerminator.Branch) entryBlock.getTerminator();
        BinaryOp exitCompareOp = branch.getFusedCmpOp();
        if (exitCompareOp == null) {
            return null;
        }
        int constLocal;
        if (branch.getFusedLeft() == 0) {
            constLocal = branch.getFusedRight();
        } else if (branch.getFusedRight() == 0) {
            constLocal = branch.getFusedLeft();
            exitCompareOp = reverseCompare(exitCompareOp);
        } else {
            return null;
        }
        Integer stopValue = findConstInt(entryBlock.getInstArray(), constLocal);
        if (stopValue == null) {
            return null;
        }
        TailIntLoopPlan3 plan = buildTailIntLoopPlan3(entryBlockId, exitCompareOp, true,
                stopValue.intValue(), branch.getThenBlock(), branch.getElseBlock(), blockArr);
        if (plan != null) {
            return plan;
        }
        return buildTailIntLoopPlan3(entryBlockId, exitCompareOp, false,
                stopValue.intValue(), branch.getElseBlock(), branch.getThenBlock(), blockArr);
    }

    private TailIntLoopPlan3 buildTailIntLoopPlan3(int entryBlockId, BinaryOp exitCompareOp,
                                                   boolean exitOnTrue, int stopValue,
                                                   int exitBlockId, int bodyBlockId,
                                                   BasicBlock[] blockArr) {
        if (exitBlockId < 0 || exitBlockId >= blockArr.length
                || bodyBlockId < 0 || bodyBlockId >= blockArr.length) {
            return null;
        }
        BasicBlock exitBlock = blockArr[exitBlockId];
        BasicBlock bodyBlock = blockArr[bodyBlockId];
        if (exitBlock == null || bodyBlock == null) {
            return null;
        }
        Integer returnLocal = resolveTailReturnLocal(exitBlock, 2);
        if (returnLocal == null) {
            return null;
        }
        MirTerminator term = bodyBlock.getTerminator();
        if (!(term instanceof MirTerminator.TailCall)
                || ((MirTerminator.TailCall) term).getEntryBlockId() != entryBlockId) {
            return null;
        }
        Map<Integer, IntExpr> exprs = new HashMap<Integer, IntExpr>();
        exprs.put(Integer.valueOf(0), IntExpr.param(0));
        exprs.put(Integer.valueOf(1), IntExpr.param(1));
        exprs.put(Integer.valueOf(2), IntExpr.param(2));
        for (MirInst inst : bodyBlock.getInstArray()) {
            switch (inst.getOp()) {
                case CONST_INT:
                    exprs.put(Integer.valueOf(inst.getDest()), IntExpr.constant(inst.extraInt));
                    break;
                case MOVE: {
                    IntExpr src = exprs.get(Integer.valueOf(inst.operand(0)));
                    if (src == null) {
                        return null;
                    }
                    exprs.put(Integer.valueOf(inst.getDest()), src);
                    break;
                }
                case BINARY: {
                    BinaryOp op = inst.extraAs();
                    if (op != BinaryOp.ADD && op != BinaryOp.SUB) {
                        return null;
                    }
                    IntExpr left = exprs.get(Integer.valueOf(inst.operand(0)));
                    IntExpr right = exprs.get(Integer.valueOf(inst.operand(1)));
                    if (left == null || right == null) {
                        return null;
                    }
                    exprs.put(Integer.valueOf(inst.getDest()), IntExpr.binary(op, left, right));
                    break;
                }
                default:
                    return null;
            }
        }
        IntExpr counterExpr = exprs.get(Integer.valueOf(0));
        IntExpr update1 = exprs.get(Integer.valueOf(1));
        IntExpr update2 = exprs.get(Integer.valueOf(2));
        if (counterExpr == null || update1 == null || update2 == null) {
            return null;
        }
        Integer counterDelta = extractCounterDelta(counterExpr);
        if (counterDelta == null) {
            return null;
        }
        return new TailIntLoopPlan3(entryBlockId, exitCompareOp, exitOnTrue, stopValue,
                returnLocal.intValue(), counterDelta.intValue(), update1, update2);
    }

    private Integer extractCounterDelta(IntExpr expr) {
        if (expr == null || expr.kind != IntExpr.BINARY || expr.left == null || expr.right == null) {
            return null;
        }
        if (expr.left.kind == IntExpr.PARAM && expr.left.value == 0 && expr.right.kind == IntExpr.CONST) {
            return Integer.valueOf(expr.op == BinaryOp.ADD ? expr.right.value
                    : expr.op == BinaryOp.SUB ? -expr.right.value : 0);
        }
        if (expr.op == BinaryOp.ADD && expr.right.kind == IntExpr.PARAM && expr.right.value == 0
                && expr.left.kind == IntExpr.CONST) {
            return Integer.valueOf(expr.left.value);
        }
        return null;
    }

    private long evalIntExpr(IntExpr expr, long p0, long p1, long p2) {
        switch (expr.kind) {
            case IntExpr.PARAM:
                switch (expr.value) {
                    case 0:
                        return p0;
                    case 1:
                        return p1;
                    default:
                        return p2;
                }
            case IntExpr.CONST:
                return expr.value;
            case IntExpr.BINARY:
                return applyTailIntBinary(evalIntExpr(expr.left, p0, p1, p2),
                        evalIntExpr(expr.right, p0, p1, p2), expr.op);
            default:
                throw new NovaRuntimeException("Unknown int expr kind: " + expr.kind);
        }
    }

    private NovaValue executeTailIntLoopFast3(MirFrame frame, TailIntLoopPlan3 plan) {
        NovaValue[] locals = frame.locals;
        long[] rawLocals = frame.rawLocals;
        long p0;
        long p1;
        long p2;

        NovaValue v0 = locals[0];
        if (v0 == MirFrame.RAW_INT_MARKER) p0 = rawLocals[0];
        else if (v0 instanceof NovaInt) p0 = ((NovaInt) v0).getValue();
        else return null;

        NovaValue v1 = locals[1];
        if (v1 == MirFrame.RAW_INT_MARKER) p1 = rawLocals[1];
        else if (v1 instanceof NovaInt) p1 = ((NovaInt) v1).getValue();
        else return null;

        NovaValue v2 = locals[2];
        if (v2 == MirFrame.RAW_INT_MARKER) p2 = rawLocals[2];
        else if (v2 instanceof NovaInt) p2 = ((NovaInt) v2).getValue();
        else return null;

        int tceCount = frame.tceCount;
        while (true) {
            boolean exit = compareTailInt(p0, plan.exitCompareOp, plan.stopValue);
            if (exit == plan.exitOnTrue) {
                rawLocals[0] = p0;
                rawLocals[1] = p1;
                rawLocals[2] = p2;
                locals[0] = MirFrame.RAW_INT_MARKER;
                locals[1] = MirFrame.RAW_INT_MARKER;
                locals[2] = MirFrame.RAW_INT_MARKER;
                frame.tceCount = tceCount;
                long result;
                switch (plan.returnLocal) {
                    case 0: result = p0; break;
                    case 1: result = p1; break;
                    default: result = p2; break;
                }
                return result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE
                        ? NovaInt.of((int) result) : NovaLong.of(result);
            }

            long next0 = p0 + plan.counterDelta;
            long next1 = evalIntExpr(plan.update1, p0, p1, p2);
            long next2 = evalIntExpr(plan.update2, p0, p1, p2);
            p0 = next0;
            p1 = next1;
            p2 = next2;
            rawLocals[0] = p0;
            rawLocals[1] = p1;
            rawLocals[2] = p2;
            locals[0] = MirFrame.RAW_INT_MARKER;
            locals[1] = MirFrame.RAW_INT_MARKER;
            locals[2] = MirFrame.RAW_INT_MARKER;

            tceCount++;
            frame.tceCount = tceCount;
            if (cachedMaxRecursionDepth > 0 && tceCount >= cachedMaxRecursionDepth) {
                throw new NovaRuntimeException("Maximum recursion depth exceeded (" + cachedMaxRecursionDepth + ")");
            }
            if (interp.hasSecurityLimits) {
                interp.checkLoopLimits();
            }
        }
    }

    private NovaValue executeFrame(MirFrame frame, int startBlockId) {
        BasicBlock[] blockArr = frame.function.getBlockArr();
        if (blockArr.length == 0) return NovaNull.UNIT;

        // entry block = 指定的起始块或第一个块的 ID
        frame.currentBlockId = startBlockId >= 0 ? startBlockId
                : frame.function.getBlocks().get(0).getId();

        List<MirFunction.TryCatchEntry> tryCatches = frame.function.getTryCatchEntries();
        TailIntLoopPlan tailIntLoopPlan = isTailIntLoopCandidate(frame.function)
                ? resolveTailIntLoopPlan(frame.function, blockArr) : null;
        TailIntLoopPlan3 tailIntLoopPlan3 = isTailIntLoopCandidate3(frame.function)
                ? resolveTailIntLoopPlan3(frame.function, blockArr) : null;
        StringAccumLoopPlan stringAccumLoopPlan = resolveStringAccumLoopPlan(frame.function);

        int tceCount = 0;  // TCE 尾递归转循环的迭代计数
        int prevBlockId = -1;
        while (true) {
            BasicBlock block = blockArr[frame.currentBlockId];
            MirInst[] insts = block.getInstArray();

            // 回边检测：跳转到 ID <= 当前块的块 → 循环迭代，检查安全限制
            if (frame.currentBlockId <= prevBlockId && interp.hasSecurityLimits) {
                interp.checkLoopLimits();
            }

            try {
                prevBlockId = frame.currentBlockId;
                NovaValue[] locals = frame.locals;
                long[] rawLocals = frame.rawLocals;
                if (tailIntLoopPlan != null && frame.currentBlockId == tailIntLoopPlan.entryBlockId) {
                    frame.tceCount = tceCount;
                    NovaValue fusedResult = executeTailIntLoopFast(frame, tailIntLoopPlan);
                    tceCount = frame.tceCount;
                    if (fusedResult != null) {
                        return fusedResult;
                    }
                }
                if (tailIntLoopPlan3 != null && frame.currentBlockId == tailIntLoopPlan3.entryBlockId) {
                    frame.tceCount = tceCount;
                    NovaValue fusedResult = executeTailIntLoopFast3(frame, tailIntLoopPlan3);
                    tceCount = frame.tceCount;
                    if (fusedResult != null) {
                        return fusedResult;
                    }
                }
                if (stringAccumLoopPlan != null && frame.currentBlockId == stringAccumLoopPlan.headerBlockId) {
                    return executeStringAccumLoopFast(frame, stringAccumLoopPlan);
                }
                // 执行块内所有指令（热操作码内联，减少方法调用开销）
                for (frame.pc = 0; frame.pc < insts.length; frame.pc++) {
                    MirInst inst = insts[frame.pc];
                    switch (inst.getOp()) {
                        case CONST_INT:
                            rawLocals[inst.getDest()] = inst.extraInt;
                            locals[inst.getDest()] = MirFrame.RAW_INT_MARKER;
                            continue;
                        case MOVE: {
                            int src = inst.operand(0);
                            int dest = inst.getDest();
                            NovaValue srcVal = locals[src];
                            locals[dest] = srcVal;
                            if (srcVal == MirFrame.RAW_INT_MARKER) {
                                rawLocals[dest] = rawLocals[src];
                            }
                            continue;
                        }
                        case BINARY:
                            executeBinaryRawFast(frame, inst, locals, rawLocals);
                            continue;
                        case INDEX_GET: {
                            NovaValue tgt = locals[inst.operand(0)];
                            int ir = inst.operand(1);
                            if (locals[ir] == MirFrame.RAW_INT_MARKER) {
                                int idx = (int) rawLocals[ir];
                                if (idx >= 0) {
                                    int d = inst.getDest();
                                    if (tgt instanceof NovaList) {
                                        NovaValue elem = ((NovaList) tgt).getElements().get(idx);
                                        if (elem instanceof NovaInt) {
                                            rawLocals[d] = ((NovaInt) elem).getValue();
                                            locals[d] = MirFrame.RAW_INT_MARKER;
                                        } else {
                                            locals[d] = elem;
                                        }
                                        continue;
                                    }
                                    if (tgt instanceof NovaArray
                                            && ((NovaArray) tgt).getElementType() == NovaArray.ElementType.INT) {
                                        rawLocals[d] = ((int[]) ((NovaArray) tgt).getRawArray())[idx];
                                        locals[d] = MirFrame.RAW_INT_MARKER;
                                        continue;
                                    }
                                }
                            }
                            executeIndexGet(frame, inst);
                            continue;
                        }
                        case INDEX_SET: {
                            NovaValue tgt = locals[inst.operand(0)];
                            int ir = inst.operand(1);
                            if (locals[ir] == MirFrame.RAW_INT_MARKER) {
                                int idx = (int) rawLocals[ir];
                                if (idx >= 0) {
                                    if (tgt instanceof NovaArray
                                            && ((NovaArray) tgt).getElementType() == NovaArray.ElementType.INT) {
                                        int vr = inst.operand(2);
                                        NovaValue vs = locals[vr];
                                        if (vs == MirFrame.RAW_INT_MARKER) {
                                            ((int[]) ((NovaArray) tgt).getRawArray())[idx] = (int) rawLocals[vr];
                                        } else {
                                            ((int[]) ((NovaArray) tgt).getRawArray())[idx] = vs.asInt();
                                        }
                                        continue;
                                    }
                                    if (tgt instanceof NovaList) {
                                        ((NovaList) tgt).set(idx, frame.get(inst.operand(2)));
                                        continue;
                                    }
                                }
                            }
                            executeIndexSet(frame, inst);
                            continue;
                        }
                        case CONST_NULL:
                            locals[inst.getDest()] = NovaNull.NULL;
                            continue;
                        default:
                            executeInst(frame, inst);
                    }
                }

                // 处理终结指令
                MirTerminator term = block.getTerminator();
                if (term == null) {
                    return NovaNull.UNIT;
                }

                switch (term.kind) {
                    case MirTerminator.KIND_BRANCH: {
                        MirTerminator.Branch br = (MirTerminator.Branch) term;
                        BinaryOp fusedOp = br.getFusedCmpOp();
                        if (fusedOp != null) {
                            frame.currentBlockId = compareFusedFast(frame, locals, rawLocals, fusedOp, br.getFusedLeft(), br.getFusedRight())
                                    ? br.getThenBlock() : br.getElseBlock();
                        } else {
                            NovaValue cond = frame.get(br.getCondition());
                            frame.currentBlockId = isTruthy(cond) ? br.getThenBlock() : br.getElseBlock();
                        }
                        break;
                    }
                    case MirTerminator.KIND_GOTO: {
                        int targetId = ((MirTerminator.Goto) term).getTargetBlockId();
                        if (stringAccumLoopPlan != null && targetId == stringAccumLoopPlan.headerBlockId) {
                            return executeStringAccumLoopFast(frame, stringAccumLoopPlan);
                        }
                        // 穿透：Goto 目标为空指令块 + Branch → 直接评估 Branch，省一次块转换
                        BasicBlock targetBlock = blockArr[targetId];
                        if (targetBlock.getInstArray().length == 0) {
                            MirTerminator tt = targetBlock.getTerminator();
                            if (tt != null && tt.kind == MirTerminator.KIND_BRANCH) {
                                MirTerminator.Branch br = (MirTerminator.Branch) tt;
                                BinaryOp fop = br.getFusedCmpOp();
                                if (fop != null) {
                                    frame.currentBlockId = compareFusedFast(frame, locals, rawLocals, fop, br.getFusedLeft(), br.getFusedRight())
                                            ? br.getThenBlock() : br.getElseBlock();
                                } else {
                                    frame.currentBlockId = isTruthy(frame.get(br.getCondition()))
                                            ? br.getThenBlock() : br.getElseBlock();
                                }
                                break;
                            }
                        }
                        frame.currentBlockId = targetId;
                        break;
                    }
                    case MirTerminator.KIND_RETURN: {
                        int valLocal = ((MirTerminator.Return) term).getValueLocal();
                        return valLocal >= 0 ? frame.get(valLocal) : NovaNull.UNIT;
                    }
                    case MirTerminator.KIND_TAIL_CALL: {
                        tceCount++;
                        if (cachedMaxRecursionDepth > 0 && tceCount >= cachedMaxRecursionDepth) {
                            throw new NovaRuntimeException(
                                    "Maximum recursion depth exceeded (" + cachedMaxRecursionDepth + ")");
                        }
                        frame.currentBlockId = ((MirTerminator.TailCall) term).getEntryBlockId();
                        break;
                    }
                    case MirTerminator.KIND_SWITCH: {
                        MirTerminator.Switch sw = (MirTerminator.Switch) term;
                        NovaValue key = frame.get(sw.getKey());
                        Object keyObj;
                        if (key instanceof NovaEnumEntry) {
                            keyObj = ((NovaEnumEntry) key).name();
                        } else {
                            keyObj = unwrapNovaValue(key);
                        }
                        Integer target = sw.getCases().get(keyObj);
                        frame.currentBlockId = target != null ? target : sw.getDefaultBlock();
                        break;
                    }
                    case MirTerminator.KIND_THROW: {
                        NovaValue ex = frame.get(((MirTerminator.Throw) term).getExceptionLocal());
                        // LoopSignal: non-local break/continue，直接抛出原始信号
                        if (ex instanceof NovaExternalObject) {
                            Object jv = ex.toJavaValue();
                            if (jv instanceof LoopSignal) throw (LoopSignal) jv;
                        }
                        throw toRuntimeException(ex);
                    }
                    default: // UNREACHABLE
                        throw new NovaRuntimeException("Reached unreachable code");
                }
            } catch (LoopSignal sig) {
                throw sig; // non-local break/continue：直接穿透，不拦截
            } catch (Exception e) {
                NovaRuntimeException nre;
                if (e instanceof NovaRuntimeException) {
                    nre = (NovaRuntimeException) e;
                } else {
                    nre = new NovaRuntimeException(e.getMessage());
                    nre.initCause(e);
                }
                MirFunction.TryCatchEntry handler = findMatchingHandler(tryCatches, frame.currentBlockId, e);
                if (handler != null) {
                    frame.locals[handler.exceptionLocal] = wrapException(nre);
                    frame.currentBlockId = handler.handlerBlock;
                    continue;
                }
                frame.tceCount = tceCount;
                lastTceCount = tceCount;
                throw nre;
            }
        }
    }

    // ============ 指令分派 ============

    private void executeInst(MirFrame frame, MirInst inst) {
        switch (inst.getOp()) {
            // ===== 常量加载 =====
            case CONST_INT:
                frame.rawLocals[inst.getDest()] = inst.extraInt;
                frame.locals[inst.getDest()] = MirFrame.RAW_INT_MARKER;
                break;
            case CONST_LONG:
                frame.locals[inst.getDest()] = NovaLong.of(((Number) inst.getExtra()).longValue());
                break;
            case CONST_FLOAT:
                frame.locals[inst.getDest()] = NovaFloat.of(((Number) inst.getExtra()).floatValue());
                break;
            case CONST_DOUBLE:
                frame.locals[inst.getDest()] = NovaDouble.of(((Number) inst.getExtra()).doubleValue());
                break;
            case CONST_STRING:
                frame.locals[inst.getDest()] = NovaString.of((String) inst.getExtra());
                break;
            case CONST_BOOL:
                frame.locals[inst.getDest()] = NovaBoolean.of((boolean) inst.getExtra());
                break;
            case CONST_CHAR: {
                Object extra = inst.getExtra();
                char c = extra instanceof Integer ? (char) ((int) extra) : (char) extra;
                frame.locals[inst.getDest()] = NovaChar.of(c);
                break;
            }
            case CONST_NULL:
                frame.locals[inst.getDest()] = NovaNull.NULL;
                break;

            // ===== 变量 =====
            case MOVE: {
                int src = inst.operand(0);
                int dest = inst.getDest();
                NovaValue srcVal = frame.locals[src];
                frame.locals[dest] = srcVal;
                if (srcVal == MirFrame.RAW_INT_MARKER) {
                    frame.rawLocals[dest] = frame.rawLocals[src];
                }
                break;
            }

            // ===== 算术/逻辑 =====
            case BINARY:
                executeBinaryRaw(frame, inst);
                break;
            case UNARY:
                executeUnary(frame, inst);
                break;

            // ===== 对象系统 =====
            case NEW_OBJECT:
                executeNewObject(frame, inst);
                break;
            case GET_FIELD:
                executeGetField(frame, inst);
                break;
            case SET_FIELD:
                executeSetField(frame, inst);
                break;
            case GET_STATIC:
                executeGetStatic(frame, inst);
                break;
            case SET_STATIC:
                executeSetStatic(frame, inst);
                break;

            // ===== 调用 =====
            case INVOKE_VIRTUAL:
            case INVOKE_INTERFACE:
            case INVOKE_SPECIAL:
                callDispatcher.executeInvokeVirtual(frame, inst);
                break;
            case INVOKE_STATIC:
                callDispatcher.executeInvokeStatic(frame, inst);
                break;

            // ===== 集合/数组 =====
            case INDEX_GET:
                executeIndexGet(frame, inst);
                break;
            case INDEX_SET:
                executeIndexSet(frame, inst);
                break;
            case NEW_ARRAY:
                executeNewArray(frame, inst);
                break;
            case NEW_COLLECTION:
                executeNewCollection(frame, inst);
                break;

            // ===== 类型 =====
            case TYPE_CHECK:
                executeTypeCheck(frame, inst);
                break;
            case TYPE_CAST:
                executeTypeCast(frame, inst);
                break;
            case CONST_CLASS:
                executeConstClass(frame, inst);
                break;

            // ===== 闭包 =====
            case CLOSURE:
                executeClosure(frame, inst);
                break;

            case INVOKE_DYNAMIC:
                executeInvokeDynamic(frame, inst);
                break;
        }
    }

    // ============ BINARY ============

    /**
     * 双槽 BINARY 快速路径：两个操作数均为 RAW_INT_MARKER 时直接在 rawLocals 中
     * 执行 long 运算，算术结果存回 rawLocals（不装箱），比较结果存 NovaBoolean。
     * 非 raw 操作数回退到 executeBinary（通过 frame.get() 自动具化）。
     */
    private void executeBinaryRawFast(MirFrame frame, MirInst inst, NovaValue[] locals, long[] rawLocals) {
        int[] operands = inst.getOperands();
        int leftIdx = operands[0];
        int rightIdx = operands[1];
        NovaValue leftSlot = locals[leftIdx];
        NovaValue rightSlot = locals[rightIdx];
        if (leftSlot == MirFrame.RAW_INT_MARKER && rightSlot == MirFrame.RAW_INT_MARKER) {
            long a = rawLocals[leftIdx];
            long b = rawLocals[rightIdx];
            BinaryOp op = inst.extraAs();
            int dest = inst.getDest();
            switch (op) {
                case ADD: rawLocals[dest] = a + b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case SUB: rawLocals[dest] = a - b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case MUL: rawLocals[dest] = a * b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case DIV: if (b == 0) throw new ArithmeticException("/ by zero"); rawLocals[dest] = a / b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case MOD: if (b == 0) throw new ArithmeticException("/ by zero"); rawLocals[dest] = a % b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case EQ:  locals[dest] = NovaBoolean.of(a == b); return;
                case NE:  locals[dest] = NovaBoolean.of(a != b); return;
                case LT:  locals[dest] = NovaBoolean.of(a < b); return;
                case GT:  locals[dest] = NovaBoolean.of(a > b); return;
                case LE:  locals[dest] = NovaBoolean.of(a <= b); return;
                case GE:  locals[dest] = NovaBoolean.of(a >= b); return;
                case SHL: rawLocals[dest] = a << b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case SHR: rawLocals[dest] = a >> b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case USHR: rawLocals[dest] = ((int) a) >>> (int) b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case BAND: rawLocals[dest] = a & b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case BOR: rawLocals[dest] = a | b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case BXOR: rawLocals[dest] = a ^ b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
                default: break;
            }
        }
        long a;
        long b;
        if (leftSlot == MirFrame.RAW_INT_MARKER) a = rawLocals[leftIdx];
        else if (leftSlot instanceof NovaInt) a = ((NovaInt) leftSlot).getValue();
        else { executeBinary(frame, inst); return; }

        if (rightSlot == MirFrame.RAW_INT_MARKER) b = rawLocals[rightIdx];
        else if (rightSlot instanceof NovaInt) b = ((NovaInt) rightSlot).getValue();
        else { executeBinary(frame, inst); return; }

        BinaryOp op = inst.extraAs();
        int dest = inst.getDest();
        switch (op) {
            case ADD: rawLocals[dest] = a + b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case SUB: rawLocals[dest] = a - b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case MUL: rawLocals[dest] = a * b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case DIV: if (b == 0) throw new ArithmeticException("/ by zero"); rawLocals[dest] = a / b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case MOD: if (b == 0) throw new ArithmeticException("/ by zero"); rawLocals[dest] = a % b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case EQ:  locals[dest] = NovaBoolean.of(a == b); return;
            case NE:  locals[dest] = NovaBoolean.of(a != b); return;
            case LT:  locals[dest] = NovaBoolean.of(a < b); return;
            case GT:  locals[dest] = NovaBoolean.of(a > b); return;
            case LE:  locals[dest] = NovaBoolean.of(a <= b); return;
            case GE:  locals[dest] = NovaBoolean.of(a >= b); return;
            case SHL: rawLocals[dest] = a << b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case SHR: rawLocals[dest] = a >> b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case USHR: rawLocals[dest] = ((int) a) >>> (int) b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case BAND: rawLocals[dest] = a & b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case BOR: rawLocals[dest] = a | b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case BXOR: rawLocals[dest] = a ^ b; locals[dest] = MirFrame.RAW_INT_MARKER; return;
            default: executeBinary(frame, inst);
        }
    }

    private boolean compareFusedFast(MirFrame frame, NovaValue[] locals, long[] rawLocals, BinaryOp op, int leftIdx, int rightIdx) {
        boolean leftRaw = locals[leftIdx] == MirFrame.RAW_INT_MARKER;
        boolean rightRaw = locals[rightIdx] == MirFrame.RAW_INT_MARKER;
        if (leftRaw && rightRaw) {
            long a = rawLocals[leftIdx];
            long b = rawLocals[rightIdx];
            switch (op) {
                case EQ: return a == b;
                case NE: return a != b;
                case LT: return a < b;
                case GT: return a > b;
                case LE: return a <= b;
                case GE: return a >= b;
                default: break;
            }
        }
        return compareFused(frame, op, leftIdx, rightIdx);
    }

    private void executeBinaryRaw(MirFrame frame, MirInst inst) {
        int leftIdx = inst.operand(0);
        int rightIdx = inst.operand(1);
        if (frame.locals[leftIdx] == MirFrame.RAW_INT_MARKER
                && frame.locals[rightIdx] == MirFrame.RAW_INT_MARKER) {
            long a = frame.rawLocals[leftIdx];
            long b = frame.rawLocals[rightIdx];
            BinaryOp op = inst.extraAs();
            int dest = inst.getDest();
            switch (op) {
                case ADD: frame.rawLocals[dest] = a + b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case SUB: frame.rawLocals[dest] = a - b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case MUL: frame.rawLocals[dest] = a * b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case DIV: if (b == 0) throw new ArithmeticException("/ by zero"); frame.rawLocals[dest] = a / b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case MOD: if (b == 0) throw new ArithmeticException("/ by zero"); frame.rawLocals[dest] = a % b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case EQ:  frame.locals[dest] = NovaBoolean.of(a == b); return;
                case NE:  frame.locals[dest] = NovaBoolean.of(a != b); return;
                case LT:  frame.locals[dest] = NovaBoolean.of(a < b); return;
                case GT:  frame.locals[dest] = NovaBoolean.of(a > b); return;
                case LE:  frame.locals[dest] = NovaBoolean.of(a <= b); return;
                case GE:  frame.locals[dest] = NovaBoolean.of(a >= b); return;
                case SHL: frame.rawLocals[dest] = a << b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case SHR: frame.rawLocals[dest] = a >> b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case USHR: frame.rawLocals[dest] = ((int) a) >>> (int) b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case BAND: frame.rawLocals[dest] = a & b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case BOR:  frame.rawLocals[dest] = a | b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case BXOR: frame.rawLocals[dest] = a ^ b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                default: break;
            }
        }
        // 混合快速路径：一个 raw + 一个 NovaInt，或两个 NovaInt → 结果存 raw（逆装箱）
        long a, b;
        NovaValue lv = frame.locals[leftIdx], rv = frame.locals[rightIdx];
        if (lv == MirFrame.RAW_INT_MARKER) a = frame.rawLocals[leftIdx];
        else if (lv instanceof NovaInt) a = ((NovaInt) lv).getValue();
        else { executeBinary(frame, inst); return; }

        if (rv == MirFrame.RAW_INT_MARKER) b = frame.rawLocals[rightIdx];
        else if (rv instanceof NovaInt) b = ((NovaInt) rv).getValue();
        else { executeBinary(frame, inst); return; }

        BinaryOp op = inst.extraAs();
        int dest = inst.getDest();
        switch (op) {
            case ADD: frame.rawLocals[dest] = a + b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case SUB: frame.rawLocals[dest] = a - b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case MUL: frame.rawLocals[dest] = a * b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case DIV: if (b == 0) throw new ArithmeticException("/ by zero"); frame.rawLocals[dest] = a / b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case MOD: if (b == 0) throw new ArithmeticException("/ by zero"); frame.rawLocals[dest] = a % b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case EQ:  frame.locals[dest] = NovaBoolean.of(a == b); return;
            case NE:  frame.locals[dest] = NovaBoolean.of(a != b); return;
            case LT:  frame.locals[dest] = NovaBoolean.of(a < b); return;
            case GT:  frame.locals[dest] = NovaBoolean.of(a > b); return;
            case LE:  frame.locals[dest] = NovaBoolean.of(a <= b); return;
            case GE:  frame.locals[dest] = NovaBoolean.of(a >= b); return;
            case SHL: frame.rawLocals[dest] = a << b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case SHR: frame.rawLocals[dest] = a >> b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case USHR: frame.rawLocals[dest] = ((int) a) >>> (int) b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case BAND: frame.rawLocals[dest] = a & b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case BOR:  frame.rawLocals[dest] = a | b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case BXOR: frame.rawLocals[dest] = a ^ b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            default: break;
        }
        executeBinary(frame, inst);
    }

    private void executeBinary(MirFrame frame, MirInst inst) {
        NovaValue left = frame.get(inst.operand(0));
        NovaValue right = frame.get(inst.operand(1));
        BinaryOp op = inst.extraAs();

        // 快速路径：int + int
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int a = ((NovaInt) left).getValue(), b = ((NovaInt) right).getValue();
            NovaValue result;
            switch (op) {
                case ADD: result = NovaInt.of(a + b); break;
                case SUB: result = NovaInt.of(a - b); break;
                case MUL: result = NovaInt.of(a * b); break;
                case DIV: if (b == 0) throw new ArithmeticException("/ by zero"); result = NovaInt.of(a / b); break;
                case MOD: if (b == 0) throw new ArithmeticException("/ by zero"); result = NovaInt.of(a % b); break;
                case EQ:  result = NovaBoolean.of(a == b); break;
                case NE:  result = NovaBoolean.of(a != b); break;
                case LT:  result = NovaBoolean.of(a < b); break;
                case GT:  result = NovaBoolean.of(a > b); break;
                case LE:  result = NovaBoolean.of(a <= b); break;
                case GE:  result = NovaBoolean.of(a >= b); break;
                case SHL: result = NovaInt.of(a << b); break;
                case SHR: result = NovaInt.of(a >> b); break;
                case USHR: result = NovaInt.of(a >>> b); break;
                case BAND: result = NovaInt.of(a & b); break;
                case BOR:  result = NovaInt.of(a | b); break;
                case BXOR: result = NovaInt.of(a ^ b); break;
                default:   result = generalBinary(left, right, op); break;
            }
            frame.locals[inst.getDest()] = result;
            return;
        }

        frame.locals[inst.getDest()] = generalBinary(left, right, op);
    }

    private NovaValue generalBinary(NovaValue left, NovaValue right, BinaryOp op) {
        // 逻辑运算
        if (op == BinaryOp.AND) return NovaBoolean.of(isTruthy(left) && isTruthy(right));
        if (op == BinaryOp.OR) return NovaBoolean.of(isTruthy(left) || isTruthy(right));

        // 相等性比较（处理 null）
        if (op == BinaryOp.EQ) return NovaBoolean.of(novaEquals(left, right));
        if (op == BinaryOp.NE) return NovaBoolean.of(!novaEquals(left, right));
        if (op == BinaryOp.REF_EQ) return NovaBoolean.of(left == right);
        if (op == BinaryOp.REF_NE) return NovaBoolean.of(left != right);

        // 算术运算 → BinaryOps
        switch (op) {
            case ADD: return BinaryOps.add(left, right, interp);
            case SUB: return BinaryOps.sub(left, right, interp);
            case MUL: return BinaryOps.mul(left, right, interp);
            case DIV: return BinaryOps.div(left, right, interp);
            case MOD: return BinaryOps.mod(left, right, interp);
            default: break;
        }

        // 比较运算 → BinaryOps
        if (op == BinaryOp.LT || op == BinaryOp.GT || op == BinaryOp.LE || op == BinaryOp.GE) {
            int cmp = BinaryOps.compare(left, right, interp);
            switch (op) {
                case LT: return NovaBoolean.of(cmp < 0);
                case GT: return NovaBoolean.of(cmp > 0);
                case LE: return NovaBoolean.of(cmp <= 0);
                case GE: return NovaBoolean.of(cmp >= 0);
                default: break;
            }
        }

        // 位运算
        switch (op) {
            case BAND: return BinaryOps.bitwiseAnd(left, right);
            case BOR:  return BinaryOps.bitwiseOr(left, right);
            case BXOR: return BinaryOps.bitwiseXor(left, right);
            case SHL:  return BinaryOps.shiftLeft(left, right);
            case SHR:  return BinaryOps.shiftRight(left, right);
            case USHR: return BinaryOps.unsignedShiftRight(left, right);
            default: break;
        }

        throw new NovaRuntimeException("Unsupported binary operation: " + op
                + " on " + left.getTypeName() + " and " + right.getTypeName());
    }

    // ============ UNARY ============

    private void executeUnary(MirFrame frame, MirInst inst) {
        NovaValue operand = frame.get(inst.operand(0));
        UnaryOp op = inst.extraAs();
        NovaValue result;
        switch (op) {
            case NEG:
                if (operand instanceof NovaInt) result = NovaInt.of(-((NovaInt) operand).getValue());
                else if (operand instanceof NovaDouble) result = NovaDouble.of(-((NovaDouble) operand).getValue());
                else if (operand instanceof NovaLong) result = NovaLong.of(-((NovaLong) operand).getValue());
                else if (operand instanceof NovaFloat) result = NovaFloat.of(-((NovaFloat) operand).getValue());
                else if (operand instanceof NovaObject) {
                    // 尝试 unaryMinus 运算符重载
                    NovaCallable method = ((NovaObject) operand).getMethod("unaryMinus");
                    if (method != null) {
                        result = method.call(interp, Collections.singletonList(operand));
                        break;
                    }
                    // 尝试 unaryPlus（POS 操作码复用 NEG 时 extra 区分）
                    throw new NovaRuntimeException("Cannot negate " + operand.getTypeName());
                }
                else throw new NovaRuntimeException("Cannot negate " + operand.getTypeName());
                break;
            case POS:
                if (operand instanceof NovaObject) {
                    NovaCallable method = ((NovaObject) operand).getMethod("unaryPlus");
                    if (method != null) {
                        result = method.call(interp, Collections.singletonList(operand));
                        break;
                    }
                }
                result = operand; // +x = x for numbers
                break;
            case NOT:
                result = NovaBoolean.of(!isTruthy(operand));
                break;
            case BNOT:
                if (operand instanceof NovaInt) result = NovaInt.of(~((NovaInt) operand).getValue());
                else if (operand instanceof NovaLong) result = NovaLong.of(~((NovaLong) operand).getValue());
                else throw new NovaRuntimeException("Cannot bitwise-not " + operand.getTypeName());
                break;
            default:
                throw new NovaRuntimeException("Unknown unary op: " + op);
        }
        frame.locals[inst.getDest()] = result;
    }

    // ============ NEW_OBJECT ============

    private void executeNewObject(MirFrame frame, MirInst inst) {
        String className = inst.extraAs();
        int[] ops = inst.getOperands();
        int argCount = ops != null ? ops.length : 0;

        NewObjectSite site = inst.cache instanceof NewObjectSite ? (NewObjectSite) inst.cache : null;
        if (site == null || site.arity != argCount) {
            site = resolveNewObjectSite(className, argCount);
            if (site != null) {
                inst.cache = site;
            }
        }

        if (site != null && site.scalarizable) {
            frame.locals[inst.getDest()] = ScalarizedNovaObject.fromOperands(
                    site.novaClass, site.fieldCount, frame, ops != null ? ops : EMPTY_OPERANDS);
            return;
        }

        if (site != null && site.fastPath) {
            frame.locals[inst.getDest()] = interp.functionExecutor.instantiateMirFast(
                    site.novaClass, site.constructor, frame, ops != null ? ops : EMPTY_OPERANDS);
            return;
        }

        NovaValue[] argsArray = collectArgsArray(frame, ops);
        List<NovaValue> args = Arrays.asList(argsArray);

        MirClassInfo classInfo = site != null ? site.classInfo : mirClasses.get(className);
        if (classInfo != null && classInfo.novaClass != null) {
            frame.locals[inst.getDest()] = classInfo.novaClass.call(interp, args);
            return;
        }

        NovaValue classVal = interp.getEnvironment().tryGet(toJavaDotName(className));
        if (classVal instanceof NovaClass) {
            frame.locals[inst.getDest()] = ((NovaClass) classVal).call(interp, args);
            return;
        }

        if (className.contains(MARKER_LAMBDA) || className.contains(MARKER_METHOD_REF)) {
            frame.locals[inst.getDest()] = createLambdaInstance(className, frame, ops != null ? ops : EMPTY_OPERANDS);
            return;
        }

        // Java 集合快捷转换（安全策略有方法黑名单时跳过，保留真实 Java 类型供安全检查使用）
        if (!interp.getSecurityPolicy().hasMethodRestrictions()) {
            if (JAVA_ARRAY_LIST.equals(className)) {
                frame.locals[inst.getDest()] = new NovaList();
                return;
            }
            if (JAVA_HASH_MAP.equals(className)) {
                frame.locals[inst.getDest()] = new NovaMap();
                return;
            }
            if (JAVA_LINKED_HASH_SET.equals(className)) {
                frame.locals[inst.getDest()] = new NovaList();
                return;
            }
        }

        try {
            Class<?> javaClass = JavaInterop.loadClass(toJavaDotName(className));
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                NovaValue v = args.get(i);
                javaArgs[i] = (v != null) ? v.toJavaValue() : null;
            }
            Object javaObj = MethodHandleCache.getInstance().newInstance(javaClass, javaArgs);
            frame.locals[inst.getDest()] = new NovaExternalObject(javaObj);
        } catch (Throwable e) {
            throw new NovaRuntimeException("Cannot create object: " + className);
        }
    }

    private NewObjectSite resolveNewObjectSite(String className, int argCount) {
        MirClassInfo classInfo = mirClasses.get(className);
        if (classInfo == null || classInfo.novaClass == null) {
            return null;
        }
        NovaClass novaClass = classInfo.novaClass;
        if (novaClass.hasJavaSuperTypes() || novaClass.getSuperclass() != null) {
            return new NewObjectSite(classInfo, novaClass, null, argCount, false, false, 0);
        }
        NovaCallable ctor = novaClass.getConstructorByArity(argCount);
        if (ctor instanceof MirCallable) {
            MirCallable mirCtor = (MirCallable) ctor;
            MirFunction ctorFunc = mirCtor.getFunction();
            if (!ctorFunc.hasDelegation() && !ctorFunc.hasSuperInitArgs()) {
                if (isScalarizableValueClass(classInfo, ctorFunc, argCount)) {
                    return new NewObjectSite(classInfo, novaClass, mirCtor, argCount, false, true,
                            classInfo.mirClass.getFields().size());
                }
                return new NewObjectSite(classInfo, novaClass, mirCtor, argCount, true, false, 0);
            }
        }
        if (ctor == null && argCount == 0 && novaClass.getHirConstructors().isEmpty()) {
            return new NewObjectSite(classInfo, novaClass, null, argCount, true, false, 0);
        }
        return new NewObjectSite(classInfo, novaClass, null, argCount, false, false, 0);
    }

    private boolean isScalarizableValueClass(MirClassInfo classInfo, MirFunction ctorFunc, int argCount) {
        List<MirField> fields = classInfo.mirClass.getFields();
        if (fields.isEmpty() || fields.size() > 4 || fields.size() != argCount) {
            return false;
        }
        if (ctorFunc.getBlocks().size() != 1) {
            return false;
        }
        BasicBlock block = ctorFunc.getBlocks().get(0);
        List<MirInst> insts = block.getInstructions();
        if (insts.size() != fields.size()) {
            return false;
        }
        for (int i = 0; i < insts.size(); i++) {
            MirInst inst = insts.get(i);
            if (inst.getOp() != MirOp.SET_FIELD || inst.operand(0) != 0 || inst.operand(1) != i + 1) {
                return false;
            }
            if (!fields.get(i).getName().equals(String.valueOf(inst.getExtra()))) {
                return false;
            }
        }
        return block.getTerminator() != null && block.getTerminator().kind == MirTerminator.KIND_RETURN;
    }

    private NovaValue createLambdaInstance(String lambdaClassName, MirFrame frame, int[] captureOps) {
        // 查找 Lambda 的 MirClass（在 additionalClasses 中注册）
        MirClassInfo lambdaInfo = mirClasses.get(lambdaClassName);
        if (lambdaInfo != null) {
            // 查找 invoke 方法
            MirFunction invokeFunc = null;
            for (MirFunction method : lambdaInfo.mirClass.getMethods()) {
                if ("invoke".equals(method.getName())) {
                    invokeFunc = method;
                    break;
                }
            }
            if (invokeFunc != null) {
                // 构建捕获变量字段映射（字段名 → 值）
                Map<String, NovaValue> captureFields = new LinkedHashMap<>();
                List<MirField> fields = lambdaInfo.mirClass.getFields();
                for (int i = 0; i < Math.min(fields.size(), captureOps.length); i++) {
                    captureFields.put(fields.get(i).getName(), frame.get(captureOps[i]));
                }
                return new MirCallable(this, invokeFunc, captureFields);
            }
        }
        throw new NovaRuntimeException("Lambda class not found: " + lambdaClassName);
    }

    // ============ INVOKE_DYNAMIC ============

    /**
     * INVOKE_DYNAMIC 解释器分派：根据 bootstrap 方法类型路由到已有的分派器。
     * 使统一 MIR（编译路径风格）在解释器中也能正确执行。
     */
    private void executeInvokeDynamic(MirFrame frame, MirInst inst) {
        Object extra = inst.getExtra();
        if (!(extra instanceof com.novalang.ir.mir.InvokeDynamicInfo)) {
            throw new NovaRuntimeException("INVOKE_DYNAMIC: invalid extra type");
        }
        com.novalang.ir.mir.InvokeDynamicInfo info = (com.novalang.ir.mir.InvokeDynamicInfo) extra;
        int[] ops = inst.getOperands();

        switch (info.bootstrapMethod) {
            case "bootstrapInvoke": {
                // target.method(args) → 委托 executeInvokeVirtual
                MirInst virtualInst = new MirInst(
                        com.novalang.ir.mir.MirOp.INVOKE_VIRTUAL,
                        inst.getDest(), ops, info.methodName, inst.getLocation());
                callDispatcher.executeInvokeVirtual(frame, virtualInst);
                return;
            }
            case "bootstrapGetMember": {
                // target.member → 委托 executeGetField
                MirInst getInst = new MirInst(
                        com.novalang.ir.mir.MirOp.GET_FIELD,
                        inst.getDest(), ops, info.methodName, inst.getLocation());
                executeGetField(frame, getInst);
                return;
            }
            case "bootstrapSetMember": {
                // target.member = value → 委托 executeSetField
                MirInst setInst = new MirInst(
                        com.novalang.ir.mir.MirOp.SET_FIELD,
                        inst.getDest(), ops, info.methodName, inst.getLocation());
                executeSetField(frame, setInst);
                return;
            }
            case "bootstrapStaticInvoke": {
                // 委托给 StaticMethodDispatcher 的 $PipeCall 分派链
                MirInst pipeInst = new MirInst(
                        MirOp.INVOKE_STATIC,
                        inst.getDest(), ops, "$PipeCall|" + info.methodName, inst.getLocation());
                callDispatcher.executeInvokeStatic(frame, pipeInst);
                return;
            }
            default:
                throw new NovaRuntimeException("Unknown bootstrap method: " + info.bootstrapMethod);
        }
    }

    // ============ GET_FIELD / SET_FIELD ============

    private void executeGetField(MirFrame frame, MirInst inst) {
        NovaValue target = frame.get(inst.operand(0));
        String fieldName = inst.extraAs();

        if (target instanceof NovaClass) {
            NovaClass cls = (NovaClass) target;
            NovaValue staticVal = cls.getStaticField(fieldName);
            if (staticVal != null && !(staticVal instanceof NovaNull)) {
                frame.locals[inst.getDest()] = staticVal;
                return;
            }
            NovaValue instance = cls.getStaticField("INSTANCE");
            if (instance instanceof NovaObject) {
                NovaObject obj = (NovaObject) instance;
                if (obj.hasField(fieldName)) {
                    frame.locals[inst.getDest()] = obj.getField(fieldName);
                    return;
                }
                NovaCallable method = obj.getMethod(fieldName);
                if (method != null) {
                    frame.locals[inst.getDest()] = method.call(interp, Collections.singletonList(instance));
                    return;
                }
            }
        }

        // NovaPair / NovaResult / 其他带 resolveMember 的类型
        // 注意：NovaObject 跳过此路径，走后面的 MemberResolver（有可见性检查）
        if (!(target instanceof NovaObject)) {
            NovaValue memberVal = target.resolveMember(fieldName);
            if (memberVal != null) {
                frame.locals[inst.getDest()] = memberVal;
                return;
            }
        }

        if (target instanceof NovaExternalObject && "message".equals(fieldName)) {
            Object jObj = target.toJavaValue();
            if (jObj instanceof Exception) {
                String msg = ((Exception) jObj).getMessage();
                frame.locals[inst.getDest()] = msg != null ? NovaString.of(msg) : NovaNull.NULL;
                return;
            }
        }

        if (target instanceof MirCallable) {
            NovaValue fieldVal = ((MirCallable) target).getCaptureField(fieldName);
            if (fieldVal != null) {
                frame.locals[inst.getDest()] = fieldVal;
                return;
            }
            if (callDispatcher.scopeReceiver != null) {
                NovaValue resolved = callDispatcher.resolveFieldOnValue(callDispatcher.scopeReceiver, fieldName);
                if (resolved != null) {
                    frame.locals[inst.getDest()] = resolved;
                    return;
                }
            }
            NovaValue envVal = interp.getEnvironment().tryGet(fieldName);
            frame.locals[inst.getDest()] = envVal != null ? envVal : NovaNull.NULL;
            return;
        }

        if (target instanceof NovaEnumEntry) {
            NovaEnumEntry entry = (NovaEnumEntry) target;
            if (entry.hasField(fieldName)) {
                frame.locals[inst.getDest()] = entry.getField(fieldName);
                return;
            }
            NovaCallable method = entry.getMethod(fieldName);
            if (method != null) {
                frame.locals[inst.getDest()] = new NovaBoundMethod(entry, method);
                return;
            }
        }

        if (target instanceof ScalarizedNovaObject) {
            ScalarizedNovaObject obj = (ScalarizedNovaObject) target;
            NovaClass objClass = obj.getNovaClass();
            NovaCallable scalarGetter = objClass.getCustomGetter(fieldName);
            if (scalarGetter != null && !("get$" + fieldName).equals(frame.function.getName())) {
                frame.locals[inst.getDest()] = scalarGetter.call(interp, Collections.singletonList(obj));
                return;
            }
            FieldAccessSite site = inst.cache instanceof FieldAccessSite ? (FieldAccessSite) inst.cache : null;
            if (site != null && site.cachedClass == objClass && site.fieldIndex >= 0) {
                obj.exportFieldToFrame(site.fieldIndex, frame, inst.getDest());
                return;
            }
            int fieldIdx = objClass.getFieldIndex(fieldName);
            if (fieldIdx >= 0) {
                if (site == null) {
                    site = new FieldAccessSite();
                    inst.cache = site;
                }
                site.cachedClass = objClass;
                site.fieldIndex = fieldIdx;
                obj.exportFieldToFrame(fieldIdx, frame, inst.getDest());
                return;
            }
            NovaCallable method = objClass.findCallableMethod(fieldName);
            if (method != null) {
                frame.locals[inst.getDest()] = method.call(interp, Collections.singletonList(target));
                return;
            }
        }

        if (target instanceof NovaObject) {
            NovaObject obj = (NovaObject) target;
            NovaClass objClass = obj.getNovaClass();
            // 自定义 getter → 调用 get$fieldName() 合成方法（跳过递归：在 getter 函数自身内不拦截）
            NovaCallable customGetter = objClass.getCustomGetter(fieldName);
            if (customGetter != null && !("get$" + fieldName).equals(frame.function.getName())) {
                frame.locals[inst.getDest()] = customGetter.call(interp, Collections.singletonList(obj));
                return;
            }
            // DEBUG: detect lambda class accessing non-existent field (scope function scenario)
            if (objClass.getName().contains("$Lambda$") && objClass.getFieldIndex(fieldName) < 0
                    && !obj.hasField(fieldName) && obj.getMethod(fieldName) == null) {
                // Lambda object missing field → check scopeReceiver
                if (callDispatcher.scopeReceiver != null) {
                    NovaValue resolved = callDispatcher.resolveFieldOnValue(callDispatcher.scopeReceiver, fieldName);
                    if (resolved != null) {
                        frame.locals[inst.getDest()] = resolved;
                        return;
                    }
                }
                NovaValue envVal = interp.getEnvironment().tryGet(fieldName);
                if (envVal != null) {
                    frame.locals[inst.getDest()] = envVal;
                    return;
                }
            }
            FieldAccessSite site = inst.cache instanceof FieldAccessSite ? (FieldAccessSite) inst.cache : null;
            if (site != null && site.cachedClass == objClass && site.fieldIndex >= 0) {
                NovaValue cachedVal = obj.getFieldByIndex(site.fieldIndex);
                if (cachedVal != null) {
                    frame.locals[inst.getDest()] = cachedVal;
                    return;
                }
            }
            int fieldIdx = objClass.getFieldIndex(fieldName);
            if (fieldIdx >= 0) {
                NovaClass currentClass = interp.getCurrentClass();
                if (currentClass == null && frame.locals[0] instanceof NovaObject) {
                    currentClass = ((NovaObject) frame.locals[0]).getNovaClass();
                }
                if (!objClass.isFieldAccessibleFrom(fieldName, currentClass)) {
                    throw new NovaRuntimeException("Cannot access private field '" + fieldName + "'");
                }
                if (site == null) {
                    site = new FieldAccessSite();
                    inst.cache = site;
                }
                site.cachedClass = objClass;
                site.fieldIndex = fieldIdx;
                NovaValue fieldVal = obj.getFieldByIndex(fieldIdx);
                if (fieldVal != null) {
                    frame.locals[inst.getDest()] = fieldVal;
                    return;
                }
            }
            if (obj.hasField(fieldName)) {
                frame.locals[inst.getDest()] = obj.getField(fieldName);
                return;
            }
            NovaCallable method = obj.getMethod(fieldName);
            if (method != null) {
                frame.locals[inst.getDest()] = method.call(interp, Collections.singletonList(target));
                return;
            }
            NovaValue envVal = interp.getEnvironment().tryGet(fieldName);
            if (envVal != null) {
                frame.locals[inst.getDest()] = envVal;
                return;
            }
        }

        frame.locals[inst.getDest()] = resolver.resolveMemberOnValue(target, fieldName, null);
    }

    private void executeSetField(MirFrame frame, MirInst inst) {
        NovaValue target = frame.get(inst.operand(0));
        NovaValue value = frame.get(inst.operand(1));
        String fieldName = inst.extraAs();

        if (target instanceof MirCallable) {
            if (((MirCallable) target).hasCaptureField(fieldName)) {
                ((MirCallable) target).setCaptureField(fieldName, value);
            } else if (callDispatcher.scopeReceiver instanceof ScalarizedNovaObject) {
                ScalarizedNovaObject sobj = (ScalarizedNovaObject) callDispatcher.scopeReceiver;
                int idx = sobj.getNovaClass().getFieldIndex(fieldName);
                if (idx >= 0) {
                    sobj.setFieldByIndex(idx, value);
                } else {
                    interp.getEnvironment().redefine(fieldName, value, true);
                }
            } else if (callDispatcher.scopeReceiver instanceof NovaObject
                    && ((NovaObject) callDispatcher.scopeReceiver).hasField(fieldName)) {
                ((NovaObject) callDispatcher.scopeReceiver).setField(fieldName, value);
            } else {
                interp.getEnvironment().redefine(fieldName, value, true);
            }
            return;
        }

        if (target instanceof ScalarizedNovaObject) {
            ScalarizedNovaObject obj = (ScalarizedNovaObject) target;
            NovaClass objClass = obj.getNovaClass();
            FieldAccessSite site = inst.cache instanceof FieldAccessSite ? (FieldAccessSite) inst.cache : null;
            if (site != null && site.cachedClass == objClass && site.fieldIndex >= 0) {
                obj.setFieldByIndex(site.fieldIndex, value);
                return;
            }
            int fieldIdx = objClass.getFieldIndex(fieldName);
            if (fieldIdx >= 0) {
                if (site == null) {
                    site = new FieldAccessSite();
                    inst.cache = site;
                }
                site.cachedClass = objClass;
                site.fieldIndex = fieldIdx;
                obj.setFieldByIndex(fieldIdx, value);
                return;
            }
        }

        if (target instanceof NovaObject) {
            NovaObject obj = (NovaObject) target;
            NovaClass objClass = obj.getNovaClass();
            // 自定义 setter → 调用 set$fieldName(value) 合成方法（跳过递归）
            NovaCallable customSetter = objClass.getCustomSetter(fieldName);
            if (customSetter != null && !("set$" + fieldName).equals(frame.function.getName())) {
                customSetter.call(interp, java.util.Arrays.asList(obj, value));
                return;
            }
            // Lambda class SET_FIELD: field not on lambda → redirect to scopeReceiver
            if (objClass.getName().contains("$Lambda$") && objClass.getFieldIndex(fieldName) < 0
                    && !obj.hasField(fieldName)) {
                if (callDispatcher.scopeReceiver instanceof NovaObject
                        && ((NovaObject) callDispatcher.scopeReceiver).hasField(fieldName)) {
                    ((NovaObject) callDispatcher.scopeReceiver).setField(fieldName, value);
                } else {
                    interp.getEnvironment().redefine(fieldName, value, true);
                }
                return;
            }
            FieldAccessSite site = inst.cache instanceof FieldAccessSite ? (FieldAccessSite) inst.cache : null;
            if (site != null && site.cachedClass == objClass && site.fieldIndex >= 0) {
                obj.setFieldByIndex(site.fieldIndex, value);
                return;
            }
            int fieldIdx = objClass.getFieldIndex(fieldName);
            if (fieldIdx >= 0) {
                if (site == null) {
                    site = new FieldAccessSite();
                    inst.cache = site;
                }
                site.cachedClass = objClass;
                site.fieldIndex = fieldIdx;
                obj.setFieldByIndex(fieldIdx, value);
                return;
            }
            obj.setField(fieldName, value);
        } else if (target instanceof NovaExternalObject) {
            ((NovaExternalObject) target).setField(fieldName, value);
        } else {
            throw new NovaRuntimeException("Cannot set field '" + fieldName + "' on " + target.getTypeName());
        }
    }

    // ============ GET_STATIC / SET_STATIC ============

    /** $Module 顶层静态字段存储（MIR 解释器路径） */
    private Map<String, NovaValue> moduleStaticFields = new java.util.concurrent.ConcurrentHashMap<>();

    private void executeGetStatic(MirFrame frame, MirInst inst) {
        // 缓存字符串解析结果
        Object cached = inst.cache;
        String owner, fieldName;
        if (cached instanceof StaticFieldSite) {
            StaticFieldSite site = (StaticFieldSite) cached;
            owner = site.owner;
            fieldName = site.fieldName;
            // $Module 快速路径（缓存命中）
            if (site.isModule) {
                NovaValue val = moduleStaticFields.get(fieldName);
                frame.locals[inst.getDest()] = val != null ? val : NovaNull.NULL;
                return;
            }
        } else if (cached instanceof java.lang.invoke.MethodHandle) {
            // Java 静态字段缓存命中
            try {
                frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(
                        ((java.lang.invoke.MethodHandle) cached).invoke());
                return;
            } catch (Throwable e) {
                String extra = inst.extraAs();
                throw new NovaRuntimeException("Failed to access static field " + extra + ": " + e.getMessage(), e);
            }
        } else if (cached == STATIC_FIELD_MISS) {
            frame.locals[inst.getDest()] = NovaNull.NULL;
            return;
        } else {
            // 首次调用：解析字符串
            String extra = inst.extraAs();
            int pipe = extra.indexOf('|');
            owner = extra.substring(0, pipe);
            int pipe2 = extra.indexOf('|', pipe + 1);
            fieldName = pipe2 >= 0 ? extra.substring(pipe + 1, pipe2) : extra.substring(pipe + 1);
            StaticFieldSite site = new StaticFieldSite(owner, fieldName);
            inst.cache = site;

            // $Module 顶层静态字段
            if (site.isModule) {
                NovaValue val = moduleStaticFields.get(fieldName);
                frame.locals[inst.getDest()] = val != null ? val : NovaNull.NULL;
                return;
            }
        }

        // 特殊处理 System.out
        if (JAVA_SYSTEM.equals(owner) && "out".equals(fieldName)) {
            frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(interp.getStdout());
            return;
        }
        if (JAVA_SYSTEM.equals(owner) && "err".equals(fieldName)) {
            frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(interp.getStderr());
            return;
        }
        // Dispatchers: 优先使用 Builtins 注册的 NovaMap（支持动态 Main 注入）
        if ("DISPATCHERS".equals(fieldName)) {
            NovaValue dispatchers = interp.getGlobals().tryGet("Dispatchers");
            if (dispatchers != null) {
                frame.locals[inst.getDest()] = dispatchers;
                return;
            }
        }

        // Nova 类的静态字段
        MirClassInfo classInfo = mirClasses.get(owner);
        if (classInfo != null) {
            NovaValue staticVal = classInfo.novaClass.getStaticField(fieldName);
            if (staticVal != null) {
                frame.locals[inst.getDest()] = staticVal;
                return;
            }
        }

        // 从环境查找类
        String normalizedOwner = toJavaDotName(owner);
        NovaValue classVal = interp.getEnvironment().tryGet(normalizedOwner);
        if (classVal == null) classVal = interp.getEnvironment().tryGet(owner);

        if (classVal instanceof NovaClass) {
            NovaValue staticVal = ((NovaClass) classVal).getStaticField(fieldName);
            if (staticVal != null) {
                frame.locals[inst.getDest()] = staticVal;
                return;
            }
        }
        if (classVal instanceof NovaEnum) {
            NovaEnumEntry entry = ((NovaEnum) classVal).getEntry(fieldName);
            if (entry != null) {
                frame.locals[inst.getDest()] = entry;
                return;
            }
        }

        // Java 静态字段：首次解析并缓存 MethodHandle（覆盖 StaticFieldSite）
        try {
            String javaDotName = toJavaDotName(owner);
            if (!interp.getSecurityPolicy().isClassAllowed(javaDotName)) {
                throw NovaSecurityPolicy.denied("Cannot access class: " + javaDotName);
            }
            Class<?> javaClass = JavaInterop.loadClass(javaDotName);
            java.lang.invoke.MethodHandle mh =
                    MethodHandleCache.getInstance().findStaticGetter(javaClass, fieldName);
            if (mh != null) {
                inst.cache = mh;
                frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(mh.invoke());
            } else {
                inst.cache = STATIC_FIELD_MISS;
                frame.locals[inst.getDest()] = NovaNull.NULL;
            }
        } catch (NovaRuntimeException e) {
            throw e;
        } catch (ClassNotFoundException e) {
            inst.cache = STATIC_FIELD_MISS;
            frame.locals[inst.getDest()] = NovaNull.NULL;
        } catch (Throwable e) {
            throw new NovaRuntimeException("Failed to access static field "
                    + owner + "." + fieldName + ": " + e.getMessage(), e);
        }
    }

    private void executeSetStatic(MirFrame frame, MirInst inst) {
        // 缓存字符串解析结果（避免每次 indexOf + substring）
        Object cached = inst.cache;
        StaticFieldSite site;
        if (cached instanceof StaticFieldSite) {
            site = (StaticFieldSite) cached;
        } else {
            String extra = inst.extraAs();
            int pipe = extra.indexOf('|');
            String owner = extra.substring(0, pipe);
            int pipe2 = extra.indexOf('|', pipe + 1);
            String fieldName = pipe2 >= 0 ? extra.substring(pipe + 1, pipe2) : extra.substring(pipe + 1);
            site = new StaticFieldSite(owner, fieldName);
            inst.cache = site;
        }
        NovaValue value = frame.get(inst.operand(0));

        // $Module 顶层静态字段
        if (site.isModule) {
            moduleStaticFields.put(site.fieldName, value);
            return;
        }

        MirClassInfo classInfo = mirClasses.get(site.owner);
        if (classInfo != null) {
            classInfo.novaClass.setStaticField(site.fieldName, value);
            return;
        }

        String normalizedOwner = toJavaDotName(site.owner);
        NovaValue classVal = interp.getEnvironment().tryGet(normalizedOwner);
        if (classVal == null) classVal = interp.getEnvironment().tryGet(site.owner);
        if (classVal instanceof NovaClass) {
            ((NovaClass) classVal).setStaticField(site.fieldName, value);
        }
    }

    // ============ INDEX_GET / INDEX_SET ============

    private void executeIndexGet(MirFrame frame, MirInst inst) {
        NovaValue target = frame.locals[inst.operand(0)];
        if (target instanceof NovaList) {
            int idxReg = inst.operand(1);
            int idx;
            NovaValue idxVal = frame.locals[idxReg];
            if (idxVal == MirFrame.RAW_INT_MARKER) {
                idx = (int) frame.rawLocals[idxReg];
            } else if (idxVal instanceof NovaInt) {
                idx = ((NovaInt) idxVal).getValue();
            } else {
                // 非 int 索引（Range 切片等）→ 通用路径
                frame.locals[inst.getDest()] = resolver.performIndex(target, frame.get(idxReg), null);
                return;
            }
            NovaValue elem = ((NovaList) target).getElements().get(idx);
            int dest = inst.getDest();
            // 逆装箱：NovaInt 元素 → raw 存储，使后续 BINARY 走纯 raw 快速路径
            if (elem instanceof NovaInt) {
                frame.rawLocals[dest] = ((NovaInt) elem).getValue();
                frame.locals[dest] = MirFrame.RAW_INT_MARKER;
            } else {
                frame.locals[dest] = elem;
            }
            return;
        }
        // NovaArray 快速路径
        if (target instanceof NovaArray) {
            NovaArray arr = (NovaArray) target;
            int idxReg = inst.operand(1);
            int idx;
            NovaValue idxVal = frame.locals[idxReg];
            if (idxVal == MirFrame.RAW_INT_MARKER) {
                idx = (int) frame.rawLocals[idxReg];
            } else if (idxVal instanceof NovaInt) {
                idx = ((NovaInt) idxVal).getValue();
            } else {
                frame.locals[inst.getDest()] = resolver.performIndex(target, frame.get(idxReg), null);
                return;
            }
            int dest = inst.getDest();
            if (idx >= 0 && arr.getElementType() == NovaArray.ElementType.INT) {
                frame.rawLocals[dest] = ((int[]) arr.getRawArray())[idx];
                frame.locals[dest] = MirFrame.RAW_INT_MARKER;
            } else {
                frame.locals[dest] = arr.get(idx);
            }
            return;
        }
        // 通用路径：String/Map/Range/Pair/运算符重载
        frame.locals[inst.getDest()] = resolver.performIndex(
                frame.get(inst.operand(0)), frame.get(inst.operand(1)), null);
    }

    private void executeIndexSet(MirFrame frame, MirInst inst) {
        NovaValue target = frame.locals[inst.operand(0)];
        if (target instanceof NovaList) {
            int idxReg = inst.operand(1);
            int idx;
            NovaValue idxVal = frame.locals[idxReg];
            if (idxVal == MirFrame.RAW_INT_MARKER) {
                idx = (int) frame.rawLocals[idxReg];
            } else if (idxVal instanceof NovaInt) {
                idx = ((NovaInt) idxVal).getValue();
            } else {
                resolver.performIndexSet(frame.get(inst.operand(0)), frame.get(idxReg),
                        frame.get(inst.operand(2)), null);
                return;
            }
            ((NovaList) target).set(idx, frame.get(inst.operand(2)));
            return;
        }
        // NovaArray 快速路径
        if (target instanceof NovaArray) {
            NovaArray arr = (NovaArray) target;
            int idxReg = inst.operand(1);
            int idx;
            NovaValue idxVal = frame.locals[idxReg];
            if (idxVal == MirFrame.RAW_INT_MARKER) {
                idx = (int) frame.rawLocals[idxReg];
            } else if (idxVal instanceof NovaInt) {
                idx = ((NovaInt) idxVal).getValue();
            } else {
                resolver.performIndexSet(frame.get(inst.operand(0)), frame.get(idxReg),
                        frame.get(inst.operand(2)), null);
                return;
            }
            if (idx >= 0 && arr.getElementType() == NovaArray.ElementType.INT) {
                // raw int 直通：跳过 NovaValue 装箱/拆箱
                int valReg = inst.operand(2);
                NovaValue valSlot = frame.locals[valReg];
                if (valSlot == MirFrame.RAW_INT_MARKER) {
                    ((int[]) arr.getRawArray())[idx] = (int) frame.rawLocals[valReg];
                } else {
                    ((int[]) arr.getRawArray())[idx] = valSlot.asInt();
                }
            } else {
                arr.set(idx, frame.get(inst.operand(2)));
            }
            return;
        }
        resolver.performIndexSet(
                frame.get(inst.operand(0)), frame.get(inst.operand(1)),
                frame.get(inst.operand(2)), null);
    }

    // ============ NEW_ARRAY ============

    private void executeNewArray(MirFrame frame, MirInst inst) {
        int size = frame.get(inst.operand(0)).asInt();
        // 检查局部变量类型决定数组元素类型
        MirLocal local = frame.function.getLocals().get(inst.getDest());
        String className = local.getType().getClassName();
        if ("[I".equals(className)) {
            frame.locals[inst.getDest()] = new NovaArray(NovaArray.ElementType.INT, size);
        } else if ("[D".equals(className)) {
            frame.locals[inst.getDest()] = new NovaArray(NovaArray.ElementType.DOUBLE, size);
        } else if ("[J".equals(className)) {
            frame.locals[inst.getDest()] = new NovaArray(NovaArray.ElementType.LONG, size);
        } else if ("[F".equals(className)) {
            frame.locals[inst.getDest()] = new NovaArray(NovaArray.ElementType.FLOAT, size);
        } else if ("[Z".equals(className)) {
            frame.locals[inst.getDest()] = new NovaArray(NovaArray.ElementType.BOOLEAN, size);
        } else if ("[C".equals(className)) {
            frame.locals[inst.getDest()] = new NovaArray(NovaArray.ElementType.CHAR, size);
        } else {
            // 通用 Object[] → 使用 NovaList 模拟
            NovaList list = new NovaList();
            for (int i = 0; i < size; i++) {
                list.add(NovaNull.NULL);
            }
            frame.locals[inst.getDest()] = list;
        }
    }

    // ============ NEW_COLLECTION ============

    private void executeNewCollection(MirFrame frame, MirInst inst) {
        // 当前 HirToMirLowering 不产生此操作码，预留处理
        frame.locals[inst.getDest()] = new NovaList();
    }

    // ============ TYPE_CHECK / TYPE_CAST / CONST_CLASS ============

    private void executeTypeCheck(MirFrame frame, MirInst inst) {
        NovaValue value = frame.get(inst.operand(0));
        String typeName = inst.extraAs();
        // reified 类型参数解析
        if (frame.reifiedTypes != null && frame.reifiedTypes.containsKey(typeName)) {
            typeName = frame.reifiedTypes.get(typeName);
        }
        frame.locals[inst.getDest()] = NovaBoolean.of(classRegistrar.isInstanceOf(value,typeName));
    }

    /**
     * 类型转换（统一使用 SamAdapter/NovaResult —— 与编译路径共享语义）。
     */
    private void executeTypeCast(MirFrame frame, MirInst inst) {
        NovaValue value = frame.get(inst.operand(0));
        String typeName = (String) inst.getExtra();
        if (typeName == null || typeName.isEmpty()) {
            frame.locals[inst.getDest()] = value;
            return;
        }

        boolean safeCast = typeName.startsWith("?|");
        String actualType = safeCast ? typeName.substring(2) : typeName;

        // Result/Ok/Err 特殊处理（与编译路径统一使用 NovaResult.castResult）
        if ("Ok".equals(actualType) || "Err".equals(actualType) || "Result".equals(actualType)) {
            Object javaVal = value != null ? value.toJavaValue() : null;
            Object result = com.novalang.runtime.NovaResult.castResult(javaVal, actualType, safeCast);
            frame.locals[inst.getDest()] = result == null ? NovaNull.NULL : AbstractNovaValue.fromJava(result);
            return;
        }

        // Nova 类类型检查（isInstanceOf 处理 Nova 自定义类）
        if (classRegistrar.isInstanceOf(value, actualType)) {
            frame.locals[inst.getDest()] = value;
            return;
        }

        // Java 类型转换 + SAM 适配（与编译路径统一使用 SamAdapter）
        try {
            Class<?> targetClass = JavaInterop.loadClass(toJavaDotName(actualType));
            Object javaVal = value != null ? value.toJavaValue() : null;
            Object result;
            if (safeCast) {
                result = com.novalang.runtime.SamAdapter.safeCastOrAdapt(javaVal, targetClass);
            } else {
                result = com.novalang.runtime.SamAdapter.castOrAdapt(javaVal, targetClass);
            }
            frame.locals[inst.getDest()] = result == null ? NovaNull.NULL : AbstractNovaValue.fromJava(result);
            return;
        } catch (ClassNotFoundException e) {
            // 不是 Java 类
        } catch (ClassCastException e) {
            if (safeCast) {
                frame.locals[inst.getDest()] = NovaNull.NULL;
                return;
            }
            throw new NovaRuntimeException(e.getMessage());
        }

        if (safeCast) {
            frame.locals[inst.getDest()] = NovaNull.NULL;
            return;
        }

        String operandType = value != null ? value.getNovaTypeName() : "null";
        throw new NovaRuntimeException("Cannot cast " + operandType + " to " + actualType + " (use as? for safe cast)");
    }

    private void executeConstClass(MirFrame frame, MirInst inst) {
        String className = inst.extraAs();
        try {
            Class<?> cls = JavaInterop.loadClass(toJavaDotName(className));
            frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(cls);
        } catch (ClassNotFoundException e) {
            // 可能是 Nova 类
            NovaValue classVal = interp.getEnvironment().tryGet(className);
            frame.locals[inst.getDest()] = classVal != null ? classVal : NovaNull.NULL;
        }
    }

    // ============ CLOSURE ============

    private void executeClosure(MirFrame frame, MirInst inst) {
        // 当前 HirToMirLowering 不产生此操作码（Lambda → 匿名类 + NEW_OBJECT）
        // 预留处理
        frame.locals[inst.getDest()] = NovaNull.NULL;
    }

    // ============ 辅助方法 ============

    private boolean isTruthy(NovaValue value) {
        if (value == null) return false;
        if (value instanceof NovaNull) return !value.isNull();
        return value.isTruthy();
    }

    /**
     * 融合比较+分支快速路径。双槽 raw int 时直接原始比较，否则回退到标准逻辑。
     */
    private boolean compareFused(MirFrame frame, BinaryOp op, int leftIdx, int rightIdx) {
        boolean leftRaw = frame.locals[leftIdx] == MirFrame.RAW_INT_MARKER;
        boolean rightRaw = frame.locals[rightIdx] == MirFrame.RAW_INT_MARKER;
        if (leftRaw && rightRaw) {
            long a = frame.rawLocals[leftIdx];
            long b = frame.rawLocals[rightIdx];
            switch (op) {
                case EQ: return a == b;
                case NE: return a != b;
                case LT: return a < b;
                case GT: return a > b;
                case LE: return a <= b;
                case GE: return a >= b;
                default: break;
            }
        }
        // 单侧 raw int 与 null 比较: raw int 永远不等于 null（避免装箱）
        if ((leftRaw || rightRaw) && (op == BinaryOp.EQ || op == BinaryOp.NE)) {
            NovaValue other = leftRaw ? frame.locals[rightIdx] : frame.locals[leftIdx];
            if (other instanceof NovaNull || other == null) {
                return op == BinaryOp.NE;
            }
        }
        // 回退：标准比较
        NovaValue left = frame.get(leftIdx);
        NovaValue right = frame.get(rightIdx);
        NovaValue result = generalBinary(left, right, op);
        return isTruthy(result);
    }

    /** MIR lowering 的 resolveMethodAlias 反向映射：Java 方法名 → Nova 方法名 */
    private boolean novaEquals(NovaValue a, NovaValue b) {
        return BinaryOps.novaEquals(a, b);
    }

    private Object unwrapNovaValue(NovaValue value) {
        if (value == null || value instanceof NovaNull) return null;
        return value.toJavaValue();
    }

    private NovaRuntimeException toRuntimeException(NovaValue ex) {
        if (ex instanceof NovaExternalObject) {
            Object javaObj = ex.toJavaValue();
            if (javaObj instanceof NovaRuntimeException) return (NovaRuntimeException) javaObj;
            if (javaObj instanceof RuntimeException) return new NovaRuntimeException(((RuntimeException) javaObj).getMessage());
        }
        return new NovaRuntimeException(ex.asString());
    }

    private NovaValue wrapException(NovaRuntimeException e) {
        // 返回异常消息字符串（与 HIR 路径行为一致）
        String msg = e.getMessage();
        return msg != null ? NovaString.of(msg) : NovaNull.NULL;
    }

    private MirFunction.TryCatchEntry findHandler(List<MirFunction.TryCatchEntry> entries, int blockId) {
        for (MirFunction.TryCatchEntry entry : entries) {
            if (blockId >= entry.tryStartBlock && blockId < entry.tryEndBlock) {
                return entry;
            }
        }
        return null;
    }

    /** 带异常类型匹配的 handler 查找——multi-catch 需要按类型过滤 */
    private MirFunction.TryCatchEntry findMatchingHandler(List<MirFunction.TryCatchEntry> entries,
                                                          int blockId, Throwable exception) {
        MirFunction.TryCatchEntry fallback = null;
        for (MirFunction.TryCatchEntry entry : entries) {
            if (blockId >= entry.tryStartBlock && blockId < entry.tryEndBlock) {
                // 通用 catch（无类型或 Exception）→ 直接匹配
                if (entry.exceptionType == null || "java/lang/Exception".equals(entry.exceptionType)
                        || "java/lang/Throwable".equals(entry.exceptionType)) {
                    if (fallback == null) fallback = entry;
                    continue;
                }
                // 精确类型匹配
                try {
                    Class<?> exClass = JavaInterop.loadClass(entry.exceptionType.replace('/', '.'));
                    Throwable cause = (exception instanceof NovaRuntimeException && exception.getCause() != null)
                            ? exception.getCause() : exception;
                    if (exClass.isInstance(cause) || exClass.isInstance(exception)) {
                        return entry;
                    }
                } catch (ClassNotFoundException ignored) {
                    if (fallback == null) fallback = entry;
                }
            }
        }
        // 没有精确匹配时用通用 catch 兜底
        return fallback;
    }

    private static final NovaValue[] EMPTY_ARGS = new NovaValue[0];
    private static final int[] EMPTY_OPERANDS = new int[0];

    private static final class NewObjectSite {
        final MirClassInfo classInfo;
        final NovaClass novaClass;
        final MirCallable constructor;
        final int arity;
        final boolean fastPath;
        final boolean scalarizable;
        final int fieldCount;

        NewObjectSite(MirClassInfo classInfo, NovaClass novaClass, MirCallable constructor,
                      int arity, boolean fastPath, boolean scalarizable, int fieldCount) {
            this.classInfo = classInfo;
            this.novaClass = novaClass;
            this.constructor = constructor;
            this.arity = arity;
            this.fastPath = fastPath;
            this.scalarizable = scalarizable;
            this.fieldCount = fieldCount;
        }
    }

    private static final class FieldAccessSite {
        NovaClass cachedClass;
        int fieldIndex = -1;
    }

    /** SET_STATIC/GET_STATIC 字符串解析缓存（避免每次 indexOf + substring） */
    private static final class StaticFieldSite {
        final String owner;
        final String fieldName;
        final boolean isModule;

        StaticFieldSite(String owner, String fieldName) {
            this.owner = owner;
            this.fieldName = fieldName;
            this.isModule = owner.endsWith("$Module");
        }
    }

    NovaValue[] collectArgsArray(MirFrame frame, int[] ops) {
        if (ops == null || ops.length == 0) return EMPTY_ARGS;
        NovaValue[] args = new NovaValue[ops.length];
        for (int i = 0; i < ops.length; i++) {
            args[i] = frame.get(ops[i]);
        }
        return args;
    }

}
