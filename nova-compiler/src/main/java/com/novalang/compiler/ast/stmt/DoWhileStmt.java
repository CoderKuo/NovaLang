package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * Do-While 语句
 */
public class DoWhileStmt extends Statement {
    private final String label;
    private final Statement body;
    private final Expression condition;

    public DoWhileStmt(SourceLocation location, String label, Statement body, Expression condition) {
        super(location);
        this.label = label;
        this.body = body;
        this.condition = condition;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    public Statement getBody() {
        return body;
    }

    public Expression getCondition() {
        return condition;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitDoWhileStmt(this, context);
    }
}
