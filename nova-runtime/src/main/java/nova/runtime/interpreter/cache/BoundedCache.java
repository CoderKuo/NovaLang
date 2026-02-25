package nova.runtime.interpreter.cache;

/**
 * 有界缓存接口
 *
 * <p>统一的缓存抽象，支持多种实现（Caffeine、LinkedHashMap 等）</p>
 */
public interface BoundedCache<K, V> {

    /**
     * 获取缓存值
     *
     * @return 缓存值，不存在则返回 null
     */
    V get(K key);

    /**
     * 放入缓存
     *
     * @return 之前关联的值，不存在则返回 null
     */
    V put(K key, V value);

    /**
     * 如果不存在则计算并缓存
     *
     * @param mappingFunction 计算函数
     * @return 缓存值（可能是新计算的）
     */
    V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction);

    /**
     * 获取缓存大小
     */
    long size();

    /**
     * 清空缓存
     */
    void clear();

    /**
     * 获取缓存统计
     */
    CacheStats getStats();
}
