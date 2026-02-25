package nova.runtime;

/**
 * 无额外参数的扩展方法接口
 *
 * @param <T> 接收者类型
 * @param <R> 返回类型
 */
@FunctionalInterface
public interface Extension0<T, R> {
    R invoke(T receiver);
}
