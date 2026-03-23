package com.novalang.compiler.ast.stmt;

import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.decl.DestructuringEntry;
import com.novalang.compiler.ast.expr.Expression;

import java.util.ArrayList;
import java.util.List;

/**
 * For 语句
 */
public class ForStmt extends Statement {
    private final String label;  // 可选
    private final List<DestructuringEntry> entries;  // 支持位置和名称解构
    private final Expression iterable;
    private final Statement body;

    public ForStmt(SourceLocation location, String label, List<DestructuringEntry> entries,
                   Expression iterable, Statement body) {
        super(location);
        this.label = label;
        this.entries = entries;
        this.iterable = iterable;
        this.body = body;
    }

    public String getLabel() {
        return label;
    }

    public boolean hasLabel() {
        return label != null;
    }

    public List<DestructuringEntry> getEntries() {
        return entries;
    }

    /** 兼容方法：提取所有变量名 */
    public List<String> getVariables() {
        List<String> vars = new ArrayList<String>();
        for (DestructuringEntry e : entries) {
            vars.add(e.getLocalName() != null ? e.getLocalName() : "_");
        }
        return vars;
    }

    public Expression getIterable() {
        return iterable;
    }

    public Statement getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return visitor.visitForStmt(this, context);
    }
}
