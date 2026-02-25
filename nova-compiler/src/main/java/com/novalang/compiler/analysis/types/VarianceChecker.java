package com.novalang.compiler.analysis.types;

import com.novalang.compiler.analysis.SemanticDiagnostic;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.type.*;

import java.util.*;

/**
 * 检查 declaration-site variance 的位置合法性。
 *
 * <ul>
 *   <li>{@code out T}: 只能出现在协变位置（返回类型、val 属性类型）</li>
 *   <li>{@code in T}: 只能出现在逆变位置（方法参数类型）</li>
 * </ul>
 *
 * 嵌套泛型中位置会翻转：{@code List<in T>} 中的 {@code in} 翻转 T 的位置。
 */
public final class VarianceChecker {

    private VarianceChecker() {}

    /**
     * 检查类声明中的 variance 违规。
     *
     * @return 诊断列表（WARNING 级别）
     */
    public static List<SemanticDiagnostic> check(ClassDecl classDecl, TypeResolver resolver) {
        List<SemanticDiagnostic> diagnostics = new ArrayList<SemanticDiagnostic>();

        if (classDecl.getTypeParams() == null) return diagnostics;

        // 收集有 variance 标注的类型参数
        Map<String, TypeArgument.Variance> variantParams = new LinkedHashMap<String, TypeArgument.Variance>();
        for (TypeParameter tp : classDecl.getTypeParams()) {
            if (tp.getVariance() != TypeArgument.Variance.INVARIANT) {
                variantParams.put(tp.getName(), tp.getVariance());
            }
        }
        if (variantParams.isEmpty()) return diagnostics;

        // 检查构造参数（val/var 属性）
        if (classDecl.getPrimaryConstructorParams() != null) {
            for (Parameter p : classDecl.getPrimaryConstructorParams()) {
                if (p.isProperty() && p.getType() != null) {
                    boolean isVar = !p.hasModifier(com.novalang.compiler.ast.Modifier.FINAL);
                    if (isVar) {
                        // var 属性：类型参数不能是 out 也不能是 in
                        checkType(p.getType(), variantParams, Position.INVARIANT, diagnostics,
                                "var 属性 '" + p.getName() + "'");
                    } else {
                        // val 属性：协变位置（getter 返回类型）
                        checkType(p.getType(), variantParams, Position.OUT, diagnostics,
                                "val 属性 '" + p.getName() + "'");
                    }
                }
            }
        }

        // 检查成员
        for (Declaration member : classDecl.getMembers()) {
            if (member instanceof FunDecl) {
                checkFunDecl((FunDecl) member, variantParams, diagnostics);
            } else if (member instanceof PropertyDecl) {
                checkPropertyDecl((PropertyDecl) member, variantParams, diagnostics);
            }
        }

        return diagnostics;
    }

    private static void checkFunDecl(FunDecl fun, Map<String, TypeArgument.Variance> variantParams,
                                      List<SemanticDiagnostic> diagnostics) {
        // 方法参数：逆变位置
        for (Parameter p : fun.getParams()) {
            if (p.getType() != null) {
                checkType(p.getType(), variantParams, Position.IN, diagnostics,
                        "方法 '" + fun.getName() + "' 的参数 '" + p.getName() + "'");
            }
        }
        // 方法返回类型：协变位置
        if (fun.getReturnType() != null) {
            checkType(fun.getReturnType(), variantParams, Position.OUT, diagnostics,
                    "方法 '" + fun.getName() + "' 的返回类型");
        }
    }

    private static void checkPropertyDecl(PropertyDecl prop, Map<String, TypeArgument.Variance> variantParams,
                                           List<SemanticDiagnostic> diagnostics) {
        if (prop.getType() == null) return;
        if (prop.isVal()) {
            // val 属性：协变位置
            checkType(prop.getType(), variantParams, Position.OUT, diagnostics,
                    "val 属性 '" + prop.getName() + "'");
        } else {
            // var 属性：不变位置
            checkType(prop.getType(), variantParams, Position.INVARIANT, diagnostics,
                    "var 属性 '" + prop.getName() + "'");
        }
    }

    private static void checkType(TypeRef typeRef, Map<String, TypeArgument.Variance> variantParams,
                                   Position position, List<SemanticDiagnostic> diagnostics,
                                   String context) {
        typeRef.accept(new TypeRefVisitor<Void>() {
            @Override
            public Void visitSimple(SimpleType type) {
                String name = type.getName().getFullName();
                checkTypeParamPosition(name, variantParams, position, typeRef, diagnostics, context);
                return null;
            }

            @Override
            public Void visitNullable(NullableType type) {
                checkType(type.getInnerType(), variantParams, position, diagnostics, context);
                return null;
            }

            @Override
            public Void visitGeneric(GenericType gt) {
                // 基类名本身
                String baseName = gt.getName().getFullName();
                checkTypeParamPosition(baseName, variantParams, position, typeRef, diagnostics, context);
                // 类型参数
                for (TypeArgument ta : gt.getTypeArgs()) {
                    if (ta.isWildcard() || ta.getType() == null) continue;
                    Position argPosition = position;
                    // 类型参数的 variance 可能翻转位置
                    if (ta.getVariance() == TypeArgument.Variance.IN) {
                        argPosition = position.flip();
                    }
                    checkType(ta.getType(), variantParams, argPosition, diagnostics, context);
                }
                return null;
            }

            @Override
            public Void visitFunction(FunctionType ft) {
                // 函数参数：逆变位置（翻转）
                for (TypeRef pt : ft.getParamTypes()) {
                    checkType(pt, variantParams, position.flip(), diagnostics, context);
                }
                // 函数返回类型：协变位置（不翻转）
                if (ft.getReturnType() != null) {
                    checkType(ft.getReturnType(), variantParams, position, diagnostics, context);
                }
                return null;
            }
        });
    }

    private static void checkTypeParamPosition(String name, Map<String, TypeArgument.Variance> variantParams,
                                                Position position, TypeRef typeRef,
                                                List<SemanticDiagnostic> diagnostics, String context) {
        TypeArgument.Variance declaredVariance = variantParams.get(name);
        if (declaredVariance == null) return;

        boolean violation = false;
        String msg = null;

        if (declaredVariance == TypeArgument.Variance.OUT) {
            // out T 不能出现在逆变或不变位置
            if (position == Position.IN) {
                violation = true;
                msg = "类型参数 'out " + name + "' 不能出现在逆变位置（" + context + "）";
            } else if (position == Position.INVARIANT) {
                violation = true;
                msg = "类型参数 'out " + name + "' 不能出现在不变位置（" + context + "）";
            }
        } else if (declaredVariance == TypeArgument.Variance.IN) {
            // in T 不能出现在协变位置
            if (position == Position.OUT) {
                violation = true;
                msg = "类型参数 'in " + name + "' 不能出现在协变位置（" + context + "）";
            } else if (position == Position.INVARIANT) {
                violation = true;
                msg = "类型参数 'in " + name + "' 不能出现在不变位置（" + context + "）";
            }
        }

        if (violation) {
            diagnostics.add(new SemanticDiagnostic(SemanticDiagnostic.Severity.WARNING,
                    msg, typeRef.getLocation(), Math.max(typeRef.getLocation().getLength(), 1)));
        }
    }

    /** 位置类型：协变（OUT）、逆变（IN）、不变（INVARIANT） */
    private enum Position {
        OUT, IN, INVARIANT;

        /** 翻转位置 */
        Position flip() {
            switch (this) {
                case OUT: return IN;
                case IN: return OUT;
                default: return INVARIANT;
            }
        }
    }
}
