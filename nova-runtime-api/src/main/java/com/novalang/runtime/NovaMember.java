package com.novalang.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a visible Nova type member.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaMember {
    String description() default "";

    String returnType() default "";

    boolean property() default false;

    String contract() default "";
}
