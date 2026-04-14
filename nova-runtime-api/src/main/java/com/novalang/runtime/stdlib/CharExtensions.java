package com.novalang.runtime.stdlib;

import com.novalang.runtime.stdlib.internal.CharOps;

/**
 * Character extensions.
 */
@Ext("java/lang/Character")
public final class CharExtensions {

    private CharExtensions() {}

    public static Object isDigit(Object ch) {
        return CharOps.isDigit((Character) ch);
    }

    public static Object isLetter(Object ch) {
        return CharOps.isLetter((Character) ch);
    }

    public static Object isWhitespace(Object ch) {
        return CharOps.isWhitespace((Character) ch);
    }

    public static Object isUpperCase(Object ch) {
        return CharOps.isUpperCase((Character) ch);
    }

    public static Object isLowerCase(Object ch) {
        return CharOps.isLowerCase((Character) ch);
    }

    public static Object uppercase(Object ch) {
        return String.valueOf(CharOps.toUpperCase((Character) ch));
    }

    public static Object lowercase(Object ch) {
        return String.valueOf(CharOps.toLowerCase((Character) ch));
    }

    public static Object code(Object ch) {
        return CharOps.code((Character) ch);
    }

    public static Object digitToInt(Object ch) {
        return CharOps.digitToInt((Character) ch);
    }

    public static Object isLetterOrDigit(Object ch) {
        return CharOps.isLetterOrDigit((Character) ch);
    }

    public static Object toInt(Object ch) {
        return CharOps.code((Character) ch);
    }

    public static Object compareTo(Object ch, Object other) {
        return CharOps.compareTo((Character) ch, (Character) other);
    }
}
