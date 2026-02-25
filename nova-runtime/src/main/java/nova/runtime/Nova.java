package nova.runtime;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.interpreter.*;


import java.io.*;
import java.util.*;

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
        NovaValue nv = NovaValue.fromJava(value);
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
        interpreter.getGlobals().defineVal(name, NovaValue.fromJava(value));
        valRegistry.put(name, NativeFunctionAdapter.toBindingValue(value));
        return this;
    }

    /**
     * 为指定 Java 类型注册扩展方法。
     * 同时写入解释器和 ExtensionRegistry（供编译模式使用）。
     * <pre>
     * nova.registerExtension(String.class, "reverse", new NovaNativeFunction(...));
     * </pre>
     */
    public Nova registerExtension(Class<?> targetType, String methodName, NovaCallable method) {
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
                        novaArgs.add(NovaValue.fromJava(receiver));
                        for (Object a : args) novaArgs.add(NovaValue.fromJava(a));
                        NovaValue result = method.call(null, novaArgs);
                        return result == NovaNull.UNIT ? null : result.toJavaValue();
                    }
                });
        return this;
    }

    ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
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
     * 调用已定义的 Nova 函数，参数自动从 Java 转换。
     */
    public Object call(String funcName, Object... args) {
        NovaValue val = interpreter.getGlobals().tryGet(funcName);
        if (val == null) {
            throw new NovaRuntimeException("Function '" + funcName + "' is not defined");
        }
        if (!(val instanceof NovaCallable)) {
            throw new NovaRuntimeException("'" + funcName + "' is not callable");
        }
        NovaCallable callable = (NovaCallable) val;
        List<NovaValue> novaArgs = new ArrayList<>(args.length);
        for (Object arg : args) {
            novaArgs.add(NovaValue.fromJava(arg));
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
