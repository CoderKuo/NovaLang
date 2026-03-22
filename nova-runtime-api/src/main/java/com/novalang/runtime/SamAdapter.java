package com.novalang.runtime;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;

/**
 * SAM（Single Abstract Method）自动适配器。
 *
 * <p>将 Nova 的 FunctionN / NovaCallable lambda 自动转换为 Java 函数式接口（Comparator/Consumer/Function 等）。
 * 编译路径和解释路径共用。</p>
 */
public final class SamAdapter {

    private SamAdapter() {}

    /** SAM 方法缓存（ClassValue 随 Class 生命周期自动回收，不钉住 ClassLoader） */
    private static final ClassValue<Method> samCacheCV = new ClassValue<Method>() {
        @Override
        protected Method computeValue(Class<?> cls) {
            if (!cls.isInterface()) return null;
            Method sam = null;
            for (Method m : cls.getMethods()) {
                if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
                if (OBJECT_METHOD_NAMES.contains(m.getName() + ":" + m.getParameterCount())) continue;
                if (sam != null) return null; // 多个抽象方法
                sam = m;
            }
            return sam;
        }
    };

    /** Object 方法名集合（避免 try-catch 异常开销） */
    private static final java.util.Set<String> OBJECT_METHOD_NAMES = new java.util.HashSet<>();
    static {
        for (Method m : Object.class.getMethods()) {
            OBJECT_METHOD_NAMES.add(m.getName() + ":" + m.getParameterCount());
        }
    }

    /**
     * 检查类是否为函数式接口（只有一个抽象方法的接口）。
     */
    public static boolean isFunctionalInterface(Class<?> clazz) {
        return findSamMethod(clazz) != null;
    }

    /**
     * 检查对象是否为可适配的 Nova lambda（FunctionN 或 NovaCallable）。
     */
    public static boolean isAdaptable(Object obj) {
        return obj instanceof NovaCallable
                || obj instanceof Function0
                || obj instanceof Function1
                || obj instanceof Function2
                || obj instanceof Function3;
    }

    /**
     * 检查 source 是否可以赋值给 target 类型（考虑 SAM 适配）。
     */
    public static boolean isSamAssignable(Class<?> target, Object arg) {
        if (!target.isInterface()) return false;
        if (!isAdaptable(arg)) return false;
        return isFunctionalInterface(target);
    }

    /**
     * 将 Nova lambda 适配为指定的 Java 函数式接口。
     * 支持 FunctionN 和 NovaCallable 两种 lambda 类型。
     *
     * @param interfaceClass 目标函数式接口（如 Comparator.class）
     * @param lambda Nova lambda 对象（FunctionN 或 NovaCallable）
     * @return Proxy 实例，实现指定接口
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object adapt(Class<?> interfaceClass, Object lambda) {
        Method sam = findSamMethod(interfaceClass);
        if (sam == null) {
            throw new IllegalArgumentException("Not a functional interface: " + interfaceClass.getName());
        }

        return Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[] { interfaceClass },
                (proxy, method, args) -> {
                    // Object 方法直接处理
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return lambda.toString();
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return method.invoke(lambda, args);
                    }
                    // 默认方法直接调用
                    if (method.isDefault()) {
                        return java.lang.invoke.MethodHandles.privateLookupIn(interfaceClass,
                                        java.lang.invoke.MethodHandles.lookup())
                                .findSpecial(interfaceClass, method.getName(),
                                        java.lang.invoke.MethodType.methodType(method.getReturnType(), method.getParameterTypes()),
                                        interfaceClass)
                                .bindTo(proxy)
                                .invokeWithArguments(args);
                    }
                    // SAM 方法 → 调用 Nova lambda
                    Object result = invokeLambda(lambda, args);
                    Class<?> returnType = method.getReturnType();
                    if (returnType == void.class || returnType == Void.class) return null;
                    if (result instanceof NovaValue) result = ((NovaValue) result).toJavaValue();
                    return result;
                });
    }

    /**
     * 对方法参数数组进行 SAM 适配——将 FunctionN/NovaCallable 参数转换为目标函数式接口。
     * 修改并返回原数组。
     */
    public static Object[] adaptArgs(Method method, Object[] args) {
        if (args == null || args.length == 0) return args;
        // 快速退出：参数中无 lambda 则跳过
        boolean hasLambda = false;
        for (Object arg : args) {
            if (arg != null && isAdaptable(arg)) { hasLambda = true; break; }
        }
        if (!hasLambda) return args;
        Class<?>[] paramTypes = method.getParameterTypes();
        for (int i = 0; i < args.length && i < paramTypes.length; i++) {
            if (args[i] != null && isSamAssignable(paramTypes[i], args[i])) {
                args[i] = adapt(paramTypes[i], args[i]);
            }
        }
        return args;
    }

