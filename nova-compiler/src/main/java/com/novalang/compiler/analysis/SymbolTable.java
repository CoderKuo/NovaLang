package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.SourceLocation;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Symbol table for scope lookup and source-position queries.
 */
public final class SymbolTable {
    private final Scope globalScope;
    private final Map<AstNode, Scope> nodeToScope = new IdentityHashMap<AstNode, Scope>();
    private final List<ScopeRange> scopeRanges = new ArrayList<ScopeRange>();
    // The compiler pipeline can disable position tracking to save memory.
    private boolean recordPositionInfo = true;

    public SymbolTable() {
        this.globalScope = new Scope(Scope.ScopeType.GLOBAL, null, null);
    }

    public void setRecordPositionInfo(boolean record) {
        this.recordPositionInfo = record;
    }

    public Scope getGlobalScope() { return globalScope; }

    public Symbol resolve(String name, int line, int column) {
        Scope scope = getScopeAtPosition(line, column);
        return scope != null ? scope.resolve(name) : globalScope.resolve(name);
    }

    public Symbol resolveValue(String name, int line, int column) {
        return resolve(name, line, column);
    }

    public Symbol resolveType(String name, int line, int column) {
        Scope scope = getScopeAtPosition(line, column);
        return scope != null ? scope.resolveType(name) : globalScope.resolveType(name);
    }

    public Symbol resolveAny(String name, int line, int column) {
        Scope scope = getScopeAtPosition(line, column);
        return scope != null ? scope.resolveAny(name) : globalScope.resolveAny(name);
    }

    public List<Symbol> getVisibleSymbols(int line, int column) {
        Scope scope = getScopeAtPosition(line, column);
        return scope != null ? scope.getAllVisible() : globalScope.getAllVisible();
    }

    public Scope getScopeAtPosition(int line, int column) {
        Scope result = globalScope;
        int bestStartLine = -1;
        int bestStartCol = -1;
        for (ScopeRange range : scopeRanges) {
            if (range.contains(line, column)) {
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
        for (Symbol sym : scope.getTypeSymbols().values()) {
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

    public Symbol findDefinition(String name, int line, int column) {
        return resolveAny(name, line, column);
    }

    public void mapNodeToScope(AstNode node, Scope scope) {
        if (recordPositionInfo) {
            nodeToScope.put(node, scope);
        }
    }

    public void registerScopeRange(Scope scope, SourceLocation start, SourceLocation end) {
        if (recordPositionInfo && start != null && end != null) {
            scopeRanges.add(new ScopeRange(scope, start.getLine(), start.getColumn(),
                    end.getLine(), end.getColumn()));
        }
    }

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
