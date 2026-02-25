package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.type.*;

import java.util.*;

/**
 * 泛型统一 + 公共父类型计算。
 */
public final class TypeUnifier {

    private final Map<Expression, NovaType> exprNovaTypeMap;
    private final SuperTypeRegistry superTypeRegistry;
    private final TypeResolver typeResolver;

    public TypeUnifier(Map<Expression, NovaType> exprNovaTypeMap,
                       SuperTypeRegistry superTypeRegistry,
                       TypeResolver typeResolver) {
        this.exprNovaTypeMap = exprNovaTypeMap;
        this.superTypeRegistry = superTypeRegistry;
        this.typeResolver = typeResolver;
    }

    /**
     * 计算两个类型的最小公共父类型。
     */
    public NovaType commonSuperType(NovaType a, NovaType b) {
        if (a == null) return b;
        if (b == null) return a;
        if (a.equals(b)) return a;

        if (a instanceof ErrorType) return b;
        if (b instanceof ErrorType) return a;
        if (a instanceof NothingType) return b;
        if (b instanceof NothingType) return a;

        boolean nullable = a.isNullable() || b.isNullable();

        // 数值提升
        if (NovaTypes.isNumericType(a) && NovaTypes.isNumericType(b)) {
            NovaType promoted = NovaTypes.promoteNumeric(a, b);
            return nullable ? promoted.withNullable(true) : promoted;
        }

        // 同名类类型 → 合并泛型参数
        String nameA = a.getTypeName();
        String nameB = b.getTypeName();
        if (nameA != null && nameB != null && nameA.equals(nameB)) {
            if (a instanceof ClassNovaType && b instanceof ClassNovaType) {
                ClassNovaType ca = (ClassNovaType) a;
                ClassNovaType cb = (ClassNovaType) b;
                if (ca.hasTypeArgs() && cb.hasTypeArgs() &&
                    ca.getTypeArgs().size() == cb.getTypeArgs().size()) {
                    List<NovaTypeArgument> merged = new ArrayList<NovaTypeArgument>();
                    for (int i = 0; i < ca.getTypeArgs().size(); i++) {
                        NovaType ma = ca.getTypeArgs().get(i).getType();
                        NovaType mb = cb.getTypeArgs().get(i).getType();
                        NovaType m = (ma != null && mb != null) ? commonSuperType(ma, mb) : NovaTypes.ANY;
                        merged.add(NovaTypeArgument.invariant(m));
                    }
                    return new ClassNovaType(nameA, merged, nullable);
                }
            }
            return nullable ? a.withNullable(true) : a;
        }

        // 子类型关系
        if (nameA != null && nameB != null) {
            if (superTypeRegistry.isSubtype(nameA, nameB)) {
                return nullable ? b.withNullable(true) : b;
            }
            if (superTypeRegistry.isSubtype(nameB, nameA)) {
                return nullable ? a.withNullable(true) : a;
            }
        }

        return nullable ? NovaTypes.ANY.withNullable(true) : NovaTypes.ANY;
    }

    /**
     * 泛型函数返回类型推断：从实参类型推断类型参数并替换返回类型。
     */
    public NovaType inferGenericReturnType(Symbol funcSym, List<CallExpr.Argument> args) {
        AstNode decl = funcSym.getDeclaration();
        if (!(decl instanceof FunDecl)) return null;
        FunDecl funDecl = (FunDecl) decl;
        List<TypeParameter> typeParams = funDecl.getTypeParams();
        if (typeParams == null || typeParams.isEmpty()) return null;

        NovaType retType = funcSym.getResolvedNovaType();
        if (retType == null) return null;

        List<Symbol> paramSymbols = funcSym.getParameters();
        Map<String, NovaType> bindings = inferBindingsFromSymbols(typeParams, paramSymbols, args);
        if (bindings.isEmpty()) return null;

        return substituteTypeParams(retType, bindings);
    }

