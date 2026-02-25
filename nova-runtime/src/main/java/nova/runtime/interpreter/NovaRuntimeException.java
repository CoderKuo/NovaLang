package nova.runtime.interpreter;

import com.novalang.compiler.ast.SourceLocation;
import nova.runtime.NovaException;

/**
 * NovaLang 运行时异常
 *
 * 支持源代码位置信息，可以显示详细的错误位置指示。
 */
public class NovaRuntimeException extends NovaException {

    private String novaStackTrace;
    private final SourceLocation location;
    private final String sourceLine;

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

    /** 返回不含调用栈和位置信息的纯错误消息 */
    public String getRawMessage() {
        return super.getMessage();
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getMessage());

        if (location != null && location.getLine() > 0) {
            sb.append("\n");
            sb.append(formatErrorWithLocation());
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
