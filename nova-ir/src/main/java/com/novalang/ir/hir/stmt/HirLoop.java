package com.novalang.ir.hir.stmt;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Statement;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;

/**
 * 循环语句，合并 WhileStmt + DoWhileStmt。
 */
public class HirLoop extends HirStmt {

    private final String label;         // nullable
    private final Expression condition;
    private final Statement body;
    private final boolean isDoWhile;

    public HirLoop(SourceLocation location, String label, Expression condition,
                   Statement body, boolean isDoWhile) {
        super(location);
        this.label = label;
        this.condition = condition;
        this.body = body;
        this.isDoWhile = isDoWhile;
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

    public boolean isDoWhile() {
        return isDoWhile;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitLoop(this, context);
    }
}
