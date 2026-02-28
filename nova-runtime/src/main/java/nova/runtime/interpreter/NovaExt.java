package nova.runtime.interpreter;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个 public static 方法为 Nova 扩展方法。
 * 第一个参数（非 Interpreter）约定为接收者（receiver），其余为方法参数。
 *
 * <pre>
 * public class StringExtensions {
 *     &#64;NovaExt("shout")
 *     public static String shout(String self) {
 *         return self.toUpperCase() + "!";
 *     }
 *
 *     &#64;NovaExt("truncate")
 *     public static String truncate(String self, int maxLen) {
 *         return self.length() &lt;= maxLen ? self : self.substring(0, maxLen) + "...";
 *     }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaExt {
    /** 方法在 Nova 中的名称 */
    String value();

    /** 可选的别名列表 */
    String[] aliases() default {};
}
