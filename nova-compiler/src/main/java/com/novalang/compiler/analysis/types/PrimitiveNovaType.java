package com.novalang.compiler.analysis.types;

import java.util.Objects;

/**
 * 原始类型: Int, Long, Float, Double, Boolean, Char
 */
public final class PrimitiveNovaType extends NovaType {

    private final String name;

    public PrimitiveNovaType(String name, boolean nullable) {
        super(nullable);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    @Override
    public String getTypeName() {
        return name;
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        if (this.nullable == nullable) return this;
        return new PrimitiveNovaType(name, nullable);
    }

    @Override
    public String toDisplayString() {
        return nullable ? name + "?" : name;
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitPrimitive(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PrimitiveNovaType)) return false;
        PrimitiveNovaType that = (PrimitiveNovaType) o;
        return nullable == that.nullable && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, nullable);
    }
}
