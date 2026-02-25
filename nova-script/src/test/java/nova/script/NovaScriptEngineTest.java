package nova.script;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.script.*;
import java.io.StringReader;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.*;

/**
 * NovaLang JSR-223 ScriptEngine 集成测试
 */
class NovaScriptEngineTest {

    private ScriptEngineManager manager;

    @BeforeEach
    void setUp() {
        manager = new ScriptEngineManager();
    }

    // ======== 引擎发现 ========

    @Test
    void discoverByName_nova() {
        ScriptEngine engine = manager.getEngineByName("nova");
        assertThat(engine).isNotNull();
        assertThat(engine).isInstanceOf(NovaScriptEngine.class);
    }

    @Test
    void discoverByName_novalang() {
        assertThat(manager.getEngineByName("novalang")).isNotNull();
    }

    @Test
    void discoverByName_NovaLang() {
        assertThat(manager.getEngineByName("NovaLang")).isNotNull();
    }

    @Test
    void discoverByExtension() {
        assertThat(manager.getEngineByExtension("nova")).isNotNull();
    }

    @Test
    void discoverByMimeType() {
        assertThat(manager.getEngineByMimeType("application/x-nova")).isNotNull();
    }

    // ======== Factory 属性 ========

    @Test
    void factoryMetadata() {
        ScriptEngine engine = manager.getEngineByName("nova");
        ScriptEngineFactory factory = engine.getFactory();
        assertThat(factory.getEngineName()).isEqualTo("NovaLang");
        assertThat(factory.getLanguageName()).isEqualTo("nova");
        assertThat(factory.getExtensions()).containsExactly("nova");
        assertThat(factory.getNames()).contains("nova", "novalang", "NovaLang");
    }

    @Test
    void factoryMethodCallSyntax() {
        ScriptEngineFactory factory = manager.getEngineByName("nova").getFactory();
        assertThat(factory.getMethodCallSyntax("obj", "foo", "a", "b"))
                .isEqualTo("obj.foo(a, b)");
    }

    @Test
    void factoryOutputStatement() {
        ScriptEngineFactory factory = manager.getEngineByName("nova").getFactory();
        assertThat(factory.getOutputStatement("\"hello\""))
                .isEqualTo("println(\"hello\")");
    }

    // ======== eval 基本执行 ========

