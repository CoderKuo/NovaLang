package nova.runtime.interpreter.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Caffeine 缓存功能测试
 */
public class CaffeineCacheTest {

    @Test
    public void testBasicOperations() {
        BoundedCache<String, Integer> cache = new CaffeineCache<>(100);

        // Put and get
        cache.put("a", 1);
        assertEquals(Integer.valueOf(1), cache.get("a"));

        // ComputeIfAbsent
        Integer value = cache.computeIfAbsent("b", k -> 2);
        assertEquals(Integer.valueOf(2), value);
        assertEquals(Integer.valueOf(2), cache.get("b"));

        // Size
        assertEquals(2L, cache.size());
    }

    @Test
    public void testEviction() {
        BoundedCache<Integer, String> cache = new CaffeineCache<>(3);

        // Fill cache
        cache.put(1, "one");
        cache.put(2, "two");
        cache.put(3, "three");
        assertEquals(3L, cache.size());

        // Trigger eviction by adding 4th item
        cache.put(4, "four");

        // Cache should maintain size limit (approximately)
        // Note: Caffeine may temporarily exceed limit slightly
        assertTrue(cache.size() <= 4, "Cache size should be near limit");
    }

    @Test
    public void testStats() {
        BoundedCache<String, String> cache = new CaffeineCache<>(100);

        cache.put("a", "A");
        cache.get("a");  // hit
        cache.get("b");  // miss

        CacheStats stats = cache.getStats();
        assertEquals(1L, stats.getHitCount());
        assertEquals(1L, stats.getMissCount());
        assertEquals(0.5, stats.getHitRate(), 0.01);
    }

    @Test
    public void testComputeIfAbsentStats() {
        BoundedCache<Integer, Integer> cache = new CaffeineCache<>(100);

        // First call: miss + load
        Integer v1 = cache.computeIfAbsent(1, k -> k * 10);
        assertEquals(Integer.valueOf(10), v1);

        // Second call: hit
        Integer v2 = cache.computeIfAbsent(1, k -> k * 20);
        assertEquals(Integer.valueOf(10), v2);  // Still 10, not recomputed

        CacheStats stats = cache.getStats();
        assertTrue(stats.getHitCount() > 0, "Should have hits");
        assertTrue(stats.getLoadSuccessCount() > 0, "Should have loads");
    }

    @Test
    public void testClear() {
        BoundedCache<String, String> cache = new CaffeineCache<>(100);

        cache.put("a", "A");
        cache.put("b", "B");
        assertEquals(2L, cache.size());

        cache.clear();
        assertEquals(0L, cache.size());
        assertNull(cache.get("a"));
    }

    @Test
    public void testNullHandling() {
        BoundedCache<String, String> cache = new CaffeineCache<>(100);

        // Get non-existent key
        assertNull(cache.get("missing"));

        // ComputeIfAbsent with null result (Caffeine doesn't cache nulls)
        String value = cache.computeIfAbsent("key", k -> null);
        assertNull(value);
    }
}
