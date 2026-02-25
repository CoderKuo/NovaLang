package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.List;

/**
 * Lambda 表达式
 */
public class LambdaExpr extends Expression {
    private final List<LambdaParam> params;
    private final AstNode body;  // Block 或 Expression

    public LambdaExpr(SourceLocation location, List<LambdaParam> params, AstNode body) {
        super(location);
        this.params = params;
        this.body = body;
    }

    public List<LambdaParam> getParams() {
        return params;
    }

    public AstNode getBody() {
        return body;
    }

    public boolean hasExplicitParams() {
        return !params.isEmpty();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitLambdaExpr(this, context);
    }

    /**
     * Lambda 参数
     */
    public static final class LambdaParam extends AstNode {
        private final String name;
        private final TypeRef type;  // 可选

        public LambdaParam(SourceLocation location, String name, TypeRef type) {
            super(location);
            this.name = name;
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public TypeRef getType() {
            return type;
        }

        public boolean hasType() {
            return type != null;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
