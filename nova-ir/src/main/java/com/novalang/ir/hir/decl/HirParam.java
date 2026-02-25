package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.Collections;

/**
 * 函数参数。
 */
public class HirParam extends HirDecl {

    private final HirType type;
    private final Expression defaultValue;
    private final boolean isVararg;

    public HirParam(SourceLocation location, String name, HirType type,
                    Expression defaultValue, boolean isVararg) {
        super(location, name, Collections.emptySet(), Collections.emptyList());
        this.type = type;
        this.defaultValue = defaultValue;
        this.isVararg = isVararg;
    }

    public HirType getType() {
        return type;
    }

    public Expression getDefaultValue() {
        return defaultValue;
    }

    public boolean hasDefaultValue() {
        return defaultValue != null;
    }

    public boolean isVararg() {
        return isVararg;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitParam(this, context);
    }
}