    /**
     * 泛型构造器类型推断：class Box&lt;T&gt;(val value: T) → Box(42) 推断为 Box&lt;Int&gt;
     */
    public NovaType inferGenericConstructorType(Symbol classSym, List<CallExpr.Argument> args) {
        AstNode decl = classSym.getDeclaration();
        if (!(decl instanceof ClassDecl)) return null;
        ClassDecl classDecl = (ClassDecl) decl;
        List<TypeParameter> typeParams = classDecl.getTypeParams();
        if (typeParams == null || typeParams.isEmpty()) return null;

        List<Parameter> ctorParams = classDecl.getPrimaryConstructorParams();
        if (ctorParams == null || ctorParams.isEmpty()) return null;

        typeResolver.enterTypeParams(typeParams);
        List<NovaType> formalTypes = new ArrayList<NovaType>();
        for (Parameter p : ctorParams) {
            formalTypes.add(typeResolver.resolve(p.getType()));
        }
        typeResolver.exitTypeParams();

        Set<String> typeParamNames = new HashSet<String>();
        for (TypeParameter tp : typeParams) {
            typeParamNames.add(tp.getName());
        }

        Map<String, NovaType> bindings = new HashMap<String, NovaType>();
        int count = Math.min(formalTypes.size(), args.size());
        for (int i = 0; i < count; i++) {
            NovaType formalType = formalTypes.get(i);
            NovaType argType = exprNovaTypeMap.get(args.get(i).getValue());
            if (formalType == null || argType == null) continue;
            collectBindings(formalType, argType, typeParamNames, bindings);
        }
        if (bindings.isEmpty()) return null;

        List<NovaTypeArgument> typeArgs = new ArrayList<NovaTypeArgument>();
        for (TypeParameter tp : typeParams) {
            NovaType bound = bindings.get(tp.getName());
            typeArgs.add(NovaTypeArgument.invariant(bound != null ? bound : NovaTypes.ANY));
        }
        return new ClassNovaType(classDecl.getName(), typeArgs, false);
    }

    /**
     * 使用已解析的参数 Symbol 推断类型参数绑定（适用于函数调用）。
     */
    private Map<String, NovaType> inferBindingsFromSymbols(
            List<TypeParameter> typeParams,
            List<Symbol> paramSymbols,
            List<CallExpr.Argument> actualArgs) {
        Map<String, NovaType> bindings = new HashMap<String, NovaType>();
        if (paramSymbols == null || actualArgs == null) return bindings;

        Set<String> typeParamNames = new HashSet<String>();
        for (TypeParameter tp : typeParams) {
            typeParamNames.add(tp.getName());
        }

        int count = Math.min(paramSymbols.size(), actualArgs.size());
        for (int i = 0; i < count; i++) {
            NovaType formalType = paramSymbols.get(i).getResolvedNovaType();
            NovaType argType = exprNovaTypeMap.get(actualArgs.get(i).getValue());
            if (formalType == null || argType == null) continue;
            collectBindings(formalType, argType, typeParamNames, bindings);
        }
        return bindings;
    }

