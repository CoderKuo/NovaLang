package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 声明基类
 */
public abstract class Declaration extends AstNode {
    protected final List<Annotation> annotations;
    protected final List<Modifier> modifiers;
    protected final String name;
    /** 名称标识符的精确位置（语义令牌用），null 表示未设置 */
    private SourceLocation nameLocation;

    protected Declaration(SourceLocation location, List<Annotation> annotations,
                          List<Modifier> modifiers, String name) {
        super(location);
        this.annotations = annotations;
        this.modifiers = modifiers;
        this.name = name;
    }

    public SourceLocation getNameLocation() {
        return nameLocation;
    }

    public void setNameLocation(SourceLocation loc) {
        this.nameLocation = loc;
    }

    public List<Annotation> getAnnotations() {
        return annotations;
    }

    public List<Modifier> getModifiers() {
        return modifiers;
    }

    public String getName() {
        return name;
    }

    public boolean hasModifier(Modifier modifier) {
        return modifiers.contains(modifier);
    }
}
