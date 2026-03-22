package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaDynamic;
import com.novalang.runtime.NovaResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

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

    /** 将任意 Iterable/Collection/List 统一为可迭代对象 */
    private static Iterable<?> asIterable(Object obj) {
        if (obj instanceof Iterable) return (Iterable<?>) obj;
        if (obj instanceof Object[]) return java.util.Arrays.asList((Object[]) obj);
        throw new ClassCastException("Expected Iterable, got: " + obj.getClass().getName());
    }

    public static Object filter(Object listObj, Object lambda) {
        if (listObj instanceof Map) {
            return MapExtensions.filter(listObj, lambda);
        }
        List<Object> result = new ArrayList<>();
        for (Object item : asIterable(listObj)) {
            if (isTruthy(invokeLambda1(lambda, item))) result.add(item);
        }
        return result;
    }

    public static Object map(Object listObj, Object lambda) {
        if (listObj instanceof Map) {
            return MapExtensions.map(listObj, lambda);
        }
        if (listObj instanceof NovaResult) {
            NovaResult r = (NovaResult) listObj;
            if (r.isOk()) return NovaResult.ok(invokeLambda1(lambda, r.getValue()));
            return r;
        }
        List<Object> result = new ArrayList<>();
        for (Object item : asIterable(listObj)) {
            result.add(invokeLambda1(lambda, item));
        }
        return result;
    }

    public static Object forEach(Object listObj, Object lambda) {
        if (listObj instanceof Map) {
            return MapExtensions.forEach(listObj, lambda);
        }
        for (Object item : asIterable(listObj)) {
            invokeLambda1(lambda, item);
        }
        return null;
    }

    public static Object find(Object listObj, Object lambda) {
        if (!(listObj instanceof List) && !(listObj instanceof Collection)) {
            return NovaDynamic.invoke1(listObj, "find", lambda);
        }
        for (Object item : asIterable(listObj)) {
            if (isTruthy(invokeLambda1(lambda, item))) return item;
        }
        return null;
    }

    public static Object mapNotNull(Object listObj, Object lambda) {
        List<Object> result = new ArrayList<>();
        for (Object item : asIterable(listObj)) {
            Object mapped = invokeLambda1(lambda, item);
            if (mapped != null) result.add(mapped);
        }
        return result;
    }

    public static Object reduce(Object listObj, Object lambda) {
        java.util.Iterator<?> it = asIterable(listObj).iterator();
        if (!it.hasNext()) throw new IllegalArgumentException("Cannot reduce an empty list");
        Object acc = it.next();
        while (it.hasNext()) {
            acc = invokeLambda2(lambda, acc, it.next());
        }
        return acc;
    }

    public static Object reduce(Object listObj, Object initial, Object lambda) {
        Object acc = initial;
        for (Object item : asIterable(listObj)) {
            acc = invokeLambda2(lambda, acc, item);
        }
        return acc;
    }

    // ========== Lambda 调用辅助（委托 LambdaUtils 统一 MethodHandle 缓存） ==========

    private static Object invokeLambda1(Object lambda, Object arg) {
        return LambdaUtils.invoke1(lambda, arg);
    }

    private static Object invokeLambda2(Object lambda, Object arg1, Object arg2) {
        return LambdaUtils.invoke2(lambda, arg1, arg2);
    }

    private static boolean isTruthy(Object value) {
        return LambdaUtils.isTruthy(value);
    }
}
