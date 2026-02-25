package com.novalang.lsp;

import com.novalang.compiler.analysis.AnalysisResult;
import com.novalang.compiler.analysis.SemanticAnalyzer;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.ParseResult;
import com.novalang.compiler.parser.Parser;

import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 文档管理器
 *
 * <p>管理当前打开的文档内容，支持 LSP 的 textDocument/didOpen、didChange、didClose。</p>
 * <p>同时维护 AST + 语义分析结果缓存，didChange 使用 debounce 避免频繁重分析。</p>
 */
public class DocumentManager {
    private static final Logger LOG = Logger.getLogger(DocumentManager.class.getName());

    /** debounce 延迟（毫秒） */
    private static final long DEBOUNCE_MS = 200;

    /** URI -> 文档内容 */
    private final Map<String, String> documents = new ConcurrentHashMap<>();

    /** URI -> 缓存的分析结果 */
    private final Map<String, CachedAnalysis> analysisCache = new ConcurrentHashMap<>();

    /** URI -> 待执行的 debounce 任务 */
    private final Map<String, ScheduledFuture<?>> pendingAnalysis = new ConcurrentHashMap<>();

    /** 文档版本计数器（全局递增） */
    private final AtomicLong versionCounter = new AtomicLong(0);

    /** URI -> 当前版本号（用于防止关闭/更新后旧回调回写） */
    private final Map<String, Long> documentVersions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "nova-lsp-analyzer");
        t.setDaemon(true);
        return t;
    });

    /** 分析完成回调（用于触发诊断发布） */
    private volatile AnalysisCallback analysisCallback;

    @FunctionalInterface
    public interface AnalysisCallback {
        void onAnalysisComplete(String uri, String content);
    }

    public void setAnalysisCallback(AnalysisCallback callback) {
        this.analysisCallback = callback;
    }

    /** 缓存的分析结果 */
    public static class CachedAnalysis {
        public final ParseResult parseResult;
        public final AnalysisResult analysisResult;

        public CachedAnalysis(ParseResult parseResult, AnalysisResult analysisResult) {
            this.parseResult = parseResult;
            this.analysisResult = analysisResult;
        }
    }

    /**
     * 打开文档（立即分析，无 debounce）
     */
    public void open(String uri, String content) {
        documents.put(uri, content);
        documentVersions.put(uri, versionCounter.incrementAndGet());
        reanalyze(uri, content);
    }

    /**
     * 更新文档内容（debounce 延迟分析）
     */
    public void change(String uri, String content) {
        documents.put(uri, content);
        analysisCache.remove(uri); // 立即清除旧缓存，避免 inlayHint 等用到过期 AST
        long version = versionCounter.incrementAndGet();
        documentVersions.put(uri, version);
        scheduleReanalyze(uri, content, version);
    }

    /**
     * 应用增量文本变更（LSP TextDocumentSyncKind.Incremental）
     *
     * @param uri       文档 URI
     * @param startLine 变更起始行（0-based）
     * @param startChar 变更起始列（0-based）
     * @param endLine   变更结束行（0-based）
     * @param endChar   变更结束列（0-based）
     * @param text      替换文本
     */
    public void applyIncrementalChange(String uri, int startLine, int startChar,
                                        int endLine, int endChar, String text) {
        String current = documents.get(uri);
        if (current == null) return;

        // 计算偏移量
        int startOffset = lineCharToOffset(current, startLine, startChar);
        int endOffset = lineCharToOffset(current, endLine, endChar);

        if (startOffset < 0) startOffset = 0;
        if (endOffset < 0) endOffset = current.length();
        if (startOffset > current.length()) startOffset = current.length();
        if (endOffset > current.length()) endOffset = current.length();

        String newContent = current.substring(0, startOffset) + text + current.substring(endOffset);
        documents.put(uri, newContent);
        analysisCache.remove(uri); // 立即清除旧缓存，避免 inlayHint 等用到过期 AST

        long version = versionCounter.incrementAndGet();
        documentVersions.put(uri, version);
        scheduleReanalyze(uri, newContent, version);
    }

    /**
     * 将行列位置（0-based）转换为字符偏移量
     */
    private static int lineCharToOffset(String content, int line, int character) {
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line && offset < content.length()) {
            if (content.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return offset + character;
    }

    /**
     * 关闭文档
     */
    public void close(String uri) {
        documentVersions.remove(uri);
        documents.remove(uri);
        analysisCache.remove(uri);
        ScheduledFuture<?> pending = pendingAnalysis.remove(uri);
        if (pending != null) pending.cancel(false);
    }

    /**
     * 关闭调度器，释放线程资源
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * 获取文档内容
     */
    public String getContent(String uri) {
        return documents.get(uri);
    }

    /**
     * 获取缓存的分析结果
     */
    public CachedAnalysis getAnalysis(String uri) {
        return analysisCache.get(uri);
    }

    /**
     * 检查文档是否已打开
     */
    public boolean isOpen(String uri) {
        return documents.containsKey(uri);
    }

    /**
     * 获取所有打开的文档 URI
     */
    public Iterable<String> getOpenDocuments() {
        return documents.keySet();
    }

    /**
     * 从 URI 提取文件名
     */
    public static String getFileName(String uri) {
        // file:///path/to/file.nova -> file.nova
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash >= 0) {
            return uri.substring(lastSlash + 1);
        }
        return uri;
    }

    /**
     * 带 debounce 的延迟分析
     *
     * @param version 调度时的文档版本，回调时校验是否过期
     */
    private void scheduleReanalyze(String uri, String content, long version) {
        ScheduledFuture<?> prev = pendingAnalysis.remove(uri);
        if (prev != null) prev.cancel(false);

        ScheduledFuture<?> future = scheduler.schedule(() -> {
            pendingAnalysis.remove(uri);
            // 版本校验：文档已关闭或已有更新则跳过
            Long currentVersion = documentVersions.get(uri);
            if (currentVersion == null || currentVersion != version) return;

            reanalyze(uri, content);
            AnalysisCallback cb = analysisCallback;
            if (cb != null) {
                cb.onAnalysisComplete(uri, content);
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        pendingAnalysis.put(uri, future);
    }

    /**
     * 执行容错解析 + 语义分析并缓存结果
     */
    private void reanalyze(String uri, String content) {
        try {
            String fileName = getFileName(uri);
            Lexer lexer = new Lexer(content, fileName);
            Parser parser = new Parser(lexer, fileName);
            ParseResult parseResult = parser.parseTolerant();

            SemanticAnalyzer analyzer = new SemanticAnalyzer();
            AnalysisResult analysisResult = analyzer.analyze(
                    parseResult.getProgram(), parseResult.getTopLevelStatements());

            analysisCache.put(uri, new CachedAnalysis(parseResult, analysisResult));
        } catch (Exception e) {
            LOG.log(Level.WARNING, "分析文档失败: " + uri, e);
            analysisCache.remove(uri);
        }
    }
}
