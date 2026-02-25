package com.novalang.compiler.formatter;

/**
 * Nova 源码字符串转义/反转义工具
 */
public final class NovaStringUtils {

    private NovaStringUtils() {}

    /**
     * 反转义：将转义字符标识符转为实际字符。
     * <p>给定反斜杠后面的字符（如 'n'），返回对应的实际字符（如 '\n'）。
     * Unicode 转义 (&#92;uXXXX) 由调用者自行处理。</p>
     *
     * @return 反转义后的字符，未识别的转义返回 -1
     */
    public static int unescapeChar(char c) {
        switch (c) {
            case 'n':  return '\n';
            case 'r':  return '\r';
            case 't':  return '\t';
            case 'b':  return '\b';
            case 'f':  return '\f';
            case '0':  return '\0';
            case '\\': return '\\';
            case '\'': return '\'';
            case '"':  return '"';
            case '$':  return '$';
            default:   return -1;
        }
    }

    /** 转义字符串内容（用于双引号包裹的字符串） */
    public static String escapeString(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 转义单个字符（用于单引号包裹的字符字面量） */
    public static String escapeChar(char c) {
        switch (c) {
            case '\\': return "\\\\";
            case '\'': return "\\'";
            case '\n': return "\\n";
            case '\r': return "\\r";
            case '\t': return "\\t";
            default: return String.valueOf(c);
        }
    }
}
