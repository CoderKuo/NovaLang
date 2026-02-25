package nova.lang;

/**
 * NotImplementedError - 表示功能尚未实现的错误
 *
 * <p>用于标记代码中尚未完成的部分。</p>
 *
 * <pre>
 * fun complexAlgorithm(): Result {
 *     todo("需要实现复杂算法")
 * }
 * </pre>
 */
public class NotImplementedError extends Error {

    private static final long serialVersionUID = 1L;

    /**
     * 创建一个带有默认消息的 NotImplementedError
     */
    public NotImplementedError() {
        super("An operation is not implemented.");
    }

    /**
     * 创建一个带有指定消息的 NotImplementedError
     *
     * @param message 错误消息
     */
    public NotImplementedError(String message) {
        super(message);
    }

    /**
     * 创建一个带有指定消息和原因的 NotImplementedError
     *
     * @param message 错误消息
     * @param cause   原因
     */
    public NotImplementedError(String message, Throwable cause) {
        super(message, cause);
    }
}
