package com.novalang.compiler.analysis.types;

import com.novalang.compiler.ast.type.TypeArgument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Type-compatibility checks used by semantic analysis.
 */
public final class TypeCompatibility {

    private static final Map<String, List<NovaTypeParam>> DECLARATION_SITE_PARAMS =
            new ConcurrentHashMap<String, List<NovaTypeParam>>();

    private TypeCompatibility() {}

    public static void registerDeclarationSiteTypeParams(String typeName, List<NovaTypeParam> params) {
        if (typeName == null || typeName.isEmpty()) return;
        List<NovaTypeParam> snapshot = params != null
                ? Collections.unmodifiableList(new ArrayList<NovaTypeParam>(params))
                : Collections.<NovaTypeParam>emptyList();
        DECLARATION_SITE_PARAMS.put(typeName, snapshot);
    }

    public static boolean isAssignable(NovaType target, NovaType source, SuperTypeRegistry registry) {
        if (target == null || source == null) return true;

        if (target instanceof ErrorType || source instanceof ErrorType) return true;
        if (NovaTypes.isDynamicType(target) || NovaTypes.isDynamicType(source)) return true;

        if (source instanceof NothingType) {
            if (source.isNullable()) {
                return target.isNullable();
            }
            return true;
        }

        if ("Any".equals(target.getTypeName())) {
            if (!target.isNullable() && source.isNullable()) return false;
            return true;
        }
        if ("String".equals(target.getTypeName()) && "Exception".equals(source.getTypeName())) {
            return true;
        }

        if (!target.isNullable() && source.isNullable()) return false;

        if (target instanceof PrimitiveNovaType && source instanceof PrimitiveNovaType) {
            String targetName = target.getTypeName();
            String sourceName = source.getTypeName();
            if (targetName.equals(sourceName)) return true;
            return NovaTypes.isNumericWidening(targetName, sourceName);
        }

        if (target instanceof PrimitiveNovaType && source instanceof ClassNovaType) {
            return target.getTypeName().equals(source.getTypeName());
        }
        if (target instanceof ClassNovaType && source instanceof PrimitiveNovaType) {
            String targetName = target.getTypeName();
            String sourceName = source.getTypeName();
            if (targetName.equals(sourceName)) return true;
            if ("Number".equals(targetName) && NovaTypes.isNumericName(sourceName)) return true;
            if ("Any".equals(targetName)) return true;
            return false;
        }

        if (target instanceof ClassNovaType && source instanceof ClassNovaType) {
            if (target instanceof JavaClassNovaType || source instanceof JavaClassNovaType) {
                return isJavaClassAssignable((ClassNovaType) target, (ClassNovaType) source, registry);
            }
            return isClassAssignable((ClassNovaType) target, (ClassNovaType) source, registry);
        }
        if (target instanceof ClassNovaType && source instanceof FunctionNovaType) {
            if (target instanceof JavaClassNovaType) {
                FunctionNovaType samType = ((JavaClassNovaType) target).getDescriptor().toSamFunctionType(target.isNullable());
                return samType != null && isFunctionAssignable(samType, (FunctionNovaType) source, registry);
            }
            return isSamAssignable((ClassNovaType) target, (FunctionNovaType) source, registry);
        }

        if (source instanceof TypeParameterType) {
            return isAssignable(target, ((TypeParameterType) source).getUpperBound(), registry);
        }
        if (target instanceof TypeParameterType) {
            return isAssignable(((TypeParameterType) target).getUpperBound(), source, registry);
        }

        if (target instanceof FunctionNovaType && source instanceof FunctionNovaType) {
            return isFunctionAssignable((FunctionNovaType) target, (FunctionNovaType) source, registry);
        }

        return target instanceof UnitType && source instanceof UnitType;
    }

    private static boolean isClassAssignable(ClassNovaType target, ClassNovaType source,
                                             SuperTypeRegistry registry) {
        String targetName = target.getName();
        String sourceName = source.getName();

        if (targetName.equals(sourceName)) {
            if (!target.hasTypeArgs() || !source.hasTypeArgs()) return true;
            return areTypeArgsCompatible(targetName, target.getTypeArgs(), source.getTypeArgs(), registry);
        }

        if ("Number".equals(targetName) && NovaTypes.isNumericName(sourceName)) return true;
        return registry != null && registry.isSubtype(sourceName, targetName);
    }

