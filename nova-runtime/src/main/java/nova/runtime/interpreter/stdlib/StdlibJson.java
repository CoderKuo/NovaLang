package nova.runtime.interpreter.stdlib;
import nova.runtime.*;
import nova.runtime.types.Environment;

import nova.runtime.interpreter.*;

/**
 * nova.json — JSON 解析与序列化（手写递归下降 parser，零外部依赖）
 */
public final class StdlibJson {

    private StdlibJson() {}

    public static void register(Environment env, Interpreter interp) {
        env.defineVal("jsonParse", NovaNativeFunction.create("jsonParse", (text) -> {
            return new JsonParser(text.asString()).parse();
        }));

        env.defineVal("jsonStringify", NovaNativeFunction.create("jsonStringify", (value) -> {
            return NovaString.of(stringify(value, false, 0, 2));
        }));

        env.defineVal("jsonStringifyPretty", new NovaNativeFunction("jsonStringifyPretty", -1, (interpreter, args) -> {
            NovaValue value = args.get(0);
            int indent = args.size() > 1 ? args.get(1).asInt() : 2;
            return NovaString.of(stringify(value, true, 0, indent));
        }));
    }

    // ========== JSON Serializer ==========

    private static String stringify(NovaValue value, boolean pretty, int depth, int indent) {
        if (value == null || value.isNull()) return "null";
        if (value instanceof NovaBoolean) return value.asString();
        if (value instanceof NovaInt || value instanceof NovaLong || value instanceof NovaDouble || value instanceof NovaFloat) {
            return value.asString();
        }
        if (value instanceof NovaString) return escapeJsonString(value.asString());
        if (value instanceof NovaList) {
            NovaList list = (NovaList) value;
            if (list.size() == 0) return "[]";
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                if (pretty) { sb.append('\n'); appendIndent(sb, depth + 1, indent); }
                sb.append(stringify(list.get(i), pretty, depth + 1, indent));
            }
            if (pretty) { sb.append('\n'); appendIndent(sb, depth, indent); }
            sb.append(']');
            return sb.toString();
        }
        if (value instanceof NovaMap) {
            NovaMap map = (NovaMap) value;
            if (map.size() == 0) return "{}";
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            for (java.util.Map.Entry<NovaValue, NovaValue> e : map.getEntries().entrySet()) {
                if (!first) sb.append(',');
                first = false;
                if (pretty) { sb.append('\n'); appendIndent(sb, depth + 1, indent); }
                sb.append(escapeJsonString(e.getKey().asString()));
                sb.append(':');
                if (pretty) sb.append(' ');
                sb.append(stringify(e.getValue(), pretty, depth + 1, indent));
            }
            if (pretty) { sb.append('\n'); appendIndent(sb, depth, indent); }
            sb.append('}');
            return sb.toString();
        }
        // 其他类型转字符串
        return escapeJsonString(value.asString());
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

    // ========== JSON Parser（递归下降） ==========

    static class JsonParser {
        private final String input;
        private int pos;

        JsonParser(String input) {
            this.input = input;
            this.pos = 0;
        }

        NovaValue parse() {
            skipWhitespace();
            NovaValue result = parseValue();
            skipWhitespace();
            return result;
        }

        private NovaValue parseValue() {
            skipWhitespace();
            if (pos >= input.length()) throw new NovaRuntimeException("Unexpected end of JSON");
            char c = input.charAt(pos);
            switch (c) {
                case '"': return parseString();
                case '{': return parseObject();
                case '[': return parseArray();
                case 't': case 'f': return parseBoolean();
                case 'n': return parseNull();
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) return parseNumber();
                    throw new NovaRuntimeException("Unexpected char in JSON at position " + pos + ": " + c);
            }
        }

        private NovaString parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return NovaString.of(sb.toString());
                if (c == '\\') {
                    if (pos >= input.length()) throw new NovaRuntimeException("Unterminated string escape");
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
                            if (pos + 4 > input.length()) throw new NovaRuntimeException("Invalid unicode escape");
                            sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: throw new NovaRuntimeException("Invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new NovaRuntimeException("Unterminated string");
        }

        private NovaMap parseObject() {
            expect('{');
            NovaMap map = new NovaMap();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                NovaString key = parseString();
                skipWhitespace();
                expect(':');
                NovaValue value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= input.length()) throw new NovaRuntimeException("Unterminated object");
                char c = input.charAt(pos++);
                if (c == '}') return map;
                if (c != ',') throw new NovaRuntimeException("Expected ',' or '}' in object at position " + (pos - 1));
            }
        }

        private NovaList parseArray() {
            expect('[');
            NovaList list = new NovaList();
            skipWhitespace();
            if (pos < input.length() && input.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= input.length()) throw new NovaRuntimeException("Unterminated array");
                char c = input.charAt(pos++);
                if (c == ']') return list;
                if (c != ',') throw new NovaRuntimeException("Expected ',' or ']' in array at position " + (pos - 1));
            }
        }

        private NovaValue parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            String numStr = input.substring(start, pos);
            if (isFloat) return NovaDouble.of(Double.parseDouble(numStr));
            long val = Long.parseLong(numStr);
            if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) return NovaInt.of((int) val);
            return NovaLong.of(val);
        }

        private NovaBoolean parseBoolean() {
            if (input.startsWith("true", pos)) { pos += 4; return NovaBoolean.TRUE; }
            if (input.startsWith("false", pos)) { pos += 5; return NovaBoolean.FALSE; }
            throw new NovaRuntimeException("Expected boolean at position " + pos);
        }

        private NovaValue parseNull() {
            if (input.startsWith("null", pos)) { pos += 4; return NovaNull.NULL; }
            throw new NovaRuntimeException("Expected null at position " + pos);
        }

        private void expect(char c) {
            if (pos >= input.length() || input.charAt(pos) != c)
                throw new NovaRuntimeException("Expected '" + c + "' at position " + pos);
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
}
