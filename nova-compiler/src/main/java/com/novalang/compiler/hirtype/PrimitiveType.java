package com.novalang.compiler.hirtype;

/**
 * 原始类型：INT, LONG, FLOAT, DOUBLE, BOOLEAN, CHAR, UNIT, NOTHING。
 */
public class PrimitiveType extends HirType {

    public enum Kind {
        INT, LONG, FLOAT, DOUBLE, BOOLEAN, CHAR, UNIT, NOTHING
    }

    private final Kind kind;

    public PrimitiveType(Kind kind, boolean nullable) {
        super(nullable);
        this.kind = kind;
    }

    public PrimitiveType(Kind kind) {
        this(kind, false);
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public HirType withNullable(boolean nullable) {
        return new PrimitiveType(kind, nullable);
    }

    @Override
    public String toString() {
        return kind.name() + (nullable ? "?" : "");
    }
}
