package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 程序（编译单元）
 */
public class Program extends AstNode {
    private final PackageDecl packageDecl;  // 可选
    private final List<ImportDecl> imports;
    private final List<Declaration> declarations;

    public Program(SourceLocation location, PackageDecl packageDecl,
                   List<ImportDecl> imports, List<Declaration> declarations) {
        super(location);
        this.packageDecl = packageDecl;
        this.imports = imports;
        this.declarations = declarations;
    }

    public PackageDecl getPackageDecl() {
        return packageDecl;
    }

    public List<ImportDecl> getImports() {
        return imports;
    }

    public List<Declaration> getDeclarations() {
        return declarations;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitProgram(this, context);
    }
}
