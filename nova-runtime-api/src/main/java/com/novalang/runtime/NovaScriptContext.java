package com.novalang.runtime;

import com.novalang.runtime.stdlib.NovaScopeFunctions;


import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private Map<String, Object> bindings = new ConcurrentHashMap<>();
    private ExtensionRegistry extensionRegistry;

    /** 获取当前线程的上下文（用于并发传播） */
    public static NovaScriptContext current() {
        return CURRENT.get();
    }

    /** 设置当前线程的上下文（用于 worker 线程继承父线程的上下文） */
    public static void setCurrent(NovaScriptContext ctx) {
        if (ctx != null) {
            CURRENT.set(ctx);
        } else {
            CURRENT.remove();
        }
    }

    /**
     * 初始化当前线程的脚本上下文（拷贝模式）
     */
    public static void init(Map<String, Object> initialBindings) {
        NovaScriptContext ctx = new NovaScriptContext();
        if (initialBindings != null) {
            for (Map.Entry<String, Object> e : initialBindings.entrySet()) {
                ctx.bindings.put(e.getKey(), e.getValue() != null ? e.getValue() : NULL_SENTINEL);
            }
        }
        CURRENT.set(ctx);
    }

    /**
     * 零拷贝初始化：直接使用外部 Map 作为 bindings，Nova 的读写直接操作该 Map。
     * <p>适用于需要 Nova 脚本直接修改宿主活内存的场景。</p>
     *
     * <pre>
     * // Java 宿主端
     * Map&lt;String, Object&gt; liveData = getLiveDataMap();
     * NovaScriptContext.initDirect(liveData);
     * compiled.runBytecode();
     * // liveData 已被 Nova 脚本直接修改，无需拷回
     * </pre>
     */
    public static void initDirect(Map<String, Object> liveMap) {
        NovaScriptContext ctx = new NovaScriptContext();
        ctx.bindings = liveMap;
        CURRENT.set(ctx);
    }

    /**
     * 读取绑定变量（编译后的字节码调用此方法）
     */
    public static Object get(String name) {
        NovaScriptContext ctx = CURRENT.get();
        if (ctx != null) {
            Object val = ctx.bindings.get(name);
            if (val != null) return val == NULL_SENTINEL ? null : val;
        }
        // scope receiver 字段读取（receiver.block() 内裸字段访问）
        Object scopeReceiver = NovaScopeFunctions.getScopeReceiver();
        if (scopeReceiver != null) {
            try {
                return NovaDynamic.getMember(scopeReceiver, name);
            } catch (Exception ignored) {
                // receiver 上没有此成员 → 继续回退
            }
        }
        // 回退到 shared() 全局注册表（变量 + 函数 + 命名空间代理）
        NovaRuntime.RegisteredEntry entry = NovaRuntime.shared().lookup(name);
        if (entry != null) return entry.getValue();
        NovaRuntime.NovaNamespace ns = NovaRuntime.shared().getNamespaceProxy(name);
        if (ns != null) return ns;
        return null;
    }

    /**
     * 写入绑定变量（编译后的字节码调用此方法）
     */
    /** ConcurrentHashMap 不接受 null value，用 sentinel 占位 */
    private static final Object NULL_SENTINEL = new Object();

    public static void set(String name, Object value) {
        // scope receiver 字段写入（receiver.block() 内裸字段赋值）
        Object scopeReceiver = NovaScopeFunctions.getScopeReceiver();
        if (scopeReceiver != null) {
            try {
                NovaDynamic.setMember(scopeReceiver, name, value);
                return;
            } catch (Exception ignored) {
                // receiver 上没有此字段 → 写到 bindings
            }
        }
        NovaScriptContext ctx = CURRENT.get();
        if (ctx != null) {
            ctx.bindings.put(name, value != null ? value : NULL_SENTINEL);
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
        if (ctx == null) return new HashMap<>();
        HashMap<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> e : ctx.bindings.entrySet()) {
            result.put(e.getKey(), e.getValue() == NULL_SENTINEL ? null : e.getValue());
        }
        return result;
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
                // FunctionN switch 未匹配：通过 LambdaUtils MethodHandle 缓存调用
                // （处理 HighArityAdapter / vararg 适配器 / 其他有 invoke 方法的对象）
                java.lang.invoke.MethodHandle mh = com.novalang.runtime.stdlib.LambdaUtils.getInvokeHandle(func, args.length);
                if (mh != null) {
                    try {
                        // 常见 arity 精确 invoke（JIT 友好），4+ 退化 invokeWithArguments
                        switch (args.length) {
                            case 0: return mh.invoke(func);
                            case 1: return mh.invoke(func, args[0]);
                            case 2: return mh.invoke(func, args[0], args[1]);
                            case 3: return mh.invoke(func, args[0], args[1], args[2]);
                            default:
                                Object[] full = new Object[args.length + 1];
                                full[0] = func;
                                System.arraycopy(args, 0, full, 1, args.length);
                                return mh.invokeWithArguments(full);
                        }
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw NovaErrors.wrap("函数 '" + name + "' 调用失败", t);
                    }
                }
                throw new NovaException(NovaException.ErrorKind.ARGUMENT_MISMATCH,
                        "函数 '" + name + "' 存在但无法使用 " + args.length + " 个参数调用",
                        "类型: " + func.getClass().getSimpleName());
            }
        }
        // stdlib 回退：通过 NovaRuntime 统一入口调用内置函数
        Object builtinResult = NovaRuntime.callBuiltin(name, args);
        if (builtinResult != NovaRuntime.NOT_FOUND) return builtinResult;
        // scope receiver 回退：.run { method() } 中裸方法调用分派到 scope receiver
        Object scopeReceiver = NovaScopeFunctions.getScopeReceiver();
        if (scopeReceiver != null) {
            return NovaDynamic.invokeMethod(scopeReceiver, name, args);
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
