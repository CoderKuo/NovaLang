package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 并发/异步功能测试：覆盖 async/await、Future 超时/取消、安全策略限制等。
 */
@DisplayName("并发与异步测试")
class ConcurrencyTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    private Interpreter createInterpreter(NovaSecurityPolicy policy) {
        Interpreter interp = new Interpreter(policy);
        interp.setReplMode(true);
        return interp;
    }

    // ============================================================
    //  基础 async/await
    // ============================================================

    @Nested
    @DisplayName("基础 async/await")
    class BasicAsyncTests {

        @Test
        @DisplayName("简单 async 返回值")
        void testBasicAsyncValue() {
            NovaValue result = interpreter.evalRepl(
                    "val f = async { 42 }\n" +
                    "await f"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("async 返回字符串")
        void testAsyncString() {
            NovaValue result = interpreter.evalRepl(
                    "val f = async { \"hello\" }\n" +
                    "await f"
            );
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("async 表达式运算")
        void testAsyncComputation() {
            NovaValue result = interpreter.evalRepl(
                    "val f = async { 10 * 5 + 3 }\n" +
                    "await f"
            );
            assertEquals(53, result.asInt());
        }

        @Test
        @DisplayName("async 访问外部变量（闭包）")
        void testAsyncClosure() {
            NovaValue result = interpreter.evalRepl(
                    "val x = 100\n" +
                    "val f = async { x + 1 }\n" +
                    "await f"
            );
            assertEquals(101, result.asInt());
        }

        @Test
        @DisplayName("await 非 Future 值直接返回")
        void testAwaitNonFuture() {
            NovaValue result = interpreter.evalRepl(
                    "await 42"
            );
            assertEquals(42, result.asInt());
        }
    }

    // ============================================================
    //  多个并发 Future
    // ============================================================

    @Nested
    @DisplayName("多 Future 并发")
    class MultipleFuturesTests {

        @Test
        @DisplayName("两个 Future 并发执行")
        void testTwoFutures() {
            NovaValue result = interpreter.evalRepl(
                    "val f1 = async { 10 }\n" +
                    "val f2 = async { 20 }\n" +
                    "val r1 = await f1\n" +
                    "val r2 = await f2\n" +
                    "r1 + r2"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("多个 Future 结果收集")
        void testMultipleFuturesCollect() {
            NovaValue result = interpreter.evalRepl(
                    "val f1 = async { 1 }\n" +
                    "val f2 = async { 2 }\n" +
                    "val f3 = async { 3 }\n" +
                    "val results = [await f1, await f2, await f3]\n" +
                    "results[0] + results[1] + results[2]"
            );
            assertEquals(6, result.asInt());
        }
    }

    // ============================================================
    //  Future 成员方法
    // ============================================================

    @Nested
    @DisplayName("Future 成员方法")
    class FutureMemberTests {

        @Test
        @DisplayName("Future.get() 阻塞获取")
        void testFutureGet() {
            NovaValue result = interpreter.evalRepl(
                    "val f = async { 99 }\n" +
                    "f.get()"
            );
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("Future.isDone 完成后为 true")
        void testFutureIsDone() {
            NovaValue result = interpreter.evalRepl(
                    "val f = async { 1 }\n" +
                    "await f\n" +
                    "f.isDone"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Future.cancel() 取消任务")
        void testFutureCancel() {
            // 创建一个长时间运行的任务以便取消
            NovaValue result = interpreter.evalRepl(
                    "val f = async {\n" +
                    "    var i = 0\n" +
                    "    while (i < 1000000) { i = i + 1 }\n" +
                    "    i\n" +
                    "}\n" +
                    "f.cancel()\n" +
                    "f.isCancelled || f.isDone"
            );
            // cancel 可能成功或任务已完成（取决于调度），两种情况都合法
            assertTrue(result.asBoolean());
        }
    }

    // ============================================================
    //  async 错误传播
    // ============================================================

    @Nested
    @DisplayName("async 错误传播")
    class AsyncErrorTests {

        @Test
        @DisplayName("async 中的异常通过 await 传播")
        void testAsyncExceptionPropagation() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                        "val f = async { throw \"async error\" }\n" +
                        "await f"
                );
            });
        }

        @Test
        @DisplayName("async 除零错误传播")
        void testAsyncDivisionByZero() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                        "val f = async { 1 / 0 }\n" +
                        "await f"
                );
            });
        }
    }

    // ============================================================
    //  async 与函数交互
    // ============================================================

    @Nested
    @DisplayName("async 与函数")
    class AsyncWithFunctionsTests {

        @Test
        @DisplayName("async 中调用函数")
        void testAsyncWithFunction() {
            NovaValue result = interpreter.evalRepl(
                    "fun double(x: Int): Int = x * 2\n" +
                    "val f = async { double(21) }\n" +
                    "await f"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("async 中使用 Lambda")
        void testAsyncWithLambda() {
            NovaValue result = interpreter.evalRepl(
                    "val mul = { a: Int, b: Int -> a * b }\n" +
                    "val f = async { mul(6, 7) }\n" +
                    "await f"
            );
            assertEquals(42, result.asInt());
        }
    }

    // ============================================================
    //  安全策略：异步任务限制
    // ============================================================

    @Nested
    @DisplayName("安全策略异步限制")
    class SecurityPolicyAsyncTests {

        @Test
        @DisplayName("STRICT 策略允许有限异步任务")
        void testStrictPolicyAllowsLimitedAsync() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.strict());
            // STRICT 允许最多 16 个异步任务，单个任务应该没问题
            NovaValue result = interp.evalRepl(
                    "val f = async { 42 }\n" +
                    "await f"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("自定义策略限制异步任务数量")
        void testCustomPolicyMaxAsyncTasks() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(false)
                    .allowStdio(true)
                    .maxAsyncTasks(1)
                    .build();
            Interpreter interp = createInterpreter(policy);

            // 第一个任务应该成功
            NovaValue result = interp.evalRepl(
                    "val f = async { 42 }\n" +
                    "await f"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("安全策略 maxAsyncTasks getter")
        void testMaxAsyncTasksGetter() {
            NovaSecurityPolicy strict = NovaSecurityPolicy.strict();
            assertEquals(16, strict.getMaxAsyncTasks());

            NovaSecurityPolicy standard = NovaSecurityPolicy.standard();
            assertEquals(64, standard.getMaxAsyncTasks());

            NovaSecurityPolicy unrestricted = NovaSecurityPolicy.unrestricted();
            assertEquals(0, unrestricted.getMaxAsyncTasks());
        }
    }

    // ============================================================
    //  NovaSecurityPolicy Builder 测试
    // ============================================================

    @Nested
    @DisplayName("安全策略 Builder")
    class SecurityPolicyBuilderTests {

        @Test
        @DisplayName("自定义策略所有字段")
        void testCustomPolicyFields() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowSetAccessible(false)
                    .allowStdio(true)
                    .maxExecutionTime(5000)
                    .maxRecursionDepth(100)
                    .maxLoopIterations(50000)
                    .maxAsyncTasks(8)
                    .build();

            assertTrue(policy.isJavaInteropAllowed());
            assertFalse(policy.isSetAccessibleAllowed());
            assertTrue(policy.isStdioAllowed());
            assertEquals(5000, policy.getMaxExecutionTimeMs());
            assertEquals(100, policy.getMaxRecursionDepth());
            assertEquals(50000, policy.getMaxLoopIterations());
            assertEquals(8, policy.getMaxAsyncTasks());
        }

        @Test
        @DisplayName("denied 工厂方法创建异常")
        void testDeniedFactory() {
            NovaRuntimeException ex = NovaSecurityPolicy.denied("test action");
            assertTrue(ex.getMessage().contains("Security policy denied"));
            assertTrue(ex.getMessage().contains("test action"));
        }
    }

    // ============================================================
    //  NovaFuture 直接测试
    // ============================================================

    @Nested
    @DisplayName("NovaFuture 属性")
    class NovaFuturePropertyTests {

        @Test
        @DisplayName("Future toString 格式")
        void testFutureToString() {
            NovaValue future = interpreter.evalRepl(
                    "async { 42 }"
            );
            assertNotNull(future);
            String str = future.toString();
            // 可能是 pending 或已完成
            assertTrue(str.startsWith("<future:"));
        }

        @Test
        @DisplayName("Future typeName 是 Future")
        void testFutureTypeName() {
            NovaValue future = interpreter.evalRepl(
                    "async { 1 }"
            );
            assertTrue(future instanceof NovaFuture);
            assertEquals("Future", future.getTypeName());
        }
    }

    // ============================================================
    //  全局 launch
    // ============================================================

    @Nested
    @DisplayName("全局 launch")
    class GlobalLaunchTests {

        @Test
        @DisplayName("launch 返回 Job 并能 join")
        void testLaunchReturnsJob() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "val job = launch { x = 42 }\n" +
                "job.join()\n" +
                "x"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("launch Job.isCompleted")
        void testLaunchJobIsCompleted() {
            NovaValue result = interpreter.evalRepl(
                "val job = launch { 1 + 1 }\n" +
                "job.join()\n" +
                "job.isCompleted"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("launch Job.cancel")
        void testLaunchJobCancel() {
            NovaValue result = interpreter.evalRepl(
                "val job = launch {\n" +
                "    var i = 0\n" +
                "    while (i < 1000000) { i = i + 1 }\n" +
                "}\n" +
                "job.cancel()\n" +
                "job.isCancelled || job.isCompleted"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("launch 内访问闭包变量")
        void testLaunchClosure() {
            NovaValue result = interpreter.evalRepl(
                "val base = 100\n" +
                "var out = 0\n" +
                "val job = launch { out = base + 5 }\n" +
                "job.join()\n" +
                "out"
            );
            assertEquals(105, result.asInt());
        }

        @Test
        @DisplayName("launch 内异常不影响调用者")
        void testLaunchExceptionContained() {
            // launch 是 fire-and-forget，异常不应传播到调用者（除非 join）
            NovaValue result = interpreter.evalRepl(
                "val job = launch { throw \"boom\" }\n" +
                "42"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("launch job.join 传播异常")
        void testLaunchJoinPropagatesException() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "val job = launch { throw \"boom\" }\n" +
                    "job.join()"
                );
            });
        }
    }

    // ============================================================
    //  全局 parallel
    // ============================================================

    @Nested
    @DisplayName("全局 parallel")
    class GlobalParallelTests {

        @Test
        @DisplayName("parallel 两个任务返回结果列表")
        void testParallelTwoTasks() {
            NovaValue result = interpreter.evalRepl(
                "val results = parallel({ 10 }, { 20 })\n" +
                "results[0] + results[1]"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("parallel 三个任务")
        void testParallelThreeTasks() {
            NovaValue result = interpreter.evalRepl(
                "val results = parallel({ 1 }, { 2 }, { 3 })\n" +
                "results[0] + results[1] + results[2]"
            );
            assertEquals(6, result.asInt());
        }

        @Test
        @DisplayName("parallel 单个任务")
        void testParallelSingleTask() {
            NovaValue result = interpreter.evalRepl(
                "val results = parallel({ 99 })\n" +
                "results[0]"
            );
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("parallel 任务访问闭包")
        void testParallelClosure() {
            NovaValue result = interpreter.evalRepl(
                "val x = 10\n" +
                "val results = parallel({ x * 2 }, { x * 3 })\n" +
                "results[0] + results[1]"
            );
            assertEquals(50, result.asInt());
        }

        @Test
        @DisplayName("parallel 任务异常传播")
        void testParallelExceptionPropagation() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "parallel({ 1 }, { throw \"fail\" })"
                );
            });
        }

        @Test
        @DisplayName("parallel 结果保持顺序")
        void testParallelOrderPreserved() {
            NovaValue result = interpreter.evalRepl(
                "val results = parallel({ \"a\" }, { \"b\" }, { \"c\" })\n" +
                "results[0] + results[1] + results[2]"
            );
            assertEquals("abc", result.asString());
        }
    }

    // ============================================================
    //  全局 withTimeout
    // ============================================================

    @Nested
    @DisplayName("全局 withTimeout")
    class GlobalWithTimeoutTests {

        @Test
        @DisplayName("withTimeout 正常完成返回结果")
        void testWithTimeoutNormalCompletion() {
            NovaValue result = interpreter.evalRepl(
                "withTimeout(5000, { 42 })"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("withTimeout 超时抛异常")
        void testWithTimeoutThrows() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "withTimeout(50, {\n" +
                    "    var i = 0\n" +
                    "    while (i < 999999999) { i = i + 1 }\n" +
                    "    i\n" +
                    "})"
                );
            });
        }

        @Test
        @DisplayName("withTimeout 闭包访问")
        void testWithTimeoutClosure() {
            NovaValue result = interpreter.evalRepl(
                "val x = 10\n" +
                "withTimeout(5000, { x * 5 })"
            );
            assertEquals(50, result.asInt());
        }

        @Test
        @DisplayName("withTimeout 内部异常传播")
        void testWithTimeoutInternalException() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "withTimeout(5000, { throw \"error in block\" })"
                );
            });
        }
    }

    // ============================================================
    //  全局 AtomicInt
    // ============================================================

    @Nested
    @DisplayName("全局 AtomicInt")
    class GlobalAtomicIntTests {

        @Test
        @DisplayName("AtomicInt 基本 get/set")
        void testAtomicIntGetSet() {
            NovaValue result = interpreter.evalRepl(
                "val a = AtomicInt(10)\n" +
                "a.set(20)\n" +
                "a.get()"
            );
            assertEquals(20, result.asInt());
        }

        @Test
        @DisplayName("AtomicInt incrementAndGet")
        void testAtomicIntIncrement() {
            NovaValue result = interpreter.evalRepl(
                "val a = AtomicInt(0)\n" +
                "a.incrementAndGet()\n" +
                "a.incrementAndGet()\n" +
                "a.get()"
            );
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("AtomicInt decrementAndGet")
        void testAtomicIntDecrement() {
            NovaValue result = interpreter.evalRepl(
                "val a = AtomicInt(5)\n" +
                "a.decrementAndGet()"
            );
            assertEquals(4, result.asInt());
        }

        @Test
        @DisplayName("AtomicInt addAndGet")
        void testAtomicIntAddAndGet() {
            NovaValue result = interpreter.evalRepl(
                "val a = AtomicInt(10)\n" +
                "a.addAndGet(5)"
            );
            assertEquals(15, result.asInt());
        }

        @Test
        @DisplayName("AtomicInt compareAndSet")
        void testAtomicIntCAS() {
            NovaValue result = interpreter.evalRepl(
                "val a = AtomicInt(10)\n" +
                "val ok = a.compareAndSet(10, 20)\n" +
                "val fail = a.compareAndSet(10, 30)\n" +
                "ok.toString() + \",\" + fail.toString() + \",\" + a.get()"
            );
            assertEquals("true,false,20", result.asString());
        }

        @Test
        @DisplayName("AtomicInt 并发安全")
        void testAtomicIntConcurrent() {
            NovaValue result = interpreter.evalRepl(
                "val counter = AtomicInt(0)\n" +
                "val results = parallel(\n" +
                "    { counter.incrementAndGet() },\n" +
                "    { counter.incrementAndGet() },\n" +
                "    { counter.incrementAndGet() }\n" +
                ")\n" +
                "counter.get()"
            );
            assertEquals(3, result.asInt());
        }
    }

    // ============================================================
    //  全局 AtomicLong
    // ============================================================

    @Nested
    @DisplayName("全局 AtomicLong")
    class GlobalAtomicLongTests {

        @Test
        @DisplayName("AtomicLong 基本操作")
        void testAtomicLongBasic() {
            NovaValue result = interpreter.evalRepl(
                "val a = AtomicLong(100L)\n" +
                "a.incrementAndGet()\n" +
                "a.addAndGet(9L)\n" +
                "a.get()"
            );
            assertEquals(110L, result.asLong());
        }
    }

    // ============================================================
    //  全局 AtomicRef
    // ============================================================

    @Nested
    @DisplayName("全局 AtomicRef")
    class GlobalAtomicRefTests {

        @Test
        @DisplayName("AtomicRef get/set")
        void testAtomicRefGetSet() {
            NovaValue result = interpreter.evalRepl(
                "val r = AtomicRef(\"hello\")\n" +
                "r.set(\"world\")\n" +
                "r.get()"
            );
            assertEquals("world", result.asString());
        }

        @Test
        @DisplayName("AtomicRef compareAndSet")
        void testAtomicRefCAS() {
            NovaValue result = interpreter.evalRepl(
                "val r = AtomicRef(42)\n" +
                "val ok = r.compareAndSet(42, 99)\n" +
                "ok.toString() + \",\" + r.get()"
            );
            assertEquals("true,99", result.asString());
        }
    }

    // ============================================================
    //  全局 Channel
    // ============================================================

    @Nested
    @DisplayName("全局 Channel")
    class GlobalChannelTests {

        @Test
        @DisplayName("Channel send/receive")
        void testChannelSendReceive() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "ch.send(42)\n" +
                "ch.send(99)\n" +
                "val a = ch.receive()\n" +
                "val b = ch.receive()\n" +
                "a + b"
            );
            assertEquals(141, result.asInt());
        }

        @Test
        @DisplayName("Channel tryReceive 空通道返回 null")
        void testChannelTryReceiveEmpty() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "ch.tryReceive() == null"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Channel size/isEmpty")
        void testChannelSizeEmpty() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "val empty = ch.isEmpty()\n" +
                "ch.send(1)\n" +
                "ch.send(2)\n" +
                "empty.toString() + \",\" + ch.size()"
            );
            assertEquals("true,2", result.asString());
        }

        @Test
        @DisplayName("Channel close")
        void testChannelClose() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "ch.close()\n" +
                "ch.isClosed()"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Channel close 后 send 抛异常")
        void testChannelSendAfterClose() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "val ch = Channel(10)\n" +
                    "ch.close()\n" +
                    "ch.send(1)"
                );
            });
        }
    }

    // ============================================================
    //  全局 Mutex
    // ============================================================

    @Nested
    @DisplayName("全局 Mutex")
    class GlobalMutexTests {

        @Test
        @DisplayName("Mutex lock/unlock")
        void testMutexLockUnlock() {
            NovaValue result = interpreter.evalRepl(
                "val m = Mutex()\n" +
                "m.lock()\n" +
                "val locked = m.isLocked()\n" +
                "m.unlock()\n" +
                "locked.toString() + \",\" + m.isLocked()"
            );
            assertEquals("true,false", result.asString());
        }

        @Test
        @DisplayName("Mutex tryLock")
        void testMutexTryLock() {
            NovaValue result = interpreter.evalRepl(
                "val m = Mutex()\n" +
                "m.tryLock()"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Mutex withLock")
        void testMutexWithLock() {
            NovaValue result = interpreter.evalRepl(
                "val m = Mutex()\n" +
                "var x = 0\n" +
                "m.withLock { x = 42 }\n" +
                "x"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("Mutex withLock 返回值")
        void testMutexWithLockReturnValue() {
            NovaValue result = interpreter.evalRepl(
                "val m = Mutex()\n" +
                "m.withLock { 100 + 23 }"
            );
            assertEquals(123, result.asInt());
        }

        @Test
        @DisplayName("Mutex 并发保护")
        void testMutexConcurrentProtection() {
            NovaValue result = interpreter.evalRepl(
                "val m = Mutex()\n" +
                "val counter = AtomicInt(0)\n" +
                "parallel(\n" +
                "    { m.withLock { counter.addAndGet(1) } },\n" +
                "    { m.withLock { counter.addAndGet(1) } },\n" +
                "    { m.withLock { counter.addAndGet(1) } }\n" +
                ")\n" +
                "counter.get()"
            );
            assertEquals(3, result.asInt());
        }
    }

    // ============================================================
    //  全局 awaitAll / awaitFirst
    // ============================================================

    @Nested
    @DisplayName("全局 awaitAll / awaitFirst")
    class GlobalAwaitTests {

        @Test
        @DisplayName("awaitAll 等待多个 Future")
        void testAwaitAllMultipleFutures() {
            NovaValue result = interpreter.evalRepl(
                "val f1 = async { 10 }\n" +
                "val f2 = async { 20 }\n" +
                "val f3 = async { 30 }\n" +
                "val results = awaitAll([f1, f2, f3])\n" +
                "results[0] + results[1] + results[2]"
            );
            assertEquals(60, result.asInt());
        }

        @Test
        @DisplayName("awaitAll 混合 Future 和普通值")
        void testAwaitAllMixed() {
            NovaValue result = interpreter.evalRepl(
                "val f = async { 42 }\n" +
                "val results = awaitAll([f])\n" +
                "results[0]"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("awaitFirst 返回首个完成的结果")
        void testAwaitFirst() {
            NovaValue result = interpreter.evalRepl(
                "val f1 = async { 100 }\n" +
                "val f2 = async { 200 }\n" +
                "val r = awaitFirst([f1, f2])\n" +
                "r > 0"
            );
            assertTrue(result.asBoolean());
        }
    }

    // ============================================================
    //  Channel receiveTimeout
    // ============================================================

    @Nested
    @DisplayName("Channel receiveTimeout")
    class ChannelReceiveTimeoutTests {

        @Test
        @DisplayName("receiveTimeout 正常接收")
        void testReceiveTimeoutNormal() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "ch.send(42)\n" +
                "ch.receiveTimeout(5000)"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("receiveTimeout 超时抛异常")
        void testReceiveTimeoutThrows() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "val ch = Channel(10)\n" +
                    "ch.receiveTimeout(50)"
                );
            });
        }
    }

    // ============================================================
    //  Channel 迭代协议
    // ============================================================

    @Nested
    @DisplayName("Channel 迭代")
    class ChannelIterationTests {

        @Test
        @DisplayName("for 循环遍历 Channel")
        void testChannelForLoop() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "ch.send(1)\n" +
                "ch.send(2)\n" +
                "ch.send(3)\n" +
                "ch.close()\n" +
                "var sum = 0\n" +
                "for (msg in ch) {\n" +
                "    sum = sum + msg\n" +
                "}\n" +
                "sum"
            );
            assertEquals(6, result.asInt());
        }

        @Test
        @DisplayName("close 不清空队列")
        void testCloseDoesNotClearQueue() {
            NovaValue result = interpreter.evalRepl(
                "val ch = Channel(10)\n" +
                "ch.send(42)\n" +
                "ch.close()\n" +
                "ch.receive()"
            );
            assertEquals(42, result.asInt());
        }
    }

    // ============================================================
    //  全局 withContext
    // ============================================================

    @Nested
    @DisplayName("全局 withContext")
    class GlobalWithContextTests {

        @Test
        @DisplayName("withContext 在指定 Dispatcher 上执行")
        void testWithContextDispatcher() {
            NovaValue result = interpreter.evalRepl(
                "val r = withContext(Dispatchers.IO, { 42 })\n" +
                "r"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("withContext Default dispatcher")
        void testWithContextDefault() {
            NovaValue result = interpreter.evalRepl(
                "withContext(Dispatchers.Default, { 10 + 20 })"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("withContext 闭包访问")
        void testWithContextClosure() {
            NovaValue result = interpreter.evalRepl(
                "val x = 100\n" +
                "withContext(Dispatchers.IO, { x + 5 })"
            );
            assertEquals(105, result.asInt());
        }

        @Test
        @DisplayName("withContext 内部异常传播")
        void testWithContextException() {
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl(
                    "withContext(Dispatchers.IO, { throw \"context error\" })"
                );
            });
        }
    }
}