package nova.runtime;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Nova Array 值 — 直接映射 JVM 原生数组，避免装箱开销。
 * <p>
 * 内部用 {@code Object} 持有 {@code int[]}, {@code long[]}, {@code double[]} 等，
 * 通过 {@link ElementType} 枚举分发读写操作。
 */
public final class NovaArray extends AbstractNovaValue implements NovaContainer {

    public enum ElementType {
        INT, LONG, DOUBLE, FLOAT, BOOLEAN, CHAR, STRING, OBJECT;

        public static ElementType fromTypeName(String name) {
            switch (name) {
                case "Int":     return INT;
                case "Long":    return LONG;
                case "Double":  return DOUBLE;
                case "Float":   return FLOAT;
                case "Boolean": return BOOLEAN;
                case "Char":    return CHAR;
                case "String":  return STRING;
                default:        return OBJECT;
            }
        }
    }

    private final ElementType elementType;
    private final Object array;   // int[], long[], double[], String[], Object[] 等
    private final int length;

    /** 创建指定大小的空数组 */
    public NovaArray(ElementType elementType, int size) {
        this.elementType = elementType;
        this.length = size;
        switch (elementType) {
            case INT:     array = new int[size];     break;
            case LONG:    array = new long[size];    break;
            case DOUBLE:  array = new double[size];  break;
            case FLOAT:   array = new float[size];   break;
            case BOOLEAN: array = new boolean[size]; break;
            case CHAR:    array = new char[size];    break;
            case STRING:  array = new String[size];  break;
            default:      array = new Object[size];  break;
        }
    }

    /** 包装已有的 JVM 原生数组 */
    public NovaArray(ElementType elementType, Object array, int length) {
        this.elementType = elementType;
        this.array = array;
        this.length = length;
    }

    // ============ NovaValue 重写 ============

    @Override
    public String getTypeName() {
        return "Array";
    }

    @Override
    public String getNovaTypeName() {
        return "Array";
    }

    @Override
    public Object toJavaValue() {
        return array;
    }

    @Override
    public String asString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(get(i).asString());
        }
        sb.append("]");
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof NovaArray)) return false;
        NovaArray that = (NovaArray) other;
        if (this.length != that.length || this.elementType != that.elementType) return false;
        for (int i = 0; i < length; i++) {
            if (!this.get(i).equals(that.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = elementType.hashCode();
        for (int i = 0; i < length; i++) {
            h = 31 * h + get(i).hashCode();
        }
        return h;
    }

    // ============ 数组操作 ============

    @Override
    public int size() {
        return length;
    }

    @Override
    public boolean isEmpty() {
        return length == 0;
    }

    public int length() {
        return length;
    }

    public ElementType getElementType() {
        return elementType;
    }

    public Object getRawArray() {
        return array;
    }

    public NovaValue get(int index) {
        if (index < 0) index = length + index;
        if (index < 0 || index >= length) {
            throw new NovaException("Array index out of bounds: " + index + " (size=" + length + ")");
        }
        switch (elementType) {
            case INT:     return NovaInt.of(((int[]) array)[index]);
            case LONG:    return NovaLong.of(((long[]) array)[index]);
            case DOUBLE:  return NovaDouble.of(((double[]) array)[index]);
            case FLOAT:   return NovaFloat.of(((float[]) array)[index]);
            case BOOLEAN: return NovaBoolean.of(((boolean[]) array)[index]);
            case CHAR:    return NovaChar.of(((char[]) array)[index]);
            case STRING:  {
                String s = ((String[]) array)[index];
                return s != null ? NovaString.of(s) : NovaNull.NULL;
            }
            default: {
                Object o = ((Object[]) array)[index];
                return o != null ? AbstractNovaValue.fromJava(o) : NovaNull.NULL;
            }
        }
    }

    public void set(int index, NovaValue value) {
        if (index < 0) index = length + index;
        if (index < 0 || index >= length) {
            throw new NovaException("Array index out of bounds: " + index + " (size=" + length + ")");
        }
        switch (elementType) {
            case INT:     ((int[]) array)[index] = value.asInt();       break;
            case LONG:    ((long[]) array)[index] = value.asLong();     break;
            case DOUBLE:  ((double[]) array)[index] = value.asDouble(); break;
            case FLOAT:   ((float[]) array)[index] = (float) value.asDouble(); break;
            case BOOLEAN: ((boolean[]) array)[index] = value.isTruthy(); break;
            case CHAR:    ((char[]) array)[index] = value.asString().charAt(0); break;
            case STRING:  ((String[]) array)[index] = value.isNull() ? null : value.asString(); break;
            default:      ((Object[]) array)[index] = value.isNull() ? null : value.toJavaValue(); break;
        }
    }

    /** 转为 NovaList */
    public NovaList toNovaList() {
        List<NovaValue> list = new ArrayList<NovaValue>(length);
        for (int i = 0; i < length; i++) {
            list.add(get(i));
        }
        return new NovaList(list);
    }

    /** 从第一个 NovaValue 推断 ElementType */
    public static ElementType inferElementType(NovaValue value) {
        if (value instanceof NovaInt)     return ElementType.INT;
        if (value instanceof NovaLong)    return ElementType.LONG;
        if (value instanceof NovaDouble)  return ElementType.DOUBLE;
        if (value instanceof NovaFloat)   return ElementType.FLOAT;
        if (value instanceof NovaBoolean) return ElementType.BOOLEAN;
        if (value instanceof NovaChar)    return ElementType.CHAR;
        if (value instanceof NovaString)  return ElementType.STRING;
        return ElementType.OBJECT;
    }

    // ============ Iterable 实现 ============

    @Override
    public Iterator<NovaValue> iterator() {
        return new Iterator<NovaValue>() {
            private int cursor = 0;

            @Override
            public boolean hasNext() {
                return cursor < length;
            }

            @Override
            public NovaValue next() {
                return get(cursor++);
            }
        };
    }
}
