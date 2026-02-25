package com.novalang.lsp;

import com.google.gson.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

@DisplayName("JsonRpcTransport 测试")
class JsonRpcTransportTest {

    private static final Gson GSON = new Gson();

    /**
     * 构建 LSP 格式的消息字节（Content-Length header + \r\n\r\n + body）
     */
    private byte[] buildMessage(String jsonBody) {
        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, result, headerBytes.length, bodyBytes.length);
        return result;
    }

    // ============ 读取消息 ============

    @Nested
    @DisplayName("readMessage")
    class ReadMessage {

        @Test
        @DisplayName("读取标准 JSON-RPC 请求")
        void testReadRequest() throws IOException {
            String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
            ByteArrayInputStream in = new ByteArrayInputStream(buildMessage(json));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonObject msg = transport.readMessage();

            assertThat(msg).isNotNull();
            assertThat(msg.get("jsonrpc").getAsString()).isEqualTo("2.0");
            assertThat(msg.get("id").getAsInt()).isEqualTo(1);
            assertThat(msg.get("method").getAsString()).isEqualTo("initialize");
        }

        @Test
        @DisplayName("读取通知（无 id）")
        void testReadNotification() throws IOException {
            String json = "{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}";
            ByteArrayInputStream in = new ByteArrayInputStream(buildMessage(json));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonObject msg = transport.readMessage();

            assertThat(msg).isNotNull();
            assertThat(msg.get("method").getAsString()).isEqualTo("initialized");
            assertThat(msg.get("id")).isNull();
        }

        @Test
        @DisplayName("空输入流返回 null")
        void testEmptyStream() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            assertThat(transport.readMessage()).isNull();
        }

        @Test
        @DisplayName("连续读取多条消息")
        void testReadMultipleMessages() throws IOException {
            String json1 = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"m1\"}";
            String json2 = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"m2\"}";
            byte[] msg1 = buildMessage(json1);
            byte[] msg2 = buildMessage(json2);
            byte[] combined = new byte[msg1.length + msg2.length];
            System.arraycopy(msg1, 0, combined, 0, msg1.length);
            System.arraycopy(msg2, 0, combined, msg1.length, msg2.length);

            ByteArrayInputStream in = new ByteArrayInputStream(combined);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonObject first = transport.readMessage();
            JsonObject second = transport.readMessage();

            assertThat(first.get("id").getAsInt()).isEqualTo(1);
            assertThat(second.get("id").getAsInt()).isEqualTo(2);
        }

        @Test
        @DisplayName("处理包含中文的 JSON body")
        void testReadUnicodeContent() throws IOException {
            String json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test\",\"params\":{\"text\":\"你好世界\"}}";
            ByteArrayInputStream in = new ByteArrayInputStream(buildMessage(json));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonObject msg = transport.readMessage();
            assertThat(msg.getAsJsonObject("params").get("text").getAsString())
                    .isEqualTo("你好世界");
        }
    }

    // ============ 发送消息 ============

    @Nested
    @DisplayName("sendResponse / sendError / sendNotification")
    class SendMessage {

        @Test
        @DisplayName("发送响应保持数值 id 类型")
        void testSendResponseWithNumericId() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonElement id = new JsonPrimitive(42);
            JsonObject result = new JsonObject();
            result.addProperty("name", "nova-lsp");
            transport.sendResponse(id, result);

            JsonObject response = parseOutput(out);
            assertThat(response.get("jsonrpc").getAsString()).isEqualTo("2.0");
            assertThat(response.get("id").getAsInt()).isEqualTo(42);
            assertThat(response.getAsJsonObject("result").get("name").getAsString())
                    .isEqualTo("nova-lsp");
        }

        @Test
        @DisplayName("发送响应保持字符串 id 类型")
        void testSendResponseWithStringId() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonElement id = new JsonPrimitive("req-abc");
            transport.sendResponse(id, JsonNull.INSTANCE);

            JsonObject response = parseOutput(out);
            assertThat(response.get("id").getAsString()).isEqualTo("req-abc");
        }

        @Test
        @DisplayName("发送错误响应")
        void testSendError() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonElement id = new JsonPrimitive(1);
            transport.sendError(id, -32601, "Method not found");

            JsonObject response = parseOutput(out);
            assertThat(response.get("id").getAsInt()).isEqualTo(1);
            JsonObject error = response.getAsJsonObject("error");
            assertThat(error.get("code").getAsInt()).isEqualTo(-32601);
            assertThat(error.get("message").getAsString()).isEqualTo("Method not found");
        }

        @Test
        @DisplayName("发送通知（无 id）")
        void testSendNotification() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            JsonObject params = new JsonObject();
            params.addProperty("uri", "file:///test.nova");
            params.add("diagnostics", new JsonArray());
            transport.sendNotification("textDocument/publishDiagnostics", params);

            JsonObject notification = parseOutput(out);
            assertThat(notification.get("method").getAsString())
                    .isEqualTo("textDocument/publishDiagnostics");
            assertThat(notification.has("id")).isFalse();
            assertThat(notification.getAsJsonObject("params").get("uri").getAsString())
                    .isEqualTo("file:///test.nova");
        }

        @Test
        @DisplayName("输出格式包含正确的 Content-Length header")
        void testOutputFormat() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            JsonRpcTransport transport = new JsonRpcTransport(in, out);

            transport.sendResponse(new JsonPrimitive(1), new JsonObject());

            String raw = out.toString("UTF-8");
            assertThat(raw).startsWith("Content-Length: ");
            assertThat(raw).contains("\r\n\r\n");

            // 验证 Content-Length 值与实际 body 长度匹配
            int headerEnd = raw.indexOf("\r\n\r\n");
            String header = raw.substring(0, headerEnd);
            String body = raw.substring(headerEnd + 4);
            int declaredLength = Integer.parseInt(
                    header.replace("Content-Length: ", "").trim());
            assertThat(body.getBytes(StandardCharsets.UTF_8).length).isEqualTo(declaredLength);
        }
    }

    /**
     * 从输出流解析 JSON-RPC 消息（跳过 Content-Length header）
     */
    private JsonObject parseOutput(ByteArrayOutputStream out) {
        String raw = new String(out.toByteArray(), StandardCharsets.UTF_8);
        int bodyStart = raw.indexOf("\r\n\r\n") + 4;
        String body = raw.substring(bodyStart);
        return GSON.fromJson(body, JsonObject.class);
    }
}
