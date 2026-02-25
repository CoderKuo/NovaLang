package nova.runtime;

/**
 * Nova Boolean 值
 */
public final class NovaBoolean extends NovaValue {

    /** true 常量 */
    public static final NovaBoolean TRUE = new NovaBoolean(true);

    /** false 常量 */
    public static final NovaBoolean FALSE = new NovaBoolean(false);

    private final boolean value;

    private NovaBoolean(boolean value) {
        this.value = value;
    }

    /**
     * 获取布尔值实例
     */
    public static NovaBoolean of(boolean value) {
        return value ? TRUE : FALSE;
    }

    public boolean getValue() {
        return value;
    }

    @Override
    public String getTypeName() {
        return "Boolean";
    }

    @Override
    public String getNovaTypeName() {
        return "Boolean";
    }

    @Override
    public Object toJavaValue() {
        return value;
    }

    @Override
    public boolean isTruthy() {
        return value;
    }

    @Override
    public boolean isBoolean() {
        return true;
    }

    @Override
    public boolean asBoolean() {
        return value;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    @Override
    public boolean equals(NovaValue other) {
        if (other == null) return false;
        if (other instanceof NovaBoolean) {
            return this.value == ((NovaBoolean) other).value;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Boolean.hashCode(value);
    }

    // ============ 逻辑运算 ============

    public NovaBoolean not() {
        return of(!this.value);
    }

    public NovaBoolean and(NovaBoolean other) {
        return of(this.value && other.value);
    }

    public NovaBoolean or(NovaBoolean other) {
        return of(this.value || other.value);
    }
}
