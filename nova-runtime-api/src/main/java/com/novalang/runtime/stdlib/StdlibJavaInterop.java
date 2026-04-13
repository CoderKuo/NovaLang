package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaErrors;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaException.ErrorKind;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Java 互操作增强内置函数。
 *
 * <p>提供反射查询、类型判断、集合互转等功能，
 * 所有方法签名统一为 (Object...) → Object 以兼容 StdlibRegistry。</p>
 */
public final class StdlibJavaInterop {

    private StdlibJavaInterop() {}

    private static final String OWNER = "com/novalang/runtime/stdlib/StdlibJavaInterop";
    private static final String O_O = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OO_O = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        // 反射查询
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "javaFields", 1, OWNER, "javaFields", O_O, args -> javaFields(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "javaMethods", 1, OWNER, "javaMethods", O_O, args -> javaMethods(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "javaSuperclass", 1, OWNER, "javaSuperclass", O_O, args -> javaSuperclass(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "javaInterfaces", 1, OWNER, "javaInterfaces", O_O, args -> javaInterfaces(args[0])));

        // 类型判断
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "javaInstanceOf", 2, OWNER, "javaInstanceOf", OO_O, args -> javaInstanceOf(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "javaTypeName", 1, OWNER, "javaTypeName", O_O, args -> javaTypeName(args[0])));

        // 集合互转
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toJavaList", 1, OWNER, "toJavaList", O_O, args -> toJavaList(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toJavaMap", 1, OWNER, "toJavaMap", O_O, args -> toJavaMap(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toJavaSet", 1, OWNER, "toJavaSet", O_O, args -> toJavaSet(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toJavaArray", 1, OWNER, "toJavaArray", O_O, args -> toJavaArray(args[0])));
    }

    // ============ 反射查询 ============

    /** 返回对象/类的所有 public 字段名列表 */
    public static Object javaFields(Object objOrClass) {
        Class<?> cls = resolveClass(objOrClass);
        List<String> names = new ArrayList<>();
        for (Field f : cls.getFields()) {
            names.add(f.getName());
        }
        return names;
    }

    /** 返回对象/类的所有 public 方法名列表（去重） */
    public static Object javaMethods(Object objOrClass) {
        Class<?> cls = resolveClass(objOrClass);
        Set<String> seen = new LinkedHashSet<>();
        for (Method m : cls.getMethods()) {
            if (Modifier.isPublic(m.getModifiers())) {
                seen.add(m.getName());
            }
        }
        return new ArrayList<>(seen);
    }

    /** 返回父类全限定名，无父类返回 null */
    public static Object javaSuperclass(Object objOrClass) {
        Class<?> cls = resolveClass(objOrClass);
        Class<?> sup = cls.getSuperclass();
        return sup != null ? sup.getName() : null;
    }

    /** 返回实现的接口名列表 */
    public static Object javaInterfaces(Object objOrClass) {
        Class<?> cls = resolveClass(objOrClass);
        List<String> names = new ArrayList<>();
        for (Class<?> iface : cls.getInterfaces()) {
            names.add(iface.getName());
        }
        return names;
    }

    // ============ 类型判断 ============

    /** 检查 obj 是否是 className 的实例 */
    public static Object javaInstanceOf(Object obj, Object className) {
        if (obj == null) return false;
        try {
            Class<?> cls = Class.forName(String.valueOf(className));
            return cls.isInstance(obj);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** 返回对象的 Java 类全限定名 */
    public static Object javaTypeName(Object obj) {
        if (obj == null) return "null";
        return obj.getClass().getName();
    }

    // ============ 集合互转 ============

    /** Nova List/Collection → java.util.ArrayList */
    public static Object toJavaList(Object obj) {
        if (obj instanceof List) return new ArrayList<>((List<?>) obj);
        if (obj instanceof Collection) return new ArrayList<>((Collection<?>) obj);
        if (obj instanceof Iterable) {
            List<Object> list = new ArrayList<>();
            for (Object item : (Iterable<?>) obj) list.add(item);
            return list;
        }
        if (obj != null && obj.getClass().isArray()) {
            return new ArrayList<>(Arrays.asList((Object[]) obj));
        }
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (obj == null ? "null" : obj.getClass().getName()) + " 转换为 List",
                "参数需要是 Collection 或数组类型");
    }

    /** Nova Map → java.util.HashMap */
    public static Object toJavaMap(Object obj) {
        if (obj instanceof Map) return new HashMap<>((Map<?, ?>) obj);
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (obj == null ? "null" : obj.getClass().getName()) + " 转换为 Map",
                "参数需要是 Map 类型");
    }

    /** Nova Set/Collection → java.util.HashSet */
    public static Object toJavaSet(Object obj) {
        if (obj instanceof Set) return new HashSet<>((Set<?>) obj);
        if (obj instanceof Collection) return new HashSet<>((Collection<?>) obj);
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (obj == null ? "null" : obj.getClass().getName()) + " 转换为 Set",
                "参数需要是 Collection 类型");
    }

    /** Nova List → Object[] */
    public static Object toJavaArray(Object obj) {
        if (obj instanceof List) return ((List<?>) obj).toArray();
        if (obj instanceof Collection) return ((Collection<?>) obj).toArray();
        if (obj != null && obj.getClass().isArray()) return obj;
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (obj == null ? "null" : obj.getClass().getName()) + " 转换为 Array",
                "参数需要是 Collection 或数组类型");
    }

    // ============ 辅助 ============

    /**
     * 解析对象或类：
     * - Class 实例 → 直接返回
     * - 字符串值 → 先尝试 Class.forName，失败则返回 String.class（即把字符串当对象处理）
     * - 其他对象 → 返回其 getClass()
     */
    private static Class<?> resolveClass(Object objOrClass) {
        if (objOrClass instanceof Class) return (Class<?>) objOrClass;
        if (objOrClass == null) throw new NovaException(ErrorKind.NULL_REFERENCE, "无法从 null 解析类");
        if (objOrClass instanceof String) {
            String s = (String) objOrClass;
            // 含点号的视为全限定类名（java.lang.String），否则视为普通字符串值
            if (s.contains(".")) {
                try { return Class.forName(s); }
                catch (ClassNotFoundException e) {
                    throw NovaErrors.javaClassNotFound(s, e);
                }
            }
        }
        return objOrClass.getClass();
    }
}
