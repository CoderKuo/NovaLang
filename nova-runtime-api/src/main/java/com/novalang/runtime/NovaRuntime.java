package com.novalang.runtime;

import com.novalang.runtime.stdlib.StdlibRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * NovaLang 运行时 — 全局函数注册表 + 命名空间。
 *
 * <p>通过 {@link #shared()} 获取全局单例，一次注册所有 Nova 实例自动可见。</p>
 *
 * <h3>基本用法</h3>
 * <pre>
 * // 全局注册（无命名空间，可覆盖）
 * NovaRuntime.shared().register("getPlayer", (name) -> server.getPlayer(name));
 *
 * // 带命名空间（全限定名不冲突）
 * NovaRuntime.shared().register("getPlayer", (name) -> ..., "pluginA");
 * NovaRuntime.shared().register("getPlayer", (name) -> ..., "pluginB");
 * // 脚本中：getPlayer("Steve") → 调用后注册的 pluginB 版本
 * // 脚本中：pluginA.getPlayer("Steve") → 调用 pluginA 的版本
 *
 * // 库注册
 * NovaRuntime.shared().defineLibrary("http", lib -> {
 *     lib.function("get", (url) -> httpGet(url));
 *     lib.function("post", (url, body) -> httpPost(url, body));
 * });
 * // 脚本中：http.get("https://...")
 *
 * // 发现
 * NovaRuntime.shared().listFunctions();       // 全部函数
 * NovaRuntime.shared().listNamespaces();       // 所有命名空间
 * NovaRuntime.shared().describe("getPlayer");  // 函数描述
 * </pre>
 */
public final class NovaRuntime {

    // ============ 全局单例 ============

    private static volatile NovaRuntime SHARED;

    // ============ 跨 ClassLoader 全局桥接 ============
    // 利用 System.getProperties()（JVM 全局单例，所有 ClassLoader 共享）
    // 实现不同插件 relocate 后的 NovaRuntime 之间的函数/变量共享。
    // 函数用 java.util.function.Function<Object[], Object>（bootstrap ClassLoader）传递。

    // 使用不含 "nova." 或 "com.novalang." 前缀的 key，避免被 shadow relocate 改写
    private static final String GLOBAL_REGISTRY_KEY = "__novalang_shared_registry__";
    private static final String GLOBAL_NS_KEY = "__novalang_shared_namespaces__";

    /** 获取 JVM 全局函数注册表（跨 ClassLoader 共享） */
    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, Object[]> getGlobalRegistry() {
        synchronized (System.getProperties()) {
            Object reg = System.getProperties().get(GLOBAL_REGISTRY_KEY);
            if (reg instanceof ConcurrentHashMap) {
                return (ConcurrentHashMap<String, Object[]>) reg;
            }
            ConcurrentHashMap<String, Object[]> newReg = new ConcurrentHashMap<>();
            System.getProperties().put(GLOBAL_REGISTRY_KEY, newReg);
            return newReg;
        }
    }

    /** 获取 JVM 全局命名空间表 */
    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<String, ConcurrentHashMap<String, Object[]>> getGlobalNamespaces() {
        synchronized (System.getProperties()) {
            Object ns = System.getProperties().get(GLOBAL_NS_KEY);
            if (ns instanceof ConcurrentHashMap) {
                return (ConcurrentHashMap<String, ConcurrentHashMap<String, Object[]>>) ns;
            }
            ConcurrentHashMap<String, ConcurrentHashMap<String, Object[]>> newNs = new ConcurrentHashMap<>();
            System.getProperties().put(GLOBAL_NS_KEY, newNs);
            return newNs;
        }
    }

    /**
     * 将函数/变量写入 JVM 全局注册表（跨 ClassLoader 可见）。
     * entry 格式: Object[] { name, namespace, description, isFunction, invoker, value }
     * invoker: java.util.function.Function&lt;Object[], Object&gt;（bootstrap ClassLoader）
     */
    private static void publishToGlobal(String name, String namespace, String description,
                                         boolean isFunction, java.util.function.Function<Object[], Object> invoker, Object value) {
        Object[] entry = new Object[] { name, namespace, description, isFunction, invoker, value };
        getGlobalRegistry().put(name, entry);
        if (namespace != null) {
            getGlobalNamespaces()
                    .computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                    .put(name, entry);
        }
    }

    /** 从 JVM 全局注册表查找函数并调用 */
    @SuppressWarnings("unchecked")
    public static Object callGlobal(String name, Object... args) {
        Object[] entry = getGlobalRegistry().get(name);
        if (entry == null) return NOT_FOUND;
        boolean isFunction = (Boolean) entry[3];
        if (!isFunction) return entry[5]; // 变量直接返回 value
        java.util.function.Function<Object[], Object> invoker =
                (java.util.function.Function<Object[], Object>) entry[4];
        return invoker.apply(args);
    }

    /** 检查 JVM 全局桥接注册表是否有指定名称 */
    static boolean hasGlobalBridge(String name) {
        return getGlobalRegistry().containsKey(name);
    }

    /** 注销某命名空间的全局注册（插件卸载时调用） */
    public static void unpublishNamespace(String namespace) {
        ConcurrentHashMap<String, Object[]> nsMap = getGlobalNamespaces().remove(namespace);
        if (nsMap != null) {
            ConcurrentHashMap<String, Object[]> global = getGlobalRegistry();
            for (Map.Entry<String, Object[]> e : nsMap.entrySet()) {
                global.remove(e.getKey(), e.getValue());
            }
        }
    }

    /** 将 JVM 全局桥接 entry (Object[]) 转为 RegisteredEntry */
    @SuppressWarnings("unchecked")
    private static RegisteredEntry globalEntryToRegistered(Object[] entry) {
        String name = (String) entry[0];
        String namespace = (String) entry[1];
        String description = (String) entry[2];
        boolean isFunction = (Boolean) entry[3];
        if (isFunction) {
            java.util.function.Function<Object[], Object> invoker =
                    (java.util.function.Function<Object[], Object>) entry[4];
            return new RegisteredEntry(name, namespace, "global-bridge", description,
                    (NativeFunction<Object>) invoker::apply, new Class<?>[0], Object.class);
        } else {
            return new RegisteredEntry(name, namespace, "global-bridge", description, entry[5]);
        }
    }

    /** 获取 JVM 全局桥接 entry 的 qualifiedName */
    private static String globalEntryQualifiedName(Object[] entry) {
        String name = (String) entry[0];
        String namespace = (String) entry[1];
        return namespace != null ? namespace + "." + name : name;
    }

    /** 获取全局共享运行时（懒初始化，线程安全，自动启动 HTTP API 服务） */
    public static NovaRuntime shared() {
        if (SHARED == null) {
            synchronized (NovaRuntime.class) {
                if (SHARED == null) {
                    SHARED = new NovaRuntime();
                    tryAutoStartHttpServer();
                }
            }
        }
        return SHARED;
    }

    /** 尝试自动启动 HTTP API 服务（通过反射，避免 nova-runtime-api 依赖 nova-runtime） */
    private static void tryAutoStartHttpServer() {
        try {
            // 用当前类的包名推导 NovaApiServer 的全限定名（relocate 安全）
            String basePkg = NovaRuntime.class.getPackage().getName(); // com.novalang.runtime 或 relocate 后的包
            String serverClassName = basePkg + ".http.NovaApiServer";
            Class<?> serverClass = Class.forName(serverClassName, true, NovaRuntime.class.getClassLoader());
            serverClass.getMethod("startDefault").invoke(null);
        } catch (ClassNotFoundException ignored) {
            // nova-runtime 未在 ClassPath 上（纯 API 模式），跳过
        } catch (Exception ignored) {
            // 启动失败（端口占用等），不影响核心功能
        }
    }

    /** 创建独立运行时实例（不影响全局单例） */
    public static NovaRuntime create() {
        return new NovaRuntime();
    }

    // ============ 核心数据结构 ============

    /** 已注册条目（函数或变量） */
    public static final class RegisteredEntry {
        private final String name;
        private final String namespace;    // nullable: 全局注册
        private final String source;       // nullable: 来源标识
        private final String description;  // nullable: 补全时显示的描述
        private final boolean isFunction;
        private final Object function;
        private final Object value;
        private final Class<?>[] paramTypes;
        private final Class<?> returnType;

        /** 函数条目 */
        RegisteredEntry(String name, String namespace, String source, String description,
                        Object function, Class<?>[] paramTypes, Class<?> returnType) {
            this.name = name;
            this.namespace = namespace;
            this.source = source;
            this.description = description;
            this.isFunction = true;
            this.function = function;
            this.value = null;
            this.paramTypes = paramTypes;
            this.returnType = returnType;
        }

        /** 变量条目 */
        RegisteredEntry(String name, String namespace, String source, String description, Object value) {
            this.name = name;
            this.namespace = namespace;
            this.source = source;
            this.description = description;
            this.isFunction = false;
            this.function = null;
            this.value = value;
            this.paramTypes = new Class<?>[0];
            this.returnType = value != null ? value.getClass() : Object.class;
        }

        public String getName() { return name; }
        public String getNamespace() { return namespace; }
        public String getSource() { return source; }
        public String getDescription() { return description; }
        public boolean isFunction() { return isFunction; }
        public Object getFunction() { return function; }
        public Object getValue() { return isFunction ? function : value; }
        public Class<?>[] getParamTypes() { return paramTypes; }
        public Class<?> getReturnType() { return returnType; }

        /** 全限定名：有命名空间返回 "ns.name"，无则返回 "name" */
        public String getQualifiedName() {
            return namespace != null ? namespace + "." + name : name;
        }

        /** 调用此函数（变量条目直接返回值，参数不足用 null 补齐） */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public Object invoke(Object... args) {
            if (!isFunction) return value;
            try {
                if (function instanceof NativeFunction) {
                    return ((NativeFunction) function).invoke(args);
                }
                // 按 FunctionN 类型匹配，参数不足用 null 补齐
                Object a0 = args.length > 0 ? args[0] : null;
                Object a1 = args.length > 1 ? args[1] : null;
                Object a2 = args.length > 2 ? args[2] : null;
                Object a3 = args.length > 3 ? args[3] : null;
                Object a4 = args.length > 4 ? args[4] : null;
                if (function instanceof Function0) return ((Function0) function).invoke();
                if (function instanceof Function1) return ((Function1) function).invoke(a0);
                if (function instanceof Function2) return ((Function2) function).invoke(a0, a1);
                if (function instanceof Function3) return ((Function3) function).invoke(a0, a1, a2);
                if (function instanceof Function4) return ((Function4) function).invoke(a0, a1, a2, a3);
                if (function instanceof Function5) return ((Function5) function).invoke(a0, a1, a2, a3, a4);
            } catch (NovaException e) {
                throw e; // Nova 异常原样传播
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                throw new NovaException("调用函数 '" + getQualifiedName() + "' 失败: " + msg
                        + (source != null ? " [来源: " + source + "]" : ""), e);
            }
            // 函数类型无法识别
            String funcType = resolveFunctionType();
            throw new NovaException("函数 '" + getQualifiedName() + "' 无法调用: "
                    + "传入 " + args.length + " 个参数，但函数类型为 " + funcType
                    + (source != null ? " [来源: " + source + "]" : ""));
        }

        private String resolveFunctionType() {
            if (function instanceof Function0) return "无参函数";
            if (function instanceof Function1) return "单参函数";
            if (function instanceof Function2) return "双参函数";
            if (function instanceof Function3) return "三参函数";
            if (function instanceof NativeFunction) return "原生函数";
            return "未知类型 (" + function.getClass().getSimpleName() + ")";
        }
    }

    /** 短名 → 最后注册的条目（可覆盖） */
    private final Map<String, RegisteredEntry> globalFunctions = new ConcurrentHashMap<>();

    /** namespace → (funcName → entry)（全限定名不冲突） */
    private final Map<String, Map<String, RegisteredEntry>> namespacedFunctions = new ConcurrentHashMap<>();

    /** 命名空间代理对象缓存：namespace → NovaNamespace */
    private final Map<String, NovaNamespace> namespaceProxies = new ConcurrentHashMap<>();

    // 保留原有字段（向后兼容 create() 实例）
    private final FunctionRegistry functionRegistry;
    private final ExtensionRegistry extensionRegistry;
    private final Map<String, Object> globals;
    private final Map<String, Class<?>> registeredClasses;

    private NovaRuntime() {
        this.functionRegistry = new FunctionRegistry();
        this.extensionRegistry = new ExtensionRegistry();
        this.globals = new ConcurrentHashMap<>();
        this.registeredClasses = new ConcurrentHashMap<>();
    }

    // ============ 注册 API（新） ============

    /** 注册全局函数（无命名空间，可被后续同名注册覆盖） */
    @SuppressWarnings("unchecked")
    public NovaRuntime register(String name, Function0<?> func) {
        return registerInternal(name, null, null, func, new Class<?>[0], Object.class);
    }

    @SuppressWarnings("unchecked")
    public NovaRuntime register(String name, Function1<?, ?> func) {
        return registerInternal(name, null, null, func, new Class<?>[]{Object.class}, Object.class);
    }

    @SuppressWarnings("unchecked")
    public NovaRuntime register(String name, Function2<?, ?, ?> func) {
        return registerInternal(name, null, null, func, new Class<?>[]{Object.class, Object.class}, Object.class);
    }

    @SuppressWarnings("unchecked")
    public NovaRuntime register(String name, Function3<?, ?, ?, ?> func) {
        return registerInternal(name, null, null, func,
                new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
    }

    /** 注册带命名空间的函数 */
    public NovaRuntime register(String name, Function0<?> func, String namespace) {
        return registerInternal(name, namespace, null, func, new Class<?>[0], Object.class);
    }

    public NovaRuntime register(String name, Function1<?, ?> func, String namespace) {
        return registerInternal(name, namespace, null, func, new Class<?>[]{Object.class}, Object.class);
    }

    public NovaRuntime register(String name, Function2<?, ?, ?> func, String namespace) {
        return registerInternal(name, namespace, null, func,
                new Class<?>[]{Object.class, Object.class}, Object.class);
    }

    public NovaRuntime register(String name, Function3<?, ?, ?, ?> func, String namespace) {
        return registerInternal(name, namespace, null, func,
                new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
    }

    /** 注册带命名空间和来源标识的函数 */
    public NovaRuntime register(String name, Function1<?, ?> func, String namespace, String source) {
        return registerInternal(name, namespace, source, func, new Class<?>[]{Object.class}, Object.class);
    }

    /** 注册通用 NativeFunction */
    public NovaRuntime register(String name, NativeFunction<?> func) {
        return registerInternal(name, null, null, func, new Class<?>[0], Object.class);
    }

    public NovaRuntime register(String name, NativeFunction<?> func, String namespace) {
        return registerInternal(name, namespace, null, func, new Class<?>[0], Object.class);
    }

    /** 注册变参函数 */
    public NovaRuntime registerVararg(String name, Function1<Object[], ?> func) {
        return registerInternal(name, null, null, (NativeFunction<Object>) func::invoke,
                new Class<?>[0], Object.class);
    }

    public NovaRuntime registerVararg(String name, Function1<Object[], ?> func, String namespace) {
        return registerInternal(name, namespace, null, (NativeFunction<Object>) func::invoke,
                new Class<?>[0], Object.class);
    }

    // ============ 带描述的注册 API ============

    /** 注册函数（带描述，用于补全提示） */
    public NovaRuntime register(String name, Function0<?> func, String namespace, String source, String description) {
        return registerInternal(name, namespace, source, description, func, new Class<?>[0], Object.class);
    }
    public NovaRuntime register(String name, Function1<?, ?> func, String namespace, String source, String description) {
        return registerInternal(name, namespace, source, description, func, new Class<?>[]{Object.class}, Object.class);
    }
    public NovaRuntime register(String name, Function2<?, ?, ?> func, String namespace, String source, String description) {
        return registerInternal(name, namespace, source, description, func, new Class<?>[]{Object.class, Object.class}, Object.class);
    }
    public NovaRuntime register(String name, Function3<?, ?, ?, ?> func, String namespace, String source, String description) {
        return registerInternal(name, namespace, source, description, func, new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
    }

    // ============ 按 arity 命名的便捷注册（Kotlin 友好，无需泛型强转） ============

    public NovaRuntime register0(String name, Function0<?> func) {
        return registerInternal(name, null, null, func, new Class<?>[0], Object.class);
    }
    public NovaRuntime register0(String name, Function0<?> func, String description) {
        return registerInternal(name, null, null, description, func, new Class<?>[0], Object.class);
    }
    public NovaRuntime register1(String name, Function1<?, ?> func) {
        return registerInternal(name, null, null, func, new Class<?>[]{Object.class}, Object.class);
    }
    public NovaRuntime register1(String name, Function1<?, ?> func, String description) {
        return registerInternal(name, null, null, description, func, new Class<?>[]{Object.class}, Object.class);
    }
    public NovaRuntime register2(String name, Function2<?, ?, ?> func) {
        return registerInternal(name, null, null, func, new Class<?>[]{Object.class, Object.class}, Object.class);
    }
    public NovaRuntime register2(String name, Function2<?, ?, ?> func, String description) {
        return registerInternal(name, null, null, description, func, new Class<?>[]{Object.class, Object.class}, Object.class);
    }
    public NovaRuntime register3(String name, Function3<?, ?, ?, ?> func) {
        return registerInternal(name, null, null, func, new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
    }
    public NovaRuntime register3(String name, Function3<?, ?, ?, ?> func, String description) {
        return registerInternal(name, null, null, description, func, new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
    }

    // ── SAM 便捷注册（复用 LibraryBuilder 的 SAM 接口，Kotlin 零泛型） ──

    public NovaRuntime fn0(String name, LibraryBuilder.Fn0 func) { return register0(name, func::invoke); }
    public NovaRuntime fn0(String name, LibraryBuilder.Fn0 func, String desc) { return register0(name, func::invoke, desc); }
    public NovaRuntime fn1(String name, LibraryBuilder.Fn1 func) { return register1(name, func::invoke); }
    public NovaRuntime fn1(String name, LibraryBuilder.Fn1 func, String desc) { return register1(name, func::invoke, desc); }
    public NovaRuntime fn2(String name, LibraryBuilder.Fn2 func) { return register2(name, func::invoke); }
    public NovaRuntime fn2(String name, LibraryBuilder.Fn2 func, String desc) { return register2(name, func::invoke, desc); }
    public NovaRuntime fn3(String name, LibraryBuilder.Fn3 func) { return register3(name, func::invoke); }
    public NovaRuntime fn3(String name, LibraryBuilder.Fn3 func, String desc) { return register3(name, func::invoke, desc); }

    // ============ 变量注册 API ============

    /** 注册全局变量（无命名空间，可覆盖） */
    public NovaRuntime set(String name, Object value) {
        return setInternal(name, null, null, null, value);
    }

    public NovaRuntime set(String name, Object value, String namespace) {
        return setInternal(name, namespace, null, null, value);
    }

    public NovaRuntime set(String name, Object value, String namespace, String source) {
        return setInternal(name, namespace, source, null, value);
    }

    /** 注册变量（带描述） */
    public NovaRuntime set(String name, Object value, String namespace, String source, String description) {
        return setInternal(name, namespace, source, description, value);
    }

    private NovaRuntime setInternal(String name, String namespace, String source, String description, Object value) {
        RegisteredEntry entry = new RegisteredEntry(name, namespace, source, description, value);
        globalFunctions.put(name, entry);
        if (namespace != null) {
            namespacedFunctions
                    .computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                    .put(name, entry);
        }

        // 同步写入 JVM 全局桥接
        publishToGlobal(name, namespace, description, false, null, value);

        return this;
    }

    /** 内部注册入口 */
    public NovaRuntime registerInternal(String name, String namespace, String source,
                                         Object func, Class<?>[] paramTypes, Class<?> returnType) {
        return registerInternal(name, namespace, source, null, func, paramTypes, returnType);
    }

    /** 内部注册入口（带描述） */
    public NovaRuntime registerInternal(String name, String namespace, String source, String description,
                                         Object func, Class<?>[] paramTypes, Class<?> returnType) {
        RegisteredEntry entry = new RegisteredEntry(name, namespace, source, description, func, paramTypes, returnType);

        // 写入本实例
        globalFunctions.put(name, entry);
        if (namespace != null) {
            namespacedFunctions
                    .computeIfAbsent(namespace, k -> new ConcurrentHashMap<>())
                    .put(name, entry);
        }

        // 同步写入 JVM 全局桥接（跨 ClassLoader 共享）
        final RegisteredEntry e = entry;
        publishToGlobal(name, namespace, description, true,
                args -> e.invoke(args), null);

        return this;
    }

    // ============ 库注册 ============

    /**
     * 定义函数库（命名空间 = 库名）。
     * <pre>
     * NovaRuntime.shared().defineLibrary("http", lib -> {
     *     lib.function("get", (url) -> httpGet(url));
     *     lib.function("post", (url, body) -> httpPost(url, body));
     *     lib.constant("TIMEOUT", 30000);
     * });
     * </pre>
     */
    public NovaRuntime defineLibrary(String name, Consumer<LibraryBuilder> config) {
        LibraryBuilder builder = new LibraryBuilder(this, name);
        config.accept(builder);
        return this;
    }

    /** 函数库构建器 */
    public static final class LibraryBuilder {
        private final NovaRuntime runtime;
        private final String namespace;

        LibraryBuilder(NovaRuntime runtime, String namespace) {
            this.runtime = runtime;
            this.namespace = namespace;
        }

        public LibraryBuilder function(String name, Function0<?> func) {
            runtime.registerInternal(name, namespace, null, func, new Class<?>[0], Object.class);
            return this;
        }
        public LibraryBuilder function(String name, Function0<?> func, String description) {
            runtime.registerInternal(name, namespace, null, description, func, new Class<?>[0], Object.class);
            return this;
        }

        public LibraryBuilder function(String name, Function1<?, ?> func) {
            runtime.registerInternal(name, namespace, null, func,
                    new Class<?>[]{Object.class}, Object.class);
            return this;
        }
        public LibraryBuilder function(String name, Function1<?, ?> func, String description) {
            runtime.registerInternal(name, namespace, null, description, func,
                    new Class<?>[]{Object.class}, Object.class);
            return this;
        }

        public LibraryBuilder function(String name, Function2<?, ?, ?> func) {
            runtime.registerInternal(name, namespace, null, func,
                    new Class<?>[]{Object.class, Object.class}, Object.class);
            return this;
        }
        public LibraryBuilder function(String name, Function2<?, ?, ?> func, String description) {
            runtime.registerInternal(name, namespace, null, description, func,
                    new Class<?>[]{Object.class, Object.class}, Object.class);
            return this;
        }

        public LibraryBuilder function(String name, Function3<?, ?, ?, ?> func) {
            runtime.registerInternal(name, namespace, null, func,
                    new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
            return this;
        }
        public LibraryBuilder function(String name, Function3<?, ?, ?, ?> func, String description) {
            runtime.registerInternal(name, namespace, null, description, func,
                    new Class<?>[]{Object.class, Object.class, Object.class}, Object.class);
            return this;
        }

        public LibraryBuilder functionVararg(String name, Function1<Object[], ?> func) {
            runtime.registerInternal(name, namespace, null,
                    (NativeFunction<Object>) func::invoke, new Class<?>[0], Object.class);
            return this;
        }
        public LibraryBuilder functionVararg(String name, Function1<Object[], ?> func, String description) {
            runtime.registerInternal(name, namespace, null, description,
                    (NativeFunction<Object>) func::invoke, new Class<?>[0], Object.class);
            return this;
        }

        public LibraryBuilder constant(String name, Object value) {
            runtime.setInternal(name, namespace, null, null, value);
            return this;
        }

        public LibraryBuilder set(String name, Object value) {
            runtime.setInternal(name, namespace, null, null, value);
            return this;
        }
        public LibraryBuilder set(String name, Object value, String description) {
            runtime.setInternal(name, namespace, null, description, value);
            return this;
        }
        public LibraryBuilder constant(String name, Object value, String description) {
            runtime.setInternal(name, namespace, null, description, value);
            return this;
        }

        // ── SAM 接口便捷方法（Kotlin 零泛型调用） ──

        @FunctionalInterface public interface Fn0 { Object invoke(); }
        @FunctionalInterface public interface Fn1 { Object invoke(Object a); }
        @FunctionalInterface public interface Fn2 { Object invoke(Object a, Object b); }
        @FunctionalInterface public interface Fn3 { Object invoke(Object a, Object b, Object c); }
        @FunctionalInterface public interface FnN { Object invoke(Object[] args); }

        public LibraryBuilder fn0(String name, Fn0 func) {
            return function(name, (Function0<Object>) func::invoke);
        }
        public LibraryBuilder fn0(String name, Fn0 func, String desc) {
            return function(name, (Function0<Object>) func::invoke, desc);
        }
        public LibraryBuilder fn1(String name, Fn1 func) {
            return function(name, (Function1<Object, Object>) func::invoke);
        }
        public LibraryBuilder fn1(String name, Fn1 func, String desc) {
            return function(name, (Function1<Object, Object>) func::invoke, desc);
        }
        public LibraryBuilder fn2(String name, Fn2 func) {
            return function(name, (Function2<Object, Object, Object>) func::invoke);
        }
        public LibraryBuilder fn2(String name, Fn2 func, String desc) {
            return function(name, (Function2<Object, Object, Object>) func::invoke, desc);
        }
        public LibraryBuilder fn3(String name, Fn3 func) {
            return function(name, (Function3<Object, Object, Object, Object>) func::invoke);
        }
        public LibraryBuilder fn3(String name, Fn3 func, String desc) {
            return function(name, (Function3<Object, Object, Object, Object>) func::invoke, desc);
        }
        public LibraryBuilder fnN(String name, FnN func) {
            return functionVararg(name, (Function1<Object[], Object>) func::invoke);
        }
        public LibraryBuilder fnN(String name, FnN func, String desc) {
            return functionVararg(name, (Function1<Object[], Object>) func::invoke, desc);
        }
    }

    // ============ 查找 API ============

    /** 短名查找（返回最后注册的同名函数，回退 JVM 全局桥接） */
    public RegisteredEntry lookup(String name) {
        RegisteredEntry local = globalFunctions.get(name);
        if (local != null) return local;
        Object[] globalEntry = getGlobalRegistry().get(name);
        return globalEntry != null ? globalEntryToRegistered(globalEntry) : null;
    }

    /** 全限定查找：namespace + funcName（回退 JVM 全局桥接） */
    public RegisteredEntry lookup(String namespace, String funcName) {
        Map<String, RegisteredEntry> ns = namespacedFunctions.get(namespace);
        if (ns != null) {
            RegisteredEntry e = ns.get(funcName);
            if (e != null) return e;
        }
        ConcurrentHashMap<String, Object[]> globalNs = getGlobalNamespaces().get(namespace);
        if (globalNs != null) {
            Object[] entry = globalNs.get(funcName);
            if (entry != null) return globalEntryToRegistered(entry);
        }
        return null;
    }

    /** 点分全限定查找："ns.func" */
    public RegisteredEntry lookupQualified(String qualifiedName) {
        int dot = qualifiedName.indexOf('.');
        if (dot > 0 && dot < qualifiedName.length() - 1) {
            return lookup(qualifiedName.substring(0, dot), qualifiedName.substring(dot + 1));
        }
        return lookup(qualifiedName);
    }

    /** 查找命名空间代理对象（供解释器/编译器 ns.func() 语法使用，合并 JVM 全局桥接） */
    public NovaNamespace getNamespaceProxy(String namespace) {
        if (!namespacedFunctions.containsKey(namespace) && !getGlobalNamespaces().containsKey(namespace)) return null;
        return namespaceProxies.computeIfAbsent(namespace, ns -> new NovaNamespace(ns, this));
    }

    /** 调用已注册的函数（短名） */
    public Object callRegistered(String name, Object... args) {
        RegisteredEntry entry = globalFunctions.get(name);
        if (entry != null) return entry.invoke(args);
        return NOT_FOUND;
    }

    /** 调用已注册的函数（全限定名） */
    public Object callQualified(String qualifiedName, Object... args) {
        RegisteredEntry entry = lookupQualified(qualifiedName);
        if (entry != null) return entry.invoke(args);
        return NOT_FOUND;
    }

    // ============ 可发现性 API ============

    /** 列出所有已注册函数（合并本实例 + JVM 全局桥接） */
    public List<RegisteredEntry> listFunctions() {
        Map<String, RegisteredEntry> all = new LinkedHashMap<>();
        for (RegisteredEntry e : globalFunctions.values()) {
            all.put(e.getQualifiedName(), e);
        }
        for (Map<String, RegisteredEntry> ns : namespacedFunctions.values()) {
            for (RegisteredEntry e : ns.values()) {
                all.put(e.getQualifiedName(), e);
            }
        }
        // 合并 JVM 全局桥接
        for (Map.Entry<String, Object[]> entry : getGlobalRegistry().entrySet()) {
            String qn = globalEntryQualifiedName(entry.getValue());
            if (!all.containsKey(qn)) {
                all.put(qn, globalEntryToRegistered(entry.getValue()));
            }
        }
        return new ArrayList<>(all.values());
    }

    /** 列出指定命名空间的函数（合并本实例 + JVM 全局桥接） */
    public List<RegisteredEntry> listFunctions(String namespace) {
        Map<String, RegisteredEntry> local = namespacedFunctions.get(namespace);
        Map<String, RegisteredEntry> result = new LinkedHashMap<>();
        if (local != null) {
            for (RegisteredEntry e : local.values()) result.put(e.getName(), e);
        }
        // 合并 JVM 全局桥接
        ConcurrentHashMap<String, Object[]> globalNs = getGlobalNamespaces().get(namespace);
        if (globalNs != null) {
            for (Map.Entry<String, Object[]> entry : globalNs.entrySet()) {
                if (!result.containsKey(entry.getKey())) {
                    result.put(entry.getKey(), globalEntryToRegistered(entry.getValue()));
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    /** 列出所有命名空间（合并本实例 + JVM 全局桥接） */
    public List<String> listNamespaces() {
        Set<String> all = new LinkedHashSet<>(namespacedFunctions.keySet());
        all.addAll(getGlobalNamespaces().keySet());
        return new ArrayList<>(all);
    }

    /** 描述函数 */
    public String describe(String name) {
        RegisteredEntry entry = globalFunctions.get(name);
        if (entry == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append(entry.getQualifiedName()).append('(');
        for (int i = 0; i < entry.paramTypes.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(entry.paramTypes[i].getSimpleName());
        }
        sb.append(") → ").append(entry.returnType.getSimpleName());
        if (entry.source != null) sb.append("  [from: ").append(entry.source).append(']');
        return sb.toString();
    }

    /** 注销指定命名空间的所有函数（插件卸载时调用） */
    public void unregisterNamespace(String namespace) {
        Map<String, RegisteredEntry> removed = namespacedFunctions.remove(namespace);
        namespaceProxies.remove(namespace);
        if (removed != null) {
            for (Map.Entry<String, RegisteredEntry> e : removed.entrySet()) {
                globalFunctions.remove(e.getKey(), e.getValue());
            }
        }
        // 同步清理 JVM 全局桥接
        unpublishNamespace(namespace);
    }

    /**
     * 检查命名空间中是否存在指定函数/变量。
     * 无命名空间时检查全局注册表。
     */
    public boolean has(String name) {
        if (globalFunctions.containsKey(name)) return true;
        return getGlobalRegistry().containsKey(name);
    }

    public boolean has(String namespace, String name) {
        Map<String, RegisteredEntry> ns = namespacedFunctions.get(namespace);
        if (ns != null && ns.containsKey(name)) return true;
        ConcurrentHashMap<String, Object[]> globalNs = getGlobalNamespaces().get(namespace);
        return globalNs != null && globalNs.containsKey(name);
    }

    /**
     * 从命名空间中移除单个函数/变量。
     * 同时从 globalFunctions 移除（引用相等匹配，不影响其他命名空间同名函数）。
     */
    public void remove(String namespace, String name) {
        // 本实例
        Map<String, RegisteredEntry> ns = namespacedFunctions.get(namespace);
        if (ns != null) {
            RegisteredEntry removed = ns.remove(name);
            if (removed != null) {
                globalFunctions.remove(name, removed);
            }
            if (ns.isEmpty()) {
                namespacedFunctions.remove(namespace);
                namespaceProxies.remove(namespace);
            }
        }
        // JVM 全局桥接
        ConcurrentHashMap<String, Object[]> globalNs = getGlobalNamespaces().get(namespace);
        if (globalNs != null) {
            Object[] removed = globalNs.remove(name);
            if (removed != null) {
                getGlobalRegistry().remove(name, removed);
            }
            if (globalNs.isEmpty()) {
                getGlobalNamespaces().remove(namespace);
            }
        }
    }

    /** 从全局注册表中移除（无命名空间） */
    public void remove(String name) {
        globalFunctions.remove(name);
        getGlobalRegistry().remove(name);
    }

    /** 清空本实例所有注册（不影响其他插件的全局桥接） */
    public void clearAll() {
        // 只从全局桥接中移除本实例注册的条目（按引用匹配）
        ConcurrentHashMap<String, Object[]> globalReg = getGlobalRegistry();
        for (Map.Entry<String, RegisteredEntry> e : globalFunctions.entrySet()) {
            globalReg.remove(e.getKey());
        }
        for (Map.Entry<String, Map<String, RegisteredEntry>> nsEntry : namespacedFunctions.entrySet()) {
            ConcurrentHashMap<String, Object[]> globalNs = getGlobalNamespaces().get(nsEntry.getKey());
            if (globalNs != null) {
                for (String funcName : nsEntry.getValue().keySet()) {
                    globalNs.remove(funcName);
                }
                if (globalNs.isEmpty()) {
                    getGlobalNamespaces().remove(nsEntry.getKey());
                }
            }
        }
        // 清空本实例
        globalFunctions.clear();
        namespacedFunctions.clear();
        namespaceProxies.clear();
        functionRegistry.clear();
        globals.clear();
        registeredClasses.clear();
    }

    // ============ 命名空间代理对象 ============

    /**
     * 命名空间代理：脚本中 ns.func() 访问时返回此对象。
     * 实现 NovaValue 以便在解释器的 Environment 和编译器的 NovaDynamic 中正常分派。
     */
    public static final class NovaNamespace extends AbstractNovaValue {
        private final String name;
        private final NovaRuntime runtime;

        NovaNamespace(String name, NovaRuntime runtime) {
            this.name = name;
            this.runtime = runtime;
        }

        @Override public String getTypeName() { return "Namespace:" + name; }
        @Override public Object toJavaValue() { return this; }
        @Override public String asString() { return "namespace(" + name + ")"; }

        /** ns.member 成员访问 — 函数返回 NamespacedFunction，变量返回值 */
        @Override
        public NovaValue resolveMember(String memberName) {
            RegisteredEntry entry = runtime.lookup(name, memberName);
            if (entry == null) return null;
            if (entry.isFunction()) {
                return new NamespacedFunction(entry);
            }
            // 变量：直接返回值
            Object val = entry.getValue();
            if (val instanceof NovaValue) return (NovaValue) val;
            if (val == null) return NovaNull.NULL;
            return AbstractNovaValue.fromJava(val);
        }

        public String getNamespaceName() { return name; }
    }

    /** 命名空间下的函数包装，实现 NovaCallable 以便直接调用 */
    private static final class NamespacedFunction extends AbstractNovaValue implements NovaCallable {
        private final RegisteredEntry entry;

        NamespacedFunction(RegisteredEntry entry) { this.entry = entry; }

        @Override public String getTypeName() { return "Function"; }
        @Override public Object toJavaValue() { return this; }
        @Override public boolean isCallable() { return true; }
        @Override public String asString() { return entry.getQualifiedName(); }
        @Override public int getArity() { return entry.paramTypes.length; }
        @Override public String getName() { return entry.name; }

        @Override
        public NovaValue call(ExecutionContext context, java.util.List<NovaValue> args) {
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                javaArgs[i] = args.get(i).toJavaValue();
            }
            Object result = entry.invoke(javaArgs);
            if (result == null) return NovaNull.NULL;
            if (result instanceof NovaValue) return (NovaValue) result;
            return AbstractNovaValue.fromJava(result);
        }

        @Override
        public Object dynamicInvoke(NovaValue[] args) {
            Object[] javaArgs = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                javaArgs[i] = args[i] != null ? args[i].toJavaValue() : null;
            }
            return entry.invoke(javaArgs);
        }
    }

    // ============ 保留原有 API（实例级别） ============

    public void registerFunction(String name, NativeFunction<?> function) {
        functionRegistry.register(name, new Class<?>[0], Object.class, function);
    }

    public <R> void registerFunction(String name, Class<R> returnType, Function0<R> function) {
        functionRegistry.register(name, returnType, function);
    }

    public <T1, R> void registerFunction(String name, Class<T1> t1, Class<R> returnType,
                                          Function1<T1, R> function) {
        functionRegistry.register(name, t1, returnType, function);
    }

    public <T1, T2, R> void registerFunction(String name, Class<T1> t1, Class<T2> t2,
                                              Class<R> returnType, Function2<T1, T2, R> function) {
        functionRegistry.register(name, t1, t2, returnType, function);
    }

    public <T1, T2, T3, R> void registerFunction(String name, Class<T1> t1, Class<T2> t2,
                                                  Class<T3> t3, Class<R> returnType,
                                                  Function3<T1, T2, T3, R> function) {
        functionRegistry.register(name, t1, t2, t3, returnType, function);
    }

    public <T, R> void registerExtension(Class<T> targetType, String methodName,
                                          Class<R> returnType, Extension0<T, R> method) {
        extensionRegistry.register(targetType, methodName, returnType, method);
    }

    public <T, A1, R> void registerExtension(Class<T> targetType, String methodName,
                                              Class<A1> a1, Class<R> returnType,
                                              Extension1<T, A1, R> method) {
        extensionRegistry.register(targetType, methodName, a1, returnType, method);
    }

    public <T, A1, A2, R> void registerExtension(Class<T> targetType, String methodName,
                                                  Class<A1> a1, Class<A2> a2, Class<R> returnType,
                                                  Extension2<T, A1, A2, R> method) {
        extensionRegistry.register(targetType, methodName, a1, a2, returnType, method);
    }

    public void registerExtension(Class<?> targetType, String methodName, Class<?>[] paramTypes,
                                   Class<?> returnType, ExtensionMethod<?, ?> method) {
        extensionRegistry.register(targetType, methodName, paramTypes, returnType, method);
    }

    // ── FunctionN 便捷版扩展注册（shared() 使用） ──

    /** 注册扩展方法：receiver → result */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public NovaRuntime registerExt(Class<?> type, String name, Function1<?, ?> func) {
        extensionRegistry.register(type, name, new Class<?>[0], Object.class,
                (ExtensionMethod<Object, Object>) (receiver, args) -> ((Function1) func).invoke(receiver));
        return this;
    }

    /** 注册扩展方法：(receiver, arg1) → result */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public NovaRuntime registerExt(Class<?> type, String name, Function2<?, ?, ?> func) {
        extensionRegistry.register(type, name, new Class<?>[]{Object.class}, Object.class,
                (ExtensionMethod<Object, Object>) (receiver, args) -> ((Function2) func).invoke(receiver, args[0]));
        return this;
    }

    /** 注册扩展方法：(receiver, arg1, arg2) → result */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public NovaRuntime registerExt(Class<?> type, String name, Function3<?, ?, ?, ?> func) {
        extensionRegistry.register(type, name, new Class<?>[]{Object.class, Object.class}, Object.class,
                (ExtensionMethod<Object, Object>) (receiver, args) -> ((Function3) func).invoke(receiver, args[0], args[1]));
        return this;
    }

    public void setGlobal(String name, Object value) { globals.put(name, value); }

    public NovaValue global(String name) {
        Object value = globals.get(name);
        if (value == null) return NovaNull.NULL;
        if (value instanceof NovaValue) return (NovaValue) value;
        return AbstractNovaValue.fromJava(value);
    }

    public Object getGlobal(String name) { return globals.get(name); }
    public boolean hasGlobal(String name) { return globals.containsKey(name); }
    public void removeGlobal(String name) { globals.remove(name); }

    public void registerClass(String alias, Class<?> clazz) { registeredClasses.put(alias, clazz); }
    public Class<?> getRegisteredClass(String alias) { return registeredClasses.get(alias); }

    public FunctionRegistry getFunctionRegistry() { return functionRegistry; }
    public ExtensionRegistry getExtensionRegistry() { return extensionRegistry; }

    public NovaValue call(String name, Object... args) throws Exception {
        // 先查实例级 FunctionRegistry
        FunctionRegistry.RegisteredFunction func = functionRegistry.lookup(name);
        if (func != null) {
            Object result = func.invoke(args);
            if (result == null) return NovaNull.NULL;
            if (result instanceof NovaValue) return (NovaValue) result;
            return AbstractNovaValue.fromJava(result);
        }
        // 再查全局注册表
        RegisteredEntry entry = globalFunctions.get(name);
        if (entry != null) {
            Object result = entry.invoke(args);
            if (result == null) return NovaNull.NULL;
            if (result instanceof NovaValue) return (NovaValue) result;
            return AbstractNovaValue.fromJava(result);
        }
        throw new NoSuchMethodException("Function not found: " + name);
    }

    public NovaValue callExtension(Object receiver, String methodName, Object... args) throws Exception {
        if (receiver == null) throw new NullPointerException("Cannot invoke extension method on null receiver");
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            argTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        }
        ExtensionRegistry.RegisteredExtension ext = extensionRegistry.lookup(receiver.getClass(), methodName, argTypes);
        if (ext == null) throw new NoSuchMethodException("Extension method not found: " + receiver.getClass().getName() + "." + methodName);
        Object result = ext.invoke(receiver, args);
        if (result == null) return NovaNull.NULL;
        if (result instanceof NovaValue) return (NovaValue) result;
        return AbstractNovaValue.fromJava(result);
    }

    @Deprecated
    public Object invokeFunction(String name, Object... args) throws Exception {
        FunctionRegistry.RegisteredFunction func = functionRegistry.lookup(name);
        if (func == null) throw new NoSuchMethodException("Function not found: " + name);
        return func.invoke(args);
    }

    @Deprecated
    public Object invokeExtension(Object receiver, String methodName, Object... args) throws Exception {
        if (receiver == null) throw new NullPointerException("Cannot invoke extension method on null receiver");
        Class<?>[] argTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) argTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
        ExtensionRegistry.RegisteredExtension ext = extensionRegistry.lookup(receiver.getClass(), methodName, argTypes);
        if (ext == null) throw new NoSuchMethodException("Extension method not found: " + receiver.getClass().getName() + "." + methodName);
        return ext.invoke(receiver, args);
    }

    public ExtensionBuilder extensions() { return new ExtensionBuilder(this); }

    public static final class ExtensionBuilder {
        private final NovaRuntime runtime;
        private ExtensionBuilder(NovaRuntime runtime) { this.runtime = runtime; }
        public <T> TypeExtensionBuilder<T> forType(Class<T> type) { return new TypeExtensionBuilder<>(this, type); }
        public NovaRuntime register() { return runtime; }
    }

    public static final class TypeExtensionBuilder<T> {
        private final ExtensionBuilder parent;
        private final Class<T> type;
        private TypeExtensionBuilder(ExtensionBuilder parent, Class<T> type) { this.parent = parent; this.type = type; }
        public <R> TypeExtensionBuilder<T> add(String name, Class<R> returnType, Extension0<T, R> method) { parent.runtime.registerExtension(type, name, returnType, method); return this; }
        public <A1, R> TypeExtensionBuilder<T> add(String name, Class<A1> a1, Class<R> returnType, Extension1<T, A1, R> method) { parent.runtime.registerExtension(type, name, a1, returnType, method); return this; }
        public <A1, A2, R> TypeExtensionBuilder<T> add(String name, Class<A1> a1, Class<A2> a2, Class<R> returnType, Extension2<T, A1, A2, R> method) { parent.runtime.registerExtension(type, name, a1, a2, returnType, method); return this; }
        public <U> TypeExtensionBuilder<U> forType(Class<U> type) { return parent.forType(type); }
        public NovaRuntime register() { return parent.register(); }
    }

    // ============ ExecutionContext ThreadLocal ============

    private static final ThreadLocal<ExecutionContext> CURRENT_CONTEXT = new ThreadLocal<>();

    public static ExecutionContext currentContext() { return CURRENT_CONTEXT.get(); }
    public static void setCurrentContext(ExecutionContext ctx) { CURRENT_CONTEXT.set(ctx); }
    public static void clearCurrentContext() { CURRENT_CONTEXT.remove(); }

    // ============ 作用域接收者调用（编译模式 $ScopeCall） ============

    /**
     * 以 receiver 为作用域接收者调用 callable（编译模式的 $ScopeCall 实现）。
     * 通过 ThreadLocal 获取当前 ExecutionContext 并委托其 invokeWithScopeReceiver。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object scopeCall(Object callable, Object receiver, Object[] args) {
        ExecutionContext ctx = currentContext();
        if (ctx != null) {
            // 解释器模式：通过 withScopeReceiver 绑定接收者
            NovaCallable novaCallable = ctx.extractCallable(
                    callable instanceof NovaValue ? (NovaValue) callable : AbstractNovaValue.fromJava(callable));
            if (novaCallable != null) {
                NovaValue novaReceiver = receiver instanceof NovaValue
                        ? (NovaValue) receiver : AbstractNovaValue.fromJava(receiver);
                java.util.List<NovaValue> novaArgs = new java.util.ArrayList<>();
                for (Object arg : args) {
                    novaArgs.add(arg instanceof NovaValue ? (NovaValue) arg : AbstractNovaValue.fromJava(arg));
                }
                NovaValue result = ctx.invokeWithScopeReceiver(novaCallable, novaReceiver, novaArgs);
                return result != null ? result.toJavaValue() : null;
            }
        }
        // 编译模式（无 ExecutionContext 或 NovaCallable 提取失败）：
        // 通过 NovaScopeFunctions thread-local 传递 scopeReceiver，
        // lambda 内的 NovaScriptContext.get/set/call 会自动检查 scope receiver
        Object prevReceiver = com.novalang.runtime.stdlib.NovaScopeFunctions.getScopeReceiver();
        com.novalang.runtime.stdlib.NovaScopeFunctions.setScopeReceiver(receiver);
        try {
            if (callable instanceof NovaCallable) {
                java.util.List<NovaValue> novaArgs = new java.util.ArrayList<>();
                for (Object arg : args) {
                    novaArgs.add(arg instanceof NovaValue ? (NovaValue) arg : AbstractNovaValue.fromJava(arg));
                }
                NovaValue result = ((NovaCallable) callable).call(null, novaArgs);
                return result != null ? result.toJavaValue() : null;
            }
            // FunctionN 接口（编译模式的 lambda）
            if (callable instanceof Function0) return ((Function0) callable).invoke();
            if (callable instanceof Function1) return ((Function1) callable).invoke(args.length > 0 ? args[0] : null);
            if (callable instanceof Function2) return ((Function2) callable).invoke(
                    args.length > 0 ? args[0] : null, args.length > 1 ? args[1] : null);
            throw new RuntimeException("scopeCall: not callable: " + callable.getClass().getName());
        } finally {
            com.novalang.runtime.stdlib.NovaScopeFunctions.setScopeReceiver(prevReceiver);
        }
    }

    // ============ 静态内置函数调用入口 ============

    public static final Object NOT_FOUND = new Object();

    /**
     * 统一函数调用入口：StdlibRegistry → shared() 全局注册表。
     */
    public static Object callBuiltin(String name, Object... args) {
        // 1. StdlibRegistry（内置标准库）
        StdlibRegistry.NativeFunctionInfo nf = StdlibRegistry.getNativeFunction(name);
        if (nf != null) return nf.impl.apply(args);
        if (args.length == 0) {
            StdlibRegistry.ConstantInfo ci = StdlibRegistry.getConstant(name);
            if (ci != null) return ci.value;
        }
        // 2. shared() 本实例注册表
        if (SHARED != null) {
            RegisteredEntry entry = SHARED.globalFunctions.get(name);
            if (entry != null) return entry.invoke(args);
        }
        // 3. JVM 全局桥接（其他插件 relocate 后的 NovaRuntime 注册的函数）
        return callGlobal(name, args);
    }

    public static boolean hasBuiltin(String name) {
        if (StdlibRegistry.get(name) != null) return true;
        if (SHARED != null && SHARED.globalFunctions.containsKey(name)) return true;
        return hasGlobalBridge(name);
    }
}
