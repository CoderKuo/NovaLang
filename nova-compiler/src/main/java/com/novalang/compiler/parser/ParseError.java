package com.novalang.compiler.parser;

import com.novalang.compiler.lexer.Token;

/**
 * 容错解析中收集的语法错误
 */
public final class ParseError {
    private final String message;
    private final Token token;

    public ParseError(String message, Token token) {
        this.message = message;
        this.token = token;
    }

    public String getMessage() {
        return message;
    }

    public Token getToken() {
        return token;
    }

    public int getLine() {
        return token != null ? token.getLine() : 0;
    }

    public int getColumn() {
        return token != null ? token.getColumn() : 0;
    }
}
