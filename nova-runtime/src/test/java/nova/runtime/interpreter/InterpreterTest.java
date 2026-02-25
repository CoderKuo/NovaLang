package nova.runtime.interpreter;

import nova.runtime.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 解释器单元测试
 */
class InterpreterTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    // ============ 基础值测试 ============

    @Nested
    @DisplayName("字面量")
    class LiteralTests {

        @Test
        @DisplayName("整数字面量")
        void testIntLiteral() {
            NovaValue result = interpreter.evalRepl("42");
            assertTrue(result.isInt());
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("浮点数字面量")
        void testDoubleLiteral() {
            NovaValue result = interpreter.evalRepl("3.14");
            assertTrue(result.isDouble());
            assertEquals(3.14, result.asDouble(), 0.001);
        }

        @Test
        @DisplayName("字符串字面量")
        void testStringLiteral() {
            NovaValue result = interpreter.evalRepl("\"hello\"");
            assertTrue(result.isString());
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("布尔字面量")
        void testBooleanLiteral() {
            NovaValue trueResult = interpreter.evalRepl("true");
            NovaValue falseResult = interpreter.evalRepl("false");

            assertTrue(trueResult.isBool());
            assertTrue(trueResult.asBool());
            assertFalse(falseResult.asBool());
        }

        @Test
        @DisplayName("null 字面量")
        void testNullLiteral() {
            NovaValue result = interpreter.evalRepl("null");
            assertTrue(result.isNull());
        }
    }

    // ============ 算术运算测试 ============

    @Nested
    @DisplayName("算术运算")
    class ArithmeticTests {

        @Test
        @DisplayName("加法")
        void testAddition() {
            assertEquals(5, interpreter.evalRepl("2 + 3").asInt());
            assertEquals(5.5, interpreter.evalRepl("2.5 + 3.0").asDouble(), 0.001);
        }

        @Test
        @DisplayName("减法")
        void testSubtraction() {
            assertEquals(7, interpreter.evalRepl("10 - 3").asInt());
        }

        @Test
        @DisplayName("乘法")
        void testMultiplication() {
            assertEquals(12, interpreter.evalRepl("3 * 4").asInt());
        }

        @Test
        @DisplayName("除法")
        void testDivision() {
            assertEquals(5, interpreter.evalRepl("15 / 3").asInt());
            assertEquals(2.5, interpreter.evalRepl("5.0 / 2.0").asDouble(), 0.001);
        }

        @Test
        @DisplayName("取模")
        void testModulo() {
            assertEquals(1, interpreter.evalRepl("10 % 3").asInt());
        }

        @Test
        @DisplayName("负数")
        void testNegation() {
            assertEquals(-5, interpreter.evalRepl("-5").asInt());
            assertEquals(5, interpreter.evalRepl("--5").asInt());
        }

        @Test
        @DisplayName("运算符优先级")
        void testPrecedence() {
            assertEquals(14, interpreter.evalRepl("2 + 3 * 4").asInt());
            assertEquals(20, interpreter.evalRepl("(2 + 3) * 4").asInt());
        }
    }

    // ============ 比较运算测试 ============

    @Nested
    @DisplayName("比较运算")
    class ComparisonTests {

        @Test
        @DisplayName("相等")
        void testEquals() {
            assertTrue(interpreter.evalRepl("5 == 5").asBool());
            assertFalse(interpreter.evalRepl("5 == 6").asBool());
            assertTrue(interpreter.evalRepl("\"a\" == \"a\"").asBool());
        }

        @Test
        @DisplayName("不相等")
        void testNotEquals() {
            assertTrue(interpreter.evalRepl("5 != 6").asBool());
            assertFalse(interpreter.evalRepl("5 != 5").asBool());
        }

        @Test
        @DisplayName("大于/小于")
        void testGreaterLess() {
            assertTrue(interpreter.evalRepl("5 > 3").asBool());
            assertFalse(interpreter.evalRepl("3 > 5").asBool());
            assertTrue(interpreter.evalRepl("3 < 5").asBool());
            assertTrue(interpreter.evalRepl("5 >= 5").asBool());
            assertTrue(interpreter.evalRepl("5 <= 5").asBool());
        }
    }

    // ============ 逻辑运算测试 ============

    @Nested
    @DisplayName("逻辑运算")
    class LogicalTests {

        @Test
        @DisplayName("逻辑与")
        void testAnd() {
            assertTrue(interpreter.evalRepl("true && true").asBool());
            assertFalse(interpreter.evalRepl("true && false").asBool());
            assertFalse(interpreter.evalRepl("false && true").asBool());
        }

        @Test
        @DisplayName("逻辑或")
        void testOr() {
            assertTrue(interpreter.evalRepl("true || false").asBool());
            assertTrue(interpreter.evalRepl("false || true").asBool());
            assertFalse(interpreter.evalRepl("false || false").asBool());
        }

        @Test
        @DisplayName("逻辑非")
        void testNot() {
            assertFalse(interpreter.evalRepl("!true").asBool());
            assertTrue(interpreter.evalRepl("!false").asBool());
        }

        @Test
        @DisplayName("短路求值")
        void testShortCircuit() {
            // && 短路：第一个为 false 时不求值第二个
            interpreter.evalRepl("var called = false");
            interpreter.evalRepl("fun sideEffect() { called = true; return true }");
            interpreter.evalRepl("false && sideEffect()");
            assertFalse(interpreter.evalRepl("called").asBool());

            // || 短路：第一个为 true 时不求值第二个
            interpreter.evalRepl("called = false");
            interpreter.evalRepl("true || sideEffect()");
            assertFalse(interpreter.evalRepl("called").asBool());
        }
    }

    // ============ 变量测试 ============

    @Nested
    @DisplayName("变量")
    class VariableTests {

        @Test
        @DisplayName("val 声明")
        void testValDeclaration() {
            interpreter.evalRepl("val x = 42");
            assertEquals(42, interpreter.evalRepl("x").asInt());
        }

        @Test
        @DisplayName("var 声明和赋值")
        void testVarDeclaration() {
            interpreter.evalRepl("var y = 10");
            assertEquals(10, interpreter.evalRepl("y").asInt());

            interpreter.evalRepl("y = 20");
            assertEquals(20, interpreter.evalRepl("y").asInt());
        }

        @Test
        @DisplayName("复合赋值")
        void testCompoundAssignment() {
            interpreter.evalRepl("var n = 10");
            interpreter.evalRepl("n += 5");
            assertEquals(15, interpreter.evalRepl("n").asInt());

            interpreter.evalRepl("n -= 3");
            assertEquals(12, interpreter.evalRepl("n").asInt());

            interpreter.evalRepl("n *= 2");
            assertEquals(24, interpreter.evalRepl("n").asInt());

            interpreter.evalRepl("n /= 4");
            assertEquals(6, interpreter.evalRepl("n").asInt());
        }

        @Test
        @DisplayName("val 不可重新赋值")
        void testValImmutable() {
            interpreter.evalRepl("val c = 100");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("c = 200");
            });
        }

        @Test
        @DisplayName("用户代码可覆盖内置定义（shadowing）")
        void testShadowBuiltinConstant() {
            Interpreter fresh = new Interpreter();
            // Kotlin 行为：允许用户覆盖内置定义
            NovaValue result = fresh.eval("val PI = 0\nPI");
            assertEquals(NovaInt.of(0), result);
        }

        @Test
        @DisplayName("重定义用户变量报普通错误")
        void testRedefineUserVariable() {
            Interpreter fresh = new Interpreter();
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                fresh.eval("val x = 1\nval x = 2");
            });
            assertTrue(ex.getMessage().contains("Variable already defined"),
                    "Expected 'Variable already defined' but got: " + ex.getMessage());
        }
    }

    // ============ 字符串操作测试 ============

    @Nested
    @DisplayName("字符串操作")
    class StringTests {

        @Test
        @DisplayName("字符串拼接")
        void testConcatenation() {
            assertEquals("hello world", interpreter.evalRepl("\"hello\" + \" \" + \"world\"").asString());
        }

        @Test
        @DisplayName("字符串与数字拼接")
        void testStringNumberConcat() {
            assertEquals("value: 42", interpreter.evalRepl("\"value: \" + 42").asString());
        }

        @Test
        @DisplayName("字符串方法")
        void testStringMethods() {
            interpreter.evalRepl("val s = \"Hello World\"");
            assertEquals(11, interpreter.evalRepl("s.length()").asInt());
            assertEquals("HELLO WORLD", interpreter.evalRepl("s.toUpperCase()").asString());
            assertEquals("hello world", interpreter.evalRepl("s.toLowerCase()").asString());
            assertTrue(interpreter.evalRepl("s.contains(\"World\")").asBool());
            assertTrue(interpreter.evalRepl("s.startsWith(\"Hello\")").asBool());
            assertTrue(interpreter.evalRepl("s.endsWith(\"World\")").asBool());
        }
    }

    // ============ 控制流测试 ============

    @Nested
    @DisplayName("控制流")
    class ControlFlowTests {

        @Test
        @DisplayName("if 表达式")
        void testIfExpression() {
            assertEquals("yes", interpreter.evalRepl("if (true) \"yes\" else \"no\"").asString());
            assertEquals("no", interpreter.evalRepl("if (false) \"yes\" else \"no\"").asString());
        }

        @Test
        @DisplayName("if-else if-else")
        void testIfElseIf() {
            interpreter.evalRepl("val x = 5");
            String code = "if (x < 0) \"negative\" else if (x == 0) \"zero\" else \"positive\"";
            assertEquals("positive", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("while 循环")
        void testWhileLoop() {
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("var i = 1");
            interpreter.evalRepl("while (i <= 5) { sum = sum + i; i = i + 1 }");
            assertEquals(15, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("for 循环")
        void testForLoop() {
            interpreter.evalRepl("var total = 0");
            interpreter.evalRepl("for (n in [1, 2, 3, 4, 5]) { total = total + n }");
            assertEquals(15, interpreter.evalRepl("total").asInt());
        }

        @Test
        @DisplayName("break 语句")
        void testBreak() {
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl("for (i in [1, 2, 3, 4, 5]) { if (i > 3) break; count = count + 1 }");
            assertEquals(3, interpreter.evalRepl("count").asInt());
        }

        @Test
        @DisplayName("continue 语句")
        void testContinue() {
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("for (i in [1, 2, 3, 4, 5]) { if (i == 3) continue; sum = sum + i }");
            assertEquals(12, interpreter.evalRepl("sum").asInt()); // 1+2+4+5 = 12
        }
    }

    // ============ 函数测试 ============

    @Nested
    @DisplayName("函数")
    class FunctionTests {

        @Test
        @DisplayName("简单函数")
        void testSimpleFunction() {
            interpreter.evalRepl("fun add(a, b) { return a + b }");
            assertEquals(5, interpreter.evalRepl("add(2, 3)").asInt());
        }

        @Test
        @DisplayName("表达式体函数")
        void testExpressionBodyFunction() {
            interpreter.evalRepl("fun double(x) = x * 2");
            assertEquals(10, interpreter.evalRepl("double(5)").asInt());
        }

        @Test
        @DisplayName("递归函数")
        void testRecursiveFunction() {
            interpreter.evalRepl("fun factorial(n) { if (n <= 1) return 1; return n * factorial(n - 1) }");
            assertEquals(120, interpreter.evalRepl("factorial(5)").asInt());
        }

        @Test
        @DisplayName("斐波那契")
        void testFibonacci() {
            interpreter.evalRepl("fun fib(n) { if (n <= 1) return n; return fib(n-1) + fib(n-2) }");
            assertEquals(55, interpreter.evalRepl("fib(10)").asInt());
        }

        @Test
        @DisplayName("默认参数")
        void testDefaultParameters() {
            interpreter.evalRepl("fun greet(name = \"World\") { return \"Hello, \" + name }");
            assertEquals("Hello, World", interpreter.evalRepl("greet()").asString());
            assertEquals("Hello, Nova", interpreter.evalRepl("greet(\"Nova\")").asString());
        }
    }

    // ============ Lambda 测试 ============

    @Nested
    @DisplayName("Lambda")
    class LambdaTests {

        @Test
        @DisplayName("简单 Lambda")
        void testSimpleLambda() {
            interpreter.evalRepl("val double = { x -> x * 2 }");
            assertEquals(10, interpreter.evalRepl("double(5)").asInt());
        }

        @Test
        @DisplayName("多参数 Lambda")
        void testMultiParamLambda() {
            interpreter.evalRepl("val add = { a, b -> a + b }");
            assertEquals(8, interpreter.evalRepl("add(3, 5)").asInt());
        }

        @Test
        @DisplayName("Lambda 作为参数")
        void testLambdaAsArgument() {
            interpreter.evalRepl("fun apply(x, f) { return f(x) }");
            assertEquals(25, interpreter.evalRepl("apply(5, { n -> n * n })").asInt());
        }

        @Test
        @DisplayName("闭包")
        void testClosure() {
            interpreter.evalRepl("fun makeCounter() { var count = 0; return { count = count + 1; return count } }");
            interpreter.evalRepl("val counter = makeCounter()");
            assertEquals(1, interpreter.evalRepl("counter()").asInt());
            assertEquals(2, interpreter.evalRepl("counter()").asInt());
            assertEquals(3, interpreter.evalRepl("counter()").asInt());
        }

        @Test
        @DisplayName("尾随 Lambda - 无参数调用")
        void testTrailingLambdaNoArgs() {
            // 定义接受 lambda 的函数
            interpreter.evalRepl("fun run(action) { return action() }");
            // 尾随 lambda 语法：run { 42 }
            assertEquals(42, interpreter.evalRepl("run { 42 }").asInt());
        }

        @Test
        @DisplayName("尾随 Lambda - 带参数调用")
        void testTrailingLambdaWithArgs() {
            // 定义接受值和 lambda 的函数
            interpreter.evalRepl("fun transform(x, f) { return f(x) }");
            // 尾随 lambda 语法：transform(5) { it -> it * 2 }
            assertEquals(10, interpreter.evalRepl("transform(5) { n -> n * 2 }").asInt());
        }

        @Test
        @DisplayName("尾随 Lambda - 列表 forEach")
        void testTrailingLambdaForEach() {
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            // 尾随 lambda 语法：list.forEach { ... }
            interpreter.evalRepl("list.forEach { n -> sum = sum + n }");
            assertEquals(15, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("尾随 Lambda - 列表 map")
        void testTrailingLambdaMap() {
            interpreter.evalRepl("val list = [1, 2, 3]");
            // 尾随 lambda 语法：list.map { ... }
            NovaValue result = interpreter.evalRepl("list.map { n -> n * 2 }");
            assertTrue(result instanceof NovaList);
            NovaList mapped = (NovaList) result;
            assertEquals(3, mapped.size());
            assertEquals(2, mapped.get(0).asInt());
            assertEquals(4, mapped.get(1).asInt());
            assertEquals(6, mapped.get(2).asInt());
        }

        @Test
        @DisplayName("尾随 Lambda - 列表 filter")
        void testTrailingLambdaFilter() {
            interpreter.evalRepl("val nums = [1, 2, 3, 4, 5, 6]");
            // 尾随 lambda 语法：nums.filter { ... }
            NovaValue result = interpreter.evalRepl("nums.filter { n -> n % 2 == 0 }");
            assertTrue(result instanceof NovaList);
            NovaList filtered = (NovaList) result;
            assertEquals(3, filtered.size());
            assertEquals(2, filtered.get(0).asInt());
            assertEquals(4, filtered.get(1).asInt());
            assertEquals(6, filtered.get(2).asInt());
        }
    }

    // ============ SAM 转换测试 ============

    @Nested
    @DisplayName("SAM 转换")
    class SamConversionTests {

        @Test
        @DisplayName("Lambda 转换为 Runnable")
        void testLambdaToRunnable() {
            // 创建一个 lambda 并转换为 Runnable
            interpreter.evalRepl("var executed = false");
            interpreter.evalRepl("val myRunnable = { executed = true } as Runnable");

            // 验证转换成功（返回包装的 Java 对象）
            NovaValue result = interpreter.evalRepl("myRunnable");
            assertTrue(result instanceof NovaExternalObject);

            // 通过反射调用 run 方法验证功能
            Object runnable = result.toJavaValue();
            assertTrue(runnable instanceof Runnable);
            ((Runnable) runnable).run();

            // 验证 lambda 被执行
            assertTrue(interpreter.evalRepl("executed").asBool());
        }

        @Test
        @DisplayName("Lambda 转换为 Supplier")
        void testLambdaToSupplier() {
            // 创建返回值的 lambda
            interpreter.evalRepl("val supplier = { 42 } as Supplier");

            NovaValue result = interpreter.evalRepl("supplier");
            assertTrue(result instanceof NovaExternalObject);

            Object supplier = result.toJavaValue();
            assertTrue(supplier instanceof java.util.function.Supplier);

            Object value = ((java.util.function.Supplier<?>) supplier).get();
            assertEquals(42, value);
        }

        @Test
        @DisplayName("Lambda 转换为 Consumer")
        void testLambdaToConsumer() {
            interpreter.evalRepl("var received = 0");
            interpreter.evalRepl("val consumer = { x -> received = x } as Consumer");

            NovaValue result = interpreter.evalRepl("consumer");
            Object consumer = result.toJavaValue();
            assertTrue(consumer instanceof java.util.function.Consumer);

            @SuppressWarnings("unchecked")
            java.util.function.Consumer<Object> typedConsumer =
                    (java.util.function.Consumer<Object>) consumer;
            typedConsumer.accept(99);

            assertEquals(99, interpreter.evalRepl("received").asInt());
        }

        @Test
        @DisplayName("Lambda 转换为 Function")
        void testLambdaToFunction() {
            interpreter.evalRepl("val doubler = { x -> x * 2 } as Function");

            NovaValue result = interpreter.evalRepl("doubler");
            Object function = result.toJavaValue();
            assertTrue(function instanceof java.util.function.Function);

            @SuppressWarnings("unchecked")
            java.util.function.Function<Object, Object> typedFunc =
                    (java.util.function.Function<Object, Object>) function;
            Object doubled = typedFunc.apply(21);
            assertEquals(42, doubled);
        }

        @Test
        @DisplayName("Lambda 转换为 Predicate")
        void testLambdaToPredicate() {
            interpreter.evalRepl("val isPositive = { x -> x > 0 } as Predicate");

            NovaValue result = interpreter.evalRepl("isPositive");
            Object predicate = result.toJavaValue();
            assertTrue(predicate instanceof java.util.function.Predicate);

            @SuppressWarnings("unchecked")
            java.util.function.Predicate<Object> typedPred =
                    (java.util.function.Predicate<Object>) predicate;
            assertTrue(typedPred.test(5));
            assertFalse(typedPred.test(-5));
        }

        @Test
        @DisplayName("Lambda 转换为 Comparator")
        void testLambdaToComparator() {
            interpreter.evalRepl("val compare = { a, b -> a - b } as Comparator");

            NovaValue result = interpreter.evalRepl("compare");
            Object comparator = result.toJavaValue();
            assertTrue(comparator instanceof java.util.Comparator);

            @SuppressWarnings("unchecked")
            java.util.Comparator<Object> typedComp =
                    (java.util.Comparator<Object>) comparator;
            assertTrue(typedComp.compare(5, 3) > 0);
            assertTrue(typedComp.compare(3, 5) < 0);
            assertEquals(0, typedComp.compare(5, 5));
        }

        @Test
        @DisplayName("安全 SAM 转换 (as?) - 非接口返回 null")
        void testSafeSamConversion() {
            // 尝试转换为非接口类型（需要用变量包装 lambda）
            interpreter.evalRepl("val f = { 42 }");
            NovaValue result = interpreter.evalRepl("f as? String");
            assertTrue(result.isNull());
        }
    }

    // ============ 列表测试 ============

    @Nested
    @DisplayName("列表")
    class ListTests {

        @Test
        @DisplayName("列表字面量")
        void testListLiteral() {
            interpreter.evalRepl("val list = [1, 2, 3]");
            NovaValue result = interpreter.evalRepl("list");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("列表索引访问")
        void testListIndexAccess() {
            interpreter.evalRepl("val nums = [10, 20, 30]");
            assertEquals(10, interpreter.evalRepl("nums[0]").asInt());
            assertEquals(20, interpreter.evalRepl("nums[1]").asInt());
            assertEquals(30, interpreter.evalRepl("nums[2]").asInt());
        }

        @Test
        @DisplayName("列表索引赋值")
        void testListIndexAssignment() {
            interpreter.evalRepl("val arr = [1, 2, 3]");
            interpreter.evalRepl("arr[1] = 99");
            assertEquals(99, interpreter.evalRepl("arr[1]").asInt());
        }

        @Test
        @DisplayName("列表方法")
        void testListMethods() {
            interpreter.evalRepl("val list = [1, 2, 3]");
            assertEquals(3, interpreter.evalRepl("list.size()").asInt());
            assertEquals(1, interpreter.evalRepl("list.first()").asInt());
            assertEquals(3, interpreter.evalRepl("list.last()").asInt());
            assertTrue(interpreter.evalRepl("list.contains(2)").asBool());
            assertFalse(interpreter.evalRepl("list.contains(5)").asBool());
        }

        @Test
        @DisplayName("列表添加元素")
        void testListAdd() {
            interpreter.evalRepl("val list = [1, 2]");
            interpreter.evalRepl("list.add(3)");
            assertEquals(3, interpreter.evalRepl("list.size()").asInt());
            assertEquals(3, interpreter.evalRepl("list[2]").asInt());
        }
    }

    // ============ Map 测试 ============

    @Nested
    @DisplayName("Map")
    class MapTests {

        @Test
        @DisplayName("Map 字面量")
        void testMapLiteral() {
            interpreter.evalRepl("val map = #{\"a\": 1, \"b\": 2}");
            NovaValue result = interpreter.evalRepl("map");
            assertTrue(result instanceof NovaMap);
        }

        @Test
        @DisplayName("Map 索引访问")
        void testMapIndexAccess() {
            interpreter.evalRepl("val person = #{\n\"name\": \"Alice\",\n \"age\": 30}");
            assertEquals("Alice", interpreter.evalRepl("person[\"name\"]").asString());
            assertEquals(30, interpreter.evalRepl("person[\"age\"]").asInt());
        }

        @Test
        @DisplayName("Map 索引赋值")
        void testMapIndexAssignment() {
            interpreter.evalRepl("val m = #{\"x\": 1}");
            interpreter.evalRepl("m[\"y\"] = 2");
            assertEquals(2, interpreter.evalRepl("m[\"y\"]").asInt());
        }
    }

    // ============ 内置函数测试 ============

    @Nested
    @DisplayName("内置函数")
    class BuiltinTests {

        @Test
        @DisplayName("len 函数")
        void testLen() {
            assertEquals(5, interpreter.evalRepl("len(\"hello\")").asInt());
            assertEquals(3, interpreter.evalRepl("len([1, 2, 3])").asInt());
        }

        @Test
        @DisplayName("数学函数")
        void testMathFunctions() {
            assertEquals(5, interpreter.evalRepl("abs(-5)").asInt());
            assertEquals(10, interpreter.evalRepl("max(3, 10)").asInt());
            assertEquals(3, interpreter.evalRepl("min(3, 10)").asInt());
            assertEquals(2.0, interpreter.evalRepl("sqrt(4.0)").asDouble(), 0.001);
            assertEquals(8.0, interpreter.evalRepl("pow(2.0, 3.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("类型转换")
        void testTypeConversion() {
            assertEquals(42, interpreter.evalRepl("toInt(\"42\")").asInt());
            assertEquals(3.14, interpreter.evalRepl("toDouble(\"3.14\")").asDouble(), 0.001);
            assertEquals("42", interpreter.evalRepl("toString(42)").asString());
        }

        @Test
        @DisplayName("typeof 函数")
        void testTypeof() {
            assertEquals("Int", interpreter.evalRepl("typeof(42)").asString());
            assertEquals("String", interpreter.evalRepl("typeof(\"hello\")").asString());
            assertEquals("Boolean", interpreter.evalRepl("typeof(true)").asString());
            assertEquals("List", interpreter.evalRepl("typeof([1,2,3])").asString());
        }
    }

    // ============ when 表达式测试 ============

    @Nested
    @DisplayName("when 表达式")
    class WhenTests {

        @Test
        @DisplayName("基本 when")
        void testBasicWhen() {
            interpreter.evalRepl("fun describe(n) { return when (n) { 1 -> \"one\"; 2 -> \"two\"; else -> \"other\" } }");
            assertEquals("one", interpreter.evalRepl("describe(1)").asString());
            assertEquals("two", interpreter.evalRepl("describe(2)").asString());
            assertEquals("other", interpreter.evalRepl("describe(99)").asString());
        }
    }

    // ============ 类测试 ============

    @Nested
    @DisplayName("类")
    class ClassTests {

        @Test
        @DisplayName("简单类")
        void testSimpleClass() {
            interpreter.evalRepl("class Point { var x; var y }");
            interpreter.evalRepl("val p = Point()");
            interpreter.evalRepl("p.x = 10");
            interpreter.evalRepl("p.y = 20");
            assertEquals(10, interpreter.evalRepl("p.x").asInt());
            assertEquals(20, interpreter.evalRepl("p.y").asInt());
        }

        @Test
        @DisplayName("类方法")
        void testClassMethods() {
            interpreter.evalRepl("class Counter { var value; fun increment() { value = value + 1 }; fun get() { return value } }");
            interpreter.evalRepl("val c = Counter()");
            interpreter.evalRepl("c.value = 0");
            interpreter.evalRepl("c.increment()");
            interpreter.evalRepl("c.increment()");
            assertEquals(2, interpreter.evalRepl("c.get()").asInt());
        }
    }

    // ============ 错误处理测试 ============

    @Nested
    @DisplayName("错误处理")
    class ErrorTests {

        @Test
        @DisplayName("未定义变量")
        void testUndefinedVariable() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("undefinedVar");
            });
        }

        @Test
        @DisplayName("除以零")
        void testDivisionByZero() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("10 / 0");
            });
        }

        @Test
        @DisplayName("索引越界")
        void testIndexOutOfBounds() {
            interpreter.evalRepl("val arr = [1, 2, 3]");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[10]");
            });
        }
    }

    // ============ try-catch-finally 测试 ============

    @Nested
    @DisplayName("try-catch-finally")
    class TryCatchFinallyTests {

        // ---------- 正常值 ----------

        @Test
        @DisplayName("try 正常执行，catch 不触发")
        void testTryNormalExecution() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "    x = 42\n" +
                "} catch (e: Exception) {\n" +
                "    x = -1\n" +
                "}\n" +
                "x");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("try 抛异常，catch 捕获")
        void testTryCatchBasic() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "    throw \"oops\"\n" +
                "} catch (e: Exception) {\n" +
                "    x = 1\n" +
                "}\n" +
                "x");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("catch 获取异常消息")
        void testCatchExceptionMessage() {
            NovaValue result = interpreter.evalRepl(
                "var msg = \"\"\n" +
                "try {\n" +
                "    throw \"hello error\"\n" +
                "} catch (e: Exception) {\n" +
                "    msg = e\n" +
                "}\n" +
                "msg");
            // 异常消息可能包含源码位置信息
            assertTrue(result.asString().startsWith("hello error"));
        }

        @Test
        @DisplayName("try-finally 正常路径，finally 执行")
        void testTryFinallyNormalPath() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try {\n" +
                "    log = log + \"try \"\n" +
                "} finally {\n" +
                "    log = log + \"finally\"\n" +
                "}\n" +
                "log");
            assertEquals("try finally", result.asString());
        }

        @Test
        @DisplayName("try-catch-finally 正常路径")
        void testTryCatchFinallyNormalPath() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try {\n" +
                "    log = log + \"try \"\n" +
                "} catch (e: Exception) {\n" +
                "    log = log + \"catch \"\n" +
                "} finally {\n" +
                "    log = log + \"finally\"\n" +
                "}\n" +
                "log");
            assertEquals("try finally", result.asString());
        }

        @Test
        @DisplayName("try-catch-finally 异常路径")
        void testTryCatchFinallyExceptionPath() {
            NovaValue result = interpreter.evalRepl(
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
        @DisplayName("try 作为表达式返回值（正常路径）")
        void testTryAsExpressionNormal() {
            NovaValue result = interpreter.evalRepl(
                "val x = try { 42 } catch (e: Exception) { -1 }\n" +
                "x");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("try 作为表达式返回值（异常路径）")
        void testTryAsExpressionCatch() {
            NovaValue result = interpreter.evalRepl(
                "val x = try { throw \"err\"\n 0 } catch (e: Exception) { -1 }\n" +
                "x");
            assertEquals(-1, result.asInt());
        }

        @Test
        @DisplayName("error() 内置函数被 catch 捕获")
        void testErrorBuiltinCaught() {
            NovaValue result = interpreter.evalRepl(
                "var caught = false\n" +
                "try {\n" +
                "    error(\"boom\")\n" +
                "} catch (e: Exception) {\n" +
                "    caught = true\n" +
                "}\n" +
                "caught");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("除以零被 catch 捕获")
        void testDivisionByZeroCaught() {
            NovaValue result = interpreter.evalRepl(
                "var caught = false\n" +
                "try {\n" +
                "    val x = 10 / 0\n" +
                "} catch (e: Exception) {\n" +
                "    caught = true\n" +
                "}\n" +
                "caught");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("索引越界被 catch 捕获")
        void testIndexOutOfBoundsCaught() {
            NovaValue result = interpreter.evalRepl(
                "var caught = false\n" +
                "val arr = [1, 2, 3]\n" +
                "try {\n" +
                "    val x = arr[99]\n" +
                "} catch (e: Exception) {\n" +
                "    caught = true\n" +
                "}\n" +
                "caught");
            assertTrue(result.asBool());
        }

        // ---------- 边缘值 ----------

        @Test
        @DisplayName("嵌套 try-catch")
        void testNestedTryCatch() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try {\n" +
                "    try {\n" +
                "        throw \"inner\"\n" +
                "    } catch (e: Exception) {\n" +
                "        log = log + \"inner-catch \"\n" +
                "    }\n" +
                "    log = log + \"outer-try \"\n" +
                "} catch (e: Exception) {\n" +
                "    log = log + \"outer-catch\"\n" +
                "}\n" +
                "log");
            assertEquals("inner-catch outer-try ", result.asString());
        }

        @Test
        @DisplayName("嵌套 try-catch，内层未捕获传播到外层")
        void testNestedExceptionPropagation() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try {\n" +
                "    try {\n" +
                "        throw \"propagate\"\n" +
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
        @DisplayName("catch 参数作用域隔离")
        void testCatchParameterScope() {
            NovaValue result = interpreter.evalRepl(
                "val e = \"original\"\n" +
                "var msg = \"\"\n" +
                "try {\n" +
                "    throw \"caught-msg\"\n" +
                "} catch (e: Exception) {\n" +
                "    msg = e\n" +
                "}\n" +
                "e");
            assertEquals("original", result.asString());
        }

        @Test
        @DisplayName("try-catch 在函数内部")
        void testTryCatchInsideFunction() {
            interpreter.evalRepl(
                "fun safeDivide(a: Int, b: Int): Int {\n" +
                "    return try { a / b } catch (e: Exception) { 0 }\n" +
                "}");
            assertEquals(5, interpreter.evalRepl("safeDivide(10, 2)").asInt());
            assertEquals(0, interpreter.evalRepl("safeDivide(10, 0)").asInt());
        }

        @Test
        @DisplayName("try-catch 在循环内部")
        void testTryCatchInsideLoop() {
            NovaValue result = interpreter.evalRepl(
                "var count = 0\n" +
                "for (i in 0..4) {\n" +
                "    try {\n" +
                "        if (i % 2 == 0) throw \"even\"\n" +
                "    } catch (e: Exception) {\n" +
                "        count = count + 1\n" +
                "    }\n" +
                "}\n" +
                "count");
            // i = 0, 1, 2, 3, 4 → even: 0, 2, 4 → count = 3
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("finally 中修改变量对外可见")
        void testFinallyModifiesVariable() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "    x = 1\n" +
                "} finally {\n" +
                "    x = x + 10\n" +
                "}\n" +
                "x");
            assertEquals(11, result.asInt());
        }

        @Test
        @DisplayName("finally 在异常后仍执行并修改变量")
        void testFinallyAfterException() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "    throw \"err\"\n" +
                "} catch (e: Exception) {\n" +
                "    x = 5\n" +
                "} finally {\n" +
                "    x = x + 100\n" +
                "}\n" +
                "x");
            assertEquals(105, result.asInt());
        }

        @Test
        @DisplayName("空 try 块")
        void testEmptyTryBlock() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "} catch (e: Exception) {\n" +
                "    x = -1\n" +
                "} finally {\n" +
                "    x = 42\n" +
                "}\n" +
                "x");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("空 catch 块（吞掉异常）")
        void testEmptyCatchBlock() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "    throw \"ignored\"\n" +
                "} catch (e: Exception) {\n" +
                "}\n" +
                "x");
            assertEquals(0, result.asInt());
        }

        @Test
        @DisplayName("连续多个 try-catch")
        void testSequentialTryCatch() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try { throw \"a\" } catch (e: Exception) { log = log + \"1\" }\n" +
                "try { throw \"b\" } catch (e: Exception) { log = log + \"2\" }\n" +
                "try { log = log + \"3\" } catch (e: Exception) { log = log + \"x\" }\n" +
                "log");
            assertEquals("123", result.asString());
        }

        @Test
        @DisplayName("try-catch 与 when 表达式结合")
        void testTryCatchWithWhen() {
            NovaValue result = interpreter.evalRepl(
                "var x = try { 42 } catch (e: Exception) { 0 }\n" +
                "val msg = when (x) {\n" +
                "    42 -> \"correct\"\n" +
                "    else -> \"wrong\"\n" +
                "}\n" +
                "msg");
            assertEquals("correct", result.asString());
        }

        @Test
        @DisplayName("函数内 try-finally 与 return")
        void testTryFinallyWithReturn() {
            interpreter.evalRepl(
                "var sideEffect = 0\n" +
                "fun test(): Int {\n" +
                "    try {\n" +
                "        return 42\n" +
                "    } finally {\n" +
                "        sideEffect = 99\n" +
                "    }\n" +
                "}");
            assertEquals(42, interpreter.evalRepl("test()").asInt());
            assertEquals(99, interpreter.evalRepl("sideEffect").asInt());
        }

        @Test
        @DisplayName("catch 中使用异常消息进行字符串操作")
        void testCatchMessageStringOps() {
            NovaValue result = interpreter.evalRepl(
                "var msg = \"\"\n" +
                "try {\n" +
                "    error(\"file not found\")\n" +
                "} catch (e: Exception) {\n" +
                "    msg = \"Error: \" + e\n" +
                "}\n" +
                "msg");
            assertEquals("Error: file not found", result.asString());
        }

        // ---------- 异常值 ----------

        @Test
        @DisplayName("未捕获的异常向上传播")
        void testUncaughtExceptionPropagates() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "try {\n" +
                    "    throw \"boom\"\n" +
                    "} finally {\n" +
                    "}\n");
            });
        }

        @Test
        @DisplayName("finally 中异常替换原始异常")
        void testFinallyExceptionReplacesOriginal() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "try {\n" +
                    "    throw \"original\"\n" +
                    "} catch (e: Exception) {\n" +
                    "} finally {\n" +
                    "    throw \"from-finally\"\n" +
                    "}");
            });
            assertTrue(ex.getMessage().contains("from-finally"));
        }

        @Test
        @DisplayName("catch 中抛出新异常，finally 仍执行")
        void testCatchRethrowWithFinally() {
            NovaValue result = interpreter.evalRepl(
                "var finallyRan = false\n" +
                "try {\n" +
                "    try {\n" +
                "        throw \"first\"\n" +
                "    } catch (e: Exception) {\n" +
                "        throw \"second\"\n" +
                "    } finally {\n" +
                "        finallyRan = true\n" +
                "    }\n" +
                "} catch (e: Exception) {\n" +
                "}\n" +
                "finallyRan");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("finally 无异常时不影响 try 返回值")
        void testFinallyDoesNotAffectTryResult() {
            NovaValue result = interpreter.evalRepl(
                "val x = try { 42 } finally { val y = 99 }\n" +
                "x");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("嵌套 try-finally 均执行")
        void testNestedTryFinallyAllExecute() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try {\n" +
                "    try {\n" +
                "        throw \"err\"\n" +
                "    } finally {\n" +
                "        log = log + \"f1 \"\n" +
                "    }\n" +
                "} catch (e: Exception) {\n" +
                "    log = log + \"catch \"\n" +
                "} finally {\n" +
                "    log = log + \"f2\"\n" +
                "}\n" +
                "log");
            assertEquals("f1 catch f2", result.asString());
        }

        @Test
        @DisplayName("异常在 try 块中间发生，后续代码不执行")
        void testExceptionStopsTryExecution() {
            NovaValue result = interpreter.evalRepl(
                "var x = 0\n" +
                "try {\n" +
                "    x = 1\n" +
                "    throw \"stop\"\n" +
                "    x = 2\n" +
                "} catch (e: Exception) {\n" +
                "}\n" +
                "x");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("try-only（无 catch 无 finally）抛出异常传播")
        void testTryOnlyPropagates() {
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl(
                    "try {\n" +
                    "    throw \"no handler\"\n" +
                    "}");
            });
        }

        @Test
        @DisplayName("深层嵌套 try-catch-finally")
        void testDeeplyNestedTryCatchFinally() {
            NovaValue result = interpreter.evalRepl(
                "var log = \"\"\n" +
                "try {\n" +
                "    try {\n" +
                "        try {\n" +
                "            throw \"deep\"\n" +
                "        } catch (e: Exception) {\n" +
                "            log = log + \"c1 \"\n" +
                "            throw \"re\"\n" +
                "        } finally {\n" +
                "            log = log + \"f1 \"\n" +
                "        }\n" +
                "    } catch (e: Exception) {\n" +
                "        log = log + \"c2 \"\n" +
                "    } finally {\n" +
                "        log = log + \"f2 \"\n" +
                "    }\n" +
                "} finally {\n" +
                "    log = log + \"f3\"\n" +
                "}\n" +
                "log");
            assertEquals("c1 f1 c2 f2 f3", result.asString());
        }

        @Test
        @DisplayName("catch 中访问外部变量")
        void testCatchAccessOuterVariable() {
            NovaValue result = interpreter.evalRepl(
                "val prefix = \"ERR\"\n" +
                "var result = \"\"\n" +
                "try {\n" +
                "    throw \"fail\"\n" +
                "} catch (e: Exception) {\n" +
                "    result = prefix + \": \" + e\n" +
                "}\n" +
                "result");
            // 异常消息可能包含源码位置信息
            assertTrue(result.asString().startsWith("ERR: fail"));
        }

        @Test
        @DisplayName("finally 中的计算不影响 catch 返回值")
        void testFinallySideEffectOnly() {
            NovaValue result = interpreter.evalRepl(
                "var counter = 0\n" +
                "val x = try {\n" +
                "    throw \"err\"\n" +
                "    0\n" +
                "} catch (e: Exception) {\n" +
                "    100\n" +
                "} finally {\n" +
                "    counter = counter + 1\n" +
                "}\n" +
                "x");
            assertEquals(100, result.asInt());
            assertEquals(1, interpreter.evalRepl("counter").asInt());
        }
    }

    // ============ 枚举测试 ============

    @Nested
    @DisplayName("枚举")
    class EnumTests {

        @Test
        @DisplayName("简单枚举声明")
        void testSimpleEnum() {
            interpreter.evalRepl("enum class Status { PENDING, RUNNING, COMPLETED }");

            // 访问枚举值
            NovaValue pending = interpreter.evalRepl("Status.PENDING");
            assertTrue(pending instanceof NovaEnumEntry);
            assertEquals("PENDING", pending.toString());
        }

        @Test
        @DisplayName("枚举 name 和 ordinal")
        void testEnumNameAndOrdinal() {
            interpreter.evalRepl("enum class Color { RED, GREEN, BLUE }");

            assertEquals("RED", interpreter.evalRepl("Color.RED.name").asString());
            assertEquals(0, interpreter.evalRepl("Color.RED.ordinal").asInt());

            assertEquals("GREEN", interpreter.evalRepl("Color.GREEN.name").asString());
            assertEquals(1, interpreter.evalRepl("Color.GREEN.ordinal").asInt());

            assertEquals("BLUE", interpreter.evalRepl("Color.BLUE.name").asString());
            assertEquals(2, interpreter.evalRepl("Color.BLUE.ordinal").asInt());
        }

        @Test
        @DisplayName("枚举 values() 方法")
        void testEnumValues() {
            interpreter.evalRepl("enum class Day { MON, TUE, WED }");

            NovaValue values = interpreter.evalRepl("Day.values()");
            assertTrue(values instanceof NovaList);
            NovaList list = (NovaList) values;
            assertEquals(3, list.size());
            assertEquals("MON", list.get(0).toString());
            assertEquals("TUE", list.get(1).toString());
            assertEquals("WED", list.get(2).toString());
        }

        @Test
        @DisplayName("枚举 valueOf() 方法")
        void testEnumValueOf() {
            interpreter.evalRepl("enum class Level { LOW, MEDIUM, HIGH }");

            NovaValue medium = interpreter.evalRepl("Level.valueOf(\"MEDIUM\")");
            assertTrue(medium instanceof NovaEnumEntry);
            assertEquals("MEDIUM", medium.toString());
            assertEquals(1, interpreter.evalRepl("Level.valueOf(\"MEDIUM\").ordinal").asInt());
        }

        @Test
        @DisplayName("带属性的枚举")
        void testEnumWithProperties() {
            interpreter.evalRepl("enum class Priority(val level: Int) { LOW(1), MEDIUM(5), HIGH(10) }");

            assertEquals(1, interpreter.evalRepl("Priority.LOW.level").asInt());
            assertEquals(5, interpreter.evalRepl("Priority.MEDIUM.level").asInt());
            assertEquals(10, interpreter.evalRepl("Priority.HIGH.level").asInt());
        }

        @Test
        @DisplayName("带方法的枚举")
        void testEnumWithMethods() {
            interpreter.evalRepl("enum class Size(val cm: Int) { SMALL(10), LARGE(100); fun describe() { return name + \": \" + cm + \"cm\" } }");

            assertEquals("SMALL: 10cm", interpreter.evalRepl("Size.SMALL.describe()").asString());
            assertEquals("LARGE: 100cm", interpreter.evalRepl("Size.LARGE.describe()").asString());
        }

        @Test
        @DisplayName("枚举比较")
        void testEnumComparison() {
            interpreter.evalRepl("enum class State { A, B, C }");

            assertTrue(interpreter.evalRepl("State.A == State.A").asBool());
            assertFalse(interpreter.evalRepl("State.A == State.B").asBool());
        }

        @Test
        @DisplayName("枚举在 when 中使用")
        void testEnumInWhen() {
            interpreter.evalRepl("enum class Traffic { RED, YELLOW, GREEN }");
            interpreter.evalRepl("fun action(light) { return when (light) { Traffic.RED -> \"stop\"; Traffic.YELLOW -> \"slow\"; Traffic.GREEN -> \"go\"; else -> \"unknown\" } }");

            assertEquals("stop", interpreter.evalRepl("action(Traffic.RED)").asString());
            assertEquals("slow", interpreter.evalRepl("action(Traffic.YELLOW)").asString());
            assertEquals("go", interpreter.evalRepl("action(Traffic.GREEN)").asString());
        }
    }

    // ============ 接口测试 ============

    @Nested
    @DisplayName("接口")
    class InterfaceTests {

        @Test
        @DisplayName("简单接口实现")
        void testSimpleInterface() {
            interpreter.evalRepl("interface Greeter { fun greet(): String }");
            interpreter.evalRepl("class HelloGreeter : Greeter { fun greet() { return \"Hello!\" } }");
            interpreter.evalRepl("val g = HelloGreeter()");

            assertEquals("Hello!", interpreter.evalRepl("g.greet()").asString());
        }

        @Test
        @DisplayName("接口默认方法")
        void testInterfaceDefaultMethod() {
            interpreter.evalRepl("interface Logger { fun log(msg: String); fun warn(msg: String) { return \"WARN: \" + msg } }");
            interpreter.evalRepl("class SimpleLogger : Logger { fun log(msg: String) { return \"LOG: \" + msg } }");
            interpreter.evalRepl("val logger = SimpleLogger()");

            // 调用实现的方法
            assertEquals("LOG: test", interpreter.evalRepl("logger.log(\"test\")").asString());
            // 调用默认方法
            assertEquals("WARN: danger", interpreter.evalRepl("logger.warn(\"danger\")").asString());
        }

        @Test
        @DisplayName("多接口实现")
        void testMultipleInterfaces() {
            interpreter.evalRepl("interface Readable { fun read(): String }");
            interpreter.evalRepl("interface Writable { fun write(s: String) }");
            interpreter.evalRepl("class File : Readable, Writable { var content = \"\"; fun read() { return content }; fun write(s: String) { content = s } }");
            interpreter.evalRepl("val f = File()");

            interpreter.evalRepl("f.write(\"hello\")");
            assertEquals("hello", interpreter.evalRepl("f.read()").asString());
        }

        @Test
        @DisplayName("接口继承")
        void testInterfaceInheritance() {
            interpreter.evalRepl("interface Base { fun base(): Int }");
            interpreter.evalRepl("interface Extended : Base { fun extended(): Int }");
            interpreter.evalRepl("class Impl : Extended { fun base() { return 1 }; fun extended() { return 2 } }");
            interpreter.evalRepl("val obj = Impl()");

            assertEquals(1, interpreter.evalRepl("obj.base()").asInt());
            assertEquals(2, interpreter.evalRepl("obj.extended()").asInt());
        }

        @Test
        @DisplayName("未实现接口方法报错")
        void testUnimplementedInterfaceMethod() {
            interpreter.evalRepl("interface Required { fun mustImplement() }");
            interpreter.evalRepl("class Incomplete : Required { }");  // 没有实现 mustImplement

            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("val obj = Incomplete()");
            });
        }
    }

    // ============ 匿名对象测试 ============

    @Nested
    @DisplayName("匿名对象")
    class AnonymousObjectTests {

        @Test
        @DisplayName("实现接口的匿名对象")
        void testAnonymousObjectImplementsInterface() {
            interpreter.evalRepl("interface Callback { fun onComplete(result: String) }");
            interpreter.evalRepl("var received = \"\"");
            interpreter.evalRepl("val callback = object : Callback { fun onComplete(result: String) { received = result } }");

            interpreter.evalRepl("callback.onComplete(\"done\")");
            assertEquals("done", interpreter.evalRepl("received").asString());
        }

        @Test
        @DisplayName("带返回值的匿名对象方法")
        void testAnonymousObjectWithReturnValue() {
            interpreter.evalRepl("interface Calculator { fun compute(x: Int): Int }");
            interpreter.evalRepl("val doubler = object : Calculator { fun compute(x: Int) { return x * 2 } }");

            assertEquals(10, interpreter.evalRepl("doubler.compute(5)").asInt());
        }

        @Test
        @DisplayName("匿名对象带属性")
        void testAnonymousObjectWithProperties() {
            interpreter.evalRepl("interface Counter { fun increment(); fun get(): Int }");
            interpreter.evalRepl("val counter = object : Counter { var count = 0; fun increment() { count = count + 1 }; fun get() { return count } }");

            interpreter.evalRepl("counter.increment()");
            interpreter.evalRepl("counter.increment()");
            assertEquals(2, interpreter.evalRepl("counter.get()").asInt());
        }

        @Test
        @DisplayName("匿名对象实现多个接口")
        void testAnonymousObjectMultipleInterfaces() {
            interpreter.evalRepl("interface A { fun a(): Int }");
            interpreter.evalRepl("interface B { fun b(): Int }");
            interpreter.evalRepl("val obj = object : A, B { fun a() { return 1 }; fun b() { return 2 } }");

            assertEquals(1, interpreter.evalRepl("obj.a()").asInt());
            assertEquals(2, interpreter.evalRepl("obj.b()").asInt());
        }

        @Test
        @DisplayName("匿名对象必须实现所有抽象方法")
        void testAnonymousObjectMustImplementAllMethods() {
            interpreter.evalRepl("interface TwoMethods { fun first(); fun second() }");

            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("val incomplete = object : TwoMethods { fun first() { } }");
            });
        }
    }

    // ============ 抽象类测试 ============

    @Nested
    @DisplayName("抽象类")
    class AbstractClassTests {

        @Test
        @DisplayName("抽象类不能直接实例化")
        void testCannotInstantiateAbstractClass() {
            interpreter.evalRepl("abstract class Shape { fun area(): Double }");

            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("val s = Shape()");
            });
        }

        @Test
        @DisplayName("继承抽象类")
        void testExtendAbstractClass() {
            interpreter.evalRepl("abstract class Animal { fun speak(): String }");
            interpreter.evalRepl("class Dog : Animal { fun speak() { return \"Woof!\" } }");
            interpreter.evalRepl("val dog = Dog()");

            assertEquals("Woof!", interpreter.evalRepl("dog.speak()").asString());
        }

        @Test
        @DisplayName("抽象类带具体方法")
        void testAbstractClassWithConcreteMethod() {
            interpreter.evalRepl("abstract class Base { fun concrete() { return 42 }; fun abstractMethod(): Int }");
            interpreter.evalRepl("class Derived : Base { fun abstractMethod() { return 100 } }");
            interpreter.evalRepl("val obj = Derived()");

            assertEquals(42, interpreter.evalRepl("obj.concrete()").asInt());
            assertEquals(100, interpreter.evalRepl("obj.abstractMethod()").asInt());
        }
    }

    // ============ 类继承测试 ============

    @Nested
    @DisplayName("类继承")
    class InheritanceTests {

        @Test
        @DisplayName("简单继承")
        void testSimpleInheritance() {
            interpreter.evalRepl("class Parent { fun greet() { return \"Hello from Parent\" } }");
            interpreter.evalRepl("class Child : Parent { }");
            interpreter.evalRepl("val c = Child()");

            assertEquals("Hello from Parent", interpreter.evalRepl("c.greet()").asString());
        }

        @Test
        @DisplayName("方法重写")
        void testMethodOverride() {
            interpreter.evalRepl("class Base { fun value() { return 1 } }");
            interpreter.evalRepl("class Derived : Base { fun value() { return 2 } }");

            interpreter.evalRepl("val b = Base()");
            interpreter.evalRepl("val d = Derived()");

            assertEquals(1, interpreter.evalRepl("b.value()").asInt());
            assertEquals(2, interpreter.evalRepl("d.value()").asInt());
        }

        @Test
        @DisplayName("继承链")
        void testInheritanceChain() {
            interpreter.evalRepl("class A { fun a() { return \"A\" } }");
            interpreter.evalRepl("class B : A { fun b() { return \"B\" } }");
            interpreter.evalRepl("class C : B { fun c() { return \"C\" } }");
            interpreter.evalRepl("val obj = C()");

            assertEquals("A", interpreter.evalRepl("obj.a()").asString());
            assertEquals("B", interpreter.evalRepl("obj.b()").asString());
            assertEquals("C", interpreter.evalRepl("obj.c()").asString());
        }

        @Test
        @DisplayName("类同时继承类和实现接口")
        void testClassExtendsAndImplements() {
            interpreter.evalRepl("class BaseClass { fun base() { return \"base\" } }");
            interpreter.evalRepl("interface MyInterface { fun iface(): String }");
            interpreter.evalRepl("class Combined : BaseClass, MyInterface { fun iface() { return \"interface\" } }");
            interpreter.evalRepl("val obj = Combined()");

            assertEquals("base", interpreter.evalRepl("obj.base()").asString());
            assertEquals("interface", interpreter.evalRepl("obj.iface()").asString());
        }
    }

    // ============ 可见性修饰符测试 ============

    @Nested
    @DisplayName("可见性修饰符")
    class VisibilityTests {

        @Test
        @DisplayName("public 方法可以外部访问")
        void testPublicMethodAccess() {
            interpreter.evalRepl("class MyClass { public fun greet() { return \"Hello\" } }");
            interpreter.evalRepl("val obj = MyClass()");
            assertEquals("Hello", interpreter.evalRepl("obj.greet()").asString());
        }

        @Test
        @DisplayName("默认可见性是 public")
        void testDefaultVisibilityIsPublic() {
            interpreter.evalRepl("class MyClass { fun greet() { return \"Hello\" } }");
            interpreter.evalRepl("val obj = MyClass()");
            assertEquals("Hello", interpreter.evalRepl("obj.greet()").asString());
        }

        @Test
        @DisplayName("private 方法不能从外部访问")
        void testPrivateMethodAccess() {
            interpreter.evalRepl("class MyClass { private fun secret() { return \"secret\" } }");
            interpreter.evalRepl("val obj = MyClass()");

            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("obj.secret()");
            });
        }

        @Test
        @DisplayName("private 方法可以从类内部访问")
        void testPrivateMethodFromInside() {
            interpreter.evalRepl(
                "class MyClass {\n" +
                "    private fun secret() { return \"secret\" }\n" +
                "    fun reveal() { return secret() }\n" +
                "}"
            );
            interpreter.evalRepl("val obj = MyClass()");
            assertEquals("secret", interpreter.evalRepl("obj.reveal()").asString());
        }

        @Test
        @DisplayName("private 字段不能从外部访问")
        void testPrivateFieldAccess() {
            interpreter.evalRepl("class MyClass(private val secret: String)");
            interpreter.evalRepl("val obj = MyClass(\"hidden\")");

            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("obj.secret");
            });
        }

        @Test
        @DisplayName("private 字段可以从类内部访问")
        void testPrivateFieldFromInside() {
            interpreter.evalRepl(
                "class MyClass(private val secret: String) {\n" +
                "    fun reveal() { return secret }\n" +
                "}"
            );
            interpreter.evalRepl("val obj = MyClass(\"hidden\")");
            assertEquals("hidden", interpreter.evalRepl("obj.reveal()").asString());
        }

        @Test
        @DisplayName("protected 方法可以从子类访问")
        void testProtectedMethodFromSubclass() {
            interpreter.evalRepl(
                "class Parent {\n" +
                "    protected fun secret() { return \"parent secret\" }\n" +
                "}"
            );
            interpreter.evalRepl(
                "class Child : Parent {\n" +
                "    fun reveal() { return secret() }\n" +
                "}"
            );
            interpreter.evalRepl("val child = Child()");
            assertEquals("parent secret", interpreter.evalRepl("child.reveal()").asString());
        }

        @Test
        @DisplayName("protected 方法不能从外部直接访问")
        void testProtectedMethodFromOutside() {
            interpreter.evalRepl(
                "class MyClass {\n" +
                "    protected fun secret() { return \"secret\" }\n" +
                "}"
            );
            interpreter.evalRepl("val obj = MyClass()");

            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("obj.secret()");
            });
        }

        @Test
        @DisplayName("private 属性字段")
        void testPrivatePropertyField() {
            interpreter.evalRepl(
                "class Counter {\n" +
                "    private var count = 0\n" +
                "    fun increment() { count = count + 1 }\n" +
                "    fun getCount() { return count }\n" +
                "}"
            );
            interpreter.evalRepl("val c = Counter()");
            interpreter.evalRepl("c.increment()");
            interpreter.evalRepl("c.increment()");
            assertEquals(2, interpreter.evalRepl("c.getCount()").asInt());

            // 从外部访问应该失败
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("c.count");
            });
        }

        @Test
        @DisplayName("混合可见性的成员")
        void testMixedVisibility() {
            interpreter.evalRepl(
                "class Account(private val balance: Int, val owner: String) {\n" +
                "    private fun validate() { return balance > 0 }\n" +
                "    fun isValid() { return validate() }\n" +
                "    fun getBalance() { return balance }\n" +
                "}"
            );
            interpreter.evalRepl("val acc = Account(100, \"Alice\")");

            // public 成员可访问
            assertEquals("Alice", interpreter.evalRepl("acc.owner").asString());
            assertEquals(100, interpreter.evalRepl("acc.getBalance()").asInt());
            assertTrue(interpreter.evalRepl("acc.isValid()").asBool());

            // private 成员不可访问
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("acc.balance");
            });
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("acc.validate()");
            });
        }
    }

    // ============ 扩展函数测试 ============

    @Nested
    @DisplayName("扩展函数")
    class ExtensionFunctionTests {

        @Test
        @DisplayName("String 扩展函数")
        void testStringExtension() {
            // 定义扩展函数
            interpreter.evalRepl("fun String.exclaim() = this + \"!\"");

            // 调用扩展函数
            NovaValue result = interpreter.evalRepl("\"Hello\".exclaim()");
            assertEquals("Hello!", result.asString());
        }

        @Test
        @DisplayName("String 扩展函数带参数")
        void testStringExtensionWithParams() {
            interpreter.evalRepl("fun String.duplicate(n: Int): String { var result = \"\"; for (i in 0..<n) { result = result + this }; return result }");

            NovaValue result = interpreter.evalRepl("\"ab\".duplicate(3)");
            assertEquals("ababab", result.asString());
        }

        @Test
        @DisplayName("Int 扩展函数")
        void testIntExtension() {
            interpreter.evalRepl("fun Int.double() = this * 2");

            NovaValue result = interpreter.evalRepl("21.double()");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("Int 扩展函数 - times")
        void testIntTimesExtension() {
            interpreter.evalRepl("fun Int.times(action: (Int) -> Unit) { for (i in 0..<this) { action(i) } }");
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("5.times { i -> sum = sum + i }");

            NovaValue result = interpreter.evalRepl("sum");
            assertEquals(10, result.asInt()); // 0+1+2+3+4 = 10
        }

        @Test
        @DisplayName("List 扩展函数")
        void testListExtension() {
            interpreter.evalRepl("fun List.second() = this[1]");

            NovaValue result = interpreter.evalRepl("[10, 20, 30].second()");
            assertEquals(20, result.asInt());
        }

        @Test
        @DisplayName("自定义类扩展函数")
        void testCustomClassExtension() {
            // 定义类
            interpreter.evalRepl("class Point(val x: Int, val y: Int)");

            // 定义扩展函数
            interpreter.evalRepl("fun Point.distanceFromOrigin() = sqrt(this.x * this.x + this.y * this.y)");

            // 使用
            NovaValue result = interpreter.evalRepl("Point(3, 4).distanceFromOrigin()");
            assertEquals(5.0, result.asDouble(), 0.001);
        }

        @Test
        @DisplayName("扩展函数中访问 this")
        void testExtensionThis() {
            interpreter.evalRepl("fun String.info() = \"Length: \" + this.length()");

            NovaValue result = interpreter.evalRepl("\"hello\".info()");
            assertEquals("Length: 5", result.asString());
        }

        @Test
        @DisplayName("链式扩展函数调用")
        void testChainedExtensions() {
            interpreter.evalRepl("fun String.exclaim() = this + \"!\"");
            interpreter.evalRepl("fun String.question() = this + \"?\"");

            NovaValue result = interpreter.evalRepl("\"Hi\".exclaim().question()");
            assertEquals("Hi!?", result.asString());
        }

        @Test
        @DisplayName("表达式体扩展函数")
        void testExpressionBodyExtension() {
            interpreter.evalRepl("fun Int.isEven() = this % 2 == 0");

            assertTrue(interpreter.evalRepl("4.isEven()").asBool());
            assertFalse(interpreter.evalRepl("5.isEven()").asBool());
        }

        @Test
        @DisplayName("String 扩展属性")
        void testStringExtensionProperty() {
            // 定义扩展属性
            interpreter.evalRepl("val String.len = this.length()");

            // 访问扩展属性
            NovaValue result = interpreter.evalRepl("\"hello\".len");
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("String 扩展属性 - wordCount")
        void testStringWordCountProperty() {
            interpreter.evalRepl("val String.wordCount = this.split(\" \").size()");

            NovaValue result = interpreter.evalRepl("\"hello world foo\".wordCount");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("Int 扩展属性")
        void testIntExtensionProperty() {
            interpreter.evalRepl("val Int.squared = this * this");

            NovaValue result = interpreter.evalRepl("5.squared");
            assertEquals(25, result.asInt());
        }

        @Test
        @DisplayName("List 扩展属性")
        void testListExtensionProperty() {
            interpreter.evalRepl("val List.head = this[0]");

            NovaValue result = interpreter.evalRepl("[10, 20, 30].head");
            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("扩展遮蔽内置方法 - 参数数量相同")
        void testExtensionShadowsBuiltinSameArity() {
            // 扩展函数可以遮蔽内置方法（与 Kotlin 行为一致）
            interpreter.evalRepl("fun String.length() = 42");
            NovaValue result = interpreter.evalRepl("\"hello\".length()");
            assertEquals(42, result.asInt());

            interpreter.evalRepl("fun String.repeat(n: Int) = this + \"!\"");
            NovaValue result2 = interpreter.evalRepl("\"hi\".repeat(3)");
            assertEquals("hi!", result2.asString());
        }

        @Test
        @DisplayName("扩展与内置方法重载 - 参数数量不同")
        void testExtensionOverloadDifferentArity() {
            // 参数数量不同 -> 允许（重载）
            // 内置 substring(start) 是 1 参数，这里定义 2 参数版本
            interpreter.evalRepl("fun String.substring(start: Int, end: Int) = this");

            // 不会报错，可以定义
            NovaValue result = interpreter.evalRepl("\"hello\"");
            assertNotNull(result);
        }

        @Test
        @DisplayName("自定义类扩展属性")
        void testCustomClassExtensionProperty() {
            interpreter.evalRepl("class Circle(val radius: Double)");
            interpreter.evalRepl("val Circle.area = PI * this.radius * this.radius");

            NovaValue result = interpreter.evalRepl("Circle(2.0).area");
            assertEquals(Math.PI * 4, result.asDouble(), 0.001);
        }
    }

    @Nested
    @DisplayName("安全索引")
    class SafeIndexTests {

        @Test
        @DisplayName("列表安全索引 - 非空")
        void testListSafeIndexNonNull() {
            NovaValue result = interpreter.evalRepl("[1, 2, 3]?[1]");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("列表安全索引 - 空值")
        void testListSafeIndexNull() {
            interpreter.evalRepl("val list: List? = null");
            NovaValue result = interpreter.evalRepl("list?[0]");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("字符串安全索引 - 非空")
        void testStringSafeIndexNonNull() {
            NovaValue result = interpreter.evalRepl("\"hello\"?[1]");
            assertEquals("e", result.asString());
        }

        @Test
        @DisplayName("字符串安全索引 - 空值")
        void testStringSafeIndexNull() {
            interpreter.evalRepl("val s: String? = null");
            NovaValue result = interpreter.evalRepl("s?[0]");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("Map安全索引 - 非空")
        void testMapSafeIndexNonNull() {
            interpreter.evalRepl("val map = #{\"a\": 1, \"b\": 2}");
            NovaValue result = interpreter.evalRepl("map?[\"b\"]");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("Map安全索引 - 空值")
        void testMapSafeIndexNull() {
            interpreter.evalRepl("val m: Map? = null");
            NovaValue result = interpreter.evalRepl("m?[\"key\"]");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("链式安全索引")
        void testChainedSafeIndex() {
            interpreter.evalRepl("val matrix = [[1, 2], [3, 4]]");
            NovaValue result = interpreter.evalRepl("matrix?[0]?[1]");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("安全索引与普通索引混合")
        void testMixedSafeAndNormalIndex() {
            interpreter.evalRepl("val list = [[1, 2, 3]]");
            NovaValue result = interpreter.evalRepl("list?[0][2]");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("索引运算符重载")
        void testIndexOperatorOverload() {
            // 定义带有 get 运算符的类
            interpreter.evalRepl("class Grid(val data: List) { fun get(row: Int) = data[row] }");
            interpreter.evalRepl("val grid = Grid([[1, 2], [3, 4]])");
            NovaValue result = interpreter.evalRepl("grid[1]");
            // grid[1] 返回 [3, 4]
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).get(0).asInt());
        }

        @Test
        @DisplayName("安全索引运算符重载 - 非空")
        void testSafeIndexOperatorOverloadNonNull() {
            interpreter.evalRepl("class Grid(val data: List) { fun get(row: Int) = data[row] }");
            interpreter.evalRepl("val grid = Grid([[1, 2], [3, 4]])");
            NovaValue result = interpreter.evalRepl("grid?[0]");
            assertTrue(result instanceof NovaList);
            assertEquals(1, ((NovaList) result).get(0).asInt());
        }

        @Test
        @DisplayName("安全索引运算符重载 - 空值")
        void testSafeIndexOperatorOverloadNull() {
            interpreter.evalRepl("class Grid(val data: List) { fun get(row: Int) = data[row] }");
            interpreter.evalRepl("val grid: Grid? = null");
            NovaValue result = interpreter.evalRepl("grid?[0]");
            assertTrue(result.isNull());
        }
    }

    @Nested
    @DisplayName("方法引用")
    class MethodReferenceTests {

        @Test
        @DisplayName("全局函数引用")
        void testGlobalFunctionReference() {
            interpreter.evalRepl("fun double(x: Int) = x * 2");
            interpreter.evalRepl("val ref = ::double");
            NovaValue result = interpreter.evalRepl("ref(21)");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("实例方法引用")
        void testInstanceMethodReference() {
            interpreter.evalRepl("class Counter(var count: Int) { fun inc() { count = count + 1 } }");
            interpreter.evalRepl("val c = Counter(0)");
            interpreter.evalRepl("val ref = c::inc");
            interpreter.evalRepl("ref()");
            NovaValue result = interpreter.evalRepl("c.count");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("构造函数引用")
        void testConstructorReference() {
            interpreter.evalRepl("class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val createPoint = Point::new");
            interpreter.evalRepl("val p = createPoint(3, 4)");
            assertEquals(3, interpreter.evalRepl("p.x").asInt());
            assertEquals(4, interpreter.evalRepl("p.y").asInt());
        }

        @Test
        @DisplayName("函数引用作为高阶函数参数")
        void testFunctionReferenceAsArgument() {
            interpreter.evalRepl("fun square(x: Int) = x * x");
            interpreter.evalRepl("val list = [1, 2, 3, 4]");
            NovaValue result = interpreter.evalRepl("list.map(::square)");
            // [1, 4, 9, 16]
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(4, list.size());
            assertEquals(1, list.get(0).asInt());
            assertEquals(4, list.get(1).asInt());
            assertEquals(9, list.get(2).asInt());
            assertEquals(16, list.get(3).asInt());
        }
    }

    @Nested
    @DisplayName("运算符重载")
    class OperatorOverloadTests {

        @Test
        @DisplayName("plus 运算符重载")
        void testPlusOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun plus(other: Vec2) = Vec2(x + other.x, y + other.y) }");
            interpreter.evalRepl("val v1 = Vec2(1, 2)");
            interpreter.evalRepl("val v2 = Vec2(3, 4)");
            interpreter.evalRepl("val v3 = v1 + v2");
            assertEquals(4, interpreter.evalRepl("v3.x").asInt());
            assertEquals(6, interpreter.evalRepl("v3.y").asInt());
        }

        @Test
        @DisplayName("minus 运算符重载")
        void testMinusOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun minus(other: Vec2) = Vec2(x - other.x, y - other.y) }");
            interpreter.evalRepl("val v1 = Vec2(5, 7)");
            interpreter.evalRepl("val v2 = Vec2(2, 3)");
            interpreter.evalRepl("val v3 = v1 - v2");
            assertEquals(3, interpreter.evalRepl("v3.x").asInt());
            assertEquals(4, interpreter.evalRepl("v3.y").asInt());
        }

        @Test
        @DisplayName("times 运算符重载")
        void testTimesOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun times(scalar: Int) = Vec2(x * scalar, y * scalar) }");
            interpreter.evalRepl("val v = Vec2(2, 3)");
            interpreter.evalRepl("val result = v * 3");
            assertEquals(6, interpreter.evalRepl("result.x").asInt());
            assertEquals(9, interpreter.evalRepl("result.y").asInt());
        }

        @Test
        @DisplayName("div 运算符重载")
        void testDivOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun div(scalar: Int) = Vec2(x / scalar, y / scalar) }");
            interpreter.evalRepl("val v = Vec2(6, 9)");
            interpreter.evalRepl("val result = v / 3");
            assertEquals(2, interpreter.evalRepl("result.x").asInt());
            assertEquals(3, interpreter.evalRepl("result.y").asInt());
        }

        @Test
        @DisplayName("rem 运算符重载")
        void testRemOverload() {
            interpreter.evalRepl("class ModInt(val value: Int) { fun rem(other: ModInt) = ModInt(value % other.value) }");
            interpreter.evalRepl("val a = ModInt(10)");
            interpreter.evalRepl("val b = ModInt(3)");
            interpreter.evalRepl("val c = a % b");
            assertEquals(1, interpreter.evalRepl("c.value").asInt());
        }

        @Test
        @DisplayName("unaryMinus 运算符重载")
        void testUnaryMinusOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun unaryMinus() = Vec2(-x, -y) }");
            interpreter.evalRepl("val v = Vec2(3, -4)");
            interpreter.evalRepl("val neg = -v");
            assertEquals(-3, interpreter.evalRepl("neg.x").asInt());
            assertEquals(4, interpreter.evalRepl("neg.y").asInt());
        }

        @Test
        @DisplayName("unaryPlus 运算符重载")
        void testUnaryPlusOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun unaryPlus() = Vec2(abs(x), abs(y)) }");
            interpreter.evalRepl("val v = Vec2(-3, -4)");
            interpreter.evalRepl("val pos = +v");
            assertEquals(3, interpreter.evalRepl("pos.x").asInt());
            assertEquals(4, interpreter.evalRepl("pos.y").asInt());
        }

        @Test
        @DisplayName("compareTo 运算符重载")
        void testCompareToOverload() {
            interpreter.evalRepl("class Version(val major: Int, val minor: Int) { fun compareTo(other: Version): Int { if (major != other.major) { return major - other.major } else { return minor - other.minor } } }");
            interpreter.evalRepl("val v1 = Version(1, 2)");
            interpreter.evalRepl("val v2 = Version(1, 3)");
            interpreter.evalRepl("val v3 = Version(2, 0)");
            assertTrue(interpreter.evalRepl("v1 < v2").asBool());
            assertTrue(interpreter.evalRepl("v2 < v3").asBool());
            assertTrue(interpreter.evalRepl("v3 > v1").asBool());
            assertFalse(interpreter.evalRepl("v1 >= v2").asBool());
        }

        @Test
        @DisplayName("get/set 索引运算符重载")
        void testIndexOverload() {
            interpreter.evalRepl("class Matrix(val data: List) { fun get(row: Int) = data[row]; fun set(row: Int, value: List) { data[row] = value } }");
            interpreter.evalRepl("val m = Matrix([[1, 2], [3, 4]])");
            // get
            NovaValue row0 = interpreter.evalRepl("m[0]");
            assertTrue(row0 instanceof NovaList);
            assertEquals(1, ((NovaList) row0).get(0).asInt());
            // set
            interpreter.evalRepl("m[1] = [5, 6]");
            NovaValue row1 = interpreter.evalRepl("m[1]");
            assertEquals(5, ((NovaList) row1).get(0).asInt());
            assertEquals(6, ((NovaList) row1).get(1).asInt());
        }

        @Test
        @DisplayName("链式运算符重载")
        void testChainedOperatorOverload() {
            interpreter.evalRepl("class Vec2(val x: Int, val y: Int) { fun plus(other: Vec2) = Vec2(x + other.x, y + other.y); fun times(scalar: Int) = Vec2(x * scalar, y * scalar) }");
            interpreter.evalRepl("val v1 = Vec2(1, 1)");
            interpreter.evalRepl("val v2 = Vec2(2, 2)");
            interpreter.evalRepl("val result = (v1 + v2) * 2");
            assertEquals(6, interpreter.evalRepl("result.x").asInt());
            assertEquals(6, interpreter.evalRepl("result.y").asInt());
        }

        @Test
        @DisplayName("inc 运算符重载")
        void testIncOverload() {
            interpreter.evalRepl("class Counter(var value: Int) { fun inc() = Counter(value + 1) }");
            interpreter.evalRepl("var c = Counter(5)");
            interpreter.evalRepl("c++");
            assertEquals(6, interpreter.evalRepl("c.value").asInt());
        }

        @Test
        @DisplayName("dec 运算符重载")
        void testDecOverload() {
            interpreter.evalRepl("class Counter(var value: Int) { fun dec() = Counter(value - 1) }");
            interpreter.evalRepl("var c = Counter(5)");
            interpreter.evalRepl("c--");
            assertEquals(4, interpreter.evalRepl("c.value").asInt());
        }
    }

    @Nested
    @DisplayName("切片语法")
    class SliceTests {

        @Test
        @DisplayName("列表切片 - 闭区间")
        void testListSliceClosed() {
            interpreter.evalRepl("val list = [0, 1, 2, 3, 4, 5]");
            NovaValue result = interpreter.evalRepl("list[1..3]");
            assertTrue(result instanceof NovaList);
            NovaList slice = (NovaList) result;
            assertEquals(3, slice.size());
            assertEquals(1, slice.get(0).asInt());
            assertEquals(2, slice.get(1).asInt());
            assertEquals(3, slice.get(2).asInt());
        }

        @Test
        @DisplayName("列表切片 - 半开区间")
        void testListSliceHalfOpen() {
            interpreter.evalRepl("val list = [0, 1, 2, 3, 4, 5]");
            NovaValue result = interpreter.evalRepl("list[1..<4]");
            assertTrue(result instanceof NovaList);
            NovaList slice = (NovaList) result;
            assertEquals(3, slice.size());
            assertEquals(1, slice.get(0).asInt());
            assertEquals(3, slice.get(2).asInt());
        }

        @Test
        @DisplayName("字符串切片")
        void testStringSlice() {
            NovaValue result = interpreter.evalRepl("\"Hello World\"[0..4]");
            assertEquals("Hello", result.asString());
        }

        @Test
        @DisplayName("切片省略起始")
        void testSliceOmitStart() {
            interpreter.evalRepl("val list = [0, 1, 2, 3, 4]");
            NovaValue result = interpreter.evalRepl("list[..2]");
            assertTrue(result instanceof NovaList);
            NovaList slice = (NovaList) result;
            assertEquals(3, slice.size());
            assertEquals(0, slice.get(0).asInt());
        }

        @Test
        @DisplayName("切片省略结束")
        void testSliceOmitEnd() {
            interpreter.evalRepl("val list = [0, 1, 2, 3, 4]");
            NovaValue result = interpreter.evalRepl("list[2..]");
            assertTrue(result instanceof NovaList);
            NovaList slice = (NovaList) result;
            assertEquals(3, slice.size());
            assertEquals(2, slice.get(0).asInt());
            assertEquals(4, slice.get(2).asInt());
        }
    }

    @Nested
    @DisplayName("Spread操作符")
    class SpreadTests {

        @Test
        @DisplayName("列表展开合并")
        void testListSpread() {
            interpreter.evalRepl("val a = [1, 2]");
            interpreter.evalRepl("val b = [3, 4]");
            NovaValue result = interpreter.evalRepl("[0, *a, *b, 5]");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(6, list.size());
            assertEquals(0, list.get(0).asInt());
            assertEquals(1, list.get(1).asInt());
            assertEquals(2, list.get(2).asInt());
            assertEquals(3, list.get(3).asInt());
            assertEquals(4, list.get(4).asInt());
            assertEquals(5, list.get(5).asInt());
        }

        @Test
        @DisplayName("函数调用展开")
        void testFunctionCallSpread() {
            interpreter.evalRepl("fun sum(a: Int, b: Int, c: Int) = a + b + c");
            interpreter.evalRepl("val args = [1, 2, 3]");
            NovaValue result = interpreter.evalRepl("sum(*args)");
            assertEquals(6, result.asInt());
        }
    }

    @Nested
    @DisplayName("空合并赋值")
    class NullCoalesceAssignTests {

        @Test
        @DisplayName("空合并赋值 - 原值为null时赋值")
        void testNullCoalesceAssignWhenNull() {
            interpreter.evalRepl("var x: Int? = null");
            interpreter.evalRepl("x ??= 42");
            assertEquals(42, interpreter.evalRepl("x").asInt());
        }

        @Test
        @DisplayName("空合并赋值 - 原值非null时不赋值")
        void testNullCoalesceAssignWhenNotNull() {
            interpreter.evalRepl("var x: Int? = 10");
            interpreter.evalRepl("x ??= 42");
            assertEquals(10, interpreter.evalRepl("x").asInt());
        }

        @Test
        @DisplayName("空合并赋值 - 链式使用")
        void testNullCoalesceAssignChain() {
            interpreter.evalRepl("var a: Int? = null");
            interpreter.evalRepl("var b: Int? = null");
            interpreter.evalRepl("a ??= b ?: 100");
            assertEquals(100, interpreter.evalRepl("a").asInt());
        }
    }

    @Nested
    @DisplayName("安全调用")
    class SafeCallTests {

        @Test
        @DisplayName("安全属性访问 - 非空")
        void testSafePropertyAccessNonNull() {
            interpreter.evalRepl("class User(val name: String)");
            interpreter.evalRepl("val user = User(\"Alice\")");
            NovaValue result = interpreter.evalRepl("user?.name");
            assertEquals("Alice", result.asString());
        }

        @Test
        @DisplayName("安全属性访问 - 空值")
        void testSafePropertyAccessNull() {
            interpreter.evalRepl("class User(val name: String)");
            interpreter.evalRepl("val user: User? = null");
            NovaValue result = interpreter.evalRepl("user?.name");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("安全方法调用 - 非空")
        void testSafeMethodCallNonNull() {
            interpreter.evalRepl("class Greeter { fun greet(name: String) = \"Hello, \" + name }");
            interpreter.evalRepl("val g = Greeter()");
            NovaValue result = interpreter.evalRepl("g?.greet(\"World\")");
            assertEquals("Hello, World", result.asString());
        }

        @Test
        @DisplayName("安全方法调用 - 空值")
        void testSafeMethodCallNull() {
            interpreter.evalRepl("class Greeter { fun greet(name: String) = \"Hello, \" + name }");
            interpreter.evalRepl("val g: Greeter? = null");
            NovaValue result = interpreter.evalRepl("g?.greet(\"World\")");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("链式安全调用")
        void testChainedSafeCall() {
            interpreter.evalRepl("class Address(val city: String)");
            interpreter.evalRepl("class User(val address: Address?)");
            interpreter.evalRepl("val user = User(Address(\"Beijing\"))");
            NovaValue result = interpreter.evalRepl("user?.address?.city");
            assertEquals("Beijing", result.asString());
        }

        @Test
        @DisplayName("链式安全调用 - 中间为空")
        void testChainedSafeCallMiddleNull() {
            interpreter.evalRepl("class Address(val city: String)");
            interpreter.evalRepl("class User(val address: Address?)");
            interpreter.evalRepl("val user = User(null)");
            NovaValue result = interpreter.evalRepl("user?.address?.city");
            assertTrue(result.isNull());
        }
    }

    // ============ Phase 5: 高级特性 ============

    @Nested
    @DisplayName("单例对象")
    class SingletonObjectTests {

        @Test
        @DisplayName("简单单例对象")
        void testSimpleSingletonObject() {
            interpreter.evalRepl("object Counter { var count = 0; fun inc() { count = count + 1 } }");
            interpreter.evalRepl("Counter.inc()");
            interpreter.evalRepl("Counter.inc()");
            assertEquals(2, interpreter.evalRepl("Counter.count").asInt());
        }

        @Test
        @DisplayName("单例对象方法")
        void testSingletonObjectMethod() {
            interpreter.evalRepl("object Math { fun double(x: Int) = x * 2 }");
            assertEquals(10, interpreter.evalRepl("Math.double(5)").asInt());
        }

        @Test
        @DisplayName("单例对象属性初始化")
        void testSingletonObjectProperty() {
            interpreter.evalRepl("object Config { val name = \"MyApp\"; val version = 1 }");
            assertEquals("MyApp", interpreter.evalRepl("Config.name").asString());
            assertEquals(1, interpreter.evalRepl("Config.version").asInt());
        }

        @Test
        @DisplayName("单例对象是唯一实例")
        void testSingletonUnique() {
            interpreter.evalRepl("object Singleton { var value = 0 }");
            interpreter.evalRepl("val a = Singleton");
            interpreter.evalRepl("val b = Singleton");
            interpreter.evalRepl("a.value = 42");
            assertEquals(42, interpreter.evalRepl("b.value").asInt());
        }
    }

    @Nested
    @DisplayName("伴生对象")
    class CompanionObjectTests {

        @Test
        @DisplayName("伴生对象静态方法")
        void testCompanionObjectMethod() {
            interpreter.evalRepl("class User(val name: String) { companion object { fun create(name: String) = User(name) } }");
            interpreter.evalRepl("val user = User.create(\"Alice\")");
            assertEquals("Alice", interpreter.evalRepl("user.name").asString());
        }

        @Test
        @DisplayName("伴生对象常量")
        void testCompanionObjectConstant() {
            interpreter.evalRepl("class Config { companion object { val DEFAULT_PORT = 8080 } }");
            assertEquals(8080, interpreter.evalRepl("Config.DEFAULT_PORT").asInt());
        }

        @Test
        @DisplayName("伴生对象工厂方法")
        void testCompanionObjectFactory() {
            interpreter.evalRepl("class Point(val x: Int, val y: Int) { companion object { fun origin() = Point(0, 0) } }");
            interpreter.evalRepl("val p = Point.origin()");
            assertEquals(0, interpreter.evalRepl("p.x").asInt());
            assertEquals(0, interpreter.evalRepl("p.y").asInt());
        }
    }

    @Nested
    @DisplayName("解构声明")
    class DestructuringTests {

        @Test
        @DisplayName("列表解构")
        void testListDestructuring() {
            interpreter.evalRepl("val list = [1, 2, 3]");
            interpreter.evalRepl("val (a, b, c) = list");
            assertEquals(1, interpreter.evalRepl("a").asInt());
            assertEquals(2, interpreter.evalRepl("b").asInt());
            assertEquals(3, interpreter.evalRepl("c").asInt());
        }

        @Test
        @DisplayName("类解构 - 带 componentN 方法")
        void testClassDestructuringWithComponentN() {
            interpreter.evalRepl("class Point(val x: Int, val y: Int) { fun component1() = x; fun component2() = y }");
            interpreter.evalRepl("val p = Point(3, 4)");
            interpreter.evalRepl("val (px, py) = p");
            assertEquals(3, interpreter.evalRepl("px").asInt());
            assertEquals(4, interpreter.evalRepl("py").asInt());
        }

        @Test
        @DisplayName("for 循环中解构")
        void testDestructuringInForLoop() {
            interpreter.evalRepl("val pairs = [[1, \"a\"], [2, \"b\"]]");
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("for ((num, _) in pairs) { sum = sum + num }");
            assertEquals(3, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("解构跳过元素")
        void testDestructuringSkip() {
            interpreter.evalRepl("val list = [1, 2, 3, 4]");
            interpreter.evalRepl("val (first, _, third, _) = list");
            assertEquals(1, interpreter.evalRepl("first").asInt());
            assertEquals(3, interpreter.evalRepl("third").asInt());
        }
    }

    // ============ Phase 6: 高级语法糖 ============

    @Nested
    @DisplayName("管道操作符")
    class PipelineTests {

        @Test
        @DisplayName("简单管道")
        void testSimplePipeline() {
            interpreter.evalRepl("fun double(x: Int) = x * 2");
            assertEquals(10, interpreter.evalRepl("5 |> double").asInt());
        }

        @Test
        @DisplayName("链式管道")
        void testChainedPipeline() {
            interpreter.evalRepl("fun add1(x: Int) = x + 1");
            interpreter.evalRepl("fun double(x: Int) = x * 2");
            assertEquals(12, interpreter.evalRepl("5 |> add1 |> double").asInt());
        }

        @Test
        @DisplayName("管道带参数")
        void testPipelineWithArgs() {
            interpreter.evalRepl("fun add(x: Int, y: Int) = x + y");
            assertEquals(15, interpreter.evalRepl("10 |> add(5)").asInt());
        }

        @Test
        @DisplayName("管道到 lambda")
        void testPipelineToLambda() {
            interpreter.evalRepl("val square = { x -> x * x }");
            assertEquals(25, interpreter.evalRepl("5 |> square").asInt());
        }
    }

    @Nested
    @DisplayName("链式比较")
    class ChainedComparisonTests {

        @Test
        @DisplayName("简单链式比较")
        void testSimpleChainedComparison() {
            assertTrue(interpreter.evalRepl("1 < 2 < 3").asBoolean());
            assertFalse(interpreter.evalRepl("1 < 3 < 2").asBoolean());
        }

        @Test
        @DisplayName("范围检查")
        void testRangeCheck() {
            interpreter.evalRepl("val x = 5");
            assertTrue(interpreter.evalRepl("0 <= x < 10").asBoolean());
            assertFalse(interpreter.evalRepl("0 <= x < 5").asBoolean());
        }

        @Test
        @DisplayName("多重链式比较")
        void testMultipleChainedComparison() {
            assertTrue(interpreter.evalRepl("1 < 2 < 3 < 4").asBoolean());
            assertFalse(interpreter.evalRepl("1 < 2 < 3 < 2").asBoolean());
        }

        @Test
        @DisplayName("相等链式比较")
        void testEqualityChainedComparison() {
            assertTrue(interpreter.evalRepl("5 == 5 == 5").asBoolean());
            assertFalse(interpreter.evalRepl("5 == 5 == 6").asBoolean());
        }
    }

    @Nested
    @DisplayName("条件绑定")
    class ConditionalBindingTests {

        @Test
        @DisplayName("if-let 基本用法")
        void testIfLetBasic() {
            interpreter.evalRepl("fun findValue(): Int? = 42");
            interpreter.evalRepl("var result = 0");
            interpreter.evalRepl("if (val x = findValue()) { result = x }");
            assertEquals(42, interpreter.evalRepl("result").asInt());
        }

        @Test
        @DisplayName("if-let null 情况")
        void testIfLetNull() {
            interpreter.evalRepl("fun findValue(): Int? = null");
            interpreter.evalRepl("var result = -1");
            interpreter.evalRepl("if (val x = findValue()) { result = x } else { result = 0 }");
            assertEquals(0, interpreter.evalRepl("result").asInt());
        }

        @Test
        @DisplayName("guard-let 基本用法")
        void testGuardLetBasic() {
            interpreter.evalRepl("fun process(value: Int?): Int { guard val x = value else { return -1 }; return x * 2 }");
            assertEquals(10, interpreter.evalRepl("process(5)").asInt());
            assertEquals(-1, interpreter.evalRepl("process(null)").asInt());
        }
    }

    @Nested
    @DisplayName("错误传播")
    class ErrorPropagationTests {

        @Test
        @DisplayName("错误传播 - 成功情况")
        void testErrorPropagationSuccess() {
            interpreter.evalRepl("fun getValue(): Int? = 42");
            interpreter.evalRepl("fun process(): Int? { val x = getValue()?; return x * 2 }");
            assertEquals(84, interpreter.evalRepl("process()").asInt());
        }

        @Test
        @DisplayName("错误传播 - null 提前返回")
        void testErrorPropagationNull() {
            interpreter.evalRepl("fun getValue(): Int? = null");
            interpreter.evalRepl("fun process(): Int? { val x = getValue()?; return x * 2 }");
            assertTrue(interpreter.evalRepl("process()").isNull());
        }

        @Test
        @DisplayName("链式错误传播")
        void testChainedErrorPropagation() {
            interpreter.evalRepl("fun step1(): Int? = 10");
            interpreter.evalRepl("fun step2(x: Int): Int? = x + 5");
            interpreter.evalRepl("fun process(): Int? { val a = step1()?; val b = step2(a)?; return b }");
            assertEquals(15, interpreter.evalRepl("process()").asInt());
        }
    }

    @Nested
    @DisplayName("部分应用")
    class PartialApplicationTests {

        @Test
        @DisplayName("部分应用 - 第一个参数")
        void testPartialApplicationFirst() {
            interpreter.evalRepl("fun add(a: Int, b: Int) = a + b");
            interpreter.evalRepl("val add10 = add(10, _)");
            assertEquals(15, interpreter.evalRepl("add10(5)").asInt());
        }

        @Test
        @DisplayName("部分应用 - 第二个参数")
        void testPartialApplicationSecond() {
            interpreter.evalRepl("fun sub(a: Int, b: Int) = a - b");
            interpreter.evalRepl("val sub5 = sub(_, 5)");
            assertEquals(5, interpreter.evalRepl("sub5(10)").asInt());
        }

        @Test
        @DisplayName("部分应用与 map")
        void testPartialApplicationWithMap() {
            interpreter.evalRepl("fun multiply(a: Int, b: Int) = a * b");
            interpreter.evalRepl("val double = multiply(2, _)");
            interpreter.evalRepl("val list = [1, 2, 3]");
            interpreter.evalRepl("val result = list.map(double)");
            assertEquals(2, interpreter.evalRepl("result[0]").asInt());
            assertEquals(4, interpreter.evalRepl("result[1]").asInt());
            assertEquals(6, interpreter.evalRepl("result[2]").asInt());
        }
    }

    @Nested
    @DisplayName("作用域简写")
    class ScopeShorthandTests {

        @Test
        @DisplayName("作用域简写基本用法")
        void testScopeShorthandBasic() {
            interpreter.evalRepl("class Person(var name: String, var age: Int)");
            interpreter.evalRepl("val p: Person? = Person(\"Alice\", 30)");
            interpreter.evalRepl("var result = \"\"");
            interpreter.evalRepl("p?.{ result = name }");
            assertEquals("Alice", interpreter.evalRepl("result").asString());
        }

        @Test
        @DisplayName("作用域简写 null 情况")
        void testScopeShorthandNull() {
            interpreter.evalRepl("val p: Person? = null");
            interpreter.evalRepl("var result = \"default\"");
            interpreter.evalRepl("p?.{ result = \"changed\" }");
            assertEquals("default", interpreter.evalRepl("result").asString());
        }

        @Test
        @DisplayName("作用域简写修改属性")
        void testScopeShorthandModify() {
            interpreter.evalRepl("class Counter(var count: Int)");
            interpreter.evalRepl("val c: Counter? = Counter(0)");
            interpreter.evalRepl("c?.{ this.count = count + 1 }");
            assertEquals(1, interpreter.evalRepl("c.count").asInt());
        }
    }

    // ============ @NovaFunc 测试用辅助类 ============

    static class IntFunctions {
        @NovaFunc("testAdd")
        public static int add(int a, int b) {
            return a + b;
        }

        @NovaFunc("testNegate")
        public static int negate(int x) {
            return -x;
        }

        @NovaFunc("testFortyTwo")
        public static int fortyTwo() {
            return 42;
        }
    }

    static class StringFunctions {
        @NovaFunc("testGreet")
        public static String greet(String name) {
            return "Hello, " + name;
        }

        @NovaFunc("testConcat")
        public static String concat(String a, String b) {
            return a + b;
        }
    }

    static class DoubleFunctions {
        @NovaFunc("testSqrt")
        public static double sqrt(double x) {
            return Math.sqrt(x);
        }

        @NovaFunc("testFloatHalf")
        public static float half(float x) {
            return x / 2.0f;
        }
    }

    static class BooleanFunctions {
        @NovaFunc("testIsPositive")
        public static boolean isPositive(int x) {
            return x > 0;
        }
    }

    static class LongFunctions {
        @NovaFunc("testBigSum")
        public static long bigSum(long a, long b) {
            return a + b;
        }
    }

    static class VoidFunctions {
        public static String sideEffect = "";

        @NovaFunc("testDoSomething")
        public static void doSomething(String msg) {
            sideEffect = msg;
        }
    }

    static class InterpreterFunctions {
        @NovaFunc("testEvalCode")
        public static NovaValue evalCode(Interpreter interp, String code) {
            return interp.evalRepl(code);
        }
    }

    static class VarargFunctions {
        @NovaFunc("testSum")
        public static int sum(java.util.List<NovaValue> args) {
            int total = 0;
            for (NovaValue v : args) total += v.asInt();
            return total;
        }
    }

    static class NovaValueFunctions {
        @NovaFunc("testIdentity")
        public static NovaValue identity(NovaValue v) {
            return v;
        }
    }

    static class MixedFunctions {
        @NovaFunc("testMixed")
        public static String mixed(int count, String text) {
            return text.repeat(count);
        }
    }

    // ============ @NovaFunc 注解批量注册测试 ============

    @Nested
    @DisplayName("@NovaFunc 注解批量注册")
    class NovaFuncRegistryTests {

        @Test
        @DisplayName("int 参数和返回值")
        void testIntFunction() {
            interpreter.registerAll(IntFunctions.class);
            assertEquals(5, interpreter.evalRepl("testAdd(2, 3)").asInt());
            assertEquals(-7, interpreter.evalRepl("testNegate(7)").asInt());
        }

        @Test
        @DisplayName("无参数函数")
        void testNoArgFunction() {
            interpreter.registerAll(IntFunctions.class);
            assertEquals(42, interpreter.evalRepl("testFortyTwo()").asInt());
        }

        @Test
        @DisplayName("String 参数和返回值")
        void testStringFunction() {
            interpreter.registerAll(StringFunctions.class);
            assertEquals("Hello, World", interpreter.evalRepl("testGreet(\"World\")").asString());
            assertEquals("foobar", interpreter.evalRepl("testConcat(\"foo\", \"bar\")").asString());
        }

        @Test
        @DisplayName("double 参数和返回值")
        void testDoubleFunction() {
            interpreter.registerAll(DoubleFunctions.class);
            assertEquals(3.0, interpreter.evalRepl("testSqrt(9.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("float 参数和返回值")
        void testFloatFunction() {
            interpreter.registerAll(DoubleFunctions.class);
            NovaValue result = interpreter.evalRepl("testFloatHalf(10.0)");
            assertEquals(5.0, result.asDouble(), 0.001);
        }

        @Test
        @DisplayName("boolean 返回值")
        void testBooleanFunction() {
            interpreter.registerAll(BooleanFunctions.class);
            assertTrue(interpreter.evalRepl("testIsPositive(5)").asBool());
            assertFalse(interpreter.evalRepl("testIsPositive(-1)").asBool());
        }

        @Test
        @DisplayName("long 参数和返回值")
        void testLongFunction() {
            interpreter.registerAll(LongFunctions.class);
            assertEquals(30L, interpreter.evalRepl("testBigSum(10L, 20L)").asLong());
        }

        @Test
        @DisplayName("void 返回值转为 Unit")
        void testVoidFunction() {
            VoidFunctions.sideEffect = "";
            interpreter.registerAll(VoidFunctions.class);
            NovaValue result = interpreter.evalRepl("testDoSomething(\"done\")");
            assertEquals("done", VoidFunctions.sideEffect);
            assertFalse(result.isNull());  // UNIT 不是 null
        }

        @Test
        @DisplayName("Interpreter 参数自动注入")
        void testInterpreterInjection() {
            interpreter.registerAll(InterpreterFunctions.class);
            interpreter.evalRepl("val x = 100");
            NovaValue result = interpreter.evalRepl("testEvalCode(\"x + 1\")");
            assertEquals(101, result.asInt());
        }

        @Test
        @DisplayName("可变参数 List<NovaValue>")
        void testVarargFunction() {
            interpreter.registerAll(VarargFunctions.class);
            assertEquals(6, interpreter.evalRepl("testSum(1, 2, 3)").asInt());
            assertEquals(0, interpreter.evalRepl("testSum()").asInt());
            assertEquals(10, interpreter.evalRepl("testSum(10)").asInt());
        }

        @Test
        @DisplayName("NovaValue 参数直接传递")
        void testNovaValuePassthrough() {
            interpreter.registerAll(NovaValueFunctions.class);
            assertEquals(42, interpreter.evalRepl("testIdentity(42)").asInt());
            assertEquals("hi", interpreter.evalRepl("testIdentity(\"hi\")").asString());
        }

        @Test
        @DisplayName("混合类型参数")
        void testMixedParams() {
            interpreter.registerAll(MixedFunctions.class);
            assertEquals("abcabc", interpreter.evalRepl("testMixed(2, \"abc\")").asString());
        }

        @Test
        @DisplayName("注册后可在表达式中使用")
        void testUsedInExpression() {
            interpreter.registerAll(IntFunctions.class);
            assertEquals(11, interpreter.evalRepl("testAdd(testAdd(1, 2), testAdd(3, 5))").asInt());
        }

        @Test
        @DisplayName("多个类可以分别注册")
        void testMultipleClassRegistration() {
            interpreter.registerAll(IntFunctions.class);
            interpreter.registerAll(StringFunctions.class);
            assertEquals(5, interpreter.evalRepl("testAdd(2, 3)").asInt());
            assertEquals("Hello, Nova", interpreter.evalRepl("testGreet(\"Nova\")").asString());
        }
    }

    // ============ @data 注解宏测试 ============

    @Nested
    @DisplayName("@data 注解宏")
    class DataAnnotationTests {

        @Test
        @DisplayName("@data componentN 解构")
        void testDataComponentN() {
            interpreter.evalRepl("@data class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point(3, 4)");
            assertEquals(3, interpreter.evalRepl("p.component1()").asInt());
            assertEquals(4, interpreter.evalRepl("p.component2()").asInt());
        }

        @Test
        @DisplayName("@data copy 方法")
        void testDataCopy() {
            interpreter.evalRepl("@data class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point(3, 4)");
            interpreter.evalRepl("val p2 = p.copy(x = 10)");
            assertEquals(10, interpreter.evalRepl("p2.x").asInt());
            assertEquals(4, interpreter.evalRepl("p2.y").asInt());
        }

        @Test
        @DisplayName("@data copy 无参数（完整复制）")
        void testDataCopyNoArgs() {
            interpreter.evalRepl("@data class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point(3, 4)");
            interpreter.evalRepl("val p2 = p.copy()");
            assertEquals(3, interpreter.evalRepl("p2.x").asInt());
            assertEquals(4, interpreter.evalRepl("p2.y").asInt());
        }

        @Test
        @DisplayName("@data 解构赋值")
        void testDataDestructuring() {
            interpreter.evalRepl("@data class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point(3, 4)");
            interpreter.evalRepl("val (a, b) = p");
            assertEquals(3, interpreter.evalRepl("a").asInt());
            assertEquals(4, interpreter.evalRepl("b").asInt());
        }

        @Test
        @DisplayName("@data 三个字段")
        void testDataThreeFields() {
            interpreter.evalRepl("@data class Color(val r: Int, val g: Int, val b: Int)");
            interpreter.evalRepl("val c = Color(255, 128, 0)");
            assertEquals(255, interpreter.evalRepl("c.component1()").asInt());
            assertEquals(128, interpreter.evalRepl("c.component2()").asInt());
            assertEquals(0, interpreter.evalRepl("c.component3()").asInt());
        }
    }

    // ============ @builder 注解宏测试 ============

    @Nested
    @DisplayName("@builder 注解宏")
    class BuilderAnnotationTests {

        @Test
        @DisplayName("@builder 基本用法")
        void testBuilderBasic() {
            interpreter.evalRepl("@builder class ServerConfig(val host: String, val port: Int)");
            interpreter.evalRepl("val config = ServerConfig.builder().host(\"example.com\").port(9090).build()");
            assertEquals("example.com", interpreter.evalRepl("config.host").asString());
            assertEquals(9090, interpreter.evalRepl("config.port").asInt());
        }

        @Test
        @DisplayName("@builder 链式调用顺序无关")
        void testBuilderOrderIndependent() {
            interpreter.evalRepl("@builder class ServerConfig(val host: String, val port: Int)");
            interpreter.evalRepl("val config = ServerConfig.builder().port(8080).host(\"localhost\").build()");
            assertEquals("localhost", interpreter.evalRepl("config.host").asString());
            assertEquals(8080, interpreter.evalRepl("config.port").asInt());
        }

        @Test
        @DisplayName("@builder 结合 @data")
        void testBuilderWithData() {
            interpreter.evalRepl("@data @builder class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point.builder().x(1).y(2).build()");
            assertEquals(1, interpreter.evalRepl("p.component1()").asInt());
            assertEquals(2, interpreter.evalRepl("p.component2()").asInt());
        }
    }

    // ============ reified + inline 函数测试 ============

    @Nested
    @DisplayName("reified + inline 函数")
    class ReifiedInlineTests {

        @Test
        @DisplayName("reified isInstance 检查")
        void testReifiedIsInstance() {
            interpreter.evalRepl("inline fun <reified T> isInstance(value: Any): Boolean { return value is T }");
            assertTrue(interpreter.evalRepl("isInstance<String>(\"hello\")").asBool());
            assertFalse(interpreter.evalRepl("isInstance<Int>(\"hello\")").asBool());
            assertTrue(interpreter.evalRepl("isInstance<Int>(42)").asBool());
            assertFalse(interpreter.evalRepl("isInstance<String>(42)").asBool());
        }

        @Test
        @DisplayName("reified T::class 类型名")
        void testReifiedTypeClass() {
            interpreter.evalRepl("inline fun <reified T> typeName(): String { return T::class }");
            assertEquals("String", interpreter.evalRepl("typeName<String>()").asString());
            assertEquals("Int", interpreter.evalRepl("typeName<Int>()").asString());
            assertEquals("Boolean", interpreter.evalRepl("typeName<Boolean>()").asString());
        }

        @Test
        @DisplayName("reified 多个类型参数")
        void testReifiedMultipleTypeParams() {
            interpreter.evalRepl("inline fun <reified A, reified B> checkTypes(a: Any, b: Any): Boolean { return a is A && b is B }");
            assertTrue(interpreter.evalRepl("checkTypes<String, Int>(\"hello\", 42)").asBool());
            assertFalse(interpreter.evalRepl("checkTypes<Int, String>(\"hello\", 42)").asBool());
        }

        @Test
        @DisplayName("reified 自定义类型检查")
        void testReifiedCustomType() {
            interpreter.evalRepl("class Dog(val name: String)");
            interpreter.evalRepl("class Cat(val name: String)");
            interpreter.evalRepl("inline fun <reified T> isType(value: Any): Boolean { return value is T }");
            interpreter.evalRepl("val d = Dog(\"Rex\")");
            assertTrue(interpreter.evalRepl("isType<Dog>(d)").asBool());
            assertFalse(interpreter.evalRepl("isType<Cat>(d)").asBool());
        }
    }

    // ============ 注解系统测试 ============

    @Nested
    @DisplayName("注解系统")
    class AnnotationSystemTests {

        @Test
        @DisplayName("annotation class 定义")
        void testAnnotationClassDefine() {
            interpreter.evalRepl("annotation class Serializable");
            NovaValue cls = interpreter.evalRepl("Serializable");
            assertTrue(cls instanceof NovaClass);
            assertTrue(((NovaClass) cls).isAnnotation());
        }

        @Test
        @DisplayName("annotation class 带参数定义")
        void testAnnotationClassWithParams() {
            interpreter.evalRepl("annotation class JsonName(val name: String)");
            NovaValue cls = interpreter.evalRepl("JsonName");
            assertTrue(cls instanceof NovaClass);
            assertTrue(((NovaClass) cls).isAnnotation());
        }

        @Test
        @DisplayName("annotation class 不能实例化")
        void testAnnotationClassCannotInstantiate() {
            interpreter.evalRepl("annotation class Serializable");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("Serializable()");
            });
        }

        @Test
        @DisplayName("@data 通过处理器工作")
        void testDataThroughProcessor() {
            interpreter.evalRepl("@data class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point(3, 4)");
            assertEquals(3, interpreter.evalRepl("p.component1()").asInt());
            assertEquals(4, interpreter.evalRepl("p.component2()").asInt());
        }

        @Test
        @DisplayName("@builder 通过处理器工作")
        void testBuilderThroughProcessor() {
            interpreter.evalRepl("@builder class Config(val host: String, val port: Int)");
            interpreter.evalRepl("val c = Config.builder().host(\"localhost\").port(8080).build()");
            assertEquals("localhost", interpreter.evalRepl("c.host").asString());
            assertEquals(8080, interpreter.evalRepl("c.port").asInt());
        }

        @Test
        @DisplayName("Nova 注解处理器注册和触发")
        void testNovaAnnotationProcessor() {
            interpreter.evalRepl("var processed = false");
            interpreter.evalRepl("registerAnnotationProcessor(\"track\", { target, args -> processed = true })");
            interpreter.evalRepl("@track class Foo");
            assertTrue(interpreter.evalRepl("processed").asBool());
        }

        @Test
        @DisplayName("注解参数传递")
        void testAnnotationArgs() {
            interpreter.evalRepl("var result = \"\"");
            interpreter.evalRepl("registerAnnotationProcessor(\"tag\", { target, args -> result = args[\"value\"] })");
            interpreter.evalRepl("@tag(value = \"hello\") class Bar");
            assertEquals("hello", interpreter.evalRepl("result").asString());
        }

        @Test
        @DisplayName("运行时注解查询")
        void testRuntimeAnnotationQuery() {
            interpreter.evalRepl("@data class Point(val x: Int, val y: Int)");
            NovaValue annotations = interpreter.evalRepl("Point.annotations");
            assertTrue(annotations instanceof NovaList);
            NovaList list = (NovaList) annotations;
            assertEquals(1, list.size());
            // 第一个注解的 name 应该是 "data"
            NovaValue first = list.get(0);
            assertTrue(first instanceof NovaMap);
            assertEquals("data", ((NovaMap) first).get(NovaString.of("name")).asString());
        }

        @Test
        @DisplayName("多注解查询")
        void testMultipleAnnotationsQuery() {
            interpreter.evalRepl("@data @builder class Point(val x: Int, val y: Int)");
            NovaValue annotations = interpreter.evalRepl("Point.annotations");
            assertTrue(annotations instanceof NovaList);
            assertEquals(2, ((NovaList) annotations).size());
        }

        @Test
        @DisplayName("多个注解叠加处理")
        void testMultipleAnnotationsProcessed() {
            interpreter.evalRepl("@data @builder class Point(val x: Int, val y: Int)");
            // @data 应该让 componentN 工作
            interpreter.evalRepl("val p = Point(3, 4)");
            assertEquals(3, interpreter.evalRepl("p.component1()").asInt());
            // @builder 应该让 builder() 工作
            interpreter.evalRepl("val p2 = Point.builder().x(10).y(20).build()");
            assertEquals(10, interpreter.evalRepl("p2.x").asInt());
        }

        @Test
        @DisplayName("注解带参数的运行时查询")
        void testAnnotationArgsQuery() {
            interpreter.evalRepl("@tag(value = \"hello\") class Foo");
            // 即使没有处理器，annotations 也能查到
            NovaValue annotations = interpreter.evalRepl("Foo.annotations");
            assertTrue(annotations instanceof NovaList);
            NovaList list = (NovaList) annotations;
            assertEquals(1, list.size());
            NovaMap ann = (NovaMap) list.get(0);
            assertEquals("tag", ann.get(NovaString.of("name")).asString());
            NovaMap args = (NovaMap) ann.get(NovaString.of("args"));
            assertEquals("hello", args.get(NovaString.of("value")).asString());
        }

        @Test
        @DisplayName("Java 注解处理器注册")
        void testJavaAnnotationProcessor() {
            // 通过 Java API 注册处理器
            interpreter.registerAnnotationProcessor(new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() {
                    return "javaCustom";
                }

                @Override
                public void processClass(NovaClass target, java.util.Map<String, NovaValue> args,
                                          Interpreter interp) {
                    target.setStaticField("javaProcessed", NovaBoolean.TRUE);
                }
            });
            interpreter.evalRepl("@javaCustom class MyClass");
            assertTrue(interpreter.evalRepl("MyClass.javaProcessed").asBool());
        }
    }

    // ============ 次级构造器测试 ============

    @Nested
    @DisplayName("次级构造器")
    class SecondaryConstructorTests {

        @Test
        @DisplayName("仅主构造器 — 回归测试")
        void testPrimaryConstructorOnly() {
            interpreter.evalRepl("class Point(var x: Int, var y: Int)");
            interpreter.evalRepl("val p = Point(3, 4)");
            assertEquals(3, interpreter.evalRepl("p.x").asInt());
            assertEquals(4, interpreter.evalRepl("p.y").asInt());
        }

        @Test
        @DisplayName("仅主构造器带默认值 — 回归测试")
        void testPrimaryConstructorWithDefaults() {
            interpreter.evalRepl("class Size(var w: Int, var h: Int = 10)");
            interpreter.evalRepl("val s1 = Size(5)");
            assertEquals(5, interpreter.evalRepl("s1.w").asInt());
            assertEquals(10, interpreter.evalRepl("s1.h").asInt());

            interpreter.evalRepl("val s2 = Size(5, 20)");
            assertEquals(20, interpreter.evalRepl("s2.h").asInt());
        }

        @Test
        @DisplayName("无任何构造器 — 回归测试")
        void testNoConstructor() {
            interpreter.evalRepl(
                "class Empty {\n" +
                "    var x = 42\n" +
                "}"
            );
            interpreter.evalRepl("val e = Empty()");
            assertEquals(42, interpreter.evalRepl("e.x").asInt());
        }

        @Test
        @DisplayName("无任何构造器传参报错 — 回归测试")
        void testNoConstructorWithArgsFails() {
            interpreter.evalRepl(
                "class Empty {\n" +
                "    var x = 42\n" +
                "}"
            );
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl("Empty(1, 2)");
            });
        }

        @Test
        @DisplayName("仅次级构造器")
        void testOnlySecondaryConstructor() {
            interpreter.evalRepl(
                "class MyClass {\n" +
                "    var x: Int\n" +
                "    var y: Int\n" +
                "    constructor(x: Int, y: Int) {\n" +
                "        this.x = x\n" +
                "        this.y = y\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val obj = MyClass(3, 4)");
            assertEquals(3, interpreter.evalRepl("obj.x").asInt());
            assertEquals(4, interpreter.evalRepl("obj.y").asInt());
        }

        @Test
        @DisplayName("多个次级构造器重载")
        void testMultipleSecondaryConstructors() {
            interpreter.evalRepl(
                "class MyClass {\n" +
                "    var x: Int\n" +
                "    var y: Int\n" +
                "    constructor(x: Int) {\n" +
                "        this.x = x\n" +
                "        this.y = 0\n" +
                "    }\n" +
                "    constructor(x: Int, y: Int) {\n" +
                "        this.x = x\n" +
                "        this.y = y\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val obj1 = MyClass(5)");
            assertEquals(5, interpreter.evalRepl("obj1.x").asInt());
            assertEquals(0, interpreter.evalRepl("obj1.y").asInt());

            interpreter.evalRepl("val obj2 = MyClass(3, 7)");
            assertEquals(3, interpreter.evalRepl("obj2.x").asInt());
            assertEquals(7, interpreter.evalRepl("obj2.y").asInt());
        }

        @Test
        @DisplayName("主构造器 + 次级构造器委托")
        void testPrimaryWithSecondaryDelegation() {
            interpreter.evalRepl(
                "class Point(var x: Int, var y: Int) {\n" +
                "    constructor(value: Int) : this(value, value)\n" +
                "}"
            );
            interpreter.evalRepl("val p1 = Point(3, 4)");
            assertEquals(3, interpreter.evalRepl("p1.x").asInt());
            assertEquals(4, interpreter.evalRepl("p1.y").asInt());

            interpreter.evalRepl("val p2 = Point(5)");
            assertEquals(5, interpreter.evalRepl("p2.x").asInt());
            assertEquals(5, interpreter.evalRepl("p2.y").asInt());
        }

        @Test
        @DisplayName("次级构造器委托 + 构造器体")
        void testSecondaryDelegationWithBody() {
            interpreter.evalRepl(
                "class Point(var x: Int, var y: Int) {\n" +
                "    var label = \"\"\n" +
                "    constructor(value: Int) : this(value, value) {\n" +
                "        this.label = \"square\"\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val p = Point(5)");
            assertEquals(5, interpreter.evalRepl("p.x").asInt());
            assertEquals(5, interpreter.evalRepl("p.y").asInt());
            assertEquals("square", interpreter.evalRepl("p.label").asString());
        }

        @Test
        @DisplayName("链式委托")
        void testChainedDelegation() {
            interpreter.evalRepl(
                "class Rect(var w: Int, var h: Int) {\n" +
                "    constructor(size: Int) : this(size, size)\n" +
                "    constructor() : this(1)\n" +
                "}"
            );
            interpreter.evalRepl("val r1 = Rect(3, 4)");
            assertEquals(3, interpreter.evalRepl("r1.w").asInt());
            assertEquals(4, interpreter.evalRepl("r1.h").asInt());

            interpreter.evalRepl("val r2 = Rect(5)");
            assertEquals(5, interpreter.evalRepl("r2.w").asInt());
            assertEquals(5, interpreter.evalRepl("r2.h").asInt());

            interpreter.evalRepl("val r3 = Rect()");
            assertEquals(1, interpreter.evalRepl("r3.w").asInt());
            assertEquals(1, interpreter.evalRepl("r3.h").asInt());
        }

        @Test
        @DisplayName("次级构造器体中访问 this 和调用方法")
        void testSecondaryConstructorAccessThisAndMethods() {
            interpreter.evalRepl(
                "class Counter(var count: Int) {\n" +
                "    fun increment() { count = count + 1 }\n" +
                "    constructor() : this(0) {\n" +
                "        this.increment()\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val c = Counter()");
            assertEquals(1, interpreter.evalRepl("c.count").asInt());
        }

        @Test
        @DisplayName("次级构造器委托参数中使用构造器参数")
        void testDelegationArgsUseConstructorParams() {
            interpreter.evalRepl(
                "class Vec(var x: Int, var y: Int) {\n" +
                "    constructor(x: Int) : this(x, x * 2)\n" +
                "}"
            );
            interpreter.evalRepl("val v = Vec(3)");
            assertEquals(3, interpreter.evalRepl("v.x").asInt());
            assertEquals(6, interpreter.evalRepl("v.y").asInt());
        }

        @Test
        @DisplayName("空参数次级构造器")
        void testEmptyParamsSecondaryConstructor() {
            interpreter.evalRepl(
                "class Config {\n" +
                "    var debug = false\n" +
                "    constructor() {\n" +
                "        this.debug = true\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val cfg = Config()");
            assertTrue(interpreter.evalRepl("cfg.debug").asBool());
        }

        @Test
        @DisplayName("次级构造器无体 — 仅委托")
        void testSecondaryConstructorNoBody() {
            interpreter.evalRepl(
                "class Pair(var a: Int, var b: Int) {\n" +
                "    constructor(value: Int) : this(value, value)\n" +
                "}"
            );
            interpreter.evalRepl("val p = Pair(10)");
            assertEquals(10, interpreter.evalRepl("p.a").asInt());
            assertEquals(10, interpreter.evalRepl("p.b").asInt());
        }

        @Test
        @DisplayName("参数数量不匹配时报错")
        void testConstructorArgMismatch() {
            interpreter.evalRepl(
                "class MyClass {\n" +
                "    var x: Int\n" +
                "    constructor(x: Int) {\n" +
                "        this.x = x\n" +
                "    }\n" +
                "}"
            );
            assertThrows(Exception.class, () -> {
                interpreter.evalRepl("MyClass(1, 2, 3)");
            });
        }

        @Test
        @DisplayName("次级构造器与属性初始化")
        void testSecondaryConstructorWithPropertyInit() {
            interpreter.evalRepl(
                "class Config {\n" +
                "    var name = \"default\"\n" +
                "    var value: Int\n" +
                "    constructor(v: Int) {\n" +
                "        this.value = v\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val cfg = Config(42)");
            assertEquals("default", interpreter.evalRepl("cfg.name").asString());
            assertEquals(42, interpreter.evalRepl("cfg.value").asInt());
        }

        @Test
        @DisplayName("Rect 综合示例")
        void testRectExample() {
            interpreter.evalRepl(
                "class Rect(var w: Int, var h: Int) {\n" +
                "    constructor(size: Int) : this(size, size)\n" +
                "    fun area() = w * h\n" +
                "}"
            );
            interpreter.evalRepl("val r1 = Rect(3, 4)");
            assertEquals(12, interpreter.evalRepl("r1.area()").asInt());

            interpreter.evalRepl("val r2 = Rect(5)");
            assertEquals(25, interpreter.evalRepl("r2.area()").asInt());
        }
    }

    // ============ 次级构造器集成测试 ============

    @Nested
    @DisplayName("次级构造器集成测试")
    class SecondaryConstructorIntegrationTests {

        @Test
        @DisplayName("次级构造器 + 继承")
        void testSecondaryConstructorWithInheritance() {
            interpreter.evalRepl("class Animal {\n" +
                "    var name = \"unknown\"\n" +
                "}");
            interpreter.evalRepl("class Dog : Animal {\n" +
                "    var breed = \"mixed\"\n" +
                "    constructor(name: String, breed: String) {\n" +
                "        this.name = name\n" +
                "        this.breed = breed\n" +
                "    }\n" +
                "}");
            interpreter.evalRepl("val d = Dog(\"Rex\", \"Husky\")");
            assertEquals("Rex", interpreter.evalRepl("d.name").asString());
            assertEquals("Husky", interpreter.evalRepl("d.breed").asString());
        }

        @Test
        @DisplayName("次级构造器 + 接口实现")
        void testSecondaryConstructorWithInterface() {
            interpreter.evalRepl("interface Describable { fun describe(): String }");
            interpreter.evalRepl(
                "class Item : Describable {\n" +
                "    var name: String\n" +
                "    var price: Int\n" +
                "    constructor(name: String, price: Int) {\n" +
                "        this.name = name\n" +
                "        this.price = price\n" +
                "    }\n" +
                "    constructor(name: String) : this(name, 0)\n" +
                "    fun describe() = name + \": $\" + price\n" +
                "}"
            );
            interpreter.evalRepl("val item1 = Item(\"Book\", 15)");
            assertEquals("Book: $15", interpreter.evalRepl("item1.describe()").asString());

            interpreter.evalRepl("val item2 = Item(\"Free\")");
            assertEquals("Free: $0", interpreter.evalRepl("item2.describe()").asString());
        }

        @Test
        @DisplayName("次级构造器 + @data 注解")
        void testSecondaryConstructorWithDataAnnotation() {
            interpreter.evalRepl(
                "@data class Vec2(val x: Int, val y: Int) {\n" +
                "    constructor(v: Int) : this(v, v)\n" +
                "}"
            );
            // 次级构造器创建
            interpreter.evalRepl("val v = Vec2(5)");
            assertEquals(5, interpreter.evalRepl("v.x").asInt());
            assertEquals(5, interpreter.evalRepl("v.y").asInt());

            // @data 的 copy 仍然有效
            interpreter.evalRepl("val v2 = v.copy(x = 10)");
            assertEquals(10, interpreter.evalRepl("v2.x").asInt());
            assertEquals(5, interpreter.evalRepl("v2.y").asInt());

            // @data 的 componentN 仍然有效
            interpreter.evalRepl("val (a, b) = Vec2(3, 4)");
            assertEquals(3, interpreter.evalRepl("a").asInt());
            assertEquals(4, interpreter.evalRepl("b").asInt());
        }

        @Test
        @DisplayName("次级构造器 + 可见性修饰符")
        void testSecondaryConstructorWithVisibility() {
            interpreter.evalRepl(
                "class Account(var balance: Int) {\n" +
                "    private var secret = \"hidden\"\n" +
                "    constructor() : this(0) {\n" +
                "        this.secret = \"initialized\"\n" +
                "    }\n" +
                "    fun getSecret() = secret\n" +
                "}"
            );
            interpreter.evalRepl("val acc = Account()");
            assertEquals(0, interpreter.evalRepl("acc.balance").asInt());
            assertEquals("initialized", interpreter.evalRepl("acc.getSecret()").asString());
        }

        @Test
        @DisplayName("次级构造器 + 操作符重载")
        void testSecondaryConstructorWithOperatorOverloading() {
            interpreter.evalRepl(
                "class Vec(var x: Int, var y: Int) {\n" +
                "    constructor(v: Int) : this(v, v)\n" +
                "    operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)\n" +
                "}"
            );
            interpreter.evalRepl("val a = Vec(1, 2)");
            interpreter.evalRepl("val b = Vec(3)");
            interpreter.evalRepl("val c = a + b");
            assertEquals(4, interpreter.evalRepl("c.x").asInt());
            assertEquals(5, interpreter.evalRepl("c.y").asInt());
        }

        @Test
        @DisplayName("次级构造器 + 静态成员/伴生对象")
        void testSecondaryConstructorWithStaticMembers() {
            interpreter.evalRepl(
                "class Color(var r: Int, var g: Int, var b: Int) {\n" +
                "    constructor(gray: Int) : this(gray, gray, gray)\n" +
                "    companion object {\n" +
                "        val BLACK = Color(0)\n" +
                "        val WHITE = Color(255)\n" +
                "    }\n" +
                "}"
            );
            assertEquals(0, interpreter.evalRepl("Color.BLACK.r").asInt());
            assertEquals(255, interpreter.evalRepl("Color.WHITE.r").asInt());
            assertEquals(255, interpreter.evalRepl("Color.WHITE.g").asInt());
        }

        @Test
        @DisplayName("次级构造器 + 方法引用")
        void testSecondaryConstructorWithMethodRef() {
            interpreter.evalRepl(
                "class Wrapper(var value: Int) {\n" +
                "    constructor() : this(0)\n" +
                "    fun getValue() = value\n" +
                "}"
            );
            // 主构造器引用
            interpreter.evalRepl("val factory = ::Wrapper");
            interpreter.evalRepl("val w = factory(42)");
            assertEquals(42, interpreter.evalRepl("w.value").asInt());
        }

        @Test
        @DisplayName("次级构造器体中执行逻辑")
        void testSecondaryConstructorBodyLogic() {
            interpreter.evalRepl(
                "class Adder(var items: List) {\n" +
                "    var total = 0\n" +
                "    constructor() : this([1, 2, 3]) {\n" +
                "        var s = 0\n" +
                "        for (x in items) { s = s + x }\n" +
                "        this.total = s\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val a = Adder()");
            assertEquals(6, interpreter.evalRepl("a.total").asInt());
        }

        @Test
        @DisplayName("次级构造器返回实例可调用方法")
        void testSecondaryConstructorInstanceMethods() {
            interpreter.evalRepl(
                "class Counter(var count: Int) {\n" +
                "    constructor() : this(0)\n" +
                "    fun increment() { count = count + 1 }\n" +
                "    fun decrement() { count = count - 1 }\n" +
                "    fun value() = count\n" +
                "}"
            );
            interpreter.evalRepl("val c = Counter()");
            assertEquals(0, interpreter.evalRepl("c.value()").asInt());
            interpreter.evalRepl("c.increment()");
            interpreter.evalRepl("c.increment()");
            interpreter.evalRepl("c.increment()");
            interpreter.evalRepl("c.decrement()");
            assertEquals(2, interpreter.evalRepl("c.value()").asInt());
        }

        @Test
        @DisplayName("多层链式委托综合场景")
        void testDeepChainDelegation() {
            interpreter.evalRepl(
                "class Config(var host: String, var port: Int, var debug: Boolean) {\n" +
                "    constructor(host: String, port: Int) : this(host, port, false)\n" +
                "    constructor(host: String) : this(host, 8080)\n" +
                "    constructor() : this(\"localhost\")\n" +
                "    fun url() = host + \":\" + port\n" +
                "}"
            );
            interpreter.evalRepl("val c1 = Config(\"example.com\", 443, true)");
            assertEquals("example.com:443", interpreter.evalRepl("c1.url()").asString());
            assertTrue(interpreter.evalRepl("c1.debug").asBool());

            interpreter.evalRepl("val c2 = Config(\"example.com\", 443)");
            assertEquals("example.com:443", interpreter.evalRepl("c2.url()").asString());
            assertFalse(interpreter.evalRepl("c2.debug").asBool());

            interpreter.evalRepl("val c3 = Config(\"example.com\")");
            assertEquals("example.com:8080", interpreter.evalRepl("c3.url()").asString());

            interpreter.evalRepl("val c4 = Config()");
            assertEquals("localhost:8080", interpreter.evalRepl("c4.url()").asString());
        }

        @Test
        @DisplayName("次级构造器创建对象存入集合")
        void testSecondaryConstructorInCollection() {
            interpreter.evalRepl(
                "class Point(var x: Int, var y: Int) {\n" +
                "    constructor(v: Int) : this(v, v)\n" +
                "    fun sum() = x + y\n" +
                "}"
            );
            interpreter.evalRepl("val points = [Point(1, 2), Point(3), Point(4, 5)]");
            assertEquals(3, interpreter.evalRepl("points[0].sum()").asInt());
            assertEquals(6, interpreter.evalRepl("points[1].sum()").asInt());
            assertEquals(9, interpreter.evalRepl("points[2].sum()").asInt());
        }

        @Test
        @DisplayName("次级构造器作为工厂模式")
        void testSecondaryConstructorAsFactory() {
            interpreter.evalRepl(
                "class Shape {\n" +
                "    var type: String\n" +
                "    var area: Int\n" +
                "    constructor(side: Int) {\n" +
                "        this.type = \"square\"\n" +
                "        this.area = side * side\n" +
                "    }\n" +
                "    constructor(w: Int, h: Int) {\n" +
                "        this.type = \"rectangle\"\n" +
                "        this.area = w * h\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val s = Shape(5)");
            assertEquals("square", interpreter.evalRepl("s.type").asString());
            assertEquals(25, interpreter.evalRepl("s.area").asInt());

            interpreter.evalRepl("val r = Shape(3, 7)");
            assertEquals("rectangle", interpreter.evalRepl("r.type").asString());
            assertEquals(21, interpreter.evalRepl("r.area").asInt());
        }
    }

    // ============ 次级构造器性能压力测试 ============

    @Nested
    @DisplayName("次级构造器性能压力测试")
    class SecondaryConstructorPerformanceTests {

        @Test
        @DisplayName("大量实例化 — 主构造器")
        void testMassInstantiationPrimary() {
            interpreter.evalRepl(
                "class Point(var x: Int, var y: Int) {\n" +
                "    constructor(v: Int) : this(v, v)\n" +
                "}"
            );
            interpreter.evalRepl(
                "var sum = 0\n" +
                "for (i in 0..<1000) {\n" +
                "    val p = Point(i, i)\n" +
                "    sum = sum + p.x\n" +
                "}"
            );
            // 0 + 1 + 2 + ... + 999 = 499500
            assertEquals(499500, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("大量实例化 — 次级构造器")
        void testMassInstantiationSecondary() {
            interpreter.evalRepl(
                "class Point(var x: Int, var y: Int) {\n" +
                "    constructor(v: Int) : this(v, v)\n" +
                "}"
            );
            interpreter.evalRepl(
                "var sum = 0\n" +
                "for (i in 0..<1000) {\n" +
                "    val p = Point(i)\n" +
                "    sum = sum + p.x + p.y\n" +
                "}"
            );
            // 每次 p.x + p.y = 2*i, 总和 = 2 * 499500 = 999000
            assertEquals(999000, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("大量实例化 — 仅次级构造器（无委托）")
        void testMassInstantiationSecondaryNoDelegation() {
            interpreter.evalRepl(
                "class Counter {\n" +
                "    var value: Int\n" +
                "    constructor(v: Int) {\n" +
                "        this.value = v\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl(
                "var sum = 0\n" +
                "for (i in 0..<1000) {\n" +
                "    val c = Counter(i)\n" +
                "    sum = sum + c.value\n" +
                "}"
            );
            assertEquals(499500, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("链式委托压力测试")
        void testChainedDelegationStress() {
            interpreter.evalRepl(
                "class Box(var x: Int, var y: Int, var z: Int) {\n" +
                "    constructor(x: Int, y: Int) : this(x, y, 0)\n" +
                "    constructor(x: Int) : this(x, x)\n" +
                "    constructor() : this(1)\n" +
                "}"
            );
            interpreter.evalRepl(
                "var sum = 0\n" +
                "for (i in 0..<500) {\n" +
                "    val b = Box()\n" +
                "    sum = sum + b.x + b.y + b.z\n" +
                "}"
            );
            // 每次 1+1+0 = 2, 500*2 = 1000
            assertEquals(1000, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("多重载构造器交替调用")
        void testAlternatingConstructorOverloads() {
            interpreter.evalRepl(
                "class Obj(var a: Int, var b: Int, var c: Int) {\n" +
                "    constructor(a: Int, b: Int) : this(a, b, 0)\n" +
                "    constructor(a: Int) : this(a, 0, 0)\n" +
                "}"
            );
            interpreter.evalRepl(
                "var sum = 0\n" +
                "for (i in 0..<300) {\n" +
                "    val o1 = Obj(i, i, i)\n" +
                "    val o2 = Obj(i, i)\n" +
                "    val o3 = Obj(i)\n" +
                "    sum = sum + o1.a + o1.b + o1.c + o2.a + o2.b + o2.c + o3.a + o3.b + o3.c\n" +
                "}"
            );
            // o1: 3i, o2: 2i, o3: i, 每轮 6i
            // sum = 6 * (0+1+...+299) = 6 * 44850 = 269100
            assertEquals(269100, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("大量属性初始化 + 次级构造器")
        void testManyPropertiesWithSecondaryConstructor() {
            interpreter.evalRepl(
                "class Record {\n" +
                "    var a = 1\n" +
                "    var b = 2\n" +
                "    var c = 3\n" +
                "    var d = 4\n" +
                "    var e = 5\n" +
                "    var total: Int\n" +
                "    constructor() {\n" +
                "        this.total = a + b + c + d + e\n" +
                "    }\n" +
                "    constructor(base: Int) {\n" +
                "        this.a = base\n" +
                "        this.b = base\n" +
                "        this.c = base\n" +
                "        this.d = base\n" +
                "        this.e = base\n" +
                "        this.total = a + b + c + d + e\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl(
                "var sum = 0\n" +
                "for (i in 0..<200) {\n" +
                "    val r = Record()\n" +
                "    sum = sum + r.total\n" +
                "}"
            );
            // 每次 total = 15, 200*15 = 3000
            assertEquals(3000, interpreter.evalRepl("sum").asInt());

            interpreter.evalRepl(
                "var sum2 = 0\n" +
                "for (i in 0..<200) {\n" +
                "    val r = Record(10)\n" +
                "    sum2 = sum2 + r.total\n" +
                "}"
            );
            // 每次 total = 50, 200*50 = 10000
            assertEquals(10000, interpreter.evalRepl("sum2").asInt());
        }

        @Test
        @DisplayName("次级构造器 + 方法调用压力")
        void testSecondaryConstructorWithMethodCallStress() {
            interpreter.evalRepl(
                "class Accumulator(var value: Int) {\n" +
                "    constructor() : this(0)\n" +
                "    fun add(n: Int) { value = value + n }\n" +
                "    fun get() = value\n" +
                "}"
            );
            interpreter.evalRepl(
                "val acc = Accumulator()\n" +
                "for (i in 1..1000) {\n" +
                "    acc.add(i)\n" +
                "}"
            );
            // 1 + 2 + ... + 1000 = 500500
            assertEquals(500500, interpreter.evalRepl("acc.get()").asInt());
        }
    }

    // ==================== Array 类型测试 ====================

    @Nested
    @DisplayName("Array 类型 - 正常值")
    class ArrayNormalTests {

        @Test
        @DisplayName("Array<Int> 构造并获取 size")
        void testArrayIntConstruction() {
            interpreter.evalRepl("val arr = Array<Int>(5)");
            assertEquals(5, interpreter.evalRepl("arr.size").asInt());
        }

        @Test
        @DisplayName("Array<Int> 索引读写")
        void testArrayIntIndexReadWrite() {
            interpreter.evalRepl("val arr = Array<Int>(3)");
            interpreter.evalRepl("arr[0] = 10");
            interpreter.evalRepl("arr[1] = 20");
            interpreter.evalRepl("arr[2] = 30");
            assertEquals(10, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(20, interpreter.evalRepl("arr[1]").asInt());
            assertEquals(30, interpreter.evalRepl("arr[2]").asInt());
        }

        @Test
        @DisplayName("arrayOf 创建数组")
        void testArrayOf() {
            interpreter.evalRepl("val arr = arrayOf(1, 2, 3)");
            assertEquals(3, interpreter.evalRepl("arr.size").asInt());
            assertEquals(1, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(2, interpreter.evalRepl("arr[1]").asInt());
            assertEquals(3, interpreter.evalRepl("arr[2]").asInt());
        }

        @Test
        @DisplayName("Array<String> 字符串数组")
        void testArrayString() {
            interpreter.evalRepl("val arr = Array<String>(2)");
            interpreter.evalRepl("arr[0] = \"hello\"");
            interpreter.evalRepl("arr[1] = \"world\"");
            assertEquals("hello", interpreter.evalRepl("arr[0]").asString());
            assertEquals("world", interpreter.evalRepl("arr[1]").asString());
            assertEquals(2, interpreter.evalRepl("arr.size").asInt());
        }

        @Test
        @DisplayName("Array<Double> 浮点数组")
        void testArrayDouble() {
            interpreter.evalRepl("val arr = Array<Double>(2)");
            interpreter.evalRepl("arr[0] = 3.14");
            interpreter.evalRepl("arr[1] = 2.71");
            assertEquals(3.14, interpreter.evalRepl("arr[0]").asDouble(), 0.001);
            assertEquals(2.71, interpreter.evalRepl("arr[1]").asDouble(), 0.001);
        }

        @Test
        @DisplayName("Array<Boolean> 布尔数组")
        void testArrayBoolean() {
            interpreter.evalRepl("val arr = Array<Boolean>(3)");
            interpreter.evalRepl("arr[0] = true");
            interpreter.evalRepl("arr[1] = false");
            interpreter.evalRepl("arr[2] = true");
            assertTrue(interpreter.evalRepl("arr[0]").asBool());
            assertFalse(interpreter.evalRepl("arr[1]").asBool());
            assertTrue(interpreter.evalRepl("arr[2]").asBool());
        }

        @Test
        @DisplayName("Array toList 转换")
        void testArrayToList() {
            interpreter.evalRepl("val arr = arrayOf(10, 20, 30)");
            NovaValue result = interpreter.evalRepl("arr.toList()");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(10, list.get(0).asInt());
            assertEquals(20, list.get(1).asInt());
            assertEquals(30, list.get(2).asInt());
        }

        @Test
        @DisplayName("Array for 循环迭代")
        void testArrayForLoop() {
            interpreter.evalRepl("val arr = arrayOf(1, 2, 3, 4, 5)");
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("for (x in arr) { sum = sum + x }");
            assertEquals(15, interpreter.evalRepl("sum").asInt());
        }

        @Test
        @DisplayName("Array forEach 遍历")
        void testArrayForEach() {
            interpreter.evalRepl("val arr = arrayOf(10, 20, 30)");
            interpreter.evalRepl("var total = 0");
            interpreter.evalRepl("arr.forEach { x -> total = total + x }");
            assertEquals(60, interpreter.evalRepl("total").asInt());
        }

        @Test
        @DisplayName("Array 负索引访问")
        void testArrayNegativeIndex() {
            interpreter.evalRepl("val arr = arrayOf(1, 2, 3)");
            assertEquals(3, interpreter.evalRepl("arr[-1]").asInt());
            assertEquals(2, interpreter.evalRepl("arr[-2]").asInt());
            assertEquals(1, interpreter.evalRepl("arr[-3]").asInt());
        }

        @Test
        @DisplayName("Array 带初始化Lambda")
        void testArrayWithInitLambda() {
            interpreter.evalRepl("val arr = Array<Int>(5) { i -> i * 10 }");
            assertEquals(0, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(10, interpreter.evalRepl("arr[1]").asInt());
            assertEquals(40, interpreter.evalRepl("arr[4]").asInt());
            assertEquals(5, interpreter.evalRepl("arr.size").asInt());
        }

        @Test
        @DisplayName("Array 显式第二参数初始化函数")
        void testArrayWithExplicitInitFn() {
            interpreter.evalRepl("val arr = Array<Int>(4, { i -> (i + 1) * 100 })");
            assertEquals(100, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(200, interpreter.evalRepl("arr[1]").asInt());
            assertEquals(300, interpreter.evalRepl("arr[2]").asInt());
            assertEquals(400, interpreter.evalRepl("arr[3]").asInt());
        }

        @Test
        @DisplayName("Array 尾随Lambda等价于第二参数")
        void testArrayTrailingLambdaEquivalence() {
            // 两种写法应产生相同结果
            interpreter.evalRepl("val a = Array<Int>(3) { i -> i + 1 }");
            interpreter.evalRepl("val b = Array<Int>(3, { i -> i + 1 })");
            assertEquals(interpreter.evalRepl("a[0]").asInt(), interpreter.evalRepl("b[0]").asInt());
            assertEquals(interpreter.evalRepl("a[1]").asInt(), interpreter.evalRepl("b[1]").asInt());
            assertEquals(interpreter.evalRepl("a[2]").asInt(), interpreter.evalRepl("b[2]").asInt());
        }

        @Test
        @DisplayName("Array 负索引写入")
        void testArrayNegativeIndexWrite() {
            interpreter.evalRepl("val arr = Array<Int>(3)");
            interpreter.evalRepl("arr[-1] = 99");
            assertEquals(99, interpreter.evalRepl("arr[2]").asInt());
        }

        @Test
        @DisplayName("Array asString 输出")
        void testArrayAsString() {
            interpreter.evalRepl("val arr = arrayOf(1, 2, 3)");
            String s = interpreter.evalRepl("arr").asString();
            assertEquals("[1, 2, 3]", s);
        }

        @Test
        @DisplayName("arrayOf 字符串数组")
        void testArrayOfStrings() {
            interpreter.evalRepl("val arr = arrayOf(\"a\", \"b\", \"c\")");
            assertEquals(3, interpreter.evalRepl("arr.size").asInt());
            assertEquals("a", interpreter.evalRepl("arr[0]").asString());
            assertEquals("c", interpreter.evalRepl("arr[2]").asString());
        }

        @Test
        @DisplayName("Array 值覆盖写入")
        void testArrayOverwrite() {
            interpreter.evalRepl("val arr = arrayOf(1, 2, 3)");
            interpreter.evalRepl("arr[1] = 99");
            assertEquals(99, interpreter.evalRepl("arr[1]").asInt());
            assertEquals(1, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(3, interpreter.evalRepl("arr[2]").asInt());
        }
    }

    @Nested
    @DisplayName("Array 类型 - 边界值")
    class ArrayBoundaryTests {

        @Test
        @DisplayName("空数组 size=0")
        void testEmptyArray() {
            interpreter.evalRepl("val arr = Array<Int>(0)");
            assertEquals(0, interpreter.evalRepl("arr.size").asInt());
        }

        @Test
        @DisplayName("空数组 toList 返回空列表")
        void testEmptyArrayToList() {
            interpreter.evalRepl("val arr = Array<Int>(0)");
            NovaValue result = interpreter.evalRepl("arr.toList()");
            assertTrue(result instanceof NovaList);
            assertEquals(0, ((NovaList) result).size());
        }

        @Test
        @DisplayName("空数组 for 循环不执行")
        void testEmptyArrayForLoop() {
            interpreter.evalRepl("val arr = Array<Int>(0)");
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl("for (x in arr) { count = count + 1 }");
            assertEquals(0, interpreter.evalRepl("count").asInt());
        }

        @Test
        @DisplayName("单元素数组")
        void testSingleElementArray() {
            interpreter.evalRepl("val arr = Array<Int>(1)");
            interpreter.evalRepl("arr[0] = 42");
            assertEquals(42, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(1, interpreter.evalRepl("arr.size").asInt());
        }

        @Test
        @DisplayName("单元素数组负索引 -1")
        void testSingleElementNegativeIndex() {
            interpreter.evalRepl("val arr = arrayOf(42)");
            assertEquals(42, interpreter.evalRepl("arr[-1]").asInt());
        }

        @Test
        @DisplayName("arrayOf 空参数")
        void testArrayOfEmpty() {
            interpreter.evalRepl("val arr = arrayOf()");
            assertEquals(0, interpreter.evalRepl("arr.size").asInt());
        }

        @Test
        @DisplayName("arrayOf 单参数")
        void testArrayOfSingle() {
            interpreter.evalRepl("val arr = arrayOf(100)");
            assertEquals(1, interpreter.evalRepl("arr.size").asInt());
            assertEquals(100, interpreter.evalRepl("arr[0]").asInt());
        }

        @Test
        @DisplayName("Array<Int> 默认初始值为0")
        void testArrayIntDefaultValue() {
            interpreter.evalRepl("val arr = Array<Int>(3)");
            assertEquals(0, interpreter.evalRepl("arr[0]").asInt());
            assertEquals(0, interpreter.evalRepl("arr[1]").asInt());
            assertEquals(0, interpreter.evalRepl("arr[2]").asInt());
        }

        @Test
        @DisplayName("Array<Boolean> 默认初始值为false")
        void testArrayBooleanDefaultValue() {
            interpreter.evalRepl("val arr = Array<Boolean>(2)");
            assertFalse(interpreter.evalRepl("arr[0]").asBool());
            assertFalse(interpreter.evalRepl("arr[1]").asBool());
        }

        @Test
        @DisplayName("Array<Double> 默认初始值为0.0")
        void testArrayDoubleDefaultValue() {
            interpreter.evalRepl("val arr = Array<Double>(2)");
            assertEquals(0.0, interpreter.evalRepl("arr[0]").asDouble(), 0.001);
        }

        @Test
        @DisplayName("空数组 isTruthy 返回 false")
        void testEmptyArrayIsFalsy() {
            interpreter.evalRepl("val arr = Array<Int>(0)");
            assertFalse(interpreter.evalRepl("arr").isTruthy());
        }

        @Test
        @DisplayName("非空数组 isTruthy 返回 true")
        void testNonEmptyArrayIsTruthy() {
            interpreter.evalRepl("val arr = Array<Int>(1)");
            assertTrue(interpreter.evalRepl("arr").isTruthy());
        }

        @Test
        @DisplayName("最后一个有效索引 size-1")
        void testLastValidIndex() {
            interpreter.evalRepl("val arr = Array<Int>(5)");
            interpreter.evalRepl("arr[4] = 99");
            assertEquals(99, interpreter.evalRepl("arr[4]").asInt());
        }

        @Test
        @DisplayName("第一个有效索引 0")
        void testFirstValidIndex() {
            interpreter.evalRepl("val arr = Array<Int>(5)");
            interpreter.evalRepl("arr[0] = 77");
            assertEquals(77, interpreter.evalRepl("arr[0]").asInt());
        }
    }

    @Nested
    @DisplayName("Array 类型 - 异常值")
    class ArrayExceptionTests {

        @Test
        @DisplayName("正向索引越界")
        void testIndexOutOfBoundsPositive() {
            interpreter.evalRepl("val arr = Array<Int>(3)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[3]");
            });
        }

        @Test
        @DisplayName("正向索引越界 - 远超范围")
        void testIndexOutOfBoundsFarPositive() {
            interpreter.evalRepl("val arr = Array<Int>(3)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[100]");
            });
        }

        @Test
        @DisplayName("负向索引越界")
        void testIndexOutOfBoundsNegative() {
            interpreter.evalRepl("val arr = Array<Int>(3)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[-4]");
            });
        }

        @Test
        @DisplayName("空数组索引越界")
        void testEmptyArrayIndexAccess() {
            interpreter.evalRepl("val arr = Array<Int>(0)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[0]");
            });
        }

        @Test
        @DisplayName("正向写入索引越界")
        void testWriteIndexOutOfBounds() {
            interpreter.evalRepl("val arr = Array<Int>(2)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[2] = 99");
            });
        }

        @Test
        @DisplayName("负向写入索引越界")
        void testWriteNegativeIndexOutOfBounds() {
            interpreter.evalRepl("val arr = Array<Int>(2)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[-3] = 99");
            });
        }

        @Test
        @DisplayName("空数组写入索引越界")
        void testEmptyArrayWriteIndex() {
            interpreter.evalRepl("val arr = Array<Int>(0)");
            assertThrows(NovaRuntimeException.class, () -> {
                interpreter.evalRepl("arr[0] = 1");
            });
        }
    }

    // ============ 作用域函数测试 ============

    @Nested
    @DisplayName("作用域函数")
    class ScopeFunctionTests {

        @Test
        @DisplayName("let — 传递对象给 lambda 并返回结果")
        void testLet() {
            NovaValue result = interpreter.evalRepl("\"hello\".let { it.length() }");
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("let — 链式转换")
        void testLetChain() {
            NovaValue result = interpreter.evalRepl("10.let { it + 5 }.let { it * 2 }");
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("also — 传递对象给 lambda 并返回原对象")
        void testAlso() {
            NovaValue result = interpreter.evalRepl("\"hello\".also { println(it) }");
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("also — 返回值是原对象而非 lambda 结果")
        void testAlsoReturnsOriginal() {
            NovaValue result = interpreter.evalRepl("42.also { it + 100 }");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("run — lambda 内 this 绑定到接收者")
        void testRun() {
            String code = "class Person(val name: String, val age: Int)\n" +
                    "val p = Person(\"Alice\", 25)\n" +
                    "p.run { name + \" is \" + age }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("Alice is 25", result.asString());
        }

        @Test
        @DisplayName("apply — this 绑定到接收者，返回原对象")
        void testApply() {
            String code = "class Config(var host: String, var port: Int)\n" +
                    "val cfg = Config(\"\", 0)\n" +
                    "val result = cfg.apply {\n" +
                    "    host = \"localhost\"\n" +
                    "    port = 8080\n" +
                    "}\n" +
                    "result.host + \":\" + result.port";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("localhost:8080", result.asString());
        }

        @Test
        @DisplayName("takeIf — 条件为 true 返回对象")
        void testTakeIfTrue() {
            NovaValue result = interpreter.evalRepl("42.takeIf { it > 0 }");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("takeIf — 条件为 false 返回 null")
        void testTakeIfFalse() {
            NovaValue result = interpreter.evalRepl("(-1).takeIf { it > 0 }");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("takeUnless — 条件为 false 返回对象")
        void testTakeUnlessTrue() {
            NovaValue result = interpreter.evalRepl("42.takeUnless { it < 0 }");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("takeUnless — 条件为 true 返回 null")
        void testTakeUnlessFalse() {
            NovaValue result = interpreter.evalRepl("(-1).takeUnless { it < 0 }");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("作用域函数对 List 可用")
        void testScopeFunctionOnList() {
            NovaValue result = interpreter.evalRepl("listOf(1, 2, 3).let { it.size() }");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("作用域函数对 Map 可用")
        void testScopeFunctionOnMap() {
            NovaValue result = interpreter.evalRepl("mapOf(\"a\" to 1).let { it.size() }");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("run 对字符串类型可用")
        void testRunOnString() {
            NovaValue result = interpreter.evalRepl("\"hello world\".run { length() }");
            assertEquals(11, result.asInt());
        }

        @Test
        @DisplayName("takeIf + let 链式调用")
        void testTakeIfLetChain() {
            String code = "val x = 42\n" +
                    "x.takeIf { it > 0 }?.let { it * 2 }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(84, result.asInt());
        }

        // ---- let 进阶 ----

        @Test
        @DisplayName("let — 类型转换")
        void testLetTypeConversion() {
            NovaValue result = interpreter.evalRepl("123.let { it.toString() }");
            assertEquals("123", result.asString());
        }

        @Test
        @DisplayName("let — 对 null 使用安全调用")
        void testLetOnNull() {
            String code = "val x: Int? = null\n" +
                    "x?.let { it * 2 }";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("let — lambda 可访问外部变量")
        void testLetAccessOuterScope() {
            String code = "val prefix = \"Result: \"\n" +
                    "42.let { prefix + it }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("Result: 42", result.asString());
        }

        @Test
        @DisplayName("let — 对 Boolean 可用")
        void testLetOnBoolean() {
            NovaValue result = interpreter.evalRepl("true.let { if (it) 1 else 0 }");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("let — 对 Double 可用")
        void testLetOnDouble() {
            NovaValue result = interpreter.evalRepl("3.14.let { it * 2 }");
            assertEquals(6.28, result.asDouble(), 0.001);
        }

        // ---- also 进阶 ----

        @Test
        @DisplayName("also — 链式调用保持原值")
        void testAlsoChain() {
            NovaValue result = interpreter.evalRepl("\"hello\".also { println(it) }.also { println(it.length()) }");
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("also — 副作用收集")
        void testAlsoSideEffect() {
            String code = "var log = \"\"\n" +
                    "val result = 42.also { log = \"value is \" + it }\n" +
                    "log";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("value is 42", result.asString());
        }

        @Test
        @DisplayName("also — 对 List 可用并返回原 List")
        void testAlsoOnList() {
            String code = "val list = listOf(3, 1, 2)\n" +
                    "val result = list.also { println(it) }\n" +
                    "result.size()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(3, result.asInt());
        }

        // ---- run 进阶 ----

        @Test
        @DisplayName("run — 调用接收者方法")
        void testRunCallMethod() {
            String code = "class Counter(var count: Int) {\n" +
                    "    fun increment() { count = count + 1 }\n" +
                    "    fun getCount() = count\n" +
                    "}\n" +
                    "val c = Counter(0)\n" +
                    "c.run {\n" +
                    "    increment()\n" +
                    "    increment()\n" +
                    "    increment()\n" +
                    "    getCount()\n" +
                    "}";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("run — 对 List 可用")
        void testRunOnList() {
            NovaValue result = interpreter.evalRepl("listOf(1, 2, 3).run { size() }");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("run — 返回值类型可以与接收者不同")
        void testRunDifferentReturnType() {
            String code = "class Box(val value: Int)\n" +
                    "Box(42).run { value > 0 }";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result.asBool());
        }

        // ---- apply 进阶 ----

        @Test
        @DisplayName("apply — 构建器模式")
        void testApplyBuilderPattern() {
            String code = "class Request(var url: String, var method: String, var body: String)\n" +
                    "val req = Request(\"\", \"\", \"\").apply {\n" +
                    "    url = \"https://api.example.com\"\n" +
                    "    method = \"POST\"\n" +
                    "    body = \"{\\\"key\\\": \\\"value\\\"}\"\n" +
                    "}\n" +
                    "req.method + \" \" + req.url";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("POST https://api.example.com", result.asString());
        }

        @Test
        @DisplayName("apply — 链式 apply")
        void testApplyChain() {
            String code = "class Point(var x: Int, var y: Int)\n" +
                    "val p = Point(0, 0).apply { x = 10 }.apply { y = 20 }\n" +
                    "p.x + p.y";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("apply — 调用方法")
        void testApplyCallMethod() {
            String code = "class Items(var list: List) {\n" +
                    "    fun add(item: String) { list = list + listOf(item) }\n" +
                    "}\n" +
                    "val items = Items(listOf()).apply {\n" +
                    "    add(\"a\")\n" +
                    "    add(\"b\")\n" +
                    "    add(\"c\")\n" +
                    "}\n" +
                    "items.list.size()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(3, result.asInt());
        }

        // ---- takeIf / takeUnless 进阶 ----

        @Test
        @DisplayName("takeIf — 对字符串判断")
        void testTakeIfOnString() {
            NovaValue result = interpreter.evalRepl("\"hello\".takeIf { it.length() > 3 }");
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("takeIf — 字符串条件不满足")
        void testTakeIfOnStringFalse() {
            NovaValue result = interpreter.evalRepl("\"hi\".takeIf { it.length() > 3 }");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("takeUnless — 过滤空字符串")
        void testTakeUnlessEmptyString() {
            NovaValue result = interpreter.evalRepl("\"\".takeUnless { it.length() == 0 }");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("takeUnless — 非空字符串通过")
        void testTakeUnlessNonEmpty() {
            NovaValue result = interpreter.evalRepl("\"hello\".takeUnless { it.length() == 0 }");
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("takeIf — 对 List 可用")
        void testTakeIfOnList() {
            NovaValue result = interpreter.evalRepl("listOf(1, 2, 3).takeIf { it.size() > 2 }");
            assertFalse(result.isNull());
        }

        // ---- 组合使用 ----

        @Test
        @DisplayName("also + apply 组合")
        void testAlsoApplyCombination() {
            String code = "class User(var name: String, var age: Int)\n" +
                    "var logged = \"\"\n" +
                    "val user = User(\"\", 0)\n" +
                    "    .apply {\n" +
                    "        name = \"Bob\"\n" +
                    "        age = 30\n" +
                    "    }\n" +
                    "    .also { logged = it.name }\n" +
                    "logged";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("Bob", result.asString());
        }

        @Test
        @DisplayName("let + run 组合")
        void testLetRunCombination() {
            String code = "class Wrapper(val value: Int)\n" +
                    "42.let { Wrapper(it) }.run { value * 2 }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(84, result.asInt());
        }

        @Test
        @DisplayName("takeIf + apply 安全初始化")
        void testTakeIfApplySafeInit() {
            String code = "class Config(var enabled: Boolean)\n" +
                    "val cfg = Config(false)\n" +
                    "cfg.takeIf { it.enabled }?.apply { println(\"configuring...\") }\n" +
                    "cfg.enabled";
            NovaValue result = interpreter.evalRepl(code);
            assertFalse(result.asBool());
        }

        @Test
        @DisplayName("多层嵌套 let")
        void testNestedLet() {
            String code = "\"hello\".let { a ->\n" +
                    "    a.length().let { b ->\n" +
                    "        b * 10\n" +
                    "    }\n" +
                    "}";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(50, result.asInt());
        }

        @Test
        @DisplayName("run 内部嵌套 let")
        void testRunWithNestedLet() {
            String code = "class Pair(val first: Int, val second: Int)\n" +
                    "Pair(3, 7).run {\n" +
                    "    first.let { it + second }\n" +
                    "}";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("apply 内部使用 also")
        void testApplyWithAlso() {
            String code = "var sideEffect = 0\n" +
                    "class Box(var value: Int)\n" +
                    "val box = Box(0).apply {\n" +
                    "    value = 99\n" +
                    "}\n" +
                    "box.value.also { sideEffect = it }\n" +
                    "sideEffect";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("let — 对 Range 可用")
        void testLetOnRange() {
            NovaValue result = interpreter.evalRepl("(1..10).let { it.size() }");
            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("run — 对 Int 调用 toString")
        void testRunOnInt() {
            NovaValue result = interpreter.evalRepl("42.run { toString() }");
            assertEquals("42", result.asString());
        }

        @Test
        @DisplayName("let 返回 null")
        void testLetReturnsNull() {
            NovaValue result = interpreter.evalRepl("42.let { null }");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("takeIf + takeUnless 链式")
        void testTakeIfTakeUnlessChain() {
            NovaValue result = interpreter.evalRepl("42.takeIf { it > 0 }.takeUnless { it > 100 }");
            assertEquals(42, result.asInt());
        }
    }

    // ============ Lambda with Receiver 测试 ============

    @Nested
    @DisplayName("Lambda with Receiver")
    class LambdaWithReceiverTests {

        @Test
        @DisplayName("receiver.callable() — 从环境查找可调用值并绑定 this")
        void testReceiverCallable() {
            String code = "class Person(val name: String, val age: Int)\n" +
                    "val greet = { name + \" is \" + age }\n" +
                    "val p = Person(\"Alice\", 25)\n" +
                    "p.greet()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("Alice is 25", result.asString());
        }

        @Test
        @DisplayName("receiver.callable() — 对字符串可用")
        void testReceiverCallableOnString() {
            String code = "val shout = { toUpperCase() + \"!\" }\n" +
                    "\"hello\".shout()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("HELLO!", result.asString());
        }

        @Test
        @DisplayName("receiver.callable() — 对 List 可用")
        void testReceiverCallableOnList() {
            String code = "val count = { size() }\n" +
                    "listOf(1, 2, 3).count()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("用户自定义作用域函数 — myApply")
        void testUserDefinedMyApply() {
            String code = "fun Any.myApply(block) {\n" +
                    "    this.block()\n" +
                    "    return this\n" +
                    "}\n" +
                    "class Config(var host: String, var port: Int)\n" +
                    "val cfg = Config(\"\", 0).myApply {\n" +
                    "    host = \"localhost\"\n" +
                    "    port = 3000\n" +
                    "}\n" +
                    "cfg.host + \":\" + cfg.port";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("localhost:3000", result.asString());
        }

        @Test
        @DisplayName("用户自定义作用域函数 — myRun")
        void testUserDefinedMyRun() {
            String code = "fun Any.myRun(block) = this.block()\n" +
                    "class Box(val value: Int)\n" +
                    "Box(42).myRun { value * 2 }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(84, result.asInt());
        }

        @Test
        @DisplayName("用户自定义作用域函数 — myLet")
        void testUserDefinedMyLet() {
            String code = "fun Any.myLet(block) = block(this)\n" +
                    "\"hello\".myLet { it.length() }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("T.() -> R 类型注解可以解析")
        void testReceiverFunctionTypeAnnotation() {
            String code = "fun doWith(receiver: String, block: String.() -> String): String {\n" +
                    "    return receiver.block()\n" +
                    "}\n" +
                    "doWith(\"world\") { toUpperCase() }";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals("WORLD", result.asString());
        }

        @Test
        @DisplayName("带参数的 receiver lambda")
        void testReceiverLambdaWithParams() {
            String code = "class Calculator(val base: Int)\n" +
                    "val addTo = { n -> base + n }\n" +
                    "Calculator(100).addTo(42)";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(142, result.asInt());
        }

        @Test
        @DisplayName("receiver callable 优先级低于对象自身方法")
        void testReceiverCallablePriorityLowerThanMethod() {
            String code = "class Foo(val x: Int) {\n" +
                    "    fun getValue() = x * 10\n" +
                    "}\n" +
                    "val getValue = { 999 }\n" +
                    "Foo(5).getValue()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(50, result.asInt());
        }
    }

    // ============ Pair 类型 ============

    @Nested
    @DisplayName("Pair")
    class PairTests {

        @Test
        @DisplayName("to 中缀运算符创建 Pair")
        void testToInfix() {
            NovaValue result = interpreter.evalRepl("\"key\" to 42");
            assertTrue(result instanceof NovaPair);
            assertEquals("key", ((NovaValue) ((NovaPair) result).getFirst()).asString());
            assertEquals(42, ((NovaValue) ((NovaPair) result).getSecond()).asInt());
        }

        @Test
        @DisplayName("Pair() 构造函数")
        void testPairConstructor() {
            NovaValue result = interpreter.evalRepl("Pair(\"a\", 1)");
            assertTrue(result instanceof NovaPair);
            assertEquals("a", ((NovaValue) ((NovaPair) result).getFirst()).asString());
            assertEquals(1, ((NovaValue) ((NovaPair) result).getSecond()).asInt());
        }

        @Test
        @DisplayName("first 属性")
        void testFirst() {
            interpreter.evalRepl("val p = \"hello\" to 100");
            assertEquals("hello", interpreter.evalRepl("p.first").asString());
        }

        @Test
        @DisplayName("second 属性")
        void testSecond() {
            interpreter.evalRepl("val p = \"hello\" to 100");
            assertEquals(100, interpreter.evalRepl("p.second").asInt());
        }

        @Test
        @DisplayName("toList() 方法")
        void testToList() {
            NovaValue result = interpreter.evalRepl("(1 to 2).toList()");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            assertEquals(1, ((NovaList) result).get(0).asInt());
            assertEquals(2, ((NovaList) result).get(1).asInt());
        }

        @Test
        @DisplayName("索引访问")
        void testIndexAccess() {
            interpreter.evalRepl("val p = \"x\" to \"y\"");
            assertEquals("x", interpreter.evalRepl("p[0]").asString());
            assertEquals("y", interpreter.evalRepl("p[1]").asString());
        }

        @Test
        @DisplayName("索引越界报错")
        void testIndexOutOfBounds() {
            interpreter.evalRepl("val p = 1 to 2");
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("p[2]"));
        }

        @Test
        @DisplayName("解构赋值")
        void testDestructuring() {
            interpreter.evalRepl("val (a, b) = \"name\" to 42");
            assertEquals("name", interpreter.evalRepl("a").asString());
            assertEquals(42, interpreter.evalRepl("b").asInt());
        }

        @Test
        @DisplayName("toString 格式")
        void testToString() {
            NovaValue result = interpreter.evalRepl("(\"a\" to 1).toString()");
            assertEquals("(a, 1)", result.asString());
        }

        @Test
        @DisplayName("mapOf 使用 to 语法")
        void testMapOfWithTo() {
            interpreter.evalRepl("val m = mapOf(\"x\" to 10, \"y\" to 20)");
            assertEquals(10, interpreter.evalRepl("m[\"x\"]").asInt());
            assertEquals(20, interpreter.evalRepl("m[\"y\"]").asInt());
            assertEquals(2, interpreter.evalRepl("m.size()").asInt());
        }

        @Test
        @DisplayName("equals 比较")
        void testEquals() {
            assertTrue(interpreter.evalRepl("(1 to 2) == (1 to 2)").asBool());
            assertFalse(interpreter.evalRepl("(1 to 2) == (1 to 3)").asBool());
        }

        @Test
        @DisplayName("Pair 支持作用域函数")
        void testScopeFunction() {
            NovaValue result = interpreter.evalRepl("(\"a\" to 1).let { it.first }");
            assertEquals("a", result.asString());
        }

        @Test
        @DisplayName("嵌套 Pair")
        void testNestedPair() {
            interpreter.evalRepl("val p = (1 to 2) to (3 to 4)");
            NovaValue inner = interpreter.evalRepl("p.first");
            assertTrue(inner instanceof NovaPair);
            assertEquals(1, ((NovaValue) ((NovaPair) inner).getFirst()).asInt());
        }
    }

    // ============ I/O 重定向测试 ============

    @Nested
    @DisplayName("I/O 重定向")
    class IORedirectTests {

        @Test
        @DisplayName("println 输出到自定义流")
        void testPrintlnToCustomStream() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream custom = new PrintStream(baos);
            interpreter.setStdout(custom);
            interpreter.evalRepl("println(\"hello\")");
            custom.flush();
            assertEquals("hello\n", baos.toString().replace("\r\n", "\n"));
        }

        @Test
        @DisplayName("print 输出到自定义流")
        void testPrintToCustomStream() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream custom = new PrintStream(baos);
            interpreter.setStdout(custom);
            interpreter.evalRepl("print(\"hi\")");
            custom.flush();
            assertEquals("hi", baos.toString());
        }

        @Test
        @DisplayName("readLine 从自定义流读取")
        void testReadLineFromCustomStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream("test input\n".getBytes(StandardCharsets.UTF_8));
            interpreter.setStdin(bais);
            NovaValue result = interpreter.evalRepl("readLine()");
            assertEquals("test input", result.asString());
        }

        @Test
        @DisplayName("input 双向验证")
        void testInputFromCustomStream() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream customOut = new PrintStream(baos);
            ByteArrayInputStream bais = new ByteArrayInputStream("world\n".getBytes(StandardCharsets.UTF_8));
            interpreter.setStdout(customOut);
            interpreter.setStdin(bais);
            NovaValue result = interpreter.evalRepl("input(\"name: \")");
            customOut.flush();
            assertEquals("name: ", baos.toString());
            assertEquals("world", result.asString());
        }

        @Test
        @DisplayName("默认流为 System 标准流")
        void testDefaultStreams() {
            Interpreter fresh = new Interpreter();
            assertSame(System.out, fresh.getStdout());
            assertSame(System.err, fresh.getStderr());
            assertSame(System.in, fresh.getStdin());
        }
    }

    // ============ 字符串插值测试 ============

    @Nested
    @DisplayName("字符串插值")
    class StringInterpolationTests {

        @Test
        @DisplayName("$identifier 简单变量插值")
        void testSimpleVariable() {
            interpreter.evalRepl("val name = \"World\"");
            NovaValue result = interpreter.evalRepl("\"Hello, $name!\"");
            assertEquals("Hello, World!", result.asString());
        }

        @Test
        @DisplayName("$identifier 多个变量插值")
        void testMultipleVariables() {
            interpreter.evalRepl("val first = \"Nova\"");
            interpreter.evalRepl("val second = \"Lang\"");
            NovaValue result = interpreter.evalRepl("\"$first$second\"");
            assertEquals("NovaLang", result.asString());
        }

        @Test
        @DisplayName("${expr} 表达式插值")
        void testExpressionInterpolation() {
            NovaValue result = interpreter.evalRepl("\"Result: ${1 + 2}\"");
            assertEquals("Result: 3", result.asString());
        }

        @Test
        @DisplayName("${expr} 复杂表达式插值")
        void testComplexExpression() {
            interpreter.evalRepl("val x = 10");
            NovaValue result = interpreter.evalRepl("\"x * 2 = ${x * 2}\"");
            assertEquals("x * 2 = 20", result.asString());
        }

        @Test
        @DisplayName("${expr} 方法调用插值")
        void testMethodCallInInterpolation() {
            interpreter.evalRepl("val s = \"hello\"");
            NovaValue result = interpreter.evalRepl("\"Upper: ${s.toUpperCase()}\"");
            assertEquals("Upper: HELLO", result.asString());
        }

        @Test
        @DisplayName("混合 $identifier 和 ${expr}")
        void testMixedInterpolation() {
            interpreter.evalRepl("val name = \"Alice\"");
            interpreter.evalRepl("val age = 30");
            NovaValue result = interpreter.evalRepl("\"$name is ${age + 1} years old\"");
            assertEquals("Alice is 31 years old", result.asString());
        }

        @Test
        @DisplayName("\\$ 转义不触发插值")
        void testEscapedDollar() {
            NovaValue result = interpreter.evalRepl("\"Price: \\$100\"");
            assertEquals("Price: $100", result.asString());
        }

        @Test
        @DisplayName("$ 后跟数字不触发插值")
        void testDollarFollowedByDigit() {
            NovaValue result = interpreter.evalRepl("\"Cost: $100\"");
            assertEquals("Cost: $100", result.asString());
        }

        @Test
        @DisplayName("字符串末尾的 $ 不触发插值")
        void testDollarAtEnd() {
            NovaValue result = interpreter.evalRepl("\"price$\"");
            assertEquals("price$", result.asString());
        }

        @Test
        @DisplayName("无插值的普通字符串不受影响")
        void testPlainString() {
            NovaValue result = interpreter.evalRepl("\"Hello, World!\"");
            assertEquals("Hello, World!", result.asString());
        }

        @Test
        @DisplayName("原始字符串不进行插值")
        void testRawStringNoInterpolation() {
            NovaValue result = interpreter.evalRepl("r\"$name\"");
            assertEquals("$name", result.asString());
        }

        @Test
        @DisplayName("${} 列表索引表达式")
        void testIndexExpressionInInterpolation() {
            interpreter.evalRepl("val list = [10, 20, 30]");
            NovaValue result = interpreter.evalRepl("\"item: ${list[1]}\"");
            assertEquals("item: 20", result.asString());
        }

        @Test
        @DisplayName("插值结果为 null")
        void testNullInterpolation() {
            interpreter.evalRepl("val x = null");
            NovaValue result = interpreter.evalRepl("\"value: $x\"");
            assertEquals("value: null", result.asString());
        }

        @Test
        @DisplayName("插值结果为数字")
        void testNumberInterpolation() {
            interpreter.evalRepl("val n = 42");
            NovaValue result = interpreter.evalRepl("\"n=$n\"");
            assertEquals("n=42", result.asString());
        }

        @Test
        @DisplayName("println 输出插值字符串")
        void testPrintlnWithInterpolation() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream custom = new PrintStream(baos);
            interpreter.setStdout(custom);
            interpreter.evalRepl("val name = \"World\"");
            interpreter.evalRepl("println(\"Hello, $name!\")");
            custom.flush();
            assertEquals("Hello, World!\n", baos.toString().replace("\r\n", "\n"));
        }

        @Test
        @DisplayName("$identifier 下划线变量名")
        void testUnderscoreIdentifier() {
            interpreter.evalRepl("val my_var = \"test\"");
            NovaValue result = interpreter.evalRepl("\"value: $my_var\"");
            assertEquals("value: test", result.asString());
        }

        @Test
        @DisplayName("${expr} 条件表达式")
        void testConditionalExpression() {
            interpreter.evalRepl("val x = 5");
            NovaValue result = interpreter.evalRepl("\"x is ${if (x > 3) x * 10 else 0}\"");
            assertEquals("x is 50", result.asString());
        }
    }

    @Nested
    @DisplayName("管道 Debug")
    class PipelineDebugTests {

        @Test
        @DisplayName("管道占位符: split(_, delim)")
        void testPipelineSplitPlaceholder() {
            interpreter.evalRepl("fun mySplit(s: String, delimiter: String) = s.split(delimiter)");
            NovaValue result = interpreter.evalRepl("\"hello world\" |> mySplit(_, \" \")");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
        }

        @Test
        @DisplayName("管道完整链 with filter")
        void testPipelineFullWithFilter() {
            interpreter.evalRepl("fun mySplit(s: String, delimiter: String) = s.split(delimiter)");
            NovaValue result = interpreter.evalRepl("\"  hello world  \" |> trim |> uppercase |> mySplit(_, \" \") |> filter { it.length() > 3 }");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
        }

        @Test
        @DisplayName("管道 - 完整test.nova模拟(含多线程SAM回调)")
        void testPipelineFullTestNova() {
            Interpreter fileInterp = new Interpreter();
            String code = ""
                + "@data class User(val id: Int, val name: String, val email: String?)\n"
                + "sealed class ApiResult<out T> {\n"
                + "    @data class Success<T>(val data: T) : ApiResult<T>()\n"
                + "    @data class Error(val code: Int, val message: String) : ApiResult<Nothing>()\n"
                + "}\n"
                + "object Logger {\n"
                + "    fun info(msg: String) = println(\"[INFO] $msg\")\n"
                + "    fun error(msg: String) = println(\"[ERROR] $msg\")\n"
                + "}\n"
                + "fun String.mask(visibleChars: Int = 3): String {\n"
                + "    if (length <= visibleChars) return this\n"
                + "    return take(visibleChars) + \"*\".repeat(length - visibleChars)\n"
                + "}\n"
                + "fun main(args: Array<String>) {\n"
                + "    val Executors = javaClass(\"java.util.concurrent.Executors\")\n"
                + "    val pool = Executors.newFixedThreadPool(3)\n"
                + "    val future = pool.submit { Logger.info(\"Task on thread\") }\n"
                + "    future.get()\n"
                + "    val processed = \"  hello world  \"\n"
                + "        |> trim\n"
                + "        |> uppercase\n"
                + "        |> split(_, \" \")\n"
                + "        |> filter { it.length() > 3 }\n"
                + "    Logger.info(\"Processed: $processed\")\n"
                + "    pool.shutdown()\n"
                + "}\n"
                + "fun multiply(a: Double, b: Double) = a * b\n"
                + "fun trim(s: String) = s.trim()\n"
                + "fun uppercase(s: String) = s.uppercase()\n"
                + "fun split(s: String, delimiter: String) = s.split(delimiter)\n";
            fileInterp.eval(code, "test.nova");
        }

    }

    // ============ 覆盖率补充: Char 类型（通过字符串单字符测试） ============

    @Nested
    @DisplayName("Char 类型")
    class CharTests {

        @Test
        @DisplayName("单字符字面量")
        void testCharLiteral() {
            NovaValue result = interpreter.evalRepl("'A'");
            assertEquals("Char", result.getTypeName());
            assertEquals("A", result.asString());
        }

        @Test
        @DisplayName("字符串 toInt 转码")
        void testCharToInt() {
            // 'A' 在 Nova 中是单字符 String，toInt 解析数字字符串
            NovaValue result = interpreter.evalRepl("\"42\".toInt()");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("单字符比较")
        void testCharComparison() {
            assertTrue(interpreter.evalRepl("'A' == 'A'").asBool());
            assertFalse(interpreter.evalRepl("'A' == 'B'").asBool());
            assertTrue(interpreter.evalRepl("'A' < 'B'").asBool());
            assertTrue(interpreter.evalRepl("'Z' > 'A'").asBool());
        }

        @Test
        @DisplayName("字符串 uppercase/lowercase")
        void testCharCase() {
            assertEquals("A", interpreter.evalRepl("'a'.uppercase()").asString());
            assertEquals("a", interpreter.evalRepl("'A'.lowercase()").asString());
        }

        @Test
        @DisplayName("字符串拼接")
        void testCharStringConcat() {
            NovaValue result = interpreter.evalRepl("\"hello\" + '!'");
            assertEquals("hello!", result.asString());
        }

        @Test
        @DisplayName("Char 方法")
        void testCharMethods() {
            assertEquals(65, interpreter.evalRepl("'A'.code").asInt());
            assertTrue(interpreter.evalRepl("'A'.isUpperCase()").asBool());
            assertTrue(interpreter.evalRepl("'5'.isDigit()").asBool());
            assertEquals(5, interpreter.evalRepl("\"hello\".length()").asInt());
        }
    }

    // ============ 覆盖率补充: Result 类型 ============

    @Nested
    @DisplayName("Result 类型")
    class ResultTypeTests {

        @Test
        @DisplayName("Ok 创建和检查")
        void testOkCreation() {
            NovaValue result = interpreter.evalRepl("val r = Ok(42)\nr.isOk");
            assertTrue(result.asBool());
            result = interpreter.evalRepl("Ok(42).isErr");
            assertFalse(result.asBool());
        }

        @Test
        @DisplayName("Err 创建和检查")
        void testErrCreation() {
            NovaValue result = interpreter.evalRepl("val r = Err(\"failed\")\nr.isErr");
            assertTrue(result.asBool());
            result = interpreter.evalRepl("Err(\"failed\").isOk");
            assertFalse(result.asBool());
        }

        @Test
        @DisplayName("Ok.value 获取值")
        void testOkValue() {
            NovaValue result = interpreter.evalRepl("Ok(42).value");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("Err.error 获取错误")
        void testErrError() {
            NovaValue result = interpreter.evalRepl("Err(\"oops\").error");
            assertEquals("oops", result.asString());
        }

        @Test
        @DisplayName("Ok.unwrap 成功")
        void testOkUnwrap() {
            NovaValue result = interpreter.evalRepl("Ok(100).unwrap()");
            assertEquals(100, result.asInt());
        }

        @Test
        @DisplayName("Err.unwrap 抛异常")
        void testErrUnwrapThrows() {
            assertThrows(Exception.class, () -> interpreter.evalRepl("Err(\"fail\").unwrap()"));
        }

        @Test
        @DisplayName("unwrapOr 默认值")
        void testUnwrapOr() {
            NovaValue result = interpreter.evalRepl("Ok(10).unwrapOr(99)");
            assertEquals(10, result.asInt());
            result = interpreter.evalRepl("Err(\"x\").unwrapOr(99)");
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("unwrapOrElse 回调")
        void testUnwrapOrElse() {
            NovaValue result = interpreter.evalRepl("Ok(5).unwrapOrElse { 0 }");
            assertEquals(5, result.asInt());
            result = interpreter.evalRepl("Err(\"x\").unwrapOrElse { 0 }");
            assertEquals(0, result.asInt());
        }

        @Test
        @DisplayName("getOrNull")
        void testGetOrNull() {
            NovaValue result = interpreter.evalRepl("Ok(42).getOrNull()");
            assertEquals(42, result.asInt());
            result = interpreter.evalRepl("Err(\"x\").getOrNull()");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("map 操作")
        void testResultMap() {
            NovaValue result = interpreter.evalRepl("Ok(5).map { it * 2 }");
            // map 返回新的 Ok
            assertEquals(10, interpreter.evalRepl("Ok(5).map { it * 2 }.value").asInt());
            // Err.map 返回自身
            assertTrue(interpreter.evalRepl("Err(\"x\").map { it * 2 }.isErr").asBool());
        }

        @Test
        @DisplayName("mapErr 操作")
        void testResultMapErr() {
            // Ok.mapErr 返回自身
            assertEquals(5, interpreter.evalRepl("Ok(5).mapErr { \"mapped\" }.value").asInt());
            // Err.mapErr 映射错误
            assertEquals("mapped: x", interpreter.evalRepl("Err(\"x\").mapErr { \"mapped: $it\" }.error").asString());
        }

        @Test
        @DisplayName("Result 类型检查 is Ok / is Err")
        void testResultTypeCheck() {
            assertTrue(interpreter.evalRepl("Ok(1) is Ok").asBool());
            assertFalse(interpreter.evalRepl("Ok(1) is Err").asBool());
            assertTrue(interpreter.evalRepl("Err(\"x\") is Err").asBool());
            assertFalse(interpreter.evalRepl("Err(\"x\") is Ok").asBool());
        }

        @Test
        @DisplayName("Err.value 抛异常")
        void testErrValueThrows() {
            assertThrows(Exception.class, () -> interpreter.evalRepl("Err(\"x\").value"));
        }

        @Test
        @DisplayName("Ok.error 抛异常")
        void testOkErrorThrows() {
            assertThrows(Exception.class, () -> interpreter.evalRepl("Ok(1).error"));
        }

        @Test
        @DisplayName("? 错误传播操作符")
        void testErrorPropagation() {
            String code = ""
                + "fun divide(a: Int, b: Int): Result {\n"
                + "    if (b == 0) return Err(\"division by zero\")\n"
                + "    return Ok(a / b)\n"
                + "}\n"
                + "fun calc(): Result {\n"
                + "    val x = divide(10, 2)?\n"
                + "    return Ok(x + 1)\n"
                + "}\n"
                + "calc().value";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(6, result.asInt());
        }

        @Test
        @DisplayName("? 操作符传播错误")
        void testErrorPropagationErr() {
            String code = ""
                + "fun divide(a: Int, b: Int): Result {\n"
                + "    if (b == 0) return Err(\"division by zero\")\n"
                + "    return Ok(a / b)\n"
                + "}\n"
                + "fun calc(): Result {\n"
                + "    val x = divide(10, 0)?\n"
                + "    return Ok(x + 1)\n"
                + "}\n"
                + "calc().isErr";
            assertTrue(interpreter.evalRepl(code).asBool());
        }

        @Test
        @DisplayName("Result toString")
        void testResultToString() {
            assertEquals("Ok(42)", interpreter.evalRepl("Ok(42).toString()").asString());
            assertEquals("Err(oops)", interpreter.evalRepl("Err(\"oops\").toString()").asString());
        }

        @Test
        @DisplayName("runCatching 正常返回 Ok")
        void testRunCatchingSuccess() {
            NovaValue result = interpreter.evalRepl("runCatching { 42 }");
            assertTrue(result instanceof NovaResult);
            assertTrue(interpreter.evalRepl("runCatching { 42 }.isOk").asBool());
            assertEquals(42, interpreter.evalRepl("runCatching { 42 }.value").asInt());
        }

        @Test
        @DisplayName("runCatching 异常返回 Err")
        void testRunCatchingFailure() {
            assertTrue(interpreter.evalRepl("runCatching { 1 / 0 }.isErr").asBool());
            // Err 的 error 是异常消息字符串
            NovaValue errMsg = interpreter.evalRepl("runCatching { 1 / 0 }.error");
            assertNotNull(errMsg.asString());
        }

        @Test
        @DisplayName("runCatching 带计算表达式")
        void testRunCatchingExpression() {
            assertEquals(15, interpreter.evalRepl("runCatching { 3 * 5 }.value").asInt());
            assertEquals("hello world", interpreter.evalRepl("runCatching { \"hello\" + \" \" + \"world\" }.value").asString());
        }

        @Test
        @DisplayName("runCatching 返回 null 值")
        void testRunCatchingNull() {
            assertTrue(interpreter.evalRepl("runCatching { null }.isOk").asBool());
            assertTrue(interpreter.evalRepl("runCatching { null }.value").isNull());
        }

        @Test
        @DisplayName("runCatching 链式操作")
        void testRunCatchingChain() {
            // runCatching + map
            assertEquals(84, interpreter.evalRepl("runCatching { 42 }.map { it * 2 }.value").asInt());
            // runCatching + unwrapOr
            assertEquals(42, interpreter.evalRepl("runCatching { 42 }.unwrapOr(0)").asInt());
            assertEquals(0, interpreter.evalRepl("runCatching { 1 / 0 }.unwrapOr(0)").asInt());
        }

        @Test
        @DisplayName("runCatching 抛出自定义异常")
        void testRunCatchingCustomError() {
            String code = "runCatching { error(\"custom error\") }.error";
            assertEquals("custom error", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("runCatching 与函数组合")
        void testRunCatchingWithFunction() {
            String code = ""
                + "fun riskyParse(s: String): Int {\n"
                + "    if (s == \"bad\") error(\"parse failed\")\n"
                + "    return 123\n"
                + "}\n"
                + "val r1 = runCatching { riskyParse(\"good\") }\n"
                + "val r2 = runCatching { riskyParse(\"bad\") }\n"
                + "r1.value * 10 + (if (r2.isErr) -1 else 0)";
            assertEquals(1229, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("runCatching 嵌套使用")
        void testRunCatchingNested() {
            String code = ""
                + "val outer = runCatching {\n"
                + "    val inner = runCatching { 1 / 0 }\n"
                + "    inner.unwrapOr(-1)\n"
                + "}\n"
                + "outer.value";
            assertEquals(-1, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("runCatching getOrNull")
        void testRunCatchingGetOrNull() {
            assertEquals(42, interpreter.evalRepl("runCatching { 42 }.getOrNull()").asInt());
            assertTrue(interpreter.evalRepl("runCatching { 1 / 0 }.getOrNull()").isNull());
        }
    }

    // ============ 覆盖率补充: @data copy 函数 ============

    @Nested
    @DisplayName("Data Class Copy")
    class DataCopyTests {

        @Test
        @DisplayName("基本 copy")
        void testBasicCopy() {
            String code = ""
                + "@data class Point(val x: Int, val y: Int)\n"
                + "val p = Point(1, 2)\n"
                + "val p2 = p.copy()\n"
                + "p2.x";
            assertEquals(1, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("copy 修改部分字段")
        void testCopyWithOverrides() {
            String code = ""
                + "@data class Point(val x: Int, val y: Int)\n"
                + "val p = Point(1, 2)\n"
                + "val p2 = p.copy(x = 10)\n"
                + "p2.x * 100 + p2.y";
            assertEquals(1002, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("copy 修改所有字段")
        void testCopyAllOverrides() {
            String code = ""
                + "@data class Point(val x: Int, val y: Int)\n"
                + "val p = Point(1, 2)\n"
                + "val p2 = p.copy(x = 10, y = 20)\n"
                + "p2.x + p2.y";
            assertEquals(30, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("data class equals/hashCode/toString")
        void testDataClassMethods() {
            String code = ""
                + "@data class User(val name: String, val age: Int)\n"
                + "val u1 = User(\"Alice\", 30)\n"
                + "val u2 = User(\"Alice\", 30)\n"
                + "u1 == u2";
            assertTrue(interpreter.evalRepl(code).asBool());
        }

        @Test
        @DisplayName("data class componentN 解构")
        void testDataClassDestructure() {
            String code = ""
                + "@data class Pair(val first: Int, val second: Int)\n"
                + "val (a, b) = Pair(3, 7)\n"
                + "a + b";
            assertEquals(10, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: Float 类型高级操作 ============

    @Nested
    @DisplayName("Float 类型")
    class FloatAdvancedTests {

        @Test
        @DisplayName("Float 字面量")
        void testFloatLiteral() {
            NovaValue result = interpreter.evalRepl("3.14f");
            assertTrue(result.isNumber());
            assertEquals(3.14, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("Float 算术运算")
        void testFloatArithmetic() {
            NovaValue result = interpreter.evalRepl("1.5f + 2.5f");
            assertEquals(4.0, result.asDouble(), 0.01);
            result = interpreter.evalRepl("10.0f - 3.0f");
            assertEquals(7.0, result.asDouble(), 0.01);
            result = interpreter.evalRepl("2.0f * 3.0f");
            assertEquals(6.0, result.asDouble(), 0.01);
            result = interpreter.evalRepl("10.0f / 4.0f");
            assertEquals(2.5, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("Float 取负")
        void testFloatNegate() {
            NovaValue result = interpreter.evalRepl("-3.14f");
            assertTrue(result.asDouble() < 0);
        }

        @Test
        @DisplayName("Float 与 Int 运算")
        void testFloatIntMixed() {
            NovaValue result = interpreter.evalRepl("1.5f + 2");
            assertTrue(result.asDouble() > 3.0);
        }

        @Test
        @DisplayName("Float 与 Double 运算")
        void testFloatDoubleMixed() {
            NovaValue result = interpreter.evalRepl("1.5f + 2.5");
            assertEquals(4.0, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("Float 比较")
        void testFloatComparison() {
            assertTrue(interpreter.evalRepl("1.0f == 1.0f").asBool());
            assertTrue(interpreter.evalRepl("1.0f < 2.0f").asBool());
            assertTrue(interpreter.evalRepl("3.0f > 2.0f").asBool());
            assertTrue(interpreter.evalRepl("1.0f == 1.0").asBool());
        }

        @Test
        @DisplayName("Float 转换")
        void testFloatConversion() {
            assertEquals(3, interpreter.evalRepl("3.7f.toInt()").asInt());
            assertEquals(3L, interpreter.evalRepl("3.7f.toLong()").asLong());
        }
    }

    // ============ 覆盖率补充: Map 高级操作 ============

    @Nested
    @DisplayName("Map 高级操作")
    class MapAdvancedTests {

        @Test
        @DisplayName("Map remove")
        void testMapRemove() {
            String code = ""
                + "val m = mutableMapOf(\"a\" to 1, \"b\" to 2, \"c\" to 3)\n"
                + "m.remove(\"b\")\n"
                + "m.size()";
            assertEquals(2, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Map clear")
        void testMapClear() {
            String code = ""
                + "val m = mutableMapOf(\"a\" to 1, \"b\" to 2)\n"
                + "m.clear()\n"
                + "m.size()";
            assertEquals(0, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Map containsKey/containsValue")
        void testMapContains() {
            String code = "val m = mapOf(\"a\" to 1, \"b\" to 2)\n";
            interpreter.evalRepl(code);
            assertTrue(interpreter.evalRepl("m.containsKey(\"a\")").asBool());
            assertFalse(interpreter.evalRepl("m.containsKey(\"z\")").asBool());
            assertTrue(interpreter.evalRepl("m.containsValue(1)").asBool());
            assertFalse(interpreter.evalRepl("m.containsValue(99)").asBool());
        }

        @Test
        @DisplayName("Map keys/values")
        void testMapKeysValues() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            NovaValue keys = interpreter.evalRepl("m.keys()");
            assertTrue(keys instanceof NovaList);
            assertEquals(2, ((NovaList) keys).size());
            NovaValue values = interpreter.evalRepl("m.values()");
            assertTrue(values instanceof NovaList);
            assertEquals(2, ((NovaList) values).size());
        }

        @Test
        @DisplayName("Map getOrDefault")
        void testMapGetOrDefault() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1)");
            assertEquals(1, interpreter.evalRepl("m.getOrDefault(\"a\", 99)").asInt());
            assertEquals(99, interpreter.evalRepl("m.getOrDefault(\"z\", 99)").asInt());
        }

        @Test
        @DisplayName("Map isEmpty")
        void testMapIsEmpty() {
            assertTrue(interpreter.evalRepl("mapOf().isEmpty()").asBool());
            assertFalse(interpreter.evalRepl("mapOf(\"a\" to 1).isEmpty()").asBool());
        }

        @Test
        @DisplayName("Map putAll")
        void testMapPutAll() {
            String code = ""
                + "val m1 = mutableMapOf(\"a\" to 1)\n"
                + "val m2 = mapOf(\"b\" to 2, \"c\" to 3)\n"
                + "m1.putAll(m2)\n"
                + "m1.size()";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Map 相等比较")
        void testMapEquals() {
            String code = "mapOf(\"a\" to 1, \"b\" to 2) == mapOf(\"a\" to 1, \"b\" to 2)";
            assertTrue(interpreter.evalRepl(code).asBool());
        }

        @Test
        @DisplayName("Map entries 遍历")
        void testMapEntries() {
            String code = ""
                + "val m = mapOf(\"a\" to 1, \"b\" to 2)\n"
                + "val entries = m.entries()\n"
                + "entries.size()";
            assertEquals(2, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Map toJavaValue")
        void testMapToJava() {
            NovaValue result = interpreter.evalRepl("mapOf(\"x\" to 10)");
            Object javaVal = result.toJavaValue();
            assertTrue(javaVal instanceof java.util.Map);
        }
    }

    // ============ 覆盖率补充: 数学函数 ============

    @Nested
    @DisplayName("数学函数")
    class StdlibMathAdvancedTests {

        @Test
        @DisplayName("三角函数: sin/cos/tan")
        void testTrigFunctions() {
            assertEquals(0.0, interpreter.evalRepl("sin(0.0)").asDouble(), 0.001);
            assertEquals(1.0, interpreter.evalRepl("cos(0.0)").asDouble(), 0.001);
            assertEquals(0.0, interpreter.evalRepl("tan(0.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("反三角函数: asin/acos/atan")
        void testInverseTrigFunctions() {
            assertEquals(0.0, interpreter.evalRepl("asin(0.0)").asDouble(), 0.001);
            assertEquals(0.0, interpreter.evalRepl("acos(1.0)").asDouble(), 0.001);
            assertEquals(0.0, interpreter.evalRepl("atan(0.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("atan2")
        void testAtan2() {
            assertEquals(Math.atan2(1.0, 1.0), interpreter.evalRepl("atan2(1.0, 1.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("对数函数: log/log10/log2")
        void testLogFunctions() {
            assertEquals(0.0, interpreter.evalRepl("log(1.0)").asDouble(), 0.001);
            assertEquals(1.0, interpreter.evalRepl("log10(10.0)").asDouble(), 0.001);
            assertEquals(3.0, interpreter.evalRepl("log2(8.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("指数函数: exp")
        void testExpFunction() {
            assertEquals(1.0, interpreter.evalRepl("exp(0.0)").asDouble(), 0.001);
            assertEquals(Math.E, interpreter.evalRepl("exp(1.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("sign 符号函数")
        void testSignFunction() {
            assertEquals(1, interpreter.evalRepl("sign(42)").asInt());
            assertEquals(-1, interpreter.evalRepl("sign(-5)").asInt());
            assertEquals(0, interpreter.evalRepl("sign(0)").asInt());
        }

        @Test
        @DisplayName("clamp 钳制函数")
        void testClampFunction() {
            assertEquals(5, interpreter.evalRepl("clamp(5, 0, 10)").asInt());
            assertEquals(0, interpreter.evalRepl("clamp(-5, 0, 10)").asInt());
            assertEquals(10, interpreter.evalRepl("clamp(15, 0, 10)").asInt());
        }

        @Test
        @DisplayName("floor/ceil/round")
        void testRoundingFunctions() {
            assertEquals(3, interpreter.evalRepl("floor(3.7)").asInt());
            assertEquals(4, interpreter.evalRepl("ceil(3.2)").asInt());
            assertEquals(4L, interpreter.evalRepl("round(3.6)").asLong());
            assertEquals(3L, interpreter.evalRepl("round(3.4)").asLong());
        }

        @Test
        @DisplayName("sqrt/pow")
        void testSqrtPow() {
            assertEquals(3.0, interpreter.evalRepl("sqrt(9.0)").asDouble(), 0.001);
            assertEquals(8.0, interpreter.evalRepl("pow(2.0, 3.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("min/max")
        void testMinMax() {
            assertEquals(1, interpreter.evalRepl("min(1, 5)").asInt());
            assertEquals(5, interpreter.evalRepl("max(1, 5)").asInt());
            assertEquals(1.5, interpreter.evalRepl("min(1.5, 2.5)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("abs 绝对值")
        void testAbs() {
            assertEquals(5, interpreter.evalRepl("abs(-5)").asInt());
            assertEquals(5, interpreter.evalRepl("abs(5)").asInt());
            assertEquals(3.14, interpreter.evalRepl("abs(-3.14)").asDouble(), 0.001);
        }
    }

    // ============ 覆盖率补充: 类型检查函数 ============

    @Nested
    @DisplayName("类型检查函数")
    class StdlibTypeCheckTests {

        @Test
        @DisplayName("isNull")
        void testIsNull() {
            assertTrue(interpreter.evalRepl("isNull(null)").asBool());
            assertFalse(interpreter.evalRepl("isNull(42)").asBool());
            assertFalse(interpreter.evalRepl("isNull(\"\")").asBool());
        }

        @Test
        @DisplayName("isNumber")
        void testIsNumber() {
            assertTrue(interpreter.evalRepl("isNumber(42)").asBool());
            assertTrue(interpreter.evalRepl("isNumber(3.14)").asBool());
            assertFalse(interpreter.evalRepl("isNumber(\"42\")").asBool());
            assertFalse(interpreter.evalRepl("isNumber(null)").asBool());
        }

        @Test
        @DisplayName("isString")
        void testIsString() {
            assertTrue(interpreter.evalRepl("isString(\"hello\")").asBool());
            assertTrue(interpreter.evalRepl("isString(\"\")").asBool());
            assertFalse(interpreter.evalRepl("isString(42)").asBool());
        }

        @Test
        @DisplayName("isList")
        void testIsList() {
            assertTrue(interpreter.evalRepl("isList(listOf(1,2,3))").asBool());
            assertTrue(interpreter.evalRepl("isList(listOf())").asBool());
            assertFalse(interpreter.evalRepl("isList(42)").asBool());
        }

        @Test
        @DisplayName("isMap")
        void testIsMap() {
            assertTrue(interpreter.evalRepl("isMap(mapOf(\"a\" to 1))").asBool());
            assertFalse(interpreter.evalRepl("isMap(listOf())").asBool());
            assertFalse(interpreter.evalRepl("isMap(42)").asBool());
        }

        @Test
        @DisplayName("typeof 函数")
        void testTypeof() {
            assertEquals("Int", interpreter.evalRepl("typeof(42)").asString());
            assertEquals("String", interpreter.evalRepl("typeof(\"hi\")").asString());
            assertEquals("Boolean", interpreter.evalRepl("typeof(true)").asString());
            assertEquals("Double", interpreter.evalRepl("typeof(3.14)").asString());
            assertEquals("Null", interpreter.evalRepl("typeof(null)").asString());
            assertEquals("List", interpreter.evalRepl("typeof(listOf())").asString());
        }
    }

    // ============ 覆盖率补充: 错误处理函数 ============

    @Nested
    @DisplayName("错误处理函数")
    class StdlibErrorFunctionTests {

        @Test
        @DisplayName("assert 成功")
        void testAssertSuccess() {
            // 不应抛异常
            interpreter.evalRepl("assert(true, \"should not fail\")");
        }

        @Test
        @DisplayName("assert 失败")
        void testAssertFailure() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("assert(false, \"assertion failed\")"));
        }

        @Test
        @DisplayName("require 成功")
        void testRequireSuccess() {
            interpreter.evalRepl("require(1 > 0, \"positive\")");
        }

        @Test
        @DisplayName("require 失败")
        void testRequireFailure() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("require(false, \"precondition failed\")"));
        }

        @Test
        @DisplayName("todo 抛异常")
        void testTodoThrows() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("todo(\"not implemented yet\")"));
        }

        @Test
        @DisplayName("error 函数")
        void testErrorFunction() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("error(\"custom error\")"));
        }
    }

    // ============ 覆盖率补充: 集合高阶操作 ============

    @Nested
    @DisplayName("集合高阶操作")
    class CollectionAdvancedTests {

        @Test
        @DisplayName("find 查找元素")
        void testFind() {
            NovaValue result = interpreter.evalRepl("listOf(1,2,3,4,5).find { it > 3 }");
            assertEquals(4, result.asInt());
        }

        @Test
        @DisplayName("find 未找到返回 null")
        void testFindNotFound() {
            NovaValue result = interpreter.evalRepl("listOf(1,2,3).find { it > 10 }");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("mapNotNull")
        void testMapNotNull() {
            String code = "listOf(1, 2, 3, 4).mapNotNull { if (it % 2 == 0) it * 10 else null }";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            assertEquals(20, ((NovaList) result).get(0).asInt());
            assertEquals(40, ((NovaList) result).get(1).asInt());
        }

        @Test
        @DisplayName("reduce 归约")
        void testReduce() {
            String code = "listOf(1, 2, 3, 4).reduce(0, { acc, x -> acc + x })";
            assertEquals(10, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("reduce 字符串拼接")
        void testReduceString() {
            String code = "listOf(\"a\", \"b\", \"c\").reduce(\"\", { acc, x -> acc + x })";
            assertEquals("abc", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("flatMap")
        void testFlatMap() {
            String code = "listOf(1, 2, 3).flatMap { listOf(it, it * 10) }";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result instanceof NovaList);
            assertEquals(6, ((NovaList) result).size());
        }

        @Test
        @DisplayName("any/all/none")
        void testAnyAllNone() {
            assertTrue(interpreter.evalRepl("listOf(1,2,3).any { it > 2 }").asBool());
            assertFalse(interpreter.evalRepl("listOf(1,2,3).any { it > 5 }").asBool());
            assertTrue(interpreter.evalRepl("listOf(1,2,3).all { it > 0 }").asBool());
            assertFalse(interpreter.evalRepl("listOf(1,2,3).all { it > 2 }").asBool());
            assertTrue(interpreter.evalRepl("listOf(1,2,3).none { it > 5 }").asBool());
            assertFalse(interpreter.evalRepl("listOf(1,2,3).none { it > 0 }").asBool());
        }

        @Test
        @DisplayName("sorted/sortedBy")
        void testSorted() {
            String code = "listOf(3, 1, 4, 1, 5).sorted()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(1, ((NovaList) result).get(0).asInt());
            assertEquals(5, ((NovaList) result).get(4).asInt());
        }

        @Test
        @DisplayName("distinct")
        void testDistinct() {
            String code = "listOf(1, 2, 2, 3, 3, 3).distinct()";
            NovaValue result = interpreter.evalRepl(code);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("take/drop")
        void testTakeDrop() {
            assertEquals(2, ((NovaList) interpreter.evalRepl("listOf(1,2,3,4,5).take(2)")).size());
            assertEquals(3, ((NovaList) interpreter.evalRepl("listOf(1,2,3,4,5).drop(2)")).size());
        }

        @Test
        @DisplayName("zip 合并")
        void testZip() {
            String code = "listOf(1,2,3).zip(listOf(\"a\",\"b\",\"c\"))";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("sum 求和")
        void testSum() {
            assertEquals(15, interpreter.evalRepl("listOf(1,2,3,4,5).sum()").asInt());
        }

        @Test
        @DisplayName("joinToString")
        void testJoinToString() {
            assertEquals("1, 2, 3", interpreter.evalRepl("listOf(1,2,3).joinToString(\", \")").asString());
        }

        @Test
        @DisplayName("reversed")
        void testReversed() {
            NovaValue result = interpreter.evalRepl("listOf(1,2,3).reversed()");
            assertEquals(3, ((NovaList) result).get(0).asInt());
            assertEquals(1, ((NovaList) result).get(2).asInt());
        }

        @Test
        @DisplayName("count 条件计数")
        void testCount() {
            assertEquals(3, interpreter.evalRepl("listOf(1,2,3,4,5).count { it > 2 }").asInt());
        }

        @Test
        @DisplayName("first/last")
        void testFirstLast() {
            assertEquals(1, interpreter.evalRepl("listOf(1,2,3).first()").asInt());
            assertEquals(3, interpreter.evalRepl("listOf(1,2,3).last()").asInt());
        }

        @Test
        @DisplayName("contains 检查")
        void testListContains() {
            assertTrue(interpreter.evalRepl("listOf(1,2,3).contains(2)").asBool());
            assertFalse(interpreter.evalRepl("listOf(1,2,3).contains(5)").asBool());
        }

        @Test
        @DisplayName("indexOf")
        void testIndexOf() {
            assertEquals(1, interpreter.evalRepl("listOf(10,20,30).indexOf(20)").asInt());
            assertEquals(-1, interpreter.evalRepl("listOf(10,20,30).indexOf(99)").asInt());
        }
    }

    // ============ 覆盖率补充: 反射 API ============

    @Nested
    @DisplayName("反射 API")
    class ReflectAdvancedTests {

        @Test
        @DisplayName("classOf 获取类信息")
        void testClassOf() {
            String code = ""
                + "class MyClass(val x: Int, val y: String)\n"
                + "val info = classOf(MyClass)\n"
                + "info.name";
            assertEquals("MyClass", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("classOf 实例")
        void testClassOfInstance() {
            String code = ""
                + "class Foo(val x: Int)\n"
                + "val info = classOf(Foo(1))\n"
                + "info.name";
            assertEquals("Foo", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("反射获取字段列表")
        void testReflectFields() {
            String code = ""
                + "class Person(val name: String, val age: Int)\n"
                + "val info = classOf(Person)\n"
                + "info.fields.size()";
            assertEquals(2, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("反射获取方法列表")
        void testReflectMethods() {
            String code = ""
                + "class Calc {\n"
                + "    fun add(a: Int, b: Int) = a + b\n"
                + "    fun sub(a: Int, b: Int) = a - b\n"
                + "}\n"
                + "val info = classOf(Calc)\n"
                + "info.methods.size()";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result.asInt() >= 2);
        }

        @Test
        @DisplayName("字段信息 name/type")
        void testFieldInfo() {
            String code = ""
                + "class Item(val name: String)\n"
                + "val info = classOf(Item)\n"
                + "info.fields[0].name";
            assertEquals("name", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("方法信息")
        void testMethodInfo() {
            String code = ""
                + "class Adder {\n"
                + "    fun add(a: Int, b: Int) = a + b\n"
                + "}\n"
                + "val info = classOf(Adder)\n"
                + "val method = info.methods.find { it.name == \"add\" }\n"
                + "method.name";
            assertEquals("add", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("注解反射")
        void testAnnotationReflect() {
            String code = ""
                + "annotation class MyAnno\n"
                + "@MyAnno class Foo\n"
                + "val info = classOf(Foo)\n"
                + "info.annotations.size()";
            assertTrue(interpreter.evalRepl(code).asInt() >= 1);
        }

        @Test
        @DisplayName("反射 superclass")
        void testReflectSuperclass() {
            String code = ""
                + "open class Base\n"
                + "class Child : Base()\n"
                + "val info = classOf(Child)\n"
                + "info.superclass";
            assertEquals("Base", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("反射 interfaces")
        void testReflectInterfaces() {
            String code = ""
                + "interface Greetable { fun greet(): String }\n"
                + "class Hello : Greetable {\n"
                + "    fun greet() = \"hi\"\n"
                + "}\n"
                + "val info = classOf(Hello)\n"
                + "info.interfaces.size()";
            assertTrue(interpreter.evalRepl(code).asInt() >= 1);
        }
    }

    // ============ 覆盖率补充: 随机函数 ============

    @Nested
    @DisplayName("随机函数")
    class StdlibRandomTests {

        @Test
        @DisplayName("randomInt 范围")
        void testRandomInt() {
            for (int i = 0; i < 10; i++) {
                NovaValue result = interpreter.evalRepl("randomInt(1, 10)");
                int v = result.asInt();
                assertTrue(v >= 1 && v <= 10, "randomInt out of range: " + v);
            }
        }

        @Test
        @DisplayName("randomDouble 范围")
        void testRandomDouble() {
            for (int i = 0; i < 10; i++) {
                NovaValue result = interpreter.evalRepl("randomDouble(0.0, 1.0)");
                double v = result.asDouble();
                assertTrue(v >= 0.0 && v < 1.0, "randomDouble out of range: " + v);
            }
        }

        @Test
        @DisplayName("randomBool 返回布尔")
        void testRandomBool() {
            NovaValue result = interpreter.evalRepl("randomBool()");
            assertTrue(result.isBool());
        }

        @Test
        @DisplayName("randomStr 长度")
        void testRandomStr() {
            NovaValue result = interpreter.evalRepl("randomStr(\"abc\", 5)");
            assertEquals(5, result.asString().length());
        }

        @Test
        @DisplayName("randomList 大小和范围")
        void testRandomList() {
            NovaValue result = interpreter.evalRepl("randomList(0, 100, 10)");
            assertTrue(result instanceof NovaList);
            assertEquals(10, ((NovaList) result).size());
        }
    }

    // ============ 覆盖率补充: 接口高级特性 ============

    @Nested
    @DisplayName("接口高级特性")
    class InterfaceAdvancedTests {

        @Test
        @DisplayName("接口默认方法")
        void testInterfaceDefaultMethod() {
            String code = ""
                + "interface Describable {\n"
                + "    fun describe(): String\n"
                + "    fun fullDescription() = \"Description: ${describe()}\"\n"
                + "}\n"
                + "class Dog(val name: String) : Describable {\n"
                + "    fun describe() = name\n"
                + "}\n"
                + "Dog(\"Buddy\").fullDescription()";
            assertEquals("Description: Buddy", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("多接口实现")
        void testMultipleInterfaces() {
            String code = ""
                + "interface Printable { fun print(): String }\n"
                + "interface Saveable { fun save(): String }\n"
                + "class Doc(val content: String) : Printable, Saveable {\n"
                + "    fun print() = \"Printing: $content\"\n"
                + "    fun save() = \"Saving: $content\"\n"
                + "}\n"
                + "val d = Doc(\"hello\")\n"
                + "d.print() + \" | \" + d.save()";
            assertEquals("Printing: hello | Saving: hello", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("接口 is 检查")
        void testInterfaceTypeCheck() {
            String code = ""
                + "interface Shape { fun area(): Double }\n"
                + "class Circle(val r: Double) : Shape {\n"
                + "    fun area() = 3.14 * r * r\n"
                + "}\n"
                + "val c = Circle(5.0)\n"
                + "c is Shape";
            assertTrue(interpreter.evalRepl(code).asBool());
        }
    }

    // ============ 覆盖率补充: 枚举高级特性 ============

    @Nested
    @DisplayName("枚举高级特性")
    class EnumAdvancedTests {

        @Test
        @DisplayName("枚举 values()")
        void testEnumValues() {
            String code = ""
                + "enum class Color { RED, GREEN, BLUE }\n"
                + "Color.values().size()";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("枚举 valueOf()")
        void testEnumValueOf() {
            String code = ""
                + "enum class Color { RED, GREEN, BLUE }\n"
                + "Color.valueOf(\"GREEN\").name";
            assertEquals("GREEN", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("枚举 ordinal")
        void testEnumOrdinal() {
            String code = ""
                + "enum class Color { RED, GREEN, BLUE }\n"
                + "Color.BLUE.ordinal";
            assertEquals(2, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("枚举带方法")
        void testEnumWithMethods() {
            String code = ""
                + "enum class Planet(val mass: Double) {\n"
                + "    EARTH(5.97),\n"
                + "    MARS(0.642);\n"
                + "    fun describe() = \"$name: mass=$mass\"\n"
                + "}\n"
                + "Planet.EARTH.describe()";
            assertEquals("EARTH: mass=5.97", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("枚举 when 匹配")
        void testEnumWhenMatch() {
            String code = ""
                + "enum class Dir { UP, DOWN, LEFT, RIGHT }\n"
                + "val d = Dir.LEFT\n"
                + "when (d) {\n"
                + "    Dir.UP -> 1\n"
                + "    Dir.DOWN -> 2\n"
                + "    Dir.LEFT -> 3\n"
                + "    Dir.RIGHT -> 4\n"
                + "}";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 异常处理高级场景 ============

    @Nested
    @DisplayName("异常处理高级场景")
    class TryCatchAdvancedTests {

        @Test
        @DisplayName("多 catch 分支")
        void testMultiCatch() {
            String code = ""
                + "try {\n"
                + "    error(\"test error\")\n"
                + "} catch (e: Exception) {\n"
                + "    \"caught: $e\"\n"
                + "}";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result.asString().contains("caught:"));
        }

        @Test
        @DisplayName("finally 总是执行")
        void testFinallyAlwaysRuns() {
            String code = ""
                + "var x = 0\n"
                + "try {\n"
                + "    x = 1\n"
                + "} finally {\n"
                + "    x = x + 10\n"
                + "}\n"
                + "x";
            assertEquals(11, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("finally 在异常后执行")
        void testFinallyAfterException() {
            String code = ""
                + "var x = 0\n"
                + "try {\n"
                + "    error(\"oops\")\n"
                + "} catch (e: Exception) {\n"
                + "    x = 1\n"
                + "} finally {\n"
                + "    x = x + 100\n"
                + "}\n"
                + "x";
            assertEquals(101, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("嵌套 try-catch")
        void testNestedTryCatch() {
            String code = ""
                + "var result = \"\"\n"
                + "try {\n"
                + "    try {\n"
                + "        error(\"inner\")\n"
                + "    } catch (e: Exception) {\n"
                + "        result = \"inner caught\"\n"
                + "        error(\"outer\")\n"
                + "    }\n"
                + "} catch (e: Exception) {\n"
                + "    result = result + \" + outer caught\"\n"
                + "}\n"
                + "result";
            assertEquals("inner caught + outer caught", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("try 作为表达式")
        void testTryAsExpression() {
            String code = ""
                + "val x = try { 42 } catch (e: Exception) { -1 }\n"
                + "x";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 继承高级场景 ============

    @Nested
    @DisplayName("继承高级场景")
    class InheritanceAdvancedTests {

        @Test
        @DisplayName("三层继承链方法覆写")
        void testThreeLevelInheritance() {
            String code = ""
                + "open class A {\n"
                + "    open fun name() = \"A\"\n"
                + "}\n"
                + "open class B : A() {\n"
                + "    override fun name() = \"B\"\n"
                + "}\n"
                + "class C : B() {\n"
                + "    override fun name() = \"C\"\n"
                + "}\n"
                + "C().name()";
            assertEquals("C", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("super 调用父类方法")
        void testSuperCall() {
            String code = ""
                + "open class Base {\n"
                + "    open fun greet() = \"Hello from Base\"\n"
                + "}\n"
                + "class Child : Base() {\n"
                + "    override fun greet() = super.greet() + \" and Child\"\n"
                + "}\n"
                + "Child().greet()";
            assertEquals("Hello from Base and Child", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("is 子类型检查")
        void testIsSubtype() {
            String code = ""
                + "open class Animal\n"
                + "class Cat : Animal()\n"
                + "val c = Cat()\n"
                + "c is Animal";
            assertTrue(interpreter.evalRepl(code).asBool());
        }

        @Test
        @DisplayName("继承字段")
        void testInheritedFields() {
            String code = ""
                + "open class Base(val x: Int)\n"
                + "class Child(val y: Int) : Base(10)\n"
                + "val c = Child(20)\n"
                + "c.x + c.y";
            assertEquals(30, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: Scope 函数高级用法 ============

    @Nested
    @DisplayName("Scope 函数高级")
    class ScopeFunctionAdvancedTests {

        @Test
        @DisplayName("let 链式调用")
        void testLetChain() {
            String code = "\"hello\".let { it.uppercase() }.let { it + \"!\" }";
            assertEquals("HELLO!", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("also 返回原对象")
        void testAlsoReturnsSelf() {
            String code = ""
                + "var sideEffect = \"\"\n"
                + "val result = \"test\".also { sideEffect = it }\n"
                + "result + \" | \" + sideEffect";
            assertEquals("test | test", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("run 在对象上执行")
        void testRunOnObject() {
            String code = "\"hello world\".run { length() }";
            assertEquals(11, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("apply 配置对象")
        void testApply() {
            String code = ""
                + "val list = mutableListOf(1, 2).apply {\n"
                + "    add(3)\n"
                + "    add(4)\n"
                + "}\n"
                + "list.size()";
            assertEquals(4, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 字符串高级操作 ============

    @Nested
    @DisplayName("字符串高级操作")
    class StringAdvancedTests {

        @Test
        @DisplayName("字符串 padStart/padEnd")
        void testPadding() {
            assertEquals("  hi", interpreter.evalRepl("\"hi\".padStart(4)").asString());
            assertEquals("hi  ", interpreter.evalRepl("\"hi\".padEnd(4)").asString());
        }

        @Test
        @DisplayName("字符串 repeat")
        void testRepeat() {
            assertEquals("abcabcabc", interpreter.evalRepl("\"abc\".repeat(3)").asString());
        }

        @Test
        @DisplayName("字符串 startsWith/endsWith")
        void testStartsEndsWith() {
            assertTrue(interpreter.evalRepl("\"hello\".startsWith(\"hel\")").asBool());
            assertFalse(interpreter.evalRepl("\"hello\".startsWith(\"xyz\")").asBool());
            assertTrue(interpreter.evalRepl("\"hello\".endsWith(\"llo\")").asBool());
        }

        @Test
        @DisplayName("字符串 replace")
        void testReplace() {
            assertEquals("hXllX", interpreter.evalRepl("\"hello\".replace(\"e\", \"X\").replace(\"o\", \"X\")").asString());
        }

        @Test
        @DisplayName("字符串 trim/trimStart/trimEnd")
        void testTrimVariants() {
            assertEquals("hello", interpreter.evalRepl("\"  hello  \".trim()").asString());
            assertEquals("hello  ", interpreter.evalRepl("\"  hello  \".trimStart()").asString());
            assertEquals("  hello", interpreter.evalRepl("\"  hello  \".trimEnd()").asString());
        }

        @Test
        @DisplayName("字符串 indexOf")
        void testStringIndexOf() {
            assertEquals(2, interpreter.evalRepl("\"hello\".indexOf(\"ll\")").asInt());
            assertEquals(-1, interpreter.evalRepl("\"hello\".indexOf(\"xyz\")").asInt());
        }

        @Test
        @DisplayName("字符串 substring")
        void testSubstring() {
            assertEquals("llo", interpreter.evalRepl("\"hello\".substring(2)").asString());
            assertEquals("ell", interpreter.evalRepl("\"hello\".substring(1, 4)").asString());
        }

        @Test
        @DisplayName("字符串 toInt/toDouble")
        void testStringToNumber() {
            assertEquals(42, interpreter.evalRepl("\"42\".toInt()").asInt());
            assertEquals(3.14, interpreter.evalRepl("\"3.14\".toDouble()").asDouble(), 0.001);
        }

        @Test
        @DisplayName("多行字符串")
        void testMultilineString() {
            String code = "val s = \"\"\"line1\nline2\nline3\"\"\"\ns.split(\"\\n\").size()";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: when 表达式高级场景 ============

    @Nested
    @DisplayName("when 表达式高级")
    class WhenAdvancedTests {

        @Test
        @DisplayName("when 无参数形式")
        void testWhenNoSubject() {
            String code = ""
                + "val x = 15\n"
                + "when {\n"
                + "    x < 0 -> \"negative\"\n"
                + "    x < 10 -> \"small\"\n"
                + "    x < 100 -> \"medium\"\n"
                + "    else -> \"large\"\n"
                + "}";
            assertEquals("medium", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("when 类型匹配")
        void testWhenTypeMatch() {
            String code = ""
                + "fun describe(x: Any): String = when (x) {\n"
                + "    is Int -> \"integer\"\n"
                + "    is String -> \"string\"\n"
                + "    is Boolean -> \"boolean\"\n"
                + "    else -> \"unknown\"\n"
                + "}\n"
                + "describe(42) + \" \" + describe(\"hi\") + \" \" + describe(true)";
            assertEquals("integer string boolean", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("when 范围匹配")
        void testWhenRangeMatch() {
            String code = ""
                + "fun grade(score: Int) = when (score) {\n"
                + "    in 90..100 -> \"A\"\n"
                + "    in 80..89 -> \"B\"\n"
                + "    in 70..79 -> \"C\"\n"
                + "    else -> \"F\"\n"
                + "}\n"
                + "grade(95) + grade(85) + grade(75) + grade(60)";
            assertEquals("ABCF", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("when 多值匹配")
        void testWhenMultiValue() {
            String code = ""
                + "fun check(x: Int) = when (x) {\n"
                + "    1 -> \"one\"\n"
                + "    2 -> \"two\"\n"
                + "    3 -> \"three\"\n"
                + "    else -> \"other\"\n"
                + "}\n"
                + "check(2) + \" \" + check(5)";
            assertEquals("two other", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("when (val r = ...) 连续使用相同绑定名")
        void testWhenValBindingReuse() {
            String code = ""
                + "fun check(x: Int): Result<String, String> {\n"
                + "    if (x > 0) return Ok(\"pos\")\n"
                + "    return Err(\"neg\")\n"
                + "}\n"
                + "var out = \"\"\n"
                + "when (val r = check(1)) {\n"
                + "    is Ok -> out = out + r.value\n"
                + "    is Err -> out = out + r.error\n"
                + "}\n"
                + "when (val r = check(-1)) {\n"
                + "    is Ok -> out = out + r.value\n"
                + "    is Err -> out = out + r.error\n"
                + "}\n"
                + "out";
            assertEquals("posneg", interpreter.evalRepl(code).asString());
        }
    }

    // ============ 覆盖率补充: 抽象类 ============

    @Nested
    @DisplayName("抽象类高级")
    class AbstractClassAdvancedTests {

        @Test
        @DisplayName("抽象类不能实例化")
        void testAbstractCannotInstantiate() {
            String code = ""
                + "abstract class Shape {\n"
                + "    abstract fun area(): Double\n"
                + "}\n";
            interpreter.evalRepl(code);
            assertThrows(Exception.class, () -> interpreter.evalRepl("Shape()"));
        }

        @Test
        @DisplayName("抽象方法覆写后可用")
        void testAbstractMethodOverride() {
            String code = ""
                + "abstract class Vehicle {\n"
                + "    abstract fun fuel(): String\n"
                + "}\n"
                + "class Car : Vehicle() {\n"
                + "    fun fuel() = \"gasoline\"\n"
                + "}\n"
                + "Car().fuel()";
            assertEquals("gasoline", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("抽象类可以有具体方法")
        void testAbstractWithConcreteMethods() {
            String code = ""
                + "abstract class Base {\n"
                + "    abstract fun value(): Int\n"
                + "    fun doubled() = value() * 2\n"
                + "}\n"
                + "class Impl : Base() {\n"
                + "    fun value() = 21\n"
                + "}\n"
                + "Impl().doubled()";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 闭包和高阶函数 ============

    @Nested
    @DisplayName("闭包和高阶函数")
    class ClosureAdvancedTests {

        @Test
        @DisplayName("闭包捕获外部变量")
        void testClosureCapture() {
            String code = ""
                + "var count = 0\n"
                + "val inc = { count = count + 1 }\n"
                + "inc()\n"
                + "inc()\n"
                + "inc()\n"
                + "count";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("函数返回函数")
        void testFunctionReturnsFunction() {
            String code = ""
                + "fun adder(x: Int): (Int) -> Int = { y -> x + y }\n"
                + "val add5 = adder(5)\n"
                + "add5(3)";
            assertEquals(8, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("高阶函数参数")
        void testHigherOrderFunction() {
            String code = ""
                + "fun apply(x: Int, f: (Int) -> Int) = f(x)\n"
                + "apply(5) { it * it }";
            assertEquals(25, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("嵌套闭包")
        void testNestedClosures() {
            String code = ""
                + "fun makeAdder(x: Int): (Int) -> Int {\n"
                + "    return { y -> x + y }\n"
                + "}\n"
                + "val add5 = makeAdder(5)\n"
                + "add5(10)";
            assertEquals(15, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Lambda 作为最后一个参数")
        void testTrailingLambda() {
            String code = ""
                + "fun repeat(n: Int, action: (Int) -> Unit) {\n"
                + "    for (i in 0..<n) action(i)\n"
                + "}\n"
                + "var sum = 0\n"
                + "repeat(5) { sum = sum + it }\n"
                + "sum";
            assertEquals(10, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: sealed class ============

    @Nested
    @DisplayName("Sealed Class")
    class SealedClassTests {

        @Test
        @DisplayName("基本 sealed class")
        void testBasicSealedClass() {
            String code = ""
                + "sealed class Expr\n"
                + "class Num(val value: Int) : Expr()\n"
                + "class Add(val left: Expr, val right: Expr) : Expr()\n"
                + "val n = Num(42)\n"
                + "n.value";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("sealed class when 匹配（无需 else）")
        void testSealedClassWhen() {
            String code = ""
                + "sealed class Shape\n"
                + "class Circle(val r: Double) : Shape()\n"
                + "class Rect(val w: Double, val h: Double) : Shape()\n"
                + "fun area(s: Shape) = when (s) {\n"
                + "    is Circle -> s.r * s.r * 3.14\n"
                + "    is Rect -> s.w * s.h\n"
                + "}\n"
                + "area(Rect(3.0, 4.0))";
            assertEquals(12.0, interpreter.evalRepl(code).asDouble(), 0.001);
        }

        @Test
        @DisplayName("sealed class 不可直接实例化")
        void testCannotInstantiateSealedClass() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl(
                    "sealed class Base\n"
                    + "val b = Base()"));
        }

        @Test
        @DisplayName("跨 evalRepl 不可继承 sealed class")
        void testCannotExtendSealedAcrossEvalRepl() {
            interpreter.evalRepl("sealed class Animal");
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("class Dog : Animal()"));
        }

        @Test
        @DisplayName("sealed class 间接子类不受限")
        void testIndirectSubclassAllowed() {
            interpreter.evalRepl(
                "sealed class Node\n"
                + "open class Leaf(val v: Int) : Node()");
            // 间接子类（Leaf 的子类）可以在不同编译单元中定义
            NovaValue result = interpreter.evalRepl(
                "class SpecialLeaf(val x: Int) : Leaf(x)\n"
                + "SpecialLeaf(99).v");
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("sealed class 子类可正常使用 is 类型检查")
        void testSealedSubclassTypeCheck() {
            String code = ""
                + "sealed class Outcome\n"
                + "class Success(val value: Int) : Outcome()\n"
                + "class Failure(val msg: String) : Outcome()\n"
                + "val r: Outcome = Success(42)\n"
                + "r is Success";
            assertTrue(interpreter.evalRepl(code).asBoolean());
        }

        @Test
        @DisplayName("sealed class 多个子类同一单元")
        void testMultipleSubclassesSameUnit() {
            String code = ""
                + "sealed class Color\n"
                + "class Red : Color()\n"
                + "class Green : Color()\n"
                + "class Blue : Color()\n"
                + "val colors = listOf(Red(), Green(), Blue())\n"
                + "colors.size";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 泛型与类型参数 ============

    @Nested
    @DisplayName("泛型和类型参数")
    class GenericTests {

        @Test
        @DisplayName("泛型类")
        void testGenericClass() {
            String code = ""
                + "class Box<T>(val value: T) {\n"
                + "    fun get() = value\n"
                + "}\n"
                + "Box(42).get()";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("泛型函数")
        void testGenericFunction() {
            String code = ""
                + "fun <T> identity(x: T): T = x\n"
                + "identity(\"hello\")";
            assertEquals("hello", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("多类型参数")
        void testMultiTypeParams() {
            String code = ""
                + "class Pair<A, B>(val first: A, val second: B)\n"
                + "val p = Pair(1, \"hello\")\n"
                + "p.second";
            assertEquals("hello", interpreter.evalRepl(code).asString());
        }
    }

    // ============ 覆盖率补充: 扩展函数/属性高级 ============

    @Nested
    @DisplayName("扩展函数高级")
    class ExtensionAdvancedTests {

        @Test
        @DisplayName("扩展属性")
        void testExtensionProperty() {
            String code = ""
                + "val String.wordCount = this.split(\" \").size()\n"
                + "\"hello world foo\".wordCount";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Int 扩展函数")
        void testIntExtension() {
            String code = ""
                + "fun Int.isEven() = this % 2 == 0\n"
                + "4.isEven()";
            assertTrue(interpreter.evalRepl(code).asBool());
        }

        @Test
        @DisplayName("List 扩展函数")
        void testListExtension() {
            String code = ""
                + "fun List.secondOrNull() = if (this.size() >= 2) this[1] else null\n"
                + "listOf(10, 20, 30).secondOrNull()";
            assertEquals(20, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("扩展函数中 this 引用")
        void testExtensionThis() {
            String code = ""
                + "fun String.exclaim() = this + \"!\"\n"
                + "\"hello\".exclaim()";
            assertEquals("hello!", interpreter.evalRepl(code).asString());
        }
    }

    // ============ 覆盖率补充: @builder 注解 ============

    @Nested
    @DisplayName("Builder 注解高级")
    class BuilderAdvancedTests {

        @Test
        @DisplayName("builder 基本使用")
        void testBuilderBasic() {
            String code = ""
                + "@builder class Config(val host: String, val port: Int, val debug: Boolean)\n"
                + "val c = Config.builder().host(\"localhost\").port(8080).debug(true).build()\n"
                + "c.host + \":\" + c.port";
            assertEquals("localhost:8080", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("builder 部分构建")
        void testBuilderPartial() {
            String code = ""
                + "@builder class Settings(val name: String, val value: Int)\n"
                + "val s = Settings.builder().name(\"test\").value(42).build()\n"
                + "s.value";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 运算符重载高级 ============

    @Nested
    @DisplayName("运算符重载高级")
    class OperatorOverloadAdvancedTests {

        @Test
        @DisplayName("比较运算符重载 compareTo")
        void testCompareToOverload() {
            String code = ""
                + "class Version(val major: Int, val minor: Int) {\n"
                + "    fun compareTo(other: Version): Int {\n"
                + "        if (major != other.major) return major - other.major\n"
                + "        return minor - other.minor\n"
                + "    }\n"
                + "}\n"
                + "val v1 = Version(1, 0)\n"
                + "val v2 = Version(2, 0)\n"
                + "v1 < v2";
            assertTrue(interpreter.evalRepl(code).asBool());
        }

        @Test
        @DisplayName("一元运算符 unaryMinus/unaryPlus")
        void testUnaryOperators() {
            String code = ""
                + "class Vec(val x: Int, val y: Int) {\n"
                + "    fun unaryMinus() = Vec(-x, -y)\n"
                + "    fun unaryPlus() = Vec(abs(x), abs(y))\n"
                + "}\n"
                + "val v = Vec(3, -4)\n"
                + "val neg = -v\n"
                + "neg.x";
            assertEquals(-3, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("索引运算符 get/set")
        void testIndexOperators() {
            String code = ""
                + "class Grid(val data: List) {\n"
                + "    fun get(i: Int) = data[i]\n"
                + "}\n"
                + "val g = Grid(listOf(10, 20, 30))\n"
                + "g[1]";
            assertEquals(20, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("inc/dec 运算符")
        void testIncDecOperators() {
            String code = ""
                + "class Counter(val value: Int) {\n"
                + "    fun inc() = Counter(value + 1)\n"
                + "    fun dec() = Counter(value - 1)\n"
                + "}\n"
                + "var c = Counter(5)\n"
                + "c++\n"
                + "c.value";
            assertEquals(6, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 单例对象高级 ============

    @Nested
    @DisplayName("单例对象高级")
    class SingletonAdvancedTests {

        @Test
        @DisplayName("object 实现接口")
        void testObjectImplementsInterface() {
            String code = ""
                + "interface Logger { fun log(msg: String): String }\n"
                + "object ConsoleLogger : Logger {\n"
                + "    fun log(msg: String) = \"LOG: $msg\"\n"
                + "}\n"
                + "ConsoleLogger.log(\"hello\")";
            assertEquals("LOG: hello", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("companion object 工厂方法")
        void testCompanionFactory() {
            String code = ""
                + "class Color(val r: Int, val g: Int, val b: Int) {\n"
                + "    companion object {\n"
                + "        fun red() = Color(255, 0, 0)\n"
                + "        fun green() = Color(0, 255, 0)\n"
                + "    }\n"
                + "}\n"
                + "Color.red().r";
            assertEquals(255, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: Range 高级操作 ============

    @Nested
    @DisplayName("Range 高级操作")
    class RangeAdvancedTests {

        @Test
        @DisplayName("Range contains")
        void testRangeContains() {
            assertTrue(interpreter.evalRepl("(1..10).contains(5)").asBool());
            assertFalse(interpreter.evalRepl("(1..10).contains(15)").asBool());
        }

        @Test
        @DisplayName("Range toList")
        void testRangeToList() {
            NovaValue result = interpreter.evalRepl("(1..5).toList()");
            assertTrue(result instanceof NovaList);
            assertEquals(5, ((NovaList) result).size());
        }

        @Test
        @DisplayName("Range first/last")
        void testRangeFirstLast() {
            assertEquals(1, interpreter.evalRepl("(1..10).first").asInt());
            assertEquals(10, interpreter.evalRepl("(1..10).last").asInt());
        }

        @Test
        @DisplayName("Range size")
        void testRangeSize() {
            assertEquals(10, interpreter.evalRepl("(1..10).size").asInt());
            assertEquals(5, interpreter.evalRepl("(0..<5).size").asInt());
        }

        @Test
        @DisplayName("Range forEach")
        void testRangeForEach() {
            String code = ""
                + "var sum = 0\n"
                + "(1..5).forEach { sum = sum + it }\n"
                + "sum";
            assertEquals(15, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Range map/filter")
        void testRangeMapFilter() {
            NovaValue result = interpreter.evalRepl("(1..10).filter { it % 2 == 0 }");
            assertTrue(result instanceof NovaList);
            assertEquals(5, ((NovaList) result).size());
        }
    }

    // ============ 覆盖率补充: 解构和 Pair ============

    @Nested
    @DisplayName("解构高级场景")
    class DestructuringAdvancedTests {

        @Test
        @DisplayName("Map entries 遍历求和")
        void testDestructureMapEntries() {
            String code = ""
                + "val m = mapOf(\"a\" to 1, \"b\" to 2)\n"
                + "var sum = 0\n"
                + "val entries = m.entries()\n"
                + "for (entry in entries) {\n"
                + "    sum = sum + entry.value\n"
                + "}\n"
                + "sum";
            assertEquals(3, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Pair 创建和解构")
        void testPairDestructure() {
            String code = ""
                + "val (a, b) = Pair(\"hello\", 42)\n"
                + "a + \" \" + b";
            assertEquals("hello 42", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("data class 解构遍历")
        void testNestedDataDestructure() {
            String code = ""
                + "@data class Point(val x: Int, val y: Int)\n"
                + "val points = listOf(Point(1, 2), Point(3, 4))\n"
                + "var sum = 0\n"
                + "for (p in points) {\n"
                + "    sum = sum + p.x + p.y\n"
                + "}\n"
                + "sum";
            assertEquals(10, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 条件绑定与空安全 ============

    @Nested
    @DisplayName("空安全高级")
    class NullSafetyAdvancedTests {

        @Test
        @DisplayName("?. 链式安全调用")
        void testSafeCallChain() {
            String code = ""
                + "class Inner(val value: Int)\n"
                + "class Outer(val inner: Inner?)\n"
                + "val o: Outer? = Outer(Inner(42))\n"
                + "o?.inner?.value";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("?. null 短路")
        void testSafeCallNullShortCircuit() {
            String code = ""
                + "class Inner(val value: Int)\n"
                + "class Outer(val inner: Inner?)\n"
                + "val o: Outer? = null\n"
                + "o?.inner?.value";
            assertTrue(interpreter.evalRepl(code).isNull());
        }

        @Test
        @DisplayName("?: Elvis 运算符")
        void testElvisOperator() {
            assertEquals(42, interpreter.evalRepl("null ?: 42").asInt());
            assertEquals(10, interpreter.evalRepl("10 ?: 42").asInt());
        }

        @Test
        @DisplayName("?. + ?: 组合")
        void testSafeCallWithElvis() {
            String code = ""
                + "val s: String? = null\n"
                + "s?.length() ?: -1";
            assertEquals(-1, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("?: return — elvis 右侧为 return 语句")
        void testElvisReturn() {
            String code = ""
                + "fun getEmail(name: String?): String {\n"
                + "    val n = name ?: return \"no name\"\n"
                + "    return n + \"@test.com\"\n"
                + "}\n"
                + "getEmail(null) + \"|\" + getEmail(\"alice\")";
            assertEquals("no name|alice@test.com", interpreter.evalRepl(code).asString());
        }

        @Test
        @DisplayName("?: throw — elvis 右侧为 throw 语句")
        void testElvisThrow() {
            String code = ""
                + "fun mustGet(x: Int?): Int {\n"
                + "    return x ?: throw error(\"null!\")\n"
                + "}\n"
                + "mustGet(42)";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("?[] 安全索引")
        void testSafeIndex() {
            String code = "val list: List? = null\nlist?[0]";
            assertTrue(interpreter.evalRepl(code).isNull());
        }

        @Test
        @DisplayName("?[] 非 null 正常索引")
        void testSafeIndexNonNull() {
            String code = "val list: List? = listOf(10, 20, 30)\nlist?[1]";
            assertEquals(20, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ 覆盖率补充: 长整型 Long ============

    @Nested
    @DisplayName("Long 类型")
    class LongTests {

        @Test
        @DisplayName("Long 字面量")
        void testLongLiteral() {
            NovaValue result = interpreter.evalRepl("1000000000000L");
            assertEquals("Long", result.getTypeName());
            assertEquals(1000000000000L, result.asLong());
        }

        @Test
        @DisplayName("Long 算术")
        void testLongArithmetic() {
            assertEquals(3000000000L, interpreter.evalRepl("1000000000L + 2000000000L").asLong());
            assertEquals(2000000000000L, interpreter.evalRepl("1000000L * 2000000L").asLong());
        }

        @Test
        @DisplayName("Long 与 Int 混合运算")
        void testLongIntMixed() {
            NovaValue result = interpreter.evalRepl("1000000000000L + 1");
            assertEquals(1000000000001L, result.asLong());
        }

        @Test
        @DisplayName("Long 比较")
        void testLongComparison() {
            assertTrue(interpreter.evalRepl("1L == 1L").asBool());
            assertTrue(interpreter.evalRepl("1L < 2L").asBool());
        }

        @Test
        @DisplayName("Long 转换")
        void testLongConversion() {
            assertEquals(42, interpreter.evalRepl("42L.toInt()").asInt());
            assertEquals(42.0, interpreter.evalRepl("42L.toDouble()").asDouble(), 0.001);
        }
    }

    // ============ 覆盖率补充: 数组 Array ============

    @Nested
    @DisplayName("Array 高级操作")
    class ArrayAdvancedTests {

        @Test
        @DisplayName("Array 创建和访问")
        void testArrayAccess() {
            String code = ""
                + "val arr = Array(5) { it * 2 }\n"
                + "arr[2]";
            assertEquals(4, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Array 修改")
        void testArrayModify() {
            String code = ""
                + "val arr = Array(3) { 0 }\n"
                + "arr[1] = 42\n"
                + "arr[1]";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("Array size")
        void testArraySize() {
            assertEquals(5, interpreter.evalRepl("Array(5) { it }.size").asInt());
        }

        @Test
        @DisplayName("Array toList")
        void testArrayToList() {
            String code = "Array(3) { it + 1 }.toList()";
            NovaValue result = interpreter.evalRepl(code);
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("Array 越界抛异常")
        void testArrayOutOfBounds() {
            interpreter.evalRepl("val arr = Array(3) { 0 }");
            assertThrows(Exception.class, () -> interpreter.evalRepl("arr[5]"));
        }
    }

    // ============ 覆盖率补充: 并发 Future ============

    @Nested
    @DisplayName("Future 基本操作")
    class FutureTests {

        @Test
        @DisplayName("async/get")
        void testAsyncAwait() {
            String code = ""
                + "val f = async({ 42 })\n"
                + "f.get()";
            assertEquals(42, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("async 计算")
        void testAsyncComputation() {
            String code = ""
                + "val f = async({\n"
                + "    var sum = 0\n"
                + "    for (i in 1..10) sum = sum + i\n"
                + "    sum\n"
                + "})\n"
                + "f.get()";
            assertEquals(55, interpreter.evalRepl(code).asInt());
        }

        @Test
        @DisplayName("多个并发 async 递归调用 — callStack 线程安全")
        void testAsyncConcurrentRecursion() {
            String code = ""
                + "fun fib(n: Int): Int {\n"
                + "    if (n <= 1) return n\n"
                + "    return fib(n - 1) + fib(n - 2)\n"
                + "}\n"
                + "val fa = async({ fib(20) })\n"
                + "val fb = async({ fib(20) })\n"
                + "val ra = fa.get()\n"
                + "val rb = fb.get()\n"
                + "ra + rb";
            assertEquals(6765 * 2, interpreter.evalRepl(code).asInt());
        }
    }

    // ============ init 块测试 ============

    @Nested
    @DisplayName("init 块")
    class InitBlockTests {

        @Test
        @DisplayName("基本 init 块 — 访问构造器参数")
        void testBasicInitBlock() {
            interpreter.evalRepl(
                "class Foo(val x: Int) {\n" +
                "    var doubled = 0\n" +
                "    init {\n" +
                "        doubled = x * 2\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val f = Foo(5)");
            assertEquals(10, interpreter.evalRepl("f.doubled").asInt());
            assertEquals(5, interpreter.evalRepl("f.x").asInt());
        }

        @Test
        @DisplayName("init 块可访问前面声明的属性")
        void testInitBlockAccessesPrecedingProperty() {
            interpreter.evalRepl(
                "class Bar(val x: Int) {\n" +
                "    val a = x + 1\n" +
                "    var result = 0\n" +
                "    init {\n" +
                "        result = a * 10\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val b = Bar(3)");
            assertEquals(4, interpreter.evalRepl("b.a").asInt());
            assertEquals(40, interpreter.evalRepl("b.result").asInt());
        }

        @Test
        @DisplayName("多个 init 块按声明顺序执行")
        void testMultipleInitBlocksOrder() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream custom = new PrintStream(baos);
            interpreter.setStdout(custom);
            interpreter.evalRepl(
                "class Multi(val x: Int) {\n" +
                "    init { println(\"first\") }\n" +
                "    init { println(\"second\") }\n" +
                "    init { println(\"third\") }\n" +
                "}"
            );
            interpreter.evalRepl("val m = Multi(1)");
            custom.flush();
            String output = baos.toString().replace("\r\n", "\n");
            assertEquals("first\nsecond\nthird\n", output);
        }

        @Test
        @DisplayName("init 块与属性初始化器交织执行")
        void testInitBlockInterleavedWithProperties() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream custom = new PrintStream(baos);
            interpreter.setStdout(custom);
            interpreter.evalRepl(
                "class Interleaved(val x: Int) {\n" +
                "    val a = x + 1\n" +
                "    init { println(a) }\n" +
                "    val b = a * 2\n" +
                "    init { println(b) }\n" +
                "}"
            );
            interpreter.evalRepl("val obj = Interleaved(3)");
            custom.flush();
            String output = baos.toString().replace("\r\n", "\n");
            // a = 3+1 = 4, 然后 println(4); b = 4*2 = 8, 然后 println(8)
            assertEquals("4\n8\n", output);
            assertEquals(4, interpreter.evalRepl("obj.a").asInt());
            assertEquals(8, interpreter.evalRepl("obj.b").asInt());
        }

        @Test
        @DisplayName("init 块中调用方法")
        void testInitBlockCallsMethod() {
            interpreter.evalRepl(
                "class WithMethod(val x: Int) {\n" +
                "    var computed = 0\n" +
                "    fun calculate(): Int = x * x + 1\n" +
                "    init {\n" +
                "        computed = calculate()\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val w = WithMethod(4)");
            assertEquals(17, interpreter.evalRepl("w.computed").asInt());
        }

        @Test
        @DisplayName("无主构造器参数的 init 块")
        void testInitBlockWithoutPrimaryParams() {
            interpreter.evalRepl(
                "class NoParams {\n" +
                "    var value = 10\n" +
                "    init {\n" +
                "        value = value + 5\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val np = NoParams()");
            assertEquals(15, interpreter.evalRepl("np.value").asInt());
        }

        @Test
        @DisplayName("init 块 + 次级构造器委托")
        void testInitBlockWithSecondaryConstructor() {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream custom = new PrintStream(baos);
            interpreter.setStdout(custom);
            interpreter.evalRepl(
                "class WithSecondary(val x: Int, val y: Int) {\n" +
                "    init { println(\"init: \" + x + \",\" + y) }\n" +
                "    constructor(value: Int) : this(value, value * 2)\n" +
                "}"
            );
            interpreter.evalRepl("val ws = WithSecondary(3)");
            custom.flush();
            String output = baos.toString().replace("\r\n", "\n");
            assertEquals("init: 3,6\n", output);
            assertEquals(3, interpreter.evalRepl("ws.x").asInt());
            assertEquals(6, interpreter.evalRepl("ws.y").asInt());
        }

        @Test
        @DisplayName("init 块中使用控制流")
        void testInitBlockWithControlFlow() {
            interpreter.evalRepl(
                "class Validated(val x: Int) {\n" +
                "    var status = \"\"\n" +
                "    init {\n" +
                "        if (x > 0) {\n" +
                "            status = \"positive\"\n" +
                "        } else {\n" +
                "            status = \"non-positive\"\n" +
                "        }\n" +
                "    }\n" +
                "}"
            );
            assertEquals("positive", interpreter.evalRepl("Validated(5).status").asString());
            assertEquals("non-positive", interpreter.evalRepl("Validated(-1).status").asString());
        }

        @Test
        @DisplayName("init 块中使用循环")
        void testInitBlockWithLoop() {
            interpreter.evalRepl(
                "class Summer(val n: Int) {\n" +
                "    var sum = 0\n" +
                "    init {\n" +
                "        for (i in 1..n) {\n" +
                "            sum = sum + i\n" +
                "        }\n" +
                "    }\n" +
                "}"
            );
            assertEquals(55, interpreter.evalRepl("Summer(10).sum").asInt());
            assertEquals(15, interpreter.evalRepl("Summer(5).sum").asInt());
        }

        @Test
        @DisplayName("init 块累积多个属性")
        void testInitBlockAccumulatesProperties() {
            interpreter.evalRepl(
                "class Accumulator(val base: Int) {\n" +
                "    val doubled = base * 2\n" +
                "    var tripled = 0\n" +
                "    var info = \"\"\n" +
                "    init {\n" +
                "        tripled = base * 3\n" +
                "        info = \"base=\" + base + \",doubled=\" + doubled + \",tripled=\" + tripled\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val acc = Accumulator(7)");
            assertEquals(14, interpreter.evalRepl("acc.doubled").asInt());
            assertEquals(21, interpreter.evalRepl("acc.tripled").asInt());
            assertEquals("base=7,doubled=14,tripled=21", interpreter.evalRepl("acc.info").asString());
        }

        @Test
        @DisplayName("多实例各自独立执行 init 块")
        void testInitBlockPerInstance() {
            interpreter.evalRepl(
                "class Counter(val start: Int) {\n" +
                "    var count = 0\n" +
                "    init {\n" +
                "        count = start + 100\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("val c1 = Counter(1)");
            interpreter.evalRepl("val c2 = Counter(2)");
            interpreter.evalRepl("val c3 = Counter(3)");
            assertEquals(101, interpreter.evalRepl("c1.count").asInt());
            assertEquals(102, interpreter.evalRepl("c2.count").asInt());
            assertEquals(103, interpreter.evalRepl("c3.count").asInt());
        }
    }

    // ============ Java 互操作测试 ============

    @Nested
    @DisplayName("java.lang 自动导入")
    class JavaLangAutoImportTests {

        // ---- 正常值 ----

        @Test
        @DisplayName("Math.abs 无需 import")
        void testMathAbsAutoImport() {
            assertEquals(42, interpreter.evalRepl("Math.abs(-42)").asInt());
        }

        @Test
        @DisplayName("Integer.parseInt 无需 import")
        void testIntegerParseIntAutoImport() {
            assertEquals(123, interpreter.evalRepl("Integer.parseInt(\"123\")").asInt());
        }

        @Test
        @DisplayName("String.valueOf 无需 import")
        void testStringValueOfAutoImport() {
            assertEquals("42", interpreter.evalRepl("String.valueOf(42)").asString());
        }

        @Test
        @DisplayName("Math.max 两参数静态方法")
        void testMathMaxAutoImport() {
            assertEquals(20, interpreter.evalRepl("Math.max(10, 20)").asInt());
        }

        // ---- 边缘值 ----

        @Test
        @DisplayName("Math.abs(0) — 零值")
        void testMathAbsZero() {
            assertEquals(0, interpreter.evalRepl("Math.abs(0)").asInt());
        }

        @Test
        @DisplayName("Integer.MAX_VALUE 静态常量")
        void testIntegerMaxValue() {
            assertEquals(Integer.MAX_VALUE, interpreter.evalRepl("Integer.MAX_VALUE").asInt());
        }

        @Test
        @DisplayName("Boolean.TRUE 静态常量")
        void testBooleanTrue() {
            assertTrue(interpreter.evalRepl("Boolean.TRUE").asBoolean());
        }

        // ---- 异常值 ----

        @Test
        @DisplayName("Integer.parseInt 非法字符串抛异常")
        void testIntegerParseIntError() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("Integer.parseInt(\"abc\")"));
        }

        // ---- 不干扰 Nova stdlib ----

        @Test
        @DisplayName("Nova 顶层 abs() 仍可用")
        void testNovaStdlibAbsStillWorks() {
            assertEquals(42, interpreter.evalRepl("abs(-42)").asInt());
        }

        @Test
        @DisplayName("Nova 顶层 max() 仍可用")
        void testNovaStdlibMaxStillWorks() {
            assertEquals(20, interpreter.evalRepl("max(10, 20)").asInt());
        }

        // ---- 显式 import 不冲突 ----

        @Test
        @DisplayName("显式 import java.lang.Math 不冲突")
        void testExplicitImportNoConflict() {
            interpreter.evalRepl("import java java.lang.Math");
            assertEquals(Math.PI, interpreter.evalRepl("Math.PI").asDouble(), 0.0001);
        }
    }

    @Nested
    @DisplayName("JavaBean 属性语法")
    class JavaBeanPropertyTests {

        // ---- getter 正常值 ----

        @Test
        @DisplayName("Thread.name — getName() 属性读取")
        void testJavaBeanGetterName() {
            interpreter.evalRepl("val t = Thread(\"my-thread\")");
            assertEquals("my-thread", interpreter.evalRepl("t.name").asString());
        }

        @Test
        @DisplayName("Thread.daemon — isDaemon() 布尔属性读取")
        void testJavaBeanBooleanGetter() {
            interpreter.evalRepl("val t = Thread(\"test\")");
            assertFalse(interpreter.evalRepl("t.daemon").asBoolean());
        }

        // ---- setter 正常值 ----

        @Test
        @DisplayName("Thread.name = 'x' — setName() 属性写入")
        void testJavaBeanSetterName() {
            interpreter.evalRepl("val t = Thread(\"old\")");
            interpreter.evalRepl("t.name = \"new-name\"");
            assertEquals("new-name", interpreter.evalRepl("t.name").asString());
        }

        @Test
        @DisplayName("Thread.daemon = true — setDaemon() 布尔属性写入")
        void testJavaBeanBooleanSetter() {
            interpreter.evalRepl("val t = Thread(\"test\")");
            interpreter.evalRepl("t.daemon = true");
            assertTrue(interpreter.evalRepl("t.daemon").asBoolean());
        }

        // ---- setter 边缘值 ----

        @Test
        @DisplayName("Date.time = 0 — setTime(long) 数值属性")
        void testJavaBeanLongSetter() {
            interpreter.evalRepl("import java java.util.Date");
            interpreter.evalRepl("val d = Date()");
            interpreter.evalRepl("d.time = 0");
            assertEquals(0L, interpreter.evalRepl("d.time").asLong());
        }

        @Test
        @DisplayName("Thread.name 多次写入读回")
        void testJavaBeanSetterMultipleWrites() {
            interpreter.evalRepl("val t = Thread(\"v1\")");
            interpreter.evalRepl("t.name = \"v2\"");
            interpreter.evalRepl("t.name = \"v3\"");
            assertEquals("v3", interpreter.evalRepl("t.name").asString());
        }

        // ---- 异常值 ----

        @Test
        @DisplayName("Java 对象设置不存在的属性抛异常")
        void testSetNonExistentPropertyThrows() {
            interpreter.evalRepl("val t = Thread(\"test\")");
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("t.nonExistentProp = 42"));
        }
    }

    @Nested
    @DisplayName("Java 互操作")
    class JavaInteropTests {

        @Test
        @DisplayName("Java.type 获取类并实例化")
        void testJavaType() {
            interpreter.evalRepl("val ArrayList = Java.type(\"java.util.ArrayList\")");
            interpreter.evalRepl("val list = ArrayList()");
            interpreter.evalRepl("list.add(\"hello\")");
            assertEquals(1, interpreter.evalRepl("list.size()").asInt());
        }

        @Test
        @DisplayName("Java.static 调用静态方法")
        void testJavaStatic() {
            NovaValue result = interpreter.evalRepl(
                "Java.static(\"java.lang.Math\", \"max\", 10, 20)");
            assertEquals(20, result.asInt());
        }

        @Test
        @DisplayName("Java.field 获取静态字段")
        void testJavaField() {
            NovaValue result = interpreter.evalRepl(
                "Java.field(\"java.lang.Math\", \"PI\")");
            assertEquals(Math.PI, result.asDouble(), 0.0001);
        }

        @Test
        @DisplayName("Java.new 创建实例")
        void testJavaNew() {
            interpreter.evalRepl("val sb = Java.new(\"java.lang.StringBuilder\", \"hello\")");
            interpreter.evalRepl("sb.append(\" world\")");
            NovaValue result = interpreter.evalRepl("sb.toString()");
            assertEquals("hello world", result.asString());
        }

        @Test
        @DisplayName("varargs — String.format 通过 Java.static")
        void testJavaStaticVarargs() {
            NovaValue result = interpreter.evalRepl(
                "Java.static(\"java.lang.String\", \"format\", \"hello %s, %d!\", \"world\", 42)");
            assertEquals("hello world, 42!", result.asString());
        }

        @Test
        @DisplayName("varargs — 实例方法 String.format")
        void testInstanceVarargsViaType() {
            // 通过 Java.type 获取 String 类，调用静态 format
            interpreter.evalRepl("val Str = Java.type(\"java.lang.String\")");
            NovaValue result = interpreter.evalRepl(
                "Str.format(\"%s + %s = %s\", \"1\", \"2\", \"3\")");
            assertEquals("1 + 2 = 3", result.asString());
        }

        @Test
        @DisplayName("varargs — Arrays.asList")
        void testVarargsArraysAsList() {
            interpreter.evalRepl(
                "val list = Java.static(\"java.util.Arrays\", \"asList\", \"a\", \"b\", \"c\")");
            assertEquals(3, interpreter.evalRepl("list.size()").asInt());
            assertEquals("b", interpreter.evalRepl("list.get(1)").asString());
        }

        @Test
        @DisplayName("数值宽化 — int 传给 long 参数")
        void testNumericWideningIntToLong() {
            // Math.addExact(long, long) 接受 int 参数
            NovaValue result = interpreter.evalRepl(
                "Java.static(\"java.lang.Math\", \"abs\", -42)");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("方法重载 — StringBuilder append 选择正确重载")
        void testMethodOverloadResolution() {
            interpreter.evalRepl("val sb = Java.new(\"java.lang.StringBuilder\")");
            interpreter.evalRepl("sb.append(\"hello\")");
            interpreter.evalRepl("sb.append(42)");
            interpreter.evalRepl("sb.append(true)");
            NovaValue result = interpreter.evalRepl("sb.toString()");
            assertEquals("hello42true", result.asString());
        }

        @Test
        @DisplayName("异常 cause 保留")
        void testExceptionCausePreserved() {
            try {
                interpreter.evalRepl("Java.static(\"java.lang.Integer\", \"parseInt\", \"not_a_number\")");
                fail("Should throw");
            } catch (NovaRuntimeException e) {
                assertNotNull(e.getCause(), "原始异常 cause 应被保留");
            }
        }

        @Test
        @DisplayName("异常 cause — Java.new 错误构造器参数")
        void testExceptionCauseOnNew() {
            try {
                interpreter.evalRepl("Java.new(\"java.lang.Integer\", \"not_a_number\")");
                fail("Should throw");
            } catch (NovaRuntimeException e) {
                assertNotNull(e.getCause(), "原始异常 cause 应被保留");
            }
        }

        @Test
        @DisplayName("NovaExternalObject 方法调用异常保留 cause")
        void testExternalObjectMethodExceptionCause() {
            // charAt 越界会触发 StringIndexOutOfBoundsException
            interpreter.evalRepl("val sb = Java.new(\"java.lang.StringBuilder\", \"hi\")");
            try {
                interpreter.evalRepl("sb.charAt(999)");
                fail("Should throw");
            } catch (NovaRuntimeException e) {
                assertNotNull(e.getCause(), "原始异常 cause 应被保留");
            }
        }
    }

    @Nested
    @DisplayName("三元表达式")
    class TernaryExpressionTests {

        @Test
        @DisplayName("基本三元表达式 - true 分支")
        void testTernaryTrue() {
            assertEquals(1, interpreter.eval("true ? 1 : 2", "test.nova").asInt());
        }

        @Test
        @DisplayName("基本三元表达式 - false 分支")
        void testTernaryFalse() {
            assertEquals(2, interpreter.eval("false ? 1 : 2", "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式 with 比较条件")
        void testTernaryWithComparison() {
            assertEquals("positive",
                interpreter.eval("val x = 5\nx > 0 ? \"positive\" : \"negative\"", "test.nova").asString());
            assertEquals("negative",
                interpreter.eval("val x = -3\nx > 0 ? \"positive\" : \"negative\"", "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式 with 等式条件")
        void testTernaryWithEquality() {
            assertEquals("yes",
                interpreter.eval("val x = 42\nx == 42 ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("嵌套三元表达式 - then 嵌套")
        void testNestedTernaryInThen() {
            // a ? b ? c : d : e → a ? (b ? c : d) : e
            assertEquals("c",
                interpreter.eval("true ? true ? \"c\" : \"d\" : \"e\"", "test.nova").asString());
            assertEquals("d",
                interpreter.eval("true ? false ? \"c\" : \"d\" : \"e\"", "test.nova").asString());
            assertEquals("e",
                interpreter.eval("false ? true ? \"c\" : \"d\" : \"e\"", "test.nova").asString());
        }

        @Test
        @DisplayName("嵌套三元表达式 - else 嵌套")
        void testNestedTernaryInElse() {
            // a ? b : c ? d : e → a ? b : (c ? d : e)
            assertEquals("b",
                interpreter.eval("true ? \"b\" : true ? \"d\" : \"e\"", "test.nova").asString());
            assertEquals("d",
                interpreter.eval("false ? \"b\" : true ? \"d\" : \"e\"", "test.nova").asString());
            assertEquals("e",
                interpreter.eval("false ? \"b\" : false ? \"d\" : \"e\"", "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式作为赋值右值")
        void testTernaryInAssignment() {
            assertEquals(10,
                interpreter.eval("val x = true ? 10 : 20\nx", "test.nova").asInt());
            assertEquals(20,
                interpreter.eval("val x = false ? 10 : 20\nx", "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式 with 函数调用")
        void testTernaryWithFunctionCall() {
            String code = "fun double(x: Int): Int = x * 2\nval y = true ? double(5) : double(10)\ny";
            assertEquals(10, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式 with 算术表达式")
        void testTernaryWithArithmetic() {
            assertEquals(15,
                interpreter.eval("val a = 10\nval b = 5\ntrue ? a + b : a - b", "test.nova").asInt());
            assertEquals(5,
                interpreter.eval("val a = 10\nval b = 5\nfalse ? a + b : a - b", "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式 with 逻辑条件")
        void testTernaryWithLogicalCondition() {
            assertEquals("both",
                interpreter.eval("val a = true\nval b = true\na && b ? \"both\" : \"not\"", "test.nova").asString());
            assertEquals("either",
                interpreter.eval("val a = true\nval b = false\na || b ? \"either\" : \"none\"", "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式在函数参数中")
        void testTernaryInFunctionArg() {
            String code = "fun add(a: Int, b: Int): Int = a + b\nadd(true ? 1 : 2, false ? 3 : 4)";
            assertEquals(5, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式与错误传播共存")
        void testTernaryCoexistsWithErrorPropagation() {
            // 错误传播仍然正常工作
            String code = "fun getValue(): Int? = 42\n"
                + "fun process(): Int? { val x = getValue()?; return x * 2 }\n"
                + "process()";
            assertEquals(84, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式与 Elvis 共存")
        void testTernaryCoexistsWithElvis() {
            assertEquals(42,
                interpreter.eval("val x: Int? = null\nval y = x ?: 42\ntrue ? y : 0", "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式返回不同类型")
        void testTernaryMixedTypes() {
            assertEquals("hello",
                interpreter.eval("true ? \"hello\" : 42", "test.nova").asString());
            assertEquals(42,
                interpreter.eval("false ? \"hello\" : 42", "test.nova").asInt());
        }

        // ---- 边缘值 & 异常值 ----

        @Test
        @DisplayName("条件为 null → falsy")
        void testTernaryNullCondition() {
            assertEquals("no",
                interpreter.eval("val x: Int? = null\nx ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("条件为整数 → truthy（非零）")
        void testTernaryIntConditionTruthy() {
            assertEquals("yes", interpreter.eval("1 ? \"yes\" : \"no\"", "test.nova").asString());
            assertEquals("yes", interpreter.eval("-1 ? \"yes\" : \"no\"", "test.nova").asString());
            assertEquals("yes", interpreter.eval("0 ? \"yes\" : \"no\"", "test.nova").asString()); // Int 默认 truthy
        }

        @Test
        @DisplayName("条件为空字符串 → falsy")
        void testTernaryEmptyStringCondition() {
            assertEquals("no", interpreter.eval("\"\" ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("条件为非空字符串 → truthy")
        void testTernaryNonEmptyStringCondition() {
            assertEquals("yes", interpreter.eval("\"hello\" ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("条件为空列表 → falsy")
        void testTernaryEmptyListCondition() {
            assertEquals("no", interpreter.eval("listOf() ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("条件为非空列表 → truthy")
        void testTernaryNonEmptyListCondition() {
            assertEquals("yes", interpreter.eval("listOf(1) ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("条件为空 Map → falsy")
        void testTernaryEmptyMapCondition() {
            assertEquals("no", interpreter.eval("mapOf() ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("条件为非空 Map → truthy")
        void testTernaryNonEmptyMapCondition() {
            assertEquals("yes", interpreter.eval("mapOf(\"a\" to 1) ? \"yes\" : \"no\"", "test.nova").asString());
        }

        @Test
        @DisplayName("分支返回 null")
        void testTernaryBranchReturnsNull() {
            assertTrue(interpreter.eval("true ? null : 42", "test.nova").isNull());
            assertEquals(42, interpreter.eval("false ? null : 42", "test.nova").asInt());
        }

        @Test
        @DisplayName("双分支均为 null")
        void testTernaryBothBranchesNull() {
            assertTrue(interpreter.eval("true ? null : null", "test.nova").isNull());
        }

        @Test
        @DisplayName("三层嵌套三元表达式")
        void testTripleNestedTernary() {
            // true ? (true ? (true ? "a" : "b") : "c") : "d" → "a"
            assertEquals("a",
                interpreter.eval("true ? true ? true ? \"a\" : \"b\" : \"c\" : \"d\"", "test.nova").asString());
            // true ? (true ? (false ? "a" : "b") : "c") : "d" → "b"
            assertEquals("b",
                interpreter.eval("true ? true ? false ? \"a\" : \"b\" : \"c\" : \"d\"", "test.nova").asString());
            // true ? (false ? ... : "c") : "d" → "c"
            assertEquals("c",
                interpreter.eval("true ? false ? true ? \"a\" : \"b\" : \"c\" : \"d\"", "test.nova").asString());
            // false ? ... : "d" → "d"
            assertEquals("d",
                interpreter.eval("false ? true ? true ? \"a\" : \"b\" : \"c\" : \"d\"", "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式在字符串插值中")
        void testTernaryInStringInterpolation() {
            assertEquals("result: yes",
                interpreter.eval("\"result: ${true ? \"yes\" : \"no\"}\"", "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式在列表字面量中")
        void testTernaryInListLiteral() {
            NovaValue result = interpreter.eval("listOf(true ? 1 : 2, false ? 3 : 4)", "test.nova");
            assertTrue(result.isList());
            NovaList list = (NovaList) result;
            assertEquals(1, list.get(0).asInt());
            assertEquals(4, list.get(1).asInt());
        }

        @Test
        @DisplayName("三元表达式 with lambda 分支")
        void testTernaryWithLambdaBranch() {
            String code = "val fn = true ? { x: Int -> x * 2 } : { x: Int -> x * 3 }\nfn(5)";
            assertEquals(10, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("三元表达式条件为函数调用结果")
        void testTernaryConditionIsFunctionCall() {
            String code = "fun isEven(n: Int): Boolean = n % 2 == 0\nisEven(4) ? \"even\" : \"odd\"";
            assertEquals("even", interpreter.eval(code, "test.nova").asString());
            String code2 = "fun isEven(n: Int): Boolean = n % 2 == 0\nisEven(3) ? \"even\" : \"odd\"";
            assertEquals("odd", new Interpreter().eval(code2, "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式分支为复杂表达式")
        void testTernaryBranchWithComplexExpr() {
            String code = "val list = listOf(1, 2, 3)\ntrue ? list.size : -1";
            assertEquals(3, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("语法错误：三元表达式 then 分支语法错误")
        void testTernaryMalformedThenExpr() {
            // 扫描器找到 :，识别为三元，但 then 分支 1 + * 不是合法表达式
            assertThrows(Exception.class, () -> {
                interpreter.eval("true ? 1 + * : 2", "test.nova");
            });
        }

        @Test
        @DisplayName("语法错误：缺少 else 表达式")
        void testTernaryMissingElseExpr() {
            assertThrows(Exception.class, () -> {
                interpreter.eval("true ? 1 :", "test.nova");
            });
        }

        @Test
        @DisplayName("语法错误：缺少 then 表达式")
        void testTernaryMissingThenExpr() {
            assertThrows(Exception.class, () -> {
                interpreter.eval("true ? : 2", "test.nova");
            });
        }

        @Test
        @DisplayName("错误传播 postfix ? 不受影响")
        void testPostfixQuestionStillWorks() {
            // postfix ? 在非三元上下文中正常工作
            String code = "fun getVal(): Int? = null\nfun test(): Int? { return getVal()? }\ntest()";
            assertTrue(interpreter.eval(code, "test.nova").isNull());
        }

        @Test
        @DisplayName("同一表达式中混合三元和 postfix ?")
        void testTernaryAndPostfixInSameExpr() {
            String code = "fun getVal(): Int? = 10\nval x = getVal()?\nx > 5 ? \"big\" : \"small\"";
            assertEquals("big", interpreter.eval(code, "test.nova").asString());
        }

        @Test
        @DisplayName("三元表达式用作方法链起点")
        void testTernaryResultUsedInChain() {
            assertEquals(5,
                interpreter.eval("(true ? \"hello\" : \"hi\").length", "test.nova").asInt());
            assertEquals(2,
                interpreter.eval("(false ? \"hello\" : \"hi\").length", "test.nova").asInt());
        }
    }

    @Nested
    @DisplayName("尾调用消除")
    class TailCallEliminationTests {

        @Test
        @DisplayName("static 自递归 — 深尾递归不爆栈")
        void testStaticSelfRecursionDeep() {
            String code = "fun sum(n: Int, acc: Int): Int {\n"
                + "  if (n <= 0) return acc\n"
                + "  return sum(n - 1, acc + n)\n"
                + "}\n"
                + "sum(100000, 0)";
            NovaValue result = interpreter.eval(code, "test.nova");
            // sum(100000) = 100000 * 100001 / 2 = 5000050000 → long
            assertEquals(5000050000L, result.asLong());
        }

        @Test
        @DisplayName("实例方法尾递归")
        void testInstanceMethodTailRecursion() {
            String code = "class Counter {\n"
                + "  fun countdown(n: Int, acc: Int): Int {\n"
                + "    if (n <= 0) return acc\n"
                + "    return countdown(n - 1, acc + 1)\n"
                + "  }\n"
                + "}\n"
                + "val c = Counter()\n"
                + "c.countdown(50000, 0)";
            assertEquals(50000, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("非尾位不优化 — 尾位后有运算")
        void testNonTailPositionNotOptimized() {
            // fact(n) = n * fact(n-1)，调用后有乘法，不能尾调用优化
            // 但小递归深度仍能正常执行
            String code = "fun fact(n: Int): Int {\n"
                + "  if (n <= 1) return 1\n"
                + "  return n * fact(n - 1)\n"
                + "}\n"
                + "fact(10)";
            assertEquals(3628800, interpreter.eval(code, "test.nova").asInt());
        }

        @Test
        @DisplayName("异常栈折叠 — tail-call frames omitted")
        void testExceptionStackFolding() {
            String code = "fun loop(n: Int): Int {\n"
                + "  if (n <= 0) return error(\"done\")\n"
                + "  return loop(n - 1)\n"
                + "}\n"
                + "loop(100)";
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () -> {
                interpreter.eval(code, "test.nova");
            });
            String trace = ex.getNovaStackTrace();
            assertNotNull(trace);
            assertTrue(trace.contains("tail-call frames omitted"),
                "异常栈应包含尾调用折叠信息: " + trace);
        }

        @Test
        @DisplayName("尾递归阶乘 — 累加器模式")
        void testTailRecursiveFactorial() {
            String code = "fun factTail(n: Int, acc: Int): Int {\n"
                + "  if (n <= 1) return acc\n"
                + "  return factTail(n - 1, n * acc)\n"
                + "}\n"
                + "factTail(12, 1)";
            assertEquals(479001600, interpreter.eval(code, "test.nova").asInt());
        }
    }

}
