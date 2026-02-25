package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.hirtype.HirType;

/**
 * 一元表达式
 */
public class UnaryExpr extends Expression {
    private final UnaryOp operator;
    private final Expression operand;
    private final boolean isPrefix;

    public UnaryExpr(SourceLocation location, UnaryOp operator, Expression operand, boolean isPrefix) {
        super(location);
        this.operator = operator;
        this.operand = operand;
        this.isPrefix = isPrefix;
    }

    /** HIR lowering 用构造器，携带类型信息 */
    public UnaryExpr(SourceLocation location, HirType hirType, UnaryOp operator, Expression operand, boolean isPrefix) {
        this(location, operator, operand, isPrefix);
        this.hirType = hirType;
    }

    public UnaryOp getOperator() {
        return operator;
    }

    public Expression getOperand() {
        return operand;
    }

    public boolean isPrefix() {
        return isPrefix;
    }

    public boolean isPostfix() {
        return !isPrefix;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitUnaryExpr(this, context);
    }

    /**
     * 一元运算符
     */
    public enum UnaryOp {
        NEG("-"),
        POS("+"),
        NOT("!"),
        INC("++"),
        DEC("--");

        private final String source;

        UnaryOp(String source) {
            this.source = source;
        }

        /** 返回 Nova 源码中对应的运算符 */
        public String toSourceString() {
            return source;
        }
    }
}
