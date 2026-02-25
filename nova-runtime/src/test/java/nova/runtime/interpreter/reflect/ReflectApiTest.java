package nova.runtime.interpreter.reflect;

import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 反射 API 解释器模式集成测试
 */
class ReflectApiTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    @Nested
    @DisplayName("classOf() 函数")
    class ClassOfTests {

        @Test
        @DisplayName("classOf(ClassName) 返回 ClassInfo")
        void testClassOfByClass() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Person)");
            assertNotNull(result);
            assertEquals("ClassInfo", result.getTypeName());
        }

        @Test
        @DisplayName("classOf(instance) 从实例获取 ClassInfo")
        void testClassOfByInstance() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            interpreter.evalRepl("val p = Person(\"Alice\", 30)");
            NovaValue result = interpreter.evalRepl("classOf(p)");
            assertNotNull(result);
            assertEquals("ClassInfo", result.getTypeName());
        }

        @Test
        @DisplayName("ClassInfo.name 返回正确类名")
        void testClassInfoName() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Person).name");
            assertEquals("Person", result.asString());
        }
    }

    @Nested
    @DisplayName("字段反射")
    class FieldReflectionTests {

        @Test
        @DisplayName("info.fields 返回正确数量的 FieldInfo")
        void testFieldsCount() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Person).fields.size()");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("info.field(name) 返回 FieldInfo")
        void testFieldByName() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Person).field(\"name\").name");
            assertEquals("name", result.asString());
        }

        @Test
        @DisplayName("field.get(obj) 读取字段值")
        void testFieldGet() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            interpreter.evalRepl("val p = Person(\"Alice\", 30)");
            NovaValue result = interpreter.evalRepl("classOf(Person).field(\"name\").get(p)");
            assertEquals("Alice", result.asString());
        }

        @Test
        @DisplayName("field.set(obj, value) 修改可变字段")
        void testFieldSet() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            interpreter.evalRepl("val p = Person(\"Alice\", 30)");
            interpreter.evalRepl("classOf(Person).field(\"age\").set(p, 31)");
            NovaValue result = interpreter.evalRepl("p.age");
            assertEquals(31, result.asInt());
        }

        @Test
        @DisplayName("field.type 返回类型信息")
        void testFieldType() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Person).field(\"name\").type");
            assertEquals("String", result.asString());
        }

        @Test
        @DisplayName("field.visibility 返回可见性")
        void testFieldVisibility() {
            interpreter.evalRepl("class Secret(private val code: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Secret).field(\"code\").visibility");
            assertEquals("private", result.asString());
        }

        @Test
        @DisplayName("field.mutable 区分 val/var")
        void testFieldMutable() {
            interpreter.evalRepl("class Person(val name: String, var age: Int)");
            NovaValue nameResult = interpreter.evalRepl("classOf(Person).field(\"name\").mutable");
            NovaValue ageResult = interpreter.evalRepl("classOf(Person).field(\"age\").mutable");
            // val 有 FINAL 修饰符 → mutable=false, var 无 FINAL → mutable=true
            assertFalse(nameResult.asBoolean());
            assertTrue(ageResult.asBoolean());
        }
    }

    @Nested
    @DisplayName("方法反射")
    class MethodReflectionTests {

        @Test
        @DisplayName("info.methods 返回方法列表")
        void testMethods() {
            interpreter.evalRepl("class Calc { fun add(a: Int, b: Int) = a + b }");
            NovaValue result = interpreter.evalRepl("classOf(Calc).methods.size()");
            assertTrue(result.asInt() >= 1);
        }

        @Test
        @DisplayName("method.call(obj, args) 调用方法")
        void testMethodCall() {
            interpreter.evalRepl("class Calc { fun add(a: Int, b: Int) = a + b }");
            interpreter.evalRepl("val c = Calc()");
            NovaValue result = interpreter.evalRepl("classOf(Calc).method(\"add\").call(c, 3, 4)");
            assertEquals(7, result.asInt());
        }

        @Test
        @DisplayName("method.params 返回参数信息")
        void testMethodParams() {
            interpreter.evalRepl("class Calc { fun add(a: Int, b: Int) = a + b }");
            NovaValue result = interpreter.evalRepl("classOf(Calc).method(\"add\").params.size()");
            assertEquals(2, result.asInt());
        }

        @Test
        @DisplayName("method.name 返回方法名")
        void testMethodName() {
            interpreter.evalRepl("class Calc { fun add(a: Int, b: Int) = a + b }");
            NovaValue result = interpreter.evalRepl("classOf(Calc).method(\"add\").name");
            assertEquals("add", result.asString());
        }
    }

    @Nested
    @DisplayName("注解处理器接收 ClassInfo")
    class AnnotationProcessorTests {

        @Test
        @DisplayName("注解处理器 lambda 接收 ClassInfo 并能访问成员")
        void testAnnotationProcessorReceivesClassInfo() {
            interpreter.evalRepl("var capturedName = \"\"");
            interpreter.evalRepl("registerAnnotationProcessor(\"MyAnn\") { target, args -> capturedName = target.name }");
            interpreter.evalRepl("annotation class MyAnn");
            interpreter.evalRepl("@MyAnn class Foo(val x: Int)");
            NovaValue result = interpreter.evalRepl("capturedName");
            assertEquals("Foo", result.asString());
        }

        @Test
        @DisplayName("注解处理器能访问字段信息")
        void testAnnotationProcessorAccessFields() {
            interpreter.evalRepl("var fieldCount = 0");
            interpreter.evalRepl("registerAnnotationProcessor(\"Inspect\") { target, args -> fieldCount = target.fields.size() }");
            interpreter.evalRepl("annotation class Inspect");
            interpreter.evalRepl("@Inspect class Bar(val a: String, val b: Int, var c: Double)");
            NovaValue result = interpreter.evalRepl("fieldCount");
            assertEquals(3, result.asInt());
        }
    }

    @Nested
    @DisplayName("ClassInfo 其他属性")
    class ClassInfoMiscTests {

        @Test
        @DisplayName("superclass 属性")
        void testSuperclass() {
            interpreter.evalRepl("open class Animal { fun speak() = \"...\" }");
            interpreter.evalRepl("class Dog : Animal { fun speak() = \"Woof\" }");
            NovaValue result = interpreter.evalRepl("classOf(Dog).superclass");
            assertEquals("Animal", result.asString());
        }

        @Test
        @DisplayName("interfaces 属性")
        void testInterfaces() {
            interpreter.evalRepl("interface Greetable { fun greet(): String }");
            interpreter.evalRepl("class Greeter : Greetable { fun greet() = \"hello\" }");
            NovaValue result = interpreter.evalRepl("classOf(Greeter).interfaces.size()");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("无父类时 superclass 为 null")
        void testNoSuperclass() {
            interpreter.evalRepl("class Simple(val x: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Simple).superclass");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("isData 属性")
        void testIsData() {
            interpreter.evalRepl("@data class DataClass(val x: Int)");
            NovaValue result = interpreter.evalRepl("classOf(DataClass).isData");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("普通类 isData 为 false")
        void testIsDataFalse() {
            interpreter.evalRepl("class Normal(val x: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Normal).isData");
            assertFalse(result.asBool());
        }

        @Test
        @DisplayName("isAbstract 属性")
        void testIsAbstract() {
            interpreter.evalRepl("abstract class Base { abstract fun foo(): Int }");
            NovaValue result = interpreter.evalRepl("classOf(Base).isAbstract");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("普通类 isAbstract 为 false")
        void testIsAbstractFalse() {
            interpreter.evalRepl("class Concrete(val x: Int)");
            NovaValue result = interpreter.evalRepl("classOf(Concrete).isAbstract");
            assertFalse(result.asBool());
        }

        @Test
        @DisplayName("ClassInfo toString")
        void testClassInfoToString() {
            interpreter.evalRepl("class Foo");
            NovaValue result = interpreter.evalRepl("classOf(Foo).toString()");
            assertTrue(result.asString().contains("Foo"));
        }
    }

    @Nested
    @DisplayName("注解信息反射")
    class AnnotationInfoTests {

        @Test
        @DisplayName("获取注解列表")
        void testAnnotations() {
            interpreter.evalRepl("annotation class Tag");
            interpreter.evalRepl("@Tag class Foo");
            NovaValue result = interpreter.evalRepl("classOf(Foo).annotations.size()");
            assertTrue(result.asInt() >= 1);
        }

        @Test
        @DisplayName("注解名称")
        void testAnnotationName() {
            interpreter.evalRepl("annotation class MyAnno");
            interpreter.evalRepl("@MyAnno class Bar");
            NovaValue result = interpreter.evalRepl("classOf(Bar).annotations[0].name");
            assertEquals("MyAnno", result.asString());
        }

        @Test
        @DisplayName("带参数注解")
        void testAnnotationWithArgs() {
            String code = ""
                + "annotation class Label(val text: String)\n"
                + "@Label(text = \"hello\") class Item";
            interpreter.evalRepl(code);
            NovaValue result = interpreter.evalRepl("classOf(Item).annotations.size()");
            assertTrue(result.asInt() >= 1);
        }

        @Test
        @DisplayName("多个注解")
        void testMultipleAnnotations() {
            String code = ""
                + "annotation class A\n"
                + "annotation class B\n"
                + "@A @B class Multi";
            interpreter.evalRepl(code);
            NovaValue result = interpreter.evalRepl("classOf(Multi).annotations.size()");
            assertEquals(2, result.asInt());
        }
    }

    @Nested
    @DisplayName("方法参数反射")
    class ParamInfoTests {

        @Test
        @DisplayName("参数名称")
        void testParamName() {
            interpreter.evalRepl("class Calc { fun add(a: Int, b: Int) = a + b }");
            NovaValue result = interpreter.evalRepl("classOf(Calc).method(\"add\").params[0].name");
            assertEquals("a", result.asString());
        }

        @Test
        @DisplayName("参数类型")
        void testParamType() {
            interpreter.evalRepl("class Calc { fun greet(name: String) = \"hi $name\" }");
            NovaValue result = interpreter.evalRepl("classOf(Calc).method(\"greet\").params[0].type");
            assertEquals("String", result.asString());
        }

        @Test
        @DisplayName("参数有默认值标识")
        void testParamHasDefault() {
            interpreter.evalRepl("class Calc { fun add(a: Int, b: Int = 0) = a + b }");
            NovaValue result = interpreter.evalRepl("classOf(Calc).method(\"add\").params[1].hasDefault");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("方法可见性")
        void testMethodVisibility() {
            interpreter.evalRepl("class Foo { private fun secret() = 42 }");
            NovaValue result = interpreter.evalRepl("classOf(Foo).method(\"secret\").visibility");
            assertEquals("private", result.asString());
        }
    }

    @Nested
    @DisplayName("Java 类反射")
    class JavaClassReflectionTests {

        @Test
        @DisplayName("classOf 带方法的 Nova 类")
        void testClassOfWithMethods() {
            String code = ""
                + "class Calculator {\n"
                + "    fun add(a: Int, b: Int) = a + b\n"
                + "    fun mul(a: Int, b: Int) = a * b\n"
                + "}\n"
                + "val info = classOf(Calculator)\n"
                + "info.methods.size()";
            assertTrue(interpreter.evalRepl(code).asInt() >= 2);
        }
    }
}
