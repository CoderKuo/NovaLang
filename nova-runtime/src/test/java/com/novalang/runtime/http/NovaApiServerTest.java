package com.novalang.runtime.http;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;

/**
 * NovaApiServer HTTP 端点集成测试。
 * 在随机端口启动 NanoHTTPD 服务，通过 HttpURLConnection 验证各 API 响应。
 */
class NovaApiServerTest {

    private static NovaApiServer server;
    private static int port;

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    static void startServer() throws Exception {
        port = findFreePort();
        server = new NovaApiServer(port);
        server.start();
        // 确保服务已就绪
        Thread.sleep(200);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        // 清理 shared() 注册的测试数据
        NovaRuntime.shared().clearAll();
    }

    // ============ HTTP 工具方法 ============

    private String httpGet(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private int httpGetStatus(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        return conn.getResponseCode();
    }

    private String httpGetError(String path) throws Exception {
        URL url = new URL("http://localhost:" + port + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        int code = conn.getResponseCode();
        InputStream is = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    // ============ /api/health ============

    @Test
    void healthEndpointReturnsOk() throws Exception {
        String body = httpGet("/api/health");
        assertEquals("{\"status\":\"ok\"}", body);
    }

    @Test
    void healthEndpointReturnsJsonContentType() throws Exception {
        URL url = new URL("http://localhost:" + port + "/api/health");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        String contentType = conn.getContentType();
        assertTrue(contentType.contains("application/json"),
                "Content-Type should be application/json, got: " + contentType);
        conn.disconnect();
    }

    @Test
    void healthEndpointHasCorsHeaders() throws Exception {
        URL url = new URL("http://localhost:" + port + "/api/health");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.getResponseCode(); // trigger response
        String cors = conn.getHeaderField("Access-Control-Allow-Origin");
        assertEquals("*", cors, "CORS header should be *");
        conn.disconnect();
    }

    // ============ /api/functions ============

    @Test
    void functionsEndpointReturnsRegisteredFunction() throws Exception {
        NovaRuntime.shared().register("testApiFunc", (Function1<Object, Object>) x -> "hello " + x);

        String body = httpGet("/api/functions");
        assertTrue(body.contains("\"name\":\"testApiFunc\""),
                "Should contain registered function name, got: " + body);
        assertTrue(body.contains("\"scope\":\"shared\""),
                "Shared functions should have scope=shared");
    }

    @Test
    void functionsEndpointWithNamespaceFilter() throws Exception {
        NovaRuntime.shared().register("nsFunc", (Function0<Object>) () -> "value", "testNs");

        // 按命名空间查询
        String filtered = httpGet("/api/functions?ns=testNs");
        assertTrue(filtered.contains("\"name\":\"nsFunc\""),
                "Should contain namespaced function, got: " + filtered);
        assertTrue(filtered.contains("\"namespace\":\"testNs\""),
                "Should include namespace field");

        // 不存在的命名空间返回空数组
        String empty = httpGet("/api/functions?ns=nonExistent");
        // 可能包含 stdlib 函数，但不包含 nsFunc 的命名空间过滤结果
        assertFalse(empty.contains("\"namespace\":\"testNs\""),
                "Non-existent ns should not contain testNs entries");
    }

    @Test
    void functionsEndpointIncludesArity() throws Exception {
        NovaRuntime.shared().register("arityTestFunc",
                (Function2<Object, Object, Object>) (a, b) -> a.toString() + b);

        String body = httpGet("/api/functions");
        assertTrue(body.contains("\"name\":\"arityTestFunc\""), "Should contain function");
        // Function2 注册时 paramTypes 长度为 2
        assertTrue(body.contains("\"arity\":2"), "Should report arity=2 for Function2");
    }

    // ============ /api/variables ============

    @Test
    void variablesEndpointReturnsRegisteredVariable() throws Exception {
        NovaRuntime.shared().set("testApiVar", "hello");

        String body = httpGet("/api/variables");
        assertTrue(body.contains("\"name\":\"testApiVar\""),
                "Should contain registered variable, got: " + body);
        assertTrue(body.contains("\"scope\":\"shared\""),
                "Variable from shared() should have scope=shared");
    }

    @Test
    void variablesEndpointShowsValueType() throws Exception {
        NovaRuntime.shared().set("intVar", 42);

        String body = httpGet("/api/variables");
        assertTrue(body.contains("\"name\":\"intVar\""),
                "Should contain intVar, got: " + body);
        assertTrue(body.contains("\"type\":\"Integer\""),
                "Should report type as Integer");
    }

    // ============ /api/extensions ============

    @Test
    void extensionsEndpointReturnsRegisteredExtension() throws Exception {
        NovaRuntime.shared().registerExt(String.class, "shout",
                (Function1<String, Object>) s -> s.toUpperCase() + "!");

        String body = httpGet("/api/extensions");
        assertTrue(body.contains("\"name\":\"shout\""),
                "Should contain extension method, got: " + body);
        assertTrue(body.contains("\"targetType\":\"String\""),
                "Should show target type as String");
        assertTrue(body.contains("\"scope\":\"shared\""),
                "Extension from shared() should have scope=shared");
    }

    @Test
    void extensionsEndpointFiltersByType() throws Exception {
        NovaRuntime.shared().registerExt(Integer.class, "double",
                (Function1<Integer, Object>) n -> n * 2);

        // 过滤 Integer 类型
        String filtered = httpGet("/api/extensions?type=Integer");
        assertTrue(filtered.contains("\"name\":\"double\""),
                "Should contain Integer extension, got: " + filtered);

        // 过滤不存在的类型
        String other = httpGet("/api/extensions?type=NoSuchType");
        assertFalse(other.contains("\"name\":\"double\""),
                "Should not contain Integer extension when filtering by NoSuchType");
    }

    // ============ /api/namespaces ============

    @Test
    void namespacesEndpointListsRegisteredNamespaces() throws Exception {
        NovaRuntime.shared().register("nsListFunc", (Function0<Object>) () -> "v", "myTestNs");

        String body = httpGet("/api/namespaces");
        assertTrue(body.contains("\"myTestNs\""),
                "Should list registered namespace, got: " + body);
    }

    // ============ /api/describe ============

    @Test
    void describeEndpointReturnsDetailsForFunction() throws Exception {
        NovaRuntime.shared().register("descTestFunc", (Function1<Object, Object>) x -> x);

        String body = httpGet("/api/describe?name=descTestFunc");
        assertTrue(body.contains("\"name\":\"descTestFunc\""), "Should include name");
        assertTrue(body.contains("\"isFunction\":true"), "Should be marked as function");
        assertTrue(body.contains("\"scope\":\"shared\""), "Should be from shared scope");
    }

    @Test
    void describeEndpointReturnsDetailsForVariable() throws Exception {
        NovaRuntime.shared().set("descTestVar", "value123");

        String body = httpGet("/api/describe?name=descTestVar");
        assertTrue(body.contains("\"name\":\"descTestVar\""), "Should include name");
        assertTrue(body.contains("\"isFunction\":false"), "Should be marked as variable");
    }

    @Test
    void describeEndpointMissingNameReturns400() throws Exception {
        int status = httpGetStatus("/api/describe");
        assertEquals(400, status, "Missing 'name' param should return 400");
    }

    @Test
    void describeEndpointNotFoundReturns404() throws Exception {
        int status = httpGetStatus("/api/describe?name=noSuchEntry99999");
        assertEquals(404, status, "Unknown name should return 404");
    }

    // ============ /api/completions ============

    @Test
    void completionsEndpointFiltersByPrefix() throws Exception {
        NovaRuntime.shared().register("completionGetPlayer",
                (Function1<Object, Object>) x -> x);
        NovaRuntime.shared().register("completionSetPlayer",
                (Function1<Object, Object>) x -> x);

        String body = httpGet("/api/completions?prefix=completionGet");
        assertTrue(body.contains("\"label\":\"completionGetPlayer\""),
                "Should include function starting with prefix, got: " + body);
        assertFalse(body.contains("\"label\":\"completionSetPlayer\""),
                "Should not include function not matching prefix");
    }

    @Test
    void completionsEndpointEmptyPrefixReturnsAll() throws Exception {
        NovaRuntime.shared().register("compAllTest", (Function0<Object>) () -> "x");

        String body = httpGet("/api/completions?prefix=");
        assertTrue(body.contains("\"label\":\"compAllTest\""),
                "Empty prefix should include all entries");
    }

    // ============ /api/members ============

    @Test
    void membersEndpointReflectsJavaMethods() throws Exception {
        // 注册一个 String 变量，反射其方法
        NovaRuntime.shared().set("memberTestStr", "hello");

        String body = httpGet("/api/members?name=memberTestStr&prefix=length");
        assertTrue(body.contains("\"label\":\"length\""),
                "Should reflect String.length(), got: " + body);
        assertTrue(body.contains("\"kind\":\"method\""),
                "Should be marked as method");
    }

    @Test
    void membersEndpointWithTypeParam() throws Exception {
        String body = httpGet("/api/members?type=java.lang.String&prefix=sub");
        assertTrue(body.contains("\"label\":\"substring\""),
                "Should reflect String.substring() via type param, got: " + body);
    }

    @Test
    void membersEndpointNotFoundReturns404() throws Exception {
        int status = httpGetStatus("/api/members?name=noSuchVar12345");
        assertEquals(404, status, "Unknown variable name should return 404");
    }

    // ============ /api/resolve-type ============

    @Test
    void resolveTypeEndpointWithDirectQuery() throws Exception {
        String body = httpGet("/api/resolve-type?type=java.lang.String&method=length");
        assertTrue(body.contains("\"simpleName\":\"int\""),
                "String.length() should return int, got: " + body);
    }

    @Test
    void resolveTypeEndpointNotFoundReturns404() throws Exception {
        int status = httpGetStatus("/api/resolve-type?type=no.such.Class&method=foo");
        assertEquals(404, status, "Unknown type should return 404");
    }

    // ============ 404 未知路径 ============

    @Test
    void unknownPathReturns404() throws Exception {
        int status = httpGetStatus("/api/nonexistent");
        assertEquals(404, status, "Unknown path should return 404");
    }

    @Test
    void unknownPathReturnsErrorJson() throws Exception {
        String body = httpGetError("/api/nonexistent");
        assertTrue(body.contains("\"error\""),
                "404 response should contain error field, got: " + body);
    }

    // ============ 服务生命周期 ============

    @Test
    void serverIsAliveAfterStart() {
        assertTrue(server.isAlive(), "Server should be alive after start()");
    }

    @Test
    void serverStopAndRestart() throws Exception {
        int lifecyclePort = findFreePort();
        NovaApiServer tempServer = new NovaApiServer(lifecyclePort);
        tempServer.start();
        assertTrue(tempServer.isAlive(), "Temp server should be alive");

        // 验证能响应请求
        URL url = new URL("http://localhost:" + lifecyclePort + "/api/health");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();

        // 停止
        tempServer.stop();
        assertFalse(tempServer.isAlive(), "Temp server should not be alive after stop()");

        // 停止后连接应失败
        try {
            URL url2 = new URL("http://localhost:" + lifecyclePort + "/api/health");
            HttpURLConnection conn2 = (HttpURLConnection) url2.openConnection();
            conn2.setConnectTimeout(1000);
            conn2.getResponseCode();
            fail("Should not be able to connect after stop()");
        } catch (IOException expected) {
            // 预期：连接被拒绝
        }
    }

    // ============ /api/contexts ============

    @Test
    void contextsEndpointReturnsEmptyByDefault() throws Exception {
        NovaContextRegistry.clear();
        String body = httpGet("/api/contexts");
        assertEquals("[]", body, "Should return empty array when no contexts registered");
    }

    // ============ /api/java-classes ============

    @Test
    void javaClassesEndpointReturnsArray() throws Exception {
        // classNameCache 可能尚未就绪（后台扫描），但端点应返回有效 JSON
        String body = httpGet("/api/java-classes?prefix=java.lang.&limit=5");
        assertTrue(body.startsWith("["), "Should return JSON array, got: " + body);
    }
}
