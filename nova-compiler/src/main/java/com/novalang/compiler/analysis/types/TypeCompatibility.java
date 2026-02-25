package com.novalang.compiler.analysis.types;

import com.novalang.compiler.ast.type.TypeArgument;

import java.util.List;

/**
 * 类型兼容性判断：判断 source 是否可以赋值给 target。
 */
public final class TypeCompatibility {

    private TypeCompatibility() {}

    /**
     * 判断 source 类型是否可以赋值给 target 类型。
     */
    public static boolean isAssignable(NovaType target, NovaType source, SuperTypeRegistry registry) {
        if (target == null || source == null) return true;

        // ErrorType 与任何类型兼容
        if (target instanceof ErrorType || source instanceof ErrorType) return true;

        // Nothing 是所有类型的子类型
        if (source instanceof NothingType) {
            // Nothing? (null) 只能赋给可空目标
            if (source.isNullable()) {
                return target.isNullable();
            }
            return true;
        }

        // Any 接受所有类型（但非空 Any 不接受可空源）
        if ("Any".equals(target.getTypeName())) {
            if (!target.isNullable() && source.isNullable()) return false;
            return true;
        }

        // 空安全检查：非空目标不接受可空源
        if (!target.isNullable() && source.isNullable()) return false;

        // 同为原始类型
        if (target instanceof PrimitiveNovaType && source instanceof PrimitiveNovaType) {
            String targetName = target.getTypeName();
            String sourceName = source.getTypeName();
            if (targetName.equals(sourceName)) return true;
            // 数值拓宽: Int → Long → Float → Double
            return NovaTypes.isNumericWidening(targetName, sourceName);
        }

        // 原始类型与类类型之间：通过名称匹配（Int 既是 PrimitiveNovaType 也可以作为 ClassNovaType）
        if (target instanceof PrimitiveNovaType && source instanceof ClassNovaType) {
            String targetName = target.getTypeName();
            String sourceName = source.getTypeName();
            return targetName.equals(sourceName);
        }
        if (target instanceof ClassNovaType && source instanceof PrimitiveNovaType) {
            String targetName = target.getTypeName();
            String sourceName = source.getTypeName();
            if (targetName.equals(sourceName)) return true;
            // Number 接受数值类型
            if ("Number".equals(targetName) && NovaTypes.isNumericName(sourceName)) return true;
            if ("Any".equals(targetName)) return true;
            return false;
        }

        // 同为类类型
        if (target instanceof ClassNovaType && source instanceof ClassNovaType) {
            return isClassAssignable((ClassNovaType) target, (ClassNovaType) source, registry);
        }

        // TypeParameterType：检查 upper bound
        if (source instanceof TypeParameterType) {
            TypeParameterType tpSource = (TypeParameterType) source;
            return isAssignable(target, tpSource.getUpperBound(), registry);
        }
        if (target instanceof TypeParameterType) {
            // 赋值给类型参数，检查源满足 upper bound
            TypeParameterType tpTarget = (TypeParameterType) target;
            return isAssignable(tpTarget.getUpperBound(), source, registry);
        }

        // 函数类型
        if (target instanceof FunctionNovaType && source instanceof FunctionNovaType) {
            return isFunctionAssignable((FunctionNovaType) target, (FunctionNovaType) source, registry);
        }

        // UnitType
        if (target instanceof UnitType && source instanceof UnitType) return true;

        return false;
    }

    private static boolean isClassAssignable(ClassNovaType target, ClassNovaType source,
                                              SuperTypeRegistry registry) {
        String targetName = target.getName();
        String sourceName = source.getName();

        // 同名类型
        if (targetName.equals(sourceName)) {
            // 检查泛型参数
            if (!target.hasTypeArgs() || !source.hasTypeArgs()) return true;
            return areTypeArgsCompatible(target.getTypeArgs(), source.getTypeArgs(), registry);
        }

        // Number 接受所有数值类型
        if ("Number".equals(targetName) && NovaTypes.isNumericName(sourceName)) return true;

        // 查继承关系
        if (registry != null && registry.isSubtype(sourceName, targetName)) return true;

        return false;
    }

    private static boolean areTypeArgsCompatible(List<NovaTypeArgument> targetArgs,
                                                  List<NovaTypeArgument> sourceArgs,
                                                  SuperTypeRegistry registry) {
        if (targetArgs.size() != sourceArgs.size()) return true; // 参数数量不同，跳过

        for (int i = 0; i < targetArgs.size(); i++) {
            NovaTypeArgument targetArg = targetArgs.get(i);
            NovaTypeArgument sourceArg = sourceArgs.get(i);

            // 通配符总兼容
            if (targetArg.isWildcard() || sourceArg.isWildcard()) continue;

            NovaType targetType = targetArg.getType();
            NovaType sourceType = sourceArg.getType();
            if (targetType == null || sourceType == null) continue;

            // 根据 variance 确定兼容性方向
            TypeArgument.Variance effectiveVariance = targetArg.getVariance();
            if (effectiveVariance == TypeArgument.Variance.INVARIANT) {
                effectiveVariance = sourceArg.getVariance();
            }

            switch (effectiveVariance) {
                case OUT:
                    // 协变: source.type <: target.type
                    if (!isAssignable(targetType, sourceType, registry)) return false;
                    break;
                case IN:
                    // 逆变: target.type <: source.type
                    if (!isAssignable(sourceType, targetType, registry)) return false;
                    break;
                case INVARIANT:
                default:
                    // 不变: 双向兼容
                    if (!isAssignable(targetType, sourceType, registry) ||
                        !isAssignable(sourceType, targetType, registry)) return false;
                    break;
            }
        }
        return true;
    }

    private static boolean isFunctionAssignable(FunctionNovaType target, FunctionNovaType source,
                                                 SuperTypeRegistry registry) {
        // 参数逆变
        if (target.getParamTypes().size() != source.getParamTypes().size()) return false;
        for (int i = 0; i < target.getParamTypes().size(); i++) {
            // 参数类型逆变：target 的参数是 source 参数的子类型
            if (!isAssignable(source.getParamTypes().get(i), target.getParamTypes().get(i), registry)) {
                return false;
            }
        }
        // 返回值协变
        return isAssignable(target.getReturnType(), source.getReturnType(), registry);
    }

}
