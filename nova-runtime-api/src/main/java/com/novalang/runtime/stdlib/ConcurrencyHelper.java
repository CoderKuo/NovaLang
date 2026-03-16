package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaScheduler;
import com.novalang.runtime.NovaScriptContext;
import com.novalang.runtime.SchedulerHolder;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/**
 * 编译路径的并发辅助类。
 *
 * <p>编译器将 {@code launch/parallel/withTimeout} 编译为
 * {@code INVOKESTATIC ConcurrencyHelper.xxx([Object])Object}。</p>
 */
public final class ConcurrencyHelper {

    private ConcurrencyHelper() {}

    // invoke 缓存已迁移到 LambdaUtils

    /** 递归守卫：避免 delegateToInterpreter → callFunction → impl → delegateToInterpreter 无限循环 */
    private static final ThreadLocal<Boolean> DELEGATE_GUARD = new ThreadLocal<>();

    /**
     * 统一代理：如果有 Interpreter 上下文且 Builtins 有独立实现，委托给它。
     * 递归守卫防止 callFunction → StdlibRegistry impl → delegateToInterpreter 无限循环。
     */
    static Object delegateToInterpreter(String funcName, Object[] args) {
        if (Boolean.TRUE.equals(DELEGATE_GUARD.get())) return null;
        com.novalang.runtime.ExecutionContext ctx = com.novalang.runtime.NovaRuntime.currentContext();
        if (ctx == null) return null;
        DELEGATE_GUARD.set(Boolean.TRUE);
        try {
            java.util.List<com.novalang.runtime.NovaValue> novaArgs = new java.util.ArrayList<>(args.length);
            for (Object arg : args) {
                novaArgs.add(arg instanceof com.novalang.runtime.NovaValue
                    ? (com.novalang.runtime.NovaValue) arg
                    : com.novalang.runtime.AbstractNovaValue.fromJava(arg));
            }
            com.novalang.runtime.NovaValue result = ctx.callFunction(funcName, novaArgs);
            if (result != null) return result.toJavaValue();
        } finally {
            DELEGATE_GUARD.remove();
        }
        return null;
    }

    /**
     * launch(block) — fire-and-forget 异步执行，返回 CompileJob。
     * vararg 入口：args[0]=block
     */
    public static Object launch(Object[] args) {
        Object delegated = delegateToInterpreter("launch", args);
        if (delegated != null) return delegated;
        if (args.length != 1) {
            throw new RuntimeException("launch expects 1 argument (block), got " + args.length);
        }
        Object block = args[0];
        Executor exec = getAsyncExecutor();
        NovaScriptContext parentCtx = NovaScriptContext.current();
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            NovaScriptContext.setCurrent(parentCtx);
            try {
                return invoke0(block);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, exec);
        // 未 await 的异常输出到日志，避免静默吞掉
        future.exceptionally(ex -> {
            java.util.logging.Logger.getLogger("Nova")
                    .log(java.util.logging.Level.SEVERE, "launch task failed", ex);
            return null;
        });
        return new StructuredConcurrencyHelper.CompileJob(future);
    }

