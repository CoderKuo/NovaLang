package com.novalang.ir.lowering;

import com.novalang.compiler.NovaTypeNames;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.expr.Literal.LiteralKind;
import com.novalang.compiler.ast.decl.DestructuringEntry;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.compiler.hirtype.*;
import com.novalang.ir.mir.*;
import com.novalang.runtime.resolution.MethodNameCanonicalizer;
import com.novalang.runtime.resolution.MethodSemantics;
import com.novalang.runtime.resolution.StdlibMethodResolver;
import com.novalang.runtime.stdlib.BuiltinModuleExports;
import com.novalang.runtime.stdlib.StdlibRegistry;

import java.util.*;

/**
 * HIR → MIR 降级。
 * 将结构化控制流（IfStmt, HirLoop, ForStmt 等）转换为 CFG（BasicBlock + Terminator）。
 */
public class HirToMirLowering {

    /** Object 类的方法名集合（scopeReceiver 上总是可用，不应被 StdlibRegistry 全局函数遮蔽） */
    private static final Set<String> OBJECT_METHOD_NAMES;
    static {
        Set<String> names = new HashSet<>();
        for (java.lang.reflect.Method m : Object.class.getMethods()) {
            names.add(m.getName());
        }
        OBJECT_METHOD_NAMES = Collections.unmodifiableSet(names);
    }

    private final Set<String> topLevelFunctionNames = new HashSet<>();
    private final Set<String> classNames = new HashSet<>();
    private final Set<String> objectNames = new HashSet<>();
    // val x by lazy { ... } 的内联懒加载信息: varName → [lambdaLocal, cacheLocal, doneLocal]
    private final Map<String, int[]> lazyVarLocals = new HashMap<>();
    private final Set<String> interfaceNames = new HashSet<>();
    /** 外部已知类型名（仅供 typeToInternalName 使用，不参与方法分派） */
    private final Set<String> externalTypeNames = new HashSet<>();
    private final Set<String> enumClassNames = new HashSet<>();
    /** typealias 映射: 别名 → 目标类型（HirType） */
    private final Map<String, HirType> typeAliasMap = new HashMap<>();
    // 类实现的接口列表: className → [interfaceName, ...]
    private final Map<String, List<String>> classInterfaceMap = new HashMap<>();
    private String moduleClassName = "$Module";

    // 接收者 Lambda 内联编译上下文（buildString / buildList / buildMap / buildSet）
    private StdlibRegistry.ReceiverLambdaInfo currentReceiverLambda;
    private int receiverLocalIndex = -1;

    // 匿名对象支持
    private String currentEnclosingClassName;
    private int anonymousClassCounter;

    /** 设置匿名类计数器初始值（用于跨 evalRepl 避免类名冲突） */
    public void setAnonymousClassCounterBase(int base) { this.anonymousClassCounter = base; }
    /** 获取当前计数器值（lowering 完成后读取，供下次调用使用） */
    public int getAnonymousClassCounter() { return anonymousClassCounter; }
    private final List<MirClass> additionalClasses = new ArrayList<>();

    // 扩展函数注册: methodName → ownerClass (internal name)
    private final Map<String, String> extensionMethods = new HashMap<>();
    // 扩展函数描述符: methodName → JVM descriptor (使用原始类型)
    private final Map<String, String> extensionDescriptors = new HashMap<>();
    // 当前正在降级的函数的类型参数名（用于 lowerTypeCheck 保留 reified 类型参数名）
    private Set<String> currentFunctionTypeParams = Collections.emptySet();

    // Nova 类/接口方法描述符: className → (methodName → typed JVM descriptor)
    private final Map<String, Map<String, String>> novaMethodDescs = new HashMap<>();
    // 继承链方法描述符缓存: "owner#methodName" → descriptor（null 用 "" 标记）
    private final Map<String, String> inheritedDescCache = new HashMap<>();
    // Nova 类继承关系: className → superClassName（仅 Nova 类，非 java/lang/Object）
    private final Map<String, String> classSuperClass = new HashMap<>();
    // 顶层函数描述符: funcName → typed JVM descriptor
    private final Map<String, String> topLevelFuncDescs = new HashMap<>();
    // 顶层函数声明: funcName → HirFunction（用于默认参数填充）
    private final Map<String, HirFunction> topLevelFunctionDecls = new HashMap<>();
    // 类构造器声明: className → HirFunction（用于默认参数填充）
    private final Map<String, HirFunction> classConstructorDecls = new HashMap<>();
    // 局部函数声明: funcName → HirFunction（用于嵌套函数默认参数填充）
    private final Map<String, HirFunction> localFunctionDecls = new HashMap<>();
    // 类字段名: className → Set<fieldName>（用于区分字段调用和方法调用）
    private final Map<String, Set<String>> classFieldNames = new HashMap<>();
    // 类注解数据: className → List<HirAnnotation>（用于运行时 .annotations 访问）
    private final Map<String, List<HirAnnotation>> classAnnotationData = new HashMap<>();

    // Java import 映射: 简单名 → JVM 内部名（如 "System" → "java/lang/System"）
    private final Map<String, String> javaImports = new HashMap<>();

    // javaClass() 常量传播: 变量名 → JVM 内部名（如 "Integer" → "java/lang/Integer"）
    // val X = javaClass("java.lang.Integer") 时记录，后续 X.FIELD/X.method() 直接静态解析
    private final Map<String, String> javaClassBindings = new HashMap<>();

    // Java 方法查找缓存: "className#methodName#argCount" → Method
    private final Map<String, java.lang.reflect.Method> javaMethodCache = new HashMap<>();

    // try-finally 上下文栈：内层到外层的 finally 块，用于 return/throw 路径上内联 finally
    private final Deque<AstNode> finallyStack = new ArrayDeque<>();

    // 嵌套 lambda 捕获栈：追踪外层 lambda 的捕获变量，用于多层嵌套闭包的捕获分析
    private final Deque<Set<String>> lambdaCaptureStack = new ArrayDeque<>();
    // 深度收集标志：捕获分析时递归进入嵌套 lambda body 收集变量引用
    private boolean deepVarRefCollection = false;

    // 自定义属性访问器注册: "className:fieldName" → true
    private final Set<String> customGetters = new HashSet<>();
    private final Set<String> customSetters = new HashSet<>();

    // 循环上下文栈：break/continue 跳转目标
    private final Deque<LoopContext> loopStack = new ArrayDeque<>();

    // 块作用域深度：>0 时跳过 $ENV|defineVal/defineVar（块内变量仅通过寄存器访问）
    private int blockScopeDepth = 0;

    // 可变闭包捕获: varName → 外部作用域中 Object[] box 的局部变量索引
    private final Map<String, Integer> boxedMutableCaptures = new HashMap<>();

    // 扩展属性信息（由 MirInterpreter 注册到解释器）
    private final List<MirModule.ExtensionPropertyInfo> extensionPropertyInfos = new ArrayList<>();
    private final List<MirModule.ExtensionFunctionInfo> extensionFunctionInfos = new ArrayList<>();

    // 内置模块函数：函数名 → "jvmOwner|jvmMethodName|jvmDescriptor"
    private final Map<String, String> builtinModuleFunctions = new HashMap<>();

    // JSR-223 脚本模式：未解析变量通过 NovaScriptContext 读取，main() 返回 Object
    private boolean scriptMode = false;
    // scriptMode main 函数中通过 defineVar 导出到 $ENV 的 var 名称（兼容旧路径）
    private final Set<String> scriptExportedVars = new HashSet<>();
    // 顶层变量 → $Module 静态字段（名称 → 是否 val）
    private final Map<String, Boolean> topLevelFieldNames = new LinkedHashMap<>();
    // 解释器模式：生成解释器友好的 MIR（不做方法别名转换、不生成 NovaDynamic 等 JVM 辅助类调用）
    /** @deprecated interpreterMode 已统一消除，所有 MIR 生成路径不再区分解释器/编译器 */
    @Deprecated
    private boolean interpreterMode = false;

    public void setScriptMode(boolean scriptMode) {
        this.scriptMode = scriptMode;
    }

    public void setInterpreterMode(boolean interpreterMode) {
        this.interpreterMode = interpreterMode;
    }

    /** 脚本上下文 owner（统一使用 JVM 类名，StaticMethodDispatcher 已处理两种格式） */
    private String scriptContextOwner() {
        return "com/novalang/runtime/NovaScriptContext";
    }

    private static final class LoopContext {
        final String label;       // nullable
        final int headerBlockId;  // continue 跳转目标
        final int exitBlockId;    // break 跳转目标
        LoopContext(String label, int headerBlockId, int exitBlockId) {
            this.label = label;
            this.headerBlockId = headerBlockId;
            this.exitBlockId = exitBlockId;
        }
    }

    // Java 类型解析
    private static final String[] JAVA_PREFIXES = {
        "", "java.lang.", "java.util.", "java.io.",
        "java.util.concurrent.", "java.util.function."
    };
    private final Map<String, Class<?>> javaTypeCache = new HashMap<>();

    /**
     * Nova 方法名 → Java 方法名别名解析。
     */
    private String resolveMethodAlias(String methodName) {
        return MethodNameCanonicalizer.canonicalName(methodName);
    }

    /** 基本类型 → 对应的装箱类型 owner，用于 StdlibRegistry 扩展方法查找 */
    private static String primitiveToBoxedOwner(MirType type) {
        switch (type.getKind()) {
            case CHAR:    return "java/lang/Character";
            case INT:     return "java/lang/Integer";
            case LONG:    return "java/lang/Long";
            case DOUBLE:  return "java/lang/Double";
            case FLOAT:   return "java/lang/Float";
            case BOOLEAN: return "java/lang/Boolean";
            default:      return "java/lang/Object";
        }
    }

    private Class<?> resolveJavaClass(String name) {
        if (javaTypeCache.containsKey(name)) return javaTypeCache.get(name);
        String javaName = name.replace('/', '.');
        for (String prefix : JAVA_PREFIXES) {
            try {
                Class<?> cls = Class.forName(prefix + javaName);
                javaTypeCache.put(name, cls);
                return cls;
            } catch (ClassNotFoundException e) { /* continue */ }
        }
        javaTypeCache.put(name, null);
        return null;
    }

    /**
     * 注册外部模块（import）的类声明，预填充 classNames / novaMethodDescs 等，
     * 使主模块编译时能生成正确的方法描述符。
     */
    /**
     * 注册外部已知类型名（仅名称），使 typeToInternalName 能识别跨 evalRepl 的类引用。
     * 不加入 classNames 以避免影响方法分派逻辑。
     */
    public void registerExternalClassNames(Collection<String> names) {
        externalTypeNames.addAll(names);
    }

    public void registerExternalInterfaceNames(Collection<String> names) {
        interfaceNames.addAll(names);
    }

    public void registerExternalClasses(List<HirClass> externalClasses) {
        for (HirClass hc : externalClasses) {
            classNames.add(hc.getName());
            if (hc.getClassKind() == ClassKind.OBJECT) {
                objectNames.add(hc.getName());
            }
            if (hc.getClassKind() == ClassKind.INTERFACE) {
                interfaceNames.add(hc.getName());
            }
            Map<String, String> methodDescs = new HashMap<>();
            for (HirFunction m : hc.getMethods()) {
                if (!m.isExtensionFunction() && !m.getName().startsWith("<")) {
                    methodDescs.put(m.getName(), buildHirMethodDescriptor(m));
                }
            }
            if (!methodDescs.isEmpty()) {
                novaMethodDescs.put(hc.getName(), methodDescs);
            }
            // 记录字段名（用于 lowerVarRef 区分 this.field 和全局变量）
            // 始终注册（即使为空），使字段保护逻辑能正确区分字段和外部变量
            Set<String> fieldSet = new HashSet<>();
            for (HirField f : hc.getFields()) {
                fieldSet.add(f.getName());
            }
            classFieldNames.put(hc.getName(), fieldSet);
            // 记录继承关系（也检查 externalTypeNames 以支持跨 evalRepl 的继承）
            if (hc.getSuperClass() != null) {
                String superName = typeToInternalName(hc.getSuperClass());
                if (classNames.contains(superName) || externalTypeNames.contains(superName)) {
                    classSuperClass.put(hc.getName(), superName);
                }
            }
        }
    }

