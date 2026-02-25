package nova.runtime.interpreter;

import nova.runtime.interpreter.cache.BoundedCache;
import nova.runtime.interpreter.cache.CaffeineCache;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MethodHandle 缓存
 *
 * <p>缓存 Java 方法的 MethodHandle，避免重复查找开销。
 * MethodHandle 比反射调用性能更好，JVM 可以对其进行优化。</p>
 *
 * <p>使用 Caffeine 缓存库，提供 Window TinyLfu 淘汰算法，
 * 自动保护热点数据，无需手动管理缓存大小。</p>
 */
public final class MethodHandleCache {

    private static final MethodHandleCache INSTANCE = new MethodHandleCache();

    /** 方法句柄缓存 */
    private final BoundedCache<MethodKey, MethodHandle> methodCache = new CaffeineCache<>(4096);

    /** 构造器句柄缓存 */
    private final BoundedCache<ConstructorKey, MethodHandle> constructorCache = new CaffeineCache<>(4096);

    /** 字段 getter 缓存 */
    private final BoundedCache<FieldKey, MethodHandle> getterCache = new CaffeineCache<>(4096);

    /** 字段 setter 缓存 */
    private final BoundedCache<FieldKey, MethodHandle> setterCache = new CaffeineCache<>(4096);

    /** 按类+方法名索引的方法列表（避免每次 getMethods() 全遍历） */
    private final BoundedCache<Class<?>, Map<String, List<Method>>> methodsByName = new CaffeineCache<>(4096);

    /** 函数式接口判定缓存 */
    private final Map<Class<?>, Boolean> functionalInterfaceCache = new ConcurrentHashMap<>();

    /** SAM 方法缓存: interface → SAM Method (使用 Optional 包装以支持 null 结果) */
    private final Map<Class<?>, java.util.Optional<Method>> samMethodCache = new ConcurrentHashMap<>();

    private final MethodHandles.Lookup lookup = MethodHandles.lookup();

    private MethodHandleCache() {}

    public static MethodHandleCache getInstance() {
        return INSTANCE;
    }

    // ============ setAccessible 安全守卫 ============

    /**
     * 当前线程是否允许调用 setAccessible(true)。
     * 由 Interpreter 根据 NovaSecurityPolicy.isSetAccessibleAllowed() 设置。
     * 默认 true（无策略时保持原有行为）。
     */
    private static final ThreadLocal<Boolean> TL_ALLOW_SET_ACCESSIBLE = ThreadLocal.withInitial(() -> Boolean.TRUE);

    /**
     * 设置当前线程的 setAccessible 策略。
     * 由 Interpreter 构造器调用。
     */
    public static void setAllowSetAccessible(boolean allow) {
        TL_ALLOW_SET_ACCESSIBLE.set(allow);
    }

    /**
     * 受策略守卫的 setAccessible 调用。
     * 策略不允许时静默跳过，后续 unreflect 若因访问权限失败会在 catch 中返回 null。
     */
    private static void trySetAccessible(java.lang.reflect.AccessibleObject ao) {
        if (TL_ALLOW_SET_ACCESSIBLE.get()) {
            ao.setAccessible(true);
        }
    }

    // ============ 方法调用 ============

    /**
     * 获取方法句柄（自动查找最匹配的方法）
     */
    public MethodHandle findMethod(Class<?> clazz, String name, Class<?>[] argTypes) {
        MethodKey key = new MethodKey(clazz, name, argTypes, false);
        return methodCache.computeIfAbsent(key, k -> lookupMethod(clazz, name, argTypes));
    }

