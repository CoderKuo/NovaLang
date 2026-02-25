package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.ir.hir.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 模块（编译单元），合并 Program + PackageDecl + ImportDecl。
 */
public class HirModule extends HirDecl {

    private final String packageName;
    private final List<HirImport> imports;
    private final List<HirDecl> declarations;

    public HirModule(SourceLocation location, String packageName,
                     List<HirImport> imports, List<HirDecl> declarations) {
        super(location, packageName, Collections.emptySet(), Collections.emptyList());
        this.packageName = packageName;
        this.imports = imports;
        this.declarations = declarations;
    }

    public String getPackageName() {
        return packageName;
    }

    public List<HirImport> getImports() {
        return imports;
    }

    public List<HirDecl> getDeclarations() {
        return declarations;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitModule(this, context);
    }
}