    @Test
    void evalSimpleExpression() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval("1 + 2");
        assertThat(result).isEqualTo(3);
    }

    @Test
    void evalStringExpression() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval("\"hello\" + \" world\"");
        assertThat(result).isEqualTo("hello world");
    }

    @Test
    void evalBooleanExpression() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        assertThat(engine.eval("true")).isEqualTo(true);
        assertThat(engine.eval("1 > 2")).isEqualTo(false);
    }

    @Test
    void evalMultilineScript() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval("val x = 10\nval y = 20\nx + y");
        assertThat(result).isEqualTo(30);
    }

    @Test
    void evalFromReader() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval(new StringReader("42"));
        assertThat(result).isEqualTo(42);
    }

    @Test
    void evalNullResult() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval("null");
        assertThat(result).isNull();
    }

    @Test
    void evalWithFunctionDecl() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval("fun add(a: Int, b: Int) = a + b\nadd(3, 4)");
        assertThat(result).isEqualTo(7);
    }

    @Test
    void evalWithIfExpression() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        Object result = engine.eval("val x = 5\nif (x > 3) \"big\" else \"small\"");
        assertThat(result).isEqualTo("big");
    }

    @Test
    void evalAccumulatesState() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.eval("val a = 10");
        Object result = engine.eval("a + 5");
        assertThat(result).isEqualTo(15);
    }

    // ======== Bindings 变量注入和回读 ========

    @Test
    void bindingsInjectInt() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.put("x", 10);
        Object result = engine.eval("x + 5");
        assertThat(result).isEqualTo(15);
    }

    @Test
    void bindingsInjectString() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.put("name", "World");
        Object result = engine.eval("\"Hello, \" + name");
        assertThat(result).isEqualTo("Hello, World");
    }

    @Test
    void bindingsExport() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.eval("val answer = 42");
        assertThat(engine.get("answer")).isEqualTo(42);
    }

    @Test
    void bindingsExportMultipleVars() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.eval("val a = 1\nval b = \"two\"\nval c = true");
        assertThat(engine.get("a")).isEqualTo(1);
        assertThat(engine.get("b")).isEqualTo("two");
        assertThat(engine.get("c")).isEqualTo(true);
    }

    @Test
    void bindingsRoundTrip() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.put("name", "Nova");
        engine.eval("val greeting = \"Hello, \" + name + \"!\"");
        assertThat(engine.get("greeting")).isEqualTo("Hello, Nova!");
    }

    @Test
    void bindingsOverwriteVar() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        engine.put("x", 1);
        engine.eval("x");
        engine.put("x", 99);
        Object result = engine.eval("x");
        assertThat(result).isEqualTo(99);
    }

    // ======== Compilable 预编译 — 基本 ========

    @Test
    void compileAndEval() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("1 + 1");
        assertThat(compiled).isNotNull();
        Object result = compiled.eval();
        assertThat(result).isEqualTo(2);
    }

    @Test
    void compileStringExpression() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("\"hello\" + \" world\"");
        assertThat(compiled.eval()).isEqualTo("hello world");
    }

    @Test
    void compileBooleanExpression() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        assertThat(engine.compile("true").eval()).isEqualTo(true);
        assertThat(engine.compile("1 < 2").eval()).isEqualTo(true);
        assertThat(engine.compile("3 > 5").eval()).isEqualTo(false);
    }

    @Test
    void compileFromReader() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile(new StringReader("\"compiled\""));
        assertThat(compiled.eval()).isEqualTo("compiled");
    }

    @Test
    void compiledScriptGetEngine() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("1");
        assertThat(compiled.getEngine()).isSameAs(engine);
    }

    // ======== Compilable 预编译 — Bindings 交互 ========

    @Test
    void compileWithBindingsInject() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        engine.put("n", 0);
        CompiledScript compiled = engine.compile("n + 1");

        Object r1 = compiled.eval();
        assertThat(r1).isEqualTo(1);

        engine.put("n", 10);
        Object r2 = compiled.eval();
        assertThat(r2).isEqualTo(11);
    }

    @Test
    void compileWithBindingsInjectString() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        engine.put("who", "Nova");
        CompiledScript compiled = engine.compile("\"Hi, \" + who");
        assertThat(compiled.eval()).isEqualTo("Hi, Nova");

        engine.put("who", "World");
        assertThat(compiled.eval()).isEqualTo("Hi, World");
    }

    @Test
    void compileWithBindingsExport() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("val result = 42");
        compiled.eval();
        assertThat(engine.get("result")).isEqualTo(42);
    }

    @Test
    void compileWithBindingsExportMultipleVars() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("val x = 10\nval y = 20\nval sum = x + y");
        compiled.eval();
        assertThat(engine.get("x")).isEqualTo(10);
        assertThat(engine.get("y")).isEqualTo(20);
        assertThat(engine.get("sum")).isEqualTo(30);
    }

    @Test
    void compileWithBindingsRoundTrip() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        engine.put("base", 100);
        CompiledScript compiled = engine.compile("val doubled = base * 2");
        compiled.eval();
        assertThat(engine.get("doubled")).isEqualTo(200);
    }

    @Test
    void compileBindingsIsolatedPerEval() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("val counter = 1");

        compiled.eval();
        assertThat(engine.get("counter")).isEqualTo(1);

        // 每次 eval 应独立，不会因上次结果影响
        compiled.eval();
        assertThat(engine.get("counter")).isEqualTo(1);
    }

    // ======== Compilable 预编译 — 自定义 ScriptContext ========

    @Test
    void compileWithCustomScriptContext() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("x + y");

        SimpleScriptContext ctx = new SimpleScriptContext();
        Bindings bindings = new SimpleBindings();
        bindings.put("x", 3);
        bindings.put("y", 7);
        ctx.setBindings(bindings, ScriptContext.ENGINE_SCOPE);

        Object result = compiled.eval(ctx);
        assertThat(result).isEqualTo(10);
    }

    @Test
    void compileWithDifferentContextsYieldDifferentResults() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("n * 2");

        SimpleScriptContext ctx1 = new SimpleScriptContext();
        Bindings b1 = new SimpleBindings();
        b1.put("n", 5);
        ctx1.setBindings(b1, ScriptContext.ENGINE_SCOPE);

        SimpleScriptContext ctx2 = new SimpleScriptContext();
        Bindings b2 = new SimpleBindings();
        b2.put("n", 100);
        ctx2.setBindings(b2, ScriptContext.ENGINE_SCOPE);

        assertThat(compiled.eval(ctx1)).isEqualTo(10);
        assertThat(compiled.eval(ctx2)).isEqualTo(200);
    }

    // ======== Compilable 预编译 — 复杂逻辑 ========

    @Test
    void compileMultilineWithVarDecl() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("val a = 3\nval b = 4\na + b");
        assertThat(compiled.eval()).isEqualTo(7);
    }

    @Test
    void compileWithIfExpression() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        engine.put("score", 85);
        CompiledScript compiled = engine.compile("if (score >= 60) \"pass\" else \"fail\"");
        assertThat(compiled.eval()).isEqualTo("pass");

        engine.put("score", 30);
        assertThat(compiled.eval()).isEqualTo("fail");
    }

    @Test
    void compileWithFunction() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile(
                "fun square(n: Int) = n * n\nsquare(7)");
        assertThat(compiled.eval()).isEqualTo(49);
    }

    @Test
    void compileWithStringInterpolation() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        engine.put("name", "Nova");
        CompiledScript compiled = engine.compile("\"Hello, ${name}!\"");
        assertThat(compiled.eval()).isEqualTo("Hello, Nova!");
    }

    // ======== Compilable 预编译 — compileFile ========

    @Test
    void compileFileAndEval() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compileFile("val x = 99\nx", "test.nova");
        assertThat(compiled.eval()).isEqualTo(99);
    }

    // ======== Compilable 预编译 — 异常处理 ========

    @Test
    void compileInvalidScriptThrowsScriptException() {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        assertThatThrownBy(() -> engine.compile("@@@invalid"))
                .isInstanceOf(ScriptException.class);
    }

    @Test
    void compiledScriptRuntimeErrorThrowsScriptException() throws ScriptException {
        NovaScriptEngine engine = (NovaScriptEngine) manager.getEngineByName("nova");
        CompiledScript compiled = engine.compile("1 / 0");
        assertThatThrownBy(() -> compiled.eval())
                .isInstanceOf(ScriptException.class);
    }

    // ======== stdout 重定向 ========

    @Test
    void evalStdoutRedirect() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        StringWriter writer = new StringWriter();
        engine.getContext().setWriter(writer);
        engine.eval("println(\"hello from nova\")");
        assertThat(writer.toString().trim()).isEqualTo("hello from nova");
    }

    @Test
    void evalStderrRedirect() throws ScriptException {
        ScriptEngine engine = manager.getEngineByName("nova");
        StringWriter errWriter = new StringWriter();
        engine.getContext().setErrorWriter(errWriter);
        // stderr 重定向至少不应报错
        engine.eval("1 + 1");
    }

    // ======== eval 异常处理 ========

    @Test
    void evalInvalidScriptThrowsScriptException() {
        ScriptEngine engine = manager.getEngineByName("nova");
        assertThatThrownBy(() -> engine.eval("@@@invalid"))
                .isInstanceOf(ScriptException.class);
    }

    @Test
    void evalRuntimeErrorThrowsScriptException() {
        ScriptEngine engine = manager.getEngineByName("nova");
        assertThatThrownBy(() -> engine.eval("1 / 0"))
                .isInstanceOf(ScriptException.class);
    }

    // ======== createBindings ========

    @Test
    void createBindingsReturnsSimpleBindings() {
        ScriptEngine engine = manager.getEngineByName("nova");
        Bindings bindings = engine.createBindings();
        assertThat(bindings).isInstanceOf(SimpleBindings.class);
    }

    // ======== Compilable 接口检查 ========

    @Test
    void engineImplementsCompilable() {
        ScriptEngine engine = manager.getEngineByName("nova");
        assertThat(engine).isInstanceOf(Compilable.class);
    }
}
