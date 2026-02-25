package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Java 互操作 UX 集成测试：java.lang 自动导入 + JavaBean 属性语法
 *
 * <p>解释器路径通过 Interpreter.eval() 测试；
 * 编译器路径通过 NovaIrCompiler.compileAndLoad() 测试。</p>
 */
@DisplayName("Java 互操作 UX 集成测试")
class JavaInteropUxIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    // ============ 辅助方法 ============

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

    /** import 放在顶层，body 放在 fun run() 内 */
    private String wrapWithImport(String imports, String body) {
        return imports + "\nobject Test {\n  fun run(): Any {\n" + body + "\n  }\n}";
    }

    // ============ java.lang 自动导入（解释器路径） ============

    @Nested
    @DisplayName("java.lang 自动导入 — 解释器")
    class AutoImportInterpreterTests {

        @Test
        @DisplayName("Math.abs — 正常值")
        void testMathAbs() {
            assertEquals(42, interp("Math.abs(-42)").asInt());
        }

        @Test
        @DisplayName("Integer.parseInt — 正常值")
        void testIntegerParseInt() {
            assertEquals(123, interp("Integer.parseInt(\"123\")").asInt());
        }

        @Test
        @DisplayName("String.valueOf — 正常值")
        void testStringValueOf() {
            assertEquals("true", interp("String.valueOf(true)").asString());
        }

        @Test
        @DisplayName("Math.abs(0) — 边缘: 零值")
        void testMathAbsZero() {
            assertEquals(0, interp("Math.abs(0)").asInt());
        }

        @Test
        @DisplayName("Integer.MIN_VALUE — 边缘: 极值")
        void testIntegerMinValue() {
            assertEquals(Integer.MIN_VALUE, interp("Integer.MIN_VALUE").asInt());
        }

        @Test
        @DisplayName("Integer.parseInt 非法输入 — 异常")
        void testParseIntError() {
            assertThrows(Exception.class, () -> interp("Integer.parseInt(\"\")"));
        }

        @Test
        @DisplayName("Nova stdlib abs() 不受干扰")
        void testNovaAbsUnaffected() {
            assertEquals(7, interp("abs(-7)").asInt());
        }
    }

    // ============ JavaBean 属性语法 — 解释器路径 ============

    @Nested
    @DisplayName("JavaBean 属性 — 解释器")
    class BeanPropertyInterpreterTests {

        private static final String IMPORT_DATE = "import java java.util.Date\n";

        @Test
        @DisplayName("getter — Thread.name 读取")
        void testGetterRead() {
            NovaValue result = interp(
                "val t = Thread(\"bean-test\")\nt.name");
            assertEquals("bean-test", result.asString());
        }

        @Test
        @DisplayName("setter — Thread.name 写入后读回")
        void testSetterWriteRead() {
            NovaValue result = interp(
                "val t = Thread(\"old\")\nt.name = \"new\"\nt.name");
            assertEquals("new", result.asString());
        }

        @Test
        @DisplayName("boolean getter — isDaemon()")
        void testBooleanGetter() {
            NovaValue result = interp(
                "val t = Thread(\"test\")\nt.daemon");
            assertFalse(result.asBoolean());
        }

        @Test
        @DisplayName("boolean setter — setDaemon(true)")
        void testBooleanSetter() {
            NovaValue result = interp(
                "val t = Thread(\"test\")\nt.daemon = true\nt.daemon");
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("long setter — Date.setTime(0)")
        void testLongSetter() {
            NovaValue result = interp(
                IMPORT_DATE + "val d = Date()\nd.time = 0\nd.time");
            assertEquals(0L, result.asLong());
        }

        @Test
        @DisplayName("setter 设置空字符串 — 边缘值")
        void testSetterEmptyString() {
            NovaValue result = interp(
                "val t = Thread(\"x\")\nt.name = \"\"\nt.name");
            assertEquals("", result.asString());
        }

        @Test
        @DisplayName("不存在的属性 — 异常")
        void testNonExistentPropertyError() {
            assertThrows(Exception.class, () ->
                interp("val t = Thread(\"x\")\nt.noSuchProp = 1"));
        }
    }

    // ============ JavaBean 属性语法 — 编译器路径 ============

    @Nested
    @DisplayName("JavaBean 属性 — 编译器")
    class BeanPropertyCompilerTests {

        @Test
        @DisplayName("getter — Date.getTime() 属性读取")
        void testGetterCompiled() throws Exception {
            String code = wrapWithImport(
                "import java java.util.Date",
                "    val d = Date(1000L)\n" +
                "    return d.time");
            Object result = compile(code);
            assertEquals(1000L, ((Number) result).longValue());
        }

        @Test
        @DisplayName("setter — Date.setTime() 属性写入后读回")
        void testSetterCompiled() throws Exception {
            String code = wrapWithImport(
                "import java java.util.Date",
                "    val d = Date()\n" +
                "    d.time = 0L\n" +
                "    return d.time");
            Object result = compile(code);
            assertEquals(0L, ((Number) result).longValue());
        }

        @Test
        @DisplayName("getter — ArrayList.size() 属性读取")
        void testListSizeGetter() throws Exception {
            String code = wrapWithImport(
                "import java java.util.ArrayList",
                "    val list = ArrayList()\n" +
                "    list.add(\"a\")\n" +
                "    list.add(\"b\")\n" +
                "    return list.size()");
            Object result = compile(code);
            assertEquals(2, ((Number) result).intValue());
        }

        @Test
        @DisplayName("setter + getter 组合 — 编译器路径")
        void testSetGetCombination() throws Exception {
            String code = wrapWithImport(
                "import java java.util.Date",
                "    val d = Date()\n" +
                "    d.time = 12345L\n" +
                "    return d.time");
            Object result = compile(code);
            assertEquals(12345L, ((Number) result).longValue());
        }

        @Test
        @DisplayName("boolean setter — 编译器路径")
        void testBooleanSetterCompiled() throws Exception {
            String code = wrapWithImport(
                "import java java.lang.Thread",
                "    val t = Thread(\"ct\")\n" +
                "    t.daemon = true\n" +
                "    return t.daemon");
            Object result = compile(code);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("多次写入 — 编译器路径")
        void testMultipleWrites() throws Exception {
            String code = wrapWithImport(
                "import java java.util.Date",
                "    val d = Date()\n" +
                "    d.time = 100L\n" +
                "    d.time = 200L\n" +
                "    d.time = 300L\n" +
                "    return d.time");
            Object result = compile(code);
            assertEquals(300L, ((Number) result).longValue());
        }
    }
}
