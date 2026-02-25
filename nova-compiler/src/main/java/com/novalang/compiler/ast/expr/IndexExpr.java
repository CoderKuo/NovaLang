package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 索引表达式（如 arr[0]）
 */
public class IndexExpr extends Expression {
    private final Expression target;
    private final Expression index;

    public IndexExpr(SourceLocation location, Expression target, Expression index) {
        super(location);
        this.target = target;
        this.index = index;
    }

    public Expression getTarget() {
        return target;
    }

    public Expression getIndex() {
        return index;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitIndexExpr(this, context);
    }
}