    /**
     * 调用实例方法
     */
    public Object invokeMethod(Object target, String name, Object[] args) throws Throwable {
        Class<?> clazz = target.getClass();

        // Proxy 对象直接通过 InvocationHandler 分发，
        // 避免 MethodHandle 对 Proxy 类的兼容性问题
        if (Proxy.isProxyClass(clazz)) {
            Method method = findCompatibleMethod(clazz, name, getArgTypes(args), false);
            if (method == null) {
                throw new NovaRuntimeException("Method not found: " + clazz.getName() + "." + name);
            }
            Object[] invokeArgs = method.isVarArgs() ? packVarArgs(method, args) : args;
            InvocationHandler handler = Proxy.getInvocationHandler(target);
            return handler.invoke(target, method, invokeArgs.length == 0 ? null : invokeArgs);
        }

        Class<?>[] argTypes = getArgTypes(args);

        MethodHandle mh = findMethod(clazz, name, argTypes);
        if (mh == null) {
            throw new NovaRuntimeException("Method not found: " + clazz.getName() + "." + name);
        }

        // 构建参数数组：target + args
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = target;
        System.arraycopy(args, 0, fullArgs, 1, args.length);

        return mh.invokeWithArguments(fullArgs);
    }

    /**
     * 调用静态方法
     */
    public Object invokeStatic(Class<?> clazz, String name, Object[] args) throws Throwable {
        Class<?>[] argTypes = getArgTypes(args);

        MethodKey key = new MethodKey(clazz, name, argTypes, true);
        MethodHandle mh = methodCache.computeIfAbsent(key, k -> lookupStaticMethod(clazz, name, argTypes));

        if (mh == null) {
            throw new NovaRuntimeException("Static method not found: " + clazz.getName() + "." + name);
        }

        return mh.invokeWithArguments(args);
    }

