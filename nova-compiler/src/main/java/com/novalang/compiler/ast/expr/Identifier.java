package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 标识符表达式
 */
public class Identifier extends Expression {
    private final String name;
    private int resolvedDepth = -1;  // -1 = 未解析
    private int resolvedSlot = -1;

    public Identifier(SourceLocation location, String name) {
        super(location);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isResolved() { return resolvedDepth >= 0; }
    public int getResolvedDepth() { return resolvedDepth; }
    public int getResolvedSlot() { return resolvedSlot; }
    public void setResolved(int depth, int slot) {
        this.resolvedDepth = depth;
        this.resolvedSlot = slot;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitIdentifier(this, context);
    }
}
