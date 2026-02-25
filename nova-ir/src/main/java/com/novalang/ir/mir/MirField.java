package com.novalang.ir.mir;

import com.novalang.compiler.ast.Modifier;

import java.util.Set;

/**
 * MIR 字段。
 */
public class MirField {

    private final String name;
    private final MirType type;
    private final Set<Modifier> modifiers;

    public MirField(String name, MirType type, Set<Modifier> modifiers) {
        this.name = name;
        this.type = type;
        this.modifiers = modifiers;
    }

    public String getName() { return name; }
    public MirType getType() { return type; }
    public Set<Modifier> getModifiers() { return modifiers; }
}
