package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 符号表：管理作用域树和位置索引
 */
public final class SymbolTable {
    private final Scope globalScope;
    private final Map<AstNode, Scope> nodeToScope = new IdentityHashMap<AstNode, Scope>();
    private final List<ScopeRange> scopeRanges = new ArrayList<ScopeRange>();

    public SymbolTable() {
        this.globalScope = new Scope(Scope.ScopeType.GLOBAL, null, null);
    }

    public Scope getGlobalScope() { return globalScope; }

    /** 在指定位置查找符号 */
    public Symbol resolve(String name, int line, int column) {
        Scope scope = getScopeAtPosition(line, column);
        return scope != null ? scope.resolve(name) : globalScope.resolve(name);
    }

    /** 获取指定位置所有可见符号 */
    public List<Symbol> getVisibleSymbols(int line, int column) {
        Scope scope = getScopeAtPosition(line, column);
        return scope != null ? scope.getAllVisible() : globalScope.getAllVisible();
    }

    /** 获取指定位置所在最内层作用域 */
    public Scope getScopeAtPosition(int line, int column) {
        Scope result = globalScope;
        int bestStartLine = -1;
        int bestStartCol = -1;
        for (ScopeRange range : scopeRanges) {
            if (range.contains(line, column)) {
                // 选起始位置最晚的作用域（即最内层）
                if (range.startLine > bestStartLine
                        || (range.startLine == bestStartLine && range.startCol > bestStartCol)) {
                    bestStartLine = range.startLine;
                    bestStartCol = range.startCol;
                    result = range.scope;
                }
            }
        }
        return result;
    }

    /** 获取所有指定类型的符号 */
    public List<Symbol> getAllSymbolsOfKind(SymbolKind... kinds) {
        List<Symbol> result = new ArrayList<Symbol>();
        collectSymbolsOfKind(globalScope, kinds, result);
        return result;
    }

    private void collectSymbolsOfKind(Scope scope, SymbolKind[] kinds, List<Symbol> result) {
        for (Symbol sym : scope.getSymbols().values()) {
            for (SymbolKind kind : kinds) {
                if (sym.getKind() == kind) {
                    result.add(sym);
                    break;
                }
            }
        }
        for (Scope child : scope.getChildren()) {
            collectSymbolsOfKind(child, kinds, result);
        }
    }

    /** 查找符号定义位置 */
    public Symbol findDefinition(String name, int line, int column) {
        return resolve(name, line, column);
    }

    /** 记录 AST 节点到作用域的映射 */
    public void mapNodeToScope(AstNode node, Scope scope) {
        nodeToScope.put(node, scope);
    }

    /** 记录作用域范围 */
    public void registerScopeRange(Scope scope, SourceLocation start, SourceLocation end) {
        if (start != null && end != null) {
            scopeRanges.add(new ScopeRange(scope, start.getLine(), start.getColumn(),
                    end.getLine(), end.getColumn()));
        }
    }

    /** 作用域范围 */
    static final class ScopeRange {
        final Scope scope;
        final int startLine, startCol, endLine, endCol;

        ScopeRange(Scope scope, int startLine, int startCol, int endLine, int endCol) {
            this.scope = scope;
            this.startLine = startLine;
            this.startCol = startCol;
            this.endLine = endLine;
            this.endCol = endCol;
        }

        boolean contains(int line, int col) {
            if (line < startLine || line > endLine) return false;
            if (line == startLine && col < startCol) return false;
            if (line == endLine && col > endCol) return false;
            return true;
        }
    }
}
