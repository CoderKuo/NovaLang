package com.novalang.runtime.stdlib;

import com.novalang.runtime.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Lambda 相关工具方法（包内共享 + 跨模块安全守卫）。
 *
 * <p>统一的 lambda MethodHandle 缓存 + 安全 setAccessible 守卫，
 * 供 CollectionOps、LambdaInvoker、AsyncHelper、StructuredConcurrencyHelper、
 * ConcurrencyPrimitivesHelper、SamAdapter 共用。</p>
 */
public final class LambdaUtils {

    private LambdaUtils() {}

    // ============ setAccessible 安全守卫 ============

    /**
     * 当前线程是否允许 setAccessible(true)。
     * 由 Interpreter (nova-runtime) 根据 NovaSecurityPolicy 设置。
     * 默认 true（无策略时保持原有行为）。
     */
    private static final ThreadLocal<Boolean> TL_ALLOW_SET_ACCESSIBLE =
            ThreadLocal.withInitial(() -> Boolean.TRUE);

    /** 设置当前线程的 setAccessible 策略 */
    public static void setAllowSetAccessible(boolean allow) {
        if (allow) {
            TL_ALLOW_SET_ACCESSIBLE.set(Boolean.TRUE);
        } else {
            TL_ALLOW_SET_ACCESSIBLE.set(Boolean.FALSE);
        }
    }

    /** 安全的 setAccessible：策略不允许时静默跳过 */
    public static void trySetAccessible(java.lang.reflect.AccessibleObject ao) {
        if (TL_ALLOW_SET_ACCESSIBLE.get()) {
            try {
                ao.setAccessible(true);
            } catch (Exception ignored) {}
        }
    }

    // ============ 真值判断 ============

    static boolean isTruthy(Object value) {
        return AbstractNovaValue.truthyCheck(value);
    }

    // ============ ClassValue<MethodHandle[]> 统一缓存 ============

    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    /**
     * 按 Class 缓存 invoke 方法的 MethodHandle 数组。
     * 索引 0~8 对应参数数量 0~8 的 invoke 方法。
     */
    private static final ClassValue<MethodHandle[]> INVOKE_HANDLES = new ClassValue<MethodHandle[]>() {
        @Override
        protected MethodHandle[] computeValue(Class<?> cls) {
            MethodHandle[] handles = new MethodHandle[9];
            for (Method m : cls.getMethods()) {
                if (!"invoke".equals(m.getName())) continue;
                int pc = m.getParameterCount();
                if (pc < 0 || pc >= handles.length || handles[pc] != null) continue;
                handles[pc] = toHandle(m);
            }
            return handles;
        }
    };

    private static MethodHandle toHandle(Method m) {
        try {
            return PUBLIC_LOOKUP.unreflect(m);
        } catch (IllegalAccessException e) {
            trySetAccessible(m);
            try {
                return LOOKUP.unreflect(m);
            } catch (IllegalAccessException ignored) {
                return null;
            }
        }
    }

    /** 获取指定 arity 的 invoke MethodHandle（带缓存），找不到返回 null */
    public static MethodHandle getInvokeHandle(Object lambda, int arity) {
        if (arity < 0 || arity > 8) return null;
        return INVOKE_HANDLES.get(lambda.getClass())[arity];
    }

    /** 是否存在任意 arity 的 invoke 方法 */
    public static boolean hasAnyInvokeHandle(Object lambda) {
        MethodHandle[] handles = INVOKE_HANDLES.get(lambda.getClass());
        for (MethodHandle h : handles) {
            if (h != null) return true;
        }
        return false;
    }

    // ============ Lambda 调用方法 ============

