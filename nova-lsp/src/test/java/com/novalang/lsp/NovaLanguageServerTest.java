package com.novalang.lsp;

import com.google.gson.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/**
 * LSP 服务器集成测试
 *
 * <p>通过 ByteArrayInputStream/ByteArrayOutputStream 模拟 LSP 客户端，
 * 验证完整的请求-响应流程。</p>
 */
@DisplayName("NovaLanguageServer 集成测试")
class NovaLanguageServerTest {

    private static final Gson GSON = new Gson();

    // ============ 辅助方法 ============

    /**
     * 将 JSON 对象编码为 LSP 消息格式
     */
    private byte[] encode(JsonObject message) {
        String body = GSON.toJson(message);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        String header = "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] result = new byte[headerBytes.length + bodyBytes.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(bodyBytes, 0, result, headerBytes.length, bodyBytes.length);
        return result;
    }

    /**
     * 构造请求消息
     */
    private JsonObject request(int id, String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("id", id);
        msg.addProperty("method", method);
        msg.add("params", params != null ? params : new JsonObject());
        return msg;
    }

    /**
     * 构造通知消息（无 id）
     */
    private JsonObject notification(String method, JsonObject params) {
        JsonObject msg = new JsonObject();
        msg.addProperty("jsonrpc", "2.0");
        msg.addProperty("method", method);
        msg.add("params", params != null ? params : new JsonObject());
        return msg;
    }

