package nova.runtime;

import nova.runtime.stdlib.StdlibRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 编译模式运行时动态成员访问辅助。
 * 用于编译器对未知类型的成员访问/方法调用的通用回退。
 * <p>
 * 使用 MethodHandle/Method 缓存避免重复反射扫描。
 */
public final class NovaDynamic {

    private NovaDynamic() {}

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /** getter 缓存: class → (memberName → MethodHandle) */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>>
            getterCache = new ConcurrentHashMap<>();
    /** setter 缓存: class → (memberName → MethodHandle) */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>>
            setterCache = new ConcurrentHashMap<>();
    /** 方法缓存: class → (methodName#argCount → MethodHandle) */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>>
            methodCache = new ConcurrentHashMap<>();

    /**
     * 动态读取成员：公共字段 -> getXxx() -> isXxx() -> 同名方法()
     */
    public static Object getMember(Object target, String memberName) {
        if (target == null) {
            throw new NullPointerException("Cannot access member '" + memberName + "' on null");
        }

        // NovaMap 成员访问：查找 map 中的条目
        if (target instanceof NovaMap) {
            NovaValue val = ((NovaMap) target).get(NovaString.of(memberName));
            if (val != null) return val;
        }

        Class<?> clazz = target.getClass();
        ConcurrentHashMap<String, MethodHandle> cache =
                getterCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        MethodHandle mh = cache.get(memberName);
        if (mh == null) {
            mh = resolveGetter(clazz, memberName);
            cache.put(memberName, mh);
        }
        try {
            return mh.invoke(target);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * 动态写入成员：公共字段 -> setXxx(value)
     */
    public static void setMember(Object target, String memberName, Object value) {
        if (target == null) {
            throw new NullPointerException("Cannot set member '" + memberName + "' on null");
        }
        Class<?> clazz = target.getClass();
        ConcurrentHashMap<String, MethodHandle> cache =
                setterCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        MethodHandle mh = cache.get(memberName);
        if (mh == null) {
            mh = resolveSetter(clazz, memberName);
            cache.put(memberName, mh);
        }
        try {
            mh.invoke(target, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * 动态方法调用：按名称查找方法并调用
     */
    public static Object invokeMethod(Object target, String methodName, Object... args) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        // NovaMap 成员调用：查找 map 中的 callable 成员并调用
        if (target instanceof NovaMap) {
            NovaValue member = ((NovaMap) target).get(NovaString.of(methodName));
            if (member != null) {
                if (member.isCallable()) {
                    NovaValue[] novaArgs = new NovaValue[args.length];
                    for (int i = 0; i < args.length; i++) {
                        novaArgs[i] = args[i] instanceof NovaValue ? (NovaValue) args[i]
                                : NovaValue.fromJava(args[i]);
                    }
                    return member.dynamicInvoke(novaArgs);
                }
                return member; // 非 callable 成员直接返回
            }
        }

        Class<?> clazz = target.getClass();
        ConcurrentHashMap<String, MethodHandle> cache =
                methodCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());

        // 快速路径：含参数类型的缓存键（区分同名不同类型的重载方法）
        String cacheKey = cacheKey(methodName, args);
        MethodHandle cached = cache.get(cacheKey);
        if (cached != null) {
            try {
                Object[] fullArgs = buildArgs(target, args);
                return cached.invokeWithArguments(fullArgs);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
            }
        }

        // 缓存未命中：完整解析（精确匹配 + varargs）
        MethodHandle resolved = resolveMethod(clazz, methodName, args);
        if (resolved != null) {
            cache.put(cacheKey, resolved);
            try {
                Object[] fullArgs = buildArgs(target, args);
                return resolved.invokeWithArguments(fullArgs);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
            }
        }

        // 尝试方法别名
        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invokeMethod(target, aliased, args);
        }

        // 回退1: StdlibRegistry 全局扩展方法
        StdlibRegistry.ExtensionMethodInfo extInfo =
                StdlibRegistry.findExtensionMethod(clazz, methodName, args.length);
        if (extInfo != null) {
            Object[] fullArgs = new Object[args.length + 1];
            fullArgs[0] = target;
            System.arraycopy(args, 0, fullArgs, 1, args.length);
            return extInfo.impl.apply(fullArgs);
        }

        // 回退2: NovaScriptContext 动态 ExtensionRegistry（per-instance 用户注册）
        ExtensionRegistry extReg = NovaScriptContext.getExtensionRegistry();
        if (extReg != null) {
            Class<?>[] argTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                argTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            ExtensionRegistry.RegisteredExtension ext = extReg.lookup(clazz, methodName, argTypes);
            if (ext != null) {
                try {
                    return ext.invoke(target, args);
                } catch (RuntimeException e) { throw e; }
                catch (Exception e) { throw new RuntimeException(e.getMessage(), e); }
            }
        }

        throw new RuntimeException("No method '" + methodName + "' found on " + clazz.getSimpleName()
                + " with " + args.length + " arguments");
    }

    // ========== 固定参数数量重载（编译器直接调用，避免 varargs 数组创建） ==========

    public static Object invoke0(Object target, String methodName) {
        return invokeMethod(target, methodName);
    }

    public static Object invoke1(Object target, String methodName, Object a0) {
        return invokeMethod(target, methodName, a0);
    }

    public static Object invoke2(Object target, String methodName, Object a0, Object a1) {
        return invokeMethod(target, methodName, a0, a1);
    }

    public static Object invoke3(Object target, String methodName, Object a0, Object a1, Object a2) {
        return invokeMethod(target, methodName, a0, a1, a2);
    }

    // ========== 解析辅助 ==========

    private static MethodHandle resolveGetter(Class<?> clazz, String memberName) {
        // 1. 公共字段
        try {
            Field field = clazz.getField(memberName);
            return LOOKUP.unreflectGetter(field);
        } catch (Exception e) {
            // 继续
        }

        // 2. getXxx()
        String getterName = "get" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        try {
            Method m = clazz.getMethod(getterName);
            return LOOKUP.unreflect(m);
        } catch (Exception e) {
            // 继续
        }

        // 3. isXxx()
        String isGetterName = "is" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        try {
            Method m = clazz.getMethod(isGetterName);
            return LOOKUP.unreflect(m);
        } catch (Exception e) {
            // 继续
        }

        // 4. 同名无参方法
        try {
            Method m = clazz.getMethod(memberName);
            return LOOKUP.unreflect(m);
        } catch (Exception e) {
            // 继续
        }

        throw new RuntimeException("No member '" + memberName + "' found on " + clazz.getSimpleName());
    }

    private static MethodHandle resolveSetter(Class<?> clazz, String memberName) {
        // 1. 公共字段
        try {
            Field field = clazz.getField(memberName);
            return LOOKUP.unreflectSetter(field);
        } catch (Exception e) {
            // 继续
        }

        // 2. setXxx(value)
        String setterName = "set" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                try {
                    return LOOKUP.unreflect(m);
                } catch (Exception e) {
                    // 继续
                }
            }
        }

        throw new RuntimeException("No settable member '" + memberName + "' found on " + clazz.getSimpleName());
    }

