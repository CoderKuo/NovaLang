package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 安全索引表达式（如 list?[0]）
 *
 * 当 target 为 null 时返回 null，不抛出异常
 */
public class SafeIndexExpr extends Expression {
    private final Expression target;
    private final Expression index;

    public SafeIndexExpr(SourceLocation location, Expression target, Expression index) {
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
        return visitor.visitSafeIndexExpr(this, context);
    }
}
