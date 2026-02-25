package nova.runtime.stdlib;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记扩展方法为属性（直接返回值，不包装为 NovaNativeFunction）。
 * <p>仅适用于 0-arity 方法（只有 receiver 参数）。</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ExtProperty {
}
