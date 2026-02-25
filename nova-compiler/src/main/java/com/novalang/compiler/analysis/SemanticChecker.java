package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;

import java.util.List;
import java.util.Map;

/**
 * 语义验证：所有语义检查和诊断报告。
 */
public final class SemanticChecker {

    private final List<SemanticDiagnostic> diagnostics;
    private final SuperTypeRegistry superTypeRegistry;
    private final Map<Expression, NovaType> exprNovaTypeMap;

    public SemanticChecker(List<SemanticDiagnostic> diagnostics,
                           SuperTypeRegistry superTypeRegistry,
                           Map<Expression, NovaType> exprNovaTypeMap) {
        this.diagnostics = diagnostics;
        this.superTypeRegistry = superTypeRegistry;
        this.exprNovaTypeMap = exprNovaTypeMap;
    }

    /** 报告诊断 */
    public void addDiagnostic(SemanticDiagnostic.Severity severity, String message, AstNode node) {
        SourceLocation loc = node != null ? node.getLocation() : null;
        int length = 1;
        if (loc != null) {
            length = Math.max(loc.getLength(), 1);
        }
        diagnostics.add(new SemanticDiagnostic(severity, message, loc, length));
    }

    /** 检查当前作用域是否已有同名符号，若有则报错 */
    public void checkRedefinition(Scope scope, String name, AstNode node) {
        Symbol existing = scope.resolveLocal(name);
        if (existing == null || existing.getLocation() == null) return;
        SourceLocation nameLoc = null;
        if (node instanceof Declaration) {
            nameLoc = ((Declaration) node).getNameLocation();
        } else if (node instanceof Parameter) {
            nameLoc = ((Parameter) node).getNameLocation();
        }
        if (nameLoc == null) nameLoc = node.getLocation();
        int length = name.length();
        diagnostics.add(new SemanticDiagnostic(SemanticDiagnostic.Severity.ERROR,
                "'" + name + "' 在此作用域中已定义（首次定义于第 " + existing.getLocation().getLine() + " 行）",
                nameLoc, length));
    }

    /** 类型兼容性检查 */
    public void checkTypeCompatibility(NovaType target, NovaType source, AstNode node, String context) {
        if (target == null || source == null) return;
        if (!TypeCompatibility.isAssignable(target, source, superTypeRegistry)) {
            addDiagnostic(SemanticDiagnostic.Severity.WARNING,
                    context + ": 类型不匹配，期望 '" + target.toDisplayString() +
                            "' 但得到 '" + source.toDisplayString() + "'", node);
        }
    }

    /** 参数数量检查 */
    public void checkCallArgCount(CallExpr node, Symbol funcSym) {
        if (funcSym.getParameters() == null) return;
        int expectedCount = funcSym.getParameters().size();
        int actualCount = node.getArgs().size();
        if (node.getTrailingLambda() != null) actualCount++;

        if (funcSym.getDeclaration() instanceof FunDecl) {
            FunDecl funDecl = (FunDecl) funcSym.getDeclaration();
            if (!funDecl.isSimpleParams()) return;
        }
        if (funcSym.getKind() == SymbolKind.BUILTIN_FUNCTION) return;

        if (actualCount != expectedCount) {
            addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "函数 '" + funcSym.getName() + "' 需要 " + expectedCount +
                            " 个参数，实际传入 " + actualCount + " 个", node);
        }
    }

    /** 参数类型兼容性检查 */
    public void checkCallArgTypes(CallExpr node, Symbol funcSym) {
        if (funcSym.getParameters() == null) return;
        if (funcSym.getKind() == SymbolKind.BUILTIN_FUNCTION) return;

        List<Symbol> params = funcSym.getParameters();
        List<CallExpr.Argument> args = node.getArgs();
        int count = Math.min(params.size(), args.size());
        for (int i = 0; i < count; i++) {
            NovaType paramType = params.get(i).getResolvedNovaType();
            NovaType argType = exprNovaTypeMap.get(args.get(i).getValue());
            if (paramType != null && argType != null) {
                checkTypeCompatibility(paramType, argType, args.get(i).getValue(),
                        "参数 '" + params.get(i).getName() + "'");
            }
        }
    }

    /** 递归检查表达式是否为编译期常量 */
    public boolean isCompileTimeConstant(Expression expr, Scope currentScope) {
        if (expr instanceof Literal) {
            Literal lit = (Literal) expr;
            return lit.getKind() != Literal.LiteralKind.NULL;
        }
        if (expr instanceof Identifier) {
            String name = ((Identifier) expr).getName();
            Symbol sym = currentScope.resolve(name);
            if (sym != null && sym.getDeclaration() instanceof PropertyDecl) {
                return ((PropertyDecl) sym.getDeclaration()).isConst();
            }
            return false;
        }
        if (expr instanceof UnaryExpr) {
            UnaryExpr unary = (UnaryExpr) expr;
            if (unary.getOperator() == UnaryExpr.UnaryOp.NEG ||
                unary.getOperator() == UnaryExpr.UnaryOp.POS) {
                return isCompileTimeConstant(unary.getOperand(), currentScope);
            }
            return false;
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr binary = (BinaryExpr) expr;
            BinaryExpr.BinaryOp op = binary.getOperator();
            if (op == BinaryExpr.BinaryOp.ADD || op == BinaryExpr.BinaryOp.SUB ||
                op == BinaryExpr.BinaryOp.MUL || op == BinaryExpr.BinaryOp.DIV ||
                op == BinaryExpr.BinaryOp.MOD) {
                return isCompileTimeConstant(binary.getLeft(), currentScope) &&
                       isCompileTimeConstant(binary.getRight(), currentScope);
            }
            return false;
        }
        return false;
    }
}
