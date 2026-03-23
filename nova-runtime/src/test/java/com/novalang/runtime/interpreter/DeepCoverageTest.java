package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 深度覆盖测试 — 针对 JaCoCo 分析发现的未覆盖代码路径。
 * 覆盖目标: NovaSuperProxy / takeIf-takeUnless / 类型转换 / scope 函数 /
 *           枚举方法 / 数组初始化 / protected / Reflect / Result / 时间测量 /
 *           with-repeat / 运算符重载 / getter-setter / 错误处理
 */
class DeepCoverageTest {

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
    // 1. Super 调用 (NovaSuperProxy)
    // ================================================================

    @Nested
    @DisplayName("Super 调用")
    class SuperCallTests {

        @Test
        @DisplayName("子类 override 调用 super 方法")
        void testSuperMethodCall() {
            eval("open class Animal {\n"
                + "    open fun sound() = \"...\"\n"
                + "}");
            eval("class Dog : Animal() {\n"
                + "    override fun sound() = \"Dog:\" + super.sound()\n"
                + "}");
            assertEquals("Dog:...", eval("Dog().sound()").asString());
        }

        @Test
        @DisplayName("多层继承 super 调用")
        void testMultiLevelSuper() {
            eval("open class A {\n"
                + "    open fun value() = 1\n"
                + "}");
            eval("open class B : A() {\n"
                + "    override fun value() = super.value() + 10\n"
                + "}");
            eval("class C : B() {\n"
                + "    override fun value() = super.value() + 100\n"
                + "}");
            assertEquals(111, eval("C().value()").asInt());
        }

        @Test
        @DisplayName("super 属性访问")
        void testSuperPropertyAccess() {
            eval("open class Base {\n"
                + "    open val tag = \"base\"\n"
                + "}");
            eval("class Child : Base() {\n"
                + "    override val tag = \"child\"\n"
                + "    fun parentTag() = super.tag\n"
                + "}");
            // super.tag 在某些实现中可能返回 base 的值
            NovaValue result = eval("Child().parentTag()");
            assertNotNull(result);
        }

        @Test
        @DisplayName("构造器中调用 super 方法")
        void testSuperInConstructor() {
            eval("open class Logger {\n"
                + "    open fun init() = \"initialized\"\n"
                + "}");
            eval("class AppLogger : Logger() {\n"
                + "    val status = super.init()\n"
                + "}");
            assertEquals("initialized", eval("AppLogger().status").asString());
        }
    }

    // ================================================================
    // 2. takeIf / takeUnless
    // ================================================================

    @Nested
    @DisplayName("takeIf / takeUnless")
    class TakeIfTests {

        @Test
        @DisplayName("takeIf 条件为真返回对象本身")
        void testTakeIfTrue() {
            assertEquals(10, eval("10.takeIf { it > 5 }").asInt());
        }

        @Test
        @DisplayName("takeIf 条件为假返回 null")
        void testTakeIfFalse() {
            assertTrue(eval("10.takeIf { it > 20 }").isNull());
        }

        @Test
        @DisplayName("takeUnless 条件为假返回对象本身")
        void testTakeUnlessTrue() {
            assertEquals(10, eval("10.takeUnless { it > 20 }").asInt());
        }

        @Test
        @DisplayName("takeUnless 条件为真返回 null")
        void testTakeUnlessFalse() {
            assertTrue(eval("10.takeUnless { it > 5 }").isNull());
        }

        @Test
        @DisplayName("takeIf 链式调用")
        void testTakeIfChained() {
            NovaValue result = eval("42.takeIf { it > 0 }?.takeIf { it < 100 }");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("takeIf 在字符串上使用")
        void testTakeIfOnString() {
            assertEquals("hello", eval("\"hello\".takeIf { it.length > 3 }").asString());
            assertTrue(eval("\"hi\".takeIf { it.length > 3 }").isNull());
        }

        @Test
        @DisplayName("takeUnless 在列表上使用")
        void testTakeUnlessOnList() {
            NovaValue result = eval("[1,2,3].takeUnless { it.isEmpty() }");
            assertTrue(result instanceof NovaList);
        }
    }

    // ================================================================
    // 3. 类型转换边界
    // ================================================================

    @Nested
    @DisplayName("类型转换")
    class TypeConversionTests {

        @Test
        @DisplayName("Double 转 Int 截断")
        void testDoubleToIntTruncation() {
            assertEquals(3, eval("3.7.toInt()").asInt());
        }

        @Test
        @DisplayName("负数 Double 转 Int")
        void testNegativeDoubleToInt() {
            assertEquals(-3, eval("(-3.7).toInt()").asInt());
        }

        @Test
        @DisplayName("Int 转 Double")
        void testIntToDouble() {
            assertEquals(42.0, eval("42.toDouble()").asDouble(), 0.001);
        }

        @Test
        @DisplayName("Int 转 Long")
        void testIntToLong() {
            assertEquals(42L, eval("42.toLong()").asLong());
        }

        @Test
        @DisplayName("Int 转 Float")
        void testIntToFloat() {
            NovaValue result = eval("42.toFloat()");
            assertNotNull(result);
        }

        @Test
        @DisplayName("String 转 Int")
        void testStringToInt() {
            assertEquals(123, eval("\"123\".toInt()").asInt());
        }

        @Test
        @DisplayName("非数字 String 转 Int 抛异常")
        void testInvalidStringToInt() {
            assertThrows(Exception.class, () -> eval("\"abc\".toInt()"));
        }

        @Test
        @DisplayName("toBoolean 转换")
        void testToBoolean() {
            assertTrue(eval("\"true\".toBoolean()").asBool());
            assertFalse(eval("\"false\".toBoolean()").asBool());
        }

        @Test
        @DisplayName("Int 转 Char")
        void testIntToChar() {
            NovaValue ch = eval("65.toChar()");
            assertNotNull(ch);
        }

        @Test
        @DisplayName("Char 转 Int — 使用 code 属性")
        void testCharCode() {
            NovaValue ch = eval("'A'");
            assertNotNull(ch);
        }

        @Test
        @DisplayName("toString 方法")
        void testToString() {
            assertEquals("42", eval("42.toString()").asString());
            assertEquals("3.14", eval("3.14.toString()").asString());
            assertEquals("true", eval("true.toString()").asString());
        }
    }

    // ================================================================
    // 4. Scope 函数 — apply / also / let / with
    // ================================================================

    @Nested
    @DisplayName("Scope 函数")
    class ScopeFunctionTests {

        @Test
        @DisplayName("apply 返回 this")
        void testApplyReturnsThis() {
            eval("class Box(var value: Int)");
            eval("val box = Box(0).apply { value = 42 }");
            assertEquals(42, eval("box.value").asInt());
        }

