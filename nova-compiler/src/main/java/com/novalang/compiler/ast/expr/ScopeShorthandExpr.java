package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Block;

/**
 * 作用域简写表达式 obj?.{ ... }
 * 等价于 obj?.let { ... }，在 obj 非 null 时执行 block，this 绑定到 obj
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
