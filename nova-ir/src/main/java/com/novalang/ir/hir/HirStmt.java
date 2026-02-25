package com.novalang.ir.hir;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Statement;

/**
 * HIR 语句基类。
 * <p>
 * Phase 0 迁移：extends Statement implements HirNode，
 * HIR 语句同时也是 AST Statement。
 */
public abstract class HirStmt extends Statement implements HirNode {

    protected HirStmt(SourceLocation location) {
        super(location);
    }

    /**
     * AST visitor 不处理 HIR 节点，返回 null。
     */
    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
