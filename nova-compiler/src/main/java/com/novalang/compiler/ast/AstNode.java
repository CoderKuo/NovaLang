package com.novalang.compiler.ast;

/**
 * AST 节点基类
 */
public abstract class AstNode {
    protected final SourceLocation location;

    protected AstNode(SourceLocation location) {
        this.location = location;
    }

    public SourceLocation getLocation() {
        return location;
    }

    public abstract <R, C> R accept(AstVisitor<R, C> visitor, C context);
}
