package com.novalang.runtime;

import com.novalang.runtime.stdlib.StdlibRegistry;

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
 * // 调用已注册的函数（类型安全 API）
 * NovaValue result = runtime.call("add", 1, 2);
 * int sum = result.toJava(int.class);  // 类型安全: 3
 *
 * NovaValue ext = runtime.callExtension("hello", "shout");
 * String s = ext.toJava(String.class);  // 类型安全: HELLO!
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
     * 获取全局变量（类型安全）
     *
     * @param name 变量名
     * @return 包装为 NovaValue 的变量值，不存在时返回 {@link NovaNull#NULL}
     */
    public NovaValue global(String name) {
        Object value = globals.get(name);
        if (value == null) return NovaNull.NULL;
        if (value instanceof NovaValue) return (NovaValue) value;
        return AbstractNovaValue.fromJava(value);
    }

    /**
     * 获取全局变量
     *
     * @deprecated 使用 {@link #global(String)} 替代，返回类型安全的 NovaValue
     */
    @Deprecated
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
     * 调用注册的函数（类型安全）
     *
     * @param name 函数名
     * @param args 参数
     * @return 包装为 NovaValue 的返回值
     */
    public NovaValue call(String name, Object... args) throws Exception {
        FunctionRegistry.RegisteredFunction func = functionRegistry.lookup(name);
        if (func == null) {
            throw new NoSuchMethodException("Function not found: " + name);
        }
        Object result = func.invoke(args);
        if (result == null) return NovaNull.NULL;
        if (result instanceof NovaValue) return (NovaValue) result;
        return AbstractNovaValue.fromJava(result);
    }

    /**
     * 调用扩展方法（类型安全）
     *
     * @param receiver   接收者对象
     * @param methodName 方法名
     * @param args       参数
     * @return 包装为 NovaValue 的返回值
     */
    public NovaValue callExtension(Object receiver, String methodName, Object... args) throws Exception {
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
        Object result = ext.invoke(receiver, args);
        if (result == null) return NovaNull.NULL;
        if (result instanceof NovaValue) return (NovaValue) result;
        return AbstractNovaValue.fromJava(result);
    }

    /**
     * 调用注册的函数
     *
     * @deprecated 使用 {@link #call(String, Object...)} 替代，返回类型安全的 NovaValue
     */
    @Deprecated
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
     * @deprecated 使用 {@link #callExtension(Object, String, Object...)} 替代，返回类型安全的 NovaValue
     */
    @Deprecated
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

    // ============ ExecutionContext ThreadLocal ============

    /** 当前线程的 ExecutionContext（Interpreter 在执行 MIR 前设置） */
    private static final ThreadLocal<ExecutionContext> CURRENT_CONTEXT = new ThreadLocal<>();

    /** 获取当前 ExecutionContext（LambdaUtils 等统一路径调用） */
    public static ExecutionContext currentContext() {
        return CURRENT_CONTEXT.get();
    }

    /** 设置当前 ExecutionContext（Interpreter 在 eval 前调用） */
    public static void setCurrentContext(ExecutionContext ctx) {
        CURRENT_CONTEXT.set(ctx);
    }

    /** 清除当前 ExecutionContext */
    public static void clearCurrentContext() {
        CURRENT_CONTEXT.remove();
    }

    // ============ 静态内置函数调用（内部运行时统一入口） ============

    /**
     * 调用内置函数（静态入口）。
     *
     * <p>解释器路径（StaticMethodDispatcher）和编译路径（NovaScriptContext/NovaBootstrap）
     * 统一通过此方法调用 StdlibRegistry 中注册的内置函数。</p>
     *
     * @param name 函数名
     * @param args 参数（raw Java Object）
     * @return 返回值（raw Java Object），未找到时返回 null
     */
    /** 未找到函数的 sentinel 值 */
    public static final Object NOT_FOUND = new Object();

    public static Object callBuiltin(String name, Object... args) {
        StdlibRegistry.NativeFunctionInfo nf = StdlibRegistry.getNativeFunction(name);
        if (nf != null) return nf.impl.apply(args); // 可能返回 null（void 函数），这是有效返回值
        if (args.length == 0) {
            StdlibRegistry.ConstantInfo ci = StdlibRegistry.getConstant(name);
            if (ci != null) return ci.value;
        }
        return NOT_FOUND; // 函数未找到
    }

    /**
     * 检查内置函数是否已注册。
     */
    public static boolean hasBuiltin(String name) {
        return StdlibRegistry.get(name) != null;
    }
}