    public MirModule lower(HirModule hirModule) {
        // 计算模块类名
        String packagePrefix = hirModule.getPackageName() != null && !hirModule.getPackageName().isEmpty()
                ? hirModule.getPackageName().replace('.', '/') + "/" : "";
        moduleClassName = packagePrefix + "$Module";

        // 收集 Java import 映射 + Nova import 类名
        // 收集用于运行时注册的 import 元数据
        Map<String, String> javaImportsMeta = new HashMap<>();
        Map<String, String> staticImportsMeta = new HashMap<>();
        List<String> wildcardImportsMeta = new ArrayList<>();
        List<MirModule.NovaImportInfo> novaImportInfos = new ArrayList<>();
        for (HirImport imp : hirModule.getImports()) {
            if (imp.isJava()) {
                String qn = imp.getQualifiedName();
                if (imp.isWildcard()) {
                    wildcardImportsMeta.add(qn);
                } else {
                    String simpleName = imp.hasAlias() ? imp.getAlias()
                            : qn.substring(qn.lastIndexOf('.') + 1);
                    String internalName = qn.replace('.', '/');
                    javaImports.put(simpleName, internalName);
                    javaImportsMeta.put(simpleName, qn);
                }
            } else if (imp.isStatic()) {
                String qn = imp.getQualifiedName();
                String simpleName = qn.substring(qn.lastIndexOf('.') + 1);
                staticImportsMeta.put(simpleName, qn);
            } else {
                String qn = imp.getQualifiedName();

                // 自动识别 Java 包导入（Parser 未标记 isJava 时的回退）
                if (qn.startsWith("java.") || qn.startsWith("javax.") || qn.startsWith("org.") || qn.startsWith("com.")) {
                    try {
                        Class.forName(qn);
                        // 确认是 Java 类，按 Java import 处理
                        String simpleName = imp.hasAlias() ? imp.getAlias()
                                : qn.substring(qn.lastIndexOf('.') + 1);
                        String internalName = qn.replace('.', '/');
                        javaImports.put(simpleName, internalName);
                        javaImportsMeta.put(simpleName, qn);
                        continue;
                    } catch (ClassNotFoundException ignored) {
                        // 不是 Java 类，走 Nova 模块处理
                    }
                }

                // 检查是否为内置模块（nova.time, nova.io 等）
                String builtinModule = BuiltinModuleExports.resolveModuleName(qn);
                if (builtinModule != null && BuiltinModuleExports.has(builtinModule)) {
                    // 编译模式：将命名空间注册为 javaImports，将函数通过反射自动发现
                    String symbol = imp.isWildcard() ? null
                            : (imp.hasAlias() ? imp.getAlias() : qn.substring(qn.lastIndexOf('.') + 1));
                    if (imp.isWildcard()) {
                        javaImports.putAll(BuiltinModuleExports.getNamespaces(builtinModule));
                    } else {
                        Map<String, String> ns = BuiltinModuleExports.getNamespaces(builtinModule);
                        if (ns.containsKey(symbol)) {
                            javaImports.put(symbol, ns.get(symbol));
                        }
                    }
                    // 反射发现模块顶层函数
                    String moduleClass = BuiltinModuleExports.getModuleClass(builtinModule);
                    if (moduleClass != null) {
                        discoverModuleFunctions(moduleClass, symbol);
                    }
                } else if (!imp.isWildcard() && externalTypeNames.isEmpty()) {
                    // 非 evalRepl 场景：首字母大写的符号假定为类名
                    // evalRepl 场景完全跳过（运行时 NovaScriptContext/Environment 动态解析）
                    String symbolName = imp.hasAlias() ? imp.getAlias()
                            : qn.substring(qn.lastIndexOf('.') + 1);
                    if (symbolName.length() > 0 && Character.isUpperCase(symbolName.charAt(0))) {
                        classNames.add(symbolName);
                    }
                }
                novaImportInfos.add(new MirModule.NovaImportInfo(
                        qn, imp.hasAlias() ? imp.getAlias() : null, imp.isWildcard()));
            }
        }

        // 提升嵌套类定义到顶层（支持函数/方法体内的类声明）
        hoistNestedClasses(hirModule.getDeclarations());

        // 第一遍：收集顶层函数名、类名、方法描述符、类型别名
        // 预扫描 main() 体内的顶层字段（scriptMode 下 var/val 声明被合成到 main 中）
        if (scriptMode) {
            for (HirDecl decl : hirModule.getDeclarations()) {
                if (decl instanceof HirFunction && "main".equals(decl.getName())) {
                    HirFunction mainFn = (HirFunction) decl;
                    if (mainFn.getBody() instanceof Block) {
                        for (Statement stmt : ((Block) mainFn.getBody()).getStatements()) {
                            if (stmt instanceof HirDeclStmt && ((HirDeclStmt) stmt).getDeclaration() instanceof HirField) {
                                HirField hf = (HirField) ((HirDeclStmt) stmt).getDeclaration();
                                topLevelFieldNames.put(hf.getName(), hf.isVal());
                                // javaClass() 常量传播预扫描
                                if (hf.isVal() && hf.hasInitializer()) {
                                    String jcName = extractJavaClassName(hf.getInitializer());
                                    if (jcName != null) {
                                        javaClassBindings.put(hf.getName(), jcName.replace('.', '/'));
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            }
        }
        for (HirDecl decl : hirModule.getDeclarations()) {
            if (decl instanceof HirTypeAlias) {
                typeAliasMap.put(((HirTypeAlias) decl).getName(), ((HirTypeAlias) decl).getTargetType());
                continue;
            }
            if (decl instanceof HirFunction && !"main".equals(decl.getName())) {
                HirFunction hf = (HirFunction) decl;
                if (!hf.isExtensionFunction()) {
                    topLevelFunctionNames.add(decl.getName());
                    topLevelFuncDescs.put(decl.getName(), buildHirMethodDescriptor(hf));
                    topLevelFunctionDecls.put(decl.getName(), hf);
                }
            } else if (decl instanceof HirClass) {
                HirClass hc = (HirClass) decl;
                classNames.add(hc.getName());
                if (hc.getClassKind() == ClassKind.OBJECT) {
                    objectNames.add(hc.getName());
                }
                if (hc.getClassKind() == ClassKind.INTERFACE) {
                    interfaceNames.add(hc.getName());
                }
                if (hc.getClassKind() == ClassKind.ENUM) {
                    enumClassNames.add(hc.getName());
                }
                // 记录类实现的接口
                {
                    List<String> ifaces = new ArrayList<>();
                    if (hc.getSuperClass() != null) {
                        String sn = typeToInternalName(hc.getSuperClass());
                        if (interfaceNames.contains(sn)) ifaces.add(sn);
                    }
                    for (HirType it : hc.getInterfaces()) {
                        ifaces.add(typeToInternalName(it));
                    }
                    if (!ifaces.isEmpty()) classInterfaceMap.put(hc.getName(), ifaces);
                }
                // 预收集方法描述符（调用端查找用）
                Map<String, String> methodDescs = new HashMap<>();
                for (HirFunction m : hc.getMethods()) {
                    if (!m.isExtensionFunction() && !m.getName().startsWith("<")) {
                        methodDescs.put(m.getName(), buildHirMethodDescriptor(m));
                    }
                }
                // 枚举类: 注册内置 name()/ordinal() 方法描述符
                if (hc.getClassKind() == ClassKind.ENUM) {
                    methodDescs.put("name", "()Ljava/lang/Object;");
                    methodDescs.put("ordinal", "()Ljava/lang/Object;");
                }
                novaMethodDescs.put(hc.getName(), methodDescs);
                // 记录主构造器（用于默认参数填充）
                if (!hc.getConstructors().isEmpty()) {
                    classConstructorDecls.put(hc.getName(), hc.getConstructors().get(0));
                }
                // 记录字段名（用于区分字段调用和方法调用）
                // 始终注册（即使为空），使字段保护逻辑能正确区分字段和外部变量
                Set<String> fieldSet = new HashSet<>();
                for (HirField f : hc.getFields()) {
                    fieldSet.add(f.getName());
                }
                classFieldNames.put(hc.getName(), fieldSet);
                // 记录注解数据（用于 .annotations 访问）
                if (hc.getAnnotations() != null && !hc.getAnnotations().isEmpty()) {
                    classAnnotationData.put(hc.getName(), hc.getAnnotations());
                }
                // 记录继承关系（也检查 externalTypeNames 以支持跨 evalRepl 的继承）
                if (hc.getSuperClass() != null) {
                    String superName = typeToInternalName(hc.getSuperClass());
                    if (classNames.contains(superName) || externalTypeNames.contains(superName)) {
                        classSuperClass.put(hc.getName(), superName);
                    }
                }
            }
        }

        // 修复 override 方法描述符：继承父类方法的描述符
        for (HirDecl decl : hirModule.getDeclarations()) {
            if (decl instanceof HirClass) {
                HirClass hc = (HirClass) decl;
                String superName = classSuperClass.get(hc.getName());
                if (superName != null) {
                    Map<String, String> childDescs = novaMethodDescs.get(hc.getName());
                    for (HirFunction m : hc.getMethods()) {
                        if (m.getModifiers().contains(Modifier.OVERRIDE)) {
                            String parentDesc = lookupNovaMethodDescInherited(superName, m.getName());
                            if (parentDesc != null) {
                                childDescs.put(m.getName(), parentDesc);
                            }
                        }
                    }
                }
                // Java 超类方法覆盖：通过反射获取 Java 方法的正确描述符
                if (superName == null && hc.getSuperClass() != null) {
                    String javaSuperName = typeToInternalName(hc.getSuperClass());
                    Class<?> javaSuperClass = resolveJavaClass(javaSuperName);
                    if (javaSuperClass != null) {
                        Map<String, String> childDescs = novaMethodDescs.get(hc.getName());
                        if (childDescs != null) {
                            for (HirFunction m : hc.getMethods()) {
                                java.lang.reflect.Method javaMethod =
                                        findJavaMethod(javaSuperClass, m.getName(), m.getParams().size());
                                if (javaMethod != null) {
                                    childDescs.put(m.getName(), buildJavaMethodDesc(javaMethod));
                                }
                            }
                        }
                    }
                }
            }
        }

        List<MirClass> classes = new ArrayList<>();
        List<MirFunction> topLevel = new ArrayList<>();

        for (HirDecl decl : hirModule.getDeclarations()) {
            if (decl instanceof HirClass) {
                currentEnclosingClassName = decl.getName();
                classes.add(lowerClass((HirClass) decl));
            } else if (decl instanceof HirFunction) {
                currentEnclosingClassName = "$Module";
                HirFunction hf = (HirFunction) decl;
                if (hf.isExtensionFunction()) {
                    // 顶层扩展函数 → 静态方法 + 注册到扩展函数表
                    MirFunction extFunc = lowerExtensionFunction(hf, moduleClassName);
                    topLevel.add(extFunc);
                    extensionMethods.put(hf.getName(), moduleClassName);
                    extensionDescriptors.put(hf.getName(), extFunc.getOverrideDescriptor());
                    // 记录扩展函数元数据（MirInterpreter 注册用）
                    String receiverTypeName = hf.getReceiverType() != null
                            ? typeToInternalName(hf.getReceiverType()) : "java/lang/Object";
                    extensionFunctionInfos.add(new MirModule.ExtensionFunctionInfo(
                            receiverTypeName, hf.getName()));
                } else {
                    MirFunction func = lowerFunction(hf, null);
                    String tlDesc = topLevelFuncDescs.get(decl.getName());
                    if (tlDesc != null) {
                        func.setOverrideDescriptor(tlDesc);
                    }
                    topLevel.add(func);
                }
            } else if (decl instanceof HirField) {
                currentEnclosingClassName = "$Module";
                HirField hf = (HirField) decl;
                if (hf.isExtensionProperty() && hf.getInitializer() != null) {
                    // 扩展属性 → 生成 getter 函数并记录元数据
                    String receiverTypeName = typeToInternalName(hf.getReceiverType());
                    String getterName = "$extProp$" + receiverTypeName.replace("/", "$")
                            + "$" + hf.getName();
                    MirFunction getter = lowerExtensionPropertyGetter(hf, receiverTypeName, getterName);
                    topLevel.add(getter);
                    extensionPropertyInfos.add(new MirModule.ExtensionPropertyInfo(
                            receiverTypeName, hf.getName(), getterName));
                }
            }
        }

        // 添加匿名类
        classes.addAll(additionalClasses);

        // 编译完成，清理反射缓存
        javaMethodCache.clear();
        javaTypeCache.clear();

        MirModule module = new MirModule(hirModule.getPackageName(), classes, topLevel);
        // 顶层变量 → $Module 静态字段
        if (!topLevelFieldNames.isEmpty()) {
            List<MirField> tlFields = new ArrayList<>();
            for (Map.Entry<String, Boolean> e : topLevelFieldNames.entrySet()) {
                Set<Modifier> mods = new HashSet<>();
                mods.add(Modifier.STATIC);
                mods.add(Modifier.PUBLIC);
                if (Boolean.TRUE.equals(e.getValue())) mods.add(Modifier.FINAL);
                tlFields.add(new MirField(e.getKey(), MirType.ofObject("java/lang/Object"), mods));
            }
            module.setTopLevelFields(tlFields);
        }
        if (!extensionPropertyInfos.isEmpty()) {
            module.setExtensionProperties(extensionPropertyInfos);
        }
        if (!extensionFunctionInfos.isEmpty()) {
            module.setExtensionFunctions(extensionFunctionInfos);
        }
        if (!javaImportsMeta.isEmpty()) {
            module.setJavaImports(javaImportsMeta);
        }
        if (!staticImportsMeta.isEmpty()) {
            module.setStaticImports(staticImportsMeta);
        }
        if (!wildcardImportsMeta.isEmpty()) {
            module.setWildcardJavaImports(wildcardImportsMeta);
        }
        if (!novaImportInfos.isEmpty()) {
            module.setNovaImports(novaImportInfos);
        }
        return module;
    }

    /**
     * 提升嵌套类定义到顶层声明列表。
     * 支持在函数/方法体内定义的类（如 object Test { fun run() { class Foo ... } }）。
     */
    private void hoistNestedClasses(List<HirDecl> declarations) {
        List<HirClass> hoisted = new ArrayList<>();
        for (HirDecl decl : declarations) {
            if (decl instanceof HirClass) {
                for (HirFunction m : ((HirClass) decl).getMethods()) {
                    collectNestedClasses(m.getBody(), hoisted);
                }
            } else if (decl instanceof HirFunction) {
                collectNestedClasses(((HirFunction) decl).getBody(), hoisted);
            }
        }
        declarations.addAll(hoisted);
    }

    private void collectNestedClasses(AstNode node, List<HirClass> result) {
        if (node == null) return;
        if (node instanceof Block) {
            for (Statement stmt : ((Block) node).getStatements()) {
                collectNestedClasses(stmt, result);
            }
        } else if (node instanceof HirDeclStmt) {
            HirDecl decl = ((HirDeclStmt) node).getDeclaration();
            if (decl instanceof HirClass) {
                result.add((HirClass) decl);
                // 递归提升嵌套类内部的类定义
                for (HirFunction m : ((HirClass) decl).getMethods()) {
                    collectNestedClasses(m.getBody(), result);
                }
            } else if (decl instanceof HirFunction) {
                collectNestedClasses(((HirFunction) decl).getBody(), result);
            }
        } else if (node instanceof IfStmt) {
            collectNestedClasses(((IfStmt) node).getThenBranch(), result);
            collectNestedClasses(((IfStmt) node).getElseBranch(), result);
        }
    }

    private MirClass lowerClass(HirClass hirClass) {
        List<MirField> fields = new ArrayList<>();
        for (HirField f : hirClass.getFields()) {
            MirField mf = new MirField(f.getName(), hirTypeToMir(f.getType()), f.getModifiers());
            if (f.hasCustomGetter()) mf.setGetterFunctionName("get$" + f.getName());
            if (f.hasCustomSetter()) mf.setSetterFunctionName("set$" + f.getName());
            fields.add(mf);
        }

        String className = hirClass.getName();

        // 枚举条目 → 静态字段（使用 Object 描述符以匹配 GETSTATIC/PUTSTATIC）
        List<HirEnumEntry> enumEntries = hirClass.getEnumEntries();
        if (hirClass.getClassKind() == ClassKind.ENUM && enumEntries != null) {
            for (HirEnumEntry entry : enumEntries) {
                Set<Modifier> mods = EnumSet.of(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL);
                fields.add(new MirField(entry.getName(),
                        MirType.ofObject("java/lang/Object"), mods));
            }
            // 枚举内置隐藏字段: $name (String), $ordinal (Integer)
            fields.add(new MirField("$name", MirType.ofObject("java/lang/Object"),
                    EnumSet.noneOf(Modifier.class)));
            fields.add(new MirField("$ordinal", MirType.ofObject("java/lang/Object"),
                    EnumSet.noneOf(Modifier.class)));
        }

        // 提前注册自定义 getter/setter（方法体 lowering 时需要查询）
        for (HirField f : hirClass.getFields()) {
            if (f.hasCustomGetter()) {
                customGetters.add(className + ":" + f.getName());
            }
            if (f.hasCustomSetter()) {
                customSetters.add(className + ":" + f.getName());
            }
        }

        List<MirFunction> methods = new ArrayList<>();
        for (HirFunction m : hirClass.getMethods()) {
            if (m.isExtensionFunction()) {
                MirFunction extFunc = lowerExtensionFunction(m, className);
                methods.add(extFunc);
                extensionMethods.put(m.getName(), className);
                extensionDescriptors.put(m.getName(), extFunc.getOverrideDescriptor());
            } else {
                boolean isStatic = m.getModifiers().contains(Modifier.STATIC);
                MirFunction func = lowerFunction(m, isStatic ? null : className);
                // 设置类型化描述符（跳过构造器和类初始化器）
                if (!m.getName().startsWith("<")) {
                    Map<String, String> classDescs = novaMethodDescs.get(className);
                    if (classDescs != null && classDescs.containsKey(m.getName())) {
                        func.setOverrideDescriptor(classDescs.get(m.getName()));
                    }
                }
                methods.add(func);
            }
        }
        // 两遍构造器处理：第一遍正常 lower，第二遍尝试内联次级构造器委托链
        Map<HirFunction, MirFunction> ctorMap = new LinkedHashMap<>();
        for (HirFunction c : hirClass.getConstructors()) {
            ctorMap.put(c, lowerConstructor(c, className, hirClass.getFields(),
                    hirClass.getSuperConstructorArgs(), hirClass.getInstanceInitializers()));
        }
        for (Map.Entry<HirFunction, MirFunction> entry : ctorMap.entrySet()) {
            if (entry.getKey().hasDelegation()) {
                tryInlineConstructorDelegation(entry.getKey(), entry.getValue(),
                        hirClass.getConstructors(), hirClass.getFields(),
                        hirClass.getInstanceInitializers());
            }
            methods.add(entry.getValue());
        }

        // 无显式构造器时，生成合成构造器以初始化字段默认值
        if (hirClass.getConstructors().isEmpty() && hirClass.getClassKind() != ClassKind.INTERFACE) {
            MirFunction syntheticCtor = generateFieldInitConstructor(hirClass, className);
            // 如果类有超类构造器参数，确保有一个包含 superInitArgLocals 的构造器
            List<Expression> superCtorExprs = hirClass.getSuperConstructorArgs();
            if (superCtorExprs != null && !superCtorExprs.isEmpty()) {
                if (syntheticCtor == null) {
                    syntheticCtor = new MirFunction("<init>", MirType.ofVoid(),
                            Collections.emptyList(), EnumSet.of(Modifier.PUBLIC));
                    MirBuilder b = new MirBuilder(syntheticCtor);
                    b.newLocal("this", MirType.ofObject(className));
                    b.emitReturnVoid(hirClass.getLocation());
                }
                // 将超类构造器参数作为 CONST 指令插入入口块
                SourceLocation loc = hirClass.getLocation();
                BasicBlock entry = syntheticCtor.getBlocks().get(0);
                List<MirInst> prependInsts = new ArrayList<>();
                int[] superArgLocals = new int[superCtorExprs.size()];
                for (int i = 0; i < superCtorExprs.size(); i++) {
                    Expression arg = superCtorExprs.get(i);
                    int dest = syntheticCtor.newLocal("$sarg" + i, MirType.ofObject("java/lang/Object"));
                    superArgLocals[i] = dest;
                    if (arg instanceof Literal) {
                        Literal lit = (Literal) arg;
                        switch (lit.getKind()) {
                            case INT: prependInsts.add(new MirInst(MirOp.CONST_INT, dest, null, lit.getValue(), loc)); break;
                            case STRING: prependInsts.add(new MirInst(MirOp.CONST_STRING, dest, null, lit.getValue(), loc)); break;
                            case BOOLEAN: prependInsts.add(new MirInst(MirOp.CONST_BOOL, dest, null, lit.getValue(), loc)); break;
                            default: prependInsts.add(new MirInst(MirOp.CONST_NULL, dest, null, null, loc)); break;
                        }
                    } else {
                        prependInsts.add(new MirInst(MirOp.CONST_NULL, dest, null, null, loc));
                    }
                }
                entry.getInstructions().addAll(0, prependInsts);
                syntheticCtor.setSuperInitArgLocals(superArgLocals);
                String superName = classSuperClass.get(className);
                if (superName != null) {
                    syntheticCtor.setSuperClassName(superName);
                }
            }
            if (syntheticCtor != null) {
                methods.add(syntheticCtor);
            }
        }

        // 自定义属性访问器 → 合成 getter/setter 方法
        for (HirField f : hirClass.getFields()) {
            if (f.hasCustomGetter()) {
                methods.add(generatePropertyGetter(f, className));
                customGetters.add(className + ":" + f.getName());
            }
            if (f.hasCustomSetter()) {
                methods.add(generatePropertySetter(f, className));
                customSetters.add(className + ":" + f.getName());
            }
        }

        // 枚举: 生成 name()/ordinal() 方法 + <clinit> 初始化枚举条目
        if (hirClass.getClassKind() == ClassKind.ENUM && enumEntries != null
                && !enumEntries.isEmpty()) {
            methods.add(generateEnumNameMethod(className));
            methods.add(generateEnumOrdinalMethod(className));
            methods.add(generateEnumClinit(hirClass, className));
        }

        // 静态字段初始化: 生成 <clinit>（如 companion object 字段）
        if (hirClass.getClassKind() != ClassKind.ENUM) {
            MirFunction staticClinit = generateStaticFieldClinit(hirClass, className);
            if (staticClinit != null) {
                methods.add(staticClinit);
            }
        }

        String superClass = "java/lang/Object";
        List<String> interfaces = new ArrayList<>();

        if (hirClass.getSuperClass() != null) {
            String resolved = typeToInternalName(hirClass.getSuperClass());
            Class<?> javaClass = resolveJavaClass(resolved);
            if (javaClass != null && javaClass.isInterface()) {
                interfaces.add(resolved);
            } else if (interfaceNames.contains(resolved)) {
                // Nova 定义的接口
                interfaces.add(resolved);
            } else {
                superClass = resolved;
            }
        }
        for (HirType iface : hirClass.getInterfaces()) {
            interfaces.add(typeToInternalName(iface));
        }

        // 收集注解名
        List<String> annotationNames = new ArrayList<>();
        if (hirClass.getAnnotations() != null) {
            for (HirAnnotation ann : hirClass.getAnnotations()) {
                annotationNames.add(ann.getName());
            }
        }

        MirClass mirClass = new MirClass(hirClass.getName(), hirClass.getClassKind(),
                hirClass.getModifiers(), superClass, interfaces, fields, methods,
                annotationNames);
        if (hirClass.getAnnotations() != null && !hirClass.getAnnotations().isEmpty()) {
            mirClass.setHirAnnotations(hirClass.getAnnotations());
        }
        return mirClass;
    }

    private MirFunction generateEnumClinit(HirClass hirClass, String className) {
        Set<Modifier> staticMods = EnumSet.of(Modifier.STATIC);
        MirFunction clinit = new MirFunction("<clinit>", MirType.ofVoid(),
                Collections.emptyList(), staticMods);
        MirBuilder builder = new MirBuilder(clinit);
        SourceLocation loc = hirClass.getLocation();

        int ordinal = 0;
        for (HirEnumEntry entry : hirClass.getEnumEntries()) {
            int[] args = lowerArgs(entry.getArgs(), builder);
            int instance = builder.emitNewObject(className, args, loc);
            // 设置枚举条目的 $name 和 $ordinal 隐藏字段
            int nameConst = builder.emitConstString(entry.getName(), loc);
            builder.emitSetField(instance, "$name", nameConst, loc);
            int ordinalConst = builder.emitConstInt(ordinal, loc);
            builder.emitSetField(instance, "$ordinal", ordinalConst, loc);
            builder.emitSetStatic(className, entry.getName(),
                    MethodDescriptor.OBJECT_DESC, instance, loc);
            ordinal++;
        }
        builder.emitReturnVoid(loc);
        return clinit;
    }

    /** 生成枚举 name() 方法：返回 this.$name */
    /**
     * 扩展属性 getter：静态函数，$this 为接收者，返回初始化表达式的值。
     * 例如 val String.len = this.length() → fun $extProp$...$len($this: String) = $this.length()
     */
    private MirFunction lowerExtensionPropertyGetter(HirField field, String receiverType, String funcName) {
        List<MirParam> params = Collections.singletonList(
                new MirParam("$this", MirType.ofObject(receiverType)));
        MirFunction func = new MirFunction(funcName,
                MirType.ofObject("java/lang/Object"), params, EnumSet.of(Modifier.STATIC));
        MirBuilder builder = new MirBuilder(func);
        // local 0 = $this (接收者)
        builder.newLocal("$this", MirType.ofObject(receiverType));
        // 初始化表达式中的 this 引用映射到 $this
        // HirToMirLowering 的 lowerVarRef 会把 "this" 解析到 local 0（名为 "$this"）
        // 但标准的 lowerVarRef 查找 "this" 不会匹配 "$this"，所以需要额外添加 "this" 别名
        builder.newLocal("this", MirType.ofObject(receiverType));
        builder.emitMoveTo(0, 1, field.getLocation()); // this = $this
        int result = lowerExpr(field.getInitializer(), builder);
        builder.emitReturn(result, field.getLocation());
        return func;
    }

    private MirFunction generateEnumNameMethod(String className) {
        MirFunction func = new MirFunction("name", MirType.ofObject("java/lang/Object"),
                Collections.emptyList(), EnumSet.noneOf(Modifier.class));
        MirBuilder builder = new MirBuilder(func);
        builder.newLocal("this", MirType.ofObject(className));
        int nameVal = builder.emitGetField(0, "$name",
                MirType.ofObject("java/lang/Object"), null);
        builder.emitReturn(nameVal, null);
        return func;
    }

    /** 生成枚举 ordinal() 方法：返回 this.$ordinal */
    private MirFunction generateEnumOrdinalMethod(String className) {
        MirFunction func = new MirFunction("ordinal", MirType.ofObject("java/lang/Object"),
                Collections.emptyList(), EnumSet.noneOf(Modifier.class));
        MirBuilder builder = new MirBuilder(func);
        builder.newLocal("this", MirType.ofObject(className));
        int ordinalVal = builder.emitGetField(0, "$ordinal",
                MirType.ofObject("java/lang/Object"), null);
        builder.emitReturn(ordinalVal, null);
        return func;
    }

    /**
     * @param ownerClass 如果非 null，表示这是类的实例方法/构造器，在局部变量 0 处分配 this
     */
    private MirFunction lowerFunction(HirFunction hirFunc, String ownerClass) {
        List<MirParam> params = new ArrayList<>();
        for (HirParam p : hirFunc.getParams()) {
            // vararg 参数运行时是 List，类型用 Object
            MirType paramType = p.isVararg()
                    ? MirType.ofObject("java/lang/Object")
                    : hirTypeToMir(p.getType());
            params.add(new MirParam(p.getName(), paramType, p.getDefaultValue() != null));
        }

        MirType returnType;
        if (hirFunc.getReturnType() != null) {
            returnType = hirTypeToMir(hirFunc.getReturnType());
        } else if (hirFunc.getBody() != null) {
            // 有 body（expression body 或 block body）无显式返回类型 → 默认 Object
            // block body 可能包含显式 return value 语句，不能假设为 void
            returnType = MirType.ofObject("java/lang/Object");
        } else {
            // 无 body（抽象方法）→ void
            returnType = MirType.ofVoid();
        }
        // 脚本模式: main() 返回 Object（最后一个表达式的值）
        if (scriptMode && "main".equals(hirFunc.getName()) && returnType.getKind() == MirType.Kind.VOID) {
            returnType = MirType.ofObject("java/lang/Object");
        }

        Set<Modifier> funcModifiers = hirFunc.getModifiers();
        // 无 body 的方法标记为 ABSTRACT（接口抽象方法在 Parser 中无显式 abstract 关键字）
        if (hirFunc.getBody() == null && !funcModifiers.contains(Modifier.ABSTRACT)) {
            funcModifiers = new HashSet<>(funcModifiers);
            funcModifiers.add(Modifier.ABSTRACT);
        }
        MirFunction func = new MirFunction(hirFunc.getName(), returnType,
                params, funcModifiers);
        func.setMemoized(hasAnnotation(hirFunc.getAnnotations(), "memoized", "memoize")
                && ownerClass == null && !hirFunc.isConstructor() && !hirFunc.hasReifiedTypeParams());
        if (hirFunc.getTypeParams() != null && !hirFunc.getTypeParams().isEmpty()) {
            func.setTypeParams(hirFunc.getTypeParams());
            currentFunctionTypeParams = new HashSet<>(hirFunc.getTypeParams());
        } else {
            currentFunctionTypeParams = Collections.emptySet();
        }
        MirBuilder builder = new MirBuilder(func);
        boxedMutableCaptures.clear();
        scriptExportedVars.clear();

        // 实例方法/构造器：在索引 0 处分配 this
        if (ownerClass != null) {
            builder.newLocal("this", MirType.ofObject(ownerClass));
        }

        // 为参数分配局部变量
        for (MirParam p : params) {
            builder.newLocal(p.getName(), p.getType());
        }

        // 为 reified 类型参数分配 __reified_T 局部变量（运行时绑定实际类型名）
        for (String tp : currentFunctionTypeParams) {
            builder.newLocal("__reified_" + tp, MirType.ofObject("java/lang/String"));
        }

        // 默认参数：在函数体开头生成 null 检查 + 默认值赋值
        // 当函数被跨 evalRepl 调用且调用方缺少默认参数信息时，参数为 null
        for (int i = 0; i < hirFunc.getParams().size(); i++) {
            HirParam hp = hirFunc.getParams().get(i);
            if (hp.hasDefaultValue()) {
                int paramLocalIdx = (ownerClass != null ? 1 : 0) + i;
                SourceLocation loc = hirFunc.getLocation();
                // if (param == null) param = defaultValue
                int nullConst = builder.emitConstNull(loc);
                int cmpResult = builder.emitBinary(BinaryOp.EQ, paramLocalIdx, nullConst,
                        MirType.ofBoolean(), loc);
                BasicBlock defaultBlock = builder.newBlock();
                BasicBlock afterBlock = builder.newBlock();
                builder.emitBranch(cmpResult, defaultBlock.getId(), afterBlock.getId(), loc);
                builder.switchToBlock(defaultBlock);
                int defaultVal = lowerExpr(hp.getDefaultValue(), builder);
                builder.emitMoveTo(defaultVal, paramLocalIdx, loc);
                builder.emitGoto(afterBlock.getId(), loc);
                builder.switchToBlock(afterBlock);
            }
        }

        // 记录 body 起始块 ID（默认参数检查之后的块，构造器用于 SET_FIELD 插入位置）
        func.setBodyStartBlockId(builder.getCurrentBlock().getId());

        // 降级函数体
        if (hirFunc.getBody() != null) {
            int savedScopeDepth = blockScopeDepth;
            blockScopeDepth = 0;
            int result = lowerNode(hirFunc.getBody(), builder);
            blockScopeDepth = savedScopeDepth;
            // 确保最后有 return
            if (!builder.getCurrentBlock().hasTerminator()) {
                if (returnType.getKind() != MirType.Kind.VOID && result >= 0) {
                    builder.emitReturn(result, hirFunc.getLocation());
                } else if (returnType.getKind() != MirType.Kind.VOID) {
                    // 返回类型为 Object 但无显式结果值 → 返回 null
                    int nullVal = builder.emitConstNull(hirFunc.getLocation());
                    builder.emitReturn(nullVal, hirFunc.getLocation());
                } else {
                    builder.emitReturnVoid(hirFunc.getLocation());
                }
            }
        } else {
            builder.emitReturnVoid(hirFunc.getLocation());
        }

        return func;
    }

    /**
     * 为具有自定义 getter 的属性生成合成方法: get$fieldName()
     */
    private MirFunction generatePropertyGetter(HirField field, String ownerClass) {
        MirType returnType = MirType.ofObject("java/lang/Object");
        MirFunction func = new MirFunction("get$" + field.getName(), returnType,
                Collections.emptyList(), field.getModifiers());
        MirBuilder builder = new MirBuilder(func);
        builder.newLocal("this", MirType.ofObject(ownerClass));

        int savedScopeDepth = blockScopeDepth;
        blockScopeDepth = 0;
        int result = lowerNode(field.getGetterBody(), builder);
        blockScopeDepth = savedScopeDepth;
        if (!builder.getCurrentBlock().hasTerminator()) {
            if (result >= 0) {
                builder.emitReturn(result, field.getLocation());
            } else {
                int nullVal = builder.emitConstNull(field.getLocation());
                builder.emitReturn(nullVal, field.getLocation());
            }
        }
        return func;
    }

    /**
     * 为具有自定义 setter 的属性生成合成方法: set$fieldName(value)
     */
    private MirFunction generatePropertySetter(HirField field, String ownerClass) {
        List<MirParam> params = new ArrayList<>();
        String paramName = field.getSetterParam() != null ? field.getSetterParam().getName() : "value";
        params.add(new MirParam(paramName, MirType.ofObject("java/lang/Object")));

        MirFunction func = new MirFunction("set$" + field.getName(), MirType.ofVoid(),
                params, field.getModifiers());
        MirBuilder builder = new MirBuilder(func);
        builder.newLocal("this", MirType.ofObject(ownerClass));
        builder.newLocal(paramName, MirType.ofObject("java/lang/Object"));

        int savedScopeDepth = blockScopeDepth;
        blockScopeDepth = 0;
        lowerNode(field.getSetterBody(), builder);
        blockScopeDepth = savedScopeDepth;
        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitReturnVoid(field.getLocation());
        }
        return func;
    }

    /**
     * 扩展函数编译为静态方法，接收者作为第一个参数 $this。
     * fun Int.double() = this * 2  →  static Object double(Object $this)
     * 函数体中 ThisExpr 解析到 local 0 = $this (receiver)。
     */
    private MirFunction lowerExtensionFunction(HirFunction hirFunc, String ownerClass) {
        List<MirParam> params = new ArrayList<>();
        // 接收者作为第一个参数（使用实际类型以支持原始类型描述符）
        MirType receiverType = hirFunc.getReceiverType() != null
                ? hirTypeToMir(hirFunc.getReceiverType())
                : MirType.ofObject("java/lang/Object");
        params.add(new MirParam("$this", receiverType));
        for (HirParam p : hirFunc.getParams()) {
            params.add(new MirParam(p.getName(), hirTypeToMir(p.getType()), p.getDefaultValue() != null));
        }

        MirType returnType;
        if (hirFunc.getReturnType() != null) {
            returnType = hirTypeToMir(hirFunc.getReturnType());
        } else if (hirFunc.getBody() != null) {
            // block body 可能包含显式 return value 语句
            returnType = MirType.ofObject("java/lang/Object");
        } else {
            returnType = MirType.ofVoid();
        }

        Set<Modifier> mods = EnumSet.noneOf(Modifier.class);
        mods.addAll(hirFunc.getModifiers());
        mods.add(Modifier.STATIC);

        MirFunction func = new MirFunction(hirFunc.getName(), returnType, params, mods);

        // 计算并设置原始类型描述符（静态方法可安全使用原始类型）
        String nativeDesc = buildNativeStaticDescriptor(func);
        func.setOverrideDescriptor(nativeDesc);

        MirBuilder builder = new MirBuilder(func);

        // 静态方法: 不分配 class this，参数直接从 local 0 开始
        // local 0 = $this (receiver)，后续为普通参数
        for (MirParam p : params) {
            builder.newLocal(p.getName(), p.getType());
        }

        // 默认参数：在函数体开头生成 null 检查 + 默认值赋值
        // 扩展函数: local 0 = $this (receiver)，local 1+ = 普通参数
        for (int i = 0; i < hirFunc.getParams().size(); i++) {
            HirParam hp = hirFunc.getParams().get(i);
            if (hp.hasDefaultValue()) {
                int paramLocalIdx = 1 + i; // $this 占 local 0
                SourceLocation loc = hirFunc.getLocation();
                // if (param == null) param = defaultValue
                int nullConst = builder.emitConstNull(loc);
                int cmpResult = builder.emitBinary(BinaryOp.EQ, paramLocalIdx, nullConst,
                        MirType.ofBoolean(), loc);
                BasicBlock defaultBlock = builder.newBlock();
                BasicBlock afterBlock = builder.newBlock();
                builder.emitBranch(cmpResult, defaultBlock.getId(), afterBlock.getId(), loc);
                builder.switchToBlock(defaultBlock);
                int defaultVal = lowerExpr(hp.getDefaultValue(), builder);
                builder.emitMoveTo(defaultVal, paramLocalIdx, loc);
                builder.emitGoto(afterBlock.getId(), loc);
                builder.switchToBlock(afterBlock);
            }
        }

        // 降级函数体 — ThisExpr 返回 local 0 = $this (receiver)
        if (hirFunc.getBody() != null) {
            int result = lowerNode(hirFunc.getBody(), builder);
            if (!builder.getCurrentBlock().hasTerminator()) {
                if (returnType.getKind() != MirType.Kind.VOID && result >= 0) {
                    builder.emitReturn(result, hirFunc.getLocation());
                } else if (returnType.getKind() != MirType.Kind.VOID) {
                    // 返回类型为 Object 但无显式结果值 → 返回 null
                    int nullVal = builder.emitConstNull(hirFunc.getLocation());
                    builder.emitReturn(nullVal, hirFunc.getLocation());
                } else {
                    builder.emitReturnVoid(hirFunc.getLocation());
                }
            }
        } else {
            builder.emitReturnVoid(hirFunc.getLocation());
        }

        return func;
    }

    /**
     * 降级构造函数：在函数体之前插入参数到字段的赋值。
     */
    private MirFunction lowerConstructor(HirFunction hirFunc, String ownerClass,
                                          List<HirField> classFields,
                                          List<Expression> superConstructorArgs,
                                          List<AstNode> instanceInitializers) {
        MirFunction func = lowerFunction(hirFunc, ownerClass);

        // 超类构造器参数: super(arg1, arg2, ...)
        if (superConstructorArgs != null && !superConstructorArgs.isEmpty()
                && !hirFunc.hasDelegation()) {
            SourceLocation loc = hirFunc.getLocation();
            int[] superArgLocals = new int[superConstructorArgs.size()];
            BasicBlock entry = func.getBlocks().isEmpty() ? null : func.getBlocks().get(0);
            List<MirInst> prependInsts = new ArrayList<>();
            for (int i = 0; i < superConstructorArgs.size(); i++) {
                Expression arg = superConstructorArgs.get(i);
                if (arg instanceof Identifier) {
                    String varName = ((Identifier) arg).getName();
                    int found = -1;
                    for (MirLocal local : func.getLocals()) {
                        if (local.getName().equals(varName)) { found = local.getIndex(); break; }
                    }
                    superArgLocals[i] = found >= 0 ? found : 0;
                } else if (arg instanceof Literal) {
                    Literal lit = (Literal) arg;
                    int dest = func.newLocal("$sarg" + i, MirType.ofObject("java/lang/Object"));
                    superArgLocals[i] = dest;
                    switch (lit.getKind()) {
                        case INT: prependInsts.add(new MirInst(MirOp.CONST_INT, dest, null, lit.getValue(), loc)); break;
                        case STRING: prependInsts.add(new MirInst(MirOp.CONST_STRING, dest, null, lit.getValue(), loc)); break;
                        default: prependInsts.add(new MirInst(MirOp.CONST_NULL, dest, null, null, loc)); break;
                    }
                } else {
                    // 复杂表达式（如 extra * 2）：用临时 block 求值，再前置到 entry
                    if (entry != null) {
                        BasicBlock tmpBlock = func.newBlock();
                        MirBuilder argBuilder = new MirBuilder(func, tmpBlock);
                        int result = lowerExpr(arg, argBuilder);
                        superArgLocals[i] = result;
                        // 将临时 block 的指令前置到 entry（在其他 prepend 之后）
                        prependInsts.addAll(tmpBlock.getInstructions());
                        func.getBlocks().remove(tmpBlock);
                    } else {
                        int dest = func.newLocal("$sarg" + i, MirType.ofObject("java/lang/Object"));
                        superArgLocals[i] = dest;
                        prependInsts.add(new MirInst(MirOp.CONST_NULL, dest, null, null, loc));
                    }
                }
            }
            if (!prependInsts.isEmpty() && entry != null) {
                entry.getInstructions().addAll(0, prependInsts);
            }
            func.setSuperInitArgLocals(superArgLocals);
            // 记录超类名称，供 MirInterpreter 查找正确的超类构造器
            String superName = classSuperClass.get(ownerClass);
            if (superName != null) {
                func.setSuperClassName(superName);
            }
        }

        // 次级构造器: 委托到 this(...)，不插入 SET_FIELD（由被委托的构造器负责）
        if (hirFunc.hasDelegation()) {
            SourceLocation loc = hirFunc.getLocation();
            List<Expression> delegationExprs = hirFunc.getDelegationArgs();
            // lowerFunction 已将构造器体降级到 blocks 中。
            // 创建独立的 delegation block 并放到 position 0，body blocks 保留在 position 1+。
            // 这样 MirInterpreter 可以先执行 delegation args，调用委托构造器，再执行 body。
            // 记住原始 entry block（body 块入口），用于添加 GOTO 终止器
            BasicBlock originalEntry = func.getBlocks().isEmpty() ? null : func.getBlocks().get(0);
            BasicBlock delegBlock = func.newBlock(); // ID = 当前 blocks 数量（不与 body blocks 冲突）
            MirBuilder delegBuilder = new MirBuilder(func, delegBlock);

            int[] delegationLocals = new int[delegationExprs.size()];
            for (int i = 0; i < delegationExprs.size(); i++) {
                delegationLocals[i] = lowerExpr(delegationExprs.get(i), delegBuilder);
            }
            func.setDelegationArgLocals(delegationLocals);

            // 添加终止器: GOTO 到 body block（确保 DeadBlockElimination 不会删除后续块）
            if (originalEntry != null) {
                delegBuilder.emitGoto(originalEntry.getId(), loc);
            } else {
                delegBuilder.emitReturnVoid(loc);
            }

            // 将 delegation block 从末尾移到 position 0（body blocks 在 position 1+）
            func.getBlocks().remove(func.getBlocks().size() - 1);
            func.getBlocks().add(0, delegBlock);
            return func;
        }

        // 主构造器: 插入 SET_FIELD 指令（将构造参数存储到对应字段）
        Set<String> fieldNames = new HashSet<>();
        for (HirField f : classFields) {
            fieldNames.add(f.getName());
        }
        int paramFieldStoreCount = 0;
        if (!classFields.isEmpty() && !func.getBlocks().isEmpty()) {
            // 使用 body 起始块（默认参数处理之后的块），确保 SET_FIELD 在默认值赋值之后执行
            BasicBlock bodyBlock = func.getBlocks().get(func.getBodyStartBlockId());
            List<MirInst> fieldStores = new ArrayList<>();
            SourceLocation loc = hirFunc.getLocation();

            for (MirLocal local : func.getLocals()) {
                if (!"this".equals(local.getName()) && fieldNames.contains(local.getName())) {
                    fieldStores.add(new MirInst(MirOp.SET_FIELD, -1,
                            new int[]{0, local.getIndex()}, local.getName(), loc));
                }
            }
            if (!fieldStores.isEmpty()) {
                bodyBlock.getInstructions().addAll(0, fieldStores);
                paramFieldStoreCount = fieldStores.size();
            }
        }

        // 有序字段初始化器 + init 块
        // 正确执行顺序: SET_FIELD 参数 → instanceInitializers → 构造器 body
        if (instanceInitializers != null && !instanceInitializers.isEmpty()
                && !func.getBlocks().isEmpty()) {
            BasicBlock bodyBlock = func.getBlocks().get(func.getBodyStartBlockId());
            List<MirInst> allInsts = bodyBlock.getInstructions();
            // 分离: 参数 SET_FIELD（保留在前面）和 body 指令（移到初始化器之后）
            List<MirInst> bodyInsts = new ArrayList<>(allInsts.subList(paramFieldStoreCount, allInsts.size()));
            allInsts.subList(paramFieldStoreCount, allInsts.size()).clear();
            MirTerminator savedTerminator = bodyBlock.getTerminator();
            bodyBlock.setTerminator(null);
            MirBuilder initBuilder = new MirBuilder(func, bodyBlock);
            SourceLocation loc = hirFunc.getLocation();
            for (AstNode initNode : instanceInitializers) {
                if (initNode instanceof HirField) {
                    HirField f = (HirField) initNode;
                    if (f.getInitializer() != null && !f.getModifiers().contains(Modifier.STATIC)) {
                        int initVal = lowerNode(f.getInitializer(), initBuilder);
                        initBuilder.emitSetField(0, f.getName(), initVal, f.getLocation());
                    }
                } else {
                    // init 块体: lower 为 MIR 指令
                    lowerNode(initNode, initBuilder);
                }
            }
            // 在初始化器之后追加 body 指令
            initBuilder.getCurrentBlock().getInstructions().addAll(bodyInsts);
            // 恢复终止指令
            if (initBuilder.getCurrentBlock().getTerminator() == null) {
                if (savedTerminator != null) {
                    initBuilder.getCurrentBlock().setTerminator(savedTerminator);
                } else {
                    initBuilder.emitReturnVoid(loc);
                }
            }
        }

        return func;
    }

    /**
     * 解析次级构造器的委托链：[当前次级, 中间次级..., 主构造器]。
     * 返回 null 表示无法解析（循环、arity 歧义等）。
     */
    private List<HirFunction> resolveConstructorChain(HirFunction start,
                                                       List<HirFunction> allCtors) {
        List<HirFunction> chain = new ArrayList<>();
        Set<HirFunction> visited = new HashSet<>();
        HirFunction current = start;
        while (current.hasDelegation()) {
            if (!visited.add(current)) return null;
            chain.add(current);
            int targetArity = current.getDelegationArgs().size();
            HirFunction target = null;
            int matchCount = 0;
            for (HirFunction c : allCtors) {
                if (c == current) continue;
                if (c.getParams().size() == targetArity) {
                    target = c;
                    matchCount++;
                }
            }
            if (target == null || matchCount > 1) return null;
            current = target;
        }
        chain.add(current);
        return chain;
    }

    /**
     * 尝试将次级构造器的委托链在 lowering 阶段内联展平。
     * 成功时直接修改 func 的 blocks 并清除 delegation 标记；失败时不做任何修改。
     */
    private void tryInlineConstructorDelegation(HirFunction hirFunc, MirFunction func,
                                                 List<HirFunction> allCtors,
                                                 List<HirField> classFields,
                                                 List<AstNode> instanceInitializers) {
        List<HirFunction> chain = resolveConstructorChain(hirFunc, allCtors);
        if (chain == null || chain.size() < 2) return;

        // 逐级构建参数映射：paramName → localIndex in func
        Map<String, Integer> paramMap = new HashMap<>();
        for (MirLocal local : func.getLocals()) {
            if (!"this".equals(local.getName())) {
                paramMap.put(local.getName(), local.getIndex());
            }
        }

        SourceLocation loc = hirFunc.getLocation();
        List<MirInst> constInsts = new ArrayList<>();
        int[] finalArgLocals = null;

        for (int ci = 0; ci < chain.size() - 1; ci++) {
            HirFunction ctor = chain.get(ci);
            HirFunction target = chain.get(ci + 1);
            List<Expression> delegArgs = ctor.getDelegationArgs();
            int[] argLocals = new int[delegArgs.size()];

            for (int i = 0; i < delegArgs.size(); i++) {
                Expression arg = delegArgs.get(i);
                if (arg instanceof Identifier) {
                    String name = ((Identifier) arg).getName();
                    Integer mapped = paramMap.get(name);
                    if (mapped == null) return;
                    argLocals[i] = mapped;
                } else if (arg instanceof Literal) {
                    Literal lit = (Literal) arg;
                    int dest = func.newLocal("$deleg" + ci + "_" + i,
                            MirType.ofObject("java/lang/Object"));
                    argLocals[i] = dest;
                    switch (lit.getKind()) {
                        case INT:
                            constInsts.add(new MirInst(MirOp.CONST_INT, dest,
                                    null, lit.getValue(), loc));
                            break;
                        case LONG:
                            constInsts.add(new MirInst(MirOp.CONST_LONG, dest,
                                    null, lit.getValue(), loc));
                            break;
                        case STRING:
                            constInsts.add(new MirInst(MirOp.CONST_STRING, dest,
                                    null, lit.getValue(), loc));
                            break;
                        case BOOLEAN:
                            constInsts.add(new MirInst(MirOp.CONST_BOOL, dest,
                                    null, lit.getValue(), loc));
                            break;
                        case DOUBLE:
                            constInsts.add(new MirInst(MirOp.CONST_DOUBLE, dest,
                                    null, lit.getValue(), loc));
                            break;
                        case FLOAT:
                            constInsts.add(new MirInst(MirOp.CONST_FLOAT, dest,
                                    null, lit.getValue(), loc));
                            break;
                        case NULL:
                            constInsts.add(new MirInst(MirOp.CONST_NULL, dest,
                                    null, null, loc));
                            break;
                        default:
                            return; // fallback
                    }
                } else {
                    return; // 复杂表达式，fallback
                }
            }

            finalArgLocals = argLocals;

            // 为下一级构建参数映射
            if (ci < chain.size() - 2) {
                paramMap.clear();
                List<HirParam> targetParams = target.getParams();
                for (int pi = 0; pi < targetParams.size(); pi++) {
                    paramMap.put(targetParams.get(pi).getName(), argLocals[pi]);
                }
            }
        }

        if (finalArgLocals == null) return;

        // 生成 SET_FIELD：主构造器参数 → 字段
        HirFunction primary = chain.get(chain.size() - 1);
        Set<String> fieldNames = new HashSet<>();
        for (HirField f : classFields) {
            fieldNames.add(f.getName());
        }
        List<MirInst> fieldStores = new ArrayList<>();
        List<HirParam> primaryParams = primary.getParams();
        for (int pi = 0; pi < primaryParams.size(); pi++) {
            String pName = primaryParams.get(pi).getName();
            if (fieldNames.contains(pName) && pi < finalArgLocals.length) {
                fieldStores.add(new MirInst(MirOp.SET_FIELD, -1,
                        new int[]{0, finalArgLocals[pi]}, pName, loc));
            }
        }

        // 重组 blocks：移除 delegation block（当前 block 0），保留 body blocks
        List<BasicBlock> blocks = func.getBlocks();
        if (blocks.isEmpty()) return;
        blocks.remove(0); // 移除 delegation block

        BasicBlock bodyBlock;
        if (blocks.isEmpty()) {
            bodyBlock = func.newBlock();
        } else {
            bodyBlock = blocks.get(0);
        }

        // 在 body 最前面插入: CONST 指令 + SET_FIELD
        List<MirInst> allPrepend = new ArrayList<>(constInsts.size() + fieldStores.size());
        allPrepend.addAll(constInsts);
        allPrepend.addAll(fieldStores);
        bodyBlock.getInstructions().addAll(0, allPrepend);
        int paramFieldStoreCount = allPrepend.size();

        // instanceInitializers（复用主构造器路径的逻辑）
        if (instanceInitializers != null && !instanceInitializers.isEmpty()) {
            List<MirInst> allInsts = bodyBlock.getInstructions();
            List<MirInst> bodyInsts = new ArrayList<>(
                    allInsts.subList(paramFieldStoreCount, allInsts.size()));
            allInsts.subList(paramFieldStoreCount, allInsts.size()).clear();
            MirTerminator savedTerminator = bodyBlock.getTerminator();
            bodyBlock.setTerminator(null);
            MirBuilder initBuilder = new MirBuilder(func, bodyBlock);
            for (AstNode initNode : instanceInitializers) {
                if (initNode instanceof HirField) {
                    HirField f = (HirField) initNode;
                    if (f.getInitializer() != null
                            && !f.getModifiers().contains(Modifier.STATIC)) {
                        int initVal = lowerNode(f.getInitializer(), initBuilder);
                        initBuilder.emitSetField(0, f.getName(), initVal, f.getLocation());
                    }
                } else {
                    lowerNode(initNode, initBuilder);
                }
            }
            initBuilder.getCurrentBlock().getInstructions().addAll(bodyInsts);
            if (initBuilder.getCurrentBlock().getTerminator() == null) {
                if (savedTerminator != null) {
                    initBuilder.getCurrentBlock().setTerminator(savedTerminator);
                } else {
                    initBuilder.emitReturnVoid(loc);
                }
            }
        }

        // 清除 delegation 标记 → 运行时不再走委托路径
        func.setDelegationArgLocals(null);
    }

    /**
     * 为无显式构造器的类生成合成构造器，编译字段默认值初始化。
     * 返回 null 表示无需合成（没有字段初始化器）。
     */
    private MirFunction generateFieldInitConstructor(HirClass hirClass, String className) {
        // 优先使用有序初始化列表（字段初始化器 + init 块）
        List<AstNode> instanceInitializers = hirClass.getInstanceInitializers();
        if (instanceInitializers != null && !instanceInitializers.isEmpty()) {
            MirFunction ctor = new MirFunction("<init>", MirType.ofVoid(),
                    Collections.emptyList(), EnumSet.of(Modifier.PUBLIC));
            MirBuilder builder = new MirBuilder(ctor);
            builder.newLocal("this", MirType.ofObject(className));
            for (AstNode initNode : instanceInitializers) {
                if (initNode instanceof HirField) {
                    HirField f = (HirField) initNode;
                    if (f.getInitializer() != null && !f.getModifiers().contains(Modifier.STATIC)) {
                        int initVal = lowerNode(f.getInitializer(), builder);
                        builder.emitSetField(0, f.getName(), initVal, f.getLocation());
                    }
                } else {
                    lowerNode(initNode, builder);
                }
            }
            builder.emitReturnVoid(hirClass.getLocation());
            return ctor;
        }

        // 回退：仅字段初始化器
        boolean hasInit = false;
        for (HirField f : hirClass.getFields()) {
            if (f.getInitializer() != null && !f.getModifiers().contains(Modifier.STATIC)) {
                hasInit = true;
                break;
            }
        }
        if (!hasInit) return null;

        MirFunction ctor = new MirFunction("<init>", MirType.ofVoid(),
                Collections.emptyList(), EnumSet.of(Modifier.PUBLIC));
        MirBuilder builder = new MirBuilder(ctor);
        builder.newLocal("this", MirType.ofObject(className));
        SourceLocation loc = hirClass.getLocation();

        for (HirField f : hirClass.getFields()) {
            if (f.getInitializer() != null && !f.getModifiers().contains(Modifier.STATIC)) {
                int initVal = lowerNode(f.getInitializer(), builder);
                builder.emitSetField(0, f.getName(), initVal, loc);
            }
        }

        builder.emitReturnVoid(loc);
        return ctor;
    }

    /**
     * 为有静态字段初始化器的类生成 <clinit>（如 companion object 字段）。
     */
    private MirFunction generateStaticFieldClinit(HirClass hirClass, String className) {
        boolean hasStaticInit = false;
        for (HirField f : hirClass.getFields()) {
            if (f.getInitializer() != null && f.getModifiers().contains(Modifier.STATIC)) {
                hasStaticInit = true;
                break;
            }
        }
        if (!hasStaticInit) return null;

        Set<Modifier> staticMods = EnumSet.of(Modifier.STATIC);
        MirFunction clinit = new MirFunction("<clinit>", MirType.ofVoid(),
                Collections.emptyList(), staticMods);
        MirBuilder builder = new MirBuilder(clinit);
        SourceLocation loc = hirClass.getLocation();

        for (HirField f : hirClass.getFields()) {
            if (f.getInitializer() != null && f.getModifiers().contains(Modifier.STATIC)) {
                int initVal = lowerExpr(f.getInitializer(), builder);
                builder.emitSetStatic(className, f.getName(),
                        MethodDescriptor.OBJECT_DESC, initVal, loc);
            }
        }
        builder.emitReturnVoid(loc);
        return clinit;
    }

    // ========== 节点降级 ==========

    /**
     * 降级一个 HIR 节点（语句或表达式），返回结果局部变量索引（-1 表示无结果）。
     */
    private int lowerNode(AstNode node, MirBuilder builder) {
        if (node instanceof Block) return lowerBlock((Block) node, builder);
        if (node instanceof ExpressionStmt) return lowerExprStmt((ExpressionStmt) node, builder);
        if (node instanceof HirDeclStmt) return lowerDeclStmt((HirDeclStmt) node, builder);
        if (node instanceof IfStmt) return lowerIf((IfStmt) node, builder);
        if (node instanceof HirLoop) return lowerLoop((HirLoop) node, builder);
        if (node instanceof ForStmt) return lowerFor((ForStmt) node, builder);
        if (node instanceof CStyleForStmt) return lowerCStyleFor((CStyleForStmt) node, builder);
        if (node instanceof HirTry) return lowerTry((HirTry) node, builder);
        if (node instanceof ReturnStmt) return lowerReturn((ReturnStmt) node, builder);
        if (node instanceof ThrowStmt) return lowerThrow((ThrowStmt) node, builder);
        if (node instanceof BreakStmt) return lowerBreakContinue(true, ((BreakStmt) node).getLabel(), node.getLocation(), builder);
        if (node instanceof ContinueStmt) return lowerBreakContinue(false, ((ContinueStmt) node).getLabel(), node.getLocation(), builder);
        if (node instanceof Expression) return lowerExpr((Expression) node, builder);
        return -1;
    }

    private int lowerBlock(Block block, MirBuilder builder) {
        int last = -1;
        for (Statement stmt : block.getStatements()) {
            last = lowerNode(stmt, builder);
        }
        return last;
    }

    private int lowerExprStmt(ExpressionStmt stmt, MirBuilder builder) {
        return lowerExpr(stmt.getExpression(), builder);
    }

    private int lowerDeclStmt(HirDeclStmt stmt, MirBuilder builder) {
        HirDecl decl = stmt.getDeclaration();
        if (decl instanceof HirClass) {
            return -1; // 已提升到顶层处理
        }
        if (decl instanceof HirTypeAlias) {
            typeAliasMap.put(((HirTypeAlias) decl).getName(), ((HirTypeAlias) decl).getTargetType());
            return -1;
        }
        if (decl instanceof HirField) {
            HirField field = (HirField) decl;
            if (field.hasInitializer()) {
                int value = lowerExpr(field.getInitializer(), builder);
                MirType localType = hirTypeToMir(field.getType());
                // 如果声明类型是泛型 Object，使用初始化值的更具体类型
                if (value >= 0 && localType.getKind() == MirType.Kind.OBJECT
                        && "java/lang/Object".equals(localType.getClassName())) {
                    List<MirLocal> locals = builder.getFunction().getLocals();
                    if (value < locals.size()) {
                        MirType valueType = locals.get(value).getType();
                        // 允许提升到原始类型（INT/LONG/DOUBLE/FLOAT/BOOLEAN）或更具体的对象类型
                        if (valueType.getKind() == MirType.Kind.INT
                                || valueType.getKind() == MirType.Kind.LONG
                                || valueType.getKind() == MirType.Kind.DOUBLE
                                || valueType.getKind() == MirType.Kind.FLOAT
                                || valueType.getKind() == MirType.Kind.BOOLEAN
                                || (valueType.getKind() == MirType.Kind.OBJECT
                                    && valueType.getClassName() != null
                                    && !"java/lang/Object".equals(valueType.getClassName()))) {
                            localType = valueType;
                        }
                    }
                }
                int local = builder.newLocal(field.getName(), localType);
                // 直接将初始化值 MOVE 到命名的局部变量
                builder.emitMoveTo(value, local, field.getLocation());
                // lazy 变量：val x by lazy { expr } → 内联懒加载
                if (field.isLazy()) {
                    // 创建辅助 locals: lambda 存储 + 缓存值 + 初始化标记
                    int cacheLocal = builder.newLocal(field.getName() + "$cache",
                            MirType.ofObject("java/lang/Object"));
                    builder.emitMoveTo(builder.emitConstNull(field.getLocation()), cacheLocal, field.getLocation());
                    int doneLocal = builder.newLocal(field.getName() + "$done", MirType.ofBoolean());
                    builder.emitMoveTo(builder.emitConstBool(false, field.getLocation()), doneLocal, field.getLocation());
                    lazyVarLocals.put(field.getName(), new int[]{local, cacheLocal, doneLocal});
                }
                // javaClass() 常量传播：val X = javaClass("literal") → 记录绑定
                if (field.isVal()) {
                    String jcClassName = extractJavaClassName(field.getInitializer());
                    if (jcClassName != null) {
                        javaClassBindings.put(field.getName(), jcClassName.replace('.', '/'));
                    }
                }
                // 顶层变量 → $Module 静态字段（PUTSTATIC 初始化）
                // 块作用域内（while/if/for/try）的变量仅通过寄存器访问，跳过静态字段注册
                if (scriptMode && blockScopeDepth == 0
                        && "main".equals(builder.getFunction().getName())) {
                    topLevelFieldNames.put(field.getName(), field.isVal());
                    builder.emitPutStatic(moduleClassName, field.getName(),
                            "Ljava/lang/Object;", local, field.getLocation());
                    // 同步到 NovaScriptContext（REPL 跨 eval 共享 + JSR-223 兼容）
                    int nameConst = builder.emitConstString(field.getName(), field.getLocation());
                    String defineOp = field.isVal() ? "defineVal" : "defineVar";
                    builder.emitInvokeStatic(
                            scriptContextOwner() + "|" + defineOp + "|(Ljava/lang/String;Ljava/lang/Object;)V",
                            new int[]{nameConst, local},
                            MirType.ofVoid(), field.getLocation());
                }
                return local;
            }
            return builder.newLocal(field.getName(), hirTypeToMir(field.getType()));
        }
        if (decl instanceof HirFunction) {
            // 局部函数 → 编译为 lambda 类并存储到命名局部变量
            HirFunction func = (HirFunction) decl;
            localFunctionDecls.put(func.getName(), func);
            // 检查是否自引用（递归嵌套函数）：预分配局部变量以便捕获分析能找到它
            Set<String> bodyRefs = new LinkedHashSet<>();
            collectVarRefs(func.getBody(), bodyRefs);
            boolean isSelfRecursive = bodyRefs.contains(func.getName());
            int preLocal = -1;
            if (isSelfRecursive) {
                preLocal = builder.newLocal(func.getName(), MirType.ofObject("java/lang/Object"));
            }
            HirLambda lambda = new HirLambda(func.getLocation(), null,
                    func.getParams(), func.getBody(), Collections.emptyList());
            int lambdaInst = lowerLambda(lambda, builder);
            if (preLocal >= 0) {
                // 自引用：赋值到预分配的 local，并回填捕获字段
                builder.emitMoveTo(lambdaInst, preLocal, func.getLocation());
                builder.emitSetField(lambdaInst, func.getName(), preLocal, func.getLocation());
                return preLocal;
            }
            MirType lambdaType = builder.getFunction().getLocals().get(lambdaInst).getType();
            int local = builder.newLocal(func.getName(), lambdaType);
            builder.emitMoveTo(lambdaInst, local, func.getLocation());
            return local;
        }
        return -1;
    }

    /**
     * if → Branch(cond, B_then, B_else) + merge
     */
    private int lowerIf(IfStmt node, MirBuilder builder) {
        SourceLocation loc = node.getLocation();
        int cond = lowerExpr(node.getCondition(), builder);

        BasicBlock thenBlock = builder.newBlock();
        BasicBlock elseBlock = node.hasElse() ? builder.newBlock() : null;
        BasicBlock mergeBlock = builder.newBlock();

        // 预分配结果变量（初始 null，用于合并分支值）
        // 无 else 时也分配：WhenStmt 降级为嵌套 IfStmt 链，需逐层传递结果
        int resultLocal = builder.emitConstNull(loc);

        builder.emitBranch(cond, thenBlock.getId(),
                elseBlock != null ? elseBlock.getId() : mergeBlock.getId(), loc);

        // then
        builder.switchToBlock(thenBlock);
        blockScopeDepth++;
        int thenVal = lowerNode(node.getThenBranch(), builder);
        blockScopeDepth--;
        if (thenVal >= 0 && resultLocal >= 0 && !builder.getCurrentBlock().hasTerminator()) {
            builder.emitMoveTo(thenVal, resultLocal, loc);
        }
        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitGoto(mergeBlock.getId(), loc);
        }

        // else
        int elseVal = -1;
        if (node.hasElse()) {
            builder.switchToBlock(elseBlock);
            blockScopeDepth++;
            elseVal = lowerNode(node.getElseBranch(), builder);
            blockScopeDepth--;
            if (elseVal >= 0 && resultLocal >= 0 && !builder.getCurrentBlock().hasTerminator()) {
                builder.emitMoveTo(elseVal, resultLocal, loc);
            }
            if (!builder.getCurrentBlock().hasTerminator()) {
                builder.emitGoto(mergeBlock.getId(), loc);
            }
        }

        builder.switchToBlock(mergeBlock);
        return resultLocal;
    }

    /**
     * 条件表达式 → Branch + 两分支写入同一结果变量 + merge。
     * 替代 Phi 节点：两个分支将结果 MOVE 到共享的结果局部变量。
     */
    private int lowerConditional(ConditionalExpr node, MirBuilder builder) {
        // 尝试优化为 Switch（枚举/常量 when 链）
        int switchResult = trySwitchOptimization(node, builder);
        if (switchResult >= 0) return switchResult;

        SourceLocation loc = node.getLocation();
        int cond = lowerExpr(node.getCondition(), builder);

        // 预分配并初始化结果局部变量（确保 JVM 验证器在所有路径上都能看到已初始化的 local）
        int resultLocal = builder.emitConstNull(loc);

        BasicBlock thenBlock = builder.newBlock();
        BasicBlock elseBlock = builder.newBlock();
        BasicBlock mergeBlock = builder.newBlock();

        builder.emitBranch(cond, thenBlock.getId(), elseBlock.getId(), loc);

        // then 分支：计算结果并写入 resultLocal
        builder.switchToBlock(thenBlock);
        int thenVal = lowerExpr(node.getThenExpr(), builder);
        if (thenVal >= 0) {
            builder.emitMoveTo(thenVal, resultLocal, loc);
        }
        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitGoto(mergeBlock.getId(), loc);
        }

        // else 分支：计算结果并写入 resultLocal
        builder.switchToBlock(elseBlock);
        int elseVal = lowerExpr(node.getElseExpr(), builder);
        if (elseVal >= 0) {
            builder.emitMoveTo(elseVal, resultLocal, loc);
        }
        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitGoto(mergeBlock.getId(), loc);
        }

        builder.switchToBlock(mergeBlock);
        return resultLocal;
    }

    /**
     * 尝试将 ConditionalExpr 链优化为 MirTerminator.Switch。
     * 检测模式: 嵌套 if-else 链，每个条件是 subject == constant_key（枚举/int/string）。
     * @return 结果局部变量索引，不匹配时返回 -1
     */
    private int trySwitchOptimization(ConditionalExpr node, MirBuilder builder) {
        List<Object[]> switchCases = new ArrayList<>(); // [caseKey, thenExpr]
        Expression subject = null;
        Expression current = node;

        while (current instanceof ConditionalExpr) {
            ConditionalExpr ce = (ConditionalExpr) current;
            List<Object> keys = new ArrayList<>();
            Expression detectedSubject = extractEqKeys(ce.getCondition(), keys);
            if (detectedSubject == null || keys.isEmpty()) break;

            if (subject == null) subject = detectedSubject;
            else if (subject != detectedSubject) break; // 不同 subject 引用，放弃

            // guard 条件会产生嵌套 ConditionalExpr 作为 thenExpr，此时不能优化为 switch
            if (ce.getThenExpr() instanceof ConditionalExpr) break;

            for (Object key : keys) {
                switchCases.add(new Object[]{key, ce.getThenExpr()});
            }
            current = ce.getElseExpr();
        }

        if (switchCases.size() >= 2) {
            return lowerAsSwitch(subject, switchCases, current, builder, node.getLocation());
        }
        return -1;
    }

    /**
     * 从条件表达式提取 EQ 比较的 keys。处理单个 EQ 和 OR 组合。
     * @return subject 表达式引用，不匹配返回 null
     */
    private Expression extractEqKeys(Expression cond, List<Object> keys) {
        if (!(cond instanceof BinaryExpr)) return null;
        BinaryExpr bin = (BinaryExpr) cond;

        if (bin.getOperator() == BinaryExpr.BinaryOp.EQ) {
            Object key = extractConstantKey(bin.getRight());
            if (key == null) return null;
            keys.add(key);
            return bin.getLeft();
        }

        if (bin.getOperator() == BinaryExpr.BinaryOp.OR) {
            Expression leftSubject = extractEqKeys(bin.getLeft(), keys);
            if (leftSubject == null) return null;
            Expression rightSubject = extractEqKeys(bin.getRight(), keys);
            if (rightSubject == null || leftSubject != rightSubject) return null;
            return leftSubject;
        }

        return null;
    }

    /**
     * 提取编译期常量 key（枚举条目名/int/string 字面量）。
     */
    private Object extractConstantKey(Expression expr) {
        if (expr instanceof MemberExpr) {
            MemberExpr fa = (MemberExpr) expr;
            if (fa.getTarget() instanceof Identifier) {
                String cls = ((Identifier) fa.getTarget()).getName();
                if (enumClassNames.contains(cls)) return fa.getMember();
            }
        }
        if (expr instanceof Literal) {
            Literal lit = (Literal) expr;
            if (lit.getKind() == LiteralKind.INT) return lit.getValue();
            if (lit.getKind() == LiteralKind.STRING) return lit.getValue();
        }
        return null;
    }

    /**
     * 生成 Switch MIR：subject 求值一次，各 case 走 HashMap O(1) 查找。
     */
    private int lowerAsSwitch(Expression subject, List<Object[]> cases,
                              Expression elseExpr, MirBuilder builder, SourceLocation loc) {
        int subjectLocal = lowerExpr(subject, builder);
        int resultLocal = builder.emitConstNull(loc);

        BasicBlock defaultBlock = builder.newBlock();
        BasicBlock mergeBlock = builder.newBlock();
        Map<Object, Integer> caseMap = new LinkedHashMap<>();

        // 同一 thenExpr 引用的多个 key 共享同一块（处理 1, 2 -> body 场景）
        IdentityHashMap<Expression, BasicBlock> exprBlocks = new IdentityHashMap<>();
        List<Expression> orderedExprs = new ArrayList<>();

        for (Object[] c : cases) {
            Expression thenExpr = (Expression) c[1];
            BasicBlock block = exprBlocks.get(thenExpr);
            if (block == null) {
                block = builder.newBlock();
                exprBlocks.put(thenExpr, block);
                orderedExprs.add(thenExpr);
            }
            caseMap.put(c[0], block.getId());
        }

        builder.emitSwitch(subjectLocal, caseMap, defaultBlock.getId(), loc);

        for (Expression thenExpr : orderedExprs) {
            builder.switchToBlock(exprBlocks.get(thenExpr));
            int val = lowerExpr(thenExpr, builder);
            if (val >= 0) builder.emitMoveTo(val, resultLocal, loc);
            if (!builder.getCurrentBlock().hasTerminator()) builder.emitGoto(mergeBlock.getId(), loc);
        }

        builder.switchToBlock(defaultBlock);
        int elseVal = lowerExpr(elseExpr, builder);
        if (elseVal >= 0) builder.emitMoveTo(elseVal, resultLocal, loc);
        if (!builder.getCurrentBlock().hasTerminator()) builder.emitGoto(mergeBlock.getId(), loc);

        builder.switchToBlock(mergeBlock);
        return resultLocal;
    }

    /**
     * while/do-while → header, body, exit blocks
     */
    private int lowerLoop(HirLoop node, MirBuilder builder) {
        SourceLocation loc = node.getLocation();

        BasicBlock headerBlock = builder.newBlock();
        BasicBlock bodyBlock = builder.newBlock();
        BasicBlock exitBlock = builder.newBlock();

        loopStack.push(new LoopContext(node.getLabel(), headerBlock.getId(), exitBlock.getId()));
        blockScopeDepth++;
        try {
            if (node.isDoWhile()) {
                // do-while: 先执行 body
                builder.emitGoto(bodyBlock.getId(), loc);

                builder.switchToBlock(bodyBlock);
                lowerNode(node.getBody(), builder);
                if (!builder.getCurrentBlock().hasTerminator()) {
                    builder.emitGoto(headerBlock.getId(), loc);
                }

                builder.switchToBlock(headerBlock);
                int cond = lowerExpr(node.getCondition(), builder);
                builder.emitBranch(cond, bodyBlock.getId(), exitBlock.getId(), loc);
            } else {
                // while: 先检查条件
                builder.emitGoto(headerBlock.getId(), loc);

                builder.switchToBlock(headerBlock);
                int cond = lowerExpr(node.getCondition(), builder);
                builder.emitBranch(cond, bodyBlock.getId(), exitBlock.getId(), loc);

                builder.switchToBlock(bodyBlock);
                lowerNode(node.getBody(), builder);
                if (!builder.getCurrentBlock().hasTerminator()) {
                    builder.emitGoto(headerBlock.getId(), loc);
                }
            }
        } finally {
            blockScopeDepth--;
            loopStack.pop();
        }

        builder.switchToBlock(exitBlock);
        return -1;
    }

    /**
     * for → 展开为 iterator 模式或计数器循环
     */
    private int lowerFor(ForStmt node, MirBuilder builder) {
        SourceLocation loc = node.getLocation();

        // 特殊处理：for (i in start..end) → 计数器循环
        if (node.getIterable() instanceof RangeExpr) {
            return lowerForRange(node, (RangeExpr) node.getIterable(), builder);
        }

        // 通用路径：NovaCollections.toIterable() 桥接 + Iterator 接口
        int iterable = lowerExpr(node.getIterable(), builder);
        int iterableObj = builder.emitInvokeStatic(
                "com/novalang/runtime/NovaCollections|toIterable|(Ljava/lang/Object;)Ljava/lang/Iterable;",
                new int[]{iterable}, MirType.ofObject("java/lang/Iterable"), loc);
        int iter = builder.emitInvokeInterfaceDesc(iterableObj, "iterator", new int[0],
                "java/lang/Iterable", "()Ljava/util/Iterator;",
                MirType.ofObject("java/util/Iterator"), loc);

        BasicBlock headerBlock = builder.newBlock();
        BasicBlock bodyBlock = builder.newBlock();
        BasicBlock exitBlock = builder.newBlock();

        builder.emitGoto(headerBlock.getId(), loc);

        // header: hasNext() check
        builder.switchToBlock(headerBlock);
        int hasNext = builder.emitInvokeInterfaceDesc(iter, "hasNext", new int[0],
                "java/util/Iterator", "()Z",
                MirType.ofBoolean(), loc);
        builder.emitBranch(hasNext, bodyBlock.getId(), exitBlock.getId(), loc);

        // body: next() + loop body
        builder.switchToBlock(bodyBlock);
        int next = builder.emitInvokeInterfaceDesc(iter, "next", new int[0],
                "java/util/Iterator", "()Ljava/lang/Object;",
                MirType.ofObject("java/lang/Object"), loc);

        // 绑定循环变量
        List<DestructuringEntry> entries = node.getEntries();
        if (entries.size() == 1 && !entries.get(0).isNameBased()) {
            String varName = entries.get(0).getLocalName();
            if (varName != null) {
                int varLocal = builder.newLocal(varName, MirType.ofObject("java/lang/Object"));
                builder.emitMoveTo(next, varLocal, loc);
            }
        } else {
            // 解构：支持位置和名称解构
            for (int vi = 0; vi < entries.size(); vi++) {
                DestructuringEntry entry = entries.get(vi);
                if (entry.isSkip()) continue;
                String varName = entry.getLocalName();
                if (varName == null) continue;

                int value;
                if (entry.isNameBased()) {
                    // 名称解构: NovaDynamic.getMember(next, propertyName)
                    // 使用动态成员访问（编译模式下 next 类型为 Object，无法用 GETFIELD）
                    int nameConst = builder.emitConstString(entry.getPropertyName(), loc);
                    value = builder.emitInvokeStatic(
                            "com/novalang/runtime/NovaDynamic|getMember|(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;",
                            new int[]{next, nameConst}, MirType.ofObject("java/lang/Object"), loc);
                } else {
                    // 位置解构: componentN(next, vi+1)
                    int nConst = builder.emitConstInt(vi + 1, loc);
                    value = builder.emitInvokeStatic(
                            "com/novalang/runtime/NovaCollections|componentN|(Ljava/lang/Object;I)Ljava/lang/Object;",
                            new int[]{next, nConst}, MirType.ofObject("java/lang/Object"), loc);
                }
                int varLocal = builder.newLocal(varName, MirType.ofObject("java/lang/Object"));
                builder.emitMoveTo(value, varLocal, loc);
            }
        }

        loopStack.push(new LoopContext(node.getLabel(), headerBlock.getId(), exitBlock.getId()));
        blockScopeDepth++;
        try {
            lowerNode(node.getBody(), builder);
        } finally {
            blockScopeDepth--;
            loopStack.pop();
        }
        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitGoto(headerBlock.getId(), loc);
        }

        builder.switchToBlock(exitBlock);
        return -1;
    }

    /**
     * for (i in start..end) → 计数器循环（无需 iterator）
     */
    private int lowerForRange(ForStmt node, RangeExpr range, MirBuilder builder) {
        SourceLocation loc = node.getLocation();

        // 计算 start 和 end
        int start = lowerExpr(range.getStart(), builder);
        int end = lowerExpr(range.getEnd(), builder);

        // 创建循环变量（INT 类型，避免装箱开销）
        String varName = node.getEntries().get(0).getLocalName();
        int loopVar = builder.newLocal(varName, MirType.ofInt());
        builder.emitMoveTo(start, loopVar, loc);

        BasicBlock headerBlock = builder.newBlock();
        BasicBlock bodyBlock = builder.newBlock();
        BasicBlock exitBlock = builder.newBlock();

        builder.emitGoto(headerBlock.getId(), loc);

        // header: check loopVar <= end (inclusive) 或 < end (exclusive)
        builder.switchToBlock(headerBlock);
        BinaryOp cmpOp = range.isEndExclusive() ? BinaryOp.LT : BinaryOp.LE;
        int cond = builder.emitBinary(cmpOp, loopVar, end, MirType.ofBoolean(), loc);
        builder.emitBranch(cond, bodyBlock.getId(), exitBlock.getId(), loc);

        // body
        builder.switchToBlock(bodyBlock);

        // continue 应跳转到递增+header，而非直接跳 header（否则跳过 i++）
        BasicBlock incrBlock = builder.newBlock();
        loopStack.push(new LoopContext(node.getLabel(), incrBlock.getId(), exitBlock.getId()));
        blockScopeDepth++;
        try {
            lowerNode(node.getBody(), builder);
        } finally {
            blockScopeDepth--;
            loopStack.pop();
        }

        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitGoto(incrBlock.getId(), loc);
        }

        // 递增循环变量（支持 step）
        builder.switchToBlock(incrBlock);
        int stepVal;
        if (range.hasStep()) {
            stepVal = lowerExpr(range.getStep(), builder);
        } else {
            stepVal = builder.emitConstInt(1, loc);
        }
        int incremented = builder.emitBinary(BinaryOp.ADD, loopVar, stepVal, MirType.ofInt(), loc);
        builder.emitMoveTo(incremented, loopVar, loc);
        builder.emitGoto(headerBlock.getId(), loc);

        builder.switchToBlock(exitBlock);
        return -1;
    }

