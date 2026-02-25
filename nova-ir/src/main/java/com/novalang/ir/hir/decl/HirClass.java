package com.novalang.ir.hir.decl;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.Modifier;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 统一的类声明，classKind 区分 CLASS/INTERFACE/ENUM/OBJECT/ANNOTATION。
 */
public class HirClass extends HirDecl {

    private final ClassKind classKind;
    private final List<String> typeParams;
    private final List<HirField> fields;
    private final List<HirFunction> methods;
    private final List<HirFunction> constructors;
    private final HirType superClass;
    private final List<HirType> interfaces;
    private final List<HirEnumEntry> enumEntries;
    private final List<Expression> superConstructorArgs;
    /** 有序实例初始化列表：HirField（有初始化器的实例字段）和 AstNode（init 块体）按声明顺序 */
    private final List<AstNode> instanceInitializers;

    public HirClass(SourceLocation location, String name, Set<Modifier> modifiers,
                    List<HirAnnotation> annotations, ClassKind classKind,
                    List<String> typeParams, List<HirField> fields,
                    List<HirFunction> methods, List<HirFunction> constructors,
                    HirType superClass, List<HirType> interfaces,
                    List<HirEnumEntry> enumEntries) {
        this(location, name, modifiers, annotations, classKind, typeParams, fields,
             methods, constructors, superClass, interfaces, enumEntries,
             Collections.emptyList(), Collections.emptyList());
    }

    public HirClass(SourceLocation location, String name, Set<Modifier> modifiers,
                    List<HirAnnotation> annotations, ClassKind classKind,
                    List<String> typeParams, List<HirField> fields,
                    List<HirFunction> methods, List<HirFunction> constructors,
                    HirType superClass, List<HirType> interfaces,
                    List<HirEnumEntry> enumEntries, List<Expression> superConstructorArgs) {
        this(location, name, modifiers, annotations, classKind, typeParams, fields,
             methods, constructors, superClass, interfaces, enumEntries,
             superConstructorArgs, Collections.emptyList());
    }

    public HirClass(SourceLocation location, String name, Set<Modifier> modifiers,
                    List<HirAnnotation> annotations, ClassKind classKind,
                    List<String> typeParams, List<HirField> fields,
                    List<HirFunction> methods, List<HirFunction> constructors,
                    HirType superClass, List<HirType> interfaces,
                    List<HirEnumEntry> enumEntries, List<Expression> superConstructorArgs,
                    List<AstNode> instanceInitializers) {
        super(location, name, modifiers, annotations);
        this.classKind = classKind;
        this.typeParams = typeParams;
        this.fields = fields;
        this.methods = methods;
        this.constructors = constructors;
        this.superClass = superClass;
        this.interfaces = interfaces;
        this.enumEntries = enumEntries;
        this.superConstructorArgs = superConstructorArgs;
        this.instanceInitializers = instanceInitializers;
    }

    public ClassKind getClassKind() {
        return classKind;
    }

    public List<String> getTypeParams() {
        return typeParams;
    }

    public List<HirField> getFields() {
        return fields;
    }

    public List<HirFunction> getMethods() {
        return methods;
    }

    public List<HirFunction> getConstructors() {
        return constructors;
    }

    public HirType getSuperClass() {
        return superClass;
    }

    public List<HirType> getInterfaces() {
        return interfaces;
    }

    public List<HirEnumEntry> getEnumEntries() {
        return enumEntries;
    }

    public List<Expression> getSuperConstructorArgs() {
        return superConstructorArgs;
    }

    public List<AstNode> getInstanceInitializers() {
        return instanceInitializers;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitClass(this, context);
    }
}
