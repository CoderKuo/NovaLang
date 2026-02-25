package nova.runtime.interpreter;

import nova.runtime.*;

import java.util.*;

/**
 * 类型解析器：负责 Java 类查找、类型匹配和解析缓存。
 *
 * <p>从 Interpreter 提取，集中管理类型解析逻辑和相关缓存。</p>
 */
class TypeResolver {

    /** Java 类解析缓存：类型名 → Class 对象（避免重复 Class.forName） */
    final Map<String, Class<?>> resolvedClassCache = new HashMap<>();

    /** Java 类解析负缓存：Class.forName 失败过的全限定名 */
    final Set<String> classNotFoundCache = new HashSet<>();

    private final Interpreter interp;

    TypeResolver(Interpreter interp) {
        this.interp = interp;
    }

    /** 子解释器构造器：共享父级缓存 */
    TypeResolver(Interpreter interp, TypeResolver parent) {
        this.interp = interp;
        this.resolvedClassCache.putAll(parent.resolvedClassCache);
        this.classNotFoundCache.addAll(parent.classNotFoundCache);
    }

    /**
     * 解析类型名为 Java Class 对象。
     * 支持常用接口简写（Runnable、Consumer 等）和包前缀自动探测。
     */
    Class<?> resolveClass(String typeName) {
        // 缓存查找
        Class<?> cached = resolvedClassCache.get(typeName);
        if (cached != null) return cached;

        // 负缓存检查：已确认不存在的类型直接返回
        if (classNotFoundCache.contains(typeName)) return null;

        // 常用 Java 接口映射
        Class<?> result;
        switch (typeName) {
            case "Runnable":    result = Runnable.class; break;
            case "Callable":    result = java.util.concurrent.Callable.class; break;
            case "Comparator":  result = java.util.Comparator.class; break;
            case "Consumer":    result = java.util.function.Consumer.class; break;
            case "Supplier":    result = java.util.function.Supplier.class; break;
            case "Function":    result = java.util.function.Function.class; break;
            case "Predicate":   result = java.util.function.Predicate.class; break;
            case "BiFunction":  result = java.util.function.BiFunction.class; break;
            case "BiConsumer":  result = java.util.function.BiConsumer.class; break;
            default:            result = null;
        }
        if (result != null) {
            resolvedClassCache.put(typeName, result);
            return result;
        }

        // 尝试完整类名 + java.lang + java.util + java.util.function
        String[] prefixes = { "", "java.lang.", "java.util.", "java.util.function." };
        for (String prefix : prefixes) {
            String fullName = prefix + typeName;
            if (classNotFoundCache.contains(fullName)) continue;
            try {
                result = Class.forName(fullName);
                resolvedClassCache.put(typeName, result);
                return result;
            } catch (ClassNotFoundException ignored) {
                // 将失败的全名加入负缓存
                classNotFoundCache.add(fullName);
            }
        }
        // 所有前缀都失败，标记为不存在
        classNotFoundCache.add(typeName);
        return null;
    }

    /**
     * 解析全限定 Java 类名，带正/负缓存。
     */
    Class<?> resolveJavaClass(String fullName) {
        if (classNotFoundCache.contains(fullName)) return null;
        Class<?> cached = resolvedClassCache.get(fullName);
        if (cached != null) return cached;
        try {
            Class<?> clazz = Class.forName(fullName);
            resolvedClassCache.put(fullName, clazz);
            return clazz;
        } catch (ClassNotFoundException e) {
            classNotFoundCache.add(fullName);
            return null;
        }
    }

    /**
     * 检查值是否匹配指定类型名。
     */
    boolean isValueOfType(NovaValue value, String typeName) {
        return TypeOps.isInstanceOf(value, typeName, this::resolveClass, this::resolveTypeAsClass);
    }

    /**
     * 解析限定类型名（如 "ApiResult.Success"）为 NovaClass。
     * 通过当前环境逐级查找嵌套静态类。
     */
    private NovaClass resolveTypeAsClass(String qualifiedName) {
        String[] parts = qualifiedName.split("\\.");
        NovaValue current = interp.environment.tryGet(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof NovaClass) {
                current = ((NovaClass) current).getStaticField(parts[i]);
            } else {
                return null;
            }
        }
        return current instanceof NovaClass ? (NovaClass) current : null;
    }
}
