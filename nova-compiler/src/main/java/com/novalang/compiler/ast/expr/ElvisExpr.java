package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * Elvis 表达式（如 x ?: defaultValue）
 */
public class ElvisExpr extends Expression {
    private final Expression left;
    private final Expression right;

    public ElvisExpr(SourceLocation location, Expression left, Expression right) {
        super(location);
        this.left = left;
        this.right = right;
    }

    public Expression getLeft() {
        return left;
    }

    public Expression getRight() {
        return right;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitElvisExpr(this, context);
    }
}
