package nova.runtime;

import nova.runtime.*;
import nova.runtime.interpreter.NovaExt;
import nova.runtime.interpreter.NovaFunc;
import nova.runtime.interpreter.NovaLibrary;
import nova.runtime.interpreter.NovaNativeFunction;
import nova.runtime.interpreter.NovaRuntimeException;
import nova.runtime.interpreter.NovaSecurityPolicy;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ── hasFunction ──────────────────────────────────

    @Nested
    @DisplayName("hasFunction()")
    class HasFunctionTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("脚本定义的函数返回 true")
        void scriptDefinedFunction() {
            nova.eval("fun greet(name: String) = \"Hi, $name\"");
            assertTrue(nova.hasFunction("greet"));
        }

        @Test
        @DisplayName("defineFunction 定义的函数返回 true")
        void javaDefinedFunction() {
            nova.defineFunction("myFunc", () -> 42);
            assertTrue(nova.hasFunction("myFunc"));
        }

        @Test
        @DisplayName("defineVal 注入的 NovaNativeFunction 返回 true")
        void nativeFunctionViaDefineVal() {
            nova.defineVal("nf", NovaNativeFunction.create("nf", () -> NovaInt.of(1)));
            assertTrue(nova.hasFunction("nf"));
        }

        @Test
        @DisplayName("未定义的函数返回 false")
        void undefinedFunction() {
            assertFalse(nova.hasFunction("notExist"));
        }

        @Test
        @DisplayName("非函数变量返回 false")
        void nonCallableVariable() {
            nova.set("x", 42);
            assertFalse(nova.hasFunction("x"));
        }

        @Test
        @DisplayName("字符串变量返回 false")
        void stringVariable() {
            nova.set("s", "hello");
            assertFalse(nova.hasFunction("s"));
        }
    }

    // ── defineFunction ──────────────────────────────

    @Nested
    @DisplayName("defineFunction() 便捷方法")
    class DefineFunctionTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        // ── 各参数数量正常值 ──

        @Test
        @DisplayName("0 参数函数")
        void function0() {
            nova.defineFunction("zero", () -> 42);
            assertEquals(42, nova.call("zero"));
        }

        @Test
        @DisplayName("1 参数函数")
        void function1() {
            nova.defineFunction("inc", (a) -> (int) a + 1);
            assertEquals(11, nova.call("inc", 10));
        }

        @Test
        @DisplayName("2 参数函数")
        void function2() {
            nova.defineFunction("add", (a, b) -> (int) a + (int) b);
            assertEquals(30, nova.call("add", 10, 20));
        }

        @Test
        @DisplayName("3 参数函数")
        void function3() {
            nova.defineFunction("sum3", (Object a, Object b, Object c) ->
                    (int) a + (int) b + (int) c);
            assertEquals(60, nova.call("sum3", 10, 20, 30));
        }

        @Test
        @DisplayName("4 参数函数")
        void function4() {
            nova.defineFunction("sum4", (Function4<Object, Object, Object, Object, Object>)
                    (a, b, c, d) -> (int) a + (int) b + (int) c + (int) d);
            assertEquals(100, nova.call("sum4", 10, 20, 30, 40));
        }

        @Test
        @DisplayName("5 参数函数")
        void function5() {
            nova.defineFunction("sum5", (Function5<Object, Object, Object, Object, Object, Object>)
                    (a, b, c, d, e) -> (int) a + (int) b + (int) c + (int) d + (int) e);
            assertEquals(150, nova.call("sum5", 10, 20, 30, 40, 50));
        }

        @Test
        @DisplayName("6 参数函数")
        void function6() {
            nova.defineFunction("sum6", (Function6<Object, Object, Object, Object, Object, Object, Object>)
                    (a, b, c, d, e, f) -> (int) a + (int) b + (int) c + (int) d + (int) e + (int) f);
            assertEquals(210, nova.call("sum6", 10, 20, 30, 40, 50, 60));
        }

        @Test
        @DisplayName("7 参数函数")
        void function7() {
            nova.defineFunction("sum7", (Function7<Object, Object, Object, Object, Object, Object, Object, Object>)
                    (a, b, c, d, e, f, g) -> (int) a + (int) b + (int) c + (int) d + (int) e + (int) f + (int) g);
            assertEquals(280, nova.call("sum7", 10, 20, 30, 40, 50, 60, 70));
        }

        @Test
        @DisplayName("8 参数函数")
        void function8() {
            nova.defineFunction("sum8", (Function8<Object, Object, Object, Object, Object, Object, Object, Object, Object>)
                    (a, b, c, d, e, f, g, h) ->
                            (int) a + (int) b + (int) c + (int) d + (int) e + (int) f + (int) g + (int) h);
            assertEquals(360, nova.call("sum8", 10, 20, 30, 40, 50, 60, 70, 80));
        }

        // ── 返回类型覆盖 ──

        @Test
        @DisplayName("返回字符串")
        void returnString() {
            nova.defineFunction("hello", (name) -> "Hello, " + name);
            assertEquals("Hello, World", nova.call("hello", "World"));
        }

        @Test
        @DisplayName("返回浮点数")
        void returnDouble() {
            nova.defineFunction("half", (n) -> ((Number) n).doubleValue() / 2.0);
            Object result = nova.call("half", 10);
            assertEquals(5.0, ((Number) result).doubleValue(), 0.0001);
        }

        @Test
        @DisplayName("返回布尔值")
        void returnBoolean() {
            nova.defineFunction("isPositive", (n) -> ((Number) n).intValue() > 0);
            assertEquals(true, nova.call("isPositive", 5));
            assertEquals(false, nova.call("isPositive", -1));
        }

        // ── null 相关边缘值 ──

        @Test
        @DisplayName("返回 null")
        void returnNull() {
            nova.defineFunction("nothing", () -> null);
            assertNull(nova.call("nothing"));
        }

        @Test
        @DisplayName("接收 null 参数")
        void receiveNullArg() {
            nova.defineFunction("isNull", (a) -> a == null ? "yes" : "no");
            assertEquals("yes", nova.call("isNull", (Object) null));
        }

        @Test
        @DisplayName("多参数部分为 null")
        void partialNullArgs() {
            nova.defineFunction("coalesce", (a, b) -> a != null ? a : b);
            assertEquals("fallback", nova.call("coalesce", null, "fallback"));
            assertEquals("primary", nova.call("coalesce", "primary", "fallback"));
        }

        // ── 从 Nova 脚本调用 Java 定义的函数 ──

        @Test
        @DisplayName("Nova 脚本中调用 defineFunction 定义的函数")
        void callFromScript() {
            nova.defineFunction("double", (n) -> (int) n * 2);
            assertEquals(20, nova.eval("double(10)"));
        }

        @Test
        @DisplayName("Nova 脚本中链式调用 Java 函数")
        void chainCallFromScript() {
            nova.defineFunction("inc", (n) -> (int) n + 1);
            nova.defineFunction("double", (n) -> (int) n * 2);
            assertEquals(22, nova.eval("double(inc(10))"));
        }

        @Test
        @DisplayName("Nova 脚本中使用多参数 Java 函数")
        void multiArgFromScript() {
            nova.defineFunction("add", (a, b) -> (int) a + (int) b);
            assertEquals(30, nova.eval("add(10, 20)"));
        }

        // ── fluent API ──

        @Test
        @DisplayName("defineFunction 返回 Nova 实例（链式调用）")
        void fluentChain() {
            Nova result = nova
                    .defineFunction("f0", () -> 0)
                    .defineFunction("f1", (a) -> a)
                    .defineFunction("f2", (a, b) -> a);
            assertSame(nova, result);
        }

        // ── hasFunction 与 defineFunction 配合 ──

        @Test
        @DisplayName("defineFunction 之后 hasFunction 返回 true")
        void hasFunctionAfterDefine() {
            assertFalse(nova.hasFunction("myFn"));
            nova.defineFunction("myFn", () -> 1);
            assertTrue(nova.hasFunction("myFn"));
        }

        // ── 异常情况 ──

        @Test
        @DisplayName("Java 函数抛异常会传播")
        void exceptionPropagation() {
            nova.defineFunction("boom", () -> {
                throw new IllegalStateException("test error");
            });
            assertThrows(Exception.class, () -> nova.call("boom"));
        }

        @Test
        @DisplayName("Java 函数抛 RuntimeException 传播")
        void runtimeExceptionPropagation() {
            nova.defineFunction("fail", (a) -> {
                if (a == null) throw new NullPointerException("arg is null");
                return a;
            });
            assertThrows(Exception.class, () -> nova.call("fail", (Object) null));
        }
    }

    // ── defineFunctionVararg ────────────────────────

    @Nested
    @DisplayName("defineFunctionVararg() 可变参数函数")
    class DefineFunctionVarargTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("零参数调用")
        void zeroArgs() {
            nova.defineFunctionVararg("count", args -> args.length);
            assertEquals(0, nova.call("count"));
        }

        @Test
        @DisplayName("单参数调用")
        void oneArg() {
            nova.defineFunctionVararg("identity", args -> args[0]);
            assertEquals(42, nova.call("identity", 42));
        }

        @Test
        @DisplayName("多参数求和")
        void multiArgSum() {
            nova.defineFunctionVararg("sum", args -> {
                int total = 0;
                for (Object a : args) total += (int) a;
                return total;
            });
            assertEquals(0, nova.call("sum"));
            assertEquals(10, nova.call("sum", 10));
            assertEquals(30, nova.call("sum", 10, 20));
            assertEquals(60, nova.call("sum", 10, 20, 30));
            assertEquals(100, nova.call("sum", 10, 20, 30, 40));
        }

        @Test
        @DisplayName("参数数组包含 null")
        void argsContainNull() {
            nova.defineFunctionVararg("firstNonNull", args -> {
                for (Object a : args) {
                    if (a != null) return a;
                }
                return null;
            });
            assertNull(nova.call("firstNonNull", (Object) null));
            assertEquals("found", nova.call("firstNonNull", null, "found"));
        }

        @Test
        @DisplayName("返回 null")
        void returnNull() {
            nova.defineFunctionVararg("noop", args -> null);
            assertNull(nova.call("noop", 1, 2, 3));
        }

        @Test
        @DisplayName("混合类型参数")
        void mixedTypeArgs() {
            nova.defineFunctionVararg("concat", args -> {
                StringBuilder sb = new StringBuilder();
                for (Object a : args) sb.append(a);
                return sb.toString();
            });
            assertEquals("hello42true", nova.call("concat", "hello", 42, true));
        }

        @Test
        @DisplayName("从 Nova 脚本调用 vararg 函数")
        void callFromScript() {
            nova.defineFunctionVararg("sum", args -> {
                int total = 0;
                for (Object a : args) total += (int) a;
                return total;
            });
            assertEquals(60, nova.eval("sum(10, 20, 30)"));
        }

        @Test
        @DisplayName("vararg 函数抛异常传播")
        void exceptionPropagation() {
            nova.defineFunctionVararg("fail", args -> {
                throw new UnsupportedOperationException("not supported");
            });
            assertThrows(Exception.class, () -> nova.call("fail", 1));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.defineFunctionVararg("f", args -> null);
            assertSame(nova, result);
        }
    }

    // ── defineVal 注入 NovaNativeFunction ───────────

    @Nested
    @DisplayName("defineVal() 注入 NovaNativeFunction")
    class DefineValNativeFunctionTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("注入 NovaNativeFunction 后可调用")
        void injectAndCall() {
            NovaNativeFunction fn = NovaNativeFunction.create("greet",
                    (arg) -> NovaString.of("Hi, " + arg.toJavaValue()));
            nova.defineVal("greet", fn);
            assertEquals("Hi, World", nova.call("greet", "World"));
        }

        @Test
        @DisplayName("注入 NovaNativeFunction 后 hasFunction 返回 true")
        void hasFunction() {
            NovaNativeFunction fn = NovaNativeFunction.create("test", () -> NovaInt.of(1));
            nova.defineVal("test", fn);
            assertTrue(nova.hasFunction("test"));
        }
    }

    // ── 综合集成测试 ────────────────────────────────

    @Nested
    @DisplayName("defineFunction 集成测试")
    class DefineFunctionIntegrationTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("Java 函数与 Nova 函数互调")
        void javaAndNovaInterop() {
            nova.defineFunction("javaDouble", (n) -> (int) n * 2);
            nova.eval("fun novaAdd(a: Int, b: Int) = a + b");
            // Nova 调用 Java 再由 Java 结果参与 Nova 计算
            assertEquals(25, nova.eval("novaAdd(javaDouble(10), 5)"));
        }

        @Test
        @DisplayName("副作用函数（计数器）")
        void sideEffectCounter() {
            AtomicInteger counter = new AtomicInteger(0);
            nova.defineFunction("tick", () -> counter.incrementAndGet());
            nova.eval("tick()");
            nova.eval("tick()");
            nova.eval("tick()");
            assertEquals(3, counter.get());
        }

        @Test
        @DisplayName("类型自动转换: Int")
        void typeCoercionInt() {
            nova.defineFunction("doubleIt", (n) -> ((Number) n).intValue() * 2);
            assertEquals(20, nova.eval("doubleIt(10)"));
        }

        @Test
        @DisplayName("类型自动转换: String")
        void typeCoercionString() {
            nova.defineFunction("len", (s) -> ((String) s).length());
            assertEquals(5, nova.eval("len(\"hello\")"));
        }

        @Test
        @DisplayName("类型自动转换: Boolean")
        void typeCoercionBoolean() {
            nova.defineFunction("not", (b) -> !(boolean) b);
            assertEquals(false, nova.eval("not(true)"));
            assertEquals(true, nova.eval("not(false)"));
        }

        @Test
        @DisplayName("defineFunction 重复定义同名函数抛异常（val 语义）")
        void redefineThrows() {
            nova.defineFunction("fn", () -> "first");
            assertEquals("first", nova.call("fn"));
            assertThrows(NovaException.class, () -> nova.defineFunction("fn", () -> "second"));
        }

        @Test
        @DisplayName("通过 set 覆盖已有函数")
        void overrideViaSet() {
            nova.defineFunction("fn", () -> "first");
            assertEquals("first", nova.call("fn"));
            NovaNativeFunction replacement = new NovaNativeFunction("fn", 0,
                    (ctx, args) -> NovaString.of("second"));
            nova.set("fn", replacement);
            assertEquals("second", nova.call("fn"));
        }

        @Test
        @DisplayName("大量参数 vararg 压力测试")
        void varargManyArgs() {
            nova.defineFunctionVararg("countArgs", args -> args.length);
            // 通过 call 直接传入大量参数
            Object[] args = new Object[20];
            Arrays.fill(args, 1);
            assertEquals(20, nova.call("countArgs", args));
        }
    }

    // ── registerFunctions (顶级) ────────────────────

    // 测试用 @NovaFunc 类
    public static class TopLevelFunctions {
        @NovaFunc("testAdd")
        public static int testAdd(int a, int b) { return a + b; }

        @NovaFunc("testGreet")
        public static String testGreet(String name) { return "Hello, " + name; }
    }

    @Nested
    @DisplayName("registerFunctions(Class) 顶级注册")
    class RegisterFunctionsTopLevel {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("扫描 @NovaFunc 注册为顶级函数")
        void registerAndCall() {
            nova.registerFunctions(TopLevelFunctions.class);
            assertEquals(30, nova.eval("testAdd(10, 20)"));
            assertEquals("Hello, World", nova.eval("testGreet(\"World\")"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.registerFunctions(TopLevelFunctions.class);
            assertSame(nova, result);
        }
    }

    // ── registerFunctions (命名空间) ─────────────────

    public static class MathFunctions {
        @NovaFunc("sqrt")
        public static double sqrt(double x) { return Math.sqrt(x); }

        @NovaFunc("abs")
        public static int abs(int x) { return Math.abs(x); }
    }

    public static class ExtraMathFunctions {
        @NovaFunc("max")
        public static int max(int a, int b) { return Math.max(a, b); }
    }

    @Nested
    @DisplayName("registerFunctions(ns, Class) 命名空间注册")
    class RegisterFunctionsNamespaced {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("命名空间函数调用")
        void namespacedCall() {
            nova.registerFunctions("math", MathFunctions.class);
            assertEquals(4.0, ((Number) nova.eval("math.sqrt(16.0)")).doubleValue(), 0.0001);
            assertEquals(5, nova.eval("math.abs(-5)"));
        }

        @Test
        @DisplayName("增量注册合并到同一命名空间")
        void incrementalRegistration() {
            nova.registerFunctions("math", MathFunctions.class);
            nova.registerFunctions("math", ExtraMathFunctions.class);
            // 旧函数仍在
            assertEquals(5, nova.eval("math.abs(-5)"));
            // 新函数也可用
            assertEquals(20, nova.eval("math.max(10, 20)"));
        }

        @Test
        @DisplayName("不同命名空间互不干扰")
        void separateNamespaces() {
            nova.registerFunctions("ns1", MathFunctions.class);
            nova.registerFunctions("ns2", ExtraMathFunctions.class);
            assertEquals(5, nova.eval("ns1.abs(-5)"));
            assertEquals(20, nova.eval("ns2.max(10, 20)"));
        }

        @Test
        @DisplayName("访问不存在的成员抛异常")
        void unknownMemberThrows() {
            nova.registerFunctions("math", MathFunctions.class);
            assertThrows(Exception.class, () -> nova.eval("math.notExist()"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.registerFunctions("math", MathFunctions.class);
            assertSame(nova, result);
        }
    }

    // ── defineLibrary (Builder 模式) ─────────────────

    @Nested
    @DisplayName("defineLibrary() Builder 模式")
    class DefineLibraryTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("注册函数和值")
        void functionsAndValues() {
            nova.defineLibrary("config", lib -> {
                lib.defineVal("VERSION", "2.0");
                lib.defineVal("DEBUG", true);
                lib.defineFunction("getVersion", () -> "2.0");
            });
            assertEquals("2.0", nova.eval("config.VERSION"));
            assertEquals(true, nova.eval("config.DEBUG"));
            assertEquals("2.0", nova.eval("config.getVersion()"));
        }

        @Test
        @DisplayName("多参数函数")
        void multiArgFunctions() {
            nova.defineLibrary("calc", lib -> {
                lib.defineFunction("add", (a, b) -> (int) a + (int) b);
                lib.defineFunction("mul3", (Object a, Object b, Object c) ->
                        (int) a * (int) b * (int) c);
            });
            assertEquals(30, nova.eval("calc.add(10, 20)"));
            assertEquals(60, nova.eval("calc.mul3(3, 4, 5)"));
        }

        @Test
        @DisplayName("vararg 函数")
        void varargFunction() {
            nova.defineLibrary("util", lib -> {
                lib.defineFunctionVararg("sum", args -> {
                    int total = 0;
                    for (Object a : args) total += (int) a;
                    return total;
                });
            });
            assertEquals(0, nova.eval("util.sum()"));
            assertEquals(60, nova.eval("util.sum(10, 20, 30)"));
        }

        @Test
        @DisplayName("增量合并")
        void incrementalMerge() {
            nova.defineLibrary("lib", lib -> lib.defineFunction("a", () -> "A"));
            nova.defineLibrary("lib", lib -> lib.defineFunction("b", () -> "B"));
            assertEquals("A", nova.eval("lib.a()"));
            assertEquals("B", nova.eval("lib.b()"));
        }

        @Test
        @DisplayName("覆盖同名成员")
        void overrideMember() {
            nova.defineLibrary("lib", lib -> lib.defineVal("x", 1));
            nova.defineLibrary("lib", lib -> lib.defineVal("x", 2));
            assertEquals(2, nova.eval("lib.x"));
        }

        @Test
        @DisplayName("null 返回值")
        void nullReturn() {
            nova.defineLibrary("lib", lib ->
                    lib.defineFunction("nothing", () -> null));
            assertNull(nova.eval("lib.nothing()"));
        }

        @Test
        @DisplayName("函数抛异常传播")
        void exceptionPropagation() {
            nova.defineLibrary("lib", lib ->
                    lib.defineFunction("boom", () -> { throw new RuntimeException("test"); }));
            assertThrows(Exception.class, () -> nova.eval("lib.boom()"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.defineLibrary("lib", lib -> {});
            assertSame(nova, result);
        }

        @Test
        @DisplayName("命名空间与 registerFunctions 混合使用")
        void mixWithRegisterFunctions() {
            nova.registerFunctions("math", MathFunctions.class);
            nova.defineLibrary("math", lib -> lib.defineVal("PI", 3.14159));
            // @NovaFunc 函数仍在
            assertEquals(5, nova.eval("math.abs(-5)"));
            // builder 添加的值也可用
            assertEquals(3.14159, ((Number) nova.eval("math.PI")).doubleValue(), 0.0001);
        }
    }

    // ── registerObject (Java 对象暴露) ───────────────

    public static class MockService {
        public String greet(String name) { return "Hi, " + name; }
        public int add(int a, int b) { return a + b; }
        public String getVersion() { return "1.0"; }
    }

    @Nested
    @DisplayName("registerObject() Java 对象暴露")
    class RegisterObjectTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("调用 Java 对象方法")
        void callMethods() {
            nova.registerObject("svc", new MockService());
            assertEquals("Hi, World", nova.eval("svc.greet(\"World\")"));
            assertEquals(30, nova.eval("svc.add(10, 20)"));
        }

        @Test
        @DisplayName("JavaBean getter 属性访问")
        void beanProperty() {
            nova.registerObject("svc", new MockService());
            assertEquals("1.0", nova.eval("svc.version"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.registerObject("svc", new MockService());
            assertSame(nova, result);
        }
    }

    // ── NovaLibrary 值类型 ─────────────────────────

    @Nested
    @DisplayName("NovaLibrary 值类型")
    class NovaLibraryTypeTests {

        @Test
        @DisplayName("getTypeName 返回 Library")
        void typeName() {
            NovaLibrary lib = new NovaLibrary("test");
            assertEquals("Library", lib.getTypeName());
        }

        @Test
        @DisplayName("toString 格式")
        void toStringFormat() {
            NovaLibrary lib = new NovaLibrary("http");
            assertEquals("<library http>", lib.toString());
        }

        @Test
        @DisplayName("putMember/getMember/hasMember")
        void memberOps() {
            NovaLibrary lib = new NovaLibrary("test");
            assertFalse(lib.hasMember("x"));
            assertNull(lib.getMember("x"));

            lib.putMember("x", NovaInt.of(42));
            assertTrue(lib.hasMember("x"));
            assertEquals(42, ((NovaInt) lib.getMember("x")).getValue());
        }

        @Test
        @DisplayName("getMembers 返回只读视图")
        void membersReadOnly() {
            NovaLibrary lib = new NovaLibrary("test");
            lib.putMember("a", NovaInt.of(1));
            Map<String, NovaValue> members = lib.getMembers();
            assertEquals(1, members.size());
            assertThrows(UnsupportedOperationException.class, () ->
                    members.put("b", NovaInt.of(2)));
        }
    }

    // ── registerExtension Lambda 便捷方法 ────────────

    @Nested
    @DisplayName("registerExtension() Lambda 便捷方法")
    class RegisterExtensionLambdaTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("0 额外参数扩展方法（仅 receiver）")
        void zeroExtraArgs() {
            nova.registerExtension(String.class, "reverse",
                    (s) -> new StringBuilder((String) s).reverse().toString());
            assertEquals("olleh", nova.eval("\"hello\".reverse()"));
        }

        @Test
        @DisplayName("1 额外参数扩展方法")
        void oneExtraArg() {
            nova.registerExtension(String.class, "repeatN",
                    (s, n) -> {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < (int) n; i++) sb.append((String) s);
                        return sb.toString();
                    });
            assertEquals("ababab", nova.eval("\"ab\".repeatN(3)"));
        }

        @Test
        @DisplayName("2 额外参数扩展方法")
        void twoExtraArgs() {
            nova.registerExtension(String.class, "surround",
                    (Object s, Object prefix, Object suffix) ->
                            (String) prefix + (String) s + (String) suffix);
            assertEquals("[hello]", nova.eval("\"hello\".surround(\"[\", \"]\")"));
        }

        @Test
        @DisplayName("扩展方法返回 null")
        void returnNull() {
            nova.registerExtension(String.class, "toNull", (s) -> null);
            assertNull(nova.eval("\"hello\".toNull()"));
        }

        @Test
        @DisplayName("扩展方法抛异常传播")
        void exceptionPropagation() {
            nova.registerExtension(String.class, "boom",
                    (s) -> { throw new RuntimeException("test error"); });
            assertThrows(Exception.class, () -> nova.eval("\"hello\".boom()"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.registerExtension(String.class, "test", (s) -> s);
            assertSame(nova, result);
        }

        @Test
        @DisplayName("Int 扩展方法")
        void intExtension() {
            nova.registerExtension(Integer.class, "doubled",
                    (n) -> (int) n * 2);
            assertEquals(20, nova.eval("10.doubled()"));
        }
    }

    // ── registerExtensions 批量注解注册 ──────────────

    public static class StringExtensions {
        @NovaExt("shout")
        public static String shout(String self) {
            return self.toUpperCase() + "!";
        }

        @NovaExt("truncate")
        public static String truncate(String self, int maxLen) {
            return self.length() <= maxLen ? self : self.substring(0, maxLen) + "...";
        }
    }

    public static class IntExtensions {
        @NovaExt("isEven")
        public static boolean isEven(int self) {
            return self % 2 == 0;
        }

        @NovaExt("clampTo")
        public static int clampTo(int self, int lo, int hi) {
            return Math.max(lo, Math.min(hi, self));
        }
    }

    @Nested
    @DisplayName("registerExtensions() 批量注解注册")
    class RegisterExtensionsBatchTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("@NovaExt 扫描注册 String 扩展")
        void scanStringExtensions() {
            nova.registerExtensions(String.class, StringExtensions.class);
            assertEquals("HELLO!", nova.eval("\"hello\".shout()"));
            assertEquals("hello...", nova.eval("\"hello world\".truncate(5)"));
        }

        @Test
        @DisplayName("@NovaExt 扫描注册 Int 扩展")
        void scanIntExtensions() {
            nova.registerExtensions(Integer.class, IntExtensions.class);
            assertEquals(true, nova.eval("4.isEven()"));
            assertEquals(false, nova.eval("3.isEven()"));
            assertEquals(10, nova.eval("15.clampTo(0, 10)"));
        }

        @Test
        @DisplayName("Lambda 和批量注册混合使用")
        void mixLambdaAndBatch() {
            nova.registerExtension(String.class, "reverse",
                    (s) -> new StringBuilder((String) s).reverse().toString());
            nova.registerExtensions(String.class, StringExtensions.class);
            // 两种方式注册的扩展都可用
            assertEquals("olleh", nova.eval("\"hello\".reverse()"));
            assertEquals("HELLO!", nova.eval("\"hello\".shout()"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            Nova result = nova.registerExtensions(String.class, StringExtensions.class);
            assertSame(nova, result);
        }
    }

    // ── alias 别名 ──────────────────────────────────

    @Nested
    @DisplayName("alias() 函数别名")
    class AliasTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("为 defineFunction 创建别名")
        void aliasDefineFunction() {
            nova.defineFunction("sum", (a, b) -> (int) a + (int) b);
            nova.alias("sum", "求和", "add");
            assertEquals(30, nova.eval("求和(10, 20)"));
            assertEquals(30, nova.eval("add(10, 20)"));
            assertEquals(30, nova.eval("sum(10, 20)"));
        }

        @Test
        @DisplayName("为 defineVal 创建别名")
        void aliasDefineVal() {
            nova.defineVal("PI", 3.14159);
            nova.alias("PI", "圆周率");
            assertEquals(3.14159, ((Number) nova.eval("圆周率")).doubleValue(), 0.0001);
        }

        @Test
        @DisplayName("别名不存在的变量抛异常")
        void aliasUndefined() {
            assertThrows(Exception.class, () -> nova.alias("notExist", "别名"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            nova.defineFunction("f", () -> 1);
            Nova result = nova.alias("f", "g");
            assertSame(nova, result);
        }
    }

    // ── aliasExtension 扩展方法别名 ─────────────────

    @Nested
    @DisplayName("aliasExtension() 扩展方法别名")
    class AliasExtensionTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("为扩展方法创建别名")
        void aliasExtension() {
            nova.registerExtension(String.class, "reverse",
                    (s) -> new StringBuilder((String) s).reverse().toString());
            nova.aliasExtension(String.class, "reverse", "反转");
            assertEquals("olleh", nova.eval("\"hello\".反转()"));
            assertEquals("olleh", nova.eval("\"hello\".reverse()"));
        }

        @Test
        @DisplayName("多个别名")
        void multipleAliases() {
            nova.registerExtension(String.class, "shout",
                    (s) -> ((String) s).toUpperCase() + "!");
            nova.aliasExtension(String.class, "shout", "大喊", "yell");
            assertEquals("HELLO!", nova.eval("\"hello\".大喊()"));
            assertEquals("HELLO!", nova.eval("\"hello\".yell()"));
        }

        @Test
        @DisplayName("别名不存在的扩展方法抛异常")
        void aliasUndefined() {
            assertThrows(Exception.class, () ->
                    nova.aliasExtension(String.class, "notExist", "别名"));
        }

        @Test
        @DisplayName("fluent API 返回 Nova 实例")
        void fluent() {
            nova.registerExtension(String.class, "test", (s) -> s);
            Nova result = nova.aliasExtension(String.class, "test", "t");
            assertSame(nova, result);
        }
    }

    // ── @NovaFunc aliases 注解别名 ──────────────────

    public static class FunctionsWithAliases {
        @NovaFunc(value = "sum", aliases = {"求和", "add"})
        public static int sum(int a, int b) { return a + b; }
    }

    @Nested
    @DisplayName("@NovaFunc aliases 注解别名")
    class NovaFuncAliasTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("顶级注册的注解别名")
        void topLevelAliases() {
            nova.registerFunctions(FunctionsWithAliases.class);
            assertEquals(30, nova.eval("sum(10, 20)"));
            assertEquals(30, nova.eval("求和(10, 20)"));
            assertEquals(30, nova.eval("add(10, 20)"));
        }

        @Test
        @DisplayName("命名空间注册的注解别名")
        void namespacedAliases() {
            nova.registerFunctions("math", FunctionsWithAliases.class);
            assertEquals(30, nova.eval("math.sum(10, 20)"));
            assertEquals(30, nova.eval("math.求和(10, 20)"));
            assertEquals(30, nova.eval("math.add(10, 20)"));
        }
    }

    // ── @NovaExt aliases 注解别名 ───────────────────

    public static class ExtWithAliases {
        @NovaExt(value = "shout", aliases = {"大喊", "yell"})
        public static String shout(String self) {
            return self.toUpperCase() + "!";
        }
    }

    @Nested
    @DisplayName("@NovaExt aliases 注解别名")
    class NovaExtAliasTests {

        private Nova nova;

        @BeforeEach
        void setUp() {
            nova = new Nova();
        }

        @Test
        @DisplayName("扩展方法注解别名")
        void extAliases() {
            nova.registerExtensions(String.class, ExtWithAliases.class);
            assertEquals("HELLO!", nova.eval("\"hello\".shout()"));
            assertEquals("HELLO!", nova.eval("\"hello\".大喊()"));
            assertEquals("HELLO!", nova.eval("\"hello\".yell()"));
        }
    }
}
