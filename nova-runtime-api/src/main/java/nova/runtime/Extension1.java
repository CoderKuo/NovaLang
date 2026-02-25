package nova.runtime;

/**
 * 带一个额外参数的扩展方法接口
 *
 * @param <T>  接收者类型
 * @param <A1> 参数类型
 * @param <R>  返回类型
 */
@FunctionalInterface
public interface Extension1<T, A1, R> {
    R invoke(T receiver, A1 arg1);
}
