package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.Declaration;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.Collections;
import java.util.List;

/**
 * 匿名对象表达式
 */
public class ObjectLiteralExpr extends Expression {
    private final List<TypeRef> superTypes;
    private final List<Declaration> members;
    private final List<Expression> superConstructorArgs;

    public ObjectLiteralExpr(SourceLocation location, List<TypeRef> superTypes,
                             List<Declaration> members) {
        this(location, superTypes, members, Collections.emptyList());
    }

    public ObjectLiteralExpr(SourceLocation location, List<TypeRef> superTypes,
                             List<Declaration> members, List<Expression> superConstructorArgs) {
        super(location);
        this.superTypes = superTypes;
        this.members = members;
        this.superConstructorArgs = superConstructorArgs;
    }

    public List<TypeRef> getSuperTypes() {
        return superTypes;
    }

    public List<Declaration> getMembers() {
        return members;
    }

    public List<Expression> getSuperConstructorArgs() {
        return superConstructorArgs;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitObjectLiteralExpr(this, context);
    }
}
