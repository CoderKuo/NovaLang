package com.novalang.compiler.ast.type;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.analysis.types.NovaType;

/**
 * 类型引用基类
 */
public abstract class TypeRef extends AstNode {
    // 语义分析后解析的结构化类型
    protected NovaType resolvedType;

    protected TypeRef(SourceLocation location) {
        super(location);
    }

    public NovaType getResolvedType() {
        return resolvedType;
    }

    public void setResolvedType(NovaType type) {
        this.resolvedType = type;
    }

    /** 接受轻量 TypeRefVisitor 进行类型引用分派 */
    public abstract <R> R accept(TypeRefVisitor<R> visitor);
}
