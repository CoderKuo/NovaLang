package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.stdlib.spi.SerializationProviders;

import java.util.*;

/**
 * nova.yaml 模块 — 编译模式运行时实现。
 * <p>委托给 {@link SerializationProviders#yaml()} 选择的 provider。
 * 默认使用内置手写 parser，classpath 有 SnakeYAML/Bukkit 时自动切换。</p>
 */
public final class StdlibYamlCompiled {

    private StdlibYamlCompiled() {}

    // ============ 公共 API（委托 provider） ============

    public static Object yamlParse(Object text) {
        return SerializationProviders.yaml().parse(text == null ? "" : text.toString());
    }

    public static Object yamlStringify(Object value) {
        return SerializationProviders.yaml().stringify(value);
    }

    // ============ 内置实现入口（供 BuiltinYamlProvider 调用） ============

    public static Object builtinParse(String text) {
        List<String> lines = splitLines(text);
        return new YamlParser(lines).parse();
    }

    public static String builtinStringify(Object value) {
        StringBuilder sb = new StringBuilder();
        stringify(value, sb, 0);
        return sb.toString();
    }

    // ============ YAML Parser ============

    private static class YamlParser {
        private final List<String> lines;
        private int pos;

        YamlParser(List<String> lines) {
            this.lines = lines;
            this.pos = 0;
        }

        Object parse() {
            skipEmptyAndComments();
            if (pos >= lines.size()) return new LinkedHashMap<>();
            String first = lines.get(pos);
            String trimmed = first.trim();
            // 顶层是列表
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                return parseList(indent(first));
            }
            // 顶层是 Map
            return parseMap(indent(first));
        }