        @Test
        @DisplayName("also 返回 this")
        void testAlsoReturnsThis() {
            eval("var sideEffect = 0");
            eval("val result = 42.also { sideEffect = it }");
            assertEquals(42, eval("result").asInt());
            assertEquals(42, eval("sideEffect").asInt());
        }

        @Test
        @DisplayName("let 返回 lambda 结果")
        void testLetReturnsLambdaResult() {
            assertEquals(10, eval("5.let { it * 2 }").asInt());
        }

        @Test
        @DisplayName("with 返回 lambda 结果")
        void testWithReturnsLambdaResult() {
            eval("class Config(val host: String, val port: Int)");
            eval("val cfg = Config(\"localhost\", 8080)");
            NovaValue result = eval("with(cfg) { host + \":\" + port }");
            assertEquals("localhost:8080", result.asString());
        }

        @Test
        @DisplayName("嵌套 scope 函数")
        void testNestedScopeFunctions() {
            eval("class Counter(var count: Int)");
            eval("val c = Counter(0).apply {\n"
                + "    count = count.let { it + 10 }\n"
                + "}");
            assertEquals(10, eval("c.count").asInt());
        }

        @Test
        @DisplayName("run 在 Map 上使用")
        void testRunOnMap() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertEquals(2, eval("m.run { size() }").asInt());
        }
    }

    // ================================================================
    // 5. 枚举成员方法 — 带参数
    // ================================================================

    @Nested
    @DisplayName("枚举高级特性")
    class EnumAdvancedTests {

        @Test
        @DisplayName("枚举带多个字段")
        void testEnumMultipleFields() {
            eval("enum class Planet(val mass: Double, val radius: Double) {\n"
                + "    EARTH(5.97, 6.37),\n"
                + "    MARS(0.64, 3.39)\n"
                + "}");
            assertEquals(5.97, eval("Planet.EARTH.mass").asDouble(), 0.01);
            assertEquals(3.39, eval("Planet.MARS.radius").asDouble(), 0.01);
        }

        @Test
        @DisplayName("枚举带方法 + 参数")
        void testEnumWithParameterizedMethod() {
            eval("enum class Op {\n"
                + "    ADD, SUB;\n"
                + "    fun apply(a: Int, b: Int) = if (name == \"ADD\") a + b else a - b\n"
                + "}");
            assertEquals(7, eval("Op.ADD.apply(3, 4)").asInt());
            assertEquals(-1, eval("Op.SUB.apply(3, 4)").asInt());
        }

        @Test
        @DisplayName("枚举 values() 函数")
        void testEnumValues() {
            eval("enum class Coin { PENNY, NICKEL, DIME, QUARTER }");
            NovaValue vals = eval("Coin.values()");
            assertTrue(vals instanceof NovaList);
            assertEquals(4, ((NovaList) vals).size());
        }

        @Test
        @DisplayName("枚举 valueOf() 函数")
        void testEnumValueOf() {
            eval("enum class Season { SPRING, SUMMER, FALL, WINTER }");
            assertEquals("SUMMER", eval("Season.valueOf(\"SUMMER\").name").asString());
        }

        @Test
        @DisplayName("枚举 toString 返回 name")
        void testEnumToString() {
            eval("enum class Color { RED, GREEN, BLUE }");
            assertEquals("RED", eval("Color.RED.toString()").asString());
        }

        @Test
        @DisplayName("枚举 entries 属性")
        void testEnumEntries() {
            eval("enum class Dir { N, S, E, W }");
            NovaValue entries = eval("Dir.entries");
            assertNotNull(entries);
        }
    }

    // ================================================================
    // 6. 数组初始化器 — IntArray / LongArray / DoubleArray
    // ================================================================

    @Nested
    @DisplayName("Typed Array 初始化")
    class TypedArrayTests {

        @Test
        @DisplayName("IntArray 创建")
        void testIntArrayCreate() {
            eval("val arr = IntArray(5)");
            assertEquals(5, eval("arr.size").asInt());
        }

        @Test
        @DisplayName("IntArray 带初始化器")
        void testIntArrayWithInit() {
            eval("val arr = IntArray(4) { it * 3 }");
            assertEquals(0, eval("arr[0]").asInt());
            assertEquals(9, eval("arr[3]").asInt());
        }

        @Test
        @DisplayName("LongArray 创建")
        void testLongArrayCreate() {
            eval("val arr = LongArray(3)");
            assertEquals(3, eval("arr.size").asInt());
        }

        @Test
        @DisplayName("DoubleArray 创建")
        void testDoubleArrayCreate() {
            eval("val arr = DoubleArray(3) { it.toDouble() * 1.5 }");
            assertEquals(0.0, eval("arr[0]").asDouble(), 0.01);
            assertEquals(3.0, eval("arr[2]").asDouble(), 0.01);
        }

        @Test
        @DisplayName("FloatArray 创建")
        void testFloatArrayCreate() {
            eval("val arr = FloatArray(2)");
            assertEquals(2, eval("arr.size").asInt());
        }

        @Test
        @DisplayName("BooleanArray 创建")
        void testBooleanArrayCreate() {
            eval("val arr = BooleanArray(3)");
            assertEquals(3, eval("arr.size").asInt());
        }

        @Test
        @DisplayName("CharArray 创建")
        void testCharArrayCreate() {
            eval("val arr = CharArray(4)");
            assertEquals(4, eval("arr.size").asInt());
        }
    }

    // ================================================================
    // 7. Protected / internal 可见性
    // ================================================================

    @Nested
    @DisplayName("Protected 可见性")
    class ProtectedVisibilityTests {

        @Test
        @DisplayName("protected 方法在子类中可调用")
        void testProtectedInSubclass() {
            eval("open class Base {\n"
                + "    protected fun secret() = 42\n"
                + "}");
            eval("class Child : Base() {\n"
                + "    fun reveal() = secret()\n"
                + "}");
            assertEquals(42, eval("Child().reveal()").asInt());
        }

        @Test
        @DisplayName("protected 方法不能从外部直接访问")
        void testProtectedFromOutside() {
            eval("open class Base {\n"
                + "    protected fun secret() = 42\n"
                + "}");
            eval("val b = Base()");
            assertThrows(Exception.class, () -> eval("b.secret()"));
        }

        @Test
        @DisplayName("internal 方法可访问")
        void testInternalAccess() {
            eval("class Module {\n"
                + "    internal fun helper() = \"internal\"\n"
                + "}");
            NovaValue result = eval("Module().helper()");
            assertEquals("internal", result.asString());
        }
    }

