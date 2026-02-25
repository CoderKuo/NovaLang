package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * super 表达式
 */
public class SuperExpr extends Expression {
    private final String label;  // super@Outer

    public SuperExpr(SourceLocation location, String label) {
        super(location);
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitSuperExpr(this, context);
    }
}
