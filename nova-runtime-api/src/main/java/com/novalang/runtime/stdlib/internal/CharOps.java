package com.novalang.runtime.stdlib.internal;

import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaException.ErrorKind;

/**
 * Shared character operations used by both Nova value objects and stdlib extensions.
 */
public final class CharOps {

    private CharOps() {}

    public static boolean isDigit(char value) {
        return Character.isDigit(value);
    }

    public static boolean isLetter(char value) {
        return Character.isLetter(value);
    }

    public static boolean isWhitespace(char value) {
        return Character.isWhitespace(value);
    }

    public static boolean isUpperCase(char value) {
        return Character.isUpperCase(value);
    }

    public static boolean isLowerCase(char value) {
        return Character.isLowerCase(value);
    }

    public static char toUpperCase(char value) {
        return Character.toUpperCase(value);
    }

    public static char toLowerCase(char value) {
        return Character.toLowerCase(value);
    }

    public static int code(char value) {
        return value;
    }

    public static int digitToInt(char value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "'" + value + "' 不是数字字符",
                "确保字符在 '0'..'9' 范围内");
    }

    public static boolean isLetterOrDigit(char value) {
        return Character.isLetterOrDigit(value);
    }

    public static int compareTo(char left, char right) {
        return Character.compare(left, right);
    }
}
