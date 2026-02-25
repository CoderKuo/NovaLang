package nova.runtime.interpreter;

/**
 * 桥接回调接口，用于 ASM 生成的 Java 子类回调到 Nova 方法
 */
public interface NovaBridgeCallback {
    Object invoke(String methodName, Object[] args);
}
