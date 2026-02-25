package com.novalang.compiler.ast;

/**
 * 源码位置信息
 */
public final class SourceLocation {
    private final String file;
    private final int line;
    private final int column;
    private final int offset;
    private final int length;

    public static final SourceLocation UNKNOWN = new SourceLocation("<unknown>", 0, 0, 0, 0);

    public SourceLocation(String file, int line, int column, int offset, int length) {
        this.file = file != null ? file.intern() : null;
        this.line = line;
        this.column = column;
        this.offset = offset;
        this.length = length;
    }

    public String getFile() {
        return file;
    }

    public int getLine() {
        return line;
    }

    public int getColumn() {
        return column;
    }

    public int getOffset() {
        return offset;
    }

    public int getLength() {
        return length;
    }

    @Override
    public String toString() {
        return file + ":" + line + ":" + column;
    }
}
