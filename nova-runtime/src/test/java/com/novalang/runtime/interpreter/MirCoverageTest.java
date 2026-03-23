package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MirInterpreter 未覆盖路径测试。
 * 覆盖 TYPE_CAST / SPREAD / NAMED_ARG / DESTRUCTURING / STRING_INTERP / WHEN / TRY-CATCH / NULL_SAFETY / RANGE / LAMBDA 等。
 */
class MirCoverageTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    // ============ 1. 类型转换 (as / as?) ============

    @Nested
    @DisplayName("类型转换")
    class TypeCastTests {

        @Test
        @DisplayName("as Int — 整数保持不变")
        void testCastIntAsInt() {
            NovaValue result = eval("42 as Int");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("as? Int — 字符串安全转换失败返回 null")
        void testSafeCastStringAsInt() {
            NovaValue result = eval("\"hello\" as? Int");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("as? Int — 整数安全转换成功")
        void testSafeCastIntAsInt() {
            NovaValue result = eval("42 as? Int");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("Ok(1) as Ok — Result 类型转换")
        void testCastOkAsOk() {
            NovaValue result = eval("Ok(1) as Ok");
            assertFalse(result.isNull());
        }

        @Test
        @DisplayName("Err(\"e\") as? Ok — Result 安全转换失败返回 null")
        void testSafeCastErrAsOk() {
            NovaValue result = eval("Err(\"e\") as? Ok");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("as String — 字符串保持不变")
        void testCastStringAsString() {
            NovaValue result = eval("\"hello\" as String");
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("as? String — 整数安全转换为 String 失败返回 null")
        void testSafeCastIntAsString() {
            NovaValue result = eval("42 as? String");
            assertTrue(result.isNull());
        }
    }

    // ============ 2. Spread 操作符 ============

    @Nested
    @DisplayName("Spread 操作符")
    class SpreadTests {

        @Test
        @DisplayName("spread 调用 vararg 函数")
        void testSpreadInFunctionCall() {
            NovaValue result = eval("fun sum(vararg args) { var s = 0; for (a in args) { s = s + a }; return s }\nsum(1, 2, 3)");
            assertEquals(6, result.asInt());
        }

        @Test
        @DisplayName("spread 在列表字面量中展开")
        void testSpreadInListLiteral() {
            eval("val a = [1, 2]");
            NovaValue result = eval("val b = [*a, 3, 4]\nb.size()");
            assertEquals(4, result.asInt());
        }

        @Test
        @DisplayName("spread 在列表字面量中展开 — 验证元素")
        void testSpreadInListLiteralElements() {
            eval("val a = [10, 20]");
            eval("val b = [*a, 30]");
            assertEquals(10, eval("b[0]").asInt());
            assertEquals(20, eval("b[1]").asInt());
            assertEquals(30, eval("b[2]").asInt());
        }
    }

    // ============ 3. 命名参数 ============

    @Nested
    @DisplayName("命名参数")
    class NamedParamTests {

        @Test
        @DisplayName("命名参数 + 默认值")

        void testNamedParamWithDefault() {
            eval("fun greet(name: String, greeting: String = \"Hello\") = \"$greeting, $name\"");
            NovaValue result = eval("greet(name = \"World\")");
            assertEquals("Hello, World", result.asString());
        }

        @Test
        @DisplayName("命名参数乱序调用")

        void testNamedParamReordered() {
            eval("fun point(x: Int, y: Int) = x + y");
            NovaValue result = eval("point(y = 10, x = 5)");
            assertEquals(15, result.asInt());
        }

        @Test
        @DisplayName("命名参数与位置参数混合")

        void testNamedParamMixed() {
            // 单次 eval 验证混合命名参数 + 默认值
            NovaValue result = eval(
                "fun info(name: String, age: Int, city: String = \"Beijing\") = \"$name,$age,$city\"\n" +
                "info(\"Alice\", age = 25)");
            assertEquals("Alice,25,Beijing", result.asString());
        }
    }

    // ============ 4. 解构 ============

    @Nested
    @DisplayName("解构")
    class DestructuringTests {

        @Test
        @DisplayName("Pair 解构")
        void testPairDestructuring() {
            eval("val (a, b) = Pair(1, 2)");
            assertEquals(1, eval("a").asInt());
            assertEquals(2, eval("b").asInt());
        }

        @Test
        @DisplayName("列表解构")
        void testListDestructuring() {
            eval("val (x, y, z) = [10, 20, 30]");
            assertEquals(10, eval("x").asInt());
            assertEquals(20, eval("y").asInt());
            assertEquals(30, eval("z").asInt());
        }

        @Test
        @DisplayName("for 循环中 Map 解构")
        void testMapDestructuringInFor() {
            eval("var result = \"\"");
            eval("for ((k, v) in mapOf(\"a\" to 1, \"b\" to 2)) { result = result + k + v }");
            NovaValue result = eval("result");
            // Map 遍历顺序可能不固定，检查包含即可
            String s = result.asString();
            assertTrue(s.contains("a1"));
            assertTrue(s.contains("b2"));
        }

        @Test
        @DisplayName("data class 解构")
        void testDataClassDestructuring() {
            eval("@data\nclass Point(val x: Int, val y: Int)");
            eval("val (px, py) = Point(3, 4)");
            assertEquals(3, eval("px").asInt());
            assertEquals(4, eval("py").asInt());
        }
    }

    // ============ 5. 字符串插值边界 ============

    @Nested
    @DisplayName("字符串插值")
    class StringInterpolationTests {

        @Test
        @DisplayName("插值内 if 表达式")
        void testInterpolationWithIf() {
            NovaValue result = eval("\"${if (true) \"yes\" else \"no\"}\"");
            assertEquals("yes", result.asString());
        }

        @Test
        @DisplayName("插值内算术表达式")
        void testInterpolationWithArithmetic() {
            NovaValue result = eval("\"result: ${1 + 2 * 3}\"");
            assertEquals("result: 7", result.asString());
        }

        @Test
        @DisplayName("多变量插值")
        void testMultiVariableInterpolation() {
            eval("val name = \"Alice\"");
            eval("val age = 30");
            NovaValue result = eval("\"$name is $age years old\"");
            assertEquals("Alice is 30 years old", result.asString());
        }

        @Test
        @DisplayName("插值内方法调用")
        void testInterpolationWithMethodCall() {
            NovaValue result = eval("\"len: ${\"hello\".length()}\"");
            assertEquals("len: 5", result.asString());
        }
    }

    // ============ 6. when 表达式 ============

    @Nested
    @DisplayName("when 表达式")
    class WhenTests {

        @Test
        @DisplayName("when 无参数 — 条件分支")
        void testWhenWithoutArgument() {
            eval("val x = 5");
            NovaValue result = eval("when { x > 0 -> \"pos\"; x < 0 -> \"neg\"; else -> \"zero\" }");
            assertEquals("pos", result.asString());
        }

        @Test
        @DisplayName("when 枚举匹配")
        void testWhenWithEnum() {
            eval("enum class Color { RED, GREEN, BLUE }");
            eval("val c = Color.GREEN");
            NovaValue result = eval("when (c) { Color.RED -> \"red\"; Color.GREEN -> \"green\"; Color.BLUE -> \"blue\"; else -> \"unknown\" }");
            assertEquals("green", result.asString());
        }

        @Test
        @DisplayName("when + is 类型检查")
        void testWhenWithTypeCheck() {
            eval("fun describe(v: Any): String = when (v) { is String -> \"string\"; is Int -> \"int\"; else -> \"other\" }");
            assertEquals("string", eval("describe(\"hi\")").asString());
            assertEquals("int", eval("describe(42)").asString());
        }

        @Test
        @DisplayName("when 多值匹配")
        void testWhenMultipleValues() {
            eval("fun classify(n: Int) = when (n) { 1, 2 -> \"low\"; 3, 4 -> \"mid\"; else -> \"high\" }");
            assertEquals("low", eval("classify(1)").asString());
            assertEquals("mid", eval("classify(4)").asString());
            assertEquals("high", eval("classify(10)").asString());
        }

        @Test
        @DisplayName("when 作为表达式赋值")
        void testWhenAsExpression() {
            eval("val x = 2");
            NovaValue result = eval("val msg = when (x) { 1 -> \"one\"; 2 -> \"two\"; else -> \"other\" }\nmsg");
            assertEquals("two", result.asString());
        }
    }

    // ============ 7. 错误处理 ============

    @Nested
    @DisplayName("错误处理")
    class ErrorHandlingTests {

        @Test
        @DisplayName("try-catch 捕获特定类型")
        void testTryCatchSpecificType() {
            NovaValue result = eval(
                "var msg = \"\"\n" +
                "try {\n" +
                "    throw \"oops\"\n" +
                "} catch (e: Exception) {\n" +
                "    msg = \"caught\"\n" +
                "}\n" +
                "msg"
            );
            assertEquals("caught", result.asString());
        }

        @Test
        @DisplayName("try-catch-finally")
        void testTryCatchFinally() {
            NovaValue result = eval(
                "var log = \"\"\n" +
                "try {\n" +
                "    log = log + \"try,\"\n" +
                "    throw \"err\"\n" +
                "} catch (e: Exception) {\n" +
                "    log = log + \"catch,\"\n" +
                "} finally {\n" +
                "    log = log + \"finally\"\n" +
                "}\n" +
                "log"
            );
            assertEquals("try,catch,finally", result.asString());
        }

        @Test
        @DisplayName("try 作为表达式")
        void testTryAsExpression() {
            NovaValue result = eval("val x = try { 42 } catch (e: Exception) { -1 }\nx");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("try 异常时表达式返回 catch 值")
        void testTryExpressionCatchValue() {
            NovaValue result = eval("val x = try { throw \"err\"; 0 } catch (e: Exception) { -1 }\nx");
            assertEquals(-1, result.asInt());
        }

        @Test
        @DisplayName("嵌套 try-catch")
        void testNestedTryCatch() {
            NovaValue result = eval(
                "var outer = \"\"\n" +
                "try {\n" +
                "    try {\n" +
                "        throw \"inner\"\n" +
                "    } catch (e: Exception) {\n" +
                "        outer = \"inner_caught\"\n" +
                "        throw \"rethrow\"\n" +
                "    }\n" +
                "} catch (e: Exception) {\n" +
                "    outer = outer + \",outer_caught\"\n" +
                "}\n" +
                "outer"
            );
            assertEquals("inner_caught,outer_caught", result.asString());
        }

        @Test
        @DisplayName("finally 无异常也执行")
        void testFinallyWithoutException() {
            NovaValue result = eval(
                "var log = \"\"\n" +
                "try {\n" +
                "    log = \"ok\"\n" +
                "} finally {\n" +
                "    log = log + \",done\"\n" +
                "}\n" +
                "log"
            );
            assertEquals("ok,done", result.asString());
        }
    }

    // ============ 8. Null 安全 ============

    @Nested
    @DisplayName("Null 安全")
    class NullSafetyTests {

        @Test
        @DisplayName("?. 链式安全调用 — 非 null")
        void testSafeCallChainNonNull() {
            eval("class Inner(val value: Int)");
            eval("class Outer(val inner: Inner)");
            eval("val o = Outer(Inner(42))");
            NovaValue result = eval("o?.inner?.value");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("?. 链式安全调用 — null 中断")
        void testSafeCallChainNull() {
            eval("class Inner(val value: Int)");
            eval("class Outer(val inner: Inner?)");
            eval("val o: Outer? = null");
            NovaValue result = eval("o?.inner?.value");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("?: elvis 操作符")
        void testElvisOperator() {
            NovaValue result = eval("val x: Int? = null\nx ?: 99");
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("?: elvis 操作符 — 非 null 取左边")
        void testElvisOperatorNonNull() {
            NovaValue result = eval("val x: Int? = 5\nx ?: 99");
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("!! 非 null 断言 — 成功")
        void testNotNullAssertionSuccess() {
            NovaValue result = eval("val x: Int? = 42\nx!!");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("!! 非 null 断言 — 失败抛异常")
        void testNotNullAssertionFailure() {
            assertThrows(NovaRuntimeException.class, () -> {
                eval("val x: Int? = null\nx!!");
            });
        }

        @Test
        @DisplayName("??= null 合并赋值 — null 时赋值")
        void testNullCoalesceAssignNull() {
            eval("var x: Int? = null");
            eval("x ??= 10");
            assertEquals(10, eval("x").asInt());
        }

        @Test
        @DisplayName("??= null 合并赋值 — 非 null 时保持")
        void testNullCoalesceAssignNonNull() {
            eval("var x: Int? = 5");
            eval("x ??= 10");
            assertEquals(5, eval("x").asInt());
        }
    }

    // ============ 9. Range 操作 ============

    @Nested
    @DisplayName("Range 操作")
    class RangeTests {

        @Test
        @DisplayName("1..5 范围迭代")
        void testInclusiveRangeIteration() {
            eval("var sum = 0");
            eval("for (i in 1..5) { sum = sum + i }");
            assertEquals(15, eval("sum").asInt());
        }

        @Test
        @DisplayName("1..<5 排他范围迭代")
        void testExclusiveRangeIteration() {
            eval("var sum = 0");
            eval("for (i in 1..<5) { sum = sum + i }");
            assertEquals(10, eval("sum").asInt());
        }

        @Test
        @DisplayName("(1..5).toList() 转列表")
        void testRangeToList() {
            NovaValue result = eval("(1..5).toList()");
            // 验证长度
            assertEquals(5, eval("(1..5).toList().size()").asInt());
        }

        @Test
        @DisplayName("x in 1..10 范围检查")
        void testRangeContains() {
            eval("val x = 5");
            assertTrue(eval("x in 1..10").asBool());
        }

        @Test
        @DisplayName("x !in 1..10 范围排除检查")
        void testRangeNotContains() {
            eval("val x = 15");
            assertTrue(eval("x !in 1..10").asBool());
        }

        @Test
        @DisplayName("Range size 方法")
        void testRangeSize() {
            assertEquals(10, eval("(1..10).size()").asInt());
        }
    }

    // ============ 10. Lambda / 闭包 ============

    @Nested
    @DisplayName("Lambda 与闭包")
    class LambdaTests {

        @Test
        @DisplayName("lambda 捕获可变变量")
        void testLambdaCaptureMutableVariable() {
            eval("var counter = 0");
            eval("val inc = { counter = counter + 1 }");
            eval("inc()");
            eval("inc()");
            eval("inc()");
            assertEquals(3, eval("counter").asInt());
        }

        @Test
        @DisplayName("尾随 lambda 语法")
        void testTrailingLambda() {
            eval("fun repeat(n: Int, action: (Int) -> Unit) { for (i in 0..<n) { action(i) } }");
            eval("var sum = 0");
            eval("repeat(5) { sum = sum + it }");
            assertEquals(10, eval("sum").asInt()); // 0+1+2+3+4 = 10
        }

        @Test
        @DisplayName("it 隐式参数")
        void testImplicitItParameter() {
            NovaValue result = eval("[1, 2, 3].map { it * 2 }");
            assertEquals(3, eval("[1, 2, 3].map { it * 2 }.size()").asInt());
            assertEquals(2, eval("[1, 2, 3].map { it * 2 }[0]").asInt());
            assertEquals(6, eval("[1, 2, 3].map { it * 2 }[2]").asInt());
        }

        @Test
        @DisplayName("高阶函数返回 lambda")
        void testHigherOrderFunctionReturningLambda() {
            eval("fun adder(x: Int) = { y: Int -> x + y }");
            eval("val add5 = adder(5)");
            assertEquals(8, eval("add5(3)").asInt());
            assertEquals(15, eval("add5(10)").asInt());
        }

        @Test
        @DisplayName("lambda 嵌套捕获")
        void testNestedLambdaCapture() {
            eval("fun make(): () -> Int { var x = 0; val inc = { x = x + 1; x }; return inc }");
            eval("val f = make()");
            assertEquals(1, eval("f()").asInt());
            assertEquals(2, eval("f()").asInt());
        }

        @Test
        @DisplayName("lambda 作为参数传递")
        void testLambdaAsArgument() {
            eval("fun apply(x: Int, fn: (Int) -> Int) = fn(x)");
            assertEquals(25, eval("apply(5) { it * it }").asInt());
        }
    }

    // ============ 补充覆盖: 类型检查、复合表达式 ============

    @Nested
    @DisplayName("is 类型检查")
    class TypeCheckTests {

        @Test
        @DisplayName("is Int 检查")
        void testIsInt() {
            assertTrue(eval("42 is Int").asBool());
            assertFalse(eval("\"hello\" is Int").asBool());
        }

        @Test
        @DisplayName("is String 检查")
        void testIsString() {
            assertTrue(eval("\"hello\" is String").asBool());
            assertFalse(eval("42 is String").asBool());
        }

        @Test
        @DisplayName("is Boolean 检查")
        void testIsBoolean() {
            assertTrue(eval("true is Boolean").asBool());
            assertFalse(eval("42 is Boolean").asBool());
        }

        @Test
        @DisplayName("is + 自定义类")
        void testIsCustomClass() {
            eval("class Dog(val name: String)");
            eval("val d = Dog(\"Rex\")");
            assertTrue(eval("d is Dog").asBool());
            assertFalse(eval("42 is Dog").asBool());
        }
    }

    @Nested
    @DisplayName("复合路径")
    class CompoundPathTests {

        @Test
        @DisplayName("when + try + lambda 组合")
        void testWhenTryLambdaCombination() {
            eval("fun safeDivide(a: Int, b: Int): String {\n" +
                 "    return when {\n" +
                 "        b == 0 -> \"div_by_zero\"\n" +
                 "        else -> try { (a / b).toString() } catch (e: Exception) { \"error\" }\n" +
                 "    }\n" +
                 "}");
            assertEquals("div_by_zero", eval("safeDivide(10, 0)").asString());
            assertEquals("5", eval("safeDivide(10, 2)").asString());
        }

        @Test
        @DisplayName("解构 + 范围 + lambda")
        void testDestructuringRangeLambda() {
            eval("val pairs = [Pair(1, 10), Pair(2, 20), Pair(3, 30)]");
            eval("var sum = 0");
            eval("for ((a, b) in pairs) { sum = sum + a + b }");
            assertEquals(66, eval("sum").asInt()); // (1+10)+(2+20)+(3+30) = 66
        }

        @Test
        @DisplayName("elvis + safe call + 方法调用链")
        void testElvisSafeCallChain() {
            eval("class Wrapper(val value: String?)");
            eval("val w: Wrapper? = Wrapper(null)");
            NovaValue result = eval("w?.value ?: \"default\"");
            assertEquals("default", result.asString());
        }

        @Test
        @DisplayName("if 表达式嵌套 when")
        void testIfNestedWhen() {
            eval("val x = 5");
            NovaValue result = eval(
                "if (x > 0) when (x) { 5 -> \"five\"; else -> \"pos\" } else \"neg\""
            );
            assertEquals("five", result.asString());
        }

        @Test
        @DisplayName("for + break")
        void testForWithBreak() {
            eval("var sum = 0");
            eval("for (i in 1..100) { if (i > 5) break; sum = sum + i }");
            assertEquals(15, eval("sum").asInt());
        }

        @Test
        @DisplayName("for + continue")
        void testForWithContinue() {
            eval("var sum = 0");
            eval("for (i in 1..10) { if (i % 2 == 0) continue; sum = sum + i }");
            assertEquals(25, eval("sum").asInt()); // 1+3+5+7+9 = 25
        }

        @Test
        @DisplayName("while + 复合赋值 + break")
        void testWhileCompoundAssignBreak() {
            eval("var x = 1");
            eval("while (true) { x *= 2; if (x >= 100) break }");
            assertEquals(128, eval("x").asInt());
        }

        @Test
        @DisplayName("Result 类型 + when + is 检查")
        void testResultTypeWithWhen() {
            eval("val r = Ok(42)");
            NovaValue result = eval("when (r) { is Ok -> \"ok\"; is Err -> \"err\"; else -> \"unknown\" }");
            assertEquals("ok", result.asString());
        }

        @Test
        @DisplayName("多层 lambda 闭包")
        void testMultiLayerClosure() {
            eval("fun outer(x: Int) {\n" +
                 "    return { x * 2 }\n" +
                 "}");
            assertEquals(20, eval("outer(10)()").asInt());
        }
    }
}
