package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.List;

/**
 * 调用表达式
 */
public class CallExpr extends Expression {
    private final Expression callee;
    private final List<TypeRef> typeArgs;
    private final List<Argument> args;
    private final LambdaExpr trailingLambda;  // 尾随 Lambda

    public CallExpr(SourceLocation location, Expression callee, List<TypeRef> typeArgs,
                    List<Argument> args, LambdaExpr trailingLambda) {
        super(location);
        this.callee = callee;
        this.typeArgs = typeArgs;
        this.args = args;
        this.trailingLambda = trailingLambda;
    }

    public Expression getCallee() {
        return callee;
    }

    public List<TypeRef> getTypeArgs() {
        return typeArgs;
    }

    public List<Argument> getArgs() {
        return args;
    }

    public LambdaExpr getTrailingLambda() {
        return trailingLambda;
    }

    public boolean hasTrailingLambda() {
        return trailingLambda != null;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitCallExpr(this, context);
    }

    /**
     * 调用参数
     */
    public static final class Argument extends AstNode {
        private final String name;           // 命名参数
        private final Expression value;
        private final boolean isSpread;      // *args

        public Argument(SourceLocation location, String name, Expression value, boolean isSpread) {
            super(location);
            this.name = name;
            this.value = value;
            this.isSpread = isSpread;
        }

        public String getName() {
            return name;
        }

        public boolean isNamed() {
            return name != null;
        }

        public Expression getValue() {
            return value;
        }

        public boolean isSpread() {
            return isSpread;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
