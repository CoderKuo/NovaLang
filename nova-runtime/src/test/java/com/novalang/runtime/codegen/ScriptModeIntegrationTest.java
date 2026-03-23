package com.novalang.runtime.codegen;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * scriptMode 全面集成测试：覆盖 compileToBytecode + run/call 的所有场景。
 *
 * <p>验证编译模式下脚本语义的正确性：变量作用域、函数共享全局状态、
 * 闭包捕获、类/对象、异常处理、运算符重载等。</p>
 */
@DisplayName("scriptMode 集成测试")
class ScriptModeIntegrationTest {

    // ============ 辅助方法 ============

    private static Object run(String code) {
        return new Nova().compileToBytecode(code, "test.nova").run();
    }

    private static CompiledNova compile(String code) {
        CompiledNova c = new Nova().compileToBytecode(code, "test.nova");
        c.run(); // 执行初始化（注册函数等）
        return c;
    }

    // ============ 1. 基础变量 ============

    @Nested
    @DisplayName("变量定义与访问")
    class VariableTests {

        @Test void valBasic() { assertEquals(42, run("val x = 42\nx")); }
        @Test void varBasic() { assertEquals(10, run("var x = 10\nx")); }

        @Test void varMutation() {
            assertEquals(20, run("var x = 10\nx = 20\nx"));
        }

        @Test void varCompoundAssign() {
            assertEquals(15, run("var x = 10\nx += 5\nx"));
        }

        @Test void multipleVars() {
            assertEquals(30, run("var a = 10\nvar b = 20\na + b"));
        }

        @Test void varInLoop() {
            assertEquals(10, run("var sum = 0\nfor (i in 1..4) { sum += i }\nsum"));
        }

        @Test void valStringInterpolation() {
            assertEquals("hello world", run("val name = \"world\"\n\"hello $name\""));
        }

        @Test void varReassignDifferentType() {
            // var 可以重新赋值为不同类型
            assertEquals("hello", run("var x = 42\nx = \"hello\"\nx"));
        }
    }

    // ============ 2. 函数与全局变量共享 ============

    @Nested
    @DisplayName("函数与全局变量共享")
    class FunctionGlobalSharingTests {

        @Test void functionReadsGlobal() {
            assertEquals(42, run("var x = 42\nfun getX() = x\ngetX()"));
        }

        @Test void functionWritesGlobal() {
            assertEquals(99, run("var x = 0\nfun setX(v) { x = v }\nsetX(99)\nx"));
        }

        @Test void functionIncrementsGlobal() {
            assertEquals(3, run(
                    "var counter = 0\n" +
                    "fun inc() { counter = counter + 1 }\n" +
                    "inc()\ninc()\ninc()\ncounter"));
        }

        @Test void multipleFunctionsShareGlobal() {
            assertEquals(100, run(
                    "var value = 0\n" +
                    "fun set(v) { value = v }\n" +
                    "fun get() = value\n" +
                    "set(100)\nget()"));
        }

        @Test void functionReadAfterModify() {
            // 函数修改后，main 后续读取能看到最新值
            assertEquals("after", run(
                    "var state = \"before\"\n" +
                    "fun update() { state = \"after\" }\n" +
                    "update()\nstate"));
        }

        @Test void globalVarInCondition() {
            assertEquals(true, run(
                    "var flag = false\n" +
                    "fun enable() { flag = true }\n" +
                    "enable()\nflag"));
        }
    }

    // ============ 3. call() 跨调用持久化 ============

    @Nested
    @DisplayName("call() 跨调用全局变量持久化")
    class CallPersistenceTests {

        @Test void callMutatesGlobal() {
            CompiledNova c = compile(
                    "var x = 0\n" +
                    "fun setX(v) { x = v }\n" +
                    "fun getX() = x");
            c.call("setX", 42);
            assertEquals(42, c.call("getX"));
        }

        @Test void callIncrement() {
            CompiledNova c = compile(
                    "var n = 0\n" +
                    "fun inc() { n = n + 1 }\n" +
                    "fun getN() = n");
            c.call("inc");
            c.call("inc");
            c.call("inc");
            assertEquals(3, c.call("getN"));
        }

        @Test void callWithArgs() {
            CompiledNova c = compile(
                    "var result = \"\"\n" +
                    "fun append(s) { result = result + s }\n" +
                    "fun getResult() = result");
            c.call("append", "hello");
            c.call("append", " ");
            c.call("append", "world");
            assertEquals("hello world", c.call("getResult"));
        }

        @Test void callReturnValue() {
            CompiledNova c = compile("fun add(a, b) = a + b");
            assertEquals(30, c.call("add", 10, 20));
        }

        @Test void callMultipleGlobals() {
            CompiledNova c = compile(
                    "var a = 0\nvar b = 0\n" +
                    "fun setA(v) { a = v }\n" +
                    "fun setB(v) { b = v }\n" +
                    "fun sum() = a + b");
            c.call("setA", 10);
            c.call("setB", 20);
            assertEquals(30, c.call("sum"));
        }
    }

    // ============ 4. 闭包与 mutable capture ============

    @Nested
    @DisplayName("闭包与可变捕获")
    class ClosureTests {

        @Test void closureMutableCapture() {
            assertEquals(3, run(
                    "var counter = 0\n" +
                    "val inc = { counter = counter + 1 }\n" +
                    "inc()\ninc()\ninc()\ncounter"));
        }

        @Test void closureReadCapture() {
            assertEquals(42, run("val x = 42\nval fn = { x }\nfn()"));
        }

        @Test void closureAndFunctionCoexist() {
            // 闭包修改 var，函数也能读到
            assertEquals(99, run(
                    "var x = 0\n" +
                    "val setter = { x = 99 }\n" +
                    "fun getX() = x\n" +
                    "setter()\ngetX()"));
        }

        @Test void nestedClosure() {
            assertEquals(10, run(
                    "var x = 0\n" +
                    "val outer = {\n" +
                    "    val inner = { x = x + 1 }\n" +
                    "    inner()\n" +
                    "}\n" +
                    "for (i in 1..10) { outer() }\nx"));
        }
    }

    // ============ 5. 类与运算符重载 ============

    @Nested
    @DisplayName("类与运算符重载")
    class ClassTests {

        @Test void classInstantiation() {
            assertEquals(42, run("class Box(val value: Int)\nBox(42).value"));
        }

        @Test void classMethodCall() {
            assertEquals(6, run(
                    "class Calc {\n" +
                    "    fun mul(a: Int, b: Int) = a * b\n" +
                    "}\n" +
                    "Calc().mul(2, 3)"));
        }

        @Test void classVarInLoop() {
            assertEquals(15, run(
                    "class Acc(var total: Int) {\n" +
                    "    fun plus(n: Int) = Acc(total + n)\n" +
                    "}\n" +
                    "var a = Acc(0)\n" +
                    "for (i in 1..5) { a = a + i }\n" +
                    "a.total"));
        }

        @Test void dataClass() {
            assertEquals("Point(x=1, y=2)", run(
                    "@data class Point(val x: Int, val y: Int)\n" +
                    "Point(1, 2).toString()"));
        }

        @Test void classInGlobalVar() {
            // 全局 var 持有类实例，函数可修改
            assertEquals(99, run(
                    "class State(var value: Int)\n" +
                    "var state = State(0)\n" +
                    "fun update(v) { state = State(v) }\n" +
                    "update(99)\nstate.value"));
        }
    }

    // ============ 6. 异常处理 ============

    @Nested
    @DisplayName("异常处理")
    class ExceptionTests {

        @Test void tryCatch() {
            assertEquals("caught", run(
                    "try { throw \"err\" } catch (e: Exception) { \"caught\" }"));
        }

        @Test void tryCatchFinally() {
            assertEquals(1, run(
                    "var counter = 0\n" +
                    "try { throw \"err\" } catch (e: Exception) { 0 } finally { counter = counter + 1 }\n" +
                    "counter"));
        }

        @Test void finallyAlwaysRuns() {
            assertEquals(1, run(
                    "var counter = 0\n" +
                    "try { 42 } finally { counter = counter + 1 }\n" +
                    "counter"));
        }

        @Test void exceptionInFunction() {
            assertEquals("caught", run(
                    "fun explode() { throw \"boom\" }\n" +
                    "try { explode() } catch (e: Exception) { \"caught\" }"));
        }
    }

    // ============ 7. 控制流 ============

    @Nested
    @DisplayName("控制流")
    class ControlFlowTests {

        @Test void ifExpression() {
            assertEquals("yes", run("val x = 10\nif (x > 5) \"yes\" else \"no\""));
        }

        @Test void whenExpression() {
            assertEquals("two", run("val x = 2\nwhen (x) { 1 -> \"one\"; 2 -> \"two\"; else -> \"other\" }"));
        }

        @Test void forWithGlobalVar() {
            assertEquals(55, run("var sum = 0\nfor (i in 1..10) { sum += i }\nsum"));
        }

        @Test void whileWithGlobalVar() {
            assertEquals(5, run(
                    "var x = 0\nwhile (x < 5) { x = x + 1 }\nx"));
        }

        @Test void forWithFunctionCall() {
            assertEquals(10, run(
                    "var total = 0\n" +
                    "fun add(n) { total = total + n }\n" +
                    "for (i in 1..4) { add(i) }\ntotal"));
        }
    }

    // ============ 8. 集合操作 ============

    @Nested
    @DisplayName("集合操作")
    class CollectionTests {

        @Test void listLiteral() {
            assertEquals(3, run("[1, 2, 3].size()"));
        }

        @Test void mapLiteralBareKey() {
            assertEquals("Alice", run("#{name: \"Alice\"}[\"name\"]"));
        }

        @Test void mapDotAccess() {
            assertEquals("Bob", run("val m = #{name: \"Bob\"}\nm.name"));
        }

        @Test void listIndex() {
            assertEquals(20, run("[10, 20, 30][1]"));
        }

        @Test void listNegativeIndex() {
            assertEquals(30, run("[10, 20, 30][-1]"));
        }

        @Test void collectionInGlobalVar() {
            assertEquals(3, run(
                    "var items = []\n" +
                    "fun addItem(x) { items = items + [x] }\n" +
                    "addItem(1)\naddItem(2)\naddItem(3)\n" +
                    "items.size()"));
        }
    }

    // ============ 9. defineFunction / defineLibrary 注入 ============

    @Nested
    @DisplayName("宿主函数注入")
    class HostInjectionTests {

        @Test void defineFunction() {
            Nova nova = new Nova();
            nova.defineFunction("double", (Object x) -> ((Number) x).intValue() * 2);
            assertEquals(84, nova.compileToBytecode("double(42)", "t.nova").run());
        }

        @Test void defineFunctionAndGlobalVar() {
            Nova nova = new Nova();
            nova.defineFunction("getTime", () -> 12345L);
            CompiledNova c = nova.compileToBytecode(
                    "var lastTime = 0\n" +
                    "fun update() { lastTime = getTime() }\n" +
                    "fun getLast() = lastTime", "t.nova");
            c.run();
            c.call("update");
            assertEquals(12345L, c.call("getLast"));
        }

        @Test void defineLibrary() {
            Nova nova = new Nova();
            nova.defineLibrary("math", lib -> {
                lib.defineFunction("square", (Object x) -> ((Number) x).intValue() * ((Number) x).intValue());
                lib.defineVal("PI", 3.14159);
            });
            assertEquals(25, nova.compileToBytecode("math.square(5)", "t.nova").run());
        }

        @Test void registerExtension() {
            Nova nova = new Nova();
            nova.registerExtension(String.class, "shout",
                    (Object s) -> ((String) s).toUpperCase() + "!");
            assertEquals("HELLO!", nova.compileToBytecode("\"hello\".shout()", "t.nova").run());
        }
    }

    // ============ 10. 边缘情况 ============

    @Nested
    @DisplayName("边缘情况")
    class EdgeCaseTests {

        @Test void emptyScript() {
            // 空脚本不应崩溃
            assertDoesNotThrow(() -> run(""));
        }

        @Test void onlyComment() {
            assertDoesNotThrow(() -> run("// just a comment"));
        }

        @Test void globalVarShadowedByLocal() {
            // 函数内局部变量不应影响全局
            assertEquals(0, run(
                    "var x = 0\n" +
                    "fun f() { val x = 99 }\n" +
                    "f()\nx"));
        }

        @Test void recursiveFunction() {
            assertEquals(120, run(
                    "fun fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)\n" +
                    "fact(5)"));
        }

        @Test void nullValue() {
            assertNull(run("null"));
        }

        @Test void booleanGlobal() {
            assertEquals(true, run(
                    "var ready = false\n" +
                    "fun setReady() { ready = true }\n" +
                    "setReady()\nready"));
        }

        @Test void stringGlobal() {
            assertEquals("updated", run(
                    "var msg = \"initial\"\n" +
                    "fun setMsg(s) { msg = s }\n" +
                    "setMsg(\"updated\")\nmsg"));
        }

        @Test void doubleGlobal() {
            Object result = run(
                    "var price = 0.0\n" +
                    "fun setPrice(p) { price = p }\n" +
                    "setPrice(9.99)\nprice");
            assertEquals(9.99, ((Number) result).doubleValue(), 0.001);
        }

