package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 包声明
 */
public class PackageDecl extends AstNode {
    private final QualifiedName name;

    public PackageDecl(SourceLocation location, QualifiedName name) {
        super(location);
        this.name = name;
    }

    public QualifiedName getName() {
        return name;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitPackageDecl(this, context);
    }
}