    /**
     * 递归匹配形参类型与实参类型，收集类型参数绑定。
     */
    private void collectBindings(NovaType formal, NovaType actual,
                                  Set<String> typeParamNames,
                                  Map<String, NovaType> bindings) {
        formal.accept(new NovaTypeVisitor<Void>() {
            @Override
            public Void visitTypeParameter(TypeParameterType tp) {
                String name = tp.getName();
                if (typeParamNames.contains(name)) {
                    NovaType existing = bindings.get(name);
                    bindings.put(name, existing == null ? actual : commonSuperType(existing, actual));
                }
                return null;
            }

            @Override
            public Void visitClass(ClassNovaType formalClass) {
                if (actual instanceof ClassNovaType) {
                    ClassNovaType actualClass = (ClassNovaType) actual;
                    if (formalClass.getName().equals(actualClass.getName()) &&
                        formalClass.hasTypeArgs() && actualClass.hasTypeArgs() &&
                        formalClass.getTypeArgs().size() == actualClass.getTypeArgs().size()) {
                        for (int i = 0; i < formalClass.getTypeArgs().size(); i++) {
                            NovaType formalArg = formalClass.getTypeArgs().get(i).getType();
                            NovaType actualArg = actualClass.getTypeArgs().get(i).getType();
                            if (formalArg != null && actualArg != null) {
                                collectBindings(formalArg, actualArg, typeParamNames, bindings);
                            }
                        }
                    }
                }
                return null;
            }

            @Override
            public Void visitFunction(FunctionNovaType formalFn) {
                if (actual instanceof FunctionNovaType) {
                    FunctionNovaType actualFn = (FunctionNovaType) actual;
                    int cnt = Math.min(formalFn.getParamTypes().size(), actualFn.getParamTypes().size());
                    for (int i = 0; i < cnt; i++) {
                        collectBindings(formalFn.getParamTypes().get(i), actualFn.getParamTypes().get(i),
                                typeParamNames, bindings);
                    }
                    if (formalFn.getReturnType() != null && actualFn.getReturnType() != null) {
                        collectBindings(formalFn.getReturnType(), actualFn.getReturnType(),
                                typeParamNames, bindings);
                    }
                }
                return null;
            }

            @Override
            public Void visitPrimitive(PrimitiveNovaType type) { return null; }
            @Override
            public Void visitNothing(NothingType type) { return null; }
            @Override
            public Void visitUnit(UnitType type) { return null; }
            @Override
            public Void visitError(ErrorType type) { return null; }
        });
    }

    /** 将类型中的类型参数替换为具体类型 */
    private NovaType substituteTypeParams(NovaType type, Map<String, NovaType> bindings) {
        return type.accept(new NovaTypeVisitor<NovaType>() {
            @Override
            public NovaType visitTypeParameter(TypeParameterType tp) {
                NovaType bound = bindings.get(tp.getName());
                if (bound != null) {
                    return tp.isNullable() ? bound.withNullable(true) : bound;
                }
                return tp;
            }

            @Override
            public NovaType visitClass(ClassNovaType ct) {
                if (!ct.hasTypeArgs()) return ct;
                List<NovaTypeArgument> newArgs = new ArrayList<NovaTypeArgument>();
                boolean changed = false;
                for (NovaTypeArgument ta : ct.getTypeArgs()) {
                    if (ta.getType() == null) {
                        newArgs.add(ta);
                    } else {
                        NovaType sub = substituteTypeParams(ta.getType(), bindings);
                        if (sub != ta.getType()) changed = true;
                        newArgs.add(new NovaTypeArgument(ta.getVariance(), sub, ta.isWildcard()));
                    }
                }
                return changed ? new ClassNovaType(ct.getName(), newArgs, ct.isNullable()) : ct;
            }

            @Override
            public NovaType visitFunction(FunctionNovaType ft) {
                NovaType newRet = ft.getReturnType() != null
                        ? substituteTypeParams(ft.getReturnType(), bindings) : null;
                List<NovaType> newParams = new ArrayList<NovaType>();
                boolean changed = (newRet != ft.getReturnType());
                for (NovaType pt : ft.getParamTypes()) {
                    NovaType sub = substituteTypeParams(pt, bindings);
                    if (sub != pt) changed = true;
                    newParams.add(sub);
                }
                NovaType newRecv = ft.getReceiverType() != null
                        ? substituteTypeParams(ft.getReceiverType(), bindings) : null;
                if (newRecv != ft.getReceiverType()) changed = true;
                return changed ? new FunctionNovaType(newRecv, newParams, newRet, ft.isNullable()) : ft;
            }

            @Override
            public NovaType visitPrimitive(PrimitiveNovaType type) { return type; }
            @Override
            public NovaType visitNothing(NothingType type) { return type; }
            @Override
            public NovaType visitUnit(UnitType type) { return type; }
            @Override
            public NovaType visitError(ErrorType type) { return type; }
        });
    }
}
