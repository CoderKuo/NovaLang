package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 分派/解析路径覆盖测试：MemberResolver、StaticMethodDispatcher、
 * MirCallDispatcher、VirtualMethodDispatcher、Builtins、Interpreter。
 *
 * 所有测试通过 eval(code) 执行 Nova 代码驱动。
 */
class DispatchCoverageTest {

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
    // 1. MemberResolver edge cases
    // ================================================================

    @Nested
    @DisplayName("MemberResolver")
    class MemberResolverTests {

        @Test
        @DisplayName("接口默认方法覆盖")
        void interfaceDefaultMethodOverride() {
            eval("interface Greeter { fun greet(): String = \"Hello\" }");
            eval("class MyGreeter : Greeter { override fun greet(): String = \"Hi\" }");
            NovaValue result = eval("MyGreeter().greet()");
            assertEquals("Hi", result.asString());
        }

        @Test
        @DisplayName("接口默认方法未覆盖时使用默认实现")
        void interfaceDefaultMethodFallback() {
            eval("interface Speaker { fun speak(): String = \"Default\" }");
            eval("class MySpeaker : Speaker {}");
            NovaValue result = eval("MySpeaker().speak()");
            assertEquals("Default", result.asString());
        }

        @Test
        @DisplayName("Java 互操作：访问 Java 静态字段")
        void javaInteropStaticField() {
            NovaValue result = eval("val Integer = javaClass(\"java.lang.Integer\")\nInteger.MAX_VALUE");
            assertEquals(Integer.MAX_VALUE, result.asInt());
        }