    /**
     * C 风格 for: for (var i = 0; i < n; i += 1) { ... }
     */
    private int lowerCStyleFor(CStyleForStmt node, MirBuilder builder) {
        SourceLocation loc = node.getLocation();

        // init: 声明循环变量并赋初值
        int initVal = lowerExpr(node.getInit(), builder);
        int loopVar = builder.newLocal(node.getVarName(), MirType.ofObject("java/lang/Object"));
        builder.emitMoveTo(initVal, loopVar, loc);

        BasicBlock headerBlock = builder.newBlock();
        BasicBlock bodyBlock = builder.newBlock();
        BasicBlock incrBlock = builder.newBlock();
        BasicBlock exitBlock = builder.newBlock();

        builder.emitGoto(headerBlock.getId(), loc);

        // header: condition check
        builder.switchToBlock(headerBlock);
        if (node.getCondition() != null) {
            int cond = lowerExpr(node.getCondition(), builder);
            builder.emitBranch(cond, bodyBlock.getId(), exitBlock.getId(), loc);
        } else {
            builder.emitGoto(bodyBlock.getId(), loc);
        }

        // body
        builder.switchToBlock(bodyBlock);
        loopStack.push(new LoopContext(node.getLabel(), incrBlock.getId(), exitBlock.getId()));
        blockScopeDepth++;
        try {
            lowerNode(node.getBody(), builder);
        } finally {
            blockScopeDepth--;
            loopStack.pop();
        }
        if (!builder.getCurrentBlock().hasTerminator()) {
            builder.emitGoto(incrBlock.getId(), loc);
        }

        // incr: update expression
        builder.switchToBlock(incrBlock);
        if (node.getUpdate() != null) {
            lowerExpr(node.getUpdate(), builder);
        }
        builder.emitGoto(headerBlock.getId(), loc);

        builder.switchToBlock(exitBlock);
        return -1;
    }

    /**
     * try-catch-finally
     */
    private int lowerTry(HirTry node, MirBuilder builder) {
        SourceLocation loc = node.getLocation();

        // 预分配结果局部变量并初始化为 null（确保 JVM 验证器在所有路径上都能看到已初始化的 local）
        int resultLocal = builder.emitConstNull(loc);

        // try 开始块
        BasicBlock tryStartBlock = builder.newBlock();
        builder.emitGoto(tryStartBlock.getId(), loc);
        builder.switchToBlock(tryStartBlock);

        // 如有 finally，入栈上下文（lowerReturn/lowerThrow 会内联 finally）
        if (node.hasFinally()) {
            finallyStack.push(node.getFinallyBlock());
        }

        // 降级 try 体
        blockScopeDepth++;
        int tryResult = lowerNode(node.getTryBlock(), builder);
        blockScopeDepth--;

        // merge 块（同时也是 try 的 end 标记）
        BasicBlock mergeBlock = builder.newBlock();
        if (!builder.getCurrentBlock().hasTerminator()) {
            if (tryResult >= 0) {
                builder.emitMoveTo(tryResult, resultLocal, loc);
            }
            builder.emitGoto(mergeBlock.getId(), loc);
        }

        // 降级每个 catch 子句
        BasicBlock firstCatchBlock = null;
        for (HirTry.CatchClause catchClause : node.getCatches()) {
            BasicBlock catchBlock = builder.newBlock();
            if (firstCatchBlock == null) firstCatchBlock = catchBlock;
            builder.switchToBlock(catchBlock);

            // 异常参数 local（MirCodeGenerator 在 handler 入口 ASTORE 栈顶异常）
            int exLocal = builder.newLocal(catchClause.getParamName(),
                    MirType.ofObject("java/lang/Exception"));

            // 降级 catch 体
            blockScopeDepth++;
            int catchResult = lowerNode(catchClause.getBody(), builder);
            blockScopeDepth--;

            // 密封 catch 参数作用域：重命名 catch 局部变量，防止在 catch 外部被名称查找误匹配
            List<MirLocal> allLocals = builder.getFunction().getLocals();
            if (exLocal < allLocals.size()) {
                MirLocal catchLocal = allLocals.get(exLocal);
                if (catchLocal.getName().equals(catchClause.getParamName())) {
                    catchLocal.setName("$catch$" + catchLocal.getName());
                }
            }

            if (!builder.getCurrentBlock().hasTerminator()) {
                if (catchResult >= 0) {
                    builder.emitMoveTo(catchResult, resultLocal, loc);
                }
                builder.emitGoto(mergeBlock.getId(), loc);
            }

            // 注册异常表（未指定异常类型时默认捕获 Exception）
            String exType = catchClause.getExceptionType() != null
                    ? typeToInternalName(catchClause.getExceptionType())
                    : "java/lang/Exception";
            builder.getFunction().addTryCatchEntry(
                    tryStartBlock.getId(), mergeBlock.getId(),
                    catchBlock.getId(), exType, exLocal);
            // 多异常捕获: 为备选类型注册额外的异常表条目（指向同一 handler block）
            if (catchClause.isMultiCatch()) {
                for (HirType extraType : catchClause.getExtraExceptionTypes()) {
                    String extraExType = typeToInternalName(extraType);
                    builder.getFunction().addTryCatchEntry(
                            tryStartBlock.getId(), mergeBlock.getId(),
                            catchBlock.getId(), extraExType, exLocal);
                }
            }
        }

        // catch 区域结束标记块（供 finally catch-all 覆盖 catch 体内的隐式异常）
        BasicBlock catchRegionEnd = null;
        if (node.hasFinally() && firstCatchBlock != null) {
            catchRegionEnd = builder.newBlock();
        }

        // 出栈 finally 上下文
        if (node.hasFinally()) {
            finallyStack.pop();
        }

        // 正常路径：mergeBlock 中执行 finally
        builder.switchToBlock(mergeBlock);
        if (node.hasFinally()) {
            blockScopeDepth++;
            lowerNode(node.getFinallyBlock(), builder);
            blockScopeDepth--;
        }

        // 异常路径 catch-all：未被具体 catch 子句捕获的异常也须执行 finally
        if (node.hasFinally()) {
            // 正常路径需跳过异常处理块到 continuation
            BasicBlock afterFinally = builder.newBlock();
            if (!builder.getCurrentBlock().hasTerminator()) {
                builder.emitGoto(afterFinally.getId(), loc);
            }

            BasicBlock finallyHandler = builder.newBlock();
            builder.switchToBlock(finallyHandler);
            int excLocal = builder.newLocal("$finallyExc",
                    MirType.ofObject("java/lang/Throwable"));
            blockScopeDepth++;
            lowerNode(node.getFinallyBlock(), builder);
            blockScopeDepth--;
            if (!builder.getCurrentBlock().hasTerminator()) {
                builder.emitThrow(excLocal, loc);
            }
            builder.getFunction().addTryCatchEntry(
                    tryStartBlock.getId(), mergeBlock.getId(),
                    finallyHandler.getId(), null, excLocal);

            // catch 体内抛出的异常也须执行 finally（catch 块在 mergeBlock 之后发射）
            if (catchRegionEnd != null) {
                builder.getFunction().addTryCatchEntry(
                        firstCatchBlock.getId(), catchRegionEnd.getId(),
                        finallyHandler.getId(), null, excLocal);
            }

            // 切回 continuation 块，后续代码在此发射
            builder.switchToBlock(afterFinally);
        }

        return resultLocal;
    }

