package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Block;

/**
 * Scope shorthand expression: {@code obj?.{ ... }}.
 * Equivalent to {@code obj?.apply { ... }}: when {@code obj} is non-null, the block runs with
 * {@code this} bound to {@code obj}, and the whole expression evaluates to {@code obj?}.
 */
public class ScopeShorthandExpr extends Expression {

    private final Expression target;
    private final Block block;

    public ScopeShorthandExpr(SourceLocation location, Expression target, Block block) {
        super(location);
        this.target = target;
        this.block = block;
    }

    public Expression getTarget() {
        return target;
    }

    public Block getBlock() {
        return block;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitScopeShorthandExpr(this, context);
    }
}
