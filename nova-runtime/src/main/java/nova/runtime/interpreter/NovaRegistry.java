package nova.runtime.interpreter;
import nova.runtime.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

/**
 * 扫描带有 {@link NovaFunc} 注解的方法并批量注册到 Environment。
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

            if (!Modifier.isStatic(method.getModifiers()) || !Modifier.isPublic(method.getModifiers())) {
                throw new IllegalArgumentException(
                        "@NovaFunc method must be public static: " + clazz.getName() + "." + method.getName());
            }

            String funcName = ann.value();
            Class<?>[] paramTypes = method.getParameterTypes();

            // 判断是否为可变参数：唯一参数为 List<NovaValue>
            boolean isVararg = isVarargSignature(method, paramTypes);
            // 判断第一个参数是否为 Interpreter
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

            NovaNativeFunction nf = new NovaNativeFunction(funcName, finalArity, (interpreter, args) -> {
                try {
                    Object[] javaArgs = convertArgs(method, paramTypes, finalNeedsInterp, finalIsVararg, interpreter, args);
                    Object result = mh.invokeWithArguments(javaArgs);
                    return toNovaValue(result, returnType);
                } catch (NovaRuntimeException e) {
                    throw e;
                } catch (Throwable e) {
                    if (e.getCause() instanceof NovaRuntimeException) throw (NovaRuntimeException) e.getCause();
                    throw new NovaRuntimeException("Error in native function '" + funcName + "': " + e.getMessage());
                }
            });

            env.defineVal(funcName, nf);
        }
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
