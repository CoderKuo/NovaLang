package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 结构化并发双路径集成测试：同时验证解释器和编译器路径。
 * 覆盖正常值、异常值、边缘值。
 */
@DisplayName("结构化并发 双路径测试")
class StructuredConcurrencyCodegenTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private NovaValue interp(String code) {
        return new Interpreter().eval(code, "test.nova");
    }

    private Object compile(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> c = loaded.get("Test");
        assertNotNull(c, "编译后应生成 Test 类");
        Object inst = c.getField("INSTANCE").get(null);
        Method m = c.getDeclaredMethod("run");
        m.setAccessible(true);
        return m.invoke(inst);
    }

    private String wrap(String body) {
        return "object Test {\n  fun run(): Any {\n" + body + "\n  }\n}";
    }

    private void dual(String interpCode, String compileCode, Object expected) throws Exception {
        NovaValue ir = interp(interpCode);
        Object cr = compile(compileCode);
        if (expected instanceof Integer) {
            assertEquals(expected, ir.asInt(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Boolean) {
            assertEquals(expected, ir.asBoolean(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else {
            assertEquals(String.valueOf(expected), ir.asString(), "解释器");
            assertEquals(String.valueOf(expected), String.valueOf(cr), "编译器");
        }
    }

    /**
     * 断言解释器和编译器都抛出异常。
     */
    private void dualThrows(String interpCode, String compileCode) {
        assertThrows(Exception.class, () -> interp(interpCode), "解释器应抛异常");
        assertThrows(Exception.class, () -> compile(compileCode), "编译器应抛异常");
    }

    // ============================================================
    //  coroutineScope 基础 — 正常值
    // ============================================================

    @Nested
    @DisplayName("coroutineScope 正常值")
    class CoroutineScopeNormalTests {

        @Test
        @DisplayName("coroutineScope 返回常量")
        void testScopeReturnsConstant() throws Exception {
            dual(
                "coroutineScope { 42 }",
                wrap("return coroutineScope { 42 }"),
                42
            );
        }

        @Test
        @DisplayName("coroutineScope 内 async + await 求和")
        void testScopeAsyncAwaitSum() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val a = s.async { 10 }\n" +
                "    val b = s.async { 20 }\n" +
                "    a.get() + b.get()\n" +
                "}";
            dual(body, wrap("return " + body), 30);
        }

        @Test
        @DisplayName("coroutineScope 返回字符串拼接")
        void testScopeStringConcat() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val a = s.async { \"hello\" }\n" +
                "    val b = s.async { \" world\" }\n" +
                "    a.get() + b.get()\n" +
                "}";
            dual(body, wrap("return " + body), "hello world");
        }

        @Test
        @DisplayName("coroutineScope 内调用函数")
        void testScopeWithFunction() throws Exception {
            String pre = "fun double(x: Int) = x * 2\n";
            String body =
                "coroutineScope { s ->\n" +
                "    val d = s.async { double(21) }\n" +
                "    d.get()\n" +
                "}";
            dual(
                pre + body,
                pre + wrap("return " + body),
                42
            );
        }

        @Test
        @DisplayName("coroutineScope 多个 async")
        void testScopeMultipleAsync() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val a = s.async { 1 }\n" +
                "    val b = s.async { 2 }\n" +
                "    val c = s.async { 3 }\n" +
                "    a.get() + b.get() + c.get()\n" +
                "}";
            dual(body, wrap("return " + body), 6);
        }

        @Test
        @DisplayName("coroutineScope 闭包捕获外部变量")
        void testScopeAsyncClosure() throws Exception {
            String pre = "val x = 100\n";
            String body =
                "coroutineScope { s ->\n" +
                "    val d = s.async { x + 1 }\n" +
                "    d.get()\n" +
                "}";
            dual(
                pre + body,
                wrap("val x = 100\n    return " + body),
                101
            );
        }
    }

    // ============================================================
    //  launch 正常值
    // ============================================================

    @Nested
    @DisplayName("launch 正常值")
    class LaunchNormalTests {

        @Test
        @DisplayName("launch + join 完成")
        void testLaunchJoinCompleted() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 1 + 1 }\n" +
                "    j.join()\n" +
                "    j.isCompleted\n" +
                "}";
            dual(body, wrap("return " + body), true);
        }

        @Test
        @DisplayName("launch join 后 isActive 为 false")
        void testLaunchIsActiveAfterJoin() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 1 }\n" +
                "    j.join()\n" +
                "    j.isActive\n" +
                "}";
            dual(body, wrap("return " + body), false);
        }
    }

    // ============================================================
    //  supervisorScope 正常值
    // ============================================================

    @Nested
    @DisplayName("supervisorScope 正常值")
    class SupervisorScopeNormalTests {

        @Test
        @DisplayName("supervisorScope 返回常量")
        void testSupervisorReturnsConstant() throws Exception {
            dual(
                "supervisorScope { 99 }",
                wrap("return supervisorScope { 99 }"),
                99
            );
        }

        @Test
        @DisplayName("supervisorScope async + await")
        void testSupervisorAsyncAwait() throws Exception {
            String body =
                "supervisorScope { s ->\n" +
                "    val d = s.async { 50 }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), 50);
        }

        @Test
        @DisplayName("supervisorScope 子任务失败不影响兄弟")
        void testSupervisorIsolation() throws Exception {
            String body =
                "supervisorScope { s ->\n" +
                "    s.launch { throw \"fail\" }\n" +
                "    val d = s.async { 42 }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), 42);
        }
    }

    // ============================================================
    //  Dispatchers 正常值
    // ============================================================

    @Nested
    @DisplayName("Dispatchers 正常值")
    class DispatcherNormalTests {

        @Test
        @DisplayName("Dispatchers.IO")
        void testDispatchersIO() throws Exception {
            String body =
                "coroutineScope(Dispatchers.IO) { s ->\n" +
                "    val d = s.async { 42 }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), 42);
        }

        @Test
        @DisplayName("Dispatchers.Default")
        void testDispatchersDefault() throws Exception {
            String body =
                "coroutineScope(Dispatchers.Default) { s ->\n" +
                "    val d = s.async { 100 }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), 100);
        }

        @Test
        @DisplayName("Dispatchers.Unconfined")
        void testDispatchersUnconfined() throws Exception {
            String body =
                "coroutineScope(Dispatchers.Unconfined) { s ->\n" +
                "    val d = s.async { 7 * 6 }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), 42);
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
        void testScopeNoChildren() throws Exception {
            dual(
                "coroutineScope { 0 }",
                wrap("return coroutineScope { 0 }"),
                0
            );
        }

        @Test
        @DisplayName("async 返回布尔 false")
        void testAsyncReturnsFalse() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val d = s.async { false }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), false);
        }

        @Test
        @DisplayName("async 返回负数")
        void testAsyncReturnsNegative() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val d = s.async { -1 }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), -1);
        }

        @Test
        @DisplayName("async 返回空字符串")
        void testAsyncReturnsEmptyString() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val d = s.async { \"\" }\n" +
                "    d.get()\n" +
                "}";
            dual(body, wrap("return " + body), "");
        }

        @Test
        @DisplayName("嵌套 coroutineScope")
        void testNestedScope() throws Exception {
            String body =
                "coroutineScope { outer ->\n" +
                "    val inner = coroutineScope { inn ->\n" +
                "        val d1 = inn.async { 10 }\n" +
                "        d1.get()\n" +
                "    }\n" +
                "    val d2 = outer.async { inner + 20 }\n" +
                "    d2.get()\n" +
                "}";
            dual(body, wrap("return " + body), 30);
        }

        @Test
        @DisplayName("coroutineScope 内嵌 supervisorScope")
        void testCoroutineNestedSupervisor() throws Exception {
            String body =
                "coroutineScope { outer ->\n" +
                "    supervisorScope { inner ->\n" +
                "        inner.launch { throw \"ignored\" }\n" +
                "        val d = inner.async { 42 }\n" +
                "        d.get()\n" +
                "    }\n" +
                "}";
            dual(body, wrap("return " + body), 42);
        }
    }

    // ============================================================
    //  异常值
    // ============================================================

    @Nested
    @DisplayName("异常值")
    class ExceptionTests {

        @Test
        @DisplayName("coroutineScope 子任务异常传播 — 解释器")
        void testChildExceptionInterpreter() {
            assertThrows(Exception.class, () -> interp(
                "coroutineScope { s ->\n" +
                "    s.async { throw \"child error\" }\n" +
                "    s.async { 42 }\n" +
                "    \"done\"\n" +
                "}"
            ));
        }

        @Test
        @DisplayName("coroutineScope 子任务异常传播 — 编译器")
        void testChildExceptionCompiler() {
            assertThrows(Exception.class, () -> compile(wrap(
                "return coroutineScope { s ->\n" +
                "    s.async { throw \"child error\" }\n" +
                "    s.async { 42 }\n" +
                "    \"done\"\n" +
                "}"
            )));
        }

        @Test
        @DisplayName("await 失败的 Deferred — 解释器")
        void testAwaitFailedInterpreter() {
            assertThrows(Exception.class, () -> interp(
                "coroutineScope { s ->\n" +
                "    val d = s.async { throw \"fail\" }\n" +
                "    d.get()\n" +
                "}"
            ));
        }

        @Test
        @DisplayName("await 失败的 Deferred — 编译器")
        void testAwaitFailedCompiler() {
            assertThrows(Exception.class, () -> compile(wrap(
                "return coroutineScope { s ->\n" +
                "    val d = s.async { throw \"fail\" }\n" +
                "    d.get()\n" +
                "}"
            )));
        }

        @Test
        @DisplayName("supervisorScope 中 await 失败的 Deferred 仍抛异常 — 解释器")
        void testSupervisorAwaitFailedInterpreter() {
            assertThrows(Exception.class, () -> interp(
                "supervisorScope { s ->\n" +
                "    val d = s.async { throw \"fail\" }\n" +
                "    d.get()\n" +
                "}"
            ));
        }

        @Test
        @DisplayName("supervisorScope 中 await 失败的 Deferred 仍抛异常 — 编译器")
        void testSupervisorAwaitFailedCompiler() {
            assertThrows(Exception.class, () -> compile(wrap(
                "return supervisorScope { s ->\n" +
                "    val d = s.async { throw \"fail\" }\n" +
                "    d.get()\n" +
                "}"
            )));
        }
    }

    // ============================================================
    //  类型检查
    // ============================================================

    @Nested
    @DisplayName("类型检查")
    class TypeCheckTests {

        @Test
        @DisplayName("Scope 的 typeof")
        void testScopeTypeof() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    typeof(s)\n" +
                "}";
            dual(body, wrap("return " + body), "Scope");
        }

        @Test
        @DisplayName("Deferred 的 typeof")
        void testDeferredTypeof() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val d = s.async { 1 }\n" +
                "    typeof(d)\n" +
                "}";
            dual(body, wrap("return " + body), "Deferred");
        }

        @Test
        @DisplayName("Job 的 typeof")
        void testJobTypeof() throws Exception {
            String body =
                "coroutineScope { s ->\n" +
                "    val j = s.launch { 1 }\n" +
                "    typeof(j)\n" +
                "}";
            dual(body, wrap("return " + body), "Job");
        }
    }

    // ============================================================
    //  与旧 async/Future 兼容
    // ============================================================

    @Nested
    @DisplayName("与旧 async 兼容")
    class BackwardCompatibilityTests {

        @Test
        @DisplayName("独立 async 仍返回 Future — 解释器")
        void testStandaloneAsyncReturnsFuture() {
            // typeof 仅解释器支持
            NovaValue r = interp("typeof(async { 42 })");
            assertEquals("Future", r.asString());
        }

        @Test
        @DisplayName("独立 async + await 正常工作")
        void testStandaloneAsyncAwait() throws Exception {
            String pre = "val f = async { 10 + 20 }\n";
            dual(
                pre + "await f",
                wrap("val f = async { 10 + 20 }\n    return await f"),
                30
            );
        }
    }
}
