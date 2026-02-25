package com.novalang.ir.hir.expr;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.HirExpr;
import com.novalang.ir.hir.HirVisitor;
import com.novalang.compiler.hirtype.HirType;

import java.util.List;

/**
 * 构造器调用（从 CallExpr 中提取）。
 */
public class HirNew extends HirExpr {

    private final String className;
    private final List<HirType> typeArgs;
    private final List<Expression> args;

    public HirNew(SourceLocation location, HirType type,
                  String className, List<HirType> typeArgs, List<Expression> args) {
        super(location, type);
        this.className = className;
        this.typeArgs = typeArgs;
        this.args = args;
    }

    public String getClassName() {
        return className;
    }

    public List<HirType> getTypeArgs() {
        return typeArgs;
    }

    public List<Expression> getArgs() {
        return args;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitNew(this, context);
    }
}
