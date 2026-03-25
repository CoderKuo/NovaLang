package com.novalang.ir.mir;

import com.novalang.compiler.ast.Modifier;
import com.novalang.ir.hir.HirAnnotation;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * MIR 字段。
 */
public class MirField {

    private final String name;
    private final MirType type;
    private final Set<Modifier> modifiers;
    private String getterFunctionName;
    private String setterFunctionName;

    public MirField(String name, MirType type, Set<Modifier> modifiers) {
        this.name = name;
        this.type = type;
        this.modifiers = modifiers;
    }

    public String getName() { return name; }
    public MirType getType() { return type; }
    public Set<Modifier> getModifiers() { return modifiers; }

    public String getGetterFunctionName() { return getterFunctionName; }
    public void setGetterFunctionName(String name) { this.getterFunctionName = name; }
    public boolean hasCustomGetter() { return getterFunctionName != null; }

    public String getSetterFunctionName() { return setterFunctionName; }
    public void setSetterFunctionName(String name) { this.setterFunctionName = name; }
    public boolean hasCustomSetter() { return setterFunctionName != null; }

    private List<HirAnnotation> hirAnnotations = Collections.emptyList();
    public List<HirAnnotation> getHirAnnotations() { return hirAnnotations; }
    public void setHirAnnotations(List<HirAnnotation> anns) { this.hirAnnotations = anns; }
}
