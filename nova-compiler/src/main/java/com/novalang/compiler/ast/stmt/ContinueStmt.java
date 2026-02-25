package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * Continue 语句
 */
public class ContinueStmt extends Statement {
    private final String label;

    public ContinueStmt(SourceLocation location, String label) {
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
        return visitor.visitContinueStmt(this, context);
    }
}