    private MethodHandle lookupMethod(Class<?> clazz, String name, Class<?>[] argTypes) {
        try {
            Method method = findCompatibleMethod(clazz, name, argTypes, false);
            if (method != null) {
                trySetAccessible(method);
                MethodHandle mh = lookup.unreflect(method);
                if (method.isVarArgs()) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    mh = mh.asVarargsCollector(paramTypes[paramTypes.length - 1]);
                }
                return mh;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private MethodHandle lookupStaticMethod(Class<?> clazz, String name, Class<?>[] argTypes) {
        try {
            Method method = findCompatibleMethod(clazz, name, argTypes, true);
            if (method != null) {
                trySetAccessible(method);
                MethodHandle mh = lookup.unreflect(method);
                if (method.isVarArgs()) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    mh = mh.asVarargsCollector(paramTypes[paramTypes.length - 1]);
                }
                return mh;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取按方法名索引的方法列表（每个类只调用一次 getMethods()） */
    private Map<String, List<Method>> getMethodIndex(Class<?> clazz) {
        return methodsByName.computeIfAbsent(clazz, c -> {
            Map<String, List<Method>> index = new java.util.HashMap<>();
            for (Method m : c.getMethods()) {
                index.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
            }
            return index;
        });
    }

    /**
     * 查找兼容的方法（支持类型自动转换 + varargs）
     */
    private Method findCompatibleMethod(Class<?> clazz, String name, Class<?>[] argTypes, boolean isStatic) {
        List<Method> candidates = getMethodIndex(clazz).get(name);
        if (candidates == null) return null;
        List<Method> matches = new ArrayList<>();
        // 1. 精确匹配（非 varargs）
        for (Method method : candidates) {
            if (isStatic != Modifier.isStatic(method.getModifiers())) continue;
            if (!method.isVarArgs() && isCompatible(method.getParameterTypes(), argTypes)) {
                matches.add(method);
            }
        }
        // 2. Varargs 匹配
        if (matches.isEmpty()) {
            for (Method method : candidates) {
                if (isStatic != Modifier.isStatic(method.getModifiers())) continue;
                if (method.isVarArgs() && isVarArgsCompatible(method.getParameterTypes(), argTypes)) {
                    matches.add(method);
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);
        return selectMostSpecific(matches);
    }

    /**
     * 从多个兼容方法中选出最具体的（参数类型最窄的）
     */
    private Method selectMostSpecific(List<Method> methods) {
        Method best = methods.get(0);
        for (int i = 1; i < methods.size(); i++) {
            if (isMoreSpecific(methods.get(i), best)) {
                best = methods.get(i);
            }
        }
        return best;
    }

    /**
     * 判断方法 a 是否比方法 b 更具体
     */
    private boolean isMoreSpecific(Method a, Method b) {
        // 非 varargs 优先于 varargs
        if (!a.isVarArgs() && b.isVarArgs()) return true;
        if (a.isVarArgs() && !b.isVarArgs()) return false;
        // 逐参数比较：a 的参数类型是否比 b 更窄（用 isAssignable 支持基本类型宽化）
        Class<?>[] aParams = a.getParameterTypes();
        Class<?>[] bParams = b.getParameterTypes();
        int len = Math.min(aParams.length, bParams.length);
        for (int i = 0; i < len; i++) {
            if (isAssignable(bParams[i], aParams[i]) && !aParams[i].equals(bParams[i])) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查 varargs 方法的参数兼容性
     */
    private boolean isVarArgsCompatible(Class<?>[] paramTypes, Class<?>[] argTypes) {
        int fixedCount = paramTypes.length - 1;
        if (argTypes.length < fixedCount) return false;
        // 检查固定参数
        for (int i = 0; i < fixedCount; i++) {
            if (!isAssignable(paramTypes[i], argTypes[i])) return false;
        }
        // 如果实参数量恰好等于形参数量，且最后一个实参是数组类型，也兼容
        if (argTypes.length == paramTypes.length && isAssignable(paramTypes[fixedCount], argTypes[fixedCount])) {
            return true;
        }
        // 检查变参部分
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        for (int i = fixedCount; i < argTypes.length; i++) {
            if (argTypes[i] != null && !isAssignable(componentType, argTypes[i])) return false;
        }
        return true;
    }

    /**
     * 将调用参数打包为 varargs 方法所需的格式
     */
    static Object[] packVarArgs(Method method, Object[] args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        int fixedCount = paramTypes.length - 1;
        // 如果实参数量恰好等于形参数量且最后一个已经是数组，不需打包
        if (args.length == paramTypes.length && args[fixedCount] != null
                && paramTypes[fixedCount].isInstance(args[fixedCount])) {
            return args;
        }
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        Object varArray = java.lang.reflect.Array.newInstance(componentType, args.length - fixedCount);
        for (int i = fixedCount; i < args.length; i++) {
            java.lang.reflect.Array.set(varArray, i - fixedCount, args[i]);
        }
        Object[] packed = new Object[paramTypes.length];
        System.arraycopy(args, 0, packed, 0, fixedCount);
        packed[fixedCount] = varArray;
        return packed;
    }

    /**
     * 检查类是否拥有指定名称的方法（O(1) 查找）
     */
    public boolean hasMethodName(Class<?> clazz, String name) {
        return getMethodIndex(clazz).containsKey(name);
    }

    /**
     * 检查参数类型是否兼容
     */
    private boolean isCompatible(Class<?>[] paramTypes, Class<?>[] argTypes) {
        if (paramTypes.length != argTypes.length) {
            return false;
        }
        for (int i = 0; i < paramTypes.length; i++) {
            if (!isAssignable(paramTypes[i], argTypes[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查类型是否可赋值（包括基本类型装箱）
     */
    private boolean isAssignable(Class<?> target, Class<?> source) {
        // null 可以赋值给任何对象类型（必须在 isAssignableFrom 之前，避免 NPE）
        if (source == null) {
            return !target.isPrimitive();
        }
        if (target.isAssignableFrom(source)) {
            return true;
        }
        // Object 接受任何值
        if (target == Object.class) {
            return true;
        }
        // 基本类型装箱 + 数值宽化
        if (target == int.class) return source == Integer.class;
        if (target == long.class || target == Long.class) {
            return source == Long.class || source == long.class ||
                   source == int.class || source == Integer.class;
        }
        if (target == double.class || target == Double.class) {
            return source == Double.class || source == double.class ||
                   source == int.class || source == Integer.class ||
                   source == long.class || source == Long.class ||
                   source == float.class || source == Float.class;
        }
        if (target == float.class || target == Float.class) {
            return source == Float.class || source == float.class ||
                   source == int.class || source == Integer.class ||
                   source == long.class || source == Long.class;
        }
        if (target == boolean.class) return source == Boolean.class;
        if (target == char.class) return source == Character.class;
        if (target == byte.class) return source == Byte.class;
        if (target == short.class) return source == Short.class;
        // 反向：基本类型包装类接受基本类型
        if (target == Integer.class) return source == int.class;
        if (target == Boolean.class) return source == boolean.class;
        if (target == Character.class) return source == char.class;
        // SAM 转换：NovaCallable 可以适配函数式接口（Runnable, Callable, Consumer 等）
        if (source != null && NovaCallable.class.isAssignableFrom(source) && isFunctionalInterface(target)) {
            return true;
        }
        return false;
    }

    /**
     * 判断是否为函数式接口（恰好有一个抽象方法）
     */
    private boolean isFunctionalInterface(Class<?> clazz) {
        return functionalInterfaceCache.computeIfAbsent(clazz, c -> {
            if (!c.isInterface()) return false;
            int abstractCount = 0;
            for (Method m : c.getMethods()) {
                if (Modifier.isAbstract(m.getModifiers())
                        && !isObjectMethod(m)) {
                    abstractCount++;
                    if (abstractCount > 1) return false;
                }
            }
            return abstractCount == 1;
        });
    }

    /** 判断方法是否是 Object 公共方法的重新声明 */
    private static boolean isObjectMethod(Method m) {
        // Object 有 5 个公共方法: toString(), hashCode(), equals(Object), getClass(), notify/wait 等
        // 函数式接口中重新声明 toString/equals/hashCode 不计入抽象方法数
        String name = m.getName();
        Class<?>[] params = m.getParameterTypes();
        if (params.length == 0 && ("toString".equals(name) || "hashCode".equals(name))) return true;
        if (params.length == 1 && "equals".equals(name) && params[0] == Object.class) return true;
        return false;
    }

    /**
     * 查找方法的参数类型（供外部做 SAM 适配）
     */
    public Class<?>[] getMethodParamTypes(Class<?> clazz, String name, Class<?>[] argTypes, boolean isStatic) {
        Method m = findCompatibleMethod(clazz, name, argTypes, isStatic);
        return m != null ? m.getParameterTypes() : null;
    }

    /**
     * 判断是否为函数式接口（恰好有一个抽象方法），供外部使用
     */
    public boolean isSamInterface(Class<?> clazz) {
        return isFunctionalInterface(clazz);
    }

    // ============ SAM 方法查找 ============

    /**
     * 获取接口的单一抽象方法（SAM），结果缓存。
     * @return SAM 方法，若不是函数式接口则返回 null
     */
    public Method getSamMethod(Class<?> interfaceClass) {
        return samMethodCache.computeIfAbsent(interfaceClass, cls -> {
            if (!cls.isInterface()) return java.util.Optional.empty();
            Method sam = null;
            for (Method m : cls.getMethods()) {
                if (m.isDefault() || Modifier.isStatic(m.getModifiers())) continue;
                if (isObjectMethod(m)) continue;
                if (sam != null) return java.util.Optional.empty(); // 多个抽象方法
                sam = m;
            }
            return java.util.Optional.ofNullable(sam);
        }).orElse(null);
    }

    // ============ 静态字段访问 ============

    /**
     * 获取静态字段的 getter MethodHandle（带缓存）
     */
    public MethodHandle findStaticGetter(Class<?> clazz, String fieldName) {
        FieldKey key = new FieldKey(clazz, "static#" + fieldName);
        return getterCache.computeIfAbsent(key, k -> {
            try {
                java.lang.reflect.Field f = clazz.getField(fieldName);
                if (Modifier.isStatic(f.getModifiers())) {
                    trySetAccessible(f);
                    return lookup.unreflectGetter(f);
                }
            } catch (Exception e) {
                // 字段不存在或无法访问
            }
            return null;
        });
    }

    // ============ 构造器调用 ============

    /**
     * 获取构造器句柄
     */
    public MethodHandle findConstructor(Class<?> clazz, Class<?>[] argTypes) {
        ConstructorKey key = new ConstructorKey(clazz, argTypes);
        return constructorCache.computeIfAbsent(key, k -> lookupConstructor(clazz, argTypes));
    }

    /**
     * 创建实例
     */
    public Object newInstance(Class<?> clazz, Object[] args) throws Throwable {
        Class<?>[] argTypes = getArgTypes(args);
        MethodHandle mh = findConstructor(clazz, argTypes);
        if (mh == null) {
            throw new NovaRuntimeException("Constructor not found: " + clazz.getName());
        }
        return mh.invokeWithArguments(args);
    }

    private MethodHandle lookupConstructor(Class<?> clazz, Class<?>[] argTypes) {
        try {
            // 1. 精确匹配（非 varargs）
            for (java.lang.reflect.Constructor<?> ctor : clazz.getConstructors()) {
                if (!ctor.isVarArgs() && isCompatible(ctor.getParameterTypes(), argTypes)) {
                    trySetAccessible(ctor);
                    return lookup.unreflectConstructor(ctor);
                }
            }
            // 2. Varargs 构造器匹配
            for (java.lang.reflect.Constructor<?> ctor : clazz.getConstructors()) {
                if (ctor.isVarArgs() && isVarArgsCompatible(ctor.getParameterTypes(), argTypes)) {
                    trySetAccessible(ctor);
                    MethodHandle mh = lookup.unreflectConstructor(ctor);
                    Class<?>[] paramTypes = ctor.getParameterTypes();
                    return mh.asVarargsCollector(paramTypes[paramTypes.length - 1]);
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    // ============ 字段访问 ============

    /**
     * 获取字段值
     */
    public Object getField(Object target, String name) throws Throwable {
        Class<?> clazz = target.getClass();
        FieldKey key = new FieldKey(clazz, name);

        MethodHandle getter = getterCache.computeIfAbsent(key, k -> lookupGetter(clazz, name));
        if (getter == null) {
            throw new NovaRuntimeException("Field not found: " + clazz.getName() + "." + name);
        }

        return getter.invoke(target);
    }

    /**
     * 设置字段值
     */
    public void setField(Object target, String name, Object value) throws Throwable {
        Class<?> clazz = target.getClass();
        FieldKey key = new FieldKey(clazz, name);

        MethodHandle setter = setterCache.computeIfAbsent(key, k -> lookupSetter(clazz, name));
        if (setter == null) {
            throw new NovaRuntimeException("Field not found: " + clazz.getName() + "." + name);
        }

        setter.invoke(target, value);
    }

    private MethodHandle lookupGetter(Class<?> clazz, String name) {
        // 1. 直接字段
        try {
            java.lang.reflect.Field field = findField(clazz, name);
            if (field != null) {
                trySetAccessible(field);
                return lookup.unreflectGetter(field);
            }
        } catch (Exception e) { /* 继续 */ }

        // 2. JavaBean getter: getXxx()
        if (!name.isEmpty()) {
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                Method getter = clazz.getMethod("get" + cap);
                return lookup.unreflect(getter);
            } catch (NoSuchMethodException e) { /* 继续 */ }
            catch (IllegalAccessException e) { /* 继续 */ }

            // 3. JavaBean boolean getter: isXxx()
            try {
                Method isGetter = clazz.getMethod("is" + cap);
                if (isGetter.getReturnType() == boolean.class || isGetter.getReturnType() == Boolean.class) {
                    return lookup.unreflect(isGetter);
                }
            } catch (NoSuchMethodException e) { /* 继续 */ }
            catch (IllegalAccessException e) { /* 继续 */ }
        }

        return null;
    }

    private MethodHandle lookupSetter(Class<?> clazz, String name) {
        // 1. 直接字段
        try {
            java.lang.reflect.Field field = findField(clazz, name);
            if (field != null) {
                trySetAccessible(field);
                return lookup.unreflectSetter(field);
            }
        } catch (Exception e) { /* 继续 */ }

        // 2. JavaBean setter: setXxx(value)
        if (!name.isEmpty()) {
            String setterName = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
                    try { return lookup.unreflect(m); }
                    catch (IllegalAccessException e) { /* 继续 */ }
                }
            }
        }

        return null;
    }

    private java.lang.reflect.Field findField(Class<?> clazz, String name) {
        // 先查找当前类
        try {
            return clazz.getField(name);
        } catch (NoSuchFieldException e) {
            // 继续查找
        }
        // 查找声明的字段（包括私有）
        try {
            return clazz.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            // 继续向上查找
        }
        // 递归查找父类
        Class<?> superClass = clazz.getSuperclass();
        if (superClass != null) {
            return findField(superClass, name);
        }
        return null;
    }

    // ============ 工具方法 ============

    private Class<?>[] getArgTypes(Object[] args) {
        if (args == null || args.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i] != null ? args[i].getClass() : null;
        }
        return types;
    }

    /**
     * 清空缓存
     */
    public void clearCache() {
        methodCache.clear();
        constructorCache.clear();
        getterCache.clear();
        setterCache.clear();
        methodsByName.clear();
        functionalInterfaceCache.clear();
        samMethodCache.clear();
    }

    /**
     * 获取缓存统计
     */
    public String getCacheStats() {
        return String.format("MethodHandleCache:\n" +
                        "  methods: %s\n" +
                        "  constructors: %s\n" +
                        "  getters: %s\n" +
                        "  setters: %s\n" +
                        "  methodsByName: %s",
                methodCache.getStats().toString(),
                constructorCache.getStats().toString(),
                getterCache.getStats().toString(),
                setterCache.getStats().toString(),
                methodsByName.getStats().toString());
    }

    // ============ 缓存键 ============

    private static final class MethodKey {
        private final Class<?> clazz;
        private final String name;
        private final Class<?>[] argTypes;
        private final boolean isStatic;
        private final int hashCode;

        MethodKey(Class<?> clazz, String name, Class<?>[] argTypes, boolean isStatic) {
            this.clazz = clazz;
            this.name = name;
            this.argTypes = argTypes;
            this.isStatic = isStatic;
            this.hashCode = Objects.hash(clazz, name, Arrays.hashCode(argTypes), isStatic);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodKey)) return false;
            MethodKey that = (MethodKey) o;
            return clazz == that.clazz &&
                   isStatic == that.isStatic &&
                   name.equals(that.name) &&
                   Arrays.equals(argTypes, that.argTypes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class ConstructorKey {
        private final Class<?> clazz;
        private final Class<?>[] argTypes;
        private final int hashCode;

        ConstructorKey(Class<?> clazz, Class<?>[] argTypes) {
            this.clazz = clazz;
            this.argTypes = argTypes;
            this.hashCode = Objects.hash(clazz, Arrays.hashCode(argTypes));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ConstructorKey)) return false;
            ConstructorKey that = (ConstructorKey) o;
            return clazz == that.clazz && Arrays.equals(argTypes, that.argTypes);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final class FieldKey {
        private final Class<?> clazz;
        private final String name;
        private final int hashCode;

        FieldKey(Class<?> clazz, String name) {
            this.clazz = clazz;
            this.name = name;
            this.hashCode = Objects.hash(clazz, name);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof FieldKey)) return false;
            FieldKey that = (FieldKey) o;
            return clazz == that.clazz && name.equals(that.name);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }
}
