package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解构声明（如 val (a, b, c) = expr 或 val (mail = email, name = username) = expr）
 */
public class DestructuringDecl extends Declaration {
    private final boolean isVal;
    private final List<DestructuringEntry> entries;
    private final Expression initializer;

    public DestructuringDecl(SourceLocation location, boolean isVal,
                             List<DestructuringEntry> entries, Expression initializer) {
        super(location, Collections.<Annotation>emptyList(), Collections.<Modifier>emptyList(), null);
        this.isVal = isVal;
        this.entries = entries;
        this.initializer = initializer;
    }

    public boolean isVal() {
        return isVal;
    }

    public List<DestructuringEntry> getEntries() {
        return entries;
    }

    /** 兼容方法：提取所有 localName（用于未适配新 API 的代码） */
    public List<String> getNames() {
        List<String> names = new ArrayList<String>();
        for (DestructuringEntry e : entries) names.add(e.getLocalName());
        return names;
    }

    public Expression getInitializer() {
        return initializer;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitDestructuringDecl(this, context);
    }
}
