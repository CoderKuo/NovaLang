package nova.runtime;

/**
 * 运行时算术运算辅助。
 * <p>
 * 编译器在无法静态判断 ADD 操作数类型时（如 lambda 捕获变量类型为 Object），
 * 生成 {@code INVOKESTATIC NovaOps.add(Object, Object)} 调用，
 * 在运行时判断是字符串拼接还是数值加法。
 * </p>
 */
public final class NovaOps {

    private NovaOps() {}

    /**
     * 运行时动态加法：若任一操作数为 String 则拼接，否则数值加法。
     */
    public static Object add(Object a, Object b) {
        if (a instanceof String || b instanceof String) {
            return String.valueOf(a) + String.valueOf(b);
        }
        if (a instanceof Double || b instanceof Double) {
            return ((Number) a).doubleValue() + ((Number) b).doubleValue();
        }
        if (a instanceof Long || b instanceof Long) {
            return ((Number) a).longValue() + ((Number) b).longValue();
        }
        return ((Number) a).intValue() + ((Number) b).intValue();
    }
}
