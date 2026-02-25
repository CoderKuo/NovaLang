package nova.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 stdlib 静态方法为 Nova 全局函数，附带 LSP 元数据。
 *
 * <p>NovaTypeRegistry 通过反射自动扫描此注解，无需手动 registerFunction。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaFunction {
    /** 函数签名，如 "sqrt(value)"。空串则根据 name + arity 自动生成 */
    String signature() default "";

    /** 函数描述（LSP hover / 补全提示） */
    String description();

    /** 返回类型名，如 "Double"、"Int"、"Boolean" */
    String returnType();
}
