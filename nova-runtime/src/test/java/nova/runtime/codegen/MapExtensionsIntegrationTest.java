package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Map 扩展方法集成测试：解释器 + 编译器双路径。
 */
@DisplayName("Map 扩展方法集成测试")
class MapExtensionsIntegrationTest {

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

    // ========== isNotEmpty ==========

    @Nested
    @DisplayName("isNotEmpty")
    class IsNotEmpty {

        @Test void isNotEmpty_true() throws Exception {
            dual("mapOf(\"a\" to 1).isNotEmpty()", wrap("return mapOf(\"a\" to 1).isNotEmpty()"), true);
        }

        @Test void isNotEmpty_false() throws Exception {
            dual("emptyMap().isNotEmpty()", wrap("return emptyMap().isNotEmpty()"), false);
        }
    }

    // ========== keys / values / entries / toList ==========

    @Nested
    @DisplayName("keys / values / entries / toList")
    class KeysValuesEntries {

        @Test void keys_normal() throws Exception {
            dualStr("mapOf(\"a\" to 1, \"b\" to 2).keys()",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).keys()"), "[a, b]");
        }

        @Test void keys_empty() throws Exception {
            dualStr("emptyMap().keys()", wrap("return emptyMap().keys()"), "[]");
        }

        @Test void values_normal() throws Exception {
            dualStr("mapOf(\"a\" to 1, \"b\" to 2).values()",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).values()"), "[1, 2]");
        }

        @Test void values_empty() throws Exception {
            dualStr("emptyMap().values()", wrap("return emptyMap().values()"), "[]");
        }

        @Test void entries_normal() throws Exception {
            dual("mapOf(\"a\" to 1).entries().size()",
                    wrap("return mapOf(\"a\" to 1).entries().size()"), 1);
        }

        @Test void toList_normal() throws Exception {
            dual("mapOf(\"a\" to 1).toList().size()",
                    wrap("return mapOf(\"a\" to 1).toList().size()"), 1);
        }
    }

    // ========== toMutableMap ==========

    @Nested
    @DisplayName("toMutableMap")
    class ToMutableMap {

        @Test void toMutableMap_is_copy() throws Exception {
            dual("val m = mapOf(\"a\" to 1)\nval m2 = m.toMutableMap()\nm2.size()",
                    wrap("val m = mapOf(\"a\" to 1)\nval m2 = m.toMutableMap()\nreturn m2.size()"), 1);
        }
    }

    // ========== merge ==========

    @Nested
    @DisplayName("merge")
    class Merge {

        @Test void merge_normal() throws Exception {
            dual("mapOf(\"a\" to 1).merge(mapOf(\"b\" to 2)).size()",
                    wrap("return mapOf(\"a\" to 1).merge(mapOf(\"b\" to 2)).size()"), 2);
        }

        @Test void merge_override() throws Exception {
            dual("mapOf(\"a\" to 1).merge(mapOf(\"a\" to 99))[\"a\"]",
                    wrap("return mapOf(\"a\" to 1).merge(mapOf(\"a\" to 99))[\"a\"]"), 99);
        }

        @Test void merge_empty() throws Exception {
            dual("mapOf(\"a\" to 1).merge(emptyMap()).size()",
                    wrap("return mapOf(\"a\" to 1).merge(emptyMap()).size()"), 1);
        }
    }

    // ========== mapKeys / mapValues ==========

    @Nested
    @DisplayName("mapKeys / mapValues")
    class MapKeysValues {

        @Test void mapKeys_normal() throws Exception {
            dual("mapOf(\"a\" to 1).mapKeys { it + \"!\" }[\"a!\"]",
                    wrap("return mapOf(\"a\" to 1).mapKeys { it + \"!\" }[\"a!\"]"), 1);
        }

        @Test void mapValues_normal() throws Exception {
            dual("mapOf(\"a\" to 1).mapValues { it * 10 }[\"a\"]",
                    wrap("return mapOf(\"a\" to 1).mapValues { it * 10 }[\"a\"]"), 10);
        }

        @Test void mapKeys_empty() throws Exception {
            dual("emptyMap().mapKeys { it }.size()",
                    wrap("return emptyMap().mapKeys { it }.size()"), 0);
        }

        @Test void mapValues_empty() throws Exception {
            dual("emptyMap().mapValues { it }.size()",
                    wrap("return emptyMap().mapValues { it }.size()"), 0);
        }
    }

    // ========== filterKeys / filterValues ==========

    @Nested
    @DisplayName("filterKeys / filterValues")
    class FilterKeysValues {

        @Test void filterKeys_normal() throws Exception {
            dual("mapOf(\"a\" to 1, \"bb\" to 2).filterKeys { it.length() == 1 }.size()",
                    wrap("return mapOf(\"a\" to 1, \"bb\" to 2).filterKeys { it.length() == 1 }.size()"), 1);
        }

