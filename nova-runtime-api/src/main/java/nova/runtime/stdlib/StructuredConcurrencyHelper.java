package nova.runtime.stdlib;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.*;

import nova.runtime.NovaType;

/**
 * 编译路径的结构化并发辅助类。
 *
 * <p>编译器将 {@code coroutineScope { s -> ... }} 编译为
 * {@code INVOKESTATIC StructuredConcurrencyHelper.coroutineScope(lambda)}。</p>
 */
public final class StructuredConcurrencyHelper {

    private StructuredConcurrencyHelper() {}

    private static final ConcurrentHashMap<Class<?>, Method> invokeCache = new ConcurrentHashMap<>();

    // ============ Dispatchers 常量（编译路径） ============

    /** 编译路径的 Dispatchers 对象，通过 NovaDynamic.getMember 访问 IO/Default/Unconfined 字段 */
    public static final CompileDispatchers DISPATCHERS = new CompileDispatchers();

    public static final class CompileDispatchers {
        public final Executor IO = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "nova-io");
            t.setDaemon(true);
            return t;
        });
        public final Executor Default = ForkJoinPool.commonPool();
        public final Executor Unconfined = (Executor) Runnable::run;
        /** 宿主主线程执行器，由 Interpreter.setScheduler() 动态注入 */
        public volatile Executor Main;

        /** 重置可变状态（用于测试清理） */
        public void reset() {
            Main = null;
        }
    }

    /** 重置全局调度器状态（用于测试清理或多实例场景） */
    public static void resetGlobalState() {
        DISPATCHERS.reset();
    }

    // ============ 顶层函数 ============

    /** vararg 入口：coroutineScope(block) 或 coroutineScope(dispatcher, block) */
    public static Object coroutineScopeVararg(Object[] args) {
        if (args.length == 1) return coroutineScope(args[0]);
        if (args.length == 2) return coroutineScopeWithDispatcher(args[0], args[1]);
        throw new RuntimeException("coroutineScope expects 1 or 2 arguments, got " + args.length);
    }

    /** vararg 入口：supervisorScope(block) 或 supervisorScope(dispatcher, block) */
    public static Object supervisorScopeVararg(Object[] args) {
        if (args.length == 1) return supervisorScope(args[0]);
        if (args.length == 2) return supervisorScopeWithDispatcher(args[0], args[1]);
        throw new RuntimeException("supervisorScope expects 1 or 2 arguments, got " + args.length);
    }

    public static Object coroutineScope(Object block) {
        return runScope(block, false, ForkJoinPool.commonPool());
    }

    public static Object coroutineScopeWithDispatcher(Object dispatcher, Object block) {
        Executor exec = dispatcher instanceof Executor ? (Executor) dispatcher : ForkJoinPool.commonPool();
        return runScope(block, false, exec);
    }

    /** vararg 入口：withContext(dispatcher, block) — 在指定 executor 上执行 block 并阻塞等待结果 */
    public static Object withContextVararg(Object[] args) {
        if (args.length != 2) throw new RuntimeException("withContext expects 2 arguments (dispatcher, block), got " + args.length);
        Executor exec = args[0] instanceof Executor ? (Executor) args[0] : ForkJoinPool.commonPool();
        Object block = args[1];
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return invoke0(block);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, exec);
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException("withContext failed: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("withContext interrupted");
        }
    }

    public static Object supervisorScope(Object block) {
        return runScope(block, true, ForkJoinPool.commonPool());
    }

    public static Object supervisorScopeWithDispatcher(Object dispatcher, Object block) {
        Executor exec = dispatcher instanceof Executor ? (Executor) dispatcher : ForkJoinPool.commonPool();
        return runScope(block, true, exec);
    }

    private static Object runScope(Object block, boolean supervisor, Executor executor) {
        CompileScope scope = new CompileScope(supervisor, executor);
        Object result = null;
        Throwable blockError = null;
        try {
            result = invokeWith1Arg(block, scope);
        } catch (Exception e) {
            blockError = e;
        }
        // 无论 block 是否抛异常，都等待所有子任务完成
        scope.joinAll();
        if (blockError != null) {
            if (blockError instanceof RuntimeException) throw (RuntimeException) blockError;
            throw new RuntimeException(blockError);
        }
        return result;
    }

    // ============ 编译路径的 Scope ============

    @NovaType(name = "Scope", description = "结构化并发作用域")
    public static final class CompileScope {
        private final boolean supervisor;
        private final Executor executor;
        private final List<CompletableFuture<Object>> children = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled = false;
        private volatile Throwable firstError = null;

        CompileScope(boolean supervisor, Executor executor) {
            this.supervisor = supervisor;
            this.executor = executor;
        }

        /** scope.async { block } */
        public CompileDeferred async(Object block) {
            if (cancelled) throw new RuntimeException("Scope is cancelled");
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    return invoke0(block);
                } catch (Exception e) {
                    recordChildError(e);
                    throw e;
                }
            }, executor);
            children.add(future);
            return new CompileDeferred(future);
        }

        /** scope.launch { block } */
        public CompileJob launch(Object block) {
            if (cancelled) throw new RuntimeException("Scope is cancelled");
            CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
                try {
                    invoke0(block);
                    return null;
                } catch (Exception e) {
                    recordChildError(e);
                    throw e;
                }
            }, executor);
            children.add(future);
            return new CompileJob(future);
        }

        public void cancel() {
            cancelled = true;
            for (CompletableFuture<Object> child : children) {
                try { child.cancel(true); } catch (Exception ignored) {}
            }
        }

        public boolean isActive() { return !cancelled && firstError == null; }
        public boolean isCancelled() { return cancelled; }

        void joinAll() {
            // 有子任务失败时先取消剩余子任务
            if (!supervisor && firstError != null) {
                cancel();
            }
            // 等待所有子任务完成（含已取消的）
            for (CompletableFuture<Object> child : children) {
                try { child.join(); } catch (Exception ignored) {}
            }
            // coroutineScope: 传播首个子任务错误
            if (!supervisor && firstError != null) {
                if (firstError instanceof RuntimeException) throw (RuntimeException) firstError;
                throw new RuntimeException("coroutineScope child failed: " + firstError.getMessage());
            }
        }

        /** 仅记录首个错误，不立即取消（避免从 supplier 内部取消自身 future） */
        private void recordChildError(Exception e) {
            if (supervisor) return;
            if (firstError == null) {
                synchronized (this) {
                    if (firstError == null) { firstError = e; }
                }
            }
        }

        /** typeof 支持 */
        @Override public String toString() { return "Scope"; }
    }

    // ============ 编译路径的 Deferred ============

    @NovaType(name = "Deferred", description = "异步任务结果")
    public static final class CompileDeferred {
        private final CompletableFuture<Object> future;

        CompileDeferred(CompletableFuture<Object> future) { this.future = future; }

        public Object get() {
            try {
                return future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException("Deferred failed: " + cause.getMessage());
            } catch (CancellationException e) {
                throw new RuntimeException("Deferred was cancelled");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Deferred interrupted");
            }
        }

        public boolean cancel() { return future.cancel(true); }
        public Object await() { return get(); }
        public boolean isDone() { return future.isDone(); }
        public boolean isCancelled() { return future.isCancelled(); }

        @Override public String toString() { return "Deferred"; }
    }

    // ============ 编译路径的 Job ============

    @NovaType(name = "Job", description = "后台任务句柄")
    public static final class CompileJob {
        private final CompletableFuture<Object> future;

        public CompileJob(CompletableFuture<Object> future) { this.future = future; }

        public void join() {
            try {
                future.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException("Job failed: " + cause.getMessage());
            } catch (CancellationException e) {
                // Job was cancelled — normal for coroutineScope cancellation
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Job interrupted");
            }
        }

        public boolean cancel() { return future.cancel(true); }
        public boolean isActive() { return !future.isDone(); }
        public boolean isCompleted() { return future.isDone(); }
        public boolean isCancelled() { return future.isCancelled(); }

        @Override public String toString() { return "Job"; }
    }

    // ============ Lambda 调用辅助 ============

    /** 带 1 个参数调用 lambda: block.invoke(arg)；若 lambda 无参数则忽略 arg */
    private static Object invokeWith1Arg(Object lambda, Object arg) {
        try {
            Method m = findInvokeMethod(lambda, 1);
            if (m.getParameterCount() == 0) {
                return m.invoke(lambda);
            }
            return m.invoke(lambda, arg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    /** 无参调用 lambda: block.invoke() */
    private static Object invoke0(Object lambda) {
        try {
            Method m = findInvokeMethod(lambda, 0);
            return m.invoke(lambda);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    private static Method findInvokeMethod(Object lambda, int arity) {
        return invokeCache.computeIfAbsent(lambda.getClass(), cls -> {
            // 先尝试 arity 匹配
            for (Method m : cls.getMethods()) {
                if ("invoke".equals(m.getName()) && m.getParameterCount() == arity) return m;
            }
            // 回退到任意 invoke
            for (Method m : cls.getMethods()) {
                if ("invoke".equals(m.getName())) return m;
            }
            throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
        });
    }
}
