package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

/**
 * C 风格 for 语句: for (var i = 0; i < n; i += 1) { ... }
 */
public class CStyleForStmt extends Statement {
    private final String label;         // 可选标签
    private final String varName;       // 循环变量名
    private final boolean isVal;        // val or var
    private final Expression init;      // 初始化表达式
    private final Expression condition; // 条件（nullable = 无限循环）
    private final Expression update;    // 更新表达式（nullable）
    private final Statement body;

    public CStyleForStmt(SourceLocation location, String label, String varName, boolean isVal,
                         Expression init, Expression condition, Expression update, Statement body) {
        super(location);
        this.label = label;
        this.varName = varName;
        this.isVal = isVal;
        this.init = init;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }

    public String getLabel() { return label; }
    public boolean hasLabel() { return label != null; }
    public String getVarName() { return varName; }
    public boolean isVal() { return isVal; }
    public Expression getInit() { return init; }
    public Expression getCondition() { return condition; }
    public Expression getUpdate() { return update; }
    public Statement getBody() { return body; }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitCStyleForStmt(this, context);
    }
}
