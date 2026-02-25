package com.novalang.compiler.hirtype;

/**
 * HIR 类型基类。
 * 比 AST 的 TypeRef 更规范：nullable 是布尔标志而非包装类。
 */
public abstract class HirType {

    protected boolean nullable;

    protected HirType(boolean nullable) {
        this.nullable = nullable;
    }

    public boolean isNullable() {
        return nullable;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    /**
     * 返回该类型的非空版本。
     */
    public abstract HirType withNullable(boolean nullable);
}
