package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.ast.type.TypeParameter;

import java.util.List;

/**
 * 类型别名声明
 */
public class TypeAliasDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final TypeRef aliasedType;

    public TypeAliasDecl(SourceLocation location, List<Annotation> annotations,
                         List<Modifier> modifiers, String name,
                         List<TypeParameter> typeParams, TypeRef aliasedType) {
        super(location, annotations, modifiers, name);
        this.typeParams = typeParams;
        this.aliasedType = aliasedType;
    }

    public List<TypeParameter> getTypeParams() {
        return typeParams;
    }

    public TypeRef getAliasedType() {
        return aliasedType;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitTypeAliasDecl(this, context);
    }
}
