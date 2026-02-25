package com.novalang.compiler.analysis.types;

/**
 * Unit 类型 — 表示无返回值。
 */
public final class UnitType extends NovaType {

    public static final UnitType INSTANCE = new UnitType();

    private UnitType() {
        super(false);
    }

    @Override
    public String getTypeName() {
        return "Unit";
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        // Unit 不支持可空
        return this;
    }

    @Override
    public String toDisplayString() {
        return "Unit";
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitUnit(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof UnitType;
    }

    @Override
    public int hashCode() {
        return 42;
    }
}
