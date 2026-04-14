package com.novalang.runtime.stdlib.internal;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.Function0;
import com.novalang.runtime.Function1;
import com.novalang.runtime.Function2;
import com.novalang.runtime.ImplicitItFunction;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Shared map entry operations used by MapExtensions.
 */
public final class MapEntryOps {

    private MapEntryOps() {}

    public static Map<Object, Object> mapKeys(Map<?, ?> map, Object transform) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(invoke2(transform, entry.getKey(), entry.getValue()), entry.getValue());
            }
        } else if (isImplicitIt(transform)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(invoke1(transform, entry.getKey()), entry.getValue());
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(invoke1(transform, makeEntry(entry.getKey(), entry.getValue())), entry.getValue());
            }
        }
        return result;
    }

    public static Map<Object, Object> mapValues(Map<?, ?> map, Object transform) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(entry.getKey(), invoke2(transform, entry.getKey(), entry.getValue()));
            }
        } else if (isImplicitIt(transform)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(entry.getKey(), invoke1(transform, entry.getValue()));
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(entry.getKey(), invoke1(transform, makeEntry(entry.getKey(), entry.getValue())));
            }
        }
        return result;
    }

    public static Map<Object, Object> filterKeys(Map<?, ?> map, Object predicate) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (isTruthy(invoke1(predicate, entry.getKey()))) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static Map<Object, Object> filterValues(Map<?, ?> map, Object predicate) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (isTruthy(invoke1(predicate, entry.getValue()))) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    public static Map<Object, Object> filterEntries(Map<?, ?> map, Object predicate) {
        Map<Object, Object> result = new LinkedHashMap<Object, Object>();
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isTruthy(invoke2(predicate, entry.getKey(), entry.getValue()))) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(entry.getKey(), entry.getValue())))) {
                    result.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return result;
    }

    public static Object forEachEntries(Map<?, ?> map, Object action) {
        if (isBiFunction(action)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                try {
                    invoke2(action, entry.getKey(), entry.getValue());
                } catch (com.novalang.runtime.LoopSignal sig) {
                    if (sig == com.novalang.runtime.LoopSignal.BREAK) break;
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                try {
                    invoke1(action, makeEntry(entry.getKey(), entry.getValue()));
                } catch (com.novalang.runtime.LoopSignal sig) {
                    if (sig == com.novalang.runtime.LoopSignal.BREAK) break;
                }
            }
        }
        return null;
    }

    public static List<Object> mapEntries(Map<?, ?> map, Object transform) {
        List<Object> result = new ArrayList<Object>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.add(invoke2(transform, entry.getKey(), entry.getValue()));
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.add(invoke1(transform, makeEntry(entry.getKey(), entry.getValue())));
            }
        }
        return result;
    }

    public static List<Object> flatMapEntries(Map<?, ?> map, Object transform) {
        List<Object> result = new ArrayList<Object>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object mapped = invoke2(transform, entry.getKey(), entry.getValue());
                if (mapped instanceof Collection) {
                    result.addAll((Collection<?>) mapped);
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object mapped = invoke1(transform, makeEntry(entry.getKey(), entry.getValue()));
                if (mapped instanceof Collection) {
                    result.addAll((Collection<?>) mapped);
                }
            }
        }
        return result;
    }

    public static boolean anyEntries(Map<?, ?> map, Object predicate) {
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isTruthy(invoke2(predicate, entry.getKey(), entry.getValue()))) {
                    return true;
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(entry.getKey(), entry.getValue())))) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean allEntries(Map<?, ?> map, Object predicate) {
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!isTruthy(invoke2(predicate, entry.getKey(), entry.getValue()))) {
                    return false;
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!isTruthy(invoke1(predicate, makeEntry(entry.getKey(), entry.getValue())))) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean noneEntries(Map<?, ?> map, Object predicate) {
        return !anyEntries(map, predicate);
    }

    public static int countEntries(Map<?, ?> map, Object predicate) {
        int count = 0;
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isTruthy(invoke2(predicate, entry.getKey(), entry.getValue()))) {
                    count++;
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(entry.getKey(), entry.getValue())))) {
                    count++;
                }
            }
        }
        return count;
    }

    public static Object getOrPut(Map<Object, Object> map, Object key, Object defaultLambda) {
        Object existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        Object value;
        if (defaultLambda instanceof Supplier) {
            value = ((Supplier<?>) defaultLambda).get();
        } else if (defaultLambda instanceof Function0) {
            value = ((Function0<?>) defaultLambda).invoke();
        } else {
            value = invoke1(defaultLambda, key);
        }
        map.put(key, value);
        return value;
    }

    public static Map<String, Object> makeEntry(Object key, Object value) {
        Map<String, Object> entry = new LinkedHashMap<String, Object>();
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }

    public static boolean isBiFunction(Object lambda) {
        return lambda instanceof Function2 || lambda instanceof BiFunction;
    }

    public static boolean isImplicitIt(Object lambda) {
        return lambda instanceof ImplicitItFunction;
    }

    @SuppressWarnings("unchecked")
    public static Object invoke1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        return ((Function<Object, Object>) lambda).apply(arg);
    }

    @SuppressWarnings("unchecked")
    public static Object invoke2(Object lambda, Object arg1, Object arg2) {
        if (lambda instanceof Function2) {
            return ((Function2<Object, Object, Object>) lambda).invoke(arg1, arg2);
        }
        return ((BiFunction<Object, Object, Object>) lambda).apply(arg1, arg2);
    }

    public static boolean isTruthy(Object value) {
        return AbstractNovaValue.truthyCheck(value);
    }
}
