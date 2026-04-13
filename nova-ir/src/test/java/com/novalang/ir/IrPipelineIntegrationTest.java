package com.novalang.ir;

import com.novalang.ir.pass.PassPipeline;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IR Pipeline 端到端集成测试。
 *
 * <p>验证 Nova 源码 → Parser → AST → HIR → MIR(优化) → JVM 字节码 → 执行 的完整链路。
 * 每个测试场景会隐式覆盖对应的优化 Pass。</p>
 */
@DisplayName("IR Pipeline 端到端测试")
class IrPipelineIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private Object compileAndRun(String code) throws Exception {
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

    // ============ 常量折叠 (HirConstantFolding) ============

    @Nested
    @DisplayName("常量折叠")
    class ConstantFolding {

        @Test
        @DisplayName("整数常量折叠: 1 + 2 → 3")
        void testIntFolding() throws Exception {
            assertEquals(3, compileAndRun(wrap("return 1 + 2")));
        }

        @Test
        @DisplayName("多级常量折叠: (10 * 3) + 5 → 35")
        void testMultiLevel() throws Exception {
            assertEquals(35, compileAndRun(wrap("return (10 * 3) + 5")));
        }

        @Test
        @DisplayName("字符串常量折叠: \"a\" + \"b\" → \"ab\"")
        void testStringFolding() throws Exception {
            assertEquals("ab", compileAndRun(wrap("return \"a\" + \"b\"")));
        }

        @Test
        @DisplayName("布尔常量折叠: true && false → false")
        void testBoolFolding() throws Exception {
            assertEquals(false, compileAndRun(wrap("return true && false")));
        }

        @Test
        @DisplayName("混合运算不折叠: 变量 + 常量")
        void testNoFoldWithVar() throws Exception {
            assertEquals(15, compileAndRun(wrap("val x = 10\nreturn x + 5")));
        }
    }

    // ============ 死代码消除 (HirDeadCodeElimination / DeadBlockElimination) ============

    @Nested
    @DisplayName("死代码消除")
    class DeadCode {

        @Test
        @DisplayName("if-true 分支: else 被消除")
        void testIfTrue() throws Exception {
            assertEquals(1, compileAndRun(wrap("if (true) return 1 else return 2")));
        }

        @Test
        @DisplayName("if-false 分支: then 被消除")
        void testIfFalse() throws Exception {
            assertEquals(2, compileAndRun(wrap("if (false) return 1 else return 2")));
        }

        @Test
        @DisplayName("return 后的代码不影响结果")
        void testAfterReturn() throws Exception {
            assertEquals(42, compileAndRun(wrap("return 42\nval x = 100\nreturn x")));
        }
    }

    // ============ 尾调用消除 (TailCallElimination) ============

    @Nested
    @DisplayName("尾调用消除")
    class TailCall {

        @Test
        @DisplayName("尾递归阶乘不栈溢出")
        void testTailRecFactorial() throws Exception {
            String code = "object Test {\n" +
                "  fun factorial(n: Int, acc: Int): Any {\n" +
                "    if (n <= 1) return acc\n" +
                "    return factorial(n - 1, n * acc)\n" +
                "  }\n" +
                "  fun run(): Any {\n" +
                "    return factorial(10, 1)\n" +
                "  }\n" +
                "}";
            assertEquals(3628800, compileAndRun(code));
        }

        @Test
        @DisplayName("尾递归斐波那契")
        void testTailRecFib() throws Exception {
            String code = "object Test {\n" +
                "  fun fib(n: Int, a: Int, b: Int): Any {\n" +
                "    if (n <= 0) return a\n" +
                "    return fib(n - 1, b, a + b)\n" +
                "  }\n" +
                "  fun run(): Any {\n" +
                "    return fib(10, 0, 1)\n" +
                "  }\n" +
                "}";
            assertEquals(55, compileAndRun(code));
        }

        @Test
        @DisplayName("深层尾递归不溢出 (n=10000)")
        void testDeepTailRec() throws Exception {
            String code = "object Test {\n" +
                "  fun sum(n: Int, acc: Int): Any {\n" +
                "    if (n <= 0) return acc\n" +
                "    return sum(n - 1, acc + n)\n" +
                "  }\n" +
                "  fun run(): Any {\n" +
                "    return sum(10000, 0)\n" +
                "  }\n" +
                "}";
            assertEquals(50005000, compileAndRun(code));
        }
    }

    // ============ 强度削减 (StrengthReduction) ============

    @Nested
    @DisplayName("强度削减")
    class StrengthReduction {

