package nova.runtime.interpreter;
import nova.runtime.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 public static 方法为 Nova 函数。
 * 框架会自动完成 NovaValue 与 Java 原生类型之间的转换。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaFunc {
    /** 函数在 Nova 中的名称 */
    String value();

    /** 可选的别名列表 */
    String[] aliases() default {};
}
