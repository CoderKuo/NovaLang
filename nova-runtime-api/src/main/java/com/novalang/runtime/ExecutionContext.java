package com.novalang.runtime;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;

/**
 * 执行上下文接口 — NovaCallable.call() 的参数类型
 *
 * <p>提供可调用对象执行所需的核心功能，由 {@code Interpreter} 实现。</p>
 *
 * <p>设计目标：</p>
 * <ul>
 *   <li>解耦 nova-runtime-api 和 nova-runtime 模块</li>
 *   <li>提供类型安全的调用接口</li>
 *   <li>暴露可调用对象需要的核心功能</li>
 * </ul>
 */
public interface ExecutionContext {

    // ============ 安全策略 ============

    /**
     * 获取最大递归深度（0 表示无限制）
     *
     * @return 最大递归深度
     */
    int getMaxRecursionDepth();

    // ============ 调用栈管理 ============

    /**
     * 获取当前调用深度
     *
     * @return 调用深度
     */
    int getCallDepth();

    /**
     * 增加调用深度
     */
    void incrementCallDepth();

    /**
     * 减少调用深度
     */
    void decrementCallDepth();

    /**
     * 压入调用帧
     *
     * @param functionName 函数名
     * @param args 参数列表
     */
    void pushCallFrame(String functionName, List<NovaValue> args);

    /**
     * 弹出调用帧
     */
    void popCallFrame();

    /**
     * 捕获当前堆栈跟踪
     *
     * @return 堆栈跟踪信息列表
     */
    List<String> captureStackTrace();

    // ============ IO 流 ============

    /**
     * 获取标准输出流
     *
     * @return 标准输出 PrintStream
     */
    PrintStream getStdout();

    /**
     * 获取标准错误流
     *
     * @return 标准错误 PrintStream
     */
    PrintStream getStderr();

    /**
     * 获取标准输入流
     *
     * @return 标准输入 InputStream
     */
    InputStream getStdin();

    // ============ 调度器 ============

    /**
     * 获取调度器
     *
     * @return NovaScheduler 实例
     */
    NovaScheduler getScheduler();

    // ============ 可调用对象转换 ============

    /**
     * 将 NovaValue 转换为可调用对象
     *
     * @param value 要转换的值
     * @param contextName 上下文名称（用于错误信息）
     * @return NovaCallable 实例
     * @throws NovaRuntimeException 如果值不可调用
     */
    NovaCallable asCallable(NovaValue value, String contextName);

    // ============ 泛型类型参数 ============

    /**
     * 获取待处理的类型参数名
     *
     * @param index 参数索引
     * @return 类型参数名
     */
    String getPendingTypeArgName(int index);

    // ============ 实例化与子上下文 ============

    /**
     * 实例化 Nova 类
     *
     * @param novaClass 要实例化的类
     * @param args 构造器参数
     * @param namedArgs 命名参数（可为 null）
     * @return 实例化后的对象
     */
    NovaValue instantiate(NovaValue novaClass, List<NovaValue> args, Map<String, NovaValue> namedArgs);

    /**
     * 创建子执行上下文（用于并发/异步场景）
     *
     * <p>子上下文共享全局变量，但拥有独立的调用栈。</p>
     *
     * @return 新的子执行上下文
     */
    ExecutionContext createChild();

    /**
     * 执行绑定方法
     *
     * @param boundMethod 绑定方法
     * @param args 参数列表
     * @param namedArgs 命名参数（可为 null）
     * @return 执行结果
     */
    NovaValue executeBoundMethod(NovaValue boundMethod, List<NovaValue> args, Map<String, NovaValue> namedArgs);

    /**
     * 执行 HIR 函数
     *
     * @param function HIR 函数
     * @param args 参数列表
     * @param namedArgs 命名参数（可为 null）
     * @return 执行结果
     */
    NovaValue executeHirFunction(NovaValue function, List<NovaValue> args, Map<String, NovaValue> namedArgs);

    /**
     * 执行 HIR Lambda
     *
     * @param lambda HIR Lambda
     * @param args 参数列表
     * @return 执行结果
     */
    NovaValue executeHirLambda(NovaValue lambda, List<NovaValue> args);

    // ============ 作用域接收者 ============

    /**
     * 从 NovaValue 提取 NovaCallable（直接 callable 或含 invoke 方法的对象）。
     */
    default NovaCallable extractCallable(NovaValue value) {
        return value instanceof NovaCallable ? (NovaCallable) value : null;
    }

    /**
     * 以 receiver 为作用域接收者调用 callable。
     * 在 callable 执行期间，receiver 的成员可在 callable 内直接访问。
     *
     * @param callable Nova callable
     * @param receiver 作用域接收者
     * @param args     额外参数
     * @return callable 的返回值
     */
    default NovaValue invokeWithScopeReceiver(NovaCallable callable, NovaValue receiver, List<NovaValue> args) {
        // 默认实现：不绑定 receiver，直接调用
        return callable.call(this, args);
    }

    // ============ 注解处理器 ============

    /**
     * 注册注解处理器
     *
     * @param processor 注解处理器实例
     */
    void registerAnnotationProcessor(NovaAnnotationProcessor processor);

    /**
     * 取消注册注解处理器
     *
     * @param processor 注解处理器实例
     */
    void unregisterAnnotationProcessor(NovaAnnotationProcessor processor);

    // ============ 并发作用域 ============

    /**
     * 获取异步执行器（用于 launch/parallel/withTimeout）
     *
     * @return 异步执行器，默认 ForkJoinPool.commonPool()
     */
    default java.util.concurrent.Executor getAsyncExecutor() {
        return java.util.concurrent.ForkJoinPool.commonPool();
    }

    /**
     * 在结构化并发作用域中执行 block。
     *
     * <p>Interpreter 实现使用 NovaScope 管理子任务，编译路径回退到简单调用。</p>
     *
     * @param block 要执行的 lambda
     * @param supervisor 是否为 supervisor 模式（忽略子任务异常）
     * @return block 的返回值
     */
    default Object runInScope(Object block, boolean supervisor) {
        // 默认实现：直接调用（编译路径无结构化并发支持）
        if (block instanceof NovaCallable) {
            NovaValue result = ((NovaCallable) block).call(this, java.util.Collections.emptyList());
            return result != null ? result.toJavaValue() : null;
        }
        return null;
    }

    // ============ 通用函数调用 ============

    /**
     * 调用 Interpreter 环境中注册的函数（Builtins 等）。
     *
     * <p>用于 StdlibCore/ConcurrencyHelper 等 nova-runtime-api 层的函数实现
     * 需要调用 Interpreter 环境中的函数时。消除 interpreterBuiltin 双注册。</p>
     *
     * @param name 函数名
     * @param args 参数列表
     * @return 返回值，函数不存在时返回 null
     */
    default NovaValue callFunction(String name, List<NovaValue> args) {
        return null;
    }

    // ============ MIR 支持 ============

    /**
     * 获取 MIR 解释器（如果可用）
     *
     * <p>用于 MirCallable 访问 MIR 执行环境。</p>
     *
     * @return MIR 解释器，可能为 null
     */
    Object getMirInterpreter();
}
