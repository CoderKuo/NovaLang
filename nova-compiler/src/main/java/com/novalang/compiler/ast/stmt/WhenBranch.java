package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.List;

/**
 * When 分支
 */
public class WhenBranch extends AstNode {
    private final List<WhenCondition> conditions;
    private final Statement body;  // Block 或 ExpressionStmt

    public WhenBranch(SourceLocation location, List<WhenCondition> conditions, Statement body) {
        super(location);
        this.conditions = conditions;
        this.body = body;
    }

    public List<WhenCondition> getConditions() {
        return conditions;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }

    /**
     * When 条件基类
     */
    public static abstract class WhenCondition extends AstNode {
        protected WhenCondition(SourceLocation location) {
            super(location);
        }
    }

    /**
     * 表达式条件
     */
    public static final class ExpressionCondition extends WhenCondition {
        private final Expression expression;

        public ExpressionCondition(SourceLocation location, Expression expression) {
            super(location);
            this.expression = expression;
        }

        public Expression getExpression() {
            return expression;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }

    /**
     * 类型条件（is / !is）
     */
    public static final class TypeCondition extends WhenCondition {
        private final TypeRef type;
        private final boolean negated;  // !is

        public TypeCondition(SourceLocation location, TypeRef type, boolean negated) {
            super(location);
            this.type = type;
            this.negated = negated;
        }

        public TypeRef getType() {
            return type;
        }

        public boolean isNegated() {
            return negated;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }

    /**
     * 范围条件（in / !in）
     */
    public static final class RangeCondition extends WhenCondition {
        private final Expression range;
        private final boolean negated;  // !in

        public RangeCondition(SourceLocation location, Expression range, boolean negated) {
            super(location);
            this.range = range;
            this.negated = negated;
        }

        public Expression getRange() {
            return range;
        }

        public boolean isNegated() {
            return negated;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
