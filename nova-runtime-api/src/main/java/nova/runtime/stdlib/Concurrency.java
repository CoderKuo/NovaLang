package nova.runtime.stdlib;

import nova.runtime.NovaTypeRegistry;

import java.util.Arrays;

/**
 * 并发相关的 stdlib 注册：async 函数 + Future 类型元数据。
 */
final class Concurrency {

    private Concurrency() {}

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
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "coroutineScope", -1,
            "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "coroutineScopeVararg",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> StructuredConcurrencyHelper.coroutineScopeVararg(args)
        ));

        // supervisorScope(block) 或 supervisorScope(dispatcher, block)
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "supervisorScope", -1,
            "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "supervisorScopeVararg",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> StructuredConcurrencyHelper.supervisorScopeVararg(args)
        ));

        // Dispatchers 常量：Dispatchers.IO / Dispatchers.Default / Dispatchers.Unconfined
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "Dispatchers",
            "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "DISPATCHERS",
            "Lnova/runtime/stdlib/StructuredConcurrencyHelper$CompileDispatchers;",
            StructuredConcurrencyHelper.DISPATCHERS
        ));

        // schedule(delayMs, block) — 延迟调度，返回 Task
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "schedule", -1,
            "nova/runtime/stdlib/SchedulerHelper",
            "schedule",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> SchedulerHelper.schedule(args)
        ));

        // scheduleRepeat(delayMs, periodMs, block) — 重复调度，返回 Task
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "scheduleRepeat", -1,
            "nova/runtime/stdlib/SchedulerHelper",
            "scheduleRepeat",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> SchedulerHelper.scheduleRepeat(args)
        ));

        // delay(millis) — 主线程安全检查 + Thread.sleep
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "delay", -1,
            "nova/runtime/stdlib/SchedulerHelper",
            "delay",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> SchedulerHelper.delay(args)
        ));

        // scope { block } — 在 IO 线程执行，阻塞调用者直到完成
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "scope", -1,
            "nova/runtime/stdlib/SchedulerHelper",
            "scope",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> SchedulerHelper.scope(args)
        ));

        // sync { block } — 提交到主线程执行并等待结果
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sync", -1,
            "nova/runtime/stdlib/SchedulerHelper",
            "sync",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> SchedulerHelper.sync(args)
        ));

        // launch { block } — fire-and-forget 异步，返回 Job
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "launch", -1,
            "nova/runtime/stdlib/ConcurrencyHelper",
            "launch",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyHelper.launch(args)
        ));

        // parallel(tasks...) — 并行执行多个 lambda，返回结果列表
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "parallel", -1,
            "nova/runtime/stdlib/ConcurrencyHelper",
            "parallel",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyHelper.parallel(args)
        ));

        // withTimeout(millis, block) — 带超时执行
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "withTimeout", -1,
            "nova/runtime/stdlib/ConcurrencyHelper",
            "withTimeout",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyHelper.withTimeout(args)
        ));

        // ============ 并发原语构造器 ============

        // AtomicInt(initial) — 原子整数
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "AtomicInt", -1,
            "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicInt",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyPrimitivesHelper.atomicInt(args)
        ));

        // AtomicLong(initial) — 原子长整数
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "AtomicLong", -1,
            "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicLong",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyPrimitivesHelper.atomicLong(args)
        ));

        // AtomicRef(initial) — 原子引用
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "AtomicRef", -1,
            "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicRef",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyPrimitivesHelper.atomicRef(args)
        ));

        // Channel(capacity?) — 并发通道
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "Channel", -1,
            "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "channel",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyPrimitivesHelper.channel(args)
        ));

        // Mutex() — 互斥锁
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "Mutex", -1,
            "nova/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "mutex",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyPrimitivesHelper.mutex(args)
        ));

        // ============ awaitAll / awaitFirst / withContext ============

        // awaitAll(futures) — 等待所有 Future 完成
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "awaitAll", -1,
            "nova/runtime/stdlib/ConcurrencyHelper",
            "awaitAll",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyHelper.awaitAll(args)
        ));

        // awaitFirst(futures) — 返回首个完成的结果
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "awaitFirst", -1,
            "nova/runtime/stdlib/ConcurrencyHelper",
            "awaitFirst",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> ConcurrencyHelper.awaitFirst(args)
        ));

        // withContext(dispatcher, block) — 在指定 executor 上执行 block
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "withContext", -1,
            "nova/runtime/stdlib/StructuredConcurrencyHelper",
            "withContextVararg",
            "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> StructuredConcurrencyHelper.withContextVararg(args)
        ));

        // Future 类型元数据（直接注册，避免反射依赖 nova-runtime 内部类）
        NovaTypeRegistry.registerFutureType();
    }
}
