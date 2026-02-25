package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 类型参数声明（如 <T : Comparable<T>>）
 */
public final class TypeParameter extends AstNode {
    private final String name;
    private final TypeArgument.Variance variance;
    private final TypeRef upperBound;  // : SomeType
    private final boolean isReified;

    public TypeParameter(SourceLocation location, String name,
                         TypeArgument.Variance variance, TypeRef upperBound) {
        this(location, name, variance, upperBound, false);
    }

    public TypeParameter(SourceLocation location, String name,
                         TypeArgument.Variance variance, TypeRef upperBound, boolean isReified) {
        super(location);
        this.name = name;
        this.variance = variance;
        this.upperBound = upperBound;
        this.isReified = isReified;
    }

    public String getName() {
        return name;
    }

    public TypeArgument.Variance getVariance() {
        return variance;
    }

    public TypeRef getUpperBound() {
        return upperBound;
    }

    public boolean hasUpperBound() {
        return upperBound != null;
    }

    public boolean isReified() {
        return isReified;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
