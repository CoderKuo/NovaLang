package com.novalang.compiler.analysis.types;

/**
 * 错误类型 — 类型检查出错时的占位符，与任何类型兼容。
 */
public final class ErrorType extends NovaType {

    public static final ErrorType INSTANCE = new ErrorType();

    private ErrorType() {
        super(false);
    }

    @Override
    public String getTypeName() {
        return null;
    }

    @Override
    public NovaType withNullable(boolean nullable) {
        return this;
    }

    @Override
    public String toDisplayString() {
        return "<error>";
    }

    @Override
    public <R> R accept(NovaTypeVisitor<R> visitor) {
        return visitor.visitError(this);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof ErrorType;
    }

    @Override
    public int hashCode() {
        return -1;
    }
}
