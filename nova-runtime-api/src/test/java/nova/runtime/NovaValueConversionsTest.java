package nova.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("NovaValueConversions 类型转换")
class NovaValueConversionsTest {

    @Nested
    @DisplayName("null 处理")
    class NullHandling {
        @Test
        @DisplayName("null → 引用类型返回 null")
        void nullToReferenceType() {
            assertThat(NovaValueConversions.convertArg(null, String.class)).isNull();
            assertThat(NovaValueConversions.convertArg(null, Integer.class)).isNull();
            assertThat(NovaValueConversions.convertArg(null, List.class)).isNull();
        }

        @Test
        @DisplayName("null → 基本类型抛异常")
        void nullToPrimitiveThrows() {
            assertThatThrownBy(() -> NovaValueConversions.convertArg(null, int.class))
                    .isInstanceOf(NovaException.class)
                    .hasMessageContaining("primitive");
        }
    }

    @Nested
    @DisplayName("直接赋值")
    class DirectAssignment {
        @Test
        @DisplayName("String → String")
        void stringToString() {
            assertThat(NovaValueConversions.convertArg("hello", String.class)).isEqualTo("hello");
        }

        @Test
        @DisplayName("Integer → Integer")
        void integerToInteger() {
            assertThat(NovaValueConversions.convertArg(42, Integer.class)).isEqualTo(42);
        }

        @Test
        @DisplayName("List → List")
        void listToList() {
            List<String> list = Arrays.asList("a", "b");
            assertThat(NovaValueConversions.convertArg(list, List.class)).isSameAs(list);
        }
    }

    @Nested
    @DisplayName("数值宽化")
    class NumberWidening {
        @Test
        @DisplayName("Integer → long")
        void intToLong() {
            assertThat(NovaValueConversions.convertArg(42, long.class)).isEqualTo(42L);
        }

        @Test
        @DisplayName("Integer → double")
        void intToDouble() {
            assertThat(NovaValueConversions.convertArg(42, double.class)).isEqualTo(42.0);
        }

        @Test
        @DisplayName("Long → double")
        void longToDouble() {
            assertThat(NovaValueConversions.convertArg(100L, double.class)).isEqualTo(100.0);
        }

        @Test
        @DisplayName("Double → float")
        void doubleToFloat() {
            assertThat(NovaValueConversions.convertArg(3.14, float.class)).isEqualTo(3.14f);
        }

        @Test
        @DisplayName("Integer → int (unbox)")
        void integerToInt() {
            assertThat(NovaValueConversions.convertArg(Integer.valueOf(7), int.class)).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("NovaValue 转换")
    class NovaValueConversion {
        @Test
        @DisplayName("NovaInt → int")
        void novaIntToInt() {
            assertThat(NovaValueConversions.convertArg(NovaInt.of(42), int.class)).isEqualTo(42);
        }

        @Test
        @DisplayName("NovaInt → long (宽化)")
        void novaIntToLong() {
            assertThat(NovaValueConversions.convertArg(NovaInt.of(42), long.class)).isEqualTo(42L);
        }

        @Test
        @DisplayName("NovaInt → double (宽化)")
        void novaIntToDouble() {
            assertThat(NovaValueConversions.convertArg(NovaInt.of(42), double.class)).isEqualTo(42.0);
        }

        @Test
        @DisplayName("NovaString → String")
        void novaStringToString() {
            assertThat(NovaValueConversions.convertArg(NovaString.of("hi"), String.class)).isEqualTo("hi");
        }

        @Test
        @DisplayName("NovaBoolean → boolean")
        void novaBooleanToBoolean() {
            assertThat(NovaValueConversions.convertArg(NovaBoolean.TRUE, boolean.class)).isTrue();
            assertThat(NovaValueConversions.convertArg(NovaBoolean.FALSE, boolean.class)).isFalse();
        }

        @Test
        @DisplayName("NovaDouble → double")
        void novaDoubleToDouble() {
            assertThat(NovaValueConversions.convertArg(NovaDouble.of(3.14), double.class)).isEqualTo(3.14);
        }

        @Test
        @DisplayName("NovaNull → 引用类型返回 null")
        void novaNullToReferenceType() {
            assertThat(NovaValueConversions.convertArg(NovaNull.NULL, String.class)).isNull();
        }

        @Test
        @DisplayName("NovaNull → 基本类型抛异常")
        void novaNullToPrimitiveThrows() {
            assertThatThrownBy(() -> NovaValueConversions.convertArg(NovaNull.NULL, int.class))
                    .isInstanceOf(NovaException.class)
                    .hasMessageContaining("primitive");
        }

        @Test
        @DisplayName("NovaValue → NovaValue 返回自身")
        void novaValueToNovaValue() {
            NovaValue val = NovaInt.of(1);
            assertThat(NovaValueConversions.convertArg(val, NovaValue.class)).isSameAs(val);
        }

        @Test
        @DisplayName("NovaList → List")
        void novaListToList() {
            NovaList list = new NovaList(Arrays.asList(NovaInt.of(1), NovaInt.of(2)));
            List<?> result = NovaValueConversions.convertArg(list, List.class);
            assertThat(result).hasSize(2);
        }

        @Test
        @DisplayName("NovaMap → Map")
        void novaMapToMap() {
            Map<NovaValue, NovaValue> inner = new HashMap<>();
            inner.put(NovaString.of("a"), NovaInt.of(1));
            NovaMap map = new NovaMap(inner);
            Map<?, ?> result = NovaValueConversions.convertArg(map, Map.class);
            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("String 回退")
    class StringFallback {
        @Test
        @DisplayName("Integer → String")
        void integerToString() {
            assertThat(NovaValueConversions.convertArg(42, String.class)).isEqualTo("42");
        }

        @Test
        @DisplayName("Boolean → String")
        void booleanToString() {
            assertThat(NovaValueConversions.convertArg(true, String.class)).isEqualTo("true");
        }
    }

    @Nested
    @DisplayName("NovaValue.toJava")
    class ToJavaMethod {
        @Test
        @DisplayName("NovaInt.toJava(int.class)")
        void novaIntToJava() {
            NovaValue val = NovaInt.of(99);
            assertThat(val.toJava(int.class)).isEqualTo(99);
        }

        @Test
        @DisplayName("NovaString.toJava(String.class)")
        void novaStringToJava() {
            NovaValue val = NovaString.of("test");
            assertThat(val.toJava(String.class)).isEqualTo("test");
        }
    }

    @Nested
    @DisplayName("错误场景")
    class ErrorCases {
        @Test
        @DisplayName("不兼容类型抛 NovaException")
        void incompatibleTypeThrows() {
            assertThatThrownBy(() -> NovaValueConversions.convertArg("hello", int.class))
                    .isInstanceOf(Exception.class);
        }

        @Test
        @DisplayName("NovaString → int 抛异常")
        void novaStringToIntThrows() {
            assertThatThrownBy(() -> NovaValueConversions.convertArg(NovaString.of("abc"), int.class))
                    .isInstanceOf(Exception.class);
        }
    }
}
