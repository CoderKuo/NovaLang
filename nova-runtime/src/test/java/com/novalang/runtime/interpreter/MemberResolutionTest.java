package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 成员解析测试 — 覆盖属性访问器、扩展函数/属性、内置类型方法、
 * 可见性控制、Java 互操作、作用域函数、data class、枚举等场景。
 */
class MemberResolutionTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    // ============ 1. 自定义 getter/setter ============

    @Nested
    @DisplayName("自定义 getter/setter")
    class CustomAccessorTests {

        @Test
        @DisplayName("自定义 getter — get() = w * h")
        void testCustomGetter() {
            String code =
                "class Rect(val w: Int, val h: Int) {\n" +
                "    val area: Int\n" +
                "        get() = w * h\n" +
                "}\n" +
                "Rect(3, 4).area";
            assertEquals(12, eval(code).asInt());
        }

        @Test
        @DisplayName("自定义 setter — set(x) { raw = clamp(x) }")
        void testCustomSetter() {
            String code =
                "class Clamp {\n" +
                "    var raw: Int = 0\n" +
                "    var v: Int = 0\n" +
                "        set(x) { raw = if (x > 100) 100 else x }\n" +
                "}\n" +
                "val c = Clamp()\n" +
                "c.v = 200\n" +
                "c.raw";
            assertEquals(100, eval(code).asInt());
        }

        @Test
        @DisplayName("字段默认值初始化")
        void testFieldDefaultInit() {
            String code =
                "class Box(val x: Int) {\n" +
                "    val doubled = x * 2\n" +
                "}\n" +
                "Box(5).doubled";
            assertEquals(10, eval(code).asInt());
        }

        @Test
        @DisplayName("getter 带块体 — get() { return expr }")
        void testGetterBlock() {
            String code =
                "class Temp(val c: Int) {\n" +
                "    val f: Int\n" +
                "        get() {\n" +
                "            return c * 9 / 5 + 32\n" +
                "        }\n" +
                "}\n" +
                "Temp(100).f";
            assertEquals(212, eval(code).asInt());
        }
    }

    // ============ 2. 扩展函数解析 ============

    @Nested
    @DisplayName("扩展函数解析")
    class ExtensionFunctionTests {

        @Test
        @DisplayName("String 扩展函数")
        void testStringExtension() {
            eval("fun String.shout() = this + \"!\"");
            assertEquals("Hi!", eval("\"Hi\".shout()").asString());
        }

        @Test
        @DisplayName("Int 扩展函数")
        void testIntExtension() {
            eval("fun Int.triple() = this * 3");
            assertEquals(15, eval("5.triple()").asInt());
        }

        @Test
        @DisplayName("List 扩展函数")
        void testListExtension() {
            eval("fun List.first() = this[0]");
            assertEquals(10, eval("[10, 20, 30].first()").asInt());
        }

        @Test
        @DisplayName("自定义类扩展函数")
        void testCustomClassExtension() {
            eval("class Vec(val x: Int, val y: Int)");
            eval("fun Vec.magnitude() = sqrt(this.x * this.x + this.y * this.y)");
            assertEquals(5.0, eval("Vec(3, 4).magnitude()").asDouble(), 0.001);
        }
    }

    // ============ 3. 扩展属性 ============

    @Nested
    @DisplayName("扩展属性")
    class ExtensionPropertyTests {

        @Test
        @DisplayName("String 扩展属性 — len")
        void testStringExtensionProperty() {
            eval("val String.len = this.length()");
            assertEquals(5, eval("\"hello\".len").asInt());
        }

        @Test
        @DisplayName("Int 扩展属性 — squared")
        void testIntExtensionProperty() {
            eval("val Int.squared = this * this");
            assertEquals(49, eval("7.squared").asInt());
        }

        @Test
        @DisplayName("自定义类扩展属性")
        void testCustomClassExtensionProperty() {
            eval("class Rect(val w: Double, val h: Double)");
            eval("val Rect.area = this.w * this.h");
            assertEquals(15.0, eval("Rect(3.0, 5.0).area").asDouble(), 0.001);
        }
    }

    // ============ 4. 内置类型方法 ============

    @Nested
    @DisplayName("内置类型方法")
    class BuiltinTypeMethodTests {

        @Test
        @DisplayName("String.length()")
        void testStringLength() {
            assertEquals(5, eval("\"hello\".length()").asInt());
        }

        @Test
        @DisplayName("String.toUpperCase()")
        void testStringUpperCase() {
            assertEquals("HELLO", eval("\"hello\".toUpperCase()").asString());
        }

        @Test
        @DisplayName("String.uppercase()")
        void testStringUppercaseMethod() {
            assertEquals("HELLO", eval("\"hello\".uppercase()").asString());
        }

        @Test
        @DisplayName("List.size()")
        void testListSize() {
            assertEquals(3, eval("[1, 2, 3].size()").asInt());
        }

        @Test
        @DisplayName("List.reversed()")
        void testListReversed() {
            NovaValue result = eval("listOf(1, 2, 3).reversed()");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).get(0).asInt());
            assertEquals(1, ((NovaList) result).get(2).asInt());
        }

        @Test
        @DisplayName("Map.keys()")
        void testMapKeys() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            NovaValue keys = eval("m.keys()");
            assertTrue(keys instanceof NovaList);
            assertEquals(2, ((NovaList) keys).size());
        }

        @Test
        @DisplayName("Map.size()")
        void testMapSize() {
            assertEquals(2, eval("mapOf(\"x\" to 10, \"y\" to 20).size()").asInt());
        }
    }

    // ============ 5. private 字段访问控制 ============

    @Nested
    @DisplayName("private 字段访问控制")
    class PrivateFieldAccessTests {

        @Test
        @DisplayName("private 字段不能从外部访问")
        void testPrivateFieldNotAccessibleOutside() {
            eval("class Secret(private val code: String)");
            eval("val s = Secret(\"abc\")");
            assertThrows(NovaRuntimeException.class, () -> eval("s.code"));
        }

        @Test
        @DisplayName("private 字段可以从类内部访问")
        void testPrivateFieldAccessibleInside() {
            eval(
                "class Secret(private val code: String) {\n" +
                "    fun reveal() { return code }\n" +
                "}"
            );
            eval("val s = Secret(\"abc\")");
            assertEquals("abc", eval("s.reveal()").asString());
        }

        @Test
        @DisplayName("private 方法不能从外部调用")
        void testPrivateMethodNotAccessibleOutside() {
            eval("class Calc { private fun internal() { return 42 } }");
            eval("val c = Calc()");
            assertThrows(NovaRuntimeException.class, () -> eval("c.internal()"));
        }
    }

    // ============ 6. protected 方法访问 ============

    @Nested
    @DisplayName("protected 方法访问")
    class ProtectedMethodAccessTests {

        @Test
        @DisplayName("protected 方法可以从子类访问")
        void testProtectedAccessibleInSubclass() {
            eval(
                "class Base {\n" +
                "    protected fun helper() { return \"from base\" }\n" +
                "}"
            );
            eval(
                "class Derived : Base {\n" +
                "    fun callHelper() { return helper() }\n" +
                "}"
            );
            eval("val d = Derived()");
            assertEquals("from base", eval("d.callHelper()").asString());
        }

        @Test
        @DisplayName("protected 方法不能从外部直接访问")
        void testProtectedNotAccessibleExternally() {
            eval(
                "class Guarded {\n" +
                "    protected fun secret() { return \"hidden\" }\n" +
                "}"
            );
            eval("val g = Guarded()");
            assertThrows(NovaRuntimeException.class, () -> eval("g.secret()"));
        }
    }

    // ============ 7. Java 互操作成员访问 ============

    @Nested
    @DisplayName("Java 互操作成员访问")
    class JavaInteropMemberTests {

        @Test
        @DisplayName("Java 静态方法 — Math.max")
        void testJavaStaticMethod() {
            assertEquals(20, eval("Math.max(10, 20)").asInt());
        }

        @Test
        @DisplayName("Java 静态字段 — Math.PI")
        void testJavaStaticField() {
            assertEquals(Math.PI, eval("Math.PI").asDouble(), 0.0001);
        }

        @Test
        @DisplayName("Java 实例方法 — StringBuilder")
        void testJavaInstanceMethod() {
            eval("val sb = Java.new(\"java.lang.StringBuilder\", \"hello\")");
            eval("sb.append(\" world\")");
            assertEquals("hello world", eval("sb.toString()").asString());
        }

        @Test
        @DisplayName("Java.type 实例化并调用方法")
        void testJavaTypeInstantiateAndCall() {
            eval("val ArrayList = Java.type(\"java.util.ArrayList\")");
            eval("val list = ArrayList()");
            eval("list.add(\"x\")");
            eval("list.add(\"y\")");
            assertEquals(2, eval("list.size()").asInt());
        }

        @Test
        @DisplayName("JavaBean 属性语法 — getter")
        void testJavaBeanPropertyGetter() {
            eval("val t = Thread(\"test-thread\")");
            assertEquals("test-thread", eval("t.name").asString());
        }
    }

    // ============ 8. 作用域函数成员解析 ============

    @Nested
    @DisplayName("作用域函数成员解析")
    class ScopeFunctionMemberTests {

        @Test
        @DisplayName("run — this 绑定到接收者，length() 解析为 this.length()")
        void testRunResolvesThisMember() {
            assertEquals(5, eval("\"hello\".run { length() }").asInt());
        }

        @Test
        @DisplayName("run — 自定义类属性解析")
        void testRunResolvesCustomClassFields() {
            String code =
                "class Person(val name: String, val age: Int)\n" +
                "val p = Person(\"Alice\", 25)\n" +
                "p.run { name + \" is \" + age }";
            assertEquals("Alice is 25", eval(code).asString());
        }

        @Test
        @DisplayName("let — it 传递对象")
        void testLetPassesObjectAsIt() {
            assertEquals(5, eval("\"hello\".let { it.length() }").asInt());
        }

        @Test
        @DisplayName("also — 返回原对象")
        void testAlsoReturnsOriginal() {
            assertEquals(42, eval("42.also { it + 100 }").asInt());
        }

        @Test
        @DisplayName("apply — this 绑定，返回原对象")
        void testApplyThisBinding() {
            String code =
                "class Config(var host: String, var port: Int)\n" +
                "val c = Config(\"\", 0).apply {\n" +
                "    host = \"localhost\"\n" +
                "    port = 8080\n" +
                "}\n" +
                "c.port";
            assertEquals(8080, eval(code).asInt());
        }
    }

    // ============ 9. data class 自动生成方法 ============

    @Nested
    @DisplayName("data class 自动生成方法")
    class DataClassTests {

        @Test
        @DisplayName("@data equals — 同值相等")
        void testDataClassEquals() {
            String code =
                "@data class Point(val x: Int, val y: Int)\n" +
                "Point(1, 2) == Point(1, 2)";
            assertTrue(eval(code).asBool());
        }

        @Test
        @DisplayName("@data equals — 不同值不等")
        void testDataClassNotEquals() {
            String code =
                "@data class Point(val x: Int, val y: Int)\n" +
                "Point(1, 2) == Point(3, 4)";
            assertFalse(eval(code).asBool());
        }

        @Test
        @DisplayName("@data copy 方法")
        void testDataClassCopy() {
            eval("@data class Point(val x: Int, val y: Int)");
            eval("val p = Point(1, 2)");
            eval("val p2 = p.copy(x = 10)");
            assertEquals(10, eval("p2.x").asInt());
            assertEquals(2, eval("p2.y").asInt());
        }

        @Test
        @DisplayName("@data componentN 解构")
        void testDataClassComponentN() {
            eval("@data class Point(val x: Int, val y: Int)");
            eval("val p = Point(3, 7)");
            assertEquals(3, eval("p.component1()").asInt());
            assertEquals(7, eval("p.component2()").asInt());
        }

        @Test
        @DisplayName("@data 解构赋值")
        void testDataClassDestructuring() {
            String code =
                "@data class Pair(val a: Int, val b: Int)\n" +
                "val (x, y) = Pair(5, 9)\n" +
                "x + y";
            assertEquals(14, eval(code).asInt());
        }
    }

    // ============ 10. 枚举成员解析 ============

    @Nested
    @DisplayName("枚举成员解析")
    class EnumMemberTests {

        @Test
        @DisplayName("枚举 name 属性")
        void testEnumName() {
            eval("enum class Color { RED, GREEN, BLUE }");
            assertEquals("RED", eval("Color.RED.name").asString());
        }

        @Test
        @DisplayName("枚举 ordinal 属性")
        void testEnumOrdinal() {
            eval("enum class Color { RED, GREEN, BLUE }");
            assertEquals(0, eval("Color.RED.ordinal").asInt());
            assertEquals(2, eval("Color.BLUE.ordinal").asInt());
        }

        @Test
        @DisplayName("带属性的枚举 — 字段访问")
        void testEnumWithPropertyAccess() {
            eval("enum class Priority(val level: Int) { LOW(1), MEDIUM(5), HIGH(10) }");
            assertEquals(5, eval("Priority.MEDIUM.level").asInt());
        }

        @Test
        @DisplayName("带方法的枚举 — 方法调用")
        void testEnumWithMethodCall() {
            eval("enum class Size(val cm: Int) { SMALL(10), LARGE(100); fun label() { return name + \"(\" + cm + \"cm)\" } }");
            assertEquals("SMALL(10cm)", eval("Size.SMALL.label()").asString());
        }

        @Test
        @DisplayName("枚举 values() 静态方法")
        void testEnumValues() {
            eval("enum class Dir { UP, DOWN }");
            NovaValue vals = eval("Dir.values()");
            assertTrue(vals instanceof NovaList);
            assertEquals(2, ((NovaList) vals).size());
        }

        @Test
        @DisplayName("枚举 valueOf() 静态方法")
        void testEnumValueOf() {
            eval("enum class Dir { UP, DOWN }");
            NovaValue v = eval("Dir.valueOf(\"DOWN\")");
            assertTrue(v instanceof NovaEnumEntry);
            assertEquals("DOWN", v.toString());
        }
    }
}
