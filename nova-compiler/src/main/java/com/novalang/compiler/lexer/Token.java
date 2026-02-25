package com.novalang.compiler.lexer;

/**
 * 词法单元
 */
public final class Token {
    private final TokenType type;
    private final String lexeme;
    private final Object literal;
    private final int line;
    private final int column;
    private final int offset;

    public Token(TokenType type, String lexeme, Object literal, int line, int column, int offset) {
        this.type = type;
        this.lexeme = lexeme;
        this.literal = literal;
        this.line = line;
        this.column = column;
        this.offset = offset;
    }

    public TokenType getType() {
        return type;
    }

    public String getLexeme() {
        return lexeme;
    }

    public Object getLiteral() {
        return literal;
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

    public boolean is(TokenType type) {
        return this.type == type;
    }

    public boolean isOneOf(TokenType... types) {
        for (TokenType t : types) {
            if (this.type == t) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        if (literal != null) {
            return String.format("%s(%s, %s) at %d:%d",
                    type, lexeme, literal, line, column);
        }
        return String.format("%s(%s) at %d:%d",
                type, lexeme, line, column);
    }
}
