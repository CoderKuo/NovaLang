package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.Declaration;

/**
 * 声明语句（用于在语句位置使用声明，如局部变量）
 */
public class DeclarationStmt extends Statement {
    private final Declaration declaration;

    public DeclarationStmt(SourceLocation location, Declaration declaration) {
        super(location);
        this.declaration = declaration;
    }

    public Declaration getDeclaration() {
        return declaration;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitDeclarationStmt(this, context);
    }
}
