package com.novalang.compiler.lexer;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * NovaLang 词法分析器
 */
public class Lexer {
    private String source;  // non-final: 解析完成后可释放
    private final String fileName;
    private final List<Token> tokens = new ArrayList<>();

    private int start = 0;
    private int current = 0;
    private int line = 1;
    private int column = 1;
    private int lineStart = 0;

    private final PrintStream errStream;

    // 关键词映射表
    private static final Map<String, TokenType> KEYWORDS;

    static {
        Map<String, TokenType> map = new HashMap<>();

        // 声明
        map.put("val", TokenType.KW_VAL);
        map.put("var", TokenType.KW_VAR);
        map.put("fun", TokenType.KW_FUN);
        map.put("class", TokenType.KW_CLASS);
        map.put("interface", TokenType.KW_INTERFACE);
        map.put("object", TokenType.KW_OBJECT);
        map.put("enum", TokenType.KW_ENUM);
        map.put("typealias", TokenType.KW_TYPEALIAS);
        map.put("package", TokenType.KW_PACKAGE);
        map.put("import", TokenType.KW_IMPORT);
        map.put("module", TokenType.KW_MODULE);
        // "constructor"/"init" 是软关键词，仅在类体内识别

        // 修饰符
        map.put("public", TokenType.KW_PUBLIC);
        map.put("private", TokenType.KW_PRIVATE);
        map.put("protected", TokenType.KW_PROTECTED);
        map.put("internal", TokenType.KW_INTERNAL);
        map.put("abstract", TokenType.KW_ABSTRACT);
        map.put("sealed", TokenType.KW_SEALED);
        map.put("open", TokenType.KW_OPEN);
        map.put("override", TokenType.KW_OVERRIDE);
        map.put("final", TokenType.KW_FINAL);
        map.put("const", TokenType.KW_CONST);
        map.put("inline", TokenType.KW_INLINE);
        map.put("crossinline", TokenType.KW_CROSSINLINE);
        map.put("reified", TokenType.KW_REIFIED);
        map.put("operator", TokenType.KW_OPERATOR);
        map.put("vararg", TokenType.KW_VARARG);
        map.put("suspend", TokenType.KW_SUSPEND);
        map.put("static", TokenType.KW_STATIC);
        map.put("companion", TokenType.KW_COMPANION);

        // 控制流
        map.put("if", TokenType.KW_IF);
        map.put("else", TokenType.KW_ELSE);
        map.put("when", TokenType.KW_WHEN);
        map.put("for", TokenType.KW_FOR);
        map.put("while", TokenType.KW_WHILE);
        map.put("do", TokenType.KW_DO);
        map.put("return", TokenType.KW_RETURN);
        map.put("break", TokenType.KW_BREAK);
        map.put("continue", TokenType.KW_CONTINUE);
        map.put("throw", TokenType.KW_THROW);
        map.put("try", TokenType.KW_TRY);
        map.put("catch", TokenType.KW_CATCH);
        map.put("finally", TokenType.KW_FINALLY);
        // "guard"/"step" 是软关键词，仅在特定上下文识别
        map.put("use", TokenType.KW_USE);

        // 类型操作
        map.put("is", TokenType.KW_IS);
        map.put("as", TokenType.KW_AS);
        map.put("in", TokenType.KW_IN);
        // "out"/"where" 是软关键词，仅在特定上下文识别，不在此注册
        map.put("true", TokenType.KW_TRUE);
        map.put("false", TokenType.KW_FALSE);
        map.put("null", TokenType.KW_NULL);

        // 内置类型
        map.put("Any", TokenType.KW_ANY);
        map.put("Unit", TokenType.KW_UNIT);
        map.put("Nothing", TokenType.KW_NOTHING);
        map.put("Int", TokenType.KW_INT);
        map.put("Long", TokenType.KW_LONG);
        map.put("Float", TokenType.KW_FLOAT);
        map.put("Double", TokenType.KW_DOUBLE);
        map.put("Boolean", TokenType.KW_BOOLEAN);
        map.put("Char", TokenType.KW_CHAR);
        map.put("String", TokenType.KW_STRING);
        map.put("Array", TokenType.KW_ARRAY);

        // 特殊
        map.put("this", TokenType.KW_THIS);
        map.put("super", TokenType.KW_SUPER);
        // "it"/"scope"/"launch" 是普通标识符，通过 Builtins 注册为全局函数
        map.put("await", TokenType.KW_AWAIT);

        KEYWORDS = Collections.unmodifiableMap(map);
    }

