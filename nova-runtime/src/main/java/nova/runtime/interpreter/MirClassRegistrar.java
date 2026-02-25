package nova.runtime.interpreter;

import com.novalang.ir.mir.*;
import com.novalang.ir.hir.ClassKind;
import com.novalang.ir.hir.HirAnnotation;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.expr.Expression;
import nova.runtime.*;
import nova.runtime.interpreter.reflect.NovaClassInfo;

import java.util.*;

/**
 * MIR 类注册器：负责将 MirClass 转换为 NovaClass 并注册到运行时环境。
 *
 * <p>从 MirInterpreter 提取，处理模块加载时的类注册、枚举后处理、反射信息构建等。</p>
 */
final class MirClassRegistrar {

    private static final String MARKER_LAMBDA = "$Lambda$";
    private static final String MARKER_METHOD_REF = "$MethodRef$";
    private static final String SPECIAL_INIT = "<init>";
    private static final String SPECIAL_CLINIT = "<clinit>";

    private final Interpreter interp;
    private final Map<String, MirInterpreter.MirClassInfo> mirClasses;
    private final Map<String, MirCallable> mirFunctions;
    private final MirInterpreter mirInterp;

    /** 当前模块类名集合（用于 sealed class 继承检查） */
    private Set<String> currentModuleClassNames;
    /** 持久类名集合（跨 evalRepl 保留，含类/枚举/接口） */
    final Set<String> persistentClassNames = new HashSet<>();
    /** 持久接口名集合（跨 evalRepl 保留） */
    final Set<String> persistentInterfaceNames = new HashSet<>();
    /** 持久接口对象（跨 evalRepl 保留，带抽象方法信息） */
    private final Map<String, NovaInterface> persistentInterfaces = new HashMap<>();

    /** Java 类解析缓存 */
    private static final String[] JAVA_PREFIXES = {
            "", "java.lang.", "java.util.", "java.io.",
            "java.util.concurrent.", "java.util.function."
    };
    private static final Class<?> UNRESOLVED = void.class;
    private final Map<String, Class<?>> javaClassCache = new HashMap<>();

    MirClassRegistrar(Interpreter interp, Map<String, MirInterpreter.MirClassInfo> mirClasses,
                      Map<String, MirCallable> mirFunctions, MirInterpreter mirInterp) {
        this.interp = interp;
        this.mirClasses = mirClasses;
        this.mirFunctions = mirFunctions;
        this.mirInterp = mirInterp;
    }

    /** 子 MirInterpreter 构造：共享父级持久化状态 */
    MirClassRegistrar(Interpreter interp, Map<String, MirInterpreter.MirClassInfo> mirClasses,
                      Map<String, MirCallable> mirFunctions, MirInterpreter mirInterp,
                      MirClassRegistrar parent) {
        this(interp, mirClasses, mirFunctions, mirInterp);
        this.persistentClassNames.addAll(parent.persistentClassNames);
        this.persistentInterfaceNames.addAll(parent.persistentInterfaceNames);
        this.persistentInterfaces.putAll(parent.persistentInterfaces);
    }

    void setCurrentModuleClassNames(Set<String> names) {
        this.currentModuleClassNames = names;
    }

    void clearCurrentModuleClassNames() {
        this.currentModuleClassNames = null;
    }

    Set<String> getKnownClassNames() {
        return persistentClassNames;
    }

    Set<String> getKnownInterfaceNames() {
        return persistentInterfaceNames;
    }

    // ============ 类注册 ============

