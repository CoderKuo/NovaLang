package nova.runtime.stdlib;

import java.util.List;
import java.util.Map;

import nova.runtime.NovaFunction;
import nova.runtime.NovaValue;

/**
 * 类型检查相关的 stdlib 函数：isNull / isNumber / isString / isList / isMap。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 */
public final class StdlibTypeChecks {

    private StdlibTypeChecks() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibTypeChecks";
    private static final String O_O = "(Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "isNull", 1, OWNER, "isNull", O_O, args -> isNull(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "isNumber", 1, OWNER, "isNumber", O_O, args -> isNumber(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "isString", 1, OWNER, "isString", O_O, args -> isString(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "isList", 1, OWNER, "isList", O_O, args -> isList(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "isMap", 1, OWNER, "isMap", O_O, args -> isMap(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "len", 1, OWNER, "len", O_O, args -> len(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "typeof", 1, OWNER, "typeof", O_O, args -> typeof(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "isCallable", 1, OWNER, "isCallable", O_O, args -> isCallable(args[0])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    @NovaFunction(signature = "isNull(value)", description = "检查值是否为 null", returnType = "Boolean")
    public static Object isNull(Object value) {
        return value == null;
    }

    @NovaFunction(signature = "isNumber(value)", description = "检查值是否为数值类型", returnType = "Boolean")
    public static Object isNumber(Object value) {
        return value instanceof Number;
    }

    @NovaFunction(signature = "isString(value)", description = "检查值是否为字符串", returnType = "Boolean")
    public static Object isString(Object value) {
        return value instanceof String;
    }

    @NovaFunction(signature = "isList(value)", description = "检查值是否为列表", returnType = "Boolean")
    public static Object isList(Object value) {
        return value instanceof List;
    }

    @NovaFunction(signature = "isMap(value)", description = "检查值是否为 Map", returnType = "Boolean")
    public static Object isMap(Object value) {
        return value instanceof Map;
    }

    @NovaFunction(signature = "len(value)", description = "返回字符串、列表或 Map 的长度", returnType = "Int")
    public static Object len(Object value) {
        if (value instanceof String) return ((String) value).length();
        if (value instanceof List) return ((List<?>) value).size();
        if (value instanceof Map) return ((Map<?, ?>) value).size();
        if (value != null && value.getClass().isArray()) return java.lang.reflect.Array.getLength(value);
        throw new IllegalArgumentException("len() requires string, list, or map, got: " + value);
    }

    @NovaFunction(signature = "typeof(value)", description = "获取值的类型名", returnType = "String")
    public static Object typeof(Object value) {
        return NovaValue.typeNameOf(value);
    }

    @NovaFunction(signature = "isCallable(value)", description = "检查值是否可调用", returnType = "Boolean")
    public static Object isCallable(Object value) {
        if (value instanceof NovaValue) return ((NovaValue) value).isCallable();
        // 编译路径：lambda 实现为匿名类（有 invoke 方法）
        try {
            value.getClass().getMethod("invoke");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
