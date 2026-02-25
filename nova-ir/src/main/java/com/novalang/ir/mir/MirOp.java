package com.novalang.ir.mir;

/**
 * MIR 指令操作码。
 */
public enum MirOp {
    // 常量 (8)
    CONST_INT,
    CONST_LONG,
    CONST_FLOAT,
    CONST_DOUBLE,
    CONST_STRING,
    CONST_BOOL,
    CONST_CHAR,
    CONST_NULL,

    // 变量
    MOVE,           // dest = src

    // 算术/逻辑
    BINARY,         // dest = src1 op src2
    UNARY,          // dest = op src

    // 对象/字段
    NEW_OBJECT,     // dest = new Class(args)
    GET_FIELD,      // dest = obj.field
    SET_FIELD,      // obj.field = src
    GET_STATIC,     // dest = Class.field
    SET_STATIC,     // Class.field = src

    // 调用
    INVOKE_VIRTUAL,     // dest = obj.method(args)
    INVOKE_STATIC,      // dest = Class.method(args)
    INVOKE_INTERFACE,   // dest = iface.method(args)
    INVOKE_SPECIAL,     // dest = super/constructor(args)

    // 集合/数组
    INDEX_GET,      // dest = target[index]
    INDEX_SET,      // target[index] = value
    NEW_ARRAY,      // dest = new Object[size]
    NEW_COLLECTION, // dest = new List/Set/Map(elements)

    // 类型
    TYPE_CHECK,     // dest = src instanceof Type
    TYPE_CAST,      // dest = (Type) src
    CONST_CLASS,    // dest = ClassName.class (LDC)

    // 闭包
    CLOSURE         // dest = lambda(captures, funcRef)
}
