package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 字符串插值（如 "Hello, $name!"）
 */
public class StringInterpolation extends Expression {
    private final List<StringPart> parts;

    public StringInterpolation(SourceLocation location, List<StringPart> parts) {
        super(location);
        this.parts = parts;
    }

    public List<StringPart> getParts() {
        return parts;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitStringInterpolation(this, context);
    }

    /**
     * 字符串部分基类
     */
    public static abstract class StringPart extends AstNode {
        protected StringPart(SourceLocation location) {
            super(location);
        }
    }

    /**
     * 字符串字面量部分
     */
    public static final class LiteralPart extends StringPart {
        private final String value;

        public LiteralPart(SourceLocation location, String value) {
            super(location);
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }

    /**
     * 字符串表达式部分
     */
    public static final class ExprPart extends StringPart {
        private final Expression expression;

        public ExprPart(SourceLocation location, Expression expression) {
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
}
