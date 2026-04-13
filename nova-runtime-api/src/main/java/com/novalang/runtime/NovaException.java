package com.novalang.runtime;

/**
 * NovaLang 基础运行时异常（无源位置信息）。
 *
 * <p>{@code nova-runtime} 中的 {@code NovaRuntimeException} 继承此类，
 * 并添加 SourceLocation 等诊断信息。</p>
 */
public class NovaException extends RuntimeException {

    /** 错误分类 */
    public enum ErrorKind {
        TYPE_MISMATCH,       // 类型不匹配 (as/is 失败)
        NULL_REFERENCE,      // 空指针访问
        UNDEFINED,           // 未定义的变量/函数/成员
        ARGUMENT_MISMATCH,   // 参数数量/类型不匹配
        ACCESS_DENIED,       // 安全策略拒绝
        INDEX_OUT_OF_BOUNDS, // 索引越界
        JAVA_INTEROP,        // Java 互操作错误
        IO_ERROR,            // I/O 操作失败
        PARSE_ERROR,         // 语法解析错误
        INTERNAL             // 内部错误（不应该发生）
    }

    private final ErrorKind kind;
    private final String suggestion;
    /** 编译路径：从堆栈提取的源文件名 */
    private String sourceFile;
    /** 编译路径：从堆栈提取的源码行号 */
    private int sourceLineNumber;

    public NovaException(String message) {
        super(message);
        this.kind = null;
        this.suggestion = null;
    }

    public NovaException(String message, Throwable cause) {
        super(message, cause);
        this.kind = null;
        this.suggestion = null;
    }

    public NovaException(ErrorKind kind, String message) {
        super(message);
        this.kind = kind;
        this.suggestion = null;
    }

    public NovaException(ErrorKind kind, String message, String suggestion) {
        super(message);
        this.kind = kind;
        this.suggestion = suggestion;
    }

    public NovaException(ErrorKind kind, String message, String suggestion, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.suggestion = suggestion;
    }

    /** 错误分类，可能为 null（兼容旧代码） */
    public ErrorKind getKind() {
        return kind;
    }

    /** 修复建议，可能为 null */
    public String getSuggestion() {
        return suggestion;
    }

    /** 编译路径源文件名 */
    public String getSourceFile() { return sourceFile; }
    /** 编译路径源码行号（0 = 未知） */
    public int getSourceLineNumber() { return sourceLineNumber; }

    /**
     * 从 Java 堆栈中提取编译路径的 Nova 源码行号。
     * 跳过 runtime-api 内部帧（NovaDynamic/NovaBootstrap/NovaOps 等），
     * 找到第一个有正整数行号的帧——即编译生成的 Nova 字节码类。
     */
    public NovaException attachStackLocation() {
        if (this.sourceLineNumber > 0) return this;
        for (StackTraceElement frame : getStackTrace()) {
            String cls = frame.getClassName();
            if (cls.startsWith("com.novalang.runtime.")) continue;
            if (cls.startsWith("com.novalang.ir.")) continue;
            if (cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.")) continue;
            int line = frame.getLineNumber();
            if (line > 0) {
                this.sourceFile = frame.getFileName();
                this.sourceLineNumber = line;
                break;
            }
        }
        return this;
    }

    @Override
    public String getMessage() {
        // 懒提取编译路径位置
        if (sourceLineNumber == 0 && kind != null) {
            attachStackLocation();
        }
        String base = super.getMessage();
        if (suggestion != null) {
            base += "\n  提示: " + suggestion;
        }
        if (sourceLineNumber > 0) {
            base += "\n  --> " + (sourceFile != null ? sourceFile : "<script>") + ":" + sourceLineNumber;
        }
        return base;
    }

    /** 返回不含 suggestion/位置的纯错误消息 */
    public String getRawMessage() {
        return super.getMessage();
    }
}
