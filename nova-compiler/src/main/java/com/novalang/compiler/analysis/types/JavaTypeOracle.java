package com.novalang.compiler.analysis.types;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Compiler-side Java type metadata oracle.
 * Uses cached class metadata lookups only during semantic analysis.
 */
public final class JavaTypeOracle {

    private static final JavaTypeOracle INSTANCE = new JavaTypeOracle();

    private final Map<String, JavaTypeDescriptor> descriptorCache = new ConcurrentHashMap<String, JavaTypeDescriptor>();
    private final Set<String> negativeCache = ConcurrentHashMap.newKeySet();

    private JavaTypeOracle() {}

    public static JavaTypeOracle get() {
        return INSTANCE;
    }

    public JavaTypeDescriptor resolve(String typeName) {
        if (typeName == null || typeName.isEmpty()) return null;
        if (descriptorCache.containsKey(typeName)) {
            return descriptorCache.get(typeName);
        }
        if (negativeCache.contains(typeName)) {
            return null;
        }

        JavaTypeDescriptor descriptor = resolveUncached(typeName);
        if (descriptor != null) {
            descriptorCache.put(typeName, descriptor);
        } else {
            negativeCache.add(typeName);
        }
        return descriptor;
    }

    private JavaTypeDescriptor resolveUncached(String typeName) {
        Class<?> javaClass = loadClass(typeName);
        if (javaClass == null && typeName.indexOf('.') < 0) {
            String[] prefixes = { "java.lang.", "java.util.", "java.util.concurrent.", "java.util.function." };
            for (String prefix : prefixes) {
                javaClass = loadClass(prefix + typeName);
                if (javaClass != null) break;
            }
        }
        if (javaClass == null) return null;
        Method samMethod = JavaTypeDescriptor.findSamMethod(javaClass);
        return new JavaTypeDescriptor(javaClass, samMethod);
    }

    private Class<?> loadClass(String qualifiedName) {
        try {
            ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
            if (contextLoader != null) {
                return Class.forName(qualifiedName, false, contextLoader);
            }
            return Class.forName(qualifiedName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public NovaType toNovaType(Class<?> javaClass, boolean nullable) {
        if (javaClass == null || javaClass == Void.TYPE) return NovaTypes.UNIT;
        if (javaClass == Integer.TYPE) return nullable ? NovaTypes.INT.withNullable(true) : NovaTypes.INT;
        if (javaClass == Long.TYPE) return nullable ? NovaTypes.LONG.withNullable(true) : NovaTypes.LONG;
        if (javaClass == Float.TYPE) return nullable ? NovaTypes.FLOAT.withNullable(true) : NovaTypes.FLOAT;
        if (javaClass == Double.TYPE) return nullable ? NovaTypes.DOUBLE.withNullable(true) : NovaTypes.DOUBLE;
        if (javaClass == Boolean.TYPE) return nullable ? NovaTypes.BOOLEAN.withNullable(true) : NovaTypes.BOOLEAN;
        if (javaClass == Character.TYPE) return nullable ? NovaTypes.CHAR.withNullable(true) : NovaTypes.CHAR;
        if (javaClass == String.class) return nullable ? NovaTypes.STRING.withNullable(true) : NovaTypes.STRING;
        if (javaClass == Object.class) return nullable ? NovaTypes.ANY.withNullable(true) : NovaTypes.ANY;

        JavaTypeDescriptor descriptor = resolve(javaClass.getName());
        if (descriptor == null) {
            return nullable ? NovaTypes.ANY.withNullable(true) : NovaTypes.ANY;
        }
        return new JavaClassNovaType(descriptor, nullable);
    }

    public Class<?>[] toJavaArgumentTypes(List<NovaType> argTypes) {
        if (argTypes == null || argTypes.isEmpty()) return new Class<?>[0];
        List<Class<?>> resolved = new ArrayList<Class<?>>(argTypes.size());
        for (NovaType argType : argTypes) {
            resolved.add(toJavaArgumentType(argType));
        }
        return resolved.toArray(new Class<?>[0]);
    }

    public Class<?> toJavaArgumentType(NovaType type) {
        if (type == null) return null;
        if (type instanceof NothingType && type.isNullable()) return null;
        if (NovaTypes.isDynamicType(type)) return Object.class;
        String typeName = type.getTypeName();
        if ("Int".equals(typeName)) return Integer.class;
        if ("Long".equals(typeName)) return Long.class;
        if ("Float".equals(typeName)) return Float.class;
        if ("Double".equals(typeName)) return Double.class;
        if ("Boolean".equals(typeName)) return Boolean.class;
        if ("Char".equals(typeName)) return Character.class;
        if ("String".equals(typeName)) return String.class;
        if ("Any".equals(typeName) || "Unit".equals(typeName)) return Object.class;
        if ("List".equals(typeName)) return java.util.List.class;
        if ("Set".equals(typeName)) return java.util.Set.class;
        if ("Map".equals(typeName)) return java.util.Map.class;
        if ("Array".equals(typeName)) return Object[].class;
        if (type instanceof JavaClassNovaType) {
            Class<?> javaClass = loadClass(((JavaClassNovaType) type).getQualifiedName());
            if (javaClass != null) return javaClass;
        }
        if (typeName != null) {
            Class<?> javaClass = loadClass(typeName);
            if (javaClass != null) return javaClass;
            JavaTypeDescriptor descriptor = resolve(typeName);
            if (descriptor != null) {
                javaClass = loadClass(descriptor.getQualifiedName());
                if (javaClass != null) return javaClass;
            }
        }
        return Object.class;
    }
}
