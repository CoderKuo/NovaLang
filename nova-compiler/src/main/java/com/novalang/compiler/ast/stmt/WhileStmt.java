package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * While 语句
 */
public class WhileStmt extends Statement {
    private final String label;
    private final Expression condition;
    private final Statement body;

    public WhileStmt(SourceLocation location, String label, Expression condition, Statement body) {
        super(location);
        this.label = label;
        this.condition = condition;
        this.body = body;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    public Expression getCondition() {
        return condition;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitWhileStmt(this, context);
    }
}
