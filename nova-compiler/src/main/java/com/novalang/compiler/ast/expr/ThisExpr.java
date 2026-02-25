package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * this 表达式
 */
public class ThisExpr extends Expression {
    private final String label;  // this@Outer
    private boolean isSuper;     // super 引用（HIR lowering 时设置）

    public ThisExpr(SourceLocation location, String label) {
        super(location);
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    public boolean isSuper() {
        return isSuper;
    }

    public void setIsSuper(boolean isSuper) {
        this.isSuper = isSuper;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitThisExpr(this, context);
    }
}
