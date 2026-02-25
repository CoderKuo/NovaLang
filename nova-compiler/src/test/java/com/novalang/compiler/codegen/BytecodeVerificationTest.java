package com.novalang.compiler.codegen;

import com.novalang.ir.NovaIrCompiler;
import org.junit.jupiter.api.*;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 字节码验证测试：使用 ASM CheckClassAdapter 验证 CodeGenerator 输出的字节码正确性。
 *
 * <p>覆盖所有关键语法特性，确保生成的字节码通过 JVM verifier。</p>
 */
@DisplayName("字节码验证测试")
class BytecodeVerificationTest {

    private NovaIrCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new NovaIrCompiler();
    }

    // ============================================================
    //  辅助方法
    // ============================================================

    /** 编译 Nova 源码并对所有生成的 class 做 ASM 验证 */
    private Map<String, byte[]> compileAndVerify(String source) {
        Map<String, byte[]> classes = compiler.compile(source, "test.nova");
        assertFalse(classes.isEmpty(), "未生成任何字节码");

        // 构建包含所有生成类的 ClassLoader，供跨类验证使用
        TestClassLoader loader = new TestClassLoader(classes);

        // ASM CheckClassAdapter 验证每个生成的类
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            String className = entry.getKey();
            byte[] bytecode = entry.getValue();
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            try {
                ClassReader cr = new ClassReader(bytecode);
                CheckClassAdapter.verify(cr, loader, false, pw);
            } catch (Exception e) {
                fail("字节码验证失败 [" + className + "]: " + e.getMessage() + "\n" + sw);
            }
            String verifyOutput = sw.toString();
            if (!verifyOutput.isEmpty()) {
                fail("字节码验证警告 [" + className + "]:\n" + verifyOutput);
            }
        }
        return classes;
    }

    /** 编译、验证并执行 main 方法 */
    private void compileVerifyAndRun(String source) throws Exception {
        Map<String, byte[]> classes = compileAndVerify(source);
        TestClassLoader loader = new TestClassLoader(classes);
        // 找到包含 main 的类
        for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
            try {
                Class<?> clazz = loader.loadClass(entry.getKey());
                Method main = clazz.getMethod("main", String[].class);
                main.invoke(null, (Object) new String[0]);
                return;
            } catch (NoSuchMethodException ignored) {
            }
        }
    }

    static class TestClassLoader extends ClassLoader {
        private final Map<String, byte[]> classes;
        TestClassLoader(Map<String, byte[]> classes) {
            super(TestClassLoader.class.getClassLoader());
            this.classes = classes;
        }
        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            if (bytes != null) return defineClass(name, bytes, 0, bytes.length);
            throw new ClassNotFoundException(name);
        }
    }

    // ============================================================
    //  测试用例
    // ============================================================

    @Test
    @DisplayName("基础字面量和算术运算")
    void testLiteralsAndArithmetic() throws Exception {
        compileVerifyAndRun(
            "val a = 1 + 2\n" +
            "val b = 3.14 * 2.0\n" +
            "val c = \"hello\" + \" world\"\n" +
            "val d = true && false\n" +
            "val e = 10 % 3\n" +
            "println(a)\n" +
            "println(b)\n" +
            "println(c)\n"
        );
    }

    @Test
    @DisplayName("变量声明和赋值")
    void testVariables() throws Exception {
        compileVerifyAndRun(
            "val x = 42\n" +
            "var y = 10\n" +
            "y = y + x\n" +
            "println(y)\n"
        );
    }

    @Test
    @DisplayName("if 表达式")
    void testIfExpression() throws Exception {
        compileVerifyAndRun(
            "val x = 5\n" +
            "val result = if (x > 0) \"positive\" else \"non-positive\"\n" +
            "println(result)\n"
        );
    }

    @Test
    @DisplayName("when 表达式（值匹配）")
    void testWhenExpression() throws Exception {
        compileVerifyAndRun(
            "fun classify(n: Int): String = when {\n" +
            "    n < 0 -> \"negative\"\n" +
            "    n == 0 -> \"zero\"\n" +
            "    n < 10 -> \"small\"\n" +
            "    else -> \"large\"\n" +
            "}\n" +
            "println(classify(5))\n" +
            "println(classify(-1))\n" +
            "println(classify(0))\n"
        );
    }

    @Test
    @DisplayName("when 表达式（subject 匹配）")
    void testWhenWithSubject() throws Exception {
        compileVerifyAndRun(
            "val grade = when (42) {\n" +
            "    90 -> \"A\"\n" +
            "    80 -> \"B\"\n" +
            "    42 -> \"Answer\"\n" +
            "    else -> \"?\"\n" +
            "}\n" +
            "println(grade)\n"
        );
    }

    @Test
    @DisplayName("for 循环和 range")
    void testForLoopAndRange() throws Exception {
        compileVerifyAndRun(
            "var sum = 0\n" +
            "for (i in 1..10) {\n" +
            "    sum = sum + i\n" +
            "}\n" +
            "println(sum)\n"
        );
    }

    @Test
    @DisplayName("while 循环")
    void testWhileLoop() throws Exception {
        compileVerifyAndRun(
            "var n = 10\n" +
            "var fact = 1\n" +
            "while (n > 0) {\n" +
            "    fact = fact * n\n" +
            "    n = n - 1\n" +
            "}\n" +
            "println(fact)\n"
        );
    }

    @Test
    @DisplayName("函数定义和调用")
    void testFunctions() throws Exception {
        compileVerifyAndRun(
            "fun add(a: Int, b: Int): Int = a + b\n" +
            "fun greet(name: String): String = \"Hello, \" + name\n" +
            "println(add(3, 4))\n" +
            "println(greet(\"World\"))\n"
        );
    }

    @Test
    @DisplayName("Lambda 和高阶函数")
    void testLambdaAndHigherOrderFunctions() throws Exception {
        compileVerifyAndRun(
            "fun apply(x: Int, f: (Int) -> Int): Int = f(x)\n" +
            "val result = apply(5) { it * 2 }\n" +
            "println(result)\n"
        );
    }

    @Test
    @DisplayName("顶层属性 Lambda 调用")
    void testTopLevelPropertyLambdaCall() throws Exception {
        compileVerifyAndRun(
            "val double = { x: Int -> x * 2 }\n" +
            "println(double(21))\n"
        );
    }

    @Test
    @DisplayName("类定义和方法调用")
    void testClassAndMethods() throws Exception {
        compileVerifyAndRun(
            "class Greeter(val name: String) {\n" +
            "    fun greet(): String = \"Hi, \" + name\n" +
            "}\n" +
            "val g = Greeter(\"Nova\")\n" +
            "println(g.greet())\n"
        );
    }

    @Test
    @DisplayName("枚举类")
    void testEnum() throws Exception {
        compileVerifyAndRun(
            "enum class Color { RED, GREEN, BLUE }\n" +
            "println(Color.RED)\n"
        );
    }

    @Test
    @DisplayName("枚举带构造器参数")
    void testEnumWithParams() throws Exception {
        compileVerifyAndRun(
            "enum class Direction(val dx: Int, val dy: Int) {\n" +
            "    UP(0, 1), DOWN(0, -1), LEFT(-1, 0), RIGHT(1, 0)\n" +
            "}\n" +
            "println(Direction.UP.dx)\n" +
            "println(Direction.UP.dy)\n"
        );
    }

    @Test
    @DisplayName("try-catch 表达式")
    void testTryCatch() throws Exception {
        compileVerifyAndRun(
            "val result = try {\n" +
            "    val a = 10\n" +
            "    val b = 0\n" +
            "    a / b\n" +
            "} catch (e: Exception) {\n" +
            "    -1\n" +
            "}\n" +
            "println(result)\n"
        );
    }

    @Test
    @DisplayName("字符串插值")
    void testStringInterpolation() throws Exception {
        compileVerifyAndRun(
            "val name = \"World\"\n" +
            "val age = 42\n" +
            "println(\"Hello, $name! Age: $age\")\n"
        );
    }

    @Test
    @DisplayName("集合操作 filter/map")
    void testCollectionOperations() throws Exception {
        compileVerifyAndRun(
            "val list = [1, 2, 3, 4, 5]\n" +
            "val evens = list.filter { it % 2 == 0 }\n" +
            "println(evens)\n"
        );
    }

    @Test
    @DisplayName("空安全操作符")
    void testNullSafety() throws Exception {
        compileVerifyAndRun(
            "val s: String? = null\n" +
            "val len = s?.length() ?: -1\n" +
            "println(len)\n"
        );
    }

    @Test
    @DisplayName("类型检查 is")
    void testTypeCheck() throws Exception {
        compileVerifyAndRun(
            "val x: Any = \"hello\"\n" +
            "if (x is String) {\n" +
            "    println(\"is string\")\n" +
            "}\n"
        );
    }

    @Test
    @DisplayName("扩展函数")
    void testExtensionFunction() throws Exception {
        compileVerifyAndRun(
            "fun String.exclaim(): String = this + \"!\"\n" +
            "println(\"hello\".exclaim())\n"
        );
    }

    @Test
    @DisplayName("Map 字面量")
    void testMapLiteral() throws Exception {
        compileVerifyAndRun(
            "val m = #{\"a\": 1, \"b\": 2}\n" +
            "println(m)\n"
        );
    }

    @Test
    @DisplayName("Spread 操作符")
    void testSpreadOperator() throws Exception {
        compileVerifyAndRun(
            "val a = [1, 2]\n" +
            "val b = [0, *a, 3]\n" +
            "println(b)\n"
        );
    }

    @Test
    @DisplayName("管道操作符")
    void testPipeOperator() throws Exception {
        compileVerifyAndRun(
            "fun wrap(s: String): String = \"[\" + s + \"]\"\n" +
            "fun shout(s: String): String = s + \"!\"\n" +
            "val result = \"hello\" |> wrap |> shout\n" +
            "println(result)\n"
        );
    }

    // ============================================================
    //  类型感知算术测试（MirCodeGenerator 多类型支持）
    // ============================================================

    /**
     * 编译源码并通过反射调用 $Module 中的无参静态方法，返回结果。
     */
    private Object invokeModuleFunction(String source, String funcName) throws Exception {
        Map<String, byte[]> classes = compileAndVerify(source);
        TestClassLoader loader = new TestClassLoader(classes);
        Class<?> moduleClass = loader.loadClass("$Module");
        Method method = moduleClass.getMethod(funcName);
        return method.invoke(null);
    }

    @Test
    @DisplayName("Double 算术运算精度")
    void testDoubleArithmetic() throws Exception {
        String source =
            "fun testAdd(): Double = 3.14 + 2.0\n" +
            "fun testSub(): Double = 10.5 - 3.2\n" +
            "fun testMul(): Double = 2.5 * 4.0\n" +
            "fun testDiv(): Double = 7.0 / 2.0\n" +
            "fun testMod(): Double = 7.5 % 2.0\n";

        assertEquals(5.14, ((Number) invokeModuleFunction(source, "testAdd")).doubleValue(), 1e-10);
        assertEquals(7.3, ((Number) invokeModuleFunction(source, "testSub")).doubleValue(), 1e-10);
        assertEquals(10.0, ((Number) invokeModuleFunction(source, "testMul")).doubleValue(), 1e-10);
        assertEquals(3.5, ((Number) invokeModuleFunction(source, "testDiv")).doubleValue(), 1e-10);
        assertEquals(1.5, ((Number) invokeModuleFunction(source, "testMod")).doubleValue(), 1e-10);
    }

    @Test
    @DisplayName("Long 算术运算")
    void testLongArithmetic() throws Exception {
        String source =
            "fun testAdd(): Long = 1000000000L + 2000000000L\n" +
            "fun testMul(): Long = 100000L * 100000L\n";

        assertEquals(3000000000L, ((Number) invokeModuleFunction(source, "testAdd")).longValue());
        assertEquals(10000000000L, ((Number) invokeModuleFunction(source, "testMul")).longValue());
    }

    @Test
    @DisplayName("Double 比较运算")
    void testDoubleComparison() throws Exception {
        String source =
            "fun testLt(): Boolean = 1.5 < 2.5\n" +
            "fun testGt(): Boolean = 3.14 > 2.0\n" +
            "fun testEq(): Boolean = 1.0 == 1.0\n" +
            "fun testNe(): Boolean = 1.0 != 2.0\n" +
            "fun testLe(): Boolean = 2.0 <= 2.0\n" +
            "fun testGe(): Boolean = 1.0 >= 2.0\n";

        assertEquals(true, invokeModuleFunction(source, "testLt"));
        assertEquals(true, invokeModuleFunction(source, "testGt"));
        assertEquals(true, invokeModuleFunction(source, "testEq"));
        assertEquals(true, invokeModuleFunction(source, "testNe"));
        assertEquals(true, invokeModuleFunction(source, "testLe"));
        assertEquals(false, invokeModuleFunction(source, "testGe"));
    }

    @Test
    @DisplayName("Long 比较运算")
    void testLongComparison() throws Exception {
        String source =
            "fun testLt(): Boolean = 1000000000L < 2000000000L\n" +
            "fun testGt(): Boolean = 3000000000L > 2000000000L\n" +
            "fun testEq(): Boolean = 100L == 100L\n";

        assertEquals(true, invokeModuleFunction(source, "testLt"));
        assertEquals(true, invokeModuleFunction(source, "testGt"));
        assertEquals(true, invokeModuleFunction(source, "testEq"));
    }

    @Test
    @DisplayName("一元取反 - 多类型")
    void testUnaryNegation() throws Exception {
        String source =
            "fun negDouble(): Double = -3.14\n" +
            "fun negLong(): Long = -100L\n" +
            "fun negInt(): Int = -42\n";

        assertEquals(-3.14, ((Number) invokeModuleFunction(source, "negDouble")).doubleValue(), 1e-10);
        assertEquals(-100L, ((Number) invokeModuleFunction(source, "negLong")).longValue());
        assertEquals(-42, ((Number) invokeModuleFunction(source, "negInt")).intValue());
    }

    @Test
    @DisplayName("混合类型算术提升")
    void testMixedTypePromotion() throws Exception {
        String source =
            "fun intPlusDouble(): Double = 1 + 2.5\n" +
            "fun longTimesDouble(): Double = 100L * 0.5\n";

        assertEquals(3.5, ((Number) invokeModuleFunction(source, "intPlusDouble")).doubleValue(), 1e-10);
        assertEquals(50.0, ((Number) invokeModuleFunction(source, "longTimesDouble")).doubleValue(), 1e-10);
    }

    @Test
    @DisplayName("字符串拼接检测 - 操作数类型")
    void testStringConcatDetection() throws Exception {
        String source =
            "fun strPlusInt(): String = \"value: \" + 42\n" +
            "fun intPlusStr(): String = 42 + \" is the answer\"\n";

        assertEquals("value: 42", invokeModuleFunction(source, "strPlusInt"));
        assertEquals("42 is the answer", invokeModuleFunction(source, "intPlusStr"));
    }
}
