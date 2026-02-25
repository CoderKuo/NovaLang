package nova.runtime;

/**
 * 标记接口：arity-0 lambda（隐式 it 参数）包装为 Function1 时使用。
 * <p>
 * 用于 MapExtensions 等需要区分 arity-0 和 arity-1 的扩展方法，
 * 以便为不同 arity 的 lambda 提供不同的参数（如 key/value vs entry）。
 */
@FunctionalInterface
public interface ImplicitItFunction<T1, R> extends Function1<T1, R> {
}
