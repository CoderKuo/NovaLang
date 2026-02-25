package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * String 扩展方法集成测试：解释器 + 编译器双路径。
 */
@DisplayName("String 扩展方法集成测试")
class StringExtensionsIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private NovaValue interp(String code) {
        return new Interpreter().eval(code, "test.nova");
    }

    private Object compile(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> c = loaded.get("Test");
        assertNotNull(c, "编译后应生成 Test 类");
        Object inst = c.getField("INSTANCE").get(null);
        Method m = c.getDeclaredMethod("run");
        m.setAccessible(true);
        return m.invoke(inst);
    }

    private String wrap(String body) {
        return "object Test {\n  fun run(): Any {\n" + body + "\n  }\n}";
    }

    private void dual(String interpCode, String compileBody, Object expected) throws Exception {
        NovaValue ir = interp(interpCode);
        Object cr = compile(compileBody);
        if (expected instanceof Integer) {
            assertEquals(expected, ir.asInt(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Boolean) {
            assertEquals(expected, ir.asBoolean(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Long) {
            assertEquals(expected, ir.asLong(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Double) {
            assertEquals((Double) expected, ir.asDouble(), 0.001, "解释器");
            assertEquals((Double) expected, ((Number) cr).doubleValue(), 0.001, "编译器");
        } else if (expected == null) {
            assertTrue(ir.isNull(), "解释器应为 null");
            assertNull(cr, "编译器应为 null");
        } else {
            assertEquals(String.valueOf(expected), ir.asString(), "解释器");
            assertEquals(String.valueOf(expected), String.valueOf(cr), "编译器");
        }
    }

    private void dualStr(String interpCode, String compileBody, String expected) throws Exception {
        NovaValue ir = interp(interpCode);
        Object cr = compile(compileBody);
        assertEquals(expected, ir.asString(), "解释器");
        assertEquals(expected, String.valueOf(cr), "编译器");
    }

    private void dualThrows(String interpCode, String compileBody) {
        assertThrows(Exception.class, () -> interp(interpCode), "解释器应抛异常");
        assertThrows(Exception.class, () -> compile(compileBody), "编译器应抛异常");
    }

    // ========== isNotEmpty / isBlank ==========

    @Nested
    @DisplayName("isNotEmpty / isBlank")
    class IsNotEmptyIsBlank {

        @Test void isNotEmpty_true() throws Exception {
            dual("\"hello\".isNotEmpty()", wrap("return \"hello\".isNotEmpty()"), true);
        }

        @Test void isNotEmpty_false() throws Exception {
            dual("\"\".isNotEmpty()", wrap("return \"\".isNotEmpty()"), false);
        }

        @Test void isBlank_true_empty() throws Exception {
            dual("\"\".isBlank()", wrap("return \"\".isBlank()"), true);
        }

        @Test void isBlank_true_spaces() throws Exception {
            dual("\"   \".isBlank()", wrap("return \"   \".isBlank()"), true);
        }

        @Test void isBlank_false() throws Exception {
            dual("\"hi\".isBlank()", wrap("return \"hi\".isBlank()"), false);
        }
    }

    // ========== uppercase / lowercase ==========

    @Nested
    @DisplayName("uppercase / lowercase")
    class UpperLower {

        @Test void uppercase_normal() throws Exception {
            dual("\"hello\".uppercase()", wrap("return \"hello\".uppercase()"), "HELLO");
        }

        @Test void uppercase_empty() throws Exception {
            dual("\"\".uppercase()", wrap("return \"\".uppercase()"), "");
        }

        @Test void lowercase_normal() throws Exception {
            dual("\"HELLO\".lowercase()", wrap("return \"HELLO\".lowercase()"), "hello");
        }

        @Test void lowercase_empty() throws Exception {
            dual("\"\".lowercase()", wrap("return \"\".lowercase()"), "");
        }
    }

    // ========== trimStart / trimEnd ==========

    @Nested
    @DisplayName("trimStart / trimEnd")
    class TrimStartEnd {

        @Test void trimStart_normal() throws Exception {
            dual("\"  hi  \".trimStart()", wrap("return \"  hi  \".trimStart()"), "hi  ");
        }

        @Test void trimStart_no_leading() throws Exception {
            dual("\"hi\".trimStart()", wrap("return \"hi\".trimStart()"), "hi");
        }

        @Test void trimStart_empty() throws Exception {
            dual("\"\".trimStart()", wrap("return \"\".trimStart()"), "");
        }

        @Test void trimEnd_normal() throws Exception {
            dual("\"  hi  \".trimEnd()", wrap("return \"  hi  \".trimEnd()"), "  hi");
        }

        @Test void trimEnd_no_trailing() throws Exception {
            dual("\"hi\".trimEnd()", wrap("return \"hi\".trimEnd()"), "hi");
        }

        @Test void trimEnd_empty() throws Exception {
            dual("\"\".trimEnd()", wrap("return \"\".trimEnd()"), "");
        }
    }

    // ========== reverse ==========

    @Nested
    @DisplayName("reverse")
    class Reverse {

        @Test void reverse_normal() throws Exception {
            dual("\"abc\".reverse()", wrap("return \"abc\".reverse()"), "cba");
        }

        @Test void reverse_empty() throws Exception {
            dual("\"\".reverse()", wrap("return \"\".reverse()"), "");
        }

        @Test void reverse_single() throws Exception {
            dual("\"x\".reverse()", wrap("return \"x\".reverse()"), "x");
        }

        @Test void reverse_palindrome() throws Exception {
            dual("\"aba\".reverse()", wrap("return \"aba\".reverse()"), "aba");
        }
    }

    // ========== capitalize / decapitalize ==========

    @Nested
    @DisplayName("capitalize / decapitalize")
    class CapDecap {

        @Test void capitalize_normal() throws Exception {
            dual("\"hello\".capitalize()", wrap("return \"hello\".capitalize()"), "Hello");
        }

        @Test void capitalize_empty() throws Exception {
            dual("\"\".capitalize()", wrap("return \"\".capitalize()"), "");
        }

        @Test void capitalize_already() throws Exception {
            dual("\"Hello\".capitalize()", wrap("return \"Hello\".capitalize()"), "Hello");
        }

        @Test void decapitalize_normal() throws Exception {
            dual("\"Hello\".decapitalize()", wrap("return \"Hello\".decapitalize()"), "hello");
        }

        @Test void decapitalize_empty() throws Exception {
            dual("\"\".decapitalize()", wrap("return \"\".decapitalize()"), "");
        }

        @Test void decapitalize_already() throws Exception {
            dual("\"hello\".decapitalize()", wrap("return \"hello\".decapitalize()"), "hello");
        }
    }

    // ========== toList / lines / chars ==========

    @Nested
    @DisplayName("toList / lines / chars")
    class ToListLinesChars {

        @Test void toList_normal() throws Exception {
            dualStr("\"abc\".toList()", wrap("return \"abc\".toList()"), "[a, b, c]");
        }

        @Test void toList_empty() throws Exception {
            dualStr("\"\".toList()", wrap("return \"\".toList()"), "[]");
        }

        @Test void lines_normal() throws Exception {
            dual("\"a\\nb\\nc\".lines().size()", wrap("return \"a\\nb\\nc\".lines().size()"), 3);
        }

        @Test void lines_single() throws Exception {
            dual("\"hello\".lines().size()", wrap("return \"hello\".lines().size()"), 1);
        }

        @Test void chars_normal() throws Exception {
            dual("\"ab\".chars().size()", wrap("return \"ab\".chars().size()"), 2);
        }

        @Test void chars_empty() throws Exception {
            dual("\"\".chars().size()", wrap("return \"\".chars().size()"), 0);
        }
    }

    // ========== toInt / toLong / toDouble / toBoolean ==========

    @Nested
    @DisplayName("类型转换")
    class TypeConversion {

        @Test void toInt_normal() throws Exception {
            dual("\"42\".toInt()", wrap("return \"42\".toInt()"), 42);
        }

        @Test void toInt_negative() throws Exception {
            dual("\"-7\".toInt()", wrap("return \"-7\".toInt()"), -7);
        }

        @Test void toInt_invalid_throws() {
            dualThrows("\"abc\".toInt()", wrap("return \"abc\".toInt()"));
        }

        @Test void toLong_normal() throws Exception {
            dual("\"100\".toLong()", wrap("return \"100\".toLong()"), 100L);
        }

        @Test void toDouble_normal() throws Exception {
            dual("\"3.14\".toDouble()", wrap("return \"3.14\".toDouble()"), 3.14);
        }

        @Test void toDouble_invalid_throws() {
            dualThrows("\"xyz\".toDouble()", wrap("return \"xyz\".toDouble()"));
        }

        @Test void toBoolean_true() throws Exception {
            dual("\"true\".toBoolean()", wrap("return \"true\".toBoolean()"), true);
        }

        @Test void toBoolean_false() throws Exception {
            dual("\"false\".toBoolean()", wrap("return \"false\".toBoolean()"), false);
        }

        @Test void toBoolean_anything_is_false() throws Exception {
            dual("\"hello\".toBoolean()", wrap("return \"hello\".toBoolean()"), false);
        }
    }

    // ========== toIntOrNull / toLongOrNull / toDoubleOrNull ==========

    @Nested
    @DisplayName("安全类型转换")
    class SafeConversion {

        @Test void toIntOrNull_valid() throws Exception {
            dual("\"42\".toIntOrNull()", wrap("return \"42\".toIntOrNull()"), 42);
        }

        @Test void toIntOrNull_invalid() throws Exception {
            dual("\"abc\".toIntOrNull()", wrap("return \"abc\".toIntOrNull()"), null);
        }

        @Test void toIntOrNull_empty() throws Exception {
            dual("\"\".toIntOrNull()", wrap("return \"\".toIntOrNull()"), null);
        }

        @Test void toLongOrNull_valid() throws Exception {
            dual("\"100\".toLongOrNull()", wrap("return \"100\".toLongOrNull()"), 100L);
        }

        @Test void toLongOrNull_invalid() throws Exception {
            dual("\"xyz\".toLongOrNull()", wrap("return \"xyz\".toLongOrNull()"), null);
        }

        @Test void toDoubleOrNull_valid() throws Exception {
            dual("\"3.14\".toDoubleOrNull()", wrap("return \"3.14\".toDoubleOrNull()"), 3.14);
        }

        @Test void toDoubleOrNull_invalid() throws Exception {
            dual("\"abc\".toDoubleOrNull()", wrap("return \"abc\".toDoubleOrNull()"), null);
        }
    }

    // ========== take / drop / takeLast / dropLast ==========

    @Nested
    @DisplayName("take / drop / takeLast / dropLast")
    class TakeDrop {

        @Test void take_normal() throws Exception {
            dual("\"hello\".take(3)", wrap("return \"hello\".take(3)"), "hel");
        }

        @Test void take_more_than_length() throws Exception {
            dual("\"hi\".take(10)", wrap("return \"hi\".take(10)"), "hi");
        }

        @Test void take_zero() throws Exception {
            dual("\"hello\".take(0)", wrap("return \"hello\".take(0)"), "");
        }

        @Test void take_empty() throws Exception {
            dual("\"\".take(5)", wrap("return \"\".take(5)"), "");
        }

        @Test void drop_normal() throws Exception {
            dual("\"hello\".drop(2)", wrap("return \"hello\".drop(2)"), "llo");
        }

        @Test void drop_more_than_length() throws Exception {
            dual("\"hi\".drop(10)", wrap("return \"hi\".drop(10)"), "");
        }

        @Test void drop_zero() throws Exception {
            dual("\"hello\".drop(0)", wrap("return \"hello\".drop(0)"), "hello");
        }

        @Test void takeLast_normal() throws Exception {
            dual("\"hello\".takeLast(3)", wrap("return \"hello\".takeLast(3)"), "llo");
        }

        @Test void takeLast_more_than_length() throws Exception {
            dual("\"hi\".takeLast(10)", wrap("return \"hi\".takeLast(10)"), "hi");
        }

        @Test void dropLast_normal() throws Exception {
            dual("\"hello\".dropLast(2)", wrap("return \"hello\".dropLast(2)"), "hel");
        }

        @Test void dropLast_more_than_length() throws Exception {
            dual("\"hi\".dropLast(10)", wrap("return \"hi\".dropLast(10)"), "");
        }
    }

    // ========== repeat ==========

    @Nested
    @DisplayName("repeat")
    class Repeat {

        @Test void repeat_normal() throws Exception {
            dual("\"ab\".repeat(3)", wrap("return \"ab\".repeat(3)"), "ababab");
        }

        @Test void repeat_zero() throws Exception {
            dual("\"hello\".repeat(0)", wrap("return \"hello\".repeat(0)"), "");
        }

        @Test void repeat_one() throws Exception {
            dual("\"hi\".repeat(1)", wrap("return \"hi\".repeat(1)"), "hi");
        }

        @Test void repeat_empty() throws Exception {
            dual("\"\".repeat(5)", wrap("return \"\".repeat(5)"), "");
        }
    }

    // ========== removePrefix / removeSuffix ==========

    @Nested
    @DisplayName("removePrefix / removeSuffix")
    class RemovePrefixSuffix {

        @Test void removePrefix_match() throws Exception {
            dual("\"hello world\".removePrefix(\"hello \")", wrap("return \"hello world\".removePrefix(\"hello \")"), "world");
        }

        @Test void removePrefix_no_match() throws Exception {
            dual("\"hello\".removePrefix(\"xyz\")", wrap("return \"hello\".removePrefix(\"xyz\")"), "hello");
        }

        @Test void removePrefix_empty() throws Exception {
            dual("\"hello\".removePrefix(\"\")", wrap("return \"hello\".removePrefix(\"\")"), "hello");
        }

        @Test void removeSuffix_match() throws Exception {
            dual("\"hello.txt\".removeSuffix(\".txt\")", wrap("return \"hello.txt\".removeSuffix(\".txt\")"), "hello");
        }

        @Test void removeSuffix_no_match() throws Exception {
            dual("\"hello\".removeSuffix(\".txt\")", wrap("return \"hello\".removeSuffix(\".txt\")"), "hello");
        }

        @Test void removeSuffix_empty() throws Exception {
            dual("\"hello\".removeSuffix(\"\")", wrap("return \"hello\".removeSuffix(\"\")"), "hello");
        }
    }

    // ========== any / all / none / count ==========

    @Nested
    @DisplayName("any / all / none / count")
    class Predicates {

        @Test void any_true() throws Exception {
            dual("\"abc\".any { it == 'b' }", wrap("return \"abc\".any { it == 'b' }"), true);
        }

        @Test void any_false() throws Exception {
            dual("\"abc\".any { it == 'z' }", wrap("return \"abc\".any { it == 'z' }"), false);
        }

        @Test void any_empty() throws Exception {
            dual("\"\".any { it == 'a' }", wrap("return \"\".any { it == 'a' }"), false);
        }

        @Test void all_true() throws Exception {
            dual("\"aaa\".all { it == 'a' }", wrap("return \"aaa\".all { it == 'a' }"), true);
        }

        @Test void all_false() throws Exception {
            dual("\"abc\".all { it == 'a' }", wrap("return \"abc\".all { it == 'a' }"), false);
        }

        @Test void all_empty() throws Exception {
            dual("\"\".all { it == 'a' }", wrap("return \"\".all { it == 'a' }"), true);
        }

        @Test void none_true() throws Exception {
            dual("\"abc\".none { it == 'z' }", wrap("return \"abc\".none { it == 'z' }"), true);
        }

        @Test void none_false() throws Exception {
            dual("\"abc\".none { it == 'a' }", wrap("return \"abc\".none { it == 'a' }"), false);
        }

        @Test void none_empty() throws Exception {
            dual("\"\".none { it == 'a' }", wrap("return \"\".none { it == 'a' }"), true);
        }

        @Test void count_normal() throws Exception {
            dual("\"aabbc\".count { it == 'a' }", wrap("return \"aabbc\".count { it == 'a' }"), 2);
        }

        @Test void count_none() throws Exception {
            dual("\"abc\".count { it == 'z' }", wrap("return \"abc\".count { it == 'z' }"), 0);
        }

        @Test void count_empty() throws Exception {
            dual("\"\".count { it == 'a' }", wrap("return \"\".count { it == 'a' }"), 0);
        }
    }

    // ========== padStart / padEnd ==========

    @Nested
    @DisplayName("padStart / padEnd")
    class PadStartEnd {

        @Test void padStart_normal() throws Exception {
            dual("\"42\".padStart(5, '0')", wrap("return \"42\".padStart(5, '0')"), "00042");
        }

        @Test void padStart_already_long() throws Exception {
            dual("\"hello\".padStart(3, '0')", wrap("return \"hello\".padStart(3, '0')"), "hello");
        }

        @Test void padStart_exact() throws Exception {
            dual("\"abc\".padStart(3, '0')", wrap("return \"abc\".padStart(3, '0')"), "abc");
        }

        @Test void padEnd_normal() throws Exception {
            dual("\"42\".padEnd(5, '0')", wrap("return \"42\".padEnd(5, '0')"), "42000");
        }

        @Test void padEnd_already_long() throws Exception {
            dual("\"hello\".padEnd(3, '0')", wrap("return \"hello\".padEnd(3, '0')"), "hello");
        }

        @Test void padEnd_exact() throws Exception {
            dual("\"abc\".padEnd(3, '0')", wrap("return \"abc\".padEnd(3, '0')"), "abc");
        }
    }
}
