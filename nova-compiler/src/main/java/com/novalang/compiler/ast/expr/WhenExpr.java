package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.WhenBranch;

import java.util.List;

/**
 * When 表达式
 */
public class WhenExpr extends Expression {
    private final Expression subject;  // 可选
    private final String bindingName;  // when(val x = expr) 中的 x
    private final List<WhenBranch> branches;
    private final Expression elseExpr;

    public WhenExpr(SourceLocation location, Expression subject,
                    List<WhenBranch> branches, Expression elseExpr) {
        this(location, subject, null, branches, elseExpr);
    }

    public WhenExpr(SourceLocation location, Expression subject, String bindingName,
                    List<WhenBranch> branches, Expression elseExpr) {
        super(location);
        this.subject = subject;
        this.bindingName = bindingName;
        this.branches = branches;
        this.elseExpr = elseExpr;
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

    public Expression getElseExpr() {
        return elseExpr;
    }

    public boolean hasElse() {
        return elseExpr != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitWhenExpr(this, context);
    }
}
