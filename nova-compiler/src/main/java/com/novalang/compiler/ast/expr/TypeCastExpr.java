package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;
import com.novalang.compiler.hirtype.HirType;

/**
 * 类型转换表达式（as / as?）
 */
public class TypeCastExpr extends Expression {
    private final Expression operand;
    private final TypeRef targetType;
    private final boolean isSafe;  // as?
    private HirType hirTargetType;  // HIR lowering 时设置

    public TypeCastExpr(SourceLocation location, Expression operand, TypeRef targetType, boolean isSafe) {
        super(location);
        this.operand = operand;
        this.targetType = targetType;
        this.isSafe = isSafe;
    }

    public Expression getOperand() {
        return operand;
    }

    public TypeRef getTargetType() {
        return targetType;
    }

    public boolean isSafe() {
        return isSafe;
    }

    public HirType getHirTargetType() {
        return hirTargetType;
    }

    public void setHirTargetType(HirType hirTargetType) {
        this.hirTargetType = hirTargetType;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitTypeCastExpr(this, context);
    }
}
