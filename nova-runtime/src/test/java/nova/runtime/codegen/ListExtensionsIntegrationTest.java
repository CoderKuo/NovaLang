package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * List 扩展方法集成测试：解释器 + 编译器双路径。
 */
@DisplayName("List 扩展方法集成测试")
class ListExtensionsIntegrationTest {

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

    private void dualList(String interpCode, String compileBody, String expectedStr) throws Exception {
        NovaValue ir = interp(interpCode);
        Object cr = compile(compileBody);
        assertEquals(expectedStr, ir.asString(), "解释器");
        assertEquals(expectedStr, String.valueOf(cr), "编译器");
    }

    private void dualThrows(String interpCode, String compileBody) {
        assertThrows(Exception.class, () -> interp(interpCode), "解释器应抛异常");
        assertThrows(Exception.class, () -> compile(compileBody), "编译器应抛异常");
    }

    // ========== first / last ==========

    @Nested
    @DisplayName("first / last")
    class FirstLast {

        @Test void first_normal() throws Exception {
            dual("[1, 2, 3].first()", wrap("return [1, 2, 3].first()"), 1);
        }

        @Test void first_single() throws Exception {
            dual("[42].first()", wrap("return [42].first()"), 42);
        }

        @Test void first_empty_throws() {
            dualThrows("[].first()", wrap("return [].first()"));
        }

        @Test void last_normal() throws Exception {
            dual("[1, 2, 3].last()", wrap("return [1, 2, 3].last()"), 3);
        }

        @Test void last_single() throws Exception {
            dual("[99].last()", wrap("return [99].last()"), 99);
        }

        @Test void last_empty_throws() {
            dualThrows("[].last()", wrap("return [].last()"));
        }
    }

    // ========== firstOrNull / lastOrNull ==========

    @Nested
    @DisplayName("firstOrNull / lastOrNull")
    class FirstLastOrNull {

        @Test void firstOrNull_normal() throws Exception {
            dual("[1, 2].firstOrNull()", wrap("return [1, 2].firstOrNull()"), 1);
        }

        @Test void firstOrNull_empty() throws Exception {
            dual("[].firstOrNull()", wrap("return [].firstOrNull()"), null);
        }

        @Test void lastOrNull_normal() throws Exception {
            dual("[1, 2].lastOrNull()", wrap("return [1, 2].lastOrNull()"), 2);
        }

        @Test void lastOrNull_empty() throws Exception {
            dual("[].lastOrNull()", wrap("return [].lastOrNull()"), null);
        }
    }

    // ========== single / singleOrNull ==========

    @Nested
    @DisplayName("single / singleOrNull")
    class Single {

        @Test void single_one() throws Exception {
            dual("[7].single()", wrap("return [7].single()"), 7);
        }

        @Test void single_empty_throws() {
            dualThrows("[].single()", wrap("return [].single()"));
        }

        @Test void single_many_throws() {
            dualThrows("[1, 2].single()", wrap("return [1, 2].single()"));
        }

        @Test void singleOrNull_one() throws Exception {
            dual("[7].singleOrNull()", wrap("return [7].singleOrNull()"), 7);
        }

        @Test void singleOrNull_empty() throws Exception {
            dual("[].singleOrNull()", wrap("return [].singleOrNull()"), null);
        }

        @Test void singleOrNull_many() throws Exception {
            dual("[1, 2].singleOrNull()", wrap("return [1, 2].singleOrNull()"), null);
        }
    }

    // ========== sorted / sortedDescending ==========

    @Nested
    @DisplayName("sorted / sortedDescending")
    class Sorted {

        @Test void sorted_normal() throws Exception {
            dualList("[3, 1, 2].sorted()", wrap("return [3, 1, 2].sorted()"), "[1, 2, 3]");
        }

        @Test void sorted_empty() throws Exception {
            dualList("[].sorted()", wrap("return [].sorted()"), "[]");
        }

        @Test void sorted_single() throws Exception {
            dualList("[5].sorted()", wrap("return [5].sorted()"), "[5]");
        }

        @Test void sortedDescending_normal() throws Exception {
            dualList("[1, 3, 2].sortedDescending()", wrap("return [1, 3, 2].sortedDescending()"), "[3, 2, 1]");
        }

        @Test void sortedDescending_empty() throws Exception {
            dualList("[].sortedDescending()", wrap("return [].sortedDescending()"), "[]");
        }
    }

