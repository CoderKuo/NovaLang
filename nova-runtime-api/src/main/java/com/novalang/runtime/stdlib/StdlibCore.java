package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaArray;
import com.novalang.runtime.NovaPair;
import com.novalang.runtime.NovaResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

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

        // ---- vararg ----
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "arrayOf", -1, OWNER, "arrayOf", VARARG_DESC, StdlibCore::arrayOf));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "readLine", -1, OWNER, "readLine", VARARG_DESC, StdlibCore::readLine, true));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "input", -1, OWNER, "input", VARARG_DESC, StdlibCore::input, true));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    public static Object error(Object message) {
        throw new RuntimeException(String.valueOf(message));
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

    public static Object arrayOf(Object... args) {
        return new NovaArray(NovaArray.ElementType.OBJECT, args.clone(), args.length);
    }

    public static Object readLine(Object... args) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read input: " + e.getMessage());
        }
    }

    public static Object input(Object... args) {
        if (args.length > 0) System.out.print(args[0]);
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
            return reader.readLine();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read input: " + e.getMessage());
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
}
