package nova.runtime;

import java.util.concurrent.Executor;

/**
 * 可插拔调度器 SPI。
 *
 * <p>宿主环境（如 Bukkit）实现此接口，通过
 * {@code Nova.setScheduler(NovaScheduler)} 注入，
 * 即可让 Nova 的 {@code schedule} / {@code scheduleRepeat} /
 * {@code Dispatchers.Main} 对接到宿主调度器。</p>
 */
public interface NovaScheduler {

    /**
     * 宿主主线程执行器（同步调度器）。
     * 返回 {@code null} 表示宿主无主线程概念。
     */
    Executor mainExecutor();

    /**
     * 宿主异步执行器（异步调度器）。
     * 返回 {@code null} 时 {@code scope { }} 使用内置线程池。
     * <p>Bukkit 实现应返回 {@code task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task)}。</p>
     */
    default Executor asyncExecutor() { return null; }

    /** 当前线程是否为宿主主线程 */
    boolean isMainThread();

    /** 延迟 {@code delayMs} 毫秒后在主线程执行 task */
    Cancellable scheduleLater(long delayMs, Runnable task);

    /** 延迟 {@code delayMs} 后，每隔 {@code periodMs} 毫秒在主线程重复执行 task */
    Cancellable scheduleRepeat(long delayMs, long periodMs, Runnable task);

    /** 可取消的调度句柄 */
    interface Cancellable {
        void cancel();
        boolean isCancelled();
    }
}
