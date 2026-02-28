package nova.runtime.interpreter;
import nova.runtime.*;

import nova.runtime.stdlib.Ext;
import nova.runtime.stdlib.ExtProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * NovaArray 类型扩展方法。
 */
@Ext("nova/Array")
public final class ArrayExtensions {

    private ArrayExtensions() {}

    @ExtProperty
    public static Object size(Object arr) {
        return ((NovaArray) arr).length();
    }

    public static Object toList(Object arr) {
        return ((NovaArray) arr).toNovaList();
    }

    @SuppressWarnings("unchecked")
    public static Object forEach(Object arr, Object action) {
        NovaArray a = (NovaArray) arr;
        Function<Object, Object> fn = (Function<Object, Object>) action;
        for (NovaValue item : a) {
            fn.apply(item);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object arr, Object transform) {
        NovaArray a = (NovaArray) arr;
        Function<Object, Object> fn = (Function<Object, Object>) transform;
        List<Object> result = new ArrayList<>(a.length());
        for (NovaValue item : a) {
            result.add(fn.apply(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object filter(Object arr, Object predicate) {
        NovaArray a = (NovaArray) arr;
        Function<Object, Object> fn = (Function<Object, Object>) predicate;
        List<Object> result = new ArrayList<>();
        for (NovaValue item : a) {
            if (Boolean.TRUE.equals(fn.apply(item))) {
                result.add(item.toJavaValue());
            }
        }
        return result;
    }

    public static Object contains(Object arr, Object value) {
        NovaArray a = (NovaArray) arr;
        NovaValue target = AbstractNovaValue.fromJava(value);
        for (NovaValue item : a) {
            if (item.equals(target)) return true;
        }
        return false;
    }

    public static Object indexOf(Object arr, Object value) {
        NovaArray a = (NovaArray) arr;
        NovaValue target = AbstractNovaValue.fromJava(value);
        for (int i = 0; i < a.length(); i++) {
            if (a.get(i).equals(target)) return i;
        }
        return -1;
    }
}
