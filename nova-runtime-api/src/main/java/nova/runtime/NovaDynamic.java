package nova.runtime;

import nova.runtime.stdlib.StdlibRegistry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class NovaDynamic {

    private NovaDynamic() {}

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    private static final MethodType GETTER_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType SETTER_TYPE = MethodType.methodType(void.class, Object.class, Object.class);
    private static final MethodType INSTANCE0_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType INSTANCE1_TYPE = MethodType.methodType(Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE2_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE3_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE4_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE5_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE6_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE7_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType INSTANCE8_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType STATIC0_TYPE = MethodType.methodType(Object.class);
    private static final MethodType STATIC1_TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodType STATIC2_TYPE = MethodType.methodType(Object.class, Object.class, Object.class);
    private static final MethodType STATIC3_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class);
    private static final MethodType STATIC4_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType STATIC5_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType STATIC6_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType STATIC7_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);
    private static final MethodType STATIC8_TYPE = MethodType.methodType(Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class);

    private static final Object[] EMPTY_ARGS = new Object[0];
    private static final Class<?>[] EMPTY_TYPES = new Class<?>[0];
    private static final NovaValue[] EMPTY_NOVA_ARGS = new NovaValue[0];
    private static final Object NOVA_MAP_MISS = new Object();

    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>> getterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>> setterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodDispatchCache> methodCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, MethodHandle>> staticGetterCache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, MethodDispatchCache> staticMethodCache = new ConcurrentHashMap<>();

    private static final ThreadLocal<MethodKey> lookupMethodKey = ThreadLocal.withInitial(MethodKey::new);

    private static final MethodHandle STDLIB0_FALLBACK = findOwnStatic(
            "invokeStdlib0", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class));
    private static final MethodHandle STDLIB1_FALLBACK = findOwnStatic(
            "invokeStdlib1", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class));
    private static final MethodHandle STDLIB2_FALLBACK = findOwnStatic(
            "invokeStdlib2", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class));
    private static final MethodHandle STDLIB3_FALLBACK = findOwnStatic(
            "invokeStdlib3", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class, Object.class));
    private static final MethodHandle STDLIB4_FALLBACK = findOwnStatic(
            "invokeStdlib4", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class, Object.class, Object.class));
    private static final MethodHandle STDLIB5_FALLBACK = findOwnStatic(
            "invokeStdlib5", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));
    private static final MethodHandle STDLIB6_FALLBACK = findOwnStatic(
            "invokeStdlib6", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));
    private static final MethodHandle STDLIB7_FALLBACK = findOwnStatic(
            "invokeStdlib7", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));
    private static final MethodHandle STDLIB8_FALLBACK = findOwnStatic(
            "invokeStdlib8", MethodType.methodType(Object.class, StdlibRegistry.ExtensionMethodInfo.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class, Object.class));

    public static Object getMember(Object target, String memberName) {
        if (target == null) {
            throw new NullPointerException("Cannot access member '" + memberName + "' on null");
        }

        if (target instanceof NovaMap) {
            NovaValue val = ((NovaMap) target).get(NovaString.of(memberName));
            if (val != null) {
                return val;
            }
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            ConcurrentHashMap<String, MethodHandle> cache =
                    staticGetterCache.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
            MethodHandle staticGetter = cache.get(memberName);
            if (staticGetter == null) {
                staticGetter = resolveStaticGetter(cls, memberName);
                if (staticGetter != null) {
                    cache.put(memberName, staticGetter);
                }
            }
            if (staticGetter != null) {
                return invokeStatic0(staticGetter, memberName);
            }
        }

        Class<?> clazz = target.getClass();
        ConcurrentHashMap<String, MethodHandle> cache =
                getterCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        MethodHandle getter = cache.get(memberName);
        if (getter == null) {
            getter = resolveGetter(clazz, memberName);
            cache.put(memberName, getter);
        }
        return invokeInstance0(getter, target, memberName);
    }

    public static void setMember(Object target, String memberName, Object value) {
        if (target == null) {
            throw new NullPointerException("Cannot set member '" + memberName + "' on null");
        }
        Class<?> clazz = target.getClass();
        ConcurrentHashMap<String, MethodHandle> cache =
                setterCache.computeIfAbsent(clazz, k -> new ConcurrentHashMap<>());
        MethodHandle setter = cache.get(memberName);
        if (setter == null) {
            setter = resolveSetter(clazz, memberName);
            cache.put(memberName, setter);
        }
        invokeSetter(setter, target, value, memberName);
    }

    public static Object invokeMethod(Object target, String methodName, Object... args) {
        switch (args.length) {
            case 0:
                return invoke0(target, methodName);
            case 1:
                return invoke1(target, methodName, args[0]);
            case 2:
                return invoke2(target, methodName, args[0], args[1]);
            case 3:
                return invoke3(target, methodName, args[0], args[1], args[2]);
            case 4:
                return invoke4(target, methodName, args[0], args[1], args[2], args[3]);
            case 5:
                return invoke5(target, methodName, args[0], args[1], args[2], args[3], args[4]);
            case 6:
                return invoke6(target, methodName, args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7:
                return invoke7(target, methodName, args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8:
                return invoke8(target, methodName, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            default:
                return invokeVarArgs(target, methodName, args);
        }
    }

    public static Object invokeArray(Object target, String methodName, Object[] args) {
        if (args == null) {
            return invoke0(target, methodName);
        }
        switch (args.length) {
            case 0:
                return invoke0(target, methodName);
            case 1:
                return invoke1(target, methodName, args[0]);
            case 2:
                return invoke2(target, methodName, args[0], args[1]);
            case 3:
                return invoke3(target, methodName, args[0], args[1], args[2]);
            case 4:
                return invoke4(target, methodName, args[0], args[1], args[2], args[3]);
            case 5:
                return invoke5(target, methodName, args[0], args[1], args[2], args[3], args[4]);
            case 6:
                return invoke6(target, methodName, args[0], args[1], args[2], args[3], args[4], args[5]);
            case 7:
                return invoke7(target, methodName, args[0], args[1], args[2], args[3], args[4], args[5], args[6]);
            case 8:
                return invoke8(target, methodName, args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7]);
            default:
                return invokeVarArgs(target, methodName, args);
        }
    }

    public static Object invoke0(Object target, String methodName) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodHandle staticMethod = cache.zeroArg.get(methodName);
            if (staticMethod == null && !cache.zeroArgMiss.containsKey(methodName)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, EMPTY_ARGS, STATIC0_TYPE);
                if (staticMethod != null) {
                    cache.zeroArg.put(methodName, staticMethod);
                } else {
                    cache.zeroArgMiss.put(methodName, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic0(staticMethod, methodName);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, EMPTY_ARGS);
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodHandle method = cache.zeroArg.get(methodName);
        if (method == null && !cache.zeroArgMiss.containsKey(methodName)) {
            method = resolveMethodHandle(clazz, methodName, EMPTY_ARGS, INSTANCE0_TYPE);
            if (method != null) {
                cache.zeroArg.put(methodName, method);
            } else {
                cache.zeroArgMiss.put(methodName, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance0(method, target, methodName);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke0(target, aliased);
        }

        MethodHandle stdlib = cache.zeroArgStdlib.get(methodName);
        if (stdlib == null && !cache.zeroArgStdlibMiss.containsKey(methodName)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 0, INSTANCE0_TYPE);
            if (stdlib != null) {
                cache.zeroArgStdlib.put(methodName, stdlib);
            } else {
                cache.zeroArgStdlibMiss.put(methodName, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance0(stdlib, target, methodName);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, EMPTY_ARGS);
    }

    public static Object invoke1(Object target, String methodName, Object a0) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), null, null, null, null, null, 1);
            MethodHandle staticMethod = cache.oneArg.get(key);
            if (staticMethod == null && !cache.oneArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0}, STATIC1_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.oneArg.put(storedKey, staticMethod);
                } else {
                    cache.oneArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic1(staticMethod, methodName, a0);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), null, null, null, null, null, 1);
        MethodHandle method = cache.oneArg.get(key);
        if (method == null && !cache.oneArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0}, INSTANCE1_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.oneArg.put(storedKey, method);
            } else {
                cache.oneArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance1(method, target, methodName, a0);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke1(target, aliased, a0);
        }

        MethodHandle stdlib = cache.oneArgStdlib.get(key);
        if (stdlib == null && !cache.oneArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 1, INSTANCE1_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.oneArgStdlib.put(storedKey, stdlib);
            } else {
                cache.oneArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance1(stdlib, target, methodName, a0);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0});
    }

    public static Object invoke2(Object target, String methodName, Object a0, Object a1) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), null, null, null, null, 2);
            MethodHandle staticMethod = cache.twoArg.get(key);
            if (staticMethod == null && !cache.twoArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1}, STATIC2_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.twoArg.put(storedKey, staticMethod);
                } else {
                    cache.twoArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic2(staticMethod, methodName, a0, a1);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), null, null, null, null, 2);
        MethodHandle method = cache.twoArg.get(key);
        if (method == null && !cache.twoArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1}, INSTANCE2_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.twoArg.put(storedKey, method);
            } else {
                cache.twoArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance2(method, target, methodName, a0, a1);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke2(target, aliased, a0, a1);
        }

        MethodHandle stdlib = cache.twoArgStdlib.get(key);
        if (stdlib == null && !cache.twoArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 2, INSTANCE2_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.twoArgStdlib.put(storedKey, stdlib);
            } else {
                cache.twoArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance2(stdlib, target, methodName, a0, a1);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1});
    }

    public static Object invoke3(Object target, String methodName, Object a0, Object a1, Object a2) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), null, null, null, 3);
            MethodHandle staticMethod = cache.threeArg.get(key);
            if (staticMethod == null && !cache.threeArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1, a2}, STATIC3_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.threeArg.put(storedKey, staticMethod);
                } else {
                    cache.threeArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic3(staticMethod, methodName, a0, a1, a2);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1, a2});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), null, null, null, 3);
        MethodHandle method = cache.threeArg.get(key);
        if (method == null && !cache.threeArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1, a2}, INSTANCE3_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.threeArg.put(storedKey, method);
            } else {
                cache.threeArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance3(method, target, methodName, a0, a1, a2);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke3(target, aliased, a0, a1, a2);
        }

        MethodHandle stdlib = cache.threeArgStdlib.get(key);
        if (stdlib == null && !cache.threeArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 3, INSTANCE3_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.threeArgStdlib.put(storedKey, stdlib);
            } else {
                cache.threeArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance3(stdlib, target, methodName, a0, a1, a2);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1, a2});
    }

    public static Object invoke4(Object target, String methodName, Object a0, Object a1, Object a2, Object a3) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), null, null, 4);
            MethodHandle staticMethod = cache.fourArg.get(key);
            if (staticMethod == null && !cache.fourArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1, a2, a3}, STATIC4_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.fourArg.put(storedKey, staticMethod);
                } else {
                    cache.fourArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic4(staticMethod, methodName, a0, a1, a2, a3);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1, a2, a3});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), null, null, 4);
        MethodHandle method = cache.fourArg.get(key);
        if (method == null && !cache.fourArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1, a2, a3}, INSTANCE4_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.fourArg.put(storedKey, method);
            } else {
                cache.fourArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance4(method, target, methodName, a0, a1, a2, a3);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke4(target, aliased, a0, a1, a2, a3);
        }

        MethodHandle stdlib = cache.fourArgStdlib.get(key);
        if (stdlib == null && !cache.fourArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 4, INSTANCE4_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.fourArgStdlib.put(storedKey, stdlib);
            } else {
                cache.fourArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance4(stdlib, target, methodName, a0, a1, a2, a3);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1, a2, a3});
    }

    public static Object invoke5(Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), null, 5);
            MethodHandle staticMethod = cache.fiveArg.get(key);
            if (staticMethod == null && !cache.fiveArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1, a2, a3, a4}, STATIC5_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.fiveArg.put(storedKey, staticMethod);
                } else {
                    cache.fiveArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic5(staticMethod, methodName, a0, a1, a2, a3, a4);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1, a2, a3, a4});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), null, 5);
        MethodHandle method = cache.fiveArg.get(key);
        if (method == null && !cache.fiveArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1, a2, a3, a4}, INSTANCE5_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.fiveArg.put(storedKey, method);
            } else {
                cache.fiveArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance5(method, target, methodName, a0, a1, a2, a3, a4);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke5(target, aliased, a0, a1, a2, a3, a4);
        }

        MethodHandle stdlib = cache.fiveArgStdlib.get(key);
        if (stdlib == null && !cache.fiveArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 5, INSTANCE5_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.fiveArgStdlib.put(storedKey, stdlib);
            } else {
                cache.fiveArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance5(stdlib, target, methodName, a0, a1, a2, a3, a4);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1, a2, a3, a4});
    }

    public static Object invoke6(Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), argClass(a5), 6);
            MethodHandle staticMethod = cache.sixArg.get(key);
            if (staticMethod == null && !cache.sixArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1, a2, a3, a4, a5}, STATIC6_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.sixArg.put(storedKey, staticMethod);
                } else {
                    cache.sixArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic6(staticMethod, methodName, a0, a1, a2, a3, a4, a5);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1, a2, a3, a4, a5});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), argClass(a5), 6);
        MethodHandle method = cache.sixArg.get(key);
        if (method == null && !cache.sixArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1, a2, a3, a4, a5}, INSTANCE6_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.sixArg.put(storedKey, method);
            } else {
                cache.sixArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance6(method, target, methodName, a0, a1, a2, a3, a4, a5);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke6(target, aliased, a0, a1, a2, a3, a4, a5);
        }

        MethodHandle stdlib = cache.sixArgStdlib.get(key);
        if (stdlib == null && !cache.sixArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 6, INSTANCE6_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.sixArgStdlib.put(storedKey, stdlib);
            } else {
                cache.sixArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance6(stdlib, target, methodName, a0, a1, a2, a3, a4, a5);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1, a2, a3, a4, a5});
    }

    public static Object invoke7(Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), argClass(a5), argClass(a6), null, 7);
            MethodHandle staticMethod = cache.sevenArg.get(key);
            if (staticMethod == null && !cache.sevenArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6}, STATIC7_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.sevenArg.put(storedKey, staticMethod);
                } else {
                    cache.sevenArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic7(staticMethod, methodName, a0, a1, a2, a3, a4, a5, a6);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), argClass(a5), argClass(a6), null, 7);
        MethodHandle method = cache.sevenArg.get(key);
        if (method == null && !cache.sevenArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6}, INSTANCE7_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.sevenArg.put(storedKey, method);
            } else {
                cache.sevenArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance7(method, target, methodName, a0, a1, a2, a3, a4, a5, a6);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke7(target, aliased, a0, a1, a2, a3, a4, a5, a6);
        }

        MethodHandle stdlib = cache.sevenArgStdlib.get(key);
        if (stdlib == null && !cache.sevenArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 7, INSTANCE7_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.sevenArgStdlib.put(storedKey, stdlib);
            } else {
                cache.sevenArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance7(stdlib, target, methodName, a0, a1, a2, a3, a4, a5, a6);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6});
    }

    public static Object invoke8(Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), argClass(a5), argClass(a6), argClass(a7), 8);
            MethodHandle staticMethod = cache.eightArg.get(key);
            if (staticMethod == null && !cache.eightArgMiss.containsKey(key)) {
                staticMethod = resolveStaticMethodHandle(cls, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6, a7}, STATIC8_TYPE);
                MethodKey storedKey = MethodKey.copyOf(key);
                if (staticMethod != null) {
                    cache.eightArg.put(storedKey, staticMethod);
                } else {
                    cache.eightArgMiss.put(storedKey, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStatic8(staticMethod, methodName, a0, a1, a2, a3, a4, a5, a6, a7);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6, a7});
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        MethodKey key = lookupMethodKey.get().init(methodName, argClass(a0), argClass(a1), argClass(a2), argClass(a3), argClass(a4), argClass(a5), argClass(a6), argClass(a7), 8);
        MethodHandle method = cache.eightArg.get(key);
        if (method == null && !cache.eightArgMiss.containsKey(key)) {
            method = resolveMethodHandle(clazz, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6, a7}, INSTANCE8_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (method != null) {
                cache.eightArg.put(storedKey, method);
            } else {
                cache.eightArgMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstance8(method, target, methodName, a0, a1, a2, a3, a4, a5, a6, a7);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invoke8(target, aliased, a0, a1, a2, a3, a4, a5, a6, a7);
        }

        MethodHandle stdlib = cache.eightArgStdlib.get(key);
        if (stdlib == null && !cache.eightArgStdlibMiss.containsKey(key)) {
            stdlib = resolveStdlibExtensionHandle(clazz, methodName, 8, INSTANCE8_TYPE);
            MethodKey storedKey = MethodKey.copyOf(key);
            if (stdlib != null) {
                cache.eightArgStdlib.put(storedKey, stdlib);
            } else {
                cache.eightArgStdlibMiss.put(storedKey, Boolean.TRUE);
            }
        }
        if (stdlib != null) {
            return invokeInstance8(stdlib, target, methodName, a0, a1, a2, a3, a4, a5, a6, a7);
        }

        return invokeScriptExtensionOrThrow(clazz, target, methodName, new Object[]{a0, a1, a2, a3, a4, a5, a6, a7});
    }

    private static Object invokeVarArgs(Object target, String methodName, Object[] args) {
        if (target == null) {
            throw new NullPointerException("Cannot invoke method '" + methodName + "' on null");
        }

        if (target instanceof Class<?>) {
            Class<?> cls = (Class<?>) target;
            MethodDispatchCache cache = staticMethodCache.computeIfAbsent(cls, k -> new MethodDispatchCache());
            String key = cacheKey(methodName, args);
            MethodHandle staticMethod = cache.generic.get(key);
            if (staticMethod == null && !cache.genericMiss.containsKey(key)) {
                staticMethod = resolveStaticMethod(cls, methodName, args);
                if (staticMethod != null) {
                    cache.generic.put(key, staticMethod);
                } else {
                    cache.genericMiss.put(key, Boolean.TRUE);
                }
            }
            if (staticMethod != null) {
                return invokeStaticVarArgs(staticMethod, methodName, args);
            }
        }

        if (target instanceof NovaMap) {
            Object result = invokeNovaMapMember((NovaMap) target, methodName, args);
            if (result != NOVA_MAP_MISS) {
                return result;
            }
        }

        Class<?> clazz = target.getClass();
        MethodDispatchCache cache = methodCache.computeIfAbsent(clazz, k -> new MethodDispatchCache());
        String key = cacheKey(methodName, args);
        MethodHandle method = cache.generic.get(key);
        if (method == null && !cache.genericMiss.containsKey(key)) {
            method = resolveMethod(clazz, methodName, args);
            if (method != null) {
                cache.generic.put(key, method);
            } else {
                cache.genericMiss.put(key, Boolean.TRUE);
            }
        }
        if (method != null) {
            return invokeInstanceVarArgs(method, target, methodName, args);
        }

        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            return invokeMethod(target, aliased, args);
        }
        return invokeGenericExtension(cache, clazz, target, methodName, args, key);
    }

    private static Object invokeNovaMapMember(NovaMap target, String methodName, Object[] args) {
        NovaValue member = target.get(NovaString.of(methodName));
        if (member == null) {
            return NOVA_MAP_MISS;
        }
        if (!member.isCallable()) {
            return member;
        }
        if (args.length == 0) {
            return member.dynamicInvoke(EMPTY_NOVA_ARGS);
        }
        NovaValue[] novaArgs = new NovaValue[args.length];
        for (int i = 0; i < args.length; i++) {
            novaArgs[i] = args[i] instanceof NovaValue
                    ? (NovaValue) args[i]
                    : AbstractNovaValue.fromJava(args[i]);
        }
        return member.dynamicInvoke(novaArgs);
    }

    private static Object invokeGenericExtension(MethodDispatchCache cache, Class<?> clazz, Object target,
                                               String methodName, Object[] args, String cacheKey) {
        StdlibRegistry.ExtensionMethodInfo extInfo = cache.genericStdlib.get(cacheKey);
        if (extInfo == null && !cache.genericStdlibMiss.containsKey(cacheKey)) {
            extInfo = StdlibRegistry.findExtensionMethod(clazz, methodName, args.length);
            if (extInfo != null) {
                cache.genericStdlib.put(cacheKey, extInfo);
            } else {
                cache.genericStdlibMiss.put(cacheKey, Boolean.TRUE);
            }
        }
        if (extInfo != null) {
            return invokeStdlibGeneric(extInfo, target, args);
        }
        return invokeScriptExtensionOrThrow(clazz, target, methodName, args);
    }

    private static Object invokeScriptExtensionOrThrow(Class<?> clazz, Object target, String methodName, Object[] args) {
        ExtensionRegistry extReg = NovaScriptContext.getExtensionRegistry();
        if (extReg != null) {
            Class<?>[] argTypes = args.length == 0 ? EMPTY_TYPES : new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                argTypes[i] = args[i] != null ? args[i].getClass() : Object.class;
            }
            ExtensionRegistry.RegisteredExtension ext = extReg.lookup(clazz, methodName, argTypes);
            if (ext != null) {
                try {
                    return ext.invoke(target, args);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException(e.getMessage(), e);
                }
            }
        }

        throw noSuchMethod(clazz, methodName, args.length);
    }

    private static RuntimeException noSuchMethod(Class<?> clazz, String methodName, int argCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("No method '").append(methodName).append("' found on ")
          .append(clazz.getSimpleName()).append(" with ").append(argCount).append(" argument(s)");

        // 同名方法存在但参数数量不同时，提示可用的参数数量
        java.util.TreeSet<Integer> arities = new java.util.TreeSet<Integer>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName)) {
                arities.add(m.getParameterCount());
            }
        }
        if (!arities.isEmpty()) {
            sb.append(". '").append(methodName).append("' exists with ");
            java.util.Iterator<Integer> it = arities.iterator();
            sb.append(it.next());
            while (it.hasNext()) {
                sb.append(" or ").append(it.next());
            }
            sb.append(" argument(s)");
        }

        return new RuntimeException(sb.toString());
    }

    private static Object invokeStatic0(MethodHandle handle, String methodName) {
        try {
            return (Object) handle.invokeExact();
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic1(MethodHandle handle, String methodName, Object a0) {
        try {
            return (Object) handle.invokeExact(a0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic2(MethodHandle handle, String methodName, Object a0, Object a1) {
        try {
            return (Object) handle.invokeExact(a0, a1);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic3(MethodHandle handle, String methodName, Object a0, Object a1, Object a2) {
        try {
            return (Object) handle.invokeExact(a0, a1, a2);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic4(MethodHandle handle, String methodName, Object a0, Object a1, Object a2, Object a3) {
        try {
            return (Object) handle.invokeExact(a0, a1, a2, a3);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic5(MethodHandle handle, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4) {
        try {
            return (Object) handle.invokeExact(a0, a1, a2, a3, a4);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic6(MethodHandle handle, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
        try {
            return (Object) handle.invokeExact(a0, a1, a2, a3, a4, a5);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic7(MethodHandle handle, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        try {
            return (Object) handle.invokeExact(a0, a1, a2, a3, a4, a5, a6);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStatic8(MethodHandle handle, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        try {
            return (Object) handle.invokeExact(a0, a1, a2, a3, a4, a5, a6, a7);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeStaticVarArgs(MethodHandle handle, String methodName, Object[] args) {
        try {
            return handle.invokeWithArguments(args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke static " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance0(MethodHandle handle, Object target, String methodName) {
        try {
            return (Object) handle.invokeExact(target);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance1(MethodHandle handle, Object target, String methodName, Object a0) {
        try {
            return (Object) handle.invokeExact(target, a0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance2(MethodHandle handle, Object target, String methodName, Object a0, Object a1) {
        try {
            return (Object) handle.invokeExact(target, a0, a1);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance3(MethodHandle handle, Object target, String methodName, Object a0, Object a1, Object a2) {
        try {
            return (Object) handle.invokeExact(target, a0, a1, a2);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance4(MethodHandle handle, Object target, String methodName, Object a0, Object a1, Object a2, Object a3) {
        try {
            return (Object) handle.invokeExact(target, a0, a1, a2, a3);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance5(MethodHandle handle, Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4) {
        try {
            return (Object) handle.invokeExact(target, a0, a1, a2, a3, a4);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance6(MethodHandle handle, Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
        try {
            return (Object) handle.invokeExact(target, a0, a1, a2, a3, a4, a5);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance7(MethodHandle handle, Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        try {
            return (Object) handle.invokeExact(target, a0, a1, a2, a3, a4, a5, a6);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstance8(MethodHandle handle, Object target, String methodName, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        try {
            return (Object) handle.invokeExact(target, a0, a1, a2, a3, a4, a5, a6, a7);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static Object invokeInstanceVarArgs(MethodHandle handle, Object target, String methodName, Object[] args) {
        try {
            return handle.invokeWithArguments(buildArgs(target, args));
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException("Failed to invoke " + methodName + ": " + e.getMessage(), e);
        }
    }

    private static void invokeSetter(MethodHandle handle, Object target, Object value, String memberName) {
        try {
            handle.invokeExact(target, value);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("Failed to set member '" + memberName + "': " + t.getMessage(), t);
        }
    }

    private static MethodHandle resolveStdlibExtensionHandle(Class<?> clazz, String methodName, int arity, MethodType callSiteType) {
        StdlibRegistry.ExtensionMethodInfo extInfo = StdlibRegistry.findExtensionMethod(clazz, methodName, arity);
        if (extInfo == null) {
            return null;
        }
        return bindStdlibExtensionHandle(extInfo, callSiteType);
    }

    private static MethodHandle bindStdlibExtensionHandle(StdlibRegistry.ExtensionMethodInfo extInfo, MethodType callSiteType) {
        MethodHandle handle = lookupStdlibExtensionMethodHandle(extInfo);
        if (handle != null) {
            try {
                return handle.asType(callSiteType);
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
            }
        }

        try {
            switch (callSiteType.parameterCount()) {
                case 1:
                    return MethodHandles.insertArguments(STDLIB0_FALLBACK, 0, extInfo);
                case 2:
                    return MethodHandles.insertArguments(STDLIB1_FALLBACK, 0, extInfo);
                case 3:
                    return MethodHandles.insertArguments(STDLIB2_FALLBACK, 0, extInfo);
                case 4:
                    return MethodHandles.insertArguments(STDLIB3_FALLBACK, 0, extInfo);
                case 5:
                    return MethodHandles.insertArguments(STDLIB4_FALLBACK, 0, extInfo);
                case 6:
                    return MethodHandles.insertArguments(STDLIB5_FALLBACK, 0, extInfo);
                case 7:
                    return MethodHandles.insertArguments(STDLIB6_FALLBACK, 0, extInfo);
                case 8:
                    return MethodHandles.insertArguments(STDLIB7_FALLBACK, 0, extInfo);
                case 9:
                    return MethodHandles.insertArguments(STDLIB8_FALLBACK, 0, extInfo);
                default:
                    return null;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            return null;
        }
    }

    private static MethodHandle lookupStdlibExtensionMethodHandle(StdlibRegistry.ExtensionMethodInfo extInfo) {
        if (extInfo.isVarargs || extInfo.arity < 0 || extInfo.arity > 4) {
            return null;
        }
        try {
            Class<?> ownerClass = Class.forName(extInfo.jvmOwner.replace('/', '.'));
            MethodType rawType = stdlibRawType(extInfo.arity);
            return MethodHandles.publicLookup().findStatic(ownerClass, extInfo.jvmMethodName, rawType);
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodType stdlibRawType(int arity) {
        switch (arity) {
            case 0:
                return INSTANCE0_TYPE;
            case 1:
                return INSTANCE1_TYPE;
            case 2:
                return INSTANCE2_TYPE;
            case 3:
                return INSTANCE3_TYPE;
            case 4:
                return INSTANCE4_TYPE;
            case 5:
                return INSTANCE5_TYPE;
            case 6:
                return INSTANCE6_TYPE;
            case 7:
                return INSTANCE7_TYPE;
            case 8:
                return INSTANCE8_TYPE;
            default:
                throw new IllegalArgumentException("Unsupported stdlib extension arity: " + arity);
        }
    }

    private static Object invokeStdlib0(StdlibRegistry.ExtensionMethodInfo extInfo, Object target) {
        return extInfo.impl.apply(new Object[]{target});
    }

    private static Object invokeStdlib1(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0) {
        return extInfo.impl.apply(new Object[]{target, a0});
    }

    private static Object invokeStdlib2(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1) {
        return extInfo.impl.apply(new Object[]{target, a0, a1});
    }

    private static Object invokeStdlib3(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1, Object a2) {
        return extInfo.impl.apply(new Object[]{target, a0, a1, a2});
    }

    private static Object invokeStdlib4(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1, Object a2, Object a3) {
        return extInfo.impl.apply(new Object[]{target, a0, a1, a2, a3});
    }

    private static Object invokeStdlib5(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1, Object a2, Object a3, Object a4) {
        return extInfo.impl.apply(new Object[]{target, a0, a1, a2, a3, a4});
    }

    private static Object invokeStdlib6(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5) {
        return extInfo.impl.apply(new Object[]{target, a0, a1, a2, a3, a4, a5});
    }

    private static Object invokeStdlib7(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6) {
        return extInfo.impl.apply(new Object[]{target, a0, a1, a2, a3, a4, a5, a6});
    }

    private static Object invokeStdlib8(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object a0, Object a1, Object a2, Object a3, Object a4, Object a5, Object a6, Object a7) {
        return extInfo.impl.apply(new Object[]{target, a0, a1, a2, a3, a4, a5, a6, a7});
    }

    private static Object invokeStdlibGeneric(StdlibRegistry.ExtensionMethodInfo extInfo, Object target, Object[] args) {
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = target;
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        return extInfo.impl.apply(fullArgs);
    }

    private static MethodHandle findOwnStatic(String methodName, MethodType type) {
        try {
            return LOOKUP.findStatic(NovaDynamic.class, methodName, type);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        } catch (IllegalAccessException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static MethodHandle resolveStaticGetter(Class<?> cls, String memberName) {
        try {
            Field field = cls.getField(memberName);
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                return null;
            }
            return LOOKUP.unreflectGetter(field).asType(STATIC0_TYPE);
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodHandle resolveStaticMethodHandle(Class<?> cls, String methodName, Object[] args, MethodType type) {
        MethodHandle handle = resolveStaticMethod(cls, methodName, args);
        if (handle == null) {
            return null;
        }
        try {
            return handle.asType(type);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Bootstrap 公开 API（供 NovaBootstrap 调用） ----

    /**
     * 为给定类、方法名和参数解析实例方法 MethodHandle。
     * 按优先级尝试: 反射方法 → 方法别名 → stdlib 扩展 → script 扩展。
     * 返回的 MethodHandle 签名为 (Object, Object...) → Object。
     *
     * @return 解析到的 MethodHandle，解析失败返回 null
     */
    public static MethodHandle resolveForCallSite(Class<?> clazz, String methodName,
                                                   int arity, Object[] args) {
        MethodType type = instanceType(arity);

        // 1. 反射实例方法
        MethodHandle method = resolveMethodHandle(clazz, methodName, args, type);
        if (method != null) return method;

        // 2. 方法别名
        String aliased = resolveMethodAlias(methodName);
        if (aliased != null) {
            method = resolveMethodHandle(clazz, aliased, args, type);
            if (method != null) return method;
        }

        // 3. stdlib 扩展
        method = resolveStdlibExtensionHandle(clazz, methodName, arity, type);
        if (method != null) return method;

        return null;
    }

    /**
     * 返回 getter MethodHandle，签名 (Object) → Object。
     */
    public static MethodHandle resolveGetterForCallSite(Class<?> clazz, String memberName) {
        try {
            return resolveGetter(clazz, memberName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 返回 setter MethodHandle，签名 (Object, Object) → void。
     */
    public static MethodHandle resolveSetterForCallSite(Class<?> clazz, String memberName) {
        try {
            return resolveSetter(clazz, memberName);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 返回指定 arity 的实例方法 MethodType: (Object receiver, Object... args) → Object。
     */
    static MethodType instanceType(int arity) {
        switch (arity) {
            case 0: return INSTANCE0_TYPE;
            case 1: return INSTANCE1_TYPE;
            case 2: return INSTANCE2_TYPE;
            case 3: return INSTANCE3_TYPE;
            case 4: return INSTANCE4_TYPE;
            case 5: return INSTANCE5_TYPE;
            case 6: return INSTANCE6_TYPE;
            case 7: return INSTANCE7_TYPE;
            case 8: return INSTANCE8_TYPE;
            default:
                Class<?>[] params = new Class<?>[arity + 1];
                java.util.Arrays.fill(params, Object.class);
                return MethodType.methodType(Object.class, params);
        }
    }

    private static MethodHandle resolveMethodHandle(Class<?> clazz, String methodName, Object[] args, MethodType type) {
        MethodHandle handle = resolveMethod(clazz, methodName, args);
        if (handle == null) {
            return null;
        }
        try {
            return handle.asType(type);
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodHandle resolveStaticMethod(Class<?> cls, String methodName, Object[] args) {
        List<Method> matches = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            if (m.getName().equals(methodName)
                    && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                    && isArgsCompatible(m, args)) {
                matches.add(m);
            }
        }
        if (matches.isEmpty()) {
            for (Method m : cls.getMethods()) {
                if (m.getName().equals(methodName)
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())
                        && m.isVarArgs() && isVarArgsCompatible(m, args)) {
                    matches.add(m);
                }
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        Method best = matches.size() == 1 ? matches.get(0) : selectMostSpecific(matches);
        try {
            best.setAccessible(true);
            MethodHandle handle = LOOKUP.unreflect(best);
            if (best.isVarArgs()) {
                Class<?>[] paramTypes = best.getParameterTypes();
                handle = handle.asVarargsCollector(paramTypes[paramTypes.length - 1]);
            }
            return handle;
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodHandle resolveGetter(Class<?> clazz, String memberName) {
        try {
            Field field = clazz.getField(memberName);
            return LOOKUP.unreflectGetter(field).asType(GETTER_TYPE);
        } catch (Exception e) {
        }

        String getterName = "get" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        try {
            Method method = clazz.getMethod(getterName);
            return LOOKUP.unreflect(method).asType(GETTER_TYPE);
        } catch (Exception e) {
        }

        String isGetterName = "is" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        try {
            Method method = clazz.getMethod(isGetterName);
            return LOOKUP.unreflect(method).asType(GETTER_TYPE);
        } catch (Exception e) {
        }

        try {
            Method method = clazz.getMethod(memberName);
            return LOOKUP.unreflect(method).asType(GETTER_TYPE);
        } catch (Exception e) {
        }

        throw new RuntimeException("No member '" + memberName + "' found on " + clazz.getSimpleName()
                + availableMembers(clazz));
    }

    private static MethodHandle resolveSetter(Class<?> clazz, String memberName) {
        try {
            Field field = clazz.getField(memberName);
            return LOOKUP.unreflectSetter(field).asType(SETTER_TYPE);
        } catch (Exception e) {
        }

        String setterName = "set" + Character.toUpperCase(memberName.charAt(0)) + memberName.substring(1);
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                try {
                    return LOOKUP.unreflect(method).asType(SETTER_TYPE);
                } catch (Exception e) {
                }
            }
        }

        throw new RuntimeException("No settable member '" + memberName + "' found on " + clazz.getSimpleName()
                + availableMembers(clazz));
    }

    private static String availableMembers(Class<?> clazz) {
        java.util.TreeSet<String> members = new java.util.TreeSet<String>();
        for (Field f : clazz.getFields()) {
            if (!f.getDeclaringClass().equals(Object.class)) {
                members.add(f.getName());
            }
        }
        for (Method m : clazz.getMethods()) {
            if (m.getDeclaringClass().equals(Object.class)) continue;
            String name = m.getName();
            if (m.getParameterCount() == 0) {
                if (name.startsWith("get") && name.length() > 3) {
                    members.add(Character.toLowerCase(name.charAt(3)) + name.substring(4));
                } else if (name.startsWith("is") && name.length() > 2) {
                    members.add(Character.toLowerCase(name.charAt(2)) + name.substring(3));
                }
            }
            members.add(name + "(" + m.getParameterCount() + ")");
        }
        if (members.isEmpty()) return "";
        List<String> list = new ArrayList<String>(members);
        if (list.size() > 10) {
            return ". Available: " + String.join(", ", list.subList(0, 10)) + " ... (" + list.size() + " total)";
        }
        return ". Available: " + String.join(", ", list);
    }

    private static MethodHandle resolveMethod(Class<?> clazz, String methodName, Object[] args) {
        List<Method> matches = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(methodName) && isArgsCompatible(m, args)) {
                matches.add(m);
            }
        }
        if (matches.isEmpty()) {
            for (Method m : clazz.getMethods()) {
                if (m.getName().equals(methodName) && m.isVarArgs() && isVarArgsCompatible(m, args)) {
                    matches.add(m);
                }
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        Method best = matches.size() == 1 ? matches.get(0) : selectMostSpecific(matches);
        try {
            best.setAccessible(true);
            MethodHandle handle = LOOKUP.unreflect(best);
            if (best.isVarArgs()) {
                Class<?>[] paramTypes = best.getParameterTypes();
                handle = handle.asVarargsCollector(paramTypes[paramTypes.length - 1]);
            }
            return handle;
        } catch (Exception e) {
            return null;
        }
    }

    private static Method selectMostSpecific(List<Method> methods) {
        Method best = methods.get(0);
        for (int i = 1; i < methods.size(); i++) {
            if (isMoreSpecific(methods.get(i), best)) {
                best = methods.get(i);
            }
        }
        return best;
    }

    private static boolean isMoreSpecific(Method a, Method b) {
        if (!a.isVarArgs() && b.isVarArgs()) {
            return true;
        }
        if (a.isVarArgs() && !b.isVarArgs()) {
            return false;
        }
        Class<?>[] aParams = a.getParameterTypes();
        Class<?>[] bParams = b.getParameterTypes();
        int len = Math.min(aParams.length, bParams.length);
        for (int i = 0; i < len; i++) {
            if (isAssignable(bParams[i], aParams[i]) && !aParams[i].equals(bParams[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean isVarArgsCompatible(Method method, Object[] args) {
        Class<?>[] paramTypes = method.getParameterTypes();
        int fixedCount = paramTypes.length - 1;
        if (args.length < fixedCount) {
            return false;
        }
        for (int i = 0; i < fixedCount; i++) {
            if (args[i] != null && !isAssignable(paramTypes[i], args[i].getClass())) {
                return false;
            }
        }
        if (args.length == paramTypes.length && args[fixedCount] != null
                && paramTypes[fixedCount].isInstance(args[fixedCount])) {
            return true;
        }
        Class<?> componentType = paramTypes[fixedCount].getComponentType();
        for (int i = fixedCount; i < args.length; i++) {
            if (args[i] != null && !isAssignable(componentType, args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static String cacheKey(String methodName, Object[] args) {
        if (args.length == 0) {
            return methodName + "#0";
        }
        StringBuilder sb = new StringBuilder(methodName).append('#').append(args.length);
        for (Object arg : args) {
            sb.append(':').append(arg != null ? arg.getClass().getName() : "null");
        }
        return sb.toString();
    }

    private static Object[] buildArgs(Object target, Object[] args) {
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = target;
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        return fullArgs;
    }

    private static String resolveMethodAlias(String name) {
        switch (name) {
            case "uppercase":
                return "toUpperCase";
            case "lowercase":
                return "toLowerCase";
            case "contains":
                return null;
            default:
                return null;
        }
    }

    private static boolean isArgsCompatible(Method method, Object[] args) {
        if (method.isVarArgs()) {
            return false;
        }
        Class<?>[] paramTypes = method.getParameterTypes();
        if (paramTypes.length != args.length) {
            return false;
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] != null && !isAssignable(paramTypes[i], args[i].getClass())) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> argClass(Object arg) {
        return arg != null ? arg.getClass() : null;
    }

    private static boolean isAssignable(Class<?> target, Class<?> source) {
        if (source == null) {
            return !target.isPrimitive();
        }
        if (target.isAssignableFrom(source)) {
            return true;
        }
        if (target == Object.class) {
            return true;
        }
        if (target == int.class) {
            return source == Integer.class;
        }
        if (target == long.class || target == Long.class) {
            return source == Long.class || source == long.class
                || source == int.class || source == Integer.class;
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
                || source == long.class || source == Long.class;
        }
        if (target == boolean.class) {
            return source == Boolean.class;
        }
        if (target == char.class) {
            return source == Character.class;
        }
        if (target == byte.class) {
            return source == Byte.class;
        }
        if (target == short.class) {
            return source == Short.class;
        }
        if (target == Integer.class) {
            return source == int.class;
        }
        if (target == Boolean.class) {
            return source == boolean.class;
        }
        if (target == Character.class) {
            return source == char.class;
        }
        return false;
    }

    private static final class MethodDispatchCache {
        private final ConcurrentHashMap<String, MethodHandle> zeroArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> oneArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> twoArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> threeArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> fourArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> fiveArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> sixArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> sevenArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> eightArg = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, MethodHandle> generic = new ConcurrentHashMap<>();

        private final ConcurrentHashMap<String, Boolean> zeroArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> oneArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> twoArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> threeArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> fourArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> fiveArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> sixArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> sevenArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> eightArgMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> genericMiss = new ConcurrentHashMap<>();

        private final ConcurrentHashMap<String, MethodHandle> zeroArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> oneArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> twoArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> threeArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> fourArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> fiveArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> sixArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> sevenArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, MethodHandle> eightArgStdlib = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, StdlibRegistry.ExtensionMethodInfo> genericStdlib = new ConcurrentHashMap<>();

        private final ConcurrentHashMap<String, Boolean> zeroArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> oneArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> twoArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> threeArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> fourArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> fiveArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> sixArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> sevenArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<MethodKey, Boolean> eightArgStdlibMiss = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Boolean> genericStdlibMiss = new ConcurrentHashMap<>();
    }

    private static final class MethodKey {
        private String methodName;
        private Class<?> arg0;
        private Class<?> arg1;
        private Class<?> arg2;
        private Class<?> arg3;
        private Class<?> arg4;
        private Class<?> arg5;
        private Class<?> arg6;
        private Class<?> arg7;
        private int arity;
        private int hash;

        private MethodKey init(String methodName, Class<?> arg0, Class<?> arg1, Class<?> arg2, Class<?> arg3,
                               Class<?> arg4, Class<?> arg5, int arity) {
            return init(methodName, arg0, arg1, arg2, arg3, arg4, arg5, null, null, arity);
        }

        private MethodKey init(String methodName, Class<?> arg0, Class<?> arg1, Class<?> arg2, Class<?> arg3,
                               Class<?> arg4, Class<?> arg5, Class<?> arg6, Class<?> arg7, int arity) {
            this.methodName = methodName;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.arg2 = arg2;
            this.arg3 = arg3;
            this.arg4 = arg4;
            this.arg5 = arg5;
            this.arg6 = arg6;
            this.arg7 = arg7;
            this.arity = arity;
            int result = methodName.hashCode();
            result = 31 * result + arity;
            result = 31 * result + (arg0 != null ? arg0.hashCode() : 0);
            result = 31 * result + (arg1 != null ? arg1.hashCode() : 0);
            result = 31 * result + (arg2 != null ? arg2.hashCode() : 0);
            result = 31 * result + (arg3 != null ? arg3.hashCode() : 0);
            result = 31 * result + (arg4 != null ? arg4.hashCode() : 0);
            result = 31 * result + (arg5 != null ? arg5.hashCode() : 0);
            result = 31 * result + (arg6 != null ? arg6.hashCode() : 0);
            result = 31 * result + (arg7 != null ? arg7.hashCode() : 0);
            this.hash = result;
            return this;
        }

        private static MethodKey copyOf(MethodKey source) {
            return new MethodKey().init(source.methodName, source.arg0, source.arg1, source.arg2, source.arg3, source.arg4, source.arg5, source.arg6, source.arg7, source.arity);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MethodKey)) {
                return false;
            }
            MethodKey other = (MethodKey) obj;
            return arity == other.arity
                    && methodName.equals(other.methodName)
                    && arg0 == other.arg0
                    && arg1 == other.arg1
                    && arg2 == other.arg2
                    && arg3 == other.arg3
                    && arg4 == other.arg4
                    && arg5 == other.arg5
                    && arg6 == other.arg6
                    && arg7 == other.arg7;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }
}
