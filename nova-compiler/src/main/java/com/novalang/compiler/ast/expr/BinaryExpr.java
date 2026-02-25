package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.hirtype.HirType;

/**
 * 二元表达式
 */
public class BinaryExpr extends Expression {
    private final Expression left;
    private final BinaryOp operator;
    private final Expression right;

    public BinaryExpr(SourceLocation location, Expression left, BinaryOp operator, Expression right) {
        super(location);
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    /** HIR lowering 用构造器，携带类型信息 */
    public BinaryExpr(SourceLocation location, HirType hirType, Expression left, BinaryOp operator, Expression right) {
        this(location, left, operator, right);
        this.hirType = hirType;
    }

    public Expression getLeft() {
        return left;
    }

    public BinaryOp getOperator() {
        return operator;
    }

    public Expression getRight() {
        return right;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitBinaryExpr(this, context);
    }

    /**
     * 二元运算符
     */
    public enum BinaryOp {
        // 算术
        ADD("+"),
        SUB("-"),
        MUL("*"),
        DIV("/"),
        MOD("%"),

        // 比较
        EQ("=="),
        NE("!="),
        REF_EQ("==="),
        REF_NE("!=="),
        LT("<"),
        GT(">"),
        LE("<="),
        GE(">="),

        // 逻辑
        AND("&&"),
        OR("||"),

        // 范围
        RANGE_INCLUSIVE(".."),
        RANGE_EXCLUSIVE("..<"),

        // 包含
        IN("in"),
        NOT_IN("!in"),

        // Pair 创建
        TO("to");

        private final String source;

        BinaryOp(String source) {
            this.source = source;
        }

        /** 返回 Nova 源码中对应的运算符 */
        public String toSourceString() {
            return source;
        }
    }
}
