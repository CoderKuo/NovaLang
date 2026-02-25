package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.ast.type.TypeParameter;

import java.util.List;

/**
 * 函数声明
 */
public class FunDecl extends Declaration {
    private final List<TypeParameter> typeParams;
    private final TypeRef receiverType;          // 扩展函数接收者
    private final List<Parameter> params;
    private final TypeRef returnType;            // 可选，默认 Unit
    private final AstNode body;                  // Block 或 Expression
    private final boolean isInline;
    private final boolean isOperator;
    private final boolean isSuspend;
    private int simpleParams = -1; // -1=未计算, 0=有复杂参数, 1=全是简单位置参数

    public FunDecl(SourceLocation location, List<Annotation> annotations,
                   List<Modifier> modifiers, String name, List<TypeParameter> typeParams,
                   TypeRef receiverType, List<Parameter> params, TypeRef returnType,
                   AstNode body, boolean isInline, boolean isOperator, boolean isSuspend) {
        super(location, annotations, modifiers, name);
        this.typeParams = typeParams;
        this.receiverType = receiverType;
        this.params = params;
        this.returnType = returnType;
        this.body = body;
        this.isInline = isInline;
        this.isOperator = isOperator;
        this.isSuspend = isSuspend;
    }

    public List<TypeParameter> getTypeParams() {
        return typeParams;
    }

    public TypeRef getReceiverType() {
        return receiverType;
    }

    public boolean isExtensionFunction() {
        return receiverType != null;
    }

    public List<Parameter> getParams() {
        return params;
    }

    public TypeRef getReturnType() {
        return returnType;
    }

    public AstNode getBody() {
        return body;
    }

    public boolean isInline() {
        return isInline;
    }

    public boolean isOperator() {
        return isOperator;
    }

    public boolean isSuspend() {
        return isSuspend;
    }

    /** 是否所有参数都是简单位置参数（无 vararg、无默认值） */
    public boolean isSimpleParams() {
        if (simpleParams < 0) {
            simpleParams = 1;
            for (Parameter p : params) {
                if (p.isVararg() || p.hasDefaultValue()) {
                    simpleParams = 0;
                    break;
                }
            }
        }
        return simpleParams == 1;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitFunDecl(this, context);
    }
}
