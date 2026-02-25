package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 函数类型（如 (Int, String) -> Boolean）
 */
public final class FunctionType extends TypeRef {
    private final TypeRef receiverType;     // 可选，扩展函数类型
    private final List<TypeRef> paramTypes;
    private final TypeRef returnType;
    private final boolean isSuspend;

    public FunctionType(SourceLocation location, TypeRef receiverType,
                        List<TypeRef> paramTypes, TypeRef returnType, boolean isSuspend) {
        super(location);
        this.receiverType = receiverType;
        this.paramTypes = paramTypes;
        this.returnType = returnType;
        this.isSuspend = isSuspend;
    }

    public TypeRef getReceiverType() {
        return receiverType;
    }

    public boolean hasReceiverType() {
        return receiverType != null;
    }

    public List<TypeRef> getParamTypes() {
        return paramTypes;
    }

    public TypeRef getReturnType() {
        return returnType;
    }

    public boolean isSuspend() {
        return isSuspend;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitFunctionType(this, context);
    }

    @Override
    public <R> R accept(TypeRefVisitor<R> visitor) {
        return visitor.visitFunction(this);
    }
}
