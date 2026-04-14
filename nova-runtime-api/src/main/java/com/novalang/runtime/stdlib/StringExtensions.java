package com.novalang.runtime.stdlib;

import com.novalang.runtime.Function1;
import com.novalang.runtime.NovaDynamic;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaException.ErrorKind;
import com.novalang.runtime.stdlib.internal.StringOps;

import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * String 类型扩展方法 — 解释器和编译器共享实现。
 */
@Ext("java/lang/String")
public final class StringExtensions {

    private StringExtensions() {}

    // ========== 无参方法 ==========

    public static Object length(Object str) {
        return StringOps.length((String) str);
    }

    public static Object isEmpty(Object str) {
        return StringOps.isEmpty((String) str);
    }

    public static Object isNotEmpty(Object str) {
        return StringOps.isNotEmpty((String) str);
    }

    public static Object isBlank(Object str) {
        return StringOps.isBlank((String) str);
    }

    public static Object toUpperCase(Object str) {
        return StringOps.toUpperCase((String) str);
    }

    public static Object toLowerCase(Object str) {
        return StringOps.toLowerCase((String) str);
    }

    public static Object trim(Object str) {
        return StringOps.trim((String) str);
    }

    public static Object uppercase(Object str) {
        return StringOps.toUpperCase((String) str);
    }

    public static Object lowercase(Object str) {
        return StringOps.toLowerCase((String) str);
    }

    public static Object trimStart(Object str) {
        return StringOps.trimStart((String) str);
    }

    public static Object trimEnd(Object str) {
        return StringOps.trimEnd((String) str);
    }

    public static Object reverse(Object str) {
        return StringOps.reverse((String) str);
    }

    public static Object capitalize(Object str) {
        return StringOps.capitalize((String) str);
    }

    public static Object decapitalize(Object str) {
        return StringOps.decapitalize((String) str);
    }

    public static Object toList(Object str) {
        return new ArrayList<Object>(StringOps.toCharacterList((String) str));
    }

    public static Object reversed(Object str) {
        return StringOps.reverse((String) str);
    }

    public static Object lines(Object str) {
        return new ArrayList<Object>(StringOps.lines((String) str));
    }

    public static Object chars(Object str) {
        return new ArrayList<Object>(StringOps.toCharacterList((String) str));
    }

    // ── 类型转换 ──

    public static Object toInt(Object str) {
        return StringOps.toInt((String) str);
    }

    public static Object toLong(Object str) {
        return StringOps.toLong((String) str);
    }

    public static Object toDouble(Object str) {
        return StringOps.toDouble((String) str);
    }

    public static Object toBoolean(Object str) {
        return StringOps.toBoolean((String) str);
    }

    public static Object toIntOrNull(Object str) {
        return StringOps.toIntOrNull((String) str);
    }

    public static Object toLongOrNull(Object str) {
        return StringOps.toLongOrNull((String) str);
    }

    public static Object toDoubleOrNull(Object str) {
        return StringOps.toDoubleOrNull((String) str);
    }

    // ========== 单参数方法 ==========

    public static Object split(Object str, Object separator) {
        if (!(str instanceof String)) {
            return NovaDynamic.invoke1(str, "split", separator);
        }
        return new ArrayList<Object>(StringOps.split((String) str, separator.toString()));
    }

    public static Object contains(Object str, Object sub) {
        return StringOps.contains((String) str, sub.toString());
    }

    public static Object startsWith(Object str, Object prefix) {
        return StringOps.startsWith((String) str, prefix.toString());
    }

    public static Object endsWith(Object str, Object suffix) {
        return StringOps.endsWith((String) str, suffix.toString());
    }

    public static Object indexOf(Object str, Object sub) {
        return StringOps.indexOf((String) str, sub.toString());
    }

    public static Object replace(Object str, Object target, Object replacement) {
        if (!(str instanceof String)) {
            return NovaDynamic.invoke2(str, "replace", target, replacement);
        }
        return StringOps.replace((String) str, target.toString(), replacement.toString());
    }

    public static Object take(Object str, Object n) {
        return StringOps.take((String) str, ((Number) n).intValue());
    }

    public static Object drop(Object str, Object n) {
        return StringOps.drop((String) str, ((Number) n).intValue());
    }

    public static Object takeLast(Object str, Object n) {
        return StringOps.takeLast((String) str, ((Number) n).intValue());
    }

    public static Object dropLast(Object str, Object n) {
        return StringOps.dropLast((String) str, ((Number) n).intValue());
    }

