package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NovaScheduler SPI 集成测试。
 * 使用 {@link MockTickScheduler} 模拟 Bukkit 基于 Tick 的调度器。
 */
@DisplayName("NovaScheduler SPI 测试")
class NovaSchedulerTest {

    private MockTickScheduler scheduler;
    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        scheduler = new MockTickScheduler();
        scheduler.start();
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
        interpreter.setScheduler(scheduler);
        interpreter.evalRepl("import nova.concurrent.*");
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdown();
        SchedulerHolder.set(null);
    }

    // ============================================================
    //  MockTickScheduler — 模拟 Bukkit Tick 调度器
    // ============================================================

    /**
     * 模拟 Bukkit 基于 Tick 的调度器。
     * <ul>
     *   <li>维护一个专用"主线程"（类似 Bukkit 主线程）</li>
     *   <li>1 tick = 50ms</li>
     *   <li>支持 runTaskLater（延迟 N tick 后执行）和 runTaskTimer（重复执行）</li>
     *   <li>所有任务在主线程上执行</li>
     * </ul>
     */
    static class MockTickScheduler implements NovaScheduler {

        private static final long MS_PER_TICK = 50;

        private final Thread mainThread;
        private final BlockingQueue<Runnable> mainQueue = new LinkedBlockingQueue<>();
        private volatile boolean running = true;

        /** 模拟 Bukkit 异步调度器线程池 */
        private final ExecutorService asyncPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "MockBukkit-AsyncThread");
            t.setDaemon(true);
            return t;
        });

        /** 当前 tick 计数 */
        private final AtomicLong currentTick = new AtomicLong(0);

        /** 调度任务列表 */
        private final List<TickTask> pendingTasks = new CopyOnWriteArrayList<>();

        MockTickScheduler() {
            mainThread = new Thread(this::tickLoop, "MockBukkit-MainThread");
            mainThread.setDaemon(true);
        }

        void start() {
            mainThread.start();
        }

        void shutdown() {
            running = false;
            mainThread.interrupt();
            asyncPool.shutdownNow();
        }

        /** 等待直到至少执行了 n 个 tick */
        void waitForTicks(int n) throws InterruptedException {
            long target = currentTick.get() + n;
            while (currentTick.get() < target && running) {
                Thread.sleep(MS_PER_TICK / 2);
            }
        }

        /** 主线程 tick 循环 */
        private void tickLoop() {
            while (running) {
                try {
                    // 处理直接提交到主线程的任务（mainExecutor 提交的）
                    Runnable r;
                    while ((r = mainQueue.poll()) != null) {
                        r.run();
                    }

                    // 推进 tick，执行到期任务
                    long tick = currentTick.incrementAndGet();
                    for (TickTask task : pendingTasks) {
                        if (task.cancelled) continue;
                        if (tick >= task.nextFireTick) {
                            task.runnable.run();
                            if (task.periodTicks > 0) {
                                task.nextFireTick = tick + task.periodTicks;
                            } else {
                                task.cancelled = true;
                            }
                        }
                    }
                    // 清理已完成/已取消的任务
                    pendingTasks.removeIf(t -> t.cancelled);

                    Thread.sleep(MS_PER_TICK);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }

        @Override
        public Executor mainExecutor() {
            return mainQueue::add;
        }

        @Override
        public Executor asyncExecutor() {
            return asyncPool;
        }

        @Override
        public boolean isMainThread() {
            return Thread.currentThread() == mainThread;
        }

        @Override
        public Cancellable scheduleLater(long delayMs, Runnable task) {
            long delayTicks = Math.max(1, delayMs / MS_PER_TICK);
            TickTask tt = new TickTask(task, currentTick.get() + delayTicks, 0);
            pendingTasks.add(tt);
            return tt;
        }

        @Override
        public Cancellable scheduleRepeat(long delayMs, long periodMs, Runnable task) {
            long delayTicks = Math.max(1, delayMs / MS_PER_TICK);
            long periodTicks = Math.max(1, periodMs / MS_PER_TICK);
            TickTask tt = new TickTask(task, currentTick.get() + delayTicks, periodTicks);
            pendingTasks.add(tt);
            return tt;
        }

        /** 单个调度任务 */
        static class TickTask implements Cancellable {
            final Runnable runnable;
            volatile long nextFireTick;
            final long periodTicks;
            volatile boolean cancelled = false;

            TickTask(Runnable runnable, long nextFireTick, long periodTicks) {
                this.runnable = runnable;
                this.nextFireTick = nextFireTick;
                this.periodTicks = periodTicks;
            }

            @Override
            public void cancel() { cancelled = true; }

            @Override
            public boolean isCancelled() { return cancelled; }
        }
    }

    // ============================================================
    //  schedule() 测试
    // ============================================================

    @Nested
    @DisplayName("schedule() — 延迟调度")
    class ScheduleTests {

        @Test
        @DisplayName("schedule 返回 Task 类型")
        void scheduleReturnsTask() {
            NovaValue result = interpreter.evalRepl(
                "val t = schedule(50) { }\n" +
                "typeof(t)"
            );
            assertEquals("Task", result.toString());
        }

        @Test
        @DisplayName("schedule 延迟后执行 block")
        void scheduleExecutesAfterDelay() throws Exception {
            AtomicBoolean executed = new AtomicBoolean(false);
            interpreter.getGlobals().redefine("markExecuted",
                new NovaNativeFunction("markExecuted", 0, (interp, args) -> {
                    executed.set(true);
                    return NovaNull.UNIT;
                }), false);

            interpreter.evalRepl("schedule(50) { markExecuted() }");

            assertThat(executed.get()).isFalse();

            scheduler.waitForTicks(4);

            assertThat(executed.get()).isTrue();
        }

        @Test
        @DisplayName("schedule 返回的 Task 可以 cancel")
        void scheduleCancelPreventsExecution() throws Exception {
            AtomicBoolean executed = new AtomicBoolean(false);
            interpreter.getGlobals().redefine("markExecuted2",
                new NovaNativeFunction("markExecuted2", 0, (interp, args) -> {
                    executed.set(true);
                    return NovaNull.UNIT;
                }), false);

            interpreter.evalRepl(
                "val task = schedule(200) { markExecuted2() }\n" +
                "task.cancel()"
            );

            scheduler.waitForTicks(8);

            assertThat(executed.get()).isFalse();
        }

        @Test
        @DisplayName("Task.isCancelled 反映取消状态")
        void taskIsCancelledReflectsState() {
            NovaValue before = interpreter.evalRepl(
                "val task = schedule(5000) { }\n" +
                "task.isCancelled"
            );
            assertThat(before.toString()).isEqualTo("false");

            NovaValue after = interpreter.evalRepl(
                "task.cancel()\n" +
                "task.isCancelled"
            );
            assertThat(after.toString()).isEqualTo("true");
        }
    }

    // ============================================================
    //  scheduleRepeat() 测试
    // ============================================================

    @Nested
    @DisplayName("scheduleRepeat() — 重复调度")
    class ScheduleRepeatTests {

        @Test
        @DisplayName("scheduleRepeat 多次执行 block")
        void repeatExecutesMultipleTimes() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);
            interpreter.getGlobals().redefine("incrementCounter",
                new NovaNativeFunction("incrementCounter", 0, (interp, args) -> {
                    counter.incrementAndGet();
                    return NovaNull.UNIT;
                }), false);

            interpreter.evalRepl(
                "val task = scheduleRepeat(0, 50) { incrementCounter() }"
            );

            scheduler.waitForTicks(8);

            assertThat(counter.get()).isGreaterThanOrEqualTo(3);
        }

        @Test
        @DisplayName("scheduleRepeat cancel 后停止执行")
        void repeatStopsAfterCancel() throws Exception {
            AtomicInteger counter = new AtomicInteger(0);
            interpreter.getGlobals().redefine("incrementCounter2",
                new NovaNativeFunction("incrementCounter2", 0, (interp, args) -> {
                    counter.incrementAndGet();
                    return NovaNull.UNIT;
                }), false);

            interpreter.evalRepl(
                "val task = scheduleRepeat(0, 50) { incrementCounter2() }"
            );

            scheduler.waitForTicks(4);
            int countAtCancel = counter.get();
            assertThat(countAtCancel).isGreaterThanOrEqualTo(1);

            interpreter.evalRepl("task.cancel()");

            scheduler.waitForTicks(6);
            // cancel 后计数不再增长（允许 +1 容差，取消时可能正好在执行）
            assertThat(counter.get()).isLessThanOrEqualTo(countAtCancel + 1);
        }
    }

    // ============================================================
    //  delay() 主线程安全检查
    // ============================================================

    @Nested
    @DisplayName("delay() 主线程安全")
    class DelayMainThreadTests {

        @Test
        @DisplayName("非主线程调用 delay 正常工作")
        void delayWorksOnNonMainThread() {
            assertDoesNotThrow(() -> interpreter.evalRepl("delay(1)"));
        }

        @Test
        @DisplayName("主线程调用 delay 抛异常")
        void delayThrowsOnMainThread() throws Exception {
            CompletableFuture<String> future = new CompletableFuture<>();

            scheduler.mainExecutor().execute(() -> {
                try {
                    Interpreter child = new Interpreter(interpreter);
                    child.setReplMode(true);
                    child.evalRepl("import nova.concurrent.*");
                    child.evalRepl("delay(100)");
                    future.complete("no error");
                } catch (NovaRuntimeException e) {
                    future.complete(e.getMessage());
                } catch (Exception e) {
                    future.complete("unexpected: " + e.getMessage());
                }
            });

            String msg = future.get(3, TimeUnit.SECONDS);
            assertThat(msg).contains("Cannot call delay()");
            assertThat(msg).contains("main thread");
        }
    }

    // ============================================================
    //  Dispatchers.Main 注入
    // ============================================================

    @Nested
    @DisplayName("Dispatchers.Main 注入")
    class DispatchersMainTests {

        @Test
        @DisplayName("设置 scheduler 后 Dispatchers.Main 可用")
        void dispatchersMainAvailable() {
            NovaValue result = interpreter.evalRepl("Dispatchers[\"Main\"]");
            assertNotNull(result);
            assertThat(result.isNull()).isFalse();
        }

        @Test
        @DisplayName("未设置 scheduler 时 Dispatchers 无 Main")
        void dispatchersMainUnavailableWithoutScheduler() {
            Interpreter clean = new Interpreter();
            clean.setReplMode(true);
            NovaValue result = clean.evalRepl("Dispatchers[\"Main\"]");
            assertTrue(result == null || result.isNull());
        }
    }

    // ============================================================
    //  未配置 scheduler 时的错误提示
    // ============================================================

    @Nested
    @DisplayName("无 scheduler 时的错误")
    class NoSchedulerTests {

        @Test
        @DisplayName("未配置 scheduler 时 schedule 抛异常")
        void scheduleWithoutSchedulerThrows() {
            Interpreter clean = new Interpreter();
            clean.setReplMode(true);
            assertThatThrownBy(() -> clean.evalRepl("schedule(100) { }"))
                .isInstanceOf(NovaRuntimeException.class)
                .hasMessageContaining("No scheduler configured");
        }

        @Test
        @DisplayName("未配置 scheduler 时 scheduleRepeat 抛异常")
        void scheduleRepeatWithoutSchedulerThrows() {
            Interpreter clean = new Interpreter();
            clean.setReplMode(true);
            assertThatThrownBy(() -> clean.evalRepl("scheduleRepeat(100, 50) { }"))
                .isInstanceOf(NovaRuntimeException.class)
                .hasMessageContaining("No scheduler configured");
        }
    }

    // ============================================================
    //  Task toString
    // ============================================================

    @Test
    @DisplayName("Task toString 显示状态")
    void taskToStringShowsState() {
        NovaValue active = interpreter.evalRepl(
            "val t = schedule(5000) { }\n" +
            "\"$t\""
        );
        assertThat(active.toString()).isEqualTo("<task: active>");

        NovaValue cancelled = interpreter.evalRepl(
            "t.cancel()\n" +
            "\"$t\""
        );
        assertThat(cancelled.toString()).isEqualTo("<task: cancelled>");
    }

    // ============================================================
    //  子解释器 scheduler 传播
    // ============================================================

    @Test
    @DisplayName("子解释器继承 scheduler")
    void childInterpreterInheritsScheduler() {
        Interpreter child = new Interpreter(interpreter);
        assertSame(scheduler, child.getScheduler());
    }

    // ============================================================
    //  schedule block 在主线程执行
    // ============================================================

    @Test
    @DisplayName("schedule 的 block 在调度器主线程执行")
    void scheduleBlockRunsOnMainThread() throws Exception {
        AtomicReference<String> threadName = new AtomicReference<>();
        interpreter.getGlobals().redefine("captureThread",
            new NovaNativeFunction("captureThread", 0, (interp, args) -> {
                threadName.set(Thread.currentThread().getName());
                return NovaNull.UNIT;
            }), false);

        interpreter.evalRepl("schedule(50) { captureThread() }");

        scheduler.waitForTicks(4);

        assertThat(threadName.get()).isEqualTo("MockBukkit-MainThread");
    }

    // ============================================================
    //  scope { } 测试
    // ============================================================

    @Nested
    @DisplayName("scope { } — 异步执行")
    class ScopeTests {

        @Test
        @DisplayName("scope 在非主线程执行 block")
        void scopeRunsOnNonMainThread() {
            AtomicReference<String> threadName = new AtomicReference<>();
            interpreter.getGlobals().redefine("captureThreadName",
                new NovaNativeFunction("captureThreadName", 0, (interp, args) -> {
                    threadName.set(Thread.currentThread().getName());
                    return NovaNull.UNIT;
                }), false);

            interpreter.evalRepl("scope { captureThreadName() }");

            assertThat(threadName.get()).isNotEqualTo("MockBukkit-MainThread");
            assertThat(threadName.get()).startsWith("MockBukkit-AsyncThread");
        }

        @Test
        @DisplayName("scope 返回 block 的返回值")
        void scopeReturnsBlockResult() {
            NovaValue result = interpreter.evalRepl("scope { 42 }");
            assertThat(result.asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("scope 内可使用 delay")
        void scopeAllowsDelay() {
            assertDoesNotThrow(() ->
                interpreter.evalRepl("scope { delay(1) }"));
        }

        @Test
        @DisplayName("scope 内异常传播到调用者")
        void scopePropagatesException() {
            assertThatThrownBy(() -> interpreter.evalRepl("scope { error(\"boom\") }"))
                .isInstanceOf(NovaRuntimeException.class)
                .hasMessageContaining("boom");
        }

        @Test
        @DisplayName("scope 内 async + await 并行执行")
        void scopeAsyncAwait() {
            NovaValue result = interpreter.evalRepl(
                "scope {\n" +
                "    val f1 = async { 10 }\n" +
                "    val f2 = async { 20 }\n" +
                "    f1.await() + f2.await()\n" +
                "}"
            );
            assertThat(result.asInt()).isEqualTo(30);
        }

        @Test
        @DisplayName("scope 内 async + await + sync 组合")
        void scopeAsyncAwaitSync() {
            AtomicReference<String> applyThread = new AtomicReference<>();
            interpreter.getGlobals().redefine("captureApplyThread",
                new NovaNativeFunction("captureApplyThread", 1, (interp, args) -> {
                    applyThread.set(Thread.currentThread().getName());
                    return args.get(0);
                }), false);

            NovaValue result = interpreter.evalRepl(
                "scope {\n" +
                "    val f1 = async { 10 }\n" +
                "    val f2 = async { 20 }\n" +
                "    val sum = f1.await() + f2.await()\n" +
                "    sync { captureApplyThread(sum) }\n" +
                "}"
            );
            assertThat(result.asInt()).isEqualTo(30);
            assertThat(applyThread.get()).isEqualTo("MockBukkit-MainThread");
        }

        @Test
        @DisplayName("async 返回的 Future 支持 await 方法")
        void futureSupportsAwait() {
            NovaValue result = interpreter.evalRepl(
                "val f = async { 42 }\n" +
                "f.await()"
            );
            assertThat(result.asInt()).isEqualTo(42);
        }
    }

    // ============================================================
    //  sync { } 测试
    // ============================================================

    @Nested
    @DisplayName("sync { } — 主线程执行")
    class SyncTests {

        @Test
        @DisplayName("sync 在主线程执行 block")
        void syncRunsOnMainThread() {
            AtomicReference<String> threadName = new AtomicReference<>();
            interpreter.getGlobals().redefine("captureThreadSync",
                new NovaNativeFunction("captureThreadSync", 0, (interp, args) -> {
                    threadName.set(Thread.currentThread().getName());
                    return NovaNull.UNIT;
                }), false);

            interpreter.evalRepl("scope { sync { captureThreadSync() } }");

            assertThat(threadName.get()).isEqualTo("MockBukkit-MainThread");
        }

        @Test
        @DisplayName("sync 返回 block 的返回值")
        void syncReturnsBlockResult() {
            NovaValue result = interpreter.evalRepl(
                "scope { sync { 99 } }");
            assertThat(result.asInt()).isEqualTo(99);
        }

        @Test
        @DisplayName("未配置 scheduler 时 sync 抛异常")
        void syncWithoutSchedulerThrows() {
            Interpreter clean = new Interpreter();
            clean.setReplMode(true);
            assertThatThrownBy(() -> clean.evalRepl("sync { 1 }"))
                .isInstanceOf(NovaRuntimeException.class)
                .hasMessageContaining("No scheduler configured");
        }

        @Test
        @DisplayName("scope + sync 组合：异步加载后主线程应用")
        void scopeSyncCombo() {
            AtomicReference<String> loadThread = new AtomicReference<>();
            AtomicReference<String> applyThread = new AtomicReference<>();

            interpreter.getGlobals().redefine("captureLoad",
                new NovaNativeFunction("captureLoad", 0, (interp, args) -> {
                    loadThread.set(Thread.currentThread().getName());
                    return NovaString.of("data");
                }), false);

            interpreter.getGlobals().redefine("captureApply",
                new NovaNativeFunction("captureApply", 1, (interp, args) -> {
                    applyThread.set(Thread.currentThread().getName());
                    return args.get(0);
                }), false);

            NovaValue result = interpreter.evalRepl(
                "scope {\n" +
                "    val data = captureLoad()\n" +
                "    sync { captureApply(data) }\n" +
                "}"
            );

            assertThat(loadThread.get()).startsWith("MockBukkit-AsyncThread");
            assertThat(applyThread.get()).isEqualTo("MockBukkit-MainThread");
            assertThat(result.toString()).isEqualTo("data");
        }
    }
}
