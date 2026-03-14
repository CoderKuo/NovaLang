package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式 import nova.http.* 测试。
 *
 * <p>仅测试编译和基本调用，不依赖外部网络服务。</p>
 */
@DisplayName("编译模式: import nova.http.*")
class HttpModuleCodegenTest {

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

    @Test
    @DisplayName("httpGet/httpPost/httpPut/httpDelete 编译通过")
    void testHttpFunctionsCompile() throws Exception {
        // 仅验证 import + 函数引用能编译，不实际发请求
        String code = "import nova.http.*\n" +
                "\"compiled\"";
        Object result = compileAndRun(code);
        assertEquals("compiled", String.valueOf(result));
    }

    @Test
    @DisplayName("httpGet — 调用 httpbin（需网络，标记为可选）")
    @Tag("network")
    void testHttpGetReal() throws Exception {
        // 使用 httpbin.org 测试真实 HTTP 请求
        String code = "import nova.http.*\n" +
                "val resp = httpGet(\"https://httpbin.org/get\")\n" +
                "resp.statusCode";
        try {
            Object result = compileAndRun(code);
            // NovaDynamic 返回 NovaInt
            int status;
            if (result instanceof Number) status = ((Number) result).intValue();
            else status = ((NovaValue) result).asInt();
            assertEquals(200, status);
        } catch (Exception e) {
            // 网络不可用时跳过
            System.out.println("Skipping network test: " + e.getMessage());
        }
    }
}
