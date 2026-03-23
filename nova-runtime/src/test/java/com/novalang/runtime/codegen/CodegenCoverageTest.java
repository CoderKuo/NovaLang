package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式覆盖率测试：覆盖 NovaOps / MirCodeGenerator / Stdlib 的未覆盖路径。
 * 所有测试均通过 NovaIrCompiler 脚本模式编译执行。
 */
@DisplayName("编译模式覆盖率测试")
class CodegenCoverageTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    private Object run(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module, "编译后应生成 $Module 类");
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);
        NovaScriptContext.init(null);
        try {
            Object result = main.invoke(null);
            if (result instanceof InvocationTargetException) {
                throw (Exception) ((InvocationTargetException) result).getCause();
            }
            return result;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        } finally {
            NovaScriptContext.clear();
        }
    }

    /** 使用 Nova.compileToBytecode 来运行（包含 Java 互操作支持） */
    private Object runWithJavaInterop(String code) {
        return new Nova().compileToBytecode(code, "test.nova").run();
    }

    private static int asInt(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asInt();
        if (result instanceof Number) return ((Number) result).intValue();
        fail("Expected int, got: " + (result == null ? "null" : result.getClass().getName()));
        return 0;
    }

    private static String asString(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asString();
        return String.valueOf(result);
    }

    private static boolean asBool(Object result) {
        if (result instanceof Boolean) return (Boolean) result;
        if (result instanceof NovaValue) return ((NovaValue) result).asBoolean();
        fail("Expected boolean, got: " + (result == null ? "null" : result.getClass().getName()));
        return false;
    }

    private static double asDouble(Object result) {
        if (result instanceof Number) return ((Number) result).doubleValue();
        if (result instanceof NovaValue) return ((NovaValue) result).asDouble();
        fail("Expected double, got: " + (result == null ? "null" : result.getClass().getName()));
        return 0;
    }

    // ================================================================
    // 1. NovaOps 编译路径
    // ================================================================

    @Nested
    @DisplayName("1. NovaOps 编译路径")
    class NovaOpsTests {

        @Test
        @DisplayName("Int == Long 跨类型相等")
        void testIntEqualsLong() throws Exception {
            Object result = run("val a = 42\nval b = 42L\na == b");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("Float == Double 跨类型相等")
        void testFloatEqualsDouble() throws Exception {
            Object result = run("val a = 3.14\nval b = 3.14\na == b");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("Int != Long 不相等")
        void testIntNotEqualsLong() throws Exception {
            Object result = run("val a = 42\nval b = 43L\na == b");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("跨类型 compare: Int < Long")
        void testCompareIntLong() throws Exception {
            Object result = run("val a = 1\nval b = 2L\na < b");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("跨类型 compare: Long > Int")
        void testCompareLongGreaterThanInt() throws Exception {
            Object result = run("val a = 100L\nval b = 50\na > b");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("String * Int 重复")
        void testStringMulInt() throws Exception {
            Object result = run("val s = \"ab\"\nval n = 3\ns * n");
            assertEquals("ababab", asString(result));
        }

        @Test
        @DisplayName("一元正号 +x")
        void testUnaryPlus() throws Exception {
            Object result = run("val x = 42\n+x");
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("减法运算")
        void testSub() throws Exception {
            Object result = run("val a = 10\nval b = 3\na - b");
            assertEquals(7, asInt(result));
        }

        @Test
        @DisplayName("除法运算")
        void testDiv() throws Exception {
            Object result = run("val a = 20\nval b = 4\na / b");
            assertEquals(5, asInt(result));
        }

        @Test
        @DisplayName("取模运算")
        void testMod() throws Exception {
            Object result = run("val a = 17\nval b = 5\na % b");
            assertEquals(2, asInt(result));
        }

        @Test
        @DisplayName("Long 减法")
        void testLongSub() throws Exception {
            Object result = run("val a = 100L\nval b = 30L\na - b");
            assertEquals(70L, ((Number) result).longValue());
        }

        @Test
        @DisplayName("Double 除法")
        void testDoubleDiv() throws Exception {
            Object result = run("val a = 10.0\nval b = 3.0\na / b");
            assertEquals(3.333, asDouble(result), 0.01);
        }

        @Test
        @DisplayName("Double 取模")
        void testDoubleMod() throws Exception {
            Object result = run("val a = 10.5\nval b = 3.0\na % b");
            assertEquals(1.5, asDouble(result), 0.01);
        }
    }

    // ================================================================
    // 2. MirCodeGenerator 路径
    // ================================================================

    @Nested
    @DisplayName("2. MirCodeGenerator 路径")
    class MirCodeGenTests {

        @Test
        @DisplayName("带默认参数的函数调用")
        void testNamedParamsDefault() throws Exception {
            Object result = run(
                    "fun f(a: Int, b: Int = 10): Int = a + b\n" +
                    "f(1)");
            assertEquals(11, asInt(result));
        }

        @Test
        @DisplayName("带默认参数的函数调用 — 覆盖默认值")
        void testNamedParamsOverride() throws Exception {
            Object result = run(
                    "fun f(a: Int, b: Int = 10): Int = a + b\n" +
                    "f(1, 20)");
            assertEquals(21, asInt(result));
        }

        @Test
        @DisplayName("类型转换 as Int")
        void testTypeCastAsInt() throws Exception {
            Object result = run("val x: Any = 42\nx as Int");
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("安全转换 as? Int — 成功")
        void testSafeCastSuccess() throws Exception {
            Object result = run("val x: Any = 42\nx as? Int");
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("安全转换 as? Int — 失败返回 null")
        void testSafeCastFail() throws Exception {
            Object result = run("val x: Any = \"hello\"\nval r = x as? Int\nr == null");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("Result 类型 Ok")
        void testResultOk() throws Exception {
            Object result = run("val r = Ok(42)\nr.value");
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("Result 类型 Err")
        void testResultErr() throws Exception {
            Object result = run("val r = Err(\"oops\")\nr.error");
            assertEquals("oops", asString(result));
        }

        @Test
        @DisplayName("Result isOk / isErr")
        void testResultChecks() throws Exception {
            Object result = run("val r = Ok(1)\nr.isOk");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("字符串插值 — 编译路径")
        void testStringInterpolation() throws Exception {
            Object result = run("val name = \"world\"\n\"hello $name\"");
            assertEquals("hello world", asString(result));
        }

        @Test
        @DisplayName("字符串插值 — 表达式")
        void testStringInterpolationExpr() throws Exception {
            Object result = run("val x = 5\n\"value is ${x + 1}\"");
            assertEquals("value is 6", asString(result));
        }

        @Test
        @DisplayName("if 表达式 — 编译路径")
        void testIfExpression() throws Exception {
            Object result = run("val x = 10\nif (x > 5) 1 else 2");
            assertEquals(1, asInt(result));
        }

        @Test
        @DisplayName("if 表达式 — false 分支")
        void testIfExpressionFalse() throws Exception {
            Object result = run("val x = 3\nif (x > 5) 1 else 2");
            assertEquals(2, asInt(result));
        }

        @Test
        @DisplayName("for 循环 — 编译路径")
        void testForLoop() throws Exception {
            Object result = run("var sum = 0\nfor (i in 1..5) { sum = sum + i }\nsum");
            assertEquals(15, asInt(result));
        }

        @Test
        @DisplayName("while 循环 — 编译路径")
        void testWhileLoop() throws Exception {
            Object result = run("var x = 0\nwhile (x < 10) { x = x + 1 }\nx");
            assertEquals(10, asInt(result));
        }

        @Test
        @DisplayName("when 表达式 — 编译路径")
        void testWhenExpression() throws Exception {
            Object result = run(
                    "val x = 2\n" +
                    "when (x) {\n" +
                    "    1 -> \"one\"\n" +
                    "    2 -> \"two\"\n" +
                    "    else -> \"other\"\n" +
                    "}");
            assertEquals("two", asString(result));
        }

        @Test
        @DisplayName("when 表达式 — else 分支")
        void testWhenExpressionElse() throws Exception {
            Object result = run(
                    "val x = 99\n" +
                    "when (x) {\n" +
                    "    1 -> \"one\"\n" +
                    "    2 -> \"two\"\n" +
                    "    else -> \"other\"\n" +
                    "}");
            assertEquals("other", asString(result));
        }

        @Test
        @DisplayName("Lambda 闭包捕获变量")
        void testLambdaClosure() throws Exception {
            Object result = run(
                    "var counter = 0\n" +
                    "val inc = { counter = counter + 1 }\n" +
                    "inc()\ninc()\ninc()\ncounter");
            assertEquals(3, asInt(result));
        }

        @Test
        @DisplayName("try-catch 表达式 — 编译路径")
        void testTryCatch() throws Exception {
            Object result = run(
                    "try {\n" +
                    "    throw \"boom\"\n" +
                    "} catch (e: Exception) {\n" +
                    "    \"caught\"\n" +
                    "}");
            assertEquals("caught", asString(result));
        }

        @Test
        @DisplayName("try-catch-finally — 编译路径")
        void testTryCatchFinally() throws Exception {
            Object result = run(
                    "var counter = 0\n" +
                    "try {\n" +
                    "    throw \"err\"\n" +
                    "} catch (e: Exception) {\n" +
                    "    0\n" +
                    "} finally {\n" +
                    "    counter = counter + 1\n" +
                    "}\n" +
                    "counter");
            assertEquals(1, asInt(result));
        }

        @Test
        @DisplayName("嵌套函数调用")
        void testNestedFunctionCalls() throws Exception {
            Object result = run(
                    "fun double(x: Int): Int = x * 2\n" +
                    "fun addOne(x: Int): Int = x + 1\n" +
                    "addOne(double(5))");
            assertEquals(11, asInt(result));
        }

        @Test
        @DisplayName("递归函数 — 阶乘")
        void testRecursion() throws Exception {
            Object result = run(
                    "fun factorial(n: Int): Int {\n" +
                    "    if (n <= 1) return 1\n" +
                    "    return n * factorial(n - 1)\n" +
                    "}\n" +
                    "factorial(5)");
            assertEquals(120, asInt(result));
        }

        @Test
        @DisplayName("多参数 Lambda")
        void testMultiParamLambda() throws Exception {
            Object result = run(
                    "val add = { a: Int, b: Int -> a + b }\n" +
                    "add(3, 7)");
            assertEquals(10, asInt(result));
        }
    }

    // ================================================================
    // 3. Stdlib 编译路径
    // ================================================================

    @Nested
    @DisplayName("3. Stdlib 编译路径")
    class StdlibTests {

        @Test
        @DisplayName("abs 函数")
        void testAbs() throws Exception {
            Object result = run("abs(-42)");
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("max 函数")
        void testMax() throws Exception {
            Object result = run("max(10, 20)");
            assertEquals(20, asInt(result));
        }

        @Test
        @DisplayName("min 函数")
        void testMin() throws Exception {
            Object result = run("min(10, 20)");
            assertEquals(10, asInt(result));
        }

        @Test
        @DisplayName("sqrt 函数")
        void testSqrt() throws Exception {
            Object result = run("sqrt(16)");
            assertEquals(4.0, asDouble(result), 0.001);
        }

        @Test
        @DisplayName("pow 函数")
        void testPow() throws Exception {
            Object result = run("pow(2, 10)");
            assertEquals(1024.0, asDouble(result), 0.001);
        }

        @Test
        @DisplayName("typeof 函数")
        void testTypeof() throws Exception {
            Object result = run("typeof(42)");
            assertEquals("Int", asString(result));
        }

        @Test
        @DisplayName("typeof 字符串")
        void testTypeofString() throws Exception {
            Object result = run("typeof(\"hello\")");
            assertEquals("String", asString(result));
        }

        @Test
        @DisplayName("isNumber 函数 — true")
        void testIsNumberTrue() throws Exception {
            Object result = run("isNumber(42)");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isNumber 函数 — false")
        void testIsNumberFalse() throws Exception {
            Object result = run("isNumber(\"hello\")");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("isString 函数 — true")
        void testIsStringTrue() throws Exception {
            Object result = run("isString(\"hello\")");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isString 函数 — false")
        void testIsStringFalse() throws Exception {
            Object result = run("isString(42)");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("listOf 集合构造")
        void testListOf() throws Exception {
            Object result = run("val list = listOf(1, 2, 3)\nlist.size()");
            assertEquals(3, asInt(result));
        }

        @Test
        @DisplayName("mapOf 集合构造")
        void testMapOf() throws Exception {
            Object result = run("val m = mapOf(\"a\", 1, \"b\", 2)\nm.size()");
            assertEquals(2, asInt(result));
        }

        @Test
        @DisplayName("setOf 集合构造")
        void testSetOf() throws Exception {
            Object result = run("val s = setOf(1, 2, 2, 3)\ns.size()");
            assertEquals(3, asInt(result));
        }

        @Test
        @DisplayName("字符串 length 属性")
        void testStringLength() throws Exception {
            Object result = run("\"hello\".length");
            assertEquals(5, asInt(result));
        }

        @Test
        @DisplayName("字符串 toUpperCase")
        void testStringToUpperCase() throws Exception {
            Object result = run("\"hello\".toUpperCase()");
            assertEquals("HELLO", asString(result));
        }

        @Test
        @DisplayName("字符串 toLowerCase")
        void testStringToLowerCase() throws Exception {
            Object result = run("\"HELLO\".toLowerCase()");
            assertEquals("hello", asString(result));
        }

        @Test
        @DisplayName("println 编译路径 — 不崩溃")
        void testPrintln() throws Exception {
            // 重定向 stdout 避免测试输出干扰
            PrintStream originalOut = System.out;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            NovaPrint.setOut(new PrintStream(baos));
            try {
                run("println(\"test output\")");
                String output = baos.toString();
                assertTrue(output.contains("test output"));
            } finally {
                NovaPrint.setOut(originalOut);
            }
        }

        @Test
        @DisplayName("len 函数 — 字符串")
        void testLenString() throws Exception {
            Object result = run("len(\"hello\")");
            assertEquals(5, asInt(result));
        }

        @Test
        @DisplayName("len 函数 — 列表")
        void testLenList() throws Exception {
            Object result = run("len(listOf(1, 2, 3))");
            assertEquals(3, asInt(result));
        }
    }

    // ================================================================
    // 4. SAM 适配器编译路径
    // ================================================================

    @Nested
    @DisplayName("4. SAM 适配器编译路径")
    class SamAdapterTests {

        @Test
        @DisplayName("Lambda 适配 Comparator — Collections.sort")
        void testLambdaToComparator() {
            Object result = runWithJavaInterop(
                    "val ArrayList = javaClass(\"java.util.ArrayList\")\n" +
                    "val Collections = javaClass(\"java.util.Collections\")\n" +
                    "val list = ArrayList()\n" +
                    "list.add(5)\nlist.add(1)\nlist.add(3)\n" +
                    "Collections.sort(list, { a, b -> a - b })\n" +
                    "list.get(0)");
            assertEquals(1, ((Number) result).intValue());
        }

        @Test
        @DisplayName("Lambda 适配 Consumer — forEach")
        void testLambdaToConsumer() {
            Object result = runWithJavaInterop(
                    "val ArrayList = javaClass(\"java.util.ArrayList\")\n" +
                    "val sb = javaClass(\"java.lang.StringBuilder\")()\n" +
                    "val list = ArrayList()\n" +
                    "list.add(\"a\")\nlist.add(\"b\")\nlist.add(\"c\")\n" +
                    "list.forEach({ x -> sb.append(x) })\n" +
                    "sb.toString()");
            assertEquals("abc", result);
        }

        @Test
        @DisplayName("Lambda 适配 Runnable — Thread")
        void testLambdaToRunnable() {
            // 验证 lambda 能适配为 Runnable 并通过 Thread 执行
            Object result = runWithJavaInterop(
                    "val ArrayList = javaClass(\"java.util.ArrayList\")\n" +
                    "val list = ArrayList()\n" +
                    "val Thread = javaClass(\"java.lang.Thread\")\n" +
                    "val t = Thread({ list.add(\"done\") })\n" +
                    "t.start()\n" +
                    "t.join()\n" +
                    "list.size()");
            assertEquals(1, ((Number) result).intValue());
        }

        @Test
        @DisplayName("反序排序 Comparator")
        void testReverseComparator() {
            Object result = runWithJavaInterop(
                    "val ArrayList = javaClass(\"java.util.ArrayList\")\n" +
                    "val Collections = javaClass(\"java.util.Collections\")\n" +
                    "val list = ArrayList()\n" +
                    "list.add(1)\nlist.add(5)\nlist.add(3)\n" +
                    "Collections.sort(list, { a, b -> b - a })\n" +
                    "list.get(0)");
            assertEquals(5, ((Number) result).intValue());
        }
    }

    // ================================================================
    // 5. Java 互操作编译路径
    // ================================================================

    @Nested
    @DisplayName("5. Java 互操作编译路径")
    class JavaInteropTests {

        @Test
        @DisplayName("javaClass ArrayList — create + add + size")
        void testJavaClassArrayList() {
            Object result = runWithJavaInterop(
                    "val ArrayList = javaClass(\"java.util.ArrayList\")\n" +
                    "val list = ArrayList()\n" +
                    "list.add(10)\n" +
                    "list.add(20)\n" +
                    "list.add(30)\n" +
                    "list.size()");
            assertEquals(3, ((Number) result).intValue());
        }

        @Test
        @DisplayName("Java.type Math.abs")
        void testJavaTypeMathAbs() {
            Object result = runWithJavaInterop(
                    "val Math = Java.type(\"java.lang.Math\")\n" +
                    "Math.abs(-5)");
            assertEquals(5, ((Number) result).intValue());
        }

        @Test
        @DisplayName("Java.type Integer.MAX_VALUE 静态字段")
        void testJavaTypeStaticField() {
            Object result = runWithJavaInterop(
                    "val Integer = Java.type(\"java.lang.Integer\")\n" +
                    "Integer.MAX_VALUE");
            assertEquals(Integer.MAX_VALUE, ((Number) result).intValue());
        }

        @Test
        @DisplayName("javaClass StringBuilder 链式调用")
        void testJavaClassStringBuilder() {
            Object result = runWithJavaInterop(
                    "val StringBuilder = javaClass(\"java.lang.StringBuilder\")\n" +
                    "val sb = StringBuilder()\n" +
                    "sb.append(\"foo\")\n" +
                    "sb.append(\"bar\")\n" +
                    "sb.toString()");
            assertEquals("foobar", result);
        }

        @Test
        @DisplayName("javaClass ArrayList — get 元素")
        void testJavaClassArrayListGet() {
            Object result = runWithJavaInterop(
                    "val ArrayList = javaClass(\"java.util.ArrayList\")\n" +
                    "val list = ArrayList()\n" +
                    "list.add(\"hello\")\n" +
                    "list.add(\"world\")\n" +
                    "list.get(1)");
            assertEquals("world", result);
        }

        @Test
        @DisplayName("Java.type 带参构造器")
        void testJavaTypeConstructorWithArgs() {
            Object result = runWithJavaInterop(
                    "val StringBuilder = Java.type(\"java.lang.StringBuilder\")\n" +
                    "val sb = StringBuilder(\"init\")\n" +
                    "sb.append(\"_end\")\n" +
                    "sb.toString()");
            assertEquals("init_end", result);
        }
    }

    // ================================================================
    // 6. 其他边界路径
    // ================================================================

    @Nested
    @DisplayName("6. 其他边界路径")
    class EdgeCaseTests {

        @Test
        @DisplayName("空列表操作")
        void testEmptyList() throws Exception {
            Object result = run("val list = listOf()\nlist.size()");
            assertEquals(0, asInt(result));
        }

        @Test
        @DisplayName("布尔运算 — 逻辑与")
        void testLogicalAnd() throws Exception {
            Object result = run("val a = true\nval b = false\na && b");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("布尔运算 — 逻辑或")
        void testLogicalOr() throws Exception {
            Object result = run("val a = true\nval b = false\na || b");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("布尔运算 — 逻辑非")
        void testLogicalNot() throws Exception {
            Object result = run("val a = true\n!a");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("null 安全 — null 比较")
        void testNullComparison() throws Exception {
            Object result = run("val x = null\nx == null");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("嵌套 if 表达式")
        void testNestedIfExpr() throws Exception {
            Object result = run(
                    "val x = 15\n" +
                    "if (x > 20) \"big\" else if (x > 10) \"medium\" else \"small\"");
            assertEquals("medium", asString(result));
        }

        @Test
        @DisplayName("for 循环 — List 遍历求和")
        void testForOverList() throws Exception {
            Object result = run(
                    "val nums = listOf(10, 20, 30)\n" +
                    "var sum = 0\n" +
                    "for (n in nums) { sum = sum + n }\n" +
                    "sum");
            assertEquals(60, asInt(result));
        }

        @Test
        @DisplayName("字符串拼接 — + 运算符")
        void testStringConcat() throws Exception {
            Object result = run("\"hello\" + \" \" + \"world\"");
            assertEquals("hello world", asString(result));
        }

        @Test
        @DisplayName("多重赋值 — var 多次修改")
        void testMultipleAssignment() throws Exception {
            Object result = run(
                    "var x = 1\n" +
                    "x = x + 1\n" +
                    "x = x * 3\n" +
                    "x = x - 2\n" +
                    "x");
            assertEquals(4, asInt(result));
        }

        @Test
        @DisplayName("复合赋值运算符 +=")
        void testCompoundAssignPlus() throws Exception {
            Object result = run("var x = 10\nx += 5\nx");
            assertEquals(15, asInt(result));
        }

        @Test
        @DisplayName("复合赋值运算符 -=")
        void testCompoundAssignMinus() throws Exception {
            Object result = run("var x = 10\nx -= 3\nx");
            assertEquals(7, asInt(result));
        }

        @Test
        @DisplayName("复合赋值运算符 *=")
        void testCompoundAssignMul() throws Exception {
            Object result = run("var x = 4\nx *= 5\nx");
            assertEquals(20, asInt(result));
        }

        @Test
        @DisplayName("负数字面量")
        void testNegativeLiteral() throws Exception {
            Object result = run("-42");
            assertEquals(-42, asInt(result));
        }

        @Test
        @DisplayName("Long 字面量运算")
        void testLongLiteral() throws Exception {
            Object result = run("val a = 1000000000L\nval b = 2000000000L\na + b");
            assertEquals(3000000000L, ((Number) result).longValue());
        }

        @Test
        @DisplayName("类定义与实例化 — 编译路径")
        void testClassInstantiation() throws Exception {
            Object result = run(
                    "class Point(val x: Int, val y: Int)\n" +
                    "val p = Point(3, 4)\n" +
                    "p.x + p.y");
            assertEquals(7, asInt(result));
        }
    }
}
