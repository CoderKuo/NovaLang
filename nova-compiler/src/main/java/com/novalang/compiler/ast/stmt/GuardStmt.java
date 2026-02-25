package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * Guard 语句（guard let x = expr else { return }）
 */
public class GuardStmt extends Statement {
    private final String bindingName;
    private final Expression expression;
    private final Statement elseBody;  // return/throw/break/continue

    public GuardStmt(SourceLocation location, String bindingName,
                     Expression expression, Statement elseBody) {
        super(location);
        this.bindingName = bindingName;
        this.expression = expression;
        this.elseBody = elseBody;
    }

    public String getBindingName() {
        return bindingName;
    }

    public Expression getExpression() {
        return expression;
    }

    public Statement getElseBody() {
        return elseBody;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitGuardStmt(this, context);
    }
}
