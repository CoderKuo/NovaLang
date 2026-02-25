package nova.runtime.interpreter;

import nova.runtime.NovaNull;
import nova.runtime.NovaValue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;

/**
 * 结构化并发作用域。
 *
 * <p>作为 {@code coroutineScope { }} / {@code supervisorScope { }} 的 receiver（this），
 * 提供 {@code async} 和 {@code launch} 方法创建子任务。</p>
 *
 * <h3>行为差异</h3>
 * <ul>
 *   <li><b>coroutineScope</b>：任意子任务失败 → 取消所有兄弟 → 向上抛异常</li>
 *   <li><b>supervisorScope</b>：子任务失败仅影响自身，不传播到兄弟</li>
 * </ul>
 */
public final class NovaScope extends NovaValue {

    private final Interpreter interpreter;
    private final Executor executor;
    private final boolean supervisorMode;

    private final List<CompletableFuture<NovaValue>> children = new CopyOnWriteArrayList<>();
    private volatile boolean cancelled = false;
    private volatile Throwable firstError = null;

    public NovaScope(Interpreter interpreter, Executor executor, boolean supervisorMode) {
        this.interpreter = interpreter;
        this.executor = executor;
        this.supervisorMode = supervisorMode;
    }

    /**
     * 在当前作用域启动异步任务，返回 {@link NovaDeferred}。
     */
    public NovaDeferred async(NovaCallable block) {
        checkActive();
        CompletableFuture<NovaValue> future = CompletableFuture.supplyAsync(() -> {
            try {
                Interpreter child = new Interpreter(interpreter);
                return block.call(child, Collections.emptyList());
            } catch (Exception e) {
                handleChildError(e);
                throw e;
            }
        }, executor);
        children.add(future);
        return new NovaDeferred(future);
    }

    /**
     * 在当前作用域启动无返回值任务，返回 {@link NovaJob}。
     */
    public NovaJob launch(NovaCallable block) {
        checkActive();
        CompletableFuture<NovaValue> future = CompletableFuture.supplyAsync(() -> {
            try {
                Interpreter child = new Interpreter(interpreter);
                block.call(child, Collections.emptyList());
                return NovaNull.UNIT;
            } catch (Exception e) {
                handleChildError(e);
                throw e;
            }
        }, executor);
        children.add(future);
        return new NovaJob(future);
    }

    /**
     * 等待所有子任务完成。coroutineScope 模式下若有子任务失败则抛异常。
     */
    void joinAll() {
        try {
            CompletableFuture.allOf(children.toArray(new CompletableFuture<?>[0])).join();
        } catch (CompletionException e) {
            // allOf 在任意一个失败时就会抛出
        }

        // coroutineScope: 传播第一个错误
        if (!supervisorMode && firstError != null) {
            if (firstError instanceof NovaRuntimeException) {
                throw (NovaRuntimeException) firstError;
            }
            throw new NovaRuntimeException("coroutineScope child failed: " + firstError.getMessage());
        }
    }

    /**
     * 取消所有子任务。
     */
    public void cancel() {
        cancelled = true;
        for (CompletableFuture<NovaValue> child : children) {
            child.cancel(true);
        }
    }

    /** 作用域是否活跃（未取消且无错误） */
    public boolean isActive() {
        return !cancelled && firstError == null;
    }

    /** 作用域是否已取消 */
    public boolean isCancelled() {
        return cancelled;
    }

    /** 是否为 supervisorScope */
    public boolean isSupervisor() {
        return supervisorMode;
    }

    private void checkActive() {
        if (cancelled) {
            throw new NovaRuntimeException("Scope is cancelled");
        }
    }

    private void handleChildError(Exception e) {
        if (supervisorMode) return;
        // coroutineScope: 记录第一个错误并取消所有兄弟
        if (firstError == null) {
            synchronized (this) {
                if (firstError == null) {
                    firstError = e;
                    cancel();
                }
            }
        }
    }

    @Override
    public String getTypeName() {
        return "Scope";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        String mode = supervisorMode ? "supervisor" : "coroutine";
        return "<scope: " + mode + ", children=" + children.size()
                + ", active=" + isActive() + ">";
    }
}
