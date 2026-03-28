package com.novalang.runtime.stdlib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集合相关的 stdlib 函数注册 + 编译路径静态方法。
 */
public final class NovaCollections {

    private NovaCollections() {}

    private static final String OWNER = "com/novalang/runtime/stdlib/NovaCollections";
    private static final String VARARG_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        // ---- 集合构造器（varargs） ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "listOf", -1, OWNER, "listOf", VARARG_DESC, NovaCollections::listOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "mutableListOf", -1, OWNER, "mutableListOf", VARARG_DESC, NovaCollections::mutableListOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "setOf", -1, OWNER, "setOf", VARARG_DESC, NovaCollections::setOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "mutableSetOf", -1, OWNER, "mutableSetOf", VARARG_DESC, NovaCollections::mutableSetOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "mapOf", -1, OWNER, "mapOf", VARARG_DESC, NovaCollections::mapOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "mutableMapOf", -1, OWNER, "mutableMapOf", VARARG_DESC, NovaCollections::mutableMapOf));

        // buildList { add("a"); add("b") } → [a, b]
        StdlibRegistry.register(new StdlibRegistry.ReceiverLambdaInfo(
            "buildList",
            "java/util/ArrayList",
            "Ljava/util/List;",
            null,
            consumer -> {
                List<Object> list = new ArrayList<>();
                consumer.accept(list);
                return Collections.unmodifiableList(list);
            }
        ));

        // buildMap { put("name", "Nova") } → {name=Nova}
        StdlibRegistry.register(new StdlibRegistry.ReceiverLambdaInfo(
            "buildMap",
            "java/util/LinkedHashMap",
            "Ljava/util/Map;",
            null,
            consumer -> {
                Map<Object, Object> map = new LinkedHashMap<>();
                consumer.accept(map);
                return Collections.unmodifiableMap(map);
            }
        ));

        // buildSet { add(1); add(2); add(1) } → [1, 2]
        StdlibRegistry.register(new StdlibRegistry.ReceiverLambdaInfo(
            "buildSet",
            "java/util/LinkedHashSet",
            "Ljava/util/Set;",
            null,
            consumer -> {
                Set<Object> set = new LinkedHashSet<>();
                consumer.accept(set);
                return Collections.unmodifiableSet(set);
            }
        ));

        // ---- 空集合构造器 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "emptyList", 0, OWNER, "emptyList", "()Ljava/lang/Object;",
            args -> new ArrayList<>()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "emptyMap", 0, OWNER, "emptyMap", "()Ljava/lang/Object;",
            args -> new LinkedHashMap<>()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "emptySet", 0, OWNER, "emptySet", "()Ljava/lang/Object;",
            args -> new LinkedHashSet<>()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "pairOf", 2, OWNER, "pairOf",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            args -> new Object[]{args[0], args[1]}));

        // Triple
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "Triple", 3, OWNER, "tripleOf",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            args -> tripleOf(args[0], args[1], args[2]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "tripleOf", 3, OWNER, "tripleOf",
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            args -> tripleOf(args[0], args[1], args[2])));

        // listOfNotNull
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "listOfNotNull", -1, OWNER, "listOfNotNull", VARARG_DESC,
            NovaCollections::listOfNotNull));

        // sortedMapOf / sortedSetOf / linkedMapOf
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sortedMapOf", -1, OWNER, "sortedMapOf", VARARG_DESC,
            NovaCollections::sortedMapOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sortedSetOf", -1, OWNER, "sortedSetOf", VARARG_DESC,
            NovaCollections::sortedSetOf));
    }

    // ========== 编译路径静态方法（INVOKESTATIC 目标） ==========

    public static Object listOf(Object... args) {
        List<Object> list = new ArrayList<>(args.length);
        Collections.addAll(list, args);
        return list;
    }

    public static Object mutableListOf(Object... args) {
        List<Object> list = new ArrayList<>(args.length);
        Collections.addAll(list, args);
        return list;
    }

    public static Object setOf(Object... args) {
        Set<Object> set = new LinkedHashSet<>();
        Collections.addAll(set, args);
        return set;
    }

    public static Object mutableSetOf(Object... args) {
        Set<Object> set = new LinkedHashSet<>();
        Collections.addAll(set, args);
        return set;
    }

    public static Object mapOf(Object... args) {
        Map<Object, Object> map = new LinkedHashMap<>();
        if (args.length == 0) return map;
        // Pair 形式: mapOf(pair1, pair2, ...) — NovaPair.toJavaValue() → Object[]{key, value}
        if (args[0] instanceof Object[] && ((Object[]) args[0]).length == 2) {
            for (Object arg : args) {
                Object[] pair = (Object[]) arg;
                map.put(pair[0], pair[1]);
            }
            return map;
        }
        // NovaPair 形式（编译路径: to 运算符直接产生 NovaPair 对象）
        if (args[0] instanceof com.novalang.runtime.NovaPair) {
            for (Object arg : args) {
                com.novalang.runtime.NovaPair pair = (com.novalang.runtime.NovaPair) arg;
                map.put(pair.getFirst(), pair.getSecond());
            }
            return map;
        }
        // flat 形式: mapOf(key1, val1, key2, val2, ...)
        if (args.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf requires even number of arguments (key-value pairs)");
        }
        for (int i = 0; i < args.length; i += 2) {
            map.put(args[i], args[i + 1]);
        }
        return map;
    }

    public static Object mutableMapOf(Object... args) {
        return mapOf(args);
    }

    public static Object emptyList() {
        return new ArrayList<>();
    }

    public static Object emptyMap() {
        return new LinkedHashMap<>();
    }

    public static Object emptySet() {
        return new LinkedHashSet<>();
    }

    public static Object pairOf(Object first, Object second) {
        return new Object[]{first, second};
    }

    public static Object tripleOf(Object first, Object second, Object third) {
        return new com.novalang.runtime.NovaTriple(first, second, third);
    }

    /** 创建列表，自动过滤 null */
    public static Object listOfNotNull(Object... args) {
        List<Object> list = new ArrayList<>();
        for (Object a : args) {
            if (a != null) list.add(a);
        }
        return list;
    }

    /** 有序 Map（TreeMap，按 key 自然排序） */
    @SuppressWarnings("unchecked")
    public static Object sortedMapOf(Object... args) {
        java.util.TreeMap<Object, Object> map = new java.util.TreeMap<>();
        if (args.length == 0) return map;
        if (args[0] instanceof Object[] && ((Object[]) args[0]).length == 2) {
            for (Object arg : args) {
                Object[] pair = (Object[]) arg;
                map.put(pair[0], pair[1]);
            }
            return map;
        }
        if (args[0] instanceof com.novalang.runtime.NovaPair) {
            for (Object arg : args) {
                com.novalang.runtime.NovaPair pair = (com.novalang.runtime.NovaPair) arg;
                map.put(pair.getFirst(), pair.getSecond());
            }
            return map;
        }
        if (args.length % 2 != 0) throw new IllegalArgumentException("sortedMapOf requires even number of arguments");
        for (int i = 0; i < args.length; i += 2) map.put(args[i], args[i + 1]);
        return map;
    }

    /** 有序 Set（TreeSet，自然排序） */
    @SuppressWarnings("unchecked")
    public static Object sortedSetOf(Object... args) {
        java.util.TreeSet<Object> set = new java.util.TreeSet<>();
        Collections.addAll(set, args);
        return set;
    }
}
