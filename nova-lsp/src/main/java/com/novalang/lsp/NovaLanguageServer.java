package com.novalang.lsp;

import com.google.gson.*;

import static com.novalang.lsp.LspConstants.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/**
 * NovaLang Language Server
 *
 * <p>实现 LSP 协议，提供代码智能功能。通过 stdin/stdout 与编辑器通信。</p>
 *
 * <p>支持的功能：</p>
 * <ul>
 *   <li>语法错误诊断</li>
 *   <li>关键词 + 符号补全</li>
 *   <li>悬停信息</li>
 *   <li>跳转定义</li>
 *   <li>文档符号</li>
 *   <li>代码格式化</li>
 *   <li>签名帮助</li>
 *   <li>查找引用</li>
 *   <li>文档高亮</li>
 *   <li>重命名</li>
 *   <li>代码操作</li>
 *   <li>折叠范围</li>
 *   <li>语义令牌</li>
 *   <li>内嵌提示</li>
 *   <li>工作区符号</li>
 * </ul>
 */
public class NovaLanguageServer {
    private static final Logger LOG = Logger.getLogger(NovaLanguageServer.class.getName());

    /** LSP 标准错误码 */
    private static final int ERR_INVALID_REQUEST = -32600;
    private static final int ERR_METHOD_NOT_FOUND = -32601;
    private static final int ERR_INVALID_PARAMS = -32602;
    private static final int ERR_INTERNAL = -32603;
    private static final int ERR_REQUEST_CANCELLED = -32800;

    private final JsonRpcTransport transport;
    private final DocumentManager documents;
    private final NovaAnalyzer analyzer;
    private boolean initialized = false;
    private boolean running = true;

