package com.novalang.runtime.interpreter;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaValue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 结构化并发的异步结果。
 *
 * <p>由 scope 内的 {@code async { }} 创建，携带结果值。
 * 使用 {@code await()} 获取结果。兼容旧 NovaFuture 的 {@code get()} 方法。</p>
 */
public final class NovaDeferred extends AbstractNovaValue {

    private final CompletableFuture<NovaValue> future;

    public NovaDeferred(CompletableFuture<NovaValue> future) {
        this.future = future;
    }

    /** 阻塞等待异步结果 */
    public NovaValue await(Interpreter interp) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NovaRuntimeException) throw (NovaRuntimeException) cause;
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "Deferred 执行失败: " + cause.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "Deferred 被中断", null);
        }
    }

    /** 兼容 NovaFuture API */
    public NovaValue get(Interpreter interp) {
        return await(interp);
    }

    /** 取消任务 */
    public boolean cancel() {
        return future.cancel(true);
    }

    /** 任务是否已完成 */
    public boolean isDone() {
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
        return "Deferred";
    }

    @Override
    public Object toJavaValue() {
        return future;
    }

    @Override
    public String toString() {
        if (future.isDone()) {
            try {
                return "<deferred: " + future.get() + ">";
            } catch (Exception e) {
                return "<deferred: error>";
            }
        }
        return "<deferred: pending>";
    }
}
