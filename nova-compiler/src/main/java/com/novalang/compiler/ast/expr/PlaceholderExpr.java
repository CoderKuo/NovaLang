package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 占位符表达式（用于部分应用：f(_, x)）
 */
public class PlaceholderExpr extends Expression {

    public PlaceholderExpr(SourceLocation location) {
        super(location);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitPlaceholderExpr(this, context);
    }
}
