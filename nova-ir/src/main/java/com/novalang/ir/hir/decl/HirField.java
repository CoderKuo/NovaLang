package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.List;
import java.util.Set;

/**
 * 字段/属性声明（合并 PropertyDecl）。
 */
public class HirField extends HirDecl {

    private final HirType type;
    private final Expression initializer;
    private final boolean isVal;
    private final HirType receiverType; // 扩展属性的接收者类型
    private final AstNode getterBody;   // 自定义 getter 体
    private final AstNode setterBody;   // 自定义 setter 体
    private final HirParam setterParam; // setter 参数

    public HirField(SourceLocation location, String name, Set<Modifier> modifiers,
                    List<HirAnnotation> annotations, HirType type,
                    Expression initializer, boolean isVal) {
        this(location, name, modifiers, annotations, type, initializer, isVal, null, null, null, null);
    }

    public HirField(SourceLocation location, String name, Set<Modifier> modifiers,
                    List<HirAnnotation> annotations, HirType type,
                    Expression initializer, boolean isVal, HirType receiverType) {
        this(location, name, modifiers, annotations, type, initializer, isVal, receiverType, null, null, null);
    }

    public HirField(SourceLocation location, String name, Set<Modifier> modifiers,
                    List<HirAnnotation> annotations, HirType type,
                    Expression initializer, boolean isVal, HirType receiverType,
                    AstNode getterBody, AstNode setterBody, HirParam setterParam) {
        super(location, name, modifiers, annotations);
        this.type = type;
        this.initializer = initializer;
        this.isVal = isVal;
        this.receiverType = receiverType;
        this.getterBody = getterBody;
        this.setterBody = setterBody;
        this.setterParam = setterParam;
    }

    public HirType getType() {
        return type;
    }

    public Expression getInitializer() {
        return initializer;
    }

    public boolean hasInitializer() {
        return initializer != null;
    }

    public boolean isVal() {
        return isVal;
    }

    public boolean isVar() {
        return !isVal;
    }

    public HirType getReceiverType() {
        return receiverType;
    }

    public boolean isExtensionProperty() {
        return receiverType != null;
    }

    public AstNode getGetterBody() {
        return getterBody;
    }

    public boolean hasCustomGetter() {
        return getterBody != null;
    }

    public AstNode getSetterBody() {
        return setterBody;
    }

    public HirParam getSetterParam() {
        return setterParam;
    }

    public boolean hasCustomSetter() {
        return setterBody != null;
    }

    public boolean hasCustomAccessor() {
        return getterBody != null || setterBody != null;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitField(this, context);
    }
}
