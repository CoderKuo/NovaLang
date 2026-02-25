package com.novalang.ir.hir.expr;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.HirExpr;
import com.novalang.ir.hir.HirVisitor;
import com.novalang.compiler.hirtype.HirType;

import java.util.List;

/**
 * 集合字面量（List/Set/Map）。
 */
public class HirCollectionLiteral extends HirExpr {

    public enum Kind {
        LIST, SET, MAP
    }

    private final Kind kind;
    private final List<Expression> elements;
    private final java.util.Set<Integer> spreadIndices;

    public HirCollectionLiteral(SourceLocation location, HirType type,
                                Kind kind, List<Expression> elements) {
        this(location, type, kind, elements, null);
    }

    public HirCollectionLiteral(SourceLocation location, HirType type,
                                Kind kind, List<Expression> elements,
                                java.util.Set<Integer> spreadIndices) {
        super(location, type);
        this.kind = kind;
        this.elements = elements;
        this.spreadIndices = spreadIndices;
    }

    public Kind getKind() {
        return kind;
    }

    public List<Expression> getElements() {
        return elements;
    }

    public boolean isSpread(int index) {
        return spreadIndices != null && spreadIndices.contains(index);
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitCollectionLiteral(this, context);
    }
}
