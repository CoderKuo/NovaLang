package nova.runtime.host;

@FunctionalInterface
public interface HostFunctionInvoker {
    Object invoke(Object... args) throws Exception;
}
