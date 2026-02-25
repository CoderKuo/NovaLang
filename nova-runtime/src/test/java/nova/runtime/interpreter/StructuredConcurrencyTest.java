package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化并发集成测试：coroutineScope / supervisorScope / async / launch / Dispatchers。
 * 覆盖正常值、异常值、边缘值，同时测试解释器路径。
 */
@DisplayName("结构化并发测试")
class StructuredConcurrencyTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    // ============================================================
    //  coroutineScope 基础
    // ============================================================

    @Nested
    @DisplayName("coroutineScope 基础")
    class CoroutineScopeBasicTests {

        @Test
        @DisplayName("coroutineScope 返回 block 最后一个表达式")
        void testScopeReturnsLastExpr() {
            NovaValue result = interpreter.evalRepl(
                "val r = coroutineScope { 42 }\n" +
                "r"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 内 async + await")
        void testScopeAsyncAwait() {
            NovaValue result = interpreter.evalRepl(
                "val r = coroutineScope { s ->\n" +
                "    val a = s.async { 10 }\n" +
                "    val b = s.async { 20 }\n" +
                "    a.get() + b.get()\n" +
                "}\n" +
                "r"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 内 async + .await() 方法")
        void testScopeAsyncDotAwait() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val a = s.async { 10 }\n" +
                "    val b = s.async { 20 }\n" +
                "    a.await() + b.await()\n" +
                "}"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 内 async 使用闭包变量")
        void testScopeAsyncClosure() {
            NovaValue result = interpreter.evalRepl(
                "val x = 100\n" +
                "val r = coroutineScope { s ->\n" +
                "    val a = s.async { x + 1 }\n" +
                "    a.get()\n" +
                "}\n" +
                "r"
            );
            assertEquals(101, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 内多个 async 并发执行")
        void testScopeMultipleAsync() {
            NovaValue result = interpreter.evalRepl(
                "val r = coroutineScope { s ->\n" +
                "    val a = s.async { 1 }\n" +
                "    val b = s.async { 2 }\n" +
                "    val c = s.async { 3 }\n" +
                "    a.get() + b.get() + c.get()\n" +
                "}\n" +
                "r"
            );
            assertEquals(6, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 返回字符串")
        void testScopeReturnsString() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val a = s.async { \"hello\" }\n" +
                "    val b = s.async { \" world\" }\n" +
                "    a.get() + b.get()\n" +
                "}"
            );
            assertEquals("hello world", result.asString());
        }

        @Test
        @DisplayName("coroutineScope 内调用函数")
        void testScopeCallFunction() {
            NovaValue result = interpreter.evalRepl(
                "fun double(x: Int) = x * 2\n" +
                "coroutineScope { s ->\n" +
                "    val a = s.async { double(21) }\n" +
                "    a.get()\n" +
                "}"
            );
            assertEquals(42, result.asInt());
        }
    }

    // ============================================================
    //  launch 基础
    // ============================================================

    @Nested
    @DisplayName("launch 基础")
    class LaunchBasicTests {

        @Test
        @DisplayName("launch 返回 Job")
        void testLaunchReturnsJob() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 1 + 1 }\n" +
                "    j.join()\n" +
                "    j.isCompleted\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("launch 的 Job isActive 在完成前为 true（join 后为 false）")
        void testJobIsActiveAfterJoin() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 1 }\n" +
                "    j.join()\n" +
                "    j.isActive\n" +
                "}"
            );
            assertFalse(result.asBoolean());
        }

        @Test
        @DisplayName("多个 launch 并发执行")
        void testMultipleLaunch() {
            // launch 不返回值，但应成功完成不报错
            NovaValue result = interpreter.evalRepl(
                "var count = 0\n" +
                "coroutineScope { s ->\n" +
                "    s.launch { 1 }\n" +
                "    s.launch { 2 }\n" +
                "    s.launch { 3 }\n" +
                "    \"done\"\n" +
                "}"
            );
            assertEquals("done", result.asString());
        }
    }

    // ============================================================
    //  Deferred 成员方法
    // ============================================================

    @Nested
    @DisplayName("Deferred 成员方法")
    class DeferredMemberTests {

        @Test
        @DisplayName("Deferred.isDone 完成后为 true")
        void testDeferredIsDone() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { 42 }\n" +
                "    d.get()\n" +
                "    d.isDone\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Deferred.get() 兼容 NovaFuture API")
        void testDeferredGet() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { 99 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("Deferred.cancel() 取消任务")
        void testDeferredCancel() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async {\n" +
                "        var i = 0\n" +
                "        while (i < 10000000) { i = i + 1 }\n" +
                "        i\n" +
                "    }\n" +
                "    d.cancel()\n" +
                "    d.isCancelled || d.isDone\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }
    }

    // ============================================================
    //  Job 成员方法
    // ============================================================

    @Nested
    @DisplayName("Job 成员方法")
    class JobMemberTests {

        @Test
        @DisplayName("Job.cancel() 取消任务")
        void testJobCancel() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val j = s.launch {\n" +
                "        var i = 0\n" +
                "        while (i < 10000000) { i = i + 1 }\n" +
                "    }\n" +
                "    j.cancel()\n" +
                "    j.isCancelled || j.isCompleted\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Job.isCompleted 正常完成")
        void testJobIsCompleted() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 42 }\n" +
                "    j.join()\n" +
                "    j.isCompleted\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }
    }

    // ============================================================
    //  coroutineScope 异常传播
    // ============================================================

    @Nested
    @DisplayName("coroutineScope 异常传播")
    class CoroutineScopeErrorTests {

        @Test
        @DisplayName("子任务异常通过 coroutineScope 传播")
        void testChildExceptionPropagation() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "coroutineScope { s ->\n" +
                    "    s.async { throw \"child error\" }\n" +
                    "    s.async { 42 }\n" +
                    "    \"done\"\n" +
                    "}"
                );
            });
        }

        @Test
        @DisplayName("await 异常子任务抛出错误")
        void testAwaitFailedDeferred() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "coroutineScope { s ->\n" +
                    "    val d = s.async { throw \"fail\" }\n" +
                    "    d.get()\n" +
                    "}"
                );
            });
        }

        @Test
        @DisplayName("launch 子任务异常传播到 coroutineScope")
        void testLaunchExceptionPropagation() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "coroutineScope { s ->\n" +
                    "    s.launch { throw \"launch error\" }\n" +
                    "    \"done\"\n" +
                    "}"
                );
            });
        }

        @Test
        @DisplayName("除零异常通过 async 传播")
        void testDivisionByZeroInAsync() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "coroutineScope { s ->\n" +
                    "    val d = s.async { 1 / 0 }\n" +
                    "    d.get()\n" +
                    "}"
                );
            });
        }
    }

    // ============================================================
    //  supervisorScope 子任务隔离
    // ============================================================

    @Nested
    @DisplayName("supervisorScope 子任务隔离")
    class SupervisorScopeTests {

        @Test
        @DisplayName("supervisorScope 基本返回值")
        void testSupervisorScopeBasic() {
            NovaValue result = interpreter.evalRepl(
                "supervisorScope { 42 }"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("supervisorScope 内 async + await")
        void testSupervisorScopeAsyncAwait() {
            NovaValue result = interpreter.evalRepl(
                "supervisorScope { s ->\n" +
                "    val a = s.async { 10 }\n" +
                "    val b = s.async { 20 }\n" +
                "    a.get() + b.get()\n" +
                "}"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("supervisorScope 子任务失败不影响兄弟")
        void testSupervisorScopeIsolation() {
            // supervisorScope 中一个子任务失败不会导致 scope 抛异常
            NovaValue result = interpreter.evalRepl(
                "supervisorScope { s ->\n" +
                "    s.launch { throw \"fail\" }\n" +
                "    val d = s.async { 42 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("supervisorScope 中 await 失败的 Deferred 仍然抛异常")
        void testSupervisorAwaitFailed() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "supervisorScope { s ->\n" +
                    "    val d = s.async { throw \"fail\" }\n" +
                    "    d.get()\n" +
                    "}"
                );
            });
        }
    }

    // ============================================================
    //  Scope 属性
    // ============================================================

    @Nested
    @DisplayName("Scope 属性")
    class ScopePropertyTests {

        @Test
        @DisplayName("Scope.isActive 在正常执行中为 true")
        void testScopeIsActive() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    s.isActive\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Scope.isCancelled 默认为 false")
        void testScopeIsCancelledDefault() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    s.isCancelled\n" +
                "}"
            );
            assertFalse(result.asBoolean());
        }

        @Test
        @DisplayName("Scope.cancel() 后 isCancelled 为 true")
        void testScopeCancelThenIsCancelled() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    s.cancel()\n" +
                "    s.isCancelled\n" +
                "}"
            );
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("Scope.cancel() 后 isActive 为 false")
        void testScopeCancelThenIsActive() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    s.cancel()\n" +
                "    s.isActive\n" +
                "}"
            );
            assertFalse(result.asBoolean());
        }
    }

    // ============================================================
    //  Dispatchers
    // ============================================================

    @Nested
    @DisplayName("Dispatchers")
    class DispatcherTests {

        @Test
        @DisplayName("使用 Dispatchers.IO")
        void testDispatchersIO() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope(Dispatchers.IO) { s ->\n" +
                "    val d = s.async { 42 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("使用 Dispatchers.Default")
        void testDispatchersDefault() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope(Dispatchers.Default) { s ->\n" +
                "    val d = s.async { 100 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(100, result.asInt());
        }

        @Test
        @DisplayName("使用 Dispatchers.Unconfined 在当前线程执行")
        void testDispatchersUnconfined() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope(Dispatchers.Unconfined) { s ->\n" +
                "    val d = s.async { 7 * 6 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("supervisorScope 带 Dispatcher")
        void testSupervisorScopeWithDispatcher() {
            NovaValue result = interpreter.evalRepl(
                "supervisorScope(Dispatchers.IO) { s ->\n" +
                "    val d = s.async { \"io-result\" }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals("io-result", result.asString());
        }
    }

    // ============================================================
    //  边缘值
    // ============================================================

    @Nested
    @DisplayName("边缘值")
    class EdgeCaseTests {

        @Test
        @DisplayName("coroutineScope 无子任务直接返回")
        void testScopeNoChildren() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { 42 }"
            );
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 返回 null")
        void testScopeReturnsNull() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { null }"
            );
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("async 返回 null")
        void testAsyncReturnsNull() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { null }\n" +
                "    d.get()\n" +
                "}"
            );
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("async 返回空列表")
        void testAsyncReturnsEmptyList() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { [] }\n" +
                "    d.get().size\n" +
                "}"
            );
            assertEquals(0, result.asInt());
        }

        @Test
        @DisplayName("async 返回布尔值 false")
        void testAsyncReturnsFalse() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { false }\n" +
                "    d.get()\n" +
                "}"
            );
            assertFalse(result.asBoolean());
        }

        @Test
        @DisplayName("async 返回 0")
        void testAsyncReturnsZero() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { 0 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(0, result.asInt());
        }

        @Test
        @DisplayName("async 返回负数")
        void testAsyncReturnsNegative() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { -42 }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals(-42, result.asInt());
        }

        @Test
        @DisplayName("async 返回空字符串")
        void testAsyncReturnsEmptyString() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { \"\" }\n" +
                "    d.get()\n" +
                "}"
            );
            assertEquals("", result.asString());
        }

        @Test
        @DisplayName("scope 取消后 async 抛异常")
        void testAsyncAfterCancel() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "coroutineScope { s ->\n" +
                    "    s.cancel()\n" +
                    "    s.async { 42 }\n" +
                    "}"
                );
            });
        }

        @Test
        @DisplayName("scope 取消后 launch 抛异常")
        void testLaunchAfterCancel() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "coroutineScope { s ->\n" +
                    "    s.cancel()\n" +
                    "    s.launch { 42 }\n" +
                    "}"
                );
            });
        }

        @Test
        @DisplayName("嵌套 coroutineScope")
        void testNestedCoroutineScope() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { outer ->\n" +
                "    val r = coroutineScope { inn ->\n" +
                "        val d1 = inn.async { 10 }\n" +
                "        d1.get()\n" +
                "    }\n" +
                "    val d2 = outer.async { r + 20 }\n" +
                "    d2.get()\n" +
                "}"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("coroutineScope 内嵌 supervisorScope")
        void testCoroutineScopeNestedSupervisor() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { outer ->\n" +
                "    supervisorScope { inner ->\n" +
                "        inner.launch { throw \"ignored\" }\n" +
                "        val d = inner.async { 42 }\n" +
                "        d.get()\n" +
                "    }\n" +
                "}"
            );
            assertEquals(42, result.asInt());
        }
    }

    // ============================================================
    //  类型检查
    // ============================================================

    @Nested
    @DisplayName("类型检查")
    class TypeCheckTests {

        @Test
        @DisplayName("Scope typeName 为 Scope")
        void testScopeTypeName() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    typeof(s)\n" +
                "}"
            );
            assertEquals("Scope", result.asString());
        }

        @Test
        @DisplayName("Deferred typeName 为 Deferred")
        void testDeferredTypeName() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val d = s.async { 1 }\n" +
                "    typeof(d)\n" +
                "}"
            );
            assertEquals("Deferred", result.asString());
        }

        @Test
        @DisplayName("Job typeName 为 Job")
        void testJobTypeName() {
            NovaValue result = interpreter.evalRepl(
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 1 }\n" +
                "    typeof(j)\n" +
                "}"
            );
            assertEquals("Job", result.asString());
        }
    }

    // ============================================================
    //  与旧 async/NovaFuture 兼容
    // ============================================================

    @Nested
    @DisplayName("与旧 async 兼容")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("独立 async 仍然返回 Future")
        void testStandaloneAsyncReturnsFuture() {
            NovaValue result = interpreter.evalRepl(
                "val f = async { 42 }\n" +
                "typeof(f)"
            );
            assertEquals("Future", result.asString());
        }

        @Test
        @DisplayName("独立 async + await 仍然工作")
        void testStandaloneAsyncAwait() {
            NovaValue result = interpreter.evalRepl(
                "val f = async { 10 + 20 }\n" +
                "await f"
            );
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("独立 async 与 scope async 可混合使用")
        void testMixStandaloneAndScopeAsync() {
            NovaValue result = interpreter.evalRepl(
                "val standalone = async { 100 }\n" +
                "val scoped = coroutineScope { s ->\n" +
                "    val d = s.async { 200 }\n" +
                "    d.get()\n" +
                "}\n" +
                "(await standalone) + scoped"
            );
            assertEquals(300, result.asInt());
        }
    }
}
