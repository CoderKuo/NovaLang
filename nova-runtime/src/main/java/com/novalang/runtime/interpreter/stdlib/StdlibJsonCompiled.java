package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.NovaException;
import com.novalang.runtime.interpreter.NovaRuntimeException;
import com.novalang.runtime.stdlib.spi.SerializationProviders;

import java.util.*;

/**
 * nova.json 模块的编译模式运行时实现。
 *
 * <p>委托给 {@link SerializationProviders#json()} 选择的 provider。
 * 默认使用内置手写 parser，classpath 有 Gson/FastJSON2 时自动切换。</p>
 */
public final class StdlibJsonCompiled {

    private StdlibJsonCompiled() {}

    public static Object jsonParse(Object text) {
        return SerializationProviders.json().parse(str(text));
    }

    public static Object jsonStringify(Object value) {
        return SerializationProviders.json().stringify(value);
    }

    public static Object jsonStringifyPretty(Object value) {
        return SerializationProviders.json().stringifyPretty(value, 2);
    }

    // ========== 内置实现入口（供 BuiltinJsonProvider 调用） ==========

    public static Object builtinParse(String text) {
        return new RawJsonParser(text).parse();
    }

    public static String builtinStringify(Object value, boolean pretty, int depth, int indent) {
        return stringify(value, pretty, depth, indent);
    }

    // ========== JSON Serializer（原生 Java 类型） ==========

    @SuppressWarnings("unchecked")
    private static String stringify(Object value, boolean pretty, int depth, int indent) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return value.toString();
        if (value instanceof String) return escapeJsonString((String) value);
        if (value instanceof List) {
            List<Object> list = (List<Object>) value;
            if (list.isEmpty()) return "[]";
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                if (pretty) { sb.append('\n'); appendIndent(sb, depth + 1, indent); }
                sb.append(stringify(list.get(i), pretty, depth + 1, indent));
            }
            if (pretty) { sb.append('\n'); appendIndent(sb, depth, indent); }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (map.isEmpty()) return "{}";
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) { sb.append('\n'); appendIndent(sb, depth + 1, indent); }
                sb.append(escapeJsonString(String.valueOf(e.getKey())));
                sb.append(':');
                if (pretty) sb.append(' ');
                sb.append(stringify(e.getValue(), pretty, depth + 1, indent));
            }
            if (pretty) { sb.append('\n'); appendIndent(sb, depth, indent); }
            sb.append('}');
            return sb.toString();
        }
        return escapeJsonString(String.valueOf(value));
    }

    private static String escapeJsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static void appendIndent(StringBuilder sb, int depth, int indent) {
        for (int i = 0; i < depth * indent; i++) sb.append(' ');
    }

    // ========== JSON Parser（返回原生 Java 类型） ==========

    static class RawJsonParser {
        private final String input;
        private int pos;

        RawJsonParser(String input) { this.input = input; this.pos = 0; }

        Object parse() {
            skipWhitespace();
            Object result = parseValue();
            skipWhitespace();
            return result;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 意外结束", null);
            char c = input.charAt(pos);
            switch (c) {
                case '"': return parseString();
                case '{': return parseObject();
                case '[': return parseArray();
                case 't': case 'f': return parseBoolean();
                case 'n': parseNull(); return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 位置 " + pos + " 处遇到意外字符: " + c, null);
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= input.length()) throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 字符串转义未终止", null);
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            if (pos + 4 > input.length()) throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON unicode 转义无效", null);
                            sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 无效转义: \\" + esc, null);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 字符串未终止", null);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= input.length()) throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 对象未终止", null);
                char c = input.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 位置 " + (pos - 1) + " 处期望 ',' 或 '}'", null);
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= input.length()) throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 数组未终止", null);
                char c = input.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 位置 " + (pos - 1) + " 处期望 ',' 或 ']'", null);
            }
        }

        private Number parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true; pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true; pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            String numStr = input.substring(start, pos);
            if (isFloat) return Double.parseDouble(numStr);
            long val = Long.parseLong(numStr);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) return (int) val;
            return val;
        }

        private Boolean parseBoolean() {
            if (input.startsWith("true", pos)) { pos += 4; return Boolean.TRUE; }
            if (input.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 位置 " + pos + " 处期望布尔值", null);
        }

        private void parseNull() {
            if (input.startsWith("null", pos)) { pos += 4; return; }
            throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 位置 " + pos + " 处期望 null", null);
        }

        private void expect(char c) {
            if (pos >= input.length() || input.charAt(pos) != c)
                throw new NovaRuntimeException(NovaException.ErrorKind.PARSE_ERROR, "JSON 位置 " + pos + " 处期望 '" + c + "'", null);
            pos++;
        }

        private void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                pos++;
            }
        }
    }

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return String.valueOf(o);
    }
}
