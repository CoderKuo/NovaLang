package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 函数参数
 */
public class Parameter extends AstNode {
    private final List<Annotation> annotations;
    private final List<Modifier> modifiers;
    private final String name;
    private final TypeRef type;
    private final Expression defaultValue;  // 可选
    private final boolean isVararg;
    private final boolean isProperty;  // 主构造器属性参数 (val/var)
    /** 名称标识符的精确位置（语义令牌用），null 表示未设置 */
    private SourceLocation nameLocation;

    public Parameter(SourceLocation location, List<Annotation> annotations,
                     String name, TypeRef type, Expression defaultValue, boolean isVararg) {
        this(location, annotations, Collections.emptyList(), name, type, defaultValue, isVararg, false);
    }

    public Parameter(SourceLocation location, List<Annotation> annotations,
                     String name, TypeRef type, Expression defaultValue, boolean isVararg,
                     boolean isProperty) {
        this(location, annotations, Collections.emptyList(), name, type, defaultValue, isVararg, isProperty);
    }

    public Parameter(SourceLocation location, List<Annotation> annotations, List<Modifier> modifiers,
                     String name, TypeRef type, Expression defaultValue, boolean isVararg,
                     boolean isProperty) {
        super(location);
        this.annotations = annotations;
        this.modifiers = modifiers != null ? modifiers : Collections.emptyList();
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
        this.isVararg = isVararg;
        this.isProperty = isProperty;
    }

    public List<Annotation> getAnnotations() {
        return annotations;
    }

    public List<Modifier> getModifiers() {
        return modifiers;
    }

    public boolean hasModifier(Modifier modifier) {
        return modifiers.contains(modifier);
    }

    public String getName() {
        return name;
    }

    public TypeRef getType() {
        return type;
    }

    public Expression getDefaultValue() {
        return defaultValue;
    }

    public boolean hasDefaultValue() {
        return defaultValue != null;
    }

    public boolean isVararg() {
        return isVararg;
    }

    /**
     * 是否为主构造器的属性参数（使用 val/var 声明）
     */
    public boolean isProperty() {
        return isProperty;
    }

    public SourceLocation getNameLocation() {
        return nameLocation;
    }

    public void setNameLocation(SourceLocation loc) {
        this.nameLocation = loc;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitParameter(this, context);
    }
}
