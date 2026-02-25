package nova.runtime;

/**
 * 三参数函数接口
 *
 * @param <T1> 第一个参数类型
 * @param <T2> 第二个参数类型
 * @param <T3> 第三个参数类型
 * @param <R>  返回类型
 */
@FunctionalInterface
public interface Function3<T1, T2, T3, R> {
    R invoke(T1 arg1, T2 arg2, T3 arg3);
}
