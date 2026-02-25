package com.novalang.compiler.analysis.types;

import java.util.List;
import java.util.Objects;

/**
 * 函数类型: (P1, P2) -> R, T.() -> R
 */
public final class FunctionNovaType extends NovaType {

    private final NovaType receiverType;     // 可选，扩展函数类型
    private final List<NovaType> paramTypes;
    private final NovaType returnType;

    public FunctionNovaType(NovaType receiverType, List<NovaType> paramTypes,
                            NovaType returnType, boolean nullable) {
        super(nullable);
        this.receiverType = receiverType;
        this.paramTypes = paramTypes;
        this.returnType = returnType;
    }

    public NovaType getReceiverType() {
        return receiverType;
    }

    public boolean hasReceiverType() {
        return receiverType != null;
    }

    public List<NovaType> getParamTypes() {
        return paramTypes;
    }

    public NovaType getReturnType() {
        return returnType;
    }

    @Override
    public String getTypeName() {
        return null;
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        if (this.nullable == nullable) return this;
        return new FunctionNovaType(receiverType, paramTypes, returnType, nullable);
    }

    @Override
    public String toDisplayString() {
        StringBuilder sb = new StringBuilder();
        if (receiverType != null) {
            sb.append(receiverType.toDisplayString()).append('.');
        }
        sb.append('(');
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i).toDisplayString());
        }
        sb.append(") -> ");
        sb.append(returnType.toDisplayString());
        if (nullable) {
            return "(" + sb.toString() + ")?";
        }
        return sb.toString();
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitFunction(this);
    }

    @Override
    public boolean equals(Object o) {
        FunctionNovaType that = (FunctionNovaType) o;
        return nullable == that.nullable
                && Objects.equals(receiverType, that.receiverType)
                && paramTypes.equals(that.paramTypes)
                && returnType.equals(that.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(receiverType, paramTypes, returnType, nullable);
    }
}
