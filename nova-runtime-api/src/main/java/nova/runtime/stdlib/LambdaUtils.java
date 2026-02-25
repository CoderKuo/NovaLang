package nova.runtime.stdlib;

/**
 * Lambda 相关工具方法（包内共享）。
 */
final class LambdaUtils {

    private LambdaUtils() {}

    /**
     * 判断值是否为"真值"（统一语义）。
     * <ul>
     *   <li>null → false</li>
     *   <li>Boolean → 直接返回</li>
     *   <li>Number → 非零为 true</li>
     *   <li>String → 非空为 true</li>
     *   <li>其他 → true</li>
     * </ul>
     */
    static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        return true;
    }
}