    void registerClass(MirClass cls) {
        String className = cls.getName();

        // Lambda / MethodRef 匿名类：仅注册 MirClassInfo（不创建 NovaClass）
        if (className.contains(MARKER_LAMBDA) || className.contains(MARKER_METHOD_REF)) {
            mirClasses.put(className, new MirInterpreter.MirClassInfo(cls, null));
            return;
        }

        // 查找父类
        NovaClass superclass = null;
        Class<?> javaSuperclass = null;
        String superName = cls.getSuperClass();
        if (superName != null && !"java/lang/Object".equals(superName)) {
            MirInterpreter.MirClassInfo parentInfo = mirClasses.get(superName);
            if (parentInfo != null) {
                superclass = parentInfo.novaClass;
            } else {
                NovaValue envVal = interp.getEnvironment().tryGet(superName);
                if (envVal instanceof NovaClass) {
                    superclass = (NovaClass) envVal;
                } else {
                    javaSuperclass = resolveJavaClassFromInternal(superName);
                    if (javaSuperclass != null
                            && java.lang.reflect.Modifier.isFinal(javaSuperclass.getModifiers())) {
                        throw new NovaRuntimeException(
                                "Cannot extend final class: " + javaSuperclass.getName());
                    }
                }
            }
        }

        // sealed class 继承检查
        if (superclass != null && superclass.isSealed()) {
            boolean sameModule = currentModuleClassNames != null
                    && currentModuleClassNames.contains(superName);
            if (!sameModule) {
                throw new NovaRuntimeException(
                        "Cannot extend sealed class '" + superclass.getName()
                        + "' from outside its definition scope");
            }
        }

        NovaClass novaClass = new NovaClass(className, superclass, interp.getEnvironment());

        // 设置 Java 超类
        if (javaSuperclass != null) {
            novaClass.setJavaSuperclass(javaSuperclass);
            for (MirFunction method : cls.getMethods()) {
                if (SPECIAL_INIT.equals(method.getName()) && !method.hasDelegation() && method.hasSuperInitArgs()) {
                    List<NovaValue> superCtorArgs = extractSuperCtorArgs(method);
                    if (superCtorArgs != null) {
                        novaClass.setJavaSuperConstructorArgs(superCtorArgs);
                    }
                    break;
                }
            }
        }

        // 注册方法
        Set<String> definedMethods = new HashSet<>();
        List<MirFunction> ctorMethods = new ArrayList<>();
        for (MirFunction method : cls.getMethods()) {
            if (SPECIAL_INIT.equals(method.getName())) {
                ctorMethods.add(method);
            }
        }
        ctorMethods.sort((a, b) -> Integer.compare(totalInstCount(b), totalInstCount(a)));
        for (MirFunction method : ctorMethods) {
            MirCallable ctorCallable = new MirCallable(mirInterp, method, null);
            novaClass.addHirConstructor(ctorCallable);
            if (!method.hasDelegation() && novaClass.getConstructorCallable() == null) {
                novaClass.setConstructorCallable(ctorCallable);
            }
        }
        for (MirFunction method : cls.getMethods()) {
            String methodName = method.getName();
            if (SPECIAL_INIT.equals(methodName)) {
                // 已在上面处理
            } else if (!SPECIAL_CLINIT.equals(methodName)) {
                novaClass.defineMethod(methodName, new MirCallable(mirInterp, method, null));
                definedMethods.add(methodName);
                if (method.getModifiers().contains(Modifier.PRIVATE)) {
                    novaClass.setMethodVisibility(methodName, Modifier.PRIVATE);
                } else if (method.getModifiers().contains(Modifier.PROTECTED)) {
                    novaClass.setMethodVisibility(methodName, Modifier.PROTECTED);
                }
            }
        }

        // 接口默认方法复制
        for (String ifaceName : cls.getInterfaces()) {
            MirInterpreter.MirClassInfo ifaceInfo = mirClasses.get(ifaceName);
            if (ifaceInfo != null && ifaceInfo.mirClass != null) {
                NovaInterface novaIface = new NovaInterface(ifaceName);
                for (MirFunction ifaceMethod : ifaceInfo.mirClass.getMethods()) {
                    String mName = ifaceMethod.getName();
                    if (!mName.startsWith("<") && ifaceMethod.getModifiers().contains(Modifier.ABSTRACT)) {
                        novaIface.addAbstractMethod(mName);
                    }
                }
                novaClass.addInterface(novaIface);
                for (MirFunction ifaceMethod : ifaceInfo.mirClass.getMethods()) {
                    String mName = ifaceMethod.getName();
                    if (!mName.startsWith("<") && !definedMethods.contains(mName)
                            && !ifaceMethod.getModifiers().contains(Modifier.ABSTRACT)) {
                        novaClass.defineMethod(mName, new MirCallable(mirInterp, ifaceMethod, null));
                        definedMethods.add(mName);
                    }
                }
            } else {
                NovaInterface persisted = persistentInterfaces.get(ifaceName);
                if (persisted != null) {
                    novaClass.addInterface(persisted);
                } else {
                    NovaValue envVal = interp.getEnvironment().tryGet(ifaceName);
                    if (envVal instanceof NovaInterface) {
                        novaClass.addInterface((NovaInterface) envVal);
                    } else {
                        Class<?> javaIface = resolveJavaClassFromInternal(ifaceName);
                        if (javaIface != null && javaIface.isInterface()) {
                            novaClass.addJavaInterface(javaIface);
                        }
                    }
                }
            }
        }

        // 注册静态字段
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)) {
                novaClass.setStaticField(field.getName(), NovaNull.NULL);
            }
        }

        // 保存注解
        List<HirAnnotation> hirAnns = cls.getHirAnnotations();
        if (hirAnns == null || hirAnns.isEmpty()) {
            List<String> annNames = cls.getAnnotationNames();
            if (annNames != null && !annNames.isEmpty()) {
                hirAnns = new ArrayList<>();
                for (String annName : annNames) {
                    hirAnns.add(new HirAnnotation(null, annName, Collections.emptyMap()));
                }
            }
        }
        if (hirAnns != null && !hirAnns.isEmpty()) {
            interp.hirClassAnnotations.put(className, hirAnns);
        }

        // interface 标志
        if (cls.getKind() == ClassKind.INTERFACE) {
            persistentInterfaceNames.add(className);
            NovaInterface novaIface = new NovaInterface(className);
            for (MirFunction m : cls.getMethods()) {
                String mName = m.getName();
                if (!mName.startsWith("<") && m.getModifiers().contains(Modifier.ABSTRACT)) {
                    novaIface.addAbstractMethod(mName);
                }
                if (!mName.startsWith("<") && !m.getModifiers().contains(Modifier.ABSTRACT)) {
                    novaIface.addDefaultMethod(mName, new MirCallable(mirInterp, m, null));
                }
            }
            persistentInterfaces.put(className, novaIface);
        }

        // annotation class 标志
        if (cls.getKind() == ClassKind.ANNOTATION) {
            novaClass.setAnnotation(true);
        }

        // abstract class 标志
        if (cls.getModifiers().contains(Modifier.ABSTRACT)) {
            novaClass.setAbstract(true);
        }

        // sealed class 标志
        if (cls.getModifiers().contains(Modifier.SEALED)) {
            novaClass.setSealed(true);
        }

        // data class 标志
        if (cls.hasAnnotation("data")) {
            novaClass.setData(true);
            for (MirFunction method : cls.getMethods()) {
                if (SPECIAL_INIT.equals(method.getName()) && !method.hasDelegation()) {
                    List<String> fieldOrder = new ArrayList<>();
                    for (MirParam p : method.getParams()) {
                        fieldOrder.add(p.getName());
                    }
                    novaClass.setDataFieldOrder(fieldOrder);
                    break;
                }
            }
        }

        // 字段可见性
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            if (field.getModifiers().contains(Modifier.PRIVATE)) {
                novaClass.setFieldVisibility(field.getName(), Modifier.PRIVATE);
            } else if (field.getModifiers().contains(Modifier.PROTECTED)) {
                novaClass.setFieldVisibility(field.getName(), Modifier.PROTECTED);
            }
        }

        // 计算字段布局
        {
            List<String> instanceFieldNames = new ArrayList<>();
            for (MirField field : cls.getFields()) {
                if (!field.getModifiers().contains(Modifier.STATIC)) {
                    instanceFieldNames.add(field.getName());
                }
            }
            novaClass.computeFieldLayout(instanceFieldNames);
        }

        // Java 互操作：检查未实现的抽象方法
        if (novaClass.hasJavaSuperTypes()) {
            List<String> unimplemented = novaClass.getUnimplementedMethods();
            if (!unimplemented.isEmpty()) {
                throw new NovaRuntimeException(
                        "Anonymous object must implement abstract methods: "
                                + String.join(", ", unimplemented));
            }
        }

        // 构建并缓存 ClassInfo
        novaClass.setCachedClassInfo(buildClassInfoFromMir(cls, novaClass, hirAnns));

        mirClasses.put(className, new MirInterpreter.MirClassInfo(cls, novaClass));
        persistentClassNames.add(className);
        interp.getEnvironment().defineVal(className, novaClass);

        // 触发注解处理器
        if (hirAnns != null && !hirAnns.isEmpty()) {
            for (HirAnnotation ann : hirAnns) {
                List<NovaAnnotationProcessor> processors = interp.annotationProcessors.get(ann.getName());
                if (processors != null) {
                    Map<String, NovaValue> annArgs = new HashMap<>();
                    for (Map.Entry<String, Expression> entry : ann.getArgs().entrySet()) {
                        annArgs.put(entry.getKey(), interp.evaluateHir(entry.getValue()));
                    }
                    for (NovaAnnotationProcessor proc : processors) {
                        proc.processClass(novaClass, annArgs, interp);
                    }
                }
            }
        }

        // object 单例
        if (cls.getKind() == ClassKind.OBJECT) {
            NovaValue instance = novaClass.call(interp, Collections.emptyList());
            novaClass.setStaticField("INSTANCE", instance);
        }
    }

    // ============ 枚举后处理 ============

    void finalizeEnumClass(MirClass cls) {
        String className = cls.getName();
        MirInterpreter.MirClassInfo classInfo = mirClasses.get(className);
        if (classInfo == null || classInfo.novaClass == null) return;
        NovaClass novaClass = classInfo.novaClass;

        NovaEnum novaEnum = new NovaEnum(className, interp.getEnvironment());

        for (MirField field : cls.getFields()) {
            if (!field.getModifiers().contains(Modifier.STATIC)) continue;
            if (!field.getModifiers().contains(Modifier.FINAL)) continue;
            String entryName = field.getName();
            if (entryName.startsWith("$")) continue;

            NovaValue staticVal = novaClass.getStaticField(entryName);
            if (staticVal == null || staticVal instanceof NovaNull) continue;

            Map<String, NovaValue> entryFields = new LinkedHashMap<>();
            Map<String, NovaCallable> entryMethods = new LinkedHashMap<>();

            int ordinal = novaEnum.size();
            String name = entryName;

            if (staticVal instanceof NovaObject) {
                NovaObject obj = (NovaObject) staticVal;
                NovaValue nameVal = obj.getField("$name");
                if (nameVal != null && !(nameVal instanceof NovaNull)) {
                    name = nameVal.asString();
                }
                NovaValue ordVal = obj.getField("$ordinal");
                if (ordVal != null && !(ordVal instanceof NovaNull)) {
                    ordinal = ordVal.asInt();
                }
                for (Map.Entry<String, NovaValue> fe : obj.getFields().entrySet()) {
                    if (!fe.getKey().startsWith("$")) {
                        entryFields.put(fe.getKey(), fe.getValue());
                    }
                }
            }

            for (Map.Entry<String, NovaCallable> me : novaClass.getMethods().entrySet()) {
                String mName = me.getKey();
                if (!"name".equals(mName) && !"ordinal".equals(mName)) {
                    entryMethods.put(mName, me.getValue());
                }
            }

            NovaEnumEntry entry = new NovaEnumEntry(name, ordinal, novaEnum, entryFields, entryMethods);
            novaEnum.addEntry(entry);
            novaClass.setStaticField(entryName, entry);
        }

        interp.getEnvironment().redefine(className, novaEnum, false);
    }

    // ============ 反射信息构建 ============

    private NovaClassInfo buildClassInfoFromMir(
            MirClass cls, NovaClass novaClass, List<HirAnnotation> hirAnns) {
        List<nova.runtime.interpreter.reflect.NovaFieldInfo> fields = new ArrayList<>();
        for (MirField field : cls.getFields()) {
            if (field.getModifiers().contains(Modifier.STATIC)) continue;
            Modifier vis = novaClass.getFieldVisibility(field.getName());
            String visStr = vis != null ? vis.name().toLowerCase() : "public";
            boolean isMutable = !field.getModifiers().contains(Modifier.FINAL);
            String typeName = mirTypeToNovaName(field.getType());
            fields.add(new nova.runtime.interpreter.reflect.NovaFieldInfo(
                    field.getName(), typeName, visStr, isMutable, novaClass, null));
        }

        List<nova.runtime.interpreter.reflect.NovaMethodInfo> methods = new ArrayList<>();
        for (Map.Entry<String, NovaCallable> entry : novaClass.getMethods().entrySet()) {
            Modifier vis = novaClass.getMethodVisibility(entry.getKey());
            String visStr = vis != null ? vis.name().toLowerCase() : "public";
            List<nova.runtime.interpreter.reflect.NovaParamInfo> params =
                    NovaClassInfo.extractParams(entry.getValue());
            methods.add(new nova.runtime.interpreter.reflect.NovaMethodInfo(
                    entry.getKey(), visStr, params, entry.getValue()));
        }

        List<nova.runtime.interpreter.reflect.NovaAnnotationInfo> annotations = new ArrayList<>();
        if (hirAnns != null) {
            for (HirAnnotation ann : hirAnns) {
                Map<String, NovaValue> annArgs = new HashMap<>();
                for (Map.Entry<String, Expression> e : ann.getArgs().entrySet()) {
                    annArgs.put(e.getKey(), interp.evaluateHir(e.getValue()));
                }
                annotations.add(new nova.runtime.interpreter.reflect.NovaAnnotationInfo(ann.getName(), annArgs));
            }
        }

        String superName = cls.getSuperClass();
        if ("java/lang/Object".equals(superName)) superName = null;
        return NovaClassInfo.create(
                cls.getName(), superName, cls.getInterfaces(),
                fields, methods, annotations, novaClass);
    }

    /** MirType → Nova 源码级类型名 */
    private static String mirTypeToNovaName(MirType type) {
        if (type == null) return null;
        switch (type.getKind()) {
            case INT:     return "Int";
            case LONG:    return "Long";
            case FLOAT:   return "Float";
            case DOUBLE:  return "Double";
            case BOOLEAN: return "Boolean";
            case CHAR:    return "Char";
            case VOID:    return "Unit";
            case OBJECT: {
                String cn = type.getClassName();
                if (cn == null) return null;
                String nova = com.novalang.compiler.NovaTypeNames.fromBoxedInternalName(cn);
                if (nova != null) return "Any".equals(nova) ? null : nova;
                int slash = cn.lastIndexOf('/');
                return slash >= 0 ? cn.substring(slash + 1) : cn;
            }
            case ARRAY:
                String elem = mirTypeToNovaName(type.getElementType());
                return elem != null ? "List<" + elem + ">" : "List";
            default: return null;
        }
    }

    // ============ Java 类解析 ============

    /** JVM 内部名 → Java Class 对象（带常见前缀尝试） */
    Class<?> resolveJavaClassFromInternal(String internalName) {
        Class<?> cached = javaClassCache.get(internalName);
        if (cached != null) return cached == UNRESOLVED ? null : cached;
        String dotName = internalName.replace('/', '.');
        for (String prefix : JAVA_PREFIXES) {
            try {
                Class<?> cls = Class.forName(prefix + dotName);
                javaClassCache.put(internalName, cls);
                return cls;
            } catch (ClassNotFoundException e) { /* continue */ }
        }
        javaClassCache.put(internalName, UNRESOLVED);
        return null;
    }

    /** 检查值是否为指定类型的实例 */
    boolean isInstanceOf(NovaValue value, String typeName) {
        String mapped = com.novalang.compiler.NovaTypeNames.fromBoxedInternalName(typeName);
        String resolved = mapped != null ? mapped : toJavaDotName(typeName);
        return TypeOps.isInstanceOf(value, resolved, this::resolveJavaClassFromInternal, null);
    }

    // ============ 辅助方法 ============

    private static String toJavaDotName(String internalName) {
        return internalName.replace("/", ".");
    }

    private static int totalInstCount(MirFunction f) {
        int count = 0;
        for (BasicBlock b : f.getBlocks()) {
            count += b.getInstructions().size();
        }
        return count;
    }

    /**
     * 从构造器入口块提取超类构造器参数的常量值。
     */
    private List<NovaValue> extractSuperCtorArgs(MirFunction ctor) {
        int[] argLocals = ctor.getSuperInitArgLocals();
        if (argLocals == null || argLocals.length == 0) return Collections.emptyList();
        if (ctor.getBlocks().isEmpty()) return null;
        BasicBlock entry = ctor.getBlocks().get(0);
        Map<Integer, NovaValue> constValues = new HashMap<>();
        for (MirInst inst : entry.getInstructions()) {
            switch (inst.getOp()) {
                case CONST_INT: constValues.put(inst.getDest(), NovaInt.of(inst.extraInt)); break;
                case CONST_LONG: constValues.put(inst.getDest(), NovaLong.of(((Number) inst.getExtra()).longValue())); break;
                case CONST_FLOAT: constValues.put(inst.getDest(), NovaFloat.of(((Number) inst.getExtra()).floatValue())); break;
                case CONST_STRING: constValues.put(inst.getDest(), NovaString.of((String) inst.getExtra())); break;
                case CONST_BOOL: constValues.put(inst.getDest(), NovaBoolean.of((boolean) inst.getExtra())); break;
                case CONST_NULL: constValues.put(inst.getDest(), NovaNull.NULL); break;
                default: break;
            }
        }
        List<NovaValue> args = new ArrayList<>(argLocals.length);
        for (int local : argLocals) {
            NovaValue val = constValues.get(local);
            if (val == null) return null;
            args.add(val);
        }
        return args;
    }
}
