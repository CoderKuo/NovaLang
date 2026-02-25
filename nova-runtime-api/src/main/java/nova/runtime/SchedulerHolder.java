package nova.runtime;

/**
 * 全局调度器持有者。
 * 编译路径和解释器路径都通过此静态持有者获取调度器。
 *
 * <p><b>注意</b>：这是全局静态状态，多 Interpreter 实例会相互覆盖。
 * 在测试或多租户场景中，应在使用后调用 {@link #clear()} 清理。
 */
public final class SchedulerHolder {

    private static volatile NovaScheduler instance;

    private SchedulerHolder() {}

    public static NovaScheduler get() {
        return instance;
    }

    public static void set(NovaScheduler scheduler) {
        instance = scheduler;
    }

    /** 清理全局调度器状态（用于测试清理或多实例场景） */
    public static void clear() {
        instance = null;
    }
}
