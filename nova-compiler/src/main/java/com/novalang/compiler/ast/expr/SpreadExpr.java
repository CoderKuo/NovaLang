package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 展开表达式（如 *list）
 */
public class SpreadExpr extends Expression {
    private final Expression operand;

    public SpreadExpr(SourceLocation location, Expression operand) {
        super(location);
        this.operand = operand;
    }

    public Expression getOperand() {
        return operand;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitSpreadExpr(this, context);
    }
}
