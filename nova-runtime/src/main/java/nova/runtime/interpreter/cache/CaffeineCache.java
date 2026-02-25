package nova.runtime.interpreter.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * 基于 Caffeine 的高性能缓存实现
 *
 * <p>使用 Window TinyLfu 淘汰算法，兼顾访问频率和新近度。
 * 线程安全，无锁设计，性能优于传统 LRU。</p>
 *
 * <p>相比简单的 LRU，Window TinyLfu 能更好地处理：</p>
 * <ul>
 *   <li>突发访问 - 避免缓存污染</li>
 *   <li>频繁访问 - 保护热点数据</li>
 *   <li>扫描操作 - 不会清空整个缓存</li>
 * </ul>
 *
 * @see <a href="https://github.com/ben-manes/caffeine">Caffeine GitHub</a>
 */
public final class CaffeineCache<K, V> implements BoundedCache<K, V> {

    private final Cache<K, V> cache;
    private final long maximumSize;

    /**
     * 创建 Caffeine 缓存
     *
     * @param maximumSize 最大条目数
     */
    public CaffeineCache(long maximumSize) {
        if (maximumSize <= 0) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        this.maximumSize = maximumSize;
        this.cache = Caffeine.newBuilder()
                .maximumSize(maximumSize)
                .recordStats()  // 启用统计（低开销）
                .build();
    }

    @Override
    public V get(K key) {
        return cache.getIfPresent(key);
    }

    @Override
    public V put(K key, V value) {
        cache.put(key, value);
        return null;  // Caffeine 不返回旧值（性能优化）
    }

    @Override
    public V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> mappingFunction) {
        return cache.get(key, mappingFunction);
    }

    @Override
    public long size() {
        return cache.estimatedSize();
    }

    @Override
    public void clear() {
        cache.invalidateAll();
        cache.cleanUp();  // 立即清理
    }

    @Override
    public CacheStats getStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats caffeineStats = cache.stats();

        long hitCount = caffeineStats.hitCount();
        long missCount = caffeineStats.missCount();
        long total = hitCount + missCount;
        double hitRate = total > 0 ? caffeineStats.hitRate() : 0.0;

        return new CacheStats(
                hitCount,
                missCount,
                caffeineStats.loadSuccessCount(),
                caffeineStats.loadFailureCount(),
                caffeineStats.evictionCount(),
                hitRate,
                cache.estimatedSize(),
                maximumSize
        );
    }

    /**
     * 手动触发清理（通常不需要，Caffeine 会自动异步清理）
     */
    public void cleanUp() {
        cache.cleanUp();
    }
}
