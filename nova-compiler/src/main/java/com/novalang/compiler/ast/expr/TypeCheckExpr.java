package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.hirtype.HirType;

/**
 * 类型检查表达式（is / !is）
 */
public class TypeCheckExpr extends Expression {
    private final Expression operand;
    private final TypeRef targetType;
    private final boolean negated;  // !is
    private HirType hirTargetType;  // HIR lowering 时设置

    public TypeCheckExpr(SourceLocation location, Expression operand, TypeRef targetType, boolean negated) {
        super(location);
        this.operand = operand;
        this.targetType = targetType;
        this.negated = negated;
    }

    public Expression getOperand() {
        return operand;
    }

    public TypeRef getTargetType() {
        return targetType;
    }

    public boolean isNegated() {
        return negated;
    }

    public HirType getHirTargetType() {
        return hirTargetType;
    }

    public void setHirTargetType(HirType hirTargetType) {
        this.hirTargetType = hirTargetType;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitTypeCheckExpr(this, context);
    }
}
