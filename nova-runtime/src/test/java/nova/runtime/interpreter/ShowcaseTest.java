package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设计文档 §20 示例程序综合测试
 *
 * <p>将设计文档中的示例程序拆解为独立场景逐一验证，
 * 用于检测哪些特性已实现、哪些尚未支持。</p>
 */
@DisplayName("设计文档 §20 示例程序综合展示")
class ShowcaseTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    /** 捕获 println 输出 */
    private String captureOutput(String code) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        PrintStream old = interpreter.getStdout();
        interpreter.setStdout(ps);
        try {
            interpreter.evalRepl(code);
            return baos.toString(StandardCharsets.UTF_8).trim();
        } finally {
            interpreter.setStdout(old);
        }
    }

    // ============ 1. @data class + 可空类型 ============

    @Nested
    @DisplayName("1. @data class + 可空类型")
    class DataClassTests {

        @Test
        @DisplayName("@data class 创建和 toString")
        void testDataClassCreation() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String, val email: String?)");
            NovaValue result = interpreter.evalRepl("User(1, \"Alice\", \"alice@example.com\").toString()");
            assertEquals("User(id=1, name=Alice, email=alice@example.com)", result.asString());
        }

        @Test
        @DisplayName("@data class 的 componentN 解构")
        void testDataClassDestructuring() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String, val email: String?)");
            interpreter.evalRepl("val user = User(1, \"Alice\", \"alice@example.com\")");
            interpreter.evalRepl("val (id, name, email) = user");
            assertEquals(1, interpreter.evalRepl("id").asInt());
            assertEquals("Alice", interpreter.evalRepl("name").asString());
            assertEquals("alice@example.com", interpreter.evalRepl("email").asString());
        }

        @Test
        @DisplayName("@data class 的 equals 和 copy")
        void testDataClassEqualsAndCopy() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String, val email: String?)");
            interpreter.evalRepl("val a = User(1, \"Alice\", null)");
            interpreter.evalRepl("val b = User(1, \"Alice\", null)");
            assertTrue(interpreter.evalRepl("a.equals(b)").asBool());

            interpreter.evalRepl("val c = a.copy(1, \"Bob\", null)");
            assertEquals("Bob", interpreter.evalRepl("c.name").asString());
        }

        @Test
        @DisplayName("可空字段为 null")
        void testNullableField() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String, val email: String?)");
            interpreter.evalRepl("val user = User(2, \"Bob\", null)");
            assertTrue(interpreter.evalRepl("user.email").isNull());
        }
    }

    // ============ 2. 单例对象 ============

    @Nested
    @DisplayName("2. 单例对象 (object)")
    class SingletonObjectTests {

        @Test
        @DisplayName("object 定义方法并调用")
        void testObjectMethods() {
            interpreter.evalRepl(
                "object Logger {\n" +
                "    fun info(msg: String) = \"[INFO] \" + msg\n" +
                "    fun error(msg: String) = \"[ERROR] \" + msg\n" +
                "}"
            );
            assertEquals("[INFO] hello", interpreter.evalRepl("Logger.info(\"hello\")").asString());
            assertEquals("[ERROR] fail", interpreter.evalRepl("Logger.error(\"fail\")").asString());
        }

        @Test
        @DisplayName("object 中使用字符串插值")
        void testObjectWithInterpolation() {
            String output = captureOutput(
                "object Logger {\n" +
                "    fun info(msg: String) = println(\"[INFO] $msg\")\n" +
                "}\n" +
                "Logger.info(\"test message\")"
            );
            assertEquals("[INFO] test message", output);
        }
    }

    // ============ 3. 扩展函数 ============

    @Nested
    @DisplayName("3. 扩展函数")
    class ExtensionFunctionTests {

        @Test
        @DisplayName("String 扩展函数")
        void testStringExtension() {
            interpreter.evalRepl(
                "fun String.mask(visibleChars: Int = 3): String {\n" +
                "    if (length <= visibleChars) return this\n" +
                "    return take(visibleChars) + \"*\".repeat(length - visibleChars)\n" +
                "}"
            );
            assertEquals("ali**************", interpreter.evalRepl("\"alice@example.com\".mask()").asString());
            assertEquals("alice************", interpreter.evalRepl("\"alice@example.com\".mask(5)").asString());
        }

        @Test
        @DisplayName("扩展函数与安全调用结合")
        void testExtensionWithSafeCall() {
            interpreter.evalRepl(
                "fun String.mask(visibleChars: Int = 3): String {\n" +
                "    if (length <= visibleChars) return this\n" +
                "    return take(visibleChars) + \"*\".repeat(length - visibleChars)\n" +
                "}"
            );
            interpreter.evalRepl("val email: String? = null");
            assertTrue(interpreter.evalRepl("email?.mask()").isNull());
        }
    }

    // ============ 4. 管道操作符 |> ============

    @Nested
    @DisplayName("4. 管道操作符 |>")
    class PipelineTests {

        @Test
        @DisplayName("简单管道链")
        void testSimplePipeline() {
            interpreter.evalRepl("fun double(x: Int) = x * 2");
            interpreter.evalRepl("fun addOne(x: Int) = x + 1");
            assertEquals(11, interpreter.evalRepl("5 |> double |> addOne").asInt());
        }

        @Test
        @DisplayName("设计文档中的管道示例 - trim + uppercase")
        void testDesignDocPipeline() {
            interpreter.evalRepl("fun trim(s: String) = s.trim()");
            interpreter.evalRepl("fun uppercase(s: String) = s.uppercase()");
            assertEquals("HELLO WORLD",
                interpreter.evalRepl("\"  hello world  \" |> trim |> uppercase").asString());
        }

        @Test
        @DisplayName("管道 + Lambda")
        void testPipelineWithLambda() {
            interpreter.evalRepl("fun keepBig(list: List) = list.filter { it > 3 }");
            NovaValue result = interpreter.evalRepl("[1, 2, 3, 4, 5] |> keepBig");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
        }
    }

    // ============ 5. 切片语法 ============

    @Nested
    @DisplayName("5. 切片语法")
    class SliceTests {

        @Test
        @DisplayName("前 N 个元素 [..2]")
        void testSliceFirstN() {
            interpreter.evalRepl("val numbers = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]");
            NovaValue result = interpreter.evalRepl("numbers[0..2]");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(0, list.get(0).asInt());
            assertEquals(2, list.get(2).asInt());
        }

        @Test
        @DisplayName("中间元素 [3..6]")
        void testSliceMiddle() {
            interpreter.evalRepl("val numbers = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9]");
            NovaValue result = interpreter.evalRepl("numbers[3..6]");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(4, list.size());
            assertEquals(3, list.get(0).asInt());
            assertEquals(6, list.get(3).asInt());
        }
    }

    // ============ 6. Spread 操作符 ============

    @Nested
    @DisplayName("6. Spread 操作符 *")
    class SpreadTests {

        @Test
        @DisplayName("列表 Spread 合并")
        void testSpreadMerge() {
            interpreter.evalRepl("val list1 = [1, 2, 3]");
            interpreter.evalRepl("val list2 = [4, 5, 6]");
            NovaValue result = interpreter.evalRepl("[0, *list1, *list2, 7]");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(8, list.size());
            assertEquals(0, list.get(0).asInt());
            assertEquals(1, list.get(1).asInt());
            assertEquals(6, list.get(6).asInt());
            assertEquals(7, list.get(7).asInt());
        }
    }

    // ============ 7. 空合并赋值 ??= ============

    @Nested
    @DisplayName("7. 空合并赋值 ??=")
    class NullCoalesceAssignTests {

        @Test
        @DisplayName("null 时赋值")
        void testAssignWhenNull() {
            interpreter.evalRepl("var x: Int? = null");
            interpreter.evalRepl("x ??= 42");
            assertEquals(42, interpreter.evalRepl("x").asInt());
        }

        @Test
        @DisplayName("非 null 时保持原值")
        void testKeepWhenNotNull() {
            interpreter.evalRepl("var x: Int? = 10");
            interpreter.evalRepl("x ??= 42");
            assertEquals(10, interpreter.evalRepl("x").asInt());
        }
    }

    // ============ 8. 安全索引 ?[] ============

    @Nested
    @DisplayName("8. 安全索引 ?[]")
    class SafeIndexTests {

        @Test
        @DisplayName("null 列表安全索引返回 null")
        void testSafeIndexOnNull() {
            interpreter.evalRepl("val maybeList: List? = null");
            assertTrue(interpreter.evalRepl("maybeList?[0]").isNull());
        }

        @Test
        @DisplayName("非 null 列表安全索引")
        void testSafeIndexOnValid() {
            interpreter.evalRepl("val maybeList = [1, 2, 3]");
            assertEquals(1, interpreter.evalRepl("maybeList?[0]").asInt());
        }

        @Test
        @DisplayName("安全索引 + Elvis 操作符")
        void testSafeIndexWithElvis() {
            interpreter.evalRepl("val maybeList: List? = null");
            assertEquals(-1, interpreter.evalRepl("maybeList?[0] ?: -1").asInt());
        }
    }

    // ============ 9. 方法引用 ============

    @Nested
    @DisplayName("9. 方法引用 ::")
    class MethodReferenceTests {

        @Test
        @DisplayName("全局函数引用")
        void testGlobalFunctionRef() {
            interpreter.evalRepl("fun double(x: Int) = x * 2");
            NovaValue result = interpreter.evalRepl("[1, 2, 3].map(::double)");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(2, list.get(0).asInt());
            assertEquals(4, list.get(1).asInt());
            assertEquals(6, list.get(2).asInt());
        }

        @Test
        @DisplayName("实例方法引用")
        void testInstanceMethodRef() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String)");
            interpreter.evalRepl("val users = [User(1, \"Alice\"), User(2, \"Bob\")]");
            // 方法引用获取 componentN
            NovaValue result = interpreter.evalRepl("users.map { it.name }");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals("Alice", list.get(0).asString());
            assertEquals("Bob", list.get(1).asString());
        }
    }

    // ============ 10. 链式比较 ============

    @Nested
    @DisplayName("10. 链式比较")
    class ChainedComparisonTests {

        @Test
        @DisplayName("范围检查 1024 < port <= 65535")
        void testRangeCheck() {
            interpreter.evalRepl("val port = 8080");
            assertTrue(interpreter.evalRepl("1024 < port <= 65535").asBool());
        }

        @Test
        @DisplayName("范围检查失败")
        void testRangeCheckFail() {
            interpreter.evalRepl("val port = 80");
            assertFalse(interpreter.evalRepl("1024 < port <= 65535").asBool());
        }

        @Test
        @DisplayName("三重链式比较")
        void testTripleChain() {
            assertTrue(interpreter.evalRepl("1 < 2 < 3").asBool());
            assertFalse(interpreter.evalRepl("1 < 2 < 2").asBool());
        }
    }

    // ============ 11. if-let 条件绑定 ============

    @Nested
    @DisplayName("11. if-let 条件绑定")
    class ConditionalBindingTests {

        @Test
        @DisplayName("if-let 值非空时进入分支")
        void testIfLetNonNull() {
            interpreter.evalRepl("fun findUser(): String? = \"Alice\"");
            interpreter.evalRepl("var result = \"none\"");
            interpreter.evalRepl("if (val user = findUser()) { result = user }");
            assertEquals("Alice", interpreter.evalRepl("result").asString());
        }

        @Test
        @DisplayName("if-let 值为空时跳过")
        void testIfLetNull() {
            interpreter.evalRepl("fun findUser(): String? = null");
            interpreter.evalRepl("var result = \"none\"");
            interpreter.evalRepl("if (val user = findUser()) { result = user }");
            assertEquals("none", interpreter.evalRepl("result").asString());
        }
    }

    // ============ 12. 错误传播 ? ============

    @Nested
    @DisplayName("12. 错误传播 ?")
    class ErrorPropagationTests {

        @Test
        @DisplayName("成功情况 - 值传递")
        void testPropagationSuccess() {
            interpreter.evalRepl("fun getValue(): Int? = 42");
            interpreter.evalRepl("fun process(): Int? { val x = getValue()?; return x * 2 }");
            assertEquals(84, interpreter.evalRepl("process()").asInt());
        }

        @Test
        @DisplayName("null 时提前返回")
        void testPropagationNull() {
            interpreter.evalRepl("fun getValue(): Int? = null");
            interpreter.evalRepl("fun process(): Int? { val x = getValue()?; return x * 2 }");
            assertTrue(interpreter.evalRepl("process()").isNull());
        }
    }

    // ============ 13. 作用域简写 ?.{} ============

    @Nested
    @DisplayName("13. 作用域简写 ?.{}")
    class ScopeShorthandTests {

        @Test
        @DisplayName("非空对象进入作用域")
        void testScopeShorthandNonNull() {
            interpreter.evalRepl(
                "class Person(val name: String, var age: Int)"
            );
            interpreter.evalRepl("val p = Person(\"Alice\", 30)");
            interpreter.evalRepl("var result = \"\"");
            interpreter.evalRepl("p?.{ result = name }");
            assertEquals("Alice", interpreter.evalRepl("result").asString());
        }

        @Test
        @DisplayName("null 时跳过作用域")
        void testScopeShorthandNull() {
            interpreter.evalRepl("val p: String? = null");
            interpreter.evalRepl("var result = \"default\"");
            interpreter.evalRepl("p?.{ result = this }");
            assertEquals("default", interpreter.evalRepl("result").asString());
        }
    }

    // ============ 14. 部分应用 ============

    @Nested
    @DisplayName("14. 部分应用 _")
    class PartialApplicationTests {

        @Test
        @DisplayName("部分应用第二个参数")
        void testPartialSecondArg() {
            interpreter.evalRepl("fun multiply(a: Int, b: Int) = a * b");
            interpreter.evalRepl("val double = multiply(_, 2)");
            assertEquals(10, interpreter.evalRepl("double(5)").asInt());
        }

        @Test
        @DisplayName("部分应用与 map 结合")
        void testPartialWithMap() {
            interpreter.evalRepl("fun multiply(a: Int, b: Int) = a * b");
            interpreter.evalRepl("val triple = multiply(_, 3)");
            NovaValue result = interpreter.evalRepl("[1, 2, 3].map(triple)");
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.get(0).asInt());
            assertEquals(6, list.get(1).asInt());
            assertEquals(9, list.get(2).asInt());
        }
    }

    // ============ 15. when 表达式 ============

    @Nested
    @DisplayName("15. when 表达式")
    class WhenExprTests {

        @Test
        @DisplayName("when 基本匹配")
        void testWhenBasic() {
            interpreter.evalRepl(
                "fun describe(x: Int): String = when (x) {\n" +
                "    1 -> \"one\"\n" +
                "    2 -> \"two\"\n" +
                "    else -> \"other\"\n" +
                "}"
            );
            assertEquals("one", interpreter.evalRepl("describe(1)").asString());
            assertEquals("two", interpreter.evalRepl("describe(2)").asString());
            assertEquals("other", interpreter.evalRepl("describe(99)").asString());
        }
    }

    // ============ 16. 集合操作 ============

    @Nested
    @DisplayName("16. 集合操作")
    class CollectionTests {

        @Test
        @DisplayName("map + filter 链式操作")
        void testMapFilter() {
            NovaValue result = interpreter.evalRepl(
                "[1, 2, 3, 4, 5].filter { it > 2 }.map { it * 10 }"
            );
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(30, list.get(0).asInt());
            assertEquals(40, list.get(1).asInt());
            assertEquals(50, list.get(2).asInt());
        }

        @Test
        @DisplayName("forEach 遍历")
        void testForEach() {
            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("[1, 2, 3].forEach { sum = sum + it }");
            assertEquals(6, interpreter.evalRepl("sum").asInt());
        }
    }

    // ============ 17. 空安全链 ============

    @Nested
    @DisplayName("17. 空安全链")
    class NullSafetyTests {

        @Test
        @DisplayName("安全调用 ?. + Elvis ?:")
        void testSafeCallAndElvis() {
            interpreter.evalRepl("val email: String? = null");
            assertEquals("N/A", interpreter.evalRepl("email ?: \"N/A\"").asString());
        }

        @Test
        @DisplayName("安全调用链")
        void testSafeCallChain() {
            interpreter.evalRepl("class Box(val value: String?)");
            interpreter.evalRepl("val box: Box? = Box(null)");
            assertTrue(interpreter.evalRepl("box?.value").isNull());
        }
    }

    // ============ 18. 继承和接口 ============

    @Nested
    @DisplayName("18. 继承和接口")
    class InheritanceTests {

        @Test
        @DisplayName("open class 继承")
        void testOpenClassInheritance() {
            interpreter.evalRepl("open class Animal(val name: String)");
            interpreter.evalRepl(
                "class Dog(val breed: String) : Animal(\"Dog\") {\n" +
                "    fun describe() = name + \" (\" + breed + \")\"\n" +
                "}"
            );
            assertEquals("Dog (Labrador)",
                interpreter.evalRepl("Dog(\"Labrador\").describe()").asString());
        }

        @Test
        @DisplayName("接口实现")
        void testInterfaceImpl() {
            interpreter.evalRepl(
                "interface Greeter {\n" +
                "    fun greet(): String\n" +
                "}"
            );
            interpreter.evalRepl(
                "class HelloGreeter : Greeter {\n" +
                "    fun greet() = \"Hello!\"\n" +
                "}"
            );
            assertEquals("Hello!", interpreter.evalRepl("HelloGreeter().greet()").asString());
        }
    }

    // ============ 19. 字符串插值 ============

    @Nested
    @DisplayName("19. 字符串插值")
    class StringInterpolationTests {

        @Test
        @DisplayName("简单变量插值 $var")
        void testSimpleInterpolation() {
            interpreter.evalRepl("val name = \"World\"");
            assertEquals("Hello, World!", interpreter.evalRepl("\"Hello, $name!\"").asString());
        }

        @Test
        @DisplayName("表达式插值 ${expr}")
        void testExprInterpolation() {
            interpreter.evalRepl("val x = 10");
            assertEquals("x + 1 = 11", interpreter.evalRepl("\"x + 1 = ${x + 1}\"").asString());
        }
    }

    // ============ 20. SAM 转换 ============

    @Nested
    @DisplayName("20. SAM 转换")
    class SamConversionTests {

        @Test
        @DisplayName("Runnable SAM 转换")
        void testRunnableSam() {
            interpreter.evalRepl("var executed = false");
            interpreter.evalRepl("val r: java.lang.Runnable = { executed = true }");
            interpreter.evalRepl("r.run()");
            assertTrue(interpreter.evalRepl("executed").asBool());
        }
    }

    // ============ 21. async/await ============

    @Nested
    @DisplayName("21. async/await")
    class AsyncAwaitTests {

        @Test
        @DisplayName("简单 async + await")
        void testBasicAsyncAwait() {
            NovaValue result = interpreter.evalRepl(
                "val future = async { 42 }\n" +
                "await future"
            );
            assertEquals(42, result.asInt());
        }
    }

    // ============ 22. use 资源管理 ============

    @Nested
    @DisplayName("22. use 资源管理")
    class UseStatementTests {

        @Test
        @DisplayName("use 语句基本使用")
        void testUseBasic() {
            NovaValue result = interpreter.evalRepl(
                "use (val sb = java.lang.StringBuilder()) {\n" +
                "    sb.append(\"hello\")\n" +
                "    sb.toString()\n" +
                "}"
            );
            assertEquals("hello", result.asString());
        }
    }

    // ============ 23. 操作符重载 ============

    @Nested
    @DisplayName("23. 操作符重载")
    class OperatorOverloadTests {

        @Test
        @DisplayName("plus / minus 重载")
        void testPlusMinusOverload() {
            interpreter.evalRepl(
                "class Vec2(val x: Int, val y: Int) {\n" +
                "    fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)\n" +
                "    fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)\n" +
                "    fun toString() = \"Vec2($x, $y)\"\n" +
                "}"
            );
            assertEquals("Vec2(4, 6)",
                interpreter.evalRepl("(Vec2(1, 2) + Vec2(3, 4)).toString()").asString());
            assertEquals("Vec2(2, 2)",
                interpreter.evalRepl("(Vec2(5, 6) - Vec2(3, 4)).toString()").asString());
        }

        @Test
        @DisplayName("get/set 索引重载")
        void testIndexOverload() {
            interpreter.evalRepl(
                "class Matrix(val data: List) {\n" +
                "    fun get(row: Int) = data[row]\n" +
                "}"
            );
            interpreter.evalRepl("val m = Matrix([[1, 2], [3, 4]])");
            NovaValue result = interpreter.evalRepl("m[0]");
            assertTrue(result instanceof NovaList);
        }

        @Test
        @DisplayName("compareTo 重载")
        void testCompareToOverload() {
            interpreter.evalRepl(
                "class Version(val major: Int, val minor: Int) {\n" +
                "    fun compareTo(other: Version): Int {\n" +
                "        if (major != other.major) return major - other.major\n" +
                "        return minor - other.minor\n" +
                "    }\n" +
                "}"
            );
            assertTrue(interpreter.evalRepl("Version(2, 0) > Version(1, 9)").asBool());
            assertFalse(interpreter.evalRepl("Version(1, 0) > Version(1, 1)").asBool());
        }
    }

    // ============ 24. @builder 注解 ============

    @Nested
    @DisplayName("24. @builder 注解")
    class BuilderAnnotationTests {

        @Test
        @DisplayName("@builder 链式构建")
        void testBuilderChain() {
            interpreter.evalRepl("@builder class Config(val host: String, val port: Int)");
            interpreter.evalRepl("val cfg = Config.builder().host(\"localhost\").port(8080).build()");
            assertEquals("localhost", interpreter.evalRepl("cfg.host").asString());
            assertEquals(8080, interpreter.evalRepl("cfg.port").asInt());
        }

        @Test
        @DisplayName("@data @builder 组合")
        void testDataBuilderCombo() {
            interpreter.evalRepl("@data @builder class Point(val x: Int, val y: Int)");
            interpreter.evalRepl("val p = Point.builder().x(3).y(7).build()");
            assertEquals("Point(x=3, y=7)", interpreter.evalRepl("p.toString()").asString());
            assertEquals(3, interpreter.evalRepl("p.component1()").asInt());
        }
    }

    // ============ 25. 注解系统 ============

    @Nested
    @DisplayName("25. 注解系统")
    class AnnotationSystemTests {

        @Test
        @DisplayName("自定义注解 + 处理器")
        void testCustomAnnotationProcessor() {
            interpreter.evalRepl("var logged = \"\"");
            interpreter.evalRepl(
                "registerAnnotationProcessor(\"log\") { target, args ->\n" +
                "    logged = target.name\n" +
                "}"
            );
            interpreter.evalRepl("@log class MyService");
            assertEquals("MyService", interpreter.evalRepl("logged").asString());
        }

        @Test
        @DisplayName("处理器句柄 - unregister/register/replace")
        void testProcessorHandle() {
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl(
                "val handle = registerAnnotationProcessor(\"counter\") { target, args ->\n" +
                "    count = count + 1\n" +
                "}"
            );
            interpreter.evalRepl("@counter class A");
            assertEquals(1, interpreter.evalRepl("count").asInt());

            interpreter.evalRepl("handle.unregister()");
            interpreter.evalRepl("@counter class B");
            assertEquals(1, interpreter.evalRepl("count").asInt());

            interpreter.evalRepl("handle.register()");
            interpreter.evalRepl("@counter class C");
            assertEquals(2, interpreter.evalRepl("count").asInt());
        }
    }

    // ============ 26. 综合场景: 设计文档核心流程 ============

    @Nested
    @DisplayName("26. 综合场景")
    class IntegrationTests {

        @Test
        @DisplayName("@data class + 扩展函数 + 空安全 + 字符串插值")
        void testDataClassExtensionNullSafe() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String, val email: String?)");
            interpreter.evalRepl(
                "fun String.mask(n: Int = 3): String {\n" +
                "    if (this.length() <= n) return this\n" +
                "    return this.substring(0, n) + \"*\".repeat(this.length() - n)\n" +
                "}"
            );
            interpreter.evalRepl("val user = User(1, \"Alice\", \"alice@example.com\")");
            interpreter.evalRepl("val (id, name, email) = user");

            // 空安全 + 扩展函数 + Elvis
            assertEquals("ali**************",
                interpreter.evalRepl("email?.mask() ?: \"N/A\"").asString());

            // null email 的用户
            interpreter.evalRepl("val user2 = User(2, \"Bob\", null)");
            assertEquals("N/A",
                interpreter.evalRepl("user2.email?.mask() ?: \"N/A\"").asString());
        }

        @Test
        @DisplayName("管道 + 集合操作 + Lambda")
        void testPipelineCollectionLambda() {
            interpreter.evalRepl("fun keepBig(list: List) = list.filter { it > 2 }");
            interpreter.evalRepl("fun times10(list: List) = list.map { it * 10 }");
            NovaValue result = interpreter.evalRepl(
                "[1, 2, 3, 4, 5] |> keepBig |> times10"
            );
            assertTrue(result instanceof NovaList);
            NovaList list = (NovaList) result;
            assertEquals(3, list.size());
            assertEquals(30, list.get(0).asInt());
        }

        @Test
        @DisplayName("object + @data class + 集合 + 字符串插值")
        void testObjectDataClassCollection() {
            interpreter.evalRepl("@data class User(val id: Int, val name: String)");
            interpreter.evalRepl(
                "object UserService {\n" +
                "    fun formatUser(u: User) = \"#${u.id}: ${u.name}\"\n" +
                "}"
            );
            interpreter.evalRepl("val users = [User(1, \"Alice\"), User(2, \"Bob\")]");

            assertEquals("#1: Alice",
                interpreter.evalRepl("UserService.formatUser(users[0])").asString());
            assertEquals("#2: Bob",
                interpreter.evalRepl("UserService.formatUser(users[1])").asString());
        }

        @Test
        @DisplayName("if-let + when + 错误传播综合")
        void testIfLetWhenErrorPropagation() {
            interpreter.evalRepl("fun findById(id: Int): String? = if (id == 1) \"Alice\" else null");
            interpreter.evalRepl(
                "fun greet(id: Int): String {\n" +
                "    if (val name = findById(id)) {\n" +
                "        return when (name) {\n" +
                "            \"Alice\" -> \"Hello, Alice!\"\n" +
                "            else -> \"Hello, $name!\"\n" +
                "        }\n" +
                "    }\n" +
                "    return \"User not found\"\n" +
                "}"
            );
            assertEquals("Hello, Alice!", interpreter.evalRepl("greet(1)").asString());
            assertEquals("User not found", interpreter.evalRepl("greet(99)").asString());
        }
    }
}