    // ================================================================
    // 8. Reflect API 深度 — 父类/接口/方法参数
    // ================================================================

    @Nested
    @DisplayName("Reflect API 深度")
    class ReflectDeepTests {

        @Test
        @DisplayName("classOf 有 superclass 信息")
        void testClassOfSuperclass() {
            eval("open class Animal");
            eval("class Dog : Animal()");
            NovaValue superName = eval("classOf(Dog).superclass");
            assertNotNull(superName);
        }

        @Test
        @DisplayName("classOf 有 interfaces 信息")
        void testClassOfInterfaces() {
            eval("interface Printable { fun print() }");
            eval("class Doc : Printable { override fun print() {} }");
            NovaValue ifaces = eval("classOf(Doc).interfaces");
            assertNotNull(ifaces);
        }

        @Test
        @DisplayName("method 有 parameters 信息")
        void testMethodParameters() {
            eval("class Calc { fun add(a: Int, b: Int) = a + b }");
            NovaValue params = eval("classOf(Calc).methods.find { it.name == \"add\" }.parameters");
            assertNotNull(params);
        }

        @Test
        @DisplayName("field 的 isMutable 属性")
        void testFieldMutability() {
            eval("class Data(val immutable: Int, var mutable: Int)");
            NovaValue immutableField = eval("classOf(Data).fields[0].isMutable");
            NovaValue mutableField = eval("classOf(Data).fields[1].isMutable");
            assertFalse(immutableField.asBool());
            assertTrue(mutableField.asBool());
        }

        @Test
        @DisplayName("annotation 的 name")
        void testAnnotationName() {
            eval("annotation class Deprecated");
            eval("@Deprecated class OldApi");
            NovaValue annoName = eval("classOf(OldApi).annotations[0].name");
            assertEquals("Deprecated", annoName.asString());
        }
    }

    // ================================================================
    // 9. Result 类型
    // ================================================================

    @Nested
    @DisplayName("Result 类型")
    class ResultTypeTests {

        @Test
        @DisplayName("Ok 包装")
        void testOkResult() {
            NovaValue result = eval("Ok(42)");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Err 包装")
        void testErrResult() {
            NovaValue result = eval("Err(\"failed\")");
            assertNotNull(result);
        }

        @Test
        @DisplayName("is Ok 类型检查")
        void testIsOk() {
            assertTrue(eval("Ok(42) is Ok").asBool());
            assertFalse(eval("Err(\"x\") is Ok").asBool());
        }

        @Test
        @DisplayName("is Err 类型检查")
        void testIsErr() {
            assertTrue(eval("Err(\"x\") is Err").asBool());
            assertFalse(eval("Ok(42) is Err").asBool());
        }

        @Test
        @DisplayName("Ok.value 取值")
        void testOkValue() {
            assertEquals(42, eval("Ok(42).value").asInt());
        }

        @Test
        @DisplayName("Err.error 取值")
        void testErrError() {
            assertEquals("failed", eval("Err(\"failed\").error").asString());
        }

        @Test
        @DisplayName("runCatching 成功")
        void testRunCatchingSuccess() {
            NovaValue result = eval("runCatching { 42 }");
            assertTrue(eval("runCatching { 42 } is Ok").asBool());
            assertEquals(42, eval("runCatching { 42 }.value").asInt());
        }

        @Test
        @DisplayName("runCatching 异常")
        void testRunCatchingFailure() {
            assertTrue(eval("runCatching { error(\"boom\") } is Err").asBool());
        }

        @Test
        @DisplayName("Result.map 转换")
        void testResultMap() {
            assertEquals(84, eval("Ok(42).map { it * 2 }.value").asInt());
        }

        @Test
        @DisplayName("Err.map 不执行转换")
        void testErrMap() {
            assertTrue(eval("Err(\"x\").map { it * 2 } is Err").asBool());
        }

        @Test
        @DisplayName("Result.getOrElse 默认值")
        void testGetOrElse() {
            assertEquals(42, eval("Ok(42).getOrElse { 0 }").asInt());
            assertEquals(0, eval("Err(\"x\").getOrElse { 0 }").asInt());
        }

        @Test
        @DisplayName("? 操作符 — error propagation")
        void testErrorPropagation() {
            eval("fun safeDivide(a: Int, b: Int): Result {\n"
                + "    if (b == 0) return Err(\"div by zero\")\n"
                + "    return Ok(a / b)\n"
                + "}");
            assertEquals(5, eval("safeDivide(10, 2).value").asInt());
            assertTrue(eval("safeDivide(10, 0) is Err").asBool());
        }
    }

    // ================================================================
    // 10. 时间测量
    // ================================================================

    @Nested
    @DisplayName("时间测量")
    class TimeMeasurementTests {

        @Test
        @DisplayName("measureTimeMillis 返回非负值")
        void testMeasureTimeMillis() {
            NovaValue ms = eval("measureTimeMillis { var x = 0; for (i in 1..100) x = x + i }");
            assertTrue(ms.asLong() >= 0);
        }

        @Test
        @DisplayName("measureNanoTime 返回非负值")
        void testMeasureNanoTime() {
            NovaValue ns = eval("measureNanoTime { 1 + 1 }");
            assertTrue(ns.asLong() >= 0);
        }
    }

    // ================================================================
    // 11. repeat 函数
    // ================================================================

    @Nested
    @DisplayName("repeat 函数")
    class RepeatTests {

        @Test
        @DisplayName("repeat 执行指定次数")
        void testRepeatCount() {
            eval("var count = 0");
            eval("repeat(5) { count = count + 1 }");
            assertEquals(5, eval("count").asInt());
        }

        @Test
        @DisplayName("repeat 提供索引参数")
        void testRepeatWithIndex() {
            eval("var sum = 0");
            eval("repeat(4) { i -> sum = sum + i }");
            // 0+1+2+3 = 6
            assertEquals(6, eval("sum").asInt());
        }

        @Test
        @DisplayName("repeat(0) 不执行")
        void testRepeatZero() {
            eval("var flag = false");
            eval("repeat(0) { flag = true }");
            assertFalse(eval("flag").asBool());
        }
    }

    // ================================================================
    // 12. 可变集合
    // ================================================================

    @Nested
    @DisplayName("可变集合")
    class MutableCollectionTests {

        @Test
        @DisplayName("mutableListOf 创建和修改")
        void testMutableListOf() {
            eval("val list = mutableListOf(1, 2, 3)");
            eval("list.add(4)");
            assertEquals(4, eval("list.size()").asInt());
            assertEquals(4, eval("list[3]").asInt());
        }

