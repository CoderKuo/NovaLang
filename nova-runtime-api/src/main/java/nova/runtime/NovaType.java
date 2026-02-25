package nova.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 NovaValue 子类为 Nova 内置类型。
 * 反射扫描器会自动将其成员注册到 NovaTypeRegistry（LSP 补全）和运行时分发表。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaType {
    /** 类型在 Nova 中的名称，如 "Future" */
    String name();

    /** 类型描述（LSP hover 用） */
    String description() default "";
}
