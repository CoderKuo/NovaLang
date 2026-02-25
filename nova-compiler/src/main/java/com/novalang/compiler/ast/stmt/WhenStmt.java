package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

import java.util.List;

/**
 * When 语句
 */
public class WhenStmt extends Statement {
    private final Expression subject;  // 可选
    private final String bindingName;  // when-let: when (val x = expr)
    private final List<WhenBranch> branches;
    private final Statement elseBranch;  // 可选

    public WhenStmt(SourceLocation location, Expression subject,
                    List<WhenBranch> branches, Statement elseBranch) {
        this(location, subject, null, branches, elseBranch);
    }

    public WhenStmt(SourceLocation location, Expression subject, String bindingName,
                    List<WhenBranch> branches, Statement elseBranch) {
        super(location);
        this.subject = subject;
        this.bindingName = bindingName;
        this.branches = branches;
        this.elseBranch = elseBranch;
    }

    public Expression getSubject() {
        return subject;
    }

    public boolean hasSubject() {
        return subject != null;
    }

    public String getBindingName() {
        return bindingName;
    }

    public boolean hasBinding() {
        return bindingName != null;
    }

    public List<WhenBranch> getBranches() {
        return branches;
    }

    public Statement getElseBranch() {
        return elseBranch;
    }

    public boolean hasElse() {
        return elseBranch != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitWhenStmt(this, context);
    }
}
