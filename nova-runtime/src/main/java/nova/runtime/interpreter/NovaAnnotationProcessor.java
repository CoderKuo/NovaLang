package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.Map;

/**
 * 注解处理器接口。
 * Java 或 Nova 代码可以实现此接口来处理自定义注解。
 */
public interface NovaAnnotationProcessor {

    /** 返回此处理器关联的注解名称 */
    String getAnnotationName();

    /** 处理类注解 */
    default void processClass(NovaClass target, Map<String, NovaValue> args, Interpreter interpreter) {}

    /** 处理函数注解 */
    default void processFun(NovaCallable target, Map<String, NovaValue> args, Interpreter interpreter) {}

    /** 处理属性注解 */
    default void processProperty(String propertyName, NovaValue propertyValue,
                                  Map<String, NovaValue> args, Interpreter interpreter) {}
}
