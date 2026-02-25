package nova.runtime.stdlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记类型扩展方法类。
 * <p>{@code value} 为目标类型的 JVM 内部名，如 {@code "java/util/List"}。</p>
 * <p>类中所有 {@code public static Object xxx(Object...)} 方法自动注册为扩展方法。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Ext {
    /** 目标类型 JVM 内部名 */
    String value();
}
