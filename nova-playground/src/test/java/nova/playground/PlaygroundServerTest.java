package nova.playground;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlaygroundServer 集成测试。
 */
class PlaygroundServerTest {

    private static PlaygroundServer server;
    private static String baseUrl;

    @BeforeAll
    static void setUp() {
        server = new PlaygroundServer(0); // 随机端口
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void healthCheck() throws Exception {
        HttpURLConnection conn = get("/api/health");
        assertThat(conn.getResponseCode()).isEqualTo(200);
        String body = readBody(conn);
        assertThat(body).contains("\"status\"").contains("\"ok\"");
    }

    @Test
    void executeSimpleCode() throws Exception {
        String json = "{\"code\": \"1 + 2\"}";
        HttpURLConnection conn = post("/api/execute", json);
        assertThat(conn.getResponseCode()).isEqualTo(200);
        String body = readBody(conn);
        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("\"result\":\"3\"");
    }

    @Test
    void executePrintln() throws Exception {
        String json = "{\"code\": \"println(\\\"Hello Nova\\\")\"}";
        HttpURLConnection conn = post("/api/execute", json);
        String body = readBody(conn);
        assertThat(body).contains("\"success\":true");
        assertThat(body).contains("Hello Nova");
    }

    @Test
    void executeWithError() throws Exception {
        String json = "{\"code\": \"undefinedVariable\"}";
        HttpURLConnection conn = post("/api/execute", json);
        String body = readBody(conn);
        assertThat(body).contains("\"success\":false");
        assertThat(body).contains("\"error\"");
    }

    @Test
    void emptyCodeReturns400() throws Exception {
        String json = "{\"code\": \"\"}";
        HttpURLConnection conn = post("/api/execute", json);
        assertThat(conn.getResponseCode()).isEqualTo(400);
    }

    @Test
    void missingCodeReturns400() throws Exception {
        String json = "{}";
        HttpURLConnection conn = post("/api/execute", json);
        assertThat(conn.getResponseCode()).isEqualTo(400);
    }

    // ── HTTP helpers ──

    private HttpURLConnection get(String path) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private HttpURLConnection post(String path, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(15000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return conn;
    }

    private String readBody(HttpURLConnection conn) throws Exception {
        InputStream is = conn.getResponseCode() < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        }
    }
}
