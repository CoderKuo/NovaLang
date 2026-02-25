package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.hirtype.HirType;

/**
 * 表达式基类
 */
public abstract class Expression extends AstNode {
    // 类型信息（语义分析后填充）
    protected TypeRef resolvedType;
    // HIR 类型信息（lowering 后填充，nullable）
    protected HirType hirType;

    protected Expression(SourceLocation location) {
        super(location);
    }

    public TypeRef getResolvedType() {
        return resolvedType;
    }

    public void setResolvedType(TypeRef type) {
        this.resolvedType = type;
    }

    public HirType getHirType() {
        return hirType;
    }

    public void setHirType(HirType type) {
        this.hirType = type;
    }

    /**
     * HIR 类型的便捷访问器。HirExpr 子类会覆写此方法返回自己的 type 字段。
     * 这使得消费者可以对 Expression 类型的变量统一调用 getType()。
     */
    public HirType getType() {
        return hirType;
    }

    public void setType(HirType type) {
        this.hirType = type;
    }
}
