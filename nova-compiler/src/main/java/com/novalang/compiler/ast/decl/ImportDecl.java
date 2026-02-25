package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 导入声明
 *
 * <p>支持以下导入形式：</p>
 * <ul>
 *   <li>{@code import models.User} — Nova 模块导入</li>
 *   <li>{@code import java java.util.ArrayList} — Java 类导入（java 软关键词）</li>
 *   <li>{@code import static java.lang.Math.PI} — Java 静态导入</li>
 *   <li>{@code import models.*} — 通配符导入</li>
 *   <li>{@code import Foo as Bar} — 别名导入</li>
 * </ul>
 */
public class ImportDecl extends AstNode {
    private final boolean isJava;      // import java ... (Java 类导入)
    private final boolean isStatic;
    private final QualifiedName name;
    private final boolean isWildcard;  // import java.util.*
    private final String alias;        // import Foo as Bar

    public ImportDecl(SourceLocation location, boolean isJava, boolean isStatic,
                      QualifiedName name, boolean isWildcard, String alias) {
        super(location);
        this.isJava = isJava;
        this.isStatic = isStatic;
        this.name = name;
        this.isWildcard = isWildcard;
        this.alias = alias;
    }

    /** 兼容旧构造器（isJava = false） */
    public ImportDecl(SourceLocation location, boolean isStatic, QualifiedName name,
                      boolean isWildcard, String alias) {
        this(location, false, isStatic, name, isWildcard, alias);
    }

    public boolean isJava() {
        return isJava;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public QualifiedName getName() {
        return name;
    }

    public boolean isWildcard() {
        return isWildcard;
    }

    public String getAlias() {
        return alias;
    }

    public boolean hasAlias() {
        return alias != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitImportDecl(this, context);
    }
}
