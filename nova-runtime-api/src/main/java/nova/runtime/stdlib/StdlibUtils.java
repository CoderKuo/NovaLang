package nova.runtime.stdlib;

/**
 * 实用工具函数：repeat, check, checkNotNull, requireNotNull, measureTimeMillis, measureNanoTime
 */
public final class StdlibUtils {

    private StdlibUtils() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibUtils";
    private static final String OO_O = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String O_O  = "(Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "check", 2, OWNER, "check", OO_O, args -> check(args[0], args[1])));

        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "checkNotNull", 2, OWNER, "checkNotNull", OO_O, args -> checkNotNull(args[0], args[1])));

        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "requireNotNull", 2, OWNER, "requireNotNull", OO_O, args -> requireNotNull(args[0], args[1])));
    }

    public static Object check(Object condition, Object message) {
        if (condition instanceof Boolean) {
            if (!(Boolean) condition) {
                throw new IllegalStateException(String.valueOf(message));
            }
        }
        return null;
    }

    public static Object checkNotNull(Object value, Object message) {
        if (value == null) {
            throw new IllegalStateException(String.valueOf(message));
        }
        return value;
    }

    public static Object requireNotNull(Object value, Object message) {
        if (value == null) {
            throw new IllegalArgumentException(String.valueOf(message));
        }
        return value;
    }
}