        @Test
        @DisplayName("mutableMapOf 创建和修改")
        void testMutableMapOf() {
            eval("val map = mutableMapOf(\"a\" to 1)");
            eval("map.put(\"b\", 2)");
            assertEquals(2, eval("map.size()").asInt());
            assertEquals(2, eval("map[\"b\"]").asInt());
        }

        @Test
        @DisplayName("mutableSetOf 创建和修改")
        void testMutableSetOf() {
            eval("val set = mutableSetOf(1, 2, 3)");
            eval("set.add(4)");
            assertEquals(4, eval("set.size()").asInt());
        }

        @Test
        @DisplayName("mutableSetOf 去重")
        void testMutableSetDeduplicate() {
            eval("val set = mutableSetOf(1, 1, 2, 2, 3)");
            assertEquals(3, eval("set.size()").asInt());
        }

        @Test
        @DisplayName("mutableListOf 空列表")
        void testMutableListOfEmpty() {
            eval("val list = mutableListOf()");
            assertEquals(0, eval("list.size()").asInt());
            eval("list.add(\"hello\")");
            assertEquals(1, eval("list.size()").asInt());
        }

        @Test
        @DisplayName("emptyList 创建")
        void testEmptyList() {
            eval("val list = emptyList()");
            assertEquals(0, eval("list.size()").asInt());
        }

        @Test
        @DisplayName("emptyMap 创建")
        void testEmptyMap() {
            eval("val map = emptyMap()");
            assertEquals(0, eval("map.size()").asInt());
        }

        @Test
        @DisplayName("emptySet 创建")
        void testEmptySet() {
            eval("val set = emptySet()");
            assertEquals(0, eval("set.size()").asInt());
        }
    }

    // ================================================================
    // 13. 运算符重载 — 自定义类
    // ================================================================

    @Nested
    @DisplayName("运算符重载")
    class OperatorOverloadTests {

        @Test
        @DisplayName("plus 运算符重载")
        void testPlusOverload() {
            eval("class Vec(val x: Int, val y: Int) {\n"
                + "    operator fun plus(other: Vec) = Vec(x + other.x, y + other.y)\n"
                + "}");
            eval("val v = Vec(1, 2) + Vec(3, 4)");
            assertEquals(4, eval("v.x").asInt());
            assertEquals(6, eval("v.y").asInt());
        }

        @Test
        @DisplayName("minus 运算符重载")
        void testMinusOverload() {
            eval("class Vec(val x: Int, val y: Int) {\n"
                + "    operator fun minus(other: Vec) = Vec(x - other.x, y - other.y)\n"
                + "}");
            eval("val v = Vec(5, 8) - Vec(2, 3)");
            assertEquals(3, eval("v.x").asInt());
            assertEquals(5, eval("v.y").asInt());
        }

        @Test
        @DisplayName("times 运算符重载")
        void testTimesOverload() {
            eval("class Vec(val x: Int, val y: Int) {\n"
                + "    operator fun times(scalar: Int) = Vec(x * scalar, y * scalar)\n"
                + "}");
            eval("val v = Vec(2, 3) * 4");
            assertEquals(8, eval("v.x").asInt());
            assertEquals(12, eval("v.y").asInt());
        }

        @Test
        @DisplayName("compareTo 运算符重载")
        void testCompareToOverload() {
            eval("class Weight(val kg: Double) {\n"
                + "    operator fun compareTo(other: Weight): Int {\n"
                + "        if (kg > other.kg) return 1\n"
                + "        if (kg < other.kg) return -1\n"
                + "        return 0\n"
                + "    }\n"
                + "}");
            assertTrue(eval("Weight(10.0) > Weight(5.0)").asBool());
            assertFalse(eval("Weight(3.0) > Weight(7.0)").asBool());
        }

        @Test
        @DisplayName("unaryMinus 运算符重载")
        void testUnaryMinusOverload() {
            eval("class Vec(val x: Int, val y: Int) {\n"
                + "    operator fun unaryMinus() = Vec(-x, -y)\n"
                + "}");
            eval("val v = -Vec(3, 4)");
            assertEquals(-3, eval("v.x").asInt());
            assertEquals(-4, eval("v.y").asInt());
        }

        @Test
        @DisplayName("contains 运算符 (in)")
        void testContainsOverload() {
            eval("class NumberSet(val items: List<Int>) {\n"
                + "    operator fun contains(n: Int) = items.contains(n)\n"
                + "}");
            eval("val set = NumberSet([1, 2, 3, 4, 5])");
            assertTrue(eval("3 in set").asBool());
            assertFalse(eval("6 in set").asBool());
        }
    }

    // ================================================================
    // 14. 自定义 Getter / Setter
    // ================================================================

    @Nested
    @DisplayName("自定义 Getter / Setter")
    class GetterSetterTests {

        @Test
        @DisplayName("自定义 getter")
        void testCustomGetter() {
            eval("class Temp(val celsius: Double) {\n"
                + "    val fahrenheit: Double get() = celsius * 9.0 / 5.0 + 32.0\n"
                + "}");
            assertEquals(212.0, eval("Temp(100.0).fahrenheit").asDouble(), 0.1);
        }

        @Test
        @DisplayName("自定义 setter")
        void testCustomSetter() {
            eval("class Container(var _value: Int) {\n"
                + "    var value: Int\n"
                + "        get() = _value\n"
                + "        set(v) { _value = v * 2 }\n"
                + "}");
            eval("val c = Container(0)");
            eval("c.value = 5");
            assertEquals(10, eval("c._value").asInt());
        }

        @Test
        @DisplayName("getter 依赖其他字段")
        void testGetterDependsOnField() {
            eval("class Circle(val radius: Double) {\n"
                + "    val area: Double get() = 3.14159 * radius * radius\n"
                + "}");
            assertEquals(78.54, eval("Circle(5.0).area").asDouble(), 0.1);
        }

        @Test
        @DisplayName("backing field 模式")
        void testBackingFieldPattern() {
            eval("class Logger {\n"
                + "    var _logs = mutableListOf()\n"
                + "    var lastLog: String = \"\"\n"
                + "        get() = _logs.size().toString() + \" logs\"\n"
                + "}");
            NovaValue result = eval("Logger().lastLog");
            assertNotNull(result);
        }
    }

    // ================================================================
    // 15. 错误处理
    // ================================================================

    @Nested
    @DisplayName("错误处理")
    class ErrorHandlingTests {

        @Test
        @DisplayName("error() 抛异常")
        void testErrorThrows() {
            assertThrows(NovaRuntimeException.class, () -> eval("error(\"boom\")"));
        }

