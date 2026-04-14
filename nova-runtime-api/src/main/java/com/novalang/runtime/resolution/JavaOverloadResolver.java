package com.novalang.runtime.resolution;

import com.novalang.runtime.NovaCallable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared Java overload-selection rules used by both compile-time semantic
 * analysis and runtime reflective dispatch.
 */
public final class JavaOverloadResolver {

    private JavaOverloadResolver() {}

    public static Method selectBestMethod(List<Method> candidates, boolean isStatic, Class<?>[] argTypes) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<Method> matches = new ArrayList<Method>();

        for (Method method : candidates) {
            if (isStatic != Modifier.isStatic(method.getModifiers())) continue;
            if (!method.isVarArgs() && isCompatible(method.getParameterTypes(), argTypes)) {
                matches.add(method);
            }
        }
        if (matches.isEmpty()) {
            for (Method method : candidates) {
                if (isStatic != Modifier.isStatic(method.getModifiers())) continue;
                if (method.isVarArgs() && isVarArgsCompatible(method.getParameterTypes(), argTypes)) {
                    matches.add(method);
                }
            }
        }
        if (matches.isEmpty()) {
            for (Method method : candidates) {
                if (isStatic != Modifier.isStatic(method.getModifiers())) continue;
                if (!method.isVarArgs() && isCompatibleWithNarrowing(method.getParameterTypes(), argTypes)) {
                    matches.add(method);
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);
        return selectMostSpecific(matches);
    }

    public static Constructor<?> selectBestConstructor(List<Constructor<?>> candidates, Class<?>[] argTypes) {
        if (candidates == null || candidates.isEmpty()) return null;
        List<Constructor<?>> matches = new ArrayList<Constructor<?>>();

        for (Constructor<?> ctor : candidates) {
            if (!ctor.isVarArgs() && isCompatible(ctor.getParameterTypes(), argTypes)) {
                matches.add(ctor);
            }
        }
        if (matches.isEmpty()) {
            for (Constructor<?> ctor : candidates) {
                if (ctor.isVarArgs() && isVarArgsCompatible(ctor.getParameterTypes(), argTypes)) {
                    matches.add(ctor);
                }
            }
        }
        if (matches.isEmpty()) {
            for (Constructor<?> ctor : candidates) {
                if (!ctor.isVarArgs() && isCompatibleWithNarrowing(ctor.getParameterTypes(), argTypes)) {
                    matches.add(ctor);
                }
            }
        }
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0);
        return selectMostSpecificCtor(matches);
    }

    public static boolean isVarArgsCompatible(Class<?>[] paramTypes, Class<?>[] argTypes) {
        int fixedCount = paramTypes.length - 1;
        if (argTypes.length < fixedCount) return false;
        for (int i = 0; i < fixedCount; i++) {
            if (!isAssignable(paramTypes[i], argTypes[i])) return false;
        }
        if (argTypes.length == paramTypes.length && isAssignable(paramTypes[fixedCount], argTypes[fixedCount])) {
            return true;
        }
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        for (int i = fixedCount; i < argTypes.length; i++) {
            if (argTypes[i] != null && !isAssignable(componentType, argTypes[i])) return false;
        }
        return true;
    }

