package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.type.TypeRef;

/**
 * Catch 子句
 */
public class CatchClause extends AstNode {
    private final String paramName;
    private final TypeRef paramType;
    private final Block body;

    public CatchClause(SourceLocation location, String paramName, TypeRef paramType, Block body) {
        super(location);
        this.paramName = paramName;
        this.paramType = paramType;
        this.body = body;
    }

    public String getParamName() {
        return paramName;
    }

    public TypeRef getParamType() {
        return paramType;
    }

    public Block getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
