package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * Throw 语句
 */
public class ThrowStmt extends Statement {
    private final Expression exception;

    public ThrowStmt(SourceLocation location, Expression exception) {
        super(location);
        this.exception = exception;
    }

    public Expression getException() {
        return exception;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitThrowStmt(this, context);
    }
}