        @Test
        @DisplayName("Java 互操作：调用 Java 静态方法")
        void javaInteropStaticMethod() {
            NovaValue result = eval("val Math = javaClass(\"java.lang.Math\")\nMath.abs(-42)");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("Java 互操作：调用重载 Java 方法")
        void javaInteropOverloadedMethod() {
            NovaValue result = eval(
                "val sb = javaClass(\"java.lang.StringBuilder\")()\n" +
                "sb.append(\"hello\")\n" +
                "sb.append(\" world\")\n" +
                "sb.toString()");
            assertEquals("hello world", result.asString());
        }

        @Test
        @DisplayName("类方法访问带字段")
        void classMethodWithFieldAccess() {
            eval("class Calculator(val base: Int) {\n" +
                 "  fun add(n: Int): Int = base + n\n" +
                 "}");
            NovaValue result = eval("Calculator(10).add(20)");
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("抽象类的具体方法")
        void abstractClassConcreteMethod() {
            eval("abstract class Base {\n" +
                 "  fun hello(): String = \"from Base\"\n" +
                 "  abstract fun value(): Int\n" +
                 "}");
            eval("class Derived : Base() {\n" +
                 "  override fun value(): Int = 42\n" +
                 "}");
            NovaValue helloResult = eval("Derived().hello()");
            NovaValue valueResult = eval("Derived().value()");
            assertEquals("from Base", helloResult.asString());
            assertEquals(42, valueResult.asInt());
        }

        @Test
        @DisplayName("@data\nclass copy 方法")
        void dataCopyMethod() {
            eval("@data\nclass Point(val x: Int, val y: Int)");
            NovaValue result = eval("val p = Point(1, 2)\nval p2 = p.copy(x = 10)\np2.x");
            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("NovaResult 成员 map")
        void resultMap() {
            NovaValue result = eval("val r = Ok(5)\nr.map { it * 2 }.value");
            assertEquals(10, result.asInt());
        }

        @Test
        @DisplayName("NovaResult 成员 unwrapOr")
        void resultUnwrapOr() {
            NovaValue result = eval("val r = Err(\"fail\")\nr.unwrapOr(42)");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("枚举条目字段访问")
        void enumEntryFieldAccess() {
            eval("enum Color(val hex: String) {\n" +
                 "  RED(\"#FF0000\"),\n" +
                 "  GREEN(\"#00FF00\")\n" +
                 "}");
            NovaValue result = eval("Color.RED.hex");
            assertEquals("#FF0000", result.asString());
        }

        @Test
        @DisplayName("枚举 values 和 valueOf")
        void enumValuesAndValueOf() {
            eval("enum Dir { NORTH, SOUTH, EAST, WEST }");
            NovaValue valuesSize = eval("Dir.values().size");
            assertEquals(4, valuesSize.asInt());
            NovaValue entry = eval("Dir.valueOf(\"EAST\").name");
            assertEquals("EAST", entry.asString());
        }
    }

    // ================================================================
    // 2. StaticMethodDispatcher edge cases
    // ================================================================

    @Nested
    @DisplayName("StaticMethodDispatcher")
    class StaticMethodDispatcherTests {

        @Test
        @DisplayName("导入 Java 类并调用静态方法")
        void importJavaClassCallStaticMethod() {
            NovaValue result = eval(
                "val Collections = javaClass(\"java.util.Collections\")\n" +
                "Collections.emptyList().size()");
            assertEquals(0, result.asInt());
        }

        @Test
        @DisplayName("Java.type 带参构造器")
        void javaTypeConstructorWithArgs() {
            NovaValue result = eval(
                "val StringBuilder = Java.type(\"java.lang.StringBuilder\")\n" +
                "val sb = StringBuilder(\"init\")\n" +
                "sb.length()");
            assertEquals(4, result.asInt());
        }

        @Test
        @DisplayName("spread 参数调用")
        void spreadArgsCalling() {
            // spread 直接调用（不跨 REPL）
            NovaValue result = eval(
                "fun sum(vararg nums) {\n" +
                "  var total = 0\n" +
                "  for (n in nums) total = total + n\n" +
                "  return total\n" +
                "}\nsum(1, 2, 3)");
            assertEquals(6, result.asInt());
        }

        @Test
        @DisplayName("模块级变量跨 evalRepl 访问")
        void moduleLevelVariableAccess() {
            eval("var counter = 0");
            eval("counter = counter + 1");
            eval("counter = counter + 1");
            NovaValue result = eval("counter");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("用户函数遮蔽内置函数")
        void userFunctionShadowsStdlib() {
            eval("fun abs(x: Int): Int = if (x < 0) -x * 10 else x * 10");
            NovaValue result = eval("abs(-5)");
            assertEquals(50, result.asInt());
        }

        @Test
        @DisplayName("未定义函数的错误信息")
        void undefinedFunctionError() {
            assertThrows(NovaRuntimeException.class, () -> eval("nonExistentFunc()"));
        }

        @Test
        @DisplayName("模块级函数定义后跨 REPL 调用")
        void moduleFunctionCrossRepl() {
            eval("fun double(x: Int): Int = x * 2");
            NovaValue result = eval("double(21)");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("lambda 作为参数传递")
        void lambdaAsArgument() {
            eval("fun apply(x: Int, f: (Int) -> Int): Int = f(x)");
            NovaValue result = eval("apply(5) { it * 3 }");
            assertEquals(15, result.asInt());
        }
    }

    // ================================================================
    // 3. Builtins coverage
    // ================================================================

    @Nested
    @DisplayName("Builtins — typeof")
    class BuiltinsTypeofTests {

        @Test void typeofInt()     { assertEquals("Int", eval("typeof(42)").asString()); }
        @Test void typeofLong()    { assertEquals("Long", eval("typeof(42L)").asString()); }
        @Test void typeofFloat()   { assertEquals("Float", eval("typeof(3.14f)").asString()); }
        @Test void typeofDouble()  { assertEquals("Double", eval("typeof(3.14)").asString()); }
        @Test void typeofString()  { assertEquals("String", eval("typeof(\"hello\")").asString()); }
        @Test void typeofBoolean() { assertEquals("Boolean", eval("typeof(true)").asString()); }
        @Test void typeofNull()    { assertEquals("Null", eval("typeof(null)").asString()); }
        @Test void typeofList()    { assertEquals("List", eval("typeof(listOf(1,2))").asString()); }
        @Test void typeofMap()     { assertEquals("Map", eval("typeof(mapOf(\"a\" to 1))").asString()); }
        @Test void typeofChar()    { assertEquals("Char", eval("typeof('a')").asString()); }
        @Test void typeofRange()   { assertEquals("Range", eval("typeof(1..5)").asString()); }
        @Test void typeofPair()    { assertEquals("Pair", eval("typeof(Pair(1, 2))").asString()); }
    }

    @Nested
    @DisplayName("Builtins — len")
    class BuiltinsLenTests {

        @Test void lenString() { assertEquals(5, eval("len(\"hello\")").asInt()); }
        @Test void lenList()   { assertEquals(3, eval("len(listOf(1, 2, 3))").asInt()); }
        @Test void lenMap()    { assertEquals(2, eval("len(mapOf(\"a\" to 1, \"b\" to 2))").asInt()); }
        @Test void lenRange()  { assertEquals(5, eval("(1..5).toList().size()").asInt()); }
    }

    @Nested
    @DisplayName("Builtins — 类型转换")
    class BuiltinsConversionTests {

        @Test void toIntFromDouble()  { assertEquals(3, eval("toInt(3.7)").asInt()); }
        @Test void toIntFromString()  { assertEquals(42, eval("toInt(\"42\")").asInt()); }
        @Test void toDoubleFromInt()  { assertEquals(5.0, eval("toDouble(5)").asDouble(), 0.001); }
        @Test void toDoubleFromStr()  { assertEquals(3.14, eval("toDouble(\"3.14\")").asDouble(), 0.001); }
        @Test void toLongFromInt()    { assertEquals(100L, eval("toLong(100)").asLong()); }
        @Test void toCharFromInt()    { assertEquals('A', (char) eval("toChar(65)").toJavaValue()); }
        @Test void toStringFromInt()  { assertEquals("42", eval("toString(42)").asString()); }
        @Test void toStringFromBool() { assertEquals("true", eval("toString(true)").asString()); }
    }

    @Nested
    @DisplayName("Builtins — isCallable")
    class BuiltinsIsCallableTests {

        @Test
        void isCallableWithFunction() {
            eval("fun f() = 1");
            assertTrue(eval("isCallable(f)").asBool());
        }

        @Test
        void isCallableWithLambda() {
            assertTrue(eval("isCallable({ x -> x })").asBool());
        }

        @Test
        void isCallableWithNonCallable() {
            assertFalse(eval("isCallable(42)").asBool());
        }

        @Test
        void isCallableWithString() {
            assertFalse(eval("isCallable(\"hello\")").asBool());
        }
    }

    @Nested
    @DisplayName("Builtins — 集合构造器")
    class BuiltinsCollectionTests {

        @Test void listOfCreation()        { assertEquals(3, eval("listOf(1, 2, 3).size").asInt()); }
        @Test void mapOfCreation()         { assertEquals(2, eval("mapOf(\"a\" to 1, \"b\" to 2).size").asInt()); }
        @Test void setOfCreation()         { assertEquals(3, eval("setOf(1, 2, 3, 2, 1).size()").asInt()); }
        @Test void mutableListOfCreation() { assertEquals(0, eval("mutableListOf().size").asInt()); }
        @Test void mutableMapOfCreation()  { assertEquals(0, eval("mutableMapOf().size").asInt()); }
    }

    @Nested
    @DisplayName("Builtins — error / assert / sleep / hashCode")
    class BuiltinsMiscTests {

        @Test
        @DisplayName("error() 抛出运行时异常")
        void errorFunction() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class,
                () -> eval("error(\"boom\")"));
            assertTrue(ex.getMessage().contains("boom"));
        }

        @Test
        @DisplayName("assertTrue 不抛异常")
        void assertTruePasses() {
            assertDoesNotThrow(() -> eval("import nova.test\nassertTrue(true)"));
        }

        @Test
        @DisplayName("assertTrue(false) 抛异常")
        void assertFalseThrows() {
            assertThrows(Exception.class, () -> eval("import nova.test\nassertTrue(false)"));
        }

        @Test
        @DisplayName("delay(1) 不崩溃")
        void delaySmallDuration() {
            assertDoesNotThrow(() -> eval("import nova.time\nThread.sleep(1)"));
        }

        @Test
        @DisplayName("hashCode 各类型返回整数")
        void hashCodeOnTypes() {
            assertTrue(eval("hashCode(42)").isInt());
            assertTrue(eval("hashCode(\"hello\")").isInt());
            assertTrue(eval("hashCode(true)").isInt());
            assertTrue(eval("hashCode(3.14)").isInt());
        }
    }

    // ================================================================
    // 4. VirtualMethodDispatcher
    // ================================================================

    @Nested
    @DisplayName("VirtualMethodDispatcher")
    class VirtualMethodDispatcherTests {

        @Test
        @DisplayName("枚举条目自定义方法调用")
        void enumEntryCustomMethod() {
            eval("enum Planet(val mass: Double) {\n" +
                 "  EARTH(5.97),\n" +
                 "  MARS(0.642);\n" +
                 "  fun describe(): String = name + \": \" + mass\n" +
                 "}");
            NovaValue result = eval("Planet.EARTH.describe()");
            assertTrue(result.asString().contains("EARTH"));
        }

        @Test
        @DisplayName("子类覆盖 toString")
        void overrideToStringInSubclass() {
            eval("open class Animal(val name: String) {\n" +
                 "  override fun toString(): String = \"Animal(\" + name + \")\"\n" +
                 "}");
            NovaValue result = eval("Animal(\"Cat\").toString()");
            assertEquals("Animal(Cat)", result.asString());
        }

        @Test
        @DisplayName("继承方法调用")
        void callInheritedMethod() {
            eval("open class Shape {\n" +
                 "  fun type(): String = \"Shape\"\n" +
                 "}");
            eval("class Circle : Shape() {}");
            NovaValue result = eval("Circle().type()");
            assertEquals("Shape", result.asString());
        }

        @Test
        @DisplayName("运算符重载 compareTo")
        void operatorOverloadCompareTo() {
            eval("class Score(val value: Int) {\n" +
                 "  operator fun compareTo(other: Score): Int = value - other.value\n" +
                 "}");
            NovaValue result = eval("Score(10) > Score(5)");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("运算符重载 contains")
        void operatorOverloadContains() {
            eval("class IntSet(val items: List<Int>) {\n" +
                 "  operator fun contains(x: Int): Boolean = items.contains(x)\n" +
                 "}");
            NovaValue result = eval("3 in IntSet(listOf(1, 2, 3))");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("作用域函数 let")
        void scopeFunctionLet() {
            NovaValue result = eval("42.let { it * 2 }");
            assertEquals(84, result.asInt());
        }

        @Test
        @DisplayName("作用域函数 also")
        void scopeFunctionAlso() {
            NovaValue result = eval("\"hello\".also { println(it) }");
            assertEquals("hello", result.asString());
        }

        @Test
        @DisplayName("作用域函数 run")
        void scopeFunctionRun() {
            eval("class Config(var host: String, var port: Int)");
            NovaValue result = eval("Config(\"localhost\", 80).run { host + \":\" + port }");
            assertEquals("localhost:80", result.asString());
        }

        @Test
        @DisplayName("作用域函数 apply")
        void scopeFunctionApply() {
            eval("class Builder(var name: String = \"\", var value: Int = 0)");
            NovaValue result = eval(
                "val b = Builder().apply { name = \"test\"; value = 42 }\n" +
                "b.name + \":\" + b.value");
            assertEquals("test:42", result.asString());
        }

        @Test
        @DisplayName("作用域函数 takeIf")
        void scopeFunctionTakeIf() {
            NovaValue result = eval("10.takeIf { it > 5 }");
            assertEquals(10, result.asInt());
            NovaValue nullResult = eval("3.takeIf { it > 5 }");
            assertTrue(nullResult.isNull());
        }

        @Test
        @DisplayName("作用域函数 takeUnless")
        void scopeFunctionTakeUnless() {
            NovaValue result = eval("10.takeUnless { it > 20 }");
            assertEquals(10, result.asInt());
            NovaValue nullResult = eval("10.takeUnless { it > 5 }");
            assertTrue(nullResult.isNull());
        }
    }

    // ================================================================
    // 5. Interpreter internal paths
    // ================================================================

    @Nested
    @DisplayName("Interpreter internal paths")
    class InterpreterInternalTests {

        @Test
        @DisplayName("REPL 模式重定义变量")
        void replRedefineVariable() {
            eval("val x = 10");
            eval("val x = 20");
            NovaValue result = eval("x");
            assertEquals(20, result.asInt());
        }

        @Test
        @DisplayName("REPL 模式重定义函数")
        void replRedefineFunction() {
            eval("fun greet() = \"Hello\"");
            NovaValue result1 = eval("greet()");
            assertEquals("Hello", result1.asString());

            eval("fun greet() = \"Hi\"");
            NovaValue result2 = eval("greet()");
            assertEquals("Hi", result2.asString());
        }

        @Test
        @DisplayName("安全策略 STRICT 模式禁止 Java 互操作")
        void securityStrictModeBlocksJavaInterop() {
            Interpreter strictInterp = new Interpreter(NovaSecurityPolicy.strict());
            strictInterp.setReplMode(true);
            assertThrows(Exception.class,
                () -> strictInterp.evalRepl("javaClass(\"java.lang.Runtime\")"));
        }

        @Test
        @DisplayName("安全策略 STRICT 模式允许基础运算")
        void securityStrictModeAllowsBasicOps() {
            Interpreter strictInterp = new Interpreter(NovaSecurityPolicy.strict());
            strictInterp.setReplMode(true);
            NovaValue result = strictInterp.evalRepl("1 + 2");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("eval 后清理：多次 eval 不互相干扰")
        void cleanupAfterEval() {
            eval("val a = 1");
            eval("val b = 2");
            NovaValue result = eval("a + b");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("函数内局部变量不泄漏到全局")
        void localVariableDoesNotLeak() {
            eval("fun f() { val local = 42; return local }");
            eval("f()");
            assertThrows(Exception.class, () -> eval("local"));
        }

        @Test
        @DisplayName("递归函数正常工作")
        void recursionWorks() {
            eval("fun fib(n: Int): Int = if (n <= 1) n else fib(n - 1) + fib(n - 2)");
            NovaValue result = eval("fib(10)");
            assertEquals(55, result.asInt());
        }
    }

    // ================================================================
    // 6. MirCallDispatcher / 综合分派路径
    // ================================================================

    @Nested
    @DisplayName("MirCallDispatcher 分派路径")
    class MirCallDispatcherTests {

        @Test
        @DisplayName("with 作用域函数")
        void withScopeFunction() {
            NovaValue result = eval("\"hello\".run { length }");
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("Pair 解构")
        void pairDestructuring() {
            NovaValue result = eval("val (a, b) = Pair(10, 20)\na + b");
            assertEquals(30, result.asInt());
        }

        @Test
        @DisplayName("@data\nclass 解构")
        void dataClassDestructuring() {
            eval("@data\nclass Vec2(val x: Int, val y: Int)");
            NovaValue result = eval("val (x, y) = Vec2(3, 4)\nx * x + y * y");
            assertEquals(25, result.asInt());
        }

        @Test
        @DisplayName("Range 创建和迭代")
        void rangeCreationAndIteration() {
            NovaValue result = eval("var sum = 0\nfor (i in 1..5) sum = sum + i\nsum");
            assertEquals(15, result.asInt());
        }

        @Test
        @DisplayName("NovaResult Ok/Err 判断")
        void resultOkErrCheck() {
            assertTrue(eval("Ok(1).isOk").asBool());
            assertFalse(eval("Ok(1).isErr").asBool());
            assertTrue(eval("Err(\"e\").isErr").asBool());
            assertFalse(eval("Err(\"e\").isOk").asBool());
        }

        @Test
        @DisplayName("runCatching 正常执行")
        void runCatchingSuccess() {
            NovaValue result = eval("runCatching { 42 }.value");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("runCatching 异常捕获")
        void runCatchingError() {
            NovaValue result = eval("runCatching { error(\"fail\") }.isErr");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("repeat 函数")
        void repeatFunction() {
            NovaValue result = eval("var sum = 0\nrepeat(5) { sum = sum + 1 }\nsum");
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("List HOF map/filter")
        void listHigherOrderFunctions() {
            NovaValue mapped = eval("listOf(1, 2, 3).map { it * 2 }");
            assertEquals(3, eval("listOf(1, 2, 3).map { it * 2 }.size").asInt());

            NovaValue filtered = eval("listOf(1, 2, 3, 4, 5).filter { it > 3 }");
            assertEquals(2, eval("listOf(1, 2, 3, 4, 5).filter { it > 3 }.size").asInt());
        }

        @Test
        @DisplayName("String 成员方法 length/uppercase/contains")
        void stringMemberMethods() {
            assertEquals(5, eval("\"hello\".length").asInt());
            assertEquals("HELLO", eval("\"hello\".uppercase()").asString());
            assertTrue(eval("\"hello\".contains(\"ell\")").asBool());
        }

        @Test
        @DisplayName("Map 成员方法 containsKey/get/size")
        void mapMemberMethods() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertTrue(eval("m.containsKey(\"a\")").asBool());
            assertFalse(eval("m.containsKey(\"c\")").asBool());
            assertEquals(2, eval("m.size").asInt());
        }

        @Test
        @DisplayName("is 类型检查")
        void typeCheckIsOperator() {
            assertTrue(eval("42 is Int").asBool());
            assertTrue(eval("\"hello\" is String").asBool());
            assertFalse(eval("42 is String").asBool());
        }

        @Test
        @DisplayName("when 表达式")
        void whenExpression() {
            eval("fun describe(x: Any): String = when (x) {\n" +
                 "  is Int -> \"int\"\n" +
                 "  is String -> \"string\"\n" +
                 "  else -> \"other\"\n" +
                 "}");
            assertEquals("int", eval("describe(42)").asString());
            assertEquals("string", eval("describe(\"hi\")").asString());
        }

        @Test
        @DisplayName("运算符重载 plus")
        void operatorOverloadPlus() {
            eval("@data\nclass Vec(val x: Int, val y: Int) {\n" +
                 "  operator fun plus(other: Vec): Vec = Vec(x + other.x, y + other.y)\n" +
                 "}");
            NovaValue result = eval("val v = Vec(1, 2) + Vec(3, 4)\nv.x");
            assertEquals(4, result.asInt());
        }

        @Test
        @DisplayName("运算符重载 minus")
        void operatorOverloadMinus() {
            eval("@data\nclass Money(val amount: Int) {\n" +
                 "  operator fun minus(other: Money): Money = Money(amount - other.amount)\n" +
                 "}");
            NovaValue result = eval("(Money(100) - Money(30)).amount");
            assertEquals(70, result.asInt());
        }

        @Test
        @DisplayName("运算符重载 times")
        void operatorOverloadTimes() {
            eval("@data\nclass Scale(val factor: Int) {\n" +
                 "  operator fun times(n: Int): Scale = Scale(factor * n)\n" +
                 "}");
            NovaValue result = eval("(Scale(3) * 4).factor");
            assertEquals(12, result.asInt());
        }

        @Test
        @DisplayName("方法引用 ::")
        void methodReference() {
            eval("fun double(x: Int): Int = x * 2");
            NovaValue result = eval("listOf(1, 2, 3).map(::double)");
            // 验证结果是一个列表
            NovaValue first = eval("listOf(1, 2, 3).map(::double)[0]");
            assertEquals(2, first.asInt());
        }

        @Test
        @DisplayName("List 索引访问")
        void listIndexAccess() {
            NovaValue result = eval("val list = listOf(10, 20, 30)\nlist[1]");
            assertEquals(20, result.asInt());
        }

        @Test
        @DisplayName("Map 索引访问")
        void mapIndexAccess() {
            NovaValue result = eval("val m = mapOf(\"key\" to 99)\nm[\"key\"]");
            assertEquals(99, result.asInt());
        }

        @Test
        @DisplayName("String 索引访问返回 Char")
        void stringIndexAccess() {
            NovaValue result = eval("\"hello\"[0]");
            assertEquals('h', (char) result.toJavaValue());
        }

        @Test
        @DisplayName("Elvis 运算符")
        void elvisOperator() {
            NovaValue result = eval("val x: Int? = null\nx ?: 42");
            assertEquals(42, result.asInt());
        }

        @Test
        @DisplayName("安全调用运算符 ?.")
        void safeCallOperator() {
            NovaValue result = eval("val s: String? = null\ns?.length ?: -1");
            assertEquals(-1, result.asInt());
        }

        @Test
        @DisplayName("字符串模板")
        void stringTemplate() {
            eval("val name = \"World\"");
            NovaValue result = eval("\"Hello, $name!\"");
            assertEquals("Hello, World!", result.asString());
        }

        @Test
        @DisplayName("if 作为表达式")
        void ifExpression() {
            NovaValue result = eval("val x = if (true) 1 else 2\nx");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("try-catch 表达式")
        void tryCatchExpression() {
            NovaValue result = eval("val x = try { 1 / 0 } catch (e: Exception) { -1 }\nx");
            assertEquals(-1, result.asInt());
        }

        @Test
        @DisplayName("measureTimeMillis 返回非负值")
        void measureTimeMillis() {
            NovaValue result = eval("measureTimeMillis { 1 + 1 }");
            assertTrue(result.asLong() >= 0);
        }
    }
}
