package nova.runtime;

/**
 * 调度任务句柄，包装 {@link NovaScheduler.Cancellable}。
 *
 * <p>由 {@code schedule()} / {@code scheduleRepeat()} 返回，
 * 提供 {@code cancel()} 和 {@code isCancelled} 成员。</p>
 */
public final class NovaTask extends AbstractNovaValue {

    private final NovaScheduler.Cancellable handle;

    public NovaTask(NovaScheduler.Cancellable handle) {
        this.handle = handle;
    }

    public void cancel() {
        handle.cancel();
    }

    public boolean isCancelled() {
        return handle.isCancelled();
    }

    @Override
    public String getTypeName() {
        return "Task";
    }

    @Override
    public Object toJavaValue() {
        return handle;
    }

    @Override
    public String toString() {
        return handle.isCancelled() ? "<task: cancelled>" : "<task: active>";
    }
}
