package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 可空类型（如 String?）
 */
public final class NullableType extends TypeRef {
    private final TypeRef innerType;

    public NullableType(SourceLocation location, TypeRef innerType) {
        super(location);
        this.innerType = innerType;
    }

    public TypeRef getInnerType() {
        return innerType;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitNullableType(this, context);
    }

    @Override
    public <R> R accept(TypeRefVisitor<R> visitor) {
        return visitor.visitNullable(this);
    }
}
