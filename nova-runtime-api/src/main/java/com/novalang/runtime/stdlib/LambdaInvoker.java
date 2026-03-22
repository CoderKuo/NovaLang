package com.novalang.runtime.stdlib;

/**
 * Lambda 调用辅助：当 lambda 参数个数超过 Function3（即 4+ 参数）且编译期
 * 类型为 Object 时，委托 LambdaUtils 的统一 MethodHandle 缓存调用。
 * <p>
 * 提供 invoke4 ~ invoke8 固定参数重载，避免 MIR 层面创建数组。
 * </p>
 */
public final class LambdaInvoker {

    private LambdaInvoker() {}

    public static Object invoke4(Object fn, Object a0, Object a1, Object a2, Object a3) {
        return LambdaUtils.invokeN(fn, 4, a0, a1, a2, a3);
    }

    public static Object invoke5(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4) {
        return LambdaUtils.invokeN(fn, 5, a0, a1, a2, a3, a4);
    }

    public static Object invoke6(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
        return LambdaUtils.invokeN(fn, 6, a0, a1, a2, a3, a4, a5);
    }

    public static Object invoke7(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        return LambdaUtils.invokeN(fn, 7, a0, a1, a2, a3, a4, a5, a6);
    }

    public static Object invoke8(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        return LambdaUtils.invokeN(fn, 8, a0, a1, a2, a3, a4, a5, a6, a7);
    }
}
