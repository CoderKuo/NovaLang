package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.ast.type.TypeParameter;

import java.util.List;

/**
 * 属性声明
 */
public class PropertyDecl extends Declaration {
    private final boolean isVal;                 // true=val, false=var
    private final List<TypeParameter> typeParams;
    private final TypeRef receiverType;          // 扩展属性
    private final TypeRef type;                  // 可选，可推断
    private final Expression initializer;        // 可选
    private final PropertyAccessor getter;       // 可选
    private final PropertyAccessor setter;       // 可选
    private final boolean isConst;
    private final boolean isLazy;

    public PropertyDecl(SourceLocation location, List<Annotation> annotations,
                        List<Modifier> modifiers, String name, boolean isVal,
                        List<TypeParameter> typeParams, TypeRef receiverType,
                        TypeRef type, Expression initializer,
                        PropertyAccessor getter, PropertyAccessor setter,
                        boolean isConst, boolean isLazy) {
        super(location, annotations, modifiers, name);
        this.isVal = isVal;
        this.typeParams = typeParams;
        this.receiverType = receiverType;
        this.type = type;
        this.initializer = initializer;
        this.getter = getter;
        this.setter = setter;
        this.isConst = isConst;
        this.isLazy = isLazy;
    }

    public boolean isVal() {
        return isVal;
    }

    public boolean isVar() {
        return !isVal;
    }

    public List<TypeParameter> getTypeParams() {
        return typeParams;
    }

    public TypeRef getReceiverType() {
        return receiverType;
    }

    public boolean isExtensionProperty() {
        return receiverType != null;
    }

    public TypeRef getType() {
        return type;
    }

    public Expression getInitializer() {
        return initializer;
    }

    public boolean hasInitializer() {
        return initializer != null;
    }

    public PropertyAccessor getGetter() {
        return getter;
    }

    public PropertyAccessor getSetter() {
        return setter;
    }

    public boolean isConst() {
        return isConst;
    }

    public boolean isLazy() {
        return isLazy;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitPropertyDecl(this, context);
    }
}
