package nova.runtime;

/**
 * 扩展方法接口
 *
 * <p>用于从 Java 注册扩展方法到 Nova 运行时。</p>
 *
 * @param <T> 接收者类型
 * @param <R> 返回类型
 */
@FunctionalInterface
public interface ExtensionMethod<T, R> {

    /**
     * 调用扩展方法
     *
     * @param receiver 接收者对象
     * @param args     额外参数
     * @return 返回值
     * @throws Exception 如果执行出错
     */
    R invoke(T receiver, Object[] args) throws Exception;
}
