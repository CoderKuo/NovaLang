package nova.runtime.stdlib;

import nova.runtime.Function1;
import nova.runtime.Function2;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Map 类型扩展方法。
 */
@Ext("java/util/Map")
public final class MapExtensions {

    private MapExtensions() {}

    // ========== 可变操作（从 Interpreter.getMapMethod 迁移） ==========

    @SuppressWarnings("unchecked")
    public static Object put(Object map, Object key, Object value) {
        ((Map<Object, Object>) map).put(key, value);
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object remove(Object map, Object key) {
        return ((Map<Object, Object>) map).remove(key);
    }

    @SuppressWarnings("unchecked")
    public static Object clear(Object map) {
        ((Map<Object, Object>) map).clear();
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object putAll(Object map, Object other) {
        ((Map<Object, Object>) map).putAll((Map<?, ?>) other);
        return null;
    }

    // ========== 无参方法 ==========

    public static Object size(Object map) {
        return ((Map<?, ?>) map).size();
    }

    public static Object isEmpty(Object map) {
        return ((Map<?, ?>) map).isEmpty();
    }

    public static Object isNotEmpty(Object map) {
        return !((Map<?, ?>) map).isEmpty();
    }

    public static Object keys(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).keySet());
    }

    public static Object values(Object map) {
        return new ArrayList<>(((Map<?, ?>) map).values());
    }

