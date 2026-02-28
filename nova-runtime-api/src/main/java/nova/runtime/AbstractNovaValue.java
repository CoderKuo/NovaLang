package nova.runtime;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * NovaValue 的抽象基类实现
 *
 * <p>提供以下功能：</p>
 * <ul>
 *   <li>静态工具方法：{@link #fromJava(Object)}, {@link #typeNameOf(Object)}, {@link #truthyCheck(Object)}</li>
 *   <li>Object 方法默认实现：{@link #hashCode()}, {@link #equals(Object)}</li>
 *   <li>回退转换器注册：{@link #setFallbackConverter(Function)}</li>
 * </ul>
 *
 * <p>实现 NovaValue 的类可选择继承此类获得默认实现，或直接实现接口。</p>
 */
public abstract class AbstractNovaValue implements NovaValue {

    // ============ 回退转换器 ============

    /**
     * 回退转换器 — 用于处理未知 Java 对象（如 NovaExternalObject）。
     * 由 nova-runtime 的 Interpreter 初始化时注册。
     */
    private static Function<Object, NovaValue> fallbackConverter;

    /**
     * 注册回退转换器
     *
     * @param converter 转换器函数
     */
    public static void setFallbackConverter(Function<Object, NovaValue> converter) {
        fallbackConverter = converter;
    }

    /**
     * 获取当前回退转换器
     *
     * @return 回退转换器，可能为 null
     */
    public static Function<Object, NovaValue> getFallbackConverter() {
        return fallbackConverter;
    }

    // ============ 静态工具方法 ============

    /**
     * 将 Java 值转换为 NovaValue
     *
     * @param javaValue Java 对象
     * @return 对应的 NovaValue
     * @throws NovaException 如果无法转换
     */
    @SuppressWarnings("unchecked")
    public static NovaValue fromJava(Object javaValue) {
        if (javaValue == null) {
            return NovaNull.NULL;
        }
        if (javaValue instanceof NovaValue) {
            return (NovaValue) javaValue;
        }
        if (javaValue instanceof Integer) {
            return NovaInt.of((Integer) javaValue);
        }
        if (javaValue instanceof Long) {
            return NovaLong.of((Long) javaValue);
        }
        if (javaValue instanceof Double) {
            return NovaDouble.of((Double) javaValue);
        }
        if (javaValue instanceof Float) {
            return NovaFloat.of((Float) javaValue);
        }
        if (javaValue instanceof Boolean) {
            return NovaBoolean.of((Boolean) javaValue);
        }
        if (javaValue instanceof String) {
            return NovaString.of((String) javaValue);
        }
        if (javaValue instanceof Character) {
            return NovaChar.of((Character) javaValue);
        }
        if (javaValue instanceof int[]) {
            return new NovaArray(NovaArray.ElementType.INT, javaValue, ((int[]) javaValue).length);
        }
        if (javaValue instanceof long[]) {
            return new NovaArray(NovaArray.ElementType.LONG, javaValue, ((long[]) javaValue).length);
        }
        if (javaValue instanceof double[]) {
            return new NovaArray(NovaArray.ElementType.DOUBLE, javaValue, ((double[]) javaValue).length);
        }
        if (javaValue instanceof float[]) {
            return new NovaArray(NovaArray.ElementType.FLOAT, javaValue, ((float[]) javaValue).length);
        }
        if (javaValue instanceof boolean[]) {
            return new NovaArray(NovaArray.ElementType.BOOLEAN, javaValue, ((boolean[]) javaValue).length);
        }
        if (javaValue instanceof char[]) {
            return new NovaArray(NovaArray.ElementType.CHAR, javaValue, ((char[]) javaValue).length);
        }
        if (javaValue instanceof String[]) {
            return new NovaArray(NovaArray.ElementType.STRING, javaValue, ((String[]) javaValue).length);
        }
        if (javaValue instanceof Object[]) {
            return new NovaArray(NovaArray.ElementType.OBJECT, javaValue, ((Object[]) javaValue).length);
        }
        if (javaValue instanceof List) {
            NovaList list = new NovaList();
            for (Object item : (List<?>) javaValue) {
                list.add(fromJava(item));
            }
            return list;
        }
        if (javaValue instanceof Map) {
            NovaMap map = new NovaMap();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) javaValue).entrySet()) {
                map.put(fromJava(entry.getKey()), fromJava(entry.getValue()));
            }
            return map;
        }
        // 回退转换器（由 Interpreter 注册为 NovaExternalObject::new）
        if (fallbackConverter != null) {
            return fallbackConverter.apply(javaValue);
        }
        throw new NovaException("Cannot convert Java object to NovaValue: " + javaValue.getClass().getName());
    }

    /**
     * 获取任意 Java 对象的 Nova 类型名。
     *
     * <p>NovaValue 子类走 getTypeName()，原始 Java 类型走 fromJava 转换，
     * 未知类型回退到 Java 类简名。</p>
     *
     * @param value Java 对象
     * @return Nova 类型名
     */
    public static String typeNameOf(Object value) {
        if (value == null) return "Null";
        if (value instanceof NovaValue) return ((NovaValue) value).getTypeName();
        // 原始 Java 类型 → Nova 类型名（与 fromJava 识别的类型一致）
        if (value instanceof Integer) return "Int";
        if (value instanceof String) return "String";
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof Long) return "Long";
        if (value instanceof Double) return "Double";
        if (value instanceof Float) return "Float";
        if (value instanceof Character) return "Char";
        if (value instanceof List) return "List";
        if (value instanceof Map) return "Map";
        // @NovaType 注解（编译路径的 CompileScope/CompileDeferred/CompileJob 等）
        NovaType ann = value.getClass().getAnnotation(NovaType.class);
        if (ann != null) return ann.name();
        // 编译路径自定义类 / 其他
        return value.getClass().getSimpleName();
    }

    /**
     * 编译器路径的 truthy 检查：兼容 NovaValue 和原生 Java 类型（String/Boolean/Collection 等）。
     *
     * @param value 要检查的值
     * @return 是否为真值
     */
    public static boolean truthyCheck(Object value) {
        if (value == null) return false;
        if (value instanceof NovaValue) return ((NovaValue) value).isTruthy();
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof java.util.Collection) return !((java.util.Collection<?>) value).isEmpty();
        if (value instanceof java.util.Map) return !((java.util.Map<?, ?>) value).isEmpty();
        return true;
    }

    // ============ Object 方法实现 ============

    @Override
    public int hashCode() {
        Object val = toJavaValue();
        return val != null ? val.hashCode() : 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof NovaValue) {
            return equals((NovaValue) obj);
        }
        return false;
    }
}
