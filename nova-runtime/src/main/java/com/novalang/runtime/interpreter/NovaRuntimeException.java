package com.novalang.runtime.interpreter;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.runtime.NovaException;

/**
 * NovaLang 运行时异常
 *
 * 支持源代码位置信息，可以显示详细的错误位置指示。
 */
public class NovaRuntimeException extends NovaException {

    private String novaStackTrace;
    private SourceLocation location;
    private String sourceLine;

    public NovaRuntimeException(String message) {
        super(message);
        this.novaStackTrace = null;
        this.location = null;
        this.sourceLine = null;
    }

    public NovaRuntimeException(String message, Throwable cause) {
        super(message, cause);
        this.novaStackTrace = null;
        this.location = null;
        this.sourceLine = null;
    }

    public NovaRuntimeException(String message, String novaStackTrace) {
        super(message);
        this.novaStackTrace = novaStackTrace;
        this.location = null;
        this.sourceLine = null;
    }

    public NovaRuntimeException(String message, SourceLocation location, String sourceLine) {
        super(message);
        this.novaStackTrace = null;
        this.location = location;
        this.sourceLine = sourceLine;
    }

    public NovaRuntimeException(ErrorKind kind, String message, String suggestion) {
        super(kind, message, suggestion);
        this.novaStackTrace = null;
        this.location = null;
        this.sourceLine = null;
    }

    public NovaRuntimeException(ErrorKind kind, String message, String suggestion, Throwable cause) {
        super(kind, message, suggestion, cause);
        this.novaStackTrace = null;
        this.location = null;
        this.sourceLine = null;
    }

    public NovaRuntimeException(ErrorKind kind, String message, String suggestion,
                                SourceLocation location, String sourceLine) {
        super(kind, message, suggestion);
        this.novaStackTrace = null;
        this.location = location;
        this.sourceLine = sourceLine;
    }

    public String getNovaStackTrace() {
        return novaStackTrace;
    }

    public SourceLocation getLocation() {
        return location;
    }

    public String getSourceLine() {
        return sourceLine;
    }

    void setNovaStackTrace(String trace) {
        if (trace != null && this.novaStackTrace == null) {
            this.novaStackTrace = trace;
        }
    }

    /**
     * 事后附加源码位置信息（仅在尚未设置位置时生效）。
     * 用于 MirInterpreter catch 块从当前指令获取位置并补充到异常上。
     */
    public void attachLocation(SourceLocation loc, String srcLine) {
        if (this.location == null && loc != null) {
            this.location = loc;
            this.sourceLine = srcLine;
        }
    }

    /** 返回不含调用栈和位置信息的纯错误消息 */
    @Override
    public String getRawMessage() {
        return super.getRawMessage();
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(getRawMessage());

        if (getSuggestion() != null) {
            sb.append("\n  提示: ").append(getSuggestion());
        }

        if (location != null && location.getLine() > 0) {
            sb.append("\n");
            sb.append(formatErrorWithLocation());
        } else if (getSourceLineNumber() > 0) {
            // 编译路径回退：从堆栈提取的行号（无源码行文本）
            sb.append("\n  --> ").append(getSourceFile() != null ? getSourceFile() : "<script>")
              .append(":").append(getSourceLineNumber());
        }

        if (novaStackTrace != null) {
            sb.append("\n").append(novaStackTrace);
        }

        return sb.toString();
    }

    /**
     * 格式化带位置信息的错误消息
     *
     * 输出格式类似:
     * --> script.nova:5:10
     *   |
     * 5 |     obj.secret
     *   |         ^^^^^^
     */
    private String formatErrorWithLocation() {
        StringBuilder sb = new StringBuilder();

        // 位置指示行
        String file = location.getFile();
        if (file == null || file.isEmpty() || "<repl>".equals(file)) {
            file = "<script>";
        }
        sb.append("  --> ").append(file)
          .append(":").append(location.getLine())
          .append(":").append(location.getColumn())
          .append("\n");

        // 如果有源代码行，显示它
        if (sourceLine != null && !sourceLine.isEmpty()) {
            String lineNum = String.valueOf(location.getLine());
            String padding = repeat(" ", lineNum.length());

            // 空行
            sb.append(padding).append(" |\n");

            // 源代码行
            sb.append(lineNum).append(" | ").append(sourceLine).append("\n");

            // 指示器行
            sb.append(padding).append(" | ");
            int col = location.getColumn();
            if (col > 0) {
                sb.append(repeat(" ", col - 1));
            }
            int len = location.getLength();
            if (len <= 0) len = 1;
            sb.append(repeat("^", len));
        }

        return sb.toString();
    }

    private static String repeat(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
        return sb.toString();
    }
}
