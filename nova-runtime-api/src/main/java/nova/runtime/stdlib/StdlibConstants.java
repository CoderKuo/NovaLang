package nova.runtime.stdlib;

import nova.runtime.NovaConstant;

/**
 * 数学/数值常量的 stdlib 注册。
 *
 * <p>编译器可通过 {@code GETSTATIC} 直接读取 public static final 字段，
 * 解释器通过 {@link StdlibRegistry.ConstantInfo#value} 获取值。</p>
 */
public final class StdlibConstants {

    private StdlibConstants() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibConstants";

    @NovaConstant(type = "Double", description = "圆周率 π")
    public static final double PI = Math.PI;

    @NovaConstant(type = "Double", description = "自然常数 e")
    public static final double E = Math.E;

    @NovaConstant(type = "Double", description = "τ = 2π")
    public static final double TAU = 2 * Math.PI;

    @NovaConstant(type = "Int", description = "Int 最大值")
    public static final int MAX_INT = Integer.MAX_VALUE;

    @NovaConstant(type = "Int", description = "Int 最小值")
    public static final int MIN_INT = Integer.MIN_VALUE;

    @NovaConstant(type = "Double", description = "正无穷大")
    public static final double POSITIVE_INFINITY = Double.POSITIVE_INFINITY;

    @NovaConstant(type = "Double", description = "负无穷大")
    public static final double NEGATIVE_INFINITY = Double.NEGATIVE_INFINITY;

    @NovaConstant(type = "Double", description = "非数字值")
    public static final double NaN = Double.NaN;

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "PI", OWNER, "PI", "D", PI));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "E", OWNER, "E", "D", E));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "TAU", OWNER, "TAU", "D", TAU));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "MAX_INT", OWNER, "MAX_INT", "I", MAX_INT));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "MIN_INT", OWNER, "MIN_INT", "I", MIN_INT));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "POSITIVE_INFINITY", OWNER, "POSITIVE_INFINITY", "D", POSITIVE_INFINITY));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "NEGATIVE_INFINITY", OWNER, "NEGATIVE_INFINITY", "D", NEGATIVE_INFINITY));
        StdlibRegistry.register(new StdlibRegistry.ConstantInfo(
            "NaN", OWNER, "NaN", "D", NaN));
    }
}
