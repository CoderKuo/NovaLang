package nova.runtime;

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
 * <p>使用 ThreadLocal 保证线程安全，由 {@code NovaCompiledScript.eval()} 管理生命周期。</p>
 */
public class NovaScriptContext {

    private static final ThreadLocal<NovaScriptContext> CURRENT = new ThreadLocal<>();

    private final Map<String, Object> bindings = new HashMap<>();
    private ExtensionRegistry extensionRegistry;

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
