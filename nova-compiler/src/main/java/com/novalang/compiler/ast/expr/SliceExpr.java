package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 切片表达式（如 arr[1..3], arr[..5]）
 */
public class SliceExpr extends Expression {
    private final Expression target;
    private final Expression start;           // 可选
    private final Expression end;             // 可选
    private final boolean isEndExclusive;     // ..<

    public SliceExpr(SourceLocation location, Expression target,
                     Expression start, Expression end, boolean isEndExclusive) {
        super(location);
        this.target = target;
        this.start = start;
        this.end = end;
        this.isEndExclusive = isEndExclusive;
    }

    public Expression getTarget() {
        return target;
    }

    public Expression getStart() {
        return start;
    }

    public boolean hasStart() {
        return start != null;
    }

    public Expression getEnd() {
        return end;
    }

    public boolean hasEnd() {
        return end != null;
    }

    public boolean isEndExclusive() {
        return isEndExclusive;
    }

    public boolean isExclusive() {
        return isEndExclusive;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitSliceExpr(this, context);
    }
}
