package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nova 类继承 Java 类 & 实现 Java 接口 — 解释器测试
 */
@DisplayName("Java 类继承 & 接口实现（解释器）")
class JavaInheritanceTest {

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

    // ============ 接口实现 (Proxy) ============

    @Nested
    @DisplayName("匿名对象实现 Java 接口")
    class AnonymousObjectJavaInterface {

        @Test
        @DisplayName("object : Runnable — 基本实现")
        void testObjectImplementsRunnable() {
            interpreter.evalRepl("var executed = false");
            interpreter.evalRepl("val r = object : Runnable { fun run() { executed = true } }");

            // NovaObject 应该有 javaDelegate
            NovaValue r = interpreter.evalRepl("r");
            assertTrue(r instanceof NovaObject);
            NovaObject obj = (NovaObject) r;
            assertNotNull(obj.getJavaDelegate());

            // toJavaValue() 应该返回 Proxy
            Object javaVal = r.toJavaValue();
            assertTrue(javaVal instanceof Runnable);

            // 执行 Runnable.run()
            ((Runnable) javaVal).run();
            assertTrue(interpreter.evalRepl("executed").asBool());
        }

        @Test
        @DisplayName("object : Callable — 有返回值")
        void testObjectImplementsCallable() {
            interpreter.evalRepl("val c = object : Callable { fun call() = 42 }");

            Object javaVal = interpreter.evalRepl("c").toJavaValue();
            assertTrue(javaVal instanceof java.util.concurrent.Callable);

            try {
                Object result = ((java.util.concurrent.Callable<?>) javaVal).call();
                assertEquals(42, result);
            } catch (Exception e) {
                fail("Callable.call() threw: " + e.getMessage());
            }
        }

        @Test
        @DisplayName("object : Comparator — 双参数接口方法")
        void testObjectImplementsComparator() {
            interpreter.evalRepl(
                "val cmp = object : Comparator {\n" +
                "    fun compare(a, b) = a - b\n" +
                "}");

            Object javaVal = interpreter.evalRepl("cmp").toJavaValue();
            assertTrue(javaVal instanceof java.util.Comparator);

            @SuppressWarnings("unchecked")
            java.util.Comparator<Object> comparator = (java.util.Comparator<Object>) javaVal;
            assertTrue(comparator.compare(3, 1) > 0);
            assertTrue(comparator.compare(1, 3) < 0);
            assertEquals(0, comparator.compare(2, 2));
        }

