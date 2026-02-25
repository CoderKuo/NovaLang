package com.novalang.ir.hir;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.hirtype.HirType;

/**
 * HIR 表达式基类。所有表达式节点都携带类型信息。
 * <p>
 * Phase 0 迁移：extends Expression implements HirNode，
 * HIR 表达式同时也是 AST Expression。
 */
public abstract class HirExpr extends Expression implements HirNode {

    protected HirType type;

    protected HirExpr(SourceLocation location, HirType type) {
        super(location);
        this.type = type;
    }

    public HirType getType() {
        return type;
    }

    public void setType(HirType type) {
        this.type = type;
    }

    /**
     * AST visitor 不处理 HIR 节点，返回 null。
     */
    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
