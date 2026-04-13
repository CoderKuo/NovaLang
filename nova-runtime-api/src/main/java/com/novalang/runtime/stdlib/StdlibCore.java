package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaArray;
import com.novalang.runtime.NovaErrors;
import com.novalang.runtime.NovaException;
import com.novalang.runtime.NovaException.ErrorKind;
import com.novalang.runtime.NovaPair;
import com.novalang.runtime.NovaResult;
import com.novalang.runtime.NovaRuntime;
import com.novalang.runtime.NovaValue;
import com.novalang.runtime.stdlib.spi.SerializationProviders;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.*;

/**
 * 核心内置函数的 stdlib 实现：error / Pair / range / with / repeat / runCatching 等。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 *
 * <p>Lambda 参数通过反射调用 {@code invoke()} 方法（与 ConcurrencyHelper 同模式）。</p>
 */
public final class StdlibCore {

    private StdlibCore() {}

    private static final String OWNER = "com/novalang/runtime/stdlib/StdlibCore";
    private static final String O_O = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OO_O = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String VARARG_DESC = "([Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        // ---- 固定 arity ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "error", 1, OWNER, "error", O_O, args -> error(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "Pair", 2, OWNER, "pair", OO_O, args -> pair(args[0], args[1]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "range", 2, OWNER, "range", OO_O, args -> range(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "rangeClosed", 2, OWNER, "rangeClosed", OO_O, args -> rangeClosed(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "with", 2, OWNER, "withScope", OO_O, args -> withScope(args[0], args[1]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "repeat", 2, OWNER, "repeat", OO_O, args -> repeat(args[0], args[1]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "List", 2, OWNER, "listInit", OO_O, args -> listInit(args[0], args[1]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "measureTimeMillis", 1, OWNER, "measureTimeMillis", O_O, args -> measureTimeMillis(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "measureNanoTime", 1, OWNER, "measureNanoTime", O_O, args -> measureNanoTime(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "runCatching", 1, OWNER, "runCatching", O_O, args -> runCatching(args[0]), true));

        // ---- 类型化数组构造器 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "IntArray", 1, OWNER, "intArray", O_O, args -> intArray(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "LongArray", 1, OWNER, "longArray", O_O, args -> longArray(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "DoubleArray", 1, OWNER, "doubleArray", O_O, args -> doubleArray(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "FloatArray", 1, OWNER, "floatArray", O_O, args -> floatArray(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "BooleanArray", 1, OWNER, "booleanArray", O_O, args -> booleanArray(args[0]), true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "CharArray", 1, OWNER, "charArray", O_O, args -> charArray(args[0]), true));

        // ---- 类型转换函数（rawNovaArgs：接受 NovaValue 参数） ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toInt", 1, OWNER, "toInt", O_O, args -> toInt(args[0]), false, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toLong", 1, OWNER, "toLong", O_O, args -> toLong(args[0]), false, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toDouble", 1, OWNER, "toDouble", O_O, args -> toDouble(args[0]), false, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toFloat", 1, OWNER, "toFloat", O_O, args -> toFloat(args[0]), false, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toString", 1, OWNER, "toStr", O_O, args -> toStr(args[0]), false, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toBoolean", 1, OWNER, "toBoolean", O_O, args -> toBoolean(args[0]), false, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "toChar", 1, OWNER, "toChar", O_O, args -> toChar(args[0]), false, true));

        // ---- vararg ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "arrayOf", -1, OWNER, "arrayOf", VARARG_DESC, StdlibCore::arrayOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "readLine", -1, OWNER, "readLine", VARARG_DESC, StdlibCore::readLine, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "input", -1, OWNER, "input", VARARG_DESC, StdlibCore::input, true));

        // ---- 序列化 provider 查询/切换 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "jsonProvider", -1, OWNER, "jsonProvider", VARARG_DESC, StdlibCore::jsonProviderFn));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "yamlProvider", -1, OWNER, "yamlProvider", VARARG_DESC, StdlibCore::yamlProviderFn));

        // ---- Hutool 启发：集合/字符串工具 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "similar", 2, OWNER, "similar", OO_O, args -> similar(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "disjunction", 2, OWNER, "disjunction", OO_O, args -> disjunction(args[0], args[1])));

