package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * 标准库及解释器未覆盖路径补充测试。
 *
 * <p>重点覆盖：StdlibHttp（8%）、MemberDispatcher（36%）、NativeFunctionAdapter（38%）
 * 以及 Time/System/JSON/IO/Collection/String/Number 模块的边缘路径。</p>
 */
class StdlibCoverageTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    private String captureOutput(String code) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        PrintStream old = interpreter.getStdout();
        interpreter.setStdout(ps);
        try {
            eval(code);
            return baos.toString(StandardCharsets.UTF_8.name()).trim();
        } catch (Exception e) {
            return baos.toString().trim();
        } finally {
            interpreter.setStdout(old);
        }
    }

    // ================================================================
    // 1. StdlibHttp — 模块加载 + 函数存在性验证（不发送实际网络请求）
    // ================================================================

    @Nested
    @DisplayName("StdlibHttp 模块")
    class HttpModuleTest {

        @Test
        @DisplayName("import nova.http.* 不抛异常")
        void testHttpModuleLoads() {
            assertDoesNotThrow(() -> eval("import nova.http.*"));
        }

        @Test
        @DisplayName("httpGet 函数已注册")
        void testHttpGetExists() {
            eval("import nova.http.*");
            NovaValue fn = eval("httpGet");
            assertNotNull(fn);
            assertFalse(fn.isNull());
        }

        @Test
        @DisplayName("httpPost 函数已注册")
        void testHttpPostExists() {
            eval("import nova.http.*");
            NovaValue fn = eval("httpPost");
            assertNotNull(fn);
            assertFalse(fn.isNull());
        }

        @Test
        @DisplayName("httpPut 函数已注册")
        void testHttpPutExists() {
            eval("import nova.http.*");
            NovaValue fn = eval("httpPut");
            assertNotNull(fn);
            assertFalse(fn.isNull());
        }

        @Test
        @DisplayName("httpDelete 函数已注册")
        void testHttpDeleteExists() {
            eval("import nova.http.*");
            NovaValue fn = eval("httpDelete");
            assertNotNull(fn);
            assertFalse(fn.isNull());
        }

        @Test
        @DisplayName("HttpRequest builder 已注册")
        void testHttpRequestBuilderExists() {
            eval("import nova.http.*");
            NovaValue fn = eval("HttpRequest");
            assertNotNull(fn);
            assertFalse(fn.isNull());
        }

        @Test
        @DisplayName("HttpRequest builder 链式调用构建请求对象")
        void testHttpRequestBuilderChain() {
            eval("import nova.http.*");
            // 构建请求对象但不发送 — 验证 builder 方法返回 map
            eval("val req = HttpRequest(\"http://localhost:9999\")");
            // 验证 builder 返回的 map 包含 url
            NovaValue url = eval("req[\"url\"]");
            assertEquals("http://localhost:9999", url.asString());
        }

        @Test
        @DisplayName("HttpRequest builder method/header/body/timeout 链式调用")
        void testHttpRequestBuilderMethods() {
            eval("import nova.http.*");
            eval("val req = HttpRequest(\"http://localhost:9999\").method(\"POST\").header(\"X-Test\", \"1\").body(\"{}\").timeout(5000)");
            // method 已设置
            assertEquals("POST", eval("req[\"_method\"]").asString());
            // body 已设置
            assertEquals("{}", eval("req[\"_body\"]").asString());
            // timeout 已设置
            assertEquals(5000, eval("req[\"_timeout\"]").asInt());
            // header 已设置
            assertEquals("1", eval("req[\"_headers\"][\"X-Test\"]").asString());
        }

        @Test
        @DisplayName("HttpRequest headers 批量设置")
        void testHttpRequestBulkHeaders() {
            eval("import nova.http.*");
            eval("val req = HttpRequest(\"http://localhost:9999\").headers(mapOf(\"A\" to \"1\", \"B\" to \"2\"))");
            assertEquals("1", eval("req[\"_headers\"][\"A\"]").asString());
            assertEquals("2", eval("req[\"_headers\"][\"B\"]").asString());
        }
    }

    // ================================================================
    // 2. Time 模块 — 补充 sleep / measure 等未覆盖路径
    // ================================================================

    @Nested
    @DisplayName("Time 模块补充覆盖")
    class TimeModuleCoverageTest {

        @Test
        @DisplayName("sleep(10) 不崩溃")
        void testSleepDoesNotCrash() {
            eval("import nova.time.*");
            assertDoesNotThrow(() -> eval("sleep(10)"));
        }

        @Test
        @DisplayName("now() 返回正数毫秒")
        void testNowReturnsPositive() {
            eval("import nova.time.*");
            assertTrue(eval("now()").asLong() > 0);
        }

        @Test
        @DisplayName("Duration.ofMillis")
        void testDurationOfMillis() {
            eval("import nova.time.*");
            eval("val d = Duration.ofMillis(5000)");
            assertEquals(5000L, eval("d.totalMillis").asLong());
            assertEquals(5L, eval("d.totalSeconds").asLong());
        }

        @Test
        @DisplayName("Duration.ofMinutes")
        void testDurationOfMinutes() {
            eval("import nova.time.*");
            eval("val d = Duration.ofMinutes(2)");
            assertEquals(2L, eval("d.minutes").asLong());
        }

        @Test
        @DisplayName("Duration.ofHours")
        void testDurationOfHours() {
            eval("import nova.time.*");
            eval("val d = Duration.ofHours(3)");
            assertEquals(3L, eval("d.hours").asLong());
        }

        @Test
        @DisplayName("Duration.ofDays")
        void testDurationOfDays() {
            eval("import nova.time.*");
            eval("val d = Duration.ofDays(7)");
            assertEquals(7L, eval("d.days").asLong());
        }

        @Test
        @DisplayName("Duration.toString()")
        void testDurationToString() {
            eval("import nova.time.*");
            eval("val d = Duration.ofSeconds(90)");
            String s = eval("d.toString()").asString();
            // java.time.Duration.toString 格式: PT1M30S
            assertNotNull(s);
            assertFalse(s.isEmpty());
        }

        @Test
        @DisplayName("DateTime.toString()")
        void testDateTimeToString() {
            eval("import nova.time.*");
            eval("val dt = DateTime.of(2025, 6, 15, 10, 30, 0)");
            String s = eval("dt.toString()").asString();
            assertTrue(s.contains("2025"));
        }

        @Test
        @DisplayName("DateTime.parse 日期格式（无时间部分）")
        void testDateTimeParseDate() {
            eval("import nova.time.*");
            eval("val dt = DateTime.parse(\"2025-06-15\")");
            assertEquals(2025, eval("dt.year").asInt());
            assertEquals(6, eval("dt.month").asInt());
            assertEquals(15, eval("dt.day").asInt());
        }
    }

    // ================================================================
    // 3. System 模块 — 补充 sysProperty 非存在 key / 常量
    // ================================================================

    @Nested
    @DisplayName("System 模块补充覆盖")
    class SystemModuleCoverageTest {

        @Test
        @DisplayName("env 不存在的变量返回 null")
        void testEnvNonexistent() {
            eval("import nova.system.*");
            assertTrue(eval("env(\"__NOVA_NONEXISTENT_VAR_12345__\")").isNull());
        }

        @Test
        @DisplayName("sysProperty(\"java.home\") 非 null")
        void testSysPropertyJavaHome() {
            eval("import nova.system.*");
            NovaValue home = eval("sysProperty(\"java.home\")");
            assertFalse(home.isNull());
            assertFalse(home.asString().isEmpty());
        }

        @Test
        @DisplayName("sysProperty 不存在的属性返回 null")
        void testSysPropertyNonexistent() {
            eval("import nova.system.*");
            assertTrue(eval("sysProperty(\"__nova_nonexist__\")").isNull());
        }

        @Test
        @DisplayName("availableProcessors 大于 0")
        void testAvailableProcessors() {
            eval("import nova.system.*");
            assertTrue(eval("availableProcessors").asInt() > 0);
        }
    }

    // ================================================================
    // 4. JSON 模块边缘路径
    // ================================================================

    @Nested
    @DisplayName("JSON 模块边缘路径")
    class JsonEdgeCasesTest {

        @Test
        @DisplayName("解析嵌套对象")
        void testParseNestedObject() {
            eval("import nova.json.*");
            eval("val obj = jsonParse(\"{\\\"a\\\":{\\\"b\\\":1}}\")");
            assertEquals(1, eval("obj[\"a\"][\"b\"]").asInt());
        }

        @Test
        @DisplayName("解析数组")
        void testParseArray() {
            eval("import nova.json.*");
            eval("val arr = jsonParse(\"[1,2,3]\")");
            assertEquals(3, eval("arr.size()").asInt());
            assertEquals(1, eval("arr[0]").asInt());
            assertEquals(3, eval("arr[2]").asInt());
        }

        @Test
        @DisplayName("解析空对象和空数组")
        void testParseEmptyContainers() {
            eval("import nova.json.*");
            eval("val obj = jsonParse(\"{}\")");
            assertEquals(0, eval("obj.size()").asInt());
            eval("val arr = jsonParse(\"[]\")");
            assertEquals(0, eval("arr.size()").asInt());
        }

        @Test
        @DisplayName("解析浮点数")
        void testParseFloatingPoint() {
            eval("import nova.json.*");
            assertEquals(3.14, eval("jsonParse(\"3.14\")").asDouble(), 0.001);
        }

        @Test
        @DisplayName("解析负数")
        void testParseNegativeNumber() {
            eval("import nova.json.*");
            assertEquals(-42, eval("jsonParse(\"-42\")").asInt());
        }

        @Test
        @DisplayName("解析科学计数法")
        void testParseScientificNotation() {
            eval("import nova.json.*");
            assertEquals(1.5e10, eval("jsonParse(\"1.5e10\")").asDouble(), 1e6);
        }

        @Test
        @DisplayName("解析转义字符串")
        void testParseEscapeSequences() {
            eval("import nova.json.*");
            // 解析含换行的 JSON 字符串
            NovaValue val = eval("jsonParse(\"\\\"hello\\\\nworld\\\"\")");
            assertTrue(val.asString().contains("\n"));
        }

        @Test
        @DisplayName("stringify 嵌套对象")
        void testStringifyNested() {
            eval("import nova.json.*");
            eval("val m = mapOf(\"a\" to mapOf(\"b\" to 1))");
            String json = eval("jsonStringify(m)").asString();
            assertTrue(json.contains("\"a\""));
            assertTrue(json.contains("\"b\""));
            assertTrue(json.contains("1"));
        }

        @Test
        @DisplayName("stringify 列表")
        void testStringifyList() {
            eval("import nova.json.*");
            String json = eval("jsonStringify([1, 2, 3])").asString();
            assertEquals("[1,2,3]", json);
        }

        @Test
        @DisplayName("stringify null 和布尔值")
        void testStringifyNullAndBool() {
            eval("import nova.json.*");
            assertEquals("null", eval("jsonStringify(null)").asString());
            assertEquals("true", eval("jsonStringify(true)").asString());
            assertEquals("false", eval("jsonStringify(false)").asString());
        }

        @Test
        @DisplayName("无效 JSON 抛异常")
        void testInvalidJsonThrows() {
            eval("import nova.json.*");
            assertThrows(NovaRuntimeException.class, () -> eval("jsonParse(\"{invalid}\")"));
        }

        @Test
        @DisplayName("jsonStringifyPretty 自定义缩进")
        void testStringifyPrettyCustomIndent() {
            eval("import nova.json.*");
            String pretty = eval("jsonStringifyPretty(mapOf(\"x\" to 1), 4)").asString();
            // 4 空格缩进
            assertTrue(pretty.contains("    "));
        }
    }

    // ================================================================
    // 5. IO 模块 — 输出捕获
    // ================================================================

    @Nested
    @DisplayName("IO 输出捕获")
    class IoOutputTest {

        @Test
        @DisplayName("println 输出捕获")
        void testPrintlnCapture() {
            String output = captureOutput("println(\"hello world\")");
            assertEquals("hello world", output);
        }

        @Test
        @DisplayName("print 输出捕获（无换行）")
        void testPrintCapture() {
            String output = captureOutput("print(\"hi\")");
            assertEquals("hi", output);
        }

        @Test
        @DisplayName("多次 println 输出")
        void testMultiplePrintln() {
            String output = captureOutput("println(\"a\")\nprintln(\"b\")");
            assertTrue(output.contains("a"));
            assertTrue(output.contains("b"));
        }

        @Test
        @DisplayName("println 不同类型")
        void testPrintlnDifferentTypes() {
            assertEquals("42", captureOutput("println(42)"));
            assertEquals("true", captureOutput("println(true)"));
            assertEquals("3.14", captureOutput("println(3.14)"));
            assertEquals("null", captureOutput("println(null)"));
        }
    }

    // ================================================================
    // 6. 集合高阶函数
    // ================================================================

    @Nested
    @DisplayName("集合高阶函数覆盖")
    class CollectionHofTest {

        @Test
        @DisplayName("list.map { it * 2 }")
        void testListMap() {
            NovaValue result = eval("[1,2,3].map { it * 2 }");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
            assertEquals(2, ((NovaList) result).get(0).asInt());
            assertEquals(4, ((NovaList) result).get(1).asInt());
            assertEquals(6, ((NovaList) result).get(2).asInt());
        }

        @Test
        @DisplayName("list.filter { it % 2 == 0 }")
        void testListFilter() {
            NovaValue result = eval("[1,2,3,4].filter { it % 2 == 0 }");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            assertEquals(2, ((NovaList) result).get(0).asInt());
            assertEquals(4, ((NovaList) result).get(1).asInt());
        }

        @Test
        @DisplayName("list.reduce")
        void testListReduce() {
            assertEquals(6, eval("[1,2,3].reduce(0) { acc, x -> acc + x }").asInt());
        }

        @Test
        @DisplayName("list.sortBy { it }")
        void testListSortBy() {
            NovaValue result = eval("[3,1,2].sortedBy { it }");
            assertTrue(result instanceof NovaList);
            assertEquals(1, ((NovaList) result).get(0).asInt());
            assertEquals(2, ((NovaList) result).get(1).asInt());
            assertEquals(3, ((NovaList) result).get(2).asInt());
        }

        @Test
        @DisplayName("list.flatMap")
        void testListFlatMap() {
            NovaValue result = eval("[1,2,3].flatMap { [it, it * 10] }");
            assertTrue(result instanceof NovaList);
            assertEquals(6, ((NovaList) result).size());
            assertEquals(1, ((NovaList) result).get(0).asInt());
            assertEquals(10, ((NovaList) result).get(1).asInt());
            assertEquals(2, ((NovaList) result).get(2).asInt());
            assertEquals(20, ((NovaList) result).get(3).asInt());
        }

        @Test
        @DisplayName("list.any { it > 2 }")
        void testListAny() {
            assertTrue(eval("[1,2,3].any { it > 2 }").asBool());
            assertFalse(eval("[1,2,3].any { it > 5 }").asBool());
        }

        @Test
        @DisplayName("list.all { it > 0 }")
        void testListAll() {
            assertTrue(eval("[1,2,3].all { it > 0 }").asBool());
            assertFalse(eval("[1,2,3].all { it > 1 }").asBool());
        }

        @Test
        @DisplayName("list.none { it > 5 }")
        void testListNone() {
            assertTrue(eval("[1,2,3].none { it > 5 }").asBool());
            assertFalse(eval("[1,2,3].none { it > 2 }").asBool());
        }

        @Test
        @DisplayName("list.count { it > 1 }")
        void testListCount() {
            assertEquals(2, eval("[1,2,3].count { it > 1 }").asInt());
        }

        @Test
        @DisplayName("list.distinct()")
        void testListDistinct() {
            NovaValue result = eval("[1,2,2,3,3,3].distinct()");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("list.zip")
        void testListZip() {
            NovaValue result = eval("[1,2,3].zip([4,5,6])");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("list.take(2)")
        void testListTake() {
            NovaValue result = eval("[1,2,3].take(2)");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            assertEquals(1, ((NovaList) result).get(0).asInt());
            assertEquals(2, ((NovaList) result).get(1).asInt());
        }

        @Test
        @DisplayName("list.drop(1)")
        void testListDrop() {
            NovaValue result = eval("[1,2,3].drop(1)");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            assertEquals(2, ((NovaList) result).get(0).asInt());
            assertEquals(3, ((NovaList) result).get(1).asInt());
        }

        @Test
        @DisplayName("list.chunked(2)")
        void testListChunked() {
            NovaValue result = eval("[1,2,3].chunked(2)");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            // 第一个 chunk [1,2]
            NovaList chunk0 = (NovaList) ((NovaList) result).get(0);
            assertEquals(2, chunk0.size());
            // 第二个 chunk [3]
            NovaList chunk1 = (NovaList) ((NovaList) result).get(1);
            assertEquals(1, chunk1.size());
        }

        @Test
        @DisplayName("mapOf entries / containsKey")
        void testMapEntriesAndContainsKey() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertTrue(eval("m.containsKey(\"a\")").asBool());
            assertFalse(eval("m.containsKey(\"c\")").asBool());
            NovaValue entries = eval("m.entries()");
            assertTrue(entries instanceof NovaList);
            assertEquals(2, ((NovaList) entries).size());
        }

        @Test
        @DisplayName("空列表的高阶函数")
        void testEmptyListHof() {
            eval("val empty = []");
            assertEquals(0, eval("empty.map { it }.size()").asInt());
            assertEquals(0, eval("empty.filter { it > 0 }.size()").asInt());
            assertFalse(eval("empty.any { true }").asBool());
            assertTrue(eval("empty.all { false }").asBool());
            assertTrue(eval("empty.none { true }").asBool());
        }
    }

    // ================================================================
    // 7. 字符串操作
    // ================================================================

    @Nested
    @DisplayName("字符串操作覆盖")
    class StringOpsTest {

        @Test
        @DisplayName("padStart 带填充字符")
        void testPadStart() {
            assertEquals("*****hello", eval("\"hello\".padStart(10, '*')").asString());
        }

        @Test
        @DisplayName("padEnd 带填充字符")
        void testPadEnd() {
            assertEquals("hello*****", eval("\"hello\".padEnd(10, '*')").asString());
        }

        @Test
        @DisplayName("trim")
        void testTrim() {
            assertEquals("hello", eval("\"  hello  \".trim()").asString());
        }

        @Test
        @DisplayName("split")
        void testSplit() {
            NovaValue result = eval("\"hello world\".split(\" \")");
            assertTrue(result instanceof NovaList);
            assertEquals(2, ((NovaList) result).size());
            assertEquals("hello", ((NovaList) result).get(0).asString());
            assertEquals("world", ((NovaList) result).get(1).asString());
        }

        @Test
        @DisplayName("chars()")
        void testChars() {
            NovaValue result = eval("\"abc\".chars()");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("repeat()")
        void testRepeat() {
            assertEquals("abcabcabc", eval("\"abc\".repeat(3)").asString());
        }

        @Test
        @DisplayName("contains")
        void testContains() {
            assertTrue(eval("\"hello world\".contains(\"world\")").asBool());
            assertFalse(eval("\"hello world\".contains(\"xyz\")").asBool());
        }

        @Test
        @DisplayName("startsWith / endsWith")
        void testStartsEndsWith() {
            assertTrue(eval("\"hello\".startsWith(\"he\")").asBool());
            assertTrue(eval("\"hello\".endsWith(\"lo\")").asBool());
            assertFalse(eval("\"hello\".startsWith(\"lo\")").asBool());
        }

        @Test
        @DisplayName("toUpperCase / toLowerCase")
        void testCase() {
            assertEquals("HELLO", eval("\"hello\".toUpperCase()").asString());
            assertEquals("hello", eval("\"HELLO\".toLowerCase()").asString());
        }

        @Test
        @DisplayName("replace")
        void testReplace() {
            assertEquals("hxllo", eval("\"hello\".replace(\"e\", \"x\")").asString());
        }

        @Test
        @DisplayName("indexOf")
        void testIndexOf() {
            assertEquals(2, eval("\"hello\".indexOf(\"l\")").asInt());
        }

        @Test
        @DisplayName("substring")
        void testSubstring() {
            assertEquals("ell", eval("\"hello\".substring(1, 4)").asString());
        }
    }

    // ================================================================
    // 8. 数字操作
    // ================================================================

    @Nested
    @DisplayName("数字操作覆盖")
    class NumberOpsTest {

        @Test
        @DisplayName("42.toDouble()")
        void testIntToDouble() {
            assertEquals(42.0, eval("42.toDouble()").asDouble(), 0.001);
        }

        @Test
        @DisplayName("3.14.toInt()")
        void testDoubleToInt() {
            assertEquals(3, eval("3.14.toInt()").asInt());
        }

        @Test
        @DisplayName("42.toLong()")
        void testIntToLong() {
            assertEquals(42L, eval("42.toLong()").asLong());
        }

        @Test
        @DisplayName("abs / max / min")
        void testMathFunctions() {
            assertEquals(5, eval("abs(-5)").asInt());
            assertEquals(7, eval("max(3, 7)").asInt());
            assertEquals(3, eval("min(3, 7)").asInt());
        }

        @Test
        @DisplayName("abs 浮点数")
        void testAbsDouble() {
            assertEquals(3.14, eval("abs(-3.14)").asDouble(), 0.001);
        }

        @Test
        @DisplayName("Long 算术运算")
        void testLongArithmetic() {
            eval("val big = 9999999999L");
            assertTrue(eval("big + 1L").asLong() > 9999999999L);
        }
    }

    // ================================================================
    // 9. MemberDispatcher — toString / hashCode / equals
    // ================================================================

    @Nested
    @DisplayName("MemberDispatcher 覆盖")
    class MemberDispatcherTest {

        @Test
        @DisplayName("Int.toString()")
        void testIntToString() {
            assertEquals("42", eval("42.toString()").asString());
        }

        @Test
        @DisplayName("Boolean.toString()")
        void testBoolToString() {
            assertEquals("true", eval("true.toString()").asString());
        }

        @Test
        @DisplayName("List.toString()")
        void testListToString() {
            String s = eval("[1, 2, 3].toString()").asString();
            assertNotNull(s);
            assertTrue(s.contains("1"));
        }

        @Test
        @DisplayName("自定义类 toString")
        void testCustomClassToString() {
            eval("class Point(val x: Int, val y: Int) {\n  fun toString() = \"Point($x, $y)\"\n}");
            eval("val p = Point(1, 2)");
            assertEquals("Point(1, 2)", eval("p.toString()").asString());
        }

        @Test
        @DisplayName("自定义类 equals")
        void testCustomClassEquals() {
            eval("class Val(val n: Int) {\n  fun equals(other: Val) = this.n == other.n\n}");
            eval("val a = Val(10)");
            eval("val b = Val(10)");
            eval("val c = Val(20)");
            assertTrue(eval("a.equals(b)").asBool());
            assertFalse(eval("a.equals(c)").asBool());
        }

        @Test
        @DisplayName("自定义类 hashCode")
        void testCustomClassHashCode() {
            eval("class Hc(val n: Int) {\n  fun hashCode() = n * 31\n}");
            eval("val h = Hc(10)");
            assertEquals(310, eval("h.hashCode()").asInt());
        }

        @Test
        @DisplayName("Map.toString()")
        void testMapToString() {
            String s = eval("mapOf(\"a\" to 1).toString()").asString();
            assertNotNull(s);
            assertTrue(s.contains("a"));
        }

        @Test
        @DisplayName("null.toString()")
        void testNullToString() {
            assertEquals("null", eval("null.toString()").asString());
        }
    }

    // ================================================================
    // 10. NativeFunctionAdapter — 通过 Nova API 间接触发高参函数适配
    // ================================================================

    @Nested
    @DisplayName("NativeFunctionAdapter 覆盖")
    class NativeFunctionAdapterTest {

        @Test
        @DisplayName("4 参函数通过 Nova.defineFunction 注册并调用")
        void testFourArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("sum4",
                    (Function4<Object, Object, Object, Object, Object>)
                            (a, b, c, d) -> (int) a + (int) b + (int) c + (int) d);
            assertEquals(10, nova.eval("sum4(1, 2, 3, 4)"));
        }

        @Test
        @DisplayName("5 参函数")
        void testFiveArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("sum5",
                    (Function5<Object, Object, Object, Object, Object, Object>)
                            (a, b, c, d, e) -> (int) a + (int) b + (int) c + (int) d + (int) e);
            assertEquals(15, nova.eval("sum5(1, 2, 3, 4, 5)"));
        }

        @Test
        @DisplayName("6 参函数")
        void testSixArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("sum6",
                    (Function6<Object, Object, Object, Object, Object, Object, Object>)
                            (a, b, c, d, e, f) -> (int) a + (int) b + (int) c + (int) d + (int) e + (int) f);
            assertEquals(21, nova.eval("sum6(1, 2, 3, 4, 5, 6)"));
        }

        @Test
        @DisplayName("可变参数函数")
        void testVarargFunction() {
            Nova nova = new Nova();
            nova.defineFunctionVararg("sumAll", args -> {
                int total = 0;
                for (Object a : args) total += (int) a;
                return total;
            });
            assertEquals(10, nova.eval("sumAll(1, 2, 3, 4)"));
        }

        @Test
        @DisplayName("无参函数")
        void testZeroArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("getHello", (Function0<Object>) () -> "hello");
            assertEquals("hello", nova.eval("getHello()"));
        }

        @Test
        @DisplayName("单参函数")
        void testOneArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("double", (Function1<Object, Object>) a -> (int) a * 2);
            assertEquals(20, nova.eval("double(10)"));
        }

        @Test
        @DisplayName("双参函数")
        void testTwoArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("add", (Function2<Object, Object, Object>) (a, b) -> (int) a + (int) b);
            assertEquals(30, nova.eval("add(10, 20)"));
        }

        @Test
        @DisplayName("三参函数")
        void testThreeArgFunction() {
            Nova nova = new Nova();
            nova.defineFunction("sum3",
                    (Function3<Object, Object, Object, Object>)
                            (a, b, c) -> (int) a + (int) b + (int) c);
            assertEquals(6, nova.eval("sum3(1, 2, 3)"));
        }
    }

    // ================================================================
    // 11. 补充：集合构造器 + 范围操作
    // ================================================================

    @Nested
    @DisplayName("集合构造器与范围")
    class CollectionConstructorsTest {

        @Test
        @DisplayName("listOf 构造")
        void testListOf() {
            NovaValue result = eval("listOf(1, 2, 3)");
            assertTrue(result instanceof NovaList);
            assertEquals(3, ((NovaList) result).size());
        }

        @Test
        @DisplayName("mapOf 构造")
        void testMapOf() {
            NovaValue result = eval("mapOf(\"a\" to 1, \"b\" to 2)");
            assertTrue(result instanceof NovaMap);
        }

        @Test
        @DisplayName("setOf 构造")
        void testSetOf() {
            eval("val s = setOf(1, 2, 2, 3)");
            assertEquals(3, eval("s.size()").asInt());
        }

        @Test
        @DisplayName("范围 1..5")
        void testRange() {
            eval("var sum = 0");
            eval("for (i in 1..5) { sum += i }");
            assertEquals(15, eval("sum").asInt());
        }

        @Test
        @DisplayName("reversed 范围")
        void testReversedRange() {
            NovaValue result = eval("(1..5).toList().reversed()");
            assertEquals(5, ((NovaList) result).get(0).asInt());
            assertEquals(1, ((NovaList) result).get(4).asInt());
        }
    }

    // ================================================================
    // 12. 补充：when 表达式 + 类型检查
    // ================================================================

    @Nested
    @DisplayName("when 表达式 + 类型检查")
    class WhenAndTypeCheckTest {

        @Test
        @DisplayName("when 表达式匹配")
        void testWhenExpression() {
            eval("val x = 2");
            NovaValue result = eval("when (x) { 1 -> \"one\"; 2 -> \"two\"; else -> \"other\" }");
            assertEquals("two", result.asString());
        }

        @Test
        @DisplayName("is 类型检查")
        void testIsTypeCheck() {
            assertTrue(eval("42 is Int").asBool());
            assertTrue(eval("\"hello\" is String").asBool());
            assertFalse(eval("42 is String").asBool());
        }

        @Test
        @DisplayName("typeof 函数")
        void testTypeof() {
            assertEquals("Int", eval("typeof(42)").asString());
            assertEquals("String", eval("typeof(\"hello\")").asString());
            assertEquals("Boolean", eval("typeof(true)").asString());
            assertEquals("List", eval("typeof([1,2])").asString());
        }
    }

    // ================================================================
    // 13. 补充：错误处理 / Result 类型
    // ================================================================

    @Nested
    @DisplayName("错误处理 + Result")
    class ErrorHandlingTest {

        @Test
        @DisplayName("try-catch 基本")
        void testTryCatch() {
            NovaValue result = eval("try { throw \"boom\" } catch (e) { \"caught\" }");
            assertEquals("caught", result.asString());
        }

        @Test
        @DisplayName("runCatching Ok")
        void testRunCatchingOk() {
            eval("val r = runCatching { 42 }");
            assertTrue(eval("r is Ok").asBool());
            assertEquals(42, eval("r.value").asInt());
        }

        @Test
        @DisplayName("runCatching Err")
        void testRunCatchingErr() {
            eval("val r = runCatching { throw error(\"fail\") }");
            assertTrue(eval("r is Err").asBool());
        }

        @Test
        @DisplayName("Ok / Err 构造")
        void testOkErrConstruction() {
            eval("val ok = Ok(100)");
            assertEquals(100, eval("ok.value").asInt());
            eval("val err = Err(\"bad\")");
            assertEquals("bad", eval("err.error").asString());
        }
    }

    // ================================================================
    // 14. 补充：Lambda + 闭包
    // ================================================================

    @Nested
    @DisplayName("Lambda 与闭包")
    class LambdaClosureTest {

        @Test
        @DisplayName("Lambda 捕获外部变量")
        void testLambdaClosure() {
            eval("var x = 10");
            eval("val fn = { x + 5 }");
            assertEquals(15, eval("fn()").asInt());
            eval("x = 20");
            assertEquals(25, eval("fn()").asInt());
        }

        @Test
        @DisplayName("高阶函数接收 lambda")
        void testHigherOrderFunction() {
            eval("fun apply(n: Int, f: (Int) -> Int) = f(n)");
            assertEquals(100, eval("apply(10) { it * it }").asInt());
        }

        @Test
        @DisplayName("Lambda 作为返回值")
        void testLambdaAsReturnValue() {
            eval("fun adder(n: Int) = { x: Int -> x + n }");
            eval("val add5 = adder(5)");
            assertEquals(15, eval("add5(10)").asInt());
        }
    }

    // ================================================================
    // 15. 补充：Map 高阶函数
    // ================================================================

    @Nested
    @DisplayName("Map 高阶函数覆盖")
    class MapHofTest {

        @Test
        @DisplayName("map.keys / map.values")
        void testMapKeysValues() {
            eval("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            NovaValue keys = eval("m.keys()");
            assertTrue(keys instanceof NovaList);
            assertEquals(2, ((NovaList) keys).size());
            NovaValue values = eval("m.values()");
            assertTrue(values instanceof NovaList);
            assertEquals(2, ((NovaList) values).size());
        }

        @Test
        @DisplayName("map.getOrDefault")
        void testMapGetOrDefault() {
            eval("val m = mapOf(\"a\" to 1)");
            assertEquals(1, eval("m.getOrDefault(\"a\", 0)").asInt());
            assertEquals(0, eval("m.getOrDefault(\"z\", 0)").asInt());
        }

        @Test
        @DisplayName("map.size()")
        void testMapSize() {
            assertEquals(0, eval("mapOf().size()").asInt());
            assertEquals(2, eval("mapOf(\"a\" to 1, \"b\" to 2).size()").asInt());
        }

        @Test
        @DisplayName("map.isEmpty()")
        void testMapIsEmpty() {
            assertTrue(eval("mapOf().isEmpty()").asBool());
            assertFalse(eval("mapOf(\"a\" to 1).isEmpty()").asBool());
        }
    }

    // ================================================================
    // 16. 补充：字符串模板
    // ================================================================

    @Nested
    @DisplayName("字符串模板")
    class StringTemplateTest {

        @Test
        @DisplayName("简单变量插值")
        void testSimpleInterpolation() {
            eval("val name = \"Nova\"");
            assertEquals("Hello, Nova!", eval("\"Hello, $name!\"").asString());
        }

        @Test
        @DisplayName("表达式插值")
        void testExpressionInterpolation() {
            assertEquals("1 + 2 = 3", eval("\"1 + 2 = ${1 + 2}\"").asString());
        }
    }
}
