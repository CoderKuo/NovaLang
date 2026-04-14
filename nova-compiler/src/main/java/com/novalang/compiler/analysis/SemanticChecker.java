package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        diagnostics.add(new SemanticDiagnostic(SemanticDiagnostic.Severity.ERROR,
                "Variable already defined: '" + name + "'",
                nameLoc, length));
    }

    public void checkTypeRedefinition(Scope scope, String name, AstNode node) {
        Symbol existing = scope.resolveLocalType(name);
        if (existing == null || existing.getLocation() == null) return;
        SourceLocation nameLoc = null;
        if (node instanceof Declaration) {
            nameLoc = ((Declaration) node).getNameLocation();
        }
        if (nameLoc == null) nameLoc = node.getLocation();
        int length = name.length();
        diagnostics.add(new SemanticDiagnostic(SemanticDiagnostic.Severity.ERROR,
                "Type '" + name + "' is already defined in this scope (first declared on line "
                        + existing.getLocation().getLine() + ")",
                nameLoc, length));
    }

    /** 类型兼容性检查 */
    public void checkTypeCompatibility(NovaType target, NovaType source, AstNode node, String context) {
        if (target == null || source == null) return;
        if (!TypeCompatibility.isAssignable(target, source, superTypeRegistry)) {
            addDiagnostic(SemanticDiagnostic.Severity.ERROR,
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
        for (CallExpr.Argument arg : node.getArgs()) {
            if (arg.isSpread()) return;
        }

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
            if (args.get(i).isSpread()) return;
            NovaType paramType = params.get(i).getResolvedNovaType();
            NovaType argType = exprNovaTypeMap.get(args.get(i).getValue());
            if (paramType != null && argType != null) {
                checkTypeCompatibility(paramType, argType, args.get(i).getValue(),
                        "参数 '" + params.get(i).getName() + "'");
            }
        }
    }

    public void checkCallArguments(CallExpr node, String callableName,
                                   List<Parameter> declaredParams, List<NovaType> paramTypes) {
        if (declaredParams == null) return;
        for (CallExpr.Argument arg : node.getArgs()) {
            if (arg.isSpread()) {
                return;
            }
        }

        Map<String, Integer> paramIndexByName = new LinkedHashMap<String, Integer>();
        List<List<Expression>> boundArgs = new ArrayList<List<Expression>>(declaredParams.size());
        for (int i = 0; i < declaredParams.size(); i++) {
            paramIndexByName.put(declaredParams.get(i).getName(), i);
            boundArgs.add(new ArrayList<Expression>());
        }

        boolean seenNamed = false;
        int positionalParamIndex = 0;

        for (CallExpr.Argument arg : node.getArgs()) {
            if (arg.isNamed()) {
                seenNamed = true;
                Integer paramIndex = paramIndexByName.get(arg.getName());
                if (paramIndex == null) {
                    addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "Unknown named argument '" + arg.getName() + "' for '" + callableName + "'",
                            arg);
                    continue;
                }
                Parameter parameter = declaredParams.get(paramIndex.intValue());
                List<Expression> values = boundArgs.get(paramIndex.intValue());
                if (!parameter.isVararg() && !values.isEmpty()) {
                    addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "Argument for parameter '" + parameter.getName() + "' is already provided",
                            arg);
                    continue;
                }
                values.add(arg.getValue());
                continue;
            }

            if (seenNamed) {
                addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "Positional arguments cannot appear after named arguments in call to '" + callableName + "'",
                        arg);
            }

            while (positionalParamIndex < declaredParams.size()) {
                Parameter parameter = declaredParams.get(positionalParamIndex);
                List<Expression> values = boundArgs.get(positionalParamIndex);
                if (!parameter.isVararg() && !values.isEmpty()) {
                    positionalParamIndex++;
                    continue;
                }
                break;
            }

            if (positionalParamIndex >= declaredParams.size()) {
                addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "Too many arguments for '" + callableName + "'",
                        arg);
                continue;
            }

            Parameter parameter = declaredParams.get(positionalParamIndex);
            boundArgs.get(positionalParamIndex).add(arg.getValue());
            if (!parameter.isVararg()) {
                positionalParamIndex++;
            }
        }

        if (node.getTrailingLambda() != null) {
            while (positionalParamIndex < declaredParams.size()) {
                Parameter parameter = declaredParams.get(positionalParamIndex);
                List<Expression> values = boundArgs.get(positionalParamIndex);
                if (!parameter.isVararg() && !values.isEmpty()) {
                    positionalParamIndex++;
                    continue;
                }
                break;
            }

            if (positionalParamIndex >= declaredParams.size()) {
                addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "Too many arguments for '" + callableName + "'",
                        node.getTrailingLambda());
            } else {
                Parameter parameter = declaredParams.get(positionalParamIndex);
                boundArgs.get(positionalParamIndex).add(node.getTrailingLambda());
                if (!parameter.isVararg()) {
                    positionalParamIndex++;
                }
            }
        }

        for (int i = 0; i < declaredParams.size(); i++) {
            Parameter parameter = declaredParams.get(i);
            List<Expression> values = boundArgs.get(i);
            if (values.isEmpty() && !parameter.hasDefaultValue() && !parameter.isVararg()) {
                addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "Missing required argument '" + parameter.getName() + "' for '" + callableName + "'",
                        node);
                continue;
            }

            NovaType paramType = paramTypes != null && i < paramTypes.size() ? paramTypes.get(i) : null;
            if (paramType == null) continue;
            for (Expression value : values) {
                NovaType argType = exprNovaTypeMap.get(value);
                if (argType != null) {
                    checkTypeCompatibility(paramType, argType, value,
                            "鍙傛暟 '" + parameter.getName() + "'");
                }
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
