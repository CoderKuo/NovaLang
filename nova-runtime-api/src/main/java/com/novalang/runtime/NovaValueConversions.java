package com.novalang.runtime;

import java.util.List;
import java.util.Map;

/**
 * Nova ↔ Java 类型安全转换工具。
 *
 * <p>统一提供 {@code Object → T} 的安全转换，供以下场景共用：
 * <ul>
 *   <li>{@link NovaValue#toJava(Class)} — 方向 D</li>
 *   <li>{@link NovaRuntime#call(String, Object...)} — 方向 A</li>
 *   <li>HostBindingRegistry.FunctionBuilder 的泛型 invoke — 方向 B</li>
 * </ul>
 */
public final class NovaValueConversions {
    private NovaValueConversions() {}

    /**
     * 将任意值安全转换为指定 Java 类型。
     *
     * <p>转换优先级：
     * <ol>
     *   <li>null → null（基本类型抛异常）</li>
     *   <li>NovaValue → 使用 typed accessor 直接转换</li>
     *   <li>target.isInstance(value) → 直接 cast</li>
     *   <li>Number 宽化（int→long→double 等）</li>
     *   <li>任意值 → String（仅当目标为 String）</li>
     *   <li>不匹配 → 抛 NovaException</li>
     * </ol>
     *
     * @param value  源值（可为 null、NovaValue 或原始 Java 类型）
     * @param target 目标 Java 类型
     * @return 转换后的值
     * @throws NovaException 如果无法转换
     */
    @SuppressWarnings("unchecked")
    public static <T> T convertArg(Object value, Class<T> target) {
        if (value == null) {
            if (target.isPrimitive()) {
                throw new NovaException("Cannot convert null to primitive type " + target.getSimpleName());
            }
            return null;
        }

        // NovaValue 专用路径
        if (value instanceof NovaValue) {
            return convertNovaValue((NovaValue) value, target);
        }

        // 直接赋值兼容
        if (target.isInstance(value)) {
            return (T) value;
        }

        // 数值宽化
        if (value instanceof Number) {
            Object converted = convertNumber((Number) value, target);
            if (converted != null) return (T) converted;
        }

        // String 回退
        if (target == String.class) {
            return (T) String.valueOf(value);
        }

        throw new NovaException("Cannot convert " + AbstractNovaValue.typeNameOf(value)
                + " to " + target.getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T convertNovaValue(NovaValue nv, Class<T> target) {
        // 目标就是 NovaValue
        if (target == NovaValue.class || target.isInstance(nv)) {
            return (T) nv;
        }

        // null 值
        if (nv.isNull()) {
            if (target.isPrimitive()) {
                throw new NovaException("Cannot convert null to primitive type " + target.getSimpleName());
            }
            return null;
        }

        // 基本类型：使用 typed accessor
        if (target == int.class || target == Integer.class) return (T) Integer.valueOf(nv.asInt());
        if (target == long.class || target == Long.class) return (T) Long.valueOf(nv.asLong());
        if (target == double.class || target == Double.class) return (T) Double.valueOf(nv.asDouble());
        if (target == float.class || target == Float.class) return (T) Float.valueOf((float) nv.asDouble());
        if (target == boolean.class || target == Boolean.class) return (T) Boolean.valueOf(nv.asBoolean());
        if (target == String.class) return (T) nv.asString();

        // 容器类型
        if (target == List.class || target == java.util.Collection.class || target == Iterable.class) {
            Object jv = nv.toJavaValue();
            if (jv instanceof List) return (T) jv;
        }
        if (target == Map.class) {
            Object jv = nv.toJavaValue();
            if (jv instanceof Map) return (T) jv;
        }

        // 回退：toJavaValue + 直接赋值
        Object javaValue = nv.toJavaValue();
        if (target.isInstance(javaValue)) return (T) javaValue;

        // 回退：数值宽化
        if (javaValue instanceof Number) {
            Object converted = convertNumber((Number) javaValue, target);
            if (converted != null) return (T) converted;
        }

        throw new NovaException("Cannot convert " + nv.getTypeName() + " to " + target.getSimpleName());
    }

    private static Object convertNumber(Number num, Class<?> target) {
        if (target == int.class || target == Integer.class) return num.intValue();
        if (target == long.class || target == Long.class) return num.longValue();
        if (target == double.class || target == Double.class) return num.doubleValue();
        if (target == float.class || target == Float.class) return num.floatValue();
        if (target == short.class || target == Short.class) return num.shortValue();
        if (target == byte.class || target == Byte.class) return num.byteValue();
        return null;
    }
}
