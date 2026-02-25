package com.novalang.compiler.analysis.types;

import java.util.Objects;

/**
 * 泛型体内引用未替换的类型参数 T。
 */
public final class TypeParameterType extends NovaType {

    private final String name;
    private final NovaType upperBound;  // 默认 Any

    public TypeParameterType(String name, NovaType upperBound, boolean nullable) {
        super(nullable);
        this.name = name;
        this.upperBound = upperBound != null ? upperBound : NovaTypes.ANY;
    }

    public String getName() {
        return name;
    }

    public NovaType getUpperBound() {
        return upperBound;
    }

    @Override
    public String getTypeName() {
        return name;
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        if (this.nullable == nullable) return this;
        return new TypeParameterType(name, upperBound, nullable);
    }

    @Override
    public String toDisplayString() {
        return nullable ? name + "?" : name;
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitTypeParameter(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TypeParameterType)) return false;
        TypeParameterType that = (TypeParameterType) o;
        return nullable == that.nullable && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, nullable);
    }
}
