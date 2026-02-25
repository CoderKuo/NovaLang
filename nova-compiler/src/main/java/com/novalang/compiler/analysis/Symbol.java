package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;

import com.novalang.compiler.analysis.types.NovaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 符号表中的符号
 */
public final class Symbol {
    private final String name;
    private final SymbolKind kind;
    private String typeName;              // 类型名 ("String", "List<Int>" 等)
    private final boolean mutable;        // true = var, false = val/param
    private final SourceLocation location;// 声明位置
    private final AstNode declaration;    // 声明的 AST 节点
    private final Modifier visibility;    // public/private/protected/internal

    // 结构化类型（与 typeName 并行）
    private NovaType resolvedNovaType;

    // 额外信息
    private List<Symbol> parameters;      // 如果是函数/类，其参数列表
    private String superClass;            // 如果是类，父类名
    private List<String> interfaces;      // 如果是类，实现的接口名
    private Map<String, Symbol> members;  // 如果是类/对象/enum，其成员

    public Symbol(String name, SymbolKind kind, String typeName, boolean mutable,
                  SourceLocation location, AstNode declaration, Modifier visibility) {
        this.name = name;
        this.kind = kind;
        this.typeName = typeName;
        this.mutable = mutable;
        this.location = location;
        this.declaration = declaration;
        this.visibility = visibility != null ? visibility : Modifier.PUBLIC;
    }

    public String getName() { return name; }
    public SymbolKind getKind() { return kind; }
    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }
    public boolean isMutable() { return mutable; }
    public SourceLocation getLocation() { return location; }
    public AstNode getDeclaration() { return declaration; }
    public Modifier getVisibility() { return visibility; }

    public NovaType getResolvedNovaType() { return resolvedNovaType; }
    public void setResolvedNovaType(NovaType type) { this.resolvedNovaType = type; }

    public List<Symbol> getParameters() { return parameters; }
    public void setParameters(List<Symbol> parameters) { this.parameters = parameters; }

    public String getSuperClass() { return superClass; }
    public void setSuperClass(String superClass) { this.superClass = superClass; }

    public List<String> getInterfaces() { return interfaces; }
    public void setInterfaces(List<String> interfaces) { this.interfaces = interfaces; }

    public Map<String, Symbol> getMembers() { return members; }

    public void addMember(Symbol member) {
        if (members == null) {
            members = new LinkedHashMap<String, Symbol>();
        }
        members.put(member.getName(), member);
    }
}
