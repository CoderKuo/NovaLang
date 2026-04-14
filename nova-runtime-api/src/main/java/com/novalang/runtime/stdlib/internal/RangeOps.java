package com.novalang.runtime.stdlib.internal;

import com.novalang.runtime.NovaInt;
import com.novalang.runtime.NovaList;
import com.novalang.runtime.NovaRange;
import com.novalang.runtime.NovaValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/**
 * Shared range operations used by both NovaRange and stdlib extensions.
 */
public final class RangeOps {

    private RangeOps() {}

    public static int first(NovaRange range) {
        return range.getStart();
    }

    public static int last(NovaRange range) {
        return range.getEnd();
    }

    public static int step(NovaRange range) {
        return range.getStep();
    }

    public static NovaRange reversed(NovaRange range) {
        return new NovaRange(range.getEnd(), range.getStart(), range.isInclusive(), -range.getStep());
    }

    public static int size(NovaRange range) {
        return size(range.getStart(), range.getRawEnd(), range.isInclusive(), range.getStep());
    }

    public static int size(int start, int end, boolean inclusive, int step) {
        if (step > 0) {
            int actualEnd = inclusive ? end + 1 : end;
            int span = actualEnd - start;
            return span <= 0 ? 0 : (span + step - 1) / step;
        }
        int actualEnd = inclusive ? end - 1 : end;
        int span = start - actualEnd;
        int absStep = -step;
        return span <= 0 ? 0 : (span + absStep - 1) / absStep;
    }

    public static boolean contains(NovaRange range, int value) {
        return contains(range.getStart(), range.getRawEnd(), range.isInclusive(), range.getStep(), value);
    }

    public static boolean contains(int start, int end, boolean inclusive, int step, int value) {
        if (step > 0) {
            int actualEnd = inclusive ? end : end - 1;
            if (value < start || value > actualEnd) return false;
        } else {
            int actualEnd = inclusive ? end : end + 1;
            if (value > start || value < actualEnd) return false;
        }
        return (value - start) % step == 0;
    }

    public static NovaList toNovaList(NovaRange range) {
        NovaList list = new NovaList();
        Iterator<Integer> iterator = intIterator(range);
        while (iterator.hasNext()) {
            list.add(NovaInt.of(iterator.next().intValue()));
        }
        return list;
    }

    public static List<Integer> toIntList(NovaRange range) {
        List<Integer> list = new ArrayList<Integer>();
        Iterator<Integer> iterator = intIterator(range);
        while (iterator.hasNext()) {
            list.add(iterator.next());
        }
        return list;
    }

    public static Iterator<Integer> intIterator(NovaRange range) {
        return intIterator(range.getStart(), range.getRawEnd(), range.isInclusive(), range.getStep());
    }

    public static Iterator<Integer> intIterator(final int start, final int end, final boolean inclusive, final int step) {
        return new Iterator<Integer>() {
            private int current = start;

            @Override
            public boolean hasNext() {
                return step > 0 ? current < (inclusive ? end + 1 : end) : current > (inclusive ? end - 1 : end);
            }

            @Override
            public Integer next() {
                int value = current;
                current += step;
                return Integer.valueOf(value);
            }
        };
    }

    public static Object forEach(NovaRange range, Function<Object, Object> action) {
        Iterator<Integer> iterator = intIterator(range);
        while (iterator.hasNext()) {
            action.apply(NovaInt.of(iterator.next().intValue()));
        }
        return null;
    }

    public static List<Object> map(NovaRange range, Function<Object, Object> transform) {
        List<Object> result = new ArrayList<Object>(size(range));
        Iterator<Integer> iterator = intIterator(range);
        while (iterator.hasNext()) {
            result.add(transform.apply(NovaInt.of(iterator.next().intValue())));
        }
        return result;
    }

    public static List<Object> filter(NovaRange range, Function<Object, Object> predicate) {
        List<Object> result = new ArrayList<Object>();
        Iterator<Integer> iterator = intIterator(range);
        while (iterator.hasNext()) {
            int value = iterator.next().intValue();
            NovaValue item = NovaInt.of(value);
            if (Boolean.TRUE.equals(predicate.apply(item))) {
                result.add(item.toJavaValue());
            }
        }
        return result;
    }
}
