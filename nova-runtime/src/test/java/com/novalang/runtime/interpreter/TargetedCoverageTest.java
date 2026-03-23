package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 针对 JaCoCo 分析发现的未覆盖方法的定向覆盖测试。
 * 所有测试通过 eval() 执行 Nova 代码。
 */
class TargetedCoverageTest {

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
    // 1. Reflect API — classOf() (MemberResolver.buildHirClassInfo)
    // ================================================================

    @Nested
    @DisplayName("Reflect API — classOf()")
    class ReflectClassOfTests {

        @Test
        @DisplayName("classOf(ClassName) 返回 ClassInfo，name 正确")
        void testClassOfByClassName() {
            eval("class Person(val name: String, var age: Int) { fun greet() = \"Hi\" }");
            NovaValue info = eval("classOf(Person)");
            assertNotNull(info);
            assertEquals("Person", eval("classOf(Person).name").asString());
        }

        @Test
        @DisplayName("classOf(instance) 也能工作")
        void testClassOfByInstance() {
            eval("class Dog(val breed: String)");
            eval("val d = Dog(\"Husky\")");
            assertEquals("Dog", eval("classOf(d).name").asString());
        }

        @Test
        @DisplayName("fields 列表包含正确数量")
        void testFieldsCount() {
            eval("class Point(val x: Int, val y: Int, val z: Int)");
            assertEquals(3, eval("classOf(Point).fields.size()").asInt());
        }

        @Test
        @DisplayName("fields[0].name 返回正确字段名")
        void testFieldName() {
            eval("class Item(val title: String, val price: Double)");
            assertEquals("title", eval("classOf(Item).fields[0].name").asString());
        }

        @Test
        @DisplayName("fields[1].name 返回第二个字段名")
        void testSecondFieldName() {
            eval("class Item(val title: String, val price: Double)");
            assertEquals("price", eval("classOf(Item).fields[1].name").asString());
        }

        @Test
        @DisplayName("field 的 type 返回字符串")
        void testFieldType() {
            eval("class Typed(val count: Int)");
            NovaValue fieldType = eval("classOf(Typed).fields[0].type");
            assertNotNull(fieldType);
            assertTrue(fieldType.isString());
        }

        @Test
        @DisplayName("methods 列表包含正确方法")
        void testMethodsList() {
            eval("class Calculator {\n"
                + "    fun add(a: Int, b: Int) = a + b\n"
                + "    fun multiply(a: Int, b: Int) = a * b\n"
                + "}");
            NovaValue methodCount = eval("classOf(Calculator).methods.size()");
            assertTrue(methodCount.asInt() >= 2);
        }

        @Test
        @DisplayName("methods 中可找到指定方法名")
        void testMethodName() {
            eval("class Greeter { fun sayHello() = \"hello\" }");
            NovaValue methodName = eval("classOf(Greeter).methods.find { it.name == \"sayHello\" }.name");
            assertEquals("sayHello", methodName.asString());
        }

        @Test
        @DisplayName("private 字段可见性正确反映")
        void testFieldVisibility() {
            eval("class Encapsulated(private val secret: Int, val visible: String)");
            NovaValue vis = eval("classOf(Encapsulated).fields[0].visibility");
            assertNotNull(vis);
            assertEquals("private", vis.asString());
        }

        @Test
        @DisplayName("public 字段可见性正确反映")
        void testPublicFieldVisibility() {
            eval("class Open(val data: Int)");
            NovaValue vis = eval("classOf(Open).fields[0].visibility");
            assertEquals("public", vis.asString());
        }

        @Test
        @DisplayName("annotations 列表")
        void testAnnotationsList() {
            eval("annotation class Tag");
            eval("@Tag class Annotated");
            NovaValue count = eval("classOf(Annotated).annotations.size()");
            assertTrue(count.asInt() >= 1);
        }

        @Test
        @DisplayName("无注解的类 annotations 为空列表")
        void testNoAnnotations() {
            eval("class Plain(val x: Int)");
            NovaValue count = eval("classOf(Plain).annotations.size()");
            assertEquals(0, count.asInt());
        }
    }

    // ================================================================
    // 2. Enum entry members (MemberResolver.resolveEnumEntryMember)
    // ================================================================

    @Nested
    @DisplayName("Enum entry members")
    class EnumEntryMemberTests {

        @Test
        @DisplayName("enum entry 的 name 属性")
        void testEnumEntryName() {
            eval("enum class Color { RED, GREEN, BLUE }");
            assertEquals("RED", eval("Color.RED.name").asString());
        }

        @Test
        @DisplayName("enum entry 的 ordinal 属性 — 第一个")
        void testEnumEntryOrdinalFirst() {
            eval("enum class Direction { NORTH, SOUTH, EAST, WEST }");
            assertEquals(0, eval("Direction.NORTH.ordinal").asInt());
        }

