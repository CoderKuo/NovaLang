package nova.runtime;

/**
 * Nova null 值和 Unit 值
 */
public final class NovaNull extends NovaValue {

    /** 唯一的 null 实例 */
    public static final NovaNull NULL = new NovaNull(true);

    /** 唯一的 Unit 实例 */
    public static final NovaNull UNIT = new NovaNull(false);

    private final boolean isNullValue;

    private NovaNull(boolean isNullValue) {
        this.isNullValue = isNullValue;
    }

    @Override
    public String getTypeName() {
        return isNullValue ? "Null" : "Unit";
    }

    @Override
    public String getNovaTypeName() {
        return "Unit";
    }

    @Override
    public Object toJavaValue() {
        return isNullValue ? null : nova.lang.Unit.INSTANCE;
    }

    @Override
    public boolean isTruthy() {
        return !isNullValue;  // null 为 false，Unit 为 true
    }

    @Override
    public boolean isNull() {
        return isNullValue;
    }

    @Override
    public String toString() {
        return isNullValue ? "null" : "Unit";
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return isNullValue;
        if (other instanceof NovaNull) {
            return this.isNullValue == ((NovaNull) other).isNullValue;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return isNullValue ? 0 : 1;
    }
}
