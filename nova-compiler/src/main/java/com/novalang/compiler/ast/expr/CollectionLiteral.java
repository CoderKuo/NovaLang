package com.novalang.compiler.ast.expr;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 集合字面量（如 [1, 2, 3], {a: 1, b: 2}）
 */
public class CollectionLiteral extends Expression {
    private final CollectionKind kind;
    private final List<Expression> elements;
    private final List<MapEntry> mapEntries;  // 仅 MAP

    public CollectionLiteral(SourceLocation location, CollectionKind kind,
                             List<Expression> elements, List<MapEntry> mapEntries) {
        super(location);
        this.kind = kind;
        this.elements = elements;
        this.mapEntries = mapEntries;
    }

    public CollectionKind getKind() {
        return kind;
    }

    public List<Expression> getElements() {
        return elements;
    }

    public List<MapEntry> getMapEntries() {
        return mapEntries;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitCollectionLiteral(this, context);
    }

    /**
     * 集合类型
     */
    public enum CollectionKind {
        LIST,
        SET,
        MAP
    }

    /**
     * Map 条目
     */
    public static final class MapEntry extends AstNode {
        private final Expression key;
        private final Expression value;

        public MapEntry(SourceLocation location, Expression key, Expression value) {
            super(location);
            this.key = key;
            this.value = value;
        }

        public Expression getKey() {
            return key;
        }

        public Expression getValue() {
            return value;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
