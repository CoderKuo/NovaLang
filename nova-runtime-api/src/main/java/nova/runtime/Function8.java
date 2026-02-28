package nova.runtime;

/**
 * 八参数函数接口
 */
@FunctionalInterface
public interface Function8<T1, T2, T3, T4, T5, T6, T7, T8, R> {
    R invoke(T1 arg1, T2 arg2, T3 arg3, T4 arg4, T5 arg5, T6 arg6, T7 arg7, T8 arg8);
}
