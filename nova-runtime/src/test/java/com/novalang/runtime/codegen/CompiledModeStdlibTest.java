package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式下 StdlibTestCompiled（nova.test 模块）完整测试。
 *
 * <p>覆盖 test/runTests/testGroup 流程以及所有断言函数。</p>
 */
@DisplayName("编译模式: StdlibTestCompiled 测试框架")
class CompiledModeStdlibTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    private Object compileAndRun(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module, "Should generate $Module class");
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);
        NovaScriptContext.init(new HashMap<>());
        try {
            return main.invoke(null);
        } finally {
            NovaScriptContext.clear();
        }
    }

    /** 期望编译后执行时抛出异常 */
    private void expectFailure(String code) {
        assertThrows(InvocationTargetException.class, () -> compileAndRun(code),
                "Expected assertion failure");
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> compileAndRunTests(String code) throws Exception {
        Object result = compileAndRun(code);
        assertInstanceOf(Map.class, result, "runTests() should return a Map");
        return (Map<Object, Object>) result;
    }

    // ================================================================
    // 1. test / runTests 流程
    // ================================================================

    @Nested
    @DisplayName("test/runTests 流程")
    class TestRunTestsFlow {

        @Test
        @DisplayName("全部通过 — passed/failed/total 正确")
        void testAllPass() throws Exception {
            String code = "import nova.test.*\n" +
                    "test(\"add\") { assertEqual(2, 1 + 1) }\n" +
                    "test(\"true\") { assertTrue(true) }\n" +
                    "test(\"not null\") { assertNotNull(42) }\n" +
                    "runTests()";
            Map<Object, Object> result = compileAndRunTests(code);
            assertEquals(3, result.get("passed"));
            assertEquals(0, result.get("failed"));
            assertEquals(3, result.get("total"));
        }

        @Test
        @DisplayName("有失败用例 — failed 计数正确")
        void testWithFailures() throws Exception {
            String code = "import nova.test.*\n" +
                    "test(\"ok\") { assertEqual(1, 1) }\n" +
                    "test(\"fail\") { assertEqual(1, 2) }\n" +
                    "test(\"also fail\") { assertTrue(false) }\n" +
                    "runTests()";
            Map<Object, Object> result = compileAndRunTests(code);
            assertEquals(1, result.get("passed"));
            assertEquals(2, result.get("failed"));
            assertEquals(3, result.get("total"));
        }

        @Test
        @DisplayName("无测试用例 — 全零")
        void testNoTests() throws Exception {
            String code = "import nova.test.*\n" +
                    "runTests()";
            Map<Object, Object> result = compileAndRunTests(code);
            assertEquals(0, result.get("passed"));
            assertEquals(0, result.get("failed"));
            assertEquals(0, result.get("total"));
        }
    }

    // ================================================================
    // 2. testGroup — 分组嵌套
    // ================================================================

    @Nested
    @DisplayName("testGroup 分组")
    class TestGroupTests {

        @Test
        @DisplayName("testGroup 内注册的用例全部通过")
        void testGroupAllPass() throws Exception {
            String code = "import nova.test.*\n" +
                    "testGroup(\"math\") {\n" +
                    "  test(\"addition\") { assertEqual(3, 1 + 2) }\n" +
                    "  test(\"multiply\") { assertEqual(6, 2 * 3) }\n" +
                    "}\n" +
                    "runTests()";
            Map<Object, Object> result = compileAndRunTests(code);
            assertEquals(2, result.get("passed"));
            assertEquals(0, result.get("failed"));
            assertEquals(2, result.get("total"));
        }

        @Test
        @DisplayName("testGroup 与顶层 test 混合")
        void testGroupMixed() throws Exception {
            String code = "import nova.test.*\n" +
                    "test(\"top\") { assertTrue(true) }\n" +
                    "testGroup(\"group\") {\n" +
                    "  test(\"sub\") { assertEqual(1, 1) }\n" +
                    "}\n" +
                    "runTests()";
            Map<Object, Object> result = compileAndRunTests(code);
            assertEquals(2, result.get("passed"));
            assertEquals(0, result.get("failed"));
        }

        @Test
        @DisplayName("testGroup 内有失败用例")
        void testGroupWithFailure() throws Exception {
            String code = "import nova.test.*\n" +
                    "testGroup(\"strings\") {\n" +
                    "  test(\"equal\") { assertEqual(\"a\", \"a\") }\n" +
                    "  test(\"mismatch\") { assertEqual(\"a\", \"b\") }\n" +
                    "}\n" +
                    "runTests()";
            Map<Object, Object> result = compileAndRunTests(code);
            assertEquals(1, result.get("passed"));
            assertEquals(1, result.get("failed"));
        }
    }

    // ================================================================
    // 3. assertEqual
    // ================================================================

    @Nested
    @DisplayName("assertEqual")
    class AssertEqualTests {

        @Test
        @DisplayName("整数相等 — 通过")
        void testIntEqual() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertEqual(42, 42)\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("字符串相等 — 通过")
        void testStringEqual() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertEqual(\"hello\", \"hello\")\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("不等 — 抛异常")
        void testNotEqual() {
            String code = "import nova.test.*\n" +
                    "assertEqual(1, 2)";
            expectFailure(code);
        }

        @Test
        @DisplayName("不同类型值不等 — 抛异常")
        void testDifferentTypes() {
            String code = "import nova.test.*\n" +
                    "assertEqual(\"1\", 1)";
            expectFailure(code);
        }
    }

    // ================================================================
    // 4. assertNotEqual
    // ================================================================

    @Nested
    @DisplayName("assertNotEqual")
    class AssertNotEqualTests {

        @Test
        @DisplayName("不等 — 通过")
        void testDifferentValues() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertNotEqual(1, 2)\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("字符串不等 — 通过")
        void testStringNotEqual() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertNotEqual(\"a\", \"b\")\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("相等 — 抛异常")
        void testEqualValues() {
            String code = "import nova.test.*\n" +
                    "assertNotEqual(42, 42)";
            expectFailure(code);
        }
    }

    // ================================================================
    // 5. assertTrue / assertFalse
    // ================================================================

    @Nested
    @DisplayName("assertTrue / assertFalse")
    class AssertTrueFalseTests {

        @Test
        @DisplayName("assertTrue(true) — 通过")
        void testTruePass() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertTrue(1 > 0)\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("assertTrue(false) — 抛异常")
        void testTrueFail() {
            String code = "import nova.test.*\n" +
                    "assertTrue(1 > 2)";
            expectFailure(code);
        }

        @Test
        @DisplayName("assertFalse(false) — 通过")
        void testFalsePass() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertFalse(1 > 2)\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("assertFalse(true) — 抛异常")
        void testFalseFail() {
            String code = "import nova.test.*\n" +
                    "assertFalse(1 < 2)";
            expectFailure(code);
        }
    }

    // ================================================================
    // 6. assertNull
    // ================================================================

    @Nested
    @DisplayName("assertNull")
    class AssertNullTests {

        @Test
        @DisplayName("assertNull(null) — 通过")
        void testNullPass() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertNull(null)\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("assertNull(非null) — 抛异常")
        void testNullFail() {
            String code = "import nova.test.*\n" +
                    "assertNull(42)";
            expectFailure(code);
        }

        @Test
        @DisplayName("assertNull(字符串) — 抛异常")
        void testNullStringFail() {
            String code = "import nova.test.*\n" +
                    "assertNull(\"not null\")";
            expectFailure(code);
        }
    }

    // ================================================================
    // 7. assertThrows
    // ================================================================

    @Nested
    @DisplayName("assertThrows")
    class AssertThrowsTests {

        @Test
        @DisplayName("块内抛异常 — 通过")
        void testThrowsCaught() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertThrows { error(\"boom\") }\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("块内不抛异常 — 抛异常")
        void testNoThrow() {
            String code = "import nova.test.*\n" +
                    "assertThrows { 1 + 1 }";
            expectFailure(code);
        }
    }

    // ================================================================
    // 8. assertContains
    // ================================================================

    @Nested
    @DisplayName("assertContains")
    class AssertContainsTests {

        @Test
        @DisplayName("列表包含元素 — 通过")
        void testListContains() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertContains([1, 2, 3], 2)\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("列表不包含元素 — 抛异常")
        void testListNotContains() {
            String code = "import nova.test.*\n" +
                    "assertContains([1, 2, 3], 99)";
            expectFailure(code);
        }

        @Test
        @DisplayName("字符串包含子串 — 通过")
        void testStringContains() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertContains(\"hello world\", \"world\")\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("字符串不包含子串 — 抛异常")
        void testStringNotContains() {
            String code = "import nova.test.*\n" +
                    "assertContains(\"hello\", \"xyz\")";
            expectFailure(code);
        }
    }

    // ================================================================
    // 9. assertFails
    // ================================================================

    @Nested
    @DisplayName("assertFails")
    class AssertFailsTests {

        @Test
        @DisplayName("块内抛异常 — 通过")
        void testFailsCaught() throws Exception {
            String code = "import nova.test.*\n" +
                    "assertFails { error(\"boom\") }\n" +
                    "\"ok\"";
            assertEquals("ok", String.valueOf(compileAndRun(code)));
        }

        @Test
        @DisplayName("块内不抛异常 — 抛异常")
        void testNoFail() {
            String code = "import nova.test.*\n" +
                    "assertFails { 1 + 1 }";
            expectFailure(code);
        }
    }
}
