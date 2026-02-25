package nova.runtime;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;

/**
 * 双参数函数接口。
 * 兼容 Java 的 BiFunction 和 BiConsumer。
 *
 * @param <T1> 第一个参数类型
 * @param <T2> 第二个参数类型
 * @param <R>  返回类型
 */
@FunctionalInterface
public interface Function2<T1, T2, R> extends BiFunction<T1, T2, R>, BiConsumer<T1, T2> {
    R invoke(T1 arg1, T2 arg2);

    @Override
    default R apply(T1 arg1, T2 arg2) {
        return invoke(arg1, arg2);
    }

    @Override
    default void accept(T1 arg1, T2 arg2) {
        invoke(arg1, arg2);
    }
}
