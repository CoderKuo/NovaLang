package com.novalang.runtime.stdlib;

import com.novalang.runtime.Function0;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * IR 编译路径的 async 辅助：将 lambda 对象包装为 Supplier 并提交给 CompletableFuture。
 */
public final class AsyncHelper {

    private AsyncHelper() {}

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
        // 委托 LambdaUtils 的统一 MethodHandle 缓存
        return CompletableFuture.supplyAsync(() -> LambdaUtils.invoke0(lambda));
    }
}