    public static Object repeat(Object str, Object n) {
        return StringOps.repeat((String) str, ((Number) n).intValue());
    }

    public static Object removePrefix(Object str, Object prefix) {
        return StringOps.removePrefix((String) str, prefix.toString());
    }

    public static Object removeSuffix(Object str, Object suffix) {
        return StringOps.removeSuffix((String) str, suffix.toString());
    }

    // ── 重载方法 ──

    public static Object substring(Object str, Object start) {
        return StringOps.substring((String) str, ((Number) start).intValue());
    }

    public static Object substring(Object str, Object start, Object end) {
        return StringOps.substring((String) str, ((Number) start).intValue(), ((Number) end).intValue());
    }

    public static Object padStart(Object str, Object length) {
        return padStart(str, length, (Object) ' ');
    }

    public static Object padStart(Object str, Object length, Object padChar) {
        return StringOps.padStart((String) str, ((Number) length).intValue(), toPadChar(padChar));
    }

    public static Object padEnd(Object str, Object length) {
        return padEnd(str, length, (Object) ' ');
    }

    public static Object padEnd(Object str, Object length, Object padChar) {
        return StringOps.padEnd((String) str, ((Number) length).intValue(), toPadChar(padChar));
    }

    public static Object format(Object str, Object... args) {
        return StringOps.format((String) str, args);
    }

    // ── Lambda 方法 ──

    public static Object count(Object str) {
        return ((String) str).length();
    }

