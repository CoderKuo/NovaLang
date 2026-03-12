package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.NovaNativeFunction;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 脚本模式编译路径测试：覆盖 $PipeCall 运行时分派。
 *
 * <p>测试场景：编译模式下，脚本调用宿主环境注入的外部函数（如 Minecraft 插件的 on/play 等），
 * 这些函数在编译期无法静态解析，通过 $PipeCall → NovaScriptContext.call() 运行时分派。</p>
 *
 * <p>注意：$PipeCall 仅在有参数的函数调用时生成。无参调用走 Function0 cast 路径。</p>
 */
@DisplayName("脚本模式编译: $PipeCall 运行时分派")
class ScriptModeCodegenTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    /** 编译脚本并执行 main() 方法 */
    private Object compileAndRun(String code, Map<String, Object> bindings) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module, "编译后应生成 $Module 类");
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);

        NovaScriptContext.init(bindings);
        try {
            return main.invoke(null);
        } finally {
            NovaScriptContext.clear();
        }
    }

    /*

        @DisplayName("绑定函数优先于同名 stdlib 函数")


    /** 从编译结果中提取 int 值（可能是 NovaInt 或 Java Integer） */
    private static int asInt(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asInt();
        if (result instanceof Number) return ((Number) result).intValue();
        fail("Expected int, got: " + (result == null ? "null" : result.getClass().getName()));
        return 0;
    }

    /** 从编译结果中提取 String 值 */
    private static String asString(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asString();
        return String.valueOf(result);
    }

    // ============ NovaScriptContext.call() 直接测试 ============

    @Nested
    @DisplayName("NovaScriptContext.call()")
    class ScriptContextCallTests {

        @Test
        @DisplayName("调用无参函数")
        void testCallNoArgs() {
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("greet", NovaNativeFunction.create("greet", () -> NovaString.of("hello")));
            NovaScriptContext.init(bindings);
            try {
                Object result = NovaScriptContext.call("greet");
                assertEquals("hello", String.valueOf(result));
            } finally {
                NovaScriptContext.clear();
            }
        }

        @Test
        @DisplayName("调用单参数函数")
        void testCallOneArg() {
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("double", NovaNativeFunction.create("double",
                    (NovaValue a) -> NovaInt.of(a.asInt() * 2)));
            NovaScriptContext.init(bindings);
            try {
                // call() 自动 unwrap NovaInt → Integer
                Object result = NovaScriptContext.call("double", NovaInt.of(21));
                assertEquals(42, result);
            } finally {
                NovaScriptContext.clear();
            }
        }

        @Test
        @DisplayName("调用双参数函数")
        void testCallTwoArgs() {
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("add", NovaNativeFunction.create("add",
                    (NovaValue a, NovaValue b) -> NovaInt.of(a.asInt() + b.asInt())));
            NovaScriptContext.init(bindings);
            try {
                Object result = NovaScriptContext.call("add", NovaInt.of(3), NovaInt.of(4));
                assertEquals(7, result);
            } finally {
                NovaScriptContext.clear();
            }
        }

        @Test
        @DisplayName("调用不存在的函数抛异常")
        void testCallUndefined() {
            NovaScriptContext.init(new HashMap<>());
            try {
                RuntimeException ex = assertThrows(RuntimeException.class,
                        () -> NovaScriptContext.call("nonexistent"));
                assertTrue(ex.getMessage().contains("Undefined function"));
            } finally {
                NovaScriptContext.clear();
            }
        }

        @Test
        @DisplayName("Java 原始类型参数自动转换为 NovaValue")
        void testAutoConvertJavaArgs() {
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("identity", NovaNativeFunction.create("identity",
                    (NovaValue a) -> a));
            NovaScriptContext.init(bindings);
            try {
                // 传入 Java Integer → fromJava 转 NovaInt → 函数返回 → unwrap 回 Integer
                Object result = NovaScriptContext.call("identity", 42);
                assertEquals(42, result);
            } finally {
                NovaScriptContext.clear();
            }
        }

        @Test
        @DisplayName("绑定函数优先于同名 stdlib 函数")
        void testBindingShadowsStdlibFunction() {
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("log", NovaNativeFunction.create("log",
                    (NovaValue a) -> NovaString.of("user-log:" + a.asString())));
            NovaScriptContext.init(bindings);
            try {
                Object result = NovaScriptContext.call("log", 10);
                assertEquals("user-log:10", String.valueOf(result));
            } finally {
                NovaScriptContext.clear();
            }
        }
    }

    // ============ 编译路径 $PipeCall 测试（仅有参调用生成 $PipeCall） ============

    @Nested
    @DisplayName("编译路径: $PipeCall → NovaScriptContext.call()")
    class CompiledPipeCallTests {

        @Test
        @DisplayName("调用单参数外部函数")
        void testOneArgFunction() throws Exception {
            String code = "double(21)";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("double", NovaNativeFunction.create("double",
                    (NovaValue a) -> NovaInt.of(a.asInt() * 2)));
            Object result = compileAndRun(code, bindings);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("调用多参数外部函数")
        void testMultiArgFunction() throws Exception {
            String code = "add(10, 20, 12)";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("add", NovaNativeFunction.createVararg("add", (ctx, args) ->
                    NovaInt.of(args.stream().mapToInt(NovaValue::asInt).sum())));
            Object result = compileAndRun(code, bindings);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("外部函数返回值用于后续计算")
        void testReturnValueUsed() throws Exception {
            // getVal 需要有参数才走 $PipeCall 路径
            String code = "val x = getVal(\"key\")\nx * 2";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("getVal", NovaNativeFunction.create("getVal",
                    (NovaValue key) -> NovaInt.of(21)));
            Object result = compileAndRun(code, bindings);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("外部函数嵌套调用")
        void testNestedCalls() throws Exception {
            String code = "outer(inner(7))";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("inner", NovaNativeFunction.create("inner",
                    (NovaValue a) -> NovaInt.of(a.asInt() * 3)));
            bindings.put("outer", NovaNativeFunction.create("outer",
                    (NovaValue a) -> NovaInt.of(a.asInt() * 2)));
            Object result = compileAndRun(code, bindings);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("外部函数接收字符串参数")
        void testStringArg() throws Exception {
            String code = "echo(\"world\")";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("echo", NovaNativeFunction.create("echo",
                    (NovaValue a) -> NovaString.of("hello " + a.asString())));
            Object result = compileAndRun(code, bindings);
            assertEquals("hello world", asString(result));
        }

        @Test
        @DisplayName("外部函数接收 lambda 参数 — lambda 通过 FunctionN 接口回调")
        void testLambdaArg() throws Exception {
            String code = "register(\"test\") { \"callback_result\" }";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("register", NovaNativeFunction.create("register",
                    (NovaValue name, NovaValue callbackWrapped) -> {
                        // 编译 lambda 是 Java 对象实现 Function0，被 fromJava 包装
                        // 通过 toJavaValue() 解包后用 Function0 接口调用
                        Object callback = callbackWrapped.toJavaValue();
                        if (callback instanceof Function0) {
                            Object lambdaResult = ((Function0<?>) callback).invoke();
                            return NovaString.of(name.asString() + ":" + lambdaResult);
                        }
                        fail("lambda 应实现 Function0 接口");
                        return NovaNull.NULL;
                    }));
            Object result = compileAndRun(code, bindings);
            assertEquals("test:callback_result", asString(result));
        }

        @Test
        @DisplayName("外部函数接收带参数的 lambda — 通过 Function1 回调")
        void testLambdaWithParamArg() throws Exception {
            String code = "transform(10) { x -> x * 4 + 2 }";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("transform", NovaNativeFunction.create("transform",
                    (NovaValue val, NovaValue callbackWrapped) -> {
                        Object callback = callbackWrapped.toJavaValue();
                        if (callback instanceof Function1) {
                            @SuppressWarnings("unchecked")
                            Function1<Object, Object> fn = (Function1<Object, Object>) callback;
                            // 编译 lambda 内部用 NovaOps 运算，期望 Java 原始类型参数
                            Object lambdaResult = fn.invoke(val.toJavaValue());
                            return lambdaResult instanceof NovaValue
                                    ? (NovaValue) lambdaResult
                                    : AbstractNovaValue.fromJava(lambdaResult);
                        }
                        fail("lambda 应实现 Function1 接口");
                        return NovaNull.NULL;
                    }));
            Object result = compileAndRun(code, bindings);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("调用未注册的外部函数抛异常")
        void testUndefinedFunctionThrows() {
            // unknownFunc 有参数才走 $PipeCall → NovaScriptContext.call → 抛异常
            String code = "unknownFunc(1)";
            Map<String, Object> bindings = new HashMap<>();
            assertThrows(Exception.class, () -> compileAndRun(code, bindings));
        }

        @Test
        @DisplayName("多个外部函数链式调用")
        void testMultipleFunctions() throws Exception {
            String code = "val a = first(0)\nval b = second(a)\nb";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("first", NovaNativeFunction.create("first",
                    (NovaValue ignored) -> NovaInt.of(6)));
            bindings.put("second", NovaNativeFunction.create("second",
                    (NovaValue a) -> NovaInt.of(a.asInt() * 7)));
            Object result = compileAndRun(code, bindings);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("外部函数返回字符串拼接到表达式")
        void testReturnStringInExpr() throws Exception {
            String code = "val prefix = getPrefix(\"nova\")\nprefix + \"_lang\"";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("getPrefix", NovaNativeFunction.create("getPrefix",
                    (NovaValue a) -> NovaString.of(a.asString().toUpperCase())));
            Object result = compileAndRun(code, bindings);
            assertEquals("NOVA_lang", asString(result));
        }
        @Test
        @DisplayName("编译脚本中绑定函数优先于同名 stdlib 函数")
        void testCompiledBindingShadowsStdlibFunction() throws Exception {
            String code = "log(10)";
            Map<String, Object> bindings = new HashMap<>();
            bindings.put("log", NovaNativeFunction.create("log",
                    (NovaValue a) -> NovaString.of("user-log:" + a.asString())));
            Object result = compileAndRun(code, bindings);
            assertEquals("user-log:10", asString(result));
        }
    }
}
