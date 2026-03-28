package com.novalang.runtime.stdlib;

/**
 * Boolean 类型扩展方法。
 */
@Ext("java/lang/Boolean")
public final class BooleanExtensions {

    private BooleanExtensions() {}

    /** 转为整数：true → 1, false → 0 */
    public static Object toInt(Object b) {
        return ((Boolean) b) ? 1 : 0;
    }

    /** 逻辑取反 */
    public static Object not(Object b) {
        return !((Boolean) b);
    }

    /** 逻辑与 */
    public static Object and(Object b, Object other) {
        return ((Boolean) b) && ((Boolean) other);
    }

    /** 逻辑或 */
    public static Object or(Object b, Object other) {
        return ((Boolean) b) || ((Boolean) other);
    }

    /** 逻辑异或 */
    public static Object xor(Object b, Object other) {
        return ((Boolean) b) ^ ((Boolean) other);
    }

    /** 比较 */
    public static Object compareTo(Object b, Object other) {
        return Boolean.compare((Boolean) b, (Boolean) other);
    }
}
