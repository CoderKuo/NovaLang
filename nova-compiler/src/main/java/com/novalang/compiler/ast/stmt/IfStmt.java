package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * If 语句
 */
public class IfStmt extends Statement {
    private final Expression condition;
    private final String bindingName;  // if-let: if (val x = expr)
    private final Statement thenBranch;
    private final Statement elseBranch;  // 可选

    public IfStmt(SourceLocation location, Expression condition, String bindingName,
                  Statement thenBranch, Statement elseBranch) {
        super(location);
        this.condition = condition;
        this.bindingName = bindingName;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    public Expression getCondition() {
        return condition;
    }

    public String getBindingName() {
        return bindingName;
    }

    public boolean isIfLet() {
        return bindingName != null;
    }

    public boolean hasBinding() {
        return bindingName != null;
    }

    public Statement getThenBranch() {
        return thenBranch;
    }

    public Statement getElseBranch() {
        return elseBranch;
    }

    public boolean hasElse() {
        return elseBranch != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitIfStmt(this, context);
    }
}
