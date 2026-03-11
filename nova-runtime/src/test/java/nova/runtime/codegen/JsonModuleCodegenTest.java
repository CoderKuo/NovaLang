package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 import nova.json.* 测试。
 */
@DisplayName("编译模式: import nova.json.*")
class JsonModuleCodegenTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    static {
        compiler.setScriptMode(true);
    }

    private Object compileAndRun(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> module = loaded.get("$Module");
        assertNotNull(module);
        Method main = module.getDeclaredMethod("main");
        main.setAccessible(true);
        NovaScriptContext.init(new HashMap<>());
        try {
            return main.invoke(null);
        } finally {
            NovaScriptContext.clear();
        }
    }

    private static String asString(Object result) {
        if (result instanceof NovaValue) return ((NovaValue) result).asString();
        return String.valueOf(result);
    }

    // ============ jsonParse ============

    @Test
    @DisplayName("解析 JSON 对象")
    @SuppressWarnings("unchecked")
    void testParseObject() throws Exception {
        String code = "import nova.json.*\n" +
                "jsonParse(\"{\\\"name\\\":\\\"nova\\\",\\\"version\\\":1}\")";
        Object result = compileAndRun(code);
        assertInstanceOf(Map.class, result);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals("nova", map.get("name"));
        assertEquals(1, map.get("version"));
    }

    @Test
    @DisplayName("解析 JSON 数组")
    @SuppressWarnings("unchecked")
    void testParseArray() throws Exception {
        String code = "import nova.json.*\n" +
                "jsonParse(\"[1, 2, 3]\")";
        Object result = compileAndRun(code);
        assertInstanceOf(List.class, result);
        List<Object> list = (List<Object>) result;
        assertEquals(3, list.size());
        assertEquals(1, list.get(0));
        assertEquals(2, list.get(1));
        assertEquals(3, list.get(2));
    }

    @Test
    @DisplayName("解析 JSON 字符串")
    void testParseString() throws Exception {
        String code = "import nova.json.*\n" +
                "jsonParse(\"\\\"hello\\\"\")";
        Object result = compileAndRun(code);
        assertEquals("hello", result);
    }

    @Test
    @DisplayName("解析 JSON 布尔值和 null")
    @SuppressWarnings("unchecked")
    void testParseBooleanAndNull() throws Exception {
        String code = "import nova.json.*\n" +
                "jsonParse(\"{\\\"a\\\":true,\\\"b\\\":false,\\\"c\\\":null}\")";
        Object result = compileAndRun(code);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(Boolean.TRUE, map.get("a"));
        assertEquals(Boolean.FALSE, map.get("b"));
        assertNull(map.get("c"));
    }

    @Test
    @DisplayName("解析浮点数")
    @SuppressWarnings("unchecked")
    void testParseFloat() throws Exception {
        String code = "import nova.json.*\n" +
                "jsonParse(\"{\\\"pi\\\":3.14}\")";
        Object result = compileAndRun(code);
        Map<String, Object> map = (Map<String, Object>) result;
        assertEquals(3.14, ((Number) map.get("pi")).doubleValue(), 0.001);
    }

    // ============ jsonStringify ============

    @Test
    @DisplayName("序列化 JSON 字符串")
    void testStringifyFromParsed() throws Exception {
        // parse → stringify 往返
        String code = "import nova.json.*\n" +
                "val obj = jsonParse(\"{\\\"a\\\":1,\\\"b\\\":true}\")\n" +
                "jsonStringify(obj)";
        Object result = compileAndRun(code);
        String json = asString(result);
        assertTrue(json.contains("\"a\":1"));
        assertTrue(json.contains("\"b\":true"));
    }

    @Test
    @DisplayName("序列化 null")
    void testStringifyNull() throws Exception {
        String code = "import nova.json.*\n" +
                "jsonStringify(null)";
        Object result = compileAndRun(code);
        assertEquals("null", asString(result));
    }

    // ============ jsonStringifyPretty ============

    @Test
    @DisplayName("美化输出含换行和缩进")
    void testStringifyPretty() throws Exception {
        String code = "import nova.json.*\n" +
                "val obj = jsonParse(\"{\\\"key\\\":\\\"value\\\"}\")\n" +
                "jsonStringifyPretty(obj)";
        Object result = compileAndRun(code);
        String pretty = asString(result);
        assertTrue(pretty.contains("\n"));
        assertTrue(pretty.contains("  ")); // 默认 2 空格缩进
    }

    // ============ 综合 ============

    @Test
    @DisplayName("嵌套结构往返")
    @SuppressWarnings("unchecked")
    void testNestedRoundTrip() throws Exception {
        String json = "{\\\"users\\\":[{\\\"name\\\":\\\"alice\\\"},{\\\"name\\\":\\\"bob\\\"}]}";
        String code = "import nova.json.*\n" +
                "val obj = jsonParse(\"" + json + "\")\n" +
                "jsonStringify(obj)";
        Object result = compileAndRun(code);
        String s = asString(result);
        assertTrue(s.contains("\"users\""));
        assertTrue(s.contains("\"alice\""));
        assertTrue(s.contains("\"bob\""));
    }
}
