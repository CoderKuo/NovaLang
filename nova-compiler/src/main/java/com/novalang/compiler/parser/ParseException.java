package com.novalang.compiler.parser;

import com.novalang.compiler.lexer.Token;

/**
 * 解析异常
 */
public class ParseException extends RuntimeException {
    private final Token token;
    private final String expected;
    private String sourceLine;
    private int overrideLine;

    public ParseException(String message, Token token) {
        super(message);
        this.token = token;
        this.expected = null;
    }

    public ParseException(String message, Token token, String expected) {
        super(message);
        this.token = token;
        this.expected = expected;
    }

    /** 附加源代码（由 Parser 在抛出前设置） */
    public ParseException withSource(String source) {
        if (source != null && token != null) {
            int line = token.getLine();
            String[] lines = source.split("\n", -1);
            if (line >= 1 && line <= lines.length) {
                this.sourceLine = lines[line - 1];
            }
        }
        return this;
    }

    /** 附加源代码，指定显示的行号（用于 "unclosed brace" 等需要显示打开位置的错误） */
    public ParseException withSourceAt(String source, int displayLine) {
        if (source != null) {
            String[] lines = source.split("\n", -1);
            if (displayLine >= 1 && displayLine <= lines.length) {
                this.sourceLine = lines[displayLine - 1];
                this.overrideLine = displayLine;
            }
        }
        return this;
    }

    public Token getToken() {
        return token;
    }

    public String getExpected() {
        return expected;
    }

    @Override
    public String getMessage() {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getMessage());
        if (token != null) {
            sb.append(" at line ").append(token.getLine());
            sb.append(", column ").append(token.getColumn());
            sb.append(" (found '").append(token.getLexeme()).append("')");
        }
        if (expected != null) {
            sb.append(", expected: ").append(expected);
        }
        if (sourceLine != null) {
            int lineNum = overrideLine > 0 ? overrideLine : (token != null ? token.getLine() : 0);
            int col = overrideLine > 0 ? 0 : (token != null ? Math.max(0, token.getColumn() - 1) : 0);
            String prefix = lineNum + " | ";
            sb.append('\n').append(prefix).append(sourceLine);
            if (col > 0) {
                sb.append('\n');
                for (int i = 0; i < prefix.length() + col; i++) sb.append('-');
                sb.append('^');
            }
        }
        return sb.toString();
    }
}
