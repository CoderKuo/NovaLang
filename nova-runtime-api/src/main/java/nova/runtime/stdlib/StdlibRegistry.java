package nova.runtime.stdlib;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 标准库函数注册表。
 *
 * <p>解释器（Builtins）和编译器（CodeGenerator）共用此注册表，新增函数只需在 stdlib 包中注册即可。</p>
 */
public final class StdlibRegistry {

    /**
     * 接收者 Lambda 函数的元信息（buildString / buildList / buildMap / buildSet）。
     */
    public static final class ReceiverLambdaInfo extends StdlibFunction {
        /** 接收者类型的 JVM 内部名，如 "java/lang/StringBuilder" */
        public final String receiverType;
        /** 返回值的 JVM 类型描述符，如 "Ljava/lang/String;" */
        public final String returnTypeDesc;
        /** receiver 上的终结方法名（如 "toString"），null 表示直接返回 receiver */
        public final String finalizerMethod;
        /** 直接执行的实现 lambda（无反射） */
        public final Function<Consumer<Object>, Object> impl;

        public ReceiverLambdaInfo(String name, String receiverType,
                                  String returnTypeDesc, String finalizerMethod,
                                  Function<Consumer<Object>, Object> impl) {
            super(name, 1); // 接收者 Lambda 固定 1 个参数（lambda 本身）
            this.receiverType = receiverType;
            this.returnTypeDesc = returnTypeDesc;
            this.finalizerMethod = finalizerMethod;
            this.impl = impl;
        }
    }

    /**
     * 普通原生函数的元信息（min / max / abs …）。
     *
     * <p>编译器通过 {@code jvmOwner/jvmMethodName/jvmDescriptor} 生成 INVOKESTATIC，
     * 解释器通过 {@code impl} lambda 直接调用——全程无反射。</p>
     */
    public static final class NativeFunctionInfo extends StdlibFunction {
        /** 静态方法所在类的 JVM 内部名，如 "nova/runtime/stdlib/StdlibMath" */
        public final String jvmOwner;
        /** JVM 方法名 */
        public final String jvmMethodName;
        /** JVM 方法描述符，如 "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" */
        public final String jvmDescriptor;
        /** 解释器直接调用的实现（无反射） */
        public final Function<Object[], Object> impl;

        public NativeFunctionInfo(String name, int arity,
                                   String jvmOwner, String jvmMethodName, String jvmDescriptor,
                                   Function<Object[], Object> impl) {
            super(name, arity);
            this.jvmOwner = jvmOwner;
            this.jvmMethodName = jvmMethodName;
            this.jvmDescriptor = jvmDescriptor;
            this.impl = impl;
        }
    }

    /**
     * 常量的元信息（PI / E / MAX_INT / MIN_INT …）。
     *
     * <p>编译器通过 {@code GETSTATIC jvmOwner.jvmFieldName : jvmDescriptor} 读取，
     * 解释器通过 {@code value} 字段直接获取。</p>
     */
    public static final class ConstantInfo extends StdlibFunction {
        /** 常量所在类的 JVM 内部名，如 "nova/runtime/stdlib/StdlibConstants" */
        public final String jvmOwner;
        /** JVM 字段名 */
        public final String jvmFieldName;
        /** JVM 字段描述符，如 "D"（double）或 "I"（int） */
        public final String jvmDescriptor;
        /** 常量值 */
        public final Object value;

        public ConstantInfo(String name, String jvmOwner, String jvmFieldName,
                            String jvmDescriptor, Object value) {
            super(name, 0);
            this.jvmOwner = jvmOwner;
            this.jvmFieldName = jvmFieldName;
            this.jvmDescriptor = jvmDescriptor;
            this.value = value;
        }
    }

