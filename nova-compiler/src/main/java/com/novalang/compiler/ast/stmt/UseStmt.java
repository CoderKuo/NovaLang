package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

import java.util.List;

/**
 * Use 语句（自动资源管理）
 */
public class UseStmt extends Statement {
    private final List<UseBinding> bindings;
    private final Block body;

    public UseStmt(SourceLocation location, List<UseBinding> bindings, Block body) {
        super(location);
        this.bindings = bindings;
        this.body = body;
    }

    public List<UseBinding> getBindings() {
        return bindings;
    }

    public Block getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitUseStmt(this, context);
    }

    /**
     * Use 绑定
     */
    public static final class UseBinding extends AstNode {
        private final String name;
        private final Expression initializer;

        public UseBinding(SourceLocation location, String name, Expression initializer) {
            super(location);
            this.name = name;
            this.initializer = initializer;
        }

        public String getName() {
            return name;
        }

        public Expression getInitializer() {
            return initializer;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
