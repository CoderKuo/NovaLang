package nova.runtime.interpreter;

import java.util.concurrent.*;

/**
 * 结构化并发调度器。
 *
 * <ul>
 *   <li>{@code DEFAULT} — ForkJoinPool.commonPool()，适用于 CPU 密集型任务</li>
 *   <li>{@code IO} — CachedThreadPool，适用于阻塞 IO 操作</li>
 *   <li>{@code UNCONFINED} — 直接在调用线程执行，无线程切换</li>
 * </ul>
 */
public final class NovaDispatchers {

    private NovaDispatchers() {}

    /** CPU 密集型任务（默认） */
    public static final ExecutorService DEFAULT = ForkJoinPool.commonPool();

    /** IO 密集型任务（可弹性扩展的守护线程池） */
    public static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        @SuppressWarnings("deprecation")
        long id = Thread.currentThread().getId();
        Thread t = new Thread(r, "nova-io-" + id);
        t.setDaemon(true);
        return t;
    });

    /** 无调度 — 直接在当前线程同步执行 */
    public static final Executor UNCONFINED = Runnable::run;

    /** 关闭 IO 线程池（JVM 退出前清理） */
    public static void shutdown() {
        IO.shutdownNow();
    }
}