        @Test void largeLoopWithGlobal() {
            assertEquals(500500, run(
                    "var sum = 0\nfor (i in 1..1000) { sum += i }\nsum"));
        }

        @Test void multipleCallsToDifferentFunctions() {
            CompiledNova c = compile(
                    "var log = []\n" +
                    "fun a() { log = log + [\"a\"] }\n" +
                    "fun b() { log = log + [\"b\"] }\n" +
                    "fun getLog() = log.size()");
            c.call("a");
            c.call("b");
            c.call("a");
            assertEquals(3, c.call("getLog"));
        }
    }

    // ============ 11. 字符串操作 ============

    @Nested
    @DisplayName("字符串操作")
    class StringTests {

        @Test void stringConcat() { assertEquals("ab", run("\"a\" + \"b\"")); }
        @Test void stringLength() { assertEquals(5, run("\"hello\".length()")); }
        @Test void stringInterpolation() { assertEquals("x=42", run("val x = 42\n\"x=$x\"")); }
        @Test void stringInterpolationExpr() { assertEquals("sum=3", run("\"sum=${1+2}\"")); }
        @Test void stringIndex() { assertEquals("H", run("\"Hello\"[0]")); }
        @Test void stringNegativeIndex() { assertEquals("o", run("\"Hello\"[-1]")); }
        @Test void stringContains() { assertEquals(true, run("\"hello world\".contains(\"world\")")); }
        @Test void stringReplace() { assertEquals("hxllo", run("\"hello\".replace(\"e\", \"x\")")); }
        @Test void stringSplit() { assertEquals(3, run("\"a,b,c\".split(\",\").size()")); }
        @Test void stringTrim() { assertEquals("hi", run("\"  hi  \".trim()")); }
        @Test void stringToUpperCase() { assertEquals("HELLO", run("\"hello\".toUpperCase()")); }
        @Test void stringToLowerCase() { assertEquals("hello", run("\"HELLO\".toLowerCase()")); }
        @Test void stringStartsWith() { assertEquals(true, run("\"hello\".startsWith(\"hel\")")); }
        @Test void stringEndsWith() { assertEquals(true, run("\"hello\".endsWith(\"llo\")")); }
        @Test void emptyString() { assertEquals("", run("\"\"")); }
        @Test void stringRepeat() { assertEquals("aaa", run("\"a\".repeat(3)")); }
        @Test void multiLineString() { assertEquals("a\nb", run("\"a\\nb\"")); }
    }

    // ============ 12. 数值运算与类型 ============

    @Nested
    @DisplayName("数值运算与类型")
    class NumericTests {

        @Test void intArithmetic() { assertEquals(15, run("(2 + 3) * 3")); }
        @Test void intDivision() { assertEquals(3, run("10 / 3")); }
        @Test void intModulo() { assertEquals(1, run("10 % 3")); }
        @Test void longLiteral() { assertEquals(10000000000L, run("10000000000")); }
        @Test void doubleLiteral() { assertEquals(3.14, ((Number) run("3.14")).doubleValue(), 0.001); }
        @Test void intToDouble() { assertEquals(2.5, ((Number) run("5 / 2.0")).doubleValue(), 0.001); }
        @Test void negativeNumber() { assertEquals(-5, run("-5")); }
        @Test void unaryMinus() { assertEquals(-10, run("val x = 10\n-x")); }
        @Test void prefixIncrement() { assertEquals(2, run("var x = 1\n++x")); }
        @Test void postfixIncrement() { assertEquals(1, run("var x = 1\nx++")); }
        @Test void prefixDecrement() { assertEquals(0, run("var x = 1\n--x")); }
        @Test void bitwiseAnd() { assertEquals(0, run("5 & 2")); }
        @Test void bitwiseOr() { assertEquals(7, run("5 | 2")); }
        @Test void bitwiseXor() { assertEquals(7, run("5 ^ 2")); }
        @Test void shiftLeft() { assertEquals(8, run("1 << 3")); }
        @Test void shiftRight() { assertEquals(2, run("8 >> 2")); }

        @Test void divisionByZeroThrows() {
            assertThrows(Exception.class, () -> run("1 / 0"));
        }

        @Test void maxInt() { assertEquals(2147483647, run("2147483647")); }
    }

    // ============ 13. 比较与逻辑运算 ============

    @Nested
    @DisplayName("比较与逻辑运算")
    class ComparisonTests {

        @Test void equalInts() { assertEquals(true, run("1 == 1")); }
        @Test void notEqual() { assertEquals(true, run("1 != 2")); }
        @Test void lessThan() { assertEquals(true, run("1 < 2")); }
        @Test void greaterThan() { assertEquals(true, run("2 > 1")); }
        @Test void lessOrEqual() { assertEquals(true, run("2 <= 2")); }
        @Test void greaterOrEqual() { assertEquals(true, run("3 >= 2")); }
        @Test void logicalAnd() { assertEquals(true, run("true && true")); }
        @Test void logicalOr() { assertEquals(true, run("false || true")); }
        @Test void logicalNot() { assertEquals(false, run("!true")); }

        @Test void shortCircuitAnd() {
            // && 短路：第二个表达式不执行
            assertEquals(0, run("var x = 0\nfalse && { x = 1; true }()\nx"));
        }

        @Test void shortCircuitOr() {
            // || 短路：第二个表达式不执行
            assertEquals(0, run("var x = 0\ntrue || { x = 1; false }()\nx"));
        }

        @Test void stringEquality() { assertEquals(true, run("\"abc\" == \"abc\"")); }
        @Test void nullEquality() { assertEquals(true, run("null == null")); }
        @Test void nullInequality() { assertEquals(true, run("null != 1")); }
    }

    // ============ 14. 空安全 ============

    @Nested
    @DisplayName("空安全操作符")
    class NullSafetyTests {

        @Test void elvisWithNull() { assertEquals(42, run("val x = null\nx ?: 42")); }
        @Test void elvisWithValue() { assertEquals(10, run("val x = 10\nx ?: 42")); }
        @Test void safeCallNull() { assertNull(run("val x = null\nx?.toString()")); }
        @Test void safeCallNonNull() { assertEquals("42", run("val x = 42\nx?.toString()")); }
        @Test void nullAssignment() { assertEquals(true, run("var x = null\nx == null")); }
    }

    // ============ 15. 类型检查与转换 ============

    @Nested
    @DisplayName("类型检查")
    class TypeCheckTests {

        @Test void isInt() { assertEquals(true, run("42 is Int")); }
        @Test void isString() { assertEquals(true, run("\"hello\" is String")); }
        @Test void isBoolean() { assertEquals(true, run("true is Boolean")); }
        @Test void isNotString() { assertEquals(true, run("42 !is String")); }
        @Test void typeofInt() { assertEquals("Int", run("typeof(42)")); }
        @Test void typeofString() { assertEquals("String", run("typeof(\"hello\")")); }
        @Test void typeofBoolean() { assertEquals("Boolean", run("typeof(true)")); }
        @Test void typeofNull() { assertEquals("Null", run("typeof(null)")); }
        @Test void typeofList() { assertEquals("List", run("typeof([1,2])")); }
    }

    // ============ 16. 继承与接口 ============

    @Nested
    @DisplayName("继承与接口")
    class InheritanceTests {

        @Test void classInheritance() {
            assertEquals("Dog speaks", run(
                    "open class Animal(val name: String) { fun speak() = name + \" speaks\" }\n" +
                    "class Dog(name: String) : Animal(name)\n" +
                    "Dog(\"Dog\").speak()"));
        }

        @Test void methodOverride() {
            assertEquals("Woof", run(
                    "open class Animal { fun sound() = \"...\" }\n" +
                    "class Dog : Animal { fun sound() = \"Woof\" }\n" +
                    "Dog().sound()"));
        }

        @Test void interfaceImplementation() {
            assertEquals("hello", run(
                    "interface Greeter { fun greet(): String }\n" +
                    "class MyGreeter : Greeter { fun greet() = \"hello\" }\n" +
                    "MyGreeter().greet()"));
        }

        @Test void abstractClass() {
            assertEquals(42, run(
                    "abstract class Base { abstract fun value() }\n" +
                    "class Derived : Base { fun value() = 42 }\n" +
                    "Derived().value()"));
        }
    }

    // ============ 17. 枚举 ============

    @Nested
    @DisplayName("枚举")
    class EnumTests {

        @Test void enumValue() {
            assertEquals("RED", run("enum Color { RED, GREEN, BLUE }\nColor.RED.name"));
        }

        @Test void enumOrdinal() {
            assertEquals(1, run("enum Color { RED, GREEN, BLUE }\nColor.GREEN.ordinal"));
        }

        @Test void enumInWhen() {
            assertEquals("red", run(
                    "enum Color { RED, GREEN, BLUE }\n" +
                    "val c = Color.RED\n" +
                    "when (c) { Color.RED -> \"red\"; Color.GREEN -> \"green\"; else -> \"other\" }"));
        }

        @Test void enumWithMethod() {
            assertEquals("Color: RED", run(
                    "enum Color { RED, GREEN, BLUE\n" +
                    "  fun display() = \"Color: \" + name\n" +
                    "}\n" +
                    "Color.RED.display()"));
        }
    }

    // ============ 18. 高阶函数与函数式 ============

    @Nested
    @DisplayName("高阶函数")
    class HigherOrderFunctionTests {

        @Test void mapOnList() { assertEquals(3, run("[1,2,3].map { it * 2 }.size()")); }
        @Test void filterOnList() { assertEquals(2, run("[1,2,3,4].filter { it > 2 }.size()")); }
        @Test void forEachSideEffect() {
            assertEquals(6, run("var sum = 0\n[1,2,3].forEach { sum += it }\nsum"));
        }
        @Test void reduceOnList() { assertEquals(10, run("[1,2,3,4].reduce { acc, x -> acc + x }")); }
        @Test void anyOnList() { assertEquals(true, run("[1,2,3].any { it > 2 }")); }
        @Test void allOnList() { assertEquals(true, run("[2,4,6].all { it % 2 == 0 }")); }
        @Test void noneOnList() { assertEquals(true, run("[1,3,5].none { it % 2 == 0 }")); }
        @Test void flatMapOnList() { assertEquals(6, run("[1,2,3].flatMap { [it, it*10] }.size()")); }

        @Test void functionAsArgument() {
            assertEquals(10, run(
                    "fun apply(f, x) = f(x)\n" +
                    "apply({ it * 2 }, 5)"));
        }

        @Test void functionReturn() {
            assertEquals(15, run(
                    "fun multiplier(n: Int) = { x: Int -> x * n }\n" +
                    "val triple = multiplier(3)\n" +
                    "triple(5)"));
        }
    }

    // ============ 19. 解构赋值 ============

    @Nested
    @DisplayName("解构赋值")
    class DestructuringTests {

        @Test void pairDestructure() {
            assertEquals(3, run("val (a, b) = 1 to 2\na + b"));
        }

        @Test void forDestructure() {
            assertEquals(6, run(
                    "val map = #{\"a\": 1, \"b\": 2, \"c\": 3}\n" +
                    "var sum = 0\n" +
                    "for ((k, v) in map) { sum += v }\n" +
                    "sum"));
        }

        @Test void dataClassDestructure() {
            assertEquals(30, run(
                    "@data class Point(val x: Int, val y: Int)\n" +
                    "val (x, y) = Point(10, 20)\n" +
                    "x + y"));
        }
    }

    // ============ 20. Range 与 Spread ============

    @Nested
    @DisplayName("Range 与 Spread")
    class RangeSpreadTests {

        @Test void intRange() { assertEquals(5, run("(1..5).toList().size()")); }
        @Test void exclusiveRange() { assertEquals(4, run("(1..<5).toList().size()")); }
        @Test void rangeContains() { assertEquals(true, run("3 in 1..5")); }
        @Test void rangeNotContains() { assertEquals(true, run("6 !in 1..5")); }

        @Test void spreadOperator() {
            assertEquals(4, run(
                    "val a = [1, 2]\nval b = [..a, 3, 4]\nb.size()"));
        }

        @Test void spreadInFunctionCall() {
            assertEquals(6, run(
                    "fun sum(a, b, c) = a + b + c\n" +
                    "val args = [1, 2, 3]\n" +
                    "sum(..args)"));
        }
    }

    // ============ 21. 默认参数与命名参数 ============

    @Nested
    @DisplayName("默认参数与命名参数")
    class DefaultNamedArgsTests {

        @Test void defaultParam() {
            assertEquals("hello world", run("fun greet(name = \"world\") = \"hello $name\"\ngreet()"));
        }

        @Test void defaultParamOverride() {
            assertEquals("hello Nova", run("fun greet(name = \"world\") = \"hello $name\"\ngreet(\"Nova\")"));
        }

        @Test void multipleDefaults() {
            assertEquals(6, run("fun calc(a = 1, b = 2, c = 3) = a + b + c\ncalc()"));
        }
    }

    // ============ 22. 作用域函数 ============

