package nova.runtime;

import nova.runtime.interpreter.reflect.NovaClassInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * 运行时注解处理器注册表，供编译后的字节码调用。
 *
 * <p>编译模式下，{@code registerAnnotationProcessor("name", lambda)} 编译为
 * {@code NovaAnnotations.register("name", lambda)}；被注解类的 {@code <clinit>}
 * 编译为 {@code NovaAnnotations.trigger("name", Foo.class, argsMap)}。</p>
 */
public class NovaAnnotations {

    private static final Map<String, List<NovaProcessorHandle>> processors
            = new ConcurrentHashMap<>();

    /**
     * 注册运行时注解处理器，返回句柄对象。
     *
     * @param name    注解名称
     * @param handler 处理器（参数1: NovaClassInfo, 参数2: 注解参数 Map）
     * @return 处理器句柄，支持 unregister/register/replace
     */
    @SuppressWarnings("rawtypes")
    public static NovaProcessorHandle register(String name, BiConsumer handler) {
        NovaProcessorHandle handle = new NovaProcessorHandle(name, handler);
        processors.computeIfAbsent(name, k -> new ArrayList<>()).add(handle);
        return handle;
    }

    /**
     * 重新注册已有的句柄。
     */
    public static void register(String name, NovaProcessorHandle handle) {
        processors.computeIfAbsent(name, k -> new ArrayList<>()).add(handle);
    }

    /**
     * 注销指定句柄。
     */
    public static void unregister(String name, NovaProcessorHandle handle) {
        List<NovaProcessorHandle> list = processors.get(name);
        if (list != null) list.remove(handle);
    }

    /**
     * 触发指定注解的所有已注册处理器。
     * 将 Class<?> 包装为 NovaClassInfo 传给处理器。
     *
     * @param annotationName 注解名称
     * @param targetClass    被注解的类
     * @param args           注解参数
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void trigger(String annotationName, Class<?> targetClass, Map<String, Object> args) {
        List<NovaProcessorHandle> handles = processors.get(annotationName);
        if (handles != null) {
            NovaClassInfo classInfo = NovaClassInfo.fromJavaClass(targetClass);
            for (NovaProcessorHandle h : handles) {
                h.getHandler().accept(classInfo, args);
            }
        }
    }

    /**
     * 清空所有已注册的处理器（测试用）。
     */
    public static void clear() {
        processors.clear();
    }
}
