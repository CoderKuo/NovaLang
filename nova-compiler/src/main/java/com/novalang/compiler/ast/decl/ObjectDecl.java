package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.List;

/**
 * 对象声明（单例）
 */
public class ObjectDecl extends Declaration {
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;
    private final boolean isCompanion;

    public ObjectDecl(SourceLocation location, List<Annotation> annotations,
                      List<Modifier> modifiers, String name,
                      List<TypeRef> superTypes, List<Declaration> members,
                      boolean isCompanion) {
        super(location, annotations, modifiers, name);
        this.superTypes = superTypes;
        this.members = members;
        this.isCompanion = isCompanion;
    }

    public List<TypeRef> getSuperTypes() {
        return superTypes;
    }

    public List<Declaration> getMembers() {
        return members;
    }

    public boolean isCompanion() {
        return isCompanion;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitObjectDecl(this, context);
    }
}