    @Nested
    @DisplayName("作用域函数")
    class ScopeFunctionTests {

        @Test void letFunction() {
            assertEquals(10, run("5.let { it * 2 }"));
        }

        @Test void alsoFunction() {
            assertEquals(5, run("var x = 0\n5.also { x = it }\nx"));
        }

        @Test void runFunction() {
            assertEquals("HELLO", run("\"hello\".run { toUpperCase() }"));
        }

        @Test void takeIfTrue() {
            assertEquals(10, run("10.takeIf { it > 5 }"));
        }

        @Test void takeIfFalse() {
            assertNull(run("10.takeIf { it > 20 }"));
        }

        @Test void takeUnlessTrue() {
            assertNull(run("10.takeUnless { it > 5 }"));
        }
    }

    // ============ 23. 多异常捕获 ============

    @Nested
    @DisplayName("多异常捕获")
    class MultiCatchTests {

        @Test void catchMultipleTypes() {
            assertEquals("caught", run(
                    "try {\n" +
                    "    throw \"error\"\n" +
                    "} catch (e: ArithmeticException | NullPointerException | Exception) {\n" +
                    "    \"caught\"\n" +
                    "}"));
        }

        @Test void finallyWithMultiCatch() {
            assertEquals(1, run(
                    "var count = 0\n" +
                    "try { throw \"err\" }\n" +
                    "catch (e: Exception) { 0 }\n" +
                    "finally { count = count + 1 }\n" +
                    "count"));
        }
    }

    // ============ 24. Lazy 属性 ============

    @Nested
    @DisplayName("Lazy 属性")
    class LazyTests {

        @Test void lazyBasic() {
            assertEquals(42, run("val x by lazy { 42 }\nx"));
        }

        @Test void lazyNotEvaluatedEarly() {
            assertEquals(0, run(
                    "var counter = 0\n" +
                    "val x by lazy { counter = counter + 1; 99 }\n" +
                    "counter"));
        }

        @Test void lazyEvaluatedOnAccess() {
            assertEquals(1, run(
                    "var counter = 0\n" +
                    "val x by lazy { counter = counter + 1; 99 }\n" +
                    "val v = x\n" +
                    "counter"));
        }

        @Test void lazyCached() {
            assertEquals(1, run(
                    "var counter = 0\n" +
                    "val x by lazy { counter = counter + 1; 99 }\n" +
                    "val a = x\nval b = x\nval c = x\n" +
                    "counter"));
        }
    }

    // ============ 25. 错误处理边缘值 ============

    @Nested
    @DisplayName("错误处理边缘值")
    class ErrorEdgeCaseTests {

        @Test void undefinedVarReturnsNull() {
            // 编译模式下未定义变量返回 null（脚本可动态注入变量）
            assertNull(run("undefined_var"));
        }

        @Test void undefinedFuncThrows() {
            assertThrows(Exception.class, () -> run("nonexistent()"));
        }

        @Test void nullMemberAccessThrows() {
            assertThrows(Exception.class, () -> run("val x = null\nx.toString()"));
        }

        @Test void stackOverflowThrows() {
            // 非尾递归（1 + inf() 不会被尾调用优化）→ JVM StackOverflowError
            assertThrows(Throwable.class, () -> run(
                    "fun inf(n: Int): Int = 1 + inf(n)\ninf(0)"));
        }

        @Test void throwAndCatchCustomMessage() {
            assertEquals("boom", run(
                    "try { throw \"boom\" } catch (e: Exception) { e.message }"));
        }
    }

    // ============ 26. 扩展函数 ============

    @Nested
    @DisplayName("扩展函数")
    class ExtensionFunctionTests {

        @Test void stringExtension() {
            assertEquals("hello!", run(
                    "fun String.exclaim() = this + \"!\"\n\"hello\".exclaim()"));
        }

        @Test void intExtension() {
            assertEquals(true, run(
                    "fun Int.isEven() = this % 2 == 0\n4.isEven()"));
        }

        @Test void intExtensionFalse() {
            assertEquals(false, run(
                    "fun Int.isEven() = this % 2 == 0\n3.isEven()"));
        }

        @Test void extensionWithParam() {
            assertEquals(8, run(
                    "fun Int.add(other: Int) = this + other\n3.add(5)"));
        }

        @Test void extensionOnList() {
            assertEquals(3, run(
                    "fun List.second() = this[1]\n[1, 3, 5].second()"));
        }
    }

    // ============ 27. 命名参数 ============

    @Nested
    @DisplayName("命名参数")
    class NamedArgsTests {

        @Test void namedArgOrder() {
            assertEquals(10, run(
                    "fun sub(a: Int, b: Int) = a - b\nsub(b = 5, a = 15)"));
        }

        @Test void namedArgMixPositional() {
            assertEquals("Alice:30", run(
                    "fun info(name: String, age: Int) = \"$name:$age\"\n" +
                    "info(\"Alice\", age = 30)"));
        }

        @Test void namedWithDefault() {
            assertEquals("hi Bob", run(
                    "fun greet(greeting: String = \"hi\", name: String = \"world\") = \"$greeting $name\"\n" +
                    "greet(name = \"Bob\")"));
        }
    }

    // ============ 28. 中缀函数 ============

    @Nested
    @DisplayName("中缀函数")
    class InfixFunctionTests {

        @Test void infixCall() {
            assertEquals(8, run(
                    "infix fun Int.plus2(other: Int) = this + other\n2 plus2 6"));
        }

        @Test void infixDotCall() {
            assertEquals(8, run(
                    "infix fun Int.plus2(other: Int) = this + other\n2.plus2(6)"));
        }

        @Test void toInfix() {
            // 内置的 to 中缀函数
            assertEquals(3, run("val p = 1 to 2\np.first + p.second"));
        }
    }

    // ============ 29. 管道运算符 ============

    @Nested
    @DisplayName("管道运算符")
    class PipeOperatorTests {

        @Test void basicPipe() {
            assertEquals(10, run(
                    "fun double(x: Int) = x * 2\n5 |> double"));
        }

        @Test void chainedPipe() {
            assertEquals(12, run(
                    "fun double(x: Int) = x * 2\nfun add1(x: Int) = x + 1\n5 |> add1 |> double"));
        }

        @Test void pipeToLambda() {
            assertEquals(25, run("5 |> { it * it }"));
        }
    }

    // ============ 30. 方法引用 ============

    @Nested
    @DisplayName("方法引用")
    class MethodRefTests {

        @Test void globalFuncRef() {
            assertEquals(3, run(
                    "fun double(x: Int) = x * 2\nval ref = ::double\n[1, 2, 3].map(ref).size()"));
        }

        @Test void funcRefInMap() {
            assertEquals(6, run(
                    "fun double(x: Int) = x * 2\n[1, 2, 3].map(::double)[2]"));
        }
    }

    // ============ 31. init 块 ============

    @Nested
    @DisplayName("init 块")
    class InitBlockTests {

        @Test void initBasic() {
            assertEquals("valid", run(
                    "class Config(val port: Int) {\n" +
                    "    var status = \"\"\n" +
                    "    init { status = if (port > 0) \"valid\" else \"invalid\" }\n" +
                    "}\n" +
                    "Config(8080).status"));
        }

        @Test void initModifiesField() {
            assertEquals(100, run(
                    "class Counter {\n" +
                    "    var count = 0\n" +
                    "    init { count = 100 }\n" +
                    "}\n" +
                    "Counter().count"));
        }

        @Test void initAccessesConstructorParam() {
            assertEquals(6, run(
                    "class Doubler(val x: Int) {\n" +
                    "    var doubled = 0\n" +
                    "    init { doubled = x * 2 }\n" +
                    "}\n" +
                    "Doubler(3).doubled"));
        }
    }

    // ============ 32. 次级构造器 ============

    @Nested
    @DisplayName("次级构造器")
    class SecondaryConstructorTests {

        @Test void secondaryConstructor() {
            assertEquals(25, run(
                    "class Rectangle(val width: Int, val height: Int) {\n" +
                    "    constructor(size: Int) : this(size, size)\n" +
                    "    fun area() = width * height\n" +
                    "}\n" +
                    "Rectangle(5).area()"));
        }

        @Test void primaryConstructor() {
            assertEquals(12, run(
                    "class Rectangle(val width: Int, val height: Int) {\n" +
                    "    constructor(size: Int) : this(size, size)\n" +
                    "    fun area() = width * height\n" +
                    "}\n" +
                    "Rectangle(3, 4).area()"));
        }
    }

    // ============ 33. 单例对象 ============

    @Nested
    @DisplayName("单例对象")
    class ObjectSingletonTests {

        @Test void objectField() {
            assertEquals("localhost", run(
                    "object Config {\n" +
                    "    val host = \"localhost\"\n" +
                    "}\n" +
                    "Config.host"));
        }

        @Test void objectMethod() {
            assertEquals(25, run(
                    "object MathUtil {\n" +
                    "    fun square(x: Int) = x * x\n" +
                    "}\n" +
                    "MathUtil.square(5)"));
        }
    }

    // ============ 34. 伴生对象 ============

    @Nested
    @DisplayName("伴生对象")
    class CompanionObjectTests {

        @Test void companionField() {
            assertEquals("Anonymous", run(
                    "class User(val name: String) {\n" +
                    "    companion object {\n" +
                    "        val DEFAULT_NAME = \"Anonymous\"\n" +
                    "    }\n" +
                    "}\n" +
                    "User.DEFAULT_NAME"));
        }

        @Test void companionMethod() {
            assertEquals("Alice", run(
                    "class User(val name: String) {\n" +
                    "    companion object {\n" +
                    "        fun create(name: String) = User(name)\n" +
                    "    }\n" +
                    "}\n" +
                    "User.create(\"Alice\").name"));
        }
    }

    // ============ 35. 匿名对象 ============

    @Nested
    @DisplayName("匿名对象")
    class AnonymousObjectTests {

        @Test void anonymousObjectInterface() {
            assertEquals("clicked", run(
                    "interface Listener { fun onClick(): String }\n" +
                    "val l = object : Listener { fun onClick() = \"clicked\" }\n" +
                    "l.onClick()"));
        }
    }

    // ============ 36. 密封类 ============

    @Nested
    @DisplayName("密封类")
    class SealedClassTests {

        @Test void sealedClassInheritance() {
            assertEquals("ok", run(
                    "sealed class Result\n" +
                    "class Success(val data: String) : Result()\n" +
                    "class Failure(val msg: String) : Result()\n" +
                    "val r = Success(\"ok\")\n" +
                    "r.data"));
        }

        @Test void sealedClassTypeCheck() {
            assertEquals(true, run(
                    "sealed class Shape\n" +
                    "class Circle(val r: Int) : Shape()\n" +
                    "val s = Circle(5)\n" +
                    "s is Circle"));
        }
    }

    // ============ 37. 可见性修饰符 ============

    @Nested
    @DisplayName("可见性修饰符")
    class VisibilityTests {

        @Test void privateFieldViaMethod() {
            assertEquals(100, run(
                    "class Account(private val balance: Int) {\n" +
                    "    fun getBalance() = balance\n" +
                    "}\n" +
                    "Account(100).getBalance()"));
        }

        @Test void privateFieldDirectAccessThrows() {
            assertThrows(Exception.class, () -> run(
                    "class Account(private val balance: Int)\n" +
                    "Account(100).balance"));
        }
    }

    // ============ 38. 运算符重载 ============

    @Nested
    @DisplayName("运算符重载")
    class OperatorOverloadTests {

        @Test void plusOperator() {
            assertEquals(30, run(
                    "class Money(val amount: Int) {\n" +
                    "    fun plus(other: Money) = Money(amount + other.amount)\n" +
                    "}\n" +
                    "(Money(10) + Money(20)).amount"));
        }

        @Test void minusOperator() {
            assertEquals(5, run(
                    "class Money(val amount: Int) {\n" +
                    "    fun minus(other: Money) = Money(amount - other.amount)\n" +
                    "}\n" +
                    "(Money(15) - Money(10)).amount"));
        }

        @Test void timesOperator() {
            assertEquals(30, run(
                    "class Vec(val x: Int) {\n" +
                    "    fun times(scalar: Int) = Vec(x * scalar)\n" +
                    "}\n" +
                    "(Vec(10) * 3).x"));
        }

        @Test void unaryMinusOperator() {
            assertEquals(-5, run(
                    "class Num(val v: Int) {\n" +
                    "    fun unaryMinus() = Num(-v)\n" +
                    "}\n" +
                    "(-Num(5)).v"));
        }

        @Test void compareToOperator() {
            assertEquals(true, run(
                    "class Version(val v: Int) {\n" +
                    "    fun compareTo(other: Version) = v - other.v\n" +
                    "}\n" +
                    "Version(2) > Version(1)"));
        }

        @Test void indexGetOperator() {
            assertEquals(20, run(
                    "class Grid(val data: List) {\n" +
                    "    fun get(index: Int) = data[index]\n" +
                    "}\n" +
                    "Grid([10, 20, 30])[1]"));
        }
    }

    // ============ 39. do-while 循环 ============

