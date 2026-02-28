package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.NovaClass;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 注解系统集成测试 — 覆盖常见注解使用场景
 */
@DisplayName("注解系统集成测试")
class AnnotationIntegrationTest {

    private Interpreter interpreter;
    private ByteArrayOutputStream outputStream;

    @BeforeEach
    void setUp() {
        outputStream = new ByteArrayOutputStream();
        interpreter = new Interpreter();
        interpreter.setStdout(new PrintStream(outputStream));
        interpreter.setReplMode(true);
    }

    private String getOutput() {
        return outputStream.toString().trim();
    }

    // ============ 场景1: 自定义验证注解 ============

    @Nested
    @DisplayName("场景: 验证注解")
    class ValidationAnnotation {

        @Test
        @DisplayName("自定义 @validate 注解检查字段是否有必填标记")
        void testValidateAnnotation() {
            // 注册 @validate 处理器: 在类上添加 validate() 静态方法
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"validate\") { target, args ->\n" +
                "    // target 现在是 ClassInfo, 通过 target.name 获取类名\n" +
                "    println(\"Registered validator for: \" + target.name)\n" +
                "}"
            );
            interpreter.evalRepl("annotation class validate");
            interpreter.evalRepl("@validate class User(val name: String, val email: String)");
            assertTrue(getOutput().contains("Registered validator for: User"));
        }

