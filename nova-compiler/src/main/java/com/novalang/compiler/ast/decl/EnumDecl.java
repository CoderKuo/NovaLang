package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.type.TypeRef;

import java.util.List;

/**
 * 枚举声明
 */
public class EnumDecl extends Declaration {
    private final List<Parameter> primaryConstructorParams;
    private final List<TypeRef> superTypes;
    private final List<EnumEntry> entries;
    private final List<Declaration> members;

    public EnumDecl(SourceLocation location, List<Annotation> annotations,
                    List<Modifier> modifiers, String name,
                    List<Parameter> primaryConstructorParams, List<TypeRef> superTypes,
                    List<EnumEntry> entries, List<Declaration> members) {
        super(location, annotations, modifiers, name);
        this.primaryConstructorParams = primaryConstructorParams;
        this.superTypes = superTypes;
        this.entries = entries;
        this.members = members;
    }

    public List<Parameter> getPrimaryConstructorParams() {
        return primaryConstructorParams;
    }

    public List<TypeRef> getSuperTypes() {
        return superTypes;
    }

    public List<EnumEntry> getEntries() {
        return entries;
    }

    public List<Declaration> getMembers() {
        return members;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitEnumDecl(this, context);
    }

    /**
     * 枚举条目
     */
    public static final class EnumEntry extends AstNode {
        private final String name;
        private final List<Expression> args;
        private final List<Declaration> members;

        public EnumEntry(SourceLocation location, String name,
                         List<Expression> args, List<Declaration> members) {
            super(location);
            this.name = name;
            this.args = args;
            this.members = members;
        }

        public String getName() {
            return name;
        }

        public List<Expression> getArgs() {
            return args;
        }

        public List<Declaration> getMembers() {
            return members;
        }

        @Override
        public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
            return null;
        }
    }
}
