package com.novalang.runtime.stdlib;

import com.novalang.runtime.Function1;
import com.novalang.runtime.Function2;
import com.novalang.runtime.NovaDynamic;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaException.ErrorKind;
import com.novalang.runtime.NovaPair;
import com.novalang.runtime.NovaRange;
import com.novalang.runtime.stdlib.internal.CollectionLambdaOps;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * List 类型扩展方法。
 * <p>
 * 编译器通过 {@code INVOKESTATIC} 调用静态方法，
 * NovaDynamic 通过 {@link StdlibRegistry.ExtensionMethodInfo#impl} 调用。
 * </p>
 */
@Ext("java/util/List")
public final class ListExtensions {

    private ListExtensions() {}

    // ========== 基本操作（从 Interpreter.getListMethod 迁移） ==========

    public static Object size(Object list) {
        return ((List<?>) list).size();
    }

    public static Object isEmpty(Object list) {
        return ((List<?>) list).isEmpty();
    }

    @SuppressWarnings("unchecked")
    public static Object add(Object list, Object value) {
        ((List<Object>) list).add(value);
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object remove(Object list, Object value) {
        return ((List<Object>) list).remove(value);
    }

    @SuppressWarnings("unchecked")
    public static Object clear(Object list) {
        ((List<Object>) list).clear();
        return null;
    }

    public static Object contains(Object list, Object value) {
        return ((List<?>) list).contains(value);
    }

    public static Object indexOf(Object list, Object value) {
        return ((List<?>) list).indexOf(value);
    }

    public static Object get(Object list, Object index) {
        return ((List<?>) list).get(((Number) index).intValue());
    }

    public static Object slice(Object list, Object range) {
        List<?> l = (List<?>) list;
        if (range instanceof NovaRange) {
            NovaRange r = (NovaRange) range;
            List<Object> result = new ArrayList<>();
            java.util.Iterator<Integer> it = r.intIterator();
            while (it.hasNext()) {
                int idx = it.next();
                if (idx >= 0 && idx < l.size()) result.add(l.get(idx));
            }
            return result;
        }
        throw new NovaException(ErrorKind.TYPE_MISMATCH, "slice 需要 Range 类型参数", "传入 Range，例如: list.slice(0..5)");
    }

    // ========== 无参方法 ==========

    public static Object first(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "列表为空，无法获取 first", "使用 firstOrNull() 安全获取");
        return l.get(0);
    }

    public static Object last(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "列表为空，无法获取 last", "使用 lastOrNull() 安全获取");
        return l.get(l.size() - 1);
    }

    public static Object firstOrNull(Object list) {
        List<?> l = (List<?>) list;
        return l.isEmpty() ? null : l.get(0);
    }

    public static Object lastOrNull(Object list) {
        List<?> l = (List<?>) list;
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }

    public static Object single(Object list) {
        List<?> l = (List<?>) list;
        if (l.size() != 1) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH,
                "列表包含 " + l.size() + " 个元素，single() 要求恰好 1 个", "使用 singleOrNull() 安全获取");
        return l.get(0);
    }

    public static Object singleOrNull(Object list) {
        List<?> l = (List<?>) list;
        return l.size() == 1 ? l.get(0) : null;
    }

    @SuppressWarnings("unchecked")
    public static Object sorted(Object list) {
        List<Object> copy = new ArrayList<>((List<?>) list);
        copy.sort((a, b) -> ((Comparable<Object>) a).compareTo(b));
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static Object sortedDescending(Object list) {
        List<Object> copy = new ArrayList<>((List<?>) list);
        copy.sort((a, b) -> ((Comparable<Object>) b).compareTo(a));
        return copy;
    }

    public static Object reversed(Object list) {
        List<Object> copy = new ArrayList<>((List<?>) list);
        Collections.reverse(copy);
        return copy;
    }

    public static Object distinct(Object list) {
        return new ArrayList<>(new LinkedHashSet<>((List<?>) list));
    }

    public static Object flatten(Object list) {
        List<?> l = (List<?>) list;
        List<Object> result = new ArrayList<>();
        for (Object item : l) {
            if (item instanceof List) {
                result.addAll((List<?>) item);
            } else {
                result.add(item);
            }
        }
        return result;
    }

    public static Object shuffled(Object list) {
        List<Object> copy = new ArrayList<>((List<?>) list);
        Collections.shuffle(copy);
        return copy;
    }

    public static Object sum(Object list) {
        double sum = 0;
        boolean allInt = true;
        for (Object item : (List<?>) list) {
            if (item instanceof Number) {
                if (item instanceof Double || item instanceof Float) allInt = false;
                sum += ((Number) item).doubleValue();
            }
        }
        return allInt ? (Object) (int) sum : sum;
    }

    public static Object average(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) return 0.0;
        double sum = 0;
        for (Object item : l) {
            if (item instanceof Number) sum += ((Number) item).doubleValue();
        }
        return sum / l.size();
    }

    @SuppressWarnings("unchecked")
    public static Object max(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "列表为空，无法获取 max", "使用 maxOrNull() 安全获取");
        return Collections.max((List<? extends Comparable<Object>>) l);
    }

    @SuppressWarnings("unchecked")
    public static Object min(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "列表为空，无法获取 min", "使用 minOrNull() 安全获取");
        return Collections.min((List<? extends Comparable<Object>>) l);
    }

    @SuppressWarnings("unchecked")
    public static Object maxOrNull(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) return null;
        return Collections.max((List<? extends Comparable<Object>>) l);
    }

    @SuppressWarnings("unchecked")
    public static Object minOrNull(Object list) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) return null;
        return Collections.min((List<? extends Comparable<Object>>) l);
    }

    public static Object toList(Object list) {
        return new ArrayList<>((List<?>) list);
    }

    public static Object toSet(Object list) {
        return new LinkedHashSet<>((List<?>) list);
    }

    public static Object isNotEmpty(Object list) {
        return !((List<?>) list).isEmpty();
    }

    public static Object toMutableList(Object list) {
        return new ArrayList<>((List<?>) list);
    }

    // ========== 单参数方法 ==========

    @SuppressWarnings("unchecked")
    public static Object removeAt(Object list, Object index) {
        return ((List<Object>) list).remove(((Number) index).intValue());
    }

    public static Object joinToString(Object list) {
        return joinToString(list, ", ");
    }

    public static Object joinToString(Object list, Object separator) {
        return joinToString(list, separator, null, null);
    }

    public static Object joinToString(Object list, Object separator, Object prefix) {
        return joinToString(list, separator, prefix, null);
    }

    public static Object joinToString(Object list, Object separator, Object prefix, Object postfix) {
        List<?> l = (List<?>) list;
        String sep = separator != null ? separator.toString() : ", ";
        String pre = prefix != null ? prefix.toString() : "";
        String post = postfix != null ? postfix.toString() : "";
        StringBuilder sb = new StringBuilder(pre);
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(l.get(i));
        }
        sb.append(post);
        return sb.toString();
    }

    public static Object take(Object list, Object n) {
        List<?> l = (List<?>) list;
        int count = ((Number) n).intValue();
        return new ArrayList<>(l.subList(0, Math.min(count, l.size())));
    }

    public static Object drop(Object list, Object n) {
        List<?> l = (List<?>) list;
        int count = ((Number) n).intValue();
        return new ArrayList<>(l.subList(Math.min(count, l.size()), l.size()));
    }

    public static Object takeLast(Object list, Object n) {
        List<?> l = (List<?>) list;
        int count = ((Number) n).intValue();
        return new ArrayList<>(l.subList(Math.max(0, l.size() - count), l.size()));
    }

    public static Object dropLast(Object list, Object n) {
        List<?> l = (List<?>) list;
        int count = ((Number) n).intValue();
        return new ArrayList<>(l.subList(0, Math.max(0, l.size() - count)));
    }

    // ========== Lambda 方法 ==========

    @SuppressWarnings("unchecked")
    public static Object any(Object list, Object predicate) {
        return CollectionLambdaOps.any((List<?>) list, predicate);
    }

    @SuppressWarnings("unchecked")
    public static Object all(Object list, Object predicate) {
        return CollectionLambdaOps.all((List<?>) list, predicate);
    }

    @SuppressWarnings("unchecked")
    public static Object none(Object list, Object predicate) {
        return CollectionLambdaOps.none((List<?>) list, predicate);
    }

    public static Object count(Object list) {
        return ((List<?>) list).size();
    }

    @SuppressWarnings("unchecked")
    public static Object count(Object list, Object predicate) {
        return CollectionLambdaOps.countWhere((List<?>) list, predicate);
    }

    @SuppressWarnings("unchecked")
    public static Object sortedBy(Object list, Object selector) {
        List<Object> copy = new ArrayList<>((List<?>) list);
        copy.sort((a, b) -> {
            Comparable<Object> ka = (Comparable<Object>) invoke1(selector, a);
            Comparable<Object> kb = (Comparable<Object>) invoke1(selector, b);
            return ka.compareTo((Object) kb);
        });
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static Object flatMap(Object list, Object transform) {
        return CollectionLambdaOps.flatMapToList((List<?>) list, transform, false);
    }

    @SuppressWarnings("unchecked")
    public static Object groupBy(Object list, Object keySelector) {
        return CollectionLambdaOps.groupBy((List<?>) list, keySelector);
    }

    @SuppressWarnings("unchecked")
    public static Object forEachIndexed(Object list, Object action) {
        List<?> l = (List<?>) list;
        for (int i = 0; i < l.size(); i++) {
            try {
                invoke2(action, i, l.get(i));
            } catch (com.novalang.runtime.LoopSignal sig) {
                if (sig == com.novalang.runtime.LoopSignal.BREAK) break;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object mapIndexed(Object list, Object transform) {
        List<?> l = (List<?>) list;
        List<Object> result = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) result.add(invoke2(transform, i, l.get(i)));
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object list, Object transform) {
        return CollectionLambdaOps.mapToList((List<?>) list, transform);
    }

    @SuppressWarnings("unchecked")
    public static Object filter(Object list, Object predicate) {
        return CollectionLambdaOps.filterToList((List<?>) list, predicate);
    }

    @SuppressWarnings("unchecked")
    public static Object find(Object list, Object predicate) {
        if (!(list instanceof List)) {
            return NovaDynamic.invoke1(list, "find", predicate);
        }
        return CollectionLambdaOps.find((List<?>) list, predicate);
    }

    @SuppressWarnings("unchecked")
    public static Object findLast(Object list, Object predicate) {
        List<?> l = (List<?>) list;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (isTruthy(invoke1(predicate, l.get(i)))) return l.get(i);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object mapNotNull(Object list, Object transform) {
        return CollectionLambdaOps.mapNotNullToList((List<?>) list, transform);
    }

    @SuppressWarnings("unchecked")
    public static Object filterNot(Object list, Object predicate) {
        return CollectionLambdaOps.filterNotToList((List<?>) list, predicate);
    }

    @SuppressWarnings("unchecked")
    public static Object filterIndexed(Object list, Object predicate) {
        List<?> l = (List<?>) list;
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < l.size(); i++) {
            if (isTruthy(invoke2(predicate, i, l.get(i)))) result.add(l.get(i));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object takeWhile(Object list, Object predicate) {
        List<Object> result = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (!isTruthy(invoke1(predicate, item))) break;
            result.add(item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object dropWhile(Object list, Object predicate) {
        List<?> l = (List<?>) list;
        List<Object> result = new ArrayList<>();
        boolean dropping = true;
        for (Object item : l) {
            if (dropping && isTruthy(invoke1(predicate, item))) continue;
            dropping = false;
            result.add(item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object reduce(Object list, Object operation) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "列表为空，无法执行 reduce", "使用 fold() 并提供初始值");
        Object acc = l.get(0);
        for (int i = 1; i < l.size(); i++) {
            acc = invoke2(operation, acc, l.get(i));
        }
        return acc;
    }

    @SuppressWarnings("unchecked")
    public static Object reduce(Object list, Object initial, Object operation) {
        Object acc = initial;
        for (Object item : (List<?>) list) {
            acc = invoke2(operation, acc, item);
        }
        return acc;
    }

    @SuppressWarnings("unchecked")
    public static Object fold(Object list, Object initial, Object operation) {
        Object acc = initial;
        for (Object item : (List<?>) list) {
            acc = invoke2(operation, acc, item);
        }
        return acc;
    }

    @SuppressWarnings("unchecked")
    public static Object foldRight(Object list, Object initial, Object operation) {
        List<?> l = (List<?>) list;
        Object acc = initial;
        for (int i = l.size() - 1; i >= 0; i--) {
            acc = invoke2(operation, l.get(i), acc);
        }
        return acc;
    }

    public static Object zip(Object list, Object other) {
        List<?> l1 = (List<?>) list;
        List<?> l2 = (List<?>) other;
        int len = Math.min(l1.size(), l2.size());
        List<Object> result = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            result.add(Arrays.asList(l1.get(i), l2.get(i)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object associateBy(Object list, Object keySelector) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Object item : (List<?>) list) {
            result.put(invoke1(keySelector, item), item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object associateWith(Object list, Object valueSelector) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Object item : (List<?>) list) {
            result.put(item, invoke1(valueSelector, item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object partition(Object list, Object predicate) {
        List<Object> first = new ArrayList<>();
        List<Object> second = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (isTruthy(invoke1(predicate, item))) first.add(item);
            else second.add(item);
        }
        return new NovaPair(first, second);
    }

    public static Object chunked(Object list, Object size) {
        List<?> l = (List<?>) list;
        int chunkSize = ((Number) size).intValue();
        if (chunkSize <= 0) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH, "chunked 的分块大小必须为正数", "传入大于 0 的整数");
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < l.size(); i += chunkSize) {
            result.add(new ArrayList<>(l.subList(i, Math.min(i + chunkSize, l.size()))));
        }
        return result;
    }

    public static Object windowed(Object list, Object size) {
        return windowed(list, size, (Object) 1);
    }

    public static Object windowed(Object list, Object size, Object step) {
        List<?> l = (List<?>) list;
        int windowSize = ((Number) size).intValue();
        int stepVal = ((Number) step).intValue();
        if (windowSize <= 0 || stepVal <= 0) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH, "windowed 的窗口大小和步长必须为正数");
        List<Object> result = new ArrayList<>();
        for (int i = 0; i + windowSize <= l.size(); i += stepVal) {
            result.add(new ArrayList<>(l.subList(i, i + windowSize)));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object maxBy(Object list, Object selector) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) return null;
        Object maxItem = l.get(0);
        Comparable<Object> maxKey = (Comparable<Object>) invoke1(selector, maxItem);
        for (int i = 1; i < l.size(); i++) {
            Comparable<Object> key = (Comparable<Object>) invoke1(selector, l.get(i));
            if (key.compareTo((Object) maxKey) > 0) { maxItem = l.get(i); maxKey = key; }
        }
        return maxItem;
    }

    @SuppressWarnings("unchecked")
    public static Object minBy(Object list, Object selector) {
        List<?> l = (List<?>) list;
        if (l.isEmpty()) return null;
        Object minItem = l.get(0);
        Comparable<Object> minKey = (Comparable<Object>) invoke1(selector, minItem);
        for (int i = 1; i < l.size(); i++) {
            Comparable<Object> key = (Comparable<Object>) invoke1(selector, l.get(i));
            if (key.compareTo((Object) minKey) < 0) { minItem = l.get(i); minKey = key; }
        }
        return minItem;
    }

    @SuppressWarnings("unchecked")
    public static Object sumBy(Object list, Object selector) {
        long sum = 0;
        for (Object item : (List<?>) list) {
            sum += ((Number) invoke1(selector, item)).longValue();
        }
        return sum;
    }

    @SuppressWarnings("unchecked")
    public static Object forEach(Object list, Object action) {
        return CollectionLambdaOps.forEach((List<?>) list, action);
    }

    public static Object withIndex(Object list) {
        List<?> l = (List<?>) list;
        List<Object> result = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) {
            result.add(new NovaPair(i, l.get(i)));
        }
        return result;
    }

    public static Object intersect(Object list, Object other) {
        Set<Object> otherSet = new LinkedHashSet<>((List<?>) other);
        List<Object> result = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (otherSet.contains(item)) result.add(item);
        }
        return result;
    }

    public static Object subtract(Object list, Object other) {
        Set<Object> otherSet = new LinkedHashSet<>((List<?>) other);
        List<Object> result = new ArrayList<>();
        for (Object item : (List<?>) list) {
            if (!otherSet.contains(item)) result.add(item);
        }
        return result;
    }

    public static Object union(Object list, Object other) {
        Set<Object> seen = new LinkedHashSet<>();
        List<Object> result = new ArrayList<>();
        for (Object item : (List<?>) list) { if (seen.add(item)) result.add(item); }
        for (Object item : (List<?>) other) { if (seen.add(item)) result.add(item); }
        return result;
    }

    public static Object toMap(Object list) {
        List<?> l = (List<?>) list;
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Object item : l) {
            if (item instanceof NovaPair) {
                NovaPair p = (NovaPair) item;
                result.put(p.getFirst(), p.getSecond());
            } else if (item instanceof List && ((List<?>) item).size() == 2) {
                result.put(((List<?>) item).get(0), ((List<?>) item).get(1));
            } else {
                throw new NovaException(ErrorKind.TYPE_MISMATCH, "toMap 需要 Pair 列表", "确保列表元素为 Pair 或包含两个元素的 List");
            }
        }
        return result;
    }

    public static Object unzip(Object list) {
        List<?> l = (List<?>) list;
        List<Object> firsts = new ArrayList<>();
        List<Object> seconds = new ArrayList<>();
        for (Object item : l) {
            if (item instanceof NovaPair) {
                NovaPair p = (NovaPair) item;
                firsts.add(p.getFirst());
                seconds.add(p.getSecond());
            } else if (item instanceof List && ((List<?>) item).size() == 2) {
                firsts.add(((List<?>) item).get(0));
                seconds.add(((List<?>) item).get(1));
            }
        }
        return new NovaPair(firsts, seconds);
    }

    // ========== 辅助 ==========

    /**
     * 调用单参数 lambda：优先使用 Function1.invoke()（编译器生成的 lambda），
     * 回退到 Function.apply()（解释器包装的 lambda）。
     * 避免跨 classloader 模块边界的类型转换问题。
     */
    @SuppressWarnings("unchecked")
    static Object invoke1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        return ((Function<Object, Object>) lambda).apply(arg);
    }

    // ========== Hutool 启发 ==========

    /** 元素频次统计 → Map<元素, 出现次数> */
    @SuppressWarnings("unchecked")
    public static Object countMap(Object list) {
        Map<Object, Integer> result = new LinkedHashMap<>();
        for (Object item : (List<?>) list) {
            result.merge(item, 1, Integer::sum);
        }
        return result;
    }

    /** 安全索引访问，越界返回 null */
    public static Object getOrNull(Object list, Object index) {
        List<?> l = (List<?>) list;
        int i = ((Number) index).intValue();
        if (i < 0 || i >= l.size()) return null;
        return l.get(i);
    }

    /** 安全索引访问，越界返回默认值 */
    public static Object getOrDefault(Object list, Object index, Object defaultValue) {
        List<?> l = (List<?>) list;
        int i = ((Number) index).intValue();
        if (i < 0 || i >= l.size()) return defaultValue;
        return l.get(i);
    }

    /** 带谓词的 first：找到第一个满足条件的元素，找不到抛异常 */
    @SuppressWarnings("unchecked")
    public static Object first(Object list, Object predicate) {
        for (Object item : (List<?>) list) {
            if (isTruthy(invoke1(predicate, item))) return item;
        }
        throw new NovaException(ErrorKind.UNDEFINED, "没有元素匹配谓词条件", "使用 firstOrNull { ... } 安全获取");
    }

    /** 带谓词的 last：找到最后一个满足条件的元素，找不到抛异常 */
    @SuppressWarnings("unchecked")
    public static Object last(Object list, Object predicate) {
        List<?> l = (List<?>) list;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (isTruthy(invoke1(predicate, l.get(i)))) return l.get(i);
        }
        throw new NovaException(ErrorKind.UNDEFINED, "没有元素匹配谓词条件", "使用 lastOrNull { ... } 安全获取");
    }

    /** 带谓词的 firstOrNull */
    @SuppressWarnings("unchecked")
    public static Object firstOrNull(Object list, Object predicate) {
        for (Object item : (List<?>) list) {
            if (isTruthy(invoke1(predicate, item))) return item;
        }
        return null;
    }

    /** 带谓词的 lastOrNull */
    @SuppressWarnings("unchecked")
    public static Object lastOrNull(Object list, Object predicate) {
        List<?> l = (List<?>) list;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (isTruthy(invoke1(predicate, l.get(i)))) return l.get(i);
        }
        return null;
    }

    /** lastIndexOf：最后一个匹配元素的索引 */
    public static Object lastIndexOf(Object list, Object value) {
        List<?> l = (List<?>) list;
        for (int i = l.size() - 1; i >= 0; i--) {
            if (java.util.Objects.equals(l.get(i), value)) return i;
        }
        return -1;
    }

    // ========== 辅助 ==========

    /**
     * 调用双参数 lambda：优先使用 Function2.invoke()，回退到 BiFunction.apply()。
     */
    @SuppressWarnings("unchecked")
    static Object invoke2(Object lambda, Object arg1, Object arg2) {
        if (lambda instanceof Function2) {
            return ((Function2<Object, Object, Object>) lambda).invoke(arg1, arg2);
        }
        return ((BiFunction<Object, Object, Object>) lambda).apply(arg1, arg2);
    }

    private static boolean isTruthy(Object value) {
        return LambdaUtils.isTruthy(value);
    }
}
