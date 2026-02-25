package com.novalang.ir.mir;

import com.novalang.compiler.ast.Modifier;
import com.novalang.ir.hir.ClassKind;
import com.novalang.ir.hir.HirAnnotation;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * MIR 类。
 */
public class MirClass {

    private final String name;
    private final ClassKind kind;
    private final Set<Modifier> modifiers;
    private final String superClass;
    private final List<String> interfaces;
    private final List<MirField> fields;
    private final List<MirFunction> methods;
    private final List<String> annotationNames;
    /** 原始 HIR 注解（含参数），供解释器注解处理器使用 */
    private List<HirAnnotation> hirAnnotations;
    /** 匿名类的超类构造器描述符（如 "(Ljava/lang/String;)V"），null 表示非匿名类 */
    private String superCtorDesc;

    public MirClass(String name, ClassKind kind, Set<Modifier> modifiers,
                    String superClass, List<String> interfaces,
                    List<MirField> fields, List<MirFunction> methods) {
        this(name, kind, modifiers, superClass, interfaces, fields, methods, Collections.emptyList());
    }

    public MirClass(String name, ClassKind kind, Set<Modifier> modifiers,
                    String superClass, List<String> interfaces,
                    List<MirField> fields, List<MirFunction> methods,
                    List<String> annotationNames) {
        this.name = name;
        this.kind = kind;
        this.modifiers = modifiers;
        this.superClass = superClass;
        this.interfaces = interfaces;
        this.fields = fields;
        this.methods = methods;
        this.annotationNames = annotationNames;
    }

    public String getName() { return name; }
    public ClassKind getKind() { return kind; }
    public Set<Modifier> getModifiers() { return modifiers; }
    public String getSuperClass() { return superClass; }
    public List<String> getInterfaces() { return interfaces; }
    public List<MirField> getFields() { return fields; }
    public List<MirFunction> getMethods() { return methods; }
    public List<String> getAnnotationNames() { return annotationNames; }

    public boolean hasAnnotation(String name) {
        return annotationNames.contains(name);
    }

    public List<HirAnnotation> getHirAnnotations() { return hirAnnotations; }
    public void setHirAnnotations(List<HirAnnotation> hirAnnotations) { this.hirAnnotations = hirAnnotations; }

    public String getSuperCtorDesc() { return superCtorDesc; }
    public void setSuperCtorDesc(String superCtorDesc) { this.superCtorDesc = superCtorDesc; }
}
