package nova.runtime.interpreter.cache;

/**
 * 缓存统计信息
 */
public final class CacheStats {
    private final long hitCount;
    private final long missCount;
    private final long loadSuccessCount;
    private final long loadFailureCount;
    private final long evictionCount;
    private final double hitRate;
    private final long estimatedSize;
    private final long maximumSize;

    public CacheStats(long hitCount, long missCount, long loadSuccessCount, long loadFailureCount,
                      long evictionCount, double hitRate, long estimatedSize, long maximumSize) {
        this.hitCount = hitCount;
        this.missCount = missCount;
        this.loadSuccessCount = loadSuccessCount;
        this.loadFailureCount = loadFailureCount;
        this.evictionCount = evictionCount;
        this.hitRate = hitRate;
        this.estimatedSize = estimatedSize;
        this.maximumSize = maximumSize;
    }

    public long getHitCount() { return hitCount; }
    public long getMissCount() { return missCount; }
    public long getLoadSuccessCount() { return loadSuccessCount; }
    public long getLoadFailureCount() { return loadFailureCount; }
    public long getEvictionCount() { return evictionCount; }
    public double getHitRate() { return hitRate; }
    public long getEstimatedSize() { return estimatedSize; }
    public long getMaximumSize() { return maximumSize; }

    @Override
    public String toString() {
        return String.format("hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, size=%d/%d",
                hitCount, missCount, hitRate * 100, evictionCount, estimatedSize, maximumSize);
    }
}
