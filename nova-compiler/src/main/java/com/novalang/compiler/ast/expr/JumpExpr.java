package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Statement;

/**
 * 跳转表达式 — 将 return/throw/break/continue 包装为表达式（类型为 Nothing）
 * 用于 elvis 右侧等需要表达式的位置：val x = y ?: return / throw / break
 */
public class JumpExpr extends Expression {
    private final Statement statement;

    public JumpExpr(SourceLocation location, Statement statement) {
        super(location);
        this.statement = statement;
    }

    public Statement getStatement() {
        return statement;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitJumpExpr(this, context);
    }
}
