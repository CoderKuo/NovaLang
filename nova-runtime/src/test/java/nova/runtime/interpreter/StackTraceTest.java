package nova.runtime.interpreter;

import nova.runtime.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 调用堆栈跟踪单元测试
 */
class StackTraceTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
    }

    // ============ NovaCallFrame 测试 ============

    @Nested
    @DisplayName("NovaCallFrame")
    class CallFrameTests {

        @Test
        @DisplayName("参数摘要截断 - 长字符串值")
        void testParamSummaryTruncation() {
            NovaCallFrame frame = new NovaCallFrame(
                "test", "test.nova", 1, 1,
                "a: this_is_a_very_long_string_that_should_be_truncated"
            );
            assertEquals("test", frame.getFunctionName());
            assertEquals("test.nova", frame.getFileName());
            assertEquals(1, frame.getLine());
        }

        @Test
        @DisplayName("基本属性")
        void testBasicProperties() {
            NovaCallFrame frame = new NovaCallFrame("myFunc", "app.nova", 10, 5, "x: 1, y: 2");
            assertEquals("myFunc", frame.getFunctionName());
            assertEquals("app.nova", frame.getFileName());
            assertEquals(10, frame.getLine());
            assertEquals(5, frame.getColumn());
            assertEquals("x: 1, y: 2", frame.getParamSummary());
        }
    }

    // ============ NovaCallStack 测试 ============

    @Nested
    @DisplayName("NovaCallStack")
    class CallStackTests {

        @Test
        @DisplayName("push 和 pop 基本操作")
        void testPushPop() {
            NovaCallStack stack = new NovaCallStack();
            assertEquals(0, stack.size());
            assertNull(stack.peek());

            NovaCallFrame frame1 = new NovaCallFrame("func1", "a.nova", 1, 1, "");
            stack.push(frame1);
            assertEquals(1, stack.size());
            assertEquals(frame1, stack.peek());

            NovaCallFrame frame2 = new NovaCallFrame("func2", "a.nova", 5, 1, "");
            stack.push(frame2);
            assertEquals(2, stack.size());
            assertEquals(frame2, stack.peek());

            stack.pop();
            assertEquals(1, stack.size());
            assertEquals(frame1, stack.peek());

            stack.pop();
            assertEquals(0, stack.size());
            assertNull(stack.peek());
        }

        @Test
        @DisplayName("pop 空栈不报错")
        void testPopEmpty() {
            NovaCallStack stack = new NovaCallStack();
            stack.pop(); // 不应抛异常
            assertEquals(0, stack.size());
        }

        @Test
        @DisplayName("clear 清空所有帧")
        void testClear() {
            NovaCallStack stack = new NovaCallStack();
            stack.push(new NovaCallFrame("f1", "a.nova", 1, 1, ""));
            stack.push(new NovaCallFrame("f2", "a.nova", 2, 1, ""));
            stack.push(new NovaCallFrame("f3", "a.nova", 3, 1, ""));
            assertEquals(3, stack.size());

            stack.clear();
            assertEquals(0, stack.size());
            assertNull(stack.peek());
        }

        @Test
        @DisplayName("空栈格式化返回 null")
        void testFormatEmptyStack() {
            NovaCallStack stack = new NovaCallStack();
            assertNull(stack.formatStackTrace(null, null));
        }

        @Test
        @DisplayName("格式化包含 Call Stack 标题")
        void testFormatContainsHeader() {
            NovaCallStack stack = new NovaCallStack();
            stack.push(new NovaCallFrame("myFunc", "test.nova", 5, 1, "x: 42"));
            String trace = stack.formatStackTrace(null, null);
            assertNotNull(trace);
            assertTrue(trace.contains("Call Stack:"));
        }

        @Test
        @DisplayName("格式化包含函数名和文件信息")
        void testFormatContainsFuncInfo() {
            NovaCallStack stack = new NovaCallStack();
            stack.push(new NovaCallFrame("calculate", "math.nova", 15, 1, "a: 10"));
            String trace = stack.formatStackTrace(null, null);
            assertTrue(trace.contains("calculate"));
            assertTrue(trace.contains("math.nova:15"));
            assertTrue(trace.contains("a: 10"));
        }

        @Test
        @DisplayName("多帧格式化 - 最新帧在前")
        void testFormatFrameOrder() {
            NovaCallStack stack = new NovaCallStack();
            stack.push(new NovaCallFrame("outer", "a.nova", 1, 1, ""));
            stack.push(new NovaCallFrame("middle", "a.nova", 5, 1, ""));
            stack.push(new NovaCallFrame("inner", "a.nova", 10, 1, ""));
            String trace = stack.formatStackTrace(null, null);

            // inner (most recent) should appear before outer
            int innerIdx = trace.indexOf("inner");
            int outerIdx = trace.indexOf("outer");
            assertTrue(innerIdx < outerIdx, "Most recent frame (inner) should appear first");
        }

        @Test
        @DisplayName("源代码行显示")
        void testFormatWithSourceLines() {
            NovaCallStack stack = new NovaCallStack();
            stack.push(new NovaCallFrame("divide", "test.nova", 3, 1, "a: 10, b: 0"));

            String[] sourceLines = {
                "fun divide(a: Int, b: Int): Int {",
                "    // division",
                "    return a / b",
                "}"
            };
            String trace = stack.formatStackTrace(sourceLines, "test.nova");
            assertTrue(trace.contains("return a / b"), "Should contain the source line");
        }

        @Test
        @DisplayName("不同文件的帧不显示源码行")
        void testFormatDifferentFileNoSource() {
            NovaCallStack stack = new NovaCallStack();
            stack.push(new NovaCallFrame("helper", "other.nova", 1, 1, ""));

            String[] sourceLines = {"val x = 1"};
            String trace = stack.formatStackTrace(sourceLines, "main.nova");
            // 不应包含源码行，因为帧来自 other.nova 而 sourceLines 属于 main.nova
            assertFalse(trace.contains("val x = 1"));
        }

        @Test
        @DisplayName("超过限制帧数时折叠显示")
        void testFormatFoldedDisplay() {
            NovaCallStack stack = new NovaCallStack();
            for (int i = 0; i < 20; i++) {
                stack.push(new NovaCallFrame("func" + i, "a.nova", i + 1, 1, ""));
            }
            String trace = stack.formatStackTrace(null, null, 16);
            assertTrue(trace.contains("frames omitted"), "Should contain folded indicator");
            assertTrue(trace.contains("4 frames omitted"), "Should omit 4 frames (20 - 16)");
        }

        @Test
        @DisplayName("恰好等于限制时不折叠")
        void testFormatExactLimit() {
            NovaCallStack stack = new NovaCallStack();
            for (int i = 0; i < 16; i++) {
                stack.push(new NovaCallFrame("func" + i, "a.nova", i + 1, 1, ""));
            }
            String trace = stack.formatStackTrace(null, null, 16);
            assertFalse(trace.contains("frames omitted"), "Should not fold when exactly at limit");
        }
    }

    // ============ 集成测试: 解释器堆栈跟踪 ============

    @Nested
    @DisplayName("解释器堆栈跟踪集成")
    class InterpreterStackTraceTests {

        @Test
        @DisplayName("多层函数调用的堆栈跟踪")
        void testMultiLayerFunctionCallStack() {
            String code = ""
                + "fun divide(a: Int, b: Int): Int {\n"
                + "    return a / b\n"
                + "}\n"
                + "fun calculate(x: Int): Int {\n"
                + "    return divide(x, 0)\n"
                + "}\n"
                + "calculate(10)";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "math.nova");
            });

            String msg = ex.getMessage();
            // 应包含 Call Stack 标题
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack header, got: " + msg);
            // 应包含 divide 和 calculate 函数名
            assertTrue(msg.contains("divide"), "Should contain 'divide' in stack trace");
            assertTrue(msg.contains("calculate"), "Should contain 'calculate' in stack trace");
        }

        @Test
        @DisplayName("Lambda 中抛错的堆栈跟踪")
        void testLambdaStackTrace() {
            String code = ""
                + "fun process(items: List<Int>) {\n"
                + "    items.forEach { item ->\n"
                + "        if (item == 0) throw \"zero found\"\n"
                + "    }\n"
                + "}\n"
                + "process(listOf(1, 2, 0, 3))";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "app.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack, got: " + msg);
            // 应包含 lambda 标记
            assertTrue(msg.contains("<lambda"), "Should contain lambda frame indicator");
        }

        @Test
        @DisplayName("构造器中抛错的堆栈跟踪")
        void testConstructorStackTrace() {
            String code = ""
                + "class Validator(val value: Int) {\n"
                + "    fun validate() {\n"
                + "        if (value < 0) throw \"negative value\"\n"
                + "    }\n"
                + "}\n"
                + "fun createValidator(v: Int) {\n"
                + "    val obj = Validator(v)\n"
                + "    obj.validate()\n"
                + "}\n"
                + "createValidator(-1)";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "validator.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack, got: " + msg);
            assertTrue(msg.contains("createValidator"), "Should contain createValidator in stack trace");
        }

        @Test
        @DisplayName("递归函数的堆栈跟踪")
        void testRecursionStackTrace() {
            String code = ""
                + "fun countdown(n: Int): Int {\n"
                + "    if (n == 0) throw \"boom\"\n"
                + "    return countdown(n - 1)\n"
                + "}\n"
                + "countdown(5)";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "recurse.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack, got: " + msg);
            assertTrue(msg.contains("countdown"), "Should contain countdown in stack trace");
        }

        @Test
        @DisplayName("深层递归触发折叠显示")
        void testDeepRecursionFolding() {
            String code = ""
                + "fun deep(n: Int): Int {\n"
                + "    if (n == 0) throw \"bottom\"\n"
                + "    return deep(n - 1)\n"
                + "}\n"
                + "deep(20)";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "deep.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack");
            // 21 层调用（deep(20)到deep(0)），超过 16 帧限制应折叠
            assertTrue(msg.contains("frames omitted"), "Should fold frames for deep recursion, got: " + msg);
        }

        @Test
        @DisplayName("无函数调用的错误不应显示 Call Stack")
        void testTopLevelErrorNoCallStack() {
            String code = "val x = 1 / 0";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "simple.nova");
            });

            String msg = ex.getMessage();
            // 顶层错误没有调用帧，不应有 Call Stack
            assertFalse(msg.contains("Call Stack:"),
                "Top-level error should not have Call Stack, got: " + msg);
        }

        @Test
        @DisplayName("单层函数调用的堆栈跟踪")
        void testSingleFunctionCallStack() {
            String code = ""
                + "fun boom() {\n"
                + "    throw \"error\"\n"
                + "}\n"
                + "boom()";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "single.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack");
            assertTrue(msg.contains("boom"), "Should contain function name 'boom'");
        }

        @Test
        @DisplayName("参数值显示在堆栈跟踪中")
        void testParamValuesInStackTrace() {
            String code = ""
                + "fun greet(name: String) {\n"
                + "    throw \"error\"\n"
                + "}\n"
                + "greet(\"Alice\")";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "params.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack");
            assertTrue(msg.contains("greet"), "Should contain function name");
            assertTrue(msg.contains("Alice"), "Should show parameter value 'Alice'");
        }

        @Test
        @DisplayName("REPL 模式的堆栈跟踪")
        void testReplStackTrace() {
            interpreter.setReplMode(true);
            // 先定义函数
            interpreter.evalRepl(
                "fun failInner() { throw \"inner error\" }\n" +
                "fun failOuter() { failInner() }"
            );

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("failOuter()");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack in REPL, got: " + msg);
            assertTrue(msg.contains("failInner"), "Should contain failInner");
            assertTrue(msg.contains("failOuter"), "Should contain failOuter");
        }

        @Test
        @DisplayName("正常执行不受影响")
        void testNormalExecutionUnaffected() {
            String code = ""
                + "fun add(a: Int, b: Int): Int = a + b\n"
                + "fun calculate(): Int {\n"
                + "    val x = add(1, 2)\n"
                + "    val y = add(3, 4)\n"
                + "    return x + y\n"
                + "}\n"
                + "calculate()";

            NovaValue result = interpreter.eval(code, "normal.nova");
            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("嵌套 lambda 的堆栈跟踪")
        void testNestedLambdaStackTrace() {
            String code = ""
                + "fun outer() {\n"
                + "    val fn = { throw \"nested lambda error\" }\n"
                + "    fn()\n"
                + "}\n"
                + "outer()";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "nested.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack");
            assertTrue(msg.contains("outer"), "Should contain outer function");
        }

        @Test
        @DisplayName("方法调用的堆栈跟踪")
        void testMethodCallStackTrace() {
            String code = ""
                + "class Calculator {\n"
                + "    fun divide(a: Int, b: Int): Int = a / b\n"
                + "}\n"
                + "fun doCalc() {\n"
                + "    val calc = Calculator()\n"
                + "    calc.divide(10, 0)\n"
                + "}\n"
                + "doCalc()";

            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "method.nova");
            });

            String msg = ex.getMessage();
            assertTrue(msg.contains("Call Stack:"), "Should contain Call Stack, got: " + msg);
            assertTrue(msg.contains("divide"), "Should contain method name 'divide'");
            assertTrue(msg.contains("doCalc"), "Should contain caller function 'doCalc'");
        }
    }
}
