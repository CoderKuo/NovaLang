package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.NovaAnnotations;
import nova.runtime.*;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import nova.runtime.interpreter.reflect.NovaFieldInfo;
import nova.runtime.interpreter.reflect.NovaMethodInfo;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 反射 API 编译模式集成测试
 *
 * <p>端到端：Nova 源码 → 编译 → 加载 → NovaClassInfo.fromJavaClass 验证反射信息</p>
 *
 * <p>放在 nova-runtime 模块（可访问 nova-compiler 和 nova-runtime 两个模块的类）</p>
 */
@DisplayName("编译模式: 反射 API 集成测试")
class CompileReflectIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

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

    // ============ 场景1: classOf 基本功能 ============

    @Nested
    @DisplayName("场景: classOf 基本功能")
    class ClassOfBasic {

        @Test
        @DisplayName("fromJavaClass 返回正确类名")
        void testClassInfoName() throws Exception {
            String code = "class Person(val name: String, val age: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);

            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Person"));
            assertEquals("Person", info.name);
        }

        @Test
        @DisplayName("classOf(ClassName) 在编译代码中返回 ClassInfo")
        void testClassOfInCompiledCode() throws Exception {
            String code =
                    "class Animal(val species: String)\n" +
                    "object Test {\n" +
                    "    fun testName(): String {\n" +
                    "        val info = classOf(Animal)\n" +
                    "        return info.name\n" +
                    "    }\n" +
                    "}\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class<?> testClass = loaded.get("Test");
            Object instance = getInstance(testClass);

            assertEquals("Animal", invoke(instance, testClass, "testName"));
        }
    }

    // ============ 场景2: 字段反射 ============

    @Nested
    @DisplayName("场景: 字段反射")
    class FieldReflection {

        @Test
        @DisplayName("fromJavaClass 包含正确数量的字段")
        void testFieldCount() throws Exception {
            String code = "class Vec3(val x: Int, val y: Int, val z: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Vec3"));

            assertEquals(3, info.getFieldInfos().size());
        }

        @Test
        @DisplayName("字段名称正确")
        void testFieldNames() throws Exception {
            String code = "class Config(val host: String, val port: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Config"));

            assertTrue(info.getFieldInfos().stream().anyMatch(f -> "host".equals(f.name)));
            assertTrue(info.getFieldInfos().stream().anyMatch(f -> "port".equals(f.name)));
        }

        @Test
        @DisplayName("字段类型信息正确")
        void testFieldTypes() throws Exception {
            String code = "class Pair(val first: String, val second: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Pair"));

            NovaFieldInfo first = info.findField("first");
            NovaFieldInfo second = info.findField("second");
            assertNotNull(first);
            assertNotNull(second);
            assertEquals("String", first.type);
            assertTrue(second.type.equals("int") || second.type.equals("Integer"));
        }

        @Test
        @DisplayName("val 字段不可变，var 字段可变")
        void testFieldMutability() throws Exception {
            String code = "class Mixed(val id: Int, var name: String)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Mixed"));

            NovaFieldInfo id = info.findField("id");
            NovaFieldInfo nameField = info.findField("name");
            assertNotNull(id);
            assertNotNull(nameField);
            assertFalse(id.mutable, "val 字段应不可变");
            assertTrue(nameField.mutable, "var 字段应可变");
        }
    }

    // ============ 场景3: 方法反射 ============

    @Nested
    @DisplayName("场景: 方法反射")
    class MethodReflection {

        @Test
        @DisplayName("fromJavaClass 包含实例方法")
        void testMethodExists() throws Exception {
            String code =
                    "class Calculator {\n" +
                    "    fun add(a: Int, b: Int) = a + b\n" +
                    "    fun sub(a: Int, b: Int) = a - b\n" +
                    "}\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Calculator"));

            assertNotNull(info.findMethod("add"));
            assertNotNull(info.findMethod("sub"));
        }

        @Test
        @DisplayName("方法参数信息正确")
        void testMethodParams() throws Exception {
            String code =
                    "class Greeter {\n" +
                    "    fun greet(name: String, times: Int) = name\n" +
                    "}\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Greeter"));

            NovaMethodInfo greet = info.findMethod("greet");
            assertNotNull(greet);
            assertEquals(2, greet.getParamInfos().size());
        }

        @Test
        @DisplayName("findMethod 查找不存在的方法返回 null")
        void testFindMethodNotFound() throws Exception {
            String code = "class Empty\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Empty"));

            assertNull(info.findMethod("nonexistent"));
        }
    }

    // ============ 场景4: 继承和接口反射 ============

    @Nested
    @DisplayName("场景: 继承和接口反射")
    class InheritanceReflection {

        @Test
        @DisplayName("无父类时 superclass 为 null")
        void testNoSuperclass() throws Exception {
            String code = "class Standalone(val x: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Standalone"));

            assertNull(info.superclass);
        }

        @Test
        @DisplayName("有父类时 superclass 不为空")
        void testWithSuperclass() throws Exception {
            String code =
                    "open class Base(val id: Int)\n" +
                    "class Child(val name: String) : Base(1)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Child"));

            assertNotNull(info.superclass);
            assertEquals("Base", info.superclass);
        }

        @Test
        @DisplayName("接口实现反射")
        void testInterfaceReflection() throws Exception {
            String code =
                    "interface Printable { fun print(): String }\n" +
                    "class Doc(val text: String) : Printable {\n" +
                    "    fun print() = text\n" +
                    "}\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Doc"));

            assertTrue(info.interfaces.stream().anyMatch(i -> i.contains("Printable")));
        }
    }

    // ============ 场景5: @data 类反射 ============

    @Nested
    @DisplayName("场景: @data 类反射")
    class DataClassReflection {

        @Test
        @DisplayName("@data class 的字段可通过反射读取")
        void testDataClassFields() throws Exception {
            String code = "@data class User(val name: String, val age: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("User"));

            assertNotNull(info.findField("name"));
            assertNotNull(info.findField("age"));
        }

        @Test
        @DisplayName("@data class 包含 componentN 和 copy 方法")
        void testDataClassMethods() throws Exception {
            String code = "@data class Coord(val x: Int, val y: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Coord"));

            assertNotNull(info.findMethod("component1"), "应有 component1 方法");
            assertNotNull(info.findMethod("component2"), "应有 component2 方法");
            assertNotNull(info.findMethod("copy"), "应有 copy 方法");
        }
    }

    // ============ 场景6: 注解 + 反射联动 ============

    @Nested
    @DisplayName("场景: 注解 + 反射联动")
    class AnnotationReflectionCombo {

        @BeforeEach
        void setUp() {
            NovaAnnotations.clear();
        }

        @Test
        @DisplayName("处理器接收 NovaClassInfo 且字段信息正确")
        void testProcessorReceivesClassInfoWithFields() throws Exception {
            AtomicReference<Object> captured = new AtomicReference<>();
            NovaAnnotations.register("reflect_test",
                    (BiConsumer<Object, Object>) (cls, args) -> captured.set(cls));

            String code = "@reflect_test class Widget(val width: Int, val height: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Widget", true, loaded.get("Widget").getClassLoader());

            assertTrue(captured.get() instanceof NovaClassInfo);
            NovaClassInfo info = (NovaClassInfo) captured.get();
            assertEquals("Widget", info.name);
            assertEquals(2, info.getFieldInfos().size());
        }

        @Test
        @DisplayName("处理器中可通过 findField 访问字段信息")
        void testProcessorUsesReflection() throws Exception {
            AtomicReference<String> result = new AtomicReference<>("");
            NovaAnnotations.register("inspect_fields", (BiConsumer<Object, Object>) (cls, args) -> {
                NovaClassInfo info = (NovaClassInfo) cls;
                StringBuilder sb = new StringBuilder();
                for (NovaFieldInfo f : info.getFieldInfos()) {
                    sb.append(f.name).append(";");
                }
                result.set(sb.toString());
            });

            String code = "@inspect_fields class Settings(val volume: Int, val brightness: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Settings", true, loaded.get("Settings").getClassLoader());

            assertTrue(result.get().contains("volume"));
            assertTrue(result.get().contains("brightness"));
        }

        @Test
        @DisplayName("处理器中可查看方法信息")
        void testProcessorInspectsMethods() throws Exception {
            AtomicReference<String> result = new AtomicReference<>("");
            NovaAnnotations.register("inspect_methods", (BiConsumer<Object, Object>) (cls, args) -> {
                NovaClassInfo info = (NovaClassInfo) cls;
                StringBuilder sb = new StringBuilder();
                for (NovaMethodInfo m : info.getMethodInfos()) {
                    sb.append(m.name).append(";");
                }
                result.set(sb.toString());
            });

            String code =
                    "@inspect_methods class Service {\n" +
                    "    fun start() = \"started\"\n" +
                    "    fun stop() = \"stopped\"\n" +
                    "}\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Service", true, loaded.get("Service").getClassLoader());

            assertTrue(result.get().contains("start"));
            assertTrue(result.get().contains("stop"));
        }
    }

    // ============ 场景7: 多类反射 ============

    @Nested
    @DisplayName("场景: 多类反射")
    class MultiClassReflection {

        @Test
        @DisplayName("多个类独立反射")
        void testMultipleClassesReflection() throws Exception {
            String code =
                    "class Cat(val name: String, val lives: Int)\n" +
                    "class Dog(val name: String, val breed: String, val age: Int)\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);

            NovaClassInfo catInfo = NovaClassInfo.fromJavaClass(loaded.get("Cat"));
            NovaClassInfo dogInfo = NovaClassInfo.fromJavaClass(loaded.get("Dog"));

            assertEquals("Cat", catInfo.name);
            assertEquals(2, catInfo.getFieldInfos().size());

            assertEquals("Dog", dogInfo.name);
            assertEquals(3, dogInfo.getFieldInfos().size());
        }

        @Test
        @DisplayName("空类的反射信息正确")
        void testEmptyClassReflection() throws Exception {
            String code = "class Empty\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            NovaClassInfo info = NovaClassInfo.fromJavaClass(loaded.get("Empty"));

            assertEquals("Empty", info.name);
            assertEquals(0, info.getFieldInfos().size());
        }
    }
}