    @Nested
    @DisplayName("do-while 循环")
    class DoWhileTests {

        @Test void doWhileBasic() {
            assertEquals(5, run(
                    "var x = 0\ndo { x = x + 1 } while (x < 5)\nx"));
        }

        @Test void doWhileRunsOnce() {
            // 条件一开始就 false，但 body 至少执行一次
            assertEquals(1, run(
                    "var x = 0\ndo { x = x + 1 } while (false)\nx"));
        }

        @Test void doWhileWithBreak() {
            assertEquals(3, run(
                    "var x = 0\ndo { x = x + 1; if (x == 3) break } while (x < 10)\nx"));
        }
    }

    // ============ 40. break 与 continue ============

    @Nested
    @DisplayName("break 与 continue")
    class BreakContinueTests {

        @Test void breakInFor() {
            assertEquals(3, run(
                    "var last = 0\nfor (i in 1..10) { if (i > 3) break; last = i }\nlast"));
        }

        @Test void continueInFor() {
            // 只累加奇数
            assertEquals(9, run(
                    "var sum = 0\nfor (i in 1..5) { if (i % 2 == 0) continue; sum += i }\nsum"));
        }

        @Test void breakInWhile() {
            assertEquals(5, run(
                    "var x = 0\nwhile (true) { x += 1; if (x == 5) break }\nx"));
        }

        @Test void nestedBreak() {
            // 内层 break 不影响外层
            assertEquals(3, run(
                    "var count = 0\n" +
                    "for (i in 1..3) {\n" +
                    "    for (j in 1..10) { if (j > 2) break }\n" +
                    "    count += 1\n" +
                    "}\n" +
                    "count"));
        }
    }

    // ============ 41. when 高级 ============

    @Nested
    @DisplayName("when 高级")
    class AdvancedWhenTests {

        @Test void whenMultipleValues() {
            assertEquals("small", run(
                    "val x = 2\nwhen (x) { 1, 2, 3 -> \"small\"; else -> \"big\" }"));
        }

        @Test void whenRangeCheck() {
            assertEquals("teen", run(
                    "val age = 15\nwhen (age) { in 0..12 -> \"child\"; in 13..19 -> \"teen\"; else -> \"adult\" }"));
        }

        @Test void whenTypeCheck() {
            assertEquals("string", run(
                    "val v = \"hello\"\nwhen (v) { is Int -> \"int\"; is String -> \"string\"; else -> \"other\" }"));
        }

        @Test void whenNoArgCondition() {
            assertEquals("positive", run(
                    "val x = 5\nwhen { x < 0 -> \"negative\"; x == 0 -> \"zero\"; else -> \"positive\" }"));
        }

        @Test void whenAsExpression() {
            assertEquals("B", run(
                    "val score = 85\n" +
                    "val grade = when { score >= 90 -> \"A\"; score >= 80 -> \"B\"; else -> \"C\" }\n" +
                    "grade"));
        }

        @Test void whenElseDefault() {
            assertEquals("other", run(
                    "val x = 99\nwhen (x) { 1 -> \"one\"; 2 -> \"two\"; else -> \"other\" }"));
        }
    }

    // ============ 42. 类型转换 ============

    @Nested
    @DisplayName("类型转换")
    class TypeCastTests {

        @Test void safeCastSuccess() {
            assertEquals("hello", run("val x = \"hello\"\nval s = x as? String\ns"));
        }

        @Test void safeCastFailure() {
            assertNull(run("val x = 42\nval s = x as? String\ns"));
        }

        @Test void isWithSmartCast() {
            assertEquals(5, run(
                    "val x = \"hello\"\nif (x is String) x.length() else 0"));
        }
    }

    // ============ 43. 集合高级操作 ============

    @Nested
    @DisplayName("集合高级操作")
    class AdvancedCollectionTests {

        @Test void listFind() {
            assertEquals(4, run("[1, 2, 3, 4, 5].find { it > 3 }"));
        }

        @Test void listFindNotFound() {
            assertNull(run("[1, 2, 3].find { it > 10 }"));
        }

        @Test void listFirst() {
            assertEquals(1, run("[1, 2, 3].first()"));
        }

        @Test void listLast() {
            assertEquals(3, run("[1, 2, 3].last()"));
        }

        @Test void listSorted() {
            assertEquals(1, run("[3, 1, 2].sorted()[0]"));
        }

        @Test void listReversed() {
            assertEquals(3, run("[1, 2, 3].reversed()[0]"));
        }

        @Test void listDistinct() {
            assertEquals(3, run("[1, 2, 2, 3, 3, 3].distinct().size()"));
        }

        @Test void listJoinToString() {
            assertEquals("1, 2, 3", run("[1, 2, 3].joinToString(\", \")"));
        }

        @Test void listContains() {
            assertEquals(true, run("[1, 2, 3].contains(2)"));
        }

        @Test void listIsEmpty() {
            assertEquals(true, run("[].isEmpty()"));
        }

        @Test void listIsNotEmpty() {
            assertEquals(true, run("[1].isNotEmpty()"));
        }

        @Test void listDrop() {
            assertEquals(2, run("[1, 2, 3, 4].drop(2).size()"));
        }

        @Test void listTake() {
            assertEquals(2, run("[1, 2, 3, 4].take(2).size()"));
        }

        @Test void listZip() {
            assertEquals(3, run("[1, 2, 3].zip([\"a\", \"b\", \"c\"]).size()"));
        }

        @Test void listSum() {
            assertEquals(10, run("[1, 2, 3, 4].sum()"));
        }

        @Test void listMinMax() {
            assertEquals(1, run("[3, 1, 2].min()"));
        }

        @Test void listMax() {
            assertEquals(3, run("[3, 1, 2].max()"));
        }

        @Test void listCount() {
            assertEquals(2, run("[1, 2, 3, 4].count { it > 2 }"));
        }

        @Test void mapKeys() {
            assertEquals(2, run("#{\"a\": 1, \"b\": 2}.keys().size()"));
        }

        @Test void mapValues() {
            assertEquals(2, run("#{\"a\": 1, \"b\": 2}.values().size()"));
        }

        @Test void mapContainsKey() {
            assertEquals(true, run("#{\"a\": 1}.containsKey(\"a\")"));
        }

        @Test void mapGetOrDefault() {
            assertEquals(0, run("#{\"a\": 1}.getOrDefault(\"b\", 0)"));
        }

        @Test void setCreation() {
            assertEquals(3, run("setOf(1, 2, 2, 3, 3).size()"));
        }
    }

    // ============ 44. 字符串高级操作 ============

    @Nested
    @DisplayName("字符串高级操作")
    class AdvancedStringTests {

        @Test void substring() {
            assertEquals("ell", run("\"hello\".substring(1, 4)"));
        }

        @Test void indexOf() {
            assertEquals(2, run("\"hello\".indexOf(\"l\")"));
        }

        @Test void isEmpty() {
            assertEquals(true, run("\"\".isEmpty()"));
        }

        @Test void isNotEmpty() {
            assertEquals(true, run("\"hello\".isNotEmpty()"));
        }

        @Test void toIntConversion() {
            assertEquals(42, run("\"42\".toInt()"));
        }

        @Test void toDoubleConversion() {
            assertEquals(3.14, ((Number) run("\"3.14\".toDouble()")).doubleValue(), 0.001);
        }

        @Test void charLiteral() {
            assertEquals("A", run("val c = 'A'\nc.toString()"));
        }

        @Test void charComparison() {
            assertEquals(true, run("'a' < 'b'"));
        }

        @Test void stringPlusNumber() {
            assertEquals("value=42", run("\"value=\" + 42"));
        }

        @Test void rawString() {
            assertEquals("a\\nb", run("r\"a\\nb\""));
        }

        @Test void tripleQuoteString() {
            // 多行字符串
            assertNotNull(run("val s = \"\"\"hello\nworld\"\"\"\ns"));
        }
    }

    // ============ 45. Result 类型 ============

    @Nested
    @DisplayName("Result 类型")
    class ResultTypeTests {

        @Test void okCreation() {
            assertEquals(42, run("Ok(42).value"));
        }

        @Test void errCreation() {
            assertEquals("fail", run("Err(\"fail\").error"));
        }

        @Test void isOkCheck() {
            assertEquals(true, run("Ok(1) is Ok"));
        }

        @Test void isErrCheck() {
            assertEquals(true, run("Err(\"x\") is Err"));
        }

        @Test void resultMap() {
            assertEquals(84, run("Ok(42).map { it * 2 }.value"));
        }

        @Test void runCatchingSuccess() {
            assertEquals(42, run("runCatching { 42 }.value"));
        }

        @Test void runCatchingFailure() {
            assertEquals(true, run("runCatching { throw \"err\" } is Err"));
        }
    }

    // ============ 46. 字面量高级 ============

    @Nested
    @DisplayName("字面量高级")
    class AdvancedLiteralTests {

        @Test void hexLiteral() {
            assertEquals(255, run("0xFF"));
        }

        @Test void binaryLiteral() {
            assertEquals(10, run("0b1010"));
        }

        @Test void octalLiteral() {
            assertEquals(63, run("0o77"));
        }

        @Test void longSuffix() {
            assertEquals(100L, run("100L"));
        }

        @Test void floatSuffix() {
            assertNotNull(run("2.5f"));
        }

        @Test void charLiteralValue() {
            assertEquals("A", run("'A'.toString()"));
        }
    }

    // ============ 47. 复合赋值运算符 ============

    @Nested
    @DisplayName("复合赋值运算符")
    class CompoundAssignTests {

        @Test void plusAssign() { assertEquals(15, run("var x = 10\nx += 5\nx")); }
        @Test void minusAssign() { assertEquals(5, run("var x = 10\nx -= 5\nx")); }
        @Test void timesAssign() { assertEquals(20, run("var x = 10\nx *= 2\nx")); }
        @Test void divAssign() { assertEquals(5, run("var x = 10\nx /= 2\nx")); }
        @Test void modAssign() { assertEquals(1, run("var x = 10\nx %= 3\nx")); }

        @Test void nullCoalesceAssign() {
            assertEquals(42, run("var x = null\nx ??= 42\nx"));
        }

        @Test void nullCoalesceAssignNoOverwrite() {
            assertEquals(10, run("var x = 10\nx ??= 42\nx"));
        }
    }

    // ============ 48. 安全索引与非空断言 ============

    @Nested
    @DisplayName("安全索引与非空断言")
    class SafeIndexTests {

        @Test void safeIndexNull() {
            assertNull(run("val list = null\nlist?[0]"));
        }

        @Test void safeIndexNonNull() {
            assertEquals(10, run("val list = [10, 20]\nlist?[0]"));
        }

        @Test void notNullAssertionSuccess() {
            assertEquals(42, run("val x = 42\nx!!"));
        }

        @Test void notNullAssertionFailure() {
            assertThrows(Exception.class, () -> run("val x = null\nx!!"));
        }
    }

    // ============ 49. 链式比较 ============

    @Nested
    @DisplayName("链式比较")
    class ChainedComparisonTests {

        @Test void chainedLess() {
            assertEquals(true, run("1 < 2 < 3"));
        }

        @Test void chainedLessFail() {
            assertEquals(false, run("1 < 2 < 2"));
        }

        @Test void chainedRange() {
            assertEquals(true, run("val x = 5\n0 <= x < 10"));
        }
    }

    // ============ 50. Data class 高级 ============

    @Nested
    @DisplayName("Data class 高级")
    class AdvancedDataClassTests {

        @Test void dataClassToString() {
            assertEquals("User(name=Alice, age=30)", run(
                    "@data class User(val name: String, val age: Int)\nUser(\"Alice\", 30).toString()"));
        }

        @Test void dataClassEquality() {
            assertEquals(true, run(
                    "@data class Point(val x: Int, val y: Int)\n" +
                    "Point(1, 2) == Point(1, 2)"));
        }

        @Test void dataClassInequality() {
            assertEquals(true, run(
                    "@data class Point(val x: Int, val y: Int)\n" +
                    "Point(1, 2) != Point(3, 4)"));
        }

        @Test void dataClassCopy() {
            assertEquals("Bob", run(
                    "@data class User(val name: String, val age: Int)\n" +
                    "val u = User(\"Alice\", 30)\nu.copy(name = \"Bob\").name"));
        }

        @Test void dataClassDestructure3() {
            assertEquals(6, run(
                    "@data class Vec3(val x: Int, val y: Int, val z: Int)\n" +
                    "val (a, b, c) = Vec3(1, 2, 3)\na + b + c"));
        }
    }

    // ============ 51. 内置函数 ============

    @Nested
    @DisplayName("内置函数")
    class BuiltinFunctionTests {

        @Test void absPositive() { assertEquals(5, run("abs(-5)")); }
        @Test void absZero() { assertEquals(0, run("abs(0)")); }
        @Test void minTwo() { assertEquals(1, run("min(1, 2)")); }
        @Test void maxTwo() { assertEquals(2, run("max(1, 2)")); }

        @Test void sqrtValue() {
            assertEquals(3.0, ((Number) run("sqrt(9.0)")).doubleValue(), 0.001);
        }

