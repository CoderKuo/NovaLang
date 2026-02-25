package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Block;

import java.util.Collections;

/**
 * init 块声明
 *
 * <p>语法: init { body }</p>
 */
public class InitBlockDecl extends Declaration {
    private final Block body;

    public InitBlockDecl(SourceLocation location, Block body) {
        super(location, Collections.emptyList(), Collections.emptyList(), "<init-block>");
        this.body = body;
    }

    public Block getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitInitBlockDecl(this, context);
    }
}
