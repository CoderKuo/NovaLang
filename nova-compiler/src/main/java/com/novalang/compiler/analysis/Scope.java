package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.AstNode;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lexical scope with separate value and type namespaces.
 */
public final class Scope {

    public enum ScopeType {
        GLOBAL,
        CLASS,
        FUNCTION,
        BLOCK,
        LAMBDA,
        ENUM
    }

    private final ScopeType type;
    private final Scope parent;
    private final AstNode node;
    private final Map<String, Symbol> symbols = new LinkedHashMap<String, Symbol>();
    private final Map<String, Symbol> typeSymbols = new LinkedHashMap<String, Symbol>();
    private final List<Scope> children = new ArrayList<Scope>();

    // For class/object/enum scopes, used to infer the type of `this`.
    private String ownerTypeName;

    public Scope(ScopeType type, Scope parent, AstNode node) {
        this.type = type;
        this.parent = parent;
        this.node = node;
    }

    public ScopeType getType() { return type; }
    public Scope getParent() { return parent; }
    public AstNode getNode() { return node; }
    public Map<String, Symbol> getSymbols() { return symbols; }
    public Map<String, Symbol> getTypeSymbols() { return typeSymbols; }
    public List<Scope> getChildren() { return children; }

    public String getOwnerTypeName() { return ownerTypeName; }
    public void setOwnerTypeName(String ownerTypeName) { this.ownerTypeName = ownerTypeName; }

    public void addChild(Scope child) { children.add(child); }

    public void define(Symbol symbol) {
        symbols.put(symbol.getName(), symbol);
    }

    public void defineType(Symbol symbol) {
        typeSymbols.put(symbol.getName(), symbol);
    }

    public Symbol resolve(String name) {
        Symbol symbol = symbols.get(name);
        if (symbol != null) return symbol;
        if (parent != null) return parent.resolve(name);
        return null;
    }

    public Symbol resolveType(String name) {
        Symbol symbol = typeSymbols.get(name);
        if (symbol != null) return symbol;
        if (parent != null) return parent.resolveType(name);
        return null;
    }

    public Symbol resolveAny(String name) {
        Symbol symbol = symbols.get(name);
        if (symbol == null) {
            symbol = typeSymbols.get(name);
        }
        if (symbol != null) return symbol;
        if (parent != null) return parent.resolveAny(name);
        return null;
    }

    public Symbol resolveLocal(String name) {
        return symbols.get(name);
    }

    public Symbol resolveLocalType(String name) {
        return typeSymbols.get(name);
    }

    public List<Symbol> getAllVisible() {
        Map<String, Symbol> values = new LinkedHashMap<String, Symbol>();
        Map<String, Symbol> types = new LinkedHashMap<String, Symbol>();
        collectVisible(values, types);
        return mergeSymbols(values, types);
    }

    public List<Symbol> getDeclaredSymbols() {
        return mergeSymbols(symbols, typeSymbols);
    }

    private void collectVisible(Map<String, Symbol> values, Map<String, Symbol> types) {
        if (parent != null) {
            parent.collectVisible(values, types);
        }
        values.putAll(symbols);
        types.putAll(typeSymbols);
    }

    private List<Symbol> mergeSymbols(Map<String, Symbol> values, Map<String, Symbol> types) {
        List<Symbol> merged = new ArrayList<Symbol>(values.size() + types.size());
        Map<Symbol, Boolean> seen = new IdentityHashMap<Symbol, Boolean>();
        appendDistinct(merged, seen, values.values());
        appendDistinct(merged, seen, types.values());
        return merged;
    }

    private void appendDistinct(List<Symbol> merged, Map<Symbol, Boolean> seen,
                                Iterable<Symbol> symbolsToAppend) {
        for (Symbol symbol : symbolsToAppend) {
            if (!seen.containsKey(symbol)) {
                seen.put(symbol, Boolean.TRUE);
                merged.add(symbol);
            }
        }
    }

    public String getEnclosingTypeName() {
        if (type == ScopeType.CLASS || type == ScopeType.ENUM) return ownerTypeName;
        if (parent != null) return parent.getEnclosingTypeName();
        return null;
    }
}