    private int lowerReturn(ReturnStmt node, MirBuilder builder) {
        if (node.hasValue()) {
            int value = lowerExpr(node.getValue(), builder);
            // 在 return 前执行所有 finally 块（从内到外）
            for (AstNode finallyBlock : finallyStack) {
                lowerNode(finallyBlock, builder);
            }
            builder.emitReturn(value, node.getLocation());
        } else {
            for (AstNode finallyBlock : finallyStack) {
                lowerNode(finallyBlock, builder);
            }
            builder.emitReturnVoid(node.getLocation());
        }
        return -1;
    }

    private int lowerThrow(ThrowStmt node, MirBuilder builder) {
        int exc = lowerExpr(node.getException(), builder);
        // 不在此处内联 finally 块 — 依赖 TryCatchEntry 异常处理表来执行 finally
        // （lowerReturn 保留 finally 内联，因为 return 不触发异常处理表）
        // JVM athrow 要求 Throwable；Nova 允许 throw 任意值，包装为 RuntimeException
        SourceLocation loc = node.getLocation();
        int msg = builder.emitInvokeVirtualDesc(exc, "toString", new int[]{},
                "java/lang/Object", "()Ljava/lang/String;",
                MirType.ofObject("java/lang/String"), loc);
        int wrapped = builder.emitNewObject("java/lang/RuntimeException",
                new int[]{msg}, loc);
        builder.emitThrow(wrapped, loc);
        return -1;
    }

    private int lowerBreakContinue(boolean isBreak, String label, SourceLocation location, MirBuilder builder) {
        LoopContext target = null;
        if (label != null) {
            // 标签跳转：查找匹配 label 的循环
            for (LoopContext ctx : loopStack) {
                if (label.equals(ctx.label)) {
                    target = ctx;
                    break;
                }
            }
        } else if (!loopStack.isEmpty()) {
            // 无标签：最内层循环
            target = loopStack.peek();
        }
        if (target == null) {
            // lambda 内部的 break/continue：生成 throw LoopSignal（non-local jump）
            String signalField = isBreak ? "BREAK" : "CONTINUE";
            int signal = builder.emitGetStatic("com/novalang/runtime/LoopSignal", signalField,
                    "Lcom/novalang/runtime/LoopSignal;",
                    MirType.ofObject("com/novalang/runtime/LoopSignal"), location);
            builder.emitThrow(signal, location);
            return -1;
        }
        int blockId = isBreak ? target.exitBlockId : target.headerBlockId;
        builder.emitGoto(blockId, location);
        return -1;
    }

    /**
     * 错误传播操作符 `?`：
     * result? → if (result.isErr()) return result; else result.getValue()
     */
    private int lowerErrorPropagation(ErrorPropagationExpr expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        int operand = lowerExpr(expr.getOperand(), builder);

        // 预分配结果 local
        int resultLocal = builder.emitConstNull(loc);

        // 1. null 检查: operand == null → return null
        int nullConst = builder.emitConstNull(loc);
        int isNull = builder.emitBinary(BinaryOp.EQ, operand, nullConst,
                MirType.ofBoolean(), loc);
        BasicBlock nullReturnBlock = builder.newBlock();
        BasicBlock notNullBlock = builder.newBlock();
        builder.emitBranch(isNull, nullReturnBlock.getId(), notNullBlock.getId(), loc);

        builder.switchToBlock(nullReturnBlock);
        builder.emitReturn(nullConst, loc);

        // 2. Result 检查: operand is NovaResult
        builder.switchToBlock(notNullBlock);
        int isResult = builder.emitTypeCheck(operand, "com/novalang/runtime/NovaResult", loc);
        BasicBlock resultBlock = builder.newBlock();
        BasicBlock passBlock = builder.newBlock();
        builder.emitBranch(isResult, resultBlock.getId(), passBlock.getId(), loc);

        // Result 分支: isErr → return Err, else getValue()
        builder.switchToBlock(resultBlock);
        int isErr = builder.emitInvokeVirtualDesc(operand, "isErr", new int[0],
                "com/novalang/runtime/NovaResult", "()Z",
                MirType.ofBoolean(), loc);
        BasicBlock errBlock = builder.newBlock();
        BasicBlock okBlock = builder.newBlock();
        builder.emitBranch(isErr, errBlock.getId(), okBlock.getId(), loc);

        builder.switchToBlock(errBlock);
        builder.emitReturn(operand, loc);

        builder.switchToBlock(okBlock);
        int value = builder.emitInvokeVirtualDesc(operand, "getValue", new int[0],
                "com/novalang/runtime/NovaResult", "()Ljava/lang/Object;",
                MirType.ofObject("java/lang/Object"), loc);
        builder.emitMoveTo(value, resultLocal, loc);
        BasicBlock mergeBlock = builder.newBlock();
        builder.emitGoto(mergeBlock.getId(), loc);

        // 非 Result 分支: 直接使用 operand
        builder.switchToBlock(passBlock);
        builder.emitMoveTo(operand, resultLocal, loc);
        builder.emitGoto(mergeBlock.getId(), loc);

        builder.switchToBlock(mergeBlock);

        return resultLocal;
    }

    // ========== 表达式降级 ==========

    private int lowerExpr(Expression expr, MirBuilder builder) {
        if (expr == null) return -1;
        SourceLocation loc = expr.getLocation();

        if (expr instanceof Literal) return lowerLiteral((Literal) expr, builder);
        if (expr instanceof Identifier) return lowerVarRef((Identifier) expr, builder);
        if (expr instanceof BinaryExpr) return lowerBinary((BinaryExpr) expr, builder);
        if (expr instanceof UnaryExpr) return lowerUnary((UnaryExpr) expr, builder);
        if (expr instanceof HirCall) return lowerCall((HirCall) expr, builder);
        if (expr instanceof MemberExpr) return lowerFieldAccess((MemberExpr) expr, builder);
        if (expr instanceof IndexExpr) return lowerIndex((IndexExpr) expr, builder);
        if (expr instanceof AssignExpr) return lowerAssign((AssignExpr) expr, builder);
        if (expr instanceof TypeCheckExpr) return lowerTypeCheck((TypeCheckExpr) expr, builder);
        if (expr instanceof TypeCastExpr) return lowerTypeCast((TypeCastExpr) expr, builder);
        if (expr instanceof HirNew) return lowerNew((HirNew) expr, builder);
        if (expr instanceof ThisExpr) {
            // 查找 "this" 或 "$this"（扩展函数中接收者命名为 $this）
            for (MirLocal local : builder.getFunction().getLocals()) {
                String n = local.getName();
                if ("this".equals(n) || "$this".equals(n)) {
                    return local.getIndex();
                }
            }
            return 0; // 回退
        }
        if (expr instanceof NotNullExpr) {
            int operand = lowerExpr(((NotNullExpr) expr).getOperand(), builder);
            // null 时抛出 NullPointerException
            int nullConst = builder.emitConstNull(expr.getLocation());
            int isNull = builder.emitBinary(BinaryOp.EQ, operand, nullConst,
                    MirType.ofBoolean(), expr.getLocation());
            BasicBlock throwBlock = builder.newBlock();
            BasicBlock passBlock = builder.newBlock();
            builder.emitBranch(isNull, throwBlock.getId(), passBlock.getId(), expr.getLocation());
            builder.switchToBlock(throwBlock);
            int msgConst = builder.emitConstString("Value is null but non-null was asserted", expr.getLocation());
            int npe = builder.emitNewObject("java/lang/NullPointerException", new int[]{msgConst}, expr.getLocation());
            builder.emitThrow(npe, expr.getLocation());
            builder.switchToBlock(passBlock);
            return operand;
        }
        if (expr instanceof AwaitExpr) {
            int future = lowerExpr(((AwaitExpr) expr).getOperand(), builder);
            // await → ConcurrencyHelper.awaitJoin(target)
            // 统一处理 CompletableFuture / CompileJob / NovaJob 等
            String extra = "com/novalang/runtime/stdlib/ConcurrencyHelper|awaitJoin|(Ljava/lang/Object;)Ljava/lang/Object;";
            return builder.emitInvokeStatic(extra, new int[]{future},
                    MirType.ofObject("java/lang/Object"), expr.getLocation());
        }
        if (expr instanceof ConditionalExpr) {
            return lowerConditional((ConditionalExpr) expr, builder);
        }
        if (expr instanceof BlockExpr) {
            BlockExpr blockExpr = (BlockExpr) expr;
            for (Statement stmt : blockExpr.getStatements()) {
                lowerNode(stmt, builder);
            }
            return lowerExpr(blockExpr.getResult(), builder);
        }
        if (expr instanceof RangeExpr) {
            RangeExpr range = (RangeExpr) expr;
            int startVal = lowerExpr(range.getStart(), builder);
            int endVal = lowerExpr(range.getEnd(), builder);
            int inclusive = builder.emitConstBool(!range.isEndExclusive(), loc);
            String extra = "com/novalang/runtime/NovaCollections|createRange|(IIZ)Ljava/util/List;";
            return builder.emitInvokeStatic(extra, new int[]{startVal, endVal, inclusive},
                    MirType.ofObject("java/util/ArrayList"), loc);
        }
        if (expr instanceof HirCollectionLiteral) {
            return lowerCollectionLiteral((HirCollectionLiteral) expr, builder);
        }
        if (expr instanceof HirObjectLiteral) {
            return lowerObjectLiteral((HirObjectLiteral) expr, builder);
        }
        if (expr instanceof HirLambda) {
            return lowerLambda((HirLambda) expr, builder);
        }
        if (expr instanceof ErrorPropagationExpr) {
            return lowerErrorPropagation((ErrorPropagationExpr) expr, builder);
        }
        if (expr instanceof MethodRefExpr) {
            return lowerMethodRef((MethodRefExpr) expr, builder);
        }

        // 其余表达式默认返回 null
        return builder.emitConstNull(loc);
    }

    private int lowerObjectLiteral(HirObjectLiteral expr, MirBuilder builder) {
        String anonName = currentEnclosingClassName + "$" + (++anonymousClassCounter);

        // 解析 superClass 和 interfaces
        String superClass = "java/lang/Object";
        List<String> interfaces = new ArrayList<>();
        for (HirType st : expr.getSuperTypes()) {
            String resolved = typeToInternalName(st);
            Class<?> javaClass = resolveJavaClass(resolved);
            if (javaClass != null && javaClass.isInterface()) {
                interfaces.add(resolved);
            } else if (interfaceNames.contains(resolved)) {
                interfaces.add(resolved);
            } else {
                superClass = resolved;
            }
        }

        // 收集字段
        List<MirField> fields = new ArrayList<>();
        List<HirField> hirFields = new ArrayList<>();
        Set<String> fieldSet = new HashSet<>();
        for (HirDecl member : expr.getMembers()) {
            if (member instanceof HirField) {
                HirField hf = (HirField) member;
                fields.add(new MirField(hf.getName(), hirTypeToMir(hf.getType()), hf.getModifiers()));
                hirFields.add(hf);
                fieldSet.add(hf.getName());
            }
        }
        // 始终注册（即使为空），使 lowerVarRef 的 interpreterMode 保护逻辑能正确区分字段和外部变量
        classFieldNames.put(anonName, fieldSet);

        // 降级成员方法（在 classFieldNames 注册之后，使方法体中的字段引用正确解析为 this.field）
        List<MirFunction> methods = new ArrayList<>();
        for (HirDecl member : expr.getMembers()) {
            if (member instanceof HirFunction) {
                methods.add(lowerFunction((HirFunction) member, anonName));
            }
        }

        // 生成构造器以初始化字段默认值
        if (!hirFields.isEmpty()) {
            MirFunction ctor = new MirFunction("<init>", MirType.ofVoid(),
                    Collections.emptyList(), EnumSet.of(Modifier.PUBLIC));
            MirBuilder ctorBuilder = new MirBuilder(ctor);
            ctorBuilder.newLocal("this", MirType.ofObject(anonName));
            for (HirField hf : hirFields) {
                if (hf.getInitializer() != null) {
                    int initVal = lowerExpr(hf.getInitializer(), ctorBuilder);
                    ctorBuilder.emitSetField(0, hf.getName(), initVal, hf.getLocation());
                }
            }
            ctorBuilder.emitReturnVoid(expr.getLocation());
            methods.add(ctor);
        }

        // 解析超类构造器描述符
        List<Expression> ctorArgs = expr.getSuperConstructorArgs();
        String superCtorDesc = null;
        if (!ctorArgs.isEmpty()) {
            Class<?> superJavaClass = resolveJavaClass(superClass);
            superCtorDesc = resolveConstructorDesc(superJavaClass, ctorArgs);
        }

        // 创建匿名 MirClass
        MirClass anonClass = new MirClass(anonName, ClassKind.CLASS,
                EnumSet.of(Modifier.PUBLIC, Modifier.FINAL),
                superClass, interfaces,
                fields, methods);
        if (superCtorDesc != null) {
            anonClass.setSuperCtorDesc(superCtorDesc);
        }
        additionalClasses.add(anonClass);

        // 降级构造参数并发射 NEW_OBJECT
        int[] argLocals = new int[ctorArgs.size()];
        for (int i = 0; i < ctorArgs.size(); i++) {
            argLocals[i] = lowerExpr(ctorArgs.get(i), builder);
        }
        return builder.emitNewObject(anonName, argLocals, expr.getLocation());
    }

    /**
     * Lambda 表达式 → 匿名类。
     * 生成包含 invoke 方法和捕获变量字段的匿名类。
     */
    private int lowerLambda(HirLambda expr, MirBuilder builder) {
        String lambdaName = currentEnclosingClassName + "$Lambda$" + (++anonymousClassCounter);
        SourceLocation loc = expr.getLocation();

        // 隐式 it 参数检测: 无显式参数但 body 引用了 "it" → 添加隐式参数
        boolean isImplicitIt = false;
        List<HirParam> effectiveParams = expr.getParams();
        if (effectiveParams.isEmpty()) {
            Set<String> bodyRefs = new LinkedHashSet<>();
            collectVarRefs(expr.getBody(), bodyRefs);
            if (bodyRefs.contains("it")) {
                effectiveParams = Collections.singletonList(
                        new HirParam(loc, "it", null, null, false));
                isImplicitIt = true;
            }
        }

        // 捕获分析: 收集 lambda body 中引用但不在参数中的外部变量
        Set<String> paramNames = new HashSet<>();
        for (HirParam p : effectiveParams) paramNames.add(p.getName());
        Set<String> refNames = new LinkedHashSet<>();
        deepVarRefCollection = true;  // 深度收集：包括嵌套 lambda body 中的引用（传递性捕获）
        collectVarRefs(expr.getBody(), refNames);
        deepVarRefCollection = false;
        // 过滤出真正的捕获变量
        List<String> captures = new ArrayList<>();
        for (String name : refNames) {
            if (paramNames.contains(name)) continue;
            if ("this".equals(name)) continue;
            if (topLevelFunctionNames.contains(name)) continue;
            if (topLevelFieldNames.containsKey(name)) continue; // 顶层变量通过 GETSTATIC 访问，无需捕获
            if (classNames.contains(name)) continue;
            // 检查是否存在于外部作用域的局部变量
            boolean existsOuter = false;
            for (MirLocal local : builder.getFunction().getLocals()) {
                if (local.getName().equals(name)) { existsOuter = true; break; }
            }
            // 检查外层 lambda 的捕获变量（嵌套闭包：外层 lambda 捕获的变量存为 this.field）
            if (!existsOuter) {
                for (Set<String> enclosingCaptures : lambdaCaptureStack) {
                    if (enclosingCaptures.contains(name)) { existsOuter = true; break; }
                }
            }
            if (existsOuter) captures.add(name);
        }

        // 可变捕获检测: 在 lambda body 中被赋值的捕获变量需要 Object[1] boxing
        Set<String> assignedInBody = new HashSet<>();
        collectAssignedVarNames(expr.getBody(), assignedInBody);
        for (String capName : captures) {
            if (!assignedInBody.contains(capName)) continue;
            if (boxedMutableCaptures.containsKey(capName)) continue;
            // 找到外部局部变量
            int origLocal = -1;
            for (MirLocal local : builder.getFunction().getLocals()) {
                if (local.getName().equals(capName)) {
                    origLocal = local.getIndex();
                    break;
                }
            }
            if (origLocal < 0) continue;
            // 创建 Object[1] 数组并存入当前值
            int sizeConst = builder.emitConstInt(1, loc);
            int boxArray = builder.emitNewArray(sizeConst, loc);
            int zeroConst = builder.emitConstInt(0, loc);
            builder.emitIndexSet(boxArray, zeroConst, origLocal, loc);
            // 创建命名的 box 局部变量
            int boxLocal = builder.newLocal(capName + "$box", MirType.ofObject("[Ljava/lang/Object;"));
            builder.emitMoveTo(boxArray, boxLocal, loc);
            boxedMutableCaptures.put(capName, boxLocal);
        }

        // 1. 创建捕获变量字段
        List<MirField> fields = new ArrayList<>();
        for (String cap : captures) {
            fields.add(new MirField(cap, MirType.ofObject("java/lang/Object"),
                    EnumSet.of(Modifier.PUBLIC)));
        }

        // 2. 创建构造器（将捕获变量存储到字段）
        // 注意: generateMethod 会自动插入 super() 调用
        List<MirParam> ctorParams = new ArrayList<>();
        for (String cap : captures) {
            ctorParams.add(new MirParam(cap, MirType.ofObject("java/lang/Object")));
        }
        MirFunction ctorFunc = new MirFunction("<init>", MirType.ofVoid(),
                ctorParams, EnumSet.of(Modifier.PUBLIC));
        MirBuilder ctorBuilder = new MirBuilder(ctorFunc);
        ctorBuilder.newLocal("this", MirType.ofObject(lambdaName));
        for (MirParam p : ctorParams) {
            ctorBuilder.newLocal(p.getName(), p.getType());
        }
        // this.field = param（super() 由 MirCodeGenerator 自动生成）
        for (int i = 0; i < captures.size(); i++) {
            ctorBuilder.emitSetField(0, captures.get(i), i + 1, loc);
        }
        ctorBuilder.emitReturnVoid(loc);

        // 3. 创建 invoke 方法
        List<MirParam> invokeParams = new ArrayList<>();
        for (HirParam p : effectiveParams) {
            invokeParams.add(new MirParam(p.getName(), MirType.ofObject("java/lang/Object")));
        }
        MirFunction invokeFunc = new MirFunction("invoke",
                MirType.ofObject("java/lang/Object"),
                invokeParams, EnumSet.of(Modifier.PUBLIC));
        MirBuilder invokeBuilder = new MirBuilder(invokeFunc);
        invokeBuilder.newLocal("this", MirType.ofObject(lambdaName));
        for (MirParam p : invokeParams) {
            invokeBuilder.newLocal(p.getName(), p.getType());
        }

        // 降级 lambda body（push 当前 lambda 的捕获变量，供嵌套 lambda 的捕获分析使用）
        // 隔离 loopStack：lambda 内的 break/continue 不应匹配外层循环（生成 throw LoopSignal）
        lambdaCaptureStack.push(new HashSet<>(captures));
        Deque<LoopContext> savedLoopStack = new ArrayDeque<>(loopStack);
        loopStack.clear();
        int savedScopeDepth = blockScopeDepth;
        blockScopeDepth = 0;
        AstNode body = expr.getBody();
        int result = lowerNode(body, invokeBuilder);
        blockScopeDepth = savedScopeDepth;
        loopStack.clear();
        loopStack.addAll(savedLoopStack);
        lambdaCaptureStack.pop();
        if (!invokeBuilder.getCurrentBlock().hasTerminator()) {
            if (result >= 0) {
                invokeBuilder.emitReturn(result, loc);
            } else {
                // invoke 返回 Object，空 body 时返回 null（不能用 void return）
                int nullVal = invokeBuilder.emitConstNull(loc);
                invokeBuilder.emitReturn(nullVal, loc);
            }
        }

        // 4. 创建 MirClass（实现 FunctionN 接口以避免反射调用）
        List<String> lambdaInterfaces;
        switch (invokeParams.size()) {
            case 0:  lambdaInterfaces = Collections.singletonList("com/novalang/runtime/Function0"); break;
            case 1:  lambdaInterfaces = Collections.singletonList(
                         isImplicitIt ? "com/novalang/runtime/ImplicitItFunction" : "com/novalang/runtime/Function1"); break;
            case 2:  lambdaInterfaces = Collections.singletonList("com/novalang/runtime/Function2"); break;
            case 3:  lambdaInterfaces = Collections.singletonList("com/novalang/runtime/Function3"); break;
            default: lambdaInterfaces = Collections.emptyList(); break;
        }
        List<MirFunction> methods = new ArrayList<>();
        methods.add(ctorFunc);
        methods.add(invokeFunc);
        MirClass lambdaClass = new MirClass(lambdaName, ClassKind.CLASS,
                EnumSet.of(Modifier.PUBLIC, Modifier.FINAL),
                "java/lang/Object", lambdaInterfaces,
                fields, methods);
        additionalClasses.add(lambdaClass);

        // 5. 加载捕获变量值并实例化
        int[] captureLocals = new int[captures.size()];
        for (int i = 0; i < captures.size(); i++) {
            String capName = captures.get(i);
            // 装箱的可变捕获：使用 Object[] box 引用
            if (boxedMutableCaptures.containsKey(capName)) {
                int bLocal = boxedMutableCaptures.get(capName);
                List<MirLocal> curLocals = builder.getFunction().getLocals();
                // boxLocal 有效（在当前 builder 中）→ 直接传递 box 引用
                if (bLocal < curLocals.size()
                        && curLocals.get(bLocal).getName().equals(capName + "$box")) {
                    captureLocals[i] = bLocal;
                    continue;
                }
                // 嵌套 lambda：boxLocal 属于外层 builder，从 this.field 获取 box 引用
                MirLocal thisLocal = findThisLocal(builder);
                if (thisLocal != null) {
                    captureLocals[i] = builder.emitGetField(thisLocal.getIndex(), capName,
                            MirType.ofObject("java/lang/Object"), loc);
                    continue;
                }
            }
            // 在外部函数中查找捕获变量的局部变量
            int capLocal = -1;
            for (MirLocal local : builder.getFunction().getLocals()) {
                if (local.getName().equals(capName)) {
                    capLocal = local.getIndex();
                    break;
                }
            }
            if (capLocal < 0) {
                // 可能是外层 this 的字段
                MirLocal thisLocal = findThisLocal(builder);
                if (thisLocal != null) {
                    capLocal = builder.emitGetField(thisLocal.getIndex(), capName,
                            MirType.ofObject("java/lang/Object"), loc);
                } else {
                    capLocal = builder.emitConstNull(loc);
                }
            }
            captureLocals[i] = capLocal;
        }
        return builder.emitNewObject(lambdaName, captureLocals, loc);
    }

    /**
     * 方法引用 ::funcName / obj::method / Type::new → FunctionN 包装类
     */
    private int lowerMethodRef(MethodRefExpr expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();

        // T::class — reified 类型参数的类名引用 → 返回 __reified_T 局部变量
        if ("class".equals(expr.getMethodName()) && expr.hasTarget()
                && expr.getTarget() instanceof Identifier) {
            String varName = ((Identifier) expr.getTarget()).getName();
            if (currentFunctionTypeParams.contains(varName)) {
                String localName = "__reified_" + varName;
                for (MirLocal local : builder.getFunction().getLocals()) {
                    if (localName.equals(local.getName())) {
                        return local.getIndex();
                    }
                }
            }
        }

        String refName = currentEnclosingClassName + "$MethodRef$" + (++anonymousClassCounter);
        String methodName = expr.getMethodName();

        if (!expr.hasTarget() && !expr.isConstructor()) {
            // 局部函数引用 ::localFunc → 直接返回局部变量（嵌套函数已编译为 lambda）
            for (int i = builder.getFunction().getLocals().size() - 1; i >= 0; i--) {
                MirLocal local = builder.getFunction().getLocals().get(i);
                if (methodName.equals(local.getName())) {
                    return local.getIndex();
                }
            }

            // 全局函数引用 ::funcName → 生成 FunctionN 包装类，invoke 转发到 $Module.funcName
            String desc = topLevelFuncDescs.get(methodName);

            // 跨 evalRepl（desc 为 null）：直接从环境获取函数引用
            if (desc == null) {
                int nameConst = builder.emitConstString(methodName, loc);
                return builder.emitInvokeStatic(
                        scriptContextOwner() + "|get|(Ljava/lang/String;)Ljava/lang/Object;",
                        new int[]{nameConst}, MirType.ofObject("java/lang/Object"), loc);
            }

            int paramCount = desc != null ? countDescParams(desc) : 1;

            // 创建 invoke 方法：接收 paramCount 个 Object 参数，转发到目标函数
            List<MirParam> invokeParams = new ArrayList<>();
            for (int i = 0; i < paramCount; i++) {
                invokeParams.add(new MirParam("p" + i, MirType.ofObject("java/lang/Object")));
            }
            MirFunction invokeFunc = new MirFunction("invoke",
                    MirType.ofObject("java/lang/Object"), invokeParams, EnumSet.of(Modifier.PUBLIC));
            MirBuilder invokeBuilder = new MirBuilder(invokeFunc);
            invokeBuilder.newLocal("this", MirType.ofObject(refName));
            int[] argLocals = new int[paramCount];
            for (int i = 0; i < paramCount; i++) {
                argLocals[i] = invokeBuilder.newLocal("p" + i, MirType.ofObject("java/lang/Object"));
            }
            // INVOKESTATIC $Module.funcName(args)
            String callDesc = desc != null ? desc : MethodDescriptor.allObjectDesc(paramCount);
            String extra = moduleClassName + "|" + methodName + "|" + callDesc;
            String retDescStr = callDesc.substring(callDesc.indexOf(')') + 1);
            MirType retType = descriptorToMirType(retDescStr);
            int result = invokeBuilder.emitInvokeStatic(extra, argLocals, retType, loc);
            invokeBuilder.emitReturn(result, loc);

            // 选择 FunctionN 接口
            List<String> ifaces;
            switch (paramCount) {
                case 0:  ifaces = Collections.singletonList("com/novalang/runtime/Function0"); break;
                case 1:  ifaces = Collections.singletonList("com/novalang/runtime/Function1"); break;
                case 2:  ifaces = Collections.singletonList("com/novalang/runtime/Function2"); break;
                case 3:  ifaces = Collections.singletonList("com/novalang/runtime/Function3"); break;
                default: ifaces = Collections.emptyList(); break;
            }

            // 生成无参构造器
            MirFunction ctorFunc = new MirFunction("<init>", MirType.ofVoid(),
                    Collections.emptyList(), EnumSet.of(Modifier.PUBLIC));
            MirBuilder ctorBuilder = new MirBuilder(ctorFunc);
            ctorBuilder.newLocal("this", MirType.ofObject(refName));
            ctorBuilder.emitReturnVoid(loc);

            List<MirFunction> methods = new ArrayList<>();
            methods.add(ctorFunc);
            methods.add(invokeFunc);
            MirClass refClass = new MirClass(refName, ClassKind.CLASS,
                    EnumSet.of(Modifier.PUBLIC, Modifier.FINAL),
                    "java/lang/Object", ifaces,
                    Collections.emptyList(), methods);
            additionalClasses.add(refClass);
            return builder.emitNewObject(refName, new int[0], loc);
        }

        if (expr.isConstructor()) {
            // Type::new → 包装构造器调用
            int targetLocal = lowerExpr(expr.getTarget(), builder);
            String typeName = null;
            if (expr.getTarget() instanceof Identifier) {
                typeName = ((Identifier) expr.getTarget()).getName();
            }
            if (typeName == null) {
                return builder.emitConstNull(loc);
            }

            // 从构造器声明推断参数数量
            HirFunction ctorDecl = classConstructorDecls.get(typeName);
            if (ctorDecl == null) {
                // 跨 evalRepl: 类在当前模块中不可见 → 直接从环境加载 NovaClass
                // NovaClass 本身实现了 NovaCallable，可直接调用构造器
                int nameConst = builder.emitConstString(typeName, loc);
                return builder.emitInvokeStatic(
                        scriptContextOwner() + "|get|(Ljava/lang/String;)Ljava/lang/Object;",
                        new int[]{nameConst}, MirType.ofObject("java/lang/Object"), loc);
            }
            int paramCount = ctorDecl.getParams().size();

            List<MirParam> invokeParams = new ArrayList<>();
            for (int i = 0; i < paramCount; i++) {
                invokeParams.add(new MirParam("p" + i, MirType.ofObject("java/lang/Object")));
            }
            MirFunction invokeFunc = new MirFunction("invoke",
                    MirType.ofObject("java/lang/Object"), invokeParams, EnumSet.of(Modifier.PUBLIC));
            MirBuilder invokeBuilder = new MirBuilder(invokeFunc);
            invokeBuilder.newLocal("this", MirType.ofObject(refName));
            int[] argLocals = new int[paramCount];
            for (int i = 0; i < paramCount; i++) {
                argLocals[i] = invokeBuilder.newLocal("p" + i, MirType.ofObject("java/lang/Object"));
            }
            int created = invokeBuilder.emitNewObject(typeName, argLocals, loc);
            invokeBuilder.emitReturn(created, loc);

            MirFunction ctorFunc = new MirFunction("<init>", MirType.ofVoid(),
                    Collections.emptyList(), EnumSet.of(Modifier.PUBLIC));
            MirBuilder ctorBuilder = new MirBuilder(ctorFunc);
            ctorBuilder.newLocal("this", MirType.ofObject(refName));
            ctorBuilder.emitReturnVoid(loc);

            // 选择 FunctionN 接口
            List<String> ifaces;
            switch (paramCount) {
                case 0:  ifaces = Collections.singletonList("com/novalang/runtime/Function0"); break;
                case 1:  ifaces = Collections.singletonList("com/novalang/runtime/Function1"); break;
                case 2:  ifaces = Collections.singletonList("com/novalang/runtime/Function2"); break;
                case 3:  ifaces = Collections.singletonList("com/novalang/runtime/Function3"); break;
                default: ifaces = Collections.emptyList(); break;
            }

            List<MirFunction> methods = new ArrayList<>();
            methods.add(ctorFunc);
            methods.add(invokeFunc);
            MirClass refClass = new MirClass(refName, ClassKind.CLASS,
                    EnumSet.of(Modifier.PUBLIC, Modifier.FINAL),
                    "java/lang/Object", ifaces,
                    Collections.emptyList(), methods);
            additionalClasses.add(refClass);
            return builder.emitNewObject(refName, new int[0], loc);
        }

        if (expr.hasTarget()) {
            // obj::method → 捕获 obj，invoke 中调用 obj.method(args)
            int targetObj = lowerExpr(expr.getTarget(), builder);

            // 默认参数数量 1（单参方法最常见）
            int paramCount = 1;
            // 尝试从目标类型的方法描述符获取真实参数数量
            MirType targetType = targetObj >= 0 && targetObj < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(targetObj).getType() : null;
            if (targetType != null && targetType.getClassName() != null) {
                Map<String, String> classDescs = novaMethodDescs.get(targetType.getClassName());
                if (classDescs != null && classDescs.containsKey(methodName)) {
                    paramCount = countDescParams(classDescs.get(methodName));
                }
            }

            // 捕获字段：存储目标对象
            List<MirField> fields = Collections.singletonList(
                    new MirField("target", MirType.ofObject("java/lang/Object"), EnumSet.of(Modifier.PUBLIC)));

            // 构造器：接收目标对象
            MirFunction ctorFunc = new MirFunction("<init>", MirType.ofVoid(),
                    Collections.singletonList(new MirParam("target", MirType.ofObject("java/lang/Object"))),
                    EnumSet.of(Modifier.PUBLIC));
            MirBuilder ctorBuilder = new MirBuilder(ctorFunc);
            ctorBuilder.newLocal("this", MirType.ofObject(refName));
            ctorBuilder.newLocal("target", MirType.ofObject("java/lang/Object"));
            ctorBuilder.emitSetField(0, "target", 1, loc);
            ctorBuilder.emitReturnVoid(loc);

            // invoke 方法
            List<MirParam> invokeParams = new ArrayList<>();
            for (int i = 0; i < paramCount; i++) {
                invokeParams.add(new MirParam("p" + i, MirType.ofObject("java/lang/Object")));
            }
            MirFunction invokeFunc = new MirFunction("invoke",
                    MirType.ofObject("java/lang/Object"), invokeParams, EnumSet.of(Modifier.PUBLIC));
            MirBuilder invokeBuilder = new MirBuilder(invokeFunc);
            invokeBuilder.newLocal("this", MirType.ofObject(refName));
            int[] argLocals = new int[paramCount];
            for (int i = 0; i < paramCount; i++) {
                argLocals[i] = invokeBuilder.newLocal("p" + i, MirType.ofObject("java/lang/Object"));
            }
            // 加载 this.target
            int target = invokeBuilder.emitGetField(0, "target",
                    MirType.ofObject("java/lang/Object"), loc);
            // 调用 target.method(args) via NovaDynamic
            int result = invokeBuilder.emitInvokeVirtualDesc(target, methodName, argLocals,
                    "java/lang/Object", MethodDescriptor.allObjectDesc(paramCount),
                    MirType.ofObject("java/lang/Object"), loc);
            invokeBuilder.emitReturn(result, loc);

            List<String> ifaces;
            switch (paramCount) {
                case 0:  ifaces = Collections.singletonList("com/novalang/runtime/Function0"); break;
                case 1:  ifaces = Collections.singletonList("com/novalang/runtime/Function1"); break;
                case 2:  ifaces = Collections.singletonList("com/novalang/runtime/Function2"); break;
                case 3:  ifaces = Collections.singletonList("com/novalang/runtime/Function3"); break;
                default: ifaces = Collections.emptyList(); break;
            }

            List<MirFunction> methods = new ArrayList<>();
            methods.add(ctorFunc);
            methods.add(invokeFunc);
            MirClass refClass = new MirClass(refName, ClassKind.CLASS,
                    EnumSet.of(Modifier.PUBLIC, Modifier.FINAL),
                    "java/lang/Object", ifaces,
                    fields, methods);
            additionalClasses.add(refClass);
            return builder.emitNewObject(refName, new int[]{targetObj}, loc);
        }

        return builder.emitConstNull(loc);
    }

