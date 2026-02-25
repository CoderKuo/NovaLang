package com.novalang.ir.hir;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;
import java.util.Set;

/**
 * HIR 声明基类。
 * <p>
 * Phase 0 迁移：extends AstNode implements HirNode。
 * 不继承 Declaration（字段类型差异：Set vs List Modifier, HirAnnotation vs Annotation）。
 */
public abstract class HirDecl extends AstNode implements HirNode {

    protected final String name;
    protected final Set<Modifier> modifiers;
    protected final List<HirAnnotation> annotations;

    protected HirDecl(SourceLocation location, String name,
                      Set<Modifier> modifiers, List<HirAnnotation> annotations) {
        super(location);
        this.name = name;
        this.modifiers = modifiers;
        this.annotations = annotations;
    }

    public String getName() {
        return name;
    }

    public Set<Modifier> getModifiers() {
        return modifiers;
    }

    public boolean hasModifier(Modifier modifier) {
        return modifiers != null && modifiers.contains(modifier);
    }

    public List<HirAnnotation> getAnnotations() {
        return annotations;
    }

    /**
     * AST visitor 不处理 HIR 节点，返回 null。
     */
    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        return null;
    }
}
