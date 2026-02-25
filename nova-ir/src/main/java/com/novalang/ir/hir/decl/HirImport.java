package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.ir.hir.*;

import java.util.Collections;

/**
 * 导入声明。
 */
public class HirImport extends HirDecl {

    private final String qualifiedName;
    private final String alias;
    private final boolean isWildcard;
    private final boolean isJava;
    private final boolean isStatic;

    public HirImport(SourceLocation location, String qualifiedName,
                     String alias, boolean isWildcard) {
        this(location, qualifiedName, alias, isWildcard, false, false);
    }

    public HirImport(SourceLocation location, String qualifiedName,
                     String alias, boolean isWildcard,
                     boolean isJava, boolean isStatic) {
        super(location, alias != null ? alias : qualifiedName,
              Collections.emptySet(), Collections.emptyList());
        this.qualifiedName = qualifiedName;
        this.alias = alias;
        this.isWildcard = isWildcard;
        this.isJava = isJava;
        this.isStatic = isStatic;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getAlias() {
        return alias;
    }

    public boolean hasAlias() {
        return alias != null;
    }

    public boolean isWildcard() {
        return isWildcard;
    }

    public boolean isJava() {
        return isJava;
    }

    public boolean isStatic() {
        return isStatic;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitImport(this, context);
    }
}
