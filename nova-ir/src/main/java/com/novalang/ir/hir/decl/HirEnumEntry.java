package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 枚举条目。
 */
public class HirEnumEntry extends HirDecl {

    private final List<Expression> args;
    private final List<HirDecl> members;

    public HirEnumEntry(SourceLocation location, String name,
                        List<Expression> args, List<HirDecl> members) {
        super(location, name, Collections.emptySet(), Collections.emptyList());
        this.args = args;
        this.members = members;
    }

    public List<Expression> getArgs() {
        return args;
    }

    public List<HirDecl> getMembers() {
        return members;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitEnumEntry(this, context);
    }
}
