package com.novalang.runtime;

import com.novalang.runtime.stdlib.StdlibRegistry;

import java.lang.invoke.*;
import java.util.function.Function;

/**
 * JVM invokedynamic bootstrap 方法。
 *
 * <p>编译模式下，Nova 对未知类型对象的方法调用/属性访问通过 invokedynamic 指令分派。
 * JVM 在首次遇到 invokedynamic 时调用 bootstrap 方法创建 CallSite，
 * 后续调用直接通过 CallSite 内的 MethodHandle 分派（可被 JIT 内联）。
 *
 * <p>分派策略：单态内联缓存 (monomorphic inline cache)。
 * <ul>
 *   <li>首次调用：解析 receiver 类型 → 绑定直接 MethodHandle → 安装 class guard</li>
 *   <li>类型命中：直接走缓存的 MethodHandle（O(1)，可 JIT 内联）</li>
 *   <li>类型未命中：退化到 NovaDynamic.invokeN 全路径分派</li>
 * </ul>
 */
public final class NovaBootstrap {

    private NovaBootstrap() {}

    private static final MethodHandle INVOKE_FALLBACK;
    private static final MethodHandle GET_MEMBER_FALLBACK;
    private static final MethodHandle SET_MEMBER_FALLBACK;
    private static final MethodHandle STATIC_INVOKE_FALLBACK;
    private static final MethodHandle CLASS_CHECK;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            INVOKE_FALLBACK = lookup.findStatic(NovaBootstrap.class, "invokeFallback",
                    MethodType.methodType(Object.class,
                            MutableCallSite.class, String.class, Object[].class));
            GET_MEMBER_FALLBACK = lookup.findStatic(NovaBootstrap.class, "getMemberFallback",
                    MethodType.methodType(Object.class,
                            MutableCallSite.class, String.class, Object.class));
            SET_MEMBER_FALLBACK = lookup.findStatic(NovaBootstrap.class, "setMemberFallback",
                    MethodType.methodType(void.class,
                            MutableCallSite.class, String.class, Object.class, Object.class));
            STATIC_INVOKE_FALLBACK = lookup.findStatic(NovaBootstrap.class, "staticInvokeFallback",
                    MethodType.methodType(Object.class,
                            MutableCallSite.class, String.class, Object[].class));
            CLASS_CHECK = lookup.findStatic(NovaBootstrap.class, "classCheck",
                    MethodType.methodType(boolean.class, Class.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ---- 实例方法调用 ----

    /**
     * 实例方法调用的 bootstrap。
     * invokedynamic 签名: (Object receiver, Object... args) → Object
     */
    public static CallSite bootstrapInvoke(MethodHandles.Lookup lookup,
                                            String methodName,
                                            MethodType type) {
        MutableCallSite site = new MutableCallSite(type);
        // 初始 target: 收集所有参数 → invokeFallback(site, methodName, Object[] args)
        // args[0] = receiver, args[1..] = method args
        MethodHandle fallback = MethodHandles.insertArguments(INVOKE_FALLBACK, 0, site, methodName)
                .asCollector(Object[].class, type.parameterCount())
                .asType(type);
        site.setTarget(fallback);
        return site;
    }

    /**
     * fallback：首次调用或类型未命中时执行。
     * 尝试解析直接 MethodHandle 并安装 guard；失败则退化到 NovaDynamic.invokeMethod。
     */
    private static Object invokeFallback(MutableCallSite site, String methodName,
                                          Object[] allArgs) throws Throwable {
        Object receiver = allArgs[0];
        if (receiver == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        int arity = allArgs.length - 1; // 不含 receiver
        Object[] methodArgs = new Object[arity];
        System.arraycopy(allArgs, 1, methodArgs, 0, arity);

        Class<?> receiverClass = receiver.getClass();

        // 安全策略检查（仅 fallback 首次调用时执行，内联缓存命中后不再经过此路径）
        NovaSecurityPolicy.checkClass(receiverClass.getName());
        NovaSecurityPolicy.checkMethod(receiverClass.getName(), methodName);

        // 尝试解析直接 MethodHandle
        MethodHandle resolved = NovaDynamic.resolveForCallSite(receiverClass, methodName, arity, methodArgs);

        // NovaJavaClass 穿透：解析底层 Java 类的静态方法并安装缓存
        // （NovaExternalObject 的实例方法由 NovaDynamic.invokeMethod 精确匹配，不穿透）
        if (resolved == null && receiver instanceof NovaValue) {
            Object javaVal = ((NovaValue) receiver).toJavaValue();
            if (javaVal instanceof Class<?>) {
                resolved = NovaDynamic.resolveStaticForCallSite((Class<?>) javaVal, methodName, arity, methodArgs);
                if (resolved != null) {
                    allArgs[0] = javaVal; // MethodHandle 期望 Class 对象而非 NovaJavaClass
                }
            }
        }

        if (resolved != null) {
            // 安装单态内联缓存: guard(classCheck) → resolved ; else → currentTarget (fallback)
            MethodHandle adaptedResolved = resolved.asType(site.type());

            // classGuard: (Object) → boolean，仅检测第一个参数（receiver）
            MethodHandle classGuard = MethodHandles.insertArguments(CLASS_CHECK, 0, receiverClass);
            // 扩展到完整参数列表（drop 多余参数），使其与 target/fallback 签名一致
            if (site.type().parameterCount() > 1) {
                Class<?>[] extraTypes = new Class<?>[site.type().parameterCount() - 1];
                System.arraycopy(site.type().parameterArray(), 1, extraTypes, 0, extraTypes.length);
                classGuard = MethodHandles.dropArguments(classGuard, 1, extraTypes);
            }

            MethodHandle guard = MethodHandles.guardWithTest(
                    classGuard,
                    adaptedResolved,
                    site.getTarget() // fallback
            );
            site.setTarget(guard);

            // 执行本次调用
            return resolved.invokeWithArguments(allArgs);
        }

        // 无法解析直接 handle（NovaMap / script extension 等）→ 走 NovaDynamic 全路径
        return NovaDynamic.invokeMethod(receiver, methodName, methodArgs);
    }

    // ---- 属性访问 ----

    /**
     * 属性读取的 bootstrap。
     * invokedynamic 签名: (Object target) → Object
     */
    public static CallSite bootstrapGetMember(MethodHandles.Lookup lookup,
                                               String memberName,
                                               MethodType type) {
        MutableCallSite site = new MutableCallSite(type);
        MethodHandle fallback = MethodHandles.insertArguments(GET_MEMBER_FALLBACK, 0, site, memberName)
                .asType(type);
        site.setTarget(fallback);
        return site;
    }

    private static Object getMemberFallback(MutableCallSite site, String memberName,
                                             Object target) throws Throwable {
        if (target == null) {
            throw new NullPointerException("Cannot access member '" + memberName + "' on null");
        }

        Class<?> clazz = target.getClass();

        // 安全策略检查
        NovaSecurityPolicy.checkClass(clazz.getName());

        MethodHandle getter = NovaDynamic.resolveGetterForCallSite(clazz, memberName);

        if (getter != null) {
            MethodHandle adaptedGetter = getter.asType(site.type());
            MethodHandle guard = MethodHandles.guardWithTest(
                    MethodHandles.insertArguments(CLASS_CHECK, 0, clazz)
                            .asType(MethodType.methodType(boolean.class, site.type().parameterArray()[0])),
                    adaptedGetter,
                    site.getTarget()
            );
            site.setTarget(guard);
            return getter.invoke(target);
        }

        // 退化到全路径
        return NovaDynamic.getMember(target, memberName);
    }

    /**
     * 属性写入的 bootstrap。
     * invokedynamic 签名: (Object target, Object value) → void
     */
    public static CallSite bootstrapSetMember(MethodHandles.Lookup lookup,
                                               String memberName,
                                               MethodType type) {
        MutableCallSite site = new MutableCallSite(type);
        MethodHandle fallback = MethodHandles.insertArguments(SET_MEMBER_FALLBACK, 0, site, memberName)
                .asType(type);
        site.setTarget(fallback);
        return site;
    }

    private static void setMemberFallback(MutableCallSite site, String memberName,
                                           Object target, Object value) throws Throwable {
        if (target == null) {
            throw new NullPointerException("Cannot set member '" + memberName + "' on null");
        }

        Class<?> clazz = target.getClass();

        // 安全策略检查
        NovaSecurityPolicy.checkClass(clazz.getName());

        MethodHandle setter = NovaDynamic.resolveSetterForCallSite(clazz, memberName);

        if (setter != null) {
            MethodHandle adaptedSetter = setter.asType(site.type());
            // classGuard: (Object) → boolean，扩展到 (Object, Object) → boolean
            MethodHandle classGuard = MethodHandles.insertArguments(CLASS_CHECK, 0, clazz);
            classGuard = MethodHandles.dropArguments(classGuard, 1, Object.class);
            MethodHandle guard = MethodHandles.guardWithTest(
                    classGuard,
                    adaptedSetter,
                    site.getTarget()
            );
            site.setTarget(guard);
            setter.invoke(target, value);
            return;
        }

        // 退化到全路径
        NovaDynamic.setMember(target, memberName, value);
    }

    // ---- 全局/静态函数调用 ----

    /**
     * 全局/静态函数调用的 bootstrap。
     * invokedynamic 签名: (Object... args) → Object
     *
     * <p>对 StdlibRegistry 中的不可变函数（sin, cos, abs 等）永久缓存 CallSite；
     * 对 NovaScriptContext 的可变绑定，每次走全路径分派。</p>
     */
    public static CallSite bootstrapStaticInvoke(MethodHandles.Lookup lookup,
                                                  String funcName,
                                                  MethodType type) {
        MutableCallSite site = new MutableCallSite(type);
        MethodHandle fallback = MethodHandles.insertArguments(STATIC_INVOKE_FALLBACK, 0, site, funcName)
                .asCollector(Object[].class, type.parameterCount())
                .asType(type);
        site.setTarget(fallback);
        return site;
    }

    /**
     * 静态函数 fallback：首次调用解析函数。
     * - StdlibRegistry 函数：包装为 MethodHandle 并永久缓存（不可变）
     * - NovaScriptContext 绑定：每次调用走全路径（可变）
     */
    @SuppressWarnings("unchecked")
    private static Object staticInvokeFallback(MutableCallSite site, String funcName,
                                                Object[] args) throws Throwable {
        if (NovaScriptContext.isActive()) {
            return NovaScriptContext.call(funcName, args);
        }
        // 1. 尝试 StdlibRegistry 内置函数（不可变，可永久缓存）
        StdlibRegistry.NativeFunctionInfo nfInfo = StdlibRegistry.getNativeFunction(funcName);
        // TODO Phase 1: 这里将改为 NovaRuntime.lookupBuiltin(funcName) 统一查找
        if (nfInfo != null) {
            // 包装 nfInfo.impl.apply(Object[]) 为 MethodHandle
            MethodHandle applyMH = MethodHandles.lookup()
                    .findVirtual(Function.class, "apply",
                            MethodType.methodType(Object.class, Object.class))
                    .bindTo(nfInfo.impl);
            // 适配：收集所有参数为 Object[] 再调用 apply
            MethodHandle adapted = applyMH
                    .asCollector(Object[].class, site.type().parameterCount())
                    .asType(site.type());
            site.setTarget(adapted);  // 永久缓存（stdlib 函数不可变）
            return nfInfo.impl.apply(args);
        }

        // 2. NovaScriptContext 绑定（可变，不缓存，每次走全路径）
        return NovaScriptContext.call(funcName, args);
    }

    // ---- 辅助方法 ----

    /**
     * 类型 guard：检查 receiver 是否为指定类的实例。
     */
    private static boolean classCheck(Class<?> expected, Object receiver) {
        return receiver != null && receiver.getClass() == expected;
    }
}
