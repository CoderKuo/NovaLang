package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.hirtype.HirType;

/**
 * 字面量表达式
 */
public class Literal extends Expression {
    private final Object value;
    private final LiteralKind kind;

    public Literal(SourceLocation location, Object value, LiteralKind kind) {
        super(location);
        this.value = value;
        this.kind = kind;
    }

    /** HIR lowering 用构造器，携带类型信息 */
    public Literal(SourceLocation location, HirType hirType, Object value, LiteralKind kind) {
        this(location, value, kind);
        this.hirType = hirType;
    }

    public Object getValue() {
        return value;
    }

    public LiteralKind getKind() {
        return kind;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitLiteral(this, context);
    }

    /**
     * 字面量类型
     */
    public enum LiteralKind {
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        CHAR,
        STRING,
        BOOLEAN,
        NULL;

        /** 是否为数值字面量类型 */
        public boolean isNumeric() {
            return numericRank() >= 0;
        }

        /** 数值提升等级：INT(0) < LONG(1) < FLOAT(2) < DOUBLE(3)，非数值返回 -1 */
        public int numericRank() {
            switch (this) {
                case INT:    return 0;
                case LONG:   return 1;
                case FLOAT:  return 2;
                case DOUBLE: return 3;
                default:     return -1;
            }
        }
    }
}
