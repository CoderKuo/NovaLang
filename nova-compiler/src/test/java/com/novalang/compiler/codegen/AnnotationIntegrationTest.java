package com.novalang.compiler.codegen;

import com.novalang.ir.NovaIrCompiler;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注解系统编译模式集成测试
 *
 * <p>端到端：Nova 源码字符串 → 编译 → 加载 → 反射执行 → 断言</p>
 */
@DisplayName("注解系统编译模式集成测试")
class AnnotationIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    // ============ 辅助方法 ============

    private Map<String, Class<?>> compileAndLoad(String source) {
        return compiler.compileAndLoad(source, "test.nova");
    }

    private Object getInstance(Class<?> clazz) throws Exception {
        return clazz.getField("INSTANCE").get(null);
    }

    private Object invoke(Object target, Class<?> clazz, String method) throws Exception {
        Method m = clazz.getDeclaredMethod(method);
        m.setAccessible(true);
        return m.invoke(target);
    }

    // ============ @data 集成测试 ============

    @Nested
    @DisplayName("@data 端到端")
    class DataE2E {

        @Test
        @DisplayName("@data class 的 componentN 和 toString")
        void testDataComponentAndToString() throws Exception {
            String code =
                    "@data class Point(val x: Int, val y: Int)\n" +
                    "object Test {\n" +
                    "    fun testToString(): String {\n" +
                    "        val p = Point(3, 7)\n" +
                    "        return p.toString()\n" +
                    "    }\n" +
                    "    fun testComponent1(): Int {\n" +
                    "        val p = Point(3, 7)\n" +
                    "        return p.component1()\n" +
                    "    }\n" +
                    "    fun testComponent2(): Int {\n" +
                    "        val p = Point(3, 7)\n" +
                    "        return p.component2()\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("Point(x=3, y=7)", invoke(instance, testClass, "testToString"));
            assertEquals(3, invoke(instance, testClass, "testComponent1"));
            assertEquals(7, invoke(instance, testClass, "testComponent2"));
        }

        @Test
        @DisplayName("@data class 的 equals 和 hashCode")
        void testDataEqualsAndHashCode() throws Exception {
            String code =
                    "@data class Coord(val x: Int, val y: Int)\n" +
                    "object Test {\n" +
                    "    fun testEquals(): Boolean {\n" +
                    "        val a = Coord(1, 2)\n" +
                    "        val b = Coord(1, 2)\n" +
                    "        return a.equals(b)\n" +
                    "    }\n" +
                    "    fun testNotEquals(): Boolean {\n" +
                    "        val a = Coord(1, 2)\n" +
                    "        val c = Coord(3, 4)\n" +
                    "        return a.equals(c)\n" +
                    "    }\n" +
                    "    fun testHashCodeConsistent(): Boolean {\n" +
                    "        val a = Coord(5, 10)\n" +
                    "        val b = Coord(5, 10)\n" +
                    "        return a.hashCode() == b.hashCode()\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals(true, invoke(instance, testClass, "testEquals"));
            assertEquals(false, invoke(instance, testClass, "testNotEquals"));
            assertEquals(true, invoke(instance, testClass, "testHashCodeConsistent"));
        }

        @Test
        @DisplayName("@data class 的 copy 方法")
        void testDataCopy() throws Exception {
            String code =
                    "@data class User(val name: String, val age: Int)\n" +
                    "object Test {\n" +
                    "    fun testCopy(): String {\n" +
                    "        val u = User(\"Alice\", 30)\n" +
                    "        val u2 = u.copy(\"Bob\", 25)\n" +
                    "        return u2.toString()\n" +
                    "    }\n" +
                    "    fun testOriginalUnchanged(): String {\n" +
                    "        val u = User(\"Alice\", 30)\n" +
                    "        val u2 = u.copy(\"Bob\", 25)\n" +
                    "        return u.toString()\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("User(name=Bob, age=25)", invoke(instance, testClass, "testCopy"));
            assertEquals("User(name=Alice, age=30)", invoke(instance, testClass, "testOriginalUnchanged"));
        }

        @Test
        @DisplayName("@data class 使用字符串字段的 equals")
        void testDataStringFields() throws Exception {
            String code =
                    "@data class Tag(val key: String, val value: String)\n" +
                    "object Test {\n" +
                    "    fun testEquals(): Boolean {\n" +
                    "        val a = Tag(\"env\", \"prod\")\n" +
                    "        val b = Tag(\"env\", \"prod\")\n" +
                    "        return a.equals(b)\n" +
                    "    }\n" +
                    "    fun testToString(): String {\n" +
                    "        return Tag(\"env\", \"prod\").toString()\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals(true, invoke(instance, testClass, "testEquals"));
            assertEquals("Tag(key=env, value=prod)", invoke(instance, testClass, "testToString"));
        }
    }

    // ============ @builder 集成测试 ============

    @Nested
    @DisplayName("@builder 端到端")
    class BuilderE2E {

        @Test
        @DisplayName("@builder 链式构建并读取字段")
        void testBuilderChain() throws Exception {
            String code =
                    "@builder class Config(val host: String, val port: Int)\n" +
                    "object Test {\n" +
                    "    fun testHost(): String {\n" +
                    "        val c = Config.builder().host(\"localhost\").port(8080).build()\n" +
                    "        return c.host\n" +
                    "    }\n" +
                    "    fun testPort(): Int {\n" +
                    "        val c = Config.builder().host(\"localhost\").port(8080).build()\n" +
                    "        return c.port\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("localhost", invoke(instance, testClass, "testHost"));
            assertEquals(8080, invoke(instance, testClass, "testPort"));
        }

        @Test
        @DisplayName("@builder 生成 Builder 内部类")
        void testBuilderClassGenerated() throws Exception {
            String code = "@builder class App(val name: String, val version: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);

            assertNotNull(loaded.get("App"), "原类应存在");
            assertNotNull(loaded.get("App$Builder"), "Builder 内部类应存在");
        }
    }

    // ============ @data @builder 组合集成测试 ============

    @Nested
    @DisplayName("@data @builder 组合端到端")
    class DataBuilderE2E {

        @Test
        @DisplayName("@data @builder 组合使用")
        void testDataBuilderCombined() throws Exception {
            String code =
                    "@data @builder class Product(val name: String, val price: Int)\n" +
                    "object Test {\n" +
                    "    fun testBuilderAndToString(): String {\n" +
                    "        val p = Product.builder().name(\"Widget\").price(100).build()\n" +
                    "        return p.toString()\n" +
                    "    }\n" +
                    "    fun testBuilderAndEquals(): Boolean {\n" +
                    "        val a = Product.builder().name(\"X\").price(10).build()\n" +
                    "        val b = Product(\"X\", 10)\n" +
                    "        return a.equals(b)\n" +
                    "    }\n" +
                    "    fun testBuilderAndComponent(): String {\n" +
                    "        val p = Product.builder().name(\"Gadget\").price(50).build()\n" +
                    "        return p.component1()\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("Product(name=Widget, price=100)",
                    invoke(instance, testClass, "testBuilderAndToString"));
            assertEquals(true, invoke(instance, testClass, "testBuilderAndEquals"));
            assertEquals("Gadget", invoke(instance, testClass, "testBuilderAndComponent"));
        }
    }

    // ============ annotation class 集成测试 ============

    @Nested
    @DisplayName("annotation class 端到端")
    class AnnotationClassE2E {

        @Test
        @DisplayName("annotation class 编译为 JVM @interface")
        void testAnnotationClassIsAnnotation() throws Exception {
            String code = "annotation class Marker\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> clazz = loaded.get("Marker");

            assertTrue(clazz.isAnnotation());
            assertTrue(clazz.isInterface());
        }

        @Test
        @DisplayName("annotation class 带 @Retention(RUNTIME)")
        void testAnnotationRetention() throws Exception {
            String code = "annotation class Serializable\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> clazz = loaded.get("Serializable");

            java.lang.annotation.Retention retention =
                    clazz.getAnnotation(java.lang.annotation.Retention.class);
            assertNotNull(retention);
            assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("annotation class 带参数")
        void testAnnotationClassWithParams() throws Exception {
            String code = "annotation class JsonName(val name: String)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> clazz = loaded.get("JsonName");

            assertTrue(clazz.isAnnotation());
            Method nameMethod = clazz.getMethod("name");
            assertNotNull(nameMethod);
            assertEquals(String.class, nameMethod.getReturnType());
        }

        @Test
        @DisplayName("自定义注解标注类后可通过反射读取")
        void testCustomAnnotationOnClass() throws Exception {
            String code =
                    "annotation class MyTag\n" +
                    "@MyTag class Foo\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> tagClass = loaded.get("MyTag");
            Class<?> fooClass = loaded.get("Foo");

            java.lang.annotation.Annotation[] annotations = fooClass.getAnnotations();
            boolean found = false;
            for (java.lang.annotation.Annotation a : annotations) {
                if (a.annotationType() == tagClass) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "Foo 类应标注 @MyTag");
        }
    }

    // ============ 业务场景集成测试 ============

    @Nested
    @DisplayName("业务场景")
    class BusinessScenarios {

        @Test
        @DisplayName("@data class 用于领域模型")
        void testDomainModel() throws Exception {
            String code =
                    "@data class Order(val id: Int, val amount: Int, val status: String)\n" +
                    "object Test {\n" +
                    "    fun testOrderCreation(): String {\n" +
                    "        val order = Order(1, 500, \"pending\")\n" +
                    "        return order.toString()\n" +
                    "    }\n" +
                    "    fun testOrderEquality(): Boolean {\n" +
                    "        val a = Order(1, 500, \"pending\")\n" +
                    "        val b = Order(1, 500, \"pending\")\n" +
                    "        return a.equals(b)\n" +
                    "    }\n" +
                    "    fun testOrderCopy(): String {\n" +
                    "        val order = Order(1, 500, \"pending\")\n" +
                    "        val shipped = order.copy(1, 500, \"shipped\")\n" +
                    "        return shipped.component3()\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("Order(id=1, amount=500, status=pending)",
                    invoke(instance, testClass, "testOrderCreation"));
            assertEquals(true, invoke(instance, testClass, "testOrderEquality"));
            assertEquals("shipped", invoke(instance, testClass, "testOrderCopy"));
        }

        @Test
        @DisplayName("@builder 用于复杂配置对象")
        void testComplexConfig() throws Exception {
            String code =
                    "@builder class DbConfig(val host: String, val port: Int, val dbName: String, val poolSize: Int)\n" +
                    "object Test {\n" +
                    "    fun testConfig(): String {\n" +
                    "        val cfg = DbConfig.builder()\n" +
                    "            .host(\"db.example.com\")\n" +
                    "            .port(5432)\n" +
                    "            .dbName(\"mydb\")\n" +
                    "            .poolSize(10)\n" +
                    "            .build()\n" +
                    "        return cfg.host\n" +
                    "    }\n" +
                    "    fun testPort(): Int {\n" +
                    "        val cfg = DbConfig.builder()\n" +
                    "            .host(\"localhost\")\n" +
                    "            .port(3306)\n" +
                    "            .dbName(\"test\")\n" +
                    "            .poolSize(5)\n" +
                    "            .build()\n" +
                    "        return cfg.port\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("db.example.com", invoke(instance, testClass, "testConfig"));
            assertEquals(3306, invoke(instance, testClass, "testPort"));
        }

        @Test
        @DisplayName("多个 @data class 协作")
        void testMultipleDataClasses() throws Exception {
            String code =
                    "@data class Name(val first: String, val last: String)\n" +
                    "@data class Age(val value: Int)\n" +
                    "object Test {\n" +
                    "    fun testName(): String {\n" +
                    "        val n = Name(\"John\", \"Doe\")\n" +
                    "        return n.toString()\n" +
                    "    }\n" +
                    "    fun testAge(): String {\n" +
                    "        val a = Age(25)\n" +
                    "        return a.toString()\n" +
                    "    }\n" +
                    "    fun testEquality(): Boolean {\n" +
                    "        val n1 = Name(\"Jane\", \"Doe\")\n" +
                    "        val n2 = Name(\"Jane\", \"Doe\")\n" +
                    "        return n1.equals(n2)\n" +
                    "    }\n" +
                    "}\n";

            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("Name(first=John, last=Doe)", invoke(instance, testClass, "testName"));
            assertEquals("Age(value=25)", invoke(instance, testClass, "testAge"));
            assertEquals(true, invoke(instance, testClass, "testEquality"));
        }
    }
}
