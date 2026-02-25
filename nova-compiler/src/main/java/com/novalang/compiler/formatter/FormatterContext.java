package com.novalang.compiler.formatter;

/**
 * 格式化上下文，跟踪输出缓冲区和缩进层级
 */
public class FormatterContext {
    private final StringBuilder output = new StringBuilder();
    private final FormatConfig config;
    private int indentLevel = 0;
    private boolean atLineStart = true;

    public FormatterContext(FormatConfig config) {
        this.config = config;
    }

    public FormatConfig getConfig() {
        return config;
    }

    public void indent() {
        indentLevel++;
    }

    public void dedent() {
        if (indentLevel > 0) {
            indentLevel--;
        }
    }

    /**
     * 追加文本（自动处理行首缩进）
     */
    public void append(String text) {
        if (text == null || text.isEmpty()) return;
        if (atLineStart) {
            output.append(indentString());
            atLineStart = false;
        }
        output.append(text);
    }

    /**
     * 换行
     */
    public void newLine() {
        output.append("\n");
        atLineStart = true;
    }

    /**
     * 追加空行（两个换行）
     */
    public void blankLine() {
        // 避免连续多个空行
        String current = output.toString();
        if (current.endsWith("\n\n")) {
            return;
        }
        if (!current.endsWith("\n")) {
            output.append("\n");
        }
        output.append("\n");
        atLineStart = true;
    }

    /**
     * 追加空格
     */
    public void space() {
        append(" ");
    }

    /**
     * 获取当前输出
     */
    public String getOutput() {
        return output.toString();
    }

    /**
     * 获取当前行的大致长度（用于判断是否需要换行）
     */
    public int getCurrentLineLength() {
        int lastNewline = output.lastIndexOf("\n");
        if (lastNewline < 0) {
            return output.length();
        }
        return output.length() - lastNewline - 1;
    }

    private String indentString() {
        String unit = config.getIndentString();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indentLevel; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
