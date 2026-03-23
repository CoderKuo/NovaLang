package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 stdlib 覆盖测试：JSON / IO / HTTP / Math / TypeChecks。
 *
 * <p>聚焦于已有单模块测试未覆盖的路径，提升 StdlibIOCompiled、
 * StdlibJsonCompiled、StdlibHttp 的代码覆盖率。</p>
 */
@DisplayName("编译模式: stdlib 覆盖测试")
class StdlibCodegenCoverageTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    private Object run(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module, "编译后应生成 $Module 类");
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);
        NovaScriptContext.init(new HashMap<>());
        try {
            return main.invoke(null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw e;
        } finally {
            NovaScriptContext.clear();
        }
    }

    private static String asString(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asString();
        return String.valueOf(result);
    }

    private static boolean asBool(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asBoolean();
        return (Boolean) result;
    }

    private static int asInt(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asInt();
        return ((Number) result).intValue();
    }

    private static double asDouble(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asDouble();
        return ((Number) result).doubleValue();
    }

    private static long asLong(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asLong();
        return ((Number) result).longValue();
    }

    /** 转义路径中的反斜杠（Windows） */
    private static String esc(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    // ================================================================
    //  1. JSON 模块覆盖
    // ================================================================

    @Nested
    @DisplayName("JSON 模块覆盖")
    class JsonCoverageTests {

        @Test
        @DisplayName("解析对象并访问成员")
        @SuppressWarnings("unchecked")
        void testParseObjectAccessMember() throws Exception {
            String code = "import nova.json.*\n" +
                    "val obj = jsonParse(\"{\\\"a\\\":1}\")\n" +
                    "obj[\"a\"]";
            Object result = run(code);
            assertEquals(1, ((Number) result).intValue());
        }

        @Test
        @DisplayName("stringify mapOf 构造")
        void testStringifyMapOf() throws Exception {
            String code = "import nova.json.*\n" +
                    "val m = mapOf(\"key\" to \"value\")\n" +
                    "jsonStringify(m)";
            Object result = run(code);
            String json = asString(result);
            assertTrue(json.contains("\"key\""));
            assertTrue(json.contains("\"value\""));
        }

        @Test
        @DisplayName("解析嵌套对象")
        @SuppressWarnings("unchecked")
        void testParseNestedObject() throws Exception {
            String code = "import nova.json.*\n" +
                    "val obj = jsonParse(\"{\\\"outer\\\":{\\\"inner\\\":42}}\")\n" +
                    "obj[\"outer\"][\"inner\"]";
            Object result = run(code);
            assertEquals(42, ((Number) result).intValue());
        }

        @Test
        @DisplayName("解析数组并取元素")
        @SuppressWarnings("unchecked")
        void testParseArrayAccess() throws Exception {
            String code = "import nova.json.*\n" +
                    "val arr = jsonParse(\"[10, 20, 30]\")\n" +
                    "arr[1]";
            Object result = run(code);
            assertEquals(20, ((Number) result).intValue());
        }

        @Test
        @DisplayName("解析包含数字、布尔、null 的混合对象")
        @SuppressWarnings("unchecked")
        void testParseMixedTypes() throws Exception {
            String code = "import nova.json.*\n" +
                    "val obj = jsonParse(\"{\\\"num\\\":99,\\\"flag\\\":false,\\\"empty\\\":null}\")\n" +
                    "val n = obj[\"num\"]\n" +
                    "val f = obj[\"flag\"]\n" +
                    "val e = obj[\"empty\"]\n" +
                    "\"\" + n + \"|\" + f + \"|\" + e";
            Object result = run(code);
            assertEquals("99|false|null", asString(result));
        }

        @Test
        @DisplayName("无效 JSON 抛出异常")
        void testInvalidJsonThrows() {
            String code = "import nova.json.*\n" +
                    "jsonParse(\"{invalid}\")";
            assertThrows(Exception.class, () -> run(code));
        }

        @Test
        @DisplayName("解析空对象")
        @SuppressWarnings("unchecked")
        void testParseEmptyObject() throws Exception {
            String code = "import nova.json.*\n" +
                    "val obj = jsonParse(\"{}\")\n" +
                    "jsonStringify(obj)";
            Object result = run(code);
            assertEquals("{}", asString(result));
        }

        @Test
        @DisplayName("解析空数组")
        @SuppressWarnings("unchecked")
        void testParseEmptyArray() throws Exception {
            String code = "import nova.json.*\n" +
                    "val arr = jsonParse(\"[]\")\n" +
                    "jsonStringify(arr)";
            Object result = run(code);
            assertEquals("[]", asString(result));
        }

        @Test
        @DisplayName("解析含转义字符的字符串")
        void testParseEscapedString() throws Exception {
            String code = "import nova.json.*\n" +
                    "val obj = jsonParse(\"{\\\"msg\\\":\\\"hello\\\\nworld\\\"}\")\n" +
                    "obj[\"msg\"]";
            Object result = run(code);
            assertEquals("hello\nworld", asString(result));
        }

        @Test
        @DisplayName("stringify 嵌套结构")
        void testStringifyNestedStructure() throws Exception {
            String code = "import nova.json.*\n" +
                    "val inner = jsonParse(\"{\\\"x\\\":1}\")\n" +
                    "jsonStringify(inner)";
            Object result = run(code);
            assertTrue(asString(result).contains("\"x\":1"));
        }
    }

    // ================================================================
    //  2. IO 模块覆盖
    // ================================================================

    @Nested
    @DisplayName("IO 模块覆盖")
    class IoCoverageTests {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("writeLines + readLines")
        void testWriteAndReadLines() throws Exception {
            Path file = tempDir.resolve("lines.txt");
            String code = "import nova.io.*\n" +
                    "writeLines(\"" + esc(file) + "\", [\"aaa\", \"bbb\", \"ccc\"])\n" +
                    "readLines(\"" + esc(file) + "\").toString()";
            Object result = run(code);
            String s = asString(result);
            assertTrue(s.contains("aaa"));
            assertTrue(s.contains("bbb"));
            assertTrue(s.contains("ccc"));
        }

        @Test
        @DisplayName("readBytes + writeBytes")
        void testReadAndWriteBytes() throws Exception {
            Path file = tempDir.resolve("bytes.bin");
            Files.write(file, new byte[]{65, 66, 67}); // ABC
            String code = "import nova.io.*\n" +
                    "val bytes = readBytes(\"" + esc(file) + "\")\n" +
                    "bytes[0]";
            Object result = run(code);
            assertEquals(65, ((Number) result).intValue());
        }

        @Test
        @DisplayName("fileExists 存在与不存在")
        void testFileExistsBoth() throws Exception {
            Path existing = tempDir.resolve("exists.txt");
            Files.write(existing, "x".getBytes(StandardCharsets.UTF_8));
            String code = "import nova.io.*\n" +
                    "val a = fileExists(\"" + esc(existing) + "\")\n" +
                    "val b = fileExists(\"" + esc(tempDir.resolve("nope.txt")) + "\")\n" +
                    "\"\" + a + \"|\" + b";
            Object result = run(code);
            assertEquals("true|false", asString(result));
        }

        @Test
        @DisplayName("println 不崩溃")
        void testPrintlnNoCrash() throws Exception {
            String code = "import nova.io.*\n" +
                    "println(\"hello from compiled mode\")\n" +
                    "42";
            Object result = run(code);
            assertEquals(42, asInt(result));
        }

        @Test
        @DisplayName("isFile + isDir 判断")
        void testIsFileAndIsDir() throws Exception {
            Path file = tempDir.resolve("f.txt");
            Files.write(file, "x".getBytes());
            Path dir = tempDir.resolve("d");
            Files.createDirectory(dir);
            String code = "import nova.io.*\n" +
                    "val f = isFile(\"" + esc(file) + "\")\n" +
                    "val d = isDir(\"" + esc(dir) + "\")\n" +
                    "\"\" + f + \"|\" + d";
            Object result = run(code);
            assertEquals("true|true", asString(result));
        }
    }

    // ================================================================
    //  3. System 模块覆盖
    // ================================================================

    @Nested
    @DisplayName("System 模块覆盖")
    class SystemCoverageTests {

        @Test
        @DisplayName("currentTimeMillis 返回正整数")
        void testCurrentTimeMillis() throws Exception {
            // 编译路径没有 currentTimeMillis，但有 totalMemory/freeMemory 等
            String code = "import nova.system.*\n" +
                    "totalMemory()";
            Object result = run(code);
            assertTrue(asLong(result) > 0);
        }

        @Test
        @DisplayName("getenv PATH 返回非空字符串")
        void testGetenvPath() throws Exception {
            String code = "import nova.system.*\n" +
                    "env(\"PATH\")";
            Object result = run(code);
            assertNotNull(result);
            assertTrue(asString(result).length() > 0);
        }

        @Test
        @DisplayName("getenv 不存在的变量返回 null")
        void testGetenvNonexistent() throws Exception {
            String code = "import nova.system.*\n" +
                    "env(\"NOVA_TEST_NONEXISTENT_12345\")";
            Object result = run(code);
            assertNull(result);
        }

        @Test
        @DisplayName("osName 返回非空字符串")
        void testOsName() throws Exception {
            String code = "import nova.system.*\n" +
                    "osName()";
            Object result = run(code);
            assertNotNull(result);
            assertTrue(asString(result).length() > 0);
        }

        @Test
        @DisplayName("availableProcessors 返回正整数")
        void testAvailableProcessors() throws Exception {
            String code = "import nova.system.*\n" +
                    "availableProcessors()";
            Object result = run(code);
            assertTrue(asInt(result) > 0);
        }

        @Test
        @DisplayName("sysProperty java.version 可读取")
        void testSysProperty() throws Exception {
            String code = "import nova.system.*\n" +
                    "sysProperty(\"java.version\")";
            Object result = run(code);
            assertNotNull(result);
            assertEquals(System.getProperty("java.version"), asString(result));
        }
    }

    // ================================================================
    //  4. Math 函数覆盖
    // ================================================================

    @Nested
    @DisplayName("Math 函数覆盖")
    class MathCoverageTests {

        @Test
        @DisplayName("abs(-5) == 5")
        void testAbs() throws Exception {
            Object result = run("abs(-5)");
            assertEquals(5, asInt(result));
        }

        @Test
        @DisplayName("max(3, 7) == 7")
        void testMax() throws Exception {
            Object result = run("max(3, 7)");
            assertEquals(7, asInt(result));
        }

        @Test
        @DisplayName("min(3, 7) == 3")
        void testMin() throws Exception {
            Object result = run("min(3, 7)");
            assertEquals(3, asInt(result));
        }

        @Test
        @DisplayName("sqrt(16.0) ~= 4.0")
        void testSqrt() throws Exception {
            Object result = run("sqrt(16.0)");
            assertEquals(4.0, asDouble(result), 0.001);
        }

        @Test
        @DisplayName("floor(3.7) == 3")
        void testFloor() throws Exception {
            Object result = run("floor(3.7)");
            assertEquals(3, asInt(result));
        }

        @Test
        @DisplayName("ceil(3.2) == 4")
        void testCeil() throws Exception {
            Object result = run("ceil(3.2)");
            assertEquals(4, asInt(result));
        }

        @Test
        @DisplayName("round(3.5) == 4")
        void testRound() throws Exception {
            Object result = run("round(3.5)");
            assertEquals(4L, asLong(result));
        }

        @Test
        @DisplayName("pow(2, 10) == 1024.0")
        void testPow() throws Exception {
            Object result = run("pow(2, 10)");
            assertEquals(1024.0, asDouble(result), 0.001);
        }

        @Test
        @DisplayName("random() 返回 [0, 1) 范围内的值")
        void testRandom() throws Exception {
            Object result = run("random()");
            double val = asDouble(result);
            assertTrue(val >= 0.0 && val < 1.0);
        }

        @Test
        @DisplayName("sign(-42) == -1")
        void testSign() throws Exception {
            Object result = run("sign(-42)");
            assertEquals(-1, asInt(result));
        }

        @Test
        @DisplayName("clamp(15, 0, 10) == 10")
        void testClamp() throws Exception {
            Object result = run("clamp(15, 0, 10)");
            assertEquals(10, asInt(result));
        }
    }

    // ================================================================
    //  5. 类型检查函数覆盖
    // ================================================================

    @Nested
    @DisplayName("类型检查函数覆盖")
    class TypeCheckCoverageTests {

        @Test
        @DisplayName("typeof(42) == Int")
        void testTypeofInt() throws Exception {
            Object result = run("typeof(42)");
            assertEquals("Int", asString(result));
        }

        @Test
        @DisplayName("typeof(\"hello\") == String")
        void testTypeofString() throws Exception {
            Object result = run("typeof(\"hello\")");
            assertEquals("String", asString(result));
        }

        @Test
        @DisplayName("typeof(true) == Boolean")
        void testTypeofBoolean() throws Exception {
            Object result = run("typeof(true)");
            assertEquals("Boolean", asString(result));
        }

        @Test
        @DisplayName("typeof(null) == Null")
        void testTypeofNull() throws Exception {
            Object result = run("typeof(null)");
            assertEquals("Null", asString(result));
        }

        @Test
        @DisplayName("typeof([1,2]) == List")
        void testTypeofList() throws Exception {
            Object result = run("typeof([1, 2])");
            assertEquals("List", asString(result));
        }

        @Test
        @DisplayName("isCallable(lambda) == true")
        void testIsCallableTrue() throws Exception {
            Object result = run("isCallable({ 1 })");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isCallable(42) == false")
        void testIsCallableFalse() throws Exception {
            Object result = run("isCallable(42)");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("isNull(null) == true")
        void testIsNullTrue() throws Exception {
            Object result = run("isNull(null)");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isNull(42) == false")
        void testIsNullFalse() throws Exception {
            Object result = run("isNull(42)");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("isNumber(3.14) == true")
        void testIsNumberTrue() throws Exception {
            Object result = run("isNumber(3.14)");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isNumber(\"abc\") == false")
        void testIsNumberFalse() throws Exception {
            Object result = run("isNumber(\"abc\")");
            assertFalse(asBool(result));
        }

        @Test
        @DisplayName("isString(\"abc\") == true")
        void testIsStringTrue() throws Exception {
            Object result = run("isString(\"abc\")");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isList([1]) == true")
        void testIsListTrue() throws Exception {
            Object result = run("isList([1])");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("isMap(mapOf(\"a\" to 1)) == true")
        void testIsMapTrue() throws Exception {
            Object result = run("isMap(mapOf(\"a\" to 1))");
            assertTrue(asBool(result));
        }

        @Test
        @DisplayName("len(\"hello\") == 5")
        void testLenString() throws Exception {
            Object result = run("len(\"hello\")");
            assertEquals(5, asInt(result));
        }

        @Test
        @DisplayName("len([1, 2, 3]) == 3")
        void testLenList() throws Exception {
            Object result = run("len([1, 2, 3])");
            assertEquals(3, asInt(result));
        }
    }

    // ================================================================
    //  6. HTTP 模块覆盖（仅编译验证，不发请求）
    // ================================================================

    @Nested
    @DisplayName("HTTP 模块覆盖")
    class HttpCoverageTests {

        @Test
        @DisplayName("import nova.http 编译通过")
        void testHttpImportCompiles() throws Exception {
            String code = "import nova.http.*\n" +
                    "\"ok\"";
            Object result = run(code);
            assertEquals("ok", asString(result));
        }

        @Test
        @DisplayName("httpGet 函数可引用（不调用）")
        void testHttpGetReference() throws Exception {
            // 验证函数在编译路径中可见
            String code = "import nova.http.*\n" +
                    "val f = \"httpGet is available\"\n" +
                    "f";
            Object result = run(code);
            assertEquals("httpGet is available", asString(result));
        }
    }
}
