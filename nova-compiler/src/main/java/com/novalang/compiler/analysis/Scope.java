package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.AstNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作用域
 */
public final class Scope {

    public enum ScopeType {
        GLOBAL,     // 顶层
        CLASS,      // class body
        FUNCTION,   // function body
        BLOCK,      // if/for/while/try block
        LAMBDA,     // lambda body
        ENUM        // enum body
    }

    private final ScopeType type;
    private final Scope parent;
    private final AstNode node;
    private final Map<String, Symbol> symbols = new LinkedHashMap<String, Symbol>();
    private final List<Scope> children = new ArrayList<Scope>();

    // 所属类型名（class/object scope 中 = 类名，用于 this 类型推断）
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
    public List<Scope> getChildren() { return children; }

    public String getOwnerTypeName() { return ownerTypeName; }
    public void setOwnerTypeName(String ownerTypeName) { this.ownerTypeName = ownerTypeName; }

    public void addChild(Scope child) { children.add(child); }

    /** 注册符号到当前作用域 */
    public void define(Symbol symbol) {
        symbols.put(symbol.getName(), symbol);
    }

    /** 从当前作用域向上查找 */
    public Symbol resolve(String name) {
        Symbol s = symbols.get(name);
        if (s != null) return s;
        if (parent != null) return parent.resolve(name);
        return null;
    }

    /** 仅查找当前作用域 */
    public Symbol resolveLocal(String name) {
        return symbols.get(name);
    }

    /** 获取当前作用域可见的所有符号（含父级） */
    public List<Symbol> getAllVisible() {
        Map<String, Symbol> all = new LinkedHashMap<String, Symbol>();
        collectVisible(all);
        return new ArrayList<Symbol>(all.values());
    }

    private void collectVisible(Map<String, Symbol> result) {
        if (parent != null) parent.collectVisible(result);
        result.putAll(symbols); // 子作用域覆盖父作用域同名符号
    }

    /** 查找最近的类作用域的 ownerTypeName（用于 this 推断） */
    public String getEnclosingTypeName() {
        if (type == ScopeType.CLASS || type == ScopeType.ENUM) return ownerTypeName;
        if (parent != null) return parent.getEnclosingTypeName();
        return null;
    }
}
