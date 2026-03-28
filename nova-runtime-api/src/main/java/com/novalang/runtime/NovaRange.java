package com.novalang.runtime;

import java.util.Iterator;

/**
 * Nova Range 值（惰性求值，不会预先生成所有元素）
 * 支持 for 循环迭代、size、contains 等操作。
 * 同时替代旧的 IntRange 类。
 */
public final class NovaRange extends AbstractNovaValue implements NovaContainer {

    private final int start;
    private final int end;
    private final boolean inclusive;
    private final int step;

    public NovaRange(int start, int end, boolean inclusive) {
        this(start, end, inclusive, 1);
    }

    public NovaRange(int start, int end, boolean inclusive, int step) {
        this.start = start;
        this.end = end;
        this.inclusive = inclusive;
        this.step = step != 0 ? step : 1;
    }

    /** 便捷构造器，默认 inclusive=true（对应 start..end） */
    public NovaRange(int start, int end) {
        this(start, end, true, 1);
    }

    public int getStep() {
        return step;
    }

    /** 创建带步长的新 Range */
    public NovaRange step(int newStep) {
        return new NovaRange(start, end, inclusive, newStep);
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return inclusive ? end : end - 1;
    }

    /** 返回不含最后元素的结束位置（exclusive end），等价于旧 IntRange 的语义 */
    public int getEndExclusive() {
        return inclusive ? end + 1 : end;
    }

    /** 旧 IntRange 兼容方法 */
    public int getEndInclusive() {
        return inclusive ? end : end - 1;
    }

    public boolean isInclusive() {
        return inclusive;
    }

    public int size() {
        if (step > 0) {
            int actualEnd = inclusive ? end + 1 : end;
            int span = actualEnd - start;
            return span <= 0 ? 0 : (span + step - 1) / step;
        } else {
            int actualEnd = inclusive ? end - 1 : end;
            int span = start - actualEnd;
            int absStep = -step;
            return span <= 0 ? 0 : (span + absStep - 1) / absStep;
        }
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(int value) {
        if (step > 0) {
            int actualEnd = inclusive ? end : end - 1;
            if (value < start || value > actualEnd) return false;
        } else {
            int actualEnd = inclusive ? end : end + 1;
            if (value > start || value < actualEnd) return false;
        }
        return (value - start) % step == 0;
    }

    public NovaValue get(int index) {
        if (index < 0 || index >= size()) {
            throw new NovaException("Range index out of bounds: " + index);
        }
        return NovaInt.of(start + index * step);
    }

    /** 转为 NovaList */
    public NovaList toList() {
        NovaList list = new NovaList();
        if (step > 0) {
            int limit = inclusive ? end + 1 : end;
            for (int i = start; i < limit; i += step) list.add(NovaInt.of(i));
        } else {
            int limit = inclusive ? end - 1 : end;
            for (int i = start; i > limit; i += step) list.add(NovaInt.of(i));
        }
        return list;
    }

    /** 转为 Java Integer List */
    public java.util.List<Integer> toIntList() {
        java.util.List<Integer> list = new java.util.ArrayList<>();
        if (step > 0) {
            int limit = inclusive ? end + 1 : end;
            for (int i = start; i < limit; i += step) list.add(i);
        } else {
            int limit = inclusive ? end - 1 : end;
            for (int i = start; i > limit; i += step) list.add(i);
        }
        return list;
    }

    @Override
    public Iterator<NovaValue> iterator() {
        final int s = step;
        return new Iterator<NovaValue>() {
            private int current = start;
            @Override
            public boolean hasNext() {
                return s > 0 ? current < (inclusive ? end + 1 : end) : current > (inclusive ? end - 1 : end);
            }
            @Override
            public NovaValue next() {
                int val = current;
                current += s;
                return NovaInt.of(val);
            }
        };
    }

    /** Integer 迭代器 */
    public Iterator<Integer> intIterator() {
        final int s = step;
        return new Iterator<Integer>() {
            private int current = start;
            @Override
            public boolean hasNext() {
                return s > 0 ? current < (inclusive ? end + 1 : end) : current > (inclusive ? end - 1 : end);
            }
            @Override
            public Integer next() {
                int val = current;
                current += s;
                return val;
            }
        };
    }

    @Override
    public String getTypeName() {
        return "Range";
    }

    @Override
    public String getNovaTypeName() {
        return "Range";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        String base = start + (inclusive ? ".." : "..<") + end;
        return step != 1 ? base + " step " + step : base;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NovaRange)) return false;
        NovaRange other = (NovaRange) o;
        return start == other.start && end == other.end && inclusive == other.inclusive && step == other.step;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * start + end) + (inclusive ? 1 : 0)) + step;
    }
}
