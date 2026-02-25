package nova.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NovaLang 运行时
 *
 * <p>提供脚本执行、函数注册、扩展方法注册等核心功能。</p>
 *
 * <pre>
 * NovaRuntime runtime = NovaRuntime.create();
 *
 * // 注册全局函数
 * runtime.registerFunction("add", Integer.class, Integer.class, Integer.class,
 *     (a, b) -> a + b);
 *
 * // 注册扩展方法
 * runtime.registerExtension(String.class, "shout", String.class,
 *     s -> s.toUpperCase() + "!");
 *
 * // 调用已注册的函数
 * runtime.invokeFunction("add", 1, 2);  // 返回: 3
 * runtime.invokeExtension("hello", "shout");  // 返回: HELLO!
 * </pre>
 */
public final class NovaRuntime {

    private final FunctionRegistry functionRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final Map<String, Object> globals;
    private final Map<String, Class<?>> registeredClasses;

    private NovaRuntime() {
        this.functionRegistry = new FunctionRegistry();
        this.extensionRegistry = new ExtensionRegistry();
        this.globals = new ConcurrentHashMap<String, Object>();
        this.registeredClasses = new ConcurrentHashMap<String, Class<?>>();
    }

    /**
     * 创建新的运行时实例
     */
    public static NovaRuntime create() {
        return new NovaRuntime();
    }

    // ============ 全局函数注册 ============

    /**
     * 注册通用函数
     *
     * @param name     函数名
     * @param function 函数实现
     */
    public void registerFunction(String name, NativeFunction<?> function) {
        functionRegistry.register(name, new Class<?>[0], Object.class, function);
    }

    /**
     * 注册无参数函数
     */
    public <R> void registerFunction(String name, Class<R> returnType, Function0<R> function) {
        functionRegistry.register(name, returnType, function);
    }

    /**
     * 注册单参数函数
     */
    public <T1, R> void registerFunction(String name, Class<T1> t1, Class<R> returnType,
                                          Function1<T1, R> function) {
        functionRegistry.register(name, t1, returnType, function);
    }

    /**
     * 注册双参数函数
     */
    public <T1, T2, R> void registerFunction(String name, Class<T1> t1, Class<T2> t2,
                                              Class<R> returnType, Function2<T1, T2, R> function) {
        functionRegistry.register(name, t1, t2, returnType, function);
    }

    /**
     * 注册三参数函数
     */
    public <T1, T2, T3, R> void registerFunction(String name, Class<T1> t1, Class<T2> t2,
                                                  Class<T3> t3, Class<R> returnType,
                                                  Function3<T1, T2, T3, R> function) {
        functionRegistry.register(name, t1, t2, t3, returnType, function);
    }

    // ============ 扩展方法注册 ============

    /**
     * 注册无参数扩展方法
     *
     * @param targetType 目标类型
     * @param methodName 方法名
     * @param returnType 返回类型
     * @param method     方法实现
     */
    public <T, R> void registerExtension(Class<T> targetType, String methodName,
                                          Class<R> returnType, Extension0<T, R> method) {
        extensionRegistry.register(targetType, methodName, returnType, method);
    }

    /**
     * 注册单参数扩展方法
     */
    public <T, A1, R> void registerExtension(Class<T> targetType, String methodName,
                                              Class<A1> a1, Class<R> returnType,
                                              Extension1<T, A1, R> method) {
        extensionRegistry.register(targetType, methodName, a1, returnType, method);
    }

    /**
     * 注册双参数扩展方法
     */
    public <T, A1, A2, R> void registerExtension(Class<T> targetType, String methodName,
                                                  Class<A1> a1, Class<A2> a2, Class<R> returnType,
                                                  Extension2<T, A1, A2, R> method) {
        extensionRegistry.register(targetType, methodName, a1, a2, returnType, method);
    }

    /**
     * 注册通用扩展方法
     */
    public void registerExtension(Class<?> targetType, String methodName, Class<?>[] paramTypes,
                                   Class<?> returnType, ExtensionMethod<?, ?> method) {
        extensionRegistry.register(targetType, methodName, paramTypes, returnType, method);
    }

    // ============ 全局变量 ============

    /**
     * 设置全局变量
     */
    public void setGlobal(String name, Object value) {
        globals.put(name, value);
    }

    /**
     * 获取全局变量
     */
    public Object getGlobal(String name) {
        return globals.get(name);
    }

