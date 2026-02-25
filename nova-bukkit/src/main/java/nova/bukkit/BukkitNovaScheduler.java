package nova.bukkit;

import nova.runtime.NovaScheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.Executor;

/**
 * Bukkit/Spigot 环境下的 {@link NovaScheduler} 实现。
 *
 * <p>通过 {@link BukkitSchedulers#register(JavaPlugin)} 一行注册即可使用。</p>
 */
public final class BukkitNovaScheduler implements NovaScheduler {

    private final JavaPlugin plugin;
    private final Executor mainExec;
    private final Executor asyncExec;

    BukkitNovaScheduler(JavaPlugin plugin) {
        this.plugin = plugin;
        this.mainExec = task -> Bukkit.getScheduler().runTask(plugin, task);
        this.asyncExec = task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    @Override
    public Executor mainExecutor() {
        return mainExec;
    }

    @Override
    public Executor asyncExecutor() {
        return asyncExec;
    }

    @Override
    public boolean isMainThread() {
        return Bukkit.isPrimaryThread();
    }

    @Override
    public Cancellable scheduleLater(long delayMs, Runnable task) {
        long ticks = Math.max(1, delayMs / 50);
        BukkitTask bt = Bukkit.getScheduler().runTaskLater(plugin, task, ticks);
        return new BukkitCancellable(bt);
    }

    @Override
    public Cancellable scheduleRepeat(long delayMs, long periodMs, Runnable task) {
        long delayTicks = Math.max(1, delayMs / 50);
        long periodTicks = Math.max(1, periodMs / 50);
        BukkitTask bt = Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        return new BukkitCancellable(bt);
    }

    private static final class BukkitCancellable implements Cancellable {
        private final BukkitTask task;

        BukkitCancellable(BukkitTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }
}
