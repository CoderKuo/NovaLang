package com.novalang.runtime;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.interpreter.*;
import com.novalang.runtime.types.Environment;


import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.Consumer;

/**
 * Nova 便捷 API — 一行代码执行 Nova 脚本。
 *
 * <p>静态调用（每次创建临时实例）：</p>
 * <pre>
 * Object result = Nova.run("1 + 2");
 * Object result = Nova.run("x + y", "x", 10, "y", 20);
 * Object result = Nova.runFile("script.nova");
 * </pre>
 *
 * <p>实例调用（共享状态，多次调用）：</p>
 * <pre>
 * Nova nova = new Nova();
 * nova.set("name", "Alice");
 * nova.eval("fun greet(n: String) = \"Hello, $n!\"");
 * Object greeting = nova.call("greet", "World");
 * </pre>
 */
public final class Nova {

    private final Interpreter interpreter;

    /**
     * 编译模式扩展方法注册表。
     * registerExtension 的方法同时写入这里，compileToBytecode 时注入到 CompiledNova。
     */
    private final ExtensionRegistry extensionRegistry = new ExtensionRegistry();

    /**
     * 供字节码模式使用的值注册表。
     * defineVal/set 的值同时存入这里，compileToBytecode 时注入到 CompiledNova 的 bindings。
     * NovaNativeFunction 会被适配为 FunctionN 接口，使编译后的字节码可以通过 invoke() 调用。
     */
    private final Map<String, Object> valRegistry = new LinkedHashMap<>();

    /**
     * 命名空间绑定注册表。
     * 通过 defineVal/defineFunction/set 的带 namespace 重载存入此 Map，
     * 在 eval/compile/compileToBytecode 时通过 applyNamespaceBindings() 合并到全局环境。
     * 命名空间中的定义优先于全局同名定义（覆盖语义）。
     */
    private final Map<String, Map<String, Object>> namespaceBindings = new HashMap<>();
    private final List<PreludeSource> preludeSources = new ArrayList<>();
    private int evaluatedPreludeCount = 0;

    private static final class PreludeSource {
        final String source;
        final String fileName;

        PreludeSource(String source, String fileName) {
            this.source = source;
            this.fileName = fileName;
        }
    }

    /**
     * 编译缓存：源码哈希 → 已编译的类集合。
     * 避免相同代码重复编译。通过 {@link #enableCompilationCache()} 开启。
     */
    private Map<String, Map<String, Class<?>>> compilationCache;

    /** NovaLang 版本号（从 jar manifest 自动读取，开发环境回退 "dev"） */
    public static final String VERSION = resolveVersion();

    /** 获取 NovaLang 版本号 */
    public static String getVersion() { return VERSION; }

    private static String resolveVersion() {
        Package pkg = Nova.class.getPackage();
        if (pkg != null && pkg.getImplementationVersion() != null) {
            return pkg.getImplementationVersion();
        }
        return "dev";
    }

    public Nova() {
        this.interpreter = new Interpreter();
    }

    public Nova(NovaSecurityPolicy policy) {
        this.interpreter = new Interpreter(policy);
    }

    // ── 成员名称解析器 ──────────────────────────────────────

    /**
     * 设置自定义成员名称解析器（全局生效）。
     * <p>当 Nova 在 Java 对象上找不到指定成员时，回调此接口进行名称映射。</p>
     *
     * <pre>
     * Nova.setMemberResolver((target, name, isMethod) -> {
     *     if (isMethod) return McpMappingResolver.resolveMethod(name);
     *     else return McpMappingResolver.resolveField(name);
     * });
     * </pre>
     */
    public static void setMemberResolver(MemberNameResolver resolver) {
        NovaRuntime.setMemberNameResolver(resolver);
    }

    // ── 序列化 Provider ──────────────────────────────────────

    /**
     * 设置 JSON 序列化 provider（全局生效）。
     *
     * <pre>
     * Nova.setJsonProvider(new GsonJsonProvider());
     * </pre>
     *
     * @param provider JSON provider 实现
     */
    public static void setJsonProvider(com.novalang.runtime.stdlib.spi.JsonProvider provider) {
        com.novalang.runtime.stdlib.spi.SerializationProviders.registerJsonProvider(provider);
    }

    /**
     * 设置 YAML 序列化 provider（全局生效）。
     *
     * <pre>
     * Nova.setYamlProvider(new SnakeYamlProvider());
     * </pre>
     *
     * @param provider YAML provider 实现
     */
    public static void setYamlProvider(com.novalang.runtime.stdlib.spi.YamlProvider provider) {
        com.novalang.runtime.stdlib.spi.SerializationProviders.registerYamlProvider(provider);
    }

    /**
     * 按名称切换 JSON provider。
     *
     * <pre>
     * Nova.setJsonProvider("gson");
     * </pre>
     *
     * @param name provider 名称（"builtin"、"gson"、"fastjson2"）
     * @throws IllegalArgumentException 如果指定名称的 provider 不可用
     */
    public static void setJsonProvider(String name) {
        String result = com.novalang.runtime.stdlib.spi.SerializationProviders.setJsonProvider(name);
        if (result == null) throw new IllegalArgumentException(
                "JSON provider not found: " + name + ". Available: "
                        + com.novalang.runtime.stdlib.spi.SerializationProviders.listJsonProviders());
    }

    /**
     * 按名称切换 YAML provider。
     *
     * @param name provider 名称（"builtin"、"snakeyaml"、"bukkit"）
     */
    public static void setYamlProvider(String name) {
        String result = com.novalang.runtime.stdlib.spi.SerializationProviders.setYamlProvider(name);
        if (result == null) throw new IllegalArgumentException(
                "YAML provider not found: " + name + ". Available: "
                        + com.novalang.runtime.stdlib.spi.SerializationProviders.listYamlProviders());
    }

    /** 获取当前 JSON provider 名称 */
    public static String getJsonProvider() {
        com.novalang.runtime.stdlib.spi.JsonProvider p = com.novalang.runtime.stdlib.spi.SerializationProviders.json();
        return p != null ? p.name() : "none";
    }

