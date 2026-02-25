package com.novalang.compiler.ast.type;

/**
 * TypeRef 轻量访问者接口，用于替代 instanceof 分派。
 */
public interface TypeRefVisitor<R> {
    R visitSimple(SimpleType type);
    R visitNullable(NullableType type);
    R visitGeneric(GenericType type);
    R visitFunction(FunctionType type);
}
