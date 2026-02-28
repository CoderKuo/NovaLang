package nova.runtime;

import java.util.List;
import java.util.Map;

/**
 * Nova 可调用对象接口
 *
 * <p>所有可调用的 Nova 值（函数、Lambda、类构造器等）都实现此接口，
 * 提供统一的调用契约。</p>
 *
 * <p>实现类：</p>
 * <ul>
 *   <li>NovaNativeFunction - 原生 Java 函数</li>
 *   <li>HirFunctionValue - HIR 函数值</li>
 *   <li>HirLambdaValue - Lambda 值</li>
 *   <li>MirCallable - MIR 可调用对象</li>
 *   <li>NovaBoundMethod - 绑定方法</li>
 *   <li>NovaClass - 类（作为构造器）</li>
 *   <li>NovaEnum - 枚举类型</li>
 * </ul>
 */
public interface NovaCallable extends NovaValue {

    /**
     * 获取函数名称
     *
     * @return 函数名称
     */
    String getName();

    /**
     * 获取参数数量
     *
     * @return 参数数量，-1 表示可变参数
     */
    int getArity();

    /**
     * 调用函数
     *
     * @param ctx 执行上下文
     * @param args 参数列表
     * @return 返回值
     */
    NovaValue call(ExecutionContext ctx, List<NovaValue> args);

    // ============ 反射 API 支持方法 ============

    /**
     * 获取参数名称列表（供反射 API 使用）
     *
     * @return 参数名称列表
     */
    default List<String> getParamNames() {
        return java.util.Collections.emptyList();
    }

    /**
     * 获取参数类型名称列表（供反射 API 使用）
     *
     * @return 参数类型名称列表
     */
    default List<String> getParamTypeNames() {
        return java.util.Collections.emptyList();
    }

    /**
     * 获取各参数是否有默认值（供反射 API 使用）
     *
     * @return 参数默认值标志列表
     */
    default List<Boolean> getParamHasDefaults() {
        return java.util.Collections.emptyList();
    }

    // ============ 命名参数支持 ============

    /**
     * 是否支持命名参数
     *
     * @return 如果支持命名参数返回 true
     */
    default boolean supportsNamedArgs() {
        return false;
    }

    /**
     * 使用命名参数调用
     *
     * @param ctx 执行上下文
     * @param args 位置参数
     * @param namedArgs 命名参数
     * @return 返回值
     */
    default NovaValue callWithNamed(ExecutionContext ctx, List<NovaValue> args,
                                     Map<String, NovaValue> namedArgs) {
        return call(ctx, args);
    }

    // ============ NovaValue 默认实现覆写 ============

    @Override
    default boolean isCallable() {
        return true;
    }
}
