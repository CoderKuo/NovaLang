package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.QualifiedName;

import java.util.List;

/**
 * 泛型类型（如 List<String>, Map<String, Int>）
 */
public final class GenericType extends TypeRef {
    private final QualifiedName name;
    private final List<TypeArgument> typeArgs;

    public GenericType(SourceLocation location, QualifiedName name, List<TypeArgument> typeArgs) {
        super(location);
        this.name = name;
        this.typeArgs = typeArgs;
    }

    public QualifiedName getName() {
        return name;
    }

    public List<TypeArgument> getTypeArgs() {
        return typeArgs;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitGenericType(this, context);
    }

    @Override
    public <R> R accept(TypeRefVisitor<R> visitor) {
        return visitor.visitGeneric(this);
    }
}