    /** 获取所有关键词集合（供 LSP 等外部工具使用） */
    public static Set<String> getKeywords() {
        return KEYWORDS.keySet();
    }

    public Lexer(String source, String fileName) {
        this(source, fileName, System.err);
    }

    public Lexer(String source, String fileName, PrintStream errStream) {
        this.source = source;
        this.fileName = fileName;
        this.errStream = errStream;
    }

    public Lexer(String source) {
        this(source, "<input>");
    }

    /** 释放源码字符串引用（解析完成后调用） */
    public void releaseSource() {
        source = null;
    }

    /**
     * 获取下一个 Token（流式接口）
     *
     * @return 下一个 Token
     */
    public Token nextToken() {
        // 跳过空白（但不跳过换行）
        skipWhitespace();

        if (isAtEnd()) {
            return new Token(TokenType.EOF, "", null, line, column, current);
        }

        start = current;
        scanToken();

        // 返回最后添加的 token
        if (!tokens.isEmpty()) {
            return tokens.remove(tokens.size() - 1);
        }

        // 如果没有生成 token（如注释），递归获取下一个
        return nextToken();
    }

    private void skipWhitespace() {
        while (!isAtEnd()) {
            char c = peek();
            if (c == ' ' || c == '\r' || c == '\t') {
                advance();
            } else {
                break;
            }
        }
    }

    /**
     * 执行词法分析，返回 Token 列表
     */
    public List<Token> scanTokens() {
        while (!isAtEnd()) {
            start = current;
            scanToken();
        }

        tokens.add(new Token(TokenType.EOF, "", null, line, column, current));
        return tokens;
    }

