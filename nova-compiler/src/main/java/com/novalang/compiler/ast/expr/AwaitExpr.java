package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * Await 表达式
 */
public class AwaitExpr extends Expression {
    private final Expression operand;

    public AwaitExpr(SourceLocation location, Expression operand) {
        super(location);
        this.operand = operand;
    }

    public Expression getOperand() {
        return operand;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitAwaitExpr(this, context);
    }
}
