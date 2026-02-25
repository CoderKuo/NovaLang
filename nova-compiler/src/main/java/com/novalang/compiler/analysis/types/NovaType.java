package com.novalang.compiler.analysis.types;

/**
 * 结构化类型表示基类。
 * 与现有的字符串类型名并行工作，用于类型兼容性检查。
 */
public abstract class NovaType {

    protected final boolean nullable;

    protected NovaType(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean isNullable() {
        return nullable;
    }

    /** 返回一个相同类型但可空性不同的副本 */
    public abstract NovaType withNullable(boolean nullable);

    /**
     * 返回类型的简单名称（如 "Int", "String", "Nothing", "Unit"）。
     * 函数类型和错误类型返回 null。
     */
    public abstract String getTypeName();

    /** 人类可读的类型名，用于诊断消息 */
    public abstract String toDisplayString();

    /** 接受 NovaTypeVisitor 进行类型分派 */
    public abstract <R> R accept(NovaTypeVisitor<R> visitor);

    @Override
    public String toString() {
        return toDisplayString();
    }

    @Override
    public abstract boolean equals(Object o);

    @Override
    public abstract int hashCode();
}
