package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.types.Environment;
import com.novalang.runtime.interpreter.*;

/**
 * nova.concurrent — 所有并发原语已提升为全局函数（Builtins）。
 *
 * <p>保留空 register 方法以兼容 {@code import nova.concurrent.*} 语句。</p>
 */
public final class StdlibConcurrent {

    private StdlibConcurrent() {}

    public static void register(Environment env, Interpreter interp) {
        // 所有函数已提升为全局（Builtins）：
        // parallel, withTimeout, awaitAll, awaitFirst,
        // AtomicInt, AtomicLong, AtomicRef, Channel, Mutex
    }
}
