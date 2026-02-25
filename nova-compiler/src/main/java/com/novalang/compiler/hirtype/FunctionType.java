package com.novalang.compiler.hirtype;

import java.util.List;

/**
 * 函数类型：(ReceiverType.)?(ParamTypes) -> ReturnType
 */
public class FunctionType extends HirType {

    private final HirType receiverType;   // nullable
    private final List<HirType> paramTypes;
    private final HirType returnType;

    public FunctionType(HirType receiverType, List<HirType> paramTypes,
                        HirType returnType, boolean nullable) {
        super(nullable);
        this.receiverType = receiverType;
        this.paramTypes = paramTypes;
        this.returnType = returnType;
    }

    public HirType getReceiverType() {
        return receiverType;
    }

    public List<HirType> getParamTypes() {
        return paramTypes;
    }

    public HirType getReturnType() {
        return returnType;
    }

    @Override
    public HirType withNullable(boolean nullable) {
        return new FunctionType(receiverType, paramTypes, returnType, nullable);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (receiverType != null) {
            sb.append(receiverType).append('.');
        }
        sb.append('(');
        for (int i = 0; i < paramTypes.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(paramTypes.get(i));
        }
        sb.append(") -> ").append(returnType);
        if (nullable) sb.append('?');
        return sb.toString();
    }
}
