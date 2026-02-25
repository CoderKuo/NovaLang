package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 错误传播表达式（如 result?）
 */
public class ErrorPropagationExpr extends Expression {
    private final Expression operand;

    public ErrorPropagationExpr(SourceLocation location, Expression operand) {
        super(location);
        this.operand = operand;
    }

    public Expression getOperand() {
        return operand;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitErrorPropagationExpr(this, context);
    }
}
