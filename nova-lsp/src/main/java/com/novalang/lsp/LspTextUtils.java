package com.novalang.lsp;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NovaAnalyzer 提取出的纯文本工具方法集合。
 *
 * <p>所有方法均为 static，不依赖 LSP 协议或 AST 类型。</p>
 */
final class LspTextUtils {

    private LspTextUtils() {}

    // ============ 正则模式 ============

    static final Pattern FUN_HEADER_PATTERN =
            Pattern.compile("\\bfun\\s+(?:[A-Z][a-zA-Z0-9_?]*\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*\\(([^)]*)\\)");

    static final Pattern FOR_VAR_PATTERN =
            Pattern.compile("\\bfor\\s*\\(\\s*(?:\\(([^)]+)\\)|([a-zA-Z_][a-zA-Z0-9_]*))\\s+in\\b");

    static final Pattern CATCH_VAR_PATTERN =
            Pattern.compile("\\bcatch\\s*\\(\\s*([a-zA-Z_][a-zA-Z0-9_]*)");

    static final Pattern LAMBDA_PARAM_PATTERN =
            Pattern.compile("\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*(?:\\s*,\\s*[a-zA-Z_][a-zA-Z0-9_]*)*)\\s*->");

    // ============ 字符 / 标识符 ============

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    static boolean isSimpleIdentifier(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!isIdentChar(s.charAt(i))) return false;
        }
        return true;
    }

    // ============ 字符串内上下文检测 ============

    static boolean isPositionInsideString(String lineText, int pos) {
        boolean inString = false;
        for (int i = 0; i < pos && i < lineText.length(); i++) {
            char c = lineText.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') inString = !inString;
        }
        return inString;
    }

    static boolean isInStringInterpolation(String content, int line, int character, String prefix) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return false;
        String lineText = lines[line];

        int prefixStart = character - prefix.length();
        if (prefixStart <= 0 || prefixStart > lineText.length()) return false;

        if (lineText.charAt(prefixStart - 1) == '$') {
            if (prefixStart >= 2 && lineText.charAt(prefixStart - 2) == '\\') return false;
            return isPositionInsideString(lineText, prefixStart - 1);
        }

        for (int i = prefixStart - 1; i >= 1; i--) {
            char c = lineText.charAt(i);
            if (c == '}' || (c == '"' && (i == 0 || lineText.charAt(i - 1) != '\\'))) break;
            if (c == '{' && lineText.charAt(i - 1) == '$') {
                return isPositionInsideString(lineText, i - 1);
            }
        }

        return false;
    }

    // ============ 括号匹配 ============

    static int findMatchingParen(String str, int closePos) {
        if (closePos < 0 || closePos >= str.length() || str.charAt(closePos) != ')') return -1;
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = closePos; i >= 0; i--) {
            char c = str.charAt(i);
            if (inString) {
                if (c == stringChar && (i == 0 || str.charAt(i - 1) != '\\')) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
            } else if (c == ')') {
                depth++;
            } else if (c == '(') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static int findMatchingBracket(String str, int closePos) {
        if (closePos < 0 || closePos >= str.length() || str.charAt(closePos) != ']') return -1;
        int depth = 0;
        for (int i = closePos; i >= 0; i--) {
            char c = str.charAt(i);
            if (c == ']') depth++;
            else if (c == '[') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static int findMatchingBrace(String str, int closePos) {
        if (closePos < 0 || closePos >= str.length() || str.charAt(closePos) != '}') return -1;
        int depth = 0;
        boolean inString = false;
        char stringChar = 0;
        for (int i = closePos; i >= 0; i--) {
            char c = str.charAt(i);
            if (inString) {
                if (c == stringChar && (i == 0 || str.charAt(i - 1) != '\\')) inString = false;
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                stringChar = c;
            } else if (c == '}') {
                depth++;
            } else if (c == '{') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static int findClosingParen(String str, int openPos) {
        if (openPos < 0 || openPos >= str.length() || str.charAt(openPos) != '(') return -1;
        int depth = 0;
        for (int i = openPos; i < str.length(); i++) {
            if (str.charAt(i) == '(') depth++;
            else if (str.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    static int findLastDotOutsideParens(String s) {
        int depth = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            else if (c == '.' && depth == 0) return i;
        }
        return -1;
    }

    // ============ 单词 / 前缀查找 ============

    static String getPrefix(String content, int line, int character) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return "";
        String lineText = lines[line];
        if (character < 0 || character > lineText.length()) return "";
        int start = character;
        while (start > 0 && isIdentChar(lineText.charAt(start - 1))) start--;
        return lineText.substring(start, character);
    }

    static String getWordAt(String content, int line, int character) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return null;
        String lineText = lines[line];
        if (character < 0 || character >= lineText.length()) return null;
        if (!isIdentChar(lineText.charAt(character))) return null;
        int start = character;
        while (start > 0 && isIdentChar(lineText.charAt(start - 1))) start--;
        int end = character;
        while (end < lineText.length() && isIdentChar(lineText.charAt(end))) end++;
        return lineText.substring(start, end);
    }

    static int findWholeWord(String text, String word) {
        int from = 0;
        while (true) {
            int idx = text.indexOf(word, from);
            if (idx < 0) return -1;
            int end = idx + word.length();
            boolean leftOk = idx == 0 || !isIdentChar(text.charAt(idx - 1));
            boolean rightOk = end >= text.length() || !isIdentChar(text.charAt(end));
            if (leftOk && rightOk) return idx;
            from = idx + 1;
        }
    }

    static int findNameInLine(String content, int lineIdx, String name, int startCol) {
        if (content == null) return startCol;
        String[] lines = content.split("\n", -1);
        if (lineIdx < 0 || lineIdx >= lines.length) return startCol;
        String lineText = lines[lineIdx];
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(lineText);
        if (m.find(startCol)) return m.start();
        return startCol;
    }

    static int findWordInLine(String lineText, String word, int near) {
        int start = near;
        while (start > 0 && isIdentChar(lineText.charAt(start - 1))) start--;
        if (start + word.length() <= lineText.length()
                && lineText.substring(start, start + word.length()).equals(word)) {
            return start;
        }
        int idx = lineText.indexOf(word);
        return idx >= 0 ? idx : near;
    }

    static String extractTrailingIdentifier(String text) {
        int end = text.length();
        while (end > 0 && isIdentChar(text.charAt(end - 1))) end--;
        return end < text.length() ? text.substring(end) : null;
    }

    static int correctColumnForWord(String[] lines, int line0, int col0, String word) {
        if (line0 < 0 || line0 >= lines.length) return col0;
        String lineText = lines[line0];
        if (col0 >= 0 && col0 + word.length() <= lineText.length()
                && word.equals(lineText.substring(col0, col0 + word.length()))) {
            boolean validStart = col0 == 0 || !isIdentChar(lineText.charAt(col0 - 1));
            boolean validEnd = col0 + word.length() >= lineText.length()
                    || !isIdentChar(lineText.charAt(col0 + word.length()));
            if (validStart && validEnd) return col0;
        }
        int idx = lineText.indexOf(word, Math.max(0, col0));
        while (idx >= 0) {
            boolean validStart = idx == 0 || !isIdentChar(lineText.charAt(idx - 1));
            boolean validEnd = idx + word.length() >= lineText.length()
                    || !isIdentChar(lineText.charAt(idx + word.length()));
            if (validStart && validEnd) return idx;
            idx = lineText.indexOf(word, idx + 1);
        }
        return col0;
    }

    // ============ 点号上下文 ============

    static String detectDotContext(String content, int line, int character, String prefix) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return null;
        String lineText = lines[line];
        int dotPos = character - prefix.length() - 1;
        if (dotPos < 0 || dotPos >= lineText.length()) return null;
        char ch = lineText.charAt(dotPos);
        if (ch == '.') return lineText.substring(0, dotPos);
        if (ch == '?' && dotPos + 1 < lineText.length() && lineText.charAt(dotPos + 1) == '.') {
            return lineText.substring(0, dotPos);
        }
        return null;
    }

    static String getTextBeforeDot(String content, int line, int character, String prefix) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return null;
        String lineText = lines[line];
        int dotPos = character - prefix.length() - 1;
        if (dotPos < 0 || dotPos >= lineText.length()) return null;
        char dotChar = lineText.charAt(dotPos);
        if (dotChar != '.' && dotChar != '?') return null;
        int end = dotPos;
        if (dotChar == '.' && end > 0 && lineText.charAt(end - 1) == '?') end--;
        int start = end - 1;
        while (start >= 0 && isIdentChar(lineText.charAt(start))) start--;
        start++;
        if (start >= end) return null;
        String ident = lineText.substring(start, end).trim();
        return ident.isEmpty() ? null : ident;
    }

    static String getExprBeforeDot(String content, int line, int character, String prefix) {
        String[] lines = content.split("\n", -1);
        if (line < 0 || line >= lines.length) return null;
        String lineText = lines[line];
        int dotPos = character - prefix.length() - 1;
        if (dotPos < 0 || dotPos >= lineText.length()) return null;
        String beforeDot = lineText.substring(0, dotPos).trim();
        if (beforeDot.endsWith("?")) beforeDot = beforeDot.substring(0, beforeDot.length() - 1).trim();
        return beforeDot.isEmpty() ? null : beforeDot;
    }

    // ============ 类型字符串工具 ============

    static String baseType(String type) {
        if (type == null) return null;
        String t = type;
        if (t.endsWith("?")) t = t.substring(0, t.length() - 1);
        String prefix = "";
        if (t.startsWith("java:")) {
            prefix = "java:";
            t = t.substring(5);
        }
        int idx = t.indexOf('<');
        return idx >= 0 ? prefix + t.substring(0, idx) : prefix + t;
    }

    static List<String> genericArgs(String type) {
        if (type == null) return Collections.emptyList();
        String t = type.startsWith("java:") ? type.substring(5) : type;
        int start = t.indexOf('<');
        int end = t.lastIndexOf('>');
        if (start < 0 || end <= start) return Collections.emptyList();
        return splitByComma(t.substring(start + 1, end));
    }

    static List<String> splitByComma(String s) {
        List<String> result = new ArrayList<String>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                result.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < s.length()) {
            result.add(s.substring(start).trim());
        }
        return result;
    }

    static String extractTypeWithGenerics(String typeStr) {
        int i = 0;
        StringBuilder result = new StringBuilder();
        while (i < typeStr.length() && (Character.isLetterOrDigit(typeStr.charAt(i))
                || typeStr.charAt(i) == '_' || typeStr.charAt(i) == '.')) {
            result.append(typeStr.charAt(i));
            i++;
        }
        if (i < typeStr.length() && typeStr.charAt(i) == '<') {
            int depth = 0;
            int start = i;
            while (i < typeStr.length()) {
                if (typeStr.charAt(i) == '<') depth++;
                else if (typeStr.charAt(i) == '>') {
                    depth--;
                    if (depth == 0) {
                        result.append(typeStr, start, i + 1);
                        break;
                    }
                }
                i++;
            }
        }
        String r = result.toString();
        return r.isEmpty() ? null : r;
    }

    static String findParamType(String paramsStr, String varName) {
        List<String> params = splitByComma(paramsStr);
        for (String param : params) {
            param = param.trim();
            while (param.startsWith("val ") || param.startsWith("var ")
                    || param.startsWith("private ") || param.startsWith("protected ")) {
                param = param.substring(param.indexOf(' ') + 1).trim();
            }
            int colonIdx = param.indexOf(':');
            if (colonIdx < 0) continue;
            String pName = param.substring(0, colonIdx).trim();
            if (pName.equals(varName)) {
                return extractTypeWithGenerics(param.substring(colonIdx + 1).trim());
            }
        }
        return null;
    }

    static String formatTypeForDisplay(String type) {
        if (type == null) return null;
        if (type.startsWith("java:")) {
            String fqn = type.substring(5);
            int lastDot = fqn.lastIndexOf('.');
            return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
        }
        return type;
    }

    // ============ 参数工具 ============

    static boolean containsParam(String paramsStr, String name) {
        for (String p : paramsStr.split(",")) {
            String param = p.trim();
            while (param.startsWith("val ") || param.startsWith("var ")) {
                param = param.substring(4).trim();
            }
            int colonIdx = param.indexOf(':');
            String paramName = colonIdx >= 0 ? param.substring(0, colonIdx).trim() : param.trim();
            if (name.equals(paramName)) return true;
        }
        return false;
    }

    static int findParamInLine(String line, String paramsStr, String name) {
        int paramsStart = line.indexOf(paramsStr);
        if (paramsStart < 0) {
            int parenIdx = line.indexOf('(');
            if (parenIdx >= 0) paramsStart = parenIdx + 1;
        }
        if (paramsStart >= 0) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\b").matcher(line);
            while (m.find()) {
                if (m.start() >= paramsStart) return m.start();
            }
        }
        return -1;
    }

    static int findVarDeclaration(String line, String name) {
        Matcher varMatcher = Pattern.compile("\\b(?:val|var)\\s+" + Pattern.quote(name) + "\\b").matcher(line);
        if (varMatcher.find()) return varMatcher.end() - name.length();

        Matcher funMatcher = FUN_HEADER_PATTERN.matcher(line.trim());
        if (funMatcher.find()) {
            String paramsStr = funMatcher.group(2);
            if (paramsStr != null && containsParam(paramsStr, name)) {
                int paramIdx = findParamInLine(line, paramsStr, name);
                if (paramIdx >= 0) return paramIdx;
            }
        }

        Matcher forMatcher = FOR_VAR_PATTERN.matcher(line.trim());
        if (forMatcher.find()) {
            String destructured = forMatcher.group(1);
            String singleVar = forMatcher.group(2);
            if (name.equals(singleVar) || (destructured != null && containsParam(destructured, name))) {
                int idx = line.indexOf(name, line.indexOf("for"));
                if (idx >= 0) return idx;
            }
        }

        Matcher catchMatcher = CATCH_VAR_PATTERN.matcher(line.trim());
        if (catchMatcher.find() && name.equals(catchMatcher.group(1))) {
            int idx = line.indexOf(name, line.indexOf("catch"));
            if (idx >= 0) return idx;
        }

        Matcher lambdaMatcher = LAMBDA_PARAM_PATTERN.matcher(line.trim());
        if (lambdaMatcher.find() && containsParam(lambdaMatcher.group(1), name)) {
            int idx = line.indexOf(name, line.indexOf("{"));
            if (idx >= 0) return idx;
        }

        return -1;
    }

    // ============ 其他 ============

    static int toOffset(String content, int line, int character) {
        int offset = 0;
        int currentLine = 0;
        for (int i = 0; i < content.length(); i++) {
            if (currentLine == line) {
                return offset + character;
            }
            if (content.charAt(i) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return offset;
    }

    static int findClosingBraceLine(String[] lines, int startLine) {
        int depth = 0;
        boolean found = false;
        for (int i = startLine; i < lines.length; i++) {
            for (char c : lines[i].toCharArray()) {
                if (c == '{') { depth++; found = true; }
                else if (c == '}') depth--;
            }
            if (found && depth <= 0) return i;
        }
        return startLine;
    }
}
