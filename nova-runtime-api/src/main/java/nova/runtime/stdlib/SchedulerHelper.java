package nova.runtime.stdlib;

import nova.runtime.NovaScheduler;
import nova.runtime.NovaTask;
import nova.runtime.SchedulerHolder;

import java.lang.reflect.Method;
import java.util.concurrent.*;

/**
 * 编译路径的调度器辅助类。
 *
 * <p>编译器将 {@code schedule(ms) { block }} 编译为
 * {@code INVOKESTATIC SchedulerHelper.schedule([Object])Object}。</p>
 */
public final class SchedulerHelper {

    private SchedulerHelper() {}

    private static final ConcurrentHashMap<Class<?>, Method> invokeCache = new ConcurrentHashMap<>();

    /**
     * schedule(delayMs, block) — 延迟调度，返回 NovaTask。
     * vararg 入口：args[0]=delayMs, args[1]=block
     */
    public static Object schedule(Object[] args) {
        if (args.length != 2) {
            throw new RuntimeException("schedule expects 2 arguments (delayMs, block), got " + args.length);
        }
        NovaScheduler sched = SchedulerHolder.get();
        if (sched == null) {
            throw new RuntimeException("No scheduler configured. Use Nova.setScheduler() first.");
        }
        long delayMs = ((Number) args[0]).longValue();
        Object block = args[1];
        NovaScheduler.Cancellable handle = sched.scheduleLater(delayMs, () -> invoke0(block));
        return new NovaTask(handle);
    }

    /**
     * scheduleRepeat(delayMs, periodMs, block) — 重复调度，返回 NovaTask。
     * vararg 入口：args[0]=delayMs, args[1]=periodMs, args[2]=block
     */
    public static Object scheduleRepeat(Object[] args) {
        if (args.length != 3) {
            throw new RuntimeException("scheduleRepeat expects 3 arguments (delayMs, periodMs, block), got " + args.length);
        }
        NovaScheduler sched = SchedulerHolder.get();
        if (sched == null) {
            throw new RuntimeException("No scheduler configured. Use Nova.setScheduler() first.");
        }
        long delayMs = ((Number) args[0]).longValue();
        long periodMs = ((Number) args[1]).longValue();
        Object block = args[2];
        NovaScheduler.Cancellable handle = sched.scheduleRepeat(delayMs, periodMs, () -> invoke0(block));
        return new NovaTask(handle);
    }

    /**
     * delay(millis) — 主线程安全检查 + Thread.sleep。
     * vararg 入口：args[0]=millis
     */
    public static Object delay(Object[] args) {
        if (args.length != 1) {
            throw new RuntimeException("delay expects 1 argument (millis), got " + args.length);
        }
        long millis = ((Number) args[0]).longValue();
        NovaScheduler sched = SchedulerHolder.get();
        if (sched != null && sched.isMainThread()) {
            throw new RuntimeException("Cannot call delay() on the main thread. Use schedule(ms) { } instead.");
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private static final ExecutorService FALLBACK_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nova-scope");
        t.setDaemon(true);
        return t;
    });

    /** 获取异步执行器：优先宿主异步调度器，回退内置线程池 */
    private static Executor getAsyncExecutor() {
        NovaScheduler sched = SchedulerHolder.get();
        if (sched != null) {
            Executor async = sched.asyncExecutor();
            if (async != null) return async;
        }
        return FALLBACK_POOL;
    }

    /**
     * scope { block } — 在异步线程执行 block，阻塞调用者直到完成。
     * 优先使用宿主异步调度器（如 Bukkit 异步调度器），无则回退内置线程池。
     * vararg 入口：args[0]=block
     */
    public static Object scope(Object[] args) {
        if (args.length != 1) {
            throw new RuntimeException("scope expects 1 argument (block), got " + args.length);
        }
        NovaScheduler sched = SchedulerHolder.get();
        if (sched != null && sched.isMainThread()) {
            throw new RuntimeException("Cannot call scope() on the main thread (would block and cause deadlock). Use launch { } instead.");
        }
        Object block = args[0];
        CompletableFuture<Object> future = new CompletableFuture<>();
        getAsyncExecutor().execute(() -> {
            try {
                future.complete(invokeAndReturn(block));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("scope interrupted");
        }
    }

    /**
     * sync { block } — 提交到调度器主线程执行并等待结果返回。
     * vararg 入口：args[0]=block
     */
    public static Object sync(Object[] args) {
        if (args.length != 1) {
            throw new RuntimeException("sync expects 1 argument (block), got " + args.length);
        }
        NovaScheduler sched = SchedulerHolder.get();
        if (sched == null) {
            throw new RuntimeException("No scheduler configured. Use Nova.setScheduler() first.");
        }
        Object block = args[0];
        // 已在主线程则直接执行
        if (sched.isMainThread()) {
            return invokeAndReturn(block);
        }
        CompletableFuture<Object> future = new CompletableFuture<>();
        sched.mainExecutor().execute(() -> {
            try {
                future.complete(invokeAndReturn(block));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("sync interrupted");
        }
    }

    /** 无参调用 lambda 并返回结果 */
    private static Object invokeAndReturn(Object lambda) {
        try {
            Method m = findInvokeMethod(lambda);
            return m.invoke(lambda);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    /** 无参调用 lambda: block.invoke() */
    private static void invoke0(Object lambda) {
        try {
            Method m = findInvokeMethod(lambda);
            m.invoke(lambda);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    private static Method findInvokeMethod(Object lambda) {
        return invokeCache.computeIfAbsent(lambda.getClass(), cls -> {
            for (Method m : cls.getMethods()) {
                if ("invoke".equals(m.getName()) && m.getParameterCount() == 0) return m;
            }
            for (Method m : cls.getMethods()) {
                if ("invoke".equals(m.getName())) return m;
            }
            throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
        });
    }
}
