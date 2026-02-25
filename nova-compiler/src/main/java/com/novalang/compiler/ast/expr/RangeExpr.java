package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

/**
 * 范围表达式（如 1..10, 0..<n）
 */
public class RangeExpr extends Expression {
    private final Expression start;
    private final Expression end;
    private final Expression step;       // 可选
    private final boolean isEndExclusive;  // ..<

    public RangeExpr(SourceLocation location, Expression start, Expression end,
                     Expression step, boolean isEndExclusive) {
        super(location);
        this.start = start;
        this.end = end;
        this.step = step;
        this.isEndExclusive = isEndExclusive;
    }

    public Expression getStart() {
        return start;
    }

    public Expression getEnd() {
        return end;
    }

    public Expression getStep() {
        return step;
    }

    public boolean hasStep() {
        return step != null;
    }

    public boolean isEndExclusive() {
        return isEndExclusive;
    }

    public boolean isExclusive() {
        return isEndExclusive;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitRangeExpr(this, context);
    }
}
