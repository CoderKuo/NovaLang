package com.novalang.ir.hir;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.Collections;
import java.util.Map;

/**
 * HIR 注解节点。
 */
public class HirAnnotation extends AstNode implements HirNode {

    private final String name;
    private final Map<String, Expression> args;

    public HirAnnotation(SourceLocation location, String name, Map<String, Expression> args) {
        super(location);
        this.name = name;
        this.args = args != null ? args : Collections.emptyMap();
    }

    public String getName() {
        return name;
    }

    public Map<String, Expression> getArgs() {
        return args;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitAnnotation(this, context);
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
