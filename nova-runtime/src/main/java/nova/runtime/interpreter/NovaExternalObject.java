package nova.runtime.interpreter;
import nova.runtime.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 包装 Java 外部对象的 NovaValue
 *
 * <p>用于将任意 Java 对象包装为 NovaValue，使用 MethodHandle 高效调用其方法。</p>
 */
public final class NovaExternalObject extends NovaValue {

    private final Object javaObject;
    private final Class<?> objectClass;
    private static final MethodHandleCache cache = MethodHandleCache.getInstance();
    /** 实例级缓存: "methodName#argCount" → 形参类型数组（避免重复反射推导） */
    private Map<String, Class<?>[]> paramTypesCache;

    public NovaExternalObject(Object javaObject) {
        this.javaObject = javaObject;
        this.objectClass = javaObject != null ? javaObject.getClass() : Object.class;
    }

    /**
     * 获取底层 Java 对象
     */
    public Object getJavaObject() {
        return javaObject;
    }

    @Override
    public String getTypeName() {
        return "Java:" + objectClass.getSimpleName();
    }

    @Override
    public Object toJavaValue() {
        return javaObject;
    }

    @Override
    public boolean isNull() {
        return javaObject == null;
    }

    @Override
    public boolean isTruthy() {
        return javaObject != null;
    }

    @Override
    public boolean isObject() {
        return true;
    }

    @Override
    public String toString() {
        return javaObject != null ? javaObject.toString() : "null";
    }

    /**
     * 获取字段值（使用 MethodHandle）
     */
    public NovaValue getField(String fieldName) {
        // 特殊处理数组的 length 属性
        if (objectClass.isArray() && "length".equals(fieldName)) {
            return NovaInt.of(java.lang.reflect.Array.getLength(javaObject));
        }

        try {
            Object value = cache.getField(javaObject, fieldName);
            return NovaValue.fromJava(value);
        } catch (Throwable e) {
            throw new NovaRuntimeException("Cannot access field '" + fieldName + "' on " + getTypeName()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * 设置字段值（使用 MethodHandle）
     */
    public void setField(String fieldName, NovaValue value) {
        try {
            cache.setField(javaObject, fieldName, value.toJavaValue());
        } catch (Throwable e) {
            throw new NovaRuntimeException("Cannot set field '" + fieldName + "' on " + getTypeName()
                    + ": " + e.getMessage(), e);
        }
    }

    /**
     * 调用方法（使用 MethodHandle）
     */
    public NovaValue invokeMethod(String methodName, List<NovaValue> args) {
        return invokeMethod(methodName, args, null);
    }

    /**
     * 调用方法（带解释器引用，支持 SAM 自动转换）
     */
    public NovaValue invokeMethod(String methodName, List<NovaValue> args, Interpreter interpreter) {
        try {
            // 准备参数
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) {
                javaArgs[i] = args.get(i).toJavaValue();
            }

            // SAM 自动转换：将 NovaCallable 参数转换为 Java 函数式接口代理
            if (interpreter != null) {
                Class<?>[] paramTypes = getCachedParamTypes(methodName, javaArgs);
                if (paramTypes != null) {
                    for (int i = 0; i < javaArgs.length && i < paramTypes.length; i++) {
                        if (args.get(i) instanceof NovaCallable && cache.isSamInterface(paramTypes[i])) {
                            javaArgs[i] = SamProxyFactory.create(paramTypes[i], (NovaCallable) args.get(i), interpreter);
                        }
                    }
                }
            }

            // 使用 MethodHandle 调用
            Object result = cache.invokeMethod(javaObject, methodName, javaArgs);
            return NovaValue.fromJava(result);
        } catch (Throwable e) {
            throw new NovaRuntimeException("Failed to invoke '" + methodName + "' on " + getTypeName()
                    + ": " + e.getMessage(), e);
        }
    }


    private static final int PARAM_CACHE_MAX = 64;

    /** 缓存方法参数类型，避免每次调用重复反射推导 */
    private Class<?>[] getCachedParamTypes(String methodName, Object[] javaArgs) {
        Class<?>[] argTypes = new Class<?>[javaArgs.length];
        for (int i = 0; i < javaArgs.length; i++) {
            argTypes[i] = javaArgs[i] != null ? javaArgs[i].getClass() : Object.class;
        }
        // key 包含方法名 + 参数类型，避免同名不同类型方法缓存冲突
        String cacheKey = buildParamCacheKey(methodName, argTypes);
        if (paramTypesCache == null) {
            paramTypesCache = new HashMap<>();
        }
        if (paramTypesCache.size() >= PARAM_CACHE_MAX) {
            // 淘汰一半条目
            java.util.Iterator<String> it = paramTypesCache.keySet().iterator();
            int target = paramTypesCache.size() / 2;
            for (int i = 0; i < target && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        Class<?>[] cached = paramTypesCache.get(cacheKey);
        if (cached != null) return cached.length == 0 ? null : cached;

        Class<?>[] paramTypes = cache.getMethodParamTypes(objectClass, methodName, argTypes, false);
        // 缓存结果（null 用空数组表示"已查过但没找到"）
        paramTypesCache.put(cacheKey, paramTypes != null ? paramTypes : new Class<?>[0]);
        return paramTypes;
    }

    /** 构建包含参数类型信息的缓存 key */
    private static String buildParamCacheKey(String methodName, Class<?>[] argTypes) {
        if (argTypes.length == 0) return methodName + "#0";
        StringBuilder sb = new StringBuilder(methodName).append('#').append(argTypes.length);
        for (Class<?> t : argTypes) {
            sb.append(':').append(t.getName());
        }
        return sb.toString();
    }

    /**
     * 检查是否存在指定名称的方法（通过 MethodHandleCache 的方法名索引 O(1) 查找）
     */
    public boolean hasMethod(String methodName) {
        return cache.hasMethodName(objectClass, methodName);
    }

    /**
     * 创建绑定方法
     */
    public NovaNativeFunction getBoundMethod(String methodName) {
        return new NovaNativeFunction(methodName, -1, (interp, args) -> {
            return invokeMethod(methodName, args, interp);
        });
    }
}
