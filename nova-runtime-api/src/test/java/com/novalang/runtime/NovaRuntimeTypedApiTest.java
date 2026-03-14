package com.novalang.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NovaRuntime 类型安全 API")
class NovaRuntimeTypedApiTest {

    private NovaRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = NovaRuntime.create();
    }

    @Nested
    @DisplayName("call()")
    class CallMethod {
        @Test
        @DisplayName("返回 NovaValue 而非 Object")
        void returnsNovaValue() throws Exception {
            runtime.registerFunction("add", Integer.class, Integer.class, Integer.class,
                    (a, b) -> a + b);

            NovaValue result = runtime.call("add", 3, 4);
            assertThat(result).isNotNull();
            assertThat(result.asInt()).isEqualTo(7);
            assertThat(result.toJava(int.class)).isEqualTo(7);
        }

        @Test
        @DisplayName("返回 null 时得到 NovaNull")
        void nullReturnsNovaNull() throws Exception {
            runtime.registerFunction("nothing", Object.class,
                    (Function0<Object>) () -> null);

            NovaValue result = runtime.call("nothing");
            assertThat(result.isNull()).isTrue();
        }

        @Test
        @DisplayName("函数不存在抛异常")
        void notFoundThrows() {
            assertThatThrownBy(() -> runtime.call("nonExistent"))
                    .isInstanceOf(NoSuchMethodException.class);
        }

        @Test
        @DisplayName("字符串函数返回正确类型")
        void stringFunction() throws Exception {
            runtime.registerFunction("greet", String.class, String.class,
                    (Function1<String, String>) name -> "Hello, " + name);

            NovaValue result = runtime.call("greet", "World");
            assertThat(result.toJava(String.class)).isEqualTo("Hello, World");
        }
    }

    @Nested
    @DisplayName("callExtension()")
    class CallExtensionMethod {
        @Test
        @DisplayName("扩展方法返回 NovaValue")
        void returnsNovaValue() throws Exception {
            runtime.registerExtension(String.class, "shout", String.class,
                    (Extension0<String, String>) s -> s.toUpperCase() + "!");

            NovaValue result = runtime.callExtension("hello", "shout");
            assertThat(result.toJava(String.class)).isEqualTo("HELLO!");
        }

        @Test
        @DisplayName("null receiver 抛异常")
        void nullReceiverThrows() {
            assertThatThrownBy(() -> runtime.callExtension(null, "shout"))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("global()")
    class GlobalMethod {
        @Test
        @DisplayName("获取已设置的全局变量")
        void getSetGlobal() {
            runtime.setGlobal("PI", 3.14159);

            NovaValue val = runtime.global("PI");
            assertThat(val.toJava(double.class)).isEqualTo(3.14159);
        }

        @Test
        @DisplayName("不存在的变量返回 NovaNull")
        void missingGlobalReturnsNull() {
            NovaValue val = runtime.global("nonExistent");
            assertThat(val.isNull()).isTrue();
        }

        @Test
        @DisplayName("设置 NovaValue 类型的全局变量")
        void setNovaValueGlobal() {
            runtime.setGlobal("count", NovaInt.of(42));

            NovaValue val = runtime.global("count");
            assertThat(val.asInt()).isEqualTo(42);
        }
    }
}