    public static Object entries(Object map) {
        List<Object> result = new ArrayList<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            result.add(makeEntry(e.getKey(), e.getValue()));
        }
        return result;
    }

    public static Object toList(Object map) {
        List<Object> result = new ArrayList<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            result.add(Arrays.asList(e.getKey(), e.getValue()));
        }
        return result;
    }

    public static Object toMutableMap(Object map) {
        return new LinkedHashMap<>((Map<?, ?>) map);
    }

    // ========== 单参数非 Lambda ==========

    public static Object containsKey(Object map, Object key) {
        return ((Map<?, ?>) map).containsKey(key);
    }

    public static Object containsValue(Object map, Object value) {
        return ((Map<?, ?>) map).containsValue(value);
    }

    public static Object get(Object map, Object key) {
        return ((Map<?, ?>) map).get(key);
    }

    public static Object getOrDefault(Object map, Object key, Object defaultValue) {
        Object val = ((Map<?, ?>) map).get(key);
        return val != null ? val : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public static Object merge(Object map, Object other) {
        Map<Object, Object> result = new LinkedHashMap<>((Map<?, ?>) map);
        result.putAll((Map<Object, Object>) other);
        return result;
    }

    // ========== 单参数 Lambda（key-only / value-only） ==========

    @SuppressWarnings("unchecked")
    public static Object mapKeys(Object map, Object transform) {
        Map<Object, Object> result = new LinkedHashMap<>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                result.put(invoke2(transform, e.getKey(), e.getValue()), e.getValue());
            }
        } else if (isImplicitIt(transform)) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                result.put(invoke1(transform, e.getKey()), e.getValue());
            }
        } else {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                result.put(invoke1(transform, makeEntry(e.getKey(), e.getValue())), e.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object mapValues(Object map, Object transform) {
        Map<Object, Object> result = new LinkedHashMap<>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                result.put(e.getKey(), invoke2(transform, e.getKey(), e.getValue()));
            }
        } else if (isImplicitIt(transform)) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                result.put(e.getKey(), invoke1(transform, e.getValue()));
            }
        } else {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
                result.put(e.getKey(), invoke1(transform, makeEntry(e.getKey(), e.getValue())));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object filterKeys(Object map, Object predicate) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            if (isTruthy(invoke1(predicate, e.getKey()))) result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object filterValues(Object map, Object predicate) {
        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) map).entrySet()) {
            if (isTruthy(invoke1(predicate, e.getValue()))) result.put(e.getKey(), e.getValue());
        }
        return result;
    }

    // ========== Entry 操作（自动适配 Function2(k,v) / Function1(entry)） ==========

    @SuppressWarnings("unchecked")
    public static Object filter(Object map, Object predicate) {
        Map<?, ?> m = (Map<?, ?>) map;
        Map<Object, Object> result = new LinkedHashMap<>();
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke2(predicate, e.getKey(), e.getValue()))) result.put(e.getKey(), e.getValue());
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(e.getKey(), e.getValue())))) result.put(e.getKey(), e.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object forEach(Object map, Object action) {
        Map<?, ?> m = (Map<?, ?>) map;
        if (isBiFunction(action)) {
            for (Map.Entry<?, ?> e : m.entrySet()) invoke2(action, e.getKey(), e.getValue());
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) invoke1(action, e.getKey());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object mapObj, Object transform) {
        Map<?, ?> m = (Map<?, ?>) mapObj;
        List<Object> result = new ArrayList<>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> e : m.entrySet()) result.add(invoke2(transform, e.getKey(), e.getValue()));
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) result.add(invoke1(transform, makeEntry(e.getKey(), e.getValue())));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object flatMap(Object mapObj, Object transform) {
        Map<?, ?> m = (Map<?, ?>) mapObj;
        List<Object> result = new ArrayList<>();
        if (isBiFunction(transform)) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object mapped = invoke2(transform, e.getKey(), e.getValue());
                if (mapped instanceof Collection) result.addAll((Collection<?>) mapped);
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object mapped = invoke1(transform, makeEntry(e.getKey(), e.getValue()));
                if (mapped instanceof Collection) result.addAll((Collection<?>) mapped);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object any(Object map, Object predicate) {
        Map<?, ?> m = (Map<?, ?>) map;
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke2(predicate, e.getKey(), e.getValue()))) return true;
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(e.getKey(), e.getValue())))) return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static Object all(Object map, Object predicate) {
        Map<?, ?> m = (Map<?, ?>) map;
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!isTruthy(invoke2(predicate, e.getKey(), e.getValue()))) return false;
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!isTruthy(invoke1(predicate, makeEntry(e.getKey(), e.getValue())))) return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    public static Object none(Object map, Object predicate) {
        Map<?, ?> m = (Map<?, ?>) map;
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke2(predicate, e.getKey(), e.getValue()))) return false;
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(e.getKey(), e.getValue())))) return false;
            }
        }
        return true;
    }

    public static Object count(Object map) {
        return ((Map<?, ?>) map).size();
    }

    @SuppressWarnings("unchecked")
    public static Object count(Object map, Object predicate) {
        Map<?, ?> m = (Map<?, ?>) map;
        int count = 0;
        if (isBiFunction(predicate)) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke2(predicate, e.getKey(), e.getValue()))) count++;
            }
        } else {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (isTruthy(invoke1(predicate, makeEntry(e.getKey(), e.getValue())))) count++;
            }
        }
        return count;
    }

    // ========== 双参数 ==========

    @SuppressWarnings("unchecked")
    public static Object getOrPut(Object map, Object key, Object defaultLambda) {
        Map<Object, Object> m = (Map<Object, Object>) map;
        Object existing = m.get(key);
        if (existing != null) return existing;
        Object value;
        if (defaultLambda instanceof java.util.function.Supplier) {
            value = ((java.util.function.Supplier<?>) defaultLambda).get();
        } else if (defaultLambda instanceof nova.runtime.Function0) {
            value = ((nova.runtime.Function0<?>) defaultLambda).invoke();
        } else {
            value = invoke1(defaultLambda, key);
        }
        m.put(key, value);
        return value;
    }

    // ========== 辅助 ==========

    /** 构造 Map entry 对象，支持 entry.key / entry.value 访问 */
    private static Map<String, Object> makeEntry(Object key, Object value) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("key", key);
        entry.put("value", value);
        return entry;
    }

    /** 判断 lambda 是否为双参数函数 */
    private static boolean isBiFunction(Object lambda) {
        return lambda instanceof Function2 || lambda instanceof BiFunction;
    }

    /** 判断 lambda 是否为 arity-0（隐式 it）*/
    private static boolean isImplicitIt(Object lambda) {
        return lambda instanceof nova.runtime.ImplicitItFunction;
    }

    @SuppressWarnings("unchecked")
    static Object invoke1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        return ((Function<Object, Object>) lambda).apply(arg);
    }

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
