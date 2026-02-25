package com.novalang.ir.mir;

/**
 * MIR 二元运算子操作符。
 */
public enum BinaryOp {
    ADD, SUB, MUL, DIV, MOD,
    EQ, NE, LT, GT, LE, GE,
    AND, OR,
    SHL, SHR, USHR,
    BAND, BOR, BXOR
}