    /** 从 JVM 描述符 "(Ljava/lang/Object;I)V" 计算参数个数 */
    private int countDescParams(String desc) {
        int count = 0;
        int i = 1; // skip '('
        while (i < desc.length() && desc.charAt(i) != ')') {
            if (desc.charAt(i) == 'L') {
                i = desc.indexOf(';', i) + 1;
            } else if (desc.charAt(i) == '[') {
                i++;
                continue;
            } else {
                i++;
            }
            count++;
        }
        return count;
    }

    private String resolveConstructorDesc(Class<?> javaClass, List<Expression> args) {
        if (javaClass == null || args.isEmpty()) return "()V";
        int argCount = args.size();

        // 收集参数数量匹配的构造器，按类型匹配评分排序
        java.lang.reflect.Constructor<?> best = null;
        int bestScore = -1;
        for (java.lang.reflect.Constructor<?> ctor : javaClass.getConstructors()) {
            if (ctor.getParameterCount() != argCount) continue;
            int score = scoreCtorMatch(ctor.getParameterTypes(), args);
            if (score > bestScore) {
                bestScore = score;
                best = ctor;
            }
        }
        if (best == null) return "()V";
        return buildCtorDesc(best.getParameterTypes());
    }

    private int scoreCtorMatch(Class<?>[] paramTypes, List<Expression> args) {
        int score = 0;
        for (int i = 0; i < args.size(); i++) {
            Expression arg = args.get(i);
            Class<?> pt = paramTypes[i];
            if (arg instanceof Literal) {
                LiteralKind kind = ((Literal) arg).getKind();
                if (kind == LiteralKind.STRING && pt == String.class) score += 10;
                else if (kind == LiteralKind.INT && (pt == int.class || pt == Integer.class)) score += 10;
                else if (kind == LiteralKind.LONG && (pt == long.class || pt == Long.class)) score += 10;
                else if (kind == LiteralKind.DOUBLE && (pt == double.class || pt == Double.class)) score += 10;
                else if (kind == LiteralKind.FLOAT && (pt == float.class || pt == Float.class)) score += 10;
                else if (kind == LiteralKind.BOOLEAN && (pt == boolean.class || pt == Boolean.class)) score += 10;
            }
            if (pt == Object.class) score += 1;
        }
        return score;
    }

    private String buildCtorDesc(Class<?>[] paramTypes) {
        StringBuilder desc = new StringBuilder("(");
        for (Class<?> pt : paramTypes) {
            if (pt == boolean.class) desc.append("Z");
            else if (pt == byte.class) desc.append("B");
            else if (pt == char.class) desc.append("C");
            else if (pt == short.class) desc.append("S");
            else if (pt == int.class) desc.append("I");
            else if (pt == long.class) desc.append("J");
            else if (pt == float.class) desc.append("F");
            else if (pt == double.class) desc.append("D");
            else desc.append("L").append(pt.getName().replace('.', '/')).append(";");
        }
        desc.append(")V");
        return desc.toString();
    }

    private int lowerLiteral(Literal lit, MirBuilder builder) {
        SourceLocation loc = lit.getLocation();
        switch (lit.getKind()) {
            case INT: return builder.emitConstInt((Integer) lit.getValue(), loc);
            case LONG: return builder.emitConstLong((Long) lit.getValue(), loc);
            case FLOAT: return builder.emitConstFloat((Float) lit.getValue(), loc);
            case DOUBLE: return builder.emitConstDouble((Double) lit.getValue(), loc);
            case STRING: return builder.emitConstString((String) lit.getValue(), loc);
            case BOOLEAN: return builder.emitConstBool((Boolean) lit.getValue(), loc);
            case CHAR: return builder.emitConstChar((Character) lit.getValue(), loc);
            case NULL: return builder.emitConstNull(loc);
            default: return builder.emitConstNull(loc);
        }
    }

    /** 脚本模式: 将寄存器值写回 NovaScriptContext */
    private void emitScriptContextSet(MirBuilder builder, String varName, int valueReg, SourceLocation loc) {
        int nameConst = builder.emitConstString(varName, loc);
        builder.emitInvokeStatic(
                scriptContextOwner() + "|set|(Ljava/lang/String;Ljava/lang/Object;)V",
                new int[]{nameConst, valueReg},
                MirType.ofVoid(), loc);
    }

    private int lowerVarRef(Identifier ref, MirBuilder builder) {
        // 装箱可变捕获：外部作用域直接通过 Object[] box 访问
        Integer boxLocal = boxedMutableCaptures.get(ref.getName());
        if (boxLocal != null) {
            List<MirLocal> locals = builder.getFunction().getLocals();
            if (boxLocal < locals.size()
                    && locals.get(boxLocal).getName().equals(ref.getName() + "$box")) {
                int zeroConst = builder.emitConstInt(0, ref.getLocation());
                return builder.emitIndexGet(boxLocal, zeroConst,
                        MirType.ofObject("java/lang/Object"), ref.getLocation());
            }
        }
        // 顶层 var → GETSTATIC（可能被其他函数/闭包修改，不能用局部变量缓存）
        // main() 内 blockScopeDepth > 0 时需检查同名块级局部变量（如 for 循环变量）避免冲突
        if (topLevelFieldNames.containsKey(ref.getName())
                && !Boolean.TRUE.equals(topLevelFieldNames.get(ref.getName()))
                && !boxedMutableCaptures.containsKey(ref.getName())) {
            // main() 内块作用域中，检查是否有更近的同名局部变量（如 for 循环变量）
            if ("main".equals(builder.getFunction().getName()) && blockScopeDepth > 0) {
                int count = 0;
                for (MirLocal l : builder.getFunction().getLocals()) {
                    if (ref.getName().equals(l.getName())) count++;
                }
                if (count == 0) {
                    return builder.emitGetStatic(moduleClassName, ref.getName(),
                            "Ljava/lang/Object;",
                            MirType.ofObject("java/lang/Object"), ref.getLocation());
                }
                // 有同名局部变量 → 走局部变量查找（让 for 循环变量等优先匹配）
            } else {
                return builder.emitGetStatic(moduleClassName, ref.getName(),
                        "Ljava/lang/Object;",
                        MirType.ofObject("java/lang/Object"), ref.getLocation());
            }
        }
        // 查找已存在的局部变量（逆序查找，优先匹配最近声明的同名变量）
        List<MirLocal> locals = builder.getFunction().getLocals();
        for (int i = locals.size() - 1; i >= 0; i--) {
            if (locals.get(i).getName().equals(ref.getName())) {
                int idx = locals.get(i).getIndex();
                // lazy 变量透明解包：内联 check-and-init
                int[] lazyInfo = lazyVarLocals.get(ref.getName());
                if (lazyInfo != null) {
                    int lambdaLocal = lazyInfo[0], cacheLocal = lazyInfo[1], doneLocal = lazyInfo[2];
                    SourceLocation loc = ref.getLocation();
                    // if (!done) { cache = lambda(); done = true }
                    BasicBlock initBlock = builder.newBlock();
                    BasicBlock mergeBlock = builder.newBlock();
                    builder.emitBranch(doneLocal, mergeBlock.getId(), initBlock.getId(), loc);
                    builder.switchToBlock(initBlock);
                    // 调用 lambda: invokedynamic bootstrapInvoke 分派 invoke()
                    InvokeDynamicInfo dynInfo = new InvokeDynamicInfo(
                            "invoke", "com/novalang/runtime/NovaBootstrap", "bootstrapInvoke",
                            "(Ljava/lang/Object;)Ljava/lang/Object;");
                    int result = builder.emitInvokeDynamic(dynInfo, new int[]{lambdaLocal},
                            MirType.ofObject("java/lang/Object"), loc);
                    builder.emitMoveTo(result, cacheLocal, loc);
                    builder.emitMoveTo(builder.emitConstBool(true, loc), doneLocal, loc);
                    builder.emitGoto(mergeBlock.getId(), loc);
                    builder.switchToBlock(mergeBlock);
                    return cacheLocal;
                }
                return idx;
            }
        }
        // 顶层 val → GETSTATIC（从顶层函数访问时，无同名局部变量）
        if (topLevelFieldNames.containsKey(ref.getName())
                && !boxedMutableCaptures.containsKey(ref.getName())) {
            return builder.emitGetStatic(moduleClassName, ref.getName(),
                    "Ljava/lang/Object;",
                    MirType.ofObject("java/lang/Object"), ref.getLocation());
        }
        // 接收者 Lambda 上下文：this 引用返回接收者对象
        if ("this".equals(ref.getName()) && currentReceiverLambda != null) {
            return receiverLocalIndex;
        }
        // StdlibRegistry 常量 → GETSTATIC（优先于 this.field，避免全局常量被误解析为字段）
        StdlibRegistry.ConstantInfo constInfo = StdlibRegistry.getConstant(ref.getName());
        if (constInfo != null) {
            return builder.emitGetStatic(constInfo.jvmOwner, constInfo.jvmFieldName,
                    constInfo.jvmDescriptor, descriptorToMirType(constInfo.jvmDescriptor),
                    ref.getLocation());
        }
        // 未找到：如果当前函数有 this（或扩展函数的 $this），尝试作为字段/成员访问
        for (MirLocal local : builder.getFunction().getLocals()) {
            if (local.getName().equals("this") || local.getName().equals("$this")) {
                // 自定义 getter → 调用 get$fieldName()
                MirType thisType = local.getType();
                if (thisType.getKind() == MirType.Kind.OBJECT && thisType.getClassName() != null
                        && customGetters.contains(thisType.getClassName() + ":" + ref.getName())) {
                    return builder.emitInvokeVirtualDesc(local.getIndex(), "get$" + ref.getName(),
                            new int[0], thisType.getClassName(), "()Ljava/lang/Object;",
                            MirType.ofObject("java/lang/Object"), ref.getLocation());
                }
                // 枚举内置属性: name/ordinal → 调用 this.name()/this.ordinal()（实际访问 $name/$ordinal 隐藏字段）
                if (thisType.getKind() == MirType.Kind.OBJECT && thisType.getClassName() != null
                        && enumClassNames.contains(thisType.getClassName())
                        && ("name".equals(ref.getName()) || "ordinal".equals(ref.getName()))) {
                    String desc = "()Ljava/lang/Object;";
                    return builder.emitInvokeVirtualDesc(local.getIndex(), ref.getName(), new int[0],
                            thisType.getClassName(), desc,
                            MirType.ofObject("java/lang/Object"), ref.getLocation());
                }
                // 对已知用户定义类，检查是否有该字段/方法，避免把全局变量当成 this.field
                // 跳过: Lambda 匿名类（捕获字段不在 classFieldNames）、$Module 容器类
                String thisClassName = thisType.getClassName();
                if (thisType.getKind() == MirType.Kind.OBJECT
                        && thisClassName != null
                        && !thisClassName.contains("$Lambda$")
                        && !thisClassName.equals(moduleClassName)
                        && classFieldNames.containsKey(thisClassName)) {
                    String className = thisType.getClassName();
                    // 沿继承链查找字段（子类 → 父类）
                    boolean hasField = false;
                    boolean chainComplete = true;
                    String cur = className;
                    while (cur != null) {
                        Set<String> fields = classFieldNames.get(cur);
                        if (fields != null && fields.contains(ref.getName())) {
                            hasField = true;
                            break;
                        }
                        String parent = classSuperClass.get(cur);
                        if (parent != null && !classFieldNames.containsKey(parent)) {
                            // 父类来自外部模块（跨 evalRepl），字段信息不完整
                            chainComplete = false;
                            break;
                        }
                        cur = parent;
                    }
                    boolean hasMethod = hasNovaMethod(className, ref.getName());
                    if (!hasField && !hasMethod && chainComplete) {
                        break; // 继续查找 StdlibRegistry 常量和环境变量
                    }
                    // chainComplete=false 时，可能是继承的字段 → fall through 到 GET_FIELD
                }
                // Lambda 类：非捕获变量通过 scopeReceiver 访问
                // MirInterpreter: GET_FIELD this.name → executeGetField 回退到 scopeReceiver
                // MirCodeGenerator: invokedynamic getMember（lambda 类没有该字段，需运行时分派）
                if (thisType.getKind() == MirType.Kind.OBJECT
                        && thisType.getClassName() != null
                        && thisType.getClassName().contains("$Lambda$")
                        && !lambdaCaptureStack.isEmpty()
                        && !lambdaCaptureStack.peek().contains(ref.getName())) {
                    // 生成 GET_FIELD（MirInterpreter 通过 scopeReceiver 重定向）
                    // MirCodeGenerator 对 Lambda 类的 GET_FIELD 不会生成 GETFIELD（见下方 break 条件）
                    // 改为直接生成 INVOKE_DYNAMIC bootstrapGetMember
                    InvokeDynamicInfo getInfo = new InvokeDynamicInfo(
                            ref.getName(), "com/novalang/runtime/NovaBootstrap", "bootstrapGetMember",
                            "(Ljava/lang/Object;)Ljava/lang/Object;");
                    return builder.emitInvokeDynamic(getInfo, new int[]{local.getIndex()},
                            MirType.ofObject("java/lang/Object"), ref.getLocation());
                }
                int fieldVal = builder.emitGetField(local.getIndex(), ref.getName(),
                        MirType.ofObject("java/lang/Object"), ref.getLocation());
                // 装箱可变捕获：lambda 内部通过 this.field 访问 Object[] → 解包 field[0]
                if (boxedMutableCaptures.containsKey(ref.getName())) {
                    int zeroConst = builder.emitConstInt(0, ref.getLocation());
                    return builder.emitIndexGet(fieldVal, zeroConst,
                            MirType.ofObject("java/lang/Object"), ref.getLocation());
                }
                return fieldVal;
            }
        }
        // 脚本模式: 未解析变量通过 NovaScriptContext.get(name) 读取 Bindings
        if (scriptMode) {
            int nameConst = builder.emitConstString(ref.getName(), ref.getLocation());
            return builder.emitInvokeStatic(
                    scriptContextOwner() + "|get|(Ljava/lang/String;)Ljava/lang/Object;",
                    new int[]{nameConst},
                    MirType.ofObject("java/lang/Object"), ref.getLocation());
        }
        // 兜底：返回 null（避免创建未初始化的局部变量）
        return builder.emitConstNull(ref.getLocation());
    }

    private int lowerBinary(BinaryExpr expr, MirBuilder builder) {
        // TO: "a" to 1 → NovaPair.of(left, right)
        if (expr.getOperator() == BinaryExpr.BinaryOp.TO) {
            int left = lowerExpr(expr.getLeft(), builder);
            int right = lowerExpr(expr.getRight(), builder);
            SourceLocation loc = expr.getLocation();
            return builder.emitInvokeStatic(
                    "com/novalang/runtime/NovaPair|of|(Ljava/lang/Object;Ljava/lang/Object;)Lcom/novalang/runtime/NovaPair;",
                    new int[]{left, right},
                    MirType.ofObject("com/novalang/runtime/NovaPair"), loc);
        }

        int left = lowerExpr(expr.getLeft(), builder);
        int right = lowerExpr(expr.getRight(), builder);

        // IN / NOT_IN: right.contains(left) → INVOKEINTERFACE Collection.contains
        if (expr.getOperator() == BinaryExpr.BinaryOp.IN || expr.getOperator() == BinaryExpr.BinaryOp.NOT_IN) {
            int result = builder.emitInvokeInterfaceDesc(right, "contains", new int[]{left},
                    "java/util/Collection", "(Ljava/lang/Object;)Z",
                    MirType.ofBoolean(), expr.getLocation());
            if (expr.getOperator() == BinaryExpr.BinaryOp.NOT_IN) {
                return builder.emitUnary(UnaryOp.NOT, result, MirType.ofBoolean(), expr.getLocation());
            }
            return result;
        }

        // 运算符重载: 左操作数为 Nova 类 → 调用对应方法 (ADD→plus 等)
        String opMethod = getOperatorMethodName(expr.getOperator());
        if (opMethod != null) {
            MirType leftType = left >= 0 && left < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(left).getType() : null;
            if (leftType != null && leftType.getKind() == MirType.Kind.OBJECT
                    && leftType.getClassName() != null
                    && classNames.contains(leftType.getClassName())) {
                String owner = leftType.getClassName();
                if (hasNovaMethod(owner, opMethod)) {
                    String desc = lookupNovaMethodDesc(owner, opMethod, 1);
                    MirType retType = inferReturnType(desc.substring(desc.indexOf(')') + 1), owner);
                    return builder.emitInvokeVirtualDesc(left, opMethod, new int[]{right},
                            owner, desc, retType, expr.getLocation());
                }
            }
        }

        // 比较运算符重载: LT/GT/LE/GE 在 Nova 类上 → 调用 compareTo() 再与 0 比较
        if (expr.getOperator() == BinaryExpr.BinaryOp.LT || expr.getOperator() == BinaryExpr.BinaryOp.GT
                || expr.getOperator() == BinaryExpr.BinaryOp.LE || expr.getOperator() == BinaryExpr.BinaryOp.GE) {
            MirType leftType = left >= 0 && left < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(left).getType() : null;
            if (leftType != null && leftType.getKind() == MirType.Kind.OBJECT
                    && leftType.getClassName() != null
                    && classNames.contains(leftType.getClassName())
                    && hasNovaMethod(leftType.getClassName(), "compareTo")) {
                String owner = leftType.getClassName();
                String desc = lookupNovaMethodDesc(owner, "compareTo", 1);
                int cmpResult = builder.emitInvokeVirtualDesc(left, "compareTo", new int[]{right},
                        owner, desc, MirType.ofInt(), expr.getLocation());
                int zero = builder.emitConstInt(0, expr.getLocation());
                BinaryOp cmpOp = mapBinaryOp(expr.getOperator());
                return builder.emitBinary(cmpOp, cmpResult, zero, MirType.ofBoolean(), expr.getLocation());
            }
        }

        BinaryOp op = mapBinaryOp(expr.getOperator());
        MirType resultType = hirTypeToMir(expr.getType());
        // 当结果类型为泛型 Object 且两个操作数均为 INT 时，推导结果为 INT
        if (resultType.getKind() == MirType.Kind.OBJECT
                && "java/lang/Object".equals(resultType.getClassName())) {
            List<MirLocal> locals = builder.getFunction().getLocals();
            MirType lt = left >= 0 && left < locals.size() ? locals.get(left).getType() : null;
            MirType rt = right >= 0 && right < locals.size() ? locals.get(right).getType() : null;
            if (lt != null && rt != null && lt.getKind() == MirType.Kind.INT
                    && rt.getKind() == MirType.Kind.INT) {
                resultType = MirType.ofInt();
            }
        }
        return builder.emitBinary(op, left, right, resultType, expr.getLocation());
    }

    private static String getOperatorMethodName(BinaryExpr.BinaryOp op) {
        switch (op) {
            case ADD: return "plus";
            case SUB: return "minus";
            case MUL: return "times";
            case DIV: return "div";
            case MOD: return "rem";
            default: return null;
        }
    }

    private int lowerUnary(UnaryExpr expr, MirBuilder builder) {
        int operand = lowerExpr(expr.getOperand(), builder);
        SourceLocation loc = expr.getLocation();

        // INC/DEC: x++ → x = x + 1（返回旧值/新值取决于 prefix/postfix）
        if (expr.getOperator() == UnaryExpr.UnaryOp.INC || expr.getOperator() == UnaryExpr.UnaryOp.DEC) {
            String methodName = expr.getOperator() == UnaryExpr.UnaryOp.INC ? "inc" : "dec";
            // 检查运算符重载（自定义类 inc/dec 方法）
            MirType operandType = operand >= 0 && operand < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(operand).getType() : null;
            if (operandType != null && operandType.getKind() == MirType.Kind.OBJECT
                    && operandType.getClassName() != null
                    && hasNovaMethod(operandType.getClassName(), methodName)) {
                int oldVal = -1;
                if (expr.isPostfix()) {
                    oldVal = builder.emitMove(operand, MirType.ofObject("java/lang/Object"), loc);
                }
                String owner = operandType.getClassName();
                String desc = lookupNovaMethodDesc(owner, methodName, 0);
                MirType retType = inferReturnType(desc.substring(desc.indexOf(')') + 1), owner);
                int newVal = builder.emitInvokeVirtualDesc(operand, methodName, new int[0],
                        owner, desc, retType, loc);
                builder.emitMoveTo(newVal, operand, loc);
                // 写回变量到 $Module 静态字段 + NovaScriptContext
                if (expr.getOperand() instanceof Identifier) {
                    String vn = ((Identifier) expr.getOperand()).getName();
                    if (topLevelFieldNames.containsKey(vn) && !Boolean.TRUE.equals(topLevelFieldNames.get(vn))) {
                        builder.emitPutStatic(moduleClassName, vn, "Ljava/lang/Object;", operand, loc);
                    }
                    if (scriptMode) emitScriptContextSet(builder, vn, operand, loc);
                }
                return expr.isPostfix() ? oldVal : operand;
            }
            int oldVal = -1;
            if (expr.isPostfix()) {
                // 后缀：保存旧值
                oldVal = builder.emitMove(operand, MirType.ofObject("java/lang/Object"), loc);
            }
            int one = builder.emitConstInt(1, loc);
            BinaryOp binOp = expr.getOperator() == UnaryExpr.UnaryOp.INC ? BinaryOp.ADD : BinaryOp.SUB;
            int newVal = builder.emitBinary(binOp, operand, one, MirType.ofInt(), loc);
            builder.emitMoveTo(newVal, operand, loc);
            // 隐式 this.field 写回：++count 中 count 可能是 this.count
            if (expr.getOperand() instanceof Identifier) {
                String varName = ((Identifier) expr.getOperand()).getName();
                // 顶层 var → PUTSTATIC + ScriptContext
                if (topLevelFieldNames.containsKey(varName) && !Boolean.TRUE.equals(topLevelFieldNames.get(varName))) {
                    builder.emitPutStatic(moduleClassName, varName, "Ljava/lang/Object;", operand, loc);
                    if (scriptMode) emitScriptContextSet(builder, varName, operand, loc);
                } else {
                    boolean isLocal = false;
                    for (MirLocal local : builder.getFunction().getLocals()) {
                        if (local.getName().equals(varName)) { isLocal = true; break; }
                    }
                    if (!isLocal) {
                        for (MirLocal local : builder.getFunction().getLocals()) {
                            if ("this".equals(local.getName()) || "$this".equals(local.getName())) {
                                builder.emitSetField(local.getIndex(), varName, operand, loc);
                                break;
                            }
                        }
                    }
                    // 脚本模式: 写回变量到 NovaScriptContext
                    if (scriptMode) {
                        emitScriptContextSet(builder, varName, operand, loc);
                    }
                }
            }
            return expr.isPostfix() ? oldVal : operand;
        }

        // 运算符重载: NEG 在 Nova 类上 → 调用 unaryMinus()
        if (expr.getOperator() == UnaryExpr.UnaryOp.NEG) {
            MirType operandType = operand >= 0 && operand < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(operand).getType() : null;
            if (operandType != null && operandType.getKind() == MirType.Kind.OBJECT
                    && operandType.getClassName() != null
                    && classNames.contains(operandType.getClassName())) {
                String owner = operandType.getClassName();
                if (hasNovaMethod(owner, "unaryMinus")) {
                    String desc = lookupNovaMethodDesc(owner, "unaryMinus", 0);
                    MirType retType = inferReturnType(desc.substring(desc.indexOf(')') + 1), owner);
                    return builder.emitInvokeVirtualDesc(operand, "unaryMinus", new int[0],
                            owner, desc, retType, loc);
                }
            }
        }

        UnaryOp op;
        switch (expr.getOperator()) {
            case NOT: op = UnaryOp.NOT; break;
            case POS: op = UnaryOp.POS; break;
            case BNOT: op = UnaryOp.BNOT; break;
            default: op = UnaryOp.NEG; break;
        }
        return builder.emitUnary(op, operand, hirTypeToMir(expr.getType()), expr.getLocation());
    }

    private int lowerCall(HirCall expr, MirBuilder builder) {
        if (hasPlaceholderArg(expr)) return lowerPartialApplicationCall(expr, builder);
        if (expr.getCallee() instanceof Identifier) {
            String name = ((Identifier) expr.getCallee()).getName();
            if ("println".equals(name) || "print".equals(name))
                return lowerPrintCall(name, expr, builder);
            if ("classOf".equals(name) && expr.getArgs().size() == 1)
                return lowerClassOfCall(expr, builder);
            // 用户定义的类构造器优先于同名 stdlib 函数（允许遮蔽 Ok/Err 等内置名）
            if (classNames.contains(name)) {
                int r = lowerClassConstructorAndReceiverCall(name, expr, builder);
                if (r >= 0) return r;
            }
            // 用户定义的顶层函数优先于同名 stdlib 函数
            if (topLevelFunctionNames.contains(name)) return lowerTopLevelFunctionCall(name, expr, builder);
            { int r = lowerStdlibNativeFunctionCall(name, expr, builder); if (r >= 0) return r; }
            { int r = lowerStdlibSupplierLambdaCall(name, expr, builder); if (r >= 0) return r; }
            { int r = tryStdlibReceiverLambdaCall(name, expr, builder); if (r >= 0) return r; }
            { int r = lowerBuiltinModuleFunction(name, expr, builder); if (r >= 0) return r; }
            if ("Array".equals(name) && !expr.getArgs().isEmpty())
                return lowerArrayConstructorCall(name, expr, builder);
            { int r = lowerClassConstructorAndReceiverCall(name, expr, builder); if (r >= 0) return r; }
            { int r = trySelfMethodCall(name, expr, builder); if (r >= 0) return r; }
            return lowerPipeCallExpr(name, expr, builder);
        }
        { int r = tryJavaTypeOrQualifiedCall(expr, builder); if (r >= 0) return r; }
        return lowerFunctionTypeInvocation(expr, builder);
    }

    private boolean hasPlaceholderArg(HirCall expr) {
        for (Expression argExpr : expr.getArgs()) {
            if (argExpr instanceof Identifier && "_".equals(((Identifier) argExpr).getName())) {
                return true;
            }
        }
        return false;
    }

    /** 内置模块函数 → INVOKESTATIC。返回 -1 表示不匹配 */
    /**
     * 反射发现模块类的 public static 方法，注册到 builtinModuleFunctions。
     * @param moduleClass JVM 内部类名（如 "com/novalang/runtime/interpreter/stdlib/StdlibIOCompiled"）
     * @param symbol 指定符号名（null 表示通配符导入，注册所有方法）
     */
    private void discoverModuleFunctions(String moduleClass, String symbol) {
        try {
            Class<?> cls = Class.forName(moduleClass.replace('/', '.'));
            for (java.lang.reflect.Method m : cls.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (!java.lang.reflect.Modifier.isPublic(mod) || !java.lang.reflect.Modifier.isStatic(mod)) continue;
                if (m.getReturnType() != Object.class) continue;
                String name = m.getName();
                if (symbol != null && !name.equals(symbol)) continue;
                int paramCount = m.getParameterCount();
                StringBuilder desc = new StringBuilder("(");
                for (int i = 0; i < paramCount; i++) desc.append("Ljava/lang/Object;");
                desc.append(")Ljava/lang/Object;");
                builtinModuleFunctions.put(name, moduleClass + "|" + name + "|" + desc);
            }
        } catch (ClassNotFoundException ignored) {
            // 编译类不在 classpath（纯编译场景），跳过
        }
    }

    private int lowerBuiltinModuleFunction(String name, HirCall expr, MirBuilder builder) {
        String extra = builtinModuleFunctions.get(name);
        if (extra == null) return -1;
        int[] args = lowerArgs(expr.getArgs(), builder);
        return builder.emitInvokeStatic(extra, args,
                MirType.ofObject("java/lang/Object"), expr.getLocation());
    }

    /** StdlibRegistry NativeFunction → INVOKESTATIC。返回 -1 表示不匹配 */
    private int lowerStdlibNativeFunctionCall(String name, HirCall expr, MirBuilder builder) {
        StdlibRegistry.NativeFunctionInfo nfInfo = StdlibRegistry.getNativeFunction(name);
        if (nfInfo != null) {
            // scriptMode: 所有 stdlib 函数走动态解析（允许用户 Nova.define 覆盖）
            if (scriptMode) return -1;
        }
        if (nfInfo != null && (expr.getArgs().size() == nfInfo.arity || nfInfo.arity == -1)) {
            String extra = nfInfo.jvmOwner + "|" + nfInfo.jvmMethodName + "|" + nfInfo.jvmDescriptor;
            String retDescStr = nfInfo.jvmDescriptor.substring(nfInfo.jvmDescriptor.indexOf(')') + 1);
            MirType retType = descriptorToMirType(retDescStr);
            if (nfInfo.arity == -1) {
                // varargs: 打包参数到 Object[] 数组
                SourceLocation loc = expr.getLocation();
                int sizeConst = builder.emitConstInt(expr.getArgs().size(), loc);
                int arrLocal = builder.emitNewArray(sizeConst, loc);
                for (int i = 0; i < expr.getArgs().size(); i++) {
                    int argVal = lowerExpr(expr.getArgs().get(i), builder);
                    int idxConst = builder.emitConstInt(i, loc);
                    builder.emitIndexSet(arrLocal, idxConst, argVal, loc);
                }
                return builder.emitInvokeStatic(extra, new int[]{arrLocal}, retType, loc);
            }
            int[] args = lowerArgs(expr.getArgs(), builder);
            return builder.emitInvokeStatic(extra, args, retType, expr.getLocation());
        }
        return -1;
    }

    /** StdlibRegistry SupplierLambda（如 async）→ 编译 lambda 后调用 AsyncHelper.run。返回 -1 表示不匹配 */
    private int lowerStdlibSupplierLambdaCall(String name, HirCall expr, MirBuilder builder) {
        StdlibRegistry.SupplierLambdaInfo slInfo = StdlibRegistry.getSupplierLambda(name);
        if (slInfo != null && expr.getArgs().size() == 1) {
            int lambdaInst = lowerExpr(expr.getArgs().get(0), builder);
            String extra = "com/novalang/runtime/stdlib/AsyncHelper|run|(Ljava/lang/Object;)Ljava/lang/Object;";
            return builder.emitInvokeStatic(extra, new int[]{lambdaInst},
                    MirType.ofObject("java/util/concurrent/CompletableFuture"),
                    expr.getLocation());
        }
        return -1;
    }

    /** StdlibRegistry ReceiverLambda（buildString / buildList 等）。返回 -1 表示不匹配 */
    private int tryStdlibReceiverLambdaCall(String name, HirCall expr, MirBuilder builder) {
        StdlibRegistry.ReceiverLambdaInfo rlInfo = StdlibRegistry.getReceiverLambda(name);
        if (rlInfo != null && expr.getArgs().size() == 1) {
            return lowerReceiverLambdaCall(rlInfo, expr, builder);
        }
        return -1;
    }

    /** Array(size) / Array(size, initLambda) 构造 */
    private int lowerArrayConstructorCall(String name, HirCall expr, MirBuilder builder) {
        if (expr.getArgs().size() == 1) {
            int size = lowerExpr(expr.getArgs().get(0), builder);
            // Array<T> → 原始类型数组特化
            if (expr.getTypeArgs() != null && !expr.getTypeArgs().isEmpty()) {
                HirType elemType = expr.getTypeArgs().get(0);
                if (elemType instanceof PrimitiveType) {
                    String arrayDesc = primitiveKindToArrayDesc(((PrimitiveType) elemType).getKind());
                    if (arrayDesc != null) {
                        return builder.emitNewTypedArray(size, arrayDesc, expr.getLocation());
                    }
                }
            }
            return builder.emitNewArray(size, expr.getLocation());
        }
        // Array(size, initLambda) → INVOKE_STATIC $Module|Array（运行时由 Builtins 处理）
        int[] args = lowerArgs(expr.getArgs(), builder);
        String extra = "$Module|Array|" + MethodDescriptor.allObjectDesc(args.length);
        return builder.emitInvokeStatic(extra, args,
                MirType.ofObject("java/lang/Object"), expr.getLocation());
    }

