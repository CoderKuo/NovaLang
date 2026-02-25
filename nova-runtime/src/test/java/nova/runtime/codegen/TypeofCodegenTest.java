package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * typeof / isCallable 双路径集成测试：同时验证解释器和编译器路径。
 */
@DisplayName("typeof / isCallable 双路径测试")
class TypeofCodegenTest {

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
        if (expected instanceof Boolean) {
            assertEquals(expected, ir.asBoolean(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else {
            assertEquals(String.valueOf(expected), ir.asString(), "解释器");
            assertEquals(String.valueOf(expected), String.valueOf(cr), "编译器");
        }
    }

    // ============================================================
    //  typeof 基本类型
    // ============================================================

    @Nested
    @DisplayName("typeof 基本类型")
    class TypeofPrimitiveTests {

        @Test
        @DisplayName("typeof(42) == Int")
        void testInt() throws Exception {
            dual("typeof(42)", wrap("return typeof(42)"), "Int");
        }

        @Test
        @DisplayName("typeof(\"hello\") == String")
        void testString() throws Exception {
            dual("typeof(\"hello\")", wrap("return typeof(\"hello\")"), "String");
        }

        @Test
        @DisplayName("typeof(true) == Boolean")
        void testBoolean() throws Exception {
            dual("typeof(true)", wrap("return typeof(true)"), "Boolean");
        }

        @Test
        @DisplayName("typeof(3.14) == Double")
        void testDouble() throws Exception {
            dual("typeof(3.14)", wrap("return typeof(3.14)"), "Double");
        }

        @Test
        @DisplayName("typeof(null) == Null")
        void testNull() throws Exception {
            dual("typeof(null)", wrap("return typeof(null)"), "Null");
        }

        @Test
        @DisplayName("typeof('a') == Char")
        void testChar() throws Exception {
            dual("typeof('a')", wrap("return typeof('a')"), "Char");
        }
    }

    // ============================================================
    //  typeof 集合类型
    // ============================================================

    @Nested
    @DisplayName("typeof 集合类型")
    class TypeofCollectionTests {

        @Test
        @DisplayName("typeof([1,2,3]) == List")
        void testList() throws Exception {
            dual("typeof([1,2,3])", wrap("return typeof([1,2,3])"), "List");
        }

        @Test
        @DisplayName("typeof(mapOf(\"a\" to 1)) == Map")
        void testMap() throws Exception {
            dual(
                "typeof(mapOf(\"a\" to 1))",
                wrap("return typeof(mapOf(\"a\" to 1))"),
                "Map"
            );
        }
    }

    // ============================================================
    //  typeof 自定义类
    // ============================================================

    @Nested
    @DisplayName("typeof 自定义类")
    class TypeofCustomClassTests {

        @Test
        @DisplayName("typeof 自定义类实例")
        void testCustomClass() throws Exception {
            String code =
                "class Foo(val x: Int)\n" +
                "typeof(Foo(42))";
            String compileCode =
                "class Foo(val x: Int)\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    return typeof(Foo(42))\n" +
                "  }\n}";
            dual(code, compileCode, "Foo");
        }
    }

    // ============================================================
    //  typeof 表达式结果
    // ============================================================

    @Nested
    @DisplayName("typeof 表达式")
    class TypeofExpressionTests {

        @Test
        @DisplayName("typeof 算术结果")
        void testArithmetic() throws Exception {
            dual("typeof(1 + 2)", wrap("return typeof(1 + 2)"), "Int");
        }

        @Test
        @DisplayName("typeof 字符串拼接结果")
        void testStringConcat() throws Exception {
            dual(
                "typeof(\"a\" + \"b\")",
                wrap("return typeof(\"a\" + \"b\")"),
                "String"
            );
        }
    }

    // ============================================================
    //  isCallable
    // ============================================================

    @Nested
    @DisplayName("isCallable")
    class IsCallableTests {

        @Test
        @DisplayName("isCallable(lambda) == true")
        void testLambdaIsCallable() throws Exception {
            dual(
                "isCallable({ 42 })",
                wrap("return isCallable({ 42 })"),
                true
            );
        }

        @Test
        @DisplayName("isCallable(42) == false")
        void testIntNotCallable() throws Exception {
            dual(
                "isCallable(42)",
                wrap("return isCallable(42)"),
                false
            );
        }

        @Test
        @DisplayName("isCallable(\"hello\") == false")
        void testStringNotCallable() throws Exception {
            dual(
                "isCallable(\"hello\")",
                wrap("return isCallable(\"hello\")"),
                false
            );
        }
    }
}
