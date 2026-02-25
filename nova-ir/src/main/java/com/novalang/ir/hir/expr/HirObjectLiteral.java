package com.novalang.ir.hir.expr;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.Collections;
import java.util.List;

/**
 * 匿名对象字面量 object : Type { members }。
 */
public class HirObjectLiteral extends HirExpr {

    private final List<HirType> superTypes;
    private final List<HirDecl> members;
    private final List<Expression> superConstructorArgs;

    public HirObjectLiteral(SourceLocation location, HirType type,
                            List<HirType> superTypes, List<HirDecl> members) {
        this(location, type, superTypes, members, Collections.emptyList());
    }

    public HirObjectLiteral(SourceLocation location, HirType type,
                            List<HirType> superTypes, List<HirDecl> members,
                            List<Expression> superConstructorArgs) {
        super(location, type);
        this.superTypes = superTypes;
        this.members = members;
        this.superConstructorArgs = superConstructorArgs;
    }

    public List<HirType> getSuperTypes() {
        return superTypes;
    }

    public List<HirDecl> getMembers() {
        return members;
    }

    public List<Expression> getSuperConstructorArgs() {
        return superConstructorArgs;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitObjectLiteral(this, context);
    }
}
