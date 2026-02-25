package nova.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个方法为 Nova 类型的可见成员。
 * 配合 {@link NovaType} 使用，扫描器自动注册补全和运行时分发。
 *
 * <p>方法签名约定：</p>
 * <ul>
 *   <li>第一个参数为 Interpreter 类型时，自动注入解释器实例</li>
 *   <li>后续参数对应 Nova 调用时的实参（目前暂不使用）</li>
 *   <li>返回类型自动转换：boolean → NovaBoolean, int → NovaInt, String → NovaString 等</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaMember {
    /** 成员描述（LSP 补全提示用） */
    String description() default "";

    /** 返回类型名（LSP 类型推导用），空串表示从方法返回类型自动推断 */
    String returnType() default "";

    /** true = 属性（obj.isDone），false = 方法（obj.get()） */
    boolean property() default false;
}