    /**
     * Supplier Lambda 函数的元信息（async 等）。
     * 编译器将 lambda body 编译为 Supplier，再调用 wrapper 方法。
     */
    public static final class SupplierLambdaInfo extends StdlibFunction {
        /** 包装调用的 JVM owner，如 "java/util/concurrent/CompletableFuture" */
        public final String wrapperOwner;
        /** 包装调用的方法名，如 "supplyAsync" */
        public final String wrapperMethod;
        /** 包装调用的 JVM 描述符 */
        public final String wrapperDescriptor;
        /** Nova 返回类型名（LSP 类型推导用），如 "Future" */
        public final String novaReturnType;

        public SupplierLambdaInfo(String name, String wrapperOwner,
                                   String wrapperMethod, String wrapperDescriptor,
                                   String novaReturnType) {
            super(name, 1); // 固定 1 个参数（lambda 本身）
            this.wrapperOwner = wrapperOwner;
            this.wrapperMethod = wrapperMethod;
            this.wrapperDescriptor = wrapperDescriptor;
            this.novaReturnType = novaReturnType;
        }
    }

    /**
     * 类型扩展方法的元信息。
     * 编译器通过 jvmOwner/jvmMethodName/jvmDescriptor 生成 INVOKESTATIC，
     * NovaDynamic 通过 impl 直接调用。
     */
    public static final class ExtensionMethodInfo extends StdlibFunction {
        /** 目标类型 JVM 内部名，如 "java/util/List" */
        public final String targetType;
        /** 静态方法所在类 JVM 内部名 */
        public final String jvmOwner;
        /** JVM 方法名 */
        public final String jvmMethodName;
        /** JVM 描述符，第一个参数为 receiver，如 "(Ljava/lang/Object;)Ljava/lang/Object;" */
        public final String jvmDescriptor;
        /** 运行时直接调用：args[0]=receiver, args[1..]=params */
        public final Function<Object[], Object> impl;
        /** 是否为变参方法 */
        public final boolean isVarargs;
        /** 是否为属性（直接返回值，不包装为函数） */
        public final boolean isProperty;

        public ExtensionMethodInfo(String name, int arity, String targetType,
                                   String jvmOwner, String jvmMethodName, String jvmDescriptor,
                                   Function<Object[], Object> impl) {
            this(name, arity, targetType, jvmOwner, jvmMethodName, jvmDescriptor, impl, false, false);
        }

        public ExtensionMethodInfo(String name, int arity, String targetType,
                                   String jvmOwner, String jvmMethodName, String jvmDescriptor,
                                   Function<Object[], Object> impl, boolean isVarargs, boolean isProperty) {
            super(name, arity);
            this.targetType = targetType;
            this.jvmOwner = jvmOwner;
            this.jvmMethodName = jvmMethodName;
            this.jvmDescriptor = jvmDescriptor;
            this.impl = impl;
            this.isVarargs = isVarargs;
            this.isProperty = isProperty;
        }
    }

    // ============ 注册表 ============

    private static final Map<String, StdlibFunction> registry = new LinkedHashMap<>();

    // targetType → (methodName → ExtensionMethodInfo 列表)
    private static final Map<String, Map<String, List<ExtensionMethodInfo>>> extensionMethods
            = new LinkedHashMap<>();

    public static void register(StdlibFunction func) {
        registry.put(func.name, func);
    }

    public static void registerExtensionMethod(ExtensionMethodInfo info) {
        extensionMethods
                .computeIfAbsent(info.targetType, k -> new LinkedHashMap<>())
                .computeIfAbsent(info.name, k -> new ArrayList<>())
                .add(info);
    }

