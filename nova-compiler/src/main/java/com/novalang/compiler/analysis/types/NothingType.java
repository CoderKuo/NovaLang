package com.novalang.compiler.analysis.types;

/**
 * Nothing 类型 — 所有类型的子类型。
 * null 字面量的类型为 Nothing?。
 */
public final class NothingType extends NovaType {

    public static final NothingType INSTANCE = new NothingType(false);
    public static final NothingType NULLABLE = new NothingType(true);

    private NothingType(boolean nullable) {
        super(nullable);
    }

    @Override
    public String getTypeName() {
        return "Nothing";
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        return nullable ? NULLABLE : INSTANCE;
    }

    @Override
    public String toDisplayString() {
        return nullable ? "Nothing?" : "Nothing";
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitNothing(this);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return o instanceof NothingType && nullable == ((NothingType) o).nullable;
    }

    @Override
    public int hashCode() {
        return nullable ? 1 : 0;
    }
}
