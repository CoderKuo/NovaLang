package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 类型参数（泛型参数）
 */
public final class TypeArgument extends AstNode {
    private final Variance variance;  // in/out/无
    private final TypeRef type;       // 或 * 通配符（type为null）
    private final boolean isWildcard;

    public TypeArgument(SourceLocation location, Variance variance, TypeRef type, boolean isWildcard) {
        super(location);
        this.variance = variance;
        this.type = type;
        this.isWildcard = isWildcard;
    }

    public Variance getVariance() {
        return variance;
    }

    public TypeRef getType() {
        return type;
    }

    public boolean isWildcard() {
        return isWildcard;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }

    /**
     * 型变标记
     */
    public enum Variance {
        INVARIANT,  // 不变
        IN,         // 逆变
        OUT         // 协变
    }
}