    /**
     * 合并多条消息的字节
     */
    private byte[] concat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] arr : arrays) totalLen += arr.length;
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (byte[] arr : arrays) {
            System.arraycopy(arr, 0, result, offset, arr.length);
            offset += arr.length;
        }
        return result;
    }

    /**
     * 从输出流解析所有 JSON-RPC 消息（复用 JsonRpcTransport 避免字节/字符偏移问题）
     */
    private JsonObject[] parseAllMessages(byte[] outputBytes) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(outputBytes);
        ByteArrayOutputStream dummy = new ByteArrayOutputStream();
        JsonRpcTransport reader = new JsonRpcTransport(in, dummy);
        java.util.List<JsonObject> messages = new java.util.ArrayList<JsonObject>();
        JsonObject msg;
        while ((msg = reader.readMessage()) != null) {
            messages.add(msg);
        }
        return messages.toArray(new JsonObject[0]);
    }

    /**
     * 从消息数组中找到指定 id 的响应
     */
    private JsonObject findResponse(JsonObject[] messages, int id) {
        for (JsonObject msg : messages) {
            if (msg.has("id") && msg.get("id").getAsInt() == id) {
                return msg;
            }
        }
        return null;
    }

    /**
     * 从消息数组中找到指定 method 的通知
     */
    private JsonObject findNotification(JsonObject[] messages, String method) {
        for (JsonObject msg : messages) {
            if (msg.has("method") && method.equals(msg.get("method").getAsString())) {
                return msg;
            }
        }
        return null;
    }

    // ============ 测试 ============

    @Test
    @DisplayName("initialize 返回服务器能力")
    void testInitialize() throws IOException {
        // 构造消息序列: initialize → initialized → shutdown → exit
        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(2, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NovaLanguageServer server = new NovaLanguageServer(
                new ByteArrayInputStream(input), out);
        server.run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject initResponse = findResponse(messages, 1);

        assertThat(initResponse).isNotNull();
        JsonObject result = initResponse.getAsJsonObject("result");
        JsonObject capabilities = result.getAsJsonObject("capabilities");
        assertThat(capabilities.has("textDocumentSync")).isTrue();
        assertThat(capabilities.has("completionProvider")).isTrue();
        assertThat(capabilities.get("hoverProvider").getAsBoolean()).isTrue();
        assertThat(capabilities.get("definitionProvider").getAsBoolean()).isTrue();
        assertThat(capabilities.get("documentSymbolProvider").getAsBoolean()).isTrue();

        // 服务器信息
        JsonObject serverInfo = result.getAsJsonObject("serverInfo");
        assertThat(serverInfo.get("name").getAsString()).isEqualTo("nova-lsp");
    }

    @Test
    @DisplayName("didOpen 触发诊断推送")
    void testDidOpenPublishesDiagnostics() throws IOException {
        // 打开一个有效文件
        JsonObject didOpenParams = new JsonObject();
        JsonObject textDocument = new JsonObject();
        textDocument.addProperty("uri", "file:///test.nova");
        textDocument.addProperty("languageId", "nova");
        textDocument.addProperty("version", 1);
        textDocument.addProperty("text", "val x = 42\n");
        didOpenParams.add("textDocument", textDocument);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(request(2, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject diagNotification = findNotification(messages, "textDocument/publishDiagnostics");

        assertThat(diagNotification).isNotNull();
        JsonObject params = diagNotification.getAsJsonObject("params");
        assertThat(params.get("uri").getAsString()).isEqualTo("file:///test.nova");
        // 有效代码应无诊断
        assertThat(params.getAsJsonArray("diagnostics").size()).isEqualTo(0);
    }

    @Test
    @DisplayName("语法错误文件产生诊断")
    void testSyntaxErrorDiagnostics() throws IOException {
        JsonObject didOpenParams = new JsonObject();
        JsonObject textDocument = new JsonObject();
        textDocument.addProperty("uri", "file:///bad.nova");
        textDocument.addProperty("languageId", "nova");
        textDocument.addProperty("version", 1);
        textDocument.addProperty("text", "fun (");
        didOpenParams.add("textDocument", textDocument);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(request(2, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject diagNotification = findNotification(messages, "textDocument/publishDiagnostics");

        assertThat(diagNotification).isNotNull();
        JsonArray diagnostics = diagNotification.getAsJsonObject("params")
                .getAsJsonArray("diagnostics");
        assertThat(diagnostics.size()).isGreaterThan(0);
        assertThat(diagnostics.get(0).getAsJsonObject().get("severity").getAsInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("completion 返回补全候选")
    void testCompletion() throws IOException {
        // 先 didOpen，再请求 completion
        JsonObject didOpenParams = new JsonObject();
        JsonObject textDoc = new JsonObject();
        textDoc.addProperty("uri", "file:///test.nova");
        textDoc.addProperty("languageId", "nova");
        textDoc.addProperty("version", 1);
        textDoc.addProperty("text", "va");
        didOpenParams.add("textDocument", textDoc);

        JsonObject completionParams = new JsonObject();
        JsonObject textDocId = new JsonObject();
        textDocId.addProperty("uri", "file:///test.nova");
        completionParams.add("textDocument", textDocId);
        JsonObject position = new JsonObject();
        position.addProperty("line", 0);
        position.addProperty("character", 2);
        completionParams.add("position", position);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(request(3, "textDocument/completion", completionParams)),
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject completionResponse = findResponse(messages, 3);

        assertThat(completionResponse).isNotNull();
        JsonArray items = completionResponse.getAsJsonArray("result");
        assertThat(items.size()).isGreaterThan(0);

        // 应包含 val 和 var
        boolean hasVal = false, hasVar = false;
        for (int i = 0; i < items.size(); i++) {
            String label = items.get(i).getAsJsonObject().get("label").getAsString();
            if ("val".equals(label)) hasVal = true;
            if ("var".equals(label)) hasVar = true;
        }
        assertThat(hasVal).isTrue();
        assertThat(hasVar).isTrue();
    }

    @Test
    @DisplayName("shutdown 返回 null 结果")
    void testShutdown() throws IOException {
        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(2, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject shutdownResponse = findResponse(messages, 2);

        assertThat(shutdownResponse).isNotNull();
        assertThat(shutdownResponse.has("error")).isFalse();
        // result 字段必须存在（JSON-RPC 2.0 要求），值为 null
        assertThat(shutdownResponse.has("result"))
                .as("响应必须包含 result 字段（JSON-RPC 2.0 规范）")
                .isTrue();
        assertThat(shutdownResponse.get("result").isJsonNull()).isTrue();
    }

    @Test
    @DisplayName("未支持的方法返回错误")
    void testUnsupportedMethod() throws IOException {
        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(5, "textDocument/nonexistent", new JsonObject())),
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject errorResponse = findResponse(messages, 5);

        assertThat(errorResponse).isNotNull();
        assertThat(errorResponse.has("error")).isTrue();
        assertThat(errorResponse.getAsJsonObject("error").get("code").getAsInt())
                .isEqualTo(-32601);
    }

    @Test
    @DisplayName("didClose 清除诊断")
    void testDidCloseClearsDiagnostics() throws IOException {
        JsonObject didOpenParams = new JsonObject();
        JsonObject textDoc = new JsonObject();
        textDoc.addProperty("uri", "file:///test.nova");
        textDoc.addProperty("languageId", "nova");
        textDoc.addProperty("version", 1);
        textDoc.addProperty("text", "val x = 1");
        didOpenParams.add("textDocument", textDoc);

        JsonObject didCloseParams = new JsonObject();
        JsonObject closeTextDoc = new JsonObject();
        closeTextDoc.addProperty("uri", "file:///test.nova");
        didCloseParams.add("textDocument", closeTextDoc);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(notification("textDocument/didClose", didCloseParams)),
                encode(request(2, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());

        // 应该有两次 publishDiagnostics: didOpen 一次，didClose 清除一次
        int diagCount = 0;
        JsonObject lastDiag = null;
        for (JsonObject msg : messages) {
            if (msg.has("method") && "textDocument/publishDiagnostics".equals(
                    msg.get("method").getAsString())) {
                diagCount++;
                lastDiag = msg;
            }
        }
        assertThat(diagCount).isEqualTo(2);
        // 最后一次 diagnostics 应为空数组（关闭时清除）
        assertThat(lastDiag.getAsJsonObject("params")
                .getAsJsonArray("diagnostics").size()).isEqualTo(0);
    }

    @Test
    @DisplayName("formatting 返回格式化结果")
    void testFormatting() throws IOException {
        JsonObject didOpenParams = new JsonObject();
        JsonObject textDoc = new JsonObject();
        textDoc.addProperty("uri", "file:///test.nova");
        textDoc.addProperty("languageId", "nova");
        textDoc.addProperty("version", 1);
        textDoc.addProperty("text", "fun   add( a:Int,b:Int )=a+b\n");
        didOpenParams.add("textDocument", textDoc);

        JsonObject fmtParams = new JsonObject();
        JsonObject fmtTextDoc = new JsonObject();
        fmtTextDoc.addProperty("uri", "file:///test.nova");
        fmtParams.add("textDocument", fmtTextDoc);
        JsonObject options = new JsonObject();
        options.addProperty("tabSize", 4);
        options.addProperty("insertSpaces", true);
        fmtParams.add("options", options);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(request(3, "textDocument/formatting", fmtParams)),
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject fmtResponse = findResponse(messages, 3);

        assertThat(fmtResponse).isNotNull();
        // 结果应该是 TextEdit 数组
        JsonArray edits = fmtResponse.getAsJsonArray("result");
        assertThat(edits).isNotNull();
    }

    // ============ 异常处理 / 边界条件测试 ============

    @Test
    @DisplayName("请求处理异常时返回 error 响应而非挂起")
    void testHandlerExceptionReturnsErrorResponse() throws IOException {
        // 发送 hover 请求但 params 缺少 textDocument 字段
        JsonObject badParams = new JsonObject();
        // 故意不放 textDocument，触发参数校验错误

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(3, "textDocument/hover", badParams)),
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject hoverResponse = findResponse(messages, 3);

        // 关键断言：响应必须存在，且包含 result 或 error
        assertThat(hoverResponse).isNotNull();
        boolean hasResult = hoverResponse.has("result");
        boolean hasError = hoverResponse.has("error");
        assertThat(hasResult || hasError)
                .as("响应必须包含 result 或 error 字段")
                .isTrue();
        // 缺少必需参数应返回 -32602 Invalid Params
        assertThat(hasError).isTrue();
        assertThat(hoverResponse.getAsJsonObject("error").get("code").getAsInt())
                .isEqualTo(-32602);
    }

    @Test
    @DisplayName("异常后服务器继续正常处理后续请求")
    void testServerContinuesAfterException() throws IOException {
        // 先发一个会引发异常的请求，再发一个正常请求
        JsonObject badParams = new JsonObject();
        // 缺少 textDocument 字段

        JsonObject didOpenParams = new JsonObject();
        JsonObject textDoc = new JsonObject();
        textDoc.addProperty("uri", "file:///test.nova");
        textDoc.addProperty("languageId", "nova");
        textDoc.addProperty("version", 1);
        textDoc.addProperty("text", "val x = 1\n");
        didOpenParams.add("textDocument", textDoc);

        JsonObject hoverParams = new JsonObject();
        JsonObject hoverTextDoc = new JsonObject();
        hoverTextDoc.addProperty("uri", "file:///test.nova");
        hoverParams.add("textDocument", hoverTextDoc);
        JsonObject position = new JsonObject();
        position.addProperty("line", 0);
        position.addProperty("character", 0);
        hoverParams.add("position", position);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(2, "textDocument/hover", badParams)),     // 异常请求
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(request(3, "textDocument/hover", hoverParams)),   // 正常请求
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());

        // 异常请求应返回 error
        JsonObject errorResponse = findResponse(messages, 2);
        assertThat(errorResponse).isNotNull();
        assertThat(errorResponse.has("error")).isTrue();

        // 正常请求应返回 result
        JsonObject normalResponse = findResponse(messages, 3);
        assertThat(normalResponse).isNotNull();
        assertThat(normalResponse.has("result"))
                .as("异常后的正常请求应正确返回 result")
                .isTrue();
    }

    @Test
    @DisplayName("definition 请求异常时返回 error 响应")
    void testDefinitionExceptionReturnsError() throws IOException {
        // 缺少 position 字段
        JsonObject badParams = new JsonObject();
        JsonObject textDocId = new JsonObject();
        textDocId.addProperty("uri", "file:///test.nova");
        badParams.add("textDocument", textDocId);
        // 故意不放 position

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(3, "textDocument/definition", badParams)),
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject response = findResponse(messages, 3);

        assertThat(response).isNotNull();
        assertThat(response.has("result") || response.has("error"))
                .as("响应必须包含 result 或 error 字段")
                .isTrue();
    }

    @Test
    @DisplayName("hover 正常流程返回 result")
    void testHoverNormalResponse() throws IOException {
        JsonObject didOpenParams = new JsonObject();
        JsonObject textDoc = new JsonObject();
        textDoc.addProperty("uri", "file:///test.nova");
        textDoc.addProperty("languageId", "nova");
        textDoc.addProperty("version", 1);
        textDoc.addProperty("text", "val hello = 42\n");
        didOpenParams.add("textDocument", textDoc);

        JsonObject hoverParams = new JsonObject();
        JsonObject hoverTextDoc = new JsonObject();
        hoverTextDoc.addProperty("uri", "file:///test.nova");
        hoverParams.add("textDocument", hoverTextDoc);
        JsonObject position = new JsonObject();
        position.addProperty("line", 0);
        position.addProperty("character", 0); // 指向 "val" 关键词
        hoverParams.add("position", position);

        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(notification("textDocument/didOpen", didOpenParams)),
                encode(request(3, "textDocument/hover", hoverParams)),
                encode(request(9, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject hoverResponse = findResponse(messages, 3);

        assertThat(hoverResponse).isNotNull();
        assertThat(hoverResponse.has("result")).isTrue();
        // "val" 是关键词，应有悬停信息
        JsonObject result = hoverResponse.getAsJsonObject("result");
        assertThat(result).isNotNull();
        assertThat(result.has("contents")).isTrue();
    }

    @Test
    @DisplayName("initialize 返回 documentFormattingProvider 能力")
    void testInitializeIncludesFormattingCapability() throws IOException {
        byte[] input = concat(
                encode(request(1, "initialize", new JsonObject())),
                encode(notification("initialized", null)),
                encode(request(2, "shutdown", null)),
                encode(notification("exit", null))
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new NovaLanguageServer(new ByteArrayInputStream(input), out).run();

        JsonObject[] messages = parseAllMessages(out.toByteArray());
        JsonObject initResponse = findResponse(messages, 1);
        JsonObject capabilities = initResponse.getAsJsonObject("result")
                .getAsJsonObject("capabilities");

        assertThat(capabilities.get("documentFormattingProvider").getAsBoolean()).isTrue();
    }
}
