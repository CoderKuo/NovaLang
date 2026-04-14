package com.novalang.runtime.stdlib.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Shared string operations used by both Nova value objects and stdlib extensions.
 */
public final class StringOps {

    private StringOps() {}

    public static int length(String value) {
        return value.length();
    }

    public static boolean isEmpty(String value) {
        return value.isEmpty();
    }

    public static boolean isNotEmpty(String value) {
        return !value.isEmpty();
    }

    public static boolean isBlank(String value) {
        return value.trim().isEmpty();
    }

    public static String toUpperCase(String value) {
        return value.toUpperCase();
    }

    public static String toLowerCase(String value) {
        return value.toLowerCase();
    }

    public static String trim(String value) {
        return value.trim();
    }

    public static String trimStart(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(index);
    }

    public static String trimEnd(String value) {
        int index = value.length() - 1;
        while (index >= 0 && Character.isWhitespace(value.charAt(index))) {
            index--;
        }
        return value.substring(0, index + 1);
    }

    public static String reverse(String value) {
        return new StringBuilder(value).reverse().toString();
    }

    public static String capitalize(String value) {
        if (value.isEmpty()) return value;
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    public static String decapitalize(String value) {
        if (value.isEmpty()) return value;
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    public static List<String> split(String value, String separator) {
        return new ArrayList<String>(Arrays.asList(value.split(Pattern.quote(separator), -1)));
    }

    public static List<String> splitRegex(String value, String regex) {
        return new ArrayList<String>(Arrays.asList(value.split(regex)));
    }

    public static boolean contains(String value, String substring) {
        return value.contains(substring);
    }

    public static boolean startsWith(String value, String prefix) {
        return value.startsWith(prefix);
    }

    public static boolean endsWith(String value, String suffix) {
        return value.endsWith(suffix);
    }

    public static int indexOf(String value, String substring) {
        return value.indexOf(substring);
    }

    public static int indexOf(String value, String substring, int fromIndex) {
        return value.indexOf(substring, fromIndex);
    }

    public static int lastIndexOf(String value, String substring) {
        return value.lastIndexOf(substring);
    }

    public static String replace(String value, String target, String replacement) {
        return value.replace(target, replacement);
    }

    public static String replaceFirstLiteral(String value, String target, String replacement) {
        int index = value.indexOf(target);
        if (index < 0) return value;
        return value.substring(0, index) + replacement + value.substring(index + target.length());
    }

    public static String substring(String value, int start) {
        return value.substring(start);
    }

    public static String substring(String value, int start, int end) {
        return value.substring(start, end);
    }

    public static String repeat(String value, int count) {
        StringBuilder sb = new StringBuilder(value.length() * Math.max(count, 0));
        for (int i = 0; i < count; i++) {
            sb.append(value);
        }
        return sb.toString();
    }

    public static String take(String value, int count) {
        return value.substring(0, Math.min(count, value.length()));
    }

    public static String drop(String value, int count) {
        return value.substring(Math.min(count, value.length()));
    }

    public static String takeLast(String value, int count) {
        return value.substring(Math.max(0, value.length() - count));
    }

    public static String dropLast(String value, int count) {
        return value.substring(0, Math.max(0, value.length() - count));
    }

    public static String removePrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    public static String removeSuffix(String value, String suffix) {
        return value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value;
    }

    public static String padStart(String value, int length, char padChar) {
        if (value.length() >= length) return value;
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        sb.append(value);
        return sb.toString();
    }

    public static String padEnd(String value, int length, char padChar) {
        if (value.length() >= length) return value;
        StringBuilder sb = new StringBuilder(length);
        sb.append(value);
        for (int i = 0; i < length - value.length(); i++) {
            sb.append(padChar);
        }
        return sb.toString();
    }

    public static List<Character> toCharacterList(String value) {
        List<Character> result = new ArrayList<Character>(value.length());
        for (int i = 0; i < value.length(); i++) {
            result.add(Character.valueOf(value.charAt(i)));
        }
        return result;
    }

    public static List<String> lines(String value) {
        return new ArrayList<String>(Arrays.asList(value.split("\\r?\\n", -1)));
    }

    public static int toInt(String value) {
        return Integer.parseInt(value);
    }

    public static long toLong(String value) {
        return Long.parseLong(value);
    }

    public static double toDouble(String value) {
        return Double.parseDouble(value);
    }

    public static boolean toBoolean(String value) {
        return Boolean.parseBoolean(value);
    }

    public static Integer toIntOrNull(String value) {
        try {
            return Integer.valueOf(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long toLongOrNull(String value) {
        try {
            return Long.valueOf(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Double toDoubleOrNull(String value) {
        try {
            return Double.valueOf(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean matches(String value, String regex) {
        return value.matches(regex);
    }

    public static String format(String format, Object... args) {
        return String.format(format, args);
    }
}
