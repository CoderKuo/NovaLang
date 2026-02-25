package nova.runtime.interpreter;
import nova.runtime.*;

import nova.runtime.stdlib.Ext;
import nova.runtime.stdlib.ExtProperty;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * NovaRange 类型扩展方法。
 */
@Ext("nova/Range")
public final class RangeExtensions {

    private RangeExtensions() {}

    // ========== 属性 ==========

    @ExtProperty
    public static Object first(Object range) {
        return ((NovaRange) range).getStart();
    }

    @ExtProperty
    public static Object last(Object range) {
        return ((NovaRange) range).getEnd();
    }

    // ========== 方法 ==========

    public static Object size(Object range) {
        return ((NovaRange) range).size();
    }

    public static Object contains(Object range, Object value) {
        return ((NovaRange) range).contains(((Number) value).intValue());
    }

    public static Object toList(Object range) {
        return ((NovaRange) range).toList();
    }

    @SuppressWarnings("unchecked")
    public static Object forEach(Object range, Object action) {
        NovaRange r = (NovaRange) range;
        Function<Object, Object> fn = (Function<Object, Object>) action;
        for (NovaValue item : r) {
            fn.apply(item);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object range, Object transform) {
        NovaRange r = (NovaRange) range;
        Function<Object, Object> fn = (Function<Object, Object>) transform;
        List<Object> result = new ArrayList<>(r.size());
        for (NovaValue item : r) {
            result.add(fn.apply(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static Object filter(Object range, Object predicate) {
        NovaRange r = (NovaRange) range;
        Function<Object, Object> fn = (Function<Object, Object>) predicate;
        List<Object> result = new ArrayList<>();
        for (NovaValue item : r) {
            if (Boolean.TRUE.equals(fn.apply(item))) {
                result.add(item.toJavaValue());
            }
        }
        return result;
    }
}