    /** 异步请求线程池 */
    private final ExecutorService requestPool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "nova-lsp-request");
        t.setDaemon(true);
        return t;
    });

    /** 待处理的异步请求（id -> Future），用于取消追踪 */
    private final Map<String, Future<?>> pendingRequests = new ConcurrentHashMap<>();

    public NovaLanguageServer(InputStream input, OutputStream output) {
        this.transport = new JsonRpcTransport(input, output);
        this.documents = new DocumentManager();
        this.analyzer = new NovaAnalyzer(documents);

        // debounce 分析完成后异步发布诊断 + 更新项目索引
        this.documents.setAnalysisCallback((uri, content) -> {
            try {
                publishDiagnostics(uri, content);
                analyzer.updateProjectIndex(uri);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "发布诊断失败: " + uri, e);
            }
        });
    }

    /**
     * 启动服务器主循环
     */
    public void run() {
        LOG.info("NovaLang LSP 服务器启动");

        while (running) {
            JsonObject message = null;
            try {
                message = transport.readMessage();
                if (message == null) {
                    break; // 流结束
                }
                handleMessage(message);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "处理消息时出错", e);
                // 对带有 id 的请求发送错误响应，确保客户端不会挂起
                if (message != null && message.has("id")) {
                    try {
                        String errMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getName();
                        transport.sendError(message.get("id"), ERR_INTERNAL, "Internal error: " + errMsg);
                    } catch (IOException ioEx) {
                        LOG.log(Level.SEVERE, "发送错误响应失败", ioEx);
                    }
                }
            }
        }

        requestPool.shutdown();
        documents.shutdown();
        try {
            if (!requestPool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                requestPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            requestPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOG.info("NovaLang LSP 服务器关闭");
    }

    private void handleMessage(JsonObject message) throws IOException {
        String method = message.has("method") ? message.get("method").getAsString() : null;
        JsonElement id = message.get("id");

        // 无 method 的消息：如果有 id 则为无效请求，否则忽略（可能是响应）
        if (method == null) {
            if (id != null) {
                transport.sendError(id, ERR_INVALID_REQUEST, "Missing 'method' field");
            }
            return;
        }

        switch (method) {
            // === 生命周期 ===
            case "initialize":
                handleInitialize(id, message.getAsJsonObject("params"));
                break;
            case "initialized":
                initialized = true;
                break;
            case "shutdown":
                handleShutdown(id);
                break;
            case "exit":
                running = false;
                break;

            // === 通知（同步处理，保证顺序） ===
            case "textDocument/didOpen":
                handleDidOpen(message.getAsJsonObject("params"));
                break;
            case "textDocument/didChange":
                handleDidChange(message.getAsJsonObject("params"));
                break;
            case "textDocument/didClose":
                handleDidClose(message.getAsJsonObject("params"));
                break;
            case "$/cancelRequest":
                handleCancelRequest(message.getAsJsonObject("params"));
                break;

            // === 请求（异步处理） ===
            case "textDocument/completion":
                submitAsync(id, () -> handleCompletion(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/hover":
                submitAsync(id, () -> handleHover(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/definition":
                submitAsync(id, () -> handleDefinition(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/documentSymbol":
                submitAsync(id, () -> handleDocumentSymbol(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/formatting":
                submitAsync(id, () -> handleFormatting(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/signatureHelp":
                submitAsync(id, () -> handleSignatureHelp(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/references":
                submitAsync(id, () -> handleReferences(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/documentHighlight":
                submitAsync(id, () -> handleDocumentHighlight(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/prepareRename":
                submitAsync(id, () -> handlePrepareRename(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/rename":
                submitAsync(id, () -> handleRename(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/codeAction":
                submitAsync(id, () -> handleCodeAction(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/foldingRange":
                submitAsync(id, () -> handleFoldingRange(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/semanticTokens/full":
                submitAsync(id, () -> handleSemanticTokensFull(id, message.getAsJsonObject("params")));
                break;
            case "textDocument/inlayHint":
                submitAsync(id, () -> handleInlayHint(id, message.getAsJsonObject("params")));
                break;
            case "workspace/symbol":
                submitAsync(id, () -> handleWorkspaceSymbol(id, message.getAsJsonObject("params")));
                break;

            default:
                // 未支持的方法
                if (id != null) {
                    transport.sendError(id, ERR_METHOD_NOT_FOUND, "Method not found: " + method);
                }
                break;
        }
    }

    // ============ 异步请求管理 ============

    private void submitAsync(JsonElement id, RequestHandler handler) {
        String idStr = id != null ? id.toString() : null;
        Future<?> future = requestPool.submit(() -> {
            try {
                if (Thread.interrupted()) return;
                handler.handle();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "异步请求处理失败", e);
                try {
                    if (id != null) {
                        transport.sendError(id, ERR_INTERNAL,
                                "Internal error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
                    }
                } catch (IOException ioEx) {
                    LOG.log(Level.SEVERE, "发送错误响应失败", ioEx);
                }
            } finally {
                if (idStr != null) pendingRequests.remove(idStr);
            }
        });
        if (idStr != null) {
            pendingRequests.put(idStr, future);
        }
    }

    @FunctionalInterface
    private interface RequestHandler {
        void handle() throws Exception;
    }

    private void handleCancelRequest(JsonObject params) {
        if (params == null || !params.has("id")) return;
        String cancelId = params.get("id").toString();
        Future<?> future = pendingRequests.remove(cancelId);
        if (future != null) {
            future.cancel(true);
            // 发送取消响应
            try {
                transport.sendError(params.get("id"), ERR_REQUEST_CANCELLED, "Request cancelled");
            } catch (IOException e) {
                LOG.log(Level.WARNING, "发送取消响应失败", e);
            }
        }
    }

    // ============ LSP 方法处理 ============

    private void handleInitialize(JsonElement id, JsonObject params) throws IOException {
        // 读取 initializationOptions 中的 classpath
        List<String> classpath = new ArrayList<>();
        if (params != null && params.has("initializationOptions")) {
            JsonObject options = params.getAsJsonObject("initializationOptions");
            if (options != null && options.has("classpath")) {
                JsonArray cp = options.getAsJsonArray("classpath");
                if (cp != null) {
                    for (int i = 0; i < cp.size(); i++) {
                        classpath.add(cp.get(i).getAsString());
                    }
                }
            }
        }

        // 创建 Java 类解析器并注入 analyzer
        JavaClassResolver javaClassResolver = new JavaClassResolver(classpath);
        analyzer.setJavaClassResolver(javaClassResolver);
        LOG.info("Java classpath entries: " + classpath.size());

        JsonObject result = new JsonObject();

        // 服务器能力
        JsonObject capabilities = new JsonObject();

        // 文本同步：增量同步
        JsonObject textDocumentSync = new JsonObject();
        textDocumentSync.addProperty("openClose", true);
        textDocumentSync.addProperty("change", SYNC_INCREMENTAL);
        capabilities.add("textDocumentSync", textDocumentSync);

        // 补全
        JsonObject completionProvider = new JsonObject();
        completionProvider.addProperty("resolveProvider", false);
        JsonArray triggerChars = new JsonArray();
        triggerChars.add(".");
        triggerChars.add(":");
        triggerChars.add("$");
        completionProvider.add("triggerCharacters", triggerChars);
        capabilities.add("completionProvider", completionProvider);

        // 悬停
        capabilities.addProperty("hoverProvider", true);

        // 跳转定义
        capabilities.addProperty("definitionProvider", true);

        // 文档符号
        capabilities.addProperty("documentSymbolProvider", true);

        // 代码格式化
        capabilities.addProperty("documentFormattingProvider", true);

        // 签名帮助
        JsonObject signatureHelpProvider = new JsonObject();
        JsonArray sigTriggerChars = new JsonArray();
        sigTriggerChars.add("(");
        sigTriggerChars.add(",");
        signatureHelpProvider.add("triggerCharacters", sigTriggerChars);
        capabilities.add("signatureHelpProvider", signatureHelpProvider);

        // 查找引用
        capabilities.addProperty("referencesProvider", true);

        // 文档高亮
        capabilities.addProperty("documentHighlightProvider", true);

        // 重命名
        JsonObject renameProvider = new JsonObject();
        renameProvider.addProperty("prepareProvider", true);
        capabilities.add("renameProvider", renameProvider);

        // 代码操作
        capabilities.addProperty("codeActionProvider", true);

        // 折叠范围
        capabilities.addProperty("foldingRangeProvider", true);

        // 语义令牌
        JsonObject semanticTokensProvider = new JsonObject();
        JsonObject legend = new JsonObject();
        legend.add("tokenTypes", SemanticTokensBuilder.getTokenTypesJson());
        legend.add("tokenModifiers", SemanticTokensBuilder.getTokenModifiersJson());
        semanticTokensProvider.add("legend", legend);
        JsonObject fullProvider = new JsonObject();
        fullProvider.addProperty("delta", false);
        semanticTokensProvider.add("full", fullProvider);
        capabilities.add("semanticTokensProvider", semanticTokensProvider);

        // 内嵌提示
        capabilities.addProperty("inlayHintProvider", true);

        // 工作区符号
        capabilities.addProperty("workspaceSymbolProvider", true);

        result.add("capabilities", capabilities);

        // 服务器信息
        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", "nova-lsp");
        serverInfo.addProperty("version", "0.2.0");
        result.add("serverInfo", serverInfo);

        transport.sendResponse(id, result);
    }

    private void handleShutdown(JsonElement id) throws IOException {
        transport.sendResponse(id, JsonNull.INSTANCE);
    }

    private void handleDidOpen(JsonObject params) throws IOException {
        if (params == null) return;
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null) return;
        String uri = textDocument.get("uri").getAsString();
        String text = textDocument.get("text").getAsString();

        documents.open(uri, text);
        publishDiagnostics(uri, text);
        analyzer.updateProjectIndex(uri);
    }

    private void handleDidChange(JsonObject params) throws IOException {
        if (params == null) return;
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null) return;
        String uri = textDocument.get("uri").getAsString();

        JsonArray changes = params.getAsJsonArray("contentChanges");
        if (changes == null || changes.size() == 0) return;

        for (int i = 0; i < changes.size(); i++) {
            JsonObject change = changes.get(i).getAsJsonObject();
            if (change.has("range")) {
                // 增量变更
                JsonObject range = change.getAsJsonObject("range");
                int startLine = range.getAsJsonObject("start").get("line").getAsInt();
                int startChar = range.getAsJsonObject("start").get("character").getAsInt();
                int endLine = range.getAsJsonObject("end").get("line").getAsInt();
                int endChar = range.getAsJsonObject("end").get("character").getAsInt();
                String text = change.get("text").getAsString();
                documents.applyIncrementalChange(uri, startLine, startChar, endLine, endChar, text);
            } else {
                // 全量变更（fallback）
                String text = change.get("text").getAsString();
                documents.change(uri, text);
            }
        }
    }

    private void handleDidClose(JsonObject params) throws IOException {
        if (params == null) return;
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null) return;
        String uri = textDocument.get("uri").getAsString();
        documents.close(uri);

        // 清除诊断
        JsonObject diagParams = new JsonObject();
        diagParams.addProperty("uri", uri);
        diagParams.add("diagnostics", new JsonArray());
        transport.sendNotification("textDocument/publishDiagnostics", diagParams);
    }

    // ============ textDocument/completion ============

    private void handleCompletion(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray items = analyzer.complete(uri, content, line, character);
        transport.sendResponse(id, items);
    }

    // ============ textDocument/hover ============

    private void handleHover(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, JsonNull.INSTANCE);
            return;
        }

        JsonObject hover = analyzer.hover(uri, content, line, character);
        transport.sendResponse(id, hover != null ? hover : JsonNull.INSTANCE);
    }

    // ============ textDocument/definition ============

    private void handleDefinition(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, JsonNull.INSTANCE);
            return;
        }

        JsonObject location = analyzer.goToDefinition(uri, content, line, character);
        transport.sendResponse(id, location != null ? location : JsonNull.INSTANCE);
    }

    // ============ textDocument/documentSymbol ============

    private void handleDocumentSymbol(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null || !textDocument.has("uri")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument");
            return;
        }
        String uri = textDocument.get("uri").getAsString();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray symbols = analyzer.documentSymbols(uri, content);
        transport.sendResponse(id, symbols);
    }

    // ============ textDocument/formatting ============

    private void handleFormatting(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null || !textDocument.has("uri")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument");
            return;
        }
        String uri = textDocument.get("uri").getAsString();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray edits = analyzer.format(uri, content);
        transport.sendResponse(id, edits != null ? edits : new JsonArray());
    }

    // ============ textDocument/signatureHelp ============

    private void handleSignatureHelp(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, JsonNull.INSTANCE);
            return;
        }

        JsonObject result = analyzer.signatureHelp(uri, content, line, character);
        transport.sendResponse(id, result != null ? result : JsonNull.INSTANCE);
    }

    // ============ textDocument/references ============

    private void handleReferences(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        boolean includeDeclaration = false;
        if (params.has("context")) {
            JsonObject context = params.getAsJsonObject("context");
            if (context != null && context.has("includeDeclaration")) {
                includeDeclaration = context.get("includeDeclaration").getAsBoolean();
            }
        }

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray result = analyzer.findReferences(uri, content, line, character, includeDeclaration);
        transport.sendResponse(id, result);
    }

    // ============ textDocument/documentHighlight ============

    private void handleDocumentHighlight(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray result = analyzer.documentHighlight(uri, content, line, character);
        transport.sendResponse(id, result);
    }

    // ============ textDocument/prepareRename ============

    private void handlePrepareRename(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument or position");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, JsonNull.INSTANCE);
            return;
        }

        JsonObject result = analyzer.prepareRename(uri, content, line, character);
        transport.sendResponse(id, result != null ? result : JsonNull.INSTANCE);
    }

    // ============ textDocument/rename ============

    private void handleRename(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        JsonObject position = params.getAsJsonObject("position");
        if (textDocument == null || !textDocument.has("uri") || position == null || !params.has("newName")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument, position or newName");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        int line = position.get("line").getAsInt();
        int character = position.get("character").getAsInt();
        String newName = params.get("newName").getAsString();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, JsonNull.INSTANCE);
            return;
        }

        JsonObject result = analyzer.rename(uri, content, line, character, newName);
        transport.sendResponse(id, result != null ? result : JsonNull.INSTANCE);
    }

    // ============ textDocument/codeAction ============

    private void handleCodeAction(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null || !textDocument.has("uri")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        JsonObject range = params.getAsJsonObject("range");
        JsonObject context = params.getAsJsonObject("context");

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray result = analyzer.codeActions(uri, content, range, context);
        transport.sendResponse(id, result);
    }

    // ============ textDocument/foldingRange ============

    private void handleFoldingRange(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null || !textDocument.has("uri")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument");
            return;
        }
        String uri = textDocument.get("uri").getAsString();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray result = analyzer.foldingRanges(uri, content);
        transport.sendResponse(id, result);
    }

    // ============ textDocument/semanticTokens/full ============

    private void handleSemanticTokensFull(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null || !textDocument.has("uri")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument");
            return;
        }
        String uri = textDocument.get("uri").getAsString();

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonObject());
            return;
        }

        JsonObject result = analyzer.semanticTokensFull(uri, content);
        transport.sendResponse(id, result != null ? result : new JsonObject());
    }

    // ============ textDocument/inlayHint ============

    private void handleInlayHint(JsonElement id, JsonObject params) throws IOException {
        if (params == null) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing params");
            return;
        }
        JsonObject textDocument = params.getAsJsonObject("textDocument");
        if (textDocument == null || !textDocument.has("uri")) {
            transport.sendError(id, ERR_INVALID_PARAMS, "Missing textDocument");
            return;
        }
        String uri = textDocument.get("uri").getAsString();
        JsonObject range = params.getAsJsonObject("range");
        int startLine = 0, endLine = Integer.MAX_VALUE;
        if (range != null) {
            startLine = range.getAsJsonObject("start").get("line").getAsInt();
            endLine = range.getAsJsonObject("end").get("line").getAsInt();
        }

        String content = documents.getContent(uri);
        if (content == null) {
            transport.sendResponse(id, new JsonArray());
            return;
        }

        JsonArray result = analyzer.inlayHints(uri, content, startLine, endLine);
        transport.sendResponse(id, result);
    }

    // ============ workspace/symbol ============

    private void handleWorkspaceSymbol(JsonElement id, JsonObject params) throws IOException {
        String query = "";
        if (params != null && params.has("query")) {
            query = params.get("query").getAsString();
        }

        JsonArray result = analyzer.workspaceSymbols(query);
        transport.sendResponse(id, result);
    }

    // ============ 诊断 ============

    private void publishDiagnostics(String uri, String content) throws IOException {
        JsonArray diagnostics = analyzer.analyze(uri, content);

        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        params.add("diagnostics", diagnostics);

        transport.sendNotification("textDocument/publishDiagnostics", params);
    }

    // ============ 入口 ============

    public static void main(String[] args) {
        // 配置日志到 stderr（不干扰 stdin/stdout 的 LSP 通信）
        Logger rootLogger = Logger.getLogger("");
        for (Handler handler : rootLogger.getHandlers()) {
            rootLogger.removeHandler(handler);
        }
        Handler stderrHandler = new StreamHandler(System.err, new SimpleFormatter()) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        };
        stderrHandler.setLevel(Level.INFO);
        rootLogger.addHandler(stderrHandler);

        NovaLanguageServer server = new NovaLanguageServer(System.in, System.out);
        server.run();
    }
}