        private Map<String, Object> parseMap(int baseIndent) {
            Map<String, Object> map = new LinkedHashMap<>();
            while (pos < lines.size()) {
                skipEmptyAndComments();
                if (pos >= lines.size()) break;
                String line = lines.get(pos);
                int ind = indent(line);
                if (ind < baseIndent) break;
                if (ind > baseIndent) break; // 不属于当前层

                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) { pos++; continue; }

                int colonIdx = findColon(trimmed);
                if (colonIdx < 0) { pos++; continue; }

                String key = trimmed.substring(0, colonIdx).trim();
                // 去除引号 key
                key = unquote(key);
                String afterColon = trimmed.substring(colonIdx + 1).trim();

                if (afterColon.isEmpty()) {
                    // 值在下一行（嵌套 Map 或 List）
                    pos++;
                    skipEmptyAndComments();
                    if (pos < lines.size()) {
                        String nextLine = lines.get(pos);
                        int nextInd = indent(nextLine);
                        String nextTrimmed = nextLine.trim();
                        if (nextInd > ind) {
                            if (nextTrimmed.startsWith("- ") || nextTrimmed.equals("-")) {
                                map.put(key, parseList(nextInd));
                            } else {
                                map.put(key, parseMap(nextInd));
                            }
                        } else {
                            map.put(key, null);
                        }
                    } else {
                        map.put(key, null);
                    }
                } else if (afterColon.equals("|") || afterColon.equals(">")) {
                    // 多行字符串
                    boolean literal = afterColon.equals("|");
                    pos++;
                    map.put(key, parseMultilineString(ind, literal));
                } else {
                    map.put(key, parseScalar(afterColon));
                    pos++;
                }
            }
            return map;
        }

        private List<Object> parseList(int baseIndent) {
            List<Object> list = new ArrayList<>();
            while (pos < lines.size()) {
                skipEmptyAndComments();
                if (pos >= lines.size()) break;
                String line = lines.get(pos);
                int ind = indent(line);
                if (ind < baseIndent) break;
                if (ind > baseIndent) break;

                String trimmed = line.trim();
                if (!trimmed.startsWith("- ") && !trimmed.equals("-")) break;

                String itemText = trimmed.equals("-") ? "" : trimmed.substring(2).trim();

                if (itemText.isEmpty()) {
                    // 嵌套结构
                    pos++;
                    skipEmptyAndComments();
                    if (pos < lines.size()) {
                        int nextInd = indent(lines.get(pos));
                        String nextTrimmed = lines.get(pos).trim();
                        if (nextInd > ind) {
                            if (nextTrimmed.startsWith("- ") || nextTrimmed.equals("-")) {
                                list.add(parseList(nextInd));
                            } else if (findColon(nextTrimmed) >= 0) {
                                list.add(parseMap(nextInd));
                            } else {
                                list.add(null);
                            }
                        } else {
                            list.add(null);
                        }
                    } else {
                        list.add(null);
                    }
                } else if (findColon(itemText) >= 0 && !isQuoted(itemText)) {
                    // 列表项是 inline map: - key: value
                    // 将 "key: value" 当作单行 Map 解析
                    Map<String, Object> inlineMap = new LinkedHashMap<>();
                    int c = findColon(itemText);
                    String k = unquote(itemText.substring(0, c).trim());
                    String v = itemText.substring(c + 1).trim();
                    inlineMap.put(k, v.isEmpty() ? null : parseScalar(v));
                    pos++;
                    // 检查后续缩进行是否属于此 inline map
                    while (pos < lines.size()) {
                        String nextLine = lines.get(pos);
                        int nextInd = indent(nextLine);
                        if (nextInd <= ind) break;
                        String nt = nextLine.trim();
                        if (nt.isEmpty() || nt.startsWith("#")) { pos++; continue; }
                        int nc = findColon(nt);
                        if (nc >= 0) {
                            String nk = unquote(nt.substring(0, nc).trim());
                            String nv = nt.substring(nc + 1).trim();
                            inlineMap.put(nk, nv.isEmpty() ? null : parseScalar(nv));
                            pos++;
                        } else {
                            break;
                        }
                    }
                    list.add(inlineMap);
                } else {
                    list.add(parseScalar(itemText));
                    pos++;
                }
            }
            return list;
        }

        private String parseMultilineString(int baseIndent, boolean literal) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            while (pos < lines.size()) {
                String line = lines.get(pos);
                if (line.trim().isEmpty()) {
                    // 空行保留
                    if (!first) sb.append(literal ? "\n" : "\n");
                    pos++;
                    continue;
                }
                int ind = indent(line);
                if (ind <= baseIndent) break;
                if (!first) sb.append(literal ? "\n" : " ");
                sb.append(line.substring(Math.min(line.length(), baseIndent + 2)));
                first = false;
                pos++;
            }
            return sb.toString();
        }

        private void skipEmptyAndComments() {
            while (pos < lines.size()) {
                String trimmed = lines.get(pos).trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) { pos++; continue; }
                break;
            }
        }

        private static int indent(String line) {
            int count = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == ' ') count++;
                else break;
            }
            return count;
        }

        /** 查找非引号内的冒号位置 */
        private static int findColon(String s) {
            boolean inSingle = false, inDouble = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\'' && !inDouble) inSingle = !inSingle;
                else if (c == '"' && !inSingle) inDouble = !inDouble;
                else if (c == ':' && !inSingle && !inDouble) {
                    // 冒号后必须是空格、行尾，或字符串末尾
                    if (i + 1 >= s.length() || s.charAt(i + 1) == ' ') return i;
                }
            }
            return -1;
        }

        private static boolean isQuoted(String s) {
            return (s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"));
        }
    }

    // ============ 标量值解析 ============

    static Object parseScalar(String s) {
        if (s.isEmpty()) return null;

        // 引号字符串
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            return unquote(s);
        }

        // null
        if ("null".equals(s) || "~".equals(s)) return null;

        // 布尔值
        if ("true".equals(s) || "yes".equals(s) || "on".equals(s)) return true;
        if ("false".equals(s) || "no".equals(s) || "off".equals(s)) return false;

        // 数字
        try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        try { return Long.parseLong(s); } catch (NumberFormatException ignored) {}
        try {
            double d = Double.parseDouble(s);
            if (!s.contains("Infinity") && !s.contains("NaN")) return d;
        } catch (NumberFormatException ignored) {}

        // 裸字符串
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            if ((s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"')
                    || (s.charAt(0) == '\'' && s.charAt(s.length() - 1) == '\'')) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    // ============ YAML 生成器 ============

    @SuppressWarnings("unchecked")
    private static void stringify(Object value, StringBuilder sb, int indent) {
        if (value == null) {
            sb.append("null\n");
        } else if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                sb.append("{}\n");
                return;
            }
            if (indent > 0) sb.append("\n");
            String pad = spaces(indent);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append(pad).append(stringifyKey(entry.getKey())).append(": ");
                Object v = entry.getValue();
                if (v instanceof Map || v instanceof List) {
                    stringify(v, sb, indent + 2);
                } else {
                    stringifyScalar(v, sb);
                    sb.append("\n");
                }
            }
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                sb.append("[]\n");
                return;
            }
            if (indent > 0) sb.append("\n");
            String pad = spaces(indent);
            for (Object item : list) {
                sb.append(pad).append("- ");
                if (item instanceof Map || item instanceof List) {
                    stringify(item, sb, indent + 2);
                } else {
                    stringifyScalar(item, sb);
                    sb.append("\n");
                }
            }
        } else {
            stringifyScalar(value, sb);
            sb.append("\n");
        }
    }

    private static void stringifyScalar(Object value, StringBuilder sb) {
        if (value == null) { sb.append("null"); return; }
        if (value instanceof Boolean || value instanceof Number) { sb.append(value); return; }
        String s = value.toString();
        // 需要引号的情况：含特殊字符、空字符串、像数字/布尔的字符串
        if (s.isEmpty() || needsQuote(s)) {
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else {
            sb.append(s);
        }
    }

    private static String stringifyKey(Object key) {
        String s = key == null ? "null" : key.toString();
        if (s.contains(": ") || s.contains("#") || s.startsWith("- ") || needsQuote(s)) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    private static boolean needsQuote(String s) {
        if (s.isEmpty()) return true;
        // 像布尔值或 null 的字符串需要引号
        String lower = s.toLowerCase();
        if ("true".equals(lower) || "false".equals(lower) || "yes".equals(lower) || "no".equals(lower)
                || "on".equals(lower) || "off".equals(lower) || "null".equals(lower) || "~".equals(lower)) {
            return true;
        }
        // 像数字的字符串需要引号
        try { Double.parseDouble(s); return true; } catch (NumberFormatException ignored) {}
        // 含换行或冒号的
        return s.contains("\n") || s.contains(": ") || s.startsWith("{") || s.startsWith("[");
    }

    private static String spaces(int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, ' ');
        return new String(chars);
    }

    private static List<String> splitLines(String input) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            if (input.charAt(i) == '\n') {
                lines.add(input.substring(start, i));
                start = i + 1;
            }
        }
        if (start <= input.length()) {
            lines.add(input.substring(start));
        }
        return lines;
    }
}
