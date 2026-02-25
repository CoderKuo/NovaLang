package com.novalang.compiler.formatter;

/**
 * 代码格式化配置
 */
public class FormatConfig {
    private int indentSize = 4;
    private boolean useSpaces = true;
    private int maxLineWidth = 120;
    private boolean trailingComma = false;

    public FormatConfig() {
    }

    public int getIndentSize() {
        return indentSize;
    }

    public void setIndentSize(int indentSize) {
        this.indentSize = indentSize;
    }

    public boolean isUseSpaces() {
        return useSpaces;
    }

    public void setUseSpaces(boolean useSpaces) {
        this.useSpaces = useSpaces;
    }

    public int getMaxLineWidth() {
        return maxLineWidth;
    }

    public void setMaxLineWidth(int maxLineWidth) {
        this.maxLineWidth = maxLineWidth;
    }

    public boolean isTrailingComma() {
        return trailingComma;
    }

    public void setTrailingComma(boolean trailingComma) {
        this.trailingComma = trailingComma;
    }

    /**
     * 获取单层缩进字符串
     */
    public String getIndentString() {
        if (useSpaces) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < indentSize; i++) {
                sb.append(' ');
            }
            return sb.toString();
        } else {
            return "\t";
        }
    }
}
