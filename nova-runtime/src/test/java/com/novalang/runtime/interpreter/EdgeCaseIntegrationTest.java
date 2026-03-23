package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MIR 解释器边界场景集成测试 — 覆盖控制流、调用分派、二元运算、
 * 成员解析、解构、安全调用等未充分覆盖的路径。
 */
@DisplayName("MIR 解释器边界场景")
class EdgeCaseIntegrationTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) { return interpreter.evalRepl(code); }

    /** 捕获 println 输出 */
    private String captureOutput(String code) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        PrintStream old = interpreter.getStdout();
        interpreter.setStdout(ps);
        try {
            eval(code);
            return baos.toString(StandardCharsets.UTF_8).trim().replace("\r\n", "\n");
        } finally {
            interpreter.setStdout(old);
        }
    }

    // ================================================================
    // 1. ControlFlow — break/continue/return 复杂场景
    // ================================================================

    @Nested
    @DisplayName("1. 复杂控制流")
    class ControlFlowEdgeCases {

        @Test
        @DisplayName("labeled break 跳出外层 for 循环")
        void testLabeledBreakFor() {
            NovaValue result = eval(
                "var r = 0\n" +
                "outer@ for (i in 1..3) {\n" +
                "    for (j in 1..3) {\n" +
                "        if (j == 2) break@outer\n" +
                "        r = r + 1\n" +
                "    }\n" +
                "}\n" +
                "r");
            // i=1, j=1 → r=1, j=2 → break@outer → 结束
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("labeled continue 跳过外层 for 循环当次迭代")
        void testLabeledContinueFor() {
            NovaValue result = eval(
                "var r = 0\n" +
                "outer@ for (i in 1..3) {\n" +
                "    for (j in 1..3) {\n" +
                "        if (j == 2) continue@outer\n" +
                "        r = r + 1\n" +
                "    }\n" +
                "}\n" +
                "r");
            // 每次 i 迭代: j=1 → r+1, j=2 → continue@outer，共 3 次 → r=3
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("labeled break 跳出外层 while 循环")
        void testLabeledBreakWhile() {
            NovaValue result = eval(
                "var i = 0\n" +
                "var count = 0\n" +
                "outer@ while (i < 5) {\n" +
                "    var j = 0\n" +
                "    while (j < 5) {\n" +
                "        if (i == 2 && j == 1) break@outer\n" +
                "        j = j + 1\n" +
                "        count = count + 1\n" +
                "    }\n" +
                "    i = i + 1\n" +
                "}\n" +
                "count");
            // i=0: j 循环 5 次(count=5), i=1: 5 次(count=10), i=2: j=0→count=11, j=1→break@outer
            assertEquals(11, result.asInt());
        }

        @Test
        @DisplayName("return 从嵌套函数调用中返回")
        void testReturnFromNestedFunctionCall() {
            eval("fun findFirst(list: List<Int>, pred: (Int) -> Boolean): Int {\n" +
                 "    for (item in list) {\n" +
                 "        if (pred(item)) return item\n" +
                 "    }\n" +
                 "    return -1\n" +
                 "}");
            assertEquals(3, eval("findFirst([1, 2, 3, 4, 5]) { it > 2 }").asInt());
            assertEquals(-1, eval("findFirst([1, 2]) { it > 10 }").asInt());
        }

        @Test
        @DisplayName("continue 在 while 循环中跳过偶数")
        void testContinueInWhileWithComplexCondition() {
            NovaValue result = eval(
                "var sum = 0\n" +
                "var i = 0\n" +
                "while (i < 10) {\n" +
                "    i = i + 1\n" +
                "    if (i % 2 == 0) continue\n" +
                "    sum = sum + i\n" +
                "}\n" +
                "sum");
            // 1+3+5+7+9 = 25
            assertEquals(25, result.asInt());
        }

        @Test
        @DisplayName("break 在 when 块内的 for 循环中")
        void testBreakInsideWhenInForLoop() {
            NovaValue result = eval(
                "var result = 0\n" +
                "for (i in 1..10) {\n" +
                "    when {\n" +
                "        i > 5 -> break\n" +
                "        i % 2 == 1 -> result = result + i\n" +
                "    }\n" +
                "}\n" +
                "result");
            // i=1→+1, i=2→skip, i=3→+3, i=4→skip, i=5→+5 → i=6→break → result=9
            assertEquals(9, result.asInt());
        }

        @Test
        @DisplayName("多层 break 只跳出一层")
        void testSingleBreakOnlyExitsInnerLoop() {
            NovaValue result = eval(
                "var count = 0\n" +
                "for (i in 1..3) {\n" +
                "    for (j in 1..3) {\n" +
                "        if (j == 2) break\n" +
                "        count = count + 1\n" +
                "    }\n" +
                "}\n" +
                "count");
            // 每次内循环 j=1 → count+1, j=2 → break。共 3 次外循环 → count=3
            assertEquals(3, result.asInt());
        }
    }

    // ================================================================
    // 2. MirCallDispatcher 边界
    // ================================================================

    @Nested
    @DisplayName("2. 调用分派边界")
    class CallDispatcherEdgeCases {

        @Test
        @DisplayName("null 上调用方法抛异常")
        void testMethodCallOnNull() {
            eval("val x: String? = null");
            assertThrows(NovaRuntimeException.class, () -> eval("x.length()"));
        }

        @Test
        @DisplayName("super 方法调用")
        void testSuperMethodCall() {
            eval("class Parent {\n" +
                 "    fun greet() = \"Hello\"\n" +
                 "}");
            eval("class Child : Parent {\n" +
                 "    override fun greet() = super.greet() + \" World\"\n" +
                 "}");
            assertEquals("Hello World", eval("Child().greet()").asString());
        }

        @Test
        @DisplayName("未定义方法报错")
        void testMethodNotFoundError() {
            eval("class Foo { }");
            eval("val foo = Foo()");
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class,
                () -> eval("foo.nonExistent()"));
            assertTrue(ex.getMessage().contains("nonExistent") ||
                       ex.getMessage().toLowerCase().contains("method") ||
                       ex.getMessage().toLowerCase().contains("not found"),
                "期望错误消息包含方法名或 'not found'，实际: " + ex.getMessage());
        }

        @Test
        @DisplayName("参数数量不匹配报错")
        void testWrongArgumentCount() {
            eval("fun add(a: Int, b: Int) = a + b");
            assertThrows(Exception.class, () -> eval("add(1)"));
        }
    }

    // ================================================================
    // 3. StaticMethodDispatcher 边界
    // ================================================================

    @Nested
    @DisplayName("3. 静态分派边界")
    class StaticDispatcherEdgeCases {

        @Test
        @DisplayName("未定义变量报错")
        void testUndefinedVariableError() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class,
                () -> eval("notDefined + 1"));
            assertTrue(ex.getMessage().contains("notDefined") ||
                       ex.getMessage().toLowerCase().contains("undefined"),
                "期望错误消息包含变量名，实际: " + ex.getMessage());
        }

        @Test
        @DisplayName("枚举静态方法 values/valueOf")
        void testEnumStaticMethods() {
            eval("enum class Color { RED, GREEN, BLUE }");
            NovaValue values = eval("Color.values()");
            assertTrue(values instanceof NovaList);
            assertEquals(3, ((NovaList) values).size());

            NovaValue green = eval("Color.valueOf(\"GREEN\")");
            assertEquals("GREEN", green.toString());
        }

        @Test
        @DisplayName("类型转换函数参数类型错误")
        void testStdlibFunctionWithBadArgs() {
            assertThrows(Exception.class, () -> eval("toInt(\"not_a_number\")"));
        }
    }

    // ================================================================
    // 4. BinaryOps 未覆盖路径
    // ================================================================

    @Nested
    @DisplayName("4. 二元运算边界")
    class BinaryOpsEdgeCases {

        @Test
        @DisplayName("字符串比较 — 字典序")
        void testStringComparison() {
            assertTrue(eval("\"apple\" < \"banana\"").asBool());
            assertFalse(eval("\"banana\" < \"apple\"").asBool());
            assertTrue(eval("\"abc\" <= \"abc\"").asBool());
            assertTrue(eval("\"xyz\" >= \"abc\"").asBool());
            assertTrue(eval("\"abc\" > \"ab\"").asBool());
        }

        @Test
        @DisplayName("null == null 为 true")
        void testNullEqualsNull() {
            assertTrue(eval("null == null").asBool());
        }

        @Test
        @DisplayName("null != 非null值 为 true")
        void testNullNotEqualsValue() {
            assertTrue(eval("null != 1").asBool());
            assertTrue(eval("1 != null").asBool());
            assertFalse(eval("null == 1").asBool());
        }

        @Test
        @DisplayName("Boolean AND 短路 — 第一个 false 不执行第二个")
        void testBooleanAndShortCircuit() {
            eval("var sideEffect = false");
            eval("fun effect() { sideEffect = true; return true }");
            eval("false && effect()");
            assertFalse(eval("sideEffect").asBool());
        }

        @Test
        @DisplayName("Boolean OR 短路 — 第一个 true 不执行第二个")
        void testBooleanOrShortCircuit() {
            eval("var sideEffect = false");
            eval("fun effect() { sideEffect = true; return false }");
            eval("true || effect()");
            assertFalse(eval("sideEffect").asBool());
        }

        @Test
        @DisplayName("Int 与 Double 混合运算")
        void testIntDoubleMixedArithmetic() {
            NovaValue result = eval("3 + 2.5");
            assertEquals(5.5, result.asDouble(), 0.001);

            NovaValue result2 = eval("10 / 4.0");
            assertEquals(2.5, result2.asDouble(), 0.001);
        }

        @Test
        @DisplayName("字符串与非字符串拼接")
        void testStringConcatMixed() {
            assertEquals("val=true", eval("\"val=\" + true").asString());
            assertEquals("null", eval("\"\" + null").asString());
            assertEquals("list=[1, 2, 3]", eval("\"list=\" + [1, 2, 3]").asString());
        }
    }

    // ================================================================
    // 5. VirtualMethodDispatcher — toString/equals/hashCode
    // ================================================================

    @Nested
    @DisplayName("5. 虚方法分派")
    class VirtualMethodEdgeCases {

        @Test
        @DisplayName("自定义类 toString()")
        void testCustomToString() {
            eval("class Person(val name: String) {\n" +
                 "    fun toString() = \"Person($name)\"\n" +
                 "}");
            assertEquals("Person(Alice)", eval("Person(\"Alice\").toString()").asString());
        }

        @Test
        @DisplayName("@data class 自动生成 toString/equals")
        void testDataClassToStringEquals() {
            eval("@data class Point(val x: Int, val y: Int)");
            NovaValue str = eval("Point(1, 2).toString()");
            assertEquals("Point(x=1, y=2)", str.asString());

            assertTrue(eval("Point(1, 2).equals(Point(1, 2))").asBool());
            assertFalse(eval("Point(1, 2).equals(Point(3, 4))").asBool());
        }

        @Test
        @DisplayName("operator == 使用自定义 equals")
        void testOperatorEqualsWithCustomClass() {
            eval("@data class Id(val value: Int)");
            assertTrue(eval("Id(42) == Id(42)").asBool());
            assertFalse(eval("Id(42) == Id(99)").asBool());
        }
    }

    // ================================================================
    // 6. MirInterpreter 未覆盖操作
    // ================================================================

    @Nested
    @DisplayName("6. MIR 解释器操作覆盖")
    class MirInterpreterOps {

        @Test
        @DisplayName("解构赋值 — @data class componentN")
        void testDestructuringAssignment() {
            eval("@data class Pair(val first: Int, val second: Int)");
            eval("val (a, b) = Pair(3, 7)");
            assertEquals(3, eval("a").asInt());
            assertEquals(7, eval("b").asInt());
        }

        @Test
        @DisplayName("解构赋值 — 内置 Pair")
        void testDestructuringBuiltinPair() {
            eval("val (k, v) = Pair(\"key\", 42)");
            assertEquals("key", eval("k").asString());
            assertEquals(42, eval("v").asInt());
        }

        @Test
        @DisplayName("Spread 操作符展开列表到函数调用")
        void testSpreadOperatorInFunctionCall() {
            eval("fun sum(a: Int, b: Int, c: Int) = a + b + c");
            eval("val args = [10, 20, 30]");
            assertEquals(60, eval("sum(*args)").asInt());
        }

        @Test
        @DisplayName("Spread 操作符在列表字面量中")
        void testSpreadInListLiteral() {
            eval("val a = [1, 2]");
            eval("val b = [3, 4]");
            NovaValue result = eval("[0, *a, *b, 5]");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(6, list.size());
            assertEquals(0, list.get(0).asInt());
            assertEquals(5, list.get(5).asInt());
        }

        @Test
        @DisplayName("字符串插值 — 复合表达式")
        void testStringInterpolationComplexExpr() {
            NovaValue result = eval("\"result: ${1 + 2 * 3}\"");
            assertEquals("result: 7", result.asString());
        }

        @Test
        @DisplayName("字符串插值 — 方法调用和变量混合")
        void testStringInterpolationMixed() {
            eval("val name = \"Nova\"");
            eval("val list = [1, 2, 3]");
            NovaValue result = eval("\"$name has ${list.size()} items\"");
            assertEquals("Nova has 3 items", result.asString());
        }

        @Test
        @DisplayName("Elvis 操作符 — 配合函数调用")
        void testElvisWithFunctionCall() {
            eval("fun computeDefault() = 42");
            NovaValue result = eval("null ?: computeDefault()");
            assertEquals(42, result.asInt());

            NovaValue result2 = eval("99 ?: computeDefault()");
            assertEquals(99, result2.asInt());
        }

        @Test
        @DisplayName("安全调用链 — 中间结果为 null")
        void testSafeCallChainWithNullMiddle() {
            eval("class Inner(val value: Int)");
            eval("class Middle(val inner: Inner?)");
            eval("class Outer(val middle: Middle?)");

            eval("val a = Outer(Middle(Inner(42)))");
            assertEquals(42, eval("a?.middle?.inner?.value").asInt());

            eval("val b = Outer(Middle(null))");
            assertTrue(eval("b?.middle?.inner?.value").isNull());

            eval("val c = Outer(null)");
            assertTrue(eval("c?.middle?.inner?.value").isNull());
        }

        @Test
        @DisplayName("when 无参数形式 — 条件分支")
        void testWhenWithoutArgument() {
            eval("val x = 42");
            NovaValue result = eval(
                "when {\n" +
                "    x < 0 -> \"negative\"\n" +
                "    x == 0 -> \"zero\"\n" +
                "    x < 100 -> \"small\"\n" +
                "    else -> \"large\"\n" +
                "}");
            assertEquals("small", result.asString());
        }

        @Test
        @DisplayName("try-catch-finally 完整流程")
        void testTryCatchFinallyComplete() {
            NovaValue result = eval(
                "var log = \"\"\n" +
                "try {\n" +
                "    log = log + \"try \"\n" +
                "    throw \"err\"\n" +
                "} catch (e: Exception) {\n" +
                "    log = log + \"catch \"\n" +
                "} finally {\n" +
                "    log = log + \"finally\"\n" +
                "}\n" +
                "log");
            assertEquals("try catch finally", result.asString());
        }

        @Test
        @DisplayName("嵌套 try-catch — 内层异常被外层捕获")
        void testNestedTryCatchPropagation() {
            NovaValue result = eval(
                "var log = \"\"\n" +
                "try {\n" +
                "    try {\n" +
                "        throw \"inner\"\n" +
                "    } finally {\n" +
                "        log = log + \"inner-finally \"\n" +
                "    }\n" +
                "} catch (e: Exception) {\n" +
                "    log = log + \"outer-catch\"\n" +
                "}\n" +
                "log");
            assertEquals("inner-finally outer-catch", result.asString());
        }

        @Test
        @DisplayName("try 作为表达式赋值")
        void testTryAsExpression() {
            NovaValue result = eval(
                "val x = try { 42 } catch (e: Exception) { -1 }\n" +
                "x");
            assertEquals(42, result.asInt());

            NovaValue result2 = eval(
                "val y = try { throw \"err\"\n 0 } catch (e: Exception) { -1 }\n" +
                "y");
            assertEquals(-1, result2.asInt());
        }
    }

    // ================================================================
    // 7. MemberResolver 边界
    // ================================================================

    @Nested
    @DisplayName("7. 成员解析边界")
    class MemberResolverEdgeCases {

        @Test
        @DisplayName("枚举条目上调用方法")
        void testMethodOnEnumEntry() {
            eval("enum class Priority(val level: Int) {\n" +
                 "    LOW(1), MEDIUM(5), HIGH(10);\n" +
                 "    fun describe() = name + \"(\" + level + \")\"\n" +
                 "}");
            assertEquals("LOW(1)", eval("Priority.LOW.describe()").asString());
            assertEquals("HIGH(10)", eval("Priority.HIGH.describe()").asString());
        }

        @Test
        @DisplayName("接口默认方法由实现类继承")
        void testInterfaceDefaultMethodInherited() {
            eval("interface Formatter {\n" +
                 "    fun format(value: String): String\n" +
                 "    fun formatUpper(value: String) = format(value).toUpperCase()\n" +
                 "}");
            eval("class BracketFormatter : Formatter {\n" +
                 "    fun format(value: String) = \"[\" + value + \"]\"\n" +
                 "}");
            eval("val fmt = BracketFormatter()");
            assertEquals("[hello]", eval("fmt.format(\"hello\")").asString());
            assertEquals("[HELLO]", eval("fmt.formatUpper(\"hello\")").asString());
        }

        @Test
        @DisplayName("companion object 成员访问")
        void testCompanionObjectMemberAccess() {
            eval("class Config {\n" +
                 "    companion object {\n" +
                 "        val DEFAULT_PORT = 8080\n" +
                 "        fun create() = Config()\n" +
                 "    }\n" +
                 "}");
            assertEquals(8080, eval("Config.DEFAULT_PORT").asInt());
            NovaValue instance = eval("Config.create()");
            assertNotNull(instance);
            assertFalse(instance.isNull());
        }

        @Test
        @DisplayName("companion object 工厂方法")
        void testCompanionObjectFactory() {
            eval("class User(val name: String) {\n" +
                 "    companion object {\n" +
                 "        fun create(name: String) = User(name)\n" +
                 "    }\n" +
                 "}");
            assertEquals("Alice", eval("User.create(\"Alice\").name").asString());
        }
    }

    // ================================================================
    // 8. 综合边界场景
    // ================================================================

    @Nested
    @DisplayName("8. 综合边界场景")
    class ComprehensiveEdgeCases {

        @Test
        @DisplayName("闭包捕获可变变量")
        void testClosureCaptureMutableVariable() {
            eval("fun makeAccumulator() {\n" +
                 "    var total = 0\n" +
                 "    return { x: Int -> total = total + x; total }\n" +
                 "}");
            eval("val acc = makeAccumulator()");
            assertEquals(10, eval("acc(10)").asInt());
            assertEquals(30, eval("acc(20)").asInt());
            assertEquals(35, eval("acc(5)").asInt());
        }

        @Test
        @DisplayName("递归 + 条件 + 列表操作")
        void testRecursiveWithListOps() {
            eval("fun flatten(list: List<Any>): List<Any> {\n" +
                 "    val result = []\n" +
                 "    for (item in list) {\n" +
                 "        if (item is List) {\n" +
                 "            for (sub in flatten(item)) {\n" +
                 "                result.add(sub)\n" +
                 "            }\n" +
                 "        } else {\n" +
                 "            result.add(item)\n" +
                 "        }\n" +
                 "    }\n" +
                 "    return result\n" +
                 "}");
            NovaValue result = eval("flatten([1, [2, 3], [4, [5]]])");
            assertTrue(result instanceof NovaList);
            assertEquals(5, ((NovaList) result).size());
        }

        @Test
        @DisplayName("链式 map/filter 操作")
        void testChainedMapFilter() {
            NovaValue result = eval("[1, 2, 3, 4, 5, 6].filter { it % 2 == 0 }.map { it * it }");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(4, list.get(0).asInt());
            assertEquals(16, list.get(1).asInt());
            assertEquals(36, list.get(2).asInt());
        }

        @Test
        @DisplayName("when 表达式作为函数体")
        void testWhenAsExpressionBody() {
            eval("fun fizzbuzz(n: Int) = when {\n" +
                 "    n % 15 == 0 -> \"FizzBuzz\"\n" +
                 "    n % 3 == 0 -> \"Fizz\"\n" +
                 "    n % 5 == 0 -> \"Buzz\"\n" +
                 "    else -> toString(n)\n" +
                 "}");
            assertEquals("1", eval("fizzbuzz(1)").asString());
            assertEquals("Fizz", eval("fizzbuzz(3)").asString());
            assertEquals("Buzz", eval("fizzbuzz(5)").asString());
            assertEquals("FizzBuzz", eval("fizzbuzz(15)").asString());
        }

        @Test
        @DisplayName("多层继承 + 方法重写")
        void testMultiLevelInheritanceWithOverride() {
            eval("class A {\n" +
                 "    fun value() = 1\n" +
                 "    fun name() = \"A\"\n" +
                 "}");
            eval("class B : A {\n" +
                 "    override fun value() = 2\n" +
                 "}");
            eval("class C : B {\n" +
                 "    override fun name() = \"C\"\n" +
                 "}");
            eval("val c = C()");
            assertEquals(2, eval("c.value()").asInt());  // 从 B 继承
            assertEquals("C", eval("c.name()").asString());  // C 自身重写
        }

        @Test
        @DisplayName("for 循环遍历 range + 提前 return")
        void testForRangeWithEarlyReturn() {
            eval("fun sumUntil(limit: Int): Int {\n" +
                 "    var sum = 0\n" +
                 "    for (i in 1..100) {\n" +
                 "        sum = sum + i\n" +
                 "        if (sum > limit) return sum\n" +
                 "    }\n" +
                 "    return sum\n" +
                 "}");
            // 1+2+3+...+13 = 91, +14 = 105 > 100
            assertEquals(105, eval("sumUntil(100)").asInt());
        }

        @Test
        @DisplayName("空合并赋值 ??=")
        void testNullCoalesceAssignment() {
            eval("var x: Int? = null");
            eval("x ??= 42");
            assertEquals(42, eval("x").asInt());

            eval("var y: Int? = 10");
            eval("y ??= 99");
            assertEquals(10, eval("y").asInt());
        }

        @Test
        @DisplayName("is 类型检查 + 智能转换")
        void testIsTypeCheck() {
            eval("fun describe(x: Any) = when (x) {\n" +
                 "    is Int -> \"int:\" + x\n" +
                 "    is String -> \"str:\" + x\n" +
                 "    is Boolean -> \"bool:\" + x\n" +
                 "    is List -> \"list\"\n" +
                 "    else -> \"other\"\n" +
                 "}");
            assertEquals("int:42", eval("describe(42)").asString());
            assertEquals("str:hi", eval("describe(\"hi\")").asString());
            assertEquals("bool:true", eval("describe(true)").asString());
            assertEquals("list", eval("describe([1,2])").asString());
        }

        @Test
        @DisplayName("Map 迭代与解构")
        void testMapIterationWithDestructuring() {
            eval("val map = #{\"a\": 1, \"b\": 2, \"c\": 3}");
            eval("var sum = 0");
            eval("for ((k, v) in map) { sum = sum + v }");
            assertEquals(6, eval("sum").asInt());
        }

        @Test
        @DisplayName("高阶函数 — 函数作为返回值")
        void testFunctionAsReturnValue() {
            eval("fun multiplier(factor: Int) = { x: Int -> x * factor }");
            eval("val double = multiplier(2)");
            eval("val triple = multiplier(3)");
            assertEquals(10, eval("double(5)").asInt());
            assertEquals(15, eval("triple(5)").asInt());
        }

        @Test
        @DisplayName("链式安全调用 + Elvis 组合")
        void testSafeCallChainWithElvis() {
            eval("class Profile(val bio: String?)");
            eval("class Account(val profile: Profile?)");

            eval("val a1 = Account(Profile(\"Hello\"))");
            assertEquals("Hello", eval("a1?.profile?.bio ?: \"N/A\"").asString());

            eval("val a2 = Account(null)");
            assertEquals("N/A", eval("a2?.profile?.bio ?: \"N/A\"").asString());

            eval("val a3 = Account(Profile(null))");
            assertEquals("N/A", eval("a3?.profile?.bio ?: \"N/A\"").asString());
        }
    }
}
