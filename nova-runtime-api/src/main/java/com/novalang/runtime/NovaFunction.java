package com.novalang.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a stdlib static method as a Nova global function and attaches metadata.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface NovaFunction {
    String signature() default "";

    String description();

    String returnType();

    String contract() default "";
}
