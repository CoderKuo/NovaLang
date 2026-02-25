package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 条件表达式（三元运算）。
 * SafeCall/SafeIndex/Elvis/IfExpr/WhenExpr 脱糖后产生此节点。
 */
public class ConditionalExpr extends Expression {

    private final Expression condition;
    private final Expression thenExpr;
    private final Expression elseExpr;

    public ConditionalExpr(SourceLocation location,
                           Expression condition, Expression thenExpr, Expression elseExpr) {
        super(location);
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    public Expression getCondition() { return condition; }
    public Expression getThenExpr() { return thenExpr; }
    public Expression getElseExpr() { return elseExpr; }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitConditionalExpr(this, context);
    }
}
