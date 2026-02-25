package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 赋值表达式
 */
public class AssignExpr extends Expression {
    private final Expression target;
    private final AssignOp operator;
    private final Expression value;

    public AssignExpr(SourceLocation location, Expression target, AssignOp operator, Expression value) {
        super(location);
        this.target = target;
        this.operator = operator;
        this.value = value;
    }

    public Expression getTarget() {
        return target;
    }

    public AssignOp getOperator() {
        return operator;
    }

    public Expression getValue() {
        return value;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitAssignExpr(this, context);
    }

    /**
     * 赋值运算符
     */
    public enum AssignOp {
        ASSIGN("="),
        ADD_ASSIGN("+="),
        SUB_ASSIGN("-="),
        MUL_ASSIGN("*="),
        DIV_ASSIGN("/="),
        MOD_ASSIGN("%="),
        NULL_COALESCE("??="),
        OR_ASSIGN("||="),
        AND_ASSIGN("&&=");

        private final String source;

        AssignOp(String source) {
            this.source = source;
        }

        /** 返回 Nova 源码中对应的运算符 */
        public String toSourceString() {
            return source;
        }
    }
}