        @Test
        @DisplayName("enum entry 的 ordinal 属性 — 后续")
        void testEnumEntryOrdinalSubsequent() {
            eval("enum class Direction { NORTH, SOUTH, EAST, WEST }");
            assertEquals(1, eval("Direction.SOUTH.ordinal").asInt());
            assertEquals(2, eval("Direction.EAST.ordinal").asInt());
            assertEquals(3, eval("Direction.WEST.ordinal").asInt());
        }

        @Test
        @DisplayName("每个 entry 的 name 各不相同")
        void testEnumEntryNamesDistinct() {
            eval("enum class Suit { HEARTS, DIAMONDS, CLUBS, SPADES }");
            assertEquals("HEARTS", eval("Suit.HEARTS.name").asString());
            assertEquals("DIAMONDS", eval("Suit.DIAMONDS.name").asString());
            assertEquals("CLUBS", eval("Suit.CLUBS.name").asString());
            assertEquals("SPADES", eval("Suit.SPADES.name").asString());
        }

        @Test
        @DisplayName("enum with custom field")
        void testEnumWithCustomField() {
            eval("enum class Planet(val mass: Double) { EARTH(5.97), MARS(0.64) }");
            assertEquals(5.97, eval("Planet.EARTH.mass").asDouble(), 0.01);
            assertEquals(0.64, eval("Planet.MARS.mass").asDouble(), 0.01);
        }

        @Test
        @DisplayName("enum entry 带方法")
        void testEnumEntryWithMethod() {
            eval("enum class Shape {\n"
                + "    CIRCLE, SQUARE;\n"
                + "    fun label() = name\n"
                + "}");
            assertEquals("CIRCLE", eval("Shape.CIRCLE.label()").asString());
        }
    }

    // ================================================================
    // 3. Implicit this on built-in types (resolveBuiltinImplicitThis)
    // ================================================================

    @Nested
    @DisplayName("Implicit this — built-in types")
    class ImplicitThisTests {

        @Test
        @DisplayName("String.run 中隐式 this.length")
        void testStringRunImplicitLength() {
            NovaValue result = eval("\"hello\".run { length }");
            assertEquals(5, result.asInt());
        }

        @Test
        @DisplayName("String.let 中 it 访问")
        void testStringLetAccess() {
            NovaValue result = eval("\"hello\".let { it.toUpperCase() }");
            assertEquals("HELLO", result.asString());
        }

        @Test
        @DisplayName("Int.run 中 toString()")
        void testIntRunToString() {
            NovaValue result = eval("42.run { toString() }");
            assertEquals("42", result.asString());
        }

        @Test
        @DisplayName("List.run 中 size()")
        void testListRunSize() {
            NovaValue result = eval("[1,2,3].run { size() }");
            assertEquals(3, result.asInt());
        }

        @Test
        @DisplayName("String.run 中调用 toUpperCase()")
        void testStringRunUpperCase() {
            NovaValue result = eval("\"world\".run { toUpperCase() }");
            assertEquals("WORLD", result.asString());
        }
    }

    // ================================================================
    // 4. Method references (StaticMethodDispatcher.handleBindMethod)
    // ================================================================

    @Nested
    @DisplayName("Method references — ::")
    class MethodReferenceTests {

        @Test
        @DisplayName("全局函数引用 ::functionName")
        void testGlobalFunctionRef() {
            eval("fun double(n: Int) = n * 2");
            eval("val fn = ::double");
            assertEquals(10, eval("fn(5)").asInt());
        }

        @Test
        @DisplayName("函数引用传给 map")
        void testFunctionRefInMap() {
            eval("fun triple(n: Int) = n * 3");
            NovaValue result = eval("[1,2,3].map(::triple)");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.get(0).asInt());
            assertEquals(6, list.get(1).asInt());
            assertEquals(9, list.get(2).asInt());
        }

        @Test
        @DisplayName("实例方法引用")
        void testInstanceMethodRef() {
            eval("class Counter(var count: Int) { fun inc() { count = count + 1 } }");
            eval("val c = Counter(0)");
            eval("val ref = c::inc");
            eval("ref()");
            assertEquals(1, eval("c.count").asInt());
        }