    @SuppressWarnings("unchecked")
    public static Object count(Object str, Object predicate) {
        String s = (String) str;
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (isTruthy(invoke1(predicate, s.charAt(i)))) count++;
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    public static Object any(Object str, Object predicate) {
        String s = (String) str;
        for (int i = 0; i < s.length(); i++) {
            if (isTruthy(invoke1(predicate, s.charAt(i)))) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Object all(Object str, Object predicate) {
        String s = (String) str;
        for (int i = 0; i < s.length(); i++) {
            if (!isTruthy(invoke1(predicate, s.charAt(i)))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Object none(Object str, Object predicate) {
        String s = (String) str;
        for (int i = 0; i < s.length(); i++) {
            if (isTruthy(invoke1(predicate, s.charAt(i)))) return false;
        }
        return true;
    }

    public static Object lastIndexOf(Object str, Object substr) {
        return StringOps.lastIndexOf((String) str, substr.toString());
    }

    public static Object matches(Object str, Object regex) {
        if (!(str instanceof String)) {
            return NovaDynamic.invoke1(str, "matches", regex);
        }
        return StringOps.matches((String) str, regex.toString());
    }

    // ── 正则表达式扩展 ──

    /** 字面替换第一个匹配（非正则） */
    public static Object replaceFirst(Object str, Object target, Object replacement) {
        if (!(str instanceof String)) {
            return NovaDynamic.invoke2(str, "replaceFirst", target, replacement);
        }
        return StringOps.replaceFirstLiteral((String) str, target.toString(), replacement.toString());
    }

    /** 正则替换全部匹配 */
    public static Object replaceRegex(Object str, Object regex, Object replacement) {
        return ((String) str).replaceAll(regex.toString(), replacement.toString());
    }

    /** 正则替换第一个匹配 */
    public static Object replaceFirstRegex(Object str, Object regex, Object replacement) {
        return ((String) str).replaceFirst(regex.toString(), replacement.toString());
    }

    /** 正则查找第一个匹配，返回匹配字符串或 null */
    public static Object findRegex(Object str, Object regex) {
        java.util.regex.Matcher m = Pattern.compile(regex.toString()).matcher((String) str);
        return m.find() ? m.group() : null;
    }

    /** 正则查找所有匹配，返回字符串列表 */
    public static Object findAllRegex(Object str, Object regex) {
        java.util.regex.Matcher m = Pattern.compile(regex.toString()).matcher((String) str);
        java.util.List<Object> results = new java.util.ArrayList<>();
        while (m.find()) results.add(m.group());
        return results;
    }

    /** 正则分割 */
    public static Object splitRegex(Object str, Object regex) {
        return java.util.Arrays.asList(((String) str).split(regex.toString()));
    }

    /** 正则包含判断 */
    public static Object containsRegex(Object str, Object regex) {
        return Pattern.compile(regex.toString()).matcher((String) str).find();
    }

    /**
     * JS 风格 match：返回匹配结果 Map（类似 JS String.match()）。
     * <ul>
     *   <li>无匹配返回 null</li>
     *   <li>匹配返回 Map：{value: 全匹配, groups: [分组1, 分组2, ...], index: 起始位置}</li>
     * </ul>
     */
    public static Object match(Object str, Object regex) {
        java.util.regex.Matcher m = Pattern.compile(regex.toString()).matcher((String) str);
        if (!m.find()) return null;
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("value", m.group());
        result.put("index", m.start());
        java.util.List<Object> groups = new java.util.ArrayList<>();
        for (int i = 1; i <= m.groupCount(); i++) {
            groups.add(m.group(i));
        }
        result.put("groups", groups);
        return result;
    }

    /**
     * JS 风格 matchAll：返回所有匹配结果列表。
     * 每个元素是 Map：{value, groups, index}
     */
    public static Object matchAll(Object str, Object regex) {
        java.util.regex.Matcher m = Pattern.compile(regex.toString()).matcher((String) str);
        java.util.List<Object> results = new java.util.ArrayList<>();
        while (m.find()) {
            java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
            entry.put("value", m.group());
            entry.put("index", m.start());
            java.util.List<Object> groups = new java.util.ArrayList<>();
            for (int i = 1; i <= m.groupCount(); i++) {
                groups.add(m.group(i));
            }
            entry.put("groups", groups);
            results.add(entry);
        }
        return results;
    }

    // ── 单字符 Char 方法 ──

    public static Object isDigit(Object str) {
        String s = (String) str;
        return s.length() == 1 && Character.isDigit(s.charAt(0));
    }

    public static Object isLetter(Object str) {
        String s = (String) str;
        return s.length() == 1 && Character.isLetter(s.charAt(0));
    }

    public static Object isWhitespace(Object str) {
        String s = (String) str;
        return s.length() == 1 && Character.isWhitespace(s.charAt(0));
    }

    public static Object isUpperCase(Object str) {
        String s = (String) str;
        return s.length() == 1 && Character.isUpperCase(s.charAt(0));
    }

    public static Object isLowerCase(Object str) {
        String s = (String) str;
        return s.length() == 1 && Character.isLowerCase(s.charAt(0));
    }

    public static Object code(Object str) {
        String s = (String) str;
        if (s.length() != 1) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH, "code() 需要单字符字符串", "确保字符串长度为 1");
        return (int) s.charAt(0);
    }

    public static Object digitToInt(Object str) {
        String s = (String) str;
        if (s.length() != 1) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH, "digitToInt() 需要单字符字符串", "确保字符串长度为 1");
        char c = s.charAt(0);
        if (c >= '0' && c <= '9') return c - '0';
        throw new NovaException(ErrorKind.TYPE_MISMATCH, "'" + c + "' 不是数字字符", "确保字符在 '0'..'9' 范围内");
    }

    // ========== 辅助 ==========

    private static char toPadChar(Object arg) {
        if (arg instanceof Character) return (Character) arg;
        String s = arg.toString();
        return s.isEmpty() ? ' ' : s.charAt(0);
    }

    @SuppressWarnings("unchecked")
    // ========== Hutool 启发 ==========

    /** 截断字符串，超过 maxLen 时追加省略号 */
    public static Object truncate(Object str, Object maxLen) {
        String s = (String) str;
        int max = ((Number) maxLen).intValue();
        if (s.length() <= max) return s;
        return max <= 3 ? s.substring(0, max) : s.substring(0, max - 3) + "...";
    }

    /** 截断字符串，自定义省略符 */
    public static Object truncate(Object str, Object maxLen, Object ellipsis) {
        String s = (String) str;
        int max = ((Number) maxLen).intValue();
        String ell = String.valueOf(ellipsis);
        if (s.length() <= max) return s;
        int keep = max - ell.length();
        return keep <= 0 ? s.substring(0, max) : s.substring(0, keep) + ell;
    }

    /** 判断字符串是否为合法数字（整数/浮点/科学计数法） */
    public static Object isNumber(Object str) {
        String s = ((String) str).trim();
        if (s.isEmpty()) return false;
        try { Double.parseDouble(s); return true; }
        catch (NumberFormatException e) { return false; }
    }

    /** 字符串相似度（Levenshtein 距离，0.0~1.0） */
    public static Object similar(Object str, Object other) {
        String a = (String) str;
        String b = String.valueOf(other);
        if (a.equals(b)) return 1.0;
        int maxLen = Math.max(a.length(), b.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - (double) levenshtein(a, b) / maxLen;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1], curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    // ========== 辅助 ==========

    private static Object invoke1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        return ((Function<Object, Object>) lambda).apply(arg);
    }

    private static boolean isTruthy(Object value) {
        return LambdaUtils.isTruthy(value);
    }
}