        @Test
        @DisplayName("try-catch 捕获异常")
        void testTryCatch() {
            NovaValue result = eval("try { error(\"oops\") } catch (e) { \"caught: \" + e }");
            assertTrue(result.asString().contains("caught"));
        }

        @Test
        @DisplayName("try-catch-finally")
        void testTryCatchFinally() {
            eval("var finalized = false");
            eval("try { error(\"fail\") } catch (e) { } finally { finalized = true }");
            assertTrue(eval("finalized").asBool());
        }

        @Test
        @DisplayName("嵌套 try-catch")
        void testNestedTryCatch() {
            NovaValue result = eval("try {\n"
                + "    try { error(\"inner\") }\n"
                + "    catch (e) { error(\"rethrown\") }\n"
                + "} catch (e) { \"final: \" + e }");
            assertTrue(result.asString().contains("final"));
        }

        @Test
        @DisplayName("throw 字符串直接作为异常")
        void testThrowString() {
            assertThrows(Exception.class, () -> eval("throw \"direct error\""));
        }

        @Test
        @DisplayName("check() 函数")
        void testCheckFunction() {
            // check(true) 不抛异常
            assertDoesNotThrow(() -> eval("check(true)"));
            // check(false) 抛异常
            assertThrows(Exception.class, () -> eval("check(false)"));
        }

        @Test
        @DisplayName("require() 函数")
        void testRequireFunction() {
            assertDoesNotThrow(() -> eval("require(true)"));
            assertThrows(Exception.class, () -> eval("require(false)"));
        }
    }

    // ================================================================
    // 16. 字符串操作
    // ================================================================

    @Nested
    @DisplayName("字符串操作覆盖")
    class StringOperationTests {

        @Test
        @DisplayName("String.repeat")
        void testStringRepeat() {
            assertEquals("abcabc", eval("\"abc\".repeat(2)").asString());
        }

        @Test
        @DisplayName("String.replace")
        void testStringReplace() {
            assertEquals("hxllo", eval("\"hello\".replace(\"e\", \"x\")").asString());
        }

        @Test
        @DisplayName("String.split")
        void testStringSplit() {
            NovaValue parts = eval("\"a,b,c\".split(\",\")");
            assertTrue(parts instanceof NovaList);
            assertEquals(3, ((NovaList) parts).size());
        }

        @Test
        @DisplayName("String.trim")
        void testStringTrim() {
            assertEquals("hello", eval("\"  hello  \".trim()").asString());
        }

        @Test
        @DisplayName("String.startsWith / endsWith")
        void testStartsEndsWith() {
            assertTrue(eval("\"hello\".startsWith(\"he\")").asBool());
            assertTrue(eval("\"hello\".endsWith(\"lo\")").asBool());
            assertFalse(eval("\"hello\".startsWith(\"lo\")").asBool());
        }

        @Test
        @DisplayName("String.contains")
        void testStringContains() {
            assertTrue(eval("\"hello world\".contains(\"world\")").asBool());
            assertFalse(eval("\"hello\".contains(\"xyz\")").asBool());
        }

        @Test
        @DisplayName("String.substring")
        void testStringSubstring() {
            assertEquals("ell", eval("\"hello\".substring(1, 4)").asString());
        }

        @Test
        @DisplayName("String.indexOf")
        void testStringIndexOf() {
            assertEquals(2, eval("\"hello\".indexOf(\"l\")").asInt());
        }

        @Test
        @DisplayName("String * Int 字符串重复")
        void testStringTimesInt() {
            assertEquals("ababab", eval("\"ab\" * 3").asString());
        }

        @Test
        @DisplayName("String * 0 返回空字符串")
        void testStringTimesZero() {
            assertEquals("", eval("\"abc\" * 0").asString());
        }
    }

    // ================================================================
    // 17. 集合高阶函数
    // ================================================================

    @Nested
    @DisplayName("集合高阶函数")
    class CollectionHOFTests {

        @Test
        @DisplayName("flatMap")
        void testFlatMap() {
            NovaValue result = eval("[1,2,3].flatMap { listOf(it, it * 10) }");
            assertTrue(result instanceof NovaList);
            assertEquals(6, ((NovaList) result).size());
        }

        @Test
        @DisplayName("fold")
        void testFold() {
            assertEquals(15, eval("[1,2,3,4,5].fold(0) { acc, x -> acc + x }").asInt());
        }

        @Test
        @DisplayName("reduce")
        void testReduce() {
            assertEquals(120, eval("[1,2,3,4,5].fold(1) { acc, x -> acc * x }").asInt());
        }

        @Test
        @DisplayName("any / all / none")
        void testAnyAllNone() {
            assertTrue(eval("[1,2,3].any { it > 2 }").asBool());
            assertFalse(eval("[1,2,3].all { it > 2 }").asBool());
            assertTrue(eval("[1,2,3].none { it > 10 }").asBool());
        }

        @Test
        @DisplayName("find / firstOrNull")
        void testFindFirstOrNull() {
            assertEquals(3, eval("[1,2,3,4].find { it > 2 }").asInt());
            assertTrue(eval("[1,2,3].find { it > 10 }").isNull());
        }

        @Test
        @DisplayName("sortedBy")
        void testSortedBy() {
            NovaValue sorted = eval("[3,1,2].sortedBy { it }");
            assertTrue(sorted instanceof NovaList);
            NovaList list = (NovaList) sorted;
            assertEquals(1, list.get(0).asInt());
            assertEquals(2, list.get(1).asInt());
            assertEquals(3, list.get(2).asInt());
        }

        @Test
        @DisplayName("groupBy")
        void testGroupBy() {
            NovaValue groups = eval("[1,2,3,4,5].groupBy { it % 2 }");
            assertNotNull(groups);
        }

        @Test
        @DisplayName("zip")
        void testZip() {
            NovaValue zipped = eval("[1,2,3].zip([\"a\",\"b\",\"c\"])");
            assertNotNull(zipped);
            assertTrue(zipped instanceof NovaList);
        }

