package nova.runtime.stdlib;

import nova.runtime.Function1;

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
            invoke1(action, item);
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
