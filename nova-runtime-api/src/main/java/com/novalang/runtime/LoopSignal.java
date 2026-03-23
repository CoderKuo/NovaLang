package com.novalang.runtime;

/**
 * Non-local break/continue 信号。
 * 当 break/continue 出现在 lambda 内部（如 forEach { if (...) break }）时，
 * 编译为 throw LoopSignal，由外层 HOF（forEach/map 等）捕获处理。
 *
 * 继承 RuntimeException 而非 NovaRuntimeException，避免被 Nova 的 try-catch 拦截。
 */
public class LoopSignal extends RuntimeException {

    private LoopSignal() {
        super(null, null, true, false); // 禁用 stackTrace 填充（性能）
    }

    /** break 信号：终止整个循环 */
    public static final LoopSignal BREAK = new LoopSignal();

    /** continue 信号：跳过当前迭代，继续下一次 */
    public static final LoopSignal CONTINUE = new LoopSignal();
}