    // ========== TYPE_CAST 辅助（编译路径） ==========

    /**
     * 编译模式 TYPE_CAST 辅助（强制转换 as）：
     * 如果值已是目标类型实例则返回；可 SAM 适配则创建代理；否则抛 ClassCastException。
     */
    public static Object castOrAdapt(Object value, Class<?> targetType) {
        if (value == null) throw new ClassCastException("Cannot cast null to " + targetType.getName());
        if (targetType.isInstance(value)) return value;
        if (isAdaptable(value) && targetType.isInterface() && isFunctionalInterface(targetType)) {
            return adapt(targetType, value);
        }
        throw new ClassCastException("Cannot cast " + value.getClass().getName() + " to " + targetType.getName());
    }

    /**
     * 编译模式 TYPE_CAST 辅助（安全转换 as?）：
     * 如果值已是目标类型实例则返回；可 SAM 适配则创建代理；否则返回 null。
     */
    public static Object safeCastOrAdapt(Object value, Class<?> targetType) {
        if (value == null) return null;
        if (targetType.isInstance(value)) return value;
        if (isAdaptable(value) && targetType.isInterface() && isFunctionalInterface(targetType)) {
            try {
                return adapt(targetType, value);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // ========== MethodHandle SAM 过滤 ==========

    /**
     * 单参数 SAM 适配：如果 arg 是 FunctionN 且 targetType 是函数式接口，创建代理；
     * 否则原样返回。设计为 MethodHandles.filterArguments 的过滤器。
     *
     * @param targetType 方法参数的声明类型（绑定到 MethodHandle 过滤器）
     * @param arg 实际传入的参数值
     */
    public static Object adaptSingleArg(Class<?> targetType, Object arg) {
        if (arg != null && !targetType.isInstance(arg)
                && isAdaptable(arg) && isFunctionalInterface(targetType)) {
            return adapt(targetType, arg);
        }
        return arg;
    }

    // ========== 内部实现 ==========

    private static Method findSamMethod(Class<?> clazz) {
        return samCacheCV.get(clazz);
    }

    /** 获取接口的 SAM 方法（公共方法，供 MethodHandleCache 委托使用） */
    public static Method getSamMethod(Class<?> clazz) {
        return findSamMethod(clazz);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object invokeLambda(Object lambda, Object[] args) {
        if (args == null) args = new Object[0];
        // NovaCallable（解释器路径的 lambda）
        if (lambda instanceof NovaCallable) {
            java.util.List<NovaValue> novaArgs = new java.util.ArrayList<>();
            for (Object arg : args) novaArgs.add(AbstractNovaValue.fromJava(arg));
            NovaValue result = ((NovaCallable) lambda).call(null, novaArgs);
            return result != null ? result.toJavaValue() : null;
        }
        // FunctionN（编译路径的 lambda）
        switch (args.length) {
            case 0:
                if (lambda instanceof Function0) return ((Function0) lambda).invoke();
                break;
            case 1:
                if (lambda instanceof Function1) return ((Function1) lambda).invoke(args[0]);
                break;
            case 2:
                if (lambda instanceof Function2) return ((Function2) lambda).invoke(args[0], args[1]);
                break;
            case 3:
                if (lambda instanceof Function3) return ((Function3) lambda).invoke(args[0], args[1], args[2]);
                break;
        }
        // MethodHandle 回退（统一缓存，避免 Method.invoke 的 JIT 内联障碍）
        java.lang.invoke.MethodHandle mh = com.novalang.runtime.stdlib.LambdaUtils.getInvokeHandle(lambda, args.length);
        if (mh != null) {
            try {
                Object[] full = new Object[args.length + 1];
                full[0] = lambda;
                System.arraycopy(args, 0, full, 1, args.length);
                return mh.invokeWithArguments(full);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new RuntimeException("SAM lambda invoke failed", t);
            }
        }
        throw new RuntimeException("Cannot invoke lambda with " + args.length + " args: " + lambda.getClass().getName());
    }
}
