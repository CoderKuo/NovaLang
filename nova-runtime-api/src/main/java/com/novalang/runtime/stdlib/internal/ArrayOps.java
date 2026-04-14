package com.novalang.runtime.stdlib.internal;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.NovaArray;
import com.novalang.runtime.NovaList;
import com.novalang.runtime.NovaValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Shared array operations used by both NovaArray and stdlib extensions.
 */
public final class ArrayOps {

    private ArrayOps() {}

    public static int size(NovaArray array) {
        return array.length();
    }

    public static NovaList toNovaList(NovaArray array) {
        List<NovaValue> list = new ArrayList<NovaValue>(array.length());
        for (int i = 0; i < array.length(); i++) {
            list.add(array.get(i));
        }
        return new NovaList(list);
    }

    public static boolean contains(NovaArray array, Object value) {
        NovaValue target = AbstractNovaValue.fromJava(value);
        for (int i = 0; i < array.length(); i++) {
            if (array.get(i).equals(target)) {
                return true;
            }
        }
        return false;
    }

    public static int indexOf(NovaArray array, Object value) {
        NovaValue target = AbstractNovaValue.fromJava(value);
        for (int i = 0; i < array.length(); i++) {
            if (array.get(i).equals(target)) {
                return i;
            }
        }
        return -1;
    }

    public static Object forEach(NovaArray array, Function<Object, Object> action) {
        for (int i = 0; i < array.length(); i++) {
            action.apply(array.get(i));
        }
        return null;
    }

    public static List<Object> map(NovaArray array, Function<Object, Object> transform) {
        List<Object> result = new ArrayList<Object>(array.length());
        for (int i = 0; i < array.length(); i++) {
            result.add(transform.apply(array.get(i)));
        }
        return result;
    }

    public static List<Object> filter(NovaArray array, Function<Object, Object> predicate) {
        List<Object> result = new ArrayList<Object>();
        for (int i = 0; i < array.length(); i++) {
            NovaValue item = array.get(i);
            if (Boolean.TRUE.equals(predicate.apply(item))) {
                result.add(item.toJavaValue());
            }
        }
        return result;
    }
}
