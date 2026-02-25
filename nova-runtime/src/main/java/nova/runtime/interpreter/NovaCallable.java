package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.List;

/**
 * Nova 可调用对象接口
 */
public interface NovaCallable {

    /**
     * 获取函数名称
     */
    String getName();

    /**
     * 获取参数数量（-1 表示可变参数）
     */
    int getArity();

    /**
     * 调用函数
     *
     * @param interpreter 解释器实例
     * @param args 参数列表
     * @return 返回值
     */
    NovaValue call(Interpreter interpreter, List<NovaValue> args);

    /**
     * 获取参数名称列表（供反射 API 使用）
     */
    default java.util.List<String> getParamNames() {
        return java.util.Collections.emptyList();
    }

    /**
     * 获取参数类型名称列表（供反射 API 使用）
     */
    default java.util.List<String> getParamTypeNames() {
        return java.util.Collections.emptyList();
    }

    /**
     * 获取各参数是否有默认值（供反射 API 使用）
     */
    default java.util.List<Boolean> getParamHasDefaults() {
        return java.util.Collections.emptyList();
    }

    /**
     * 是否支持命名参数
     */
    default boolean supportsNamedArgs() {
        return false;
    }

    /**
     * 使用命名参数调用
     *
     * @param interpreter 解释器实例
     * @param args 位置参数
     * @param namedArgs 命名参数
     * @return 返回值
     */
    default NovaValue callWithNamed(Interpreter interpreter, List<NovaValue> args,
                                     java.util.Map<String, NovaValue> namedArgs) {
        return call(interpreter, args);
    }
}
