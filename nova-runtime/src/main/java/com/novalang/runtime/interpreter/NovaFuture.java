package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;

import com.novalang.runtime.NovaMember;
import com.novalang.runtime.NovaType;

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
public final class NovaFuture extends AbstractNovaValue {

    /** 活跃异步任务计数器（全局共享） */
    private static final AtomicInteger activeTaskCount = new AtomicInteger(0);

    private final CompletableFuture<NovaValue> future;

    /**
     * 通用构造器：接受 NovaCallable（支持 HirLambdaValue 等非 AST lambda）。
     * 根据 parentInterpreter 类型自动创建对应的子解释器。
     */
    public NovaFuture(com.novalang.runtime.NovaCallable callable, Interpreter parentInterpreter) {
        int maxTasks = parentInterpreter.getSecurityPolicy().getMaxAsyncTasks();
        if (maxTasks > 0) {
            int current = activeTaskCount.incrementAndGet();
            if (current > maxTasks) {
                activeTaskCount.decrementAndGet();
                throw new NovaRuntimeException(NovaException.ErrorKind.ACCESS_DENIED, "安全策略拒绝: 异步任务数超过上限 (" + maxTasks + ")", null);
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
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "异步执行失败: " + cause.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "异步执行被中断", null);
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
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "异步执行超时 (" + timeoutMs + "ms)", null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof NovaRuntimeException) {
                throw (NovaRuntimeException) cause;
            }
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "异步执行失败: " + cause.getMessage(), null);
        } catch (CancellationException e) {
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "异步执行已取消", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NovaRuntimeException(NovaException.ErrorKind.INTERNAL, "异步执行被中断", null);
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
