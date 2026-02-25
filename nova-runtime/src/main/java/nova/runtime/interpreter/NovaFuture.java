package nova.runtime.interpreter;
import nova.runtime.*;

import nova.runtime.NovaMember;
import nova.runtime.NovaType;

import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步计算包装。
 *
 * <p>{@code async { body }} 立即提交到 ForkJoinPool 并发执行。
 * 使用子 Interpreter 保证线程安全（与主线程不共享可变执行状态）。
 * {@code await} 或 {@code .get()} 阻塞等待结果。</p>
 */
@NovaType(name = "Future", description = "异步计算类型，由 async { } 创建。使用 await 或 .get() 获取结果")
public final class NovaFuture extends NovaValue {

    /** 活跃异步任务计数器（全局共享） */
    private static final AtomicInteger activeTaskCount = new AtomicInteger(0);

    private final CompletableFuture<NovaValue> future;

    /**
     * 通用构造器：接受 NovaCallable（支持 HirLambdaValue 等非 AST lambda）。
     * 根据 parentInterpreter 类型自动创建对应的子解释器。
     */
    public NovaFuture(NovaCallable callable, Interpreter parentInterpreter) {
        int maxTasks = parentInterpreter.getSecurityPolicy().getMaxAsyncTasks();
        if (maxTasks > 0) {
            int current = activeTaskCount.incrementAndGet();
            if (current > maxTasks) {
                activeTaskCount.decrementAndGet();
                throw new NovaRuntimeException("Security policy denied: max async tasks exceeded (" + maxTasks + ")");
            }
        } else {
            activeTaskCount.incrementAndGet();
        }
        this.future = CompletableFuture.supplyAsync(() -> {
            try {
                Interpreter child = new Interpreter(parentInterpreter);
                return callable.call(child, Collections.emptyList());
            } finally {
                activeTaskCount.decrementAndGet();
            }
        });
    }

    /**
     * 阻塞等待异步结果。
     */
    @NovaMember(description = "阻塞等待异步结果")
    public NovaValue get(Interpreter interpreter) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NovaRuntimeException) {
                throw (NovaRuntimeException) cause;
            }
            throw new NovaRuntimeException("async failed: " + cause.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovaRuntimeException("async interrupted");
        }
    }

    /**
     * 带超时的阻塞等待。
     */
    @NovaMember(description = "带超时的阻塞等待（毫秒）")
    public NovaValue getWithTimeout(Interpreter interpreter, long timeoutMs) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new NovaRuntimeException("async timeout after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NovaRuntimeException) {
                throw (NovaRuntimeException) cause;
            }
            throw new NovaRuntimeException("async failed: " + cause.getMessage());
        } catch (CancellationException e) {
            throw new NovaRuntimeException("async was cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovaRuntimeException("async interrupted");
        }
    }

    /**
     * 取消异步计算。
     */
    @NovaMember(description = "取消异步计算")
    public boolean cancel() {
        return future.cancel(true);
    }

    @NovaMember(description = "异步计算是否已取消", returnType = "Boolean", property = true)
    public boolean isCancelled() {
        return future.isCancelled();
    }

    @NovaMember(description = "异步计算是否已完成", returnType = "Boolean", property = true)
    public boolean isDone() {
        return future.isDone();
    }

    @Override
    public String getTypeName() {
        return "Future";
    }

    @Override
    public Object toJavaValue() {
        return future;
    }

    @Override
    public String toString() {
        if (future.isDone()) {
            try {
                return "<future: " + future.get() + ">";
            } catch (Exception e) {
                return "<future: error>";
            }
        }
        return "<future: pending>";
    }
}
