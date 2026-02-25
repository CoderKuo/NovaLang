package com.novalang.lsp;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * JSON-RPC 2.0 传输层
 *
 * <p>通过 stdin/stdout 实现 LSP 协议的消息读写。</p>
 */
public class JsonRpcTransport {
    private final InputStream input;
    private final OutputStream output;
    private final Gson gson;

    public JsonRpcTransport(InputStream input, OutputStream output) {
        this.input = input;
        this.output = output;
        this.gson = new GsonBuilder().serializeNulls().create();
    }

    /**
     * 读取一条 JSON-RPC 消息
     *
     * @return 解析后的 JSON 对象，如果流结束则返回 null
     */
    public JsonObject readMessage() throws IOException {
        // 读取 header
        int contentLength = -1;
        String line;
        while ((line = readLine()) != null) {
            if (line.isEmpty()) {
                break; // header 和 body 之间的空行
            }
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring("Content-Length:".length()).trim());
            }
            // 忽略其他 header（如 Content-Type）
        }

        if (contentLength < 0) {
            return null; // 流结束
        }

        // 读取 body
        byte[] bodyBytes = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = input.read(bodyBytes, offset, contentLength - offset);
            if (read < 0) {
                return null;
            }
            offset += read;
        }

        String body = new String(bodyBytes, StandardCharsets.UTF_8);
        return gson.fromJson(body, JsonObject.class);
    }

    /**
     * 发送 JSON-RPC 响应
     */
    public void sendResponse(JsonElement id, JsonElement result) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        writeMessage(response);
    }

    /**
     * 发送 JSON-RPC 错误响应
     */
    public void sendError(JsonElement id, int code, String message) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        writeMessage(response);
    }

    /**
     * 发送 JSON-RPC 通知（无 id）
     */
    public void sendNotification(String method, JsonElement params) throws IOException {
        JsonObject notification = new JsonObject();
        notification.addProperty("jsonrpc", "2.0");
        notification.addProperty("method", method);
        notification.add("params", params);
        writeMessage(notification);
    }

    private synchronized void writeMessage(JsonObject message) throws IOException {
        String body = gson.toJson(message);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        String header = "Content-Length: " + bodyBytes.length + "\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));
        output.write(bodyBytes);
        output.flush();
    }

    /**
     * 从输入流读取一行（以 \r\n 结尾）
     */
    private String readLine() throws IOException {
        StringBuilder sb = new StringBuilder();
        int prev = -1;
        while (true) {
            int c = input.read();
            if (c < 0) {
                return sb.length() > 0 ? sb.toString() : null;
            }
            if (c == '\n' && prev == '\r') {
                // 去掉末尾的 \r
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
            sb.append((char) c);
            prev = c;
        }
    }

    public Gson getGson() {
        return gson;
    }
}
