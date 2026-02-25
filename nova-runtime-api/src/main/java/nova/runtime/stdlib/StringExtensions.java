package nova.runtime.stdlib;

import nova.runtime.Function1;

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
        return ((String) str).length();
    }

    public static Object isEmpty(Object str) {
        return ((String) str).isEmpty();
    }

    public static Object isNotEmpty(Object str) {
        return !((String) str).isEmpty();
    }

    public static Object isBlank(Object str) {
        return ((String) str).trim().isEmpty();
    }

    public static Object toUpperCase(Object str) {
        return ((String) str).toUpperCase();
    }

    public static Object toLowerCase(Object str) {
        return ((String) str).toLowerCase();
    }

    public static Object trim(Object str) {
        return ((String) str).trim();
    }

    public static Object uppercase(Object str) {
        return ((String) str).toUpperCase();
    }

    public static Object lowercase(Object str) {
        return ((String) str).toLowerCase();
    }

    public static Object trimStart(Object str) {
        String s = (String) str;
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(i);
    }

    public static Object trimEnd(Object str) {
        String s = (String) str;
        int i = s.length() - 1;
        while (i >= 0 && Character.isWhitespace(s.charAt(i))) i--;
        return s.substring(0, i + 1);
    }

    public static Object reverse(Object str) {
        return new StringBuilder((String) str).reverse().toString();
    }

    public static Object capitalize(Object str) {
        String s = (String) str;
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public static Object decapitalize(Object str) {
        String s = (String) str;
        if (s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public static Object toList(Object str) {
        String s = (String) str;
        List<Object> result = new ArrayList<>(s.length());
        for (int i = 0; i < s.length(); i++) {
            result.add(s.charAt(i));
        }
        return result;
    }

    public static Object lines(Object str) {
        return new ArrayList<>(Arrays.asList(((String) str).split("\\r?\\n", -1)));
    }

    public static Object chars(Object str) {
        String s = (String) str;
        List<Object> result = new ArrayList<>(s.length());
        for (int i = 0; i < s.length(); i++) {
            result.add(s.charAt(i));
        }
        return result;
    }

    // ── 类型转换 ──

    public static Object toInt(Object str) {
        return Integer.parseInt((String) str);
    }

    public static Object toLong(Object str) {
        return Long.parseLong((String) str);
    }

    public static Object toDouble(Object str) {
        return Double.parseDouble((String) str);
    }

    public static Object toBoolean(Object str) {
        return Boolean.parseBoolean((String) str);
    }

    public static Object toIntOrNull(Object str) {
        try { return Integer.parseInt((String) str); }
        catch (NumberFormatException e) { return null; }
    }

    public static Object toLongOrNull(Object str) {
        try { return Long.parseLong((String) str); }
        catch (NumberFormatException e) { return null; }
    }

    public static Object toDoubleOrNull(Object str) {
        try { return Double.parseDouble((String) str); }
        catch (NumberFormatException e) { return null; }
    }

    // ========== 单参数方法 ==========

    public static Object split(Object str, Object separator) {
        return new ArrayList<>(Arrays.asList(
                ((String) str).split(Pattern.quote(separator.toString()), -1)));
    }

    public static Object contains(Object str, Object sub) {
        return ((String) str).contains(sub.toString());
    }

    public static Object startsWith(Object str, Object prefix) {
        return ((String) str).startsWith(prefix.toString());
    }

    public static Object endsWith(Object str, Object suffix) {
        return ((String) str).endsWith(suffix.toString());
    }

    public static Object indexOf(Object str, Object sub) {
        return ((String) str).indexOf(sub.toString());
    }

    public static Object replace(Object str, Object target, Object replacement) {
        return ((String) str).replace(target.toString(), replacement.toString());
    }

    public static Object take(Object str, Object n) {
        String s = (String) str;
        int count = ((Number) n).intValue();
        return s.substring(0, Math.min(count, s.length()));
    }

    public static Object drop(Object str, Object n) {
        String s = (String) str;
        int count = ((Number) n).intValue();
        return s.substring(Math.min(count, s.length()));
    }

    public static Object takeLast(Object str, Object n) {
        String s = (String) str;
        int count = ((Number) n).intValue();
        return s.substring(Math.max(0, s.length() - count));
    }

    public static Object dropLast(Object str, Object n) {
        String s = (String) str;
        int count = ((Number) n).intValue();
        return s.substring(0, Math.max(0, s.length() - count));
    }

    public static Object repeat(Object str, Object n) {
        String s = (String) str;
        int count = ((Number) n).intValue();
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    public static Object removePrefix(Object str, Object prefix) {
        String s = (String) str;
        String p = prefix.toString();
        return s.startsWith(p) ? s.substring(p.length()) : s;
    }

    public static Object removeSuffix(Object str, Object suffix) {
        String s = (String) str;
        String sf = suffix.toString();
        return s.endsWith(sf) ? s.substring(0, s.length() - sf.length()) : s;
    }

    // ── 重载方法 ──

    public static Object substring(Object str, Object start) {
        return ((String) str).substring(((Number) start).intValue());
    }

    public static Object substring(Object str, Object start, Object end) {
        return ((String) str).substring(((Number) start).intValue(), ((Number) end).intValue());
    }

    public static Object padStart(Object str, Object length) {
        return padStart(str, length, (Object) ' ');
    }

    public static Object padStart(Object str, Object length, Object padChar) {
        String s = (String) str;
        int len = ((Number) length).intValue();
        char pad = toPadChar(padChar);
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len - s.length(); i++) sb.append(pad);
        sb.append(s);
        return sb.toString();
    }

    public static Object padEnd(Object str, Object length) {
        return padEnd(str, length, (Object) ' ');
    }

    public static Object padEnd(Object str, Object length, Object padChar) {
        String s = (String) str;
        int len = ((Number) length).intValue();
        char pad = toPadChar(padChar);
        if (s.length() >= len) return s;
        StringBuilder sb = new StringBuilder(s);
        for (int i = 0; i < len - s.length(); i++) sb.append(pad);
        return sb.toString();
    }

    public static Object format(Object str, Object... args) {
        return String.format((String) str, args);
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
        return ((String) str).lastIndexOf(substr.toString());
    }

    public static Object matches(Object str, Object regex) {
        return ((String) str).matches(regex.toString());
    }

    // ── 双参数方法 ──

    public static Object replaceFirst(Object str, Object regex, Object replacement) {
        return ((String) str).replaceFirst(
                java.util.regex.Pattern.quote(regex.toString()),
                java.util.regex.Matcher.quoteReplacement(replacement.toString()));
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
        if (s.length() != 1) throw new RuntimeException("code() requires a single-character string");
        return (int) s.charAt(0);
    }

    public static Object digitToInt(Object str) {
        String s = (String) str;
        if (s.length() != 1) throw new RuntimeException("digitToInt() requires a single-character string");
        char c = s.charAt(0);
        if (c >= '0' && c <= '9') return c - '0';
        throw new RuntimeException("'" + c + "' is not a digit");
    }

    // ========== 辅助 ==========

    private static char toPadChar(Object arg) {
        if (arg instanceof Character) return (Character) arg;
        String s = arg.toString();
        return s.isEmpty() ? ' ' : s.charAt(0);
    }

    @SuppressWarnings("unchecked")
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