        @Test void powValue() {
            assertEquals(8.0, ((Number) run("pow(2.0, 3.0)")).doubleValue(), 0.001);
        }

        @Test void floorValue() {
            assertEquals(3.0, ((Number) run("floor(3.7)")).doubleValue(), 0.001);
        }

        @Test void ceilValue() {
            assertEquals(4.0, ((Number) run("ceil(3.1)")).doubleValue(), 0.001);
        }

        @Test void roundValue() {
            assertEquals(4.0, ((Number) run("round(3.6)")).doubleValue(), 0.001);
        }

        @Test void lenOfList() {
            assertEquals(3, run("len([1, 2, 3])"));
        }

        @Test void lenOfString() {
            assertEquals(5, run("len(\"hello\")"));
        }

        @Test void lenOfMap() {
            assertEquals(2, run("len(#{\"a\": 1, \"b\": 2})"));
        }

        @Test void toIntConversion() { assertEquals(42, run("toInt(\"42\")")); }
        @Test void toDoubleConversion() {
            assertEquals(3.14, ((Number) run("toDouble(\"3.14\")")).doubleValue(), 0.001);
        }
        @Test void toStringConversion() { assertEquals("42", run("toString(42)")); }

        @Test void isNullTrue() { assertEquals(true, run("isNull(null)")); }
        @Test void isNullFalse() { assertEquals(false, run("isNull(42)")); }
        @Test void isNumberTrue() { assertEquals(true, run("isNumber(42)")); }
        @Test void isStringTrue() { assertEquals(true, run("isString(\"hi\")")); }
        @Test void isListTrue() { assertEquals(true, run("isList([1])")); }
        @Test void isMapTrue() { assertEquals(true, run("isMap(#{\"a\": 1})")); }
        @Test void isCallableTrue() { assertEquals(true, run("isCallable({ 42 })")); }

        @Test void errorFunctionThrows() {
            assertThrows(Exception.class, () -> run("error(\"boom\")"));
        }

        @Test void repeatFunction() {
            assertEquals(3, run("var n = 0\nrepeat(3) { n += 1 }\nn"));
        }
    }

    // ============ 52. 内置常量 ============

    @Nested
    @DisplayName("内置常量")
    class BuiltinConstantTests {

        @Test void piConstant() {
            assertEquals(3.14159, ((Number) run("PI")).doubleValue(), 0.001);
        }

        @Test void eConstant() {
            assertEquals(2.71828, ((Number) run("E")).doubleValue(), 0.001);
        }

        @Test void maxIntConstant() {
            assertEquals(2147483647, run("MAX_INT"));
        }

        @Test void minIntConstant() {
            assertEquals(-2147483648, run("MIN_INT"));
        }
    }

    // ============ 53. 集合创建函数 ============

    @Nested
    @DisplayName("集合创建函数")
    class CollectionFactoryTests {

        @Test void listOfFunc() {
            assertEquals(3, run("listOf(1, 2, 3).size()"));
        }

        @Test void emptyListFunc() {
            assertEquals(0, run("emptyList().size()"));
        }

        @Test void mapOfFunc() {
            assertEquals("v", run("mapOf(\"k\", \"v\")[\"k\"]"));
        }

        @Test void setOfFunc() {
            assertEquals(2, run("setOf(1, 1, 2, 2).size()"));
        }

        @Test void pairFunc() {
            assertEquals(1, run("Pair(1, 2).first"));
        }
    }

    // ============ 54. 尾随 Lambda ============

    @Nested
    @DisplayName("尾随 Lambda")
    class TrailingLambdaTests {

        @Test void trailingLambdaFilter() {
            assertEquals(2, run("[1, 2, 3, 4].filter { it % 2 == 0 }.size()"));
        }

        @Test void trailingLambdaMap() {
            assertEquals(6, run("[1, 2, 3].map { it * 2 }[2]"));
        }

        @Test void trailingLambdaChain() {
            // filter → [3, 4, 5], map → [6, 8, 10], [0] → 6
            assertEquals(6, run("[1, 2, 3, 4, 5].filter { it > 2 }.map { it * 2 }[0]"));
        }
    }

    // ============ 55. apply 作用域函数 ============

    @Nested
    @DisplayName("apply 作用域函数")
    class ApplyScopeFunctionTests {

        @Test void applyReturnsSelf() {
            assertEquals(5, run("val x = 5.apply { }\nx"));
        }

        @Test void applyWithIt() {
            assertEquals(5, run("var captured = 0\n5.apply { captured = it }\ncaptured"));
        }
    }

    // ============ 56. with 函数 ============

    @Nested
    @DisplayName("with 函数")
    class WithFunctionTests {

        @Test void withBasic() {
            assertEquals(5, run("with(\"hello\") { length() }"));
        }
    }

    // ============ 57. 类型转换方法 ============

    @Nested
    @DisplayName("类型转换方法")
    class TypeConversionMethodTests {

        @Test void intToString() { assertEquals("42", run("42.toString()")); }
        @Test void stringToInt() { assertEquals(42, run("\"42\".toInt()")); }
        @Test void intToDouble() { assertEquals(42.0, ((Number) run("42.toDouble()")).doubleValue(), 0.001); }
        @Test void doubleToInt() { assertEquals(3, run("3.9.toInt()")); }
        @Test void intToLong() { assertEquals(42L, run("42.toLong()")); }
        @Test void boolToString() { assertEquals("true", run("true.toString()")); }
    }

    // ============ 58. if-else 高级 ============

    @Nested
    @DisplayName("if-else 高级")
    class AdvancedIfElseTests {

        @Test void ifElseIfElse() {
            assertEquals("zero", run(
                    "val x = 0\nif (x > 0) \"positive\" else if (x < 0) \"negative\" else \"zero\""));
        }

        @Test void nestedIf() {
            assertEquals("both", run(
                    "val a = true\nval b = true\n" +
                    "if (a) { if (b) \"both\" else \"onlyA\" } else \"none\""));
        }

        @Test void ifAsExpression() {
            assertEquals(10, run("val x = if (true) 10 else 20\nx"));
        }
    }

    // ============ 59. 接口高级 ============

    @Nested
    @DisplayName("接口高级")
    class AdvancedInterfaceTests {

        @Test void defaultMethod() {
            assertEquals("[hello]", run(
                    "interface Named {\n" +
                    "    fun name(): String\n" +
                    "    fun display() = \"[\" + name() + \"]\"\n" +
                    "}\n" +
                    "class Tag(val n: String) : Named { fun name() = n }\n" +
                    "Tag(\"hello\").display()"));
        }

        @Test void multipleInterfaces() {
            assertEquals("A+B", run(
                    "interface A { fun a() = \"A\" }\n" +
                    "interface B { fun b() = \"B\" }\n" +
                    "class C : A, B { fun combined() = a() + \"+\" + b() }\n" +
                    "C().combined()"));
        }
    }

    // ============ 60. 枚举高级 ============

    @Nested
    @DisplayName("枚举高级")
    class AdvancedEnumTests {

        @Test void enumWithConstructorParam() {
            assertEquals(5, run(
                    "enum Color(val code: Int) {\n" +
                    "    RED(1), GREEN(2), BLUE(5);\n" +
                    "}\n" +
                    "Color.BLUE.code"));
        }

        @Test void enumEquality() {
            assertEquals(true, run(
                    "enum Dir { UP, DOWN }\nDir.UP == Dir.UP"));
        }

        @Test void enumInequality() {
            assertEquals(true, run(
                    "enum Dir { UP, DOWN }\nDir.UP != Dir.DOWN"));
        }
    }

    // ============ 61. 嵌套类与内部作用域 ============

    @Nested
    @DisplayName("嵌套类")
    class NestedClassTests {

        @Test void classInsideFunction() {
            assertEquals(42, run(
                    "fun create(): Int {\n" +
                    "    class Local(val v: Int)\n" +
                    "    return Local(42).v\n" +
                    "}\n" +
                    "create()"));
        }
    }

    // ============ 62. for 循环高级 ============

    @Nested
    @DisplayName("for 循环高级")
    class AdvancedForTests {

        @Test void forOverList() {
            assertEquals(6, run(
                    "var sum = 0\nfor (x in [1, 2, 3]) { sum += x }\nsum"));
        }

        @Test void forWithIndex() {
            // indices-based
            assertEquals(3, run(
                    "val list = [10, 20, 30]\nvar count = 0\n" +
                    "for (i in 0..<list.size()) { count += 1 }\ncount"));
        }

        @Test void forNested() {
            assertEquals(9, run(
                    "var count = 0\nfor (i in 1..3) { for (j in 1..3) { count += 1 } }\ncount"));
        }

        @Test void forOverString() {
            assertEquals(5, run(
                    "var count = 0\nfor (c in \"hello\") { count += 1 }\ncount"));
        }
    }

    // ============ 63. 三元与条件表达式 ============

    @Nested
    @DisplayName("条件表达式")
    class ConditionalExprTests {

        @Test void ternaryLike() {
            assertEquals("yes", run("val x = true\nif (x) \"yes\" else \"no\""));
        }

        @Test void nestedTernary() {
            assertEquals("B", run(
                    "val n = 85\nif (n >= 90) \"A\" else if (n >= 80) \"B\" else \"C\""));
        }
    }

    // ============ 64. 标签化控制 ============

    @Nested
    @DisplayName("标签化控制")
    class LabeledControlTests {

        @Test void labeledBreak() {
            // i=1: j=1..4 各 count+1 (1*j≤4), j=5 时 1*5>4 → break@outer
            assertEquals(4, run(
                    "var count = 0\n" +
                    "outer@ for (i in 1..5) {\n" +
                    "    for (j in 1..5) {\n" +
                    "        if (i * j > 4) break@outer\n" +
                    "        count += 1\n" +
                    "    }\n" +
                    "}\n" +
                    "count"));
        }
    }

    // ============ 65. Guard 语句 ============

    @Nested
    @DisplayName("Guard 语句")
    class GuardTests {

        @Test void guardSuccess() {
            assertEquals(10, run(
                    "fun process(v): Int {\n" +
                    "    guard val x = v else { return -1 }\n" +
                    "    return x * 2\n" +
                    "}\n" +
                    "process(5)"));
        }

        @Test void guardNull() {
            assertEquals(-1, run(
                    "fun process(v): Int {\n" +
                    "    guard val x = v else { return -1 }\n" +
                    "    return x * 2\n" +
                    "}\n" +
                    "process(null)"));
        }
    }

    // ============ 66. buildString / buildList ============

    @Nested
    @DisplayName("构建器函数")
    class BuilderFunctionTests {

        @Test void buildStringBasic() {
            assertEquals("hello world", run(
                    "buildString {\n" +
                    "    append(\"hello\")\n" +
                    "    append(\" \")\n" +
                    "    append(\"world\")\n" +
                    "}"));
        }

        @Test void buildListBasic() {
            assertEquals(3, run(
                    "buildList {\n" +
                    "    add(1)\n" +
                    "    add(2)\n" +
                    "    add(3)\n" +
                    "}.size()"));
        }
    }

    // ============ 67. measureTimeMillis ============

    @Nested
    @DisplayName("工具函数")
    class UtilityFunctionTests {

        @Test void measureTimeMillisReturnsNonNegative() {
            Object result = run("measureTimeMillis { 1 + 1 }");
            assertTrue(((Number) result).longValue() >= 0);
        }
    }

    // ============ 68. 位运算高级 ============

    @Nested
    @DisplayName("位运算高级")
    class AdvancedBitwiseTests {

        @Test void bitwiseNot() {
            assertEquals(-1, run("~0"));
        }

        @Test void unsignedRightShift() {
            // (-1) >>> 16 应为正数
            Object result = run("(-1) >>> 16");
            assertTrue(((Number) result).intValue() > 0);
        }

        @Test void hexAndBitmask() {
            assertEquals(255, run("0xFF00 >> 8"));
        }
    }

    // ============ 69. 可变参数 ============

    @Nested
    @DisplayName("可变参数")
    class VarargTests {

        @Test void varargBasic() {
            assertEquals(6, run(
                    "fun sum(vararg numbers: Int): Int {\n" +
                    "    var total = 0\n" +
                    "    for (n in numbers) { total += n }\n" +
                    "    return total\n" +
                    "}\n" +
                    "sum(1, 2, 3)"));
        }

        @Test void varargEmpty() {
            assertEquals(0, run(
                    "fun sum(vararg numbers: Int): Int {\n" +
                    "    var total = 0\n" +
                    "    for (n in numbers) { total += n }\n" +
                    "    return total\n" +
                    "}\n" +
                    "sum()"));
        }

        @Test void varargSingle() {
            assertEquals(42, run(
                    "fun sum(vararg numbers: Int): Int {\n" +
                    "    var total = 0\n" +
                    "    for (n in numbers) { total += n }\n" +
                    "    return total\n" +
                    "}\n" +
                    "sum(42)"));
        }
    }

    // ============ 70. 引用相等 ============

    @Nested
    @DisplayName("引用相等")
    class ReferenceEqualityTests {

        @Test void sameReference() {
            assertEquals(true, run("val x = [1, 2]\nx === x"));
        }

