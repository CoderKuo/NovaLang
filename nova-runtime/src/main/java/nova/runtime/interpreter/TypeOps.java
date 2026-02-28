package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.*;

import java.util.function.Function;

/**
 * 统一类型检查逻辑（供 HIR 和 MIR 引擎共用）。
 * typeName 为 Nova 类型名，调用方负责 JVM 内部名映射。
 */
final class TypeOps {
    private TypeOps() {}

    static boolean isInstanceOf(NovaValue value, String typeName,
                                Function<String, Class<?>> resolveClass,
                                Function<String, NovaClass> resolveTypeAsClass) {
        if (value == null) return false;

        // Nothing: only NovaNull matches
        if ("Nothing".equals(typeName)) return value.isNull();

        // NovaNull doesn't match any other type
        if (value instanceof NovaNull) return false;

        // Exception: 只匹配实际的异常类型值
        if ("Exception".equals(typeName) || "java.lang.Exception".equals(typeName)) {
            // NovaExternalObject 包装的 Throwable
            if (value instanceof NovaExternalObject) {
                Object javaObj = value.toJavaValue();
                return javaObj instanceof Throwable;
            }
            // NovaObject 继承链中有 Exception
            if (value instanceof NovaObject) {
                NovaObject novaObj = (NovaObject) value;
                NovaClass cls = novaObj.getNovaClass();
                while (cls != null) {
                    if ("Exception".equals(cls.getName())) return true;
                    cls = cls.getSuperclass();
                }
                // 检查 Java 超类
                if (novaObj.getNovaClass().getJavaSuperclass() != null) {
                    return Throwable.class.isAssignableFrom(novaObj.getNovaClass().getJavaSuperclass());
                }
            }
            return false;
        }

        // Result supertype
        if ("Result".equals(typeName) && value instanceof NovaResult) return true;

        // Basic types: getNovaTypeName() polymorphic match
        String novaType = value.getNovaTypeName();
        if (novaType != null && novaType.equals(typeName)) return true;

        // getTypeName() fallback
        if (value.getTypeName().equals(typeName)) return true;

        // NovaObject: inheritance chain + interfaces + Java types
        if (value instanceof NovaObject) {
            NovaObject novaObj = (NovaObject) value;
            NovaClass objClass = novaObj.getNovaClass();
            // Full hierarchy: class name + interfaces at each level
            NovaClass cls = objClass.getSuperclass(); // direct class covered by getNovaTypeName
            while (cls != null) {
                if (cls.getName().equals(typeName)) return true;
                for (NovaInterface iface : cls.getInterfaces()) {
                    if (iface.getName().equals(typeName)) return true;
                }
                cls = cls.getSuperclass();
            }
            // Direct class interfaces
            for (NovaInterface iface : objClass.getInterfaces()) {
                if (iface.getName().equals(typeName)) return true;
            }
            // Qualified name resolution (e.g., ApiResult.Success)
            if (resolveTypeAsClass != null && typeName.contains(".")) {
                NovaClass resolved = resolveTypeAsClass.apply(typeName);
                if (resolved != null) {
                    cls = objClass;
                    while (cls != null) {
                        if (cls == resolved) return true;
                        cls = cls.getSuperclass();
                    }
                }
            }
            // Java type check (delegate / superclass / interfaces)
            if (resolveClass != null) {
                Class<?> targetClass = resolveClass.apply(typeName);
                if (targetClass != null) {
                    if (novaObj.getJavaDelegate() != null) {
                        if (targetClass.isInstance(novaObj.getJavaDelegate())) return true;
                    } else {
                        if (objClass.getJavaSuperclass() != null &&
                            targetClass.isAssignableFrom(objClass.getJavaSuperclass())) return true;
                        for (Class<?> javaIface : objClass.getJavaInterfaces()) {
                            if (targetClass.isAssignableFrom(javaIface)) return true;
                        }
                    }
                }
            }
            return false;
        }

        // NovaEnumEntry
        if (value instanceof NovaEnumEntry) {
            return ((NovaEnumEntry) value).getEnumClass().getName().equals(typeName);
        }

        // NovaExternalObject: Java type check
        if (value instanceof NovaExternalObject && resolveClass != null) {
            Object javaObj = value.toJavaValue();
            if (javaObj != null) {
                Class<?> targetClass = resolveClass.apply(typeName);
                if (targetClass != null && targetClass.isInstance(javaObj)) return true;
            }
        }

        return false;
    }
}
