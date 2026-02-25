package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

import java.util.List;

/**
 * 注解
 */
public class Annotation extends AstNode {
    private final String name;
    private final List<AnnotationArg> args;

    public Annotation(SourceLocation location, String name, List<AnnotationArg> args) {
        super(location);
        this.name = name;
        this.args = args;
    }

    public String getName() {
        return name;
    }

    public List<AnnotationArg> getArgs() {
        return args;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        // 注解没有单独的 visit 方法，作为声明的一部分处理
        return null;
    }

    /**
     * 注解参数
     */
    public static final class AnnotationArg extends AstNode {
        private final String name;  // 可选
        private final Expression value;

        public AnnotationArg(SourceLocation location, String name, Expression value) {
            super(location);
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public Expression getValue() {
            return value;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
