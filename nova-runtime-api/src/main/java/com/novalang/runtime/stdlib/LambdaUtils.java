package com.novalang.runtime.stdlib;

import com.novalang.runtime.AbstractNovaValue;
import com.novalang.runtime.Function1;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lambda 相关工具方法（包内共享）。
 *
 * <p>统一的 lambda 反射调用 + 方法缓存，供 NovaScopeFunctions 和 StdlibCore 共用。</p>
 */
final class LambdaUtils {

    private LambdaUtils() {}

    // ============ 真值判断 ============

    /**
     * 判断值是否为"真值"（统一语义），委托 {@link AbstractNovaValue#truthyCheck(Object)}。
     */
    static boolean isTruthy(Object value) {
        return AbstractNovaValue.truthyCheck(value);
    }

    // ============ Lambda 反射调用 ============

    private static final ConcurrentHashMap<Class<?>, Method> invoke0Cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> invoke1Cache = new ConcurrentHashMap<>();

    /** 0 参数调用 */
    static Object invoke0(Object lambda) {
        try {
            Method m = invoke0Cache.computeIfAbsent(lambda.getClass(), cls -> {
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) return method;
                }
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName())) return method;
                }
                throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
            });
            return m.invoke(lambda);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    /** 1 参数调用 */
    static Object invoke1(Object lambda, Object arg) {
        try {
            Method m = invoke1Cache.computeIfAbsent(lambda.getClass(), cls -> {
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) return method;
                }
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName())) return method;
                }
                throw new RuntimeException("Lambda has no invoke(Object) method: " + cls.getName());
            });
            return m.invoke(lambda, arg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    /** 解析 1 参数 invoke，找不到返回 null（带缓存） */
    static Method resolveInvoke1OrNull(Object lambda) {
        Class<?> cls = lambda.getClass();
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

    /** 是否有 1 参数 invoke（带缓存） */
    static boolean hasInvoke1(Object lambda) {
        return resolveInvoke1OrNull(lambda) != null;
    }

    /**
     * 灵活调用：Function1 快速路径 → 1 参数回退 → 0 参数。
     * 供作用域函数（let/also/run/apply）和 StdlibCore.withScope/repeat 共用。
     */
    @SuppressWarnings("unchecked")
    static Object invokeFlexible(Object block, Object arg) {
        if (block instanceof Function1) {
            return ((Function1<Object, Object>) block).invoke(arg);
        }
        Method invoke = resolveInvoke1OrNull(block);
        try {
            if (invoke != null) {
                return invoke.invoke(block, arg);
            }
            return invoke0(block);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }
}
