package nova.runtime.stdlib;

import nova.runtime.NovaFunction;
import nova.runtime.NovaResult;

/**
 * 错误处理相关的 stdlib 函数：todo / assert / require。
 *
 * <p>静态方法是真正的实现，编译器直接 INVOKESTATIC 调用，解释器通过 impl lambda 调用。</p>
 *
 * <p>抛出的 {@code RuntimeException} 会被解释器自动包装为 NovaRuntimeException，
 * 因此 Nova 的 try-catch 能正常捕获。</p>
 */
public final class StdlibErrors {

    private StdlibErrors() {}

    private static final String OWNER = "nova/runtime/stdlib/StdlibErrors";
    private static final String RESULT_OWNER = "nova/runtime/NovaResult";
    private static final String O_O  = "(Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String OO_O = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

    static void register() {
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "todo", 1, OWNER, "todo", O_O, args -> todo(args[0])));
        // Java 中 assert 是保留字，JVM 方法名用 assertCondition
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "assert", 2, OWNER, "assertCondition", OO_O,
            args -> assertCondition(args[0], args[1])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "require", 2, OWNER, "require", OO_O,
            args -> require(args[0], args[1])));
        // Ok(value) / Err(error) → NovaResult.ok/err
        String resultDesc = "(Ljava/lang/Object;)Lnova/runtime/NovaResult;";
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "Ok", 1, RESULT_OWNER, "ok", resultDesc, args -> NovaResult.ok(args[0])));
        StdlibRegistry.register(new StdlibRegistry.NativeFunctionInfo(
            "Err", 1, RESULT_OWNER, "err", resultDesc, args -> NovaResult.err(args[0])));
    }

    // ============ 实现（编译器 INVOKESTATIC 直接调用） ============

    @NovaFunction(signature = "todo(message)", description = "标记未实现，抛出异常", returnType = "Nothing")
    public static Object todo(Object message) {
        throw new RuntimeException("TODO: " + message);
    }

    @NovaFunction(signature = "assert(condition, message)", description = "断言条件为真，否则抛出异常", returnType = "Unit")
    public static Object assertCondition(Object condition, Object message) {
        if (!isTruthy(condition)) {
            throw new RuntimeException("Assertion failed: " + message);
        }
        return null;
    }

    @NovaFunction(signature = "require(condition, message)", description = "检查前置条件，否则抛出异常", returnType = "Unit")
    public static Object require(Object condition, Object message) {
        if (!isTruthy(condition)) {
            throw new RuntimeException("Requirement failed: " + message);
        }
        return null;
    }

    private static boolean isTruthy(Object val) {
        return LambdaUtils.isTruthy(val);
    }
}
