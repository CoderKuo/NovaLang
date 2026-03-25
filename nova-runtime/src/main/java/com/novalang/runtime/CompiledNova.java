package com.novalang.runtime;

import com.novalang.runtime.interpreter.MethodHandleCache;
import com.novalang.runtime.interpreter.NovaNativeFunction;
import com.novalang.runtime.interpreter.NovaRuntimeException;
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
    /** 脚本级 ClassLoader（编译模式用于 javaClass() 类隔离） */
    private ClassLoader scriptClassLoader;

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
        if (compiledClasses != null) {
            if (mainHandle != null) return runBytecode();
            // 纯函数定义的脚本无 main()，run() 无操作，通过 call() 调用函数
            return null;
        }
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
        // 字节码模式：初始化脚本上下文（使编译函数能访问注入的全局函数），调用后清理
        NovaScriptContext.init(bindings);
        if (extensionRegistry != null) {
            NovaScriptContext.setExtensionRegistry(extensionRegistry);
        }
        try {
            Class<?> cls = funcClassCache.get(funcName);
            if (cls == null) {
                cls = findFuncClass(funcName);
                funcClassCache.put(funcName, cls);
            }
            Object result = MethodHandleCache.getInstance().invokeStatic(cls, funcName, args);
            // 回写全局变量（函数内可能修改了 showTime 等全局变量）
            bindings.putAll(NovaScriptContext.getAll());
            if (result instanceof NovaValue) {
                if (((NovaValue) result).isNull()) return null;
                return ((NovaValue) result).toJavaValue();
            }
            return result;
        } catch (NovaRuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            NovaScriptContext.clear();
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

    // ── 函数注册 ──────────────────────────────────────────

    /** 注入无参函数 */
    public CompiledNova defineFunction(String name, Function0<Object> func) {
        return set(name, new NovaNativeFunction(name, 0, (ctx, args) ->
                AbstractNovaValue.fromJava(func.invoke())));
    }

    /** 注入单参函数 */
    public CompiledNova defineFunction(String name, Function1<Object, Object> func) {
        return set(name, new NovaNativeFunction(name, 1, (ctx, args) ->
                AbstractNovaValue.fromJava(func.invoke(unwrapArg(args.get(0))))));
    }

    /** 注入双参函数 */
    public CompiledNova defineFunction(String name, Function2<Object, Object, Object> func) {
        return set(name, new NovaNativeFunction(name, 2, (ctx, args) ->
                AbstractNovaValue.fromJava(func.invoke(unwrapArg(args.get(0)), unwrapArg(args.get(1))))));
    }

    /** 注入三参函数 */
    public CompiledNova defineFunction(String name, Function3<Object, Object, Object, Object> func) {
        return set(name, new NovaNativeFunction(name, 3, (ctx, args) ->
                AbstractNovaValue.fromJava(func.invoke(unwrapArg(args.get(0)), unwrapArg(args.get(1)), unwrapArg(args.get(2))))));
    }

    /** 注入变参函数 */
    public CompiledNova defineFunctionVararg(String name, Function1<Object[], Object> func) {
        return set(name, new NovaNativeFunction(name, -1, (ctx, args) -> {
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) javaArgs[i] = unwrapArg(args.get(i));
            return AbstractNovaValue.fromJava(func.invoke(javaArgs));
        }));
    }

    // ── 扩展函数注册 ──────────────────────────────────────

    /** 注册扩展方法：receiver → result */
    public CompiledNova registerExtension(Class<?> type, String name, Function1<Object, Object> func) {
        return registerExtensionInternal(type, name, 1, func);
    }

    /** 注册扩展方法：(receiver, arg1) → result */
    public CompiledNova registerExtension(Class<?> type, String name, Function2<Object, Object, Object> func) {
        return registerExtensionInternal(type, name, 2, func);
    }

    /** 注册扩展方法：(receiver, arg1, arg2) → result */
    public CompiledNova registerExtension(Class<?> type, String name, Function3<Object, Object, Object, Object> func) {
        return registerExtensionInternal(type, name, 3, func);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private CompiledNova registerExtensionInternal(Class<?> type, String name, int arity, Object func) {
        if (extensionRegistry == null) return this;
        int extraParams = Math.max(0, arity - 1);
        Class<?>[] paramTypes = new Class<?>[extraParams];
        java.util.Arrays.fill(paramTypes, Object.class);
        extensionRegistry.register(type, name, paramTypes, Object.class,
                new ExtensionMethod<Object, Object>() {
                    @Override
                    public Object invoke(Object receiver, Object[] args) {
                        switch (arity) {
                            case 1: return ((Function1) func).invoke(receiver);
                            case 2: return ((Function2) func).invoke(receiver, args[0]);
                            case 3: return ((Function3) func).invoke(receiver, args[0], args[1]);
                            default: return null;
                        }
                    }
                });
        // 解释器模式：同时写入 Nova 实例的 extensionRegistry
        if (nova != null) {
            NovaNativeFunction nf = new NovaNativeFunction(name, arity, (ctx, a) -> {
                Object[] javaArgs = new Object[a.size()];
                for (int i = 0; i < a.size(); i++) javaArgs[i] = unwrapArg(a.get(i));
                switch (arity) {
                    case 1: return AbstractNovaValue.fromJava(((Function1) func).invoke(javaArgs[0]));
                    case 2: return AbstractNovaValue.fromJava(((Function2) func).invoke(javaArgs[0], javaArgs[1]));
                    case 3: return AbstractNovaValue.fromJava(((Function3) func).invoke(javaArgs[0], javaArgs[1], javaArgs[2]));
                    default: return NovaNull.NULL;
                }
            });
            nova.registerExtension(type, name, nf);
        }
        return this;
    }

    private static Object unwrapArg(NovaValue v) {
        return v != null ? v.toJavaValue() : null;
    }

    /**
     * 设置变量（不经过类型转换，保持原始对象类型）。
     * 用于注入 NovaMap 等需要保持 Nova 类型系统语义的对象。
     */
    CompiledNova setRaw(String name, Object value) {
        bindings.put(name, value);
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
        return compiledClasses != null;
    }

    /**
     * 获取关联的 Nova 实例（字节码模式返回 null）。
     */
    public Nova getNova() {
        return nova;
    }

    /** 设置脚本级 ClassLoader */
    public void setScriptClassLoader(ClassLoader cl) {
        this.scriptClassLoader = cl;
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
        if (scriptClassLoader != null) {
            com.novalang.runtime.interpreter.JavaInterop.setScriptClassLoader(scriptClassLoader);
        }
        try {
            Object result = mainHandle.invoke();
            // 回写导出变量
            bindings.putAll(NovaScriptContext.getAll());
            // 将 NovaValue 转为 Java 对象（与解释器模式行为一致）
            if (result instanceof NovaValue) {
                if (((NovaValue) result).isNull()) return null;
                return ((NovaValue) result).toJavaValue();
            }
            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e.getMessage(), e);
        } finally {
            com.novalang.runtime.interpreter.JavaInterop.setScriptClassLoader(null);
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
        // 纯函数定义的脚本没有 main()，run() 返回 null，仍可通过 call() 调用函数
        return null;
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
