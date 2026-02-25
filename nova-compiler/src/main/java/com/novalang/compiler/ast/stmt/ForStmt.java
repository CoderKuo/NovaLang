package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

import java.util.List;

/**
 * For 语句
 */
public class ForStmt extends Statement {
    private final String label;  // 可选
    private final List<String> variables;  // 支持解构
    private final Expression iterable;
    private final Statement body;

    public ForStmt(SourceLocation location, String label, List<String> variables,
                   Expression iterable, Statement body) {
        super(location);
        this.label = label;
        this.variables = variables;
        this.iterable = iterable;
        this.body = body;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    public List<String> getVariables() {
        return variables;
    }

    public Expression getIterable() {
        return iterable;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitForStmt(this, context);
    }
}
