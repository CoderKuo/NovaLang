package nova.lang;

/**
 * Nothing 类型 - 底类型（Bottom Type）
 *
 * <p>Nothing 是所有类型的子类型，表示"永远不会正常返回"的情况。</p>
 *
 * <p>使用场景：</p>
 * <ul>
 *   <li>throw 表达式的类型</li>
 *   <li>永不返回的函数（如 error()、fail()）</li>
 *   <li>空集合的类型 List&lt;Nothing&gt;</li>
 *   <li>Result&lt;T, Nothing&gt; 表示永不失败</li>
 * </ul>
 *
 * <pre>
 * fun fail(msg: String): Nothing {
 *     throw RuntimeException(msg)
 * }
 *
 * val x: Int = if (valid) 42 else throw Error("invalid")
 * // throw 的类型是 Nothing，它是 Int 的子类型
 * </pre>
 *
 * <p>此类没有公共构造器，不能被实例化。</p>
 */
public final class Nothing {

    private Nothing() {
        throw new AssertionError("Nothing cannot be instantiated");
    }

    /**
     * 抛出异常并返回 Nothing（用于类型系统）
     *
     * @param message 错误消息
     * @return 永不返回
     * @throws RuntimeException 总是抛出
     */
    public static Nothing error(String message) {
        throw new RuntimeException(message);
    }

    /**
     * 抛出异常并返回 Nothing（用于类型系统）
     *
     * @param cause 异常原因
     * @return 永不返回
     * @throws RuntimeException 总是抛出
     */
    public static Nothing error(Throwable cause) {
        throw new RuntimeException(cause);
    }

    /**
     * 抛出 NotImplementedError
     *
     * @param reason 原因说明
     * @return 永不返回
     * @throws NotImplementedError 总是抛出
     */
    public static Nothing todo(String reason) {
        throw new NotImplementedError(reason);
    }

    /**
     * 抛出 NotImplementedError（默认消息）
     *
     * @return 永不返回
     * @throws NotImplementedError 总是抛出
     */
    public static Nothing todo() {
        throw new NotImplementedError("Not implemented");
    }
}
