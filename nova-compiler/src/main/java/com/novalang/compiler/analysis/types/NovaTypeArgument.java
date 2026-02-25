package com.novalang.compiler.analysis.types;

import com.novalang.compiler.ast.type.TypeArgument;

import java.util.Objects;

/**
 * use-site 类型参数（如 List&lt;out String&gt; 中的 out String）。
 */
public final class NovaTypeArgument {

    private final TypeArgument.Variance variance;
    private final NovaType type;       // null 表示 * 通配符
    private final boolean isWildcard;

    public NovaTypeArgument(TypeArgument.Variance variance, NovaType type, boolean isWildcard) {
        this.variance = variance;
        this.type = type;
        this.isWildcard = isWildcard;
    }

    public static NovaTypeArgument invariant(NovaType type) {
        return new NovaTypeArgument(TypeArgument.Variance.INVARIANT, type, false);
    }

    public static NovaTypeArgument wildcard() {
        return new NovaTypeArgument(TypeArgument.Variance.INVARIANT, null, true);
    }

    public TypeArgument.Variance getVariance() {
        return variance;
    }

    public NovaType getType() {
        return type;
    }

    public boolean isWildcard() {
        return isWildcard;
    }

    public String toDisplayString() {
        if (isWildcard) return "*";
        StringBuilder sb = new StringBuilder();
        if (variance == TypeArgument.Variance.IN) sb.append("in ");
        else if (variance == TypeArgument.Variance.OUT) sb.append("out ");
        if (type != null) sb.append(type.toDisplayString());
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NovaTypeArgument)) return false;
        NovaTypeArgument that = (NovaTypeArgument) o;
        return isWildcard == that.isWildcard && variance == that.variance && Objects.equals(type, that.type);
    }

    @Override
    public int hashCode() {
        return Objects.hash(variance, type, isWildcard);
    }
}
