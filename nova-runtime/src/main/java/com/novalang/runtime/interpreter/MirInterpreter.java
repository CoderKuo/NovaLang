package com.novalang.runtime.interpreter;

import com.novalang.ir.mir.*;
import com.novalang.ir.hir.ClassKind;
import com.novalang.runtime.*;
import com.novalang.runtime.types.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MIR 瀛楄妭鐮佽В閲婂櫒銆?
 *
 * <p>鐩存帴瑙ｉ噴鎵ц MIR 鎸囦护锛坵hile + switch 寰幆锛夛紝鏇夸唬 HirEvaluator 鐨勬爲閬嶅巻妯″瀷銆?
 * 澶嶇敤鐜版湁 Interpreter 鐨勫叏灞€鐘舵€佸拰 MemberResolver 杩涜鎴愬憳瑙ｆ瀽銆?/p>
 */
final class MirInterpreter {

    // ===== MIR 鐗规畩鏍囪 =====
    private static final String MARKER_LAMBDA = "$Lambda$";
    private static final String MARKER_METHOD_REF = "$MethodRef$";

    // ===== 鐗规畩鏂规硶鍚?=====
    private static final String SPECIAL_CLINIT = "<clinit>";

    // ===== JVM 绫诲悕 =====
    private static final String JAVA_ARRAY_LIST = "java/util/ArrayList";
    private static final String JAVA_HASH_MAP = "java/util/HashMap";
    private static final String JAVA_LINKED_HASH_SET = "java/util/LinkedHashSet";
    private static final String JAVA_SYSTEM = "java/lang/System";

    /** GET_STATIC 缂撳瓨鍝ㄥ叺锛氳〃绀哄凡瑙ｆ瀽浣嗗瓧娈?绫讳笉瀛樺湪 */
    private static final Object STATIC_FIELD_MISS = new Object();

    /** JVM 鍐呴儴鍚?鈫?Java 鐐瑰垎鍚? "java/lang/String" 鈫?"java.lang.String" */
    private static String toJavaDotName(String internalName) {
        return internalName.replace("/", ".");
    }

    final Interpreter interp;
    final MemberResolver resolver;

    /** reified 绫诲瀷鍙傛暟浼犻€掞紙$PipeCall 鈫?executeFunction锛?*/
    String[] pendingReifiedTypeArgs;

    /** MirFrame 瀵硅薄姹狅紙閬垮厤閫掑綊璋冪敤鏃堕噸澶嶅垎閰嶆暟缁勶級 */
    private final MirFrame[] framePool = new MirFrame[32];
    private int framePoolTop = 0;

    /** 缂撳瓨鐨勬渶澶ч€掑綊娣卞害锛堥伩鍏嶆瘡娆¤皟鐢ㄨ蛋铏氭柟娉曢摼锛?*/
    private final int cachedMaxRecursionDepth;

    /** executeFrame 异常路径设置的 TCE 计数，供 fastCall 读取 */
    int lastTceCount;

    /** 妯″潡绾ф敞鍐岋細鍑芥暟鍚?鈫?MirCallable */
    final Map<String, MirCallable> mirFunctions = new HashMap<>();
    /** 妯″潡绾ф敞鍐岋細绫诲悕 鈫?MirClassInfo */
    final Map<String, MirClassInfo> mirClasses = new HashMap<>();

    /** 绫绘敞鍐屽櫒 */
    final MirClassRegistrar classRegistrar;
    /** 鏂规硶鍒嗘淳鍣?*/
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

