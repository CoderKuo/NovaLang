package nova.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 集合高阶函数辅助类，供编译后的字节码调用。
 * ArrayList 没有 map/filter 方法，此类提供静态桥接。
 */
public class NovaCollections {

    public static List<Object> map(List<?> list, Function<Object, Object> mapper) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(mapper.apply(item));
        }
        return result;
    }

    public static List<Object> filter(List<?> list, Predicate<Object> predicate) {
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (predicate.test(item)) result.add(item);
        }
        return result;
    }

    /**
     * 创建 Range 列表，供编译后的字节码调用。
     */
    public static List<Object> createRange(int start, int end, boolean inclusive) {
        int limit = inclusive ? end : end - 1;
        List<Object> list = new ArrayList<>(Math.max(limit - start + 1, 0));
        for (int i = start; i <= limit; i++) {
            list.add(i);
        }
        return list;
    }

    /**
     * 将任意集合类型转为 Iterable，供 for-in 循环使用。
     * Map → entrySet(), Iterable 直接返回。
     */
    public static Iterable<?> toIterable(Object value) {
        if (value instanceof Iterable) return (Iterable<?>) value;
        if (value instanceof java.util.Map) return ((java.util.Map<?, ?>) value).entrySet();
        throw new RuntimeException("Not iterable: " + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 解构取分量：List → get(n-1)，Map.Entry → getKey()/getValue()。
     */
    public static Object componentN(Object value, int n) {
        if (value instanceof List) return ((List<?>) value).get(n - 1);
        if (value instanceof java.util.Map.Entry) {
            java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) value;
            return n == 1 ? entry.getKey() : entry.getValue();
        }
        throw new RuntimeException("Cannot destructure: " + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 运行时索引取值：List → get(index)，数组 → Array.get(index)，String → charAt。
     */
    public static Object getIndex(Object target, int index) {
        if (target instanceof List) return ((List<?>) target).get(index);
        if (target instanceof String) return String.valueOf(((String) target).charAt(index));
        if (target != null && target.getClass().isArray()) return java.lang.reflect.Array.get(target, index);
        throw new RuntimeException("Cannot index: " + (target == null ? "null" : target.getClass().getName()));
    }

    /**
     * 运行时动态索引取值：Map → get(key)，其余委托给 int 版本。
     * 当编译器无法静态推断目标类型时（owner=java/lang/Object），使用此重载。
     */
    @SuppressWarnings("unchecked")
    public static Object getIndex(Object target, Object index) {
        if (target instanceof Map) return ((Map<Object, Object>) target).get(index);
        if (index instanceof Number) return getIndex(target, ((Number) index).intValue());
        throw new RuntimeException("Cannot index " + (target == null ? "null" : target.getClass().getName())
                + " with " + (index == null ? "null" : index.getClass().getName()));
    }

    /**
     * 运行时索引赋值：List → set(index, value)，数组 → Array.set(index, value)。
     */
    @SuppressWarnings("unchecked")
    public static void setIndex(Object target, int index, Object value) {
        if (target instanceof List) { ((List<Object>) target).set(index, value); return; }
        if (target != null && target.getClass().isArray()) { java.lang.reflect.Array.set(target, index, value); return; }
        throw new RuntimeException("Cannot index: " + (target == null ? "null" : target.getClass().getName()));
    }

    /**
     * 运行时动态索引赋值：Map → put(key, value)，其余委托给 int 版本。
     */
    @SuppressWarnings("unchecked")
    public static void setIndex(Object target, Object index, Object value) {
        if (target instanceof Map) { ((Map<Object, Object>) target).put(index, value); return; }
        if (index instanceof Number) { setIndex(target, ((Number) index).intValue(), value); return; }
        throw new RuntimeException("Cannot set index on " + (target == null ? "null" : target.getClass().getName())
                + " with " + (index == null ? "null" : index.getClass().getName()));
    }

    public static int len(Object value) {
        if (value instanceof String) return ((String) value).length();
        if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).size();
        if (value instanceof java.util.Map) return ((java.util.Map<?, ?>) value).size();
        if (value != null && value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        throw new RuntimeException("len() not supported for " + (value == null ? "null" : value.getClass().getName()));
    }
}
