package com.novalang.runtime.interpreter;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaValue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 结构化并发的任务句柄。
 *
 * <p>由 {@code launch { }} 创建，不携带结果值。
 * 提供 {@code join()} 等待完成、{@code cancel()} 取消任务。</p>
 */
public final class NovaJob extends AbstractNovaValue {

    private final CompletableFuture<NovaValue> future;

    public NovaJob(CompletableFuture<NovaValue> future) {
        this.future = future;
    }

    /** 阻塞等待任务完成（不返回结果） */
    public void join() {
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "Job 执行失败: " + cause.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "Job 被中断", null);
        }
    }

    /** 取消任务 */
    public boolean cancel() {
        return future.cancel(true);
    }

    /** 任务是否仍在运行 */
    public boolean isActive() {
        return !future.isDone();
    }

    /** 任务是否已完成（包括正常完成、异常、取消） */
    public boolean isCompleted() {
        return future.isDone();
    }

    /** 任务是否已取消 */
    public boolean isCancelled() {
        return future.isCancelled();
    }

    CompletableFuture<NovaValue> getFuture() {
        return future;
    }

    @Override
    public String getTypeName() {
        return "Job";
    }

    @Override
    public Object toJavaValue() {
        return future;
    }

    @Override
    public String toString() {
        if (future.isDone()) {
            return future.isCancelled() ? "<job: cancelled>" : "<job: completed>";
        }
        return "<job: active>";
    }
}
