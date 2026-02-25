package com.novalang.compiler.parser;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.lexer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * 字面量解析辅助类：字符串/数值/字符的解析和转换
 */
class LiteralHelper {

    final Parser parser;

    LiteralHelper(Parser parser) {
        this.parser = parser;
    }

    char parseCharValue(String value) {
        // 去掉引号
        value = value.substring(1, value.length() - 1);
        if (value.startsWith("\\")) {
            return parseEscapeChar(value);
        }
        return value.charAt(0);
    }

    char parseEscapeChar(String escape) {
        if (escape.length() < 2) return escape.charAt(0);
        char c = escape.charAt(1);
        if (c == 'u' && escape.length() >= 6) {
            return (char) Integer.parseInt(escape.substring(2, 6), 16);
        }
        int result = com.novalang.compiler.formatter.NovaStringUtils.unescapeChar(c);
        return result >= 0 ? (char) result : c;
    }

    /**
     * 检查字符串内容是否包含未转义的 $ 插值
     */
    boolean hasInterpolation(String content) {
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '$' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == '{' || Character.isLetter(next) || next == '_') {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 将含有 $ 插值的字符串解析为 StringInterpolation AST 节点
     */
    Expression buildStringInterpolation(SourceLocation loc, String content) {
        List<StringInterpolation.StringPart> parts = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int i = 0;

        while (i < content.length()) {
            char c = content.charAt(i);

            if (c == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                switch (next) {
                    case 'n': literal.append('\n'); break;
                    case 'r': literal.append('\r'); break;
                    case 't': literal.append('\t'); break;
                    case '\\': literal.append('\\'); break;
                    case '"': literal.append('"'); break;
                    case '$': literal.append('$'); break;
                    default: literal.append(c); literal.append(next); break;
                }
                i += 2;
            } else if (c == '$' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);

                if (next == '{') {
                    // ${expression}
                    if (literal.length() > 0) {
                        parts.add(new StringInterpolation.LiteralPart(loc, literal.toString()));
                        literal.setLength(0);
                    }
                    int dollarOffset = i; // $ 在 content 中的偏移
                    i += 2; // skip ${
                    int braceDepth = 1;
                    StringBuilder exprStr = new StringBuilder();
                    while (i < content.length() && braceDepth > 0) {
                        char ch = content.charAt(i);
                        if (ch == '{') braceDepth++;
                        else if (ch == '}') braceDepth--;
                        if (braceDepth > 0) exprStr.append(ch);
                        i++;
                    }
                    // 用子 Lexer + 子 Parser 解析表达式
                    Lexer subLexer = new Lexer(exprStr.toString(), "<interpolation>");
                    Parser subParser = new Parser(subLexer, "<interpolation>");
                    try {
                        Expression expr = subParser.parseExpression();
                        parts.add(new StringInterpolation.ExprPart(loc, expr));
                    } catch (ParseException e) {
                        // 计算 ${} 在源码中的实际位置
                        int interpCol = parser.previous.getColumn() + 1 + dollarOffset;
                        int interpLen = exprStr.length() + 3; // ${...}
                        Token interpToken = new Token(
                            parser.previous.getType(), "${" + exprStr + "}",
                            null, parser.previous.getLine(), interpCol, parser.previous.getOffset() + 1 + dollarOffset);
                        throw new ParseException(
                            "Invalid expression in string interpolation: ${" + exprStr + "}",
                            interpToken);
                    }
                } else if (Character.isLetter(next) || next == '_') {
                    // $identifier
                    if (literal.length() > 0) {
                        parts.add(new StringInterpolation.LiteralPart(loc, literal.toString()));
                        literal.setLength(0);
                    }
                    i++; // skip $
                    StringBuilder ident = new StringBuilder();
                    while (i < content.length() &&
                           (Character.isLetterOrDigit(content.charAt(i)) || content.charAt(i) == '_')) {
                        ident.append(content.charAt(i));
                        i++;
                    }
                    parts.add(new StringInterpolation.ExprPart(loc, new Identifier(loc, ident.toString())));
                } else {
                    literal.append(c);
                    i++;
                }
            } else {
                literal.append(c);
                i++;
            }
        }

        if (literal.length() > 0) {
            parts.add(new StringInterpolation.LiteralPart(loc, literal.toString()));
        }

        return new StringInterpolation(loc, parts);
    }

    String parseStringValue(String value) {
        // 去掉引号
        if (value.startsWith("\"\"\"")) {
            value = value.substring(3, value.length() - 3);
        } else if (value.startsWith("r\"") || value.startsWith("R\"")) {
            value = value.substring(2, value.length() - 1);
            return value;  // 原始字符串不处理转义
        } else {
            value = value.substring(1, value.length() - 1);
        }

        // 处理转义字符
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '$': sb.append('$'); i++; break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
