package nova.runtime.stdlib;

import nova.runtime.Function0;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * IR 编译路径的 async 辅助：将 lambda 对象（带 invoke() 方法）包装为 Supplier 并提交给 CompletableFuture。
 */
public final class AsyncHelper {

    private AsyncHelper() {}

    private static final ConcurrentHashMap<Class<?>, Method> invokeCache = new ConcurrentHashMap<>();

    /**
     * 将 lambda 对象包装为 Supplier 并异步执行。
     */
    @SuppressWarnings("unchecked")
    public static Object run(Object lambda) {
        if (lambda instanceof Function0) {
            Function0<Object> fn = (Function0<Object>) lambda;
            return CompletableFuture.supplyAsync(fn);
        }
        if (lambda instanceof Supplier) {
            Supplier<Object> sup = (Supplier<Object>) lambda;
            return CompletableFuture.supplyAsync(sup);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                Method m = invokeCache.computeIfAbsent(lambda.getClass(), cls -> {
                    try {
                        return cls.getMethod("invoke");
                    } catch (NoSuchMethodException e) {
                        throw new RuntimeException("Lambda has no invoke() method: " + cls.getName());
                    }
                });
                return m.invoke(lambda);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                throw new RuntimeException(cause != null ? cause : e);
            }
        });
    }
}
