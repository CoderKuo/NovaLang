package nova.runtime.interpreter;
import nova.runtime.*;
import nova.runtime.types.Environment;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * 扫描带有 {@link NovaFunc} 注解的方法并批量注册到 Environment 或 NovaLibrary。
 */
public final class NovaRegistry {

    private NovaRegistry() {}

    /**
     * 扫描 clazz 中所有带 @NovaFunc 的 public static 方法，注册到 env。
     */
    public static void registerAll(Environment env, Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            NovaFunc ann = method.getAnnotation(NovaFunc.class);
            if (ann == null) continue;
            validatePublicStatic(method, clazz);
            NovaNativeFunction nf = buildNativeFunction(method, ann.value(), clazz);
            env.defineVal(ann.value(), nf);
            for (String alias : ann.aliases()) {
                env.defineVal(alias, nf);
            }
        }
    }

    /**
     * 扫描 clazz 中所有带 @NovaFunc 的 public static 方法，注册到 NovaLibrary。
     */
    public static void registerAll(NovaLibrary library, Class<?> clazz) {
        for (Method method : clazz.getDeclaredMethods()) {
            NovaFunc ann = method.getAnnotation(NovaFunc.class);
            if (ann == null) continue;
            validatePublicStatic(method, clazz);
            NovaNativeFunction nf = buildNativeFunction(method, ann.value(), clazz);
            library.putMember(ann.value(), nf);
            for (String alias : ann.aliases()) {
                library.putMember(alias, nf);
            }
        }
    }

    /**
     * 扫描 clazz 中所有带 @NovaExt 的 public static 方法，通过 registrar 注册为扩展方法。
     * 第一个非 Interpreter 参数约定为 receiver，类型必须与 targetType 兼容。
     */
    public static void registerExtensions(Class<?> targetType, Class<?> clazz,
                                           BiConsumer<String, NovaNativeFunction> registrar) {
        for (Method method : clazz.getDeclaredMethods()) {
            NovaExt ann = method.getAnnotation(NovaExt.class);
            if (ann == null) continue;
            validatePublicStatic(method, clazz);
            validateExtensionReceiver(method, targetType, clazz);
            NovaNativeFunction nf = buildNativeFunction(method, ann.value(), clazz);
            registrar.accept(ann.value(), nf);
            for (String alias : ann.aliases()) {
                registrar.accept(alias, nf);
            }
        }
    }

    private static void validateExtensionReceiver(Method method, Class<?> targetType, Class<?> clazz) {
        Class<?>[] params = method.getParameterTypes();
        int receiverIdx = (params.length > 0 && params[0] == Interpreter.class) ? 1 : 0;
        if (receiverIdx >= params.length) {
            throw new IllegalArgumentException(
                    "@NovaExt method must have at least one parameter (receiver): "
                            + clazz.getName() + "." + method.getName());
        }
        Class<?> receiverParam = toBoxed(params[receiverIdx]);
        Class<?> boxedTarget = toBoxed(targetType);
        if (!receiverParam.isAssignableFrom(boxedTarget)) {
            throw new IllegalArgumentException(
                    "@NovaExt method receiver parameter type " + params[receiverIdx].getName()
                            + " is not compatible with target type " + targetType.getName()
                            + ": " + clazz.getName() + "." + method.getName());
        }
    }

    private static Class<?> toBoxed(Class<?> type) {
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == boolean.class) return Boolean.class;
        if (type == char.class) return Character.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        return type;
    }

    private static void validatePublicStatic(Method method, Class<?> clazz) {
        if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
            throw new IllegalArgumentException(
                    "@NovaFunc method must be public static: " + clazz.getName() + "." + method.getName());
        }
    }

    private static NovaNativeFunction buildNativeFunction(Method method, String funcName, Class<?> clazz) {
        Class<?>[] paramTypes = method.getParameterTypes();

        boolean isVararg = isVarargSignature(method, paramTypes);
        boolean needsInterpreter = paramTypes.length > 0 && paramTypes[0] == Interpreter.class;

        int arity;
        if (isVararg) {
            arity = -1;
        } else {
            arity = paramTypes.length;
            if (needsInterpreter) arity--;
        }

        final int finalArity = arity;
        final boolean finalNeedsInterp = needsInterpreter;
        final boolean finalIsVararg = isVararg;

        method.setAccessible(true);
        final MethodHandle mh;
        try {
            mh = MethodHandles.lookup().unreflect(method);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException("Cannot unreflect @NovaFunc method: " + clazz.getName() + "." + funcName, e);
        }
        final Class<?> returnType = method.getReturnType();

        return new NovaNativeFunction(funcName, finalArity, (interpreter, args) -> {
            try {
                Object[] javaArgs = convertArgs(method, paramTypes, finalNeedsInterp, finalIsVararg, (Interpreter) interpreter, args);
                Object result = mh.invokeWithArguments(javaArgs);
                return toNovaValue(result, returnType);
            } catch (NovaRuntimeException e) {
                throw e;
            } catch (Throwable e) {
                if (e.getCause() instanceof NovaRuntimeException) throw (NovaRuntimeException) e.getCause();
                throw new NovaRuntimeException("Error in native function '" + funcName + "': " + e.getMessage());
            }
        });
    }

    /**
     * 判断方法是否为可变参数签名：唯一（非 Interpreter）参数为 List&lt;NovaValue&gt;
     */
    private static boolean isVarargSignature(Method method, Class<?>[] paramTypes) {
        int start = (paramTypes.length > 0 && paramTypes[0] == Interpreter.class) ? 1 : 0;
        if (paramTypes.length - start != 1) return false;
        if (paramTypes[start] != List.class) return false;

        // 检查泛型参数是否为 NovaValue
        Type[] genericTypes = method.getGenericParameterTypes();
        Type gt = genericTypes[start];
        if (gt instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) gt).getActualTypeArguments();
            return typeArgs.length == 1 && typeArgs[0] == NovaValue.class;
        }
        return false;
    }

    /**
     * 将 NovaValue 参数列表转换为 Java 方法所需的参数数组。
     */
    private static Object[] convertArgs(Method method, Class<?>[] paramTypes, boolean needsInterp,
                                        boolean isVararg, Interpreter interpreter, List<NovaValue> args) {
        Object[] javaArgs = new Object[paramTypes.length];
        int argIdx = 0; // Nova 参数索引
        for (int i = 0; i < paramTypes.length; i++) {
            if (paramTypes[i] == Interpreter.class) {
                javaArgs[i] = interpreter;
            } else if (isVararg && paramTypes[i] == List.class) {
                javaArgs[i] = args;
            } else {
                javaArgs[i] = toJavaValue(args.get(argIdx), paramTypes[i]);
                argIdx++;
            }
        }
        return javaArgs;
    }

    /**
     * NovaValue → Java 原生类型
     */
    private static Object toJavaValue(NovaValue value, Class<?> targetType) {
        if (targetType == NovaValue.class || targetType.isAssignableFrom(value.getClass())) {
            return value;
        }
        if (targetType == int.class || targetType == Integer.class) {
            return value.asInt();
        }
        if (targetType == long.class || targetType == Long.class) {
            return value.asLong();
        }
        if (targetType == double.class || targetType == Double.class) {
            return value.asDouble();
        }
        if (targetType == float.class || targetType == Float.class) {
            return (float) value.asDouble();
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return value.isTruthy();
        }
        if (targetType == String.class) {
            return value.asString();
        }
        throw new NovaRuntimeException("Unsupported parameter type: " + targetType.getName());
    }

    /**
     * Java 返回值 → NovaValue
     */
    private static NovaValue toNovaValue(Object result, Class<?> returnType) {
        if (returnType == void.class || returnType == Void.class) {
            return NovaNull.UNIT;
        }
        if (result == null) {
            return NovaNull.NULL;
        }
        if (result instanceof NovaValue) {
            return (NovaValue) result;
        }
        if (result instanceof Integer) {
            return NovaInt.of((int) result);
        }
        if (result instanceof Long) {
            return NovaLong.of((long) result);
        }
        if (result instanceof Double) {
            return NovaDouble.of((double) result);
        }
        if (result instanceof Float) {
            return NovaFloat.of((float) result);
        }
        if (result instanceof Boolean) {
            return NovaBoolean.of((boolean) result);
        }
        if (result instanceof String) {
            return NovaString.of((String) result);
        }
        throw new NovaRuntimeException("Unsupported return type: " + result.getClass().getName());
    }
}
