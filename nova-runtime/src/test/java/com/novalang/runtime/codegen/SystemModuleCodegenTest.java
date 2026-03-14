package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 import nova.system.* 测试。
 */
@DisplayName("编译模式: import nova.system.*")
class SystemModuleCodegenTest {

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

    // ============ 环境变量 ============

    @Test
    @DisplayName("env — 读取 PATH 环境变量")
    void testEnvPath() throws Exception {
        // PATH 几乎在所有系统上都存在
        String code = "import nova.system.*\n" +
                "env(\"PATH\")";
        Object result = compileAndRun(code);
        assertNotNull(result);
        assertTrue(asString(result).length() > 0);
    }

    @Test
    @DisplayName("env — 不存在的变量返回 null")
    void testEnvNotExists() throws Exception {
        String code = "import nova.system.*\n" +
                "env(\"NOVA_NONEXISTENT_VAR_12345\")";
        Object result = compileAndRun(code);
        assertNull(result);
    }

    @Test
    @DisplayName("envOrDefault — 不存在时返回默认值")
    void testEnvOrDefault() throws Exception {
        String code = "import nova.system.*\n" +
                "envOrDefault(\"NOVA_NONEXISTENT_VAR_12345\", \"fallback\")";
        Object result = compileAndRun(code);
        assertEquals("fallback", asString(result));
    }

    @Test
    @DisplayName("allEnv — 返回 Map")
    void testAllEnv() throws Exception {
        String code = "import nova.system.*\n" +
                "val m = allEnv()\n" +
                "m.toString().length() > 0";
        Object result = compileAndRun(code);
        assertTrue((Boolean) result);
    }

    // ============ 系统属性 ============

    @Test
    @DisplayName("sysProperty — java.version")
    void testSysProperty() throws Exception {
        String code = "import nova.system.*\n" +
                "sysProperty(\"java.version\")";
        Object result = compileAndRun(code);
        assertNotNull(result);
        assertEquals(System.getProperty("java.version"), asString(result));
    }

    @Test
    @DisplayName("sysProperty — 不存在返回 null")
    void testSysPropertyNull() throws Exception {
        String code = "import nova.system.*\n" +
                "sysProperty(\"nova.nonexistent.prop\")";
        Object result = compileAndRun(code);
        assertNull(result);
    }

    // ============ 系统信息（常量函数化） ============

    @Test
    @DisplayName("osName()")
    void testOsName() throws Exception {
        String code = "import nova.system.*\n" +
                "osName()";
        Object result = compileAndRun(code);
        assertEquals(System.getProperty("os.name", "unknown"), asString(result));
    }

    @Test
    @DisplayName("jvmVersion()")
    void testJvmVersion() throws Exception {
        String code = "import nova.system.*\n" +
                "jvmVersion()";
        Object result = compileAndRun(code);
        assertEquals(System.getProperty("java.version", "unknown"), asString(result));
    }

    @Test
    @DisplayName("novaVersion()")
    void testNovaVersion() throws Exception {
        String code = "import nova.system.*\n" +
                "novaVersion()";
        Object result = compileAndRun(code);
        assertEquals("1.0.0", asString(result));
    }

    @Test
    @DisplayName("availableProcessors()")
    void testAvailableProcessors() throws Exception {
        String code = "import nova.system.*\n" +
                "availableProcessors()";
        Object result = compileAndRun(code);
        assertEquals(Runtime.getRuntime().availableProcessors(), ((Number) result).intValue());
    }

    @Test
    @DisplayName("totalMemory()")
    void testTotalMemory() throws Exception {
        String code = "import nova.system.*\n" +
                "totalMemory()";
        Object result = compileAndRun(code);
        assertTrue(((Number) result).longValue() > 0);
    }

    @Test
    @DisplayName("freeMemory()")
    void testFreeMemory() throws Exception {
        String code = "import nova.system.*\n" +
                "freeMemory()";
        Object result = compileAndRun(code);
        assertTrue(((Number) result).longValue() > 0);
    }
}
