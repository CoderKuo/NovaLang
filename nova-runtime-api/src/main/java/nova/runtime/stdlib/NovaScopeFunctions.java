package nova.runtime.stdlib;

import nova.runtime.Function1;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 作用域函数（let/also/run/apply/takeIf/takeUnless）。
 * <p>
 * 编译路径中 obj.let { ... } 等调用会被路由到此类的静态方法。
 * Lambda 实现 Function1 接口时直接调用，否则回退反射。
 * </p>
 * <p>
 * run/apply 的 lambda 可能是 0 参数（Kotlin 语义：this 绑定）或 1 参数，
 * 因此使用 {@link #invokeBlockFlexible} 先尝试 1 参数，回退 0 参数。
 * </p>
 */
public final class NovaScopeFunctions {

    private NovaScopeFunctions() {}

    // ========== 核心方法 ==========

    /**
     * let: block(self) → 返回 block 结果
     */
    public static Object let(Object self, Object block) {
        return invokeBlock(block, self);
    }

    /**
     * also: block(self) → 返回 self
     */
    public static Object also(Object self, Object block) {
        invokeBlock(block, self);
        return self;
    }

    /**
     * run: block 可能是 0 参数（this 绑定）或 1 参数 → 返回 block 结果
     */
    public static Object run(Object self, Object block) {
        return invokeBlockFlexible(block, self);
    }

    /**
     * apply: block 可能是 0 参数（this 绑定）或 1 参数 → 返回 self
     */
    public static Object apply(Object self, Object block) {
        invokeBlockFlexible(block, self);
        return self;
    }

    /**
     * takeIf: predicate(self) 为 true → self，否则 null
     */
    public static Object takeIf(Object self, Object block) {
        return isTruthy(invokeBlock(block, self)) ? self : null;
    }

    /**
     * takeUnless: predicate(self) 为 false → self，否则 null
     */
    public static Object takeUnless(Object self, Object block) {
        return isTruthy(invokeBlock(block, self)) ? null : self;
    }

    // ========== 辅助方法 ==========

    private static final ConcurrentHashMap<Class<?>, Method> invoke1Cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> invoke0Cache = new ConcurrentHashMap<>();

    /** 严格 1 参数调用（let/also/takeIf/takeUnless） */
    @SuppressWarnings("unchecked")
    private static Object invokeBlock(Object block, Object arg) {
        if (block instanceof Function1) {
            return ((Function1<Object, Object>) block).invoke(arg);
        }
        Method invoke = resolveInvoke1(block);
        try {
            return invoke.invoke(block, arg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 灵活调用：先尝试 1 参数，回退 0 参数（run/apply） */
    @SuppressWarnings("unchecked")
    private static Object invokeBlockFlexible(Object block, Object arg) {
        if (block instanceof Function1) {
            return ((Function1<Object, Object>) block).invoke(arg);
        }
        Method invoke = resolveInvoke1OrNull(block);
        try {
            if (invoke != null) {
                return invoke.invoke(block, arg);
            }
            return resolveInvoke0(block).invoke(block);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 解析 1 参数 invoke，找不到则抛异常 */
    private static Method resolveInvoke1(Object lambda) {
        Method m = resolveInvoke1OrNull(lambda);
        if (m != null) return m;
        throw new RuntimeException("Lambda object has no invoke method with 1 parameter(s)");
    }

    /** 解析 1 参数 invoke，找不到返回 null */
    private static Method resolveInvoke1OrNull(Object lambda) {
        Class<?> cls = lambda.getClass();
        // 先查缓存（包含正向命中）
        Method cached = invoke1Cache.get(cls);
        if (cached != null) return cached;
        for (Method m : cls.getMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterCount() == 1) {
                m.setAccessible(true);
                invoke1Cache.put(cls, m);
                return m;
            }
        }
        return null;
    }

    /** 解析 0 参数 invoke */
    private static Method resolveInvoke0(Object lambda) {
        Class<?> cls = lambda.getClass();
        Method cached = invoke0Cache.get(cls);
        if (cached != null) return cached;
        for (Method m : cls.getMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterCount() == 0) {
                m.setAccessible(true);
                invoke0Cache.put(cls, m);
                return m;
            }
        }
        throw new RuntimeException("Lambda object has no invoke method with 0 or 1 parameter(s)");
    }

    private static boolean isTruthy(Object value) {
        return LambdaUtils.isTruthy(value);
    }
}
