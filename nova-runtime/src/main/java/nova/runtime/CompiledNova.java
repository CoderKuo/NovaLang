package nova.runtime;

import nova.runtime.interpreter.MethodHandleCache;
import nova.runtime.interpreter.NovaRuntimeException;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Map;

/**
 * 预编译的 Nova 代码 — 支持两种模式：
 *
 * <h3>解释器模式</h3>
 * <p>通过 {@link Nova#compile(String, String)} 创建，与 Nova 实例共享环境，
 * 适合需要共享状态的场景。</p>
 * <pre>
 * Nova nova = new Nova();
 * CompiledNova rule = nova.compile("price * quantity * (1 - discount)", "rule");
 * nova.set("price", 100).set("quantity", 2).set("discount", 0.1);
 * Object total = rule.run();  // 180.0
 * </pre>
 *
 * <h3>字节码模式</h3>
 * <p>通过 {@link Nova#compileToBytecode(String)} 创建，编译为 JVM 字节码独立运行，
 * 不依赖解释器环境。</p>
 * <pre>
 * CompiledNova compiled = new Nova().compileToBytecode("1 + 2");
 * Object result = compiled.run();  // 3
 * </pre>
 */
public final class CompiledNova {

    // ── 解释器模式（与 Nova 实例共享环境，MIR 管线执行） ──
    private final String source;              // nullable
    private final String fileName;            // nullable
    private final Nova nova;                 // nullable
    private final Program program;           // nullable, 预解析的 AST

    // ── 字节码模式（独立运行） ──
    private final MethodHandle mainHandle;   // nullable
    private final Map<String, Class<?>> compiledClasses;  // nullable
    private final Map<String, Object> bindings = new HashMap<>();
    private final ExtensionRegistry extensionRegistry;  // nullable
    /** 函数名 → 所属编译类（惰性缓存，避免每次全表扫描） */
    private final Map<String, Class<?>> funcClassCache = new HashMap<>();

    /** 解释器模式构造 — 预解析源代码为 AST，run() 时跳过词法分析和解析 */
    CompiledNova(String source, String fileName, Nova nova) {
        this.source = source;
        this.fileName = fileName;
        this.nova = nova;
        this.program = new Parser(new Lexer(source, fileName), fileName).parse();
        this.mainHandle = null;
        this.compiledClasses = null;
        this.extensionRegistry = null;
    }

    /** 字节码模式构造 */
    CompiledNova(Map<String, Class<?>> classes, ExtensionRegistry extensionRegistry) {
        this.source = null;
        this.fileName = null;
        this.nova = null;
        this.program = null;
        this.compiledClasses = classes;
        this.mainHandle = findMain(classes);
        this.extensionRegistry = extensionRegistry;
    }

    /**
     * 执行预编译的代码，返回最后一个表达式的值。
     */
    public Object run() {
        if (mainHandle != null) return runBytecode();
        return runInterpreted();
    }

    /**
     * 先绑定变量，再执行预编译的代码。
     * <pre>rule.run("price", 100, "quantity", 2)</pre>
     */
    public Object run(Object... kvBindings) {
        applyBindings(kvBindings);
        return run();
    }

    /**
     * 检查是否存在指定名称的函数。
     */
    public boolean hasFunction(String funcName) {
        if (nova != null) {
            NovaValue val = nova.getInterpreter().getGlobals().tryGet(funcName);
            return val instanceof NovaCallable;
        }
        // 字节码模式
        if (funcClassCache.containsKey(funcName)) return true;
        MethodHandleCache cache = MethodHandleCache.getInstance();
        for (Class<?> cls : compiledClasses.values()) {
            if (cache.hasMethodName(cls, funcName)) {
                funcClassCache.put(funcName, cls);
                return true;
            }
        }
        return false;
    }

    /**
     * 调用预编译代码中定义的函数。
     * 通过 MethodHandleCache 进行类型兼容匹配（支持自动装箱、数值宽化、varargs），
     * 并缓存函数名→编译类的映射以避免重复扫描。
     */
    public Object call(String funcName, Object... args) {
        if (nova != null) return nova.call(funcName, args);
        // 字节码模式：通过 MethodHandleCache 查找类型兼容的静态方法
        Class<?> cls = funcClassCache.get(funcName);
        if (cls == null) {
            cls = findFuncClass(funcName);
            funcClassCache.put(funcName, cls);
        }
        try {
            return MethodHandleCache.getInstance().invokeStatic(cls, funcName, args);
        } catch (NovaRuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /** 扫描编译类，查找包含指定函数名的类 */
    private Class<?> findFuncClass(String funcName) {
        MethodHandleCache cache = MethodHandleCache.getInstance();
        for (Class<?> cls : compiledClasses.values()) {
            if (cache.hasMethodName(cls, funcName)) return cls;
        }
        throw new RuntimeException("Function '" + funcName + "' not found");
    }

    /**
     * 设置变量。
     */
    public CompiledNova set(String name, Object value) {
        if (nova != null) nova.set(name, value);
        else bindings.put(name, NativeFunctionAdapter.toBindingValue(value));
        return this;
    }

    /**
     * 获取变量值。
     */
    public Object get(String name) {
        if (nova != null) return nova.get(name);
        return bindings.get(name);
    }

    /**
     * 获取所有绑定变量（字节码模式用于导出回 Bindings）。
     */
    public Map<String, Object> getBindings() {
        if (nova != null) return nova.getAll();
        return new HashMap<>(bindings);
    }

    /**
     * 是否为字节码模式。
     */
    public boolean isBytecodeMode() {
        return mainHandle != null;
    }

    /**
     * 获取关联的 Nova 实例（字节码模式返回 null）。
     */
    public Nova getNova() {
        return nova;
    }

    // ── 内部方法 ──

    private Object runInterpreted() {
        NovaValue result = nova.getInterpreter().eval(source, fileName, program);
        if (result == NovaNull.UNIT) return null;
        return result.toJavaValue();
    }

    private Object runBytecode() {
        NovaScriptContext.init(bindings);
        if (extensionRegistry != null) {
            NovaScriptContext.setExtensionRegistry(extensionRegistry);
        }
        try {
            Object result = mainHandle.invoke();
            // 回写导出变量
            bindings.putAll(NovaScriptContext.getAll());
            return result;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            NovaScriptContext.clear();
        }
    }

    private static MethodHandle findMain(Map<String, Class<?>> classes) {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        for (Class<?> cls : classes.values()) {
            try {
                java.lang.reflect.Method method = cls.getMethod("main");
                MethodHandle handle = lookup.unreflect(method);
                return handle.asType(MethodType.methodType(Object.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                // 该类没有 main()，继续查找
            }
        }
        throw new RuntimeException("No main method found in compiled classes");
    }

    private void applyBindings(Object[] kvBindings) {
        if (kvBindings.length % 2 != 0) {
            throw new IllegalArgumentException("Bindings must be key-value pairs (even number of arguments)");
        }
        for (int i = 0; i < kvBindings.length; i += 2) {
            if (!(kvBindings[i] instanceof String)) {
                throw new IllegalArgumentException("Binding key must be a String, got: " + kvBindings[i].getClass().getName());
            }
            set((String) kvBindings[i], kvBindings[i + 1]);
        }
    }
}
