package nova.runtime.stdlib;

import nova.runtime.NovaFunction;

/**
 * 数学相关的 stdlib 函数。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 */
public final class StdlibMath {

    private StdlibMath() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibMath";
    private static final String OOO_O = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OO_O  = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String O_O   = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String _O    = "()Ljava/lang/Object;";

    static void register() {
        // ---- 基础运算 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "min", 2, OWNER, "min", OO_O, args -> min(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "max", 2, OWNER, "max", OO_O, args -> max(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "abs", 1, OWNER, "abs", O_O, args -> abs(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sqrt", 1, OWNER, "sqrt", O_O, args -> sqrt(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "pow", 2, OWNER, "pow", OO_O, args -> pow(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "floor", 1, OWNER, "floor", O_O, args -> floor(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "ceil", 1, OWNER, "ceil", O_O, args -> ceil(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "round", 1, OWNER, "round", O_O, args -> round(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "random", 0, OWNER, "random", _O, args -> random()));

        // ---- 三角函数 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sin", 1, OWNER, "sin", O_O, args -> sin(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "cos", 1, OWNER, "cos", O_O, args -> cos(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "tan", 1, OWNER, "tan", O_O, args -> tan(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "asin", 1, OWNER, "asin", O_O, args -> asin(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "acos", 1, OWNER, "acos", O_O, args -> acos(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "atan", 1, OWNER, "atan", O_O, args -> atan(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "atan2", 2, OWNER, "atan2", OO_O, args -> atan2(args[0], args[1])));

        // ---- 对数 / 指数 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "log", 1, OWNER, "log", O_O, args -> log(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "log10", 1, OWNER, "log10", O_O, args -> log10(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "log2", 1, OWNER, "log2", O_O, args -> log2(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "exp", 1, OWNER, "exp", O_O, args -> exp(args[0])));

        // ---- 其他 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sign", 1, OWNER, "sign", O_O, args -> sign(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "clamp", 3, OWNER, "clamp", OOO_O, args -> clamp(args[0], args[1], args[2])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    // ---- 基础运算 ----

    @NovaFunction(signature = "min(a, b)", description = "返回较小值", returnType = "Number")
    public static Object min(Object a, Object b) {
        if (a instanceof Integer && b instanceof Integer) {
            return Math.min((Integer) a, (Integer) b);
        }
        if (a instanceof Long && b instanceof Long) {
            return Math.min((Long) a, (Long) b);
        }
        return Math.min(toDouble(a), toDouble(b));
    }

    @NovaFunction(signature = "max(a, b)", description = "返回较大值", returnType = "Number")
    public static Object max(Object a, Object b) {
        if (a instanceof Integer && b instanceof Integer) {
            return Math.max((Integer) a, (Integer) b);
        }
        if (a instanceof Long && b instanceof Long) {
            return Math.max((Long) a, (Long) b);
        }
        return Math.max(toDouble(a), toDouble(b));
    }

    @NovaFunction(signature = "abs(value)", description = "返回绝对值", returnType = "Number")
    public static Object abs(Object a) {
        if (a instanceof Integer) return Math.abs((Integer) a);
        if (a instanceof Long)    return Math.abs((Long) a);
        if (a instanceof Float)   return Math.abs((Float) a);
        if (a instanceof Double)  return Math.abs((Double) a);
        throw new IllegalArgumentException("abs() requires a number, got: " + a);
    }

    @NovaFunction(signature = "sqrt(value)", description = "返回平方根", returnType = "Double")
    public static Object sqrt(Object a) {
        return Math.sqrt(toDouble(a));
    }

    @NovaFunction(signature = "pow(base, exp)", description = "返回 base 的 exp 次方", returnType = "Double")
    public static Object pow(Object base, Object exp) {
        return Math.pow(toDouble(base), toDouble(exp));
    }

    @NovaFunction(signature = "floor(value)", description = "向下取整", returnType = "Int")
    public static Object floor(Object a) {
        return (int) Math.floor(toDouble(a));
    }

    @NovaFunction(signature = "ceil(value)", description = "向上取整", returnType = "Int")
    public static Object ceil(Object a) {
        return (int) Math.ceil(toDouble(a));
    }

    @NovaFunction(signature = "round(value)", description = "四舍五入", returnType = "Long")
    public static Object round(Object a) {
        return Math.round(toDouble(a));
    }

    @NovaFunction(description = "返回 0.0~1.0 的随机数", returnType = "Double")
    public static Object random() {
        return Math.random();
    }

    // ---- 三角函数 ----

    @NovaFunction(signature = "sin(x)", description = "正弦函数（弧度）", returnType = "Double")
    public static Object sin(Object a) {
        return Math.sin(toDouble(a));
    }

    @NovaFunction(signature = "cos(x)", description = "余弦函数（弧度）", returnType = "Double")
    public static Object cos(Object a) {
        return Math.cos(toDouble(a));
    }

    @NovaFunction(signature = "tan(x)", description = "正切函数（弧度）", returnType = "Double")
    public static Object tan(Object a) {
        return Math.tan(toDouble(a));
    }

    @NovaFunction(signature = "asin(x)", description = "反正弦函数", returnType = "Double")
    public static Object asin(Object a) {
        return Math.asin(toDouble(a));
    }

    @NovaFunction(signature = "acos(x)", description = "反余弦函数", returnType = "Double")
    public static Object acos(Object a) {
        return Math.acos(toDouble(a));
    }

    @NovaFunction(signature = "atan(x)", description = "反正切函数", returnType = "Double")
    public static Object atan(Object a) {
        return Math.atan(toDouble(a));
    }

    @NovaFunction(signature = "atan2(y, x)", description = "二参数反正切函数", returnType = "Double")
    public static Object atan2(Object y, Object x) {
        return Math.atan2(toDouble(y), toDouble(x));
    }

    // ---- 对数 / 指数 ----

    @NovaFunction(signature = "log(x)", description = "自然对数 ln(x)", returnType = "Double")
    public static Object log(Object a) {
        return Math.log(toDouble(a));
    }

    @NovaFunction(signature = "log10(x)", description = "常用对数 log10(x)", returnType = "Double")
    public static Object log10(Object a) {
        return Math.log10(toDouble(a));
    }

    private static final double LOG2 = Math.log(2);

    @NovaFunction(signature = "log2(x)", description = "二进制对数 log2(x)", returnType = "Double")
    public static Object log2(Object a) {
        return Math.log(toDouble(a)) / LOG2;
    }

    @NovaFunction(signature = "exp(x)", description = "指数函数 e^x", returnType = "Double")
    public static Object exp(Object a) {
        return Math.exp(toDouble(a));
    }

    // ---- 其他 ----

    @NovaFunction(signature = "sign(value)", description = "符号函数，返回 -1/0/1", returnType = "Number")
    public static Object sign(Object a) {
        if (a instanceof Integer) return Integer.signum((Integer) a);
        if (a instanceof Long)    return Long.signum((Long) a);
        return Math.signum(toDouble(a));
    }

    @NovaFunction(signature = "clamp(value, min, max)", description = "将值限制在 [min, max] 范围内", returnType = "Number")
    public static Object clamp(Object value, Object min, Object max) {
        if (value instanceof Integer && min instanceof Integer && max instanceof Integer) {
            int v = (Integer) value, lo = (Integer) min, hi = (Integer) max;
            return Math.max(lo, Math.min(hi, v));
        }
        if (value instanceof Long && min instanceof Long && max instanceof Long) {
            long v = (Long) value, lo = (Long) min, hi = (Long) max;
            return Math.max(lo, Math.min(hi, v));
        }
        double v = toDouble(value), lo = toDouble(min), hi = toDouble(max);
        return Math.max(lo, Math.min(hi, v));
    }

    // ---- 工具 ----

    private static double toDouble(Object o) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        throw new IllegalArgumentException("requires a number, got: " + o);
    }
}
