package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 成员访问表达式（如 obj.property）
 */
public class MemberExpr extends Expression {
    private final Expression target;
    private final String member;
    /** 成员名标识符的精确位置（不含 '.'） */
    private SourceLocation memberLocation;

    public MemberExpr(SourceLocation location, Expression target, String member) {
        super(location);
        this.target = target;
        this.member = member;
    }

    public Expression getTarget() {
        return target;
    }

    public String getMember() {
        return member;
    }

    public SourceLocation getMemberLocation() {
        return memberLocation;
    }

    public void setMemberLocation(SourceLocation loc) {
        this.memberLocation = loc;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitMemberExpr(this, context);
    }
}
