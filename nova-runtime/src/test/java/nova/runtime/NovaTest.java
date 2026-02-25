package nova.runtime;

import nova.runtime.*;
import nova.runtime.interpreter.NovaRuntimeException;
import nova.runtime.interpreter.NovaSecurityPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Nova 便捷 API")
class NovaTest {

    // ── 静态方法 ──────────────────────────────────────

    @Nested
    @DisplayName("静态 run()")
    class StaticRun {

        @Test
        @DisplayName("执行简单表达式")
        void runSimpleExpression() {
            assertEquals(3, Nova.run("1 + 2"));
        }

        @Test
        @DisplayName("执行带变量绑定的表达式")
        void runWithBindings() {
            Object result = Nova.run("x + y", "x", 10, "y", 20);
            assertEquals(30, result);
        }

        @Test
        @DisplayName("执行字符串表达式")
        void runStringExpression() {
            Object result = Nova.run("name + \" World\"", "name", "Hello");
            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("绑定参数个数为奇数时抛异常")
        void runOddBindingsThrows() {
            assertThrows(IllegalArgumentException.class, () -> Nova.run("x", "x"));
        }

        @Test
        @DisplayName("绑定 key 非字符串时抛异常")
        void runNonStringKeyThrows() {
            assertThrows(IllegalArgumentException.class, () -> Nova.run("x", 1, 2));
        }
    }

    // ── 静态 runFile ─────────────────────────────────

    @Nested
    @DisplayName("静态 runFile()")
    class StaticRunFile {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("执行文件")
        void runFile() throws Exception {
            File script = writeScript("val x = 10\nval y = 20\nx + y");
            assertEquals(30, Nova.runFile(script.getAbsolutePath()));
        }

        @Test
        @DisplayName("执行文件带变量绑定")
        void runFileWithBindings() throws Exception {
            File script = writeScript("n * n");
            assertEquals(100, Nova.runFile(script.getAbsolutePath(), "n", 10));
        }

        @Test
        @DisplayName("文件不存在时抛异常")
        void runFileMissing() {
            assertThrows(NovaRuntimeException.class, () -> Nova.runFile("nonexistent.nova"));
        }

        private File writeScript(String content) throws Exception {
            File file = tempDir.resolve("test.nova").toFile();
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try {
                writer.write(content);
            } finally {
                writer.close();
            }
            return file;
        }
    }

    // ── 实例方法 ──────────────────────────────────────

    @Nested
    @DisplayName("实例 eval/set/get")
    class InstanceMethods {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("eval 执行代码")
        void eval() {
            assertEquals(42, nova.eval("42"));
        }

        @Test
        @DisplayName("eval 声明语句返回赋值结果")
        void evalDeclarationReturnsValue() {
            assertEquals(1, nova.eval("val x = 1"));
        }

        @Test
        @DisplayName("set 和 get 变量")
        void setAndGet() {
            nova.set("x", 42);
            assertEquals(42, nova.get("x"));
        }

        @Test
        @DisplayName("get 不存在的变量返回 null")
        void getUndefined() {
            assertNull(nova.get("undefined_var"));
        }

        @Test
        @DisplayName("set 更新已存在的变量")
        void setUpdate() {
            nova.set("x", 1);
            nova.set("x", 2);
            assertEquals(2, nova.get("x"));
        }

        @Test
        @DisplayName("多次 eval 共享状态")
        void sharedState() {
            nova.eval("var count = 0");
            nova.eval("count = count + 1");
            nova.eval("count = count + 1");
            assertEquals(2, nova.get("count"));
        }

        @Test
        @DisplayName("fluent API 链式调用")
        void fluentApi() {
            Nova result = nova.set("a", 1).set("b", 2).set("c", 3);
            assertSame(nova, result);
            assertEquals(6, nova.eval("a + b + c"));
        }
    }

    // ── call 调用函数 ────────────────────────────────

    @Nested
    @DisplayName("call() 调用函数")
    class CallMethod {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("调用脚本中定义的函数")
        void callDefinedFunction() {
            nova.eval("fun add(a: Int, b: Int) = a + b");
            assertEquals(30, nova.call("add", 10, 20));
        }

        @Test
        @DisplayName("调用函数获取字符串返回值")
        void callReturnsString() {
            nova.eval("fun greet(name: String) = \"Hello, $name!\"");
            assertEquals("Hello, World!", nova.call("greet", "World"));
        }

        @Test
        @DisplayName("调用不存在的函数抛异常")
        void callUndefined() {
            assertThrows(NovaRuntimeException.class, () -> nova.call("notExist"));
        }

        @Test
        @DisplayName("调用非函数变量抛异常")
        void callNonCallable() {
            nova.set("x", 42);
            assertThrows(NovaRuntimeException.class, () -> nova.call("x"));
        }
    }

    // ── evalFile 实例方法 ────────────────────────────