    /**
     * parallel(tasks...) — 并行执行多个 lambda，返回结果列表。
     * vararg 入口：args[0..n]=blocks
     */
    public static Object parallel(Object[] args) {
        Object delegated = delegateToInterpreter("parallel", args);
        if (delegated != null) return delegated;
        if (args.length == 0) {
            return new ArrayList<>();
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>(args.length);
        Executor exec = getAsyncExecutor();
        NovaScriptContext parentCtx = NovaScriptContext.current();
        for (Object block : args) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                NovaScriptContext.setCurrent(parentCtx);
                return invoke0(block);
            }, exec));
        }
        List<Object> results = new ArrayList<>(args.length);
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get());
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException("parallel task failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("parallel interrupted");
            }
        }
        return results;
    }

    /**
     * await target — 统一等待入口，兼容 CompletableFuture / CompileJob。
     */
    @SuppressWarnings("unchecked")
    public static Object awaitJoin(Object target) {
        if (target instanceof CompletableFuture) {
            return ((CompletableFuture<Object>) target).join();
        }
        if (target instanceof StructuredConcurrencyHelper.CompileJob) {
            ((StructuredConcurrencyHelper.CompileJob) target).join();
            return null;
        }
        // 非 Future 类型：直接返回原值（await 42 → 42）
        return target;
    }

    /**
     * withTimeout(millis, block) — 带超时执行 block。
     * vararg 入口：args[0]=millis, args[1]=block
     */
    public static Object withTimeout(Object[] args) {
        Object delegated = delegateToInterpreter("withTimeout", args);
        if (delegated != null) return delegated;
        if (args.length != 2) {
            throw new RuntimeException("withTimeout expects 2 arguments (millis, block), got " + args.length);
        }
        long timeout = ((Number) args[0]).longValue();
        Object block = args[1];
        Executor exec = getAsyncExecutor();
        NovaScriptContext parentCtx = NovaScriptContext.current();
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            NovaScriptContext.setCurrent(parentCtx);
            return invoke0(block);
        }, exec);
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Timeout after " + timeout + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause.getMessage() : e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("withTimeout interrupted");
        }
    }

    /**
     * awaitAll(futures) — 等待所有 Future 完成，返回结果数组。
     * vararg 入口：args[0]=List/Array of futures
     */
    public static Object awaitAll(Object[] args) {
        Object delegated = delegateToInterpreter("awaitAll", args);
        if (delegated != null) return delegated;
        if (args.length != 1) {
            throw new RuntimeException("awaitAll expects 1 argument (list of futures), got " + args.length);
        }
        Object input = args[0];
        Object[] futures;
        if (input instanceof Object[]) {
            futures = (Object[]) input;
        } else if (input instanceof List) {
            futures = ((List<?>) input).toArray();
        } else if (input instanceof Iterable) {
            List<Object> list = new ArrayList<>();
            for (Object o : (Iterable<?>) input) list.add(o);
            futures = list.toArray();
        } else {
            throw new RuntimeException("awaitAll: argument must be a list of futures");
        }
        Object[] results = new Object[futures.length];
        for (int i = 0; i < futures.length; i++) {
            Object f = futures[i];
            if (f instanceof CompletableFuture) {
                try {
                    results[i] = ((CompletableFuture<?>) f).get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new RuntimeException("awaitAll failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("awaitAll interrupted");
                }
            } else if (f instanceof Future) {
                try {
                    results[i] = ((Future<?>) f).get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                    throw new RuntimeException("awaitAll failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("awaitAll interrupted");
                }
            } else {
                results[i] = f;
            }
        }
        return results;
    }

    /**
     * awaitFirst(futures) — 返回第一个完成的 Future 的结果。
     * vararg 入口：args[0]=List/Array of futures
     */
    public static Object awaitFirst(Object[] args) {
        Object delegated = delegateToInterpreter("awaitFirst", args);
        if (delegated != null) return delegated;
        if (args.length != 1) {
            throw new RuntimeException("awaitFirst expects 1 argument (list of futures), got " + args.length);
        }
        Object input = args[0];
        List<CompletableFuture<?>> cfs = new ArrayList<>();
        if (input instanceof Object[]) {
            for (Object o : (Object[]) input) {
                if (o instanceof CompletableFuture) cfs.add((CompletableFuture<?>) o);
            }
        } else if (input instanceof Iterable) {
            for (Object o : (Iterable<?>) input) {
                if (o instanceof CompletableFuture) cfs.add((CompletableFuture<?>) o);
            }
        }
        if (cfs.isEmpty()) return null;
        try {
            CompletableFuture<?> any = CompletableFuture.anyOf(cfs.toArray(new CompletableFuture<?>[0]));
            return any.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException("awaitFirst failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("awaitFirst interrupted");
        }
    }

    /** 无参调用 lambda 并返回结果（委托 LambdaUtils，支持 NovaCallable） */
    private static Object invoke0(Object lambda) {
        return LambdaUtils.invoke0(lambda);
    }

    /** 获取异步执行器：优先宿主异步调度器，回退 ForkJoinPool */
    private static Executor getAsyncExecutor() {
        NovaScheduler sched = SchedulerHolder.get();
        if (sched != null) {
            Executor async = sched.asyncExecutor();
            if (async != null) return async;
        }
        return ForkJoinPool.commonPool();
    }
}
