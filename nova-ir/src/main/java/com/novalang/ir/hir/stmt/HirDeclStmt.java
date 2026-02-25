package com.novalang.ir.hir.stmt;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.ir.hir.*;

/**
 * 声明语句（包裹一个声明节点）。
 */
public class HirDeclStmt extends HirStmt {

    private final HirDecl declaration;

    public HirDeclStmt(SourceLocation location, HirDecl declaration) {
        super(location);
        this.declaration = declaration;
    }

    public HirDecl getDeclaration() {
        return declaration;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitDeclStmt(this, context);
    }
}
