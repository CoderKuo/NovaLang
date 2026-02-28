package nova.runtime.interpreter;

import nova.runtime.*;
import nova.runtime.types.*;

import java.util.*;

/**
 * Java 互操作辅助类：委托对象创建、接口代理、桥接回调。
 */
final class JavaInteropHelper {

    private final Interpreter interp;

    JavaInteropHelper(Interpreter interp) {
        this.interp = interp;
    }

    Object createJavaDelegate(NovaObject instance, NovaClass novaClass,
                              List<NovaValue> superCtorArgs) {
        Class<?> javaSuperclass = novaClass.getJavaSuperclass();
        List<Class<?>> javaInterfaces = novaClass.getJavaInterfaces();

        // Case 1: 纯接口实现 + Proxy
        if (javaSuperclass == null && !javaInterfaces.isEmpty()) {
            return createJavaInterfaceProxy(instance, javaInterfaces);
        }

        // Case 2: 有 Java 超类 + ASM 子类
        if (javaSuperclass != null) {
            try {
                Set<String> novaMethodNames = new java.util.HashSet<>(novaClass.getMethods().keySet());
                novaMethodNames.addAll(novaClass.getCallableMethods().keySet());

                // 转换构造器参数
                Object[] ctorArgs = new Object[superCtorArgs.size()];
                for (int i = 0; i < superCtorArgs.size(); i++) {
                    ctorArgs[i] = superCtorArgs.get(i).toJavaValue();
                }

                // 推导构造器参数类型
                Class<?>[] ctorArgTypes = inferCtorArgTypes(javaSuperclass, ctorArgs);

                Class<?> subclass = JavaSubclassFactory.generateSubclass(
                        javaSuperclass, javaInterfaces, novaMethodNames, ctorArgTypes);

                // 实例化
                Object delegate = MethodHandleCache.getInstance().newInstance(subclass, ctorArgs);

                // 设置回调
                subclass.getField("$$callback").set(delegate, createBridgeCallback(instance));

                return delegate;
            } catch (NovaRuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new NovaRuntimeException("Failed to create Java subclass: " + e.getMessage(), e);
            }
        }

        return null;
    }

    /**
     * 推导最匹配的构造器参数类型
     */
    Class<?>[] inferCtorArgTypes(Class<?> javaSuperclass, Object[] ctorArgs) {
        // 尝试找到参数数量匹配的构造器
        for (java.lang.reflect.Constructor<?> ctor : javaSuperclass.getConstructors()) {
            if (ctor.getParameterCount() == ctorArgs.length) {
                Class<?>[] paramTypes = ctor.getParameterTypes();
                boolean match = true;
                for (int i = 0; i < ctorArgs.length; i++) {
                    if (ctorArgs[i] != null && !isAssignableTo(ctorArgs[i], paramTypes[i])) {
                        match = false;
                        break;
                    }
                }
                if (match) return paramTypes;
            }
        }
        // 回退：根据实际参数推导类型
        Class<?>[] types = new Class<?>[ctorArgs.length];
        for (int i = 0; i < ctorArgs.length; i++) {
            types[i] = ctorArgs[i] != null ? ctorArgs[i].getClass() : Object.class;
        }
        return types;
    }

    boolean isAssignableTo(Object value, Class<?> targetType) {
        if (targetType.isPrimitive()) {
            if (targetType == int.class) return value instanceof Integer;
            if (targetType == long.class) return value instanceof Long;
            if (targetType == double.class) return value instanceof Double;
            if (targetType == float.class) return value instanceof Float;
            if (targetType == boolean.class) return value instanceof Boolean;
            if (targetType == byte.class) return value instanceof Byte;
            if (targetType == char.class) return value instanceof Character;
            if (targetType == short.class) return value instanceof Short;
            return false;
        }
        return targetType.isInstance(value);
    }

    /**
     * 使用 Proxy 创建 Java 接口实现
     */
    Object createJavaInterfaceProxy(NovaObject instance, List<Class<?>> interfaces) {
        Class<?>[] ifaceArray = interfaces.toArray(new Class<?>[0]);
        return java.lang.reflect.Proxy.newProxyInstance(
                interfaces.get(0).getClassLoader(),
                ifaceArray,
                (proxy, method, args) -> {
                    // Object 方法特殊处理
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "toString": return instance.toString();
                            case "hashCode": return System.identityHashCode(proxy);
                            case "equals": return proxy == args[0];
                        }
                    }
                    // 查找 Nova 方法并调用
                    NovaBoundMethod bound = instance.getBoundMethod(method.getName());
                    if (bound != null) {
                        List<NovaValue> novaArgs = convertJavaArgsToNova(args);
                        NovaValue result = interp.executeSamCallback(bound, novaArgs);
                        return result != null ? result.toJavaValue() : null;
                    }
                    // 没有 Nova 实现，返回默认值
                    Class<?> returnType = method.getReturnType();
                    if (returnType == void.class) return null;
                    if (returnType.isPrimitive()) {
                        if (returnType == boolean.class) return false;
                        if (returnType == int.class) return 0;
                        if (returnType == long.class) return 0L;
                        if (returnType == double.class) return 0.0;
                        if (returnType == float.class) return 0.0f;
                        return 0;
                    }
                    return null;
                }
        );
    }

    /**
     * 创建桥接回调
     */
    NovaBridgeCallback createBridgeCallback(NovaObject instance) {
        return (methodName, args) -> {
            NovaBoundMethod bound = instance.getBoundMethod(methodName);
            if (bound != null) {
                List<NovaValue> novaArgs = convertJavaArgsToNova(args);
                NovaValue result = interp.executeSamCallback(bound, novaArgs);
                return result != null ? result.toJavaValue() : null;
            }
            return null;
        };
    }

    /**
     * 将 Java 参数转为 NovaValue 列表
     */
    static List<NovaValue> convertJavaArgsToNova(Object[] args) {
        List<NovaValue> novaArgs = new ArrayList<NovaValue>();
        if (args != null) {
            for (Object arg : args) {
                novaArgs.add(AbstractNovaValue.fromJava(arg));
            }
        }
        return novaArgs;
    }

    /**
     * 创建 SAM（Single Abstract Method）代理：将 NovaCallable 包装为 Java 函数式接口实例。
     */
    static Object createSamProxy(Class<?> interfaceClass, NovaCallable callable, Interpreter interp) {
        return java.lang.reflect.Proxy.newProxyInstance(
                interfaceClass.getClassLoader(),
                new Class<?>[] { interfaceClass },
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return callable.toString();
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return method.invoke(callable, args);
                    }
                    List<NovaValue> novaArgs = new ArrayList<>();
                    if (args != null) {
                        for (Object arg : args) novaArgs.add(AbstractNovaValue.fromJava(arg));
                    }
                    NovaValue result = interp.executeSamCallback(callable, novaArgs);
                    Class<?> returnType = method.getReturnType();
                    if (returnType == void.class || returnType == Void.class) return null;
                    return result != null ? result.toJavaValue() : null;
                });
    }
}
