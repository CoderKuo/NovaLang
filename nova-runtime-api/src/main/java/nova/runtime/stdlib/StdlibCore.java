package nova.runtime.stdlib;

import nova.runtime.NovaResult;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 核心内置函数的 stdlib 实现：error / Pair / range / with / repeat / runCatching 等。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 *
 * <p>Lambda 参数通过反射调用 {@code invoke()} 方法（与 ConcurrencyHelper 同模式）。</p>
 */
public final class StdlibCore {

    private StdlibCore() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibCore";
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
        return new Object[]{first, second};
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

    public static Object arrayOf(Object... args) {
        return args.clone();
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
        return invoke1(block, receiver);
    }

    public static Object repeat(Object times, Object action) {
        int n = ((Number) times).intValue();
        for (int i = 0; i < n; i++) {
            invoke1(action, i);
        }
        return null;
    }

    public static Object measureTimeMillis(Object block) {
        long start = System.currentTimeMillis();
        invoke0(block);
        return System.currentTimeMillis() - start;
    }

    public static Object measureNanoTime(Object block) {
        long start = System.nanoTime();
        invoke0(block);
        return System.nanoTime() - start;
    }

    public static Object runCatching(Object block) {
        try {
            Object result = invoke0(block);
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
            result.add(invoke1(init, i));
        }
        return result;
    }

    // ============ Lambda 调用辅助（与 ConcurrencyHelper 同模式） ============

    private static final ConcurrentHashMap<Class<?>, Method> invoke0Cache = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> invoke1Cache = new ConcurrentHashMap<>();

    private static Object invoke0(Object lambda) {
        try {
            Method m = invoke0Cache.computeIfAbsent(lambda.getClass(), cls -> {
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 0) return method;
                }
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName())) return method;
                }
                throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
            });
            return m.invoke(lambda);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }

    private static Object invoke1(Object lambda, Object arg) {
        try {
            Method m = invoke1Cache.computeIfAbsent(lambda.getClass(), cls -> {
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName()) && method.getParameterCount() == 1) return method;
                }
                for (Method method : cls.getMethods()) {
                    if ("invoke".equals(method.getName())) return method;
                }
                throw new RuntimeException("Lambda has no invoke(Object) method: " + cls.getName());
            });
            return m.invoke(lambda, arg);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause != null ? cause : e);
        }
    }
}
