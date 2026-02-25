package nova.runtime.interpreter;

/**
 * Nova 调用栈管理：记录调用帧用于错误堆栈跟踪。
 */
public final class NovaCallStack {

    private static final int MAX_FRAMES = 64;
    private static final int DEFAULT_DISPLAY_LIMIT = 16;

    // ANSI color constants
    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String DIM = "\033[2m";
    private static final String ITALIC = "\033[3m";
    private static final String YELLOW = "\033[33m";
    private static final String CYAN = "\033[36m";
    private static final String BLUE = "\033[34m";
    private static final String WHITE = "\033[37m";

    private final NovaCallFrame[] frames = new NovaCallFrame[MAX_FRAMES];
    private int size = 0;

    public void push(NovaCallFrame frame) {
        if (size >= MAX_FRAMES) {
            // Shift: discard oldest frame
            System.arraycopy(frames, 1, frames, 0, MAX_FRAMES - 1);
            frames[MAX_FRAMES - 1] = frame;
        } else {
            frames[size++] = frame;
        }
    }

    public void pop() {
        if (size > 0) {
            frames[--size] = null;
        }
    }

    public int size() {
        return size;
    }

    public NovaCallFrame peek() {
        return size > 0 ? frames[size - 1] : null;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            frames[i] = null;
        }
        size = 0;
    }

    /**
     * 格式化带 ANSI 颜色的堆栈跟踪。
     * @param sourceLines 当前文件的源代码行（可为 null）
     * @param currentFile 当前执行的文件名
     * @param displayLimit 最大显示帧数（默认 16）
     */
    public String formatStackTrace(String[] sourceLines, String currentFile, int displayLimit) {
        if (size == 0) return null;
        if (displayLimit <= 0) displayLimit = DEFAULT_DISPLAY_LIMIT;

        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(BOLD).append(WHITE).append("Call Stack:").append(RESET).append("\n");

        if (size <= displayLimit) {
            // Show all frames (top of stack = most recent = printed first)
            for (int i = size - 1; i >= 0; i--) {
                appendFrame(sb, frames[i], sourceLines, currentFile);
            }
        } else {
            // Folded display: top half + omitted + bottom half
            int half = displayLimit / 2;
            // Top frames (most recent)
            for (int i = size - 1; i >= size - half; i--) {
                appendFrame(sb, frames[i], sourceLines, currentFile);
            }
            int omitted = size - displayLimit;
            sb.append("  ").append(DIM).append(ITALIC)
              .append("... ").append(omitted).append(" frames omitted ...")
              .append(RESET).append("\n");
            // Bottom frames (oldest)
            for (int i = half - 1; i >= 0; i--) {
                appendFrame(sb, frames[i], sourceLines, currentFile);
            }
        }

        return sb.toString();
    }

    public String formatStackTrace(String[] sourceLines, String currentFile) {
        return formatStackTrace(sourceLines, currentFile, DEFAULT_DISPLAY_LIMIT);
    }

    private void appendFrame(StringBuilder sb, NovaCallFrame frame, String[] sourceLines, String currentFile) {
        if (frame == null) return; // 防御性检查
        // Line 1: at funcName(params)  file:line
        sb.append("  ").append(DIM).append("at ").append(RESET);
        sb.append(BOLD).append(YELLOW).append(frame.getFunctionName()).append(RESET);
        sb.append(DIM).append("(").append(frame.getParamSummary()).append(")").append(RESET);

        String fileLine = frame.getFileName() + ":" + frame.getLine();
        sb.append("  ").append(CYAN).append(fileLine).append(RESET).append("\n");

        // Line 2: source code at the frame's line
        String srcLine = getSourceLineForFrame(frame, sourceLines, currentFile);
        if (srcLine != null) {
            int lineNum = frame.getLine();
            sb.append("   ").append(BLUE).append(String.format("%4d", lineNum)).append(RESET)
              .append(DIM).append(" | ").append(RESET)
              .append(WHITE).append(srcLine).append(RESET).append("\n");
        }
    }

    private String getSourceLineForFrame(NovaCallFrame frame, String[] sourceLines, String currentFile) {
        return getSourceLineByNumber(frame.getLine(), sourceLines, frame.getFileName(), currentFile);
    }

    private String getSourceLineByNumber(int lineNum, String[] sourceLines, String frameFile, String currentFile) {
        if (sourceLines == null || lineNum <= 0) return null;
        // Only show source if the frame is from the current file
        if (currentFile != null && frameFile != null && !frameFile.equals(currentFile)) return null;
        if (lineNum > sourceLines.length) return null;
        return sourceLines[lineNum - 1];
    }
}
