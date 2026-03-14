package com.novalang.runtime.host;

import com.novalang.runtime.NovaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FunctionBuilder 泛型 invoke 测试")
class FunctionBuilderTypedTest {

    /**
     * 辅助方法：构建一个 registry 并提取函数的 invoker 来测试
     */
    private HostFunctionInvoker buildInvoker(java.util.function.Consumer<HostBindingRegistry.FunctionBuilder> spec) {
        HostBindingRegistry registry = HostBindingRegistry.builder()
                .globalFunction("test", spec)
                .build();
        HostFunctionDescriptor fn = (HostFunctionDescriptor) registry.globals().get(0);
        return fn.getInvoker();
    }

    @Test
    @DisplayName("invoke1 类型安全 — String 参数")
    void invoke1String() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke1(String.class, (String s) -> "Hello, " + s));

        Object result = invoker.invoke("World");
        assertThat(result).isEqualTo("Hello, World");
    }

    @Test
    @DisplayName("invoke1 类型安全 — Integer 参数")
    void invoke1Integer() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke1(Integer.class, (Integer n) -> n * 2));

        Object result = invoker.invoke(5);
        assertThat(result).isEqualTo(10);
    }

    @Test
    @DisplayName("invoke1 自动数值宽化 — Number → Integer")
    void invoke1NumberWidening() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke1(Integer.class, (Integer n) -> n + 1));

        // Long 传入但目标是 Integer，应自动宽化
        Object result = invoker.invoke(Long.valueOf(9));
        assertThat(result).isEqualTo(10);
    }

    @Test
    @DisplayName("invoke2 类型安全 — 两个 Integer")
    void invoke2Integers() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke2(Integer.class, Integer.class, (Integer a, Integer b) -> a + b));

        Object result = invoker.invoke(3, 4);
        assertThat(result).isEqualTo(7);
    }

    @Test
    @DisplayName("invoke2 类型安全 — String + Integer")
    void invoke2Mixed() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke2(String.class, Integer.class, (String name, Integer age) ->
                        name + " is " + age));

        Object result = invoker.invoke("Alice", 30);
        assertThat(result).isEqualTo("Alice is 30");
    }

    @Test
    @DisplayName("invoke3 类型安全 — 三个参数")
    void invoke3() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke3(Integer.class, Integer.class, Integer.class,
                        (Integer a, Integer b, Integer c) -> a + b + c));

        Object result = invoker.invoke(1, 2, 3);
        assertThat(result).isEqualTo(6);
    }

    @Test
    @DisplayName("invoke0 类型安全 — 无参数")
    void invoke0() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke0(String.class, () -> "constant"));

        Object result = invoker.invoke();
        assertThat(result).isEqualTo("constant");
    }

    @Test
    @DisplayName("类型不匹配时抛出 NovaException")
    void typeMismatchThrows() {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke1(Integer.class, (Integer n) -> n * 2));

        // 传入不可转换的类型
        assertThatThrownBy(() -> invoker.invoke(new Object()))
                .isInstanceOf(NovaException.class);
    }

    @Test
    @DisplayName("null 参数传入引用类型")
    void nullArgToReferenceType() throws Exception {
        HostFunctionInvoker invoker = buildInvoker(fn ->
                fn.invoke1(String.class, (String s) -> s == null ? "null!" : s));

        Object result = invoker.invoke((Object) null);
        assertThat(result).isEqualTo("null!");
    }
}
