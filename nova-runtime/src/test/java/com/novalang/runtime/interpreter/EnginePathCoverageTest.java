package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 引擎内部路径覆盖测试 — 目标: MirInterpreter / MirCallDispatcher /
 * StaticMethodDispatcher / MirClassRegistrar / Builtins 的低覆盖分支。
 */
class EnginePathCoverageTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    // ================================================================
    // 1. MirCallDispatcher — ScalarizedNovaObject plus 路径
    // ================================================================

    @Nested
    @DisplayName("标量化对象运算")
    class ScalarizedObjectTests {

        @Test
        @DisplayName("@data class 自动 plus — 2字段")
        void testDataClassPlus2Fields() {
            eval("@data class Vec2(val x: Int, val y: Int) {\n"
                + "    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)\n"
                + "}");
            eval("val v = Vec2(1, 2) + Vec2(3, 4)");
            assertEquals(4, eval("v.x").asInt());
            assertEquals(6, eval("v.y").asInt());
        }

        @Test
        @DisplayName("@data class 自动 plus — 3字段")
        void testDataClassPlus3Fields() {
            eval("@data class Vec3(val x: Int, val y: Int, val z: Int) {\n"
                + "    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)\n"
                + "}");
            eval("val v = Vec3(1, 2, 3) + Vec3(4, 5, 6)");
            assertEquals(5, eval("v.x").asInt());
            assertEquals(7, eval("v.y").asInt());
            assertEquals(9, eval("v.z").asInt());
        }

        @Test
        @DisplayName("@data class 连续运算")
        void testDataClassChainedOps() {
            eval("@data class Pt(val x: Int, val y: Int) {\n"
                + "    operator fun plus(other: Pt) = Pt(x + other.x, y + other.y)\n"
                + "    operator fun minus(other: Pt) = Pt(x - other.x, y - other.y)\n"
                + "}");
            eval("val result = Pt(10, 20) + Pt(5, 5) - Pt(3, 3)");
            assertEquals(12, eval("result.x").asInt());
            assertEquals(22, eval("result.y").asInt());
        }
    }

    // ================================================================
    // 2. Java 互操作路径
    // ================================================================

    @Nested
    @DisplayName("Java 互操作")
    class JavaInteropTests {

        @Test
        @DisplayName("使用 Java ArrayList")
        void testJavaArrayList() {
            eval("val list = java.util.ArrayList()");
            eval("list.add(\"hello\")");
            eval("list.add(\"world\")");
            assertEquals(2, eval("list.size()").asInt());
        }

        @Test
        @DisplayName("使用 Java HashMap")
        void testJavaHashMap() {
            eval("val map = java.util.HashMap()");
            eval("map.put(\"key\", \"value\")");
            assertEquals("value", eval("map.get(\"key\")").asString());
        }

        @Test
        @DisplayName("使用 java.lang.Math")
        void testJavaMath() {
            assertEquals(5.0, eval("java.lang.Math.abs(-5.0)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("Java 字符串方法")
        void testJavaStringMethods() {
            eval("val s = java.lang.String(\"HELLO\")");
            assertNotNull(eval("s.toLowerCase()"));
        }

        @Test
        @DisplayName("javaClass 加载")
        void testJavaClassLoad() {
            NovaValue cls = eval("javaClass(\"java.util.ArrayList\")");
            assertNotNull(cls);
        }

        @Test
        @DisplayName("Java 对象 toString 调用")
        void testJavaObjectToString() {
            eval("val list = java.util.ArrayList()");
            eval("list.add(1)");
            NovaValue str = eval("list.toString()");
            assertTrue(str.asString().contains("1"));
        }
    }

    // ================================================================
    // 3. 复杂继承链 — VirtualMethodDispatcher
    // ================================================================

    @Nested
    @DisplayName("继承链分派")
    class InheritanceDispatchTests {

        @Test
        @DisplayName("三层继承方法调用")
        void testThreeLevelInheritance() {
            eval("open class A {\n"
                + "    open fun name() = \"A\"\n"
                + "}");
            eval("open class B : A() {\n"
                + "    override fun name() = \"B\"\n"
                + "}");
            eval("class C : B() {\n"
                + "    override fun name() = \"C\"\n"
                + "}");
            assertEquals("C", eval("C().name()").asString());
        }

        @Test
        @DisplayName("未 override 时继承父类方法")
        void testInheritedWithoutOverride() {
            eval("open class Base {\n"
                + "    fun baseMethod() = \"from base\"\n"
                + "}");
            eval("class Derived : Base()");
            assertEquals("from base", eval("Derived().baseMethod()").asString());
        }

        @Test
        @DisplayName("多态 — 不同类型调用各自方法")
        void testPolymorphism() {
            eval("open class Shape {\n"
                + "    open fun area() = 0.0\n"
                + "}");
            eval("class Circle(val r: Double) : Shape() {\n"
                + "    override fun area() = 3.14 * r * r\n"
                + "}");
            eval("class Rect(val w: Double, val h: Double) : Shape() {\n"
                + "    override fun area() = w * h\n"
                + "}");
            assertEquals(78.5, eval("Circle(5.0).area()").asDouble(), 0.5);
            assertEquals(12.0, eval("Rect(3.0, 4.0).area()").asDouble(), 0.1);
        }

        @Test
        @DisplayName("父类带构造器参数")
        void testSuperWithArgs() {
            eval("open class Named(val name: String)");
            eval("class Person(name: String, val age: Int) : Named(name)");
            eval("val p = Person(\"Alice\", 30)");
            assertEquals("Alice", eval("p.name").asString());
            assertEquals(30, eval("p.age").asInt());
        }
    }

    // ================================================================
    // 4. Init 块
    // ================================================================

    @Nested
    @DisplayName("Init 块")
    class InitBlockTests {

        @Test
        @DisplayName("init 块执行")
        void testInitBlock() {
            eval("class Counter {\n"
                + "    var count = 0\n"
                + "    init { count = 42 }\n"
                + "}");
            assertEquals(42, eval("Counter().count").asInt());
        }

        @Test
        @DisplayName("多个 init 块按声明顺序执行")
        void testMultipleInitBlocks() {
            eval("class Multi {\n"
                + "    var result = \"\"\n"
                + "    init { result = result + \"A\" }\n"
                + "    init { result = result + \"B\" }\n"
                + "    init { result = result + \"C\" }\n"
                + "}");
            assertEquals("ABC", eval("Multi().result").asString());
        }

        @Test
        @DisplayName("init 块访问构造器参数")
        void testInitAccessesConstructorParams() {
            eval("class Validator(val value: Int) {\n"
                + "    var valid = false\n"
                + "    init { valid = value > 0 }\n"
                + "}");
            assertTrue(eval("Validator(5).valid").asBool());
            assertFalse(eval("Validator(-1).valid").asBool());
        }
    }

    // ================================================================
    // 5. 扩展函数
    // ================================================================

    @Nested
    @DisplayName("扩展函数")
    class ExtensionFunctionTests {

        @Test
        @DisplayName("基本扩展函数")
        void testBasicExtension() {
            eval("fun Int.double() = this * 2");
            assertEquals(10, eval("5.double()").asInt());
        }

        @Test
        @DisplayName("String 扩展函数")
        void testStringExtension() {
            eval("fun String.exclaim() = this + \"!\"");
            assertEquals("hello!", eval("\"hello\".exclaim()").asString());
        }

        @Test
        @DisplayName("List 扩展函数")
        void testListExtension() {
            eval("fun List<Int>.sumDouble() = this.map { it * 2 }.sum()");
            assertEquals(12, eval("[1,2,3].sumDouble()").asInt());
        }

        @Test
        @DisplayName("扩展属性")
        void testExtensionProperty() {
            eval("val String.reversed: String get() = this.reversed()");
            assertEquals("olleh", eval("\"hello\".reversed").asString());
        }

        @Test
        @DisplayName("带参数的扩展函数")
        void testExtensionWithParams() {
            eval("fun Int.add(other: Int) = this + other");
            assertEquals(15, eval("10.add(5)").asInt());
        }
    }

    // ================================================================
    // 6. 异常类型处理路径
    // ================================================================

    @Nested
    @DisplayName("异常类型处理")
    class ExceptionTypeTests {

        @Test
        @DisplayName("除以零异常")
        void testDivisionByZero() {
            assertThrows(Exception.class, () -> eval("1 / 0"));
        }

        @Test
        @DisplayName("null 上调用方法")
        void testNullMethodCall() {
            eval("val x: Any? = null");
            // null.toString() 可能返回 "null" 或抛异常
            try {
                NovaValue r = eval("x.toString()");
                // 如果不抛异常，则应返回 "null" 或合理值
                assertNotNull(r);
            } catch (Exception e) {
                // 抛异常也是正确行为
                assertTrue(e.getMessage().contains("null") || e.getMessage().contains("Null"));
            }
        }

        @Test
        @DisplayName("索引越界")
        void testIndexOutOfBounds() {
            eval("val list = [1, 2, 3]");
            assertThrows(Exception.class, () -> eval("list[10]"));
        }

        @Test
        @DisplayName("未定义变量")
        void testUndefinedVariable() {
            assertThrows(Exception.class, () -> eval("undefinedVar + 1"));
        }

        @Test
        @DisplayName("类型不匹配运算")
        void testTypeMismatchOp() {
            assertThrows(Exception.class, () -> eval("\"hello\" - 5"));
        }

        @Test
        @DisplayName("catch 获取异常消息")
        void testCatchExceptionMessage() {
            NovaValue msg = eval("try { error(\"test msg\") } catch (e) { e.toString() }");
            assertTrue(msg.asString().contains("test msg"));
        }
    }

    // ================================================================
    // 7. 复杂 when + smart cast
    // ================================================================

    @Nested
    @DisplayName("复杂 when + smart cast")
    class WhenSmartCastTests {

        @Test
        @DisplayName("when is 类型分支")
        void testWhenIsTypeBranch() {
            eval("fun process(x: Any): String = when(x) {\n"
                + "    is Int -> \"int:\" + x\n"
                + "    is String -> \"str:\" + x\n"
                + "    is Boolean -> \"bool:\" + x\n"
                + "    is List -> \"list:\" + x.size()\n"
                + "    else -> \"unknown\"\n"
                + "}");
            assertEquals("int:42", eval("process(42)").asString());
            assertEquals("str:hi", eval("process(\"hi\")").asString());
            assertEquals("bool:true", eval("process(true)").asString());
            assertEquals("list:3", eval("process([1,2,3])").asString());
        }

        @Test
        @DisplayName("when Range 条件")
        void testWhenRange() {
            eval("fun grade(score: Int) = when(score) {\n"
                + "    in 90..100 -> \"A\"\n"
                + "    in 80..89 -> \"B\"\n"
                + "    in 70..79 -> \"C\"\n"
                + "    else -> \"F\"\n"
                + "}");
            assertEquals("A", eval("grade(95)").asString());
            assertEquals("B", eval("grade(85)").asString());
            assertEquals("C", eval("grade(75)").asString());
            assertEquals("F", eval("grade(50)").asString());
        }
    }

    // ================================================================
    // 8. 高阶函数 + 函数组合
    // ================================================================

    @Nested
    @DisplayName("高阶函数组合")
    class HigherOrderFunctionTests {

        @Test
        @DisplayName("函数作为返回值")
        void testFunctionAsReturnValue() {
            eval("fun adder(n: Int): (Int) -> Int = { x -> x + n }");
            eval("val add5 = adder(5)");
            assertEquals(15, eval("add5(10)").asInt());
        }

        @Test
        @DisplayName("函数作为 Map 值")
        void testFunctionAsMapValue() {
            eval("val ops = mapOf(\n"
                + "    \"add\" to { a: Int, b: Int -> a + b },\n"
                + "    \"mul\" to { a: Int, b: Int -> a * b }\n"
                + ")");
            assertEquals(7, eval("ops[\"add\"](3, 4)").asInt());
            assertEquals(12, eval("ops[\"mul\"](3, 4)").asInt());
        }

        @Test
        @DisplayName("递归 lambda (via var)")
        void testRecursiveLambda() {
            eval("var fib: (Int) -> Int = { 0 }");
            eval("fib = { n: Int -> if (n <= 1) n else fib(n-1) + fib(n-2) }");
            assertEquals(8, eval("fib(6)").asInt());
        }
    }

    // ================================================================
    // 9. 嵌套类和对象
    // ================================================================

    @Nested
    @DisplayName("嵌套类")
    class NestedClassTests {

        @Test
        @DisplayName("object 单例")
        void testObjectSingleton() {
            eval("object Config {\n"
                + "    val version = \"1.0\"\n"
                + "    fun info() = \"v\" + version\n"
                + "}");
            assertEquals("v1.0", eval("Config.info()").asString());
        }

        @Test
        @DisplayName("嵌套类定义")
        void testNestedClassDef() {
            eval("class Outer(val x: Int)");
            eval("class Inner(val outer: Outer) {\n"
                + "    fun getX() = outer.x\n"
                + "}");
            assertEquals(42, eval("Inner(Outer(42)).getX()").asInt());
        }
    }

    // ================================================================
    // 10. 默认参数 + varargs
    // ================================================================

    @Nested
    @DisplayName("默认参数")
    class DefaultParamsTests {

        @Test
        @DisplayName("多个默认参数")
        void testMultipleDefaults() {
            eval("fun greet(name: String = \"World\", prefix: String = \"Hello\", suffix: String = \"!\") = \"$prefix, $name$suffix\"");
            assertEquals("Hello, World!", eval("greet()").asString());
            assertEquals("Hello, Alice!", eval("greet(\"Alice\")").asString());
            assertEquals("Hi, Bob!", eval("greet(\"Bob\", \"Hi\")").asString());
        }

        @Test
        @DisplayName("可变参数列表模拟")
        void testVarargSimulation() {
            eval("fun sum(nums: List<Int>): Int {\n"
                + "    var total = 0\n"
                + "    for (n in nums) total = total + n\n"
                + "    return total\n"
                + "}");
            assertEquals(15, eval("sum([1, 2, 3, 4, 5])").asInt());
        }

        @Test
        @DisplayName("空列表参数")
        void testEmptyListParam() {
            eval("fun sum(nums: List<Int>): Int {\n"
                + "    var total = 0\n"
                + "    for (n in nums) total = total + n\n"
                + "    return total\n"
                + "}");
            assertEquals(0, eval("sum([])").asInt());
        }

        @Test
        @DisplayName("默认参数 + 尾随 lambda")
        void testDefaultWithTrailingLambda() {
            eval("fun transform(x: Int, fn: (Int) -> Int = { it }) = fn(x)");
            assertEquals(5, eval("transform(5)").asInt());
            assertEquals(50, eval("transform(5) { it * 10 }").asInt());
        }
    }

    // ================================================================
    // 11. Map 成员方法
    // ================================================================

    @Nested
    @DisplayName("Map 成员方法")
    class MapMemberTests {

        @Test
        @DisplayName("Map.keys")
        void testMapKeys() {
            NovaValue keys = eval("mapOf(\"a\" to 1, \"b\" to 2).keys");
            assertNotNull(keys);
        }

        @Test
        @DisplayName("Map.values")
        void testMapValues() {
            NovaValue values = eval("mapOf(\"a\" to 1, \"b\" to 2).values");
            assertNotNull(values);
        }

        @Test
        @DisplayName("Map.entries")
        void testMapEntries() {
            NovaValue entries = eval("mapOf(\"a\" to 1, \"b\" to 2).entries");
            assertNotNull(entries);
        }

        @Test
        @DisplayName("Map.containsKey")
        void testMapContainsKey() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertTrue(eval("m.containsKey(\"a\")").asBool());
            assertFalse(eval("m.containsKey(\"c\")").asBool());
        }

        @Test
        @DisplayName("Map.containsValue")
        void testMapContainsValue() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertTrue(eval("m.containsValue(1)").asBool());
            assertFalse(eval("m.containsValue(99)").asBool());
        }

        @Test
        @DisplayName("Map.getOrDefault")
        void testMapGetOrDefault() {
            eval("val m = mapOf(\"a\" to 1)");
            assertEquals(1, eval("m.getOrDefault(\"a\", 0)").asInt());
            assertEquals(0, eval("m.getOrDefault(\"z\", 0)").asInt());
        }

        @Test
        @DisplayName("Map.isEmpty")
        void testMapIsEmpty() {
            assertTrue(eval("emptyMap().isEmpty()").asBool());
            assertFalse(eval("mapOf(\"a\" to 1).isEmpty()").asBool());
        }
    }

    // ================================================================
    // 12. 多 catch / finally 路径
    // ================================================================

    @Nested
    @DisplayName("多 catch / finally")
    class MultiCatchTests {

        @Test
        @DisplayName("finally 正常执行")
        void testFinallyNormal() {
            eval("var x = 0");
            eval("try { x = 1 } finally { x = x + 10 }");
            assertEquals(11, eval("x").asInt());
        }

        @Test
        @DisplayName("finally 异常执行")
        void testFinallyWithException() {
            eval("var cleaned = false");
            NovaValue result = eval("try {\n"
                + "    error(\"fail\")\n"
                + "} catch (e) {\n"
                + "    \"caught\"\n"
                + "} finally {\n"
                + "    cleaned = true\n"
                + "}");
            assertEquals("caught", result.asString());
            assertTrue(eval("cleaned").asBool());
        }

        @Test
        @DisplayName("try 返回值 — 无异常")
        void testTryReturnValue() {
            assertEquals(42, eval("try { 42 } catch (e) { 0 }").asInt());
        }

        @Test
        @DisplayName("try 返回值 — 有异常")
        void testTryReturnValueWithException() {
            assertEquals(0, eval("try { error(\"x\"); 42 } catch (e) { 0 }").asInt());
        }
    }

    // ================================================================
    // 13. 字符串乘法和特殊运算
    // ================================================================

    @Nested
    @DisplayName("特殊运算")
    class SpecialOperationTests {

        @Test
        @DisplayName("Int * String 字符串重复")
        void testIntTimesString() {
            assertEquals("aaa", eval("3 * \"a\"").asString());
        }

        @Test
        @DisplayName("String + 非字符串 拼接")
        void testStringPlusNonString() {
            assertEquals("val:42", eval("\"val:\" + 42").asString());
            assertEquals("bool:true", eval("\"bool:\" + true").asString());
        }

        @Test
        @DisplayName("String == 比较")
        void testStringEquality() {
            assertTrue(eval("\"hello\" == \"hello\"").asBool());
            assertFalse(eval("\"hello\" == \"world\"").asBool());
        }

        @Test
        @DisplayName("混合数值比较")
        void testMixedNumericComparison() {
            assertTrue(eval("42 == 42.0").asBool());
            assertTrue(eval("1 < 2.0").asBool());
        }

        @Test
        @DisplayName("布尔运算短路")
        void testBooleanShortCircuit() {
            eval("var sideEffect = false");
            eval("val result = false && { sideEffect = true; true }()");
            assertFalse(eval("sideEffect").asBool());
        }

        @Test
        @DisplayName("递增运算 ++")
        void testIncrementOperator() {
            eval("var x = 5");
            eval("x++");
            assertEquals(6, eval("x").asInt());
        }

        @Test
        @DisplayName("递减运算 --")
        void testDecrementOperator() {
            eval("var x = 5");
            eval("x--");
            assertEquals(4, eval("x").asInt());
        }

        @Test
        @DisplayName("+= 复合赋值")
        void testPlusAssign() {
            eval("var x = 10");
            eval("x += 5");
            assertEquals(15, eval("x").asInt());
        }

        @Test
        @DisplayName("-= 复合赋值")
        void testMinusAssign() {
            eval("var x = 10");
            eval("x -= 3");
            assertEquals(7, eval("x").asInt());
        }
    }

    // ================================================================
    // 14. for-in 循环变体
    // ================================================================

    @Nested
    @DisplayName("for-in 循环变体")
    class ForLoopVariantsTests {

        @Test
        @DisplayName("for in List")
        void testForInList() {
            eval("var sum = 0");
            eval("for (x in [1,2,3,4,5]) sum = sum + x");
            assertEquals(15, eval("sum").asInt());
        }

        @Test
        @DisplayName("for in Map")
        void testForInMap() {
            eval("var result = \"\"");
            eval("for (entry in mapOf(\"x\" to 1)) result = entry.key + \"=\" + entry.value");
            assertEquals("x=1", eval("result").asString());
        }

        @Test
        @DisplayName("for in String")
        void testForInString() {
            eval("var chars = \"\"");
            eval("for (c in \"abc\") chars = chars + c");
            assertEquals("abc", eval("chars").asString());
        }

        @Test
        @DisplayName("for 嵌套循环")
        void testNestedForLoops() {
            eval("var result = \"\"");
            eval("for (i in 1..3) {\n"
                + "    for (j in 1..2) {\n"
                + "        result = result + \"(\" + i + \",\" + j + \")\"\n"
                + "    }\n"
                + "}");
            assertEquals("(1,1)(1,2)(2,1)(2,2)(3,1)(3,2)", eval("result").asString());
        }

        @Test
        @DisplayName("for 中 break")
        void testForBreak() {
            eval("var sum = 0");
            eval("for (i in 1..100) {\n"
                + "    if (i > 5) break\n"
                + "    sum = sum + i\n"
                + "}");
            assertEquals(15, eval("sum").asInt());
        }

        @Test
        @DisplayName("for 中 continue")
        void testForContinue() {
            eval("var sum = 0");
            eval("for (i in 1..10) {\n"
                + "    if (i % 2 != 0) continue\n"
                + "    sum = sum + i\n"
                + "}");
            // 2+4+6+8+10=30
            assertEquals(30, eval("sum").asInt());
        }
    }

    // ================================================================
    // 15. while / do-while
    // ================================================================

    @Nested
    @DisplayName("While 循环")
    class WhileLoopTests {

        @Test
        @DisplayName("while 循环")
        void testWhileLoop() {
            eval("var x = 0");
            eval("while (x < 5) x = x + 1");
            assertEquals(5, eval("x").asInt());
        }

        @Test
        @DisplayName("do-while 至少执行一次")
        void testDoWhile() {
            eval("var x = 10");
            eval("do { x = x + 1 } while (x < 5)");
            assertEquals(11, eval("x").asInt());
        }

        @Test
        @DisplayName("while 中 break")
        void testWhileBreak() {
            eval("var x = 0");
            eval("while (true) {\n"
                + "    x = x + 1\n"
                + "    if (x == 5) break\n"
                + "}");
            assertEquals(5, eval("x").asInt());
        }
    }

    // ================================================================
    // 16. 条件表达式 if-else
    // ================================================================

    @Nested
    @DisplayName("条件表达式")
    class ConditionalTests {

        @Test
        @DisplayName("if-else 作为表达式")
        void testIfElseExpression() {
            assertEquals("yes", eval("if (true) \"yes\" else \"no\"").asString());
            assertEquals("no", eval("if (false) \"yes\" else \"no\"").asString());
        }

        @Test
        @DisplayName("三元嵌套")
        void testNestedConditional() {
            eval("fun classify(n: Int) = if (n > 0) \"pos\" else if (n < 0) \"neg\" else \"zero\"");
            assertEquals("pos", eval("classify(5)").asString());
            assertEquals("neg", eval("classify(-3)").asString());
            assertEquals("zero", eval("classify(0)").asString());
        }

        @Test
        @DisplayName("if 中声明变量")
        void testVarInIf() {
            eval("val result = if (true) {\n"
                + "    val x = 10\n"
                + "    x * 2\n"
                + "} else {\n"
                + "    0\n"
                + "}");
            assertEquals(20, eval("result").asInt());
        }
    }

    // ================================================================
    // 17. 泛型类
    // ================================================================

    @Nested
    @DisplayName("泛型类")
    class GenericClassTests {

        @Test
        @DisplayName("泛型容器类")
        void testGenericContainer() {
            eval("class Box<T>(val value: T) {\n"
                + "    fun get(): T = value\n"
                + "}");
            assertEquals(42, eval("Box(42).get()").asInt());
            assertEquals("hi", eval("Box(\"hi\").get()").asString());
        }

        @Test
        @DisplayName("泛型函数")
        void testGenericFunction() {
            eval("fun <T> identity(x: T): T = x");
            assertEquals(42, eval("identity(42)").asInt());
            assertEquals("hello", eval("identity(\"hello\")").asString());
        }

        @Test
        @DisplayName("泛型嵌套")
        void testNestedGeneric() {
            eval("class Wrapper<T>(val inner: T)");
            eval("val w = Wrapper(Wrapper(42))");
            assertEquals(42, eval("w.inner.inner").asInt());
        }
    }

    // ================================================================
    // 18. String.format / 模板边界
    // ================================================================

    @Nested
    @DisplayName("字符串格式化")
    class StringFormatTests {

        @Test
        @DisplayName("多表达式插值")
        void testMultiExprInterpolation() {
            eval("val a = 1; val b = 2; val c = 3");
            assertEquals("1+2+3=6", eval("\"$a+$b+$c=${a+b+c}\"").asString());
        }

        @Test
        @DisplayName("插值中的方法调用")
        void testInterpolationWithMethodCall() {
            eval("val list = [1,2,3]");
            assertEquals("size=3", eval("\"size=${list.size()}\"").asString());
        }

        @Test
        @DisplayName("空字符串")
        void testEmptyString() {
            assertEquals("", eval("\"\"").asString());
        }

        @Test
        @DisplayName("Unicode 字符串")
        void testUnicodeString() {
            assertEquals("你好", eval("\"你好\"").asString());
        }
    }

    // ================================================================
    // 19. Pair / to 操作
    // ================================================================

    @Nested
    @DisplayName("Pair 操作")
    class PairTests {

        @Test
        @DisplayName("to 中缀函数")
        void testToInfixFunction() {
            eval("val p = \"key\" to 42");
            assertEquals("key", eval("p.first").asString());
            assertEquals(42, eval("p.second").asInt());
        }

        @Test
        @DisplayName("Pair 解构")
        void testPairDestructure() {
            eval("val (k, v) = \"name\" to \"Alice\"");
            assertEquals("name", eval("k").asString());
            assertEquals("Alice", eval("v").asString());
        }

        @Test
        @DisplayName("嵌套 Pair")
        void testNestedPair() {
            eval("val p = Pair(Pair(1, 2), Pair(3, 4))");
            assertEquals(1, eval("p.first.first").asInt());
            assertEquals(4, eval("p.second.second").asInt());
        }
    }

    // ================================================================
    // 20. 位运算和逻辑运算
    // ================================================================

    @Nested
    @DisplayName("位运算")
    class BitwiseTests {

        @Test
        @DisplayName("位 AND")
        void testBitwiseAnd() {
            assertEquals(0, eval("5 & 2").asInt());     // 101 & 010 = 000
        }

        @Test
        @DisplayName("位 OR")
        void testBitwiseOr() {
            assertEquals(7, eval("5 | 2").asInt());      // 101 | 010 = 111
        }

        @Test
        @DisplayName("位 XOR")
        void testBitwiseXor() {
            assertEquals(7, eval("5 ^ 2").asInt());     // 101 ^ 010 = 111
        }

        @Test
        @DisplayName("shl / shr")
        void testShiftOps() {
            assertEquals(20, eval("5 << 2").asInt());    // 5 << 2 = 20
            assertEquals(5, eval("20 >> 2").asInt());    // 20 >> 2 = 5
        }
    }

    // ================================================================
    // 21. 安全调用链 + !! 非空断言
    // ================================================================

    @Nested
    @DisplayName("Null 安全高级")
    class NullSafetyAdvancedTests {

        @Test
        @DisplayName("!! 非空断言 — 非 null 值")
        void testNotNullAssertionSuccess() {
            eval("val x: Int? = 42");
            assertEquals(42, eval("x!!").asInt());
        }

        @Test
        @DisplayName("!! 非空断言 — null 值抛异常")
        void testNotNullAssertionFail() {
            eval("val x: Int? = null");
            assertThrows(Exception.class, () -> eval("x!!"));
        }

        @Test
        @DisplayName("?. 链 + ?: Elvis")
        void testSafeChainElvis() {
            eval("class Address(val city: String?)");
            eval("class User(val address: Address?)");
            eval("val u1 = User(Address(\"NYC\"))");
            eval("val u2 = User(null)");
            assertEquals("NYC", eval("u1.address?.city ?: \"unknown\"").asString());
            assertEquals("unknown", eval("u2.address?.city ?: \"unknown\"").asString());
        }
    }

    // ================================================================
    // 22. 集合操作边界 — empty / single
    // ================================================================

    @Nested
    @DisplayName("集合边界")
    class CollectionEdgeTests {

        @Test
        @DisplayName("空 List 的 isEmpty")
        void testEmptyListIsEmpty() {
            assertTrue(eval("listOf().isEmpty()").asBool());
        }

        @Test
        @DisplayName("非空 List 的 isNotEmpty")
        void testNonEmptyListIsNotEmpty() {
            assertTrue(eval("[1].isNotEmpty()").asBool());
        }

        @Test
        @DisplayName("List.first / last")
        void testListFirstLast() {
            assertEquals(1, eval("[1,2,3].first()").asInt());
            assertEquals(3, eval("[1,2,3].last()").asInt());
        }

        @Test
        @DisplayName("List.indexOf")
        void testListIndexOf() {
            assertEquals(1, eval("[10,20,30].indexOf(20)").asInt());
            assertEquals(-1, eval("[10,20,30].indexOf(99)").asInt());
        }

        @Test
        @DisplayName("List.joinToString")
        void testListJoinToString() {
            assertEquals("1, 2, 3", eval("[1,2,3].joinToString(\", \")").asString());
        }

        @Test
        @DisplayName("List.forEachIndexed")
        void testForEachIndexed() {
            eval("var result = \"\"");
            eval("[\"a\",\"b\",\"c\"].forEachIndexed { i, v -> result = result + i + v }");
            assertEquals("0a1b2c", eval("result").asString());
        }

        @Test
        @DisplayName("List.mapIndexed")
        void testMapIndexed() {
            NovaValue result = eval("[10,20,30].mapIndexed { i, v -> i * 100 + v }");
            NovaList list = (NovaList) result;
            assertEquals(10, list.get(0).asInt());
            assertEquals(120, list.get(1).asInt());
            assertEquals(230, list.get(2).asInt());
        }
    }
}
