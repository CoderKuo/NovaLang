package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * If 表达式
 */
public class IfExpr extends Expression {
    private final Expression condition;
    private final String bindingName;  // if-let: if (val x = expr)
    private final Expression thenExpr;
    private final Expression elseExpr;

    public IfExpr(SourceLocation location, Expression condition, String bindingName,
                  Expression thenExpr, Expression elseExpr) {
        super(location);
        this.condition = condition;
        this.bindingName = bindingName;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    public Expression getCondition() {
        return condition;
    }

    public String getBindingName() {
        return bindingName;
    }

    public boolean isIfLet() {
        return bindingName != null;
    }

    public boolean hasBinding() {
        return bindingName != null;
    }

    public Expression getThenExpr() {
        return thenExpr;
    }

    public Expression getElseExpr() {
        return elseExpr;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitIfExpr(this, context);
    }
}
