package nova.runtime;

/**
 * 带两个额外参数的扩展方法接口
 *
 * @param <T>  接收者类型
 * @param <A1> 第一个参数类型
 * @param <A2> 第二个参数类型
 * @param <R>  返回类型
 */
@FunctionalInterface
public interface Extension2<T, A1, A2, R> {
    R invoke(T receiver, A1 arg1, A2 arg2);
}
