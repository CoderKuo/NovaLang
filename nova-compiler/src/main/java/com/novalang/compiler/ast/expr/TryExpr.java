package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Block;
import com.novalang.compiler.ast.stmt.CatchClause;

import java.util.List;

/**
 * Try 表达式
 */
public class TryExpr extends Expression {
    private final Block tryBlock;
    private final List<CatchClause> catchClauses;
    private final Block finallyBlock;

    public TryExpr(SourceLocation location, Block tryBlock,
                   List<CatchClause> catchClauses, Block finallyBlock) {
        super(location);
        this.tryBlock = tryBlock;
        this.catchClauses = catchClauses;
        this.finallyBlock = finallyBlock;
    }

    public Block getTryBlock() {
        return tryBlock;
    }

    public List<CatchClause> getCatchClauses() {
        return catchClauses;
    }

    public Block getFinallyBlock() {
        return finallyBlock;
    }

    public boolean hasFinally() {
        return finallyBlock != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitTryExpr(this, context);
    }
}
