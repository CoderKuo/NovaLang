package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 安全调用表达式（如 obj?.property, obj?.method()）
 */
public class SafeCallExpr extends Expression {
    private final Expression target;
    private final String member;
    private final List<CallExpr.Argument> args;  // 可选，如果是方法调用

    public SafeCallExpr(SourceLocation location, Expression target, String member,
                        List<CallExpr.Argument> args) {
        super(location);
        this.target = target;
        this.member = member;
        this.args = args;
    }

    public Expression getTarget() {
        return target;
    }

    public String getMember() {
        return member;
    }

    public List<CallExpr.Argument> getArgs() {
        return args;
    }

    public boolean isMethodCall() {
        return args != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitSafeCallExpr(this, context);
    }
}