    public static Object[] packVarArgs(Class<?>[] paramTypes, Object[] args) {
        int fixedCount = paramTypes.length - 1;
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

    private static Method selectMostSpecific(List<Method> methods) {
        Method best = methods.get(0);
        for (int i = 1; i < methods.size(); i++) {
            if (isMoreSpecific(methods.get(i).getParameterTypes(), methods.get(i).isVarArgs(),
                    best.getParameterTypes(), best.isVarArgs())) {
                best = methods.get(i);
            }
        }
        return best;
    }

    private static Constructor<?> selectMostSpecificCtor(List<Constructor<?>> ctors) {
        Constructor<?> best = ctors.get(0);
        for (int i = 1; i < ctors.size(); i++) {
            if (isMoreSpecific(ctors.get(i).getParameterTypes(), ctors.get(i).isVarArgs(),
                    best.getParameterTypes(), best.isVarArgs())) {
                best = ctors.get(i);
            }
        }
        return best;
    }

    private static boolean isMoreSpecific(Class<?>[] aParams, boolean aVarArgs,
                                          Class<?>[] bParams, boolean bVarArgs) {
        if (!aVarArgs && bVarArgs) return true;
        if (aVarArgs && !bVarArgs) return false;
        int len = Math.min(aParams.length, bParams.length);
        for (int i = 0; i < len; i++) {
            if (isAssignable(bParams[i], aParams[i]) && !aParams[i].equals(bParams[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCompatible(Class<?>[] paramTypes, Class<?>[] argTypes) {
        if (paramTypes.length != argTypes.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (!isAssignable(paramTypes[i], argTypes[i])) return false;
        }
        return true;
    }

    private static boolean isCompatibleWithNarrowing(Class<?>[] paramTypes, Class<?>[] argTypes) {
        if (paramTypes.length != argTypes.length) return false;
        for (int i = 0; i < paramTypes.length; i++) {
            if (!isAssignableWithNarrowing(paramTypes[i], argTypes[i])) return false;
        }
        return true;
    }

    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (source == null) {
            return !target.isPrimitive();
        }
        if (target.isAssignableFrom(source)) return true;
        if (target == Object.class) return true;
        if (target == int.class || target == Integer.class) {
            return source == Integer.class || source == int.class
                    || source == Short.class || source == short.class
                    || source == Byte.class || source == byte.class;
        }
        if (target == long.class || target == Long.class) {
            return source == Long.class || source == long.class
                    || source == int.class || source == Integer.class
                    || source == Short.class || source == short.class
                    || source == Byte.class || source == byte.class;
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
                    || source == long.class || source == Long.class
                    || source == double.class || source == Double.class;
        }
        if (target == boolean.class) return source == Boolean.class;
        if (target == char.class) return source == Character.class;
        if (target == byte.class) return source == Byte.class;
        if (target == short.class) return source == Short.class;
        if (target == Integer.class) return source == int.class;
        if (target == Boolean.class) return source == boolean.class;
        if (target == Character.class) return source == char.class;
        if (source != null && NovaCallable.class.isAssignableFrom(source) && isFunctionalInterface(target)) {
            return true;
        }
        if (target.isArray() && source != null && java.util.Collection.class.isAssignableFrom(source)) {
            return true;
        }
        return false;
    }

    private static boolean isAssignableWithNarrowing(Class<?> target, Class<?> source) {
        if (isAssignable(target, source)) return true;
        if (target == int.class || target == Integer.class) {
            return source == Long.class || source == long.class
                    || source == Double.class || source == double.class
                    || source == Float.class || source == float.class;
        }
        if (target == float.class || target == Float.class) {
            return source == Double.class || source == double.class;
        }
        if (target == short.class || target == Short.class
                || target == byte.class || target == Byte.class) {
            return source == Long.class || source == long.class
                    || source == Integer.class || source == int.class;
        }
        return false;
    }

    private static boolean isFunctionalInterface(Class<?> clazz) {
        if (clazz == null || !clazz.isInterface()) return false;
        int abstractCount = 0;
        for (Method method : clazz.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            if (method.isDefault()) continue;
            if (isObjectMethod(method)) continue;
            if (!Modifier.isAbstract(method.getModifiers())) continue;
            abstractCount++;
            if (abstractCount > 1) return false;
        }
        return abstractCount == 1;
    }

    private static boolean isObjectMethod(Method method) {
        String name = method.getName();
        Class<?>[] params = method.getParameterTypes();
        if (params.length == 0 && ("toString".equals(name) || "hashCode".equals(name))) return true;
        return params.length == 1 && "equals".equals(name) && params[0] == Object.class;
    }
}
