package nova.runtime;

/**
 * Nova Double 值（64位浮点数）
 */
public final class NovaDouble extends NovaValue {

    private static final NovaDouble ZERO = new NovaDouble(0.0);
    private static final NovaDouble ONE = new NovaDouble(1.0);

    /** 获取 NovaDouble 实例，常见值从缓存取 */
    public static NovaDouble of(double value) {
        if (value == 0.0 && Double.doubleToRawLongBits(value) == 0L) return ZERO;
        if (value == 1.0) return ONE;
        return new NovaDouble(value);
    }

    private final double value;

    public NovaDouble(double value) {
        this.value = value;
    }

    public double getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "Double";
    }

    @Override
    public String getNovaTypeName() {
        return "Double";
    }

    @Override
    public Object toJavaValue() {
        return value;
    }

    @Override
    public boolean isNumber() {
        return true;
    }

    @Override
    public boolean isDouble() {
        return true;
    }

    @Override
    public int asInt() {
        return (int) value;
    }

    @Override
    public long asLong() {
        return (long) value;
    }

    @Override
    public double asDouble() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other.isNumber()) {
            return this.value == other.asDouble();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Double.hashCode(value);
    }

    // ============ 算术运算 ============

    public NovaDouble add(NovaDouble other) {
        return new NovaDouble(this.value + other.value);
    }

    public NovaDouble subtract(NovaDouble other) {
        return new NovaDouble(this.value - other.value);
    }

    public NovaDouble multiply(NovaDouble other) {
        return new NovaDouble(this.value * other.value);
    }

    public NovaDouble divide(NovaDouble other) {
        return new NovaDouble(this.value / other.value);
    }

    public NovaDouble modulo(NovaDouble other) {
        return new NovaDouble(this.value % other.value);
    }

    public NovaDouble negate() {
        return new NovaDouble(-this.value);
    }

    // ============ 比较运算 ============

    public boolean lessThan(NovaDouble other) {
        return this.value < other.value;
    }

    public boolean lessOrEqual(NovaDouble other) {
        return this.value <= other.value;
    }

    public boolean greaterThan(NovaDouble other) {
        return this.value > other.value;
    }

    public boolean greaterOrEqual(NovaDouble other) {
        return this.value >= other.value;
    }
}