    @Nested
    @DisplayName("实例 evalFile()")
    class InstanceEvalFile {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("evalFile 加载文件后 call 函数")
        void evalFileThenCall() throws Exception {
            File script = tempDir.resolve("utils.nova").toFile();
            Writer writer = new OutputStreamWriter(new FileOutputStream(script), "UTF-8");
            try {
                writer.write("fun double(n: Int) = n * 2");
            } finally {
                writer.close();
            }

            Nova nova = new Nova();
            nova.evalFile(script.getAbsolutePath());
            assertEquals(20, nova.call("double", 10));
        }
    }

    // ── compile 预编译 ─────────────────────────────────

    @Nested
    @DisplayName("compile() 预编译")
    class CompileTests {

        @Test
        @DisplayName("静态编译，多次执行")
        void compileAndRunMultipleTimes() {
            CompiledNova compiled = Nova.compile("x * x + 1");

            compiled.set("x", 3);
            assertEquals(10, compiled.run());

            compiled.set("x", 7);
            assertEquals(50, compiled.run());
        }

        @Test
        @DisplayName("run 带绑定参数")
        void runWithBindings() {
            CompiledNova compiled = Nova.compile("a + b");
            assertEquals(30, compiled.run("a", 10, "b", 20));
            assertEquals(70, compiled.run("a", 30, "b", 40));
        }

        @Test
        @DisplayName("编译函数定义，执行后可 call")
        void compileAndCallFunction() {
            CompiledNova compiled = Nova.compile("fun square(n: Int) = n * n");
            compiled.run();
            assertEquals(25, compiled.call("square", 5));
        }

        @Test
        @DisplayName("set/get 通过 CompiledNova 操作变量")
        void setAndGetViaCompiled() {
            CompiledNova compiled = Nova.compile("x + 1");
            compiled.set("x", 10);
            assertEquals(11, compiled.run());
            assertEquals(10, compiled.get("x"));
        }

        @Test
        @DisplayName("实例编译共享已有环境")
        void instanceCompileSharesEnv() {
            Nova nova = new Nova();
            nova.eval("val RATE = 0.08");
            CompiledNova compiled = nova.compile("price * (1 + RATE)", "rule.nova");

            compiled.set("price", 100);
            double result = ((Number) compiled.run()).doubleValue();
            assertEquals(108.0, result, 0.0001);

            assertSame(nova, compiled.getNova());
        }

        @Test
        @DisplayName("绑定参数个数为奇数时抛异常")
        void runOddBindingsThrows() {
            CompiledNova compiled = Nova.compile("x");
            assertThrows(IllegalArgumentException.class, () -> compiled.run("x"));
        }
    }

    // ── compileFile 预编译文件 ───────────────────────

    @Nested
    @DisplayName("compileFile() 预编译文件")
    class CompileFileTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("静态编译文件，多次执行")
        void compileFileAndRun() throws Exception {
            File script = writeScript("n * 2 + 1");

            CompiledNova compiled = Nova.compileFile(script.getAbsolutePath());

            compiled.set("n", 5);
            assertEquals(11, compiled.run());

            compiled.set("n", 10);
            assertEquals(21, compiled.run());
        }

        @Test
        @DisplayName("编译文件中的函数，执行后调用")
        void compileFileThenCall() throws Exception {
            File script = writeScript("fun triple(n: Int) = n * 3");

            CompiledNova compiled = Nova.compileFile(script.getAbsolutePath());
            compiled.run();
            assertEquals(30, compiled.call("triple", 10));
        }

        @Test
        @DisplayName("实例编译文件，共享环境")
        void instanceCompileFileSharesEnv() throws Exception {
            File script = writeScript("base + bonus");

            Nova nova = new Nova();
            nova.set("base", 100);
            CompiledNova compiled = nova.compileFile(script);
            compiled.set("bonus", 50);
            assertEquals(150, compiled.run());
        }

        private File writeScript(String content) throws Exception {
            File file = tempDir.resolve("test.nova").toFile();
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try {
                writer.write(content);
            } finally {
                writer.close();
            }
            return file;
        }
    }

    // ── 安全策略 ──────────────────────────────────────

    @Nested
    @DisplayName("安全策略")
    class SecurityPolicyTests {

        @Test
        @DisplayName("使用安全策略构造")
        void withSecurityPolicy() {
            Nova nova = new Nova(NovaSecurityPolicy.strict());
            assertEquals(42, nova.eval("42"));
        }
    }

    // ── 异常处理 ──────────────────────────────────────

    @Nested
    @DisplayName("异常处理")
    class ExceptionHandling {

        @Test
        @DisplayName("语法错误抛异常")
        void syntaxError() {
            assertThrows(Exception.class, () -> Nova.run("1 +"));
        }

        @Test
        @DisplayName("运行时错误抛异常")
        void runtimeError() {
            assertThrows(Exception.class, () -> Nova.run("1 / 0"));
        }
    }

    // ── getInterpreter ──────────────────────────────

    @Test
    @DisplayName("getInterpreter 返回底层解释器")
    void getInterpreter() {
        Nova nova = new Nova();
        assertNotNull(nova.getInterpreter());
    }
}
