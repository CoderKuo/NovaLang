package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.ast.type.TypeParameter;

import java.util.Collections;
import java.util.List;

/**
 * 类声明
 */
public class ClassDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final List<Parameter> primaryConstructorParams;  // 可选
    private final List<TypeRef> superTypes;
    private final List<Expression> superConstructorArgs;
    private final List<Declaration> members;
    private final boolean isSealed;
    private final boolean isAbstract;
    private final boolean isOpen;
    private final boolean isAnnotation;

    public ClassDecl(SourceLocation location, List<Annotation> annotations,
                     List<Modifier> modifiers, String name, List<TypeParameter> typeParams,
                     List<Parameter> primaryConstructorParams, List<TypeRef> superTypes,
                     List<Declaration> members, boolean isSealed, boolean isAbstract, boolean isOpen) {
        this(location, annotations, modifiers, name, typeParams, primaryConstructorParams,
             superTypes, Collections.<Expression>emptyList(), members, isSealed, isAbstract, isOpen, false);
    }

    public ClassDecl(SourceLocation location, List<Annotation> annotations,
                     List<Modifier> modifiers, String name, List<TypeParameter> typeParams,
                     List<Parameter> primaryConstructorParams, List<TypeRef> superTypes,
                     List<Declaration> members, boolean isSealed, boolean isAbstract, boolean isOpen,
                     boolean isAnnotation) {
        this(location, annotations, modifiers, name, typeParams, primaryConstructorParams,
             superTypes, Collections.<Expression>emptyList(), members, isSealed, isAbstract, isOpen, isAnnotation);
    }

    public ClassDecl(SourceLocation location, List<Annotation> annotations,
                     List<Modifier> modifiers, String name, List<TypeParameter> typeParams,
                     List<Parameter> primaryConstructorParams, List<TypeRef> superTypes,
                     List<Expression> superConstructorArgs,
                     List<Declaration> members, boolean isSealed, boolean isAbstract, boolean isOpen,
                     boolean isAnnotation) {
        super(location, annotations, modifiers, name);
        this.typeParams = typeParams;
        this.primaryConstructorParams = primaryConstructorParams;
        this.superTypes = superTypes;
        this.superConstructorArgs = superConstructorArgs;
        this.members = members;
        this.isSealed = isSealed;
        this.isAbstract = isAbstract;
        this.isOpen = isOpen;
        this.isAnnotation = isAnnotation;
    }

    public List<TypeParameter> getTypeParams() {
        return typeParams;
    }

    public List<Parameter> getPrimaryConstructorParams() {
        return primaryConstructorParams;
    }

    public boolean hasPrimaryConstructor() {
        return primaryConstructorParams != null;
    }

    public List<TypeRef> getSuperTypes() {
        return superTypes;
    }

    public List<Expression> getSuperConstructorArgs() {
        return superConstructorArgs;
    }

    public List<Declaration> getMembers() {
        return members;
    }

    public boolean isSealed() {
        return isSealed;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isOpen() {
        return isOpen;
    }

    public boolean isAnnotation() {
        return isAnnotation;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitClassDecl(this, context);
    }
}
