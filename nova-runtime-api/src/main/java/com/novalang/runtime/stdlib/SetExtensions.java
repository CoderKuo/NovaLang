package com.novalang.runtime.stdlib;

import com.novalang.runtime.Function1;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaException.ErrorKind;

import java.util.*;
import java.util.function.Function;

/**
 * Set 类型扩展方法。
 */
@Ext("java/util/Set")
public final class SetExtensions {

    private SetExtensions() {}

    // ========== 基本操作（从 Interpreter.getSetMethod 迁移） ==========

    public static Object size(Object set) {
        return ((Set<?>) set).size();
    }

    public static Object isEmpty(Object set) {
        return ((Set<?>) set).isEmpty();
    }

    public static Object contains(Object set, Object value) {
        return ((Set<?>) set).contains(value);
    }

    @SuppressWarnings("unchecked")
    public static Object add(Object set, Object value) {
        ((Set<Object>) set).add(value);
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object remove(Object set, Object value) {
        return ((Set<Object>) set).remove(value);
    }

    @SuppressWarnings("unchecked")
    public static Object clear(Object set) {
        ((Set<Object>) set).clear();
        return null;
    }

    // ========== 无参方法 ==========

    public static Object isNotEmpty(Object set) {
        return !((Set<?>) set).isEmpty();
    }

    public static Object toList(Object set) {
        return new ArrayList<>((Set<?>) set);
    }

    // ========== Lambda 方法 ==========

    @SuppressWarnings("unchecked")
    public static Object forEach(Object set, Object action) {
        for (Object item : (Set<?>) set) {
            try {
                invoke1(action, item);
            } catch (com.novalang.runtime.LoopSignal sig) {
                if (sig == com.novalang.runtime.LoopSignal.BREAK) break;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object set, Object transform) {
        Set<Object> result = new LinkedHashSet<>();
        for (Object item : (Set<?>) set) {
            result.add(invoke1(transform, item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object filter(Object set, Object predicate) {
        Set<Object> result = new LinkedHashSet<>();
        for (Object item : (Set<?>) set) {
            if (isTruthy(invoke1(predicate, item))) result.add(item);
        }
        return result;
    }

    // ========== 查询方法 ==========

    public static Object first(Object set) {
        Set<?> s = (Set<?>) set;
        if (s.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "集合为空，无法获取 first", "使用 firstOrNull() 安全获取");
        return s.iterator().next();
    }

    public static Object firstOrNull(Object set) {
        Set<?> s = (Set<?>) set;
        return s.isEmpty() ? null : s.iterator().next();
    }

    public static Object last(Object set) {
        Set<?> s = (Set<?>) set;
        if (s.isEmpty()) throw new NovaException(ErrorKind.INDEX_OUT_OF_BOUNDS, "集合为空，无法获取 last", "使用 lastOrNull() 安全获取");
        Object last = null;
        for (Object item : s) last = item;
        return last;
    }

    public static Object lastOrNull(Object set) {
        Set<?> s = (Set<?>) set;
        if (s.isEmpty()) return null;
        Object last = null;
        for (Object item : s) last = item;
        return last;
    }

    public static Object count(Object set) {
        return ((Set<?>) set).size();
    }

    @SuppressWarnings("unchecked")
    public static Object count(Object set, Object predicate) {
        int c = 0;
        for (Object item : (Set<?>) set) {
            if (isTruthy(invoke1(predicate, item))) c++;
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    public static Object any(Object set, Object predicate) {
        for (Object item : (Set<?>) set) {
            if (isTruthy(invoke1(predicate, item))) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Object all(Object set, Object predicate) {
        for (Object item : (Set<?>) set) {
            if (!isTruthy(invoke1(predicate, item))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Object none(Object set, Object predicate) {
        for (Object item : (Set<?>) set) {
            if (isTruthy(invoke1(predicate, item))) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Object find(Object set, Object predicate) {
        for (Object item : (Set<?>) set) {
            if (isTruthy(invoke1(predicate, item))) return item;
        }
        return null;
    }

    // ========== 变换方法 ==========

    @SuppressWarnings("unchecked")
    public static Object filterNot(Object set, Object predicate) {
        Set<Object> result = new LinkedHashSet<>();
        for (Object item : (Set<?>) set) {
            if (!isTruthy(invoke1(predicate, item))) result.add(item);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object flatMap(Object set, Object transform) {
        List<Object> result = new ArrayList<>();
        for (Object item : (Set<?>) set) {
            Object mapped = invoke1(transform, item);
            if (mapped instanceof Collection) {
                result.addAll((Collection<?>) mapped);
            } else {
                result.add(mapped);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object sorted(Object set) {
        List<Object> list = new ArrayList<>((Set<?>) set);
        list.sort((a, b) -> ((Comparable<Object>) a).compareTo(b));
        return new LinkedHashSet<>(list);
    }

    @SuppressWarnings("unchecked")
    public static Object sortedBy(Object set, Object selector) {
        List<Object> list = new ArrayList<>((Set<?>) set);
        list.sort((a, b) -> {
            Comparable<Object> ka = (Comparable<Object>) invoke1(selector, a);
            Comparable<Object> kb = (Comparable<Object>) invoke1(selector, b);
            return ka.compareTo((Object) kb);
        });
        return new LinkedHashSet<>(list);
    }

    @SuppressWarnings("unchecked")
    public static Object groupBy(Object set, Object keySelector) {
        Map<Object, List<Object>> groups = new LinkedHashMap<>();
        for (Object item : (Set<?>) set) {
            Object key = invoke1(keySelector, item);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return groups;
    }

    @SuppressWarnings("unchecked")
    public static Object joinToString(Object set) {
        return joinToString(set, ", ");
    }

    @SuppressWarnings("unchecked")
    public static Object joinToString(Object set, Object separator) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object item : (Set<?>) set) {
            if (!first) sb.append(String.valueOf(separator));
            sb.append(String.valueOf(item));
            first = false;
        }
        return sb.toString();
    }

    public static Object toMutableSet(Object set) {
        return new LinkedHashSet<>((Set<?>) set);
    }

    public static Object toSet(Object set) {
        return new LinkedHashSet<>((Set<?>) set);
    }

    // ========== 集合操作 ==========

    @SuppressWarnings("unchecked")
    public static Object union(Object set, Object other) {
        Set<Object> result = new LinkedHashSet<>((Set<?>) set);
        result.addAll((Collection<?>) other);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object intersect(Object set, Object other) {
        Set<Object> result = new LinkedHashSet<>((Set<?>) set);
        result.retainAll((Collection<?>) other);
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object subtract(Object set, Object other) {
        Set<Object> result = new LinkedHashSet<>((Set<?>) set);
        result.removeAll((Collection<?>) other);
        return result;
    }

    // ========== 辅助 ==========

    @SuppressWarnings("unchecked")
    private static Object invoke1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        return ((Function<Object, Object>) lambda).apply(arg);
    }

    private static boolean isTruthy(Object value) {
        return LambdaUtils.isTruthy(value);
    }
}
