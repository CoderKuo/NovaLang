package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.ast.type.TypeParameter;

import java.util.List;

/**
 * 接口声明
 */
public class InterfaceDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;

    public InterfaceDecl(SourceLocation location, List<Annotation> annotations,
                         List<Modifier> modifiers, String name,
                         List<TypeParameter> typeParams, List<TypeRef> superTypes,
                         List<Declaration> members) {
        super(location, annotations, modifiers, name);
        this.typeParams = typeParams;
        this.superTypes = superTypes;
        this.members = members;
    }

    public List<TypeParameter> getTypeParams() {
        return typeParams;
    }

    public List<TypeRef> getSuperTypes() {
        return superTypes;
    }

    public List<Declaration> getMembers() {
        return members;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitInterfaceDecl(this, context);
    }
}
