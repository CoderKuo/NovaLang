package com.novalang.runtime;

import com.novalang.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/**
 * 文件注解 (@file:) 综合测试。
 * 覆盖：语法解析、处理器回调、Java API、编译模式、边缘值。
 */
@DisplayName("文件注解 (@file:)")
class FileAnnotationTest {

    // ========== 语法解析 ==========

    @Nested
    @DisplayName("语法解析")
    class Parsing {

        @Test
        @DisplayName("单个文件注解 — 字符串参数")
        void singleAnnotationStringArg() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:DependsOn(\"com.google.guava:guava:31.1\")\n" +
                    "val x = 1");
            assertThat(anns).hasSize(1);
            assertThat(anns.get(0).name).isEqualTo("DependsOn");
            assertThat(anns.get(0).args.get("value")).isEqualTo("com.google.guava:guava:31.1");
        }

        @Test
        @DisplayName("多个文件注解")
        void multipleAnnotations() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:DependsOn(\"guava:31\")\n" +
                    "@file:Repository(\"https://maven.aliyun.com\")\n" +
                    "val x = 1");
            assertThat(anns).hasSize(2);
            assertThat(anns.get(0).name).isEqualTo("DependsOn");
            assertThat(anns.get(1).name).isEqualTo("Repository");
        }

        @Test
        @DisplayName("命名参数")
        void namedArgs() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:Config(name = \"app\", version = 2)\n" +
                    "val x = 1");
            assertThat(anns).hasSize(1);
            assertThat(anns.get(0).args.get("name")).isEqualTo("app");
            assertThat(anns.get(0).args.get("version")).isEqualTo(2);
        }

        @Test
        @DisplayName("无参数注解")
        void noArgs() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:Experimental\n" +
                    "val x = 1");
            assertThat(anns).hasSize(1);
            assertThat(anns.get(0).name).isEqualTo("Experimental");
            assertThat(anns.get(0).args).isEmpty();
        }

        @Test
        @DisplayName("无文件注解 — 返回空列表")
        void noFileAnnotations() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations("val x = 1");
            assertThat(anns).isEmpty();
        }

        @Test
        @DisplayName("文件注解后跟 package 声明")
        void fileAnnotationBeforePackage() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:JvmName(\"Utils\")\n" +
                    "package myapp\n" +
                    "val x = 1");
            assertThat(anns).hasSize(1);
            assertThat(anns.get(0).name).isEqualTo("JvmName");
        }

        @Test
        @DisplayName("普通注解不被识别为文件注解")
        void regularAnnotationNotFileAnnotation() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@data class User(val name: String)");
            assertThat(anns).isEmpty();
        }

        @Test
        @DisplayName("布尔参数")
        void booleanArg() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:Strict(true)\n" +
                    "val x = 1");
            assertThat(anns).hasSize(1);
            assertThat(anns.get(0).args.get("value")).isEqualTo(true);
        }
    }

    // ========== 处理器回调 ==========

    @Nested
    @DisplayName("处理器回调")
    class ProcessorCallback {

        @Test
        @DisplayName("文件注解处理器在脚本执行前被调用")
        void processorCalledBeforeExecution() {
            List<String> callOrder = new ArrayList<>();
            Nova nova = new Nova();
            nova.registerFileAnnotationProcessor("Setup", args -> {
                callOrder.add("processor:" + args.get("value"));
            });
            nova.eval(
                    "@file:Setup(\"init\")\n" +
                    "val x = 42");
            assertThat(callOrder).containsExactly("processor:init");
        }

        @Test
        @DisplayName("多个文件注解按声明顺序执行")
        void processorsCalledInOrder() {
            List<String> callOrder = new ArrayList<>();
            Nova nova = new Nova();
            nova.registerFileAnnotationProcessor("First", args -> callOrder.add("first"));
            nova.registerFileAnnotationProcessor("Second", args -> callOrder.add("second"));
            nova.eval(
                    "@file:First\n" +
                    "@file:Second\n" +
                    "val x = 1");
            assertThat(callOrder).containsExactly("first", "second");
        }

        @Test
        @DisplayName("文件注解处理器接收正确的参数值")
        void processorReceivesCorrectArgs() {
            AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
            Nova nova = new Nova();
            nova.registerFileAnnotationProcessor("Maven", captured::set);
            nova.eval(
                    "@file:Maven(group = \"com.google\", artifact = \"guava\", version = \"31.1\")\n" +
                    "val x = 1");
            assertThat(captured.get()).containsEntry("group", "com.google");
            assertThat(captured.get()).containsEntry("artifact", "guava");
            assertThat(captured.get()).containsEntry("version", "31.1");
        }

        @Test
        @DisplayName("未注册处理器的注解静默忽略")
        void unregisteredAnnotationIgnored() {
            Nova nova = new Nova();
            // 不注册处理器，不应抛异常
            Object result = nova.eval(
                    "@file:Unknown(\"test\")\n" +
                    "42");
            assertThat(result).isEqualTo(42);
        }

        @Test
        @DisplayName("处理器抛出异常传播到调用方")
        void processorExceptionPropagates() {
            Nova nova = new Nova();
            nova.registerFileAnnotationProcessor("Failing", args -> {
                throw new RuntimeException("init failed");
            });
            assertThatThrownBy(() -> nova.eval("@file:Failing\nval x = 1"))
                    .isInstanceOf(Exception.class)
                    .hasMessageContaining("init failed");
        }
    }

    // ========== Java API ==========

    @Nested
    @DisplayName("Java API")
    class JavaApi {

        @Test
        @DisplayName("extractFileAnnotations — toString 输出格式")
        void toStringFormat() {
            List<Nova.FileAnnotation> anns = Nova.extractFileAnnotations(
                    "@file:DependsOn(\"guava\")\n" +
                    "val x = 1");
            assertThat(anns.get(0).toString()).contains("@file:DependsOn");
        }

        @Test
        @DisplayName("registerFileAnnotationProcessor 返回 Nova 实例（链式调用）")
        void chainingApi() {
            Nova nova = new Nova();
            Nova result = nova
                    .registerFileAnnotationProcessor("A", args -> {})
                    .registerFileAnnotationProcessor("B", args -> {});
            assertThat(result).isSameAs(nova);
        }
    }

    // ========== 编译模式 ==========

    @Nested
    @DisplayName("编译模式")
    class CompiledMode {

        @Test
        @DisplayName("编译模式可解析文件注解")
        void compiledModeParseFileAnnotation() {
            // 编译模式：文件注解不影响编译结果
            Object r = new Nova().compileToBytecode(
                    "@file:Version(\"1.0\")\n" +
                    "42", "test.nova").run();
            assertThat(r).isEqualTo(42);
        }
    }

    // ========== 解释器直接使用 ==========

    @Nested
    @DisplayName("解释器模式")
    class InterpreterMode {

        @Test
        @DisplayName("Interpreter.eval 支持文件注解")
        void interpreterEval() {
            Interpreter interp = new Interpreter();
            interp.registerAnnotationProcessor(new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() { return "Tag"; }
                @Override
                public void processFile(Map<String, Object> args) {
                    // 验证回调被触发
                    if (!"test".equals(args.get("value"))) {
                        throw new RuntimeException("unexpected arg: " + args.get("value"));
                    }
                }
            });
            NovaValue r = interp.eval(
                    "@file:Tag(\"test\")\n" +
                    "42", "<test>");
            assertThat(r.asInt()).isEqualTo(42);
        }

        @Test
        @DisplayName("evalRepl 支持文件注解")
        void evalReplFileAnnotation() {
            Interpreter interp = new Interpreter();
            List<String> captured = new ArrayList<>();
            interp.registerAnnotationProcessor(new NovaAnnotationProcessor() {
                @Override
                public String getAnnotationName() { return "Log"; }
                @Override
                public void processFile(Map<String, Object> args) {
                    captured.add("logged:" + args.get("value"));
                }
            });
            interp.evalRepl("@file:Log(\"repl\")\nval x = 1");
            assertThat(captured).containsExactly("logged:repl");
        }
    }
}
