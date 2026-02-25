package com.novalang.lsp;

/**
 * LSP 协议常量定义。
 *
 * @see <a href="https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/">LSP 3.17 Spec</a>
 */
public final class LspConstants {

    private LspConstants() {}

    // ==================== CompletionItemKind ====================

    public static final int COMPLETION_TEXT = 1;
    public static final int COMPLETION_METHOD = 2;
    public static final int COMPLETION_FUNCTION = 3;
    public static final int COMPLETION_CONSTRUCTOR = 4;
    public static final int COMPLETION_FIELD = 5;
    public static final int COMPLETION_VARIABLE = 6;
    public static final int COMPLETION_CLASS = 7;
    public static final int COMPLETION_INTERFACE = 8;
    public static final int COMPLETION_MODULE = 9;
    public static final int COMPLETION_PROPERTY = 10;
    public static final int COMPLETION_ENUM = 13;
    public static final int COMPLETION_KEYWORD = 14;
    public static final int COMPLETION_SNIPPET = 15;
    public static final int COMPLETION_ENUM_MEMBER = 20;
    public static final int COMPLETION_CONSTANT = 21;

    // ==================== DiagnosticSeverity ====================

    public static final int SEVERITY_ERROR = 1;
    public static final int SEVERITY_WARNING = 2;
    public static final int SEVERITY_INFORMATION = 3;
    public static final int SEVERITY_HINT = 4;

    // ==================== SymbolKind ====================

    public static final int SYMBOL_NAMESPACE = 3;
    public static final int SYMBOL_CLASS = 5;
    public static final int SYMBOL_METHOD = 6;
    public static final int SYMBOL_PROPERTY = 7;
    public static final int SYMBOL_CONSTRUCTOR = 9;
    public static final int SYMBOL_ENUM = 10;
    public static final int SYMBOL_INTERFACE = 11;
    public static final int SYMBOL_FUNCTION = 12;
    public static final int SYMBOL_VARIABLE = 13;
    public static final int SYMBOL_CONSTANT = 14;
    public static final int SYMBOL_ENUM_MEMBER = 22;
    public static final int SYMBOL_TYPE_PARAMETER = 26;

    // ==================== InlayHintKind ====================

    public static final int INLAY_TYPE = 1;
    public static final int INLAY_PARAMETER = 2;

    // ==================== DocumentHighlightKind ====================

    public static final int HIGHLIGHT_READ = 2;
    public static final int HIGHLIGHT_WRITE = 3;

    // ==================== InsertTextFormat ====================

    public static final int INSERT_TEXT_PLAIN = 1;
    public static final int INSERT_TEXT_SNIPPET = 2;

    // ==================== TextDocumentSyncKind ====================

    public static final int SYNC_INCREMENTAL = 2;
}
