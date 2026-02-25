package com.novalang.compiler.ast;

/**
 * 修饰符枚举
 */
public enum Modifier {
    // 可见性
    PUBLIC,
    PRIVATE,
    PROTECTED,
    INTERNAL,

    // 继承
    ABSTRACT,
    SEALED,
    OPEN,
    FINAL,

    // 其他
    OVERRIDE,
    CONST,
    INLINE,
    CROSSINLINE,
    REIFIED,
    OPERATOR,
    VARARG,
    SUSPEND,
    STATIC;

    /** 返回 Nova 源码中对应的关键字 */
    public String toSourceString() {
        return name().toLowerCase();
    }
}
