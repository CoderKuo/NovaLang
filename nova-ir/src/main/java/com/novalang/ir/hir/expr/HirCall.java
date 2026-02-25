package com.novalang.ir.hir.expr;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.HirExpr;
import com.novalang.ir.hir.HirVisitor;
import com.novalang.compiler.hirtype.HirType;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 函数调用表达式。
 */
public class HirCall extends HirExpr {

    private final Expression callee;
    private final List<HirType> typeArgs;
    private final List<Expression> args;
    /** 命名参数: name → expr（为空时不分配 map） */
    private final Map<String, Expression> namedArgs;
    private final java.util.Set<Integer> spreadIndices;

    public HirCall(SourceLocation location, HirType type,
                   Expression callee, List<HirType> typeArgs, List<Expression> args) {
        this(location, type, callee, typeArgs, args, null, null);
    }

    public HirCall(SourceLocation location, HirType type,
                   Expression callee, List<HirType> typeArgs, List<Expression> args,
                   Map<String, Expression> namedArgs) {
        this(location, type, callee, typeArgs, args, namedArgs, null);
    }

    public HirCall(SourceLocation location, HirType type,
                   Expression callee, List<HirType> typeArgs, List<Expression> args,
                   Map<String, Expression> namedArgs, java.util.Set<Integer> spreadIndices) {
        super(location, type);
        this.callee = callee;
        this.typeArgs = (typeArgs != null && !typeArgs.isEmpty()) ? typeArgs : Collections.emptyList();
        this.args = args;
        this.namedArgs = namedArgs != null && !namedArgs.isEmpty() ? namedArgs : null;
        this.spreadIndices = (spreadIndices != null && !spreadIndices.isEmpty()) ? spreadIndices : null;
    }

    public Expression getCallee() {
        return callee;
    }

    public List<HirType> getTypeArgs() {
        return typeArgs;
    }

    public List<Expression> getArgs() {
        return args;
    }

    public Map<String, Expression> getNamedArgs() {
        return namedArgs != null ? namedArgs : Collections.emptyMap();
    }

    public boolean hasNamedArgs() {
        return namedArgs != null;
    }

    public boolean isSpread(int index) {
        return spreadIndices != null && spreadIndices.contains(index);
    }

    public boolean hasSpread() {
        return spreadIndices != null && !spreadIndices.isEmpty();
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitCall(this, context);
    }
}