    /** MIR 绫荤殑娉ㄥ唽淇℃伅 */
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
     * 瀛?MirInterpreter锛坅sync 绾跨▼鐢級銆?
     * 鍏变韩鐖剁骇鐨勫嚱鏁?绫绘敞鍐岃〃锛屾嫢鏈夌嫭绔嬬殑鍙彉鎵ц鐘舵€侊紙callStack銆乧allDepth 绛夛級銆?
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
     * 閲嶇疆妯″潡绾ф敞鍐岀姸鎬侊紙姣忔 executeModule 鍓嶈皟鐢級銆?
     */
    void resetState() {
        mirFunctions.clear();
        // 淇濈暀 lambda 鍖垮悕绫伙紙璺?evalRepl 璋冪敤鐨勯棴鍖呬粛闇€瑕佸畠浠級
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

    /** 杩斿洖鎵€鏈夊凡娉ㄥ唽鐨?Nova 绫?鎺ュ彛鍚嶇О锛堜緵涓嬫 evalRepl 缂栬瘧浣跨敤锛?*/
    Set<String> getKnownClassNames() {
        return classRegistrar.getKnownClassNames();
    }

    /** 杩斿洖宸茬煡鐨勬帴鍙ｅ悕锛堜緵璺?evalRepl 鐨?HirToMirLowering 璇嗗埆鎺ュ彛绫诲瀷锛?*/
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

    // ============ 妯″潡鎵ц ============

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

    // ============ 甯ф睜鍖?============

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
     * 蹇€熻皟鐢ㄨ矾寰勶細璺宠繃 MirCallable.callDirect 鐨勬墍鏈夐棿鎺ュ紑閿€銆?
     * 浠呯敤浜庢棤 this/鏃犳崟鑾?闈?init 鐨勭畝鍗曞嚱鏁帮紙濡傞《灞傚嚱鏁伴€掑綊锛夈€?
     * <ul>
     *   <li>鐩存帴浠庤皟鐢ㄨ€呭抚澶嶅埗鍙傛暟锛堜紶鎾?RAW_INT_MARKER锛岄浂瑁呯锛?/li>
     *   <li>甯ф睜鍖栵紙娑堥櫎 NovaValue[] + long[] 鍒嗛厤锛?/li>
     *   <li>callStack 杞婚噺鎺ㄥ叆锛堢┖鍙傚抚锛屽紓甯告椂鎯版€цˉ鍏呭弬鏁板€硷級</li>
     *   <li>浣跨敤缂撳瓨鐨?maxRecursionDepth锛堥伩鍏嶈櫄鏂规硶閾撅級</li>
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

    /** 甯у鐢ㄨ皟鐢ㄤ笂涓嬫枃锛氬皝瑁?lambda 璋冪敤鐨勫簳灞傜粏鑺?*/
    static final class BatchCtx {
        private final MirFrame frame;
        private final int slot;
        private final MirInterpreter mi;
        final NovaValue extraArg;
        /** lambda 鐨勫疄闄呭弬鏁版暟閲忥紙涓嶅惈 this锛夛紝鐢ㄤ簬 Map 鍙屽弬鍒嗘淳 */
        final int lambdaParamCount;

        BatchCtx(MirFrame frame, int slot, MirInterpreter mi, NovaValue extraArg, int lambdaParamCount) {
            this.frame = frame;
            this.slot = slot;
            this.mi = mi;
            this.extraArg = extraArg;
            this.lambdaParamCount = lambdaParamCount;
        }

        /** 鍗曞弬璋冪敤 */
        NovaValue call1(NovaValue arg) {
            frame.locals[slot] = arg;
            frame.currentBlockId = 0;
            frame.pc = 0;
            return mi.executeFrame(frame, -1);
        }

        /** 鍙屽弬璋冪敤 */
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

    /** 宸叉敞鍐岀殑 NovaList 鎵归噺鎿嶄綔锛堟坊鍔犳柊 HOF 鍙渶鍦ㄦ娉ㄥ唽涓€琛岋級 */
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
            for (int i = 0; i < n; i++) c.call1(elems.get(i));
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
            for (int i = 0; i < n; i++) c.call2(NovaInt.of(i), elems.get(i));
            return NovaNull.UNIT;
        });
    }

    // ==================== Map 鎵归噺鎿嶄綔 ====================

    /** Map 鎵归噺鎿嶄綔鍑芥暟寮忔帴鍙ｏ細鐩存帴鎿嶄綔 NovaMap锛屽悇 op 鑷杩唬 */
    @FunctionalInterface
    interface MapBatchOp {
        /** @return 缁撴灉锛屾垨 null 琛ㄧず姝?op 涓嶉€傜敤锛堜緥濡傚弬鏁版暟閲忎笉鍖归厤锛夆啋 鍥為€€鍒?stdlib */
        NovaValue exec(NovaMap map, BatchCtx c);
    }

    private static final Map<String, MapBatchOp> MAP_BATCH_OPS = new HashMap<>();
    static { registerMapBatchOps(); }

    @SuppressWarnings("unchecked")
    private static void registerMapBatchOps() {
        // ---- 鍙屽弬鏂规硶锛?-param only, 1-param 鍥為€€鍒?stdlib bridge 澶勭悊 entry/implicit-it锛?----
        MAP_BATCH_OPS.put("forEach", (map, c) -> {
            if (c.lambdaParamCount < 2) return null;
            for (Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet()) c.call2(e.getKey(), e.getValue());
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
        // ---- 鍗曞弬鏂规硶锛堜换鎰忓弬鏁版暟閲忓潎鍙級 ----
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
     * NovaMap 楂橀樁鏂规硶甯у鐢ㄦ壒閲忔墽琛屻€?
     * <p>鍙屽弬鏂规硶锛坒orEach/filter/map/...锛変粎澶勭悊 2-param lambda锛?-param 鍥為€€鍒?stdlib bridge銆?
     * 鍗曞弬鏂规硶锛坒ilterKeys/filterValues锛夌洿鎺ュ鐞嗐€?
     *
     * @return 缁撴灉锛屾垨 null锛堟湭娉ㄥ唽/鍙傛暟涓嶅尮閰嶏級
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
     * NovaList 楂橀樁鏂规硶甯у鐢ㄦ壒閲忔墽琛岋紙缁熶竴鍏ュ彛锛夈€?
     * <p>鏍规嵁 BATCH_OPS 娉ㄥ唽琛ㄥ垎娲俱€傛坊鍔犳柊 HOF 鍙渶娉ㄥ唽涓€琛屻€?
     *
     * @param extraArg fold/reduce 鐨勫垵濮嬪€硷紝鍏朵粬鏂规硶浼?null
     * @return 鎿嶄綔缁撴灉锛屾垨 null 濡傛灉 methodName 鏈敞鍐?
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

    // ============ 鍑芥暟鎵ц ============

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
        // 鍙傛暟缁戝畾锛氬墠 N 涓?locals = args
        for (int i = 0; i < args.length && i < frame.locals.length; i++) {
            frame.locals[i] = args[i];
        }
        try {
        // 缁戝畾 reified 绫诲瀷鍙傛暟鍒版爤甯?
        if (pendingReifiedTypeArgs != null) {
            List<String> typeParams = func.getTypeParams();
            if (!typeParams.isEmpty()) {
                Map<String, String> reifiedMap = new HashMap<>();
                for (int i = 0; i < Math.min(pendingReifiedTypeArgs.length, typeParams.size()); i++) {
                    reifiedMap.put(typeParams.get(i), pendingReifiedTypeArgs[i]);
                }
                frame.reifiedTypes = reifiedMap;
                // 缁戝畾 __reified_T 灞€閮ㄥ彉閲忥紙渚?T::class 寮曠敤浣跨敤锛?
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

        // 娆＄骇鏋勯€犲櫒濮旀墭锛歞elegation 淇℃伅浣滀负鍏冩暟鎹瓨鍌ㄥ湪 MirFunction 涓?
        if (func.hasDelegation()) {
            // block 0 = delegation args 涓撶敤鍧楋紙鐢?lowerConstructor 閲嶆帓鍒?position 0锛?
            // block 1+ = 鏋勯€犲櫒 body锛堝彲鑳戒负绌猴級
            List<BasicBlock> blocks = func.getBlocks();
            if (!blocks.isEmpty()) {
                for (MirInst inst : blocks.get(0).getInstructions()) {
                    executeInst(frame, inst);
                }
            }
            // 璇诲彇濮旀墭鍙傛暟
            int[] delegLocals = func.getDelegationArgLocals();
            NovaValue thisObj = frame.locals[0];
            NovaValue[] delegArgs = new NovaValue[delegLocals.length];
            for (int i = 0; i < delegLocals.length; i++) {
                delegArgs[i] = delegLocals[i] < frame.locals.length ? frame.get(delegLocals[i]) : NovaNull.NULL;
            }
            // 璋冪敤鐩爣鏋勯€犲櫒锛堜富鏋勯€犲櫒鎴栧叾浠栨绾ф瀯閫犲櫒锛?
            if (thisObj instanceof NovaObject) {
                NovaClass cls = ((NovaObject) thisObj).getNovaClass();
                NovaCallable ctor = cls.getConstructorByArity(delegArgs.length);
                // 璺宠繃鑷韩锛堥伩鍏嶉€掑綊锛?
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
            // 鎵ц濮旀墭鍚庣殑鏋勯€犲櫒 body锛坆lock 1+锛?
            if (blocks.size() > 1) {
                executeFrame(frame, blocks.get(1).getId());
            }
            return thisObj;
        }

        // 瓒呯被鏋勯€犲櫒璋冪敤锛歴uperInitArgLocals 浣滀负鍏冩暟鎹瓨鍌ㄥ湪 MirFunction 涓?
        if (func.hasSuperInitArgs()) {
            // 鎵ц entry block 鐨?CONST 鎸囦护鏉ュ垵濮嬪寲鍚堟垚灞€閮ㄥ彉閲?
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
                // 浣跨敤 MirFunction 涓婅褰曠殑瓒呯被鍚嶏紙鑰岄潪 thisObj.getNovaClass()锛夛紝
                // 閬垮厤缁ф壙閾句腑 B鈫扐 鏌ユ壘鏃惰鐢ㄥ叿浣撶被 C 鐨勮秴绫诲鑷存棤闄愰€掑綊
                NovaClass superclass = null;
                String superName = func.getSuperClassName();
                if (superName != null) {
                    NovaValue superVal = interp.getEnvironment().tryGet(superName);
                    if (superVal instanceof NovaClass) {
                        superclass = (NovaClass) superVal;
                    }
                }
                if (superclass == null) {
                    // 鍥為€€锛氫粠杩愯鏃跺璞¤幏鍙栵紙浠呭湪鏃?superClassName 鍏冩暟鎹椂锛?
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
            // 缁х画鎵ц褰撳墠鏋勯€犲櫒浣擄紙SET_FIELD 绛夛級
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

        // entry block = 鎸囧畾鐨勮捣濮嬪潡鎴栫涓€涓潡鐨?ID
        frame.currentBlockId = startBlockId >= 0 ? startBlockId
                : frame.function.getBlocks().get(0).getId();

        List<MirFunction.TryCatchEntry> tryCatches = frame.function.getTryCatchEntries();
        TailIntLoopPlan tailIntLoopPlan = isTailIntLoopCandidate(frame.function)
                ? resolveTailIntLoopPlan(frame.function, blockArr) : null;
        TailIntLoopPlan3 tailIntLoopPlan3 = isTailIntLoopCandidate3(frame.function)
                ? resolveTailIntLoopPlan3(frame.function, blockArr) : null;
        StringAccumLoopPlan stringAccumLoopPlan = resolveStringAccumLoopPlan(frame.function);

        int tceCount = 0;  // TCE 灏鹃€掑綊杞惊鐜殑杩唬璁℃暟
        int prevBlockId = -1;
        while (true) {
            BasicBlock block = blockArr[frame.currentBlockId];
            MirInst[] insts = block.getInstArray();

            // 鍥炶竟妫€娴嬶細璺宠浆鍒?ID <= 褰撳墠鍧楃殑鍧?鈫?寰幆杩唬锛屾鏌ュ畨鍏ㄩ檺鍒?
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
                // 鎵ц鍧楀唴鎵€鏈夋寚浠わ紙鐑搷浣滅爜鍐呰仈锛屽噺灏戞柟娉曡皟鐢ㄥ紑閿€锛?
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

                // 澶勭悊缁堟鎸囦护
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
                        // 绌块€忥細Goto 鐩爣涓虹┖鎸囦护鍧?+ Branch 鈫?鐩存帴璇勪及 Branch锛岀渷涓€娆″潡杞崲
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
                        throw toRuntimeException(ex);
                    }
                    default: // UNREACHABLE
                        throw new NovaRuntimeException("Reached unreachable code");
                }
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

    // ============ 鎸囦护鍒嗘淳 ============

    private void executeInst(MirFrame frame, MirInst inst) {
        switch (inst.getOp()) {
            // ===== 甯搁噺鍔犺浇 =====
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

            // ===== 鍙橀噺 =====
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

            // ===== 绠楁湳/閫昏緫 =====
            case BINARY:
                executeBinaryRaw(frame, inst);
                break;
            case UNARY:
                executeUnary(frame, inst);
                break;

            // ===== 瀵硅薄绯荤粺 =====
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

            // ===== 璋冪敤 =====
            case INVOKE_VIRTUAL:
            case INVOKE_INTERFACE:
            case INVOKE_SPECIAL:
                callDispatcher.executeInvokeVirtual(frame, inst);
                break;
            case INVOKE_STATIC:
                callDispatcher.executeInvokeStatic(frame, inst);
                break;

            // ===== 闆嗗悎/鏁扮粍 =====
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

            // ===== 绫诲瀷 =====
            case TYPE_CHECK:
                executeTypeCheck(frame, inst);
                break;
            case TYPE_CAST:
                executeTypeCast(frame, inst);
                break;
            case CONST_CLASS:
                executeConstClass(frame, inst);
                break;

            // ===== 闂寘 =====
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
     * 鍙屾Ы BINARY 蹇€熻矾寰勶細涓や釜鎿嶄綔鏁板潎涓?RAW_INT_MARKER 鏃剁洿鎺ュ湪 rawLocals 涓?
     * 鎵ц long 杩愮畻锛岀畻鏈粨鏋滃瓨鍥?rawLocals锛堜笉瑁呯锛夛紝姣旇緝缁撴灉瀛?NovaBoolean銆?
     * 闈?raw 鎿嶄綔鏁板洖閫€鍒?executeBinary锛堥€氳繃 frame.get() 鑷姩鍏峰寲锛夈€?
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
        // 娣峰悎蹇€熻矾寰勶細涓€涓?raw + 涓€涓?NovaInt锛屾垨涓や釜 NovaInt 鈫?缁撴灉瀛?raw锛堥€嗚绠憋級
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

        // 蹇€熻矾寰勶細int + int
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
        // 閫昏緫杩愮畻
        if (op == BinaryOp.AND) return NovaBoolean.of(isTruthy(left) && isTruthy(right));
        if (op == BinaryOp.OR) return NovaBoolean.of(isTruthy(left) || isTruthy(right));

        // 鐩哥瓑鎬ф瘮杈冿紙澶勭悊 null锛?
        if (op == BinaryOp.EQ) return NovaBoolean.of(novaEquals(left, right));
        if (op == BinaryOp.NE) return NovaBoolean.of(!novaEquals(left, right));
        if (op == BinaryOp.REF_EQ) return NovaBoolean.of(left == right);
        if (op == BinaryOp.REF_NE) return NovaBoolean.of(left != right);

        // 绠楁湳杩愮畻 鈫?BinaryOps
        switch (op) {
            case ADD: return BinaryOps.add(left, right, interp);
            case SUB: return BinaryOps.sub(left, right, interp);
            case MUL: return BinaryOps.mul(left, right, interp);
            case DIV: return BinaryOps.div(left, right, interp);
            case MOD: return BinaryOps.mod(left, right, interp);
            default: break;
        }

        // 姣旇緝杩愮畻 鈫?BinaryOps
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

        // 浣嶈繍绠?
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
                    // 灏濊瘯 unaryMinus 杩愮畻绗﹂噸杞?
                    NovaCallable method = ((NovaObject) operand).getMethod("unaryMinus");
                    if (method != null) {
                        result = method.call(interp, Collections.singletonList(operand));
                        break;
                    }
                    // 灏濊瘯 unaryPlus锛圥OS 鎿嶄綔鐮佸鐢?NEG 鏃?extra 鍖哄垎锛?
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
            Class<?> javaClass = Class.forName(toJavaDotName(className));
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
        // 鏌ユ壘 Lambda 鐨?MirClass锛堝湪 additionalClasses 涓敞鍐岋級
        MirClassInfo lambdaInfo = mirClasses.get(lambdaClassName);
        if (lambdaInfo != null) {
            // 鏌ユ壘 invoke 鏂规硶
            MirFunction invokeFunc = null;
            for (MirFunction method : lambdaInfo.mirClass.getMethods()) {
                if ("invoke".equals(method.getName())) {
                    invokeFunc = method;
                    break;
                }
            }
            if (invokeFunc != null) {
                // 鏋勫缓鎹曡幏鍙橀噺瀛楁鏄犲皠锛堝瓧娈靛悕 鈫?鍊硷級
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
                // （环境查找 → 类构造器 → scope receiver → stdlib → Java 类等）
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

        // NovaPair / 其他带 resolveMember 的类型
        NovaValue memberVal = target.resolveMember(fieldName);
        if (memberVal != null) {
            frame.locals[inst.getDest()] = memberVal;
            return;
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
        String extra = inst.extraAs();
        int pipe = extra.indexOf('|');
        String owner = extra.substring(0, pipe);
        int pipe2 = extra.indexOf('|', pipe + 1);
        String fieldName = pipe2 >= 0 ? extra.substring(pipe + 1, pipe2) : extra.substring(pipe + 1);

        // $Module 顶层静态字段
        if (owner.endsWith("$Module")) {
            NovaValue val = moduleStaticFields.get(fieldName);
            frame.locals[inst.getDest()] = val != null ? val : NovaNull.NULL;
            return;
        }

        // 鐗规畩澶勭悊 System.out
        if (JAVA_SYSTEM.equals(owner) && "out".equals(fieldName)) {
            frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(interp.getStdout());
            return;
        }
        if (JAVA_SYSTEM.equals(owner) && "err".equals(fieldName)) {
            frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(interp.getStderr());
            return;
        }
        // Dispatchers: 浼樺厛浣跨敤 Builtins 娉ㄥ唽鐨?NovaMap锛堟敮鎸佸姩鎬?Main 娉ㄥ叆锛?
        if ("DISPATCHERS".equals(fieldName)) {
            NovaValue dispatchers = interp.getGlobals().tryGet("Dispatchers");
            if (dispatchers != null) {
                frame.locals[inst.getDest()] = dispatchers;
                return;
            }
        }

        // Nova 绫荤殑闈欐€佸瓧娈?
        String normalizedOwner = toJavaDotName(owner);
        MirClassInfo classInfo = mirClasses.get(owner);
        if (classInfo != null) {
            NovaValue staticVal = classInfo.novaClass.getStaticField(fieldName);
            if (staticVal != null) {
                frame.locals[inst.getDest()] = staticVal;
                return;
            }
        }

        // 浠庣幆澧冩煡鎵剧被
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

        // Java 闈欐€佸瓧娈碉細inst.cache 缂撳瓨 MethodHandle锛堥伩鍏嶆瘡娆″弽灏勶級
        Object cached = inst.cache;
        if (cached instanceof java.lang.invoke.MethodHandle) {
            try {
                frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(
                        ((java.lang.invoke.MethodHandle) cached).invoke());
                return;
            } catch (Throwable e) {
                throw new NovaRuntimeException("Failed to access static field "
                        + owner + "." + fieldName + ": " + e.getMessage(), e);
            }
        }
        if (cached == STATIC_FIELD_MISS) {
            frame.locals[inst.getDest()] = NovaNull.NULL;
            return;
        }
        // 棣栨鎵ц锛氳В鏋愬苟缂撳瓨
        try {
            String javaDotName = toJavaDotName(owner);
            if (!interp.getSecurityPolicy().isClassAllowed(javaDotName)) {
                throw NovaSecurityPolicy.denied("Cannot access class: " + javaDotName);
            }
            Class<?> javaClass = Class.forName(javaDotName);
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
        String extra = inst.extraAs();
        int pipe = extra.indexOf('|');
        String owner = extra.substring(0, pipe);
        int pipe2 = extra.indexOf('|', pipe + 1);
        String fieldName = pipe2 >= 0 ? extra.substring(pipe + 1, pipe2) : extra.substring(pipe + 1);
        NovaValue value = frame.get(inst.operand(0));

        // $Module 顶层静态字段
        if (owner.endsWith("$Module")) {
            moduleStaticFields.put(fieldName, value);
            return;
        }

        MirClassInfo classInfo = mirClasses.get(owner);
        if (classInfo != null) {
            classInfo.novaClass.setStaticField(fieldName, value);
            return;
        }

        String normalizedOwner = toJavaDotName(owner);
        NovaValue classVal = interp.getEnvironment().tryGet(normalizedOwner);
        if (classVal == null) classVal = interp.getEnvironment().tryGet(owner);
        if (classVal instanceof NovaClass) {
            ((NovaClass) classVal).setStaticField(fieldName, value);
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
                // 闈?int 绱㈠紩锛圧ange 鍒囩墖绛夛級鈫?閫氱敤璺緞
                frame.locals[inst.getDest()] = resolver.performIndex(target, frame.get(idxReg), null);
                return;
            }
            NovaValue elem = ((NovaList) target).getElements().get(idx);
            int dest = inst.getDest();
            // 閫嗚绠憋細NovaInt 鍏冪礌 鈫?raw 瀛樺偍锛屼娇鍚庣画 BINARY 璧扮函 raw 蹇€熻矾寰?
            if (elem instanceof NovaInt) {
                frame.rawLocals[dest] = ((NovaInt) elem).getValue();
                frame.locals[dest] = MirFrame.RAW_INT_MARKER;
            } else {
                frame.locals[dest] = elem;
            }
            return;
        }
        // NovaArray 蹇€熻矾寰?
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
        // 閫氱敤璺緞锛歋tring/Map/Range/Pair/杩愮畻绗﹂噸杞?
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
        // NovaArray 蹇€熻矾寰?
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
                // raw int 鐩撮€氾細璺宠繃 NovaValue 瑁呯/鎷嗙
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
        // 妫€鏌ュ眬閮ㄥ彉閲忕被鍨嬪喅瀹氭暟缁勫厓绱犵被鍨?
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
            // 閫氱敤 Object[] 鈫?浣跨敤 NovaList 妯℃嫙
            NovaList list = new NovaList();
            for (int i = 0; i < size; i++) {
                list.add(NovaNull.NULL);
            }
            frame.locals[inst.getDest()] = list;
        }
    }

    // ============ NEW_COLLECTION ============

    private void executeNewCollection(MirFrame frame, MirInst inst) {
        // 褰撳墠 HirToMirLowering 涓嶄骇鐢熸鎿嶄綔鐮侊紝棰勭暀澶勭悊
        frame.locals[inst.getDest()] = new NovaList();
    }

    // ============ TYPE_CHECK / TYPE_CAST / CONST_CLASS ============

    private void executeTypeCheck(MirFrame frame, MirInst inst) {
        NovaValue value = frame.get(inst.operand(0));
        String typeName = inst.extraAs();
        // reified 绫诲瀷鍙傛暟瑙ｆ瀽
        if (frame.reifiedTypes != null && frame.reifiedTypes.containsKey(typeName)) {
            typeName = frame.reifiedTypes.get(typeName);
        }
        frame.locals[inst.getDest()] = NovaBoolean.of(classRegistrar.isInstanceOf(value,typeName));
    }

    private void executeTypeCast(MirFrame frame, MirInst inst) {
        NovaValue value = frame.get(inst.operand(0));
        String typeName = (String) inst.getExtra();

        // SAM 杞崲锛歀ambda 鈫?Java 鍑芥暟寮忔帴鍙ｏ紙璺宠繃瀹夊叏杞崲 ?| 鍓嶇紑锛?
        if (typeName != null && !typeName.startsWith("?|")) {
            // 鎻愬彇 callable锛氱洿鎺?NovaCallable 鎴?MIR lambda锛圢ovaObject with invoke锛?
            NovaCallable callable = null;
            if (value instanceof NovaCallable) {
                callable = (NovaCallable) value;
            } else if (value instanceof NovaObject) {
                NovaBoundMethod bound = ((NovaObject) value).getBoundMethod("invoke");
                if (bound != null) callable = bound;
            }
            if (callable != null) {
                try {
                    Class<?> targetClass = Class.forName(toJavaDotName(typeName));
                    if (targetClass.isInterface()) {
                        Object proxy = JavaInteropHelper.createSamProxy(targetClass, callable, interp);
                        frame.locals[inst.getDest()] = new NovaExternalObject(proxy);
                        return;
                    }
                } catch (ClassNotFoundException e) {
                    // 涓嶆槸 Java 绫伙紝鍥為€€
                }
            }
        }

        // 瀹夊叏杞崲 as? 鈥?瀵归潪鍖归厤绫诲瀷杩斿洖 null
        if (typeName != null && value != null && !(value instanceof NovaNull)) {
            // 妫€鏌ユ槸鍚︽湁 safe 鏍囪锛坋xtra 鍙兘鍖呭惈 "?|type"锛?
            if (typeName.startsWith("?|")) {
                String realType = typeName.substring(2);
                if (!classRegistrar.isInstanceOf(value, realType)) {
                    frame.locals[inst.getDest()] = NovaNull.NULL;
                    return;
                }
                // 绉婚櫎瀹夊叏鏍囪锛屽悗缁寜绫诲瀷鍖归厤澶勭悊
                typeName = realType;
            }

            // 寮哄埗杞崲锛氶獙璇佺被鍨嬪吋瀹规€?
            if (!typeName.isEmpty() && !classRegistrar.isInstanceOf(value, typeName)) {
                String operandType = value.getNovaTypeName();
                throw new NovaRuntimeException(
                    "Cannot cast " + operandType + " to " + typeName +
                    " (use as? for safe cast)"
                );
            }
        }

        frame.locals[inst.getDest()] = value;
    }

    private void executeConstClass(MirFrame frame, MirInst inst) {
        String className = inst.extraAs();
        try {
            Class<?> cls = Class.forName(toJavaDotName(className));
            frame.locals[inst.getDest()] = AbstractNovaValue.fromJava(cls);
        } catch (ClassNotFoundException e) {
            // 鍙兘鏄?Nova 绫?
            NovaValue classVal = interp.getEnvironment().tryGet(className);
            frame.locals[inst.getDest()] = classVal != null ? classVal : NovaNull.NULL;
        }
    }

    // ============ CLOSURE ============

    private void executeClosure(MirFrame frame, MirInst inst) {
        // 褰撳墠 HirToMirLowering 涓嶄骇鐢熸鎿嶄綔鐮侊紙Lambda 鈫?鍖垮悕绫?+ NEW_OBJECT锛?
        // 棰勭暀澶勭悊
        frame.locals[inst.getDest()] = NovaNull.NULL;
    }

    // ============ 杈呭姪鏂规硶 ============

    private boolean isTruthy(NovaValue value) {
        if (value == null) return false;
        if (value instanceof NovaNull) return !value.isNull();
        return value.isTruthy();
    }

    /**
     * 铻嶅悎姣旇緝+鍒嗘敮蹇€熻矾寰勩€傚弻妲?raw int 鏃剁洿鎺ュ師濮嬫瘮杈冿紝鍚﹀垯鍥為€€鍒版爣鍑嗛€昏緫銆?
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
        // 鍗曚晶 raw int 涓?null 姣旇緝: raw int 姘歌繙涓嶇瓑浜?null锛堥伩鍏嶈绠憋級
        if ((leftRaw || rightRaw) && (op == BinaryOp.EQ || op == BinaryOp.NE)) {
            NovaValue other = leftRaw ? frame.locals[rightIdx] : frame.locals[leftIdx];
            if (other instanceof NovaNull || other == null) {
                return op == BinaryOp.NE;
            }
        }
        // 鍥為€€锛氭爣鍑嗘瘮杈?
        NovaValue left = frame.get(leftIdx);
        NovaValue right = frame.get(rightIdx);
        NovaValue result = generalBinary(left, right, op);
        return isTruthy(result);
    }

    /** MIR lowering 鐨?resolveMethodAlias 鍙嶅悜鏄犲皠锛欽ava 鏂规硶鍚?鈫?Nova 鏂规硶鍚?*/
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
        // 杩斿洖寮傚父娑堟伅瀛楃涓诧紙涓?HIR 璺緞琛屼负涓€鑷达級
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
                    Class<?> exClass = Class.forName(entry.exceptionType.replace('/', '.'));
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

    NovaValue[] collectArgsArray(MirFrame frame, int[] ops) {
        if (ops == null || ops.length == 0) return EMPTY_ARGS;
        NovaValue[] args = new NovaValue[ops.length];
        for (int i = 0; i < ops.length; i++) {
            args[i] = frame.get(ops[i]);
        }
        return args;
    }

}
