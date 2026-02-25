package nova.runtime;

/**
 * 原生函数接口
 *
 * <p>用于从 Java 注册函数到 Nova 运行时。</p>
 *
 * @param <R> 返回类型
 */
@FunctionalInterface
public interface NativeFunction<R> {

    /**
     * 调用函数
     *
     * @param args 参数数组
     * @return 返回值
     * @throws Exception 如果执行出错
     */
    R invoke(Object[] args) throws Exception;
}
