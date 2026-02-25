package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.QualifiedName;

/**
 * 简单类型（如 Int, String, MyClass）
 */
public final class SimpleType extends TypeRef {
    private final QualifiedName name;

    public SimpleType(SourceLocation location, QualifiedName name) {
        super(location);
        this.name = name;
    }

    public QualifiedName getName() {
        return name;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitSimpleType(this, context);
    }

    @Override
    public <R> R accept(TypeRefVisitor<R> visitor) {
        return visitor.visitSimple(this);
    }
}
