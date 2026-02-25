package nova.runtime;

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 单参数函数接口。
 * 兼容 Java 的 Function、Consumer 和 Predicate。
 *
 * @param <T1> 参数类型
 * @param <R>  返回类型
 */
@FunctionalInterface
@SuppressWarnings("overloads")
public interface Function1<T1, R> extends Function<T1, R>, Consumer<T1>, Predicate<T1> {
    R invoke(T1 arg1);

    @Override
    default R apply(T1 arg1) {
        return invoke(arg1);
    }

    @Override
    default void accept(T1 arg1) {
        invoke(arg1);
    }

    @Override
    default boolean test(T1 arg1) {
        Object result = invoke(arg1);
        return result instanceof Boolean && (Boolean) result;
    }
}
