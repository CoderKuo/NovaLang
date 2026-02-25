package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 代码块
 */
public class Block extends Statement {
    private final List<Statement> statements;
    private final boolean transparent;

    public Block(SourceLocation location, List<Statement> statements) {
        this(location, statements, false);
    }

    public Block(SourceLocation location, List<Statement> statements, boolean transparent) {
        super(location);
        this.statements = statements;
        this.transparent = transparent;
    }

    public List<Statement> getStatements() {
        return statements;
    }

    /** transparent block 不创建新作用域，变量定义在父作用域中 */
    public boolean isTransparent() {
        return transparent;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitBlock(this, context);
    }
}