    /** 获取当前 YAML provider 名称 */
    public static String getYamlProvider() {
        com.novalang.runtime.stdlib.spi.YamlProvider p = com.novalang.runtime.stdlib.spi.SerializationProviders.yaml();
        return p != null ? p.name() : "none";
    }

    // ── ClassLoader ────────────────────────────────────────

    /**
     * 设置脚本级 ClassLoader。
     * {@code javaClass()} 会优先在此 ClassLoader 中查找类，
     * 找不到时回退到默认 ClassLoader。
     * 用于隔离脚本的 Maven 依赖，避免与其他插件的类冲突。
     */
    public Nova setScriptClassLoader(ClassLoader classLoader) {
        this.scriptClassLoader = classLoader;
        interpreter.setScriptClassLoader(classLoader);
        return this;
    }

    /** 获取脚本级 ClassLoader */
    public ClassLoader getScriptClassLoader() {
        return scriptClassLoader;
    }

    private ClassLoader scriptClassLoader;

    // ── IO 重定向 ─────────────────────────────────────────

    public Nova setStdout(PrintStream out) {
        interpreter.setStdout(out);
        NovaPrint.setOut(out); // 统一输出层（编译路径 + 解释器路径共用）
        return this;
    }

    public Nova setStderr(PrintStream err) {
        interpreter.setStderr(err);
        return this;
    }

    public Nova setScheduler(NovaScheduler scheduler) {
        interpreter.setScheduler(scheduler);
        return this;
    }

    // ── 变量操作（fluent API） ──────────────────────────────

    /**
     * 设置变量。已存在的可变变量会更新值，不存在则定义为可变变量。
     * 同时写入 valRegistry 供字节码模式使用。
     */
    public Nova set(String name, Object value) {
        // List/Map/Set: 保持 Java 引用（不复制），Nova 端修改直接影响 Java 端
        NovaValue nv;
        if (value instanceof java.util.List || value instanceof java.util.Map || value instanceof java.util.Set) {
            nv = new com.novalang.runtime.interpreter.NovaExternalObject(value);
        } else {
            nv = AbstractNovaValue.fromJava(value);
        }
        Environment env = interpreter.getGlobals();
        if (env.contains(name)) {
            if (!env.isVal(name)) {
                env.assign(name, nv);
            } else {
                // val 不可重新赋值，用 redefine 覆盖（保持 val 不可变语义）
                env.redefine(name, nv, false);
            }
        } else {
            env.defineVar(name, nv);
        }
        valRegistry.put(name, NativeFunctionAdapter.toBindingValue(value));
        return this;
    }

    /**
     * 设置变量到指定命名空间。
     */
    public Nova set(String name, Object value, String namespace) {
        namespaceBindings.computeIfAbsent(namespace, k -> new LinkedHashMap<>())
                .put(name, value);
        return this;
    }

    /**
     * 定义不可变变量（val）。支持 NovaValue（如 NovaNativeFunction）和普通 Java 对象。
     * 同时写入 valRegistry 供字节码模式使用。
     * <pre>
     * nova.defineVal("PI", 3.14159);
     * nova.defineVal("greet", NovaNativeFunction.create("greet", arg -> NovaString.of("Hello, " + arg)));
     * </pre>
     */
    public Nova defineVal(String name, Object value) {
        NovaValue nv;
        if (value instanceof java.util.List || value instanceof java.util.Map || value instanceof java.util.Set) {
            nv = new com.novalang.runtime.interpreter.NovaExternalObject(value);
        } else {
            nv = AbstractNovaValue.fromJava(value);
        }
        interpreter.getGlobals().defineVal(name, nv);
        valRegistry.put(name, NativeFunctionAdapter.toBindingValue(value));
        return this;
    }

    /**
     * 定义不可变变量到指定命名空间（不写入全局环境，避免重名冲突）。
     * 在 eval/compile/compileToBytecode 带 namespace 调用时通过 applyNamespaceBindings 合并。
     */
    public Nova defineVal(String name, Object value, String namespace) {
        namespaceBindings.computeIfAbsent(namespace, k -> new LinkedHashMap<>())
                .put(name, value);
        return this;
    }

    // ── 注入 Java 函数（自动类型转换） ───────────────────────

