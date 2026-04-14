package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;

import com.novalang.runtime.stdlib.Ext;
import com.novalang.runtime.stdlib.ExtProperty;
import com.novalang.runtime.stdlib.internal.ArrayOps;

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
        return ArrayOps.size((NovaArray) arr);
    }

    public static Object toList(Object arr) {
        return ArrayOps.toNovaList((NovaArray) arr);
    }

    @SuppressWarnings("unchecked")
    public static Object forEach(Object arr, Object action) {
        return ArrayOps.forEach((NovaArray) arr, (Function<Object, Object>) action);
    }

    @SuppressWarnings("unchecked")
    public static Object map(Object arr, Object transform) {
        return ArrayOps.map((NovaArray) arr, (Function<Object, Object>) transform);
    }

    @SuppressWarnings("unchecked")
    public static Object filter(Object arr, Object predicate) {
        return ArrayOps.filter((NovaArray) arr, (Function<Object, Object>) predicate);
    }

    public static Object contains(Object arr, Object value) {
        return ArrayOps.contains((NovaArray) arr, value);
    }

    public static Object indexOf(Object arr, Object value) {
        return ArrayOps.indexOf((NovaArray) arr, value);
    }
}
