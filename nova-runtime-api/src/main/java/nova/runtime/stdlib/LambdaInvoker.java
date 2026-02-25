package nova.runtime.stdlib;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lambda 调用辅助：当 lambda 参数个数超过 Function3（即 4+ 参数）且编译期
 * 类型为 Object 时，通过缓存反射调用 lambda 的 invoke 方法。
 * <p>
 * 提供 invoke4 ~ invoke8 固定参数重载，避免 MIR 层面创建数组。
 * </p>
 */
public final class LambdaInvoker {

    private LambdaInvoker() {}

    private static final ConcurrentHashMap<Class<?>, Method[]> cache = new ConcurrentHashMap<>();

    public static Object invoke4(Object fn, Object a0, Object a1, Object a2, Object a3) {
        try {
            return resolveInvoke(fn, 4).invoke(fn, a0, a1, a2, a3);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object invoke5(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4) {
        try {
            return resolveInvoke(fn, 5).invoke(fn, a0, a1, a2, a3, a4);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object invoke6(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
        try {
            return resolveInvoke(fn, 6).invoke(fn, a0, a1, a2, a3, a4, a5);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object invoke7(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        try {
            return resolveInvoke(fn, 7).invoke(fn, a0, a1, a2, a3, a4, a5, a6);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object invoke8(Object fn, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        try {
            return resolveInvoke(fn, 8).invoke(fn, a0, a1, a2, a3, a4, a5, a6, a7);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 通过缓存反射查找 lambda 上 invoke 方法。
     * 缓存以 Class 为 key，Method[9] 数组存储 0~8 参数的 invoke 方法。
     */
    private static Method resolveInvoke(Object fn, int argCount) {
        Class<?> cls = fn.getClass();
        Method[] methods = cache.get(cls);
        if (methods != null && methods[argCount] != null) {
            return methods[argCount];
        }
        if (methods == null) {
            methods = new Method[9];
            cache.put(cls, methods);
        }
        for (Method m : cls.getMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterCount() == argCount) {
                m.setAccessible(true);
                methods[argCount] = m;
                return m;
            }
        }
        throw new RuntimeException("Lambda has no invoke method with " + argCount + " parameter(s)");
    }
}