        @Test void differentReference() {
            assertEquals(false, run("[1, 2] === [1, 2]"));
        }

        @Test void notSameReference() {
            assertEquals(true, run("[1, 2] !== [1, 2]"));
        }
    }

    // ============ 71. 多行与分号 ============

    @Nested
    @DisplayName("语句分隔")
    class StatementSeparatorTests {

        @Test void semicolonSeparator() {
            assertEquals(3, run("val a = 1; val b = 2; a + b"));
        }

        @Test void multiLine() {
            assertEquals(3, run("val a = 1\nval b = 2\na + b"));
        }
    }

    // ============ 72. 注解系统 ============

    @Nested
    @DisplayName("注解系统")
    class AnnotationTests {

        @Test void annotationDefined() {
            // 定义注解不报错
            assertDoesNotThrow(() -> run(
                    "annotation class MyTag\n@MyTag\nclass Foo\nFoo()"));
        }

        @Test void annotationWithParam() {
            assertDoesNotThrow(() -> run(
                    "annotation class Named(val value: String)\n" +
                    "@Named(value = \"test\")\nclass Foo\nFoo()"));
        }
    }

    // ============ 73. 反射 API ============

    @Nested
    @DisplayName("反射 API")
    class ReflectApiTests {

        @Test void classOfName() {
            assertEquals("Person", run(
                    "class Person(val name: String)\n" +
                    "classOf(Person).name"));
        }

        @Test void classOfFields() {
            Object result = run(
                    "class Person(val name: String, val age: Int)\n" +
                    "classOf(Person).fields.size()");
            assertTrue(((Number) result).intValue() >= 2);
        }
    }

    // ============ 74. 类继承高级 ============

    @Nested
    @DisplayName("继承高级")
    class AdvancedInheritanceTests {

        @Test void superCallField() {
            assertEquals("Dog:Rex", run(
                    "open class Animal(val name: String)\n" +
                    "class Dog(name: String) : Animal(name) {\n" +
                    "    fun display() = \"Dog:\" + name\n" +
                    "}\n" +
                    "Dog(\"Rex\").display()"));
        }

        @Test void multiLevelInheritance() {
            assertEquals("C", run(
                    "open class A { fun tag() = \"A\" }\n" +
                    "open class B : A { fun tag() = \"B\" }\n" +
                    "class C : B { fun tag() = \"C\" }\n" +
                    "C().tag()"));
        }

        @Test void isCheckWithInheritance() {
            assertEquals(true, run(
                    "open class Animal\nclass Dog : Animal\nDog() is Animal"));
        }
    }

    // ============ 75. Lambda 高级 ============

    @Nested
    @DisplayName("Lambda 高级")
    class AdvancedLambdaTests {

        @Test void lambdaWithTypes() {
            assertEquals(7, run("val add = { a: Int, b: Int -> a + b }\nadd(3, 4)"));
        }

        @Test void lambdaNoParam() {
            assertEquals(42, run("val f = { 42 }\nf()"));
        }

        @Test void lambdaImplicitIt() {
            assertEquals(10, run("val double = { it * 2 }\ndouble(5)"));
        }

        @Test void immediatelyInvokedLambda() {
            assertEquals(99, run("{ 99 }()"));
        }

        @Test void immediatelyInvokedLambdaWithArg() {
            assertEquals(10, run("{ x -> x * 2 }(5)"));
        }

        @Test void immediatelyInvokedLambdaImplicitIt() {
            assertEquals(10, run("{ it + 1 }(9)"));
        }

        @Test void funDeclIIFENoArgs() {
            // fun name() { body }() → 声明后紧跟()立即调用
            assertEquals(42, run("fun answer(): Int { return 42 }()"));
        }

        @Test void funDeclIIFEWithArgs() {
            // fun name(a,b) = expr → (args) 紧跟立即调用
            assertEquals(6, run("fun mul(a, b) = a * b\nmul(2, 3)"));
        }

        @Test void funDeclIIFEStillRegistered() {
            // IIFE 后函数仍注册到作用域，可再次调用
            assertEquals(10, run(
                    "fun double(x: Int) = x * 2\ndouble(3)\ndouble(5)"));
        }

        @Test void lambdaCapturingVal() {
            assertEquals(52, run("val offset = 10\nval add = { x: Int -> x + offset }\nadd(42)"));
        }
    }

    // ============ 76. 字符串高级插值 ============

    @Nested
    @DisplayName("字符串插值高级")
    class AdvancedStringInterpolationTests {

        @Test void interpolationMethod() {
            assertEquals("LEN=5", run("val s = \"hello\"\n\"LEN=${s.length()}\""));
        }

        @Test void interpolationNested() {
            assertEquals("a=3", run("val x = 1\nval y = 2\n\"a=${x + y}\""));
        }

        @Test void interpolationInLoop() {
            assertEquals("0-1-2-", run(
                    "var result = \"\"\nfor (i in 0..2) { result = result + \"$i-\" }\nresult"));
        }

        @Test void interpolationBoolean() {
            assertEquals("flag=true", run("val b = true\n\"flag=$b\""));
        }
    }

    // ============ 77. 复合赋值高级 ============

    @Nested
    @DisplayName("复合赋值高级")
    class AdvancedCompoundAssignTests {

        @Test void stringPlusAssign() {
            assertEquals("hello world", run("var s = \"hello\"\ns += \" world\"\ns"));
        }

        @Test void compoundInLoop() {
            // x *= 1 不改变值，验证循环中复合赋值不出错
            assertEquals(1, run("var x = 1\nfor (i in 1..100) { x *= 1 }\nx"));
        }

        @Test void compoundOnListSize() {
            assertEquals(3, run("var n = 0\nn += [1, 2, 3].size()\nn"));
        }
    }

    // ============ 78. when 边缘 ============

    @Nested
    @DisplayName("when 边缘情况")
    class WhenEdgeCaseTests {

        @Test void whenSingleBranch() {
            assertEquals("one", run("when (1) { 1 -> \"one\"; else -> \"other\" }"));
        }

        @Test void whenElseOnly() {
            assertEquals("default", run("when (999) { else -> \"default\" }"));
        }

        @Test void whenStringMatch() {
            assertEquals("hi", run("val s = \"hello\"\nwhen (s) { \"hello\" -> \"hi\"; else -> \"?\" }"));
        }

        @Test void whenBooleanMatch() {
            assertEquals("yes", run("val b = true\nwhen (b) { true -> \"yes\"; false -> \"no\" }"));
        }

        @Test void whenNullMatch() {
            assertEquals("nil", run("val x = null\nwhen (x) { null -> \"nil\"; else -> \"val\" }"));
        }
    }

    // ============ 79. try-catch 高级 ============

    @Nested
    @DisplayName("异常处理高级")
    class AdvancedExceptionTests {

        @Test void tryCatchAsExpression() {
            assertEquals(-1, run("val x = try { 1 / 0 } catch (e: Exception) { -1 }\nx"));
        }

        @Test void nestedTryCatch() {
            assertEquals("inner", run(
                    "try {\n" +
                    "    try { throw \"inner\" } catch (e: Exception) { e.message }\n" +
                    "} catch (e: Exception) { \"outer\" }"));
        }

        @Test void finallyReturnValue() {
            // finally 不影响返回值
            assertEquals(42, run(
                    "var log = 0\n" +
                    "val x = try { 42 } finally { log = 1 }\nx"));
        }

        @Test void catchAndRethrow() {
            assertThrows(Exception.class, () -> run(
                    "try { throw \"err\" } catch (e: Exception) { throw e.message }"));
        }
    }

    // ============ 80. 类高级 ============

    @Nested
    @DisplayName("类高级特性")
    class AdvancedClassTests {

        @Test void classToString() {
            assertEquals("Box(42)", run(
                    "class Box(val v: Int) {\n" +
                    "    fun toString() = \"Box($v)\"\n" +
                    "}\n" +
                    "Box(42).toString()"));
        }

        @Test void classMultipleFields() {
            assertEquals(30, run(
                    "class Point(val x: Int, val y: Int, val z: Int)\n" +
                    "val p = Point(10, 20, 0)\np.x + p.y + p.z"));
        }

        @Test void classMutableField() {
            assertEquals(99, run(
                    "class Counter(var n: Int)\n" +
                    "val c = Counter(0)\nc.n = 99\nc.n"));
        }

        @Test void classMethodAccessesField() {
            assertEquals(100, run(
                    "class Rect(val w: Int, val h: Int) {\n" +
                    "    fun area() = w * h\n" +
                    "}\n" +
                    "Rect(10, 10).area()"));
        }

        @Test void classMethodWithDefault() {
            assertEquals("Hi, World", run(
                    "class Greeter {\n" +
                    "    fun greet(name: String = \"World\") = \"Hi, $name\"\n" +
                    "}\n" +
                    "Greeter().greet()"));
        }
    }

    // ============ 81. 空安全高级 ============

    @Nested
    @DisplayName("空安全高级")
    class AdvancedNullSafetyTests {

        @Test void safeCallChain() {
            assertNull(run("val x = null\nx?.toString()?.length()"));
        }

        @Test void elvisChain() {
            assertEquals(0, run("val x = null\nval y = null\nx ?: y ?: 0"));
        }

        @Test void safeCallOnNonNull() {
            assertEquals(5, run("val s = \"hello\"\ns?.length()"));
        }

        @Test void nullCoalesceAssignChain() {
            assertEquals(1, run("var a = null\nvar b = null\na ??= 1\nb ??= a\nb"));
        }
    }

    // ============ 82. 函数高级 ============

    @Nested
    @DisplayName("函数高级特性")
    class AdvancedFunctionTests {

        @Test void multipleReturnPaths() {
            assertEquals("even", run(
                    "fun check(n: Int): String {\n" +
                    "    if (n % 2 == 0) return \"even\"\n" +
                    "    return \"odd\"\n" +
                    "}\n" +
                    "check(4)"));
        }

        @Test void functionCallingFunction() {
            assertEquals(120, run(
                    "fun fact(n: Int): Int = if (n <= 1) 1 else n * fact(n - 1)\n" +
                    "fun compute(x: Int) = fact(x)\n" +
                    "compute(5)"));
        }

        @Test void higherOrderReturningLambda() {
            assertEquals(9, run(
                    "fun adder(n: Int) = { x: Int -> x + n }\n" +
                    "val add5 = adder(5)\nadd5(4)"));
        }

        @Test void functionWithBlockBody() {
            assertEquals(55, run(
                    "fun sumTo(n: Int): Int {\n" +
                    "    var total = 0\n" +
                    "    for (i in 1..n) { total += i }\n" +
                    "    return total\n" +
                    "}\n" +
                    "sumTo(10)"));
        }
    }

    // ============ 83. 集合变换链 ============

    @Nested
    @DisplayName("集合变换链")
    class CollectionChainTests {

        @Test void filterMapReduce() {
            assertEquals(12, run(
                    "[1, 2, 3, 4, 5].filter { it % 2 == 0 }.map { it * 2 }.reduce { acc, x -> acc + x }"));
        }

        @Test void mapThenSize() {
            assertEquals(3, run("[10, 20, 30].map { it + 1 }.size()"));
        }

        @Test void flatMapThenFilter() {
            assertEquals(3, run(
                    "[[1, 2], [3, 4], [5]].flatMap { it }.filter { it > 2 }.size()"));
        }

        @Test void sortedThenFirst() {
            assertEquals(1, run("[3, 1, 4, 1, 5].sorted().first()"));
        }

        @Test void distinctThenJoin() {
            assertEquals("1, 2, 3", run("[1, 2, 2, 3, 3].distinct().joinToString(\", \")"));
        }

        @Test void reversedThenLast() {
            assertEquals(1, run("[1, 2, 3].reversed().last()"));
        }
    }

    // ============ 84. Map 高级 ============

    @Nested
    @DisplayName("Map 高级操作")
    class AdvancedMapTests {

        @Test void mapSize() {
            assertEquals(2, run("#{\"a\": 1, \"b\": 2}.size()"));
        }

        @Test void mapIsEmpty() {
            assertEquals(true, run("#{}.isEmpty()"));
        }

        @Test void mapPutAndGet() {
            assertEquals("v", run(
                    "val m = #{\"k\": \"v\"}\nm[\"k\"]"));
        }

        @Test void mapIteration() {
            assertEquals(3, run(
                    "var sum = 0\nfor ((k, v) in #{\"a\": 1, \"b\": 2}) { sum += v }\nsum"));
        }

        @Test void mapContainsKeyFalse() {
            assertEquals(false, run("#{\"a\": 1}.containsKey(\"z\")"));
        }
    }

    // ============ 85. 类型检查高级 ============

    @Nested
    @DisplayName("类型检查高级")
    class AdvancedTypeCheckTests {

        @Test void isCustomClass() {
            assertEquals(true, run(
                    "class Foo\nval f = Foo()\nf is Foo"));
        }

        @Test void isNotCustomClass() {
            assertEquals(true, run(
                    "class Foo\nclass Bar\nFoo() !is Bar"));
        }

        @Test void typeofDouble() {
            assertEquals("Double", run("typeof(3.14)"));
        }

