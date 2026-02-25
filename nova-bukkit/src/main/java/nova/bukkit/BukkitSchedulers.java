package nova.bukkit;

import nova.runtime.NovaScheduler;
import nova.runtime.SchedulerHolder;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit 调度器注册入口。
 *
 * <pre>{@code
 * // 在插件 onEnable() 中一行注册
 * BukkitSchedulers.register(this);
 * }</pre>
 */
public final class BukkitSchedulers {

    private BukkitSchedulers() {}

    /**
     * 创建并全局注册 Bukkit 调度器。
     *
     * @param plugin Bukkit 插件实例
     * @return 已注册的调度器
     */
    public static NovaScheduler register(JavaPlugin plugin) {
        NovaScheduler scheduler = create(plugin);
        SchedulerHolder.set(scheduler);
        return scheduler;
    }

    /**
     * 仅创建调度器实例，不全局注册。
     *
     * @param plugin Bukkit 插件实例
     * @return 调度器实例
     */
    public static NovaScheduler create(JavaPlugin plugin) {
        return new BukkitNovaScheduler(plugin);
    }
}
