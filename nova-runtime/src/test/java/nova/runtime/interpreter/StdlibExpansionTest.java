package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 标准库扩展单元测试 — 覆盖 P0 核心成员方法 + P1 扩展模块
 */
@DisplayName("标准库扩展")
class StdlibExpansionTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private String captureOutput(String code) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, "UTF-8");
        PrintStream old = interpreter.getStdout();
        interpreter.setStdout(ps);
        try {
            interpreter.evalRepl(code);
            return baos.toString("UTF-8").trim().replace("\r\n", "\n");
        } finally {
            interpreter.setStdout(old);
        }
    }

    // ================================================================
    // P0-1: List 新增方法
    // ================================================================

    @Nested
    @DisplayName("List 新增方法")
    class ListMethodsTest {

        @Test
        @DisplayName("firstOrNull / lastOrNull")
        void testFirstOrNullLastOrNull() {
            interpreter.evalRepl("val list = [1, 2, 3]");
            assertEquals(1, interpreter.evalRepl("list.firstOrNull()").asInt());
            assertEquals(3, interpreter.evalRepl("list.lastOrNull()").asInt());

            interpreter.evalRepl("val empty = []");
            assertTrue(interpreter.evalRepl("empty.firstOrNull()").isNull());
            assertTrue(interpreter.evalRepl("empty.lastOrNull()").isNull());
        }

        @Test
        @DisplayName("findLast")
        void testFindLast() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            assertEquals(4, interpreter.evalRepl("list.findLast { it % 2 == 0 }").asInt());
            assertTrue(interpreter.evalRepl("list.findLast { it > 10 }").isNull());
        }

        @Test
        @DisplayName("single / singleOrNull")
        void testSingleSingleOrNull() {
            interpreter.evalRepl("val one = [42]");
            assertEquals(42, interpreter.evalRepl("one.single()").asInt());
            assertEquals(42, interpreter.evalRepl("one.singleOrNull()").asInt());

            interpreter.evalRepl("val many = [1, 2]");
            assertTrue(interpreter.evalRepl("many.singleOrNull()").isNull());
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("many.single()"));
        }

        @Test
        @DisplayName("sortedBy / sortedDescending")
        void testSortedBySortedDescending() {
            interpreter.evalRepl("val list = [3, 1, 2]");
            NovaValue sorted = interpreter.evalRepl("list.sortedBy { it }");
            assertTrue(sorted instanceof NovaList);
            assertEquals(1, ((NovaList) sorted).get(0).asInt());
            assertEquals(3, ((NovaList) sorted).get(2).asInt());

            NovaValue desc = interpreter.evalRepl("list.sortedDescending()");
            assertEquals(3, ((NovaList) desc).get(0).asInt());
            assertEquals(1, ((NovaList) desc).get(2).asInt());
        }

        @Test
        @DisplayName("groupBy")
        void testGroupBy() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            interpreter.evalRepl("val grouped = list.groupBy { it % 2 }");
            NovaValue size = interpreter.evalRepl("grouped.size()");
            assertEquals(2, size.asInt());
        }

        @Test
        @DisplayName("associateBy / associateWith")
        void testAssociateByWith() {
            interpreter.evalRepl("val list = [\"a\", \"bb\", \"ccc\"]");
            interpreter.evalRepl("val byLen = list.associateBy { it.length() }");
            assertEquals("a", interpreter.evalRepl("byLen[1]").asString());

            interpreter.evalRepl("val withLen = list.associateWith { it.length() }");
            assertEquals(3, interpreter.evalRepl("withLen[\"ccc\"]").asInt());
        }

        @Test
        @DisplayName("partition")
        void testPartition() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            interpreter.evalRepl("val p = list.partition { it % 2 == 0 }");
            assertEquals(2, interpreter.evalRepl("p.first.size()").asInt());
            assertEquals(3, interpreter.evalRepl("p.second.size()").asInt());
        }

        @Test
        @DisplayName("chunked / windowed")
        void testChunkedWindowed() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");

            NovaValue chunked = interpreter.evalRepl("list.chunked(2)");
            assertTrue(chunked instanceof NovaList);
            assertEquals(3, ((NovaList) chunked).size());

            NovaValue windowed = interpreter.evalRepl("list.windowed(3)");
            assertTrue(windowed instanceof NovaList);
            assertEquals(3, ((NovaList) windowed).size());
        }

        @Test
        @DisplayName("takeLast / dropLast")
        void testTakeLastDropLast() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            NovaValue last2 = interpreter.evalRepl("list.takeLast(2)");
            assertEquals(2, ((NovaList) last2).size());
            assertEquals(4, ((NovaList) last2).get(0).asInt());

            NovaValue drop2 = interpreter.evalRepl("list.dropLast(2)");
            assertEquals(3, ((NovaList) drop2).size());
        }

        @Test
        @DisplayName("takeWhile / dropWhile")
        void testTakeWhileDropWhile() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            NovaValue taken = interpreter.evalRepl("list.takeWhile { it < 4 }");
            assertEquals(3, ((NovaList) taken).size());

            NovaValue dropped = interpreter.evalRepl("list.dropWhile { it < 4 }");
            assertEquals(2, ((NovaList) dropped).size());
            assertEquals(4, ((NovaList) dropped).get(0).asInt());
        }

        @Test
        @DisplayName("filterNot / filterIndexed / mapIndexed / forEachIndexed")
        void testIndexedMethods() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");

            NovaValue filterNot = interpreter.evalRepl("list.filterNot { it % 2 == 0 }");
            assertEquals(3, ((NovaList) filterNot).size());

            NovaValue filterIdx = interpreter.evalRepl("list.filterIndexed { i, v -> i < 3 }");
            assertEquals(3, ((NovaList) filterIdx).size());

            NovaValue mapIdx = interpreter.evalRepl("list.mapIndexed { i, v -> i * 10 + v }");
            assertEquals(1, ((NovaList) mapIdx).get(0).asInt());   // 0*10+1
            assertEquals(12, ((NovaList) mapIdx).get(1).asInt());  // 1*10+2

            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("list.forEachIndexed { i, v -> sum = sum + i }");
            assertEquals(10, interpreter.evalRepl("sum").asInt()); // 0+1+2+3+4
        }

        @Test
        @DisplayName("fold / foldRight")
        void testFoldFoldRight() {
            interpreter.evalRepl("val list = [1, 2, 3]");
            assertEquals(106, interpreter.evalRepl("list.fold(100) { acc, v -> acc + v }").asInt());

            assertEquals("321", interpreter.evalRepl("list.foldRight(\"\") { v, acc -> acc + v.toString() }").asString());
        }

        @Test
        @DisplayName("flatten")
        void testFlatten() {
            interpreter.evalRepl("val nested = [[1, 2], [3, 4], [5]]");
            NovaValue flat = interpreter.evalRepl("nested.flatten()");
            assertEquals(5, ((NovaList) flat).size());
            assertEquals(1, ((NovaList) flat).get(0).asInt());
        }

        @Test
        @DisplayName("average / maxOrNull / minOrNull")
        void testAggregation() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            assertEquals(3.0, interpreter.evalRepl("list.average()").asDouble(), 0.001);
            assertEquals(5, interpreter.evalRepl("list.maxOrNull()").asInt());
            assertEquals(1, interpreter.evalRepl("list.minOrNull()").asInt());

            assertTrue(interpreter.evalRepl("[].maxOrNull()").isNull());
            assertTrue(interpreter.evalRepl("[].minOrNull()").isNull());
        }

        @Test
        @DisplayName("maxBy / minBy / sumBy")
        void testMaxByMinBySumBy() {
            interpreter.evalRepl("val list = [\"a\", \"bbb\", \"cc\"]");
            assertEquals("bbb", interpreter.evalRepl("list.maxBy { it.length() }").asString());
            assertEquals("a", interpreter.evalRepl("list.minBy { it.length() }").asString());

            interpreter.evalRepl("val nums = [1, 2, 3]");
            assertEquals(12, interpreter.evalRepl("nums.sumBy { it * 2 }").asInt());
        }

        @Test
        @DisplayName("toSet / toMutableList / toMap")
        void testConversions() {
            interpreter.evalRepl("val list = [1, 2, 2, 3]");
            // toSet — 去重
            interpreter.evalRepl("val s = list.toSet()");
            assertEquals(3, interpreter.evalRepl("s.size()").asInt());

            // toMutableList — 副本
            interpreter.evalRepl("val copy = list.toMutableList()");
            assertEquals(4, interpreter.evalRepl("copy.size()").asInt());

            // toMap — Pair 列表转 Map
            interpreter.evalRepl("val pairs = [Pair(\"a\", 1), Pair(\"b\", 2)]");
            interpreter.evalRepl("val m = pairs.toMap()");
            assertEquals(1, interpreter.evalRepl("m[\"a\"]").asInt());
        }

        @Test
        @DisplayName("withIndex")
        void testWithIndex() {
            interpreter.evalRepl("val list = [\"a\", \"b\", \"c\"]");
            NovaValue indexed = interpreter.evalRepl("list.withIndex()");
            assertTrue(indexed instanceof NovaList);
            // 每个元素是 Pair(index, value)
            assertEquals(0, interpreter.evalRepl("list.withIndex()[0].first").asInt());
            assertEquals("a", interpreter.evalRepl("list.withIndex()[0].second").asString());
        }

        @Test
        @DisplayName("unzip")
        void testUnzip() {
            interpreter.evalRepl("val pairs = [Pair(1, \"a\"), Pair(2, \"b\")]");
            interpreter.evalRepl("val result = pairs.unzip()");
            assertEquals(1, interpreter.evalRepl("result.first[0]").asInt());
            assertEquals("b", interpreter.evalRepl("result.second[1]").asString());
        }

        @Test
        @DisplayName("intersect / subtract / union")
        void testSetOperations() {
            interpreter.evalRepl("val a = [1, 2, 3, 4]");
            interpreter.evalRepl("val b = [3, 4, 5, 6]");

            NovaValue inter = interpreter.evalRepl("a.intersect(b)");
            assertEquals(2, ((NovaList) inter).size());

            NovaValue sub = interpreter.evalRepl("a.subtract(b)");
            assertEquals(2, ((NovaList) sub).size());

            NovaValue union = interpreter.evalRepl("a.union(b)");
            assertEquals(6, ((NovaList) union).size());
        }

        @Test
        @DisplayName("shuffled / slice")
        void testShuffledSlice() {
            interpreter.evalRepl("val list = [1, 2, 3, 4, 5]");
            NovaValue shuffled = interpreter.evalRepl("list.shuffled()");
            assertEquals(5, ((NovaList) shuffled).size());

            NovaValue sliced = interpreter.evalRepl("list.slice(1..3)");
            assertEquals(3, ((NovaList) sliced).size());
            assertEquals(2, ((NovaList) sliced).get(0).asInt());
        }
    }

    // ================================================================
    // P0-2: Map 新增方法
    // ================================================================

    @Nested
    @DisplayName("Map 新增方法")
    class MapMethodsTest {

        @Test
        @DisplayName("getOrPut")
        void testGetOrPut() {
            interpreter.evalRepl("val m = mutableMapOf(\"a\" to 1)");
            assertEquals(1, interpreter.evalRepl("m.getOrPut(\"a\") { 99 }").asInt());
            assertEquals(99, interpreter.evalRepl("m.getOrPut(\"b\") { 99 }").asInt());
            assertEquals(99, interpreter.evalRepl("m[\"b\"]").asInt());
        }

        @Test
        @DisplayName("mapKeys / mapValues")
        void testMapKeysMapValues() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            interpreter.evalRepl("val mk = m.mapKeys { e -> e.key + \"!\" }");
            assertEquals(1, interpreter.evalRepl("mk[\"a!\"]").asInt());

            interpreter.evalRepl("val mv = m.mapValues { e -> e.value * 10 }");
            assertEquals(10, interpreter.evalRepl("mv[\"a\"]").asInt());
        }

        @Test
        @DisplayName("filterKeys / filterValues / filter")
        void testMapFilters() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to 2, \"c\" to 3)");
            interpreter.evalRepl("val fk = m.filterKeys { it != \"b\" }");
            assertEquals(2, interpreter.evalRepl("fk.size()").asInt());

            interpreter.evalRepl("val fv = m.filterValues { it > 1 }");
            assertEquals(2, interpreter.evalRepl("fv.size()").asInt());

            interpreter.evalRepl("val f = m.filter { e -> e.value > 1 }");
            assertEquals(2, interpreter.evalRepl("f.size()").asInt());
        }

        @Test
        @DisplayName("forEach / map / flatMap")
        void testMapIteration() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl("m.forEach { k, v -> count = count + v }");
            assertEquals(3, interpreter.evalRepl("count").asInt());

            NovaValue mapped = interpreter.evalRepl("m.map { e -> e[\"key\"].toString() + \"=\" + e[\"value\"] }");
            assertEquals(2, ((NovaList) mapped).size());
        }

        @Test
        @DisplayName("any / all / none")
        void testMapPredicates() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to 2, \"c\" to 3)");
            assertTrue(interpreter.evalRepl("m.any { e -> e.value > 2 }").asBool());
            assertFalse(interpreter.evalRepl("m.all { e -> e.value > 2 }").asBool());
            assertFalse(interpreter.evalRepl("m.none { e -> e.value > 2 }").asBool());
            assertTrue(interpreter.evalRepl("m.none { e -> e.value > 10 }").asBool());
        }

        @Test
        @DisplayName("count / toList / toMutableMap / merge")
        void testMapMisc() {
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertEquals(2, interpreter.evalRepl("m.count()").asInt());
            assertEquals(1, interpreter.evalRepl("m.count { e -> e.value > 1 }").asInt());

            NovaValue list = interpreter.evalRepl("m.toList()");
            assertEquals(2, ((NovaList) list).size());

            interpreter.evalRepl("val m2 = m.toMutableMap()");
            assertEquals(2, interpreter.evalRepl("m2.size()").asInt());

            interpreter.evalRepl("val merged = m.merge(mapOf(\"b\" to 20, \"c\" to 3))");
            assertEquals(20, interpreter.evalRepl("merged[\"b\"]").asInt());
            assertEquals(3, interpreter.evalRepl("merged[\"c\"]").asInt());
        }
    }

    // ================================================================
    // P0-3: String 新增方法
    // ================================================================

    @Nested
    @DisplayName("String 新增方法")
    class StringMethodsTest {

        @Test
        @DisplayName("drop / takeLast / dropLast")
        void testStringSlice() {
            interpreter.evalRepl("val s = \"hello\"");
            assertEquals("llo", interpreter.evalRepl("s.drop(2)").asString());
            assertEquals("llo", interpreter.evalRepl("s.takeLast(3)").asString());
            assertEquals("he", interpreter.evalRepl("s.dropLast(3)").asString());
        }

        @Test
        @DisplayName("removePrefix / removeSuffix")
        void testRemovePrefixSuffix() {
            assertEquals("World", interpreter.evalRepl("\"HelloWorld\".removePrefix(\"Hello\")").asString());
            assertEquals("Hello", interpreter.evalRepl("\"HelloWorld\".removeSuffix(\"World\")").asString());
            assertEquals("Hello", interpreter.evalRepl("\"Hello\".removePrefix(\"xyz\")").asString());
        }

        @Test
        @DisplayName("replaceFirst / lastIndexOf")
        void testReplaceFirstLastIndexOf() {
            assertEquals("xbc", interpreter.evalRepl("\"abc\".replaceFirst(\"a\", \"x\")").asString());
            assertEquals(4, interpreter.evalRepl("\"abcba\".lastIndexOf(\"a\")").asInt());
        }

        @Test
        @DisplayName("toIntOrNull / toDoubleOrNull / toLongOrNull")
        void testSafeConversions() {
            assertEquals(42, interpreter.evalRepl("\"42\".toIntOrNull()").asInt());
            assertTrue(interpreter.evalRepl("\"abc\".toIntOrNull()").isNull());
            assertEquals(3.14, interpreter.evalRepl("\"3.14\".toDoubleOrNull()").asDouble(), 0.001);
            assertTrue(interpreter.evalRepl("\"xyz\".toDoubleOrNull()").isNull());
        }

        @Test
        @DisplayName("toInt / toDouble / toLong / toBoolean")
        void testConversions() {
            assertEquals(42, interpreter.evalRepl("\"42\".toInt()").asInt());
            assertEquals(3.14, interpreter.evalRepl("\"3.14\".toDouble()").asDouble(), 0.001);
            assertTrue(interpreter.evalRepl("\"true\".toBoolean()").asBool());
            assertFalse(interpreter.evalRepl("\"false\".toBoolean()").asBool());
        }

        @Test
        @DisplayName("lines / capitalize / decapitalize")
        void testLinesCapitalize() {
            NovaValue lines = interpreter.evalRepl("\"a\\nb\\nc\".lines()");
            assertEquals(3, ((NovaList) lines).size());

            assertEquals("Hello", interpreter.evalRepl("\"hello\".capitalize()").asString());
            assertEquals("hello", interpreter.evalRepl("\"Hello\".decapitalize()").asString());
        }

        @Test
        @DisplayName("chars / count")
        void testCharsCount() {
            NovaValue chars = interpreter.evalRepl("\"abc\".chars()");
            assertTrue(chars instanceof NovaList);
            assertEquals(3, ((NovaList) chars).size());

            assertEquals(5, interpreter.evalRepl("\"hello\".count()").asInt());
            assertEquals(2, interpreter.evalRepl("\"hello\".count { it == 'l' }").asInt());
        }

        @Test
        @DisplayName("matches")
        void testMatches() {
            assertTrue(interpreter.evalRepl("\"abc123\".matches(\"[a-z]+[0-9]+\")").asBool());
            assertFalse(interpreter.evalRepl("\"abc\".matches(\"[0-9]+\")").asBool());
        }

        @Test
        @DisplayName("format")
        void testFormat() {
            assertEquals("Hello World 42", interpreter.evalRepl("\"Hello %s %d\".format(\"World\", 42)").asString());
        }
    }

    // ================================================================
    // P0-4: Number 成员方法
    // ================================================================

    @Nested
    @DisplayName("Number 成员方法")
    class NumberMethodsTest {

        @Test
        @DisplayName("Int 转换: toDouble / toLong / toFloat / toString")
        void testIntConversions() {
            assertEquals(42.0, interpreter.evalRepl("42.toDouble()").asDouble(), 0.001);
            assertEquals(42L, interpreter.evalRepl("42.toLong()").asLong());
            assertEquals("42", interpreter.evalRepl("42.toString()").asString());
        }

        @Test
        @DisplayName("Int: abs / coerceIn / coerceAtLeast / coerceAtMost")
        void testIntMath() {
            assertEquals(5, interpreter.evalRepl("(-5).abs()").asInt());
            assertEquals(5, interpreter.evalRepl("3.coerceAtLeast(5)").asInt());
            assertEquals(3, interpreter.evalRepl("5.coerceAtMost(3)").asInt());
            assertEquals(5, interpreter.evalRepl("5.coerceIn(1, 10)").asInt());
            assertEquals(1, interpreter.evalRepl("0.coerceIn(1, 10)").asInt());
            assertEquals(10, interpreter.evalRepl("20.coerceIn(1, 10)").asInt());
        }

        @Test
        @DisplayName("Int: downTo / until")
        void testIntRanges() {
            NovaValue downTo = interpreter.evalRepl("5.downTo(1)");
            assertTrue(downTo instanceof NovaList);
            assertEquals(5, ((NovaList) downTo).size());
            assertEquals(5, ((NovaList) downTo).get(0).asInt());
            assertEquals(1, ((NovaList) downTo).get(4).asInt());

            NovaValue until = interpreter.evalRepl("1.until(5)");
            // until 返回 NovaRange，半开区间 [1, 5)
            assertTrue(until instanceof NovaRange);
            assertEquals(4, interpreter.evalRepl("1.until(5).size()").asInt());
        }

        @Test
        @DisplayName("Double: toInt / roundToInt / isNaN / isInfinite / isFinite")
        void testDoubleMethods() {
            assertEquals(3, interpreter.evalRepl("3.7.toInt()").asInt());
            assertEquals(4, interpreter.evalRepl("3.7.roundToInt()").asInt());
            assertEquals(3, interpreter.evalRepl("3.2.roundToInt()").asInt());
            assertFalse(interpreter.evalRepl("3.14.isNaN()").asBool());
            assertFalse(interpreter.evalRepl("3.14.isInfinite()").asBool());
            assertTrue(interpreter.evalRepl("3.14.isFinite()").asBool());
        }

        @Test
        @DisplayName("Double: abs")
        void testDoubleAbs() {
            assertEquals(5.5, interpreter.evalRepl("(-5.5).abs()").asDouble(), 0.001);
        }
    }

    // ================================================================
    // P0-5: Char 成员方法
    // ================================================================

    @Nested
    @DisplayName("Char 成员方法")
    class CharMethodsTest {

        @Test
        @DisplayName("isDigit / isLetter / isWhitespace")
        void testCharPredicates() {
            assertTrue(interpreter.evalRepl("'5'.isDigit()").asBool());
            assertFalse(interpreter.evalRepl("'a'.isDigit()").asBool());
            assertTrue(interpreter.evalRepl("'a'.isLetter()").asBool());
            assertFalse(interpreter.evalRepl("'5'.isLetter()").asBool());
            assertTrue(interpreter.evalRepl("' '.isWhitespace()").asBool());
        }

        @Test
        @DisplayName("isUpperCase / isLowerCase / uppercase / lowercase")
        void testCharCase() {
            assertTrue(interpreter.evalRepl("'A'.isUpperCase()").asBool());
            assertFalse(interpreter.evalRepl("'a'.isUpperCase()").asBool());
            assertTrue(interpreter.evalRepl("'a'.isLowerCase()").asBool());
            assertEquals("A", interpreter.evalRepl("'a'.uppercase()").asString());
            assertEquals("a", interpreter.evalRepl("'A'.lowercase()").asString());
        }

        @Test
        @DisplayName("code / digitToInt / toString")
        void testCharMisc() {
            assertEquals(65, interpreter.evalRepl("'A'.code").asInt());
            assertEquals(5, interpreter.evalRepl("'5'.digitToInt()").asInt());
            assertEquals("A", interpreter.evalRepl("'A'.toString()").asString());
        }
    }

    // ================================================================
    // P0-6: Set 成员方法
    // ================================================================

    @Nested
    @DisplayName("Set 成员方法")
    class SetMethodsTest {

        @Test
        @DisplayName("基本操作: size / isEmpty / isNotEmpty / contains")
        void testBasicOps() {
            interpreter.evalRepl("val s = setOf(1, 2, 3)");
            assertEquals(3, interpreter.evalRepl("s.size()").asInt());
            assertFalse(interpreter.evalRepl("s.isEmpty()").asBool());
            assertTrue(interpreter.evalRepl("s.isNotEmpty()").asBool());
            assertTrue(interpreter.evalRepl("s.contains(2)").asBool());
            assertFalse(interpreter.evalRepl("s.contains(99)").asBool());
        }

        @Test
        @DisplayName("toList / forEach / map / filter")
        void testSetIteration() {
            interpreter.evalRepl("val s = setOf(1, 2, 3)");
            assertEquals(3, interpreter.evalRepl("s.toList().size()").asInt());

            interpreter.evalRepl("var sum = 0");
            interpreter.evalRepl("s.forEach { v -> sum = sum + v }");
            assertEquals(6, interpreter.evalRepl("sum").asInt());

            // map 返回 NovaList
            assertEquals(3, interpreter.evalRepl("s.map { it * 2 }.size()").asInt());

            // filter 返回 Set（NovaExternalObject 包装），用 size() 检查
            assertEquals(2, interpreter.evalRepl("s.filter { it > 1 }.size()").asInt());
        }

        @Test
        @DisplayName("union / intersect / subtract")
        void testSetOps() {
            interpreter.evalRepl("val a = setOf(1, 2, 3)");
            interpreter.evalRepl("val b = setOf(2, 3, 4)");
            assertEquals(4, interpreter.evalRepl("a.union(b).size()").asInt());
            assertEquals(2, interpreter.evalRepl("a.intersect(b).size()").asInt());
            assertEquals(1, interpreter.evalRepl("a.subtract(b).size()").asInt());
        }
    }

    // ================================================================
    // P0-7: 顶层函数
    // ================================================================

    @Nested
    @DisplayName("顶层函数")
    class TopLevelFunctionsTest {

        @Test
        @DisplayName("toChar / toFloat")
        void testToCharToFloat() {
            NovaValue ch = interpreter.evalRepl("toChar(65)");
            assertEquals("A", ch.asString());

            NovaValue f = interpreter.evalRepl("toFloat(3.14)");
            assertTrue(f instanceof NovaFloat);
        }

        @Test
        @DisplayName("emptyList / emptyMap / emptySet")
        void testEmptyCollections() {
            assertEquals(0, interpreter.evalRepl("emptyList().size()").asInt());
            assertEquals(0, interpreter.evalRepl("emptyMap().size()").asInt());
            assertEquals(0, interpreter.evalRepl("emptySet().size()").asInt());
        }

        @Test
        @DisplayName("pairOf")
        void testPairOf() {
            interpreter.evalRepl("val p = pairOf(1, \"a\")");
            assertEquals(1, interpreter.evalRepl("p.first").asInt());
            assertEquals("a", interpreter.evalRepl("p.second").asString());
        }

        @Test
        @DisplayName("List(size, init)")
        void testListInit() {
            NovaValue list = interpreter.evalRepl("List(5) { it * 2 }");
            assertTrue(list instanceof NovaList);
            assertEquals(5, ((NovaList) list).size());
            assertEquals(0, ((NovaList) list).get(0).asInt());
            assertEquals(8, ((NovaList) list).get(4).asInt());
        }

        @Test
        @DisplayName("with")
        void testWith() {
            // with(receiver, block) — block 接收 receiver 作为参数
            NovaValue result = interpreter.evalRepl("with(10) { it + 5 }");
            assertEquals(15, result.asInt());
        }

        @Test
        @DisplayName("repeat")
        void testRepeat() {
            interpreter.evalRepl("var count = 0");
            interpreter.evalRepl("repeat(5) { count = count + 1 }");
            assertEquals(5, interpreter.evalRepl("count").asInt());
        }

        @Test
        @DisplayName("measureTimeMillis")
        void testMeasureTime() {
            NovaValue time = interpreter.evalRepl("measureTimeMillis { 1 + 1 }");
            assertTrue(time.asLong() >= 0);
        }

        @Test
        @DisplayName("check / checkNotNull / requireNotNull")
        void testPreconditions() {
            // check(condition, message) — arity=2
            interpreter.evalRepl("check(true, \"should pass\")");

            // check(false, message) 抛异常
            assertThrows(Exception.class, () -> interpreter.evalRepl("check(false, \"failed\")"));

            // checkNotNull(value, message)
            assertEquals(42, interpreter.evalRepl("checkNotNull(42, \"not null\")").asInt());
            assertThrows(Exception.class, () -> interpreter.evalRepl("checkNotNull(null, \"was null\")"));

            // requireNotNull(value, message)
            assertEquals("ok", interpreter.evalRepl("requireNotNull(\"ok\", \"msg\")").asString());
            assertThrows(Exception.class, () -> interpreter.evalRepl("requireNotNull(null, \"required\")"));
        }
    }

    // ================================================================
    // P1-1: nova.io
    // ================================================================

    @Nested
    @DisplayName("nova.io 模块")
    class IoModuleTest {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("readFile / writeFile / fileExists")
        void testReadWriteFile() {
            interpreter.evalRepl("import nova.io.*");
            String path = tempDir.resolve("test.txt").toString().replace("\\", "\\\\");
            interpreter.evalRepl("writeFile(\"" + path + "\", \"hello world\")");
            assertTrue(interpreter.evalRepl("fileExists(\"" + path + "\")").asBool());
            assertEquals("hello world", interpreter.evalRepl("readFile(\"" + path + "\")").asString());
        }

        @Test
        @DisplayName("appendFile")
        void testAppendFile() {
            interpreter.evalRepl("import nova.io.*");
            String path = tempDir.resolve("append.txt").toString().replace("\\", "\\\\");
            interpreter.evalRepl("writeFile(\"" + path + "\", \"hello\")");
            interpreter.evalRepl("appendFile(\"" + path + "\", \" world\")");
            assertEquals("hello world", interpreter.evalRepl("readFile(\"" + path + "\")").asString());
        }

        @Test
        @DisplayName("readLines / writeLines")
        void testReadWriteLines() {
            interpreter.evalRepl("import nova.io.*");
            String path = tempDir.resolve("lines.txt").toString().replace("\\", "\\\\");
            interpreter.evalRepl("writeLines(\"" + path + "\", [\"a\", \"b\", \"c\"])");
            NovaValue lines = interpreter.evalRepl("readLines(\"" + path + "\")");
            assertEquals(3, ((NovaList) lines).size());
        }

        @Test
        @DisplayName("mkdir / isDir / isFile / listDir")
        void testDirOps() {
            interpreter.evalRepl("import nova.io.*");
            String dir = tempDir.resolve("subdir").toString().replace("\\", "\\\\");
            interpreter.evalRepl("mkdir(\"" + dir + "\")");
            assertTrue(interpreter.evalRepl("isDir(\"" + dir + "\")").asBool());

            String file = tempDir.resolve("subdir/test.txt").toString().replace("\\", "\\\\");
            interpreter.evalRepl("writeFile(\"" + file + "\", \"data\")");
            assertTrue(interpreter.evalRepl("isFile(\"" + file + "\")").asBool());

            NovaValue items = interpreter.evalRepl("listDir(\"" + dir + "\")");
            assertEquals(1, ((NovaList) items).size());
        }

        @Test
        @DisplayName("deleteFile / copyFile / moveFile")
        void testFileManipulation() {
            interpreter.evalRepl("import nova.io.*");
            String f1 = tempDir.resolve("f1.txt").toString().replace("\\", "\\\\");
            String f2 = tempDir.resolve("f2.txt").toString().replace("\\", "\\\\");
            String f3 = tempDir.resolve("f3.txt").toString().replace("\\", "\\\\");

            interpreter.evalRepl("writeFile(\"" + f1 + "\", \"data\")");
            interpreter.evalRepl("copyFile(\"" + f1 + "\", \"" + f2 + "\")");
            assertEquals("data", interpreter.evalRepl("readFile(\"" + f2 + "\")").asString());

            interpreter.evalRepl("moveFile(\"" + f2 + "\", \"" + f3 + "\")");
            assertFalse(interpreter.evalRepl("fileExists(\"" + f2 + "\")").asBool());
            assertTrue(interpreter.evalRepl("fileExists(\"" + f3 + "\")").asBool());

            interpreter.evalRepl("deleteFile(\"" + f3 + "\")");
            assertFalse(interpreter.evalRepl("fileExists(\"" + f3 + "\")").asBool());
        }

        @Test
        @DisplayName("pathJoin / fileName / fileExtension / parentDir")
        void testPathUtils() {
            interpreter.evalRepl("import nova.io.*");
            String joined = interpreter.evalRepl("pathJoin(\"a\", \"b\", \"c.txt\")").asString();
            assertTrue(joined.contains("a") && joined.contains("b") && joined.contains("c.txt"));

            assertEquals("test.txt", interpreter.evalRepl("fileName(\"/path/to/test.txt\")").asString());
            assertEquals("txt", interpreter.evalRepl("fileExtension(\"/path/to/test.txt\")").asString());
        }

        @Test
        @DisplayName("readBytes / writeBytes")
        void testReadWriteBytes() {
            interpreter.evalRepl("import nova.io.*");
            String path = tempDir.resolve("bytes.bin").toString().replace("\\", "\\\\");
            // 写入字节列表
            interpreter.evalRepl("writeBytes(\"" + path + "\", [72, 101, 108, 108, 111])");
            assertTrue(interpreter.evalRepl("fileExists(\"" + path + "\")").asBool());
            // 读取字节列表
            NovaValue bytes = interpreter.evalRepl("readBytes(\"" + path + "\")");
            assertTrue(bytes instanceof NovaList);
            assertEquals(5, ((NovaList) bytes).size());
            assertEquals(72, ((NovaList) bytes).get(0).asInt());  // 'H'
            assertEquals(111, ((NovaList) bytes).get(4).asInt()); // 'o'
            // 验证和 readFile 结果一致
            assertEquals("Hello", interpreter.evalRepl("readFile(\"" + path + "\")").asString());
        }
    }

    // ================================================================
    // P1-2: nova.text (Regex)
    // ================================================================

    @Nested
    @DisplayName("nova.text 模块")
    class RegexModuleTest {

        @Test
        @DisplayName("Regex matches / containsMatchIn")
        void testRegexMatches() {
            interpreter.evalRepl("import nova.text.*");
            interpreter.evalRepl("val r = Regex(\"[0-9]+\")");
            assertTrue(interpreter.evalRepl("r.matches(\"123\")").asBool());
            assertFalse(interpreter.evalRepl("r.matches(\"abc\")").asBool());
            assertTrue(interpreter.evalRepl("r.containsMatchIn(\"abc123def\")").asBool());
        }

        @Test
        @DisplayName("Regex find / findAll")
        void testRegexFind() {
            interpreter.evalRepl("import nova.text.*");
            interpreter.evalRepl("val r = Regex(\"[0-9]+\")");

            interpreter.evalRepl("val m = r.find(\"abc123def456\")");
            assertFalse(interpreter.evalRepl("m").isNull());
            assertEquals("123", interpreter.evalRepl("m.value").asString());

            NovaValue all = interpreter.evalRepl("r.findAll(\"abc123def456\")");
            assertEquals(2, ((NovaList) all).size());
        }

        @Test
        @DisplayName("Regex replace / split")
        void testRegexReplaceSplit() {
            interpreter.evalRepl("import nova.text.*");
            interpreter.evalRepl("val r = Regex(\"[0-9]+\")");
            assertEquals("abc_def_", interpreter.evalRepl("r.replace(\"abc123def456\", \"_\")").asString());

            NovaValue parts = interpreter.evalRepl("r.split(\"abc123def456\")");
            assertTrue(parts instanceof NovaList);
        }

        @Test
        @DisplayName("RegexOption 常量 + 大小写不敏感")
        void testRegexOption() {
            interpreter.evalRepl("import nova.text.*");
            // 使用 RegexOption 常量
            interpreter.evalRepl("val r = Regex(\"hello\", RegexOption.IGNORE_CASE)");
            assertTrue(interpreter.evalRepl("r.matches(\"HELLO\")").asBool());
            assertTrue(interpreter.evalRepl("r.matches(\"Hello\")").asBool());
            assertFalse(interpreter.evalRepl("Regex(\"hello\").matches(\"HELLO\")").asBool());
        }
    }

    // ================================================================
    // P1-3: nova.json
    // ================================================================

    @Nested
    @DisplayName("nova.json 模块")
    class JsonModuleTest {

        @Test
        @DisplayName("jsonParse 基本类型")
        void testJsonParseBasic() {
            interpreter.evalRepl("import nova.json.*");
            assertEquals(42, interpreter.evalRepl("jsonParse(\"42\")").asInt());
            assertEquals("hello", interpreter.evalRepl("jsonParse(\"\\\"hello\\\"\")").asString());
            assertTrue(interpreter.evalRepl("jsonParse(\"true\")").asBool());
            assertTrue(interpreter.evalRepl("jsonParse(\"null\")").isNull());
        }

        @Test
        @DisplayName("jsonParse 对象和数组")
        void testJsonParseComplex() {
            interpreter.evalRepl("import nova.json.*");
            interpreter.evalRepl("val obj = jsonParse(\"{\\\"name\\\": \\\"Nova\\\", \\\"version\\\": 1}\")");
            assertEquals("Nova", interpreter.evalRepl("obj[\"name\"]").asString());
            assertEquals(1, interpreter.evalRepl("obj[\"version\"]").asInt());

            interpreter.evalRepl("val arr = jsonParse(\"[1, 2, 3]\")");
            assertEquals(3, interpreter.evalRepl("arr.size()").asInt());
            assertEquals(2, interpreter.evalRepl("arr[1]").asInt());
        }

        @Test
        @DisplayName("jsonStringify")
        void testJsonStringify() {
            interpreter.evalRepl("import nova.json.*");
            interpreter.evalRepl("val m = mapOf(\"a\" to 1, \"b\" to \"hello\")");
            String json = interpreter.evalRepl("jsonStringify(m)").asString();
            assertTrue(json.contains("\"a\""));
            assertTrue(json.contains("\"hello\""));
        }

        @Test
        @DisplayName("jsonStringifyPretty")
        void testJsonStringifyPretty() {
            interpreter.evalRepl("import nova.json.*");
            String pretty = interpreter.evalRepl("jsonStringifyPretty(mapOf(\"x\" to 1))").asString();
            assertTrue(pretty.contains("\n"));
        }

        @Test
        @DisplayName("jsonParse → jsonStringify 往返")
        void testRoundTrip() {
            interpreter.evalRepl("import nova.json.*");
            String original = "{\"name\":\"Nova\",\"tags\":[\"jvm\",\"lang\"],\"active\":true}";
            interpreter.evalRepl("val parsed = jsonParse(\"" + original.replace("\"", "\\\"") + "\")");
            interpreter.evalRepl("val serialized = jsonStringify(parsed)");
            interpreter.evalRepl("val reparsed = jsonParse(serialized)");
            assertEquals("Nova", interpreter.evalRepl("reparsed[\"name\"]").asString());
            assertEquals(2, interpreter.evalRepl("reparsed[\"tags\"].size()").asInt());
        }
    }

    // ================================================================
    // P1-4: nova.time
    // ================================================================

    @Nested
    @DisplayName("nova.time 模块")
    class TimeModuleTest {

        @Test
        @DisplayName("now / nowNanos")
        void testNow() {
            interpreter.evalRepl("import nova.time.*");
            NovaValue ms = interpreter.evalRepl("now()");
            assertTrue(ms.asLong() > 0);
            NovaValue ns = interpreter.evalRepl("nowNanos()");
            assertTrue(ns.asLong() > 0);
        }

        @Test
        @DisplayName("DateTime.now / DateTime.of")
        void testDateTime() {
            interpreter.evalRepl("import nova.time.*");
            interpreter.evalRepl("val dt = DateTime.now()");
            assertTrue(interpreter.evalRepl("dt.year").asInt() >= 2024);

            interpreter.evalRepl("val dt2 = DateTime.of(2025, 6, 15, 10, 30, 0)");
            assertEquals(2025, interpreter.evalRepl("dt2.year").asInt());
            assertEquals(6, interpreter.evalRepl("dt2.month").asInt());
            assertEquals(15, interpreter.evalRepl("dt2.day").asInt());
        }

        @Test
        @DisplayName("DateTime 方法: plusDays / format / isBefore / isAfter")
        void testDateTimeMethods() {
            interpreter.evalRepl("import nova.time.*");
            interpreter.evalRepl("val dt = DateTime.of(2025, 1, 1, 0, 0, 0)");
            interpreter.evalRepl("val dt2 = dt.plusDays(10)");
            assertEquals(11, interpreter.evalRepl("dt2.day").asInt());

            assertTrue(interpreter.evalRepl("dt.isBefore(dt2)").asBool());
            assertTrue(interpreter.evalRepl("dt2.isAfter(dt)").asBool());
        }

        @Test
        @DisplayName("Duration 完整属性")
        void testDuration() {
            interpreter.evalRepl("import nova.time.*");
            interpreter.evalRepl("val d = Duration.ofSeconds(3661)");
            assertEquals(3661000L, interpreter.evalRepl("d.totalMillis").asLong());
            assertEquals(3661L, interpreter.evalRepl("d.totalSeconds").asLong());
            assertEquals(1L, interpreter.evalRepl("d.hours").asLong());
            assertEquals(61L, interpreter.evalRepl("d.minutes").asLong());
            assertEquals(1L, interpreter.evalRepl("d.seconds").asLong()); // 3661 % 60 = 1
        }

        @Test
        @DisplayName("DateTime.parse / format / dayOfWeek / dayOfYear / timestamp")
        void testDateTimeParseFull() {
            interpreter.evalRepl("import nova.time.*");
            interpreter.evalRepl("val dt = DateTime.parse(\"2025-06-15T10:30:00\")");
            assertEquals(2025, interpreter.evalRepl("dt.year").asInt());
            assertEquals(6, interpreter.evalRepl("dt.month").asInt());
            assertEquals(15, interpreter.evalRepl("dt.day").asInt());
            assertEquals(10, interpreter.evalRepl("dt.hour").asInt());
            assertEquals(30, interpreter.evalRepl("dt.minute").asInt());
            // dayOfWeek: 2025-06-15 是周日 = 7
            assertEquals(7, interpreter.evalRepl("dt.dayOfWeek").asInt());
            assertTrue(interpreter.evalRepl("dt.dayOfYear").asInt() > 0);
            assertTrue(interpreter.evalRepl("dt.timestamp").asLong() > 0);
            // format
            String formatted = interpreter.evalRepl("dt.format(\"yyyy/MM/dd\")").asString();
            assertEquals("2025/06/15", formatted);
        }

        @Test
        @DisplayName("DateTime plusHours / plusMinutes / plusSeconds / minusDays / durationTo")
        void testDateTimeArithmetic() {
            interpreter.evalRepl("import nova.time.*");
            interpreter.evalRepl("val dt = DateTime.of(2025, 1, 1, 12, 0, 0)");
            assertEquals(14, interpreter.evalRepl("dt.plusHours(2).hour").asInt());
            assertEquals(30, interpreter.evalRepl("dt.plusMinutes(30).minute").asInt());
            assertEquals(45, interpreter.evalRepl("dt.plusSeconds(45).second").asInt());
            assertEquals(30, interpreter.evalRepl("dt.minusDays(2).day").asInt()); // 1月1日 - 2天 = 12月30日

            // durationTo
            interpreter.evalRepl("val dt2 = DateTime.of(2025, 1, 2, 12, 0, 0)");
            interpreter.evalRepl("val dur = dt.durationTo(dt2)");
            assertEquals(1L, interpreter.evalRepl("dur.days").asLong());
        }
    }

    // ================================================================
    // P1-5: nova.test
    // ================================================================

    @Nested
    @DisplayName("nova.test 模块")
    class TestModuleTest {

        @Test
        @DisplayName("test + runTests 基本流程")
        void testBasicTestFlow() throws Exception {
            interpreter.evalRepl("import nova.test.*");
            interpreter.evalRepl("test(\"1 + 1 = 2\") { assertEqual(2, 1 + 1) }");
            interpreter.evalRepl("test(\"true is true\") { assertTrue(true) }");

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            interpreter.setStdout(new PrintStream(baos, true, "UTF-8"));
            interpreter.evalRepl("val results = runTests()");
            assertEquals(2, interpreter.evalRepl("results[\"passed\"]").asInt());
            assertEquals(0, interpreter.evalRepl("results[\"failed\"]").asInt());
        }

        @Test
        @DisplayName("断言函数")
        void testAssertions() {
            interpreter.evalRepl("import nova.test.*");

            // assertEqual
            interpreter.evalRepl("assertEqual(1, 1)");
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("assertEqual(1, 2)"));

            // assertTrue / assertFalse
            interpreter.evalRepl("assertTrue(true)");
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("assertTrue(false)"));
            interpreter.evalRepl("assertFalse(false)");

            // assertNull / assertNotNull
            interpreter.evalRepl("assertNull(null)");
            interpreter.evalRepl("assertNotNull(42)");
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("assertNotNull(null)"));

            // assertContains
            interpreter.evalRepl("assertContains([1, 2, 3], 2)");
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("assertContains([1, 2, 3], 99)"));
        }

        @Test
        @DisplayName("assertThrows / assertFails")
        void testExceptionAssertions() {
            interpreter.evalRepl("import nova.test.*");
            interpreter.evalRepl("assertThrows { error(\"boom\") }");
            interpreter.evalRepl("assertFails { error(\"boom\") }");
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("assertThrows { 1 + 1 }"));
        }
    }

    // ================================================================
    // P1-6: nova.system
    // ================================================================

    @Nested
    @DisplayName("nova.system 模块")
    class SystemModuleTest {

        @Test
        @DisplayName("osName / jvmVersion / novaVersion")
        void testSystemInfo() {
            interpreter.evalRepl("import nova.system.*");
            assertFalse(interpreter.evalRepl("osName").asString().isEmpty());
            assertFalse(interpreter.evalRepl("jvmVersion").asString().isEmpty());
            assertEquals("1.0.0", interpreter.evalRepl("novaVersion").asString());
        }

        @Test
        @DisplayName("availableProcessors / totalMemory / freeMemory")
        void testResources() {
            interpreter.evalRepl("import nova.system.*");
            assertTrue(interpreter.evalRepl("availableProcessors").asInt() > 0);
            assertTrue(interpreter.evalRepl("totalMemory()").asLong() > 0);
            assertTrue(interpreter.evalRepl("freeMemory()").asLong() > 0);
        }

        @Test
        @DisplayName("env / envOrDefault / sysProperty")
        void testEnvSysProperty() {
            interpreter.evalRepl("import nova.system.*");
            // PATH 环境变量几乎总是存在的
            NovaValue path = interpreter.evalRepl("env(\"PATH\")");
            // 可能为 null（某些系统大小写敏感），不做断言

            assertEquals("fallback", interpreter.evalRepl("envOrDefault(\"_NOVA_TEST_NONEXIST_\", \"fallback\")").asString());

            NovaValue osName = interpreter.evalRepl("sysProperty(\"os.name\")");
            assertFalse(osName.isNull());
        }

        @Test
        @DisplayName("allEnv")
        void testAllEnv() {
            interpreter.evalRepl("import nova.system.*");
            NovaValue envMap = interpreter.evalRepl("allEnv()");
            assertTrue(envMap instanceof NovaMap);
            assertTrue(((NovaMap) envMap).size() > 0);
        }

        @Test
        @DisplayName("args")
        void testArgs() {
            interpreter.evalRepl("import nova.system.*");
            // 默认没有设置 cliArgs，返回空列表
            NovaValue argList = interpreter.evalRepl("args()");
            assertTrue(argList instanceof NovaList);
            assertEquals(0, ((NovaList) argList).size());

            // 设置 cliArgs 后再测试
            interpreter.setCliArgs(new String[]{"hello", "world"});
            NovaValue argList2 = interpreter.evalRepl("args()");
            assertEquals(2, ((NovaList) argList2).size());
            assertEquals("hello", ((NovaList) argList2).get(0).asString());
            assertEquals("world", ((NovaList) argList2).get(1).asString());
        }
    }

    // ================================================================
    // P1-7: nova.concurrent
    // ================================================================

    @Nested
    @DisplayName("nova.concurrent 模块")
    class ConcurrentModuleTest {

        @Test
        @DisplayName("parallel")
        void testParallel() {
            interpreter.evalRepl("import nova.concurrent.*");
            NovaValue results = interpreter.evalRepl("parallel({ 1 + 1 }, { 2 + 2 }, { 3 + 3 })");
            assertTrue(results instanceof NovaList);
            assertEquals(3, ((NovaList) results).size());
            assertEquals(2, ((NovaList) results).get(0).asInt());
            assertEquals(4, ((NovaList) results).get(1).asInt());
            assertEquals(6, ((NovaList) results).get(2).asInt());
        }

        @Test
        @DisplayName("withTimeout 正常完成")
        void testWithTimeoutSuccess() {
            interpreter.evalRepl("import nova.concurrent.*");
            assertEquals(42, interpreter.evalRepl("withTimeout(5000) { 42 }").asInt());
        }

        @Test
        @DisplayName("delay")
        void testDelay() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("delay(10)"); // 不抛异常即可
        }

        @Test
        @DisplayName("AtomicInt")
        void testAtomicInt() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val ai = AtomicInt(0)");
            assertEquals(0, interpreter.evalRepl("ai.get()").asInt());
            assertEquals(1, interpreter.evalRepl("ai.incrementAndGet()").asInt());
            assertEquals(0, interpreter.evalRepl("ai.decrementAndGet()").asInt());
            assertEquals(10, interpreter.evalRepl("ai.addAndGet(10)").asInt());
            interpreter.evalRepl("ai.set(42)");
            assertEquals(42, interpreter.evalRepl("ai.get()").asInt());
            assertTrue(interpreter.evalRepl("ai.compareAndSet(42, 100)").asBool());
            assertEquals(100, interpreter.evalRepl("ai.get()").asInt());
        }

        @Test
        @DisplayName("AtomicLong")
        void testAtomicLong() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val al = AtomicLong(0L)");
            assertEquals(1L, interpreter.evalRepl("al.incrementAndGet()").asLong());
        }

        @Test
        @DisplayName("AtomicRef")
        void testAtomicRef() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val ar = AtomicRef(\"hello\")");
            assertEquals("hello", interpreter.evalRepl("ar.get()").asString());
            interpreter.evalRepl("ar.set(\"world\")");
            assertEquals("world", interpreter.evalRepl("ar.get()").asString());
        }

        @Test
        @DisplayName("Channel")
        void testChannel() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val ch = Channel(10)");
            interpreter.evalRepl("ch.send(42)");
            interpreter.evalRepl("ch.send(99)");
            assertEquals(2, interpreter.evalRepl("ch.size()").asInt());
            assertEquals(42, interpreter.evalRepl("ch.receive()").asInt());
            assertEquals(99, interpreter.evalRepl("ch.receive()").asInt());
            assertTrue(interpreter.evalRepl("ch.isEmpty()").asBool());
        }

        @Test
        @DisplayName("Channel tryReceive 空队列返回 null")
        void testChannelTryReceive() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val ch = Channel()");
            assertTrue(interpreter.evalRepl("ch.tryReceive()").isNull());
        }

        @Test
        @DisplayName("Channel isClosed / close 后不能 send")
        void testChannelIsClosed() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val ch = Channel(10)");
            assertFalse(interpreter.evalRepl("ch.isClosed()").asBool());
            interpreter.evalRepl("ch.close()");
            assertTrue(interpreter.evalRepl("ch.isClosed()").asBool());
            // close 后 send 应抛异常
            assertThrows(NovaRuntimeException.class, () -> interpreter.evalRepl("ch.send(1)"));
        }

        @Test
        @DisplayName("Mutex")
        void testMutex() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val m = Mutex()");
            assertFalse(interpreter.evalRepl("m.isLocked()").asBool());
            assertTrue(interpreter.evalRepl("m.tryLock()").asBool());
            assertTrue(interpreter.evalRepl("m.isLocked()").asBool());
            interpreter.evalRepl("m.unlock()");
            assertFalse(interpreter.evalRepl("m.isLocked()").asBool());
        }

        @Test
        @DisplayName("Mutex withLock")
        void testMutexWithLock() {
            interpreter.evalRepl("import nova.concurrent.*");
            interpreter.evalRepl("val m = Mutex()");
            assertEquals(42, interpreter.evalRepl("m.withLock { 42 }").asInt());
            assertFalse(interpreter.evalRepl("m.isLocked()").asBool());
        }
    }

    // ================================================================
    // SecurityPolicy 集成测试
    // ================================================================

    @Nested
    @DisplayName("SecurityPolicy 集成")
    class SecurityPolicyTest {

        @Test
        @DisplayName("STRICT 模式下 nova.io 操作被拒绝")
        void testStrictDeniesFileIO() {
            Interpreter strict = new Interpreter(NovaSecurityPolicy.strict());
            strict.setReplMode(true);
            strict.evalRepl("import nova.io.*");
            // 路径操作（纯字符串）仍然可用
            assertDoesNotThrow(() -> strict.evalRepl("fileName(\"/path/to/test.txt\")"));
            // 文件 I/O 操作被拒绝
            assertThrows(NovaRuntimeException.class, () -> strict.evalRepl("readFile(\"/tmp/test\")"));
            assertThrows(NovaRuntimeException.class, () -> strict.evalRepl("writeFile(\"/tmp/test\", \"data\")"));
            assertThrows(NovaRuntimeException.class, () -> strict.evalRepl("fileExists(\"/tmp/test\")"));
        }

        @Test
        @DisplayName("STANDARD 模式下 exec 被拒绝")
        void testStandardDeniesExec() {
            Interpreter standard = new Interpreter(NovaSecurityPolicy.standard());
            standard.setReplMode(true);
            standard.evalRepl("import nova.system.*");
            // 信息查询仍然可用
            assertDoesNotThrow(() -> standard.evalRepl("osName"));
            // exec 被拒绝
            assertThrows(NovaRuntimeException.class, () -> standard.evalRepl("exec(\"echo\", \"hello\")"));
        }

        @Test
        @DisplayName("STRICT 模式下网络操作被拒绝")
        void testStrictDeniesNetwork() {
            Interpreter strict = new Interpreter(NovaSecurityPolicy.strict());
            strict.setReplMode(true);
            strict.evalRepl("import nova.http.*");
            assertThrows(NovaRuntimeException.class, () -> strict.evalRepl("httpGet(\"http://example.com\")"));
        }
    }
}