    /**
     * 检查全局变量是否存在
     */
    public boolean hasGlobal(String name) {
        return globals.containsKey(name);
    }

    /**
     * 移除全局变量
     */
    public void removeGlobal(String name) {
        globals.remove(name);
    }

    // ============ 类注册 ============

    /**
     * 注册类供脚本使用
     *
     * @param alias 别名（在脚本中使用的名称）
     * @param clazz 实际类
     */
    public void registerClass(String alias, Class<?> clazz) {
        registeredClasses.put(alias, clazz);
    }

    /**
     * 获取注册的类
     */
    public Class<?> getRegisteredClass(String alias) {
        return registeredClasses.get(alias);
    }

    // ============ 内部访问 ============

    /**
     * 获取函数注册表（内部使用）
     */
    public FunctionRegistry getFunctionRegistry() {
        return functionRegistry;
    }

    /**
     * 获取扩展注册表（内部使用）
     */
    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    /**
     * 调用注册的函数
     *
     * @param name 函数名
     * @param args 参数
     * @return 返回值
     */
    public Object invokeFunction(String name, Object... args) throws Exception {
        FunctionRegistry.RegisteredFunction func = functionRegistry.lookup(name);
        if (func == null) {
            throw new NoSuchMethodException("Function not found: " + name);
        }
        return func.invoke(args);
    }

    /**
     * 调用扩展方法
     *
     * @param receiver   接收者对象
     * @param methodName 方法名
     * @param args       参数
     * @return 返回值
     */
    public Object invokeExtension(Object receiver, String methodName, Object... args) throws Exception {
        if (receiver == null) {
            throw new NullPointerException("Cannot invoke extension method on null receiver");
        }

        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }

        ExtensionRegistry.RegisteredExtension ext =
                extensionRegistry.lookup(receiver.getClass(), methodName, argTypes);
        if (ext == null) {
            throw new NoSuchMethodException("Extension method not found: " +
                    receiver.getClass().getName() + "." + methodName);
        }
        return ext.invoke(receiver, args);
    }

    // ============ 链式 API ============

    /**
     * 创建扩展方法构建器
     */
    public ExtensionBuilder extensions() {
        return new ExtensionBuilder(this);
    }

    /**
     * 扩展方法链式构建器
     */
    public static final class ExtensionBuilder {
        private final NovaRuntime runtime;

        private ExtensionBuilder(NovaRuntime runtime) {
            this.runtime = runtime;
        }

        /**
         * 为指定类型添加扩展
         */
        public <T> TypeExtensionBuilder<T> forType(Class<T> type) {
            return new TypeExtensionBuilder<T>(this, type);
        }

        /**
         * 完成注册（返回运行时以便链式调用）
         */
        public NovaRuntime register() {
            return runtime;
        }
    }

    /**
     * 特定类型的扩展方法构建器
     */
    public static final class TypeExtensionBuilder<T> {
        private final ExtensionBuilder parent;
        private final Class<T> type;

        private TypeExtensionBuilder(ExtensionBuilder parent, Class<T> type) {
            this.parent = parent;
            this.type = type;
        }

        /**
         * 添加无参数扩展方法
         */
        public <R> TypeExtensionBuilder<T> add(String name, Class<R> returnType, Extension0<T, R> method) {
            parent.runtime.registerExtension(type, name, returnType, method);
            return this;
        }

        /**
         * 添加单参数扩展方法
         */
        public <A1, R> TypeExtensionBuilder<T> add(String name, Class<A1> a1, Class<R> returnType,
                                                    Extension1<T, A1, R> method) {
            parent.runtime.registerExtension(type, name, a1, returnType, method);
            return this;
        }

        /**
         * 添加双参数扩展方法
         */
        public <A1, A2, R> TypeExtensionBuilder<T> add(String name, Class<A1> a1, Class<A2> a2,
                                                        Class<R> returnType, Extension2<T, A1, A2, R> method) {
            parent.runtime.registerExtension(type, name, a1, a2, returnType, method);
            return this;
        }

        /**
         * 切换到另一个类型
         */
        public <U> TypeExtensionBuilder<U> forType(Class<U> type) {
            return parent.forType(type);
        }

        /**
         * 完成注册
         */
        public NovaRuntime register() {
            return parent.register();
        }
    }
}
