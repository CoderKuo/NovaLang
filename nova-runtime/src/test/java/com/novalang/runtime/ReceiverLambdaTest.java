package com.novalang.runtime;

import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.interpreter.NovaRuntimeException;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/**
 * 接收者 Lambda / Builder DSL 综合测试。
 * 覆盖：语法解析、解释器执行、Java 互操作 API、边缘值。
 */
@DisplayName("接收者 Lambda / Builder DSL")
class ReceiverLambdaTest {

    // ========== Nova 语言侧：receiver.block() 语义 ==========

    @Nested
    @DisplayName("receiver.block() 基本语义（解释器）")
    class ReceiverBlockEval {

        @Test
        @DisplayName("receiver lambda 读取接收者字段")
        void receiverFieldRead() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Box(var value: Int)");
            interp.evalRepl("fun configure(block: Box.() -> Int): Int { val b = Box(42); return b.block() }");
            NovaValue r = interp.evalRepl("configure { value }");
            assertThat(r.asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("receiver lambda 修改接收者字段")
        void receiverFieldWrite() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Config(var host: String, var port: Int)");
            interp.evalRepl("fun configure(block: Config.() -> Unit): Config { val c = Config(\"localhost\", 80); c.block(); return c }");
            interp.evalRepl("val cfg = configure { host = \"example.com\"; port = 443 }");
            NovaValue r = interp.evalRepl("cfg.host + \":\" + cfg.port");
            assertThat(r.asString()).isEqualTo("example.com:443");
        }

        @Test
        @DisplayName("receiver lambda 调用接收者方法")
        void receiverMethodCall() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Builder(var items: String) { fun add(s: String) { items = items + s } }");
            interp.evalRepl("fun build(block: Builder.() -> Unit): String { val b = Builder(\"\"); b.block(); return b.items }");
            NovaValue r = interp.evalRepl("build { add(\"a\"); add(\"b\"); add(\"c\") }");
            assertThat(r.asString()).isEqualTo("abc");
        }

