package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 函数声明，合并 FunDecl + ConstructorDecl。
 */
public class HirFunction extends HirDecl {

    private final List<String> typeParams;
    private final HirType receiverType;     // 扩展函数接收者类型
    private final List<HirParam> params;
    private final HirType returnType;
    private final AstNode body;             // Block 或 Expression
    private final boolean isConstructor;
    private final List<Expression> delegationArgs; // 次级构造器 this(...) 委托参数
    private final Set<String> reifiedTypeParams; // reified 类型参数名称集合

    public HirFunction(SourceLocation location, String name, Set<Modifier> modifiers,
                       List<HirAnnotation> annotations, List<String> typeParams,
                       HirType receiverType, List<HirParam> params, HirType returnType,
                       AstNode body, boolean isConstructor) {
        this(location, name, modifiers, annotations, typeParams, receiverType,
             params, returnType, body, isConstructor, null, Collections.emptySet());
    }

    public HirFunction(SourceLocation location, String name, Set<Modifier> modifiers,
                       List<HirAnnotation> annotations, List<String> typeParams,
                       HirType receiverType, List<HirParam> params, HirType returnType,
                       AstNode body, boolean isConstructor, List<Expression> delegationArgs) {
        this(location, name, modifiers, annotations, typeParams, receiverType,
             params, returnType, body, isConstructor, delegationArgs, Collections.emptySet());
    }

    public HirFunction(SourceLocation location, String name, Set<Modifier> modifiers,
                       List<HirAnnotation> annotations, List<String> typeParams,
                       HirType receiverType, List<HirParam> params, HirType returnType,
                       AstNode body, boolean isConstructor, List<Expression> delegationArgs,
                       Set<String> reifiedTypeParams) {
        super(location, name, modifiers, annotations);
        this.typeParams = typeParams;
        this.receiverType = receiverType;
        this.params = params;
        this.returnType = returnType;
        this.body = body;
        this.isConstructor = isConstructor;
        this.delegationArgs = delegationArgs;
        this.reifiedTypeParams = reifiedTypeParams != null ? reifiedTypeParams : Collections.emptySet();
    }

    public List<String> getTypeParams() {
        return typeParams;
    }

    public HirType getReceiverType() {
        return receiverType;
    }

    public boolean isExtensionFunction() {
        return receiverType != null;
    }

    public List<HirParam> getParams() {
        return params;
    }

    public HirType getReturnType() {
        return returnType;
    }

    public AstNode getBody() {
        return body;
    }

    public boolean isConstructor() {
        return isConstructor;
    }

    public boolean hasDelegation() {
        return delegationArgs != null && !delegationArgs.isEmpty();
    }

    public List<Expression> getDelegationArgs() {
        return delegationArgs;
    }

    public Set<String> getReifiedTypeParams() {
        return reifiedTypeParams;
    }

    public boolean hasReifiedTypeParams() {
        return !reifiedTypeParams.isEmpty();
    }

    public boolean isInline() {
        return hasModifier(Modifier.INLINE);
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitFunction(this, context);
    }
}
