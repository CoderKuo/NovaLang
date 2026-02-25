package nova.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 stdlib 静态字段为 Nova 全局常量，附带 LSP 元数据。
 *
 * <p>NovaTypeRegistry 通过反射自动扫描此注解，无需手动 registerConstant。</p>
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaConstant {
    /** 类型名，如 "Double"、"Int" */
    String type();

    /** 描述（LSP hover / 补全提示） */
    String description();
}
