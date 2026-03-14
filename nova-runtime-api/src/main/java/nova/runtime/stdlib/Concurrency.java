package nova.runtime.stdlib;

import nova.runtime.NovaTypeRegistry;

import java.util.Arrays;

/**
 * 并发相关的 stdlib 注册：async 函数 + Future 类型元数据。
 */
final class Concurrency {

    private Concurrency() {}

    /** 注册接受 lambda 的 varargs 函数（方法名与函数名相同） */
    private static void registerLambda(String name, String owner,
                                        java.util.function.Function<Object[], Object> impl) {
        registerLambda(name, owner, name, impl);
    }

    /** 注册接受 lambda 的 varargs 函数 */
    private static void registerLambda(String name, String owner, String methodName,
                                        java.util.function.Function<Object[], Object> impl) {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            name, -1, owner, methodName,
            "([Ljava/lang/Object;)Ljava/lang/Object;", impl, true));
    }

    static void register() {
        // async { body } → CompletableFuture.supplyAsync(() -> body)
        StdlibRegistry.register(new StdlibRegistry.SupplierLambdaInfo(
            "async",
            "java/util/concurrent/CompletableFuture",
            "supplyAsync",
            "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            "Future"
        ));

        // coroutineScope(block) 或 coroutineScope(dispatcher, block)
        registerLambda("coroutineScope", "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "coroutineScopeVararg", args -> StructuredConcurrencyHelper.coroutineScopeVararg(args));
        // supervisorScope(block) 或 supervisorScope(dispatcher, block)
        registerLambda("supervisorScope", "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "supervisorScopeVararg", args -> StructuredConcurrencyHelper.supervisorScopeVararg(args));

        // Dispatchers 常量：Dispatchers.IO / Dispatchers.Default / Dispatchers.Unconfined
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "Dispatchers",
            "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "DISPATCHERS",
            "Lnova/runtime/stdlib/StructuredConcurrencyHelper$CompileDispatchers;",
            StructuredConcurrencyHelper.DISPATCHERS
        ));

        // schedule(delayMs, block) — 延迟调度，返回 Task
        registerLambda("schedule", "nova/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.schedule(args));
        // scheduleRepeat(delayMs, periodMs, block) — 重复调度，返回 Task
        registerLambda("scheduleRepeat", "nova/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.scheduleRepeat(args));
        // delay(millis) — 主线程安全检查 + Thread.sleep
        registerLambda("delay", "nova/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.delay(args));
        // scope { block } — 在 IO 线程执行，阻塞调用者直到完成
        registerLambda("scope", "nova/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.scope(args));
        // sync { block } — 提交到主线程执行并等待结果
        registerLambda("sync", "nova/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.sync(args));
        // launch { block } — fire-and-forget 异步，返回 Job
        registerLambda("launch", "nova/runtime/stdlib/ConcurrencyHelper", args -> ConcurrencyHelper.launch(args));
        // parallel(tasks...) — 并行执行多个 lambda，返回结果列表
        registerLambda("parallel", "nova/runtime/stdlib/ConcurrencyHelper", args -> ConcurrencyHelper.parallel(args));
        // withTimeout(millis, block) — 带超时执行
        registerLambda("withTimeout", "nova/runtime/stdlib/ConcurrencyHelper", args -> ConcurrencyHelper.withTimeout(args));

        // ============ 并发原语构造器 ============

        // 并发原语构造器（解释器中需要走 Builtins）
        registerLambda("AtomicInt", "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicInt", args -> ConcurrencyPrimitivesHelper.atomicInt(args));
        registerLambda("AtomicLong", "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicLong", args -> ConcurrencyPrimitivesHelper.atomicLong(args));
        registerLambda("AtomicRef", "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicRef", args -> ConcurrencyPrimitivesHelper.atomicRef(args));
        registerLambda("Channel", "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "channel", args -> ConcurrencyPrimitivesHelper.channel(args));
        registerLambda("Mutex", "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "mutex", args -> ConcurrencyPrimitivesHelper.mutex(args));

        // ============ awaitAll / awaitFirst / withContext ============

        registerLambda("awaitAll", "nova/runtime/stdlib/ConcurrencyHelper",
            args -> ConcurrencyHelper.awaitAll(args));
        registerLambda("awaitFirst", "nova/runtime/stdlib/ConcurrencyHelper",
            args -> ConcurrencyHelper.awaitFirst(args));

        // withContext(dispatcher, block) — 在指定 executor 上执行 block
        registerLambda("withContext", "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "withContextVararg", args -> StructuredConcurrencyHelper.withContextVararg(args));

        // Future 类型元数据（直接注册，避免反射依赖 nova-runtime 内部类）
        NovaTypeRegistry.registerFutureType();
    }
}
