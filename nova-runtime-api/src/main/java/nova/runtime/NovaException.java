package nova.runtime;

/**
 * NovaLang 基础运行时异常（无源位置信息）。
 *
 * <p>{@code nova-runtime} 中的 {@code NovaRuntimeException} 继承此类，
 * 并添加 SourceLocation 等诊断信息。</p>
 */
public class NovaException extends RuntimeException {

    public NovaException(String message) {
        super(message);
    }

    public NovaException(String message, Throwable cause) {
        super(message, cause);
    }
}
