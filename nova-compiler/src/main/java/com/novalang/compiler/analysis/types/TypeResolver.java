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
    private final Set<String> knownTypeNames = new LinkedHashSet<String>();
    private final Map<String, TypeRef> typeAliases = new LinkedHashMap<String, TypeRef>();
    private final Map<String, List<TypeParameter>> typeAliasParams = new LinkedHashMap<String, List<TypeParameter>>();
    private final Map<String, String> novaImportedTypes = new LinkedHashMap<String, String>();
    private final Set<String> novaWildcardImports = new LinkedHashSet<String>();
    private final Map<String, String> javaImportedTypes = new LinkedHashMap<String, String>();
    private final Set<String> javaWildcardImports = new LinkedHashSet<String>();
    private final Set<String> resolvingAliases = new HashSet<String>();
    private final Deque<Map<String, NovaType>> aliasBindingStack = new ArrayDeque<Map<String, NovaType>>();
    private final JavaTypeOracle javaTypeOracle = JavaTypeOracle.get();
    private String currentPackageName = "";

    public TypeResolver() {
        Collections.addAll(knownTypeNames,
                "List", "Set", "Map", "Range", "Pair", "Result", "Ok", "Err", "Array",
                "Future", "Deferred", "Scope", "Job", "Exception", "Comparable",
                "dynamic", "Dynamic",
                "Byte", "Short");
    }

    /**
     * 将 TypeRef AST 节点解析为 NovaType。
     * 返回 null 表示类型无法解析（缺少类型注解时）。
     */
    public NovaType resolve(TypeRef ref) {
        if (ref == null) return null;
        return ref.accept(this);
    }

    public NovaType resolveTypeNameReference(String typeName) {
        if (typeName == null || typeName.isEmpty()) return null;
        String normalizedName = normalizeTypeName(typeName);
        NovaType aliasBinding = lookupAliasBinding(normalizedName);
        if (aliasBinding != null) return aliasBinding;

        TypeParameterType tpType = lookupTypeParam(normalizedName);
        if (tpType != null) return tpType;

        NovaType builtin = NovaTypes.fromName(normalizedName);
        if (builtin != null) return builtin;

        TypeRef alias = typeAliases.get(normalizedName);
        if (alias != null) {
            if (!resolvingAliases.add(normalizedName)) {
                return NovaTypes.ERROR;
            }
            try {
                NovaType resolvedAlias = resolve(alias);
                return resolvedAlias != null ? resolvedAlias : NovaTypes.ERROR;
            } finally {
                resolvingAliases.remove(normalizedName);
            }
        }

        String resolvedNovaTypeName = resolveVisibleNovaTypeName(normalizedName);
        if (resolvedNovaTypeName != null) {
            return new ClassNovaType(displayTypeName(typeName, resolvedNovaTypeName), false);
        }

        JavaTypeDescriptor importedJavaType = resolveImportedJavaType(normalizedName);
        if (importedJavaType != null) {
            return new JavaClassNovaType(importedJavaType, false);
        }
        JavaTypeDescriptor javaType = javaTypeOracle.resolve(normalizedName);
        if (javaType != null) {
            return new JavaClassNovaType(javaType, false);
        }
        return null;
    }

    @Override
    public NovaType visitSimple(SimpleType type) {
        String rawName = type.getName().getFullName();
        String name = normalizeTypeName(rawName);
        NovaType aliasBinding = lookupAliasBinding(name);
        if (aliasBinding != null) return aliasBinding;

        // 先查类型参数作用域
        TypeParameterType tpType = lookupTypeParam(name);
        if (tpType != null) return tpType;

        // 查预定义类型
        NovaType builtin = NovaTypes.fromName(name);
        if (builtin != null) return builtin;

        // 用户定义的类类型
        TypeRef alias = typeAliases.get(name);
        if (alias != null) {
            if (!resolvingAliases.add(name)) {
                return NovaTypes.ERROR;
            }
            try {
                NovaType resolvedAlias = resolve(alias);
                return resolvedAlias != null ? resolvedAlias : NovaTypes.ERROR;
            } finally {
                resolvingAliases.remove(name);
            }
        }
        String resolvedNovaTypeName = resolveVisibleNovaTypeName(name);
        if (resolvedNovaTypeName != null) {
            return new ClassNovaType(displayTypeName(rawName, resolvedNovaTypeName), false);
        }
        JavaTypeDescriptor importedJavaType = resolveImportedJavaType(name);
        if (importedJavaType != null) {
            return new JavaClassNovaType(importedJavaType, false);
        }
        JavaTypeDescriptor javaType = javaTypeOracle.resolve(name);
        if (javaType != null) {
            return new JavaClassNovaType(javaType, false);
        }
        return NovaTypes.ERROR;
    }

    @Override
    public NovaType visitNullable(NullableType type) {
        NovaType inner = resolve(type.getInnerType());
        if (inner == null) return null;
        return inner.withNullable(true);
    }

    @Override
    public NovaType visitGeneric(GenericType type) {
        String rawName = type.getName().getFullName();
        String name = normalizeTypeName(rawName);
        if (typeAliases.containsKey(name)) {
            return resolveAlias(name, type.getTypeArgs());
        }
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
        String resolvedNovaTypeName = resolveVisibleNovaTypeName(name);
        if (resolvedNovaTypeName != null) {
            return new ClassNovaType(displayTypeName(rawName, resolvedNovaTypeName), args, false);
        }
        JavaTypeDescriptor importedJavaType = resolveImportedJavaType(name);
        if (importedJavaType != null) {
            return new JavaClassNovaType(importedJavaType, args, false);
        }
        JavaTypeDescriptor javaType = javaTypeOracle.resolve(name);
        if (javaType != null) {
            return new JavaClassNovaType(javaType, args, false);
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

    private NovaType lookupAliasBinding(String name) {
        for (Map<String, NovaType> scope : aliasBindingStack) {
            NovaType binding = scope.get(name);
            if (binding != null) return binding;
        }
        return null;
    }

    private NovaType resolveAlias(String name, List<TypeArgument> actualArgs) {
        TypeRef aliasedType = typeAliases.get(name);
        if (aliasedType == null) return NovaTypes.ERROR;

        List<TypeParameter> params = typeAliasParams.get(name);
        if (params == null || params.isEmpty()) {
            return resolve(aliasedType);
        }

        Map<String, NovaType> bindings = new LinkedHashMap<String, NovaType>();
        for (int i = 0; i < params.size(); i++) {
            NovaType resolvedArg = NovaTypes.ANY;
            if (actualArgs != null && i < actualArgs.size()) {
                TypeArgument arg = actualArgs.get(i);
                if (!arg.isWildcard() && arg.getType() != null) {
                    NovaType actualType = resolve(arg.getType());
                    resolvedArg = actualType != null ? actualType : NovaTypes.ANY;
                }
            }
            bindings.put(params.get(i).getName(), resolvedArg);
        }

        aliasBindingStack.push(bindings);
        try {
            NovaType resolved = resolve(aliasedType);
            return resolved != null ? resolved : NovaTypes.ERROR;
        } finally {
            aliasBindingStack.pop();
        }
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
        TypeCompatibility.registerDeclarationSiteTypeParams(name, resolved);
    }

    /**
     * 获取已注册类型的类型参数。
     */
    public List<NovaTypeParam> getTypeParams(String className) {
        return typeDeclarations.get(className);
    }

    public void registerKnownType(String name) {
        if (name != null) {
            knownTypeNames.add(name);
        }
    }

    public void setCurrentPackageName(String packageName) {
        this.currentPackageName = packageName != null ? packageName : "";
    }

    public void registerNovaImport(String aliasOrSimpleName, String qualifiedName) {
        if (aliasOrSimpleName != null && qualifiedName != null) {
            novaImportedTypes.put(aliasOrSimpleName, qualifiedName);
        }
    }

    public void registerNovaWildcardImport(String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            novaWildcardImports.add(packageName);
        }
    }

    public void registerJavaImport(String aliasOrSimpleName, String qualifiedName) {
        if (aliasOrSimpleName != null && qualifiedName != null) {
            javaImportedTypes.put(aliasOrSimpleName, qualifiedName);
        }
    }

    public void registerJavaWildcardImport(String packageName) {
        if (packageName != null && !packageName.isEmpty()) {
            javaWildcardImports.add(packageName);
        }
    }

    public void registerTypeAlias(String name, TypeRef aliasedType) {
        registerTypeAlias(name, Collections.<TypeParameter>emptyList(), aliasedType);
    }

    public void registerTypeAlias(String name, List<TypeParameter> params, TypeRef aliasedType) {
        if (name != null && aliasedType != null) {
            typeAliases.put(name, aliasedType);
            typeAliasParams.put(name, params != null ? params : Collections.<TypeParameter>emptyList());
        }
    }

    public boolean knowsTypeName(String name) {
        name = normalizeTypeName(name);
        return name != null &&
                (lookupTypeParam(name) != null
                        || NovaTypes.fromName(name) != null
                        || typeAliases.containsKey(name)
                        || resolveVisibleNovaTypeName(name) != null
                        || resolveImportedJavaType(name) != null
                        || javaTypeOracle.resolve(name) != null);
    }

    private String resolveVisibleNovaTypeName(String name) {
        if (name == null) return null;
        if (knownTypeNames.contains(name) || typeDeclarations.containsKey(name) || typeAliases.containsKey(name)) {
            return name;
        }
        if (name.indexOf('.') < 0) {
            if (currentPackageName != null && !currentPackageName.isEmpty()) {
                String packageQualified = currentPackageName + "." + name;
                if (knownTypeNames.contains(packageQualified)
                        || typeDeclarations.containsKey(packageQualified)
                        || typeAliases.containsKey(packageQualified)) {
                    return packageQualified;
                }
            }
            String importedQualified = novaImportedTypes.get(name);
            if (importedQualified != null) {
                return importedQualified;
            }
            for (String pkg : novaWildcardImports) {
                String wildcardQualified = pkg + "." + name;
                if (knownTypeNames.contains(wildcardQualified)
                        || typeDeclarations.containsKey(wildcardQualified)
                        || typeAliases.containsKey(wildcardQualified)) {
                    return wildcardQualified;
                }
            }
        }
        return null;
    }

    private JavaTypeDescriptor resolveImportedJavaType(String name) {
        String qualifiedName = javaImportedTypes.get(name);
        if (qualifiedName != null) {
            return javaTypeOracle.resolve(qualifiedName);
        }
        if (name != null && name.indexOf('.') < 0) {
            for (String pkg : javaWildcardImports) {
                JavaTypeDescriptor descriptor = javaTypeOracle.resolve(pkg + "." + name);
                if (descriptor != null) {
                    return descriptor;
                }
            }
        }
        return null;
    }

    private String normalizeTypeName(String name) {
        if (name == null) return null;
        return name.startsWith("nova.") ? name.substring("nova.".length()) : name;
    }

    private String displayTypeName(String rawName, String resolvedName) {
        if (rawName == null || rawName.isEmpty()) return resolvedName;
        if (rawName.startsWith("nova.")) {
            return resolvedName;
        }
        if (rawName.indexOf('.') < 0) {
            return rawName;
        }
        return resolvedName != null && resolvedName.indexOf('.') < 0 ? resolvedName : rawName;
    }
}