    /** 类构造器调用 + 接收者 Lambda 上下文路由。返回 -1 表示不匹配 */
    private int lowerClassConstructorAndReceiverCall(String name, HirCall expr, MirBuilder builder) {
        // 类构造器调用 → NEW_OBJECT
        if (classNames.contains(name)) {
            // 默认参数填充
            List<Expression> effectiveArgs = expr.getArgs();
            HirFunction ctorDecl = classConstructorDecls.get(name);
            if (ctorDecl != null && effectiveArgs.size() < ctorDecl.getParams().size()) {
                effectiveArgs = new ArrayList<>(effectiveArgs);
                for (int i = effectiveArgs.size(); i < ctorDecl.getParams().size(); i++) {
                    HirParam param = ctorDecl.getParams().get(i);
                    if (param.hasDefaultValue()) {
                        effectiveArgs.add(param.getDefaultValue());
                    }
                }
            }
            int[] args = lowerArgs(effectiveArgs, builder);
            return builder.emitNewObject(name, args, expr.getLocation());
        }

        // Java 类构造器调用（显式 import java）→ NEW_OBJECT
        String javaInternalName = javaImports.get(name);
        if (javaInternalName != null) {
            int[] args = lowerArgs(expr.getArgs(), builder);
            return builder.emitNewObject(javaInternalName, args, expr.getLocation());
        }

        // 接收者 Lambda 上下文：将未解析的调用路由到接收者对象
        if (currentReceiverLambda != null) {
            boolean isLocal = false;
            for (MirLocal local : builder.getFunction().getLocals()) {
                if (local.getName().equals(name)) { isLocal = true; break; }
            }
            if (!isLocal) {
                int[] args = lowerArgs(expr.getArgs(), builder);
                return lowerReceiverMethodCall(name, args, expr.getLocation(), builder);
            }
        }

        return -1;
    }

    /** 自方法调用: this.method(args) → INVOKE_VIRTUAL on this。返回 -1 表示非自方法调用需回退 */
    private int trySelfMethodCall(String name, HirCall expr, MirBuilder builder) {
        MirLocal thisLocal = findThisLocal(builder);
        boolean isLocalVar = false;
        for (MirLocal local : builder.getFunction().getLocals()) {
            if (local.getName().equals(name)) { isLocalVar = true; break; }
        }
        if (thisLocal != null && !isLocalVar) {
            // 安全检查: StdlibRegistry 已知全局函数不是 this.method，跳过自方法调用
            // 但 Object 通用方法（toString 等）在 scopeReceiver 上总是可用，保留自方法调用
            if (StdlibRegistry.get(name) != null && !OBJECT_METHOD_NAMES.contains(name)) {
                return -1;
            }
            // （编译器侧不改动 lambda 方法分派——运行时通过 scopeReceiver/ScriptContext 回退处理）
            StdlibRegistry.ReceiverLambdaInfo rl = StdlibRegistry.getReceiverLambda(name);
            if (rl != null && expr.getArgs().size() == 1) {
                return lowerReceiverLambdaCall(rl, expr, builder);
            }
            String owner = thisLocal.getType().getClassName();
            if (owner == null) owner = "java/lang/Object";
            // 字段调用（如 lambda 字段 f(x)）: 先 GETFIELD 再 INVOKEINTERFACE
            Set<String> fields = classFieldNames.get(owner);
            Map<String, String> methodDescs = novaMethodDescs.get(owner);
            boolean isFieldNotMethod = fields != null && fields.contains(name)
                    && (methodDescs == null || !methodDescs.containsKey(name));
            // Lambda 类捕获的嵌套函数变量：也走字段调用路径
            if (!isFieldNotMethod && owner != null && owner.contains("$Lambda$")
                    && !lambdaCaptureStack.isEmpty() && lambdaCaptureStack.peek().contains(name)) {
                isFieldNotMethod = true;
            }
            if (isFieldNotMethod) {
                int fieldVal = builder.emitGetField(thisLocal.getIndex(), name,
                        MirType.ofObject("java/lang/Object"), expr.getLocation());
                int[] args = lowerArgs(expr.getArgs(), builder);
                String funcInterface;
                switch (args.length) {
                    case 0:  funcInterface = "com/novalang/runtime/Function0"; break;
                    case 1:  funcInterface = "com/novalang/runtime/Function1"; break;
                    case 2:  funcInterface = "com/novalang/runtime/Function2"; break;
                    case 3:  funcInterface = "com/novalang/runtime/Function3"; break;
                    default:
                        return emitLambdaInvokerCall(fieldVal, args, builder, expr.getLocation());
                }
                String funcDesc = MethodDescriptor.allObjectDesc(args.length);
                return builder.emitInvokeInterfaceDesc(fieldVal, "invoke", args,
                        funcInterface, funcDesc,
                        MirType.ofObject("java/lang/Object"), expr.getLocation());
            }
            // 真实类中，仅对已知类方法生成自调用，未知函数回退到 invokedynamic
            // Lambda 类保留自调用：scopeReceiver 在运行时重定向 this（apply/run/with 依赖此机制）
            // 继承链不完整时保留自调用：父类方法可能在之前的 evalRepl 中注册
            boolean isLambdaClass = owner != null && owner.contains("$Lambda$");
            // Lambda 类始终自调用（MirInterpreter scopeReceiver 重定向）
            // MirCodeGenerator 对 Lambda 类的 INVOKE_VIRTUAL 生成 invokedynamic
            boolean shouldSelfCall = isLambdaClass;
            if (!isLambdaClass) {
                boolean canCheckInherited = isInheritanceChainComplete(owner);
                // 已知方法 或 继承链不完整 → 生成自调用
                shouldSelfCall = !(canCheckInherited && lookupNovaMethodDescInherited(owner, name) == null);
            }
            if (shouldSelfCall) {
                int[] args = lowerArgs(expr.getArgs(), builder);
                String desc = lookupNovaMethodDesc(owner, name, args.length);
                String retDescStr = desc.substring(desc.indexOf(')') + 1);
                MirType retType = inferReturnType(retDescStr, owner);
                if (interfaceNames.contains(owner)) {
                    return builder.emitInvokeInterfaceDesc(thisLocal.getIndex(), name, args,
                            owner, desc, retType, expr.getLocation());
                }
                return builder.emitInvokeVirtualDesc(thisLocal.getIndex(), name, args,
                        owner, desc, retType, expr.getLocation());
            }
        }
        return -1;
    }

    /** 管道/未知函数回退: f(args) → $PipeCall 运行时分派 */
    private int lowerPipeCallExpr(String name, HirCall expr, MirBuilder builder) {
        boolean isLocalVar = false;
        for (MirLocal local : builder.getFunction().getLocals()) {
            if (local.getName().equals(name)) { isLocalVar = true; break; }
        }
        // 命名参数：需要走 $PipeCall 运行时分派（编译期不知道目标函数参数列表）
        if (!isLocalVar && expr.getNamedArgs() != null && !expr.getNamedArgs().isEmpty()) {
            // 跳到下面的 scriptMode $PipeCall 路径处理命名参数
        } else if (!isLocalVar && (!expr.getArgs().isEmpty()
                || (expr.getTypeArgs() != null && !expr.getTypeArgs().isEmpty()))) {
            int[] args = lowerArgs(expr.getArgs(), builder);

            // 无 spread + 无 typeArgs → invokedynamic bootstrapStaticInvoke
            boolean hasSpread = expr.hasSpread();
            boolean hasTypeArgs = expr.getTypeArgs() != null && !expr.getTypeArgs().isEmpty();
            if (!hasSpread && !hasTypeArgs) {
                StringBuilder descBuilder = new StringBuilder("(");
                for (int i = 0; i < args.length; i++) {
                    descBuilder.append("Ljava/lang/Object;");
                }
                descBuilder.append(")Ljava/lang/Object;");
                InvokeDynamicInfo pipeInfo = new InvokeDynamicInfo(
                        name, "com/novalang/runtime/NovaBootstrap", "bootstrapStaticInvoke",
                        descBuilder.toString());
                return builder.emitInvokeDynamic(pipeInfo, args,
                        MirType.ofObject("java/lang/Object"), expr.getLocation());
            }

            // 带 spread/typeArgs → 保持 $PipeCall（运行时展开）
            String extra = "$PipeCall|" + name;
            // spread 参数: 编码 spread 索引到 methodName 中供运行时展开
            if (hasSpread) {
                StringBuilder sb = new StringBuilder();
                for (int j = 0; j < expr.getArgs().size(); j++) {
                    if (expr.isSpread(j)) {
                        if (sb.length() > 0) sb.append(',');
                        sb.append(j);
                    }
                }
                extra = "$PipeCall|" + name + "%spread:" + sb;
            }
            // reified 类型参数: 附加到 extra 中供运行时解析
            if (hasTypeArgs) {
                StringBuilder sb = new StringBuilder(extra).append('#');
                for (int j = 0; j < expr.getTypeArgs().size(); j++) {
                    if (j > 0) sb.append(',');
                    sb.append(hirTypeDisplayName(expr.getTypeArgs().get(j)));
                }
                extra = sb.toString();
            }
            return builder.emitInvokeStatic(extra, args,
                    MirType.ofObject("java/lang/Object"), expr.getLocation());
        }
        // isLocalVar 或无参数无类型实参：回退到函数类型调用
        // 局部函数默认参数 + 命名参数
        HirFunction localFunc = localFunctionDecls.get(name);
        if (localFunc != null) {
            List<Expression> filled = mergeArgsWithDefaults(expr, localFunc);
            HirCall filledCall = new HirCall(expr.getLocation(), expr.getType(),
                    expr.getCallee(), expr.getTypeArgs(), filled);
            return lowerFunctionTypeInvocation(filledCall, builder);
        }
        // scriptMode 编译模式: 未解析的裸调用走 $PipeCall 运行时分派
        if (scriptMode && !isLocalVar) {
            Map<String, Expression> namedArgs = expr.getNamedArgs();
            if (namedArgs != null && !namedArgs.isEmpty()) {
                // 命名参数编码: "funcName@named:positionalCount:key1,key2"
                // 位置参数先 lower，命名参数值按 key 顺序追加
                int[] positionalArgs = lowerArgs(expr.getArgs(), builder);
                StringBuilder nameList = new StringBuilder();
                List<Integer> namedVals = new ArrayList<>();
                for (Map.Entry<String, Expression> e : namedArgs.entrySet()) {
                    if (nameList.length() > 0) nameList.append(',');
                    nameList.append(e.getKey());
                    namedVals.add(lowerExpr(e.getValue(), builder));
                }
                int[] allArgs = new int[positionalArgs.length + namedVals.size()];
                System.arraycopy(positionalArgs, 0, allArgs, 0, positionalArgs.length);
                for (int i = 0; i < namedVals.size(); i++) {
                    allArgs[positionalArgs.length + i] = namedVals.get(i);
                }
                String extra = name + "@named:" + positionalArgs.length + ":" + nameList;
                return builder.emitInvokeStatic("$PipeCall|" + extra, allArgs,
                        MirType.ofObject("java/lang/Object"), expr.getLocation());
            }
            int[] args = lowerArgs(expr.getArgs(), builder);
            return builder.emitInvokeStatic("$PipeCall|" + name, args,
                    MirType.ofObject("java/lang/Object"), expr.getLocation());
        }
        return lowerFunctionTypeInvocation(expr, builder);
    }

    /** Java.type()/限定Java类名构造器/方法调用。返回 -1 表示不匹配 */
    private int tryJavaTypeOrQualifiedCall(HirCall expr, MirBuilder builder) {
        // Java.type("className")(...) → NEW_OBJECT
        // 安全策略方法黑名单在 MirInterpreter 的 NEW_OBJECT 处理中守卫（hasMethodRestrictions 时跳过集合快捷转换）
        if (expr.getCallee() instanceof HirCall) {
            String javaClassName = extractJavaTypeClassName((HirCall) expr.getCallee());
            if (javaClassName != null) {
                String internalName = javaClassName.replace('.', '/');
                int[] args = lowerArgs(expr.getArgs(), builder);
                return builder.emitNewObject(internalName, args, expr.getLocation());
            }
        }

        // 限定 Java 类名构造器调用: java.lang.StringBuilder() → NEW_OBJECT
        if (expr.getCallee() instanceof MemberExpr) {
            String qualifiedName = extractQualifiedName(expr.getCallee());
            if (qualifiedName != null) {
                Class<?> javaClass = resolveJavaClass(qualifiedName);
                if (javaClass != null) {
                    String internalName = qualifiedName.replace('.', '/');
                    int[] args = lowerArgs(expr.getArgs(), builder);
                    return builder.emitNewObject(internalName, args, expr.getLocation());
                }
            }
            // 方法调用: target.method(args) → INVOKE_VIRTUAL
            return lowerMethodCall((MemberExpr) expr.getCallee(), expr, builder);
        }

        return -1;
    }

    /** 函数类型调用: callee(args) → INVOKE_VIRTUAL/INVOKEINTERFACE invoke */
    private int lowerFunctionTypeInvocation(HirCall expr, MirBuilder builder) {
        int callee = lowerExpr(expr.getCallee(), builder);
        int[] args = lowerArgs(expr.getArgs(), builder);
        // 尝试获取 callee 的具体类型（如 lambda 类），使用正确的 owner
        MirType calleeType = callee >= 0 && callee < builder.getFunction().getLocals().size()
                ? builder.getFunction().getLocals().get(callee).getType() : null;
        if (calleeType != null && calleeType.getKind() == MirType.Kind.OBJECT
                && calleeType.getClassName() != null
                && !"java/lang/Object".equals(calleeType.getClassName())) {
            String owner = calleeType.getClassName();
            String desc = MethodDescriptor.allObjectDesc(args.length);
            return builder.emitInvokeVirtualDesc(callee, "invoke", args,
                    owner, desc, MirType.ofObject("java/lang/Object"),
                    expr.getLocation());
        }
        // callee 类型为 Object — 可能是 FunctionN lambda、NovaJavaClass 构造器、NovaCallable 等
        // 统一走 invokedynamic 分派，由 NovaBootstrap 在运行时根据实际类型选择正确的调用路径
        // （避免 CHECKCAST FunctionN 对 NovaJavaClass 等非 FunctionN 类型的 ClassCastException）
        InvokeDynamicInfo dynInfo = new InvokeDynamicInfo(
                "invoke", "com/novalang/runtime/NovaBootstrap", "bootstrapInvoke",
                MethodDescriptor.allObjectDesc(args.length + 1));
        int[] allOps = new int[args.length + 1];
        allOps[0] = callee;
        System.arraycopy(args, 0, allOps, 1, args.length);
        return builder.emitInvokeDynamic(dynInfo, allOps,
                MirType.ofObject("java/lang/Object"), expr.getLocation());
    }

    /**
     * classOf(ClassName) → LDC Class + INVOKESTATIC NovaClassInfo.fromJavaClass(Class)
     */
    private int lowerClassOfCall(HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        Expression arg = expr.getArgs().get(0);

        // 已知类名 Identifier 走 CONST_CLASS 路径（编译期解析）
        // 变量 Identifier 走表达式路径（运行时解析）
        if (arg instanceof Identifier && (classNames.contains(((Identifier) arg).getName())
                || resolveJavaClass(((Identifier) arg).getName()) != null)) {
            String className = ((Identifier) arg).getName();
            // 解析类名
            String internalName = className;
            Class<?> javaClass = resolveJavaClass(className);
            if (javaClass != null) {
                internalName = javaClass.getName().replace('.', '/');
            }
            int classLocal = builder.emitConstClass(internalName, loc);
            String extra = "com/novalang/runtime/interpreter/reflect/NovaClassInfo|fromJavaClass|(Ljava/lang/Class;)Lcom/novalang/runtime/interpreter/reflect/NovaClassInfo;";
            return builder.emitInvokeStatic(extra, new int[]{classLocal},
                    MirType.ofObject("com/novalang/runtime/interpreter/reflect/NovaClassInfo"), loc);
        }

        // classOf(expr) — 直接传 NovaValue，StaticMethodDispatcher 处理 NovaClass/NovaObject/NovaExternalObject
        int objLocal = lowerExpr(arg, builder);
        String extra = "com/novalang/runtime/interpreter/reflect/NovaClassInfo|fromJavaClass|(Ljava/lang/Class;)Lcom/novalang/runtime/interpreter/reflect/NovaClassInfo;";
        return builder.emitInvokeStatic(extra, new int[]{objLocal},
                MirType.ofObject("com/novalang/runtime/interpreter/reflect/NovaClassInfo"), loc);
    }

    /**
     * 接收者 Lambda 内联编译（buildString / buildList / buildMap / buildSet）。
     * 生成: NEW receiverType → 执行 lambda body → finalizer → 结果
     */
    private int lowerReceiverLambdaCall(StdlibRegistry.ReceiverLambdaInfo info,
                                         HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();

        // 1. 创建接收者对象: NEW receiverType + <init>()V
        int receiver = builder.emitNewObject(info.receiverType, new int[]{}, loc);

        // 2. 保存并设置接收者上下文
        StdlibRegistry.ReceiverLambdaInfo savedLambda = currentReceiverLambda;
        int savedLocal = receiverLocalIndex;
        currentReceiverLambda = info;
        receiverLocalIndex = receiver;

        // 3. 取出 lambda 参数并内联其 body
        Expression lambdaArg = expr.getArgs().get(0);
        if (lambdaArg instanceof HirLambda) {
            AstNode body = ((HirLambda) lambdaArg).getBody();
            lowerNode(body, builder);
        }

        // 4. 恢复上下文
        currentReceiverLambda = savedLambda;
        receiverLocalIndex = savedLocal;

        // 5. 调用 finalizer（如 toString）并返回结果
        if (info.finalizerMethod != null) {
            return builder.emitInvokeVirtualDesc(receiver, info.finalizerMethod, new int[]{},
                    info.receiverType, "()" + info.returnTypeDesc,
                    descriptorToMirType(info.returnTypeDesc), loc);
        } else {
            return receiver;
        }
    }

    /**
     * 接收者 Lambda 上下文中的方法调用 → INVOKEVIRTUAL on receiver
     */
    private int lowerReceiverMethodCall(String methodName, int[] args,
                                         SourceLocation loc, MirBuilder builder) {
        String receiverType = currentReceiverLambda.receiverType;
        Class<?> cls = resolveJavaClass(receiverType);
        if (cls != null) {
            java.lang.reflect.Method m = findJavaMethod(cls, methodName, args.length);
            if (m != null) {
                String desc = buildJavaMethodDescriptor(m);
                String retDesc = desc.substring(desc.indexOf(')') + 1);
                MirType retType = descriptorToMirType(retDesc);
                return builder.emitInvokeVirtualDesc(receiverLocalIndex, methodName, args,
                        receiverType, desc, retType, loc);
            }
        }
        // 兜底: 全 Object 签名
        String desc = MethodDescriptor.allObjectDesc(args.length);
        return builder.emitInvokeVirtualDesc(receiverLocalIndex, methodName, args,
                receiverType, desc, MirType.ofObject("java/lang/Object"), loc);
    }

    /**
     * 顶层函数调用 → INVOKESTATIC $Module.name(args)
     */
    private int lowerTopLevelFunctionCall(String name, HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        HirFunction hirFunc = topLevelFunctionDecls.get(name);

        // 跨 REPL 命名参数：hirFunc 不可用时无法编译期合并 → 走 $PipeCall 运行时分派
        Map<String, Expression> namedArgs = expr.getNamedArgs();
        if (hirFunc == null && namedArgs != null && !namedArgs.isEmpty()) {
            int[] positionalArgs = lowerArgs(expr.getArgs(), builder);
            StringBuilder nameList = new StringBuilder();
            List<Integer> namedVals = new ArrayList<>();
            for (Map.Entry<String, Expression> e : namedArgs.entrySet()) {
                if (nameList.length() > 0) nameList.append(',');
                nameList.append(e.getKey());
                namedVals.add(lowerExpr(e.getValue(), builder));
            }
            int[] allArgs = new int[positionalArgs.length + namedVals.size()];
            System.arraycopy(positionalArgs, 0, allArgs, 0, positionalArgs.length);
            for (int i = 0; i < namedVals.size(); i++) {
                allArgs[positionalArgs.length + i] = namedVals.get(i);
            }
            String extra = "$PipeCall|" + name + "@named:" + positionalArgs.length + ":" + nameList;
            return builder.emitInvokeStatic(extra, allArgs,
                    MirType.ofObject("java/lang/Object"), loc);
        }

        // spread 调用: 展开列表参数到单独的位置参数
        if (expr.hasSpread() && hirFunc != null) {
            return lowerTopLevelSpreadCall(name, expr, hirFunc, builder, loc);
        }
        // vararg 调用: 将多余参数打包为 ArrayList
        if (hirFunc != null && hasVarargParam(hirFunc)) {
            return lowerTopLevelVarargCall(name, expr, hirFunc, builder, loc);
        }
        List<Expression> effectiveArgs = mergeArgsWithDefaults(expr, hirFunc);
        int[] args = lowerArgs(effectiveArgs, builder);
        String desc = topLevelFuncDescs.get(name);
        if (desc == null) {
            desc = MethodDescriptor.allObjectDesc(args.length);
        }
        String extra = moduleClassName + "|" + name + "|" + desc;
        String retDescStr = desc.substring(desc.indexOf(')') + 1);
        MirType retType = descriptorToMirType(retDescStr);
        return builder.emitInvokeStatic(extra, args, retType, loc);
    }

    /**
     * spread 调用顶层函数: sum(..args) → 从列表中按索引提取元素后调用 INVOKESTATIC
     */
    private int lowerTopLevelSpreadCall(String name, HirCall expr, HirFunction hirFunc,
                                         MirBuilder builder, SourceLocation loc) {
        int paramCount = hirFunc.getParams().size();
        List<Integer> expandedArgs = new ArrayList<>();
        for (int i = 0; i < expr.getArgs().size(); i++) {
            int argVal = lowerExpr(expr.getArgs().get(i), builder);
            if (expr.isSpread(i)) {
                // spread 参数: 从列表中按索引提取各元素
                int remaining = paramCount - expandedArgs.size() - (expr.getArgs().size() - 1 - i);
                for (int j = 0; j < remaining; j++) {
                    int idx = builder.emitConstInt(j, loc);
                    String getExtra = "com/novalang/runtime/NovaCollections|getIndex|(Ljava/lang/Object;I)Ljava/lang/Object;";
                    expandedArgs.add(builder.emitInvokeStatic(getExtra, new int[]{argVal, idx},
                            MirType.ofObject("java/lang/Object"), loc));
                }
            } else {
                expandedArgs.add(argVal);
            }
        }
        int[] args = new int[expandedArgs.size()];
        for (int i = 0; i < args.length; i++) args[i] = expandedArgs.get(i);
        String desc = topLevelFuncDescs.get(name);
        if (desc == null) desc = MethodDescriptor.allObjectDesc(args.length);
        String extra = moduleClassName + "|" + name + "|" + desc;
        String retDescStr = desc.substring(desc.indexOf(')') + 1);
        MirType retType = descriptorToMirType(retDescStr);
        return builder.emitInvokeStatic(extra, args, retType, loc);
    }

    private boolean hasVarargParam(HirFunction func) {
        List<HirParam> params = func.getParams();
        return !params.isEmpty() && params.get(params.size() - 1).isVararg();
    }

    /**
     * vararg 调用: sum(1, 2, 3) → 前 N-1 个参数正常传递，vararg 部分打包为 ArrayList
     */
    private int lowerTopLevelVarargCall(String name, HirCall expr, HirFunction hirFunc,
                                         MirBuilder builder, SourceLocation loc) {
        List<HirParam> params = hirFunc.getParams();
        int fixedCount = params.size() - 1; // vararg 前的固定参数数量
        List<Expression> callArgs = expr.getArgs();

        // 固定参数
        List<Integer> argLocals = new ArrayList<>();
        for (int i = 0; i < fixedCount && i < callArgs.size(); i++) {
            argLocals.add(lowerExpr(callArgs.get(i), builder));
        }

        // vararg 参数: 打包为 ArrayList
        int listLocal = builder.emitNewObject("java/util/ArrayList", new int[0], loc);
        for (int i = fixedCount; i < callArgs.size(); i++) {
            int elem = lowerExpr(callArgs.get(i), builder);
            builder.emitInvokeVirtualDesc(listLocal, "add", new int[]{elem},
                    "java/util/ArrayList", "(Ljava/lang/Object;)Z",
                    MirType.ofBoolean(), loc);
        }
        argLocals.add(listLocal);

        int[] args = new int[argLocals.size()];
        for (int i = 0; i < args.length; i++) args[i] = argLocals.get(i);

        String desc = topLevelFuncDescs.get(name);
        if (desc == null) desc = MethodDescriptor.allObjectDesc(args.length);
        String extra = moduleClassName + "|" + name + "|" + desc;
        String retDescStr = desc.substring(desc.indexOf(')') + 1);
        MirType retType = descriptorToMirType(retDescStr);
        return builder.emitInvokeStatic(extra, args, retType, loc);
    }

    /**
     * Java 静态方法调用（通过 import java）→ 反射查找方法签名后 INVOKESTATIC
     */
    private int lowerJavaStaticCall(String javaClass, String methodName,
                                     HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        int[] args = lowerArgs(expr.getArgs(), builder);
        // 通过反射查找方法签名
        Class<?> cls = resolveJavaClass(javaClass);
        if (cls != null) {
            java.lang.reflect.Method found = findJavaStaticMethod(cls, methodName, args.length);
            if (found != null) {
                String desc = buildJavaMethodDescriptor(found);
                String retDesc = desc.substring(desc.indexOf(')') + 1);
                MirType retType = descriptorToMirType(retDesc);
                String extra = javaClass + "|" + methodName + "|" + desc;
                return builder.emitInvokeStatic(extra, args, retType, loc);
            }
        }
        // 兜底: 全 Object 签名
        String desc = MethodDescriptor.allObjectDesc(args.length);
        String extra = javaClass + "|" + methodName + "|" + desc;
        return builder.emitInvokeStatic(extra, args, MirType.ofObject("java/lang/Object"), loc);
    }

