package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.NovaType;
import com.novalang.compiler.ast.expr.Expression;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 语义分析结果
 */
public final class AnalysisResult {
    private final SymbolTable symbolTable;
    private final List<SemanticDiagnostic> diagnostics;
    private final Map<Expression, NovaType> exprNovaTypeMap;

    public AnalysisResult(SymbolTable symbolTable, List<SemanticDiagnostic> diagnostics) {
        this(symbolTable, diagnostics, Collections.<Expression, NovaType>emptyMap());
    }

    public AnalysisResult(SymbolTable symbolTable, List<SemanticDiagnostic> diagnostics,
                          Map<Expression, NovaType> exprNovaTypeMap) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.exprNovaTypeMap = exprNovaTypeMap;
    }

    public SymbolTable getSymbolTable() { return symbolTable; }
    public List<SemanticDiagnostic> getDiagnostics() { return diagnostics; }
    public Map<Expression, NovaType> getExprNovaTypeMap() { return exprNovaTypeMap; }

    /** 获取单个表达式的类型字符串 */
    public String getExprTypeName(Expression expr) {
        NovaType type = exprNovaTypeMap.get(expr);
        return type != null ? type.toDisplayString() : null;
    }
}
