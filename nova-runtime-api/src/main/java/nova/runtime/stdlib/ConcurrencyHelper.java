package nova.runtime.stdlib;

import nova.runtime.NovaScheduler;
import nova.runtime.SchedulerHolder;

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

    private static final ConcurrentHashMap<Class<?>, Method> invokeCache = new ConcurrentHashMap<>();

    /**
     * launch(block) — fire-and-forget 异步执行，返回 CompileJob。
     * vararg 入口：args[0]=block
     */
    public static Object launch(Object[] args) {
        if (args.length != 1) {
            throw new RuntimeException("launch expects 1 argument (block), got " + args.length);
        }
        Object block = args[0];
        Executor exec = getAsyncExecutor();
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return invoke0(block);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, exec);
        return new StructuredConcurrencyHelper.CompileJob(future);
    }

    /**
     * parallel(tasks...) — 并行执行多个 lambda，返回结果列表。
     * vararg 入口：args[0..n]=blocks
     */
    public static Object parallel(Object[] args) {
        if (args.length == 0) {
            return new Object[0];
        }
        List<CompletableFuture<Object>> futures = new ArrayList<>(args.length);
        Executor exec = getAsyncExecutor();
        for (Object block : args) {
            futures.add(CompletableFuture.supplyAsync(() -> invoke0(block), exec));
        }
        Object[] results = new Object[args.length];
        for (int i = 0; i < futures.size(); i++) {
            try {
                results[i] = futures.get(i).get();
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
     * withTimeout(millis, block) — 带超时执行 block。
     * vararg 入口：args[0]=millis, args[1]=block
     */
    public static Object withTimeout(Object[] args) {
        if (args.length != 2) {
            throw new RuntimeException("withTimeout expects 2 arguments (millis, block), got " + args.length);
        }
        long timeout = ((Number) args[0]).longValue();
        Object block = args[1];
        Executor exec = getAsyncExecutor();
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> invoke0(block), exec);
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

    /** 无参调用 lambda 并返回结果 */
    private static Object invoke0(Object lambda) {
        try {
            Method m = invokeCache.computeIfAbsent(lambda.getClass(), cls -> {
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) return method;
                }
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName())) return method;
                }
                throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
            });
            return m.invoke(lambda);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
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
