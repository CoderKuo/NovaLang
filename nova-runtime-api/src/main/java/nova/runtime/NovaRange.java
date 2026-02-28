package nova.runtime;

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

    public NovaRange(int start, int end, boolean inclusive) {
        this.start = start;
        this.end = end;
        this.inclusive = inclusive;
    }

    /** 便捷构造器，默认 inclusive=true（对应 start..end） */
    public NovaRange(int start, int end) {
        this(start, end, true);
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
        int actualEnd = inclusive ? end + 1 : end;
        return Math.max(0, actualEnd - start);
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(int value) {
        int actualEnd = inclusive ? end : end - 1;
        return value >= start && value <= actualEnd;
    }

    public NovaValue get(int index) {
        if (index < 0 || index >= size()) {
            throw new NovaException("Range index out of bounds: " + index);
        }
        return NovaInt.of(start + index);
    }

    /** 转为 NovaList（当确实需要完整列表时） */
    public NovaList toList() {
        NovaList list = new NovaList();
        int actualEnd = inclusive ? end + 1 : end;
        for (int i = start; i < actualEnd; i++) {
            list.add(NovaInt.of(i));
        }
        return list;
    }

    /** 转为 Java Integer List（兼容旧 IntRange.toList()） */
    public java.util.List<Integer> toIntList() {
        java.util.List<Integer> list = new java.util.ArrayList<>(size());
        int actualEnd = inclusive ? end + 1 : end;
        for (int i = start; i < actualEnd; i++) {
            list.add(i);
        }
        return list;
    }

    @Override
    public Iterator<NovaValue> iterator() {
        return new Iterator<NovaValue>() {
            private int current = start;
            private final int limit = inclusive ? end + 1 : end;

            @Override
            public boolean hasNext() {
                return current < limit;
            }

            @Override
            public NovaValue next() {
                return NovaInt.of(current++);
            }
        };
    }

    /** Integer 迭代器，兼容旧 IntRange 的 Iterable<Integer> 用法 */
    public Iterator<Integer> intIterator() {
        return new Iterator<Integer>() {
            private int current = start;
            private final int limit = inclusive ? end + 1 : end;

            @Override
            public boolean hasNext() {
                return current < limit;
            }

            @Override
            public Integer next() {
                return current++;
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
        return start + (inclusive ? ".." : "..<") + end;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NovaRange)) return false;
        NovaRange other = (NovaRange) o;
        return start == other.start && end == other.end && inclusive == other.inclusive;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * start + end) + (inclusive ? 1 : 0);
    }
}
