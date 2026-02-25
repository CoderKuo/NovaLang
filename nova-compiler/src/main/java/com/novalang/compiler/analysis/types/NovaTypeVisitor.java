package com.novalang.compiler.analysis.types;

/**
 * NovaType 访问者接口，用于替代 instanceof 分派。
 */
public interface NovaTypeVisitor<R> {
    R visitPrimitive(PrimitiveNovaType type);
    R visitClass(ClassNovaType type);
    R visitFunction(FunctionNovaType type);
    R visitTypeParameter(TypeParameterType type);
    R visitNothing(NothingType type);
    R visitUnit(UnitType type);
    R visitError(ErrorType type);
}