    private void scanToken() {
        char c = advance();
        switch (c) {
            // 单字符 Token
            case '(': addToken(TokenType.LPAREN); break;
            case ')': addToken(TokenType.RPAREN); break;
            case '{': addToken(TokenType.LBRACE); break;
            case '}': addToken(TokenType.RBRACE); break;
            case '[': addToken(TokenType.LBRACKET); break;
            case ']': addToken(TokenType.RBRACKET); break;
            case ',': addToken(TokenType.COMMA); break;
            case ';': addToken(TokenType.SEMICOLON); break;
            case '@': addToken(TokenType.AT); break;
            case '#': addToken(TokenType.HASH); break;
            case '_':
                if (isAlphaNumeric(peek())) {
                    identifier();
                } else {
                    addToken(TokenType.UNDERSCORE);
                }
                break;

            // 可能是多字符的 Token
            case '.':
                if (match('.')) {
                    addToken(match('<') ? TokenType.RANGE_EXCLUSIVE : TokenType.RANGE);
                } else {
                    addToken(TokenType.DOT);
                }
                break;

            case ':':
                addToken(match(':') ? TokenType.DOUBLE_COLON : TokenType.COLON);
                break;

            case '+':
                if (match('+')) addToken(TokenType.INC);
                else if (match('=')) addToken(TokenType.PLUS_ASSIGN);
                else addToken(TokenType.PLUS);
                break;

            case '-':
                if (match('-')) addToken(TokenType.DEC);
                else if (match('=')) addToken(TokenType.MINUS_ASSIGN);
                else if (match('>')) addToken(TokenType.ARROW);
                else addToken(TokenType.MINUS);
                break;

            case '*':
                addToken(match('=') ? TokenType.MUL_ASSIGN : TokenType.MUL);
                break;

            case '/':
                if (match('/')) {
                    // 单行注释
                    while (peek() != '\n' && !isAtEnd()) advance();
                } else if (match('*')) {
                    // 多行注释
                    blockComment();
                } else if (match('=')) {
                    addToken(TokenType.DIV_ASSIGN);
                } else {
                    addToken(TokenType.DIV);
                }
                break;

            case '%':
                addToken(match('=') ? TokenType.MOD_ASSIGN : TokenType.MOD);
                break;

            case '=':
                if (match('=')) {
                    addToken(match('=') ? TokenType.REF_EQ : TokenType.EQ);
                } else if (match('>')) {
                    addToken(TokenType.DOUBLE_ARROW);
                } else {
                    addToken(TokenType.ASSIGN);
                }
                break;

            case '!':
                if (match('=')) {
                    addToken(match('=') ? TokenType.REF_NE : TokenType.NE);
                } else if (match('!')) {
                    addToken(TokenType.NOT_NULL);
                } else {
                    addToken(TokenType.NOT);
                }
                break;

            case '<':
                addToken(match('=') ? TokenType.LE : TokenType.LT);
                break;

            case '>':
                addToken(match('=') ? TokenType.GE : TokenType.GT);
                break;

            case '&':
                if (match('&')) {
                    addToken(match('=') ? TokenType.AND_ASSIGN : TokenType.AND);
                } else {
                    error("Unexpected character '&'. Did you mean '&&'?");
                }
                break;

            case '|':
                if (match('|')) {
                    addToken(match('=') ? TokenType.OR_ASSIGN : TokenType.OR);
                } else if (match('>')) {
                    addToken(TokenType.PIPELINE);
                } else {
                    error("Unexpected character '|'. Did you mean '||' or '|>'?");
                }
                break;

            case '?':
                if (match('.')) {
                    addToken(TokenType.SAFE_DOT);
                } else if (match('[')) {
                    addToken(TokenType.SAFE_LBRACKET);
                } else if (match(':')) {
                    addToken(TokenType.ELVIS);
                } else if (match('?')) {
                    if (match('=')) {
                        addToken(TokenType.NULL_COALESCE_ASSIGN);
                    } else {
                        error("'??' is not supported. Use '?:' (Elvis operator) for null coalescing");
                    }
                } else {
                    addToken(TokenType.QUESTION);
                }
                break;

            case '$':
                addToken(TokenType.DOLLAR);
                break;

            // 空白字符
            case ' ':
            case '\r':
            case '\t':
                break;

            case '\n':
                addToken(TokenType.NEWLINE);
                newLine();
                break;

            // 字符串
            case '"':
                // 使用 peek 检查多行字符串，避免消耗字符
                if (peek() == '"' && peekNext() == '"') {
                    advance(); // 消耗第二个 "
                    advance(); // 消耗第三个 "
                    multilineString();
                } else {
                    string();
                }
                break;

            // 字符
            case '\'':
                character();
                break;

            // 原始字符串 r"..."
            case 'r':
                if (peek() == '"') {
                    advance();
                    rawString();
                } else {
                    identifier();
                }
                break;

            default:
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    error("Unexpected character: " + c);
                }
                break;
        }
    }

    // === 辅助方法 ===

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private char advance() {
        char c = source.charAt(current++);
        column++;
        return c;
    }

    private boolean match(char expected) {
        if (isAtEnd()) return false;
        if (source.charAt(current) != expected) return false;
        current++;
        column++;
        return true;
    }

    private char peek() {
        if (isAtEnd()) return '\0';
        return source.charAt(current);
    }

    private char peekNext() {
        if (current + 1 >= source.length()) return '\0';
        return source.charAt(current + 1);
    }

    private void newLine() {
        line++;
        column = 1;
        lineStart = current;
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z') ||
               c == '_' ||
               Character.isLetter(c);
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    // === Token 构建 ===

    private void addToken(TokenType type) {
        addToken(type, null);
    }

    private void addToken(TokenType type, Object literal) {
        String lexeme = source.substring(start, current);
        int tokenColumn = column - (current - start);
        tokens.add(new Token(type, lexeme, literal, line, tokenColumn, start));
    }

    // === 复杂 Token 扫描 ===

    private void string() {
        StringBuilder value = new StringBuilder();
        int braceDepth = 0; // 跟踪 ${...} 花括号深度

        while (!isAtEnd()) {
            if (peek() == '"' && braceDepth == 0) {
                break; // 只在插值外部才结束字符串
            }
            if (peek() == '\n') {
                error("Unterminated string");
                return;
            }
            if (peek() == '\\') {
                advance();
                value.append(escapeChar());
            } else {
                char c = advance();
                value.append(c);
                // 跟踪 ${...} 深度
                if (c == '$' && peek() == '{') {
                    value.append(advance()); // consume '{'
                    braceDepth++;
                } else if (c == '{' && braceDepth > 0) {
                    braceDepth++;
                } else if (c == '}' && braceDepth > 0) {
                    braceDepth--;
                }
            }
        }

        if (isAtEnd()) {
            error("Unterminated string");
            return;
        }

        advance(); // 闭合的 "
        addToken(TokenType.STRING_LITERAL, value.toString());
    }

    private void rawString() {
        while (peek() != '"' && !isAtEnd()) {
            if (peek() == '\n') newLine();
            advance();
        }

        if (isAtEnd()) {
            error("Unterminated raw string");
            return;
        }

        advance(); // 闭合的 "
        String value = source.substring(start + 2, current - 1); // 去掉 r" 和 "
        addToken(TokenType.RAW_STRING, value);
    }

    private void multilineString() {
        boolean terminated = false;
        while (!isAtEnd()) {
            // 不消耗地检查三个连续引号
            if (peek() == '"' && current + 2 < source.length()
                    && source.charAt(current + 1) == '"'
                    && source.charAt(current + 2) == '"') {
                advance(); // 消费第一个 "
                advance(); // 消费第二个 "
                advance(); // 消费第三个 "
                terminated = true;
                break;
            }
            if (peek() == '\n') newLine();
            advance();
        }

        if (!terminated) {
            error("Unterminated multiline string");
            return;
        }

        String value = source.substring(start + 3, current - 3);
        addToken(TokenType.MULTILINE_STRING, value);
    }

    private char escapeChar() {
        char c = advance();
        if (c == 'u') {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (isAtEnd()) {
                    error("Invalid unicode escape");
                    return '\0';
                }
                hex.append(advance());
            }
            try {
                return (char) Integer.parseInt(hex.toString(), 16);
            } catch (NumberFormatException e) {
                error("Invalid unicode escape: \\u" + hex);
                return '\0';
            }
        }
        int result = com.novalang.compiler.formatter.NovaStringUtils.unescapeChar(c);
        if (result < 0) {
            error("Invalid escape character: \\" + c);
            return c;
        }
        return (char) result;
    }

    private void character() {
        if (isAtEnd()) {
            error("Unterminated character literal");
            return;
        }

        char value;
        if (peek() == '\\') {
            advance();
            value = escapeChar();
        } else {
            value = advance();
        }

        if (peek() != '\'') {
            error("Unterminated character literal");
            return;
        }
        advance();

        addToken(TokenType.CHAR_LITERAL, value);
    }

    /** 移除数字中的下划线分隔符 */
    private static String stripUnderscores(String text) {
        return text.indexOf('_') >= 0 ? text.replace("_", "") : text;
    }

    /** 消耗数字字符和下划线分隔符 */
    private void advanceDigits() {
        while (isDigit(peek()) || peek() == '_') advance();
    }

    private void number() {
        // 检查进制
        if (source.charAt(start) == '0' && current < source.length()) {
            char next = Character.toLowerCase(peek());
            if (next == 'x') {
                hexNumber();
                return;
            } else if (next == 'b') {
                binaryNumber();
                return;
            } else if (next == 'o') {
                octalNumber();
                return;
            }
        }

        advanceDigits();

        // 小数部分
        if (peek() == '.' && isDigit(peekNext())) {
            advance(); // 消费 .
            advanceDigits();

            // 指数部分
            if (peek() == 'e' || peek() == 'E') {
                advance();
                if (peek() == '+' || peek() == '-') advance();
                advanceDigits();
            }

            // Float 后缀
            if (peek() == 'f' || peek() == 'F') {
                advance();
                String text = stripUnderscores(source.substring(start, current - 1));
                parseAndAddFloat(text);
            } else {
                String text = stripUnderscores(source.substring(start, current));
                parseAndAddDouble(text);
            }
        } else if (peek() == 'e' || peek() == 'E') {
            // 无小数点的科学计数法: 1e10, 1E-3
            advance();
            if (peek() == '+' || peek() == '-') advance();
            advanceDigits();

            if (peek() == 'f' || peek() == 'F') {
                advance();
                String text = stripUnderscores(source.substring(start, current - 1));
                parseAndAddFloat(text);
            } else {
                String text = stripUnderscores(source.substring(start, current));
                parseAndAddDouble(text);
            }
        } else {
            // Long 后缀
            if (peek() == 'L' || peek() == 'l') {
                advance();
                String text = stripUnderscores(source.substring(start, current - 1));
                parseAndAddLong(text);
            } else {
                String text = stripUnderscores(source.substring(start, current));
                parseAndAddInt(text);
            }
        }
    }

    private void hexNumber() {
        advance(); // 消费 'x'
        while (isHexDigit(peek()) || peek() == '_') advance();

        String text = stripUnderscores(source.substring(start + 2, current));
        if (peek() == 'L' || peek() == 'l') {
            advance();
            parseAndAddLong(text, 16);
        } else {
            parseAndAddInt(text, 16);
        }
    }

    private void binaryNumber() {
        advance(); // 消费 'b'
        while (peek() == '0' || peek() == '1' || peek() == '_') advance();

        String text = stripUnderscores(source.substring(start + 2, current));
        if (peek() == 'L' || peek() == 'l') {
            advance();
            parseAndAddLong(text, 2);
        } else {
            parseAndAddInt(text, 2);
        }
    }

    private void octalNumber() {
        advance(); // 消费 'o'
        while ((peek() >= '0' && peek() <= '7') || peek() == '_') advance();

        String text = stripUnderscores(source.substring(start + 2, current));
        if (peek() == 'L' || peek() == 'l') {
            advance();
            parseAndAddLong(text, 8);
        } else {
            parseAndAddInt(text, 8);
        }
    }

    private boolean isHexDigit(char c) {
        return isDigit(c) ||
               (c >= 'a' && c <= 'f') ||
               (c >= 'A' && c <= 'F');
    }

    private void identifier() {
        while (isAlphaNumeric(peek())) advance();

        String text = source.substring(start, current);
        TokenType type = KEYWORDS.get(text);
        if (type == null) type = TokenType.IDENTIFIER;
        addToken(type);
    }

    private void blockComment() {
        int depth = 1;
        while (depth > 0 && !isAtEnd()) {
            if (peek() == '/' && peekNext() == '*') {
                advance();
                advance();
                depth++;
            } else if (peek() == '*' && peekNext() == '/') {
                advance();
                advance();
                depth--;
            } else {
                if (peek() == '\n') newLine();
                advance();
            }
        }
        if (depth > 0) {
            error("Unterminated block comment");
        }
    }

    // ========== 数字解析辅助方法（统一溢出错误处理）==========

    private void parseAndAddInt(String text) { parseAndAddInt(text, 10); }

    private void parseAndAddInt(String text, int radix) {
        try {
            addToken(TokenType.INT_LITERAL, Integer.parseInt(text, radix));
        } catch (NumberFormatException e) {
            error("Invalid integer literal: " + source.substring(start, current));
        }
    }

    private void parseAndAddLong(String text) { parseAndAddLong(text, 10); }

    private void parseAndAddLong(String text, int radix) {
        try {
            addToken(TokenType.LONG_LITERAL, Long.parseLong(text, radix));
        } catch (NumberFormatException e) {
            error("Invalid long literal: " + source.substring(start, current));
        }
    }

    private void parseAndAddFloat(String text) {
        try {
            addToken(TokenType.FLOAT_LITERAL, Float.parseFloat(text));
        } catch (NumberFormatException e) {
            error("Invalid float literal: " + source.substring(start, current));
        }
    }

    private void parseAndAddDouble(String text) {
        try {
            addToken(TokenType.DOUBLE_LITERAL, Double.parseDouble(text));
        } catch (NumberFormatException e) {
            error("Invalid double literal: " + source.substring(start, current));
        }
    }

    private void error(String message) {
        String errorMsg = String.format("[%s:%d:%d] Lexer error: %s",
                fileName, line, column, message);
        errStream.println(errorMsg);
        addToken(TokenType.ERROR, message);
    }
}
