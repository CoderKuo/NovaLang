package com.novalang.runtime.stdlib;

import com.novalang.runtime.AbstractNovaValue;

/**
 * 类型转换相关的 stdlib 函数：toInt / toLong / toDouble / toString / toBoolean / toChar / toFloat。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 */
public final class StdlibConversions {

    private StdlibConversions() {}

    private static final String OWNER = "com/novalang/runtime/stdlib/StdlibConversions";
    private static final String O_O = "(Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toInt", 1, OWNER, "toInt", O_O, args -> toInt(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toLong", 1, OWNER, "toLong", O_O, args -> toLong(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toDouble", 1, OWNER, "toDouble", O_O, args -> toDouble(args[0])));
        // JVM 方法名 toStr 避免与 Object.toString() 混淆
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toString", 1, OWNER, "toStr", O_O, args -> toStr(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toBoolean", 1, OWNER, "toBoolean", O_O, args -> toBoolean(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toChar", 1, OWNER, "toChar", O_O, args -> toChar(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toFloat", 1, OWNER, "toFloat", O_O, args -> toFloat(args[0])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    public static Object toInt(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try { return Integer.parseInt(((String) value).trim()); }
            catch (NumberFormatException e) {
                throw new RuntimeException("Cannot convert to Int: " + value);
            }
        }
        if (value instanceof Character) return (int) ((Character) value).charValue();
        throw new RuntimeException("Cannot convert " + AbstractNovaValue.typeNameOf(value) + " to Int");
    }

    public static Object toLong(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) {
            try { return Long.parseLong(((String) value).trim()); }
            catch (NumberFormatException e) {
                throw new RuntimeException("Cannot convert to Long: " + value);
            }
        }
        throw new RuntimeException("Cannot convert " + AbstractNovaValue.typeNameOf(value) + " to Long");
    }

    public static Object toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) {
            try { return Double.parseDouble(((String) value).trim()); }
            catch (NumberFormatException e) {
                throw new RuntimeException("Cannot convert to Double: " + value);
            }
        }
        throw new RuntimeException("Cannot convert " + AbstractNovaValue.typeNameOf(value) + " to Double");
    }

    public static Object toStr(Object value) {
        return String.valueOf(value);
    }

    public static Object toBoolean(Object value) {
        return LambdaUtils.isTruthy(value);
    }

    public static Object toChar(Object value) {
        if (value instanceof Character) return value;
        if (value instanceof Number) return (char) ((Number) value).intValue();
        if (value instanceof String) {
            String s = (String) value;
            if (s.length() == 1) return s.charAt(0);
            throw new RuntimeException("Cannot convert multi-char string to Char");
        }
        throw new RuntimeException("Cannot convert " + AbstractNovaValue.typeNameOf(value) + " to Char");
    }

    public static Object toFloat(Object value) {
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) {
            try { return Float.parseFloat(((String) value).trim()); }
            catch (NumberFormatException e) {
                throw new RuntimeException("Cannot convert to Float: " + value);
            }
        }
        throw new RuntimeException("Cannot convert " + AbstractNovaValue.typeNameOf(value) + " to Float");
    }
}
