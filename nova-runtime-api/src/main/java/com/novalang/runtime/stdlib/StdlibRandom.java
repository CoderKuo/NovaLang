package com.novalang.runtime.stdlib;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.novalang.runtime.NovaFunction;

/**
 * 随机数相关的 stdlib 函数。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 */
public final class StdlibRandom {

    private StdlibRandom() {}

    private static final String OWNER = "com/novalang/runtime/stdlib/StdlibRandom";
    private static final String OOO_O = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OO_O  = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String _O    = "()Ljava/lang/Object;";

    private static final String O_O = "(Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomInt", 2, OWNER, "randomInt", OO_O,
            args -> randomInt(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomLong", 2, OWNER, "randomLong", OO_O,
            args -> randomLong(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomDouble", 2, OWNER, "randomDouble", OO_O,
            args -> randomDouble(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomBool", 0, OWNER, "randomBool", _O,
            args -> randomBool()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomStr", 2, OWNER, "randomStr", OO_O,
            args -> randomStr(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomList", 3, OWNER, "randomList", OOO_O,
            args -> randomList(args[0], args[1], args[2])));
        // Hutool 启发
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomEle", 1, OWNER, "randomEle", O_O,
            args -> randomEle(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomEles", 2, OWNER, "randomEles", OO_O,
            args -> randomEles(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "shuffle", 1, OWNER, "shuffle", O_O,
            args -> shuffle(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "weightRandom", 1, OWNER, "weightRandom", O_O,
            args -> weightRandom(args[0])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    @NovaFunction(signature = "randomInt(min, max)", description = "闭区间随机整数 [min, max]", returnType = "Int")
    public static Object randomInt(Object min, Object max) {
        int lo = ((Number) min).intValue();
        int hi = ((Number) max).intValue();
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    @NovaFunction(signature = "randomLong(min, max)", description = "闭区间随机长整数 [min, max]", returnType = "Long")
    public static Object randomLong(Object min, Object max) {
        long lo = ((Number) min).longValue();
        long hi = ((Number) max).longValue();
        return ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    @NovaFunction(signature = "randomDouble(min, max)", description = "半开区间随机浮点 [min, max)", returnType = "Double")
    public static Object randomDouble(Object min, Object max) {
        double lo = ((Number) min).doubleValue();
        double hi = ((Number) max).doubleValue();
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    @NovaFunction(description = "随机布尔值", returnType = "Boolean")
    public static Object randomBool() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    @NovaFunction(signature = "randomStr(chars, length)", description = "从字符集随机取字符拼接指定长度的字符串", returnType = "String")
    public static Object randomStr(Object chars, Object length) {
        String charset = chars.toString();
        int len = ((Number) length).intValue();
        if (charset.isEmpty()) {
            throw new IllegalArgumentException("randomStr: charset must not be empty");
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = charset.charAt(rng.nextInt(charset.length()));
        }
        return new String(buf);
    }

    @NovaFunction(signature = "randomList(min, max, size)", description = "生成指定大小的随机整数列表 [min, max]", returnType = "List")
    public static Object randomList(Object min, Object max, Object size) {
        int lo = ((Number) min).intValue();
        int hi = ((Number) max).intValue();
        int count = ((Number) size).intValue();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(rng.nextInt(lo, hi + 1));
        }
        return list;
    }

    // ============ Hutool 启发 ============

    /** 从列表中随机取一个元素 */
    @NovaFunction(signature = "randomEle(list)", description = "从列表中随机取一个元素", returnType = "Any")
    public static Object randomEle(Object collection) {
        List<?> list = collection instanceof List ? (List<?>) collection : new ArrayList<>((java.util.Collection<?>) collection);
        if (list.isEmpty()) return null;
        return list.get(ThreadLocalRandom.current().nextInt(list.size()));
    }

    /** 从列表中随机取 N 个元素（不重复） */
    @NovaFunction(signature = "randomEles(list, count)", description = "从列表中随机取 N 个不重复元素", returnType = "List")
    public static Object randomEles(Object collection, Object count) {
        List<Object> list = new ArrayList<>((java.util.Collection<?>) collection);
        int n = Math.min(((Number) count).intValue(), list.size());
        java.util.Collections.shuffle(list, ThreadLocalRandom.current());
        return list.subList(0, n);
    }

    /** 打乱列表顺序（返回新列表） */
    @NovaFunction(signature = "shuffle(list)", description = "打乱列表顺序（返回新列表）", returnType = "List")
    public static Object shuffle(Object collection) {
        List<Object> list = new ArrayList<>((java.util.Collection<?>) collection);
        java.util.Collections.shuffle(list, ThreadLocalRandom.current());
        return list;
    }

    /**
     * 加权随机选择：传入 Map（元素 → 权重数字）。
     * 示例: weightRandom(#{"diamond": 1, "gold": 5, "iron": 20, "stone": 50})
     */
    @NovaFunction(signature = "weightRandom(weightMap)", description = "加权随机选择，传入 Map<元素, 权重>", returnType = "Any")
    @SuppressWarnings("unchecked")
    public static Object weightRandom(Object weightMap) {
        java.util.Map<?, ?> map;
        if (weightMap instanceof java.util.Map) {
            map = (java.util.Map<?, ?>) weightMap;
        } else {
            throw new IllegalArgumentException("weightRandom requires a Map<item, weight>");
        }
        double totalWeight = 0;
        for (Object v : map.values()) {
            totalWeight += ((Number) v).doubleValue();
        }
        double roll = ThreadLocalRandom.current().nextDouble() * totalWeight;
        double cumulative = 0;
        for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
            cumulative += ((Number) entry.getValue()).doubleValue();
            if (roll < cumulative) return entry.getKey();
        }
        // 回退：返回最后一个
        Object last = null;
        for (Object key : map.keySet()) last = key;
        return last;
    }
}
