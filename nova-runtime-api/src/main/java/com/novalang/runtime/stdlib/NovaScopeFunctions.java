package com.novalang.runtime.stdlib;

/**
 * 作用域函数（let/also/run/apply/takeIf/takeUnless）。
 * <p>
 * 编译路径中 obj.let { ... } 等调用会被路由到此类的静态方法。
 * Lambda 反射调用和缓存统一由 {@link LambdaUtils} 管理。
 * </p>
 */
public final class NovaScopeFunctions {

    private NovaScopeFunctions() {}

    /** scope receiver: run/apply 中 lambda 的隐式 this 绑定 */
    private static final ThreadLocal<Object> SCOPE_RECEIVER = new ThreadLocal<>();

    /** 获取当前 scope receiver（供 NovaScriptContext.call 回退方法分派） */
    public static Object getScopeReceiver() {
        return SCOPE_RECEIVER.get();
    }

    /** 设置 scope receiver（供 StdlibCore.withScope 等外部调用） */
    public static void setScopeReceiver(Object receiver) {
        SCOPE_RECEIVER.set(receiver);
    }

    // ========== 核心方法 ==========

    /**
     * let: block(self) → 返回 block 结果
     */
    public static Object let(Object self, Object block) {
        return LambdaUtils.invokeFlexible(block, self);
    }

    /**
     * also: block(self) → 返回 self
     */
    public static Object also(Object self, Object block) {
        LambdaUtils.invokeFlexible(block, self);
        return self;
    }

    /**
     * run: block 可能是 0 参数（this 绑定）或 1 参数 → 返回 block 结果
     */
    public static Object run(Object self, Object block) {
        Object prev = SCOPE_RECEIVER.get();
        SCOPE_RECEIVER.set(self);
        try {
            return LambdaUtils.invokeFlexible(block, self);
        } finally {
            SCOPE_RECEIVER.set(prev);
        }
    }

    /**
     * apply: block 可能是 0 参数（this 绑定）或 1 参数 → 返回 self
     */
    public static Object apply(Object self, Object block) {
        Object prev = SCOPE_RECEIVER.get();
        SCOPE_RECEIVER.set(self);
        try {
            LambdaUtils.invokeFlexible(block, self);
        } finally {
            SCOPE_RECEIVER.set(prev);
        }
        return self;
    }

    /**
     * takeIf: predicate(self) 为 true → self，否则 null
     */
    public static Object takeIf(Object self, Object block) {
        return LambdaUtils.isTruthy(LambdaUtils.invokeFlexible(block, self)) ? self : null;
    }

    /**
     * takeUnless: predicate(self) 为 false → self，否则 null
     */
    public static Object takeUnless(Object self, Object block) {
        return LambdaUtils.isTruthy(LambdaUtils.invokeFlexible(block, self)) ? null : self;
    }
}