        @Test
        @DisplayName("验证处理器能读取字段类型信息")
        void testValidateFieldTypes() {
            interpreter.evalRepl("var fieldTypes = \"\"");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"typed\") { target, args ->\n" +
                "    for (f in target.fields) {\n" +
                "        fieldTypes = fieldTypes + f.name + \":\" + f.type + \" \"\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("annotation class typed");
            interpreter.evalRepl("@typed class Pair(val first: String, val second: Int)");
            String types = interpreter.evalRepl("fieldTypes").asString();
            assertTrue(types.contains("first:String"));
            assertTrue(types.contains("second:Int"));
        }
    }

    // ============ 场景2: 自定义日志注解 ============

    @Nested
    @DisplayName("场景: 日志注解")
    class LoggingAnnotation {

        @Test
        @DisplayName("@log 注解记录类创建日志")
        void testLogAnnotation() {
            interpreter.evalRepl(
                "var logs = mutableListOf()\n" +
                "registerAnnotationProcessor(\"log\") { target, args ->\n" +
                "    logs.add(\"Registered class: \" + target.name)\n" +
                "}"
            );
            interpreter.evalRepl("annotation class log");
            interpreter.evalRepl("@log class ServiceA(val name: String)");
            interpreter.evalRepl("@log class ServiceB(val port: Int)");
            assertEquals(2, interpreter.evalRepl("logs.size()").asInt());
            assertEquals("Registered class: ServiceA", interpreter.evalRepl("logs[0]").asString());
            assertEquals("Registered class: ServiceB", interpreter.evalRepl("logs[1]").asString());
        }

        @Test
        @DisplayName("@log 注解带参数控制日志级别")
        void testLogAnnotationWithLevel() {
            interpreter.evalRepl("var capturedLevel = \"\"");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"logLevel\") { target, args ->\n" +
                "    capturedLevel = args[\"level\"]\n" +
                "}"
            );
            interpreter.evalRepl("annotation class logLevel(val level: String)");
            interpreter.evalRepl("@logLevel(level = \"DEBUG\") class DebugService");
            assertEquals("DEBUG", interpreter.evalRepl("capturedLevel").asString());
        }
    }

    // ============ 场景3: 注册表/工厂注解 ============

    @Nested
    @DisplayName("场景: 注册表注解")
    class RegistryAnnotation {

        @Test
        @DisplayName("@component 注解自动注册到全局注册表")
        void testComponentRegistry() {
            interpreter.evalRepl("val registry = mutableListOf()");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"component\") { target, args ->\n" +
                "    registry.add(target.name)\n" +
                "}"
            );
            interpreter.evalRepl("annotation class component");
            interpreter.evalRepl("@component class UserService(val db: String)");
            interpreter.evalRepl("@component class AuthService(val secret: String)");
            interpreter.evalRepl("@component class EmailService(val host: String)");
            assertEquals(3, interpreter.evalRepl("registry.size()").asInt());
            assertTrue(interpreter.evalRepl("registry.contains(\"UserService\")").asBoolean());
            assertTrue(interpreter.evalRepl("registry.contains(\"AuthService\")").asBoolean());
            assertTrue(interpreter.evalRepl("registry.contains(\"EmailService\")").asBoolean());
        }

        @Test
        @DisplayName("@named 注解用自定义名称注册")
        void testNamedRegistry() {
            interpreter.evalRepl("val namedRegistry = mapOf()");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"named\") { target, args ->\n" +
                "    namedRegistry[args[\"value\"]] = target.name\n" +
                "}"
            );
            interpreter.evalRepl("annotation class named(val value: String)");
            interpreter.evalRepl("@named(value = \"users\") class UserRepo");
            interpreter.evalRepl("@named(value = \"orders\") class OrderRepo");
            assertEquals("UserRepo", interpreter.evalRepl("namedRegistry[\"users\"]").asString());
            assertEquals("OrderRepo", interpreter.evalRepl("namedRegistry[\"orders\"]").asString());
        }
    }

    // ============ 场景4: @data + @builder 组合 ============

    @Nested
    @DisplayName("场景: 内置注解组合")
    class BuiltinAnnotationCombo {

        @Test
        @DisplayName("@data 类 copy 后字段独立")
        void testDataCopyIndependence() {
            interpreter.evalRepl("@data class Config(val host: String, val port: Int, val debug: Boolean)");
            interpreter.evalRepl("val c1 = Config(\"localhost\", 8080, false)");
            interpreter.evalRepl("val c2 = c1.copy(debug = true)");
            assertFalse(interpreter.evalRepl("c1.debug").asBoolean());
            assertTrue(interpreter.evalRepl("c2.debug").asBoolean());
            assertEquals("localhost", interpreter.evalRepl("c2.host").asString());
        }

        @Test
        @DisplayName("@builder 缺少必填字段时报错")
        void testBuilderMissingField() {
            interpreter.evalRepl("@builder class Required(val a: Int, val b: Int)");
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("Required.builder().a(1).build()")
            );
        }

        @Test
        @DisplayName("@data @builder 综合: builder 创建 + copy 修改 + 解构")
        void testDataBuilderFullWorkflow() {
            interpreter.evalRepl("@data @builder class Vec3(val x: Int, val y: Int, val z: Int)");
            // builder 创建
            interpreter.evalRepl("val v1 = Vec3.builder().x(1).y(2).z(3).build()");
            assertEquals(1, interpreter.evalRepl("v1.x").asInt());
            // copy 修改
            interpreter.evalRepl("val v2 = v1.copy(z = 10)");
            assertEquals(10, interpreter.evalRepl("v2.z").asInt());
            assertEquals(2, interpreter.evalRepl("v2.y").asInt());
            // 解构
            interpreter.evalRepl("val (a, b, c) = v2");
            assertEquals(1, interpreter.evalRepl("a").asInt());
            assertEquals(2, interpreter.evalRepl("b").asInt());
            assertEquals(10, interpreter.evalRepl("c").asInt());
        }
    }

    // ============ 场景5: 多处理器叠加 ============

    @Nested
    @DisplayName("场景: 多处理器叠加")
    class MultiProcessorScenario {

        @Test
        @DisplayName("同一注解名称注册多个处理器,全部执行")
        void testMultipleProcessorsForSameAnnotation() {
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"multi\") { target, args -> count = count + 1 }"
            );
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"multi\") { target, args -> count = count + 10 }"
            );
            interpreter.evalRepl("annotation class multi");
            interpreter.evalRepl("@multi class Foo");
            assertEquals(11, interpreter.evalRepl("count").asInt());
        }

        @Test
        @DisplayName("不同注解各自独立触发")
        void testDifferentAnnotationsIndependent() {
            interpreter.evalRepl("var aTriggered = false");
            interpreter.evalRepl("var bTriggered = false");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"annA\") { target, args -> aTriggered = true }"
            );
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"annB\") { target, args -> bTriggered = true }"
            );
            interpreter.evalRepl("annotation class annA");
            interpreter.evalRepl("annotation class annB");
            interpreter.evalRepl("@annA class OnlyA");
            assertTrue(interpreter.evalRepl("aTriggered").asBoolean());
            assertFalse(interpreter.evalRepl("bTriggered").asBoolean());
        }

        @Test
        @DisplayName("多注解叠加在同一个类上")
        void testMultipleAnnotationsOnSameClass() {
            interpreter.evalRepl("var tags = mutableListOf()");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"tagA\") { target, args -> tags.add(\"A:\" + target.name) }"
            );
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"tagB\") { target, args -> tags.add(\"B:\" + target.name) }"
            );
            interpreter.evalRepl("annotation class tagA");
            interpreter.evalRepl("annotation class tagB");
            interpreter.evalRepl("@tagA @tagB class Both");
            assertEquals(2, interpreter.evalRepl("tags.size()").asInt());
            assertEquals("A:Both", interpreter.evalRepl("tags[0]").asString());
            assertEquals("B:Both", interpreter.evalRepl("tags[1]").asString());
        }
    }

    // ============ 场景6: 运行时注解查询 ============

    @Nested
    @DisplayName("场景: 运行时注解查询")
    class RuntimeAnnotationQuery {

        @Test
        @DisplayName("查询类的所有注解名称")
        void testQueryAnnotationNames() {
            interpreter.evalRepl("annotation class Api");
            interpreter.evalRepl("annotation class Cacheable");
            interpreter.evalRepl("@Api @Cacheable class UserController");
            NovaValue anns = interpreter.evalRepl("UserController.annotations");
            assertEquals(2, interpreter.evalRepl("UserController.annotations.size()").asInt());
        }

        @Test
        @DisplayName("查询注解参数值")
        void testQueryAnnotationArgValues() {
            interpreter.evalRepl("annotation class Route(val path: String)");
            interpreter.evalRepl("@Route(path = \"/users\") class UserEndpoint");
            interpreter.evalRepl("val ann = UserEndpoint.annotations[0]");
            assertEquals("Route", interpreter.evalRepl("ann[\"name\"]").asString());
            assertEquals("/users", interpreter.evalRepl("ann[\"args\"][\"path\"]").asString());
        }

        @Test
        @DisplayName("无注解的类返回空列表")
        void testNoAnnotationsReturnsEmpty() {
            interpreter.evalRepl("class Plain(val x: Int)");
            assertEquals(0, interpreter.evalRepl("Plain.annotations.size()").asInt());
        }

        @Test
        @DisplayName("多个注解带不同参数的查询")
        void testMultiAnnotationQuery() {
            interpreter.evalRepl("annotation class Author(val name: String)");
            interpreter.evalRepl("annotation class Version(val num: Int)");
            interpreter.evalRepl("@Author(name = \"Alice\") @Version(num = 2) class Doc");
            assertEquals(2, interpreter.evalRepl("Doc.annotations.size()").asInt());
            // 验证各注解参数
            interpreter.evalRepl("val authorAnn = Doc.annotations[0]");
            interpreter.evalRepl("val versionAnn = Doc.annotations[1]");
            assertEquals("Author", interpreter.evalRepl("authorAnn[\"name\"]").asString());
            assertEquals("Alice", interpreter.evalRepl("authorAnn[\"args\"][\"name\"]").asString());
            assertEquals("Version", interpreter.evalRepl("versionAnn[\"name\"]").asString());
            assertEquals(2, interpreter.evalRepl("versionAnn[\"args\"][\"num\"]").asInt());
        }
    }

    // ============ 场景7: 处理器中使用反射 API ============

    @Nested
    @DisplayName("场景: 处理器 + 反射 API 联动")
    class ProcessorWithReflection {

        @Test
        @DisplayName("处理器中遍历字段生成校验逻辑")
        void testProcessorIteratesFields() {
            interpreter.evalRepl("var fieldReport = \"\"");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"inspect\") { target, args ->\n" +
                "    for (f in target.fields) {\n" +
                "        fieldReport = fieldReport + f.visibility + \" \" + f.name + \"; \"\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("annotation class inspect");
            interpreter.evalRepl("@inspect class Cfg(val host: String, private val secret: String)");
            String report = interpreter.evalRepl("fieldReport").asString();
            assertTrue(report.contains("public host"));
            assertTrue(report.contains("private secret"));
        }

        @Test
        @DisplayName("处理器中读取方法列表")
        void testProcessorReadsMethods() {
            interpreter.evalRepl("var methodNames = mutableListOf()");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"api\") { target, args ->\n" +
                "    for (m in target.methods) {\n" +
                "        methodNames.add(m.name)\n" +
                "    }\n" +
                "}"
            );
            interpreter.evalRepl("annotation class api");
            interpreter.evalRepl(
                "@api class Controller {\n" +
                "    fun getAll() = \"all\"\n" +
                "    fun getById(id: Int) = \"one\"\n" +
                "}"
            );
            assertEquals(2, interpreter.evalRepl("methodNames.size()").asInt());
            assertTrue(interpreter.evalRepl("methodNames.contains(\"getAll\")").asBoolean());
            assertTrue(interpreter.evalRepl("methodNames.contains(\"getById\")").asBoolean());
        }

        @Test
        @DisplayName("处理器中根据字段数量做条件操作")
        void testProcessorConditionalOnFieldCount() {
            interpreter.evalRepl("var label = \"\"");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"classify\") { target, args ->\n" +
                "    val n = target.fields.size()\n" +
                "    if (n == 0) label = \"empty\"\n" +
                "    else if (n <= 2) label = \"small\"\n" +
                "    else label = \"large\"\n" +
                "}"
            );
            interpreter.evalRepl("annotation class classify");
            interpreter.evalRepl("@classify class Empty");
            assertEquals("empty", interpreter.evalRepl("label").asString());
            interpreter.evalRepl("@classify class Small(val a: Int)");
            assertEquals("small", interpreter.evalRepl("label").asString());
            interpreter.evalRepl("@classify class Large(val a: Int, val b: Int, val c: Int)");
            assertEquals("large", interpreter.evalRepl("label").asString());
        }
    }

    // ============ 场景8: Java 侧注解处理器 ============

    @Nested
    @DisplayName("场景: Java API 注册处理器")
    class JavaApiProcessor {

        @Test
        @DisplayName("Java 注解处理器接收 NovaClass 并设置静态字段")
        void testJavaProcessorSetsStaticField() {
            interpreter.registerAnnotationProcessor(new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() {
                    return "singleton";
                }

                @Override
                public void processClass(NovaValue target, Map<String, NovaValue> args, ExecutionContext ctx) {
                    NovaClass novaTarget = (NovaClass) target;
                    // 创建单例实例并存为静态字段
                    Interpreter interp = (Interpreter) ctx;
                    NovaValue instance = interp.instantiate(novaTarget, java.util.Collections.emptyList(), null);
                    novaTarget.setStaticField("instance", instance);
                }
            });
            interpreter.evalRepl("@singleton class AppConfig(val debug: Boolean = false)");
            NovaValue inst = interpreter.evalRepl("AppConfig.instance");
            assertNotNull(inst);
            assertTrue(inst instanceof NovaObject);
        }

        @Test
        @DisplayName("Java 处理器读取注解参数")
        void testJavaProcessorReadsArgs() {
            final String[] capturedValue = {""};
            interpreter.registerAnnotationProcessor(new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() {
                    return "endpoint";
                }

                @Override
                public void processClass(NovaValue target, Map<String, NovaValue> args, ExecutionContext ctx) {
                    NovaValue path = args.get("path");
                    if (path != null) {
                        capturedValue[0] = path.asString();
                    }
                }
            });
            interpreter.evalRepl("@endpoint(path = \"/api/v1\") class MyEndpoint");
            assertEquals("/api/v1", capturedValue[0]);
        }

        @Test
        @DisplayName("Java 处理器与 Nova 处理器共存")
        void testJavaAndNovaProcessorsCoexist() {
            // Java 处理器
            interpreter.registerAnnotationProcessor(new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() {
                    return "dual";
                }

                @Override
                public void processClass(NovaValue target, Map<String, NovaValue> args, ExecutionContext ctx) {
                    ((NovaClass) target).setStaticField("javaProcessed", NovaBoolean.TRUE);
                }
            });
            // Nova 处理器
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"dual\") { target, args ->\n" +
                "    println(\"Nova processed: \" + target.name)\n" +
                "}"
            );
            interpreter.evalRepl("annotation class dual");
            interpreter.evalRepl("@dual class Both");
            assertTrue(interpreter.evalRepl("Both.javaProcessed").asBoolean());
            assertTrue(getOutput().contains("Nova processed: Both"));
        }
    }

    // ============ 场景9: annotation class 约束 ============

    @Nested
    @DisplayName("场景: 注解类约束")
    class AnnotationClassConstraints {

        @Test
        @DisplayName("annotation class 不可实例化")
        void testCannotInstantiateAnnotation() {
            interpreter.evalRepl("annotation class NoInst");
            assertThrows(NovaRuntimeException.class, () ->
                interpreter.evalRepl("NoInst()")
            );
        }

        @Test
        @DisplayName("annotation class 带参数也不可实例化")
        void testCannotInstantiateAnnotationWithParams() {
            interpreter.evalRepl("annotation class NoInst2(val x: Int)");
            assertThrows(NovaRuntimeException.class, () ->
                interpreter.evalRepl("NoInst2(42)")
            );
        }

        @Test
        @DisplayName("annotation class 可以用 isAnnotation 标志识别")
        void testAnnotationFlag() {
            interpreter.evalRepl("annotation class Marker");
            NovaValue cls = interpreter.evalRepl("Marker");
            assertTrue(cls instanceof NovaClass);
            assertTrue(((NovaClass) cls).isAnnotation());
        }

        @Test
        @DisplayName("普通 class 没有 isAnnotation 标志")
        void testNonAnnotationFlag() {
            interpreter.evalRepl("class Normal(val x: Int)");
            NovaValue cls = interpreter.evalRepl("Normal");
            assertTrue(cls instanceof NovaClass);
            assertFalse(((NovaClass) cls).isAnnotation());
        }
    }

    // ============ 场景10: 处理器句柄 ============

    @Nested
    @DisplayName("场景: 处理器句柄")
    class ProcessorHandle {

        @Test
        @DisplayName("registerAnnotationProcessor 返回句柄对象")
        void testReturnsHandle() {
            interpreter.evalRepl(
                "val handle = registerAnnotationProcessor(\"htest\") { target, args -> }"
            );
            // handle 是 NovaMap，包含 unregister/register/replace
            assertNotNull(interpreter.evalRepl("handle[\"unregister\"]"));
            assertNotNull(interpreter.evalRepl("handle[\"register\"]"));
            assertNotNull(interpreter.evalRepl("handle[\"replace\"]"));
        }

        @Test
        @DisplayName("handle.unregister() 移除处理器")
        void testHandleUnregister() {
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl(
                "val handle = registerAnnotationProcessor(\"unreg\") { target, args -> count = count + 1 }"
            );
            interpreter.evalRepl("annotation class unreg");
            interpreter.evalRepl("@unreg class A");
            assertEquals(1, interpreter.evalRepl("count").asInt());

            // 注销后不再触发
            interpreter.evalRepl("handle.unregister()");
            interpreter.evalRepl("@unreg class B");
            assertEquals(1, interpreter.evalRepl("count").asInt());
        }

        @Test
        @DisplayName("handle.register() 重新注册处理器")
        void testHandleReRegister() {
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl(
                "val handle = registerAnnotationProcessor(\"rereg\") { target, args -> count = count + 1 }"
            );
            interpreter.evalRepl("annotation class rereg");

            interpreter.evalRepl("handle.unregister()");
            interpreter.evalRepl("@rereg class X");
            assertEquals(0, interpreter.evalRepl("count").asInt());

            interpreter.evalRepl("handle.register()");
            interpreter.evalRepl("@rereg class Y");
            assertEquals(1, interpreter.evalRepl("count").asInt());
        }

        @Test
        @DisplayName("handle.replace() 替换处理器 handler")
        void testHandleReplace() {
            interpreter.evalRepl("var label = \"\"");
            interpreter.evalRepl(
                "val handle = registerAnnotationProcessor(\"repl\") { target, args -> label = \"old\" }"
            );
            interpreter.evalRepl("annotation class repl");
            interpreter.evalRepl("@repl class First");
            assertEquals("old", interpreter.evalRepl("label").asString());

            // replace 替换 handler
            interpreter.evalRepl("handle.replace { target, args -> label = \"new\" }");
            interpreter.evalRepl("@repl class Second");
            assertEquals("new", interpreter.evalRepl("label").asString());
        }

        @Test
        @DisplayName("unregister 只移除当前句柄，不影响同名其他处理器")
        void testUnregisterOnlyAffectsOwnHandle() {
            interpreter.evalRepl("var a = 0");
            interpreter.evalRepl("var b = 0");
            interpreter.evalRepl(
                "val h1 = registerAnnotationProcessor(\"shared\") { target, args -> a = a + 1 }"
            );
            interpreter.evalRepl(
                "val h2 = registerAnnotationProcessor(\"shared\") { target, args -> b = b + 1 }"
            );
            interpreter.evalRepl("annotation class shared");

            interpreter.evalRepl("h1.unregister()");
            interpreter.evalRepl("@shared class Test");
            assertEquals(0, interpreter.evalRepl("a").asInt());
            assertEquals(1, interpreter.evalRepl("b").asInt());
        }

        @Test
        @DisplayName("replace 后 unregister 仍然有效")
        void testReplaceAndThenUnregister() {
            interpreter.evalRepl("var result = \"\"");
            interpreter.evalRepl(
                "val handle = registerAnnotationProcessor(\"combo\") { target, args -> result = \"v1\" }"
            );
            interpreter.evalRepl("annotation class combo");

            interpreter.evalRepl("handle.replace { target, args -> result = \"v2\" }");
            interpreter.evalRepl("@combo class A");
            assertEquals("v2", interpreter.evalRepl("result").asString());

            interpreter.evalRepl("handle.unregister()");
            interpreter.evalRepl("result = \"\"");
            interpreter.evalRepl("@combo class B");
            assertEquals("", interpreter.evalRepl("result").asString());
        }
    }

    // ============ 场景11: 处理器注册时机 ============

    @Nested
    @DisplayName("场景: 注册时机和顺序")
    class RegistrationTiming {

        @Test
        @DisplayName("先注册处理器后定义注解类,处理器正常触发")
        void testProcessorBeforeAnnotationClass() {
            interpreter.evalRepl("var triggered = false");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"early\") { target, args -> triggered = true }"
            );
            // 注解类和使用在处理器注册之后
            interpreter.evalRepl("annotation class early");
            interpreter.evalRepl("@early class Late");
            assertTrue(interpreter.evalRepl("triggered").asBoolean());
        }

        @Test
        @DisplayName("处理器执行顺序与注册顺序一致")
        void testProcessorExecutionOrder() {
            interpreter.evalRepl("var order = \"\"");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"ordered\") { target, args -> order = order + \"A\" }"
            );
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"ordered\") { target, args -> order = order + \"B\" }"
            );
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"ordered\") { target, args -> order = order + \"C\" }"
            );
            interpreter.evalRepl("annotation class ordered");
            interpreter.evalRepl("@ordered class X");
            assertEquals("ABC", interpreter.evalRepl("order").asString());
        }

        @Test
        @DisplayName("未注册处理器的注解不报错,仅记录到 annotations")
        void testUnregisteredAnnotationSilent() {
            interpreter.evalRepl("@mystery class Ghost");
            // 不报错
            assertEquals(1, interpreter.evalRepl("Ghost.annotations.size()").asInt());
            assertEquals("mystery",
                interpreter.evalRepl("Ghost.annotations[0][\"name\"]").asString());
        }
    }
}
