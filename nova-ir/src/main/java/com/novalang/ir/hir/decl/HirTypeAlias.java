package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.Collections;
import java.util.List;

/**
 * 类型别名声明。
 */
public class HirTypeAlias extends HirDecl {

    private final List<String> typeParams;
    private final HirType targetType;

    public HirTypeAlias(SourceLocation location, String name,
                        List<String> typeParams, HirType targetType) {
        super(location, name, Collections.emptySet(), Collections.emptyList());
        this.typeParams = typeParams;
        this.targetType = targetType;
    }

    public List<String> getTypeParams() {
        return typeParams;
    }

    public HirType getTargetType() {
        return targetType;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitTypeAlias(this, context);
    }
}
