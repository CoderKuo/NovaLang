package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * Try 语句
 */
public class TryStmt extends Statement {
    private final Block tryBlock;
    private final List<CatchClause> catchClauses;
    private final Block finallyBlock;  // 可选

    public TryStmt(SourceLocation location, Block tryBlock,
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
        return visitor.visitTryStmt(this, context);
    }
}
