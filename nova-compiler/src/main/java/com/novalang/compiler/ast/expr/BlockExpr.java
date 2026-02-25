package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Statement;

import java.util.List;

/**
 * 块表达式：先执行一组语句，然后返回最后一个表达式的值。
 * 用于 try-catch 表达式、scope shorthand 等脱糖场景。
 */
public class BlockExpr extends Expression {

    private final List<Statement> statements;
    private final Expression result;

    public BlockExpr(SourceLocation location,
                     List<Statement> statements, Expression result) {
        super(location);
        this.statements = statements;
        this.result = result;
    }

    public List<Statement> getStatements() { return statements; }
    public Expression getResult() { return result; }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitBlockExpr(this, context);
    }
}
