package nova.runtime;

/**
 * Nova Int 值（32位整数）
 */
public final class NovaInt extends AbstractNovaValue implements NovaNumber {

    // 整数缓存（覆盖常见运算范围，含 Fibonacci 等递推序列）
    private static final int CACHE_LOW = -128;
    private static final int CACHE_HIGH = 100000;
    private static final NovaInt[] CACHE = new NovaInt[CACHE_HIGH - CACHE_LOW + 1];
    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new NovaInt(CACHE_LOW + i, true);
        }
    }

    /** 获取 NovaInt 实例，优先从缓存取 */
    public static NovaInt of(int value) {
        if (value >= CACHE_LOW && value <= CACHE_HIGH) {
            return CACHE[value - CACHE_LOW];
        }
        return new NovaInt(value);
    }

    private final int value;

    public NovaInt(int value) {
        this.value = value;
    }

    /** 内部构造器，仅供缓存初始化 */
    private NovaInt(int value, boolean cached) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "Int";
    }

    @Override
    public String getNovaTypeName() {
        return "Int";
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
        return value;
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
        return value;
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
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other instanceof NovaInt) {
            return this.value == ((NovaInt) other).value;
        }
        if (other instanceof NovaLong) {
            return this.value == ((NovaLong) other).getValue();
        }
        if (other.isNumber()) {
            return this.asDouble() == other.asDouble();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(value);
    }

    // ============ 算术运算 ============

    public NovaInt add(NovaInt other) {
        return NovaInt.of(this.value + other.value);
    }

    public NovaInt subtract(NovaInt other) {
        return NovaInt.of(this.value - other.value);
    }

    public NovaInt multiply(NovaInt other) {
        return NovaInt.of(this.value * other.value);
    }

    public NovaInt divide(NovaInt other) {
        if (other.value == 0) {
            throw new NovaException("Division by zero");
        }
        return NovaInt.of(this.value / other.value);
    }

    public NovaInt modulo(NovaInt other) {
        if (other.value == 0) {
            throw new NovaException("Modulo by zero");
        }
        return NovaInt.of(this.value % other.value);
    }

    public NovaInt negate() {
        return NovaInt.of(-this.value);
    }

    public NovaInt increment() {
        return NovaInt.of(this.value + 1);
    }

    public NovaInt decrement() {
        return NovaInt.of(this.value - 1);
    }

    // ============ 比较运算 ============

    public boolean lessThan(NovaInt other) {
        return this.value < other.value;
    }

    public boolean lessOrEqual(NovaInt other) {
        return this.value <= other.value;
    }

    public boolean greaterThan(NovaInt other) {
        return this.value > other.value;
    }

    public boolean greaterOrEqual(NovaInt other) {
        return this.value >= other.value;
    }
}