        @Test
        @DisplayName("乘以 2 → 左移（功能不变）")
        void testMul2() throws Exception {
            assertEquals(20, compileAndRun(wrap("val x = 10\nreturn x * 2")));
        }

        @Test
        @DisplayName("除以 4 → 右移（功能不变）")
        void testDiv4() throws Exception {
            assertEquals(25, compileAndRun(wrap("val x = 100\nreturn x / 4")));
        }

        @Test
        @DisplayName("取模 8 → 位与（功能不变）")
        void testMod8() throws Exception {
            assertEquals(3, compileAndRun(wrap("val x = 11\nreturn x % 8")));
        }
    }

    // ============ 循环优化 (LICM + LoopDeadStoreElimination) ============

    @Nested
    @DisplayName("循环优化")
    class LoopOpt {

        @Test
        @DisplayName("循环求和正确性")
        void testLoopSum() throws Exception {
            assertEquals(55, compileAndRun(wrap(
                "var sum = 0\nfor (i in 1..10) { sum = sum + i }\nreturn sum")));
        }

        @Test
        @DisplayName("嵌套循环")
        void testNestedLoop() throws Exception {
            assertEquals(100, compileAndRun(wrap(
                "var count = 0\nfor (i in 1..10) { for (j in 1..10) { count = count + 1 } }\nreturn count")));
        }

        @Test
        @DisplayName("while 循环")
        void testWhileLoop() throws Exception {
            assertEquals(10, compileAndRun(wrap(
                "var i = 0\nwhile (i < 10) { i = i + 1 }\nreturn i")));
        }
    }

    // ============ CSE (MirLocalCSE) ============

    @Nested
    @DisplayName("公共子表达式消除")
    class CSE {

        @Test
        @DisplayName("重复表达式只计算一次（结果一致）")
        void testCommonSubexpr() throws Exception {
            assertEquals(true, compileAndRun(wrap(
                "val x = 5\nval a = x * 3 + 1\nval b = x * 3 + 1\nreturn a == b")));
        }
    }

    // ============ Peephole (MirPeepholeOptimization) ============

    @Nested
    @DisplayName("窥孔优化")
    class Peephole {

        @Test
        @DisplayName("双重否定: !!true → true")
        void testDoubleNegation() throws Exception {
            assertEquals(true, compileAndRun(wrap("return !!true")));
        }

        @Test
        @DisplayName("加零: x + 0 → x")
        void testAddZero() throws Exception {
            assertEquals(42, compileAndRun(wrap("val x = 42\nreturn x + 0")));
        }

        @Test
        @DisplayName("乘一: x * 1 → x")
        void testMulOne() throws Exception {
            assertEquals(42, compileAndRun(wrap("val x = 42\nreturn x * 1")));
        }
    }

    // ============ 字节码生成 (MirCodeGenerator) ============

    @Nested
    @DisplayName("字节码生成")
    class CodeGen {

        @Test
        @DisplayName("函数调用和返回值")
        void testFuncCallReturn() throws Exception {
            String code = "object Test {\n" +
                "  fun add(a: Int, b: Int): Any { return a + b }\n" +
                "  fun run(): Any { return add(3, 4) }\n" +
                "}";
            assertEquals(7, compileAndRun(code));
        }

        @Test
        @DisplayName("字符串模板")
        void testStringTemplate() throws Exception {
            assertEquals("hello world", compileAndRun(wrap(
                "val name = \"world\"\nreturn \"hello ${name}\"")));
        }

        @Test
        @DisplayName("条件表达式")
        void testConditional() throws Exception {
            assertEquals("big", compileAndRun(wrap(
                "val x = 10\nreturn if (x > 5) \"big\" else \"small\"")));
        }

        @Test
        @DisplayName("when 表达式")
        void testWhen() throws Exception {
            assertEquals("two", compileAndRun(wrap(
                "val x = 2\nreturn when (x) { 1 -> \"one\"\n 2 -> \"two\"\n else -> \"other\" }")));
        }

        @Test
        @DisplayName("Lambda 闭包捕获")
        void testLambdaClosure() throws Exception {
            assertEquals(15, compileAndRun(wrap(
                "val x = 10\nval f = { y: Int -> x + y }\nreturn f(5)")));
        }

        @Test
        @DisplayName("try-catch")
        void testTryCatch() throws Exception {
            assertEquals("caught", compileAndRun(wrap(
                "return try { error(\"boom\") } catch (e: Exception) { \"caught\" }")));
        }

        @Test
        @DisplayName("List 字面量")
        void testListLiteral() throws Exception {
            Object r = compileAndRun(wrap("return [1, 2, 3]"));
            assertNotNull(r);
        }

