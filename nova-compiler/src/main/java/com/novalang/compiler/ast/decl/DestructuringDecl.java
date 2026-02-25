package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;

import java.util.Collections;
import java.util.List;

/**
 * 解构声明（如 val (a, b, c) = expr）
 */
public class DestructuringDecl extends Declaration {
    private final boolean isVal;
    private final List<String> names;  // 变量名列表，null 或 "_" 表示跳过
    private final Expression initializer;

    public DestructuringDecl(SourceLocation location, boolean isVal,
                             List<String> names, Expression initializer) {
        super(location, Collections.<Annotation>emptyList(), Collections.<Modifier>emptyList(), null);
        this.isVal = isVal;
        this.names = names;
        this.initializer = initializer;
    }

    public boolean isVal() {
        return isVal;
    }

    public List<String> getNames() {
        return names;
    }

    public Expression getInitializer() {
        return initializer;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitDestructuringDecl(this, context);
    }
}