        @Test
        @DisplayName("嵌套 scope — 内层字段不影响外层")
        void nestedScope() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class A(var x: Int)");
            interp.evalRepl("class B(var y: Int)");
            interp.evalRepl("val a = A(0)");
            interp.evalRepl("val b = B(0)");
            interp.evalRepl("with(a) { x = 10; with(b) { y = 20 } }");
            NovaValue rx = interp.evalRepl("a.x");
            NovaValue ry = interp.evalRepl("b.y");
            assertThat(rx.asInt()).isEqualTo(10);
            assertThat(ry.asInt()).isEqualTo(20);
        }

        @Test
        @DisplayName("receiver lambda 内使用外层变量（通过函数参数传递）")
        void receiverWithParam() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Box(var value: Int)");
            interp.evalRepl("fun setBox(b: Box, v: Int) { with(b) { value = v } }");
            interp.evalRepl("val b = Box(0)");
            interp.evalRepl("setBox(b, 142)");
            NovaValue r = interp.evalRepl("b.value");
            assertThat(r.asInt()).isEqualTo(142);
        }

        @Test
        @DisplayName("receiver lambda 返回值透传")
        void receiverBlockReturnValue() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Data(val x: Int, val y: Int)");
            interp.evalRepl("fun compute(block: Data.() -> Int): Int { val d = Data(3, 7); return d.block() }");
            NovaValue r = interp.evalRepl("compute { x + y }");
            assertThat(r.asInt()).isEqualTo(10);
        }

        @Test
        @DisplayName("with() 内方法调用（直接调用受体方法）")
        void withMethodCall() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class SB(var buf: String) { fun append(s: String) { buf = buf + s } }");
            interp.evalRepl("val sb = SB(\"\")");
            interp.evalRepl("with(sb) { append(\"hello\"); append(\" world\") }");
            NovaValue r = interp.evalRepl("sb.buf");
            assertThat(r.asString()).isEqualTo("hello world");
        }
    }

    // ========== Java 互操作 API ==========

    @Nested
    @DisplayName("Java API: invokeWithReceiver")
    class InvokeWithReceiverTest {

        @Test
        @DisplayName("invokeWithReceiver — Nova lambda 内调用 Java 对象方法")
        void invokeWithReceiverJavaObject() {
            Nova nova = new Nova();
            nova.eval("val block = { append(\"world\") }");
            Object block = nova.get("block");
            StringBuilder sb = new StringBuilder("hello ");
            nova.invokeWithReceiver(block, sb);
            assertThat(sb.toString()).isEqualTo("hello world");
        }

        @Test
        @DisplayName("invokeWithReceiver — callable 为 null 抛异常")
        void invokeWithReceiverNullCallable() {
            Nova nova = new Nova();
            assertThatThrownBy(() -> nova.invokeWithReceiver(null, new HashMap<>()))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("invokeWithReceiver — non-callable 抛异常")
        void invokeWithReceiverNonCallable() {
            Nova nova = new Nova();
            assertThatThrownBy(() -> nova.invokeWithReceiver("not a function", new HashMap<>()))
                    .isInstanceOf(NovaRuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Java API: defineBuilderFunction")
    class DefineBuilderFunctionTest {

        @Test
        @DisplayName("defineBuilderFunction — postAction 转换返回类型")
        void builderPostActionTransform() {
            Nova nova = new Nova();
            nova.defineBuilderFunction("buildString", StringBuilder::new,
                    sb -> sb.toString());
            Object result = nova.eval("buildString { append(\"Hello\"); append(\", World\") }");
            assertThat(result).isEqualTo("Hello, World");
        }

        @Test
        @DisplayName("defineBuilderFunction — 每次调用创建新实例")
        void builderFreshInstancePerCall() {
            Nova nova = new Nova();
            nova.defineBuilderFunction("bag", ArrayList::new);
            Object result = nova.eval(
                    "val a = bag { add(1) }\n" +
                    "val b = bag { add(2) }\n" +
                    "\"\" + a.size() + \",\" + b.size()");
            assertThat(result).isEqualTo("1,1");
        }

        @Test
        @DisplayName("defineBuilderFunction — lambda 内抛异常传播到调用方")
        void builderExceptionPropagation() {
            Nova nova = new Nova();
            nova.defineBuilderFunction("failing", HashMap::new);
            assertThatThrownBy(() -> nova.eval("failing { throw \"boom\" }"))
                    .isInstanceOf(Exception.class);
        }
    }

    // ========== T.() -> R 类型声明语法 ==========

    @Nested
    @DisplayName("T.() -> R 类型语法解析")
    class ReceiverTypeSyntax {

        @Test
        @DisplayName("无参接收者函数类型: Config.() -> Unit")
        void noParamReceiverType() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class C(var x: Int)");
            interp.evalRepl("fun go(block: C.() -> Unit): Int { val c = C(0); c.block(); return c.x }");
            NovaValue r = interp.evalRepl("go { x = 99 }");
            assertThat(r.asInt()).isEqualTo(99);
        }

        @Test
        @DisplayName("带参接收者函数类型不报语法错误")
        void paramReceiverTypeParsesOk() {
            // 验证 String.(Int) -> String 类型语法解析不报错
            Interpreter interp = new Interpreter();
            NovaValue r = interp.evalRepl("fun transform(f: String.(Int) -> String): Int = 42");
            assertThat(r).isNotNull();
        }

        @Test
        @DisplayName("函数类型变量声明: val f: Box.() -> Int")
        void receiverTypeVariable() {
            // 类型声明作为文档注解，运行时通过 b.f() 的 $ScopeCall 机制实现接收者绑定
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Box(val n: Int)");
            // lambda 和调用在同一 eval 中，确保 n 通过 scopeReceiver 解析
            NovaValue r = interp.evalRepl(
                "val f: Box.() -> Int = { n * 2 }\n" +
                "val b = Box(21)\n" +
                "b.f()");
            assertThat(r.asInt()).isEqualTo(42);
        }
    }

    // ========== 编译模式 ==========

    @Nested
    @DisplayName("编译模式 (compileToBytecode)")
    class CompiledMode {

        @Test
        @DisplayName("编译模式 — receiver 字段读写")
        void compiledFieldReadWrite() {
            Object r = new Nova().compileToBytecode(
                    "class Config(var host: String, var port: Int)\n" +
                    "fun configure(block: Config.() -> Unit): Config {\n" +
                    "    val c = Config(\"default\", 0)\n" +
                    "    c.block()\n" +
                    "    return c\n" +
                    "}\n" +
                    "val c = configure { host = \"prod\"; port = 9090 }\n" +
                    "c.host + \":\" + c.port", "test.nova").run();
            assertThat(r).isEqualTo("prod:9090");
        }

        @Test
        @DisplayName("编译模式 — receiver 方法调用")
        void compiledMethodCall() {
            Object r = new Nova().compileToBytecode(
                    "class SB(var buf: String) {\n" +
                    "    fun append(s: String) { buf = buf + s }\n" +
                    "}\n" +
                    "fun buildStr(block: SB.() -> Unit): String {\n" +
                    "    val sb = SB(\"\")\n" +
                    "    sb.block()\n" +
                    "    return sb.buf\n" +
                    "}\n" +
                    "buildStr { append(\"Hello\"); append(\", World\") }", "test.nova").run();
            assertThat(r).isEqualTo("Hello, World");
        }

        @Test
        @DisplayName("编译模式 — defineBuilderFunction (Java API)")
        void compiledBuilderFunction() {
            Nova nova = new Nova();
            nova.defineBuilderFunction("buildString", StringBuilder::new, sb -> sb.toString());
            Object result = nova.compileToBytecode(
                    "buildString { append(\"A\"); append(\"B\") }", "test.nova").run();
            assertThat(result).isEqualTo("AB");
        }
    }

    // ========== 边缘情况 ==========

    @Nested
    @DisplayName("边缘情况")
    class EdgeCases {

        @Test
        @DisplayName("receiver 为空列表 — 调用 size() 返回 0")
        void emptyReceiverList() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("fun withList(block: Any.() -> Int): Int { val list = []; return list.block() }");
            NovaValue r = interp.evalRepl("withList { size() }");
            assertThat(r.asInt()).isEqualTo(0);
        }

        @Test
        @DisplayName("receiver lambda 不修改任何东西（空 block）")
        void emptyBlock() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Box(var x: Int)");
            interp.evalRepl("fun noop(block: Box.() -> Unit): Int { val b = Box(42); b.block(); return b.x }");
            NovaValue r = interp.evalRepl("noop { }");
            assertThat(r.asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("apply scope function 字段修改")
        void applyScopeFunction() {
            Interpreter interp = new Interpreter();
            interp.evalRepl("class Cfg(var host: String, var port: Int)");
            interp.evalRepl("val c = Cfg(\"localhost\", 80)");
            interp.evalRepl("c.apply { host = \"prod\"; port = 443 }");
            NovaValue r = interp.evalRepl("c.host + \":\" + c.port");
            assertThat(r.asString()).isEqualTo("prod:443");
        }
    }
}