    /**
     * 注入无参 Java 函数。
     * <pre>nova.defineFunction("now", () -> System.currentTimeMillis());</pre>
     */
    public Nova defineFunction(String name, Function0<Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 0, (ctx, args) ->
                wrapReturn(func.invoke())));
    }

    /**
     * 注入单参 Java 函数。参数自动从 Nova 转换为 Java 类型。
     * <pre>nova.defineFunction("greet", name -> "Hello, " + name);</pre>
     */
    public Nova defineFunction(String name, Function1<Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 1, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0))))));
    }

    /**
     * 注入双参 Java 函数。
     * <pre>nova.defineFunction("add", (a, b) -> (int)a + (int)b);</pre>
     */
    public Nova defineFunction(String name, Function2<Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 2, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1))))));
    }

    /** 注入三参 Java 函数。 */
    public Nova defineFunction(String name, Function3<Object, Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 3, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2))))));
    }

    /** 注入四参 Java 函数。 */
    public Nova defineFunction(String name, Function4<Object, Object, Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 4, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3))))));
    }

    /** 注入五参 Java 函数。 */
    public Nova defineFunction(String name, Function5<Object, Object, Object, Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 5, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4))))));
    }

    /** 注入六参 Java 函数。 */
    public Nova defineFunction(String name, Function6<Object, Object, Object, Object, Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 6, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5))))));
    }

    /** 注入七参 Java 函数。 */
    public Nova defineFunction(String name, Function7<Object, Object, Object, Object, Object, Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 7, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5)), unwrap(args.get(6))))));
    }

    /** 注入八参 Java 函数。 */
    public Nova defineFunction(String name, Function8<Object, Object, Object, Object, Object, Object, Object, Object, Object> func) {
        return defineVal(name, new NovaNativeFunction(name, 8, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5)), unwrap(args.get(6)), unwrap(args.get(7))))));
    }

    /**
     * 注入可变参数 Java 函数。
     * <pre>
     * nova.defineFunctionVararg("sum", args -> {
     *     int total = 0;
     *     for (Object a : args) total += (int) a;
     *     return total;
     * });
     * </pre>
     */
    public Nova defineFunctionVararg(String name, Function1<Object[], Object> func) {
        return defineVal(name, new NovaNativeFunction(name, -1, (ctx, args) -> {
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) javaArgs[i] = unwrap(args.get(i));
            return wrapReturn(func.invoke(javaArgs));
        }));
    }

    // ── 注入 Java 函数（命名空间版） ────────────────────────

    /** 注入无参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function0<Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 0, (ctx, args) ->
                wrapReturn(func.invoke())), namespace);
    }

    /** 注入单参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function1<Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 1, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0))))), namespace);
    }

    /** 注入双参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function2<Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 2, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1))))), namespace);
    }

    /** 注入三参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function3<Object, Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 3, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2))))), namespace);
    }

    /** 注入四参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function4<Object, Object, Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 4, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3))))), namespace);
    }

    /** 注入五参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function5<Object, Object, Object, Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 5, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4))))), namespace);
    }

    /** 注入六参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function6<Object, Object, Object, Object, Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 6, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5))))), namespace);
    }

    /** 注入七参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function7<Object, Object, Object, Object, Object, Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 7, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5)), unwrap(args.get(6))))), namespace);
    }

    /** 注入八参 Java 函数到命名空间。 */
    public Nova defineFunction(String name, Function8<Object, Object, Object, Object, Object, Object, Object, Object, Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, 8, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5)), unwrap(args.get(6)), unwrap(args.get(7))))), namespace);
    }

    /** 注入可变参数 Java 函数到命名空间。 */
    public Nova defineFunctionVararg(String name, Function1<Object[], Object> func, String namespace) {
        return defineVal(name, new NovaNativeFunction(name, -1, (ctx, args) -> {
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) javaArgs[i] = unwrap(args.get(i));
            return wrapReturn(func.invoke(javaArgs));
        }), namespace);
    }

    // ── 别名 ──────────────────────────────────────────

    /**
     * 为已注册的函数或变量创建别名。
     * <pre>
     * nova.defineFunction("sum", (a, b) -> (int) a + (int) b);
     * nova.alias("sum", "求和", "add");
     * // Nova 脚本: 求和(1, 2)  → 3
     * </pre>
     */
    public Nova alias(String existing, String... aliases) {
        NovaValue val = interpreter.getGlobals().tryGet(existing);
        if (val == null) {
            throw new NovaRuntimeException("Cannot create alias: '" + existing + "' is not defined");
        }
        for (String alias : aliases) {
            interpreter.getGlobals().defineVal(alias, val);
            valRegistry.put(alias, valRegistry.get(existing));
        }
        return this;
    }

    /**
     * 为已注册的扩展方法创建别名。
     * <pre>
     * nova.registerExtension(String.class, "reverse", (s) -> ...);
     * nova.aliasExtension(String.class, "reverse", "反转");
     * // Nova 脚本: "hello".反转()  → "olleh"
     * </pre>
     */
    public Nova aliasExtension(Class<?> targetType, String existing, String... aliases) {
        com.novalang.runtime.NovaCallable method = interpreter.getExtension(targetType, existing);
        if (method == null) {
            throw new NovaRuntimeException("Cannot create alias: extension '"
                    + existing + "' is not registered for " + targetType.getName());
        }
        for (String alias : aliases) {
            registerExtension(targetType, alias, method);
        }
        return this;
    }

    private static Object unwrap(NovaValue v) {
        return v == null || v.isNull() ? null : v.toJavaValue();
    }

    private static NovaValue wrapReturn(Object result) {
        if (result == null) return NovaNull.UNIT;
        return AbstractNovaValue.fromJava(result);
    }

    /**
     * 为指定 Java 类型注册扩展方法。
     * 同时写入解释器和 ExtensionRegistry（供编译模式使用）。
     * <pre>
     * nova.registerExtension(String.class, "reverse", new NovaNativeFunction(...));
     * </pre>
     */
    public Nova registerExtension(Class<?> targetType, String methodName, com.novalang.runtime.NovaCallable method) {
        // 解释器侧
        interpreter.registerExtension(targetType, methodName, method);
        // 编译器侧 — 适配 NovaCallable → ExtensionMethod
        // NovaNativeFunction.arity 含 receiver，ExtensionRegistry.paramTypes 不含
        int arity = (method instanceof NovaNativeFunction)
                ? ((NovaNativeFunction) method).getArity() : 0;
        int extraParams = Math.max(0, arity - 1);
        Class<?>[] paramTypes = new Class<?>[extraParams];
        Arrays.fill(paramTypes, Object.class);
        extensionRegistry.register(targetType, methodName, paramTypes, Object.class,
                new ExtensionMethod<Object, Object>() {
                    @Override
                    public Object invoke(Object receiver, Object[] args) {
                        List<NovaValue> novaArgs = new ArrayList<>();
                        novaArgs.add(AbstractNovaValue.fromJava(receiver));
                        for (Object a : args) novaArgs.add(AbstractNovaValue.fromJava(a));
                        NovaValue result = method.call((ExecutionContext)null, novaArgs);
                        return result == NovaNull.UNIT ? null : result.toJavaValue();
                    }
                });
        return this;
    }

    ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    // ── 扩展方法 Lambda 便捷注册 ───────────────────────

    /** 注册扩展方法：receiver → result（0 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function1<Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 1, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0))))));
    }

    /** 注册扩展方法：(receiver, arg1) → result（1 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function2<Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 2, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1))))));
    }

    /** 注册扩展方法：(receiver, arg1, arg2) → result（2 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function3<Object, Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 3, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2))))));
    }

    /** 注册扩展方法：(receiver, arg1, arg2, arg3) → result（3 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function4<Object, Object, Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 4, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3))))));
    }

    /** 注册扩展方法：(receiver, arg1~4) → result（4 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function5<Object, Object, Object, Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 5, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4))))));
    }

    /** 注册扩展方法：(receiver, arg1~5) → result（5 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function6<Object, Object, Object, Object, Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 6, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5))))));
    }

    /** 注册扩展方法：(receiver, arg1~6) → result（6 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function7<Object, Object, Object, Object, Object, Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 7, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5)), unwrap(args.get(6))))));
    }

    /** 注册扩展方法：(receiver, arg1~7) → result（7 额外参数）。 */
    public Nova registerExtension(Class<?> type, String name, Function8<Object, Object, Object, Object, Object, Object, Object, Object, Object> func) {
        return registerExtension(type, name, new NovaNativeFunction(name, 8, (ctx, args) ->
                wrapReturn(func.invoke(unwrap(args.get(0)), unwrap(args.get(1)), unwrap(args.get(2)), unwrap(args.get(3)), unwrap(args.get(4)), unwrap(args.get(5)), unwrap(args.get(6)), unwrap(args.get(7))))));
    }

    // ── 扩展方法批量注册 ──────────────────────────────

    /**
     * 扫描 clazz 中所有 {@code @NovaExt} 注解方法，批量注册为 targetType 的扩展方法。
     * 方法必须是 public static，第一个参数（非 Interpreter）约定为 receiver。
     * <pre>
     * public class StringExtensions {
     *     &#64;NovaExt("shout")
     *     public static String shout(String self) {
     *         return self.toUpperCase() + "!";
     *     }
     * }
     * nova.registerExtensions(String.class, StringExtensions.class);
     * // Nova 脚本: "hello".shout()  → "HELLO!"
     * </pre>
     */
    public Nova registerExtensions(Class<?> targetType, Class<?> clazz) {
        NovaRegistry.registerExtensions(targetType, clazz,
                (name, nf) -> registerExtension(targetType, name, nf));
        return this;
    }

    /**
     * 一键注册 Java 类中的所有 Nova 绑定：
     * <ul>
     *   <li>{@code @NovaFunc} 方法 → 注册为全局函数</li>
     *   <li>{@code @NovaExt} 方法 → 注册为对应类型的扩展方法</li>
     * </ul>
     * <pre>
     * public class MyPlugin {
     *     &#64;NovaFunc("greet")
     *     public static String greet(String name) { return "Hi " + name; }
     *
     *     &#64;NovaExt("shout")
     *     public static String shout(String self) { return self.toUpperCase() + "!"; }
     * }
     * nova.registerAll(MyPlugin.class);
     * // Nova: greet("world"), "hello".shout()
     * </pre>
     */
    public Nova registerAll(Class<?> clazz) {
        // @NovaFunc → 全局函数
        registerFunctions(clazz);
        // @NovaExt → 扩展方法（按每个方法的 receiver 参数类型分别注册）
        java.util.Set<Class<?>> registeredTypes = new java.util.HashSet<>();
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            NovaExt ann = m.getAnnotation(NovaExt.class);
            if (ann == null) continue;
            Class<?>[] params = m.getParameterTypes();
            int receiverIdx = (params.length > 0 && params[0] == Interpreter.class) ? 1 : 0;
            if (receiverIdx < params.length) {
                Class<?> targetType = params[receiverIdx];
                if (registeredTypes.add(targetType)) {
                    registerExtensions(targetType, clazz);
                }
            }
        }
        return this;
    }

    // ── 函数集 / 命名空间注册 ────────────────────────────

    /**
     * 扫描 {@code @NovaFunc} 注解的方法，注册为顶级函数。
     * <pre>
     * public class MathFunctions {
     *     &#64;NovaFunc("sqrt")
     *     public static double sqrt(double x) { return Math.sqrt(x); }
     * }
     * nova.registerFunctions(MathFunctions.class);
     * // Nova 脚本: sqrt(16)
     * </pre>
     */
    public Nova registerFunctions(Class<?> clazz) {
        NovaRegistry.registerAll(interpreter.getGlobals(), clazz);
        return this;
    }

    /**
     * 扫描 {@code @NovaFunc} 注解的方法，注册到命名空间。支持增量合并。
     * <pre>
     * nova.registerFunctions("math", MathFunctions.class);
     * nova.registerFunctions("math", TrigFunctions.class); // 合并到同一命名空间
     * // Nova 脚本: math.sqrt(16), math.sin(1.0)
     * </pre>
     */
    public Nova registerFunctions(String namespace, Class<?> clazz) {
        NovaLibrary lib = getOrCreateLibrary(namespace);
        NovaRegistry.registerAll(lib, clazz);
        return this;
    }

    /**
     * Builder 模式注册命名空间。支持增量合并。
     * <pre>
     * nova.defineLibrary("http", lib -> {
     *     lib.defineFunction("get", url -> httpGet((String) url));
     *     lib.defineFunction("post", (url, body) -> httpPost((String) url, (String) body));
     *     lib.defineVal("TIMEOUT", 30000);
     * });
     * // Nova 脚本: http.get("https://..."), http.TIMEOUT
     * </pre>
     */
    public Nova defineLibrary(String name, Consumer<LibraryBuilder> config) {
        NovaLibrary lib = getOrCreateLibrary(name);
        config.accept(new LibraryBuilder(lib));
        return this;
    }

    /**
     * 将 Java 类的所有 public static 方法自动注册为 library 函数。
     * 支持三种注册方式（按优先级）：
     * <ol>
     *   <li>{@code @NovaFunc} 注解方法 — 使用注解指定的名称和别名</li>
     *   <li>{@code @NovaExt} 注解方法 — 跳过（扩展方法不属于 library）</li>
     *   <li>无注解的 public static 方法 — 使用方法名直接注册</li>
     * </ol>
     * <pre>
     * public class MathUtils {
     *     public static int square(int x) { return x * x; }
     *     public static double sqrt(double x) { return Math.sqrt(x); }
     * }
     * nova.defineLibrary("math", MathUtils.class);
     * // Nova: math.square(5) → 25, math.sqrt(16.0) → 4.0
     * </pre>
     */
    public Nova defineLibrary(String name, Class<?> javaClass) {
        NovaLibrary lib = getOrCreateLibrary(name);
        // 1. @NovaFunc 注解方法（优先，由 NovaRegistry 处理参数类型映射）
        NovaRegistry.registerAll(lib, javaClass);
        // 2. 无注解的 public static 方法
        for (java.lang.reflect.Method m : javaClass.getDeclaredMethods()) {
            int mod = m.getModifiers();
            if (!java.lang.reflect.Modifier.isPublic(mod) || !java.lang.reflect.Modifier.isStatic(mod)) continue;
            // 跳过已被 @NovaFunc / @NovaExt 注册的
            if (m.getAnnotation(NovaFunc.class) != null || m.getAnnotation(NovaExt.class) != null) continue;
            String funcName = m.getName();
            // 跳过已注册的（@NovaFunc 可能已用别名注册了同名方法）
            if (lib.resolveMember(funcName) != null) continue;
            int arity = m.getParameterCount();
            java.lang.reflect.Method method = m;
            lib.putMember(funcName, new NovaNativeFunction(funcName, arity, (ctx, args) -> {
                Object[] javaArgs = new Object[args.size()];
                for (int i = 0; i < args.size(); i++) {
                    javaArgs[i] = unwrap(args.get(i));
                }
                try {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    for (int i = 0; i < javaArgs.length && i < paramTypes.length; i++) {
                        javaArgs[i] = coerceArg(javaArgs[i], paramTypes[i]);
                    }
                    Object result = method.invoke(null, javaArgs);
                    return wrapReturn(result);
                } catch (Exception e) {
                    Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
                    throw NovaErrors.javaInvokeFailed(funcName, javaClass.getSimpleName(), cause);
                }
            }));
        }
        return this;
    }

    /** 参数类型适配（Number → int/long/double/float 等） */
    private static Object coerceArg(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isInstance(value)) return value;
        if (value instanceof Number) {
            Number n = (Number) value;
            if (targetType == int.class || targetType == Integer.class) return n.intValue();
            if (targetType == long.class || targetType == Long.class) return n.longValue();
            if (targetType == double.class || targetType == Double.class) return n.doubleValue();
            if (targetType == float.class || targetType == Float.class) return n.floatValue();
        }
        if (targetType == String.class) return String.valueOf(value);
        return value;
    }

    /**
     * 将 Java 对象直接暴露为命名空间（通过 Java 互操作访问其方法和字段）。
     * <pre>
     * nova.registerObject("db", myDatabaseService);
     * // Nova 脚本: db.query("SELECT ..."), db.close()
     * </pre>
     */
    public Nova registerObject(String name, Object javaObject) {
        return defineVal(name, new NovaExternalObject(javaObject));
    }

    /**
     * 获取或创建命名空间。已存在时返回现有实例（支持增量注册）。
     */
    private NovaLibrary getOrCreateLibrary(String name) {
        NovaValue existing = interpreter.getGlobals().tryGet(name);
        if (existing instanceof NovaLibrary) {
            return (NovaLibrary) existing;
        }
        NovaLibrary lib = new NovaLibrary(name);
        interpreter.getGlobals().defineVal(name, lib);
        valRegistry.put(name, lib);
        return lib;
    }

    /**
     * 获取变量值，转换为 Java 类型。不存在时返回 null。
     */
    public Object get(String name) {
        ensureInterpreterPreloadsEvaluated();
        NovaValue val = interpreter.getGlobals().tryGet(name);
        if (val == null) return null;
        return toJava(val);
    }

    /**
     * 导出所有全局变量为 Java 类型。
     */
    public Map<String, Object> getAll() {
        ensureInterpreterPreloadsEvaluated();
        Map<String, Object> result = new HashMap<>();
        for (String name : interpreter.getGlobals().getLocalNames()) {
            NovaValue val = interpreter.getGlobals().tryGet(name);
            if (val != null) {
                result.put(name, toJava(val));
            }
        }
        return result;
    }

    // ── 命名空间合并 ──────────────────────────────────────

    /**
     * 将指定命名空间的绑定合并到全局环境。
     * 通过 set() 实现覆盖语义：已存在的同名变量会被命名空间版本覆盖。
     */
    private void applyNamespaceBindings(String namespace) {
        if (namespace == null) return;
        Map<String, Object> nsBindings = namespaceBindings.get(namespace);
        if (nsBindings == null || nsBindings.isEmpty()) return;
        for (Map.Entry<String, Object> entry : nsBindings.entrySet()) {
            set(entry.getKey(), entry.getValue());
        }
    }

    // ── 执行代码 ──────────────────────────────────────────

    /**
     * 执行 Nova 代码，返回最后一个表达式的值（Java 类型）。
     */
    public Nova preload(String source) {
        return preload(source, "<preload>");
    }

    public Nova preload(String source, String fileName) {
        if (source == null) {
            throw new IllegalArgumentException("preload source must not be null");
        }
        String actualFileName = fileName != null ? fileName : "<preload>";
        preludeSources.add(new PreludeSource(source, actualFileName));
        return this;
    }

    public Nova clearPreloads() {
        preludeSources.clear();
        evaluatedPreludeCount = 0;
        return this;
    }

    public List<String> getPreloads() {
        List<String> result = new ArrayList<>(preludeSources.size());
        for (PreludeSource prelude : preludeSources) {
            result.add(prelude.source);
        }
        return Collections.unmodifiableList(result);
    }

    private String withPreloads(String code) {
        if (preludeSources.isEmpty()) {
            return code;
        }
        StringBuilder combined = new StringBuilder();
        for (PreludeSource prelude : preludeSources) {
            combined.append("// preload: ").append(prelude.fileName).append('\n');
            combined.append(prelude.source);
            if (!prelude.source.endsWith("\n")) {
                combined.append('\n');
            }
        }
        combined.append(code);
        return combined.toString();
    }

    private void markAllPreloadsEvaluated() {
        evaluatedPreludeCount = preludeSources.size();
    }

    private void ensureInterpreterPreloadsEvaluated() {
        while (evaluatedPreludeCount < preludeSources.size()) {
            PreludeSource prelude = preludeSources.get(evaluatedPreludeCount);
            interpreter.eval(prelude.source, prelude.fileName);
            evaluatedPreludeCount++;
        }
    }

    public Object eval(String code) {
        NovaValue result = preludeSources.isEmpty()
                ? interpreter.evalRepl(code)
                : interpreter.eval(withPreloads(code), "<repl>");
        markAllPreloadsEvaluated();
        return toJava(result);
    }

    /**
     * 执行 Nova 代码，命名空间中的绑定优先覆盖全局同名定义。
     */
    public Object eval(String code, String namespace) {
        applyNamespaceBindings(namespace);
        return eval(code);
    }

    /**
     * 执行文件，返回最后一个表达式的值。
     */
    public Object evalFile(String path) {
        return evalFile(new File(path));
    }

    /**
     * 执行文件，返回最后一个表达式的值。
     */
    public Object evalFile(File file) {
        String source = readFile(file);
        NovaValue result = preludeSources.isEmpty()
                ? interpreter.eval(source, file.getName())
                : interpreter.eval(withPreloads(source), file.getName());
        markAllPreloadsEvaluated();
        return toJava(result);
    }

    // ── 预编译（解析一次，执行多次） ─────────────────────

    /**
     * 预编译 Nova 代码，返回 {@link CompiledNova}，可多次执行。
     * <pre>
     * CompiledNova rule = Nova.compile("price * quantity * (1 - discount)");
     *
     * rule.set("price", 100).set("quantity", 2).set("discount", 0.1);
     * rule.run();  // 180.0
     * </pre>
     */
    public static CompiledNova compile(String code) {
        return new Nova().compile(code, "<compiled>");
    }

    /**
     * 预编译文件，返回 {@link CompiledNova}。
     */
    public static CompiledNova compileFile(String path) {
        return new Nova().compileFile(new File(path));
    }

    /**
     * 在当前实例上预编译 Nova 代码，共享已有环境。
     */
    public CompiledNova compile(String code, String fileName) {
        return new CompiledNova(withPreloads(code), fileName, this);
    }

    /**
     * 在当前实例上预编译 Nova 代码，命名空间绑定优先覆盖全局同名定义。
     */
    public CompiledNova compile(String code, String fileName, String namespace) {
        applyNamespaceBindings(namespace);
        return compile(code, fileName);
    }

    /**
     * 在当前实例上预编译文件，共享已有环境。
     */
    public CompiledNova compileFile(File file) {
        String source = readFile(file);
        return new CompiledNova(source, file.getName(), this);
    }

    // ── 字节码预编译 ────────────────────────────────────────

    /**
     * 真字节码预编译 — 编译为 JVM 字节码，独立运行。
     * 通过 defineVal/set 注册的值会自动注入到编译后的 CompiledNova 中。
     * NovaNativeFunction 会被适配为 FunctionN 接口，编译后的字节码可直接调用。
     */
    /**
     * 启用编译缓存 — 相同源码不重复编译。
     * 适合动态生成 Nova 代码但内容可能重复的场景。
     */
    public Nova enableCompilationCache() {
        if (compilationCache == null) {
            compilationCache = new java.util.concurrent.ConcurrentHashMap<>();
        }
        return this;
    }

    /**
     * 清空编译缓存。
     */
    public void clearCompilationCache() {
        if (compilationCache != null) compilationCache.clear();
    }

    public CompiledNova compileToBytecode(String code) {
        return compileToBytecode(code, "<compiled>");
    }

    /**
     * 真字节码预编译，指定文件名。
     * 若已启用编译缓存且源码命中缓存，直接复用已编译的类。
     */
    public CompiledNova compileToBytecode(String code, String fileName) {
        Builtins.ensureJavaClassRegistered();
        String actualCode = withPreloads(code);

        // 编译缓存：相同源码不重复编译
        String cacheKey = null;
        if (compilationCache != null) {
            cacheKey = buildCompilationCacheKey(actualCode, fileName);
            Map<String, Class<?>> cached = compilationCache.get(cacheKey);
            if (cached != null) {
                return buildCompiledNova(cached);
            }
        }

        NovaIrCompiler compiler = new NovaIrCompiler();
        compiler.setScriptMode(true);
        compiler.setEnableSemanticAnalysis(true);
        compiler.setStrictSemanticMode(true);
        configureRelocate(compiler);
        Map<String, Class<?>> classes = compiler.compileAndLoad(actualCode, fileName);

        if (cacheKey != null) {
            compilationCache.put(cacheKey, classes);
        }
        return buildCompiledNova(classes);
    }

    /** 从已编译的类构建 CompiledNova，注入值注册表和 Java 命名空间 */
    private CompiledNova buildCompiledNova(Map<String, Class<?>> classes) {
        return buildCompiledNova(classes, null);
    }

    private CompiledNova buildCompiledNova(Map<String, Class<?>> classes, Map<String, Object> bindingOverlay) {
        CompiledNova compiled = new CompiledNova(classes, extensionRegistry);
        if (scriptClassLoader != null) {
            compiled.setScriptClassLoader(scriptClassLoader);
        }
        for (Map.Entry<String, Object> entry : valRegistry.entrySet()) {
            compiled.set(entry.getKey(), entry.getValue());
        }
        if (bindingOverlay != null) {
            for (Map.Entry<String, Object> entry : bindingOverlay.entrySet()) {
                compiled.set(entry.getKey(), entry.getValue());
            }
        }
        NovaValue javaNamespace = interpreter.getGlobals().tryGet("Java");
        if (javaNamespace != null) {
            compiled.setRaw("Java", javaNamespace);
        }
        return compiled;
    }

    /**
     * 真字节码预编译，命名空间绑定优先覆盖全局同名定义。
     */
    public CompiledNova compileToBytecode(String code, String fileName, String namespace) {
        Builtins.ensureJavaClassRegistered();
        String actualCode = withPreloads(code);

        String cacheKey = null;
        if (compilationCache != null) {
            cacheKey = buildCompilationCacheKey(actualCode, fileName + "\0" + namespace);
            Map<String, Class<?>> cached = compilationCache.get(cacheKey);
            if (cached != null) {
                return buildCompiledNova(cached, namespaceBindings.get(namespace));
            }
        }

        NovaIrCompiler compiler = new NovaIrCompiler();
        compiler.setScriptMode(true);
        compiler.setEnableSemanticAnalysis(true);
        compiler.setStrictSemanticMode(true);
        configureRelocate(compiler);
        Map<String, Class<?>> classes = compiler.compileAndLoad(actualCode, fileName);

        if (cacheKey != null) {
            compilationCache.put(cacheKey, classes);
        }
        return buildCompiledNova(classes, namespaceBindings.get(namespace));
    }

    /**
     * 静态便捷版：无预注册值的字节码预编译。
     */
    public static CompiledNova compileToBytecodeStatic(String code) {
        return compileToBytecodeStatic(code, "<compiled>");
    }

    /**
     * 静态便捷版：无预注册值的字节码预编译，指定文件名。
     * 包含 Java 互操作支持（Java.type 等）。
     */
    public static CompiledNova compileToBytecodeStatic(String code, String fileName) {
        Builtins.ensureJavaClassRegistered();
        NovaIrCompiler compiler = new NovaIrCompiler();
        compiler.setScriptMode(true);
        compiler.setEnableSemanticAnalysis(true);
        compiler.setStrictSemanticMode(true);
        configureRelocate(compiler);
        Map<String, Class<?>> classes = compiler.compileAndLoad(code, fileName);
        CompiledNova compiled = new CompiledNova(classes, null);
        // 注入 Java 命名空间（通过临时 Interpreter 构造）
        try {
            Interpreter tempInterp = new Interpreter();
            NovaValue javaNamespace = tempInterp.getGlobals().tryGet("Java");
            if (javaNamespace != null) {
                compiled.setRaw("Java", javaNamespace);
            }
        } catch (Exception ignored) {
            // 忽略 — 不阻断编译
        }
        return compiled;
    }

    /**
     * 检测 Nova 类是否被 shadow relocate，如果是则配置编译器重映射字节码引用。
     * 例如 relocate("com.novalang.", "com.foo.novalang.") 后，
     * 包名变为 "com.foo.novalang.runtime"，提取重映射前缀。
     */
    private static void configureRelocate(NovaIrCompiler compiler) {
        String internalName = Nova.class.getName().replace('.', '/');
        // "com/novalang/runtime/Nova" → 正常，idx=4 ("com/")
        // "com/foo/novalang/runtime/Nova" → 被 relocate，idx=8 ("com/foo/")
        int idx = internalName.indexOf("novalang/runtime/");
        if (idx <= 4) return;  // 4 = "com/".length()，正常情况无需重映射
        // 被 relocate: 将 "com/novalang/" 替换为实际前缀 + "novalang/"
        compiler.setRelocatePrefix(internalName.substring(0, idx) + "novalang/");
    }

    private static String buildCompilationCacheKey(String code, String fileName) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (fileName != null) {
                digest.update(fileName.getBytes(StandardCharsets.UTF_8));
            }
            digest.update((byte) 0);
            digest.update(code.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = digest.digest();
            StringBuilder sb = new StringBuilder(bytes.length * 2 + 8);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // ── 调用函数 ──────────────────────────────────────────

    /**
     * 检查是否存在指定名称的函数。
     */
    public boolean hasFunction(String funcName) {
        ensureInterpreterPreloadsEvaluated();
        NovaValue val = interpreter.getGlobals().tryGet(funcName);
        return val instanceof NovaCallable;
    }

    /**
     * 调用已定义的 Nova 函数，参数自动从 Java 转换。
     */
    public Object call(String funcName, Object... args) {
        ensureInterpreterPreloadsEvaluated();
        NovaValue val = interpreter.getGlobals().tryGet(funcName);
        if (val == null) {
            throw new NovaRuntimeException("Function '" + funcName + "' is not defined");
        }
        if (!(val instanceof com.novalang.runtime.NovaCallable)) {
            throw new NovaRuntimeException("'" + funcName + "' is not callable");
        }
        com.novalang.runtime.NovaCallable callable = (com.novalang.runtime.NovaCallable) val;
        List<NovaValue> novaArgs = new ArrayList<>(args.length);
        for (Object arg : args) {
            novaArgs.add(AbstractNovaValue.fromJava(arg));
        }
        NovaValue result = callable.call(interpreter, novaArgs);
        return toJava(result);
    }

    // ── 接收者 Lambda / Builder DSL ────────────────────────

    /**
     * 以 receiver 为作用域接收者调用 Nova callable（lambda/函数）。
     * 在 block 执行期间，receiver 的成员可在 block 内直接访问（无需 {@code receiver.xxx}）。
     *
     * <pre>
     * // 获取 Nova 脚本中定义的 lambda
     * Object block = nova.get("myBlock");
     * Map&lt;String, Object&gt; config = new HashMap&lt;&gt;();
     * nova.invokeWithReceiver(block, config);
     * // block 内: put("key", "value") 直接调用 config.put
     * </pre>
     *
     * @param callable Nova callable（从 {@code nova.get()} 获取的函数/lambda）
     * @param receiver 作用域接收者（Java 对象或 NovaValue）
     * @param args     传递给 callable 的额外参数
     * @return callable 的返回值（自动转换为 Java 对象）
     */
    public Object invokeWithReceiver(Object callable, Object receiver, Object... args) {
        NovaValue novaCallable = callable instanceof NovaValue
                ? (NovaValue) callable : AbstractNovaValue.fromJava(callable);
        NovaValue novaReceiver = receiver instanceof NovaValue
                ? (NovaValue) receiver : AbstractNovaValue.fromJava(receiver);

        NovaCallable extracted = interpreter.extractCallable(novaCallable);
        if (extracted == null) {
            throw new NovaRuntimeException("Not callable: " + callable);
        }

        List<NovaValue> novaArgs = new ArrayList<>(args.length);
        for (Object arg : args) {
            novaArgs.add(arg instanceof NovaValue ? (NovaValue) arg : AbstractNovaValue.fromJava(arg));
        }

        NovaValue result = interpreter.invokeWithReceiver(extracted, novaReceiver, novaArgs);
        return toJava(result);
    }

    /**
     * 定义 builder 风格函数：每次调用时创建 receiver 实例，以其为作用域执行 Nova lambda，
     * 最后返回 receiver（或经 postAction 处理后的结果）。
     *
     * <pre>
     * // Java 侧定义
     * nova.defineBuilderFunction("serverConfig", ServerConfig::new, config -> {
     *     config.validate();
     *     return config;
     * });
     *
     * // Nova 脚本使用
     * val cfg = serverConfig {
     *     host = "0.0.0.0"
     *     port = 8080
     * }
     * </pre>
     *
     * @param name            函数名
     * @param receiverFactory 每次调用时创建 receiver 实例
     * @param postAction      lambda 执行完毕后处理 receiver（null 则直接返回 receiver）
     */
    @SuppressWarnings("unchecked")
    public <T> Nova defineBuilderFunction(String name,
                                           java.util.function.Supplier<T> receiverFactory,
                                           java.util.function.Function<T, Object> postAction) {
        return defineVal(name, new NovaNativeFunction(name, 1, (interp, args) -> {
            NovaCallable block = interp.asCallable(args.get(0), name);
            T receiver = receiverFactory.get();
            NovaValue novaReceiver = AbstractNovaValue.fromJava(receiver);
            ((Interpreter) interp).invokeWithReceiver(block, novaReceiver, Collections.emptyList());
            if (postAction != null) {
                return wrapReturn(postAction.apply(receiver));
            }
            return novaReceiver;
        }));
    }

    /**
     * 定义 builder 函数（简化版：无 postAction，直接返回 receiver）。
     *
     * <pre>
     * nova.defineBuilderFunction("config", Config::new);
     * // Nova: val c = config { host = "localhost"; port = 8080 }
     * </pre>
     */
    public <T> Nova defineBuilderFunction(String name, java.util.function.Supplier<T> receiverFactory) {
        return defineBuilderFunction(name, receiverFactory, null);
    }

    // ── 文件注解 ──────────────────────────────────────────

    /**
     * 注册文件注解处理器。
     * 处理器在脚本执行前被调用，适用于依赖声明、编译选项等场景。
     *
     * <pre>
     * nova.registerFileAnnotationProcessor("DependsOn", args -> {
     *     String coord = (String) args.get("value");
     *     // 下载 Maven 依赖并添加到 classpath
     * });
     * </pre>
     *
     * @param name    注解名称（不含 @file: 前缀）
     * @param handler 处理函数，接收注解参数 Map
     */
    public Nova registerFileAnnotationProcessor(String name, Consumer<Map<String, Object>> handler) {
        interpreter.registerAnnotationProcessor(new NovaAnnotationProcessor() {
            @Override
            public String getAnnotationName() { return name; }
            @Override
            public void processFile(Map<String, Object> args) { handler.accept(args); }
        });
        return this;
    }

    /**
     * 仅解析源码提取文件注解（不执行脚本）。
     * 适用于预处理场景：先提取注解 → 下载依赖 → 再执行。
     *
     * <pre>
     * List&lt;Nova.FileAnnotation&gt; anns = Nova.extractFileAnnotations(source);
     * for (Nova.FileAnnotation a : anns) {
     *     if ("DependsOn".equals(a.name)) downloadMaven((String) a.args.get("value"));
     * }
     * nova.eval(source);
     * </pre>
     */
    public static List<FileAnnotation> extractFileAnnotations(String source) {
        com.novalang.compiler.lexer.Lexer lexer = new com.novalang.compiler.lexer.Lexer(source, "<extract>");
        com.novalang.compiler.parser.Parser parser = new com.novalang.compiler.parser.Parser(lexer, "<extract>");
        com.novalang.compiler.ast.decl.Program program = parser.parse();

        List<FileAnnotation> result = new ArrayList<>();
        for (com.novalang.compiler.ast.decl.Annotation ann : program.getFileAnnotations()) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (com.novalang.compiler.ast.decl.Annotation.AnnotationArg arg : ann.getArgs()) {
                String key = arg.getName() != null ? arg.getName() : "value";
                Object val = extractLiteralValue(arg.getValue());
                args.put(key, val);
            }
            result.add(new FileAnnotation(ann.getName(), args));
        }
        return result;
    }

    /** 从 AST 字面量表达式中提取 Java 值 */
    private static Object extractLiteralValue(com.novalang.compiler.ast.expr.Expression expr) {
        if (expr instanceof com.novalang.compiler.ast.expr.Literal) {
            return ((com.novalang.compiler.ast.expr.Literal) expr).getValue();
        }
        return expr.toString();
    }

    /** 文件注解数据 */
    public static final class FileAnnotation {
        public final String name;
        public final Map<String, Object> args;

        public FileAnnotation(String name, Map<String, Object> args) {
            this.name = name;
            this.args = args;
        }

        @Override
        public String toString() {
            return "@file:" + name + (args.isEmpty() ? "" : "(" + args + ")");
        }
    }

    // ── 底层访问 ──────────────────────────────────────────

    public Interpreter getInterpreter() {
        return interpreter;
    }

    // ── 静态便捷方法 ─────────────────────────────────────

    /**
     * 执行 Nova 代码，返回结果。
     */
    public static Object run(String code) {
        return new Nova().eval(code);
    }

    /**
     * 执行 Nova 代码，支持 key-value 形式绑定变量。
     * <pre>Nova.run("x + y", "x", 10, "y", 20)</pre>
     */
    public static Object run(String code, Object... bindings) {
        Nova nova = new Nova();
        applyBindings(nova, bindings);
        return nova.eval(code);
    }

    /**
     * 执行文件，返回结果。
     */
    public static Object runFile(String path) {
        return new Nova().evalFile(path);
    }

    /**
     * 执行文件，支持 key-value 形式绑定变量。
     */
    public static Object runFile(String path, Object... bindings) {
        Nova nova = new Nova();
        applyBindings(nova, bindings);
        return nova.evalFile(path);
    }

    // ── 内部工具 ──────────────────────────────────────────

    private static Object toJava(NovaValue value) {
        if (value == NovaNull.UNIT) return null;
        return value.toJavaValue();
    }

    private static void applyBindings(Nova nova, Object[] bindings) {
        if (bindings.length % 2 != 0) {
            throw new IllegalArgumentException("Bindings must be key-value pairs (even number of arguments)");
        }
        for (int i = 0; i < bindings.length; i += 2) {
            if (!(bindings[i] instanceof String)) {
                throw new IllegalArgumentException("Binding key must be a String, got: " + bindings[i].getClass().getName());
            }
            nova.set((String) bindings[i], bindings[i + 1]);
        }
    }

    private static String readFile(File file) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new NovaRuntimeException("Cannot read file: " + file.getPath(), e);
        }
    }
}
