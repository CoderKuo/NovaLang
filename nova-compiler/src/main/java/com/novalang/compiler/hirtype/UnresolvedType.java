package com.novalang.compiler.hirtype;

/**
 * 未解析类型，后续 pass 处理。
 */
public class UnresolvedType extends HirType {

    private final String name;

    public UnresolvedType(String name, boolean nullable) {
        super(nullable);
        this.name = name;
    }

    public UnresolvedType(String name) {
        this(name, false);
    }

    public String getName() {
        return name;
    }

    @Override
    public HirType withNullable(boolean nullable) {
        return new UnresolvedType(name, nullable);
    }

    @Override
    public String toString() {
        return name + (nullable ? "?" : "");
    }
}
