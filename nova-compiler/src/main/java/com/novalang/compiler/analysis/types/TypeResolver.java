package com.novalang.compiler.analysis.types;

import com.novalang.compiler.ast.type.*;

import java.util.*;

/**
 * 将 AST TypeRef 转换为结构化 NovaType。
 * 维护类型参数作用域栈（进入 class/fun 时 push，退出时 pop）。
 */
public final class TypeResolver implements TypeRefVisitor<NovaType> {

    // 类型参数作用域栈
    private final Deque<Map<String, TypeParameterType>> typeParamStack = new ArrayDeque<Map<String, TypeParameterType>>();

    // 类型声明注册：className → List<NovaTypeParam>
    private final Map<String, List<NovaTypeParam>> typeDeclarations = new LinkedHashMap<String, List<NovaTypeParam>>();

    /**
     * 将 TypeRef AST 节点解析为 NovaType。
     * 返回 null 表示类型无法解析（缺少类型注解时）。
     */
    public NovaType resolve(TypeRef ref) {
        if (ref == null) return null;
        return ref.accept(this);
    }

    @Override
    public NovaType visitSimple(SimpleType type) {
        String name = type.getName().getFullName();

        // 先查类型参数作用域
        TypeParameterType tpType = lookupTypeParam(name);
        if (tpType != null) return tpType;

        // 查预定义类型
        NovaType builtin = NovaTypes.fromName(name);
        if (builtin != null) return builtin;

        // 用户定义的类类型
        return new ClassNovaType(name, false);
    }

    @Override
    public NovaType visitNullable(NullableType type) {
        NovaType inner = resolve(type.getInnerType());
        if (inner == null) return null;
        return inner.withNullable(true);
    }

    @Override
    public NovaType visitGeneric(GenericType type) {
        String name = type.getName().getFullName();
        List<NovaTypeArgument> args = new ArrayList<NovaTypeArgument>();
        for (TypeArgument ta : type.getTypeArgs()) {
            if (ta.isWildcard()) {
                args.add(NovaTypeArgument.wildcard());
            } else {
                NovaType argType = resolve(ta.getType());
                if (argType == null) argType = NovaTypes.ERROR;
                args.add(new NovaTypeArgument(ta.getVariance(), argType, false));
            }
        }
        return new ClassNovaType(name, args, false);
    }

    @Override
    public NovaType visitFunction(FunctionType type) {
        NovaType receiver = type.hasReceiverType() ? resolve(type.getReceiverType()) : null;
        List<NovaType> params = new ArrayList<NovaType>();
        for (TypeRef pt : type.getParamTypes()) {
            NovaType resolved = resolve(pt);
            params.add(resolved != null ? resolved : NovaTypes.ERROR);
        }
        NovaType ret = resolve(type.getReturnType());
        if (ret == null) ret = NovaTypes.UNIT;
        return new FunctionNovaType(receiver, params, ret, false);
    }

    // ============ 类型参数作用域管理 ============

    /**
     * 进入带类型参数的作用域（class/fun）。
     */
    public void enterTypeParams(List<TypeParameter> params) {
        Map<String, TypeParameterType> scope = new LinkedHashMap<String, TypeParameterType>();
        if (params != null) {
            for (TypeParameter tp : params) {
                NovaType bound = tp.hasUpperBound() ? resolve(tp.getUpperBound()) : NovaTypes.ANY;
                TypeParameterType tpType = new TypeParameterType(tp.getName(), bound, false);
                scope.put(tp.getName(), tpType);
            }
        }
        typeParamStack.push(scope);
    }

    /**
     * 退出类型参数作用域。
     */
    public void exitTypeParams() {
        if (!typeParamStack.isEmpty()) {
            typeParamStack.pop();
        }
    }

    /**
     * 在栈中查找类型参数。
     */
    private TypeParameterType lookupTypeParam(String name) {
        for (Map<String, TypeParameterType> scope : typeParamStack) {
            TypeParameterType tp = scope.get(name);
            if (tp != null) return tp;
        }
        return null;
    }

    // ============ 类型声明注册 ============

    /**
     * 注册类型声明的类型参数信息。
     */
    public void registerTypeDeclaration(String name, List<TypeParameter> params) {
        List<NovaTypeParam> resolved = new ArrayList<NovaTypeParam>();
        if (params != null) {
            for (TypeParameter tp : params) {
                NovaType bound = tp.hasUpperBound() ? resolve(tp.getUpperBound()) : NovaTypes.ANY;
                resolved.add(new NovaTypeParam(tp.getName(), tp.getVariance(), bound, tp.isReified()));
            }
        }
        typeDeclarations.put(name, resolved);
    }

    /**
     * 获取已注册类型的类型参数。
     */
    public List<NovaTypeParam> getTypeParams(String className) {
        return typeDeclarations.get(className);
    }
}
