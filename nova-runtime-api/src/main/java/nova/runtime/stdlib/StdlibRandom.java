package nova.runtime.stdlib;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import nova.runtime.NovaFunction;

/**
 * 随机数相关的 stdlib 函数。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 */
public final class StdlibRandom {

    private StdlibRandom() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibRandom";
    private static final String OOO_O = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OO_O  = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String _O    = "()Ljava/lang/Object;";

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomInt", 2, OWNER, "randomInt", OO_O,
            args -> randomInt(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomLong", 2, OWNER, "randomLong", OO_O,
            args -> randomLong(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomDouble", 2, OWNER, "randomDouble", OO_O,
            args -> randomDouble(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomBool", 0, OWNER, "randomBool", _O,
            args -> randomBool()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomStr", 2, OWNER, "randomStr", OO_O,
            args -> randomStr(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "randomList", 3, OWNER, "randomList", OOO_O,
            args -> randomList(args[0], args[1], args[2])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    @NovaFunction(signature = "randomInt(min, max)", description = "闭区间随机整数 [min, max]", returnType = "Int")
    public static Object randomInt(Object min, Object max) {
        int lo = ((Number) min).intValue();
        int hi = ((Number) max).intValue();
        return ThreadLocalRandom.current().nextInt(lo, hi + 1);
    }

    @NovaFunction(signature = "randomLong(min, max)", description = "闭区间随机长整数 [min, max]", returnType = "Long")
    public static Object randomLong(Object min, Object max) {
        long lo = ((Number) min).longValue();
        long hi = ((Number) max).longValue();
        return ThreadLocalRandom.current().nextLong(lo, hi + 1);
    }

    @NovaFunction(signature = "randomDouble(min, max)", description = "半开区间随机浮点 [min, max)", returnType = "Double")
    public static Object randomDouble(Object min, Object max) {
        double lo = ((Number) min).doubleValue();
        double hi = ((Number) max).doubleValue();
        return ThreadLocalRandom.current().nextDouble(lo, hi);
    }

    @NovaFunction(description = "随机布尔值", returnType = "Boolean")
    public static Object randomBool() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    @NovaFunction(signature = "randomStr(chars, length)", description = "从字符集随机取字符拼接指定长度的字符串", returnType = "String")
    public static Object randomStr(Object chars, Object length) {
        String charset = chars.toString();
        int len = ((Number) length).intValue();
        if (charset.isEmpty()) {
            throw new IllegalArgumentException("randomStr: charset must not be empty");
        }
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        char[] buf = new char[len];
        for (int i = 0; i < len; i++) {
            buf[i] = charset.charAt(rng.nextInt(charset.length()));
        }
        return new String(buf);
    }

    @NovaFunction(signature = "randomList(min, max, size)", description = "生成指定大小的随机整数列表 [min, max]", returnType = "List")
    public static Object randomList(Object min, Object max, Object size) {
        int lo = ((Number) min).intValue();
        int hi = ((Number) max).intValue();
        int count = ((Number) size).intValue();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        List<Object> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(rng.nextInt(lo, hi + 1));
        }
        return list;
    }
}
