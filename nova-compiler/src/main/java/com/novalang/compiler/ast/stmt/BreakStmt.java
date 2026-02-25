package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * Break 语句
 */
public class BreakStmt extends Statement {
    private final String label;

    public BreakStmt(SourceLocation location, String label) {
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
        return visitor.visitBreakStmt(this, context);
    }
}