    private java.lang.reflect.Method findJavaStaticMethod(Class<?> cls, String name, int argCount) {
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                return m;
            }
        }
        return null;
    }

    private String buildJavaMethodDescriptor(java.lang.reflect.Method m) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : m.getParameterTypes()) {
            sb.append(classToDescriptor(p));
        }
        sb.append(")");
        sb.append(classToDescriptor(m.getReturnType()));
        return sb.toString();
    }

    private String classToDescriptor(Class<?> cls) {
        if (cls == void.class) return "V";
        if (cls == int.class) return "I";
        if (cls == long.class) return "J";
        if (cls == double.class) return "D";
        if (cls == float.class) return "F";
        if (cls == boolean.class) return "Z";
        if (cls == byte.class) return "B";
        if (cls == char.class) return "C";
        if (cls == short.class) return "S";
        if (cls.isArray()) return "[" + classToDescriptor(cls.getComponentType());
        return "L" + cls.getName().replace('.', '/') + ";";
    }

    /**
     * 方法调用 target.method(args) → INVOKE_VIRTUAL / INVOKESTATIC
     */
    private int lowerMethodCall(MemberExpr fieldAccess, HirCall expr, MirBuilder builder) {
        { int r = tryStaticOrImportMethodCall(fieldAccess, expr, builder); if (r >= 0) return r; }
        SourceLocation loc = expr.getLocation();
        int target = lowerExpr(fieldAccess.getTarget(), builder);
        String methodName = resolveMethodAlias(fieldAccess.getMember());
        // 类方法默认参数: 检查参数数量不足时补 null
        List<Expression> effectiveMethodArgs = expr.getArgs();
        MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                ? builder.getFunction().getLocals().get(target).getType()
                : MirType.ofObject("java/lang/Object");
        if (targetType.getKind() == MirType.Kind.OBJECT && targetType.getClassName() != null) {
            String tOwner = targetType.getClassName();
            String methodDesc = lookupNovaMethodDescInherited(tOwner, methodName);
            if (methodDesc != null) {
                // 计算描述符中的参数数量
                int expectedArgs = countDescParams(methodDesc);
                if (effectiveMethodArgs.size() < expectedArgs) {
                    List<Expression> filled = new ArrayList<>(effectiveMethodArgs);
                    for (int i = filled.size(); i < expectedArgs; i++) {
                        filled.add(new Literal(loc, null, Literal.LiteralKind.NULL));
                    }
                    effectiveMethodArgs = filled;
                }
            }
        }
        int[] args = lowerArgs(effectiveMethodArgs, builder);
        { int r = tryLocalVarFunctionCall(target, methodName, args, builder, loc); if (r >= 0) return r; }
        // super.method() 调用：标记 owner 为 "$super$" 以便 MirInterpreter 分派到父类
        if (fieldAccess.getTarget() instanceof ThisExpr
                && ((ThisExpr) fieldAccess.getTarget()).isSuper()) {
            String superOwner = "$super$";
            if (currentEnclosingClassName != null) {
                String parentName = classSuperClass.get(currentEnclosingClassName);
                if (parentName != null) superOwner = "$super$" + parentName;
            }
            return builder.emitInvokeVirtualDesc(target, methodName, args,
                    superOwner, MethodDescriptor.allObjectDesc(args.length),
                    MirType.ofObject("java/lang/Object"), loc);
        }
        { int r = lowerDataCopyCall(target, methodName, args, expr, builder, loc); if (r >= 0) return r; }
        { int r = tryComponentNCall(target, methodName, args, builder, loc); if (r >= 0) return r; }
        return lowerStandardMethodCall(target, methodName, args, builder, loc);
    }

    /**
     * 合并位置参数 + 命名参数 + 默认值，返回完整的参数列表。
     * 支持: fun greet(name: String = "world") → greet() / greet("Nova") / greet(name = "Nova")
     */
    private List<Expression> mergeArgsWithDefaults(HirCall expr, HirFunction hirFunc) {
        List<Expression> positionalArgs = expr.getArgs();
        Map<String, Expression> namedArgs = expr.getNamedArgs();
        if (hirFunc == null) return positionalArgs;

        List<HirParam> params = hirFunc.getParams();
        // 无命名参数且参数已满 → 直接返回
        if ((namedArgs == null || namedArgs.isEmpty()) && positionalArgs.size() >= params.size()) {
            return positionalArgs;
        }

        List<Expression> result = new ArrayList<>(params.size());
        for (int i = 0; i < params.size(); i++) {
            String paramName = params.get(i).getName();
            if (namedArgs != null && namedArgs.containsKey(paramName)) {
                // 命名参数优先
                result.add(namedArgs.get(paramName));
            } else if (i < positionalArgs.size()) {
                // 位置参数
                result.add(positionalArgs.get(i));
            } else if (params.get(i).hasDefaultValue()) {
                // 默认值
                result.add(params.get(i).getDefaultValue());
            } else {
                // 缺少参数 → 用 null 占位（运行时报错）
                result.add(new Literal(expr.getLocation(), null, Literal.LiteralKind.NULL));
            }
        }
        return result;
    }

    private int[] lowerArgs(List<Expression> argExprs, MirBuilder builder) {
        int[] args = new int[argExprs.size()];
        for (int i = 0; i < args.length; i++) args[i] = lowerExpr(argExprs.get(i), builder);
        return args;
    }

    /** 类名/object/Java import/限定Java类名 静态方法调用。返回 -1 表示不匹配 */
    private int tryStaticOrImportMethodCall(MemberExpr fieldAccess, HirCall expr, MirBuilder builder) {
        // 检查是否为类名上的方法调用
        if (fieldAccess.getTarget() instanceof Identifier) {
            String targetName = ((Identifier) fieldAccess.getTarget()).getName();
            if (classNames.contains(targetName)) {
                if (objectNames.contains(targetName)) {
                    // object 单例：GETSTATIC INSTANCE + INVOKEVIRTUAL
                    return lowerObjectMethodCall(targetName, fieldAccess.getMember(), expr, builder);
                }
                return lowerStaticMethodCall(targetName, fieldAccess.getMember(), expr, builder);
            }
            // Java import 的静态方法调用（如 System.currentTimeMillis()）
            String javaClass = javaImports.get(targetName);
            if (javaClass != null) {
                return lowerJavaStaticCall(javaClass, fieldAccess.getMember(), expr, builder);
            }
            // javaClass() 常量传播（仅字段访问在 lowerFieldAccess 中处理）
            // 方法调用不做传播，因为 SAM 适配需要 invokedynamic 路径
            // Java.type("className") → fall through 到通用方法调用路径，运行时处理
            // 安全策略检查在 VirtualMethodDispatcher / NovaDynamic 中统一生效
        }

        // 限定 Java 类名的静态方法调用: java.lang.Math.max(a, b)
        if (!(fieldAccess.getTarget() instanceof Identifier)) {
            String targetQualifiedName = extractQualifiedName(fieldAccess.getTarget());
            if (targetQualifiedName != null) {
                Class<?> cls = resolveJavaClass(targetQualifiedName);
                if (cls != null) {
                    String internalName = targetQualifiedName.replace('.', '/');
                    return lowerJavaStaticCall(internalName, fieldAccess.getMember(), expr, builder);
                }
            }
        }

        return -1;
    }

    /** 局部变量函数调用: receiver.block(args) → $ScopeCall。返回 -1 表示不匹配 */
    private int tryLocalVarFunctionCall(int target, String methodName, int[] args,
                                        MirBuilder builder, SourceLocation loc) {
        int methodLocalIdx = -1;
        for (MirLocal local : builder.getFunction().getLocals()) {
            if (local.getName().equals(methodName)) {
                methodLocalIdx = local.getIndex();
                break;
            }
        }
        if (methodLocalIdx >= 0) {
            // 检查 receiver 类型是否有同名方法 → 方法优先
            MirType tgtType = target >= 0 && target < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(target).getType() : null;
            String tgtOwner = tgtType != null && tgtType.getKind() == MirType.Kind.OBJECT
                    && tgtType.getClassName() != null ? tgtType.getClassName() : null;
            boolean receiverHasMethod = tgtOwner != null
                    && lookupNovaMethodDescInherited(tgtOwner, methodName) != null;
            if (!receiverHasMethod) {
                int[] allOps = new int[2 + args.length];
                allOps[0] = methodLocalIdx;
                allOps[1] = target;
                System.arraycopy(args, 0, allOps, 2, args.length);
                return builder.emitInvokeStatic("$ScopeCall", allOps,
                        MirType.ofObject("java/lang/Object"), loc);
            }
        }
        return -1;
    }

    /** data class copy() 命名参数处理。返回 -1 表示不匹配 */
    private int lowerDataCopyCall(int target, String methodName, int[] args,
                                  HirCall expr, MirBuilder builder, SourceLocation loc) {
        // data class copy() 命名参数处理：a.copy(qty = 10)
        if ("copy".equals(methodName) && expr.hasNamedArgs()) {
            MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(target).getType() : null;
            String owner = targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                    ? targetType.getClassName() : null;
            if (owner != null && isDataClass(owner)) {
                HirFunction ctor = classConstructorDecls.get(owner);
                if (ctor != null) {
                    Map<String, Expression> named = expr.getNamedArgs();
                    int[] copyArgs = new int[ctor.getParams().size()];
                    for (int i = 0; i < ctor.getParams().size(); i++) {
                        String paramName = ctor.getParams().get(i).getName();
                        if (named.containsKey(paramName)) {
                            copyArgs[i] = lowerExpr(named.get(paramName), builder);
                        } else {
                            // 从 receiver 获取当前字段值
                            copyArgs[i] = builder.emitGetField(target, paramName,
                                    MirType.ofObject("java/lang/Object"), loc);
                        }
                    }
                    String desc = MethodDescriptor.allObjectDesc(copyArgs.length);
                    return builder.emitInvokeVirtualDesc(target, "copy", copyArgs,
                            owner, desc, MirType.ofObject(owner), loc);
                }
            }
        }

        // 通用命名参数 fallback：编码命名参数名到 extra，运行时由 MirInterpreter 解析
        if (expr.hasNamedArgs()) {
            Map<String, Expression> named = expr.getNamedArgs();
            int[] namedVals = new int[named.size()];
            StringBuilder nameList = new StringBuilder();
            int idx = 0;
            for (Map.Entry<String, Expression> e : named.entrySet()) {
                if (idx > 0) nameList.append(",");
                nameList.append(e.getKey());
                namedVals[idx++] = lowerExpr(e.getValue(), builder);
            }
            int[] allArgs = new int[args.length + namedVals.length];
            System.arraycopy(args, 0, allArgs, 0, args.length);
            System.arraycopy(namedVals, 0, allArgs, args.length, namedVals.length);
            // extra 格式: "methodName;named:positionalCount:key1,key2"
            String extra = methodName + ";named:" + args.length + ":" + nameList;
            return builder.emitInvokeVirtual(target, extra, allArgs,
                    MirType.ofObject("java/lang/Object"), loc);
        }

        return -1;
    }

    /** componentN() 解构辅助。返回 -1 表示不匹配 */
    private int tryComponentNCall(int target, String methodName, int[] args,
                                  MirBuilder builder, SourceLocation loc) {
        if (args.length == 0 && methodName.startsWith("component") && methodName.length() > 9) {
            try {
                int n = Integer.parseInt(methodName.substring(9));
                if (n >= 1) {
                    // 推断 target 类型：如果是已知 Nova 类，走正常方法调用（@data 类自带 componentN）
                    MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                            ? builder.getFunction().getLocals().get(target).getType() : null;
                    String owner = targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                            ? targetType.getClassName() : null;
                    if (owner == null || !classNames.contains(owner)) {
                        int nConst = builder.emitConstInt(n, loc);
                        return builder.emitInvokeStatic(
                                "com/novalang/runtime/NovaCollections|componentN|(Ljava/lang/Object;I)Ljava/lang/Object;",
                                new int[]{target, nConst}, MirType.ofObject("java/lang/Object"), loc);
                    }
                }
            } catch (NumberFormatException ignored) {
                // 不是 componentN 模式，走正常方法调用
            }
        }
        return -1;
    }

    /** 标准方法调用: Java反射/Stdlib/集合HOF/扩展/接口默认/虚调用 */
    private int lowerStandardMethodCall(int target, String methodName, int[] args,
                                        MirBuilder builder, SourceLocation loc) {
        // 推断 owner 从 target 的类型
        MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                ? builder.getFunction().getLocals().get(target).getType()
                : MirType.ofObject("java/lang/Object");
        String owner;
        if (targetType.getKind() == MirType.Kind.OBJECT && targetType.getClassName() != null) {
            owner = targetType.getClassName();
        } else {
            owner = primitiveToBoxedOwner(targetType);
        }

        // 标准 Java 方法使用标准签名
        String desc;
        MirType returnType;
        MethodSemantics.IntrinsicVirtualMethod intrinsic =
                MethodSemantics.resolveIntrinsicVirtual(methodName, args.length);
        if (intrinsic != null) {
            desc = intrinsic.getDescriptor();
            returnType = descriptorToMirType(desc.substring(desc.indexOf(')') + 1));
        } else {
            // StdlibRegistry 扩展方法路由（优先于 Java 反射，避免 String.lines 等被 Java 方法截获）
            Class<?> ownerClass = resolveJavaClass(owner);
            {
                StdlibRegistry.ExtensionMethodInfo stdlibExt = ownerClass != null
                        ? StdlibMethodResolver.resolveByClass(ownerClass, methodName, args.length)
                        : StdlibMethodResolver.resolveByOwner(owner, methodName, args.length);
                if (stdlibExt != null) {
                    return emitStdlibExtensionCall(target, stdlibExt, args, builder, loc);
                }
            }
            // 尝试 Java 反射解析方法描述符
            java.lang.reflect.Method javaMethod = ownerClass != null
                    ? findJavaMethod(ownerClass, methodName, args.length) : null;
            if (javaMethod != null) {
                desc = buildJavaMethodDesc(javaMethod);
                returnType = javaReturnTypeToMir(javaMethod.getReturnType());
                boolean isInterface = ownerClass.isInterface();
                if (isInterface) {
                    return builder.emitInvokeInterfaceDesc(target, methodName, args,
                            owner, desc, returnType, loc);
                }
                return builder.emitInvokeVirtualDesc(target, methodName, args,
                        owner, desc, returnType, loc);
            }
            // 集合高阶函数路由: list.filter/map/forEach → INVOKESTATIC CollectionOps
            // （Java 反射未找到方法时才走此路径）
            if (isCollectionHOF(methodName, args.length)) {
                return emitCollectionOpsCall(target, methodName, args, builder, loc);
            }
            // 扩展函数路由: i.double() → INVOKESTATIC Bench.double(i)
            if (extensionMethods.containsKey(methodName)) {
                return emitExtensionCall(target, methodName, args, builder, loc);
            }
            // 作用域函数路由: obj.let/also/run/apply/takeIf/takeUnless → INVOKESTATIC NovaScopeFunctions
            // 仅当方法未在 Nova 类继承链中注册时才路由（避免拦截用户自定义同名方法）
            if (MethodSemantics.isScopeFunction(methodName, args.length)
                    && lookupNovaMethodDescInherited(owner, methodName) == null) {
                return emitScopeFunctionCall(target, methodName, args, builder, loc);
            }
            // Java 类上方法未找到 → 回退动态分派（运行时扩展方法等）
            if (ownerClass != null && lookupNovaMethodDescInherited(owner, methodName) == null) {
                return emitDynamicInvoke(target, methodName, args, builder, loc);
            }
            // 字段调用: 字段存储的 lambda/函数 → GETFIELD + INVOKEINTERFACE FunctionN.invoke
            Set<String> ownerFields = classFieldNames.get(owner);
            Map<String, String> ownerMethodDescs = novaMethodDescs.get(owner);
            if (ownerFields != null && ownerFields.contains(methodName)
                    && (ownerMethodDescs == null || !ownerMethodDescs.containsKey(methodName))) {
                int fieldVal = builder.emitGetField(target, methodName,
                        MirType.ofObject("java/lang/Object"), loc);
                String funcInterface;
                switch (args.length) {
                    case 0:  funcInterface = "com/novalang/runtime/Function0"; break;
                    case 1:  funcInterface = "com/novalang/runtime/Function1"; break;
                    case 2:  funcInterface = "com/novalang/runtime/Function2"; break;
                    case 3:  funcInterface = "com/novalang/runtime/Function3"; break;
                    default:
                        return emitLambdaInvokerCall(fieldVal, args, builder, loc);
                }
                String funcDesc = MethodDescriptor.allObjectDesc(args.length);
                return builder.emitInvokeInterfaceDesc(fieldVal, "invoke", args,
                        funcInterface, funcDesc,
                        MirType.ofObject("java/lang/Object"), loc);
            }
            // 尝试 Nova 类/接口方法描述符注册表（沿继承链查找）
            desc = lookupNovaMethodDesc(owner, methodName, args.length);
            String retDescStr = desc.substring(desc.indexOf(')') + 1);
            returnType = inferReturnType(retDescStr, owner);
            // build() 返回原始类型（去掉 $Builder 后缀）
            if ("build".equals(methodName) && owner.endsWith("$Builder")) {
                returnType = MirType.ofObject(
                        owner.substring(0, owner.length() - "$Builder".length()));
            }
        }

        // Nova 接口 → INVOKEINTERFACE
        if (interfaceNames.contains(owner)) {
            return builder.emitInvokeInterfaceDesc(target, methodName, args,
                    owner, desc, returnType, loc);
        }
        // 接口默认方法: 方法未在类自身找到，检查其接口
        {
            Map<String, String> ownerDescs = novaMethodDescs.get(owner);
            boolean methodInOwn = ownerDescs != null && ownerDescs.containsKey(methodName);
            if (!methodInOwn) {
                // 沿继承链查找是否在祖先类中定义
                String ancestor = classSuperClass.get(owner);
                while (ancestor != null && !methodInOwn) {
                    Map<String, String> ad = novaMethodDescs.get(ancestor);
                    if (ad != null && ad.containsKey(methodName)) methodInOwn = true;
                    ancestor = classSuperClass.get(ancestor);
                }
            }
            if (!methodInOwn) {
                String ifaceOwner = findMethodInInterfaces(owner, methodName);
                if (ifaceOwner != null) {
                    desc = lookupNovaMethodDesc(ifaceOwner, methodName, args.length);
                    String retDescStr2 = desc.substring(desc.indexOf(')') + 1);
                    returnType = inferReturnType(retDescStr2, ifaceOwner);
                    return builder.emitInvokeInterfaceDesc(target, methodName, args,
                            ifaceOwner, desc, returnType, loc);
                }
            }
        }

        // owner 为 java/lang/Object 且不是已知 Nova 类方法 → 动态分派
        if ("java/lang/Object".equals(owner)) {
            return emitDynamicInvoke(target, methodName, args, builder, loc);
        }

        return builder.emitInvokeVirtualDesc(target, methodName, args,
                owner, desc, returnType, loc);
    }

    /**
     * 未知类型对象的动态方法调用。
     * <ul>
     *   <li>MirInterpreter：executeInvokeDynamic → bootstrapInvoke 分派</li>
     *   <li>编译模式：INVOKE_DYNAMIC（通过 NovaBootstrap 的 CallSite 分派）</li>
     * </ul>
     */
    private int emitDynamicInvoke(int target, String methodName, int[] args,
                                  MirBuilder builder, SourceLocation loc) {
        // 统一生成 INVOKE_DYNAMIC（MirInterpreter 通过 executeInvokeDynamic 分派，MirCodeGenerator 生成 invokedynamic 字节码）
        int[] allOps = new int[args.length + 1];
        allOps[0] = target;
        System.arraycopy(args, 0, allOps, 1, args.length);

        StringBuilder descBuilder = new StringBuilder("(");
        for (int i = 0; i <= args.length; i++) {
            descBuilder.append("Ljava/lang/Object;");
        }
        descBuilder.append(")Ljava/lang/Object;");

        InvokeDynamicInfo info = new InvokeDynamicInfo(
                methodName,
                "com/novalang/runtime/NovaBootstrap",
                "bootstrapInvoke",
                descBuilder.toString()
        );

        return builder.emitInvokeDynamic(info, allOps,
                MirType.ofObject("java/lang/Object"), loc);
    }

    /**
     * 类名上的静态方法调用（如 Config.builder()）→ INVOKESTATIC
     */
    private int lowerStaticMethodCall(String className, String methodName,
                                       HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        int[] args = lowerArgs(expr.getArgs(), builder);
        Map<String, String> classDescs = novaMethodDescs.get(className);
        String desc;
        if (classDescs != null && classDescs.containsKey(methodName)) {
            desc = classDescs.get(methodName);
        } else {
            desc = MethodDescriptor.allObjectDesc(args.length);
        }
        String extra = className + "|" + methodName + "|" + desc;

        // 推断返回类型
        String retDescStr = desc.substring(desc.indexOf(')') + 1);
        MirType returnType = inferReturnType(retDescStr, className);
        if ("builder".equals(methodName)) {
            returnType = MirType.ofObject(className + "$Builder");
        }

        return builder.emitInvokeStatic(extra, args, returnType, loc);
    }

    /**
     * object 单例方法调用：GETSTATIC INSTANCE + INVOKEVIRTUAL
     */
    private int lowerObjectMethodCall(String className, String methodName,
                                       HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        // GETSTATIC className.INSTANCE
        int instance = builder.emitGetStatic(className, "INSTANCE",
                "L" + className + ";", MirType.ofObject(className), loc);
        int[] args = lowerArgs(expr.getArgs(), builder);
        // 查找方法描述符
        Map<String, String> classDescs = novaMethodDescs.get(className);
        String desc;
        if (classDescs != null && classDescs.containsKey(methodName)) {
            desc = classDescs.get(methodName);
        } else {
            desc = MethodDescriptor.allObjectDesc(args.length);
        }
        String retDescStr = desc.substring(desc.indexOf(')') + 1);
        MirType returnType = inferReturnType(retDescStr, className);
        return builder.emitInvokeVirtualDesc(instance, methodName, args,
                className, desc, returnType, loc);
    }

    /**
     * println/print → System.out.println(Object) / System.out.print(Object)
     */
    /**
     * 部分应用: fun(arg1, _, arg3) → NovaPartialApplication
     * 生成 INVOKE_STATIC "$PartialApplication|mask" 指令，MirInterpreter 拦截处理。
     */
    private int lowerPartialApplicationCall(HirCall expr, MirBuilder builder) {
        int callee = lowerExpr(expr.getCallee(), builder);
        int mask = 0;
        int[] args = new int[expr.getArgs().size()];
        for (int i = 0; i < args.length; i++) {
            Expression arg = expr.getArgs().get(i);
            if (arg instanceof Identifier && "_".equals(((Identifier) arg).getName())) {
                mask |= (1 << i);
                args[i] = builder.emitConstNull(arg.getLocation());
            } else {
                args[i] = lowerExpr(arg, builder);
            }
        }
        int[] allArgs = new int[args.length + 1];
        allArgs[0] = callee;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        return builder.emitInvokeStatic("$PartialApplication|" + mask, allArgs,
                MirType.ofObject("java/lang/Object"), expr.getLocation());
    }

    private int lowerPrintCall(String name, HirCall expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        String owner = "com/novalang/runtime/NovaPrint";
        if (expr.getArgs().isEmpty()) {
            // println() 无参
            return builder.emitInvokeStatic(owner + "|println|()V",
                    new int[0], MirType.ofVoid(), loc);
        } else if (expr.getArgs().size() == 1) {
            // println(value) / print(value) 单参
            int[] args = lowerArgs(expr.getArgs(), builder);
            return builder.emitInvokeStatic(owner + "|" + name + "|(Ljava/lang/Object;)V",
                    args, MirType.ofVoid(), loc);
        } else {
            // println(a, b, c) 多参 → varargs
            int sizeConst = builder.emitConstInt(expr.getArgs().size(), loc);
            int arrLocal = builder.emitNewArray(sizeConst, loc);
            for (int i = 0; i < expr.getArgs().size(); i++) {
                int argVal = lowerExpr(expr.getArgs().get(i), builder);
                int idxConst = builder.emitConstInt(i, loc);
                builder.emitIndexSet(arrLocal, idxConst, argVal, loc);
            }
            String varargMethod = "println".equals(name) ? "printlnVarargs" : "printVarargs";
            return builder.emitInvokeStatic(
                    owner + "|" + varargMethod + "|([Ljava/lang/Object;)V",
                    new int[]{arrLocal}, MirType.ofVoid(), loc);
        }
    }

    private int lowerFieldAccess(MemberExpr expr, MirBuilder builder) {
        // 如果 target 是类名引用（不是局部变量），使用 GETSTATIC
        if (expr.getTarget() instanceof Identifier) {
            String targetName = ((Identifier) expr.getTarget()).getName();
            // javaClass() 常量传播：val Integer = javaClass("java.lang.Integer")
            // → Integer.MAX_VALUE 编译为 GETSTATIC java/lang/Integer.MAX_VALUE
            String jcClass = javaClassBindings.get(targetName);
            if (jcClass != null) {
                String fieldName = expr.getMember();
                // 尝试反射确认静态字段存在
                Class<?> cls = resolveJavaClass(jcClass);
                if (cls != null) {
                    try {
                        java.lang.reflect.Field f = cls.getField(fieldName);
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                            String desc = classToDescriptor(f.getType());
                            MirType retType = descriptorToMirType(desc);
                            return builder.emitGetStatic(jcClass, fieldName, desc, retType,
                                    expr.getLocation());
                        }
                    } catch (NoSuchFieldException ignored) {}
                    // 非静态字段或不存在 → 尝试无参方法
                    java.lang.reflect.Method m = findJavaMethod(cls, fieldName, 0);
                    if (m != null && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                        String desc = buildJavaMethodDescriptor(m);
                        String retDesc = desc.substring(desc.indexOf(')') + 1);
                        MirType retType = descriptorToMirType(retDesc);
                        String extra = jcClass + "|" + fieldName + "|" + desc;
                        return builder.emitInvokeStatic(extra, new int[0], retType, expr.getLocation());
                    }
                }
            }
            if (classNames.contains(targetName)) {
                // 拦截 ClassName.annotations → 内联构建注解列表
                if ("annotations".equals(expr.getMember())) {
                    return lowerClassAnnotationsAccess(targetName, builder, expr.getLocation());
                }
                // object 单例：通过 INSTANCE 访问实例字段
                if (objectNames.contains(targetName)) {
                    int instance = builder.emitGetStatic(targetName, "INSTANCE",
                            "L" + targetName + ";", MirType.ofObject(targetName),
                            expr.getLocation());
                    return builder.emitGetField(instance, expr.getMember(),
                            MirType.ofObject("java/lang/Object"), expr.getLocation());
                }
                // 枚举条目/静态字段：GETSTATIC
                MirType staticFieldType = enumClassNames.contains(targetName)
                        ? MirType.ofObject(targetName) : MirType.ofObject("java/lang/Object");
                return builder.emitGetStatic(targetName, expr.getMember(),
                        MethodDescriptor.OBJECT_DESC, staticFieldType,
                        expr.getLocation());
            }
        }
        int target = lowerExpr(expr.getTarget(), builder);
        String fieldName = expr.getMember();

        // 对 Java 对象类型：如果字段实际上是无参方法，编译为 INVOKEVIRTUAL
        MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                ? builder.getFunction().getLocals().get(target).getType() : null;

        // 数组类型 size/length → GET_FIELD（MirCodeGenerator 会编译为 ARRAYLENGTH）
        if (targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                && targetType.getClassName() != null && targetType.getClassName().startsWith("[")
                && ("size".equals(fieldName) || "length".equals(fieldName))) {
            return builder.emitGetField(target, fieldName,
                    MirType.ofInt(), expr.getLocation());
        }

        if (targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                && targetType.getClassName() != null) {
            String owner = targetType.getClassName();
            Class<?> cls = resolveJavaClass(owner);
            if (cls != null) {
                // 1) 同名无参方法（如 size()）
                java.lang.reflect.Method m = findJavaMethod(cls, fieldName, 0);
                // 2) JavaBean getter: value → getValue(), error → getError()
                if (m == null) {
                    String getter = "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                    m = findJavaMethod(cls, getter, 0);
                    if (m == null) {
                        // 3) boolean getter: ok → isOk()
                        String isGetter = "is" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                        m = findJavaMethod(cls, isGetter, 0);
                    }
                }
                if (m != null) {
                    String desc = buildJavaMethodDescriptor(m);
                    String retDesc = desc.substring(desc.indexOf(')') + 1);
                    MirType retType = descriptorToMirType(retDesc);
                    return builder.emitInvokeVirtualDesc(target, m.getName(), new int[0],
                            owner, desc, retType, expr.getLocation());
                }
            }
        }

        // Nova 类的字段：优先检查自定义 getter
        if (targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                && targetType.getClassName() != null
                && classNames.contains(targetType.getClassName())) {
            String owner = targetType.getClassName();
            if (customGetters.contains(owner + ":" + fieldName)) {
                return builder.emitInvokeVirtualDesc(target, "get$" + fieldName, new int[0],
                        owner, "()Ljava/lang/Object;",
                        MirType.ofObject("java/lang/Object"), expr.getLocation());
            }
            // 字段在声明类型上存在 → 静态 GETFIELD
            // 不存在（如 when is Rect → s.w, s 类型为 Shape）→ 回退到动态解析
            Set<String> ownerFields = classFieldNames.get(owner);
            if (ownerFields != null && ownerFields.contains(fieldName)) {
                return builder.emitGetField(target, fieldName,
                        MirType.ofObject("java/lang/Object"), expr.getLocation());
            }
            // 检查继承链上的父类字段
            String parent = classSuperClass.get(owner);
            while (parent != null) {
                Set<String> parentFields = classFieldNames.get(parent);
                if (parentFields != null && parentFields.contains(fieldName)) {
                    return builder.emitGetField(target, fieldName,
                            MirType.ofObject("java/lang/Object"), expr.getLocation());
                }
                parent = classSuperClass.get(parent);
            }
            // 字段不在声明类型及其父类上 → 回退到下方的动态解析路径
        }
        // 未知类型 → 动态成员解析（invokedynamic bootstrapGetMember）
        InvokeDynamicInfo getInfo = new InvokeDynamicInfo(
                fieldName, "com/novalang/runtime/NovaBootstrap", "bootstrapGetMember",
                "(Ljava/lang/Object;)Ljava/lang/Object;");
        return builder.emitInvokeDynamic(getInfo, new int[]{target},
                MirType.ofObject("java/lang/Object"), expr.getLocation());
    }

    /**
     * 内联生成 ClassName.annotations 的注解列表。
     * 返回一个 ArrayList，每个元素为 LinkedHashMap{name: String, args: LinkedHashMap}。
     */
    private int lowerClassAnnotationsAccess(String className, MirBuilder builder, SourceLocation loc) {
        List<HirAnnotation> annotations = classAnnotationData.getOrDefault(className, Collections.emptyList());

        // new ArrayList()
        int list = builder.emitNewObject("java/util/ArrayList", new int[0], loc);

        for (HirAnnotation ann : annotations) {
            // new LinkedHashMap()
            int map = builder.emitNewObject("java/util/LinkedHashMap", new int[0], loc);

            // map.put("name", annotationName)
            int nameKey = builder.emitConstString("name", loc);
            int nameVal = builder.emitConstString(ann.getName(), loc);
            builder.emitInvokeVirtualDesc(map, "put", new int[]{nameKey, nameVal},
                    "java/util/LinkedHashMap",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    MirType.ofObject("java/lang/Object"), loc);

            // new LinkedHashMap() for args
            int argsMap = builder.emitNewObject("java/util/LinkedHashMap", new int[0], loc);
            for (Map.Entry<String, Expression> entry : ann.getArgs().entrySet()) {
                int argKey = builder.emitConstString(entry.getKey(), loc);
                int argVal = lowerExpr(entry.getValue(), builder);
                builder.emitInvokeVirtualDesc(argsMap, "put", new int[]{argKey, argVal},
                        "java/util/LinkedHashMap",
                        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                        MirType.ofObject("java/lang/Object"), loc);
            }

            // map.put("args", argsMap)
            int argsKey = builder.emitConstString("args", loc);
            builder.emitInvokeVirtualDesc(map, "put", new int[]{argsKey, argsMap},
                    "java/util/LinkedHashMap",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                    MirType.ofObject("java/lang/Object"), loc);

            // list.add(map)
            builder.emitInvokeVirtualDesc(list, "add", new int[]{map},
                    "java/util/ArrayList", "(Ljava/lang/Object;)Z",
                    MirType.ofInt(), loc);
        }

        return list;
    }

    private int lowerIndex(IndexExpr expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();
        int target = lowerExpr(expr.getTarget(), builder);

        // Range 切片：target[start..end] → INDEX_GET + NovaRange
        if (expr.getIndex() instanceof RangeExpr) {
            int rangeIndex = lowerExpr(expr.getIndex(), builder);
            return builder.emitIndexGet(target, rangeIndex,
                    MirType.ofObject("java/lang/Object"), loc);
        }

        int index = lowerExpr(expr.getIndex(), builder);

        // 运算符重载: Nova 类的 get() 方法
        MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                ? builder.getFunction().getLocals().get(target).getType() : null;
        if (targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                && targetType.getClassName() != null
                && classNames.contains(targetType.getClassName())
                && hasNovaMethod(targetType.getClassName(), "get")) {
            String owner = targetType.getClassName();
            String desc = lookupNovaMethodDesc(owner, "get", 1);
            MirType retType = inferReturnType(desc.substring(desc.indexOf(')') + 1), owner);
            return builder.emitInvokeVirtualDesc(target, "get", new int[]{index},
                    owner, desc, retType, loc);
        }

        // int[] 数组元素类型为 INT
        MirType resultType = MirType.ofObject("java/lang/Object");
        List<MirLocal> locals = builder.getFunction().getLocals();
        if (target >= 0 && target < locals.size()) {
            MirType tt = locals.get(target).getType();
            if ("[I".equals(tt.getClassName())) {
                resultType = MirType.ofInt();
            }
        }
        return builder.emitIndexGet(target, index, resultType, loc);
    }

    private int lowerAssign(AssignExpr expr, MirBuilder builder) {
        int value = lowerExpr(expr.getValue(), builder);
        // 如果目标是已知局部变量，直接赋值到该变量（逆序查找，与 lowerVarRef 保持一致）
        if (expr.getTarget() instanceof Identifier) {
            String varName = ((Identifier) expr.getTarget()).getName();
            // 装箱可变捕获：写入 Object[] box
            Integer boxLocal = boxedMutableCaptures.get(varName);
            if (boxLocal != null) {
                List<MirLocal> locals = builder.getFunction().getLocals();
                // 外部作用域：box 局部变量在当前 builder 中
                if (boxLocal < locals.size()
                        && locals.get(boxLocal).getName().equals(varName + "$box")) {
                    int zeroConst = builder.emitConstInt(0, expr.getLocation());
                    builder.emitIndexSet(boxLocal, zeroConst, value, expr.getLocation());
                    // 顶层 var + boxing: 同步到 $Module 静态字段 + ScriptContext
                    if (topLevelFieldNames.containsKey(varName)
                            && !Boolean.TRUE.equals(topLevelFieldNames.get(varName))) {
                        builder.emitPutStatic(moduleClassName, varName,
                                "Ljava/lang/Object;", value, expr.getLocation());
                        emitScriptContextSet(builder, varName, value, expr.getLocation());
                    }
                    return value;
                }
                // lambda 内部：通过 this.field 访问 Object[] box
                for (MirLocal local : locals) {
                    if ("this".equals(local.getName())) {
                        int fieldVal = builder.emitGetField(local.getIndex(), varName,
                                MirType.ofObject("java/lang/Object"), expr.getLocation());
                        int zeroConst = builder.emitConstInt(0, expr.getLocation());
                        builder.emitIndexSet(fieldVal, zeroConst, value, expr.getLocation());
                        // 顶层 var + boxing: 同步到 $Module 静态字段 + ScriptContext
                        if (topLevelFieldNames.containsKey(varName)
                                && !Boolean.TRUE.equals(topLevelFieldNames.get(varName))) {
                            builder.emitPutStatic(moduleClassName, varName,
                                    "Ljava/lang/Object;", value, expr.getLocation());
                            emitScriptContextSet(builder, varName, value, expr.getLocation());
                        }
                        return value;
                    }
                }
            }
            List<MirLocal> locals = builder.getFunction().getLocals();
            for (int i = locals.size() - 1; i >= 0; i--) {
                if (locals.get(i).getName().equals(varName)) {
                    builder.emitMoveTo(value, locals.get(i).getIndex(), expr.getLocation());
                    // 顶层 var → 同步写入 $Module 静态字段 + ScriptContext（REPL 兼容）
                    if (topLevelFieldNames.containsKey(varName)
                            && !Boolean.TRUE.equals(topLevelFieldNames.get(varName))
                            && !boxedMutableCaptures.containsKey(varName)) {
                        builder.emitPutStatic(moduleClassName, varName,
                                "Ljava/lang/Object;", value, expr.getLocation());
                        emitScriptContextSet(builder, varName, value, expr.getLocation());
                    }
                    return locals.get(i).getIndex();
                }
            }
            // 顶层 var → PUTSTATIC（从 lambda/顶层函数内赋值，无同名局部变量）
            if (topLevelFieldNames.containsKey(varName)
                    && !Boolean.TRUE.equals(topLevelFieldNames.get(varName))) {
                builder.emitPutStatic(moduleClassName, varName,
                        "Ljava/lang/Object;", value, expr.getLocation());
                emitScriptContextSet(builder, varName, value, expr.getLocation());
                return value;
            }
            // 隐式 this 字段赋值
            for (MirLocal local : locals) {
                if ("this".equals(local.getName())) {
                    MirType thisType = local.getType();
                    if (thisType.getKind() == MirType.Kind.OBJECT && thisType.getClassName() != null) {
                        // 检查是否真的是类字段，避免将外部变量赋值误当成 this.field
                        // 跳过: Lambda 类（捕获字段不在 classFieldNames）、$Module 容器类
                        if (!thisType.getClassName().contains("$Lambda$")
                                && !thisType.getClassName().equals(moduleClassName)
                                && classFieldNames.containsKey(thisType.getClassName())) {
                            Set<String> fields = classFieldNames.get(thisType.getClassName());
                            if (fields == null || !fields.contains(varName)) {
                                break; // 不是类字段 → 走 NovaScriptContext.set 路径
                            }
                        }
                        // 自定义 setter → 调用 set$fieldName(value)
                        if (customSetters.contains(thisType.getClassName() + ":" + varName)) {
                            builder.emitInvokeVirtualDesc(local.getIndex(), "set$" + varName,
                                    new int[]{value}, thisType.getClassName(),
                                    "(Ljava/lang/Object;)V", MirType.ofVoid(), expr.getLocation());
                            return value;
                        }
                        // 普通字段 → SET_FIELD
                        builder.emitSetField(local.getIndex(), varName, value, expr.getLocation());
                        return value;
                    }
                    break;
                }
            }
            // 顶层 var → PUTSTATIC + ScriptContext（从顶层函数内赋值的情况）
            if (topLevelFieldNames.containsKey(varName)
                    && !Boolean.TRUE.equals(topLevelFieldNames.get(varName))) {
                builder.emitPutStatic(moduleClassName, varName,
                        "Ljava/lang/Object;", value, expr.getLocation());
                emitScriptContextSet(builder, varName, value, expr.getLocation());
                return value;
            }
            // 脚本模式: 外部变量赋值 → NovaScriptContext.set(name, value)
            if (scriptMode) {
                int nameConst = builder.emitConstString(varName, expr.getLocation());
                builder.emitInvokeStatic(
                        scriptContextOwner() + "|set|(Ljava/lang/String;Ljava/lang/Object;)V",
                        new int[]{nameConst, value},
                        MirType.ofVoid(), expr.getLocation());
                return value;
            }
        }
        // 字段赋值: obj.field = value
        if (expr.getTarget() instanceof MemberExpr) {
            MemberExpr fa = (MemberExpr) expr.getTarget();
            int target = lowerExpr(fa.getTarget(), builder);
            String fieldName = fa.getMember();
            MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(target).getType() : null;
            if (targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                    && targetType.getClassName() != null) {
                String owner = targetType.getClassName();
                // 自定义 setter → 调用 set$fieldName(value)
                if (customSetters.contains(owner + ":" + fieldName)) {
                    builder.emitInvokeVirtualDesc(target, "set$" + fieldName, new int[]{value},
                            owner, "(Ljava/lang/Object;)V",
                            MirType.ofVoid(), expr.getLocation());
                    return value;
                }
                // 普通字段 → SET_FIELD
                if (classNames.contains(owner)) {
                    builder.emitSetField(target, fieldName, value, expr.getLocation());
                    return value;
                }
            }
            // 未知类型 → 动态成员赋值（invokedynamic bootstrapSetMember）
            InvokeDynamicInfo setInfo = new InvokeDynamicInfo(
                    fieldName, "com/novalang/runtime/NovaBootstrap", "bootstrapSetMember",
                    "(Ljava/lang/Object;Ljava/lang/Object;)V");
            builder.emitInvokeDynamic(setInfo, new int[]{target, value},
                    MirType.ofVoid(), expr.getLocation());
            return value;
        }
        // 索引赋值: target[index] = value
        if (expr.getTarget() instanceof IndexExpr) {
            IndexExpr idx = (IndexExpr) expr.getTarget();
            int target = lowerExpr(idx.getTarget(), builder);
            int index = lowerExpr(idx.getIndex(), builder);
            // 运算符重载: Nova 类的 set() 方法
            MirType targetType = target >= 0 && target < builder.getFunction().getLocals().size()
                    ? builder.getFunction().getLocals().get(target).getType() : null;
            if (targetType != null && targetType.getKind() == MirType.Kind.OBJECT
                    && targetType.getClassName() != null
                    && classNames.contains(targetType.getClassName())
                    && hasNovaMethod(targetType.getClassName(), "set")) {
                String owner = targetType.getClassName();
                String desc = lookupNovaMethodDesc(owner, "set", 2);
                builder.emitInvokeVirtualDesc(target, "set", new int[]{index, value},
                        owner, desc, MirType.ofVoid(), expr.getLocation());
                return value;
            }
            builder.emitIndexSet(target, index, value, expr.getLocation());
            return value;
        }
        return builder.emitMove(value, MirType.ofObject("java/lang/Object"), expr.getLocation());
    }

    private int lowerTypeCheck(TypeCheckExpr expr, MirBuilder builder) {
        int operand = lowerExpr(expr.getOperand(), builder);
        String typeName = typeNameForRuntimeCheck(expr.getHirTargetType());
        int result = builder.emitTypeCheck(operand, typeName, expr.getLocation());
        if (expr.isNegated()) {
            result = builder.emitUnary(UnaryOp.NOT, result, MirType.ofBoolean(), expr.getLocation());
        }
        return result;
    }

    private int lowerTypeCast(TypeCastExpr expr, MirBuilder builder) {
        int operand = lowerExpr(expr.getOperand(), builder);
        String typeName = typeNameForRuntimeCheck(expr.getHirTargetType());
        // 安全转换 as? → 前缀 "?|" 标记，运行时非匹配返回 null
        if (expr.isSafe()) {
            typeName = "?|" + typeName;
        }
        return builder.emitTypeCast(operand, typeName,
                hirTypeToMir(expr.getHirTargetType()), expr.getLocation());
    }

    /** 类型检查/转换用的类型名：保留 reified 类型参数原名，其余走 typeToInternalName */
    private String typeNameForRuntimeCheck(HirType type) {
        if (type instanceof ClassType) {
            String name = ((ClassType) type).getName();
            if (currentFunctionTypeParams.contains(name)) {
                return name; // 保留类型参数名，运行时通过 reifiedTypes 解析
            }
        }
        return typeToInternalName(type);
    }

    private int lowerNew(HirNew expr, MirBuilder builder) {
        int[] args = lowerArgs(expr.getArgs(), builder);
        return builder.emitNewObject(expr.getClassName(), args, expr.getLocation());
    }

    private int lowerCollectionLiteral(HirCollectionLiteral expr, MirBuilder builder) {
        SourceLocation loc = expr.getLocation();

        if (expr.getKind() == HirCollectionLiteral.Kind.MAP) {
            // HashMap + put(key, value) for each entry
            int map = builder.emitNewObject("java/util/HashMap", new int[0], loc);
            for (Expression element : expr.getElements()) {
                if (element instanceof BinaryExpr && ((BinaryExpr) element).getOperator() == BinaryExpr.BinaryOp.TO) {
                    BinaryExpr pair = (BinaryExpr) element;
                    int key = lowerExpr(pair.getLeft(), builder);
                    int value = lowerExpr(pair.getRight(), builder);
                    builder.emitInvokeVirtualDesc(map, "put", new int[]{key, value},
                            "java/util/HashMap",
                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                            MirType.ofObject("java/lang/Object"), loc);
                }
            }
            return map;
        }

        boolean isSet = expr.getKind() == HirCollectionLiteral.Kind.SET;
        String collClass = isSet ? "java/util/LinkedHashSet" : "java/util/ArrayList";
        int coll = builder.emitNewObject(collClass, new int[0], loc);
        List<Expression> elements = expr.getElements();
        for (int i = 0; i < elements.size(); i++) {
            int elem = lowerExpr(elements.get(i), builder);
            if (expr.isSpread(i)) {
                builder.emitInvokeVirtualDesc(coll, "addAll", new int[]{elem},
                        collClass, "(Ljava/util/Collection;)Z",
                        MirType.ofBoolean(), loc);
            } else {
                builder.emitInvokeVirtualDesc(coll, "add", new int[]{elem},
                        collClass, "(Ljava/lang/Object;)Z",
                        MirType.ofBoolean(), loc);
            }
        }
        return coll;
    }

    // ========== 类型转换辅助 ==========

    /** HirType → 显示名（用于 reified 类型参数编码） */
    private static String hirTypeDisplayName(HirType type) {
        if (type instanceof ClassType) return ((ClassType) type).getName();
        if (type instanceof PrimitiveType) {
            switch (((PrimitiveType) type).getKind()) {
                case INT: return "Int"; case LONG: return "Long";
                case FLOAT: return "Float"; case DOUBLE: return "Double";
                case BOOLEAN: return "Boolean"; case CHAR: return "Char";
                default: return ((PrimitiveType) type).getKind().name();
            }
        }
        return type.toString();
    }

    private static String primitiveKindToArrayDesc(PrimitiveType.Kind kind) {
        switch (kind) {
            case INT:     return "[I";
            case LONG:    return "[J";
            case DOUBLE:  return "[D";
            case FLOAT:   return "[F";
            case BOOLEAN: return "[Z";
            case CHAR:    return "[C";
            default:      return null;
        }
    }

    private MirType hirTypeToMir(HirType type) {
        if (type == null) return MirType.ofObject("java/lang/Object");
        if (type instanceof PrimitiveType) {
            // 可空原始类型（Int?, Long? 等）→ Object，因为 JVM 原始类型不能为 null
            if (type.isNullable()) return MirType.ofObject("java/lang/Object");
            switch (((PrimitiveType) type).getKind()) {
                case INT: return MirType.ofInt();
                case LONG: return MirType.ofLong();
                case FLOAT: return MirType.ofFloat();
                case DOUBLE: return MirType.ofDouble();
                case BOOLEAN: return MirType.ofBoolean();
                case CHAR: return MirType.ofChar();
                case UNIT: return MirType.ofVoid();
                case NOTHING: return MirType.ofVoid();
            }
        }
        if (type instanceof ClassType) {
            String name = ((ClassType) type).getName().replace('.', '/');
            // typealias 展开
            HirType aliasTarget = name.contains("/") ? null : typeAliasMap.get(name);
            if (aliasTarget != null) return hirTypeToMir(aliasTarget);
            // nova.Xxx 限定名 → 内置类型
            if (name.startsWith("nova/") && !name.substring(5).contains("/")) {
                String simpleName = name.substring(5);
                String boxed = NovaTypeNames.toBoxedInternalName(simpleName);
                if (boxed != null) return MirType.ofObject(boxed);
            }
            if (!name.contains("/")) {
                String boxed = NovaTypeNames.toBoxedInternalName(name);
                if (boxed != null) return MirType.ofObject(boxed);
                // Nova 类/接口优先
                if (!classNames.contains(name) && !interfaceNames.contains(name)) {
                    Class<?> javaClass = resolveJavaClass(name);
                    if (javaClass != null) name = javaClass.getName().replace('.', '/');
                }
            }
            return MirType.ofObject(name);
        }
        return MirType.ofObject("java/lang/Object");
    }

    private String typeToInternalName(HirType type) {
        if (type instanceof ClassType) {
            String name = ((ClassType) type).getName().replace('.', '/');
            // nova.Xxx 限定名 → 内置类型（遮蔽时可用 nova.Result 等访问内置类型）
            if (name.startsWith("nova/") && !name.substring(5).contains("/")) {
                String simpleName = name.substring(5);
                String boxed = NovaTypeNames.toBoxedInternalName(simpleName);
                if (boxed != null) return boxed;
                if ("Ok".equals(simpleName) || "Err".equals(simpleName)
                        || "Result".equals(simpleName)) return simpleName;
            }
            // 已含 '/' 的完整限定名直接返回
            if (name.contains("/")) return name;
            // typealias 展开：递归解析别名到目标类型
            HirType aliasTarget = typeAliasMap.get(name);
            if (aliasTarget != null) return typeToInternalName(aliasTarget);
            // 用户定义的类/接口优先于内置类型映射（允许遮蔽 Result 等内置名）
            if (classNames.contains(name) || interfaceNames.contains(name)
                    || externalTypeNames.contains(name)) return name;
            // Nova 原始类型名 → JVM 装箱类型（用于 INSTANCEOF 等指令）
            String boxed = NovaTypeNames.toBoxedInternalName(name);
            if (boxed != null) return boxed;
            // Nova 内置特殊类型：直接返回（供 MirInterpreter.isInstanceOf 识别）
            if ("Ok".equals(name) || "Err".equals(name) || "Result".equals(name)) return name;
            // 尝试 Java 类型解析
            Class<?> javaClass = resolveJavaClass(name);
            if (javaClass != null) return javaClass.getName().replace('.', '/');
            // 防御性回退：未解析的类型参数（1-2 个大写字母）→ Object
            if (name.length() <= 2 && Character.isUpperCase(name.charAt(0))) {
                return "java/lang/Object";
            }
            return name;
        }
        if (type instanceof PrimitiveType) {
            switch (((PrimitiveType) type).getKind()) {
                case INT:     return "java/lang/Integer";
                case LONG:    return "java/lang/Long";
                case FLOAT:   return "java/lang/Float";
                case DOUBLE:  return "java/lang/Double";
                case BOOLEAN: return "java/lang/Boolean";
                case CHAR:    return "java/lang/Character";
                default:      return "java/lang/Object";
            }
        }
        return "java/lang/Object";
    }


    /**
     * 通过反射查找 Java 类的方法（按名称和参数数量匹配）。
     * Nova 值全部为装箱 Object，因此优先选择参数为引用类型的重载，
     * 避免选中 append(boolean) 导致运行时 ClassCastException。
     */
    private java.lang.reflect.Method findJavaMethod(Class<?> clazz, String name, int argCount) {
        String cacheKey = clazz.getName() + "#" + name + "#" + argCount;
        java.lang.reflect.Method cached = javaMethodCache.get(cacheKey);
        if (cached != null) return cached;
        // 哨兵值检查：null 表示未缓存，用 containsKey 区分"未查找"和"查找无结果"
        if (javaMethodCache.containsKey(cacheKey)) return null;

        java.lang.reflect.Method best = null;
        int bestPrimitiveCount = Integer.MAX_VALUE;
        for (java.lang.reflect.Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argCount) {
                int primitiveCount = 0;
                for (Class<?> pt : m.getParameterTypes()) {
                    if (pt.isPrimitive()) primitiveCount++;
                }
                if (best == null || primitiveCount < bestPrimitiveCount
                        || (primitiveCount == bestPrimitiveCount
                            && m.getDeclaringClass().isAssignableFrom(best.getDeclaringClass()))) {
                    best = m;
                    bestPrimitiveCount = primitiveCount;
                }
            }
        }
        javaMethodCache.put(cacheKey, best);
        return best;
    }

    /**
     * 构建 Java 方法的 JVM 描述符。参数全部用 Object 包装（兼容全装箱策略），
     * 返回类型使用真实类型（调用端由 boxReturnIfPrimitive 处理）。
     */
    private String buildJavaMethodDesc(java.lang.reflect.Method method) {
        StringBuilder desc = new StringBuilder("(");
        for (Class<?> pt : method.getParameterTypes()) {
            desc.append(javaTypeDescriptor(pt));
        }
        desc.append(")");
        desc.append(javaTypeDescriptor(method.getReturnType()));
        return desc.toString();
    }

    private String javaTypeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return "[" + javaTypeDescriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private MirType javaReturnTypeToMir(Class<?> returnType) {
        if (returnType == void.class) return MirType.ofVoid();
        if (returnType == int.class) return MirType.ofInt();
        if (returnType == long.class) return MirType.ofLong();
        if (returnType == float.class) return MirType.ofFloat();
        if (returnType == double.class) return MirType.ofDouble();
        if (returnType == boolean.class) return MirType.ofBoolean();
        return MirType.ofObject(returnType.getName().replace('.', '/'));
    }

    /**
     * 递归收集 HIR 节点中所有 Identifier 的名称。
     */
    private void collectVarRefs(AstNode node, Set<String> refs) {
        if (node == null) return;
        if (node instanceof Identifier) {
            refs.add(((Identifier) node).getName());
        } else if (node instanceof BinaryExpr) {
            collectVarRefs(((BinaryExpr) node).getLeft(), refs);
            collectVarRefs(((BinaryExpr) node).getRight(), refs);
        } else if (node instanceof UnaryExpr) {
            collectVarRefs(((UnaryExpr) node).getOperand(), refs);
        } else if (node instanceof HirCall) {
            collectVarRefs(((HirCall) node).getCallee(), refs);
            for (Expression arg : ((HirCall) node).getArgs()) collectVarRefs(arg, refs);
        } else if (node instanceof MemberExpr) {
            collectVarRefs(((MemberExpr) node).getTarget(), refs);
        } else if (node instanceof IndexExpr) {
            collectVarRefs(((IndexExpr) node).getTarget(), refs);
            collectVarRefs(((IndexExpr) node).getIndex(), refs);
        } else if (node instanceof AssignExpr) {
            collectVarRefs(((AssignExpr) node).getTarget(), refs);
            collectVarRefs(((AssignExpr) node).getValue(), refs);
        } else if (node instanceof Block) {
            for (Statement stmt : ((Block) node).getStatements()) collectVarRefs(stmt, refs);
        } else if (node instanceof ExpressionStmt) {
            collectVarRefs(((ExpressionStmt) node).getExpression(), refs);
        } else if (node instanceof HirDeclStmt) {
            HirDecl decl = ((HirDeclStmt) node).getDeclaration();
            if (decl instanceof HirField && ((HirField) decl).hasInitializer()) {
                collectVarRefs(((HirField) decl).getInitializer(), refs);
            }
            // 嵌套函数声明：收集默认值（属于外层作用域）+ 深度模式递归 body
            if (decl instanceof HirFunction) {
                HirFunction func = (HirFunction) decl;
                for (HirParam param : func.getParams()) {
                    if (param.getDefaultValue() != null) {
                        collectVarRefs(param.getDefaultValue(), refs);
                    }
                }
                if (deepVarRefCollection) {
                    collectVarRefs(func.getBody(), refs);
                }
            }
        } else if (node instanceof ReturnStmt) {
            collectVarRefs(((ReturnStmt) node).getValue(), refs);
        } else if (node instanceof IfStmt) {
            collectVarRefs(((IfStmt) node).getCondition(), refs);
            collectVarRefs(((IfStmt) node).getThenBranch(), refs);
            collectVarRefs(((IfStmt) node).getElseBranch(), refs);
        } else if (node instanceof ConditionalExpr) {
            collectVarRefs(((ConditionalExpr) node).getCondition(), refs);
            collectVarRefs(((ConditionalExpr) node).getThenExpr(), refs);
            collectVarRefs(((ConditionalExpr) node).getElseExpr(), refs);
        } else if (node instanceof BlockExpr) {
            for (Statement stmt : ((BlockExpr) node).getStatements()) collectVarRefs(stmt, refs);
            collectVarRefs(((BlockExpr) node).getResult(), refs);
        } else if (node instanceof TypeCheckExpr) {
            collectVarRefs(((TypeCheckExpr) node).getOperand(), refs);
        } else if (node instanceof TypeCastExpr) {
            collectVarRefs(((TypeCastExpr) node).getOperand(), refs);
        } else if (node instanceof NotNullExpr) {
            collectVarRefs(((NotNullExpr) node).getOperand(), refs);
        } else if (node instanceof HirCollectionLiteral) {
            for (Expression elem : ((HirCollectionLiteral) node).getElements()) collectVarRefs(elem, refs);
        } else if (node instanceof HirLambda) {
            // 处理参数默认值（属于外层作用域）
            HirLambda lambda = (HirLambda) node;
            for (HirParam param : lambda.getParams()) {
                if (param.getDefaultValue() != null) {
                    collectVarRefs(param.getDefaultValue(), refs);
                }
            }
            // 深度模式：递归进入嵌套 lambda body（用于外层 lambda 的传递性捕获分析）
            if (deepVarRefCollection) {
                collectVarRefs(lambda.getBody(), refs);
            }
        }
    }

    /**
     * 收集 HIR 节点中所有赋值目标的变量名（不递归进入嵌套 lambda）。
     */
    private void collectAssignedVarNames(AstNode node, Set<String> result) {
        if (node == null) return;
        if (node instanceof AssignExpr) {
            AssignExpr assign = (AssignExpr) node;
            if (assign.getTarget() instanceof Identifier) {
                result.add(((Identifier) assign.getTarget()).getName());
            }
            collectAssignedVarNames(assign.getValue(), result);
        } else if (node instanceof Block) {
            for (Statement stmt : ((Block) node).getStatements()) collectAssignedVarNames(stmt, result);
        } else if (node instanceof ExpressionStmt) {
            collectAssignedVarNames(((ExpressionStmt) node).getExpression(), result);
        } else if (node instanceof BlockExpr) {
            for (Statement stmt : ((BlockExpr) node).getStatements()) collectAssignedVarNames(stmt, result);
            collectAssignedVarNames(((BlockExpr) node).getResult(), result);
        } else if (node instanceof BinaryExpr) {
            collectAssignedVarNames(((BinaryExpr) node).getLeft(), result);
            collectAssignedVarNames(((BinaryExpr) node).getRight(), result);
        } else if (node instanceof UnaryExpr) {
            collectAssignedVarNames(((UnaryExpr) node).getOperand(), result);
        } else if (node instanceof HirCall) {
            collectAssignedVarNames(((HirCall) node).getCallee(), result);
            for (Expression arg : ((HirCall) node).getArgs()) collectAssignedVarNames(arg, result);
        } else if (node instanceof IfStmt) {
            collectAssignedVarNames(((IfStmt) node).getCondition(), result);
            collectAssignedVarNames(((IfStmt) node).getThenBranch(), result);
            collectAssignedVarNames(((IfStmt) node).getElseBranch(), result);
        } else if (node instanceof ReturnStmt) {
            collectAssignedVarNames(((ReturnStmt) node).getValue(), result);
        } else if (node instanceof HirLoop) {
            collectAssignedVarNames(((HirLoop) node).getBody(), result);
        } else if (node instanceof ForStmt) {
            collectAssignedVarNames(((ForStmt) node).getBody(), result);
        } else if (node instanceof CStyleForStmt) {
            CStyleForStmt cfor = (CStyleForStmt) node;
            collectAssignedVarNames(cfor.getInit(), result);
            collectAssignedVarNames(cfor.getCondition(), result);
            collectAssignedVarNames(cfor.getUpdate(), result);
            collectAssignedVarNames(cfor.getBody(), result);
        } else if (node instanceof HirDeclStmt) {
            HirDecl decl = ((HirDeclStmt) node).getDeclaration();
            if (decl instanceof HirField && ((HirField) decl).hasInitializer()) {
                collectAssignedVarNames(((HirField) decl).getInitializer(), result);
            }
        }
        // 递归进入嵌套 HirLambda body — 检测传递性可变捕获（嵌套闭包修改外层变量）
        if (node instanceof HirLambda) {
            collectAssignedVarNames(((HirLambda) node).getBody(), result);
        }
    }

    private MirLocal findThisLocal(MirBuilder builder) {
        for (MirLocal local : builder.getFunction().getLocals()) {
            if ("this".equals(local.getName()) || "$this".equals(local.getName())) return local;
        }
        return null;
    }

    // ========== 集合高阶函数路由 ==========

    private static final Set<String> COLLECTION_HOF_1 = new HashSet<>(
            Arrays.asList("filter", "map", "forEach", "find", "mapNotNull"));

    private boolean isDataClass(String className) {
        List<HirAnnotation> anns = classAnnotationData.get(className);
        if (anns != null) {
            for (HirAnnotation ann : anns) {
                if ("data".equals(ann.getName())) return true;
            }
        }
        return false;
    }

    private boolean isCollectionHOF(String methodName, int argCount) {
        if (argCount == 1 && COLLECTION_HOF_1.contains(methodName)) return true;
        if (argCount == 2 && "reduce".equals(methodName)) return true;
        return false;
    }

    /**
     * list.filter(lambda) → INVOKESTATIC CollectionOps.filter(list, lambda)
     */
    private int emitCollectionOpsCall(int target, String methodName, int[] lambdaArgs,
                                       MirBuilder builder, SourceLocation loc) {
        String collOpsOwner = "com/novalang/runtime/stdlib/CollectionOps";
        // 构建参数: [target, ...lambdaArgs]
        int[] allArgs = new int[1 + lambdaArgs.length];
        allArgs[0] = target;
        System.arraycopy(lambdaArgs, 0, allArgs, 1, lambdaArgs.length);
        // 描述符: (Object, Object, ...) → Object
        String desc = MethodDescriptor.allObjectDesc(allArgs.length);
        String extra = collOpsOwner + "|" + methodName + "|" + desc;
        return builder.emitInvokeStatic(extra, allArgs,
                MirType.ofObject("java/lang/Object"), loc);
    }

    /**
     * obj.let(lambda) → INVOKESTATIC NovaScopeFunctions.let(obj, lambda)
     */
    private int emitScopeFunctionCall(int target, String methodName, int[] lambdaArgs,
                                       MirBuilder builder, SourceLocation loc) {
        String owner = "com/novalang/runtime/stdlib/NovaScopeFunctions";
        int[] allArgs = new int[1 + lambdaArgs.length];
        allArgs[0] = target;
        System.arraycopy(lambdaArgs, 0, allArgs, 1, lambdaArgs.length);
        String desc = MethodDescriptor.allObjectDesc(allArgs.length);
        String extra = owner + "|" + methodName + "|" + desc;
        return builder.emitInvokeStatic(extra, allArgs,
                MirType.ofObject("java/lang/Object"), loc);
    }

    /**
     * 4+ 参数 lambda 调用（类型为 Object 时）→ INVOKESTATIC LambdaInvoker.invokeN(fn, args...)
     */
    private int emitLambdaInvokerCall(int callee, int[] args,
                                       MirBuilder builder, SourceLocation loc) {
        String owner = "com/novalang/runtime/stdlib/LambdaInvoker";
        String methodName = "invoke" + args.length;
        int[] allArgs = new int[1 + args.length];
        allArgs[0] = callee;
        System.arraycopy(args, 0, allArgs, 1, args.length);
        String desc = MethodDescriptor.allObjectDesc(allArgs.length);
        String extra = owner + "|" + methodName + "|" + desc;
        return builder.emitInvokeStatic(extra, allArgs,
                MirType.ofObject("java/lang/Object"), loc);
    }

    /**
     * i.double() → INVOKESTATIC Bench.double(i)
     * 使用扩展函数的原始类型描述符（避免装箱开销）
     */
    private int emitStdlibExtensionCall(int target, StdlibRegistry.ExtensionMethodInfo info,
                                         int[] callArgs, MirBuilder builder, SourceLocation loc) {
        // 参数: [receiver, ...callArgs]
        int[] allArgs = new int[1 + callArgs.length];
        allArgs[0] = target;
        System.arraycopy(callArgs, 0, allArgs, 1, callArgs.length);
        String extra = info.jvmOwner + "|" + info.jvmMethodName + "|" + info.jvmDescriptor;
        String retDesc = info.jvmDescriptor.substring(info.jvmDescriptor.indexOf(')') + 1);
        MirType retType = descriptorToMirType(retDesc);
        return builder.emitInvokeStatic(extra, allArgs, retType, loc);
    }

    private int emitExtensionCall(int target, String methodName, int[] callArgs,
                                   MirBuilder builder, SourceLocation loc) {
        String extOwner = extensionMethods.get(methodName);
        // 参数: [receiver, ...callArgs]
        int[] allArgs = new int[1 + callArgs.length];
        allArgs[0] = target;
        System.arraycopy(callArgs, 0, allArgs, 1, callArgs.length);
        // 使用预计算的原始类型描述符
        String desc = extensionDescriptors.get(methodName);
        if (desc == null) {
            // 回退: 全 Object 描述符
            desc = MethodDescriptor.allObjectDesc(allArgs.length);
        }
        String extra = extOwner + "|" + methodName + "|" + desc;
        // 推断返回类型
        String retDesc = desc.substring(desc.indexOf(')') + 1);
        MirType retType = descriptorToMirType(retDesc);
        return builder.emitInvokeStatic(extra, allArgs, retType, loc);
    }

    /**
     * 从 HIR 函数声明构建类型化 JVM 方法描述符。
     * 原始类型参数/返回值使用 I/J/D/F/Z，引用类型使用 Ljava/lang/Object;。
     */
    private String buildHirMethodDescriptor(HirFunction func) {
        List<MirType> paramTypes = new ArrayList<>();
        for (HirParam p : func.getParams()) {
            // vararg 参数运行时是 List，描述符用 Object
            if (p.isVararg()) {
                paramTypes.add(MirType.ofObject("java/lang/Object"));
            } else {
                paramTypes.add(hirTypeToMir(p.getType()));
            }
        }
        MirType ret;
        if (func.getReturnType() != null) {
            ret = hirTypeToMir(func.getReturnType());
        } else if (func.getBody() != null) {
            // block body 可能包含显式 return value 语句
            ret = MirType.ofObject("java/lang/Object");
        } else {
            ret = MirType.ofVoid();
        }
        return MethodDescriptor.of(paramTypes, ret).toJvmDescriptorIntOnly();
    }

    /**
     * 查找 Nova 类方法的类型化描述符，未找到则回退到全 Object 描述符。
     */
    private String lookupNovaMethodDesc(String owner, String methodName, int argCount) {
        String desc = lookupNovaMethodDescInherited(owner, methodName);
        return desc != null ? desc : MethodDescriptor.allObjectDesc(argCount);
    }

    /** 沿继承链查找方法描述符（不包含回退），用于 override 描述符修复 */
    private String lookupNovaMethodDescInherited(String owner, String methodName) {
        String cacheKey = owner + "#" + methodName;
        String cached = inheritedDescCache.get(cacheKey);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }
        String result = walkInheritanceForDesc(owner, methodName);
        inheritedDescCache.put(cacheKey, result != null ? result : "");
        return result;
    }

    private String walkInheritanceForDesc(String owner, String methodName) {
        String current = owner;
        while (current != null) {
            Map<String, String> classDescs = novaMethodDescs.get(current);
            if (classDescs != null) {
                String desc = classDescs.get(methodName);
                if (desc != null) return desc;
            }
            // 查实现的接口（默认方法）
            List<String> ifaces = classInterfaceMap.get(current);
            if (ifaces != null) {
                for (String iface : ifaces) {
                    Map<String, String> ifaceDescs = novaMethodDescs.get(iface);
                    if (ifaceDescs != null) {
                        String desc = ifaceDescs.get(methodName);
                        if (desc != null) return desc;
                    }
                }
            }
            current = classSuperClass.get(current);
        }
        return null;
    }

    /** 检查继承链是否完整可达（所有父类的方法信息都在当前 lowering 中可用） */
    private boolean isInheritanceChainComplete(String owner) {
        String current = owner;
        while (current != null) {
            if (!novaMethodDescs.containsKey(current)) return false;
            current = classSuperClass.get(current);
        }
        return true;
    }

    /**
     * 为静态方法构建原始类型描述符：原始类型参数/返回值使用 I/J/D/F/Z，其余使用 Object。
     */
    private String buildNativeStaticDescriptor(MirFunction func) {
        return MethodDescriptor.fromMirFunction(func).toJvmDescriptorIntOnly();
    }

    private MirType descriptorToMirType(String desc) {
        switch (desc) {
            case "I": return MirType.ofInt();
            case "J": return MirType.ofLong();
            case "F": return MirType.ofFloat();
            case "D": return MirType.ofDouble();
            case "Z": return MirType.ofBoolean();
            case "V": return MirType.ofVoid();
            default:
                if (desc.startsWith("L") && desc.endsWith(";")) {
                    return MirType.ofObject(desc.substring(1, desc.length() - 1));
                }
                return MirType.ofObject("java/lang/Object");
        }
    }

    private BinaryOp mapBinaryOp(BinaryExpr.BinaryOp op) {
        switch (op) {
            case ADD: return BinaryOp.ADD;
            case SUB: return BinaryOp.SUB;
            case MUL: return BinaryOp.MUL;
            case DIV: return BinaryOp.DIV;
            case MOD: return BinaryOp.MOD;
            case EQ: return BinaryOp.EQ;
            case NE: return BinaryOp.NE;
            case LT: return BinaryOp.LT;
            case GT: return BinaryOp.GT;
            case LE: return BinaryOp.LE;
            case GE: return BinaryOp.GE;
            case AND: return BinaryOp.AND;
            case OR: return BinaryOp.OR;
            case SHL: return BinaryOp.SHL;
            case SHR: return BinaryOp.SHR;
            case USHR: return BinaryOp.USHR;
            case BAND: return BinaryOp.BAND;
            case BOR: return BinaryOp.BOR;
            case BXOR: return BinaryOp.BXOR;
            case REF_EQ: return BinaryOp.REF_EQ;
            case REF_NE: return BinaryOp.REF_NE;
            default: return BinaryOp.ADD;
        }
    }

    /**
     * 在类实现的接口中查找方法，返回包含该方法的接口名或 null。
     */
    private String findMethodInInterfaces(String className, String methodName) {
        List<String> ifaces = classInterfaceMap.get(className);
        if (ifaces == null) return null;
        for (String iface : ifaces) {
            Map<String, String> ifaceDescs = novaMethodDescs.get(iface);
            if (ifaceDescs != null && ifaceDescs.containsKey(methodName)) {
                return iface;
            }
        }
        return null;
    }

    /**
     * 从返回类型描述符推断 MirType，优先使用描述符的精确类型，
     * 仅在无法解析时回退到 owner 类型。
     */
    private MirType inferReturnType(String retDescStr, String owner) {
        if ("V".equals(retDescStr)) return MirType.ofVoid();
        MirType resolved = descriptorToMirType(retDescStr);
        // 如果描述符编码了具体类型（如 I/J/D/Ljava/lang/String; 等），直接使用
        if (resolved.getKind() != MirType.Kind.OBJECT
                || !"java/lang/Object".equals(resolved.getClassName())) {
            return resolved;
        }
        // 描述符为 Ljava/lang/Object;（Nova 编译方法的通用描述符）→ 回退到 owner 类型
        // 以保持类型传播（运算符重载、builder 方法链等需要正确的 owner 类型）
        return MirType.ofObject(owner);
    }

    /**
     * 检查 Nova 类（含继承链）是否注册了指定方法。
     * 用于运算符重载等场景，避免对未定义运算符方法的类错误地生成重载调用。
     */
    private boolean hasNovaMethod(String owner, String methodName) {
        return lookupNovaMethodDescInherited(owner, methodName) != null;
    }

    /**
     * 检测 Java.type("className") 调用模式，返回类名或 null。
     */
    /**
     * 从 MemberExpr 链提取限定名（如 java.lang.StringBuilder）。
     * 仅当链从 Identifier 开始时返回非 null。
     */
    private String extractQualifiedName(Expression expr) {
        if (!(expr instanceof MemberExpr)) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        Expression current = expr;
        while (current instanceof MemberExpr) {
            MemberExpr fa = (MemberExpr) current;
            parts.add(fa.getMember());
            current = fa.getTarget();
        }
        if (!(current instanceof Identifier)) return null;
        String root = ((Identifier) current).getName();
        // 仅对已知 Java 包根名尝试解析（避免误判 Nova 对象链）
        if (!"java".equals(root) && !"javax".equals(root) && !"com".equals(root)
                && !"org".equals(root) && !"net".equals(root) && !"io".equals(root)) {
            return null;
        }
        StringBuilder sb = new StringBuilder(root);
        for (int i = parts.size() - 1; i >= 0; i--) {
            sb.append('.').append(parts.get(i));
        }
        return sb.toString();
    }

    /**
     * 从表达式中提取 javaClass("className") 的字面类名。
     * 返回 "java.lang.Integer" 或 null（非 javaClass 调用或非字面参数）。
     */
    private String extractJavaClassName(Expression expr) {
        if (!(expr instanceof HirCall)) return null;
        HirCall call = (HirCall) expr;
        if (!(call.getCallee() instanceof Identifier)) return null;
        if (!"javaClass".equals(((Identifier) call.getCallee()).getName())) return null;
        if (call.getArgs().size() != 1) return null;
        Expression arg = call.getArgs().get(0);
        if (arg instanceof Literal && ((Literal) arg).getKind() == LiteralKind.STRING) {
            return (String) ((Literal) arg).getValue();
        }
        return null;
    }

    private String extractJavaTypeClassName(HirCall call) {
        if (!(call.getCallee() instanceof MemberExpr)) return null;
        MemberExpr fa = (MemberExpr) call.getCallee();
        if (!(fa.getTarget() instanceof Identifier)) return null;
        if (!"Java".equals(((Identifier) fa.getTarget()).getName())) return null;
        if (!"type".equals(fa.getMember())) return null;
        if (call.getArgs().size() != 1) return null;
        Expression arg = call.getArgs().get(0);
        if (arg instanceof Literal && ((Literal) arg).getKind() == LiteralKind.STRING) {
            return (String) ((Literal) arg).getValue();
        }
        return null;
    }

    private static boolean hasAnnotation(List<HirAnnotation> annotations, String... names) {
        if (annotations == null || annotations.isEmpty()) return false;
        for (HirAnnotation ann : annotations) {
            String annName = ann.getName();
            for (String name : names) {
                if (name.equalsIgnoreCase(annName)) return true;
            }
        }
        return false;
    }

}
