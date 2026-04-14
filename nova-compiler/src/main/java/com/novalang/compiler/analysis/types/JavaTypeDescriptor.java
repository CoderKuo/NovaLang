package com.novalang.compiler.analysis.types;

import com.novalang.runtime.resolution.JavaOverloadResolver;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Cached compile-time description of a Java type.
 * This is compiler-only metadata and never participates in runtime hot paths.
 */
public final class JavaTypeDescriptor {

    public enum Kind {
        CLASS,
        INTERFACE,
        ENUM,
        ANNOTATION
    }

    private final String simpleName;
    private final String qualifiedName;
    private final Kind kind;
    private final String superClassQualifiedName;
    private final List<String> interfaceQualifiedNames;
    private final int typeParameterCount;
    private final boolean functionalInterface;
    private final Method samMethod;

    public static final class JavaExecutableDescriptor {
        private final List<NovaType> paramTypes;
        private final NovaType returnType;
        private final boolean varArgs;

        JavaExecutableDescriptor(List<NovaType> paramTypes, NovaType returnType, boolean varArgs) {
            this.paramTypes = paramTypes;
            this.returnType = returnType;
            this.varArgs = varArgs;
        }

        public List<NovaType> getParamTypes() {
            return paramTypes;
        }

        public NovaType getReturnType() {
            return returnType;
        }

        public boolean isVarArgs() {
            return varArgs;
        }
    }

    JavaTypeDescriptor(Class<?> javaClass, Method samMethod) {
        this.simpleName = javaClass.getSimpleName();
        this.qualifiedName = javaClass.getName();
        this.kind = determineKind(javaClass);
        Class<?> superClass = javaClass.getSuperclass();
        this.superClassQualifiedName = superClass != null ? superClass.getName() : null;
        List<String> interfaceNames = new ArrayList<String>();
        for (Class<?> iface : javaClass.getInterfaces()) {
            interfaceNames.add(iface.getName());
        }
        this.interfaceQualifiedNames = Collections.unmodifiableList(interfaceNames);
        this.typeParameterCount = javaClass.getTypeParameters().length;
        this.functionalInterface = samMethod != null;
        this.samMethod = samMethod;
    }

    private static Kind determineKind(Class<?> javaClass) {
        if (javaClass.isAnnotation()) return Kind.ANNOTATION;
        if (javaClass.isEnum()) return Kind.ENUM;
        if (javaClass.isInterface()) return Kind.INTERFACE;
        return Kind.CLASS;
    }

    public String getSimpleName() {
        return simpleName;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public Kind getKind() {
        return kind;
    }

    public String getSuperClassQualifiedName() {
        return superClassQualifiedName;
    }

    public List<String> getInterfaceQualifiedNames() {
        return interfaceQualifiedNames;
    }

    public int getTypeParameterCount() {
        return typeParameterCount;
    }

    public boolean isFunctionalInterface() {
        return functionalInterface;
    }

    public Method getSamMethod() {
        return samMethod;
    }

    public boolean isAssignableFrom(JavaTypeDescriptor other) {
        if (other == null) return false;
        try {
            Class<?> target = Class.forName(qualifiedName);
            Class<?> source = Class.forName(other.qualifiedName);
            return target.isAssignableFrom(source);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public FunctionNovaType toSamFunctionType(boolean nullable) {
        if (samMethod == null) return null;
        List<NovaType> paramTypes = new ArrayList<NovaType>();
        for (Class<?> paramType : samMethod.getParameterTypes()) {
            paramTypes.add(JavaTypeOracle.get().toNovaType(paramType, false));
        }
        NovaType returnType = JavaTypeOracle.get().toNovaType(samMethod.getReturnType(), false);
        return new FunctionNovaType(null, paramTypes, returnType, nullable);
    }

    public JavaExecutableDescriptor resolveMethod(String methodName, List<NovaType> argTypes, boolean staticOnly) {
        Class<?> javaClass = loadJavaClass();
        if (javaClass == null) return null;
        List<Method> candidates = new ArrayList<Method>();
        for (Method method : javaClass.getMethods()) {
            if (methodName.equals(method.getName())) {
                candidates.add(method);
            }
        }
        Method bestMethod = JavaOverloadResolver.selectBestMethod(
                candidates, staticOnly, JavaTypeOracle.get().toJavaArgumentTypes(argTypes));
        return bestMethod != null ? toExecutableDescriptor(bestMethod) : null;
    }

    public JavaExecutableDescriptor resolveConstructor(List<NovaType> argTypes) {
        Class<?> javaClass = loadJavaClass();
        if (javaClass == null) return null;
        Constructor<?> bestCtor = JavaOverloadResolver.selectBestConstructor(
                Arrays.asList(javaClass.getConstructors()),
                JavaTypeOracle.get().toJavaArgumentTypes(argTypes));
        return bestCtor != null ? toExecutableDescriptor(bestCtor) : null;
    }

    public List<JavaExecutableDescriptor> methodOverloads(String methodName, boolean staticOnly) {
        Class<?> javaClass = loadJavaClass();
        if (javaClass == null) return Collections.emptyList();
        List<JavaExecutableDescriptor> overloads = new ArrayList<JavaExecutableDescriptor>();
        for (Method method : javaClass.getMethods()) {
            if (!methodName.equals(method.getName())) continue;
            if (Modifier.isStatic(method.getModifiers()) != staticOnly) continue;
            overloads.add(toExecutableDescriptor(method));
        }
        return overloads;
    }

    public List<JavaExecutableDescriptor> constructorOverloads() {
        Class<?> javaClass = loadJavaClass();
        if (javaClass == null) return Collections.emptyList();
        List<JavaExecutableDescriptor> overloads = new ArrayList<JavaExecutableDescriptor>();
        for (Constructor<?> ctor : javaClass.getConstructors()) {
            overloads.add(toExecutableDescriptor(ctor));
        }
        return overloads;
    }

    private JavaExecutableDescriptor toExecutableDescriptor(Method method) {
        List<NovaType> paramTypes = new ArrayList<NovaType>();
        for (Class<?> paramType : method.getParameterTypes()) {
            paramTypes.add(JavaTypeOracle.get().toNovaType(paramType, false));
        }
        NovaType returnType = JavaTypeOracle.get().toNovaType(method.getReturnType(), false);
        return new JavaExecutableDescriptor(paramTypes, returnType, method.isVarArgs());
    }

    private JavaExecutableDescriptor toExecutableDescriptor(Constructor<?> constructor) {
        List<NovaType> paramTypes = new ArrayList<NovaType>();
        for (Class<?> paramType : constructor.getParameterTypes()) {
            paramTypes.add(JavaTypeOracle.get().toNovaType(paramType, false));
        }
        NovaType returnType = new JavaClassNovaType(this, false);
        return new JavaExecutableDescriptor(paramTypes, returnType, constructor.isVarArgs());
    }

    private Class<?> loadJavaClass() {
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

    static Method findSamMethod(Class<?> javaClass) {
        if (!javaClass.isInterface()) return null;
        Method candidate = null;
        for (Method method : javaClass.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.isDefault()) continue;
            if (method.getDeclaringClass() == Object.class) continue;
            if (!Modifier.isAbstract(method.getModifiers())) continue;
            if (candidate != null) return null;
            candidate = method;
        }
        return candidate;
    }
}
