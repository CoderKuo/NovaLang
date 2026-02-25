package nova.runtime;

/**
 * Nova Float 值（32位浮点数）
 */
public final class NovaFloat extends NovaValue {

    private static final NovaFloat ZERO = new NovaFloat(0.0f);
    private static final NovaFloat ONE = new NovaFloat(1.0f);

    /** 获取 NovaFloat 实例，常见值从缓存取 */
    public static NovaFloat of(float value) {
        if (value == 0.0f && Float.floatToRawIntBits(value) == 0) return ZERO;
        if (value == 1.0f) return ONE;
        return new NovaFloat(value);
    }

    private final float value;

    public NovaFloat(float value) {
        this.value = value;
    }

    public float getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "Float";
    }

    @Override
    public String getNovaTypeName() {
        return "Float";
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
        return value + "f";
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other.isNumber()) {
            return this.asDouble() == other.asDouble();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Float.hashCode(value);
    }

    // ============ 算术运算 ============

    public NovaFloat add(NovaFloat other) {
        return new NovaFloat(this.value + other.value);
    }

    public NovaFloat subtract(NovaFloat other) {
        return new NovaFloat(this.value - other.value);
    }

    public NovaFloat multiply(NovaFloat other) {
        return new NovaFloat(this.value * other.value);
    }

    public NovaFloat divide(NovaFloat other) {
        return new NovaFloat(this.value / other.value);
    }

    public NovaFloat negate() {
        return new NovaFloat(-this.value);
    }
}