    // ========== reversed ==========

    @Nested
    @DisplayName("reversed")
    class Reversed {

        @Test void reversed_normal() throws Exception {
            dualList("[1, 2, 3].reversed()", wrap("return [1, 2, 3].reversed()"), "[3, 2, 1]");
        }

        @Test void reversed_empty() throws Exception {
            dualList("[].reversed()", wrap("return [].reversed()"), "[]");
        }

        @Test void reversed_single() throws Exception {
            dualList("[1].reversed()", wrap("return [1].reversed()"), "[1]");
        }
    }

    // ========== distinct ==========

    @Nested
    @DisplayName("distinct")
    class Distinct {

        @Test void distinct_normal() throws Exception {
            dualList("[1, 2, 2, 3, 1].distinct()", wrap("return [1, 2, 2, 3, 1].distinct()"), "[1, 2, 3]");
        }

        @Test void distinct_empty() throws Exception {
            dualList("[].distinct()", wrap("return [].distinct()"), "[]");
        }

        @Test void distinct_no_dups() throws Exception {
            dualList("[1, 2, 3].distinct()", wrap("return [1, 2, 3].distinct()"), "[1, 2, 3]");
        }
    }

    // ========== flatten ==========

    @Nested
    @DisplayName("flatten")
    class Flatten {

        @Test void flatten_normal() throws Exception {
            dualList("[[1, 2], [3, 4]].flatten()", wrap("return [[1, 2], [3, 4]].flatten()"), "[1, 2, 3, 4]");
        }

        @Test void flatten_empty() throws Exception {
            dualList("[].flatten()", wrap("return [].flatten()"), "[]");
        }

        @Test void flatten_mixed() throws Exception {
            dualList("[[1], 2, [3]].flatten()", wrap("return [[1], 2, [3]].flatten()"), "[1, 2, 3]");
        }
    }

    // ========== shuffled ==========

    @Nested
    @DisplayName("shuffled")
    class Shuffled {

        @Test void shuffled_preserves_elements() throws Exception {
            // 只验证长度相同，shuffled 是随机的
            dual("[1, 2, 3].shuffled().size()", wrap("return [1, 2, 3].shuffled().size()"), 3);
        }

        @Test void shuffled_empty() throws Exception {
            dualList("[].shuffled()", wrap("return [].shuffled()"), "[]");
        }
    }

    // ========== sum / average ==========

    @Nested
    @DisplayName("sum / average")
    class SumAverage {

        @Test void sum_ints() throws Exception {
            dual("[1, 2, 3].sum()", wrap("return [1, 2, 3].sum()"), 6);
        }

        @Test void sum_empty() throws Exception {
            dual("[].sum()", wrap("return [].sum()"), 0);
        }

        @Test void sum_doubles() throws Exception {
            dual("[1.5, 2.5].sum()", wrap("return [1.5, 2.5].sum()"), 4.0);
        }

        @Test void average_normal() throws Exception {
            dual("[2, 4, 6].average()", wrap("return [2, 4, 6].average()"), 4.0);
        }

        @Test void average_empty() throws Exception {
            dual("[].average()", wrap("return [].average()"), 0.0);
        }

        @Test void average_single() throws Exception {
            dual("[10].average()", wrap("return [10].average()"), 10.0);
        }
    }

    // ========== maxOrNull / minOrNull ==========

    @Nested
    @DisplayName("maxOrNull / minOrNull")
    class MaxMin {

        @Test void maxOrNull_normal() throws Exception {
            dual("[3, 1, 5, 2].maxOrNull()", wrap("return [3, 1, 5, 2].maxOrNull()"), 5);
        }

        @Test void maxOrNull_empty() throws Exception {
            dual("[].maxOrNull()", wrap("return [].maxOrNull()"), null);
        }

        @Test void maxOrNull_single() throws Exception {
            dual("[42].maxOrNull()", wrap("return [42].maxOrNull()"), 42);
        }

        @Test void minOrNull_normal() throws Exception {
            dual("[3, 1, 5, 2].minOrNull()", wrap("return [3, 1, 5, 2].minOrNull()"), 1);
        }

        @Test void minOrNull_empty() throws Exception {
            dual("[].minOrNull()", wrap("return [].minOrNull()"), null);
        }

        @Test void minOrNull_single() throws Exception {
            dual("[42].minOrNull()", wrap("return [42].minOrNull()"), 42);
        }
    }

