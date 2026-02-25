package com.novalang.compiler.ast.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.AstVisitor;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;

import java.util.List;

/**
 * 属性访问器（getter/setter）
 */
public class PropertyAccessor extends AstNode {
    private final boolean isGetter;
    private final List<Modifier> modifiers;
    private final Parameter param;               // setter 参数
    private final AstNode body;                  // Block 或 Expression

    public PropertyAccessor(SourceLocation location, boolean isGetter,
                            List<Modifier> modifiers, Parameter param, AstNode body) {
        super(location);
        this.isGetter = isGetter;
        this.modifiers = modifiers;
        this.param = param;
        this.body = body;
    }

    public boolean isGetter() {
        return isGetter;
    }

    public boolean isSetter() {
        return !isGetter;
    }

    public List<Modifier> getModifiers() {
        return modifiers;
    }

    public Parameter getParam() {
        return param;
    }

    public AstNode getBody() {
        return body;
    }

    @Override
    public <R, C> R accept(AstVisitor<R, C> visitor, C context) {
        // 作为 PropertyDecl 的一部分处理
        return null;
    }
}
