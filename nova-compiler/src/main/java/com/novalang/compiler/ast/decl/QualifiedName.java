package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 限定名称（如 java.util.List）
 */
public class QualifiedName extends AstNode {
    private final List<String> parts;

    public QualifiedName(SourceLocation location, List<String> parts) {
        super(location);
        this.parts = parts;
    }

    public List<String> getParts() {
        return parts;
    }

    public String getFullName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(".");
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public String getSimpleName() {
        return parts.isEmpty() ? "" : parts.get(parts.size() - 1);
    }

    @Override
    public String toString() {
        return getFullName();
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitQualifiedName(this, context);
    }
}
