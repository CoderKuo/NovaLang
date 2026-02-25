package com.novalang.compiler.parser;

import com.novalang.compiler.lexer.Token;

/**
 * 解析异常
 */
public class ParseException extends RuntimeException {
    private final Token token;
    private final String expected;

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
        return sb.toString();
    }
}
