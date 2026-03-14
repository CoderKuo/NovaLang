package nova.runtime;

import nova.runtime.stdlib.StdlibRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * JSR-223 脚本模式的运行时上下文。
 *
 * <p>编译后的脚本字节码通过此类与 ScriptContext Bindings 交互：
 * <ul>
 *   <li>未解析的变量 → {@code NovaScriptContext.get(name)}</li>
 *   <li>顶层变量赋值 → {@code NovaScriptContext.set(name, value)}</li>
 * </ul>
 *
 * <p>使用 ThreadLocal 保证线程安全，由 {@code NovaCompiledScript.eval()} 管理生命周期。
 * 并发函数（launch/parallel）通过 {@link #current()} 捕获后手动传播到 worker 线程。</p>
 */
public class NovaScriptContext {

    private static final ThreadLocal<NovaScriptContext> CURRENT = new ThreadLocal<>();

    private final Map<String, Object> bindings = new HashMap<>();
    private ExtensionRegistry extensionRegistry;

    /** 获取当前线程的上下文（用于并发传播） */
    public static NovaScriptContext current() {
        return CURRENT.get();
    }

    /** 设置当前线程的上下文（用于 worker 线程继承父线程的上下文） */
    public static void setCurrent(NovaScriptContext ctx) {
        if (ctx != null) {
            CURRENT.set(ctx);
        }
    }

    /**
     * 初始化当前线程的脚本上下文
     */
    public static void init(Map<String, Object> initialBindings) {
        NovaScriptContext ctx = new NovaScriptContext();
        if (initialBindings != null) {
            ctx.bindings.putAll(initialBindings);
        }
        CURRENT.set(ctx);
    }

    /**
     * 读取绑定变量（编译后的字节码调用此方法）
     */
    public static Object get(String name) {
        NovaScriptContext ctx = CURRENT.get();
        return ctx != null ? ctx.bindings.get(name) : null;
    }

    /**
     * 写入绑定变量（编译后的字节码调用此方法）
     */
    public static void set(String name, Object value) {
        NovaScriptContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.bindings.put(name, value);
        }
    }

    /**
     * 声明不可变变量（编译后的字节码对 val 声明调用此方法）
     */
    public static void defineVal(String name, Object value) {
        set(name, value);
    }

    /**
     * 声明可变变量（编译后的字节码对 var 声明调用此方法）
     */
    public static void defineVar(String name, Object value) {
        set(name, value);
    }

    /**
     * 获取所有绑定（eval 后导出回 Bindings）
     */
    public static Map<String, Object> getAll() {
        NovaScriptContext ctx = CURRENT.get();
        return ctx != null ? new HashMap<>(ctx.bindings) : new HashMap<>();
    }

    /**
     * 运行时函数分派（编译模式：未解析的函数调用通过此方法在绑定中查找并调用）
     *
     * <p>参数自动转换：Java 原始类型 → NovaValue，编译 lambda (FunctionN) → JavaObjectValue 包装。
     * 返回值自动解包：NovaValue → Java 原始类型，以兼容编译路径的 NovaOps 运算。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object call(String name, Object... args) {
        NovaScriptContext ctx = CURRENT.get();
        if (ctx != null) {
            Object func = ctx.bindings.get(name);
            if (func instanceof NovaValue && ((NovaValue) func).isCallable()) {
                NovaValue[] novaArgs = new NovaValue[args.length];
                for (int i = 0; i < args.length; i++) {
                    novaArgs[i] = safeToNovaValue(args[i]);
                }
                Object result = ((NovaValue) func).dynamicInvoke(novaArgs);
                // 解包 NovaValue → Java 原始类型，编译路径运算符（NovaOps）期望 Number/String 等
                if (result instanceof NovaValue) {
                    return ((NovaValue) result).toJavaValue();
                }
                return result;
            }
            // 处理 FunctionN 类型（NativeFunctionAdapter.toBindingValue() 将宿主函数转为 FunctionN）
            if (func != null) {
                switch (args.length) {
                    case 0:
                        if (func instanceof Function0) return ((Function0) func).invoke();
                        break;
                    case 1:
                        if (func instanceof Function1) return ((Function1) func).invoke(args[0]);
                        break;
                    case 2:
                        if (func instanceof Function2) return ((Function2) func).invoke(args[0], args[1]);
                        break;
                    case 3:
                        if (func instanceof Function3) return ((Function3) func).invoke(args[0], args[1], args[2]);
                        break;
                    case 4:
                        if (func instanceof Function4) return ((Function4) func).invoke(args[0], args[1], args[2], args[3]);
                        break;
                    case 5:
                        if (func instanceof Function5) return ((Function5) func).invoke(args[0], args[1], args[2], args[3], args[4]);
                        break;
                    case 6:
                        if (func instanceof Function6) return ((Function6) func).invoke(args[0], args[1], args[2], args[3], args[4], args[5]);
                        break;
                    case 7:
                        if (func instanceof Function7) return ((Function7) func).invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
                        break;
                    case 8:
                        if (func instanceof Function8) return ((Function8) func).invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
                        break;
                }
                // FunctionN switch 未匹配：函数存在但 arity 不匹配或 instanceof 失败
                throw new RuntimeException("Function '" + name + "' exists but cannot be called with "
                        + args.length + " arg(s) (type: " + func.getClass().getName() + ")");
            }
        }
        // stdlib 回退：脚本绑定中未找到时，尝试调用标准库函数（如 sin, cos, abs 等）
        StdlibRegistry.NativeFunctionInfo nfInfo = StdlibRegistry.getNativeFunction(name);
        if (nfInfo != null) {
            return nfInfo.impl.apply(args);
        }
        throw new NovaException("Undefined function: " + name);
    }

    private static NovaValue safeToNovaValue(Object arg) {
        if (arg instanceof NovaValue) return (NovaValue) arg;
        try {
            return AbstractNovaValue.fromJava(arg);
        } catch (Exception e) {
            // 编译 lambda (FunctionN) 等无法转换的 Java 对象，包装为 JavaObjectValue
            return new JavaObjectValue(arg);
        }
    }

    /** 轻量包装：将任意 Java 对象表示为 NovaValue（编译 lambda、宿主对象等） */
    private static final class JavaObjectValue extends AbstractNovaValue {
        private final Object value;
        JavaObjectValue(Object value) { this.value = value; }
        @Override public String getTypeName() { return "Java:" + value.getClass().getSimpleName(); }
        @Override public Object toJavaValue() { return value; }
    }

    /**
     * 检查当前线程是否有活跃的脚本上下文
     */
    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    /**
     * 清理当前线程的上下文
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 设置扩展方法注册表（编译模式用）
     */
    public static void setExtensionRegistry(ExtensionRegistry registry) {
        NovaScriptContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.extensionRegistry = registry;
        }
    }

    /**
     * 获取扩展方法注册表（NovaDynamic 回退查找用）
     */
    public static ExtensionRegistry getExtensionRegistry() {
        NovaScriptContext ctx = CURRENT.get();
        return ctx != null ? ctx.extensionRegistry : null;
    }
}