        @Test void typeofChar() {
            assertEquals("Char", run("typeof('a')"));
        }

        @Test void typeofMap() {
            assertEquals("Map", run("typeof(#{\"a\": 1})"));
        }

        @Test void isAfterCast() {
            assertEquals(true, run("val x = 42\nx is Int"));
        }
    }

    // ============ 86. 数学函数高级 ============

    @Nested
    @DisplayName("数学函数高级")
    class AdvancedMathTests {

        @Test void absNegative() { assertEquals(42, run("abs(-42)")); }
        @Test void absPositive() { assertEquals(42, run("abs(42)")); }
        @Test void minThree() { assertEquals(1, run("min(min(3, 1), 2)")); }
        @Test void maxThree() { assertEquals(3, run("max(max(1, 3), 2)")); }

        @Test void sqrtFour() {
            assertEquals(2.0, ((Number) run("sqrt(4.0)")).doubleValue(), 0.001);
        }

        @Test void powSquare() {
            assertEquals(16.0, ((Number) run("pow(4.0, 2.0)")).doubleValue(), 0.001);
        }

        @Test void floorAndCeil() {
            assertEquals(3.0, ((Number) run("floor(3.9)")).doubleValue(), 0.001);
        }

        @Test void ceilUp() {
            assertEquals(4.0, ((Number) run("ceil(3.01)")).doubleValue(), 0.001);
        }
    }

    // ============ 87. 解构高级 ============

    @Nested
    @DisplayName("解构高级")
    class AdvancedDestructuringTests {

        @Test void listDestructure3() {
            assertEquals(6, run("val (a, b, c) = [1, 2, 3]\na + b + c"));
        }

        @Test void listDestructure2() {
            assertEquals(3, run("val (x, y) = [1, 2]\nx + y"));
        }

        @Test void destructureInFunctionReturn() {
            assertEquals(3, run(
                    "fun getPair() = 1 to 2\n" +
                    "val (a, b) = getPair()\na + b"));
        }

        @Test void forDestructurePairs() {
            assertEquals(6, run(
                    "var sum = 0\n" +
                    "for ((a, b) in [[1, 2], [3, 0]]) { sum += a + b }\nsum"));
        }
    }

    // ============ 87b. 基于名称的解构 ============

    @Nested
    @DisplayName("基于名称的解构")
    class NameBasedDestructuringCodegenTests {

        @Test void nameBasedBasic() {
            assertEquals(30, run(
                    "@data class Pt(val x: Int, val y: Int)\n" +
                    "val (b = y, a = x) = Pt(10, 20)\na + b"));
        }

        @Test void nameBasedReversedOrder() {
            assertEquals("alice", run(
                    "@data class User(val username: String, val email: String)\n" +
                    "val (name = username, mail = email) = User(\"alice\", \"a@e\")\nname"));
        }

        @Test void nameBasedThreeFields() {
            assertEquals(6, run(
                    "@data class V(val x: Int, val y: Int, val z: Int)\n" +
                    "val (c = z, a = x, b = y) = V(1, 2, 3)\na + b + c"));
        }

        @Test void nameBasedMixed() {
            assertEquals(10, run(
                    "@data class Pair(val first: Int, val second: Int)\n" +
                    "val (a = first, b) = Pair(10, 20)\na"));
        }

        @Test void nameBasedForLoop() {
            assertEquals(30, run(
                    "@data class Item(val name: String, val price: Int)\n" +
                    "val items = listOf(Item(\"a\", 10), Item(\"b\", 20))\n" +
                    "var total = 0\n" +
                    "for ((cost = price) in items) total += cost\ntotal"));
        }
    }

    // ============ 88. Scope 函数全覆盖 ============

    @Nested
    @DisplayName("Scope 函数全覆盖")
    class ScopeFunctionFullTests {

        @Test void letChain() {
            assertEquals(6, run("3.let { it * 2 }"));
        }

        @Test void letNull() {
            assertNull(run("null?.let { it.toString() }"));
        }

        @Test void alsoReturnsSelf() {
            assertEquals("hello", run("\"hello\".also { }"));
        }

        @Test void runWithBlock() {
            assertEquals(5, run("\"hello\".run { length() }"));
        }

        @Test void applyReturnsSelfValue() {
            assertEquals(42, run("42.apply { }"));
        }

        @Test void takeIfMatch() {
            assertEquals(10, run("10.takeIf { it > 5 }"));
        }

        @Test void takeIfNoMatch() {
            assertNull(run("3.takeIf { it > 5 }"));
        }

        @Test void takeUnlessMatch() {
            assertEquals(3, run("3.takeUnless { it > 5 }"));
        }

        @Test void takeUnlessNoMatch() {
            assertNull(run("10.takeUnless { it > 5 }"));
        }
    }

    // ============ 89. Result 高级 ============

    @Nested
    @DisplayName("Result 高级")
    class AdvancedResultTests {

        @Test void okUnwrap() {
            assertEquals(42, run("Ok(42).unwrap()"));
        }

        @Test void errUnwrapThrows() {
            assertThrows(Exception.class, () -> run("Err(\"fail\").unwrap()"));
        }

        @Test void unwrapOr() {
            assertEquals(0, run("Err(\"x\").unwrapOr(0)"));
        }

        @Test void unwrapOrOnOk() {
            assertEquals(42, run("Ok(42).unwrapOr(0)"));
        }

        @Test void errMapNoOp() {
            assertEquals(true, run("Err(\"x\").map { it * 2 } is Err"));
        }

        @Test void resultIsOk() {
            assertEquals(true, run("Ok(1).isOk"));
        }

        @Test void resultIsErr() {
            assertEquals(true, run("Err(\"x\").isErr"));
        }
    }

    // ============ 90. 运算符优先级 ============

    @Nested
    @DisplayName("运算符优先级")
    class OperatorPrecedenceTests {

        @Test void mulBeforeAdd() {
            assertEquals(14, run("2 + 3 * 4"));
        }

        @Test void parenOverride() {
            assertEquals(20, run("(2 + 3) * 4"));
        }

        @Test void andBeforeOr() {
            assertEquals(true, run("true || false && false"));
        }

        @Test void comparisonBeforeLogical() {
            assertEquals(true, run("1 < 2 && 3 > 2"));
        }

        @Test void unaryMinusPrecedence() {
            assertEquals(-2, run("-1 - 1"));
        }

        @Test void modPrecedence() {
            assertEquals(3, run("1 + 10 % 4"));
        }
    }

    // ============ 91. 混合场景 ============

    @Nested
    @DisplayName("混合场景")
    class MixedScenarioTests {

        @Test void classWithLambdaField() {
            assertEquals(10, run(
                    "class Processor(val transform: (Int) -> Int)\n" +
                    "val p = Processor({ it * 2 })\np.transform(5)"));
        }

        @Test void closureOverClassInstance() {
            assertEquals(42, run(
                    "class Box(val v: Int)\n" +
                    "val b = Box(42)\n" +
                    "val getter = { b.v }\ngetter()"));
        }

        @Test void functionReturnsClass() {
            assertEquals("Alice", run(
                    "class User(val name: String)\n" +
                    "fun createUser(n: String) = User(n)\n" +
                    "createUser(\"Alice\").name"));
        }

        @Test void listOfClassInstances() {
            assertEquals(3, run(
                    "class Item(val name: String)\n" +
                    "[Item(\"a\"), Item(\"b\"), Item(\"c\")].size()"));
        }

        @Test void enumInCollection() {
            assertEquals(3, run(
                    "enum Dir { UP, DOWN, LEFT }\n" +
                    "[Dir.UP, Dir.DOWN, Dir.LEFT].size()"));
        }

        @Test void dataClassInMap() {
            assertEquals("Alice", run(
                    "@data class User(val name: String, val age: Int)\n" +
                    "val users = #{\"admin\": User(\"Alice\", 30)}\n" +
                    "users[\"admin\"].name"));
        }

        @Test void recursiveDataStructure() {
            // 用 list 模拟简单链表: 1 + 2 + 3 = 6
            assertEquals(6, run(
                    "val list = [1, [2, [3, []]]]\nlist[0] + list[1][0] + list[1][1][0]"));
        }

        @Test void complexExpression() {
            assertEquals(true, run(
                    "val nums = [1, 2, 3, 4, 5]\n" +
                    "nums.filter { it > 2 }.size() == 3 && nums.sum() == 15"));
        }
    }

    // ============ C 风格 for 循环 ============

    @Nested
    @DisplayName("C 风格 for 循环")
    class CStyleForTests {

        @Test void basicCStyleFor() {
            assertEquals(10, run("var s = 0\nfor (var i = 0; i < 5; i += 1) { s = s + i }\ns"));
        }

        @Test void cStyleForWithBreak() {
            assertEquals(6, run("var s = 0\nfor (var i = 0; i < 10; i += 1) {\n  if (i > 3) break\n  s = s + i\n}\ns"));
        }

        @Test void cStyleForWithContinue() {
            assertEquals(8, run("var s = 0\nfor (var i = 0; i < 5; i += 1) {\n  if (i == 2) continue\n  s = s + i\n}\ns"));
        }

        @Test void cStyleForNested() {
            assertEquals(9, run("var s = 0\nfor (var i = 0; i < 3; i += 1) {\n  for (var j = 0; j < 3; j += 1) {\n    s = s + 1\n  }\n}\ns"));
        }

        @Test void cStyleForStepTwo() {
            assertEquals(20, run("var s = 0\nfor (var i = 0; i < 10; i += 2) { s = s + i }\ns"));
        }

        @Test void cStyleForDecrement() {
            assertEquals(15, run("var s = 0\nfor (var i = 5; i > 0; i -= 1) { s = s + i }\ns"));
        }

        @Test void cStyleForStringConcat() {
            assertEquals("012", run("var s = \"\"\nfor (var i = 0; i < 3; i += 1) { s = s + i }\ns"));
        }

        @Test void cStyleForZeroIterations() {
            assertEquals(0, run("var s = 0\nfor (var i = 0; i < 0; i += 1) { s = s + 1 }\ns"));
        }

        @Test void cStyleForSingleIteration() {
            assertEquals(10, run("var s = 0\nfor (var i = 0; i < 1; i += 1) { s = s + 10 }\ns"));
        }

        @Test void cStyleForBreakFirst() {
            assertEquals(42, run("var s = 0\nfor (var i = 0; i < 100; i += 1) { s = 42; break }\ns"));
        }

        @Test void cStyleForNegativeRange() {
            assertEquals(-6, run("var s = 0\nfor (var i = -3; i <= 0; i += 1) { s = s + i }\ns"));
        }

        @Test void cStyleForLargeStep() {
            assertEquals(1, run("var s = 0\nfor (var i = 0; i < 5; i += 100) { s = s + 1 }\ns"));
        }

        @Test void cStyleForVarMutation() {
            assertEquals(6, run("var s = 0\nfor (var i = 0; i < 10; i += 1) {\n  if (i == 3) { i = 7 }\n  s = s + 1\n}\ns"));
        }

        @Test void cStyleForMixedWithKotlinFor() {
            assertEquals(96, run("var s = 0\nfor (var i = 0; i < 3; i += 1) {\n  for (x in [10, 20]) {\n    s = s + x + i\n  }\n}\ns"));
        }

        @Test void cStyleForWithReturn() {
            assertEquals(5, run("fun findFirst(): Int {\n  for (var i = 0; i < 10; i += 1) {\n    if (i == 5) return i\n  }\n  return -1\n}\nfindFirst()"));
        }

        @Test void cStyleForCompoundUpdate() {
            assertEquals(7, run("var s = 0\nfor (var i = 1; i < 100; i *= 2) { s = s + 1 }\ns"));
        }
    }

    // ============ 尾调用消除 ============

    @Nested
    @DisplayName("尾调用消除")
    class TailCallEliminationTests {

        @Test void tailRecursiveSum() {
            assertEquals(50005000, run(
                    "fun sum(n: Int, acc: Int): Int {\n" +
                    "  if (n <= 0) return acc\n" +
                    "  return sum(n - 1, acc + n)\n" +
                    "}\n" +
                    "sum(10000, 0)"));
        }

        @Test void tailRecursiveFactorial() {
            assertEquals(3628800, run(
                    "fun factTail(n: Int, acc: Int): Int {\n" +
                    "  if (n <= 1) return acc\n" +
                    "  return factTail(n - 1, n * acc)\n" +
                    "}\n" +
                    "factTail(10, 1)"));
        }

        @Test void tailRecursiveCountdown() {
            assertEquals(0, run(
                    "fun countdown(n: Int): Int {\n" +
                    "  if (n <= 0) return 0\n" +
                    "  return countdown(n - 1)\n" +
                    "}\n" +
                    "countdown(50000)"));
        }
    }

    // ============ in / !in 运算符 ============

    @Nested
    @DisplayName("in/!in 运算符")
    class InOperatorTests {

        @Test void inRangeTrue() { assertEquals(true, run("5 in 1..10")); }
        @Test void inRangeFalse() { assertEquals(false, run("15 in 1..10")); }
        @Test void notInRangeTrue() { assertEquals(true, run("15 !in 1..10")); }
        @Test void notInRangeFalse() { assertEquals(false, run("5 !in 1..10")); }