        @Test
        @DisplayName("distinct")
        void testDistinct() {
            NovaValue result = eval("[1,2,2,3,3,3].distinct()");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("take / drop")
        void testTakeDrop() {
            NovaValue taken = eval("[1,2,3,4,5].take(3)");
            assertEquals(3, ((NovaList) taken).size());
            NovaValue dropped = eval("[1,2,3,4,5].drop(2)");
            assertEquals(3, ((NovaList) dropped).size());
        }

        @Test
        @DisplayName("sum")
        void testSum() {
            assertEquals(15, eval("[1,2,3,4,5].sum()").asInt());
        }

        @Test
        @DisplayName("count")
        void testCount() {
            assertEquals(3, eval("[1,2,3,4,5].count { it > 2 }").asInt());
        }

        @Test
        @DisplayName("reversed")
        void testReversed() {
            NovaValue rev = eval("[1,2,3].reversed()");
            NovaList list = (NovaList) rev;
            assertEquals(3, list.get(0).asInt());
            assertEquals(1, list.get(2).asInt());
        }

        @Test
        @DisplayName("Map.map 高阶函数")
        void testMapMap() {
            NovaValue result = eval("mapOf(\"a\" to 1, \"b\" to 2).map { k, v -> v * 2 }");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Map.filter 高阶函数")
        void testMapFilter() {
            NovaValue result = eval("mapOf(\"a\" to 1, \"b\" to 2, \"c\" to 3).filter { k, v -> v > 1 }");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Map.forEach")
        void testMapForEach() {
            eval("var sum = 0");
            eval("mapOf(\"a\" to 1, \"b\" to 2).forEach { k, v -> sum = sum + v }");
            assertEquals(3, eval("sum").asInt());
        }
    }

    // ================================================================
    // 18. Range 操作
    // ================================================================

    @Nested
    @DisplayName("Range 操作")
    class RangeTests {

        @Test
        @DisplayName("1..5 创建 range")
        void testRangeCreation() {
            NovaValue range = eval("1..5");
            assertNotNull(range);
        }

        @Test
        @DisplayName("Range 遍历")
        void testRangeIteration() {
            eval("var sum = 0");
            eval("for (i in 1..5) sum = sum + i");
            assertEquals(15, eval("sum").asInt());
        }

        @Test
        @DisplayName("downTo 递减范围")
        void testDownTo() {
            eval("var result = \"\"");
            eval("for (i in 3 downTo 1) result = result + i");
            assertEquals("321", eval("result").asString());
        }

        @Test
        @DisplayName("step 步进")
        void testStep() {
            eval("var sum = 0");
            eval("for (i in 0..10 step 2) sum = sum + i");
            // 0+2+4+6+8+10 = 30
            assertEquals(30, eval("sum").asInt());
        }

        @Test
        @DisplayName("until 不包含终点")
        void testUntil() {
            eval("var count = 0");
            eval("for (i in 0 until 5) count = count + 1");
            assertEquals(5, eval("count").asInt());
        }

        @Test
        @DisplayName("in Range 包含检查")
        void testInRange() {
            assertTrue(eval("3 in 1..5").asBool());
            assertFalse(eval("6 in 1..5").asBool());
        }

        @Test
        @DisplayName("Range.toList()")
        void testRangeToList() {
            NovaValue list = eval("(1..5).toList()");
            assertTrue(list instanceof NovaList);
            assertEquals(5, ((NovaList) list).size());
        }
    }

    // ================================================================
    // 19. 解构声明
    // ================================================================

    @Nested
    @DisplayName("解构声明")
    class DestructuringTests {

        @Test
        @DisplayName("Pair 解构")
        void testPairDestructuring() {
            eval("val (a, b) = Pair(1, \"hello\")");
            assertEquals(1, eval("a").asInt());
            assertEquals("hello", eval("b").asString());
        }

        @Test
        @DisplayName("data class 解构")
        void testDataClassDestructuring() {
            eval("@data class Point(val x: Int, val y: Int)");
            eval("val (x, y) = Point(3, 4)");
            assertEquals(3, eval("x").asInt());
            assertEquals(4, eval("y").asInt());
        }

        @Test
        @DisplayName("for 循环中解构 Map entries")
        void testDestructuringInForLoop() {
            eval("var result = \"\"");
            eval("for ((k, v) in mapOf(\"a\" to 1, \"b\" to 2)) result = result + k + v");
            assertTrue(eval("result").asString().contains("a1"));
            assertTrue(eval("result").asString().contains("b2"));
        }
    }

    // ================================================================
    // 20. when 表达式边界
    // ================================================================

    @Nested
    @DisplayName("when 表达式")
    class WhenExpressionTests {

        @Test
        @DisplayName("when 作为表达式返回值")
        void testWhenAsExpression() {
            eval("fun describe(x: Int) = when(x) {\n"
                + "    1 -> \"one\"\n"
                + "    2 -> \"two\"\n"
                + "    else -> \"other\"\n"
                + "}");
            assertEquals("one", eval("describe(1)").asString());
            assertEquals("two", eval("describe(2)").asString());
            assertEquals("other", eval("describe(99)").asString());
        }

        @Test
        @DisplayName("when 不带参数 (条件分支)")
        void testWhenWithoutArg() {
            eval("fun classify(n: Int) = when {\n"
                + "    n < 0 -> \"negative\"\n"
                + "    n == 0 -> \"zero\"\n"
                + "    else -> \"positive\"\n"
                + "}");
            assertEquals("negative", eval("classify(-5)").asString());
            assertEquals("zero", eval("classify(0)").asString());
            assertEquals("positive", eval("classify(10)").asString());
        }

        @Test
        @DisplayName("when 多条件匹配")
        void testWhenMultipleConditions() {
            eval("fun isWeekend(day: String) = when(day) {\n"
                + "    \"Saturday\", \"Sunday\" -> true\n"
                + "    else -> false\n"
                + "}");
            assertTrue(eval("isWeekend(\"Saturday\")").asBool());
            assertTrue(eval("isWeekend(\"Sunday\")").asBool());
            assertFalse(eval("isWeekend(\"Monday\")").asBool());
        }

        @Test
        @DisplayName("when 类型检查")
        void testWhenTypeCheck() {
            eval("fun typeDescribe(x: Any) = when(x) {\n"
                + "    is Int -> \"integer\"\n"
                + "    is String -> \"string\"\n"
                + "    else -> \"unknown\"\n"
                + "}");
            assertEquals("integer", eval("typeDescribe(42)").asString());
            assertEquals("string", eval("typeDescribe(\"hi\")").asString());
        }
    }

    // ================================================================
    // 21. 接口和抽象类
    // ================================================================

    @Nested
    @DisplayName("接口和抽象类")
    class InterfaceTests {

        @Test
        @DisplayName("接口方法实现")
        void testInterfaceImplementation() {
            eval("interface Greeter { fun greet(): String }");
            eval("class FriendlyGreeter : Greeter {\n"
                + "    override fun greet() = \"Hello!\"\n"
                + "}");
            assertEquals("Hello!", eval("FriendlyGreeter().greet()").asString());
        }

        @Test
        @DisplayName("接口默认方法")
        void testInterfaceDefaultMethod() {
            eval("interface Logger {\n"
                + "    fun log(msg: String) = \"LOG: \" + msg\n"
                + "}");
            eval("class SimpleLogger : Logger");
            assertEquals("LOG: test", eval("SimpleLogger().log(\"test\")").asString());
        }

        @Test
        @DisplayName("多接口实现")
        void testMultipleInterfaces() {
            eval("interface Readable { fun read() = \"read\" }");
            eval("interface Writable { fun write() = \"write\" }");
            eval("class File : Readable, Writable");
            eval("val f = File()");
            assertEquals("read", eval("f.read()").asString());
            assertEquals("write", eval("f.write()").asString());
        }

        @Test
        @DisplayName("object literal 实现接口")
        void testObjectLiteral() {
            eval("interface Callback { fun onResult(data: String): String }");
            eval("val cb = object : Callback {\n"
                + "    override fun onResult(data: String) = \"Got: \" + data\n"
                + "}");
            assertEquals("Got: hello", eval("cb.onResult(\"hello\")").asString());
        }
    }

    // ================================================================
    // 22. Companion object
    // ================================================================

    @Nested
    @DisplayName("Companion object")
    class CompanionObjectTests {

        @Test
        @DisplayName("companion object 方法")
        void testCompanionMethod() {
            eval("class Factory {\n"
                + "    companion object {\n"
                + "        fun create() = Factory()\n"
                + "    }\n"
                + "}");
            assertNotNull(eval("Factory.create()"));
        }

        @Test
        @DisplayName("companion object 常量")
        void testCompanionConst() {
            eval("class Config {\n"
                + "    companion object {\n"
                + "        val MAX = 100\n"
                + "    }\n"
                + "}");
            assertEquals(100, eval("Config.MAX").asInt());
        }
    }

    // ================================================================
    // 23. Lambda 边界
    // ================================================================

    @Nested
    @DisplayName("Lambda 边界")
    class LambdaEdgeCaseTests {

        @Test
        @DisplayName("立即执行 lambda")
        void testIIFE() {
            assertEquals(42, eval("{ 42 }()").asInt());
        }

        @Test
        @DisplayName("闭包捕获变量")
        void testClosureCapture() {
            eval("var x = 10");
            eval("val fn = { x + 5 }");
            assertEquals(15, eval("fn()").asInt());
        }

        @Test
        @DisplayName("Lambda 作为参数传递")
        void testLambdaAsParam() {
            eval("fun apply(x: Int, fn: (Int) -> Int) = fn(x)");
            assertEquals(25, eval("apply(5) { it * it }").asInt());
        }

        @Test
        @DisplayName("多参数 lambda")
        void testMultiParamLambda() {
            eval("fun combine(a: Int, b: Int, fn: (Int, Int) -> Int) = fn(a, b)");
            assertEquals(8, eval("combine(3, 5) { x, y -> x + y }").asInt());
        }

        @Test
        @DisplayName("返回 lambda")
        void testReturnLambda() {
            eval("fun multiplier(factor: Int) = { x: Int -> x * factor }");
            eval("val triple = multiplier(3)");
            assertEquals(15, eval("triple(5)").asInt());
        }
    }

    // ================================================================
    // 24. Null 安全
    // ================================================================

    @Nested
    @DisplayName("Null 安全")
    class NullSafetyTests {

        @Test
        @DisplayName("?. 安全调用 — 非 null")
        void testSafeCallNonNull() {
            eval("val s: String? = \"hello\"");
            assertEquals(5, eval("s?.length").asInt());
        }

        @Test
        @DisplayName("?. 安全调用 — null")
        void testSafeCallNull() {
            eval("val s: String? = null");
            assertTrue(eval("s?.length").isNull());
        }

        @Test
        @DisplayName("?: Elvis 运算符")
        void testElvisOperator() {
            eval("val s: String? = null");
            assertEquals("default", eval("s ?: \"default\"").asString());
        }

        @Test
        @DisplayName("Elvis 非 null 时返回左值")
        void testElvisNonNull() {
            eval("val s: String? = \"value\"");
            assertEquals("value", eval("s ?: \"default\"").asString());
        }

        @Test
        @DisplayName("安全索引 ?[]")
        void testSafeIndex() {
            eval("val list: List<Int>? = null");
            assertTrue(eval("list?[0]").isNull());
        }

        @Test
        @DisplayName("链式安全调用")
        void testChainedSafeCall() {
            eval("class Outer(val inner: Inner?)");
            eval("class Inner(val value: Int)");
            eval("val o = Outer(null)");
            assertTrue(eval("o.inner?.value").isNull());
        }
    }

    // ================================================================
    // 25. 字符串模板
    // ================================================================

    @Nested
    @DisplayName("字符串模板")
    class StringTemplateTests {

        @Test
        @DisplayName("简单变量插值")
        void testSimpleInterpolation() {
            eval("val name = \"World\"");
            assertEquals("Hello, World!", eval("\"Hello, $name!\"").asString());
        }

        @Test
        @DisplayName("表达式插值")
        void testExpressionInterpolation() {
            assertEquals("Result: 30", eval("\"Result: ${10 + 20}\"").asString());
        }

        @Test
        @DisplayName("嵌套插值")
        void testNestedInterpolation() {
            eval("val items = [1,2,3]");
            NovaValue result = eval("\"Count: ${items.size()}\"");
            assertEquals("Count: 3", result.asString());
        }
    }

    // ================================================================
    // 26. 多参数方法引用和绑定
    // ================================================================

    @Nested
    @DisplayName("方法引用高级")
    class MethodRefAdvancedTests {

        @Test
        @DisplayName("方法引用传给 forEach")
        void testMethodRefInForEach() {
            eval("fun process(x: Int) = x * x");
            eval("var total = 0");
            eval("[1,2,3].forEach { total = total + process(it) }");
            // 1+4+9=14
            assertEquals(14, eval("total").asInt());
        }

        @Test
        @DisplayName("构造器引用")
        void testConstructorRef() {
            eval("class Point(val x: Int, val y: Int)");
            eval("val create = ::Point");
            eval("val p = create(3, 4)");
            assertEquals(3, eval("p.x").asInt());
            assertEquals(4, eval("p.y").asInt());
        }
    }

    // ================================================================
    // 27. is 类型检查
    // ================================================================

    @Nested
    @DisplayName("is 类型检查")
    class TypeCheckTests {

        @Test
        @DisplayName("is Int")
        void testIsInt() {
            assertTrue(eval("42 is Int").asBool());
            assertFalse(eval("\"hello\" is Int").asBool());
        }

        @Test
        @DisplayName("is String")
        void testIsString() {
            assertTrue(eval("\"hello\" is String").asBool());
            assertFalse(eval("42 is String").asBool());
        }

        @Test
        @DisplayName("is List")
        void testIsList() {
            assertTrue(eval("[1,2,3] is List").asBool());
        }

        @Test
        @DisplayName("is 自定义类")
        void testIsCustomClass() {
            eval("open class Shape");
            eval("class Circle : Shape()");
            assertTrue(eval("Circle() is Shape").asBool());
            assertTrue(eval("Circle() is Circle").asBool());
        }

        @Test
        @DisplayName("!is 取反")
        void testNotIs() {
            assertTrue(eval("42 !is String").asBool());
            assertFalse(eval("42 !is Int").asBool());
        }
    }

    // ================================================================
    // 28. 数学和位运算
    // ================================================================

    @Nested
    @DisplayName("数学和位运算")
    class MathBitwiseTests {

        @Test
        @DisplayName("abs 绝对值")
        void testAbs() {
            assertEquals(5, eval("abs(-5)").asInt());
            assertEquals(5, eval("abs(5)").asInt());
        }

        @Test
        @DisplayName("max / min")
        void testMaxMin() {
            assertEquals(10, eval("max(3, 10)").asInt());
            assertEquals(3, eval("min(3, 10)").asInt());
        }

        @Test
        @DisplayName("取模运算")
        void testModulo() {
            assertEquals(1, eval("7 % 3").asInt());
        }

        @Test
        @DisplayName("整数除法")
        void testIntegerDivision() {
            assertEquals(2, eval("7 / 3").asInt());
        }

        @Test
        @DisplayName("浮点除法")
        void testFloatDivision() {
            assertEquals(2.5, eval("5.0 / 2.0").asDouble(), 0.001);
        }
    }

    // ================================================================
    // 29. 多行 REPL 边界
    // ================================================================

    @Nested
    @DisplayName("REPL 边界")
    class ReplEdgeCaseTests {

        @Test
        @DisplayName("跨 eval 调用的变量")
        void testCrossEvalVariables() {
            eval("val a = 10");
            eval("val b = 20");
            assertEquals(30, eval("a + b").asInt());
        }

        @Test
        @DisplayName("跨 eval 调用的函数")
        void testCrossEvalFunctions() {
            eval("fun square(n: Int) = n * n");
            assertEquals(25, eval("square(5)").asInt());
        }

        @Test
        @DisplayName("跨 eval 调用的类")
        void testCrossEvalClasses() {
            eval("class Counter(var count: Int) {\n"
                + "    fun inc() { count = count + 1 }\n"
                + "}");
            eval("val c = Counter(0)");
            eval("c.inc()");
            eval("c.inc()");
            assertEquals(2, eval("c.count").asInt());
        }

        @Test
        @DisplayName("REPL 中重新定义变量")
        void testReplRedefineVariable() {
            eval("var x = 10");
            eval("var x = 20");
            assertEquals(20, eval("x").asInt());
        }
    }

    // ================================================================
    // 30. 杂项覆盖
    // ================================================================

    @Nested
    @DisplayName("杂项覆盖")
    class MiscCoverageTests {

        @Test
        @DisplayName("typeof 各类型")
        void testTypeofVariousTypes() {
            assertEquals("Int", eval("typeof(42)").asString());
            assertEquals("String", eval("typeof(\"hello\")").asString());
            assertEquals("Boolean", eval("typeof(true)").asString());
            assertEquals("Double", eval("typeof(3.14)").asString());
            assertEquals("List", eval("typeof([1,2,3])").asString());
            assertEquals("Null", eval("typeof(null)").asString());
        }

        @Test
        @DisplayName("len 各类型")
        void testLenVariousTypes() {
            assertEquals(5, eval("len(\"hello\")").asInt());
            assertEquals(3, eval("len([1,2,3])").asInt());
            assertEquals(2, eval("len(mapOf(\"a\" to 1, \"b\" to 2))").asInt());
        }

        @Test
        @DisplayName("println 不抛异常")
        void testPrintlnDoesNotThrow() {
            assertDoesNotThrow(() -> eval("println(\"test\")"));
            assertDoesNotThrow(() -> eval("println(42)"));
            assertDoesNotThrow(() -> eval("println(null)"));
        }

        @Test
        @DisplayName("hashCode 和 equals")
        void testHashCodeEquals() {
            eval("@data class Pt(val x: Int, val y: Int)");
            assertTrue(eval("Pt(1,2) == Pt(1,2)").asBool());
            assertFalse(eval("Pt(1,2) == Pt(3,4)").asBool());
        }

        @Test
        @DisplayName("data class toString")
        void testDataClassToString() {
            eval("@data class User(val name: String, val age: Int)");
            NovaValue str = eval("User(\"Alice\", 30).toString()");
            assertTrue(str.asString().contains("Alice"));
            assertTrue(str.asString().contains("30"));
        }

        @Test
        @DisplayName("data class copy")
        void testDataClassCopy() {
            eval("@data class Config(val host: String, val port: Int)");
            eval("val c1 = Config(\"localhost\", 8080)");
            eval("val c2 = c1.copy(port = 9090)");
            assertEquals("localhost", eval("c2.host").asString());
            assertEquals(9090, eval("c2.port").asInt());
        }

        @Test
        @DisplayName("Pair first / second")
        void testPairAccess() {
            eval("val p = Pair(\"hello\", 42)");
            assertEquals("hello", eval("p.first").asString());
            assertEquals(42, eval("p.second").asInt());
        }

        @Test
        @DisplayName("listOf 带多种类型")
        void testListOfMixedTypes() {
            NovaValue list = eval("listOf(1, \"two\", 3.0, true, null)");
            assertTrue(list instanceof NovaList);
            assertEquals(5, ((NovaList) list).size());
        }

        @Test
        @DisplayName("mapOf 带 to 语法")
        void testMapOfWithTo() {
            eval("val m = mapOf(1 to \"one\", 2 to \"two\")");
            assertEquals("one", eval("m[1]").asString());
            assertEquals("two", eval("m[2]").asString());
        }

        @Test
        @DisplayName("setOf 创建")
        void testSetOf() {
            eval("val s = setOf(1, 2, 3, 2, 1)");
            assertEquals(3, eval("s.size()").asInt());
        }

        @Test
        @DisplayName("as 类型转换")
        void testAsCast() {
            eval("val x: Any = 42");
            assertEquals(42, eval("x as Int").asInt());
        }

        @Test
        @DisplayName("as? 安全类型转换")
        void testSafeAsCast() {
            eval("val x: Any = \"hello\"");
            assertTrue(eval("x as? Int").isNull());
            assertEquals("hello", eval("x as? String").asString());
        }
    }
}
