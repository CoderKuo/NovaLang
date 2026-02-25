package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OOP 语法集成测试：类、接口、枚举、对象、继承、运算符重载。
 * 每个测试同时执行解释器和编译器路径，验证结果一致。
 */
@DisplayName("OOP 语法集成测试")
class OopSyntaxIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private NovaValue interp(String code) {
        return new Interpreter().eval(code, "test.nova");
    }

    private Object compile(String code) throws Exception {
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

    private void dual(String interpCode, String compileCode, Object expected) throws Exception {
        NovaValue ir = interp(interpCode);
        Object cr = compile(compileCode);
        if (expected instanceof Integer) {
            assertEquals(expected, ir.asInt(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Boolean) {
            assertEquals(expected, ir.asBoolean(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else {
            assertEquals(String.valueOf(expected), ir.asString(), "解释器");
            assertEquals(String.valueOf(expected), String.valueOf(cr), "编译器");
        }
    }

    // ============ 基本类 ============

    @Nested
    @DisplayName("类声明与实例化")
    class ClassBasicTests {

        @Test void testSimpleClass() throws Exception {
            String pre = "class Point(val x: Int, val y: Int)\n";
            dual(pre + "val p = Point(3, 4)\np.x + p.y",
                 pre + wrap("val p = Point(3, 4)\n    return p.x + p.y"), 7);
        }

        @Test void testClassMethod() throws Exception {
            String pre = "class Calc {\n  fun add(a: Int, b: Int) = a + b\n}\n";
            dual(pre + "Calc().add(3, 4)",
                 pre + wrap("return Calc().add(3, 4)"), 7);
        }

        @Test void testClassFieldAccess() throws Exception {
            String pre = "class Box(var value: Int)\n";
            dual(pre + "val b = Box(10)\nb.value = 20\nb.value",
                 pre + wrap("val b = Box(10)\n    b.value = 20\n    return b.value"), 20);
        }

        @Test void testClassBodyField() throws Exception {
            String pre = "class Counter {\n  var count: Int = 0\n  fun inc() { count = count + 1 }\n  fun get() = count\n}\n";
            dual(pre + "val c = Counter()\nc.inc()\nc.inc()\nc.inc()\nc.get()",
                 pre + wrap("val c = Counter()\n    c.inc()\n    c.inc()\n    c.inc()\n    return c.get()"), 3);
        }

        @Test void testThisAccess() throws Exception {
            String pre = "class Greeter(val name: String) {\n  fun greet() = \"Hi \" + this.name\n}\n";
            dual(pre + "Greeter(\"Nova\").greet()",
                 pre + wrap("return Greeter(\"Nova\").greet()"), "Hi Nova");
        }

        @Test void testMultipleConstructorParams() throws Exception {
            String pre = "class Vec3(val x: Int, val y: Int, val z: Int) {\n  fun sum() = x + y + z\n}\n";
            dual(pre + "Vec3(1, 2, 3).sum()",
                 pre + wrap("return Vec3(1, 2, 3).sum()"), 6);
        }
    }

    // ============ 继承 ============

    @Nested
    @DisplayName("继承")
    class InheritanceTests {

        @Test void testBasicInheritance() throws Exception {
            String pre = "open class Animal(val name: String) {\n  fun speak() = name + \" speaks\"\n}\n" +
                         "class Dog(name: String) : Animal(name) {\n  fun bark() = name + \" barks\"\n}\n";
            dual(pre + "Dog(\"Rex\").bark()",
                 pre + wrap("return Dog(\"Rex\").bark()"), "Rex barks");
        }

        @Test void testInheritedMethod() throws Exception {
            String pre = "open class Base {\n  fun hello() = \"hello\"\n}\n" +
                         "class Child : Base()\n";
            dual(pre + "Child().hello()",
                 pre + wrap("return Child().hello()"), "hello");
        }

        @Test void testMethodOverride() throws Exception {
            String pre = "open class Shape {\n  open fun area(): Int = 0\n}\n" +
                         "class Square(val side: Int) : Shape() {\n  override fun area() = side * side\n}\n";
            dual(pre + "Square(5).area()",
                 pre + wrap("return Square(5).area()"), 25);
        }
    }

    // ============ 接口 ============

    @Nested
    @DisplayName("接口")
    class InterfaceTests {

        @Test void testInterfaceImplementation() throws Exception {
            String pre = "interface Describable {\n  fun describe(): String\n}\n" +
                         "class Item(val name: String) : Describable {\n  override fun describe() = \"Item: \" + name\n}\n";
            dual(pre + "Item(\"Book\").describe()",
                 pre + wrap("return Item(\"Book\").describe()"), "Item: Book");
        }

        @Test void testInterfaceDefaultMethod() throws Exception {
            String pre = "interface Printable {\n  fun label(): String\n  fun print() = \"[\" + label() + \"]\"\n}\n" +
                         "class Tag(val text: String) : Printable {\n  override fun label() = text\n}\n";
            dual(pre + "Tag(\"hello\").print()",
                 pre + wrap("return Tag(\"hello\").print()"), "[hello]");
        }

        @Test void testMultipleInterfaces() throws Exception {
            String pre = "interface HasName {\n  fun getName(): String\n}\n" +
                         "interface HasAge {\n  fun getAge(): Int\n}\n" +
                         "class Person(val n: String, val a: Int) : HasName, HasAge {\n" +
                         "  override fun getName() = n\n  override fun getAge() = a\n}\n";
            dual(pre + "val p = Person(\"Alice\", 30)\np.getName() + \":\" + p.getAge()",
                 pre + wrap("val p = Person(\"Alice\", 30)\n    return p.getName() + \":\" + p.getAge()"), "Alice:30");
        }
    }

    // ============ 枚举 ============

    @Nested
    @DisplayName("枚举")
    class EnumTests {

        @Test void testBasicEnum() throws Exception {
            String pre = "enum class Color { RED, GREEN, BLUE }\n";
            dual(pre + "Color.RED.name()",
                 pre + wrap("return Color.RED.name()"), "RED");
        }

        @Test void testEnumOrdinal() throws Exception {
            String pre = "enum class Dir { NORTH, SOUTH, EAST, WEST }\n";
            dual(pre + "Dir.EAST.ordinal()",
                 pre + wrap("return Dir.EAST.ordinal()"), 2);
        }

        @Test void testEnumWithParams() throws Exception {
            String pre = "enum class Planet(val mass: Int) {\n  EARTH(100),\n  MARS(38)\n}\n";
            dual(pre + "Planet.MARS.mass",
                 pre + wrap("return Planet.MARS.mass"), 38);
        }

        @Test void testEnumMethod() throws Exception {
            String pre = "enum class Op {\n  ADD, SUB;\n  fun apply(a: Int, b: Int): Int {\n" +
                         "    return when (this) {\n      Op.ADD -> a + b\n      Op.SUB -> a - b\n      else -> 0\n    }\n  }\n}\n";
            dual(pre + "Op.ADD.apply(3, 4)",
                 pre + wrap("return Op.ADD.apply(3, 4)"), 7);
        }

        @Test void testEnumComparison() throws Exception {
            String pre = "enum class Level { LOW, MED, HIGH }\n";
            dual(pre + "Level.HIGH == Level.HIGH",
                 pre + wrap("return Level.HIGH == Level.HIGH"), true);
        }
    }

    // ============ 对象 ============

    @Nested
    @DisplayName("对象声明")
    class ObjectTests {

        @Test void testSingleton() throws Exception {
            String pre = "object Config {\n  val version = 1\n  fun info() = \"v\" + version\n}\n";
            dual(pre + "Config.info()",
                 pre + wrap("return Config.info()"), "v1");
        }

        @Test void testCompanionObject() throws Exception {
            String pre = "class MyClass {\n  companion object {\n    fun create() = MyClass()\n    val TAG = \"MC\"\n  }\n}\n";
            dual(pre + "MyClass.TAG",
                 pre + wrap("return MyClass.TAG"), "MC");
        }

        @Test void testObjectLiteral() throws Exception {
            String pre = "interface Runner {\n  fun run(): Int\n}\n";
            String logic = pre + "val r = object : Runner {\n  override fun run() = 42\n}\nr.run()";
            String compiled = pre + wrap("val r = object : Runner {\n      override fun run() = 42\n    }\n    return r.run()");
            dual(logic, compiled, 42);
        }
    }

    // ============ 运算符重载 ============

    @Nested
    @DisplayName("运算符重载")
    class OperatorOverloadTests {

        @Test void testPlusOperator() throws Exception {
            String pre = "class Vec(val x: Int, val y: Int) {\n  fun plus(o: Vec) = Vec(x + o.x, y + o.y)\n}\n";
            dual(pre + "val r = Vec(1, 2) + Vec(3, 4)\nr.x + r.y",
                 pre + wrap("val r = Vec(1, 2) + Vec(3, 4)\n    return r.x + r.y"), 10);
        }

        @Test void testMinusOperator() throws Exception {
            String pre = "class Num(val v: Int) {\n  fun minus(o: Num) = Num(v - o.v)\n}\n";
            dual(pre + "(Num(10) - Num(3)).v",
                 pre + wrap("return (Num(10) - Num(3)).v"), 7);
        }

        @Test void testTimesOperator() throws Exception {
            String pre = "class Scale(val v: Int) {\n  fun times(n: Int) = Scale(v * n)\n}\n";
            dual(pre + "(Scale(5) * 3).v",
                 pre + wrap("return (Scale(5) * 3).v"), 15);
        }

        @Test void testUnaryMinusOperator() throws Exception {
            String pre = "class Pt(val x: Int) {\n  fun unaryMinus() = Pt(-x)\n}\n";
            dual(pre + "(-Pt(7)).x",
                 pre + wrap("return (-Pt(7)).x"), -7);
        }

        @Test void testGetOperator() throws Exception {
            String pre = "class Row(val data: List) {\n  fun get(i: Int) = data[i]\n}\n";
            dual(pre + "Row([10, 20, 30])[1]",
                 pre + wrap("return Row([10, 20, 30])[1]"), 20);
        }

        @Test void testSetOperator() throws Exception {
            String pre = "class MRow(val data: List) {\n  fun get(i: Int) = data[i]\n  fun set(i: Int, v: Int) { data[i] = v }\n}\n";
            dual(pre + "val r = MRow([1, 2, 3])\nr[0] = 99\nr[0]",
                 pre + wrap("val r = MRow([1, 2, 3])\n    r[0] = 99\n    return r[0]"), 99);
        }

        @Test void testCompareToOperator() throws Exception {
            String pre = "class Age(val v: Int) {\n  fun compareTo(o: Age) = v - o.v\n}\n";
            dual(pre + "Age(25) > Age(20)",
                 pre + wrap("return Age(25) > Age(20)"), true);
        }
    }

    // ============ 数据类 ============

    @Nested
    @DisplayName("数据类 @data")
    class DataClassTests {

        @Test void testDataClassToString() throws Exception {
            String pre = "@data class Point(val x: Int, val y: Int)\n";
            dual(pre + "Point(3, 4).toString()",
                 pre + wrap("return Point(3, 4).toString()"), "Point(x=3, y=4)");
        }

        @Test void testDataClassEquality() throws Exception {
            String pre = "@data class Point(val x: Int, val y: Int)\n";
            dual(pre + "Point(1, 2) == Point(1, 2)",
                 pre + wrap("return Point(1, 2) == Point(1, 2)"), true);
        }

        @Test void testDataClassCopy() throws Exception {
            String pre = "@data class Item(val name: String, val qty: Int)\n";
            dual(pre + "val a = Item(\"pen\", 5)\nval b = a.copy(qty = 10)\nb.qty",
                 pre + wrap("val a = Item(\"pen\", 5)\n    val b = a.copy(qty = 10)\n    return b.qty"), 10);
        }
    }

    // ============ 属性访问器 ============

    @Nested
    @DisplayName("属性 getter/setter")
    class PropertyAccessorTests {

        @Test void testCustomGetter() throws Exception {
            String pre = "class Rect(val w: Int, val h: Int) {\n  val area: Int\n    get() = w * h\n}\n";
            dual(pre + "Rect(3, 4).area",
                 pre + wrap("return Rect(3, 4).area"), 12);
        }

        @Test void testCustomGetterBlock() throws Exception {
            String pre = "class Temp(val c: Int) {\n  val f: Int\n    get() {\n      return c * 9 / 5 + 32\n    }\n}\n";
            dual(pre + "Temp(100).f",
                 pre + wrap("return Temp(100).f"), 212);
        }

        @Test void testCustomSetter() throws Exception {
            String pre = "class Clamp {\n  var raw: Int = 0\n  var v: Int = 0\n    set(x) { raw = if (x > 100) 100 else x }\n}\n";
            dual(pre + "val c = Clamp()\nc.v = 200\nc.raw",
                 pre + wrap("val c = Clamp()\n    c.v = 200\n    return c.raw"), 100);
        }

        @Test void testGetterSetterCombo() throws Exception {
            String pre = "class DoubleStore {\n  var backing: Int = 0\n  var value: Int = 0\n" +
                         "    get() = backing * 2\n    set(v) { backing = v }\n}\n";
            dual(pre + "val d = DoubleStore()\nd.value = 5\nd.value",
                 pre + wrap("val d = DoubleStore()\n    d.value = 5\n    return d.value"), 10);
        }

        @Test void testPlainProperty() throws Exception {
            String pre = "class Box(var x: Int)\n";
            dual(pre + "val b = Box(10)\nb.x = 20\nb.x",
                 pre + wrap("val b = Box(10)\n    b.x = 20\n    return b.x"), 20);
        }
    }

    // ============ 可见性修饰符 ============

    @Nested
    @DisplayName("可见性修饰符")
    class VisibilityTests {

        @Test void testPublicByDefault() throws Exception {
            String pre = "class Pub(val x: Int)\n";
            dual(pre + "Pub(42).x",
                 pre + wrap("return Pub(42).x"), 42);
        }

        @Test void testPrivateField() throws Exception {
            // 私有字段只能在类内部访问
            String pre = "class Secret(private val code: Int) {\n  fun reveal() = code\n}\n";
            dual(pre + "Secret(123).reveal()",
                 pre + wrap("return Secret(123).reveal()"), 123);
        }

        @Test void testPrivateMethod() throws Exception {
            String pre = "class Helper {\n  private fun internal() = 42\n  fun expose() = internal()\n}\n";
            dual(pre + "Helper().expose()",
                 pre + wrap("return Helper().expose()"), 42);
        }
    }

    // ============ 次级构造器 ============

    @Nested
    @DisplayName("次级构造器")
    class SecondaryConstructorTests {

        @Test void testSecondaryConstructor() throws Exception {
            String pre = "class Pt(val x: Int, val y: Int) {\n  constructor(v: Int) : this(v, v)\n}\n";
            dual(pre + "val p = Pt(5)\np.x + p.y",
                 pre + wrap("val p = Pt(5)\n    return p.x + p.y"), 10);
        }
    }

    // ============ 注解 ============

    @Nested
    @DisplayName("注解")
    class AnnotationTests {

        @Test void testAnnotationClass() throws Exception {
            String pre = "annotation class Tag(val name: String)\n@Tag(name = \"test\") class MyClass\n";
            dual(pre + "MyClass.annotations.size()",
                 pre + wrap("return MyClass.annotations.size()"), 1);
        }
    }

    // ============ 综合场景 ============

    @Nested
    @DisplayName("OOP 综合场景")
    class CombinedTests {

        @Test void testClassHierarchyWithMethods() throws Exception {
            String pre =
                "open class Shape {\n  open fun area(): Int = 0\n  fun describe() = \"area=\" + area()\n}\n" +
                "class Rect(val w: Int, val h: Int) : Shape() {\n  override fun area() = w * h\n}\n" +
                "class Circle(val r: Int) : Shape() {\n  override fun area() = r * r * 3\n}\n";
            dual(pre + "Rect(3, 4).describe()",
                 pre + wrap("return Rect(3, 4).describe()"), "area=12");
        }

        @Test void testEnumWithWhen() throws Exception {
            String pre =
                "enum class Season { SPRING, SUMMER, AUTUMN, WINTER }\n" +
                "fun temp(s: Season): String = when (s) {\n" +
                "  Season.SPRING -> \"warm\"\n  Season.SUMMER -> \"hot\"\n" +
                "  Season.AUTUMN -> \"cool\"\n  Season.WINTER -> \"cold\"\n  else -> \"?\"\n}\n";
            dual(pre + "temp(Season.WINTER)",
                 pre + wrap("return temp(Season.WINTER)"), "cold");
        }

        @Test void testBuilderPattern() throws Exception {
            String pre =
                "class Config {\n  var host: String = \"localhost\"\n  var port: Int = 8080\n" +
                "  fun host(h: String): Config { host = h; return this }\n" +
                "  fun port(p: Int): Config { port = p; return this }\n" +
                "  fun info() = host + \":\" + port\n}\n";
            dual(pre + "Config().host(\"example.com\").port(443).info()",
                 pre + wrap("return Config().host(\"example.com\").port(443).info()"), "example.com:443");
        }

        @Test void testLinkedList() throws Exception {
            String pre =
                "class Node(val value: Int, var next: Node? = null)\n" +
                "fun sumList(n: Node?): Int {\n" +
                "  if (n == null) return 0\n" +
                "  return n.value + sumList(n.next)\n}\n";
            dual(pre + "val c = Node(3)\nval b = Node(2, c)\nval a = Node(1, b)\nsumList(a)",
                 pre + wrap("val c = Node(3)\n    val b = Node(2, c)\n    val a = Node(1, b)\n    return sumList(a)"), 6);
        }

        @Test void testOperatorOverloadWithLoop() throws Exception {
            String pre =
                "class Acc(var total: Int) {\n  fun plus(n: Int) = Acc(total + n)\n}\n";
            String logic = pre + "var a = Acc(0)\nfor (i in 1..5) { a = a + i }\na.total";
            String compiled = pre + wrap("var a = Acc(0)\n    for (i in 1..5) { a = a + i }\n    return a.total");
            dual(logic, compiled, 15);
        }

        @Test void testInterfacePolymorphism() throws Exception {
            String pre =
                "interface Formatter {\n  fun format(v: Int): String\n}\n" +
                "class HexFmt : Formatter {\n  override fun format(v: Int) = \"0x\" + v\n}\n" +
                "class DecFmt : Formatter {\n  override fun format(v: Int) = \"d\" + v\n}\n" +
                "fun render(f: Formatter, v: Int) = f.format(v)\n";
            dual(pre + "render(HexFmt(), 255)",
                 pre + wrap("return render(HexFmt(), 255)"), "0x255");
        }

        @Test void testClassWithLambdaField() throws Exception {
            String pre = "class Transform(val f: (Int) -> Int) {\n  fun apply(x: Int) = f(x)\n}\n";
            dual(pre + "Transform({ it * it }).apply(7)",
                 pre + wrap("return Transform({ it * it }).apply(7)"), 49);
        }

        @Test void testMultiParamLambda_4args() throws Exception {
            String code = "val fn = { a, b, c, d -> \"\" + a + \"-\" + b + \"-\" + c + \"-\" + d }\n"
                        + "fn(1, \"hello\", true, 3.14)";
            String compCode = wrap("val fn = { a, b, c, d -> \"\" + a + \"-\" + b + \"-\" + c + \"-\" + d }\n"
                        + "    return fn(1, \"hello\", true, 3.14)");
            dual(code, compCode, "1-hello-true-3.14");
        }

        @Test void testMultiParamLambda_5args() throws Exception {
            String code = "val fn = { a, b, c, d, e -> a + b + c + d + e }\nfn(10, 20, 30, 40, 50)";
            String compCode = wrap("val fn = { a, b, c, d, e -> a + b + c + d + e }\n    return fn(10, 20, 30, 40, 50)");
            dual(code, compCode, 150);
        }

        @Test void testMultiParamLambda_passedAsArg() throws Exception {
            String pre =
                "fun apply4(fn, a, b, c, d) = fn(a, b, c, d)\n";
            dual(pre + "apply4({ a, b, c, d -> a * b + c * d }, 2, 3, 4, 5)",
                 pre + wrap("return apply4({ a, b, c, d -> a * b + c * d }, 2, 3, 4, 5)"), 26);
        }

        @Test void testAlgorithm_BubbleSort() throws Exception {
            String pre =
                "fun sort(list: List): List {\n" +
                "  val n = list.size()\n" +
                "  for (i in 0..<n) {\n" +
                "    for (j in 0..<n - 1 - i) {\n" +
                "      if (list[j] > list[j + 1]) {\n" +
                "        val tmp = list[j]\n" +
                "        list[j] = list[j + 1]\n" +
                "        list[j + 1] = tmp\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "  return list\n" +
                "}\n";
            dual(pre + "sort([3, 1, 4, 1, 5])[0]",
                 pre + wrap("return sort([3, 1, 4, 1, 5])[0]"), 1);
        }

        @Test void testAlgorithm_Fibonacci() throws Exception {
            String pre =
                "fun fib(n: Int): Int {\n" +
                "  var a = 0\n  var b = 1\n" +
                "  for (i in 0..<n) {\n" +
                "    val t = a + b\n    a = b\n    b = t\n" +
                "  }\n" +
                "  return a\n}\n";
            dual(pre + "fib(10)",
                 pre + wrap("return fib(10)"), 55);
        }
    }

    // ============ init 块 ============

    @Nested
    @DisplayName("init 块")
    class InitBlockTests {

        @Test void testBasicInitBlock() throws Exception {
            String pre =
                "class Foo(val x: Int) {\n" +
                "    var doubled = 0\n" +
                "    init {\n" +
                "        doubled = x * 2\n" +
                "    }\n" +
                "}\n";
            dual(pre + "Foo(5).doubled",
                 pre + wrap("return Foo(5).doubled"), 10);
        }

        @Test void testInitBlockAccessesPrecedingProperty() throws Exception {
            String pre =
                "class Bar(val x: Int) {\n" +
                "    val a = x + 1\n" +
                "    var result = 0\n" +
                "    init {\n" +
                "        result = a * 10\n" +
                "    }\n" +
                "}\n";
            dual(pre + "Bar(3).result",
                 pre + wrap("return Bar(3).result"), 40);
        }

        @Test void testInitBlockWithControlFlow() throws Exception {
            String pre =
                "class Validated(val x: Int) {\n" +
                "    var status = \"\"\n" +
                "    init {\n" +
                "        if (x > 0) {\n" +
                "            status = \"positive\"\n" +
                "        } else {\n" +
                "            status = \"non-positive\"\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            dual(pre + "Validated(5).status",
                 pre + wrap("return Validated(5).status"), "positive");
        }

        @Test void testInitBlockWithLoop() throws Exception {
            String pre =
                "class Summer(val n: Int) {\n" +
                "    var sum = 0\n" +
                "    init {\n" +
                "        for (i in 1..n) {\n" +
                "            sum = sum + i\n" +
                "        }\n" +
                "    }\n" +
                "}\n";
            dual(pre + "Summer(10).sum",
                 pre + wrap("return Summer(10).sum"), 55);
        }

        @Test void testInitBlockNoConstructorParams() throws Exception {
            String pre =
                "class NoParams {\n" +
                "    var value = 10\n" +
                "    init {\n" +
                "        value = value + 5\n" +
                "    }\n" +
                "}\n";
            dual(pre + "NoParams().value",
                 pre + wrap("return NoParams().value"), 15);
        }

        @Test void testInitBlockCallsMethod() throws Exception {
            String pre =
                "class WithMethod(val x: Int) {\n" +
                "    var computed = 0\n" +
                "    fun calculate(): Int = x * x + 1\n" +
                "    init {\n" +
                "        computed = calculate()\n" +
                "    }\n" +
                "}\n";
            dual(pre + "WithMethod(4).computed",
                 pre + wrap("return WithMethod(4).computed"), 17);
        }

        @Test void testInitBlockAccumulate() throws Exception {
            String pre =
                "class Acc(val base: Int) {\n" +
                "    val doubled = base * 2\n" +
                "    var tripled = 0\n" +
                "    init {\n" +
                "        tripled = base * 3\n" +
                "    }\n" +
                "}\n";
            dual(pre + "Acc(7).tripled",
                 pre + wrap("return Acc(7).tripled"), 21);
        }
    }
}
