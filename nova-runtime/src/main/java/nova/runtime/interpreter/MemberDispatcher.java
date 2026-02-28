package nova.runtime.interpreter;
import nova.runtime.*;

import nova.runtime.NovaMember;
import nova.runtime.NovaType;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于 {@link NovaType} / {@link NovaMember} 注解的运行时成员分发器。
 *
 * <p>扫描带注解的 NovaValue 子类，构建 MethodHandle 分发表，
 * 作为 {@code Interpreter.visitMemberExpr} 的兜底处理。</p>
 *
 * <p>LSP 补全元数据由 {@link nova.runtime.NovaTypeRegistry#scanAnnotatedClass} 负责。</p>
 */
public final class MemberDispatcher {

    /** 成员访问处理器 */
    @FunctionalInterface
    interface MemberHandler {
        NovaValue access(NovaValue target, Interpreter interp);
    }

    /** typeName → memberName → handler */
    private static final Map<String, Map<String, MemberHandler>> handlers = new HashMap<>();

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    // ============ 初始化 ============

    static {
        scan(NovaFuture.class);
        // 后续迁移其他类型：scan(NovaResult.class) 等
    }

    private MemberDispatcher() {}

    // ============ 扫描 ============

    /**
     * 扫描一个 @NovaType 类，提取 @NovaMember 方法，构建运行时分发处理器。
     */
    static void scan(Class<? extends NovaValue> clazz) {
        NovaType typeAnn = clazz.getAnnotation(NovaType.class);
        if (typeAnn == null) return;

        String typeName = typeAnn.name();

        for (Method method : clazz.getDeclaredMethods()) {
            NovaMember memberAnn = method.getAnnotation(NovaMember.class);
            if (memberAnn == null) continue;

            String memberName = method.getName();
            boolean isProperty = memberAnn.property();

            // 计算用户可见参数数量（排除 Interpreter 参数）
            int arity = 0;
            for (Class<?> p : method.getParameterTypes()) {
                if (p != Interpreter.class) arity++;
            }

            // 创建运行时分发处理器
            try {
                MethodHandle mh = LOOKUP.unreflect(method);
                boolean needsInterpreter = hasInterpreterParam(method);

                if (isProperty) {
                    registerHandler(typeName, memberName,
                            createPropertyHandler(mh, needsInterpreter, method.getReturnType()));
                } else {
                    // 收集非 Interpreter 参数的类型（用于参数转换）
                    Class<?>[] userParamTypes = method.getParameterTypes();
                    registerHandler(typeName, memberName,
                            createMethodHandler(mh, needsInterpreter, memberName, arity,
                                    method.getReturnType(), userParamTypes));
                }
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Cannot access @NovaMember method: " + clazz.getName() + "." + memberName, e);
            }
        }
    }

    // ============ 分发 ============

    /**
     * 尝试分发成员访问。返回 null 表示未注册（应走其他路径）。
     */
    public static NovaValue dispatch(NovaValue target, String memberName, Interpreter interp) {
        Map<String, MemberHandler> typeHandlers = handlers.get(target.getTypeName());
        if (typeHandlers == null) return null;
        MemberHandler handler = typeHandlers.get(memberName);
        if (handler == null) return null;
        return handler.access(target, interp);
    }

    /**
     * 检查指定类型的指定成员是否已注册。
     */
    public static boolean hasMember(String typeName, String memberName) {
        Map<String, MemberHandler> typeHandlers = handlers.get(typeName);
        return typeHandlers != null && typeHandlers.containsKey(memberName);
    }

    // ============ 内部方法 ============

    private static void registerHandler(String typeName, String memberName, MemberHandler handler) {
        handlers.computeIfAbsent(typeName, k -> new HashMap<>()).put(memberName, handler);
    }

    private static boolean hasInterpreterParam(Method method) {
        for (Class<?> p : method.getParameterTypes()) {
            if (p == Interpreter.class) return true;
        }
        return false;
    }

    /** 属性处理器：调用方法，包装返回值 */
    private static MemberHandler createPropertyHandler(MethodHandle mh, boolean needsInterpreter,
                                                        Class<?> returnType) {
        return (target, interp) -> {
            try {
                Object result = needsInterpreter ? mh.invoke(target, interp) : mh.invoke(target);
                return wrapResult(result, returnType);
            } catch (NovaRuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new NovaRuntimeException("Member access failed: " + e.getMessage());
            }
        };
    }

    /** 方法处理器：返回 NovaNativeFunction 包装 */
    private static MemberHandler createMethodHandler(MethodHandle mh, boolean needsInterpreter,
                                                      String name, int arity, Class<?> returnType,
                                                      Class<?>[] paramTypes) {
        return (target, interp) ->
            new NovaNativeFunction(name, arity, (callInterp, args) -> {
                try {
                    // 构建 MethodHandle 调用参数
                    Object[] invokeArgs = buildInvokeArgs(target, (Interpreter) callInterp, needsInterpreter,
                                                          args, paramTypes);
                    Object result = mh.invokeWithArguments(invokeArgs);
                    return wrapResult(result, returnType);
                } catch (NovaRuntimeException e) {
                    throw e;
                } catch (Throwable e) {
                    throw new NovaRuntimeException("Method call failed: " + e.getMessage());
                }
            });
    }

    /** 构建 MethodHandle 的调用参数列表 */
    private static Object[] buildInvokeArgs(NovaValue target, Interpreter interp,
                                             boolean needsInterpreter,
                                             java.util.List<NovaValue> novaArgs,
                                             Class<?>[] paramTypes) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        result.add(target); // 实例方法的 this
        int novaIdx = 0;
        for (Class<?> pt : paramTypes) {
            if (pt == Interpreter.class) {
                result.add(interp);
            } else if (novaIdx < novaArgs.size()) {
                result.add(convertArg(novaArgs.get(novaIdx++), pt));
            }
        }
        return result.toArray();
    }

    /** 将 NovaValue 参数转换为 Java 方法参数 */
    private static Object convertArg(NovaValue novaVal, Class<?> targetType) {
        if (targetType == long.class || targetType == Long.class) return (long) novaVal.asInt();
        if (targetType == int.class || targetType == Integer.class) return novaVal.asInt();
        if (targetType == double.class || targetType == Double.class) return novaVal.asDouble();
        if (targetType == boolean.class || targetType == Boolean.class) return novaVal.asBoolean();
        if (targetType == String.class) return novaVal.asString();
        return novaVal.toJavaValue();
    }

    /** 将 Java 返回值包装为 NovaValue */
    private static NovaValue wrapResult(Object result, Class<?> returnType) {
        if (result instanceof NovaValue) return (NovaValue) result;
        if (returnType == boolean.class || returnType == Boolean.class)
            return NovaBoolean.of((Boolean) result);
        if (returnType == int.class || returnType == Integer.class)
            return NovaInt.of((Integer) result);
        if (returnType == long.class || returnType == Long.class)
            return NovaLong.of((Long) result);
        if (returnType == double.class || returnType == Double.class)
            return NovaDouble.of((Double) result);
        if (returnType == String.class)
            return NovaString.of((String) result);
        return AbstractNovaValue.fromJava(result);
    }

}
