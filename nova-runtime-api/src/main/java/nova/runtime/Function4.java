package nova.runtime;

/**
 * 四参数函数接口
 */
@FunctionalInterface
public interface Function4<T1, T2, T3, T4, R> {
    R invoke(T1 arg1, T2 arg2, T3 arg3, T4 arg4);
}
