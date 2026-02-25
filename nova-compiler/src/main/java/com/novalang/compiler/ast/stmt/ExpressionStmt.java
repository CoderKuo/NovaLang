package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * 表达式语句
 */
public class ExpressionStmt extends Statement {
    private final Expression expression;

    public ExpressionStmt(SourceLocation location, Expression expression) {
        super(location);
        this.expression = expression;
    }

    public Expression getExpression() {
        return expression;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitExpressionStmt(this, context);
    }
}
