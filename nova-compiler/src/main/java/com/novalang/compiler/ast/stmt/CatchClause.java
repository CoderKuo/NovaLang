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
    private final java.util.List<TypeRef> alternateTypes; // 多异常: IOException | ParseException
    private final Block body;

    public CatchClause(SourceLocation location, String paramName, TypeRef paramType, Block body) {
        this(location, paramName, paramType, null, body);
    }

    public CatchClause(SourceLocation location, String paramName, TypeRef paramType,
                       java.util.List<TypeRef> alternateTypes, Block body) {
        super(location);
        this.paramName = paramName;
        this.paramType = paramType;
        this.alternateTypes = alternateTypes;
        this.body = body;
    }

    public String getParamName() {
        return paramName;
    }

    public TypeRef getParamType() {
        return paramType;
    }

    /** 多异常捕获的备选类型列表（不含主类型），null 表示单异常 */
    public java.util.List<TypeRef> getAlternateTypes() {
        return alternateTypes;
    }

    public boolean isMultiCatch() {
        return alternateTypes != null && !alternateTypes.isEmpty();
    }

    public Block getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
