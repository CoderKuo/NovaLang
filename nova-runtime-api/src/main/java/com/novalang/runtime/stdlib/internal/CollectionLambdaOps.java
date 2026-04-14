package com.novalang.runtime.stdlib.internal;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.Function1;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared lambda-driven collection operations used by stdlib collection extensions.
 */
public final class CollectionLambdaOps {

    private CollectionLambdaOps() {}

    public static Object forEach(Iterable<?> items, Object action) {
        for (Object item : items) {
            try {
                invoke1(action, item);
            } catch (com.novalang.runtime.LoopSignal sig) {
                if (sig == com.novalang.runtime.LoopSignal.BREAK) break;
            }
        }
        return null;
    }

    public static List<Object> mapToList(Iterable<?> items, Object transform) {
        return mapToCollection(items, transform, ArrayList::new);
    }

    public static <C extends Collection<Object>> C mapToCollection(Iterable<?> items, Object transform, Supplier<C> supplier) {
        C result = supplier.get();
        for (Object item : items) {
            result.add(invoke1(transform, item));
        }
        return result;
    }

    public static List<Object> filterToList(Iterable<?> items, Object predicate) {
        return filterToCollection(items, predicate, ArrayList::new);
    }

    public static <C extends Collection<Object>> C filterToCollection(Iterable<?> items, Object predicate, Supplier<C> supplier) {
        C result = supplier.get();
        for (Object item : items) {
            if (isTruthy(invoke1(predicate, item))) {
                result.add(item);
            }
        }
        return result;
    }

    public static Object find(Iterable<?> items, Object predicate) {
        for (Object item : items) {
            if (isTruthy(invoke1(predicate, item))) {
                return item;
            }
        }
        return null;
    }

    public static List<Object> mapNotNullToList(Iterable<?> items, Object transform) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : items) {
            Object mapped = invoke1(transform, item);
            if (mapped != null) {
                result.add(mapped);
            }
        }
        return result;
    }

    public static List<Object> filterNotToList(Iterable<?> items, Object predicate) {
        return filterNotToCollection(items, predicate, ArrayList::new);
    }

    public static <C extends Collection<Object>> C filterNotToCollection(Iterable<?> items, Object predicate, Supplier<C> supplier) {
        C result = supplier.get();
        for (Object item : items) {
            if (!isTruthy(invoke1(predicate, item))) {
                result.add(item);
            }
        }
        return result;
    }

    public static List<Object> flatMapToList(Iterable<?> items, Object transform, boolean appendScalar) {
        List<Object> result = new ArrayList<Object>();
        for (Object item : items) {
            Object mapped = invoke1(transform, item);
            if (mapped instanceof Collection) {
                result.addAll((Collection<?>) mapped);
            } else if (appendScalar) {
                result.add(mapped);
            }
        }
        return result;
    }

    public static Map<Object, List<Object>> groupBy(Iterable<?> items, Object keySelector) {
        Map<Object, List<Object>> result = new LinkedHashMap<Object, List<Object>>();
        for (Object item : items) {
            Object key = invoke1(keySelector, item);
            result.computeIfAbsent(key, ignored -> new ArrayList<Object>()).add(item);
        }
        return result;
    }

    public static boolean any(Iterable<?> items, Object predicate) {
        for (Object item : items) {
            if (isTruthy(invoke1(predicate, item))) {
                return true;
            }
        }
        return false;
    }

    public static boolean all(Iterable<?> items, Object predicate) {
        for (Object item : items) {
            if (!isTruthy(invoke1(predicate, item))) {
                return false;
            }
        }
        return true;
    }

    public static boolean none(Iterable<?> items, Object predicate) {
        return !any(items, predicate);
    }

    public static int countWhere(Iterable<?> items, Object predicate) {
        int count = 0;
        for (Object item : items) {
            if (isTruthy(invoke1(predicate, item))) {
                count++;
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private static Object invoke1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        return ((Function<Object, Object>) lambda).apply(arg);
    }

    private static boolean isTruthy(Object value) {
        return AbstractNovaValue.truthyCheck(value);
    }
}