    /** 0 参数调用 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object invoke0(Object lambda) {
        // NovaCallable 快速路径
        if (lambda instanceof NovaCallable) {
            NovaValue result = ((NovaCallable) lambda).call(
                    NovaRuntime.currentContext(), java.util.Collections.emptyList());
            return result != null ? result.toJavaValue() : null;
        }
        // NovaValue.dynamicInvoke 快速路径
        if (lambda instanceof NovaValue && ((NovaValue) lambda).isCallable()) {
            return ((NovaValue) lambda).dynamicInvoke(new NovaValue[0]);
        }
        // Function0 快速路径
        if (lambda instanceof Function0) {
            return ((Function0) lambda).invoke();
        }
        // MethodHandle fallback
        MethodHandle mh = getInvokeHandle(lambda, 0);
        if (mh != null) {
            try {
                return mh.invoke(lambda);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        // 尝试任意 arity invoke（兼容旧行为：0 参数找不到时找任意 invoke）
        mh = findAnyInvokeHandle(lambda);
        if (mh != null) {
            try {
                return mh.invoke(lambda);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        throw new NovaException(NovaException.ErrorKind.INTERNAL,
                "Lambda 没有 invoke() 方法: " + lambda.getClass().getName());
    }

    /** 1 参数调用 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object invoke1(Object lambda, Object arg) {
        // NovaCallable 快速路径
        if (lambda instanceof NovaCallable) {
            NovaValue argVal = toNovaValue(arg);
            NovaValue result = ((NovaCallable) lambda).call(
                    NovaRuntime.currentContext(), java.util.Collections.singletonList(argVal));
            return result != null ? result.toJavaValue() : null;
        }
        // NovaValue.dynamicInvoke 快速路径
        if (lambda instanceof NovaValue && ((NovaValue) lambda).isCallable()) {
            NovaValue argVal = toNovaValue(arg);
            return ((NovaValue) lambda).dynamicInvoke(new NovaValue[]{argVal});
        }
        // Function1 快速路径
        if (lambda instanceof Function1) {
            return ((Function1) lambda).invoke(arg);
        }
        // MethodHandle fallback
        MethodHandle mh = getInvokeHandle(lambda, 1);
        if (mh != null) {
            try {
                return mh.invoke(lambda, arg);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        // 兼容：找任意 invoke
        mh = findAnyInvokeHandle(lambda);
        if (mh != null) {
            try {
                return mh.invoke(lambda, arg);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        throw new NovaException(NovaException.ErrorKind.INTERNAL,
                "Lambda 没有 invoke(Object) 方法: " + lambda.getClass().getName());
    }

    /** 2 参数调用 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object invoke2(Object lambda, Object arg1, Object arg2) {
        if (lambda instanceof Function2) {
            return ((Function2) lambda).invoke(arg1, arg2);
        }
        MethodHandle mh = getInvokeHandle(lambda, 2);
        if (mh != null) {
            try {
                return mh.invoke(lambda, arg1, arg2);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        throw new NovaException(NovaException.ErrorKind.INTERNAL,
                "Lambda 没有 invoke(Object,Object) 方法: " + lambda.getClass().getName());
    }

    /** N 参数调用 (4~8)：FunctionN 快速路径 + MethodHandle fallback */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object invokeN(Object lambda, int arity, Object... args) {
        // FunctionN 快速路径
        switch (arity) {
            case 4: if (lambda instanceof Function4) return ((Function4) lambda).invoke(args[0], args[1], args[2], args[3]); break;
            case 5: if (lambda instanceof Function5) return ((Function5) lambda).invoke(args[0], args[1], args[2], args[3], args[4]); break;
            case 6: if (lambda instanceof Function6) return ((Function6) lambda).invoke(args[0], args[1], args[2], args[3], args[4], args[5]); break;
            case 7: if (lambda instanceof Function7) return ((Function7) lambda).invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6]); break;
            case 8: if (lambda instanceof Function8) return ((Function8) lambda).invoke(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]); break;
        }
        MethodHandle mh = getInvokeHandle(lambda, arity);
        if (mh != null) {
            try {
                Object[] full = new Object[arity + 1];
                full[0] = lambda;
                System.arraycopy(args, 0, full, 1, arity);
                return mh.invokeWithArguments(full);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        throw new NovaException(NovaException.ErrorKind.INTERNAL,
                "Lambda 没有 " + arity + " 参数的 invoke 方法");
    }

    /** 是否有 1 参数 invoke */
    static boolean hasInvoke1(Object lambda) {
        if (lambda instanceof NovaValue && ((NovaValue) lambda).isCallable()) return true;
        if (lambda instanceof Function1) return true;
        return getInvokeHandle(lambda, 1) != null;
    }

    /**
     * 灵活调用：Function1 快速路径 → 1 参数回退 → 0 参数。
     * 供作用域函数（let/also/run/apply）和 StdlibCore.withScope/repeat 共用。
     */
    @SuppressWarnings("unchecked")
    static Object invokeFlexible(Object block, Object arg) {
        if (block instanceof Function1) {
            return ((Function1<Object, Object>) block).invoke(arg);
        }
        // NovaCallable 快速路径
        if (block instanceof NovaCallable) {
            NovaValue argVal = toNovaValue(arg);
            NovaValue result = ((NovaCallable) block).call(
                    NovaRuntime.currentContext(), java.util.Collections.singletonList(argVal));
            return result != null ? result.toJavaValue() : null;
        }
        // NovaValue.dynamicInvoke 快速路径
        if (block instanceof NovaValue && ((NovaValue) block).isCallable()) {
            NovaValue argVal = toNovaValue(arg);
            return ((NovaValue) block).dynamicInvoke(new NovaValue[]{argVal});
        }
        // MethodHandle 1 参数 fallback
        MethodHandle mh1 = getInvokeHandle(block, 1);
        if (mh1 != null) {
            try {
                return mh1.invoke(block, arg);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw NovaErrors.wrap("lambda 调用失败", t);
            }
        }
        // 退回 0 参数
        return invoke0(block);
    }

    // ============ 内部辅助 ============

    private static NovaValue toNovaValue(Object arg) {
        return arg instanceof NovaValue ? (NovaValue) arg : AbstractNovaValue.fromJava(arg);
    }

    /** 找到第一个 invoke MethodHandle（不限 arity），用于兼容旧 fallback */
    private static MethodHandle findAnyInvokeHandle(Object lambda) {
        MethodHandle[] handles = INVOKE_HANDLES.get(lambda.getClass());
        for (MethodHandle h : handles) {
            if (h != null) return h;
        }
        return null;
    }
}