        @Test void filterValues_normal() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 20).filterValues { it > 5 }.size()",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 20).filterValues { it > 5 }.size()"), 1);
        }

        @Test void filterKeys_none_match() throws Exception {
            dual("mapOf(\"a\" to 1).filterKeys { it == \"z\" }.size()",
                    wrap("return mapOf(\"a\" to 1).filterKeys { it == \"z\" }.size()"), 0);
        }

        @Test void filterValues_all_match() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 2).filterValues { it > 0 }.size()",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).filterValues { it > 0 }.size()"), 2);
        }
    }

    // ========== filter (Function2) ==========

    @Nested
    @DisplayName("filter")
    class Filter {

        @Test void filter_bifunction() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 20).filter { k, v -> v > 5 }.size()",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 20).filter { k, v -> v > 5 }.size()"), 1);
        }

        @Test void filter_empty() throws Exception {
            dual("emptyMap().filter { k, v -> true }.size()",
                    wrap("return emptyMap().filter { k, v -> true }.size()"), 0);
        }
    }

    // ========== forEach ==========

    @Nested
    @DisplayName("forEach")
    class ForEach {

        @Test void forEach_bifunction() throws Exception {
            String interpCode = "var s = \"\"\nmapOf(\"a\" to 1).forEach { k, v -> s = s + k + v }\ns";
            String compileCode = wrap("var s = \"\"\nmapOf(\"a\" to 1).forEach { k, v -> s = s + k + v }\nreturn s");
            dual(interpCode, compileCode, "a1");
        }
    }

    // ========== map ==========

    @Nested
    @DisplayName("map")
    class MapTransform {

        @Test void map_bifunction() throws Exception {
            dualStr("mapOf(\"a\" to 1, \"b\" to 2).map { k, v -> k + v }",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).map { k, v -> k + v }"), "[a1, b2]");
        }

        @Test void map_empty() throws Exception {
            dualStr("emptyMap().map { k, v -> k }",
                    wrap("return emptyMap().map { k, v -> k }"), "[]");
        }
    }

    // ========== flatMap ==========

    @Nested
    @DisplayName("flatMap")
    class FlatMap {

        @Test void flatMap_normal() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 2).flatMap { k, v -> [k, v] }.size()",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).flatMap { k, v -> [k, v] }.size()"), 4);
        }

        @Test void flatMap_empty() throws Exception {
            dualStr("emptyMap().flatMap { k, v -> [k] }",
                    wrap("return emptyMap().flatMap { k, v -> [k] }"), "[]");
        }
    }

    // ========== any / all / none / count ==========

    @Nested
    @DisplayName("any / all / none / count")
    class Predicates {

        @Test void any_true() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 20).any { k, v -> v > 10 }",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 20).any { k, v -> v > 10 }"), true);
        }

        @Test void any_false() throws Exception {
            dual("mapOf(\"a\" to 1).any { k, v -> v > 10 }",
                    wrap("return mapOf(\"a\" to 1).any { k, v -> v > 10 }"), false);
        }

        @Test void any_empty() throws Exception {
            dual("emptyMap().any { k, v -> true }",
                    wrap("return emptyMap().any { k, v -> true }"), false);
        }

        @Test void all_true() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 2).all { k, v -> v > 0 }",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).all { k, v -> v > 0 }"), true);
        }

        @Test void all_false() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 2).all { k, v -> v > 1 }",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).all { k, v -> v > 1 }"), false);
        }

        @Test void all_empty() throws Exception {
            dual("emptyMap().all { k, v -> false }",
                    wrap("return emptyMap().all { k, v -> false }"), true);
        }

        @Test void none_true() throws Exception {
            dual("mapOf(\"a\" to 1).none { k, v -> v > 10 }",
                    wrap("return mapOf(\"a\" to 1).none { k, v -> v > 10 }"), true);
        }

        @Test void none_false() throws Exception {
            dual("mapOf(\"a\" to 1).none { k, v -> v == 1 }",
                    wrap("return mapOf(\"a\" to 1).none { k, v -> v == 1 }"), false);
        }

        @Test void none_empty() throws Exception {
            dual("emptyMap().none { k, v -> true }",
                    wrap("return emptyMap().none { k, v -> true }"), true);
        }

        @Test void count_normal() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 20, \"c\" to 3).count { k, v -> v > 5 }",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 20, \"c\" to 3).count { k, v -> v > 5 }"), 1);
        }

        @Test void count_empty() throws Exception {
            dual("emptyMap().count { k, v -> true }",
                    wrap("return emptyMap().count { k, v -> true }"), 0);
        }

        @Test void count_all_match() throws Exception {
            dual("mapOf(\"a\" to 1, \"b\" to 2).count { k, v -> v > 0 }",
                    wrap("return mapOf(\"a\" to 1, \"b\" to 2).count { k, v -> v > 0 }"), 2);
        }
    }

    // ========== getOrPut ==========

    @Nested
    @DisplayName("getOrPut")
    class GetOrPut {

        @Test void getOrPut_existing() throws Exception {
            dual("val m = mutableMapOf(\"a\" to 1)\nm.getOrPut(\"a\") { 99 }",
                    wrap("val m = mutableMapOf(\"a\" to 1)\nreturn m.getOrPut(\"a\") { 99 }"), 1);
        }

        @Test void getOrPut_missing() throws Exception {
            dual("val m = mutableMapOf(\"a\" to 1)\nm.getOrPut(\"b\") { 42 }",
                    wrap("val m = mutableMapOf(\"a\" to 1)\nreturn m.getOrPut(\"b\") { 42 }"), 42);
        }

        @Test void getOrPut_inserts() throws Exception {
            dual("val m = mutableMapOf(\"a\" to 1)\nm.getOrPut(\"b\") { 42 }\nm.size()",
                    wrap("val m = mutableMapOf(\"a\" to 1)\nm.getOrPut(\"b\") { 42 }\nreturn m.size()"), 2);
        }
    }
}
