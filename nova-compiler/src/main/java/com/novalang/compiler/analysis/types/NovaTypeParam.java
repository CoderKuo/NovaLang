package com.novalang.compiler.analysis.types;

import com.novalang.compiler.ast.type.TypeArgument;

/**
 * declaration-site 类型参数（如 class Box&lt;out T : Comparable&lt;T&gt;&gt; 中的 out T : Comparable&lt;T&gt;）。
 */
public final class NovaTypeParam {

    private final String name;
    private final TypeArgument.Variance variance;
    private final NovaType upperBound;    // 默认 Any
    private final boolean isReified;

    public NovaTypeParam(String name, TypeArgument.Variance variance, NovaType upperBound, boolean isReified) {
        this.name = name;
        this.variance = variance;
        this.upperBound = upperBound != null ? upperBound : NovaTypes.ANY;
        this.isReified = isReified;
    }

    public String getName() {
        return name;
    }

    public TypeArgument.Variance getVariance() {
        return variance;
    }

    public NovaType getUpperBound() {
        return upperBound;
    }

    public boolean isReified() {
        return isReified;
    }
}