    // ========== toSet ==========

    @Nested
    @DisplayName("toSet")
    class ToSet {

        @Test void toSet_removes_dups() throws Exception {
            dual("[1, 2, 2, 3].toSet().size()", wrap("return [1, 2, 2, 3].toSet().size()"), 3);
        }

        @Test void toSet_empty() throws Exception {
            dual("[].toSet().size()", wrap("return [].toSet().size()"), 0);
        }
    }

    // ========== isNotEmpty ==========

    @Nested
    @DisplayName("isNotEmpty")
    class IsNotEmpty {

        @Test void isNotEmpty_true() throws Exception {
            dual("[1].isNotEmpty()", wrap("return [1].isNotEmpty()"), true);
        }

        @Test void isNotEmpty_false() throws Exception {
            dual("[].isNotEmpty()", wrap("return [].isNotEmpty()"), false);
        }
    }

    // ========== joinToString ==========

    @Nested
    @DisplayName("joinToString")
    class JoinToString {

        @Test void joinToString_normal() throws Exception {
            dual("[1, 2, 3].joinToString(\", \")", wrap("return [1, 2, 3].joinToString(\", \")"), "1, 2, 3");
        }

        @Test void joinToString_custom_sep() throws Exception {
            dual("[\"a\", \"b\"].joinToString(\"-\")", wrap("return [\"a\", \"b\"].joinToString(\"-\")"), "a-b");
        }

        @Test void joinToString_empty() throws Exception {
            dual("[].joinToString(\", \")", wrap("return [].joinToString(\", \")"), "");
        }

        @Test void joinToString_single() throws Exception {
            dual("[42].joinToString(\", \")", wrap("return [42].joinToString(\", \")"), "42");
        }
    }

    // ========== take / drop / takeLast / dropLast ==========

    @Nested
    @DisplayName("take / drop / takeLast / dropLast")
    class TakeDrop {

        @Test void take_normal() throws Exception {
            dualList("[1, 2, 3, 4].take(2)", wrap("return [1, 2, 3, 4].take(2)"), "[1, 2]");
        }

        @Test void take_more_than_size() throws Exception {
            dualList("[1, 2].take(10)", wrap("return [1, 2].take(10)"), "[1, 2]");
        }

        @Test void take_zero() throws Exception {
            dualList("[1, 2, 3].take(0)", wrap("return [1, 2, 3].take(0)"), "[]");
        }

        @Test void drop_normal() throws Exception {
            dualList("[1, 2, 3, 4].drop(2)", wrap("return [1, 2, 3, 4].drop(2)"), "[3, 4]");
        }

        @Test void drop_more_than_size() throws Exception {
            dualList("[1, 2].drop(10)", wrap("return [1, 2].drop(10)"), "[]");
        }

        @Test void drop_zero() throws Exception {
            dualList("[1, 2, 3].drop(0)", wrap("return [1, 2, 3].drop(0)"), "[1, 2, 3]");
        }

        @Test void takeLast_normal() throws Exception {
            dualList("[1, 2, 3, 4].takeLast(2)", wrap("return [1, 2, 3, 4].takeLast(2)"), "[3, 4]");
        }

        @Test void takeLast_more_than_size() throws Exception {
            dualList("[1, 2].takeLast(10)", wrap("return [1, 2].takeLast(10)"), "[1, 2]");
        }

        @Test void dropLast_normal() throws Exception {
            dualList("[1, 2, 3, 4].dropLast(2)", wrap("return [1, 2, 3, 4].dropLast(2)"), "[1, 2]");
        }

        @Test void dropLast_more_than_size() throws Exception {
            dualList("[1, 2].dropLast(10)", wrap("return [1, 2].dropLast(10)"), "[]");
        }
    }

    // ========== any / all / none / count ==========

    @Nested
    @DisplayName("any / all / none / count")
    class Predicates {

        @Test void any_true() throws Exception {
            dual("[1, 2, 3].any { it > 2 }", wrap("return [1, 2, 3].any { it > 2 }"), true);
        }

        @Test void any_false() throws Exception {
            dual("[1, 2, 3].any { it > 5 }", wrap("return [1, 2, 3].any { it > 5 }"), false);
        }

        @Test void any_empty() throws Exception {
            dual("[].any { it > 0 }", wrap("return [].any { it > 0 }"), false);
        }