    /**
     * 扫描标注了 {@link Ext} 的类，将其中所有 public static 方法自动注册为扩展方法。
     * <p>目标类型从 {@code @Ext("java/util/List")} 的 value 中读取。</p>
     * <p>约定：第一个参数为 receiver，arity = paramCount - 1。</p>
     */
    public static void registerExtensionMethods(Class<?> clazz) {
        Ext ext = clazz.getAnnotation(Ext.class);
        if (ext == null) throw new IllegalArgumentException(clazz.getName() + " is not annotated with @Ext");
        String targetType = ext.value();
        String owner = clazz.getName().replace('.', '/');
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        for (Method m : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            if (m.isAnnotationPresent(ExtIgnore.class)) continue;

            String name = m.getName();
            int paramCount = m.getParameterCount();
            boolean isVarargs = m.isVarArgs();
            boolean isProperty = m.isAnnotationPresent(ExtProperty.class);
            int arity = isVarargs ? -1 : paramCount - 1;

            StringBuilder desc = new StringBuilder("(");
            for (int i = 0; i < paramCount; i++) {
                if (isVarargs && i == paramCount - 1) {
                    desc.append("[Ljava/lang/Object;");
                } else {
                    desc.append("Ljava/lang/Object;");
                }
            }
            desc.append(")Ljava/lang/Object;");

            try {
                MethodHandle mh = lookup.unreflect(m);
                if (isVarargs) {
                    mh = mh.asVarargsCollector(Object[].class);
                }
                MethodHandle finalMh = mh;
                Function<Object[], Object> impl = args -> {
                    try { return finalMh.invokeWithArguments(args); }
                    catch (RuntimeException e) { throw e; }
                    catch (Throwable t) { throw new RuntimeException(t); }
                };
                registerExtensionMethod(new ExtensionMethodInfo(
                        name, arity, targetType, owner, name, desc.toString(), impl, isVarargs, isProperty));
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to register extension method: " + name, e);
            }
        }
    }

    // ============ 查询 API ============

    /** 按名称查找任意类型的 stdlib 函数 */
    public static StdlibFunction get(String name) {
        return registry.get(name);
    }

    /** 按名称查找接收者 Lambda 函数，不匹配则返回 null */
    public static ReceiverLambdaInfo getReceiverLambda(String name) {
        StdlibFunction func = registry.get(name);
        return func instanceof ReceiverLambdaInfo ? (ReceiverLambdaInfo) func : null;
    }

    /** 获取所有已注册的 stdlib 函数 */
    public static Collection<StdlibFunction> getAll() {
        return Collections.unmodifiableCollection(registry.values());
    }

    /** 获取所有接收者 Lambda 函数 */
    public static List<ReceiverLambdaInfo> getReceiverLambdas() {
        List<ReceiverLambdaInfo> result = new ArrayList<>();
        for (StdlibFunction func : registry.values()) {
            if (func instanceof ReceiverLambdaInfo) {
                result.add((ReceiverLambdaInfo) func);
            }
        }
        return result;
    }

    /** 按名称查找原生函数，不匹配则返回 null */
    public static NativeFunctionInfo getNativeFunction(String name) {
        StdlibFunction func = registry.get(name);
        return func instanceof NativeFunctionInfo ? (NativeFunctionInfo) func : null;
    }

    /** 获取所有原生函数 */
    public static List<NativeFunctionInfo> getNativeFunctions() {
        List<NativeFunctionInfo> result = new ArrayList<>();
        for (StdlibFunction func : registry.values()) {
            if (func instanceof NativeFunctionInfo) {
                result.add((NativeFunctionInfo) func);
            }
        }
        return result;
    }

    /** 按名称查找 Supplier Lambda 函数，不匹配则返回 null */
    public static SupplierLambdaInfo getSupplierLambda(String name) {
        StdlibFunction func = registry.get(name);
        return func instanceof SupplierLambdaInfo ? (SupplierLambdaInfo) func : null;
    }

    /** 获取所有 Supplier Lambda 函数 */
    public static List<SupplierLambdaInfo> getSupplierLambdas() {
        List<SupplierLambdaInfo> result = new ArrayList<>();
        for (StdlibFunction func : registry.values()) {
            if (func instanceof SupplierLambdaInfo) {
                result.add((SupplierLambdaInfo) func);
            }
        }
        return result;
    }

