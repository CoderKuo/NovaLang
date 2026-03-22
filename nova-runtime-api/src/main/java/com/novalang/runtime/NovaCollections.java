package com.novalang.runtime;

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
        if (value instanceof String) {
            String s = (String) value;
            List<String> chars = new java.util.ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) chars.add(String.valueOf(s.charAt(i)));
            return chars;
        }
        throw new RuntimeException("Not iterable: " + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 解构取分量（编译路径入口）。委托 {@link NovaValue#componentN(int)}，
     * 同时处理 Map.Entry 等非 NovaValue 的 Java 对象。
     */
    public static Object componentN(Object value, int n) {
        if (value instanceof NovaValue) return ((NovaValue) value).componentN(n);
        if (value instanceof java.util.Map.Entry) {
            java.util.Map.Entry<?, ?> entry = (java.util.Map.Entry<?, ?>) value;
            return n == 1 ? entry.getKey() : entry.getValue();
        }
        if (value instanceof List) return ((List<?>) value).get(n - 1);
        throw new RuntimeException("Cannot destructure: " + (value == null ? "null" : value.getClass().getName()));
    }

    /**
     * 运行时索引取值：List → get(index)，数组 → Array.get(index)，String → charAt。
     */
    public static Object getIndex(Object target, int index) {
        // 负索引支持: -1 → 最后一个元素
        if (index < 0) {
            int size = 0;
            if (target instanceof List) size = ((List<?>) target).size();
            else if (target instanceof NovaList) size = ((NovaList) target).size();
            else if (target instanceof NovaArray) size = ((NovaArray) target).length();
            else if (target instanceof String) size = ((String) target).length();
            else if (target != null && target.getClass().isArray()) size = java.lang.reflect.Array.getLength(target);
            if (size > 0) index = size + index;
        }
        if (target instanceof List) return ((List<?>) target).get(index);
        if (target instanceof NovaList) return ((NovaList) target).get(index);
        if (target instanceof NovaArray) return ((NovaArray) target).get(index);
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
        if (target instanceof NovaMap) {
            NovaValue key = index instanceof NovaValue ? (NovaValue) index : AbstractNovaValue.fromJava(index);
            NovaValue val = ((NovaMap) target).get(key);
            return val != null ? val : NovaNull.NULL;
        }
        if (target instanceof Map) return ((Map<Object, Object>) target).get(index);
        if (target instanceof NovaList && index instanceof Number) return ((NovaList) target).get(((Number) index).intValue());
        // Range 切片: target[start..end]
        if (index instanceof NovaRange) {
            NovaRange range = (NovaRange) index;
            int start = range.getStart();
            int end = range.isInclusive() ? range.getEnd() + 1 : range.getEnd();
            if (target instanceof NovaList) {
                NovaList list = (NovaList) target;
                NovaList result = new NovaList();
                for (int i = start; i < Math.min(end, list.size()); i++) result.add(list.get(i));
                return result;
            }
            if (target instanceof String) {
                return ((String) target).substring(start, Math.min(end, ((String) target).length()));
            }
            if (target instanceof List) {
                return new java.util.ArrayList<>(((List<?>) target).subList(start, Math.min(end, ((List<?>) target).size())));
            }
        }
        // NovaPair 索引
        if (target instanceof NovaPair) {
            int i = index instanceof Number ? ((Number) index).intValue() : -1;
            if (i == 0) return ((NovaPair) target).getFirst();
            if (i == 1) return ((NovaPair) target).getSecond();
        }
        // NovaString 索引（返回 char）
        if (target instanceof NovaString && index instanceof Number) {
            int i = ((Number) index).intValue();
            String str = ((NovaString) target).getValue();
            if (i < 0) i += str.length();
            return NovaChar.of(str.charAt(i));
        }
        // 运算符重载 get()（NovaValue 子类通过 NovaDynamic）
        if (target instanceof NovaValue) {
            try {
                return NovaDynamic.invokeMethod(target, "get", index);
            } catch (RuntimeException ignored) {}
        }
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
        if (target instanceof NovaArray) { ((NovaArray) target).set(index, value instanceof NovaValue ? (NovaValue) value : AbstractNovaValue.fromJava(value)); return; }
        if (target != null && target.getClass().isArray()) { java.lang.reflect.Array.set(target, index, value); return; }
        throw new RuntimeException("Cannot index: " + (target == null ? "null" : target.getClass().getName()));
    }

    /**
     * 运行时动态索引赋值：Map → put(key, value)，其余委托给 int 版本。
     */
    @SuppressWarnings("unchecked")
    public static void setIndex(Object target, Object index, Object value) {
        if (target instanceof Map) { ((Map<Object, Object>) target).put(index, value); return; }
        if (target instanceof NovaMap) { ((NovaMap) target).put(index instanceof NovaValue ? (NovaValue) index : AbstractNovaValue.fromJava(index), value instanceof NovaValue ? (NovaValue) value : AbstractNovaValue.fromJava(value)); return; }
        // 运算符重载 set()
        if (target instanceof NovaValue) {
            try { NovaDynamic.invokeMethod(target, "set", index, value); return; }
            catch (RuntimeException ignored) {}
        }
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
