package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 非空断言表达式（如 value!!）
 */
public class NotNullExpr extends Expression {
    private final Expression operand;

    public NotNullExpr(SourceLocation location, Expression operand) {
        super(location);
        this.operand = operand;
    }

    public Expression getOperand() {
        return operand;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitNotNullExpr(this, context);
    }
}
