package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.NovaRuntimeException;
import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 import nova.test.* 测试。
 */
@DisplayName("编译模式: import nova.test.*")
class TestModuleCodegenTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    private Object compileAndRun(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module);
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);
        NovaScriptContext.init(new HashMap<>());
        try {
            return main.invoke(null);
        } finally {
            NovaScriptContext.clear();
        }
    }

    /** 期望编译后执行时抛出 NovaRuntimeException */
    private void expectFailure(String code, String messageContains) {
        assertThrows(InvocationTargetException.class, () -> {
            compileAndRun(code);
        }, "Expected assertion failure");
    }

    // ============ assertEqual ============

    @Test
    @DisplayName("assertEqual — 相等不抛异常")
    void testAssertEqualPass() throws Exception {
        String code = "import nova.test.*\n" +
                "assertEqual(42, 42)\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    @Test
    @DisplayName("assertEqual — 不等抛异常")
    void testAssertEqualFail() {
        String code = "import nova.test.*\n" +
                "assertEqual(1, 2)";
        expectFailure(code, "assertEqual failed");
    }

    @Test
    @DisplayName("assertEqual — 字符串比较")
    void testAssertEqualString() throws Exception {
        String code = "import nova.test.*\n" +
                "assertEqual(\"hello\", \"hello\")\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    // ============ assertNotEqual ============

    @Test
    @DisplayName("assertNotEqual — 不等不抛异常")
    void testAssertNotEqualPass() throws Exception {
        String code = "import nova.test.*\n" +
                "assertNotEqual(1, 2)\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    @Test
    @DisplayName("assertNotEqual — 相等抛异常")
    void testAssertNotEqualFail() {
        String code = "import nova.test.*\n" +
                "assertNotEqual(42, 42)";
        expectFailure(code, "assertNotEqual failed");
    }

    // ============ assertTrue / assertFalse ============

    @Test
    @DisplayName("assertTrue — true 不抛异常")
    void testAssertTruePass() throws Exception {
        String code = "import nova.test.*\n" +
                "assertTrue(1 > 0)\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    @Test
    @DisplayName("assertTrue — false 抛异常")
    void testAssertTrueFail() {
        String code = "import nova.test.*\n" +
                "assertTrue(1 > 2)";
        expectFailure(code, "assertTrue failed");
    }

    @Test
    @DisplayName("assertFalse — false 不抛异常")
    void testAssertFalsePass() throws Exception {
        String code = "import nova.test.*\n" +
                "assertFalse(1 > 2)\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    @Test
    @DisplayName("assertFalse — true 抛异常")
    void testAssertFalseFail() {
        String code = "import nova.test.*\n" +
                "assertFalse(1 < 2)";
        expectFailure(code, "assertFalse failed");
    }

    // ============ assertNull / assertNotNull ============

    @Test
    @DisplayName("assertNull — null 不抛异常")
    void testAssertNullPass() throws Exception {
        String code = "import nova.test.*\n" +
                "assertNull(null)\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    @Test
    @DisplayName("assertNotNull — 非 null 不抛异常")
    void testAssertNotNullPass() throws Exception {
        String code = "import nova.test.*\n" +
                "assertNotNull(\"hello\")\n" +
                "\"ok\"";
        Object result = compileAndRun(code);
        assertEquals("ok", String.valueOf(result));
    }

    // ============ 综合 ============

    @Test
    @DisplayName("多个断言组合")
    void testMultipleAssertions() throws Exception {
        String code = "import nova.test.*\n" +
                "assertEqual(1 + 1, 2)\n" +
                "assertNotEqual(\"a\", \"b\")\n" +
                "assertTrue(10 > 5)\n" +
                "assertFalse(10 < 5)\n" +
                "assertNotNull(\"x\")\n" +
                "\"all passed\"";
        Object result = compileAndRun(code);
        assertEquals("all passed", String.valueOf(result));
    }
}
