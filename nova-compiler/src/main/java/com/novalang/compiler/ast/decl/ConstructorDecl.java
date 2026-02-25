package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.stmt.Block;

import java.util.List;

/**
 * 次级构造器声明
 *
 * <p>语法: constructor(params) : this(delegationArgs) { body }</p>
 */
public class ConstructorDecl extends Declaration {
    private final List<Parameter> params;
    private final List<Expression> delegationArgs;  // : this(...) 的参数，null 表示无委托
    private final Block body;                        // 构造器体，可为 null

    public ConstructorDecl(SourceLocation location, List<Annotation> annotations,
                           List<Modifier> modifiers, List<Parameter> params,
                           List<Expression> delegationArgs, Block body) {
        super(location, annotations, modifiers, "<constructor>");
        this.params = params;
        this.delegationArgs = delegationArgs;
        this.body = body;
    }

    public List<Parameter> getParams() {
        return params;
    }

    public List<Expression> getDelegationArgs() {
        return delegationArgs;
    }

    public boolean hasDelegation() {
        return delegationArgs != null;
    }

    public Block getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitConstructorDecl(this, context);
    }
}
