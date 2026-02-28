package nova.runtime;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.interpreter.*;
import nova.runtime.types.Environment;


import java.io.*;
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

    public Nova() {
        this.interpreter = new Interpreter();
    }

    public Nova(NovaSecurityPolicy policy) {
        this.interpreter = new Interpreter(policy);
    }

    // ── IO 重定向 ─────────────────────────────────────────

    public Nova setStdout(PrintStream out) {
        interpreter.setStdout(out);
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
        NovaValue nv = AbstractNovaValue.fromJava(value);
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
     * 定义不可变变量（val）。支持 NovaValue（如 NovaNativeFunction）和普通 Java 对象。
     * 同时写入 valRegistry 供字节码模式使用。
     * <pre>
     * nova.defineVal("PI", 3.14159);
     * nova.defineVal("greet", NovaNativeFunction.create("greet", arg -> NovaString.of("Hello, " + arg)));
     * </pre>
     */
    public Nova defineVal(String name, Object value) {
        interpreter.getGlobals().defineVal(name, AbstractNovaValue.fromJava(value));
        valRegistry.put(name, NativeFunctionAdapter.toBindingValue(value));
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
        nova.runtime.NovaCallable method = interpreter.getExtension(targetType, existing);
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
    public Nova registerExtension(Class<?> targetType, String methodName, nova.runtime.NovaCallable method) {
        // 解释器侧
        interpreter.registerExtension(targetType, methodName, method);
        // 编译器侧 — 适配 NovaCallable → ExtensionMethod
        int arity = (method instanceof NovaNativeFunction)
                ? ((NovaNativeFunction) method).getArity() : 0;
        Class<?>[] paramTypes = new Class<?>[arity];
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
        NovaValue val = interpreter.getGlobals().tryGet(name);
        if (val == null) return null;
        return toJava(val);
    }

    /**
     * 导出所有全局变量为 Java 类型。
     */
    public Map<String, Object> getAll() {
        Map<String, Object> result = new HashMap<>();
        for (String name : interpreter.getGlobals().getLocalNames()) {
            NovaValue val = interpreter.getGlobals().tryGet(name);
            if (val != null) {
                result.put(name, toJava(val));
            }
        }
        return result;
    }

    // ── 执行代码 ──────────────────────────────────────────

    /**
     * 执行 Nova 代码，返回最后一个表达式的值（Java 类型）。
     */
    public Object eval(String code) {
        NovaValue result = interpreter.evalRepl(code);
        return toJava(result);
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
        NovaValue result = interpreter.eval(source, file.getName());
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
        return new CompiledNova(code, fileName, this);
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
    public CompiledNova compileToBytecode(String code) {
        return compileToBytecode(code, "<compiled>");
    }

    /**
     * 真字节码预编译，指定文件名。
     */
    public CompiledNova compileToBytecode(String code, String fileName) {
        NovaIrCompiler compiler = new NovaIrCompiler();
        compiler.setScriptMode(true);
        Map<String, Class<?>> classes = compiler.compileAndLoad(code, fileName);
        CompiledNova compiled = new CompiledNova(classes, extensionRegistry);
        // 注入已注册的值
        for (Map.Entry<String, Object> entry : valRegistry.entrySet()) {
            compiled.set(entry.getKey(), entry.getValue());
        }
        return compiled;
    }

    /**
     * 静态便捷版：无预注册值的字节码预编译。
     */
    public static CompiledNova compileToBytecodeStatic(String code) {
        return compileToBytecodeStatic(code, "<compiled>");
    }

    /**
     * 静态便捷版：无预注册值的字节码预编译，指定文件名。
     */
    public static CompiledNova compileToBytecodeStatic(String code, String fileName) {
        NovaIrCompiler compiler = new NovaIrCompiler();
        compiler.setScriptMode(true);
        Map<String, Class<?>> classes = compiler.compileAndLoad(code, fileName);
        return new CompiledNova(classes, null);
    }

    // ── 调用函数 ──────────────────────────────────────────

    /**
     * 检查是否存在指定名称的函数。
     */
    public boolean hasFunction(String funcName) {
        NovaValue val = interpreter.getGlobals().tryGet(funcName);
        return val instanceof NovaCallable;
    }

    /**
     * 调用已定义的 Nova 函数，参数自动从 Java 转换。
     */
    public Object call(String funcName, Object... args) {
        NovaValue val = interpreter.getGlobals().tryGet(funcName);
        if (val == null) {
            throw new NovaRuntimeException("Function '" + funcName + "' is not defined");
        }
        if (!(val instanceof nova.runtime.NovaCallable)) {
            throw new NovaRuntimeException("'" + funcName + "' is not callable");
        }
        nova.runtime.NovaCallable callable = (nova.runtime.NovaCallable) val;
        List<NovaValue> novaArgs = new ArrayList<>(args.length);
        for (Object arg : args) {
            novaArgs.add(AbstractNovaValue.fromJava(arg));
        }
        NovaValue result = callable.call(interpreter, novaArgs);
        return toJava(result);
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