    private static MethodHandle resolveMethod(Class<?> clazz, String methodName, Object[] args) {
        List<Method> matches = new ArrayList<>();
        // 1. 精确匹配（非 varargs）
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && isArgsCompatible(m, args)) {
                matches.add(m);
            }
        }
        // 2. Varargs 匹配（仅在无精确匹配时）
        if (matches.isEmpty()) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.isVarArgs() && isVarArgsCompatible(m, args)) {
                    matches.add(m);
                }
            }
        }
        if (matches.isEmpty()) return null;
        Method best = matches.size() == 1 ? matches.get(0) : selectMostSpecific(matches);
        try {
            best.setAccessible(true);
            MethodHandle mh = LOOKUP.unreflect(best);
            if (best.isVarArgs()) {
                Class<?>[] paramTypes = best.getParameterTypes();
                mh = mh.asVarargsCollector(paramTypes[paramTypes.length - 1]);
            }
            return mh;
        } catch (Exception e) {
            return null;
        }
    }

    private static Method selectMostSpecific(List<Method> methods) {
        Method best = methods.get(0);
        for (int i = 1; i < methods.size(); i++) {
            if (isMoreSpecific(methods.get(i), best)) {
                best = methods.get(i);
            }
        }
        return best;
    }

    private static boolean isMoreSpecific(Method a, Method b) {
        if (!a.isVarArgs() && b.isVarArgs()) return true;
        if (a.isVarArgs() && !b.isVarArgs()) return false;
        Class<?>[] aParams = a.getParameterTypes();
        Class<?>[] bParams = b.getParameterTypes();
        int len = Math.min(aParams.length, bParams.length);
        for (int i = 0; i < len; i++) {
            // 用 isAssignable 判断 a 的参数类型是否比 b 更窄（支持基本类型宽化）
            if (isAssignable(bParams[i], aParams[i]) && !aParams[i].equals(bParams[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVarArgsCompatible(Method method, Object[] args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        int fixedCount = paramTypes.length - 1;
        if (args.length < fixedCount) return false;
        // 检查固定参数
        for (int i = 0; i < fixedCount; i++) {
            if (args[i] != null && !isAssignable(paramTypes[i], args[i].getClass())) return false;
        }
        // 如果实参已经是数组且类型匹配，也兼容
        if (args.length == paramTypes.length && args[fixedCount] != null
                && paramTypes[fixedCount].isInstance(args[fixedCount])) {
            return true;
        }
        // 检查变参部分
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        for (int i = fixedCount; i < args.length; i++) {
            if (args[i] != null && !isAssignable(componentType, args[i].getClass())) return false;
        }
        return true;
    }

    private static String cacheKey(String methodName, Object[] args) {
        if (args.length == 0) return methodName + "#0";
        StringBuilder sb = new StringBuilder(methodName).append('#').append(args.length);
        for (Object arg : args) {
            sb.append(':').append(arg != null ? arg.getClass().getName() : "null");
        }
        return sb.toString();
    }

    private static Object[] buildArgs(Object target, Object[] args) {
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = target;
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        return fullArgs;
    }

    private static String resolveMethodAlias(String name) {
        switch (name) {
            case "uppercase": return "toUpperCase";
            case "lowercase": return "toLowerCase";
            case "contains": return null;
            default: return null;
        }
    }

    private static boolean isArgsCompatible(Method method, Object[] args) {
        if (method.isVarArgs()) return false;
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != args.length) return false;
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !isAssignable(paramTypes[i], args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (target.isAssignableFrom(source)) return true;
        // null 可以赋值给任何对象类型
        if (source == null) return !target.isPrimitive();
        // Object 接受任何值
        if (target == Object.class) return true;
        // 基本类型装箱 + 数值宽化
        if (target == int.class) return source == Integer.class;
        if (target == long.class || target == Long.class) {
            return source == Long.class || source == long.class
                || source == int.class || source == Integer.class;
        }
        if (target == double.class || target == Double.class) {
            return source == Double.class || source == double.class
                || source == int.class || source == Integer.class
                || source == long.class || source == Long.class
                || source == float.class || source == Float.class;
        }
        if (target == float.class || target == Float.class) {
            return source == Float.class || source == float.class
                || source == int.class || source == Integer.class
                || source == long.class || source == Long.class;
        }
        if (target == boolean.class) return source == Boolean.class;
        if (target == char.class) return source == Character.class;
        if (target == byte.class) return source == Byte.class;
        if (target == short.class) return source == Short.class;
        // 反向：基本类型包装类接受基本类型
        if (target == Integer.class) return source == int.class;
        if (target == Boolean.class) return source == boolean.class;
        if (target == Character.class) return source == char.class;
        return false;
    }
}
