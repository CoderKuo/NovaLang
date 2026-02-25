package nova.runtime;

import java.util.function.BiConsumer;

/**
 * 注解处理器句柄，支持动态注册/注销/替换。
 * 编译模式下由 {@link NovaAnnotations#register} 返回，
 * 解释器模式下作为 Builtins 中 NovaMap 句柄的内部支撑。
 */
@SuppressWarnings("rawtypes")
public class NovaProcessorHandle {

    private final String name;
    private BiConsumer handler;

    public NovaProcessorHandle(String name, BiConsumer handler) {
        this.name = name;
        this.handler = handler;
    }

    public String getName() {
        return name;
    }

    public BiConsumer getHandler() {
        return handler;
    }

    public void unregister() {
        NovaAnnotations.unregister(name, this);
    }

    public void register() {
        NovaAnnotations.register(name, this);
    }

    public void replace(BiConsumer newHandler) {
        this.handler = newHandler;
    }
}