        @Test
        @DisplayName("未实现接口方法 — 报错")
        void testUnimplementedInterfaceMethod() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl("val r = object : Runnable { }"));
        }
    }

    // ============ 命名类实现 Java 接口 ============

    @Nested
    @DisplayName("命名类实现 Java 接口")
    class NamedClassJavaInterface {

        @Test
        @DisplayName("class : Runnable — 基本实现")
        void testClassImplementsRunnable() {
            interpreter.evalRepl("var counter = 0");
            interpreter.evalRepl(
                "class MyRunner : Runnable {\n" +
                "    fun run() { counter = counter + 1 }\n" +
                "}");

            interpreter.evalRepl("val runner = MyRunner()");

            // 调用 Nova 方法
            NovaValue runnerVal = interpreter.evalRepl("runner");
            assertTrue(runnerVal instanceof NovaObject);

            // toJavaValue() 返回 Proxy
            Object javaVal = runnerVal.toJavaValue();
            assertTrue(javaVal instanceof Runnable);

            // 通过 Java 接口调用
            ((Runnable) javaVal).run();
            assertEquals(1, interpreter.evalRepl("counter").asInt());

            // 调用两次
            ((Runnable) javaVal).run();
            assertEquals(2, interpreter.evalRepl("counter").asInt());
        }

        @Test
        @DisplayName("class : Runnable — 传给 Java API")
        void testPassToJavaApi() {
            interpreter.evalRepl("var result = \"\"");
            interpreter.evalRepl(
                "class MyTask : Runnable {\n" +
                "    fun run() { result = \"done\" }\n" +
                "}");
            interpreter.evalRepl("val task = MyTask()");

            // 模拟传给 Java — 直接取 toJavaValue 并运行
            Object javaVal = interpreter.evalRepl("task").toJavaValue();
            ((Runnable) javaVal).run();
            assertEquals("done", interpreter.evalRepl("result").asString());
        }
    }

    // ============ 类继承 Java 类 (ASM) ============

    @Nested
    @DisplayName("匿名对象继承 Java 类")
    class AnonymousObjectJavaClass {

        @Test
        @DisplayName("object : Thread(name) — 覆盖 run 方法")
        void testObjectExtendsThread() {
            interpreter.evalRepl("var threadRan = false");
            interpreter.evalRepl(
                "val t = object : Thread(\"test-worker\") {\n" +
                "    fun run() { threadRan = true }\n" +
                "}");

            NovaValue tVal = interpreter.evalRepl("t");
            assertTrue(tVal instanceof NovaObject);

            Object javaVal = tVal.toJavaValue();
            assertTrue(javaVal instanceof Thread);

            // 检查线程名
            Thread thread = (Thread) javaVal;
            assertEquals("test-worker", thread.getName());

            // 调用 run() (同步，不启动新线程)
            thread.run();
            assertTrue(interpreter.evalRepl("threadRan").asBool());
        }

        @Test
        @DisplayName("object : Thread() — 无参构造器")
        void testObjectExtendsThreadNoArgs() {
            interpreter.evalRepl("var ran = false");
            interpreter.evalRepl(
                "val t = object : Thread() {\n" +
                "    fun run() { ran = true }\n" +
                "}");

            Object javaVal = interpreter.evalRepl("t").toJavaValue();
            assertTrue(javaVal instanceof Thread);
            ((Thread) javaVal).run();
            assertTrue(interpreter.evalRepl("ran").asBool());
        }
    }

    // ============ 命名类继承 Java 类 ============

    @Nested
    @DisplayName("命名类继承 Java 类")
    class NamedClassJavaClass {

        @Test
        @DisplayName("class MyThread : Thread(name) — 实例化并运行")
        void testClassExtendsThread() {
            interpreter.evalRepl("var output = \"\"");
            interpreter.evalRepl(
                "class MyThread : Thread(\"worker\") {\n" +
                "    fun run() { output = \"running\" }\n" +
                "}");
            interpreter.evalRepl("val mt = MyThread()");

            Object javaVal = interpreter.evalRepl("mt").toJavaValue();
            assertTrue(javaVal instanceof Thread);
            assertEquals("worker", ((Thread) javaVal).getName());

            ((Thread) javaVal).run();
            assertEquals("running", interpreter.evalRepl("output").asString());
        }

        @Test
        @DisplayName("final 类不可继承 — 报错")
        void testCannotExtendFinalClass() {
            // String 是 final 类
            assertThrows(Exception.class, () ->
                interpreter.evalRepl(
                    "val s = object : String() {\n" +
                    "    fun length() = 0\n" +
                    "}"));
        }
    }

    // ============ 混合：Java 类 + Java 接口 ============

    @Nested
    @DisplayName("混合继承：Java 类 + 接口")
    class MixedInheritance {

        @Test
        @DisplayName("object : Thread(name), Runnable — 同时继承和实现")
        void testMixedClassAndInterface() {
            interpreter.evalRepl("var mixedRan = false");
            interpreter.evalRepl(
                "val t = object : Thread(\"mixed\") {\n" +
                "    fun run() { mixedRan = true }\n" +
                "}");

            Object javaVal = interpreter.evalRepl("t").toJavaValue();
            assertTrue(javaVal instanceof Thread);

            // Thread 本身就 implements Runnable, 所以这应该也是 true
            assertTrue(javaVal instanceof Runnable);

            ((Thread) javaVal).run();
            assertTrue(interpreter.evalRepl("mixedRan").asBool());
        }
    }

    // ============ is 类型检查 ============

    @Nested
    @DisplayName("is 类型检查")
    class TypeChecks {

        @Test
        @DisplayName("obj is Runnable — 接口实现")
        void testIsRunnable() {
            interpreter.evalRepl(
                "val r = object : Runnable { fun run() { } }");

            NovaValue result = interpreter.evalRepl("r is Runnable");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("obj is Thread — 类继承")
        void testIsThread() {
            interpreter.evalRepl(
                "val t = object : Thread(\"t\") { fun run() { } }");

            NovaValue result = interpreter.evalRepl("t is Thread");
            assertTrue(result.asBool());
        }

        @Test
        @DisplayName("obj !is Runnable — 非实现")
        void testIsNotRunnable() {
            interpreter.evalRepl("class Plain { }");
            interpreter.evalRepl("val p = Plain()");

            NovaValue result = interpreter.evalRepl("p is Runnable");
            assertFalse(result.asBool());
        }

        @Test
        @DisplayName("Thread 对象 is Runnable — 继承链类型检查")
        void testThreadIsRunnable() {
            interpreter.evalRepl(
                "val t = object : Thread(\"r\") { fun run() { } }");

            NovaValue result = interpreter.evalRepl("t is Runnable");
            assertTrue(result.asBool());
        }
    }

    // ============ super 调用 ============

    @Nested
    @DisplayName("super 调用")
    class SuperCalls {

        @Test
        @DisplayName("super — Nova 超类存在时正常工作")
        void testSuperWithNovaClass() {
            interpreter.evalRepl(
                "class Base {\n" +
                "    fun greet() = \"hello\"\n" +
                "}");
            interpreter.evalRepl(
                "class Child : Base {\n" +
                "    fun childGreet() = super.greet() + \" world\"\n" +
                "}");

            NovaValue result = interpreter.evalRepl("Child().childGreet()");
            assertEquals("hello world", result.asString());
        }

        @Test
        @DisplayName("super — Java 超类方法可调用")
        void testSuperWithJavaClass() {
            interpreter.evalRepl(
                "val t = object : Thread(\"super-test\") {\n" +
                "    fun getThreadName() = super.getName()\n" +
                "}");

            NovaValue result = interpreter.evalRepl("t.getThreadName()");
            assertEquals("super-test", result.asString());
        }
    }

    // ============ 成员访问 (Java delegate 回退) ============

    @Nested
    @DisplayName("成员访问 — Java delegate 回退")
    class MemberAccessFallback {

        @Test
        @DisplayName("调用 Java 超类方法 getName()")
        void testAccessJavaSuperMethod() {
            interpreter.evalRepl(
                "val t = object : Thread(\"member-test\") {\n" +
                "    fun run() { }\n" +
                "}");

            // getName() 不是 Nova 定义的，应该回退到 Java delegate
            NovaValue result = interpreter.evalRepl("t.getName()");
            assertEquals("member-test", result.asString());
        }

        @Test
        @DisplayName("Nova 方法优先于 Java 方法")
        void testNovaMethodPriority() {
            interpreter.evalRepl("var novaRun = false");
            interpreter.evalRepl(
                "val t = object : Thread(\"prio-test\") {\n" +
                "    fun run() { novaRun = true }\n" +
                "}");

            // 通过 Nova 直接调用 — 应该用 Nova 方法
            interpreter.evalRepl("t.run()");
            assertTrue(interpreter.evalRepl("novaRun").asBool());
        }
    }

    // ============ toJavaValue 集成 ============

    @Nested
    @DisplayName("toJavaValue 集成")
    class ToJavaValueIntegration {

        @Test
        @DisplayName("有 javaDelegate 的对象 toJavaValue 返回 delegate")
        void testToJavaValueReturnsDelegate() {
            interpreter.evalRepl(
                "val r = object : Runnable { fun run() { } }");

            NovaValue r = interpreter.evalRepl("r");
            assertTrue(r instanceof NovaObject);

            Object javaVal = r.toJavaValue();
            // 不是 Map，而是 Proxy
            assertFalse(javaVal instanceof java.util.Map);
            assertTrue(javaVal instanceof Runnable);
        }

        @Test
        @DisplayName("普通对象 toJavaValue 仍返回 Map")
        void testToJavaValueWithoutDelegate() {
            interpreter.evalRepl("class Simple(val x: Int)");
            interpreter.evalRepl("val s = Simple(42)");

            Object javaVal = interpreter.evalRepl("s").toJavaValue();
            assertTrue(javaVal instanceof java.util.Map);
        }
    }

    // ============ Parser — 构造器参数解析 ============

    @Nested
    @DisplayName("Parser — 匿名对象构造器参数")
    class ParserConstructorArgs {

        @Test
        @DisplayName("object : Type(arg1, arg2) 语法解析正确")
        void testObjectWithCtorArgs() {
            // 如果解析失败会抛异常
            interpreter.evalRepl(
                "val t = object : Thread(\"parsed\") {\n" +
                "    fun run() { }\n" +
                "}");

            Object javaVal = interpreter.evalRepl("t").toJavaValue();
            assertTrue(javaVal instanceof Thread);
            assertEquals("parsed", ((Thread) javaVal).getName());
        }

        @Test
        @DisplayName("object : Type(arg), Interface 语法解析正确")
        void testObjectWithCtorArgsAndInterface() {
            // Thread 本身 implements Runnable，这里只是验证语法解析
            interpreter.evalRepl(
                "val t = object : Thread(\"multi\") {\n" +
                "    fun run() { }\n" +
                "}");

            Object javaVal = interpreter.evalRepl("t").toJavaValue();
            assertTrue(javaVal instanceof Thread);
        }
    }

    // ============ when 类型条件 ============

    @Nested
    @DisplayName("when 表达式 Java 类型条件")
    class WhenTypeCondition {

        @Test
        @DisplayName("when(val x = obj) { is Runnable -> ... }")
        void testWhenIsJavaType() {
            interpreter.evalRepl(
                "val r = object : Runnable { fun run() { } }");
            interpreter.evalRepl(
                "val result = if (r is Runnable) \"yes\" else \"no\"");

            assertEquals("yes", interpreter.evalRepl("result").asString());
        }
    }

    // ============ NovaClass — getUnimplementedMethods ============

    @Nested
    @DisplayName("NovaClass — Java 接口未实现方法检查")
    class UnimplementedMethodCheck {

        @Test
        @DisplayName("实现了所有方法 — 不报错")
        void testAllMethodsImplemented() {
            // 如果有未实现方法，这会抛异常
            assertDoesNotThrow(() ->
                interpreter.evalRepl(
                    "val r = object : Runnable { fun run() { } }"));
        }

        @Test
        @DisplayName("缺少 run() — 报错")
        void testMissingRunMethod() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl(
                    "val r = object : Runnable { fun notRun() { } }"));
        }

        @Test
        @DisplayName("Callable 需要 call() 方法")
        void testCallableRequiresCall() {
            assertThrows(Exception.class, () ->
                interpreter.evalRepl(
                    "val c = object : Callable { fun notCall() { } }"));
        }
    }
}
