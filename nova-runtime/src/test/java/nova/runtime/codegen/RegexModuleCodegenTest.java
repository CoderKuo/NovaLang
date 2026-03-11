package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 import nova.text.* 测试。
 */
@DisplayName("编译模式: import nova.text.*")
class RegexModuleCodegenTest {

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

    // ============ Regex 构造 ============

    @Test
    @DisplayName("Regex 构造并获取 pattern")
    void testRegexPattern() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.pattern";
        Object result = compileAndRun(code);
        assertEquals("\\d+", asString(result));
    }

    // ============ containsMatchIn（唯一名称，无冲突） ============

    @Test
    @DisplayName("containsMatchIn — 匹配成功")
    void testContainsMatchInTrue() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.containsMatchIn(\"abc123def\")";
        Object result = compileAndRun(code);
        assertEquals("true", asString(result));
    }

    @Test
    @DisplayName("containsMatchIn — 匹配失败")
    void testContainsMatchInFalse() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.containsMatchIn(\"abcdef\")";
        Object result = compileAndRun(code);
        assertEquals("false", asString(result));
    }

    @Test
    @DisplayName("containsMatchIn — 邮箱模式")
    void testContainsMatchInEmail() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\\\.[a-zA-Z]{2,}\")\n" +
                "r.containsMatchIn(\"contact: test@example.com here\")";
        Object result = compileAndRun(code);
        assertEquals("true", asString(result));
    }

    @Test
    @DisplayName("Regex 多次使用同一实例")
    void testRegexReuse() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"^[A-Z]\")\n" +
                "val a = r.containsMatchIn(\"Hello\")\n" +
                "val b = r.containsMatchIn(\"world\")\n" +
                "\"\" + a + \",\" + b";
        Object result = compileAndRun(code);
        assertEquals("true,false", asString(result));
    }

    // ============ find（与 CollectionOps/ListExtensions 同名，需类型守卫） ============

    @Test
    @DisplayName("find — 首次匹配")
    void testFind() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.find(\"abc123def456\").value";
        Object result = compileAndRun(code);
        assertEquals("123", asString(result));
    }

    @Test
    @DisplayName("find — 无匹配返回 null")
    void testFindNoMatch() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.find(\"abcdef\")";
        Object result = compileAndRun(code);
        assertNull(result);
    }

    // ============ findAll ============

    @Test
    @DisplayName("findAll — 全部匹配")
    void testFindAll() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "val matches = r.findAll(\"a1b22c333\")\n" +
                "\"\" + matches[0].value + \",\" + matches[1].value + \",\" + matches[2].value";
        Object result = compileAndRun(code);
        assertEquals("1,22,333", asString(result));
    }

    // ============ split（与 StringExtensions 同名，需类型守卫） ============

    @Test
    @DisplayName("split — 按模式分割")
    void testSplit() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"[,;]\")\n" +
                "r.split(\"a,b;c,d\").toString()";
        Object result = compileAndRun(code);
        assertEquals("[a, b, c, d]", asString(result));
    }

    // ============ replace（与 StringExtensions 同名，需类型守卫） ============

    @Test
    @DisplayName("replace — 全部替换")
    void testReplace() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.replace(\"a1b2c3\", \"#\")";
        Object result = compileAndRun(code);
        assertEquals("a#b#c#", asString(result));
    }

    // ============ matches（与 StringExtensions 同名，需类型守卫） ============

    @Test
    @DisplayName("matches — 完全匹配")
    void testMatches() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.matches(\"12345\")";
        Object result = compileAndRun(code);
        assertEquals("true", asString(result));
    }

    @Test
    @DisplayName("matches — 不完全匹配")
    void testMatchesFalse() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.matches(\"abc123\")";
        Object result = compileAndRun(code);
        assertEquals("false", asString(result));
    }

    // ============ replaceFirst（与 StringExtensions 同名，需类型守卫） ============

    @Test
    @DisplayName("replaceFirst — 只替换第一个")
    void testReplaceFirst() throws Exception {
        String code = "import nova.text.*\n" +
                "val r = Regex(\"\\\\d+\")\n" +
                "r.replaceFirst(\"a1b2c3\", \"#\")";
        Object result = compileAndRun(code);
        assertEquals("a#b2c3", asString(result));
    }
}
