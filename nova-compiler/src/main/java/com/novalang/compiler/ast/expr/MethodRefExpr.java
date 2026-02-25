package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;

/**
 * 方法引用表达式（如 String::length, ::println）
 */
public class MethodRefExpr extends Expression {
    private final Expression target;     // 可选，实例
    private final TypeRef typeTarget;    // 可选，Class::method
    private final String methodName;
    private final boolean isConstructor; // ::new

    public MethodRefExpr(SourceLocation location, Expression target, TypeRef typeTarget,
                         String methodName, boolean isConstructor) {
        super(location);
        this.target = target;
        this.typeTarget = typeTarget;
        this.methodName = methodName;
        this.isConstructor = isConstructor;
    }

    public Expression getTarget() {
        return target;
    }

    public Expression getReceiver() {
        return target;
    }

    public TypeRef getTypeTarget() {
        return typeTarget;
    }

    public String getMethodName() {
        return methodName;
    }

    public boolean isConstructor() {
        return isConstructor;
    }

    public boolean hasTarget() {
        return target != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitMethodRefExpr(this, context);
    }
}
