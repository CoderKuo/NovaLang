package nova.runtime.stdlib;

import nova.runtime.Function1;
import nova.runtime.Function2;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 集合高阶函数（filter/map/forEach/find/reduce 等）。
 * <p>
 * 编译路径中 list.filter { ... } 等调用会被路由到此类的静态方法。
 * Lambda 实现 Function1/Function2 接口时直接调用，否则回退反射。
 * </p>
 * <p>
 * 同时支持 Map 类型：当目标对象是 Map 时委托给 MapExtensions。
 * </p>
 */
public final class CollectionOps {

    private CollectionOps() {}

    // ========== 核心方法 ==========

    public static Object filter(Object listObj, Object lambda) {
        if (listObj instanceof Map) {
            return MapExtensions.filter(listObj, lambda);
        }
        List<?> list = (List<?>) listObj;
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            if (isTruthy(invokeLambda1(lambda, item))) result.add(item);
        }
        return result;
    }

    public static Object map(Object listObj, Object lambda) {
        if (listObj instanceof Map) {
            return MapExtensions.map(listObj, lambda);
        }
        List<?> list = (List<?>) listObj;
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(invokeLambda1(lambda, item));
        }
        return result;
    }

    public static Object forEach(Object listObj, Object lambda) {
        if (listObj instanceof Map) {
            return MapExtensions.forEach(listObj, lambda);
        }
        List<?> list = (List<?>) listObj;
        for (Object item : list) {
            invokeLambda1(lambda, item);
        }
        return null;
    }

    public static Object find(Object listObj, Object lambda) {
        List<?> list = (List<?>) listObj;
        for (Object item : list) {
            if (isTruthy(invokeLambda1(lambda, item))) return item;
        }
        return null;
    }

    public static Object mapNotNull(Object listObj, Object lambda) {
        List<?> list = (List<?>) listObj;
        List<Object> result = new ArrayList<>();
        for (Object item : list) {
            Object mapped = invokeLambda1(lambda, item);
            if (mapped != null) result.add(mapped);
        }
        return result;
    }

    public static Object reduce(Object listObj, Object initial, Object lambda) {
        List<?> list = (List<?>) listObj;
        Object acc = initial;
        for (Object item : list) {
            acc = invokeLambda2(lambda, acc, item);
        }
        return acc;
    }

    // ========== Lambda 调用辅助 ==========

    /** invoke 方法缓存: lambdaClass → Method（避免重复 getMethods 扫描） */
    private static final ConcurrentHashMap<Class<?>, Method> invoke1Cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> invoke2Cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    private static Object invokeLambda1(Object lambda, Object arg) {
        if (lambda instanceof Function1) {
            return ((Function1<Object, Object>) lambda).invoke(arg);
        }
        Method invoke = resolveInvoke(lambda, 1);
        try {
            return invoke.invoke(lambda, arg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object invokeLambda2(Object lambda, Object arg1, Object arg2) {
        if (lambda instanceof Function2) {
            return ((Function2<Object, Object, Object>) lambda).invoke(arg1, arg2);
        }
        Method invoke = resolveInvoke(lambda, 2);
        try {
            return invoke.invoke(lambda, arg1, arg2);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Method resolveInvoke(Object lambda, int argCount) {
        Class<?> cls = lambda.getClass();
        ConcurrentHashMap<Class<?>, Method> cache = argCount == 1 ? invoke1Cache : invoke2Cache;
        Method cached = cache.get(cls);
        if (cached != null) return cached;

        for (Method m : cls.getMethods()) {
            if ("invoke".equals(m.getName()) && m.getParameterCount() == argCount) {
                m.setAccessible(true);
                cache.put(cls, m);
                return m;
            }
        }
        throw new RuntimeException("Lambda object has no invoke method with " + argCount + " parameter(s)");
    }

    private static boolean isTruthy(Object value) {
        return LambdaUtils.isTruthy(value);
    }
}
