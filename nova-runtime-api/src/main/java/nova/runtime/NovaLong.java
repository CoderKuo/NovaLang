package nova.runtime;

/**
 * Nova Long 值（64位整数）
 */
public final class NovaLong extends AbstractNovaValue implements NovaNumber {

    // 小整数缓存
    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 1024;
    private static final NovaLong[] CACHE = new NovaLong[CACHE_HIGH - CACHE_LOW + 1];
    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new NovaLong(CACHE_LOW + i);
        }
    }

    /** 获取 NovaLong 实例，优先从缓存取 */
    public static NovaLong of(long value) {
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            return CACHE[(int) value - CACHE_LOW];
        }
        return new NovaLong(value);
    }

    private final long value;

    public NovaLong(long value) {
        this.value = value;
    }

    public long getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "Long";
    }

    @Override
    public String getNovaTypeName() {
        return "Long";
    }

    @Override
    public Object toJavaValue() {
        return value;
    }

    @Override
    public boolean isInteger() {
        return true;
    }

    @Override
    public boolean isFloatingPoint() {
        return false;
    }

    @Override
    public int intValue() {
        return (int) value;
    }

    @Override
    public long longValue() {
        return value;
    }

    @Override
    public double doubleValue() {
        return value;
    }

    @Override
    public int asInt() {
        return (int) value;
    }

    @Override
    public long asLong() {
        return value;
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public String asString() {
        return Long.toString(value);
    }

    @Override
    public String toString() {
        return value + "L";
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other instanceof NovaLong) {
            return this.value == ((NovaLong) other).value;
        }
        if (other instanceof NovaInt) {
            return this.value == ((NovaInt) other).getValue();
        }
        if (other.isNumber()) {
            return this.asDouble() == other.asDouble();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    // ============ 算术运算 ============

    public NovaLong add(NovaLong other) {
        return new NovaLong(this.value + other.value);
    }

    public NovaLong subtract(NovaLong other) {
        return new NovaLong(this.value - other.value);
    }

    public NovaLong multiply(NovaLong other) {
        return new NovaLong(this.value * other.value);
    }

    public NovaLong divide(NovaLong other) {
        if (other.value == 0) {
            throw new NovaException("Division by zero");
        }
        return new NovaLong(this.value / other.value);
    }

    public NovaLong modulo(NovaLong other) {
        if (other.value == 0) {
            throw new NovaException("Modulo by zero");
        }
        return new NovaLong(this.value % other.value);
    }

    public NovaLong negate() {
        return new NovaLong(-this.value);
    }
}