        @Test void all_true() throws Exception {
            dual("[2, 4, 6].all { it % 2 == 0 }", wrap("return [2, 4, 6].all { it % 2 == 0 }"), true);
        }

        @Test void all_false() throws Exception {
            dual("[1, 2, 3].all { it > 2 }", wrap("return [1, 2, 3].all { it > 2 }"), false);
        }

        @Test void all_empty() throws Exception {
            dual("[].all { it > 0 }", wrap("return [].all { it > 0 }"), true);
        }

        @Test void none_true() throws Exception {
            dual("[1, 2, 3].none { it > 5 }", wrap("return [1, 2, 3].none { it > 5 }"), true);
        }

        @Test void none_false() throws Exception {
            dual("[1, 2, 3].none { it > 2 }", wrap("return [1, 2, 3].none { it > 2 }"), false);
        }

        @Test void none_empty() throws Exception {
            dual("[].none { it > 0 }", wrap("return [].none { it > 0 }"), true);
        }

        @Test void count_normal() throws Exception {
            dual("[1, 2, 3, 4, 5].count { it > 3 }", wrap("return [1, 2, 3, 4, 5].count { it > 3 }"), 2);
        }

        @Test void count_none_match() throws Exception {
            dual("[1, 2, 3].count { it > 10 }", wrap("return [1, 2, 3].count { it > 10 }"), 0);
        }

        @Test void count_all_match() throws Exception {
            dual("[1, 2, 3].count { it > 0 }", wrap("return [1, 2, 3].count { it > 0 }"), 3);
        }

        @Test void count_empty() throws Exception {
            dual("[].count { it > 0 }", wrap("return [].count { it > 0 }"), 0);
        }
    }

    // ========== sortedBy ==========

    @Nested
    @DisplayName("sortedBy")
    class SortedBy {

        @Test void sortedBy_normal() throws Exception {
            dualList("[\"bb\", \"a\", \"ccc\"].sortedBy { it.length() }",
                    wrap("return [\"bb\", \"a\", \"ccc\"].sortedBy { it.length() }"),
                    "[a, bb, ccc]");
        }

        @Test void sortedBy_empty() throws Exception {
            dualList("[].sortedBy { it }", wrap("return [].sortedBy { it }"), "[]");
        }
    }

    // ========== flatMap ==========

    @Nested
    @DisplayName("flatMap")
    class FlatMap {

        @Test void flatMap_normal() throws Exception {
            dualList("[1, 2, 3].flatMap { [it, it * 10] }",
                    wrap("return [1, 2, 3].flatMap { [it, it * 10] }"),
                    "[1, 10, 2, 20, 3, 30]");
        }

        @Test void flatMap_empty() throws Exception {
            dualList("[].flatMap { [it] }", wrap("return [].flatMap { [it] }"), "[]");
        }
    }

    // ========== groupBy ==========

    @Nested
    @DisplayName("groupBy")
    class GroupBy {

        @Test void groupBy_normal() throws Exception {
            dual("[1, 2, 3, 4].groupBy { it % 2 }.size()",
                    wrap("return [1, 2, 3, 4].groupBy { it % 2 }.size()"), 2);
        }

        @Test void groupBy_empty() throws Exception {
            dual("[].groupBy { it }.size()", wrap("return [].groupBy { it }.size()"), 0);
        }
    }

    // ========== forEachIndexed ==========

    @Nested
    @DisplayName("forEachIndexed")
    class ForEachIndexed {

        @Test void forEachIndexed_normal() throws Exception {
            String interpCode = "var s = \"\"\n[\"a\", \"b\"].forEachIndexed { i, v -> s = s + i + v }\ns";
            String compileCode = wrap("var s = \"\"\n[\"a\", \"b\"].forEachIndexed { i, v -> s = s + i + v }\nreturn s");
            dual(interpCode, compileCode, "0a1b");
        }
    }

    // ========== mapIndexed ==========

    @Nested
    @DisplayName("mapIndexed")
    class MapIndexed {

        @Test void mapIndexed_normal() throws Exception {
            dualList("[10, 20, 30].mapIndexed { i, v -> i + v }",
                    wrap("return [10, 20, 30].mapIndexed { i, v -> i + v }"),
                    "[10, 21, 32]");
        }

        @Test void mapIndexed_empty() throws Exception {
            dualList("[].mapIndexed { i, v -> i }",
                    wrap("return [].mapIndexed { i, v -> i }"), "[]");
        }
    }
}
