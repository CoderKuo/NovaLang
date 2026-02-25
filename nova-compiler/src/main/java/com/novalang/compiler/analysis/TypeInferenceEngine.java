package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.expr.*;
import nova.runtime.NovaTypeRegistry;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 表达式类型推断引擎：从表达式推断 NovaType，不涉及泛型统一。
 */
public final class TypeInferenceEngine {

    private final Map<Expression, NovaType> exprNovaTypeMap;
    private final TypeUnifier typeUnifier;

    public TypeInferenceEngine(Map<Expression, NovaType> exprNovaTypeMap, TypeUnifier typeUnifier) {
        this.exprNovaTypeMap = exprNovaTypeMap;
        this.typeUnifier = typeUnifier;
    }

    /** 从字符串类型名回退解析为 NovaType */
    public NovaType resolveNovaTypeFromName(String typeName) {
        if (typeName == null) return null;
        boolean nullable = typeName.endsWith("?");
        String base = nullable ? typeName.substring(0, typeName.length() - 1) : typeName;
        int ltIdx = base.indexOf('<');
        String simpleName = ltIdx > 0 ? base.substring(0, ltIdx) : base;

        NovaType result = NovaTypes.fromName(simpleName);
        if (result == null) {
            result = new ClassNovaType(simpleName, false);
        }
        if (nullable) {
            result = result.withNullable(true);
        }
        return result;
    }

    /** 提取基础类型名（用于 NovaTypeRegistry 查询和类型符号查找） */
    public static String getBaseTypeName(NovaType type) {
        if (type == null) return null;
        String name = type.getTypeName();
        if (name != null) return name;
        return type.toDisplayString().replace("?", "");
    }

    /** 从集合 NovaType 推断元素类型 */
    public NovaType inferElementNovaType(NovaType collType) {
        if (collType == null) return null;
        String baseName = getBaseTypeName(collType);
        if ("Range".equals(baseName)) return NovaTypes.INT;
        if ("String".equals(baseName)) return NovaTypes.STRING;
        if (collType instanceof ClassNovaType) {
            ClassNovaType ct = (ClassNovaType) collType;
            if (ct.hasTypeArgs() && !ct.getTypeArgs().isEmpty()) {
                if ("List".equals(baseName) || "Set".equals(baseName) || "Array".equals(baseName)) {
                    return ct.getTypeArgs().get(0).getType();
                }
                if ("Map".equals(baseName)) return new ClassNovaType("Pair", false);
            }
        }
        return null;
    }

    /** 从 NovaTypeRegistry 查成员返回类型 */
    public String lookupMemberType(String baseTypeName, String memberName) {
        if (baseTypeName == null) return null;
        List<NovaTypeRegistry.MethodInfo> methods = NovaTypeRegistry.getMethodsForType(baseTypeName);
        if (methods != null) {
            for (NovaTypeRegistry.MethodInfo m : methods) {
                if (m.name.equals(memberName) && m.returnType != null) return m.returnType;
            }
        }
        List<NovaTypeRegistry.MethodInfo> anyMethods = NovaTypeRegistry.getMethodsForType("Any");
        if (anyMethods != null) {
            for (NovaTypeRegistry.MethodInfo m : anyMethods) {
                if (m.name.equals(memberName) && m.returnType != null) return m.returnType;
            }
        }
        return null;
    }

    /**
     * 集合工厂函数泛型推断：listOf(1,2,3) → List&lt;Int&gt;
     */
    public NovaType inferCollectionFactoryType(String funcName, List<CallExpr.Argument> args) {
        switch (funcName) {
            case "listOf":
            case "mutableListOf": {
                NovaType elem = inferCommonArgType(args);
                return NovaTypes.listOf(elem != null ? elem : NovaTypes.ANY);
            }
            case "arrayOf": {
                NovaType elem = inferCommonArgType(args);
                NovaType e = elem != null ? elem : NovaTypes.ANY;
                return new ClassNovaType("Array",
                        Collections.singletonList(NovaTypeArgument.invariant(e)), false);
            }
            case "setOf":
            case "mutableSetOf": {
                NovaType elem = inferCommonArgType(args);
                return NovaTypes.setOf(elem != null ? elem : NovaTypes.ANY);
            }
            case "mapOf":
            case "mutableMapOf":
                return inferMapFactoryType(args);
            case "Pair": {
                if (args.size() == 2) {
                    NovaType k = exprNovaTypeMap.get(args.get(0).getValue());
                    NovaType v = exprNovaTypeMap.get(args.get(1).getValue());
                    if (k != null && v != null) {
                        return new ClassNovaType("Pair",
                                java.util.Arrays.asList(NovaTypeArgument.invariant(k),
                                                         NovaTypeArgument.invariant(v)), false);
                    }
                }
                return null;
            }
            default:
                return null;
        }
    }

    /** 从参数列表中推断公共元素类型 */
    private NovaType inferCommonArgType(List<CallExpr.Argument> args) {
        if (args.isEmpty()) return null;
        NovaType result = null;
        for (CallExpr.Argument arg : args) {
            NovaType argType = exprNovaTypeMap.get(arg.getValue());
            if (argType == null) return null;
            result = (result == null) ? argType : typeUnifier.commonSuperType(result, argType);
        }
        return result;
    }

    /** mapOf("a" to 1, "b" to 2) → Map&lt;String, Int&gt; */
    private NovaType inferMapFactoryType(List<CallExpr.Argument> args) {
        if (args.isEmpty()) return NovaTypes.mapOf(NovaTypes.ANY, NovaTypes.ANY);
        NovaType keyType = null;
        NovaType valueType = null;
        for (CallExpr.Argument arg : args) {
            Expression expr = arg.getValue();
            if (expr instanceof BinaryExpr &&
                ((BinaryExpr) expr).getOperator() == BinaryExpr.BinaryOp.TO) {
                NovaType k = exprNovaTypeMap.get(((BinaryExpr) expr).getLeft());
                NovaType v = exprNovaTypeMap.get(((BinaryExpr) expr).getRight());
                if (k != null) keyType = (keyType == null) ? k : typeUnifier.commonSuperType(keyType, k);
                if (v != null) valueType = (valueType == null) ? v : typeUnifier.commonSuperType(valueType, v);
            } else {
                return NovaTypes.mapOf(NovaTypes.ANY, NovaTypes.ANY);
            }
        }
        return NovaTypes.mapOf(
                keyType != null ? keyType : NovaTypes.ANY,
                valueType != null ? valueType : NovaTypes.ANY);
    }
}
