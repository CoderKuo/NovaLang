package nova.runtime;

import java.util.Iterator;

/**
 * Nova 容器类型接口
 *
 * <p>所有容器类型（List, Map, Array, Range）都实现此接口，
 * 提供统一的容器操作契约。</p>
 */
public interface NovaContainer extends NovaValue, Iterable<NovaValue> {

    /**
     * 容器大小
     *
     * @return 元素数量
     */
    int size();

    /**
     * 是否为空
     *
     * @return 如果容器为空返回 true
     */
    boolean isEmpty();

    // ============ NovaValue 默认实现覆写 ============

    @Override
    default boolean isTruthy() {
        return !isEmpty();
    }

    // ============ Iterable 默认实现 ============

    @Override
    default Iterator<NovaValue> iterator() {
        // 子类应覆写此方法提供高效实现
        return java.util.Collections.emptyIterator();
    }
}
