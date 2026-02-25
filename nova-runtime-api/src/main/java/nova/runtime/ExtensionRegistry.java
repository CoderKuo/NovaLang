package nova.runtime;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 扩展方法注册表
 *
 * <p>管理从 Java 注册的类型扩展方法。</p>
 */
public final class ExtensionRegistry {

    // 类型 -> (方法名 -> 扩展方法列表)
    private final Map<Class<?>, Map<String, List<RegisteredExtension>>> extensions =
            new ConcurrentHashMap<Class<?>, Map<String, List<RegisteredExtension>>>();

    // lookup 缓存：(receiverType, methodName) → RegisteredExtension（避免重复继承链遍历）
    private final ConcurrentHashMap<String, RegisteredExtension> lookupCache = new ConcurrentHashMap<>();
    // lookup 负缓存标记
    private static final RegisteredExtension LOOKUP_MISS = new RegisteredExtension(Object.class, "", new Class<?>[0], Object.class, null);

    /**
     * 注册扩展方法（通用版本）
     *
     * @param targetType  目标类型
     * @param methodName  方法名
     * @param paramTypes  参数类型（不包括 receiver）
     * @param returnType  返回类型
     * @param method      方法实现
     */
    public void register(Class<?> targetType, String methodName, Class<?>[] paramTypes,
                         Class<?> returnType, ExtensionMethod<?, ?> method) {
        Map<String, List<RegisteredExtension>> typeMethods =
                extensions.computeIfAbsent(targetType, k -> new ConcurrentHashMap<>());
        List<RegisteredExtension> methodList =
                typeMethods.computeIfAbsent(methodName, k -> new CopyOnWriteArrayList<>());
        methodList.add(new RegisteredExtension(targetType, methodName, paramTypes, returnType, method));
        lookupCache.clear();
    }

    /**
     * 注册无参数扩展方法
     */
    public <T, R> void register(Class<T> targetType, String methodName, Class<R> returnType,
                                 Extension0<T, R> method) {
        register(targetType, methodName, new Class<?>[0], returnType,
                new ExtensionMethod<T, R>() {
                    @Override
                    public R invoke(T receiver, Object[] args) {
                        return method.invoke(receiver);
                    }
                });
    }

    /**
     * 注册单参数扩展方法
     */
    @SuppressWarnings("unchecked")
    public <T, A1, R> void register(Class<T> targetType, String methodName,
                                     Class<A1> a1, Class<R> returnType,
                                     Extension1<T, A1, R> method) {
        register(targetType, methodName, new Class<?>[] { a1 }, returnType,
                new ExtensionMethod<T, R>() {
                    @Override
                    public R invoke(T receiver, Object[] args) {
                        return method.invoke(receiver, (A1) args[0]);
                    }
                });
    }

    /**
     * 注册双参数扩展方法
     */
    @SuppressWarnings("unchecked")
    public <T, A1, A2, R> void register(Class<T> targetType, String methodName,
                                         Class<A1> a1, Class<A2> a2, Class<R> returnType,
                                         Extension2<T, A1, A2, R> method) {
        register(targetType, methodName, new Class<?>[] { a1, a2 }, returnType,
                new ExtensionMethod<T, R>() {
                    @Override
                    public R invoke(T receiver, Object[] args) {
                        return method.invoke(receiver, (A1) args[0], (A2) args[1]);
                    }
                });
    }

    /**
     * 查找扩展方法
     *
     * @param receiverType 接收者类型
     * @param methodName   方法名
     * @param argTypes     参数类型
     * @return 匹配的扩展方法，如果不存在返回 null
     */
    public RegisteredExtension lookup(Class<?> receiverType, String methodName, Class<?>[] argTypes) {
        int argCount = argTypes != null ? argTypes.length : 0;
        String cacheKey = receiverType.getName() + "#" + methodName + "#" + argCount;
        RegisteredExtension cached = lookupCache.get(cacheKey);
        if (cached != null) {
            return cached == LOOKUP_MISS ? null : cached;
        }
        RegisteredExtension result = lookupRecursive(receiverType, methodName, argTypes);
        lookupCache.put(cacheKey, result != null ? result : LOOKUP_MISS);
        return result;
    }

    private RegisteredExtension lookupRecursive(Class<?> receiverType, String methodName, Class<?>[] argTypes) {
        // 1. 精确匹配
        RegisteredExtension result = lookupExact(receiverType, methodName, argTypes);
        if (result != null) {
            return result;
        }

        // 2. 查找父类
        Class<?> superClass = receiverType.getSuperclass();
        if (superClass != null) {
            result = lookupRecursive(superClass, methodName, argTypes);
            if (result != null) {
                return result;
            }
        }

        // 3. 查找接口
        for (Class<?> iface : receiverType.getInterfaces()) {
            result = lookupRecursive(iface, methodName, argTypes);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    private RegisteredExtension lookupExact(Class<?> targetType, String methodName, Class<?>[] argTypes) {
        Map<String, List<RegisteredExtension>> typeMethods = extensions.get(targetType);
        if (typeMethods == null) {
            return null;
        }

        List<RegisteredExtension> methods = typeMethods.get(methodName);
        if (methods == null || methods.isEmpty()) {
            return null;
        }

        // 查找参数匹配的方法
        for (RegisteredExtension ext : methods) {
            if (ext.matches(argTypes)) {
                return ext;
            }
        }

        return null;
    }

    /**
     * 检查扩展方法是否存在
     */
    public boolean contains(Class<?> receiverType, String methodName) {
        Map<String, List<RegisteredExtension>> typeMethods = extensions.get(receiverType);
        return typeMethods != null && typeMethods.containsKey(methodName);
    }

    /**
     * 移除扩展方法
     */
    public void unregister(Class<?> targetType, String methodName) {
        Map<String, List<RegisteredExtension>> typeMethods = extensions.get(targetType);
        if (typeMethods != null) {
            typeMethods.remove(methodName);
            lookupCache.clear();
        }
    }

    /**
     * 清空所有注册
     */
    public void clear() {
        extensions.clear();
        lookupCache.clear();
    }

    /**
     * 注册的扩展方法信息
     */
    public static final class RegisteredExtension {
        private final Class<?> targetType;
        private final String methodName;
        private final Class<?>[] paramTypes;
        private final Class<?> returnType;
        private final ExtensionMethod<?, ?> method;

        public RegisteredExtension(Class<?> targetType, String methodName,
                                   Class<?>[] paramTypes, Class<?> returnType,
                                   ExtensionMethod<?, ?> method) {
            this.targetType = targetType;
            this.methodName = methodName;
            this.paramTypes = paramTypes;
            this.returnType = returnType;
            this.method = method;
        }

        public Class<?> getTargetType() {
            return targetType;
        }

        public String getMethodName() {
            return methodName;
        }

        public Class<?>[] getParamTypes() {
            return paramTypes;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public ExtensionMethod<?, ?> getMethod() {
            return method;
        }

        /**
         * 检查参数类型是否匹配
         */
        public boolean matches(Class<?>[] argTypes) {
            if (argTypes == null) {
                argTypes = new Class<?>[0];
            }
            if (paramTypes.length != argTypes.length) {
                return false;
            }
            for (int i = 0; i < paramTypes.length; i++) {
                if (!paramTypes[i].isAssignableFrom(argTypes[i])) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 调用扩展方法
         */
        @SuppressWarnings("unchecked")
        public Object invoke(Object receiver, Object[] args) throws Exception {
            return ((ExtensionMethod<Object, Object>) method).invoke(receiver, args);
        }
    }
}