    /** 按名称查找常量，不匹配则返回 null */
    public static ConstantInfo getConstant(String name) {
        StdlibFunction func = registry.get(name);
        return func instanceof ConstantInfo ? (ConstantInfo) func : null;
    }

    /** 获取所有常量 */
    public static List<ConstantInfo> getConstants() {
        List<ConstantInfo> result = new ArrayList<>();
        for (StdlibFunction func : registry.values()) {
            if (func instanceof ConstantInfo) {
                result.add((ConstantInfo) func);
            }
        }
        return result;
    }

    /** 获取指定类型和方法名的所有重载 */
    public static List<ExtensionMethodInfo> getExtensionMethodOverloads(String targetType, String methodName) {
        Map<String, List<ExtensionMethodInfo>> methods = extensionMethods.get(targetType);
        if (methods == null) return Collections.emptyList();
        List<ExtensionMethodInfo> overloads = methods.get(methodName);
        return overloads != null ? overloads : Collections.emptyList();
    }

    /** 按目标类型和方法名查找扩展方法，匹配 arity */
    public static ExtensionMethodInfo getExtensionMethod(String targetType, String methodName, int arity) {
        Map<String, List<ExtensionMethodInfo>> methods = extensionMethods.get(targetType);
        if (methods == null) return null;
        List<ExtensionMethodInfo> overloads = methods.get(methodName);
        if (overloads == null) return null;
        // 1. 精确 arity 匹配
        ExtensionMethodInfo varargsFallback = null;
        for (ExtensionMethodInfo info : overloads) {
            if (info.arity == arity) return info;
            if (info.isVarargs && varargsFallback == null) varargsFallback = info;
        }
        // 2. 变参方法匹配任意 arity
        if (varargsFallback != null) return varargsFallback;
        // 3. 兜底
        return overloads.isEmpty() ? null : overloads.get(0);
    }

    /** NovaDynamic 用：按 Java Class 查找（遍历继承链 + 所有接口，含超接口） */
    public static ExtensionMethodInfo findExtensionMethod(Class<?> receiverClass, String methodName, int arity) {
        // 1. 遍历类继承链
        for (Class<?> cls = receiverClass; cls != null; cls = cls.getSuperclass()) {
            String internalName = cls.getName().replace('.', '/');
            ExtensionMethodInfo info = getExtensionMethod(internalName, methodName, arity);
            if (info != null) return info;
        }
        // 2. 遍历继承链中所有类的接口（递归遍历超接口）
        for (Class<?> cls = receiverClass; cls != null; cls = cls.getSuperclass()) {
            ExtensionMethodInfo info = findInInterfaces(cls.getInterfaces(), methodName, arity);
            if (info != null) return info;
        }
        return null;
    }

    /** 递归搜索接口及其超接口 */
    private static ExtensionMethodInfo findInInterfaces(Class<?>[] interfaces, String methodName, int arity) {
        for (Class<?> iface : interfaces) {
            String internalName = iface.getName().replace('.', '/');
            ExtensionMethodInfo info = getExtensionMethod(internalName, methodName, arity);
            if (info != null) return info;
            // 递归搜索超接口
            info = findInInterfaces(iface.getInterfaces(), methodName, arity);
            if (info != null) return info;
        }
        return null;
    }

    // ============ 初始化 ============

    static {
        Strings.register();
        NovaCollections.register();
        StdlibMath.register();
        StdlibTypeChecks.register();
        StdlibErrors.register();
        StdlibConstants.register();
        StdlibRandom.register();
        Concurrency.register();
        StdlibUtils.register();
        registerExtensionMethods(ListExtensions.class);
        registerExtensionMethods(MapExtensions.class);
        registerExtensionMethods(StringExtensions.class);
        registerExtensionMethods(SetExtensions.class);
        registerExtensionMethods(CharExtensions.class);
        registerExtensionMethods(NumberExtensions.class);
    }
}
