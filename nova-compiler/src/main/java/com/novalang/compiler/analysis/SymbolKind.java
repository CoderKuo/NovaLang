package com.novalang.compiler.analysis;

/**
 * 符号类型
 */
public enum SymbolKind {
    VARIABLE,           // val/var 局部变量
    PARAMETER,          // 函数参数
    FUNCTION,           // fun 声明
    CLASS,              // class 声明
    INTERFACE,          // interface 声明
    ENUM,               // enum 声明
    ENUM_ENTRY,         // 枚举条目
    OBJECT,             // object 声明
    PROPERTY,           // 类属性 (constructor param with val/var)
    TYPE_ALIAS,         // typealias
    IMPORT,             // 导入的符号
    CONSTRUCTOR,        // constructor 声明
    BUILTIN_FUNCTION,   // 内置函数
    BUILTIN_CONSTANT    // 内置常量
}
