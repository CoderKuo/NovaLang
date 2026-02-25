package nova.runtime.interpreter.reflect;

import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 反射 API 集成测试 — 覆盖实际使用场景
 */
@DisplayName("反射 API 集成测试")
class ReflectIntegrationTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    // ============ 场景1: 通用序列化框架 ============

    @Nested
    @DisplayName("场景: 通用序列化")
    class SerializationScenario {

        @Test
        @DisplayName("遍历字段构建 JSON 字符串")
        void testSerializeToJson() {
            interpreter.evalRepl(
                "fun toJson(obj): String {\n" +
                "    val info = classOf(obj)\n" +
                "    var result = \"{\"\n" +
                "    val fields = info.fields\n" +
                "    for (i in 0..<fields.size()) {\n" +
                "        val f = fields[i]\n" +
                "        val value = f.get(obj)\n" +
                "        if (i > 0) result = result + \", \"\n" +
                "        if (typeof(value) == \"String\") {\n" +
                "            result = result + \"\\\"\" + f.name + \"\\\": \\\"\" + value + \"\\\"\"\n" +
                "        } else {\n" +
                "            result = result + \"\\\"\" + f.name + \"\\\": \" + value\n" +
                "        }\n" +
                "    }\n" +
                "    return result + \"}\"\n" +
                "}"
            );
            interpreter.evalRepl("class User(val name: String, val age: Int)");
            interpreter.evalRepl("val u = User(\"Alice\", 25)");
            NovaValue result = interpreter.evalRepl("toJson(u)");
            String json = result.asString();
            assertTrue(json.contains("\"name\": \"Alice\""));
            assertTrue(json.contains("\"age\": 25"));
        }

        @Test
        @DisplayName("反序列化: 通过反射读取字段名列表")
        void testFieldNameExtraction() {
            interpreter.evalRepl("class Config(val host: String, val port: Int, val debug: Boolean)");
            interpreter.evalRepl(
                "fun fieldNames(cls) {\n" +
                "    val info = classOf(cls)\n" +
                "    return info.fields.map { f -> f.name }\n" +
                "}"
            );
            NovaValue result = interpreter.evalRepl("fieldNames(Config)");
            assertEquals(3, interpreter.evalRepl("fieldNames(Config).size()").asInt());
        }
    }

    // ============ 场景2: 对象复制与变换 ============

    @Nested
    @DisplayName("场景: 反射式对象操作")
    class ObjectManipulationScenario {

        @Test
        @DisplayName("通过反射逐字段复制对象")
        void testReflectiveCopy() {
            interpreter.evalRepl("class Point(var x: Int, var y: Int)");
            interpreter.evalRepl("val src = Point(10, 20)");
            interpreter.evalRepl("val dst = Point(0, 0)");
            interpreter.evalRepl(
                "val info = classOf(src)\n" +
                "for (f in info.fields) {\n" +
                "    f.set(dst, f.get(src))\n" +
                "}"
            );
            assertEquals(10, interpreter.evalRepl("dst.x").asInt());
            assertEquals(20, interpreter.evalRepl("dst.y").asInt());
        }

        @Test
        @DisplayName("通过反射批量设置字段值")
        void testBatchFieldSet() {
            interpreter.evalRepl("class Settings(var volume: Int, var brightness: Int, var contrast: Int)");
            interpreter.evalRepl("val s = Settings(50, 50, 50)");
            interpreter.evalRepl(
                "val info = classOf(s)\n" +
                "for (f in info.fields) {\n" +
                "    if (f.mutable) f.set(s, 100)\n" +
                "}"
            );
            assertEquals(100, interpreter.evalRepl("s.volume").asInt());
            assertEquals(100, interpreter.evalRepl("s.brightness").asInt());
            assertEquals(100, interpreter.evalRepl("s.contrast").asInt());
        }

        @Test
        @DisplayName("只修改可变字段, 跳过不可变字段")
        void testSkipImmutableFields() {
            interpreter.evalRepl("class Mixed(val id: Int, var name: String, var score: Int)");
            interpreter.evalRepl("val m = Mixed(1, \"old\", 0)");
            interpreter.evalRepl(
                "val info = classOf(m)\n" +
                "for (f in info.fields) {\n" +
                "    if (f.mutable && f.type == \"String\") f.set(m, \"new\")\n" +
                "    if (f.mutable && f.type == \"Int\") f.set(m, 99)\n" +
                "}"
            );
            assertEquals(1, interpreter.evalRepl("m.id").asInt());     // val 未修改
            assertEquals("new", interpreter.evalRepl("m.name").asString());
            assertEquals(99, interpreter.evalRepl("m.score").asInt());
        }
    }

    // ============ 场景3: 方法反射调用 ============

    @Nested
    @DisplayName("场景: 方法反射调用")
    class MethodInvocationScenario {

        @Test
        @DisplayName("按名称动态调用方法")
        void testDynamicMethodInvocation() {
            interpreter.evalRepl(
                "class MathService {\n" +
                "    fun add(a: Int, b: Int) = a + b\n" +
                "    fun mul(a: Int, b: Int) = a * b\n" +
                "}"
            );
            interpreter.evalRepl("val svc = MathService()");
            interpreter.evalRepl("val info = classOf(svc)");
            assertEquals(5, interpreter.evalRepl("info.method(\"add\").call(svc, 2, 3)").asInt());
            assertEquals(6, interpreter.evalRepl("info.method(\"mul\").call(svc, 2, 3)").asInt());
        }

        @Test
        @DisplayName("遍历所有方法并获取参数信息")
        void testMethodParameterIntrospection() {
            interpreter.evalRepl(
                "class Service {\n" +
                "    fun greet(name: String) = \"Hello, \" + name\n" +
                "    fun add(a: Int, b: Int) = a + b\n" +
                "}"
            );
            interpreter.evalRepl("val info = classOf(Service)");
            // greet 有 1 个参数, add 有 2 个参数
            interpreter.evalRepl("val greetParams = info.method(\"greet\").params");
            interpreter.evalRepl("val addParams = info.method(\"add\").params");
            assertEquals(1, interpreter.evalRepl("greetParams.size()").asInt());
            assertEquals(2, interpreter.evalRepl("addParams.size()").asInt());
            assertEquals("name", interpreter.evalRepl("greetParams[0].name").asString());
            assertEquals("String", interpreter.evalRepl("greetParams[0].type").asString());
        }

        @Test
        @DisplayName("无参方法反射调用")
        void testNoArgMethodCall() {
            interpreter.evalRepl(
                "class Counter(var value: Int) {\n" +
                "    fun increment() { value = value + 1 }\n" +
                "    fun getValue() = value\n" +
                "}"
            );
            interpreter.evalRepl("val c = Counter(0)");
            interpreter.evalRepl("classOf(c).method(\"increment\").call(c)");
            interpreter.evalRepl("classOf(c).method(\"increment\").call(c)");
            assertEquals(2, interpreter.evalRepl("c.value").asInt());
        }
    }

    // ============ 场景4: 类继承体系反射 ============

    @Nested
    @DisplayName("场景: 继承体系反射")
    class InheritanceScenario {

        @Test
        @DisplayName("classOf 同一类的类引用和实例引用返回相同 name")
        void testClassRefVsInstanceRef() {
            interpreter.evalRepl("class Foo(val x: Int)");
            interpreter.evalRepl("val f = Foo(42)");
            NovaValue byClass = interpreter.evalRepl("classOf(Foo).name");
            NovaValue byInst = interpreter.evalRepl("classOf(f).name");
            assertEquals(byClass.asString(), byInst.asString());
        }

        @Test
        @DisplayName("子类反射包含自身字段")
        void testSubclassFields() {
            interpreter.evalRepl("open class Base { fun baseMethod() = 1 }");
            interpreter.evalRepl("class Child(val name: String, val score: Int) : Base");
            // Child 的主构造器参数是 name 和 score
            NovaValue result = interpreter.evalRepl("classOf(Child).fields.size()");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("接口实现反射")
        void testInterfaceReflection() {
            interpreter.evalRepl("interface Drawable { fun draw(): String }");
            interpreter.evalRepl("interface Resizable { fun resize(factor: Int): String }");
            interpreter.evalRepl(
                "class Widget : Drawable, Resizable {\n" +
                "    fun draw() = \"drawn\"\n" +
                "    fun resize(factor: Int) = \"resized by \" + factor\n" +
                "}"
            );
            assertEquals(2, interpreter.evalRepl("classOf(Widget).interfaces.size()").asInt());
        }
    }

    // ============ 场景5: 可见性反射 ============

    @Nested
    @DisplayName("场景: 可见性反射")
    class VisibilityScenario {

        @Test
        @DisplayName("反射可见不同可见性的字段")
        void testVisibilityIntrospection() {
            interpreter.evalRepl("class Entity(val id: Int, private val secret: String, protected val tag: String)");
            interpreter.evalRepl("val info = classOf(Entity)");
            assertEquals("public", interpreter.evalRepl("info.field(\"id\").visibility").asString());
            assertEquals("private", interpreter.evalRepl("info.field(\"secret\").visibility").asString());
            assertEquals("protected", interpreter.evalRepl("info.field(\"tag\").visibility").asString());
        }

        @Test
        @DisplayName("过滤出所有 public 字段")
        void testFilterPublicFields() {
            interpreter.evalRepl("class Mixed(val a: Int, private val b: Int, val c: Int, private val d: Int)");
            interpreter.evalRepl(
                "val pubFields = classOf(Mixed).fields.filter { f -> f.visibility == \"public\" }"
            );
            assertEquals(2, interpreter.evalRepl("pubFields.size()").asInt());
        }
    }

    // ============ 场景6: 反射构建通用工具函数 ============

    @Nested
    @DisplayName("场景: 通用工具函数")
    class UtilityScenario {

        @Test
        @DisplayName("通用 describe 函数输出类描述")
        void testDescribeFunction() {
            interpreter.evalRepl(
                "fun describe(cls) {\n" +
                "    val info = classOf(cls)\n" +
                "    var desc = \"class \" + info.name + \"(\"\n" +
                "    val fields = info.fields\n" +
                "    for (i in 0..<fields.size()) {\n" +
                "        if (i > 0) desc = desc + \", \"\n" +
                "        val f = fields[i]\n" +
                "        desc = desc + (if (f.mutable) \"var\" else \"val\") + \" \" + f.name\n" +
                "        if (f.type != null) desc = desc + \": \" + f.type\n" +
                "    }\n" +
                "    return desc + \")\"\n" +
                "}"
            );
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue result = interpreter.evalRepl("describe(Person)");
            String desc = result.asString();
            assertTrue(desc.startsWith("class Person("));
            assertTrue(desc.contains("val name: String"));
            assertTrue(desc.contains("var age: Int"));
        }

        @Test
        @DisplayName("通用 equals 比较两个同类对象的所有字段")
        void testGenericEquals() {
            interpreter.evalRepl(
                "fun reflectEquals(a, b): Boolean {\n" +
                "    val infoA = classOf(a)\n" +
                "    val infoB = classOf(b)\n" +
                "    if (infoA.name != infoB.name) return false\n" +
                "    for (f in infoA.fields) {\n" +
                "        if (f.get(a) != f.get(b)) return false\n" +
                "    }\n" +
                "    return true\n" +
                "}"
            );
            interpreter.evalRepl("class Vec(val x: Int, val y: Int)");
            interpreter.evalRepl("val v1 = Vec(1, 2)");
            interpreter.evalRepl("val v2 = Vec(1, 2)");
            interpreter.evalRepl("val v3 = Vec(1, 3)");
            assertTrue(interpreter.evalRepl("reflectEquals(v1, v2)").asBoolean());
            assertFalse(interpreter.evalRepl("reflectEquals(v1, v3)").asBoolean());
        }

        @Test
        @DisplayName("通用 diff 函数列出两个对象不同的字段")
        void testGenericDiff() {
            interpreter.evalRepl(
                "fun diff(a, b): List {\n" +
                "    val info = classOf(a)\n" +
                "    val result = mutableListOf()\n" +
                "    for (f in info.fields) {\n" +
                "        if (f.get(a) != f.get(b)) result.add(f.name)\n" +
                "    }\n" +
                "    return result\n" +
                "}"
            );
            interpreter.evalRepl("class Cfg(val host: String, val port: Int, val debug: Boolean)");
            interpreter.evalRepl("val a = Cfg(\"localhost\", 8080, true)");
            interpreter.evalRepl("val b = Cfg(\"localhost\", 9090, false)");
            NovaValue result = interpreter.evalRepl("diff(a, b)");
            assertEquals(2, interpreter.evalRepl("diff(a, b).size()").asInt());
        }
    }

    // ============ 场景7: 类型无关的字段 map ============

    @Nested
    @DisplayName("场景: 字段转 Map")
    class FieldToMapScenario {

        @Test
        @DisplayName("将对象所有字段转为 Map")
        void testObjectToMap() {
            interpreter.evalRepl(
                "fun toMap(obj): Map {\n" +
                "    val info = classOf(obj)\n" +
                "    val result = mutableListOf()\n" +
                "    val map = mapOf()\n" +
                "    for (f in info.fields) {\n" +
                "        map[f.name] = f.get(obj)\n" +
                "    }\n" +
                "    return map\n" +
                "}"
            );
            interpreter.evalRepl("class Pos(val x: Int, val y: Int, val z: Int)");
            interpreter.evalRepl("val m = toMap(Pos(1, 2, 3))");
            assertEquals(1, interpreter.evalRepl("m[\"x\"]").asInt());
            assertEquals(2, interpreter.evalRepl("m[\"y\"]").asInt());
            assertEquals(3, interpreter.evalRepl("m[\"z\"]").asInt());
        }
    }
}
