package nova.runtime;

import java.util.function.Supplier;

/**
 * 无参数函数接口。
 * 兼容 Java 的 Supplier 和 Runnable。
 *
 * @param <R> 返回类型
 */
@FunctionalInterface
public interface Function0<R> extends Supplier<R>, Runnable {
    R invoke();

    @Override
    default R get() {
        return invoke();
    }

    @Override
    default void run() {
        invoke();
    }
}