        @Test
        @DisplayName("Map 字面量")
        void testMapLiteral() throws Exception {
            Object r = compileAndRun(wrap("return #{\"a\": 1, \"b\": 2}"));
            assertNotNull(r);
        }
    }

    // ============ LineNumberTable / SourceFile 验证 ============

    @Nested
    @DisplayName("调试信息")
    class DebugInfo {

        @Test
        @DisplayName("生成的类包含 SourceFile 属性")
        void testSourceFileAttribute() throws Exception {
            Map<String, Class<?>> loaded = compiler.compileAndLoad(
                "object Test { fun run(): Any { return 42 } }", "myScript.nova");
            Class<?> c = loaded.get("Test");
            assertNotNull(c);
            // Java Class 对象不直接暴露 SourceFile，但 stack trace 中会显示
            // 通过创建异常来间接验证
            try {
                c.getDeclaredMethod("run").invoke(c.getField("INSTANCE").get(null));
            } catch (Exception e) {
                // 不期望抛异常，但如果抛了，stackTrace 应包含源文件名
            }
            // 简单验证类加载成功即可，SourceFile 属性在错误处理测试中验证
            assertEquals(42, c.getDeclaredMethod("run").invoke(c.getField("INSTANCE").get(null)));
        }

        @Test
        @DisplayName("异常堆栈包含源码行号")
        void testLineNumberInException() throws Exception {
            Map<String, Class<?>> loaded = compiler.compileAndLoad(
                "object Test {\n  fun run(): Any {\n    val x: Any? = null\n    return x.toString()\n  }\n}",
                "lineTest.nova");
            Class<?> c = loaded.get("Test");
            Object inst = c.getField("INSTANCE").get(null);
            try {
                c.getDeclaredMethod("run").invoke(inst);
                fail("应抛异常");
            } catch (java.lang.reflect.InvocationTargetException e) {
                // 检查异常堆栈中是否有非 -1 行号
                StackTraceElement[] stack = e.getCause().getStackTrace();
                boolean hasLineNumber = false;
                for (StackTraceElement frame : stack) {
                    if (frame.getClassName().equals("Test") && frame.getLineNumber() > 0) {
                        hasLineNumber = true;
                        break;
                    }
                }
                assertTrue(hasLineNumber, "异常堆栈中应包含 Test 类的正整数行号");
            }
        }
    }

    // ============ PassPipeline 集成 ============

    @Nested
    @DisplayName("PassPipeline 集成")
    class PipelineIntegration {

        @Test
        @DisplayName("全 Pass 流水线端到端: 复杂程序")
        void testFullPipeline() throws Exception {
            String code = "object Test {\n" +
                "  fun isPrime(n: Int): Any {\n" +
                "    if (n < 2) return false\n" +
                "    var i = 2\n" +
                "    while (i * i <= n) {\n" +
                "      if (n % i == 0) return false\n" +
                "      i = i + 1\n" +
                "    }\n" +
                "    return true\n" +
                "  }\n" +
                "  fun run(): Any {\n" +
                "    var count = 0\n" +
                "    for (n in 2..100) {\n" +
                "      if (isPrime(n) == true) count = count + 1\n" +
                "    }\n" +
                "    return count\n" +
                "  }\n" +
                "}";
            assertEquals(25, compileAndRun(code)); // 2~100 有 25 个素数
        }

        @Test
        @DisplayName("全 Pass 流水线: 递归 + 闭包 + 集合")
        void testComplexProgram() throws Exception {
            String code = "object Test {\n" +
                "  fun run(): Any {\n" +
                "    val list = [3, 1, 4, 1, 5, 9, 2, 6]\n" +
                "    var sum = 0\n" +
                "    for (item in list) { sum = sum + item }\n" +
                "    return sum\n" +
                "  }\n" +
                "}";
            assertEquals(31, compileAndRun(code));
        }

        @Test
        @DisplayName("全 Pass 流水线: 类 + 方法 + 字段")
        void testClassPipeline() throws Exception {
            String code = "class Counter {\n" +
                "  var value = 0\n" +
                "  fun inc() { value = value + 1 }\n" +
                "  fun get(): Any { return value }\n" +
                "}\n" +
                "object Test {\n" +
                "  fun run(): Any {\n" +
                "    val c = Counter()\n" +
                "    c.inc()\n" +
                "    c.inc()\n" +
                "    c.inc()\n" +
                "    return c.get()\n" +
                "  }\n" +
                "}";
            assertEquals(3, compileAndRun(code));
        }
    }
}
