package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;

import com.novalang.runtime.stdlib.Ext;
import com.novalang.runtime.stdlib.ExtProperty;
import com.novalang.runtime.stdlib.internal.RangeOps;

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
        return RangeOps.first((NovaRange) range);
    }

    @ExtProperty
    public static Object last(Object range) {
        return RangeOps.last((NovaRange) range);
    }

    @ExtProperty
    public static Object step(Object range) {
        return RangeOps.step((NovaRange) range);
    }

    // ========== 方法 ==========

    /** 设置步长，返回新 Range */
    public static Object step(Object range, Object stepVal) {
        return ((NovaRange) range).step(((Number) stepVal).intValue());
    }

    public static Object reversed(Object range) {
        return RangeOps.reversed((NovaRange) range);
    }

    public static Object size(Object range) {
        return RangeOps.size((NovaRange) range);
    }

    public static Object contains(Object range, Object value) {
        return RangeOps.contains((NovaRange) range, ((Number) value).intValue());
    }

    public static Object toList(Object range) {
        return RangeOps.toNovaList((NovaRange) range);
    }

    @SuppressWarnings("unchecked")
    public static Object forEach(Object range, Object action) {
        return RangeOps.forEach((NovaRange) range, (Function<Object, Object>) action);
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object range, Object transform) {
        return RangeOps.map((NovaRange) range, (Function<Object, Object>) transform);
    }

    @SuppressWarnings("unchecked")
    public static Object filter(Object range, Object predicate) {
        return RangeOps.filter((NovaRange) range, (Function<Object, Object>) predicate);
    }
}
