package nova.runtime;

/**
 * Nova 数值类型接口
 *
 * <p>所有数值类型（Int, Long, Double, Float）都实现此接口，
 * 提供统一的数值操作契约。</p>
 */
public interface NovaNumber extends NovaValue {

    /**
     * 是否为整数类型（Int 或 Long）
     */
    boolean isInteger();

    /**
     * 是否为浮点数类型（Double 或 Float）
     */
    boolean isFloatingPoint();

    /**
     * 获取 double 值
     */
    double doubleValue();

    /**
     * 获取 long 值
     */
    long longValue();

    /**
     * 获取 int 值
     */
    int intValue();

    // ============ NovaValue 默认实现覆写 ============

    @Override
    default boolean isNumber() {
        return true;
    }

    @Override
    default double asDouble() {
        return doubleValue();
    }

    @Override
    default long asLong() {
        return longValue();
    }

    @Override
    default int asInt() {
        return intValue();
    }
}