        @Test void inRangeBoundaryStart() { assertEquals(true, run("1 in 1..10")); }
        @Test void inRangeBoundaryEnd() { assertEquals(true, run("10 in 1..10")); }

        @Test void inExclusiveRangeEnd() { assertEquals(false, run("10 in 1..<10")); }
        @Test void inExclusiveRangeLastInside() { assertEquals(true, run("9 in 1..<10")); }

        @Test void inList() { assertEquals(true, run("3 in [1, 2, 3, 4]")); }
        @Test void notInList() { assertEquals(true, run("5 !in [1, 2, 3]")); }
        @Test void inRangeNegative() { assertEquals(true, run("-3 in -5..0")); }

        @Test void inIfCondition() {
            assertEquals("yes", run("val x = 5\nif (x in 1..10) \"yes\" else \"no\""));
        }

        @Test void inWhenNoSubject() {
            assertEquals("afternoon", run(
                    "val h = 14\nwhen {\n" +
                    "  h in 0..<6 -> \"night\"\n" +
                    "  h in 6..<12 -> \"morning\"\n" +
                    "  h in 12..<18 -> \"afternoon\"\n" +
                    "  else -> \"evening\"\n" +
                    "}"));
        }
    }

    // ============ Object/Any 类型动态运算 ============

    @Nested
    @DisplayName("Object/Any 类型动态运算")
    class DynamicBinaryOpTests {

        @Test void bitwiseAndOnAnyType() { assertEquals(3, run("val a: Any = 7\nval b: Any = 3\na & b")); }
        @Test void bitwiseOrOnAnyType() { assertEquals(7, run("val a: Any = 5\nval b: Any = 3\na | b")); }
        @Test void bitwiseXorOnAnyType() { assertEquals(5, run("val a: Any = 6\nval b: Any = 3\na ^ b")); }
        @Test void shiftLeftOnAnyType() { assertEquals(8, run("val a: Any = 1\nval b: Any = 3\na << b")); }
        @Test void shiftRightOnAnyType() { assertEquals(2, run("val a: Any = 8\nval b: Any = 2\na >> b")); }
        @Test void unsignedShiftRightOnAnyType() { assertEquals(15, run("val a: Any = -1\nval b: Any = 28\na >>> b")); }

        @Test void bitwiseAndDynamic() {
            assertEquals(3, run("fun get(x: Int): Any = x\nget(7) & get(3)"));
        }

        @Test void bitwiseOrDynamic() {
            assertEquals(7, run("fun get(x: Int): Any = x\nget(5) | get(3)"));
        }

        @Test void addDynamic() {
            assertEquals(7, run("fun get(x: Int): Any = x\nget(3) + get(4)"));
        }

        @Test void mulDynamic() {
            assertEquals(42, run("fun get(x: Int): Any = x\nget(6) * get(7)"));
        }

        @Test void stringAddDynamic() {
            assertEquals("hello world", run("fun get(x: Any): Any = x\nget(\"hello\") + get(\" world\")"));
        }

        @Test void longShiftLeft() { assertEquals(8L, run("val a = 1L\na << 3")); }
        @Test void longShiftRight() { assertEquals(16L, run("val a = 64L\na >> 2")); }
    }

    // ============ 深层嵌套闭包 ============

    @Nested
    @DisplayName("深层嵌套闭包")
    class DeepNestedClosureTests {

        @Test void threeLayerNestedCapture() {
            assertEquals(6, run(
                    "fun outer(): Int {\n" +
                    "  val a = 1\n" +
                    "  fun middle(): Int {\n" +
                    "    val b = 2\n" +
                    "    fun inner(): Int {\n" +
                    "      val c = 3\n" +
                    "      return a + b + c\n" +
                    "    }\n" +
                    "    return inner()\n" +
                    "  }\n" +
                    "  return middle()\n" +
                    "}\n" +
                    "outer()"));
        }

        @Test void nestedClosureMutation() {
            assertEquals(10, run(
                    "fun counter(): () -> Int {\n" +
                    "  var count = 0\n" +
                    "  return { count = count + 1; count }\n" +
                    "}\n" +
                    "val inc = counter()\n" +
                    "var sum = 0\n" +
                    "for (i in 1..4) { sum = sum + inc() }\n" +
                    "sum"));
        }

        @Test void closureOverNestedFunction() {
            assertEquals(15, run(
                    "fun make(): (Int) -> Int {\n" +
                    "  var total = 0\n" +
                    "  fun add(n: Int): Int {\n" +
                    "    total = total + n\n" +
                    "    return total\n" +
                    "  }\n" +
                    "  return ::add\n" +
                    "}\n" +
                    "val adder = make()\n" +
                    "adder(5)\nadder(10)"));
        }
    }

    // ============ 数组操作 ============

    @Nested
    @DisplayName("数组操作")
    class ArrayTests {

        @Test void intArrayBasic() {
            assertEquals(6, run(
                    "val arr = IntArray(3)\narr[0] = 1\narr[1] = 2\narr[2] = 3\narr[0] + arr[1] + arr[2]"));
        }

        @Test void intArraySize() {
            assertEquals(5, run("val arr = IntArray(5)\narr.size"));
        }

        @Test void intArrayInLoop() {
            assertEquals(10, run(
                    "val arr = IntArray(5)\n" +
                    "for (var i = 0; i < 5; i += 1) { arr[i] = i }\n" +
                    "var sum = 0\n" +
                    "for (var i = 0; i < 5; i += 1) { sum = sum + arr[i] }\n" +
                    "sum"));
        }

        @Test void arrayOfCreation() {
            assertEquals(3, run("val arr = arrayOf(10, 20, 30)\narr.size"));
        }
    }

    // ============ 属性访问器 ============

    @Nested
    @DisplayName("属性访问器")
    class PropertyAccessorTests {

        @Test void customGetter() {
            assertEquals("HELLO", run(
                    "class Greeter(val name: String) {\n" +
                    "  val upper: String\n" +
                    "    get() = name.toUpperCase()\n" +
                    "}\n" +
                    "Greeter(\"hello\").upper"));
        }

        @Test void customGetterAndSetter() {
            assertEquals(200, run(
                    "class Box(var _value: Int) {\n" +
                    "  var value: Int\n" +
                    "    get() = _value * 2\n" +
                    "    set(v) { _value = v }\n" +
                    "}\n" +
                    "val b = Box(50)\nb.value = 100\nb.value"));
        }
    }

    // ============ 导入别名 ============

    @Nested
    @DisplayName("导入别名")
    class ImportAliasTests {

        @Test void importJavaClass() {
            assertEquals(true, run(
                    "import java.util.ArrayList\n" +
                    "val list = ArrayList()\n" +
                    "list.add(1)\n" +
                    "list.size() == 1"));
        }

        @Test void importWithAlias() {
            assertEquals(true, run(
                    "import java.util.HashMap as JMap\n" +
                    "val m = JMap()\n" +
                    "m.put(\"a\", 1)\n" +
                    "m.size() == 1"));
        }
    }

    // ============ use 语句 ============

    @Nested
    @DisplayName("use 语句")
    class UseStatementTests {

        @Test void useAutoClose() {
            assertEquals("closed", run(
                    "class Resource {\n" +
                    "  var status = \"open\"\n" +
                    "  fun close() { status = \"closed\" }\n" +
                    "  fun read(): String = \"data\"\n" +
                    "}\n" +
                    "val r = Resource()\n" +
                    "use (val res = r) {\n" +
                    "  res.read()\n" +
                    "}\n" +
                    "r.status"));
        }
    }

    // ============ 条件绑定 ============

    @Nested
    @DisplayName("条件绑定")
    class ConditionalBindingTests {

        @Test void ifLetNonNull() {
            assertEquals(10, run(
                    "val x: Int? = 5\n" +
                    "if (val v = x) { v * 2 } else { 0 }"));
        }

        @Test void ifLetNull() {
            assertEquals(0, run(
                    "val x: Int? = null\n" +
                    "if (val v = x) { v * 2 } else { 0 }"));
        }
    }

    // ============ 字符串高级 ============

    @Nested
    @DisplayName("字符串高级操作")
    class StringAdvancedTests {

        @Test void padStart() {
            assertEquals("005", run("\"5\".padStart(3, '0')"));
        }

        @Test void padEnd() {
            assertEquals("5  ", run("\"5\".padEnd(3, ' ')"));
        }

        @Test void repeat() {
            assertEquals("abcabc", run("\"abc\".repeat(2)"));
        }

        @Test void reversed() {
            assertEquals("cba", run("\"abc\".reversed()"));
        }

        @Test void drop() {
            assertEquals("lo", run("\"hello\".drop(3)"));
        }

        @Test void take() {
            assertEquals("hel", run("\"hello\".take(3)"));
        }

        @Test void lines() {
            assertEquals(3, run("\"a\\nb\\nc\".lines().size()"));
        }
    }

    // ============ Map 高级操作 ============

    @Nested
    @DisplayName("Map 高级操作")
    class MapAdvancedTests {

        @Test void mapFilter() {
            assertEquals(1, run("val m = #{\"a\": 1, \"b\": 2, \"c\": 3}\nm.filter { k, v -> v > 2 }.size()"));
        }

        @Test void mapMap() {
            assertEquals(true, run("val m = #{\"a\": 1, \"b\": 2}\nm.map { k, v -> v * 10 }.contains(10)"));
        }

        @Test void mapForEach() {
            assertEquals(6, run("val m = #{\"a\": 1, \"b\": 2, \"c\": 3}\nvar s = 0\nm.forEach { k, v -> s = s + v }\ns"));
        }

        @Test void mapMerge() {
            assertEquals(3, run("val a = #{\"x\": 1}\nval b = #{\"y\": 2, \"z\": 3}\nval c = a + b\nc.size()"));
        }

        @Test void mapRemove() {
            assertEquals(1, run("val m = #{\"a\": 1, \"b\": 2}\nm.remove(\"a\")\nm.size()"));
        }
    }

    // ============ Set 高级操作 ============

    @Nested
    @DisplayName("Set 高级操作")
    class SetAdvancedTests {

        @Test void setContains() { assertEquals(true, run("val s = setOf(1, 2, 3)\ns.contains(2)")); }
        @Test void setSize() { assertEquals(3, run("val s = setOf(1, 2, 3)\ns.size()")); }
        @Test void setNoDuplicates() { assertEquals(3, run("val s = setOf(1, 2, 2, 3, 3)\ns.size()")); }

        @Test void setUnion() {
            assertEquals(true, run("val a = setOf(1, 2)\nval b = setOf(2, 3)\nval c = a.union(b)\nc.contains(3) && c.size() == 3"));
        }

        @Test void setIntersect() {
            assertEquals(2, run("val a = setOf(1, 2, 3)\nval b = setOf(2, 3, 4)\na.intersect(b).size()"));
        }
    }

    // ============ Error Propagation (? operator) ============

    @Nested
    @DisplayName("错误传播运算符 ?")
    class ErrorPropagationTests {

        @Test void okPropagation() {
            assertEquals(10, run(
                    "fun double(x: Int): Result = Ok(x * 2)\n" +
                    "fun chain(): Result {\n" +
                    "  val v = double(5)?\n" +
                    "  return Ok(v)\n" +
                    "}\n" +
                    "chain().value"));
        }

        @Test void errPropagation() {
            assertEquals(true, run(
                    "fun fail(): Result = Err(\"oops\")\n" +
                    "fun chain(): Result {\n" +
                    "  val v = fail()?\n" +
                    "  return Ok(v)\n" +
                    "}\n" +
                    "chain() is Err"));
        }
    }

    // ============ 类型别名 ============

    @Nested
    @DisplayName("类型别名")
    class TypeAliasTests {

        @Test void basicTypeAlias() {
            assertEquals(3, run("typealias IntList = List\nval xs: IntList = [1, 2, 3]\nxs.size()"));
        }
    }

    // ============ 密封类高级 ============

    @Nested
    @DisplayName("密封类高级")
    class SealedClassAdvancedTests {

        @Test void sealedClassWhenExhaustive() {
            assertEquals("circle", run(
                    "sealed class Shape\n" +
                    "class Circle : Shape()\n" +
                    "class Square : Shape()\n" +
                    "val s: Shape = Circle()\n" +
                    "when (s) {\n" +
                    "  is Circle -> \"circle\"\n" +
                    "  is Square -> \"square\"\n" +
                    "}"));
        }

        @Test void sealedClassWithData() {
            assertEquals(5, run(
                    "sealed class Expr\n" +
                    "@data class Num(val value: Int) : Expr()\n" +
                    "@data class Add(val left: Expr, val right: Expr) : Expr()\n" +
                    "fun eval(e: Expr): Int = when (e) {\n" +
                    "  is Num -> e.value\n" +
                    "  is Add -> eval(e.left) + eval(e.right)\n" +
                    "  else -> 0\n" +
                    "}\n" +
                    "eval(Add(Num(2), Num(3)))"));
        }
    }
}
