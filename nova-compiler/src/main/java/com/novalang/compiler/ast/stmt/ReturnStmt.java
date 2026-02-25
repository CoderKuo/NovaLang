package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * Return 语句
 */
public class ReturnStmt extends Statement {
    private final Expression value;  // 可选
    private final String label;      // return@label

    public ReturnStmt(SourceLocation location, Expression value, String label) {
        super(location);
        this.value = value;
        this.label = label;
    }

    public Expression getValue() {
        return value;
    }

    public boolean hasValue() {
        return value != null;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitReturnStmt(this, context);
    }
}