    private static boolean isJavaClassAssignable(ClassNovaType target, ClassNovaType source,
                                                 SuperTypeRegistry registry) {
        if (target instanceof JavaClassNovaType && source instanceof JavaClassNovaType) {
            JavaTypeDescriptor targetDescriptor = ((JavaClassNovaType) target).getDescriptor();
            JavaTypeDescriptor sourceDescriptor = ((JavaClassNovaType) source).getDescriptor();
            if (targetDescriptor == null || sourceDescriptor == null) return false;
            if (targetDescriptor.getQualifiedName().equals(sourceDescriptor.getQualifiedName())) {
                if (!target.hasTypeArgs() || !source.hasTypeArgs()) return true;
                return areTypeArgsCompatible(targetDescriptor.getQualifiedName(),
                        target.getTypeArgs(), source.getTypeArgs(), registry);
            }
            return targetDescriptor.isAssignableFrom(sourceDescriptor);
        }
        if (target instanceof JavaClassNovaType) {
            return target.getName().equals(source.getName());
        }
        if (source instanceof JavaClassNovaType) {
            return target.getName().equals(source.getName())
                    || (registry != null && registry.isSubtype(source.getName(), target.getName()));
        }
        return false;
    }

    private static boolean areTypeArgsCompatible(String typeName,
                                                 List<NovaTypeArgument> targetArgs,
                                                 List<NovaTypeArgument> sourceArgs,
                                                 SuperTypeRegistry registry) {
        if (targetArgs.size() != sourceArgs.size()) return true;

        List<NovaTypeParam> declaredParams = DECLARATION_SITE_PARAMS.get(typeName);
        for (int i = 0; i < targetArgs.size(); i++) {
            NovaTypeArgument targetArg = targetArgs.get(i);
            NovaTypeArgument sourceArg = sourceArgs.get(i);

            if (targetArg.isWildcard() || sourceArg.isWildcard()) continue;

            NovaType targetType = targetArg.getType();
            NovaType sourceType = sourceArg.getType();
            if (targetType == null || sourceType == null) continue;

            TypeArgument.Variance effectiveVariance = targetArg.getVariance();
            if (effectiveVariance == TypeArgument.Variance.INVARIANT) {
                effectiveVariance = sourceArg.getVariance();
            }
            if (effectiveVariance == TypeArgument.Variance.INVARIANT
                    && declaredParams != null
                    && i < declaredParams.size()
                    && declaredParams.get(i) != null) {
                effectiveVariance = declaredParams.get(i).getVariance();
            }

            switch (effectiveVariance) {
                case OUT:
                    if (!isAssignable(targetType, sourceType, registry)) return false;
                    break;
                case IN:
                    if (!isAssignable(sourceType, targetType, registry)) return false;
                    break;
                case INVARIANT:
                default:
                    if (!isAssignable(targetType, sourceType, registry)
                            || !isAssignable(sourceType, targetType, registry)) return false;
                    break;
            }
        }
        return true;
    }

    private static boolean isSamAssignable(ClassNovaType target, FunctionNovaType source,
                                           SuperTypeRegistry registry) {
        String targetName = target.getName();
        FunctionNovaType samType = null;
        if ("Runnable".equals(targetName)) {
            samType = new FunctionNovaType(null, Collections.<NovaType>emptyList(), NovaTypes.UNIT, false);
        } else if ("Supplier".equals(targetName) || "Callable".equals(targetName)) {
            samType = new FunctionNovaType(null, Collections.<NovaType>emptyList(), NovaTypes.ANY, false);
        } else if ("Consumer".equals(targetName)) {
            samType = new FunctionNovaType(null, Collections.singletonList(NovaTypes.ANY), NovaTypes.UNIT, false);
        } else if ("Function".equals(targetName)) {
            samType = new FunctionNovaType(null, Collections.singletonList(NovaTypes.ANY), NovaTypes.ANY, false);
        } else if ("Predicate".equals(targetName)) {
            samType = new FunctionNovaType(null, Collections.singletonList(NovaTypes.ANY), NovaTypes.BOOLEAN, false);
        } else if ("Comparator".equals(targetName)) {
            samType = new FunctionNovaType(null, java.util.Arrays.asList(NovaTypes.ANY, NovaTypes.ANY), NovaTypes.INT, false);
        }
        return samType != null && isFunctionAssignable(samType, source, registry);
    }

    private static boolean isFunctionAssignable(FunctionNovaType target, FunctionNovaType source,
                                                SuperTypeRegistry registry) {
        if (target.getParamTypes().size() != source.getParamTypes().size()) return false;
        for (int i = 0; i < target.getParamTypes().size(); i++) {
            if (!isAssignable(source.getParamTypes().get(i), target.getParamTypes().get(i), registry)) {
                return false;
            }
        }
        return isAssignable(target.getReturnType(), source.getReturnType(), registry);
    }
}
