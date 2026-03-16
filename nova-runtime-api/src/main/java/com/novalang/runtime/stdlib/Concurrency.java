package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaTypeRegistry;

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

    /** 注册接受 lambda 的 varargs 函数（通过 delegateToInterpreter 代理，不需要 interpreterBuiltin） */
    private static void registerLambda(String name, String owner, String methodName,
                                        java.util.function.Function<Object[], Object> impl) {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            name, -1, owner, methodName,
            "([Ljava/lang/Object;)Ljava/lang/Object;", impl));
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

        // coroutineScope/supervisorScope: 通过 ExecutionContext.runInScope 统一（不需要 interpreterBuiltin）
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "coroutineScope", -1, "com/novalang/runtime/stdlib/StructuredConcurrencyHelper",
            "coroutineScopeVararg", "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> StructuredConcurrencyHelper.coroutineScopeVararg(args)));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "supervisorScope", -1, "com/novalang/runtime/stdlib/StructuredConcurrencyHelper",
            "supervisorScopeVararg", "([Ljava/lang/Object;)Ljava/lang/Object;",
            args -> StructuredConcurrencyHelper.supervisorScopeVararg(args)));

        // Dispatchers 常量：Dispatchers.IO / Dispatchers.Default / Dispatchers.Unconfined
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "Dispatchers",
            "com/novalang/runtime/stdlib/StructuredConcurrencyHelper",
            "DISPATCHERS",
            "Lcom/novalang/runtime/stdlib/StructuredConcurrencyHelper$CompileDispatchers;",
            StructuredConcurrencyHelper.DISPATCHERS
        ));

        // schedule(delayMs, block) — 延迟调度，返回 Task
        registerLambda("schedule", "com/novalang/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.schedule(args));
        // scheduleRepeat(delayMs, periodMs, block) — 重复调度，返回 Task
        registerLambda("scheduleRepeat", "com/novalang/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.scheduleRepeat(args));
        // delay(millis) — 主线程安全检查 + Thread.sleep
        registerLambda("delay", "com/novalang/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.delay(args));
        // scope { block } — 在 IO 线程执行，阻塞调用者直到完成
        registerLambda("scope", "com/novalang/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.scope(args));
        // sync { block } — 提交到主线程执行并等待结果
        registerLambda("sync", "com/novalang/runtime/stdlib/SchedulerHelper", args -> SchedulerHelper.sync(args));
        // launch { block } — fire-and-forget 异步，返回 Job
        registerLambda("launch", "com/novalang/runtime/stdlib/ConcurrencyHelper", args -> ConcurrencyHelper.launch(args));
        // parallel(tasks...) — 并行执行多个 lambda，返回结果列表
        registerLambda("parallel", "com/novalang/runtime/stdlib/ConcurrencyHelper", args -> ConcurrencyHelper.parallel(args));
        // withTimeout(millis, block) — 带超时执行
        registerLambda("withTimeout", "com/novalang/runtime/stdlib/ConcurrencyHelper", args -> ConcurrencyHelper.withTimeout(args));

        // ============ 并发原语构造器 ============

        // 并发原语构造器（解释器中需要走 Builtins）
        registerLambda("AtomicInt", "com/novalang/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicInt", args -> ConcurrencyPrimitivesHelper.atomicInt(args));
        registerLambda("AtomicLong", "com/novalang/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicLong", args -> ConcurrencyPrimitivesHelper.atomicLong(args));
        registerLambda("AtomicRef", "com/novalang/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "atomicRef", args -> ConcurrencyPrimitivesHelper.atomicRef(args));
        registerLambda("Channel", "com/novalang/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "channel", args -> ConcurrencyPrimitivesHelper.channel(args));
        registerLambda("Mutex", "com/novalang/runtime/stdlib/ConcurrencyPrimitivesHelper",
            "mutex", args -> ConcurrencyPrimitivesHelper.mutex(args));

        // ============ awaitAll / awaitFirst / withContext ============

        registerLambda("awaitAll", "com/novalang/runtime/stdlib/ConcurrencyHelper",
            args -> ConcurrencyHelper.awaitAll(args));
        registerLambda("awaitFirst", "com/novalang/runtime/stdlib/ConcurrencyHelper",
            args -> ConcurrencyHelper.awaitFirst(args));

        // withContext(dispatcher, block) — 在指定 executor 上执行 block
        registerLambda("withContext", "com/novalang/runtime/stdlib/StructuredConcurrencyHelper",
            "withContextVararg", args -> StructuredConcurrencyHelper.withContextVararg(args));

        // Future 类型元数据（直接注册，避免反射依赖 nova-runtime 内部类）
        NovaTypeRegistry.registerFutureType();
    }
}
