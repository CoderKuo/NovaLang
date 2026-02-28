package nova.runtime.interpreter;

import com.novalang.ir.mir.*;
import com.novalang.ir.hir.ClassKind;
import nova.runtime.*;
import nova.runtime.types.*;

import java.util.*;

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

    /** 模块级注册：函数名 → MirCallable */
    final Map<String, MirCallable> mirFunctions = new HashMap<>();
    /** 模块级注册：类名 → MirClassInfo */
    final Map<String, MirClassInfo> mirClasses = new HashMap<>();

    /** 类注册器 */
    final MirClassRegistrar classRegistrar;
    /** 方法分派器 */
    final MirCallDispatcher callDispatcher;

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
        callDispatcher.resetState();
    }

    /** 返回所有已注册的 Nova 类/接口名称（供下次 evalRepl 编译使用） */
    Set<String> getKnownClassNames() {
        return classRegistrar.getKnownClassNames();
    }

    /** 返回已知的接口名（供跨 evalRepl 的 HirToMirLowering 识别接口类型） */
    Set<String> getKnownInterfaceNames() {
        return classRegistrar.getKnownInterfaceNames();
    }

    // ============ 模块执行 ============

    NovaValue executeModule(MirModule module) {
        NovaValue result = NovaNull.UNIT;

        // 0. 处理 Java import（在类注册之前，使 Java 类在顶层代码中可用）
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

        // 1. 注册类（在 import 之前，使循环依赖能找到当前模块的类定义）
        Set<String> moduleClassNames = new HashSet<>();
        for (MirClass cls : module.getClasses()) {
            moduleClassNames.add(cls.getName());
        }
        classRegistrar.setCurrentModuleClassNames(moduleClassNames);
        for (MirClass cls : module.getClasses()) {
            classRegistrar.registerClass(cls);
        }
        classRegistrar.clearCurrentModuleClassNames();

        // 2. 注册全局函数（在 import 之前，使循环依赖能找到当前模块的函数定义）
        // 函数是惰性的——注册时不执行函数体，所以不依赖 import 提供的符号
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

        // 3. 处理 Nova 模块 import（内置模块 + 文件模块）
        // 放在类/函数注册之后：循环依赖时对方模块能通过 ModuleLoader 缓存的环境找到已注册的符号
        for (MirModule.NovaImportInfo imp : module.getNovaImports()) {
            String qn = imp.qualifiedName;
            String[] parts = qn.split("\\.");

            // 内置模块（nova.io, nova.json 等）
            String builtinModule = BuiltinModuleRegistry.resolveModuleName(java.util.Arrays.asList(parts));
            if (builtinModule != null) {
                BuiltinModuleRegistry.load(builtinModule, interp.getEnvironment(), interp);
                continue;
            }

            // 尝试作为 Java 类
            Class<?> clazz = interp.resolveJavaClass(qn);
            if (clazz != null) {
                if (interp.getSecurityPolicy().isClassAllowed(clazz.getName())) {
                    String simpleName = imp.alias != null ? imp.alias : clazz.getSimpleName();
                    interp.getEnvironment().redefine(simpleName,
                            new JavaInterop.NovaJavaClass(clazz), false);
                }
                continue;
            }

            // 文件模块
            if (interp.moduleLoader == null) {
                throw new NovaRuntimeException("Cannot resolve module: " + qn + " (no module loader)");
            }
            java.util.List<String> pathParts;
            String symbolName;
            if (imp.wildcard) {
                pathParts = new java.util.ArrayList<>(java.util.Arrays.asList(parts));
                symbolName = null;
            } else {
                pathParts = new java.util.ArrayList<>();
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

        // 2.5 注册扩展属性
        for (MirModule.ExtensionPropertyInfo epInfo : module.getExtensionProperties()) {
            MirCallable getter = mirFunctions.get(epInfo.getterFuncName);
            if (getter != null) {
                String novaTypeName = com.novalang.compiler.NovaTypeNames.fromBoxedInternalName(
                        epInfo.receiverType);
                if (novaTypeName == null) {
                    novaTypeName = toJavaDotName(epInfo.receiverType);
                }
                interp.registerExtensionProperty(novaTypeName, epInfo.propertyName, getter);
            }
        }

        // 2.6 注册扩展函数（使 MemberResolver 在跨 evalRepl 时可发现）
        for (MirModule.ExtensionFunctionInfo efInfo : module.getExtensionFunctions()) {
            MirCallable func = mirFunctions.get(efInfo.functionName);
            if (func != null) {
                String novaTypeName = com.novalang.compiler.NovaTypeNames.fromBoxedInternalName(
                        efInfo.receiverType);
                if (novaTypeName == null) {
                    novaTypeName = toJavaDotName(efInfo.receiverType);
                }
                interp.registerNovaExtension(novaTypeName, efInfo.functionName, func);
            }
        }

        // 3. 执行类的静态初始化器 (<clinit>)
        for (MirClass cls : module.getClasses()) {
            for (MirFunction method : cls.getMethods()) {
                if (SPECIAL_CLINIT.equals(method.getName())) {
                    executeFunction(method, new NovaValue[0]);
                }
            }
        }

        // 3.5 枚举类后处理：将 NovaClass 转换为 NovaEnum + NovaEnumEntry
        for (MirClass cls : module.getClasses()) {
            if (cls.getKind() == ClassKind.ENUM) {
                classRegistrar.finalizeEnumClass(cls);
            }
        }

        // 3.6 单例对象: 将 Environment 中的 NovaClass 替换为 INSTANCE（NovaObject）
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

        // 4. 执行 main 函数，并将顶层变量导出到 Environment
        if (mainFunc != null) {
            MirFrame frame = new MirFrame(mainFunc);
            result = executeFrame(frame, -1);

            // 导出 main 函数中的命名局部变量到 Environment
            // （使顶层 val/var 对后续 eval 调用可见，与 HIR 路径行为一致）
            for (MirLocal local : mainFunc.getLocals()) {
                String name = local.getName();
                if (name != null && !name.startsWith("$")
                        && local.getIndex() < frame.locals.length) {
                    NovaValue value = frame.getOrNull(local.getIndex());
                    if (value != null) {
                        if (interp.getEnvironment().contains(name)) {
                            // 已通过 defineVal/defineVar 注册 → 更新值（保持原有 mutability）
                            boolean isMutable = !interp.getEnvironment().isVal(name);
                            interp.getEnvironment().redefine(name, value, isMutable);
                        } else {
                            interp.getEnvironment().redefine(name, value, false);
                        }
                    }
                }
            }
        }

        return result;
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
        MirFrame calleeFrame = acquireFrame(targetFunc);
        int[] ops = inst.getOperands();
        if (ops != null) {
            for (int i = 0; i < ops.length; i++) {
                int srcIdx = ops[i];
                calleeFrame.locals[i] = callerFrame.locals[srcIdx];
                if (callerFrame.locals[srcIdx] == MirFrame.RAW_INT_MARKER) {
                    calleeFrame.rawLocals[i] = callerFrame.rawLocals[srcIdx];
                }
            }
        }
        // 推入空参帧：确保内部调用 captureStackTrace() 时本帧可见
        interp.callStack.push(NovaCallFrame.fromMirCallable(
                targetFunc.getName(), Collections.emptyList()));
        try {
            return executeFrame(calleeFrame, -1);
        } catch (NovaRuntimeException e) {
            if (e.getNovaStackTrace() == null) {
                // 替换栈顶空参帧为带参数值的帧（冷路径）
                interp.callStack.pop();
                int paramCount = targetFunc.getParams().size();
                List<NovaValue> paramVals = new ArrayList<>(paramCount);
                for (int i = 0; i < paramCount; i++) {
                    paramVals.add(calleeFrame.get(i));
                }
                interp.callStack.push(NovaCallFrame.fromMirCallable(
                        targetFunc.getName(), paramVals));
                String trace = interp.captureStackTraceString();
                // TCE 折叠：尾递归转循环后 tceCount 为迭代次数，合成折叠提示
                if (calleeFrame.tceCount > 0) {
                    trace += "  ... " + calleeFrame.tceCount + " tail-call frames omitted ...\n";
                }
                e.setNovaStackTrace(trace);
            }
            throw e;
        } finally {
            interp.callStack.pop();
            releaseFrame(calleeFrame);
            interp.callDepth--;
        }
    }

    // ============ 列表高阶方法批量执行 ============

    /** 批量操作函数式接口 */
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
        interp.callStack.push(NovaCallFrame.fromMirCallable(displayName, Collections.emptyList()));
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
        interp.callStack.push(NovaCallFrame.fromMirCallable(displayName, Collections.emptyList()));
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

    NovaValue executeFunction(MirFunction func, NovaValue[] args) {
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

        // 次级构造器委托：delegation 信息作为元数据存储在 MirFunction 上
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

        // 超类构造器调用：superInitArgLocals 作为元数据存储在 MirFunction 上
        if (func.hasSuperInitArgs()) {
            // 执行 entry block 的 CONST 指令来初始化合成局部变量
            List<BasicBlock> blocks = func.getBlocks();
            if (!blocks.isEmpty()) {
                for (MirInst inst : blocks.get(0).getInstructions()) {
                    if (inst.getOp().name().startsWith("CONST_")) {
                        executeInst(frame, inst);
                    }
                }
            }
            int[] superLocals = func.getSuperInitArgLocals();
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

        return executeFrame(frame, -1);
        } finally {
            releaseFrame(frame);
        }
    }

    private NovaValue executeFrame(MirFrame frame, int startBlockId) {
        BasicBlock[] blockArr = frame.function.getBlockArr();
        if (blockArr.length == 0) return NovaNull.UNIT;

        // entry block = 指定的起始块或第一个块的 ID
        frame.currentBlockId = startBlockId >= 0 ? startBlockId
                : frame.function.getBlocks().get(0).getId();

        List<MirFunction.TryCatchEntry> tryCatches = frame.function.getTryCatchEntries();

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
                // 执行块内所有指令（热操作码内联，减少方法调用开销）
                for (frame.pc = 0; frame.pc < insts.length; frame.pc++) {
                    MirInst inst = insts[frame.pc];
                    switch (inst.getOp()) {
                        case CONST_INT:
                            frame.rawLocals[inst.getDest()] = inst.extraInt;
                            frame.locals[inst.getDest()] = MirFrame.RAW_INT_MARKER;
                            continue;
                        case MOVE: {
                            int src = inst.operand(0);
                            int dest = inst.getDest();
                            NovaValue srcVal = frame.locals[src];
                            frame.locals[dest] = srcVal;
                            if (srcVal == MirFrame.RAW_INT_MARKER) {
                                frame.rawLocals[dest] = frame.rawLocals[src];
                            }
                            continue;
                        }
                        case BINARY:
                            executeBinaryRaw(frame, inst);
                            continue;
                        case INDEX_GET: {
                            NovaValue tgt = frame.locals[inst.operand(0)];
                            int ir = inst.operand(1);
                            if (frame.locals[ir] == MirFrame.RAW_INT_MARKER) {
                                int idx = (int) frame.rawLocals[ir];
                                if (idx >= 0) {
                                    int d = inst.getDest();
                                    if (tgt instanceof NovaList) {
                                        NovaValue elem = ((NovaList) tgt).getElements().get(idx);
                                        if (elem instanceof NovaInt) {
                                            frame.rawLocals[d] = ((NovaInt) elem).getValue();
                                            frame.locals[d] = MirFrame.RAW_INT_MARKER;
                                        } else {
                                            frame.locals[d] = elem;
                                        }
                                        continue;
                                    }
                                    if (tgt instanceof NovaArray
                                            && ((NovaArray) tgt).getElementType() == NovaArray.ElementType.INT) {
                                        frame.rawLocals[d] = ((int[]) ((NovaArray) tgt).getRawArray())[idx];
                                        frame.locals[d] = MirFrame.RAW_INT_MARKER;
                                        continue;
                                    }
                                }
                            }
                            executeIndexGet(frame, inst);
                            continue;
                        }
                        case INDEX_SET: {
                            NovaValue tgt = frame.locals[inst.operand(0)];
                            int ir = inst.operand(1);
                            if (frame.locals[ir] == MirFrame.RAW_INT_MARKER) {
                                int idx = (int) frame.rawLocals[ir];
                                if (idx >= 0) {
                                    if (tgt instanceof NovaArray
                                            && ((NovaArray) tgt).getElementType() == NovaArray.ElementType.INT) {
                                        int vr = inst.operand(2);
                                        NovaValue vs = frame.locals[vr];
                                        if (vs == MirFrame.RAW_INT_MARKER) {
                                            ((int[]) ((NovaArray) tgt).getRawArray())[idx] = (int) frame.rawLocals[vr];
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
                            frame.locals[inst.getDest()] = NovaNull.NULL;
                            continue;
                        default:
                            executeInst(frame, inst);
                    }
                }

                // 处理终止指令
                MirTerminator term = block.getTerminator();
                if (term == null) {
                    return NovaNull.UNIT;
                }

                switch (term.kind) {
                    case MirTerminator.KIND_BRANCH: {
                        MirTerminator.Branch br = (MirTerminator.Branch) term;
                        BinaryOp fusedOp = br.getFusedCmpOp();
                        if (fusedOp != null) {
                            frame.currentBlockId = compareFused(frame, fusedOp, br.getFusedLeft(), br.getFusedRight())
                                    ? br.getThenBlock() : br.getElseBlock();
                        } else {
                            NovaValue cond = frame.get(br.getCondition());
                            frame.currentBlockId = isTruthy(cond) ? br.getThenBlock() : br.getElseBlock();
                        }
                        break;
                    }
                    case MirTerminator.KIND_GOTO: {
                        int targetId = ((MirTerminator.Goto) term).getTargetBlockId();
                        // 穿透：Goto 目标为空指令块 + Branch → 直接评估 Branch，省一次块转换
                        BasicBlock targetBlock = blockArr[targetId];
                        if (targetBlock.getInstArray().length == 0) {
                            MirTerminator tt = targetBlock.getTerminator();
                            if (tt != null && tt.kind == MirTerminator.KIND_BRANCH) {
                                MirTerminator.Branch br = (MirTerminator.Branch) tt;
                                BinaryOp fop = br.getFusedCmpOp();
                                if (fop != null) {
                                    frame.currentBlockId = compareFused(frame, fop, br.getFusedLeft(), br.getFusedRight())
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
                MirFunction.TryCatchEntry handler = findHandler(tryCatches, frame.currentBlockId);
                if (handler != null) {
                    frame.locals[handler.exceptionLocal] = wrapException(nre);
                    frame.currentBlockId = handler.handlerBlock;
                    continue;
                }
                frame.tceCount = tceCount;
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
        }
    }

    // ============ BINARY ============

    /**
     * 双槽 BINARY 快速路径：两个操作数均为 RAW_INT_MARKER 时直接在 rawLocals 上
     * 执行 long 运算，算术结果存回 rawLocals（不装箱），比较结果存 NovaBoolean。
     * 非 raw 操作数回退到 executeBinary（通过 frame.get() 自动具化）。
     */
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
                case DIV: if (b == 0) throw new NovaRuntimeException("Division by zero"); frame.rawLocals[dest] = a / b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case MOD: if (b == 0) throw new NovaRuntimeException("Division by zero"); frame.rawLocals[dest] = a % b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case EQ:  frame.locals[dest] = NovaBoolean.of(a == b); return;
                case NE:  frame.locals[dest] = NovaBoolean.of(a != b); return;
                case LT:  frame.locals[dest] = NovaBoolean.of(a < b); return;
                case GT:  frame.locals[dest] = NovaBoolean.of(a > b); return;
                case LE:  frame.locals[dest] = NovaBoolean.of(a <= b); return;
                case GE:  frame.locals[dest] = NovaBoolean.of(a >= b); return;
                case SHL: frame.rawLocals[dest] = a << b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case SHR: frame.rawLocals[dest] = a >> b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
                case USHR: frame.rawLocals[dest] = a >>> b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
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
            case DIV: if (b == 0) throw new NovaRuntimeException("Division by zero"); frame.rawLocals[dest] = a / b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case MOD: if (b == 0) throw new NovaRuntimeException("Division by zero"); frame.rawLocals[dest] = a % b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case EQ:  frame.locals[dest] = NovaBoolean.of(a == b); return;
            case NE:  frame.locals[dest] = NovaBoolean.of(a != b); return;
            case LT:  frame.locals[dest] = NovaBoolean.of(a < b); return;
            case GT:  frame.locals[dest] = NovaBoolean.of(a > b); return;
            case LE:  frame.locals[dest] = NovaBoolean.of(a <= b); return;
            case GE:  frame.locals[dest] = NovaBoolean.of(a >= b); return;
            case SHL: frame.rawLocals[dest] = a << b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case SHR: frame.rawLocals[dest] = a >> b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
            case USHR: frame.rawLocals[dest] = a >>> b; frame.locals[dest] = MirFrame.RAW_INT_MARKER; return;
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
                case DIV: if (b == 0) throw new NovaRuntimeException("Division by zero"); result = NovaInt.of(a / b); break;
                case MOD: if (b == 0) throw new NovaRuntimeException("Division by zero"); result = NovaInt.of(a % b); break;
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

        // 位运算（仅 MIR 路径支持）
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int a = ((NovaInt) left).getValue(), b = ((NovaInt) right).getValue();
            switch (op) {
                case SHL:  return NovaInt.of(a << b);
                case SHR:  return NovaInt.of(a >> b);
                case USHR: return NovaInt.of(a >>> b);
                case BAND: return NovaInt.of(a & b);
                case BOR:  return NovaInt.of(a | b);
                case BXOR: return NovaInt.of(a ^ b);
                default: break;
            }
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
        List<NovaValue> args = Arrays.asList(collectArgsArray(frame, ops));

        // Nova 类（Lambda 匿名类的 novaClass 为 null，跳过）
        MirClassInfo classInfo = mirClasses.get(className);
        if (classInfo != null && classInfo.novaClass != null) {
            frame.locals[inst.getDest()] = classInfo.novaClass.call(interp, args);
            return;
        }

        // 从环境查找 Nova 类
        NovaValue classVal = interp.getEnvironment().tryGet(toJavaDotName(className));
        if (classVal instanceof NovaClass) {
            frame.locals[inst.getDest()] = ((NovaClass) classVal).call(interp, args);
            return;
        }

        // Lambda / MethodRef 匿名类 → 查找对应的 MirClass 并创建 MirCallable 实例
        if (className.contains(MARKER_LAMBDA) || className.contains(MARKER_METHOD_REF)) {
            frame.locals[inst.getDest()] = createLambdaInstance(className, frame, ops);
            return;
        }

        // Java 集合类（由集合字面量降级生成）
        if (JAVA_ARRAY_LIST.equals(className)) {
            frame.locals[inst.getDest()] = new NovaList();
            return;
        }
        if (JAVA_HASH_MAP.equals(className)) {
            frame.locals[inst.getDest()] = new NovaMap();
            return;
        }
        if (JAVA_LINKED_HASH_SET.equals(className)) {
            frame.locals[inst.getDest()] = new NovaList(); // 用 NovaList 模拟 Set
            return;
        }

        // 回退：尝试 Java 反射构造（保留 Java 对象身份，不自动转换为 Nova 类型）
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

    // ============ GET_FIELD / SET_FIELD ============

    private void executeGetField(MirFrame frame, MirInst inst) {
        NovaValue target = frame.get(inst.operand(0));
        String fieldName = inst.extraAs();

        // NovaClass 单例/伴生对象 → 委托到 INSTANCE
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

        // NovaPair.first / .second
        if (target instanceof NovaPair) {
            NovaPair pair = (NovaPair) target;
            if ("first".equals(fieldName) || "key".equals(fieldName)) {
                frame.locals[inst.getDest()] = pair.getFirst() instanceof NovaValue
                        ? (NovaValue) pair.getFirst() : AbstractNovaValue.fromJava(pair.getFirst());
                return;
            }
            if ("second".equals(fieldName) || "value".equals(fieldName)) {
                frame.locals[inst.getDest()] = pair.getSecond() instanceof NovaValue
                        ? (NovaValue) pair.getSecond() : AbstractNovaValue.fromJava(pair.getSecond());
                return;
            }
        }

        // Exception.message — 异常消息提取
        if (target instanceof NovaExternalObject && "message".equals(fieldName)) {
            Object jObj = target.toJavaValue();
            if (jObj instanceof Exception) {
                String msg = ((Exception) jObj).getMessage();
                frame.locals[inst.getDest()] = msg != null ? NovaString.of(msg) : NovaNull.NULL;
                return;
            }
        }

        // Lambda 捕获字段访问
        if (target instanceof MirCallable) {
            NovaValue fieldVal = ((MirCallable) target).getCaptureField(fieldName);
            if (fieldVal != null) {
                frame.locals[inst.getDest()] = fieldVal;
                return;
            }
            // 作用域函数: 从 scopeReceiver 读取字段/方法
            if (callDispatcher.scopeReceiver != null) {
                NovaValue resolved = callDispatcher.resolveFieldOnValue(callDispatcher.scopeReceiver, fieldName);
                if (resolved != null) {
                    frame.locals[inst.getDest()] = resolved;
                    return;
                }
            }
            // capture field 不存在 → fallback 到环境变量（跨 evalRepl 的外部变量）
            NovaValue envVal = interp.getEnvironment().tryGet(fieldName);
            frame.locals[inst.getDest()] = envVal != null ? envVal : NovaNull.NULL;
            return;
        }

        // NovaEnumEntry 字段访问
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

        if (target instanceof NovaObject) {
            NovaObject obj = (NovaObject) target;
            // 快速路径：按索引直接访问字段（避免 hasField + getField 双重查找）
            NovaClass objClass = obj.getNovaClass();
            int fieldIdx = objClass.getFieldIndex(fieldName);
            if (fieldIdx >= 0) {
                NovaValue fieldVal = obj.getFieldByIndex(fieldIdx);
                if (fieldVal != null) {
                    // 可见性检查（private/protected 字段不允许从外部访问）
                    NovaClass currentClass = interp.getCurrentClass();
                    if (currentClass == null && frame.locals[0] instanceof NovaObject) {
                        currentClass = ((NovaObject) frame.locals[0]).getNovaClass();
                    }
                    if (!objClass.isFieldAccessibleFrom(fieldName, currentClass)) {
                        throw new NovaRuntimeException("Cannot access private field '" + fieldName + "'");
                    }
                    frame.locals[inst.getDest()] = fieldVal;
                    return;
                }
            }
            // overflow 字段回退（极少发生）
            if (obj.hasField(fieldName)) {
                frame.locals[inst.getDest()] = obj.getField(fieldName);
                return;
            }
            // 尝试无参方法（属性语法）
            NovaCallable method = obj.getMethod(fieldName);
            if (method != null) {
                frame.locals[inst.getDest()] = method.call(interp, Collections.singletonList(target));
                return;
            }
            // 字段/方法不存在：回退到全局作用域（处理扩展属性体中的全局变量如 PI）
            NovaValue envVal = interp.getEnvironment().tryGet(fieldName);
            if (envVal != null) {
                frame.locals[inst.getDest()] = envVal;
                return;
            }
        }

        // 委托给 MemberResolver（处理 NovaList.size, NovaString.length 等内置类型成员）
        frame.locals[inst.getDest()] = resolver.resolveMemberOnValue(target, fieldName, null);
    }

    private void executeSetField(MirFrame frame, MirInst inst) {
        NovaValue target = frame.get(inst.operand(0));
        NovaValue value = frame.get(inst.operand(1));
        String fieldName = inst.extraAs();

        // Lambda 捕获字段写入（可变捕获）
        if (target instanceof MirCallable) {
            // 如果 field 存在于 capture 中，更新 capture
            if (((MirCallable) target).getCaptureField(fieldName) != null) {
                ((MirCallable) target).setCaptureField(fieldName, value);
            } else if (callDispatcher.scopeReceiver instanceof NovaObject
                    && ((NovaObject) callDispatcher.scopeReceiver).hasField(fieldName)) {
                // 作用域函数: 写入 scopeReceiver 的字段
                ((NovaObject) callDispatcher.scopeReceiver).setField(fieldName, value);
            } else {
                // capture field 不存在 → 写入环境变量（跨 evalRepl 的外部变量）
                interp.getEnvironment().redefine(fieldName, value, true);
            }
            return;
        }

        if (target instanceof NovaObject) {
            ((NovaObject) target).setField(fieldName, value);
        } else if (target instanceof NovaExternalObject) {
            ((NovaExternalObject) target).setField(fieldName, value);
        } else {
            throw new NovaRuntimeException("Cannot set field '" + fieldName + "' on " + target.getTypeName());
        }
    }

    // ============ GET_STATIC / SET_STATIC ============

    private void executeGetStatic(MirFrame frame, MirInst inst) {
        String extra = inst.extraAs();
        int pipe = extra.indexOf('|');
        String owner = extra.substring(0, pipe);
        int pipe2 = extra.indexOf('|', pipe + 1);
        String fieldName = pipe2 >= 0 ? extra.substring(pipe + 1, pipe2) : extra.substring(pipe + 1);

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
        String normalizedOwner = toJavaDotName(owner);
        MirClassInfo classInfo = mirClasses.get(owner);
        if (classInfo != null) {
            NovaValue staticVal = classInfo.novaClass.getStaticField(fieldName);
            if (staticVal != null) {
                frame.locals[inst.getDest()] = staticVal;
                return;
            }
        }

        // 从环境查找类
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

        // Java 静态字段：inst.cache 缓存 MethodHandle（避免每次反射）
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
        // 首次执行：解析并缓存
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

    private void executeTypeCast(MirFrame frame, MirInst inst) {
        NovaValue value = frame.get(inst.operand(0));
        String typeName = (String) inst.getExtra();

        // SAM 转换：Lambda → Java 函数式接口（跳过安全转换 ?| 前缀）
        if (typeName != null && !typeName.startsWith("?|")) {
            // 提取 callable：直接 NovaCallable 或 MIR lambda（NovaObject with invoke）
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
                    // 不是 Java 类，回退
                }
            }
        }

        // 安全转换 as? — 对非匹配类型返回 null
        if (typeName != null && value != null && !(value instanceof NovaNull)) {
            // 检查是否有 safe 标记（extra 可能包含 "?|type"）
            if (typeName.startsWith("?|")) {
                String realType = typeName.substring(2);
                if (!classRegistrar.isInstanceOf(value, realType)) {
                    frame.locals[inst.getDest()] = NovaNull.NULL;
                    return;
                }
                // 移除安全标记，后续按类型匹配处理
                typeName = realType;
            }

            // 强制转换：验证类型兼容性
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
        if (a == b) return true;
        if (a == null || a instanceof NovaNull) return b == null || b instanceof NovaNull;
        if (b == null || b instanceof NovaNull) return false;
        return a.equals(b);
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

    private static final NovaValue[] EMPTY_ARGS = new NovaValue[0];

    NovaValue[] collectArgsArray(MirFrame frame, int[] ops) {
        if (ops == null || ops.length == 0) return EMPTY_ARGS;
        NovaValue[] args = new NovaValue[ops.length];
        for (int i = 0; i < ops.length; i++) {
            args[i] = frame.get(ops[i]);
        }
        return args;
    }

}
