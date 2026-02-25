package com.novalang.ir.hir.expr;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.HirParam;
import com.novalang.compiler.hirtype.HirType;

import java.util.List;

/**
 * Lambda 表达式。
 */
public class HirLambda extends HirExpr {

    private final List<HirParam> params;
    private final AstNode body;               // Block 或 Expression
    private final List<String> capturedVars;  // 捕获的变量名

    public HirLambda(SourceLocation location, HirType type,
                     List<HirParam> params, AstNode body,
                     List<String> capturedVars) {
        super(location, type);
        this.params = params;
        this.body = body;
        this.capturedVars = capturedVars;
    }

    public List<HirParam> getParams() {
        return params;
    }

    public AstNode getBody() {
        return body;
    }

    public List<String> getCapturedVars() {
        return capturedVars;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitLambda(this, context);
    }
}