        // ---- shared 查询 ----
        String V_O = "()Ljava/lang/Object;";
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedLibraries", 0, OWNER, "sharedLibraries", V_O, args -> sharedLibraries()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedFunctions", -1, OWNER, "sharedFunctions", VARARG_DESC, StdlibCore::sharedFunctions));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedDescribe", 1, OWNER, "sharedDescribe", O_O, args -> sharedDescribe(args[0])));

        // ---- shared 注册（脚本间共享） ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedRegister", -1, OWNER, "sharedRegister", VARARG_DESC, StdlibCore::sharedRegister, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedSet", -1, OWNER, "sharedSet", VARARG_DESC, StdlibCore::sharedSet));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedRemove", -1, OWNER, "sharedRemove", VARARG_DESC, StdlibCore::sharedRemove));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedHas", -1, OWNER, "sharedHas", VARARG_DESC, StdlibCore::sharedHas));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "sharedGet", -1, OWNER, "sharedGet", VARARG_DESC, StdlibCore::sharedGet));

        // ---- stdlib 模块查询 ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "stdlibModules", 0, OWNER, "stdlibModules", V_O, args -> stdlibModules()));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "stdlibFunctions", 1, OWNER, "stdlibFunctions", O_O, args -> stdlibFunctions(args[0])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    public static Object error(Object message) {
        throw new NovaException(String.valueOf(message));
    }

    public static Object pair(Object first, Object second) {
        return NovaPair.of(first, second);
    }

    public static Object range(Object start, Object end) {
        int s = ((Number) start).intValue();
        int e = ((Number) end).intValue();
        List<Object> list = new ArrayList<>();
        if (s <= e) {
            for (int i = s; i < e; i++) list.add(i);
        } else {
            for (int i = s; i > e; i--) list.add(i);
        }
        return list;
    }

    public static Object rangeClosed(Object start, Object end) {
        int s = ((Number) start).intValue();
        int e = ((Number) end).intValue();
        List<Object> list = new ArrayList<>();
        if (s <= e) {
            for (int i = s; i <= e; i++) list.add(i);
        } else {
            for (int i = s; i >= e; i--) list.add(i);
        }
        return list;
    }

    public static Object intArray(Object size) { return new NovaArray(NovaArray.ElementType.INT, ((Number) size).intValue()); }
    public static Object longArray(Object size) { return new NovaArray(NovaArray.ElementType.LONG, ((Number) size).intValue()); }
    public static Object doubleArray(Object size) { return new NovaArray(NovaArray.ElementType.DOUBLE, ((Number) size).intValue()); }
    public static Object floatArray(Object size) { return new NovaArray(NovaArray.ElementType.FLOAT, ((Number) size).intValue()); }
    public static Object booleanArray(Object size) { return new NovaArray(NovaArray.ElementType.BOOLEAN, ((Number) size).intValue()); }
    public static Object charArray(Object size) { return new NovaArray(NovaArray.ElementType.CHAR, ((Number) size).intValue()); }

    // ---- 类型转换（rawNovaArgs：接受 NovaValue 或 raw Object） ----

    public static Object toInt(Object value) {
        if (value instanceof NovaValue) {
            NovaValue nv = (NovaValue) value;
            if (nv.isNumber()) return nv.asInt();
            if (nv.isString()) return Integer.parseInt(nv.asString().trim());
        }
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) return Integer.parseInt(((String) value).trim());
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (value == null ? "null" : value.getClass().getSimpleName()) + " 转换为 Int",
                "使用 toInt() 进行转换");
    }

    public static Object toLong(Object value) {
        if (value instanceof NovaValue) {
            NovaValue nv = (NovaValue) value;
            if (nv.isNumber()) return nv.asLong();
            if (nv.isString()) return Long.parseLong(nv.asString().trim());
        }
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong(((String) value).trim());
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (value == null ? "null" : value.getClass().getSimpleName()) + " 转换为 Long",
                "使用 toLong() 进行转换");
    }

    public static Object toDouble(Object value) {
        if (value instanceof NovaValue) {
            NovaValue nv = (NovaValue) value;
            if (nv.isNumber()) return nv.asDouble();
            if (nv.isString()) return Double.parseDouble(nv.asString().trim());
        }
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String) return Double.parseDouble(((String) value).trim());
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (value == null ? "null" : value.getClass().getSimpleName()) + " 转换为 Double",
                "使用 toDouble() 进行转换");
    }

    public static Object toFloat(Object value) {
        if (value instanceof NovaValue) {
            NovaValue nv = (NovaValue) value;
            if (nv.isNumber()) return (float) nv.asDouble();
            if (nv.isString()) return Float.parseFloat(nv.asString().trim());
        }
        if (value instanceof Number) return ((Number) value).floatValue();
        if (value instanceof String) return Float.parseFloat(((String) value).trim());
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (value == null ? "null" : value.getClass().getSimpleName()) + " 转换为 Float",
                "使用 toFloat() 进行转换");
    }

    public static Object toStr(Object value) {
        if (value instanceof NovaValue) return ((NovaValue) value).asString();
        return String.valueOf(value);
    }

    public static Object toBoolean(Object value) {
        if (value instanceof NovaValue) return ((NovaValue) value).isTruthy();
        if (value instanceof Boolean) return value;
        return value != null;
    }

    public static Object toChar(Object value) {
        if (value instanceof NovaValue) {
            NovaValue nv = (NovaValue) value;
            if (nv.isInteger()) return (char) nv.asInt();
            if (nv.isString()) {
                String s = nv.asString();
                if (s.length() == 1) return s.charAt(0);
            }
        }
        if (value instanceof Character) return value;
        if (value instanceof Number) return (char) ((Number) value).intValue();
        if (value instanceof String && ((String) value).length() == 1) return ((String) value).charAt(0);
        throw new NovaException(ErrorKind.TYPE_MISMATCH,
                "无法将 " + (value == null ? "null" : value.getClass().getSimpleName()) + " 转换为 Char",
                "使用 toChar() 进行转换");
    }

    public static Object arrayOf(Object... args) {
        return new NovaArray(NovaArray.ElementType.OBJECT, args.clone(), args.length);
    }

    public static Object readLine(Object... args) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        } catch (Exception e) {
            throw NovaErrors.wrap("读取输入失败", e);
        }
    }

    public static Object input(Object... args) {
        if (args.length > 0) System.out.print(args[0]);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        } catch (Exception e) {
            throw NovaErrors.wrap("读取输入失败", e);
        }
    }

    // ---- Lambda 接受函数 ----

    public static Object withScope(Object receiver, Object block) {
        Object prev = NovaScopeFunctions.getScopeReceiver();
        NovaScopeFunctions.setScopeReceiver(receiver);
        try {
            return LambdaUtils.invokeFlexible(block, receiver);
        } finally {
            NovaScopeFunctions.setScopeReceiver(prev);
        }
    }

    public static Object repeat(Object times, Object action) {
        int n = ((Number) times).intValue();
        // lambda 可以是 0 参数 { body } 或 1 参数 { index -> body }
        boolean hasParam = LambdaUtils.hasInvoke1(action);
        for (int i = 0; i < n; i++) {
            if (hasParam) {
                LambdaUtils.invoke1(action, i);
            } else {
                LambdaUtils.invoke0(action);
            }
        }
        return null;
    }

    public static Object measureTimeMillis(Object block) {
        long start = System.currentTimeMillis();
        LambdaUtils.invoke0(block);
        return System.currentTimeMillis() - start;
    }

    public static Object measureNanoTime(Object block) {
        long start = System.nanoTime();
        LambdaUtils.invoke0(block);
        return System.nanoTime() - start;
    }

    public static Object runCatching(Object block) {
        try {
            Object result = LambdaUtils.invoke0(block);
            return NovaResult.ok(result);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return NovaResult.err(msg);
        }
    }

    public static Object listInit(Object size, Object init) {
        int n = ((Number) size).intValue();
        List<Object> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            result.add(LambdaUtils.invoke1(init, i));
        }
        return result;
    }

    // ============ shared 查询 ============

    /** 返回 NovaRuntime.shared() 中所有命名空间（库）列表 */
    public static Object sharedLibraries() {
        return NovaRuntime.shared().listNamespaces();
    }

    /** 无参: 全部函数名；1参: 指定命名空间的函数名 */
    public static Object sharedFunctions(Object[] args) {
        List<NovaRuntime.RegisteredEntry> entries;
        if (args.length == 0) {
            entries = NovaRuntime.shared().listFunctions();
        } else {
            entries = NovaRuntime.shared().listFunctions(String.valueOf(args[0]));
        }
        List<String> names = new ArrayList<>(entries.size());
        for (NovaRuntime.RegisteredEntry e : entries) {
            names.add(e.getQualifiedName());
        }
        return names;
    }

    /** 返回函数签名描述 */
    public static Object sharedDescribe(Object name) {
        String desc = NovaRuntime.shared().describe(String.valueOf(name));
        return desc != null ? desc : "unknown function: " + name;
    }

    // ============ stdlib 模块查询 ============

    /** 返回标准库模块名列表（全局函数模块 + import 模块） */
    public static Object stdlibModules() {
        Set<String> modules = new LinkedHashSet<>();
        // 全局函数按 jvmOwner 分组
        for (StdlibRegistry.NativeFunctionInfo nf : StdlibRegistry.getNativeFunctions()) {
            modules.add(categoryFromOwner(nf.jvmOwner));
        }
        // import 模块（nova.time / nova.io 等）
        for (String m : IMPORT_MODULES) modules.add(m);
        return new ArrayList<>(modules);
    }

    private static final String[] IMPORT_MODULES = {
        "nova.time", "nova.io", "nova.json", "nova.text",
        "nova.http", "nova.system", "nova.test", "nova.concurrent",
        "nova.yaml", "nova.encoding", "nova.crypto"
    };

    /** 返回指定模块的函数名列表 */
    public static Object stdlibFunctions(Object moduleName) {
        String mod = String.valueOf(moduleName);
        List<String> fns = new ArrayList<>();
        for (StdlibRegistry.NativeFunctionInfo nf : StdlibRegistry.getNativeFunctions()) {
            if (mod.equals(categoryFromOwner(nf.jvmOwner))) {
                fns.add(nf.name);
            }
        }
        return fns;
    }

    /** 从 jvmOwner 提取模块短名 */
    private static String categoryFromOwner(String jvmOwner) {
        if (jvmOwner == null) return "unknown";
        int lastSlash = jvmOwner.lastIndexOf('/');
        String className = lastSlash >= 0 ? jvmOwner.substring(lastSlash + 1) : jvmOwner;
        if (className.startsWith("Stdlib")) className = className.substring(6);
        return className.substring(0, 1).toLowerCase() + className.substring(1);
    }

    // ============ shared 注册（脚本间共享） ============

    /**
     * 注册函数到 shared：
     * <ul>
     *   <li>{@code sharedRegister("name", lambda)} — 全局注册</li>
     *   <li>{@code sharedRegister("name", lambda, "namespace")} — 带命名空间</li>
     * </ul>
     * rawNovaArgs = true，接收 NovaValue 参数（lambda 保持原样）。
     */
    public static Object sharedRegister(Object[] args) {
        if (args.length < 2) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH,
                "sharedRegister 需要至少 2 个参数 (name, function)，但传入了 " + args.length + " 个");
        String name = String.valueOf(args[0] instanceof NovaValue ? ((NovaValue) args[0]).toJavaValue() : args[0]);
        Object func = args[1] instanceof NovaValue ? args[1] : args[1];
        String namespace = args.length >= 3
                ? String.valueOf(args[2] instanceof NovaValue ? ((NovaValue) args[2]).toJavaValue() : args[2])
                : null;

        // 包装 Nova lambda 为 vararg function
        final Object captured = func;
        com.novalang.runtime.Function1<Object[], Object> wrapper = javaArgs -> {
            switch (javaArgs.length) {
                case 0: return LambdaUtils.invoke0(captured);
                case 1: return LambdaUtils.invoke1(captured, javaArgs[0]);
                case 2: return LambdaUtils.invoke2(captured, javaArgs[0], javaArgs[1]);
                default:
                    return LambdaUtils.invokeN(captured, javaArgs.length, javaArgs);
            }
        };

        if (namespace != null) {
            NovaRuntime.shared().registerVararg(name, wrapper, namespace);
        } else {
            NovaRuntime.shared().registerVararg(name, wrapper);
        }
        return null;
    }

    /**
     * 设置值到 shared：
     * <ul>
     *   <li>{@code sharedSet("name", value)} — 全局设置</li>
     *   <li>{@code sharedSet("name", value, "namespace")} — 带命名空间</li>
     * </ul>
     */
    public static Object sharedSet(Object[] args) {
        if (args.length < 2) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH,
                "sharedSet 需要至少 2 个参数 (name, value)，但传入了 " + args.length + " 个");
        String name = String.valueOf(args[0]);
        Object value = args[1];
        if (args.length >= 3) {
            NovaRuntime.shared().set(name, value, String.valueOf(args[2]));
        } else {
            NovaRuntime.shared().set(name, value);
        }
        return null;
    }

    /**
     * 从 shared 注销：
     * <ul>
     *   <li>{@code sharedRemove("name")} — 注销全局函数</li>
     *   <li>{@code sharedRemove("namespace")} — 注销整个命名空间</li>
     * </ul>
     */
    /**
     * 从 shared 移除：
     * <ul>
     *   <li>{@code sharedRemove("name")} — 移除全局函数/变量</li>
     *   <li>{@code sharedRemove("namespace", "name")} — 移除命名空间中单个函数/变量</li>
     * </ul>
     */
    public static Object sharedRemove(Object[] args) {
        if (args.length < 1) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH,
                "sharedRemove 需要至少 1 个参数");
        String first = String.valueOf(args[0]);
        if (args.length >= 2) {
            // sharedRemove("namespace", "funcName") — 移除命名空间中单个函数
            String funcName = String.valueOf(args[1]);
            NovaRuntime.shared().remove(first, funcName);
        } else {
            // sharedRemove("name") — 移除全局函数 + 注销同名命名空间
            NovaRuntime.shared().remove(first);
            NovaRuntime.shared().unregisterNamespace(first);
        }
        return null;
    }

    /**
     * 检查 shared 中是否存在：
     * <ul>
     *   <li>{@code sharedHas("name")} — 检查全局函数/变量是否存在</li>
     *   <li>{@code sharedHas("namespace", "name")} — 检查命名空间中是否存在</li>
     * </ul>
     */
    public static Object sharedHas(Object[] args) {
        if (args.length < 1) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH,
                "sharedHas 需要至少 1 个参数");
        String first = String.valueOf(args[0]);
        if (args.length >= 2) {
            return NovaRuntime.shared().has(first, String.valueOf(args[1]));
        } else {
            return NovaRuntime.shared().has(first);
        }
    }

    /**
     * 读取 shared 中的值：
     * <ul>
     *   <li>{@code sharedGet("name")} — 读取全局函数/变量</li>
     *   <li>{@code sharedGet("namespace", "name")} — 读取命名空间中的函数/变量</li>
     * </ul>
     */
    public static Object sharedGet(Object[] args) {
        if (args.length < 1) throw new NovaException(ErrorKind.ARGUMENT_MISMATCH,
                "sharedGet 需要至少 1 个参数");
        String first = String.valueOf(args[0]);
        NovaRuntime.RegisteredEntry entry;
        if (args.length >= 2) {
            entry = NovaRuntime.shared().lookup(first, String.valueOf(args[1]));
        } else {
            entry = NovaRuntime.shared().lookup(first);
        }
        if (entry == null) return null;
        return entry.isFunction() ? entry.getFunction() : entry.getValue();
    }

    // ============ Hutool 启发：全局工具函数 ============

    /** 字符串相似度（Levenshtein，0.0~1.0） */
    public static Object similar(Object a, Object b) {
        return StringExtensions.similar(String.valueOf(a), b);
    }

    /** 对称差集：两个集合中不重复的元素 */
    @SuppressWarnings("unchecked")
    public static Object disjunction(Object col1, Object col2) {
        Collection<?> c1 = (Collection<?>) col1;
        Collection<?> c2 = (Collection<?>) col2;
        List<Object> result = new ArrayList<>();
        for (Object item : c1) {
            if (!c2.contains(item)) result.add(item);
        }
        for (Object item : c2) {
            if (!c1.contains(item)) result.add(item);
        }
        return result;
    }

    // ============ 序列化 provider 查询/切换 ============

    /** 无参: 返回当前 JSON provider 名称；1参: 切换 provider */
    public static Object jsonProviderFn(Object[] args) {
        if (args.length == 0) {
            return SerializationProviders.json() != null ? SerializationProviders.json().name() : "none";
        }
        String name = String.valueOf(args[0]);
        String result = SerializationProviders.setJsonProvider(name);
        if (result == null) {
            throw new NovaException(ErrorKind.UNDEFINED,
                    "找不到 JSON provider: " + name,
                    "可用: " + SerializationProviders.listJsonProviders());
        }
        return result;
    }

    /** 无参: 返回当前 YAML provider 名称；1参: 切换 provider */
    public static Object yamlProviderFn(Object[] args) {
        if (args.length == 0) {
            return SerializationProviders.yaml() != null ? SerializationProviders.yaml().name() : "none";
        }
        String name = String.valueOf(args[0]);
        String result = SerializationProviders.setYamlProvider(name);
        if (result == null) {
            throw new NovaException(ErrorKind.UNDEFINED,
                    "找不到 YAML provider: " + name,
                    "可用: " + SerializationProviders.listYamlProviders());
        }
        return result;
    }
}