        @Test
        @DisplayName("函数引用传给 filter")
        void testFunctionRefInFilter() {
            eval("fun isEven(n: Int) = n % 2 == 0");
            NovaValue result = eval("[1,2,3,4,5,6].filter(::isEven)");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(2, list.get(0).asInt());
        }
    }

    // ================================================================
    // 5. Named parameters cross-REPL (reorderNamedArgs)
    // ================================================================

    @Nested
    @DisplayName("Named parameters cross-REPL")
    class NamedParameterTests {

        @Test
        @DisplayName("跨 eval 调用命名参数")
        void testNamedArgsCrossRepl() {
            eval("fun greet(name: String, greeting: String = \"Hello\") = \"$greeting, $name\"");
            assertEquals("Hello, World", eval("greet(name = \"World\")").asString());
        }

        @Test
        @DisplayName("命名参数打乱顺序")
        void testNamedArgsReordered() {
            eval("fun greet(name: String, greeting: String = \"Hello\") = \"$greeting, $name\"");
            assertEquals("Hi, Alice", eval("greet(greeting = \"Hi\", name = \"Alice\")").asString());
        }

        @Test
        @DisplayName("命名参数与位置参数混用")
        void testNamedAndPositionalArgs() {
            eval("fun format(prefix: String, value: Int, suffix: String = \"!\") = \"$prefix$value$suffix\"");
            assertEquals("Count:42!", eval("format(\"Count:\", value = 42)").asString());
        }

        @Test
        @DisplayName("多默认参数只覆盖一个")
        void testMultipleDefaultsOverrideOne() {
            eval("fun config(host: String = \"localhost\", port: Int = 8080, debug: Boolean = false) = \"$host:$port:$debug\"");
            assertEquals("localhost:9090:false", eval("config(port = 9090)").asString());
        }
    }

    // ================================================================
    // 6. Index operator overloading (MemberResolver.performIndex)
    // ================================================================

    @Nested
    @DisplayName("Index operator overloading")
    class IndexOperatorTests {

        @Test
        @DisplayName("自定义类通过 get 方法支持 [] 操作")
        void testCustomIndexGet() {
            eval("class Matrix(val data: List<List<Int>>) {\n"
                + "    fun get(row: Int): List<Int> = data[row]\n"
                + "}");
            eval("val m = Matrix([[1,2],[3,4]])");
            NovaValue row = eval("m[0]");
            assertTrue(row instanceof NovaList);
            assertEquals(1, ((NovaList) row).get(0).asInt());
        }

        @Test
        @DisplayName("自定义类通过 set 方法支持 [] 赋值")
        void testCustomIndexSet() {
            eval("class MutableVec(val items: List<Int>) {\n"
                + "    var lastSet: Int = -1\n"
                + "    fun get(i: Int) = items[i]\n"
                + "    fun set(i: Int, v: Int) { lastSet = v }\n"
                + "}");
            eval("val v = MutableVec([10,20,30])");
            eval("v[1] = 99");
            assertEquals(99, eval("v.lastSet").asInt());
        }

        @Test
        @DisplayName("List 下标访问")
        void testListIndexAccess() {
            assertEquals(20, eval("[10,20,30][1]").asInt());
        }

        @Test
        @DisplayName("Map 下标访问")
        void testMapIndexAccess() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertEquals(1, eval("m[\"a\"]").asInt());
        }

        @Test
        @DisplayName("String 下标访问返回 Char")
        void testStringIndexAccess() {
            NovaValue ch = eval("\"hello\"[0]");
            assertNotNull(ch);
            assertEquals('h', (char) ch.toJavaValue());
        }
    }

    // ================================================================
    // 7. Visibility checks (MirCallDispatcher.checkMethodVisibility)
    // ================================================================

    @Nested
    @DisplayName("Visibility checks")
    class VisibilityCheckTests {

        @Test
        @DisplayName("private 方法可从类内部调用")
        void testPrivateMethodFromInside() {
            eval("class Secret {\n"
                + "    private fun hidden() = 42\n"
                + "    fun exposed() = hidden()\n"
                + "}");
            assertEquals(42, eval("Secret().exposed()").asInt());
        }

        @Test
        @DisplayName("private 方法不能从外部访问")
        void testPrivateMethodFromOutside() {
            eval("class Secret {\n"
                + "    private fun hidden() = 42\n"
                + "    fun exposed() = hidden()\n"
                + "}");
            assertThrows(NovaRuntimeException.class, () -> eval("Secret().hidden()"));
        }

        @Test
        @DisplayName("private 字段不能从外部访问")
        void testPrivateFieldFromOutside() {
            eval("class Vault(private val code: Int)");
            assertThrows(NovaRuntimeException.class, () -> eval("Vault(123).code"));
        }

        @Test
        @DisplayName("public 方法可从外部调用")
        void testPublicMethodFromOutside() {
            eval("class Open {\n"
                + "    fun hello() = \"world\"\n"
                + "}");
            assertEquals("world", eval("Open().hello()").asString());
        }

        @Test
        @DisplayName("混合可见性：public 方法调用 private 方法")
        void testMixedVisibility() {
            eval("class Service {\n"
                + "    private fun compute() = 100\n"
                + "    fun getResult() = compute() + 1\n"
                + "}");
            assertEquals(101, eval("Service().getResult()").asInt());
        }
    }

    // ================================================================
    // 8. Array builtin (Builtins lambda)
    // ================================================================

    @Nested
    @DisplayName("Array builtin")
    class ArrayBuiltinTests {

        @Test
        @DisplayName("Array(size) { init } 创建数组")
        void testArrayCreate() {
            eval("val arr = Array(5) { it * 2 }");
            assertEquals(0, eval("arr[0]").asInt());
            assertEquals(8, eval("arr[4]").asInt());
        }

        @Test
        @DisplayName("Array size 属性")
        void testArraySize() {
            assertEquals(5, eval("Array(5) { it }.size").asInt());
        }

        @Test
        @DisplayName("Array 修改元素")
        void testArraySet() {
            eval("val arr = Array(3) { 0 }");
            eval("arr[1] = 42");
            assertEquals(42, eval("arr[1]").asInt());
        }

        @Test
        @DisplayName("Array 越界抛异常")
        void testArrayOutOfBounds() {
            eval("val arr = Array(3) { 0 }");
            assertThrows(Exception.class, () -> eval("arr[10]"));
        }

        @Test
        @DisplayName("Array toList 转换")
        void testArrayToList() {
            NovaValue result = eval("Array(3) { it + 1 }.toList()");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(1, list.get(0).asInt());
            assertEquals(2, list.get(1).asInt());
            assertEquals(3, list.get(2).asInt());
        }

        @Test
        @DisplayName("Array 用复杂表达式初始化")
        void testArrayComplexInit() {
            eval("val arr = Array(4) { if (it % 2 == 0) \"even\" else \"odd\" }");
            assertEquals("even", eval("arr[0]").asString());
            assertEquals("odd", eval("arr[1]").asString());
        }
    }

    // ================================================================
    // 9. Pipe operator (handlePipeCall)
    // ================================================================

    @Nested
    @DisplayName("Pipe operator |>")
    class PipeOperatorTests {

        @Test
        @DisplayName("简单管道")
        void testSimplePipe() {
            eval("fun double(n: Int) = n * 2");
            assertEquals(10, eval("5 |> double").asInt());
        }

        @Test
        @DisplayName("链式管道")
        void testChainedPipe() {
            eval("fun double(n: Int) = n * 2");
            eval("fun addOne(n: Int) = n + 1");
            assertEquals(11, eval("5 |> double |> addOne").asInt());
        }

        @Test
        @DisplayName("管道到 lambda")
        void testPipeToLambda() {
            eval("val square = { x -> x * x }");
            assertEquals(25, eval("5 |> square").asInt());
        }

        @Test
        @DisplayName("管道带额外参数")
        void testPipeWithArgs() {
            eval("fun add(x: Int, y: Int) = x + y");
            assertEquals(15, eval("10 |> add(5)").asInt());
        }

        @Test
        @DisplayName("复杂管道链")
        void testComplexPipeChain() {
            eval("fun inc(n: Int) = n + 1");
            eval("fun double(n: Int) = n * 2");
            eval("fun negate(n: Int) = -n");
            // (1 + 1) * 2 = 4, -4
            assertEquals(-4, eval("1 |> inc |> double |> negate").asInt());
        }
    }

    // ================================================================
    // 10. isCallable builtin
    // ================================================================

    @Nested
    @DisplayName("isCallable builtin")
    class IsCallableTests {

        @Test
        @DisplayName("lambda 是 callable")
        void testLambdaIsCallable() {
            assertTrue(eval("isCallable({ 1 })").asBool());
        }

        @Test
        @DisplayName("Int 不是 callable")
        void testIntNotCallable() {
            assertFalse(eval("isCallable(42)").asBool());
        }

        @Test
        @DisplayName("String 不是 callable")
        void testStringNotCallable() {
            assertFalse(eval("isCallable(\"hello\")").asBool());
        }

        @Test
        @DisplayName("函数变量是 callable")
        void testFunctionVarIsCallable() {
            eval("fun identity(x: Int) = x");
            eval("val f = ::identity");
            assertTrue(eval("isCallable(f)").asBool());
        }

        @Test
        @DisplayName("null 不是 callable")
        void testNullNotCallable() {
            assertFalse(eval("isCallable(null)").asBool());
        }

        @Test
        @DisplayName("Boolean 不是 callable")
        void testBoolNotCallable() {
            assertFalse(eval("isCallable(true)").asBool());
        }

        @Test
        @DisplayName("List 不是 callable")
        void testListNotCallable() {
            assertFalse(eval("isCallable([1,2,3])").asBool());
        }

        @Test
        @DisplayName("命名函数引用是 callable")
        void testNamedFunctionRefIsCallable() {
            eval("fun myFunc(x: Int) = x * 2");
            assertTrue(eval("isCallable(::myFunc)").asBool());
        }
    }
}
