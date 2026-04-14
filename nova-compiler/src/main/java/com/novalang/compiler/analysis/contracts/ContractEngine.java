package com.novalang.compiler.analysis.contracts;

import com.novalang.compiler.analysis.TypeUnifier;
import com.novalang.compiler.analysis.types.ClassNovaType;
import com.novalang.compiler.analysis.types.FunctionNovaType;
import com.novalang.compiler.analysis.types.NothingType;
import com.novalang.compiler.analysis.types.NovaType;
import com.novalang.compiler.analysis.types.NovaTypeArgument;
import com.novalang.compiler.analysis.types.NovaTypes;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.expr.IfExpr;
import com.novalang.compiler.ast.expr.Literal;
import com.novalang.compiler.ast.expr.LambdaExpr;
import com.novalang.compiler.ast.expr.MemberExpr;
import com.novalang.compiler.ast.stmt.Block;
import com.novalang.compiler.ast.stmt.ExpressionStmt;
import com.novalang.compiler.ast.stmt.IfStmt;
import com.novalang.compiler.ast.stmt.Statement;
import com.novalang.runtime.StdlibContractMetadata;
import com.novalang.runtime.contract.ConstraintExpr;
import com.novalang.runtime.contract.NovaContract;
import com.novalang.runtime.contract.TypeExpr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiler-side consumer for declarative stdlib contracts.
 */
public final class ContractEngine {
    private final TypeUnifier unifier;
    private final Map<Expression, NovaType> exprNovaTypeMap;

    public ContractEngine(TypeUnifier unifier, Map<Expression, NovaType> exprNovaTypeMap) {
        this.unifier = unifier;
        this.exprNovaTypeMap = exprNovaTypeMap;
    }

    public FunctionNovaType expectedMemberLambdaType(MemberExpr memberExpr, NovaType receiverType, LambdaExpr lambda) {
        return expectedMemberLambdaType(memberExpr, receiverType, java.util.Collections.<NovaType>emptyList(), 0, lambda);
    }

    public NovaType inferMemberAccessType(NovaType receiverType, String memberName) {
        if (receiverType == null || memberName == null) return null;
        NovaContract contract = StdlibContractMetadata.memberContract(baseType(receiverType.getTypeName()), memberName);
        if (contract == null) return null;
        boolean hasValueArgs = false;
        for (NovaContract.ArgumentSpec arg : contract.getArguments()) {
            if (arg instanceof NovaContract.ValueArgSpec || arg instanceof NovaContract.LambdaArgSpec) {
                hasValueArgs = true;
                break;
            }
        }
        if (hasValueArgs) return null;
        ContractInvocationContext context = new ContractInvocationContext(receiverType);
        bindReceiver(contract, receiverType, context.bindings);
        return evaluate(contract.getReturnType(), context);
    }

    public FunctionNovaType expectedMemberLambdaType(MemberExpr memberExpr, NovaType receiverType,
                                                     List<NovaType> argumentTypes, LambdaExpr lambda) {
        return expectedMemberLambdaType(memberExpr, receiverType, argumentTypes, 0, lambda);
    }

    public FunctionNovaType expectedMemberLambdaType(MemberExpr memberExpr, NovaType receiverType,
                                                     List<NovaType> argumentTypes, int lambdaOrdinal, LambdaExpr lambda) {
        NovaContract contract = lookupMemberContract(receiverType, memberExpr);
        if (contract == null) {
            return null;
        }
        NovaContract.LambdaArgSpec lambdaArg = lambdaArgAt(contract, lambdaOrdinal);
        if (lambdaArg == null) return null;

        ContractInvocationContext context = new ContractInvocationContext(receiverType);
        bindReceiver(contract, receiverType, context.bindings);
        bindValueArguments(contract, argumentTypes, context.bindings);

        NovaContract.LambdaVariantSpec variant = chooseVariant(lambdaArg, lambda);
        if (variant == null) return null;

        NovaType receiver = variant.getReceiverType() != null ? evaluate(variant.getReceiverType(), context) : null;
        List<NovaType> paramTypes = new ArrayList<NovaType>();
        for (TypeExpr paramType : variant.getParamTypes()) {
            paramTypes.add(evaluate(paramType, context));
        }
        NovaType returnType = expectedLambdaReturnType(variant.getReturnType(), context);
        return new FunctionNovaType(receiver, paramTypes, returnType, false);
    }

    public NovaType inferFunctionCallType(String functionName, List<NovaType> argumentTypes, NovaType expectedType) {
        return inferFunctionCallType(functionName, argumentTypes,
                java.util.Collections.<LambdaExpr>emptyList(),
                java.util.Collections.<NovaType>emptyList(),
                expectedType);
    }

    public FunctionNovaType expectedFunctionLambdaType(String functionName, List<NovaType> argumentTypes, LambdaExpr lambda) {
        return expectedFunctionLambdaType(functionName, argumentTypes, 0, lambda);
    }

    public FunctionNovaType expectedFunctionLambdaType(String functionName, List<NovaType> argumentTypes,
                                                       int lambdaOrdinal, LambdaExpr lambda) {
        NovaContract contract = lookupFunctionContract(functionName);
        if (contract == null) {
            return null;
        }
        NovaContract.LambdaArgSpec lambdaArg = lambdaArgAt(contract, lambdaOrdinal);
        if (lambdaArg == null) return null;

        ContractInvocationContext context = new ContractInvocationContext(null);
        bindValueArguments(contract, argumentTypes, context.bindings);

        NovaContract.LambdaVariantSpec variant = chooseVariant(lambdaArg, lambda);
        if (variant == null) return null;

        List<NovaType> paramTypes = new ArrayList<NovaType>();
        for (TypeExpr paramType : variant.getParamTypes()) {
            paramTypes.add(evaluate(paramType, context));
        }
        NovaType returnType = expectedLambdaReturnType(variant.getReturnType(), context);
        return new FunctionNovaType(
                variant.getReceiverType() != null ? evaluate(variant.getReceiverType(), context) : null,
                paramTypes,
                returnType,
                false);
    }

    public NovaType inferFunctionCallType(String functionName, List<NovaType> argumentTypes,
                                          LambdaExpr lambda, NovaType lambdaResult, NovaType expectedType) {
        return inferFunctionCallType(functionName, argumentTypes,
                lambda != null ? java.util.Collections.singletonList(lambda) : java.util.Collections.<LambdaExpr>emptyList(),
                lambdaResult != null ? java.util.Collections.singletonList(lambdaResult) : java.util.Collections.<NovaType>emptyList(),
                expectedType);
    }

    public NovaType inferFunctionCallType(String functionName, List<NovaType> argumentTypes,
                                          List<LambdaExpr> lambdas, List<NovaType> lambdaResults,
                                          NovaType expectedType) {
        NovaContract contract = lookupFunctionContract(functionName);
        if (contract == null) {
            return null;
        }
        List<NovaType> effectiveLambdaResults = projectLambdaResults(contract, lambdas, lambdaResults);
        ContractInvocationContext context = new ContractInvocationContext(null);
        bindValueArguments(contract, argumentTypes, context.bindings);
        bindLambdaReturns(contract, effectiveLambdaResults, context);
        bindExpectedReturnType(contract, expectedType, context.bindings);
        return evaluate(contract.getReturnType(), context);
    }

    public List<String> validateFunctionCall(String functionName, List<NovaType> argumentTypes, NovaType expectedType) {
        return validateFunctionCall(functionName, argumentTypes,
                java.util.Collections.<LambdaExpr>emptyList(),
                java.util.Collections.<NovaType>emptyList(),
                expectedType);
    }

    public List<String> validateFunctionCall(String functionName, List<NovaType> argumentTypes,
                                             LambdaExpr lambda, NovaType lambdaResult, NovaType expectedType) {
        return validateFunctionCall(functionName, argumentTypes,
                lambda != null ? java.util.Collections.singletonList(lambda) : java.util.Collections.<LambdaExpr>emptyList(),
                lambdaResult != null ? java.util.Collections.singletonList(lambdaResult) : java.util.Collections.<NovaType>emptyList(),
                expectedType);
    }

    public List<String> validateFunctionCall(String functionName, List<NovaType> argumentTypes,
                                             List<LambdaExpr> lambdas, List<NovaType> lambdaResults,
                                             NovaType expectedType) {
        NovaContract contract = lookupFunctionContract(functionName);
        if (contract == null) {
            return java.util.Collections.emptyList();
        }
        List<NovaType> effectiveLambdaResults = projectLambdaResults(contract, lambdas, lambdaResults);
        ContractValidationContext context = ContractValidationContext.forFunction(contract, functionName, null,
                firstLambdaResult(effectiveLambdaResults));
        return validateConstraints(context);
    }

    public List<String> validateMemberCall(MemberExpr memberExpr, NovaType receiverType,
                                           LambdaExpr lambda, NovaType lambdaResult) {
        return validateMemberCall(memberExpr, receiverType, java.util.Collections.<NovaType>emptyList(),
                lambda != null ? java.util.Collections.singletonList(lambda) : java.util.Collections.<LambdaExpr>emptyList(),
                lambdaResult != null ? java.util.Collections.singletonList(lambdaResult) : java.util.Collections.<NovaType>emptyList());
    }

    public List<String> validateMemberCall(MemberExpr memberExpr, NovaType receiverType,
                                           List<NovaType> argumentTypes, LambdaExpr lambda, NovaType lambdaResult) {
        return validateMemberCall(memberExpr, receiverType, argumentTypes,
                lambda != null ? java.util.Collections.singletonList(lambda) : java.util.Collections.<LambdaExpr>emptyList(),
                lambdaResult != null ? java.util.Collections.singletonList(lambdaResult) : java.util.Collections.<NovaType>emptyList());
    }

    public List<String> validateMemberCall(MemberExpr memberExpr, NovaType receiverType,
                                           List<NovaType> argumentTypes, List<LambdaExpr> lambdas, List<NovaType> lambdaResults) {
        NovaContract contract = lookupMemberContract(receiverType, memberExpr);
        if (contract == null) {
            return java.util.Collections.emptyList();
        }
        List<NovaType> effectiveLambdaResults = projectLambdaResults(contract, lambdas, lambdaResults);
        ContractInvocationContext invocationContext = new ContractInvocationContext(receiverType);
        bindReceiver(contract, receiverType, invocationContext.bindings);
        bindValueArguments(contract, argumentTypes, invocationContext.bindings);
        ContractValidationContext context = ContractValidationContext.forMember(
                contract, memberExpr.getMember(), receiverType, firstLambdaResult(effectiveLambdaResults));
        return validateConstraints(context);
    }

    public NovaType inferMemberCallType(MemberExpr memberExpr, NovaType receiverType,
                                        LambdaExpr lambda, NovaType lambdaResult) {
        return inferMemberCallType(memberExpr, receiverType, java.util.Collections.<NovaType>emptyList(),
                lambda != null ? java.util.Collections.singletonList(lambda) : java.util.Collections.<LambdaExpr>emptyList(),
                lambdaResult != null ? java.util.Collections.singletonList(lambdaResult) : java.util.Collections.<NovaType>emptyList());
    }

    public NovaType inferMemberCallType(MemberExpr memberExpr, NovaType receiverType,
                                        List<NovaType> argumentTypes, LambdaExpr lambda, NovaType lambdaResult) {
        return inferMemberCallType(memberExpr, receiverType, argumentTypes,
                lambda != null ? java.util.Collections.singletonList(lambda) : java.util.Collections.<LambdaExpr>emptyList(),
                lambdaResult != null ? java.util.Collections.singletonList(lambdaResult) : java.util.Collections.<NovaType>emptyList());
    }

    public NovaType inferMemberCallType(MemberExpr memberExpr, NovaType receiverType,
                                        List<NovaType> argumentTypes, List<LambdaExpr> lambdas, List<NovaType> lambdaResults) {
        NovaContract contract = lookupMemberContract(receiverType, memberExpr);
        if (contract == null) {
            return null;
        }
        List<NovaType> effectiveLambdaResults = projectLambdaResults(contract, lambdas, lambdaResults);
        ContractInvocationContext context = new ContractInvocationContext(receiverType);
        bindReceiver(contract, receiverType, context.bindings);
        bindValueArguments(contract, argumentTypes, context.bindings);
        bindLambdaReturns(contract, effectiveLambdaResults, context);
        return evaluate(contract.getReturnType(), context);
    }

    private NovaContract lookupMemberContract(NovaType receiverType, MemberExpr memberExpr) {
        if (receiverType == null || memberExpr == null) return null;
        String baseType = baseType(receiverType.getTypeName());
        NovaContract contract = StdlibContractMetadata.memberContract(baseType, memberExpr.getMember());
        if (contract == null) {
            contract = StdlibContractMetadata.memberContract("Any", memberExpr.getMember());
        }
        return contract;
    }

    private NovaContract lookupFunctionContract(String functionName) {
        return functionName != null ? StdlibContractMetadata.functionContract(functionName) : null;
    }

    private void bindReceiver(NovaContract contract, NovaType receiverType, Map<String, NovaType> bindings) {
        if (contract.getReceiver() == null || receiverType == null) return;
        bind(contract.getReceiver().getType(), receiverType.withNullable(false), bindings);
    }

    private void bindValueArguments(NovaContract contract, List<NovaType> argumentTypes, Map<String, NovaType> bindings) {
        if (contract == null || argumentTypes == null) return;
        int argIndex = 0;
        for (NovaContract.ArgumentSpec arg : contract.getArguments()) {
            if (!(arg instanceof NovaContract.ValueArgSpec)) continue;
            if (argIndex >= argumentTypes.size()) break;
            bind(((NovaContract.ValueArgSpec) arg).getType(), argumentTypes.get(argIndex), bindings);
            argIndex++;
        }
    }

    private void bindExpectedReturnType(NovaContract contract, NovaType expectedType, Map<String, NovaType> bindings) {
        if (contract == null || expectedType == null) return;
        bind(contract.getReturnType(), expectedType, bindings);
    }

    private void bindLambdaReturns(NovaContract contract, List<NovaType> lambdaResults, ContractInvocationContext context) {
        if (contract == null || lambdaResults == null) return;
        for (int ordinal = 0; ordinal < lambdaResults.size(); ordinal++) {
            NovaType lambdaResult = lambdaResults.get(ordinal);
            if (lambdaResult == null) continue;
            context.lambdaResults.put(Integer.valueOf(ordinal), lambdaResult);
            NovaContract.LambdaArgSpec lambdaArg = lambdaArgAt(contract, ordinal);
            if (lambdaArg == null || lambdaArg.getVariants().isEmpty()) continue;
            for (NovaContract.LambdaVariantSpec variant : lambdaArg.getVariants()) {
                bind(variant.getReturnType(), lambdaResult, context.bindings);
            }
        }
    }

    private NovaContract.LambdaArgSpec firstLambdaArg(NovaContract contract) {
        return lambdaArgAt(contract, 0);
    }

    private NovaContract.LambdaArgSpec lambdaArgAt(NovaContract contract, int ordinal) {
        int current = 0;
        for (NovaContract.ArgumentSpec arg : contract.getArguments()) {
            if (arg instanceof NovaContract.LambdaArgSpec) {
                if (current == ordinal) {
                    return (NovaContract.LambdaArgSpec) arg;
                }
                current++;
            }
        }
        return null;
    }

    private NovaContract.LambdaVariantSpec chooseVariant(NovaContract.LambdaArgSpec lambdaArg, LambdaExpr lambda) {
        int explicitParams = lambda != null && lambda.hasExplicitParams() ? lambda.getParams().size() : -1;
        for (NovaContract.LambdaVariantSpec variant : lambdaArg.getVariants()) {
            switch (variant.getShape()) {
                case RECEIVER_BLOCK:
                    if (explicitParams < 0) return variant;
                    break;
                case ZERO_ARG_BLOCK:
                    if (explicitParams < 0) return variant;
                    break;
                case IMPLICIT_IT:
                    if (explicitParams < 0) return variant;
                    break;
                case EXPLICIT_PARAMS:
                    if (explicitParams == variant.getParamTypes().size()) return variant;
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    private void bind(TypeExpr expected, NovaType actual, Map<String, NovaType> bindings) {
        if (expected == null || actual == null) return;
        if (expected instanceof TypeExpr.TypeVarExpr) {
            mergeBinding(((TypeExpr.TypeVarExpr) expected).getName(), actual, bindings);
            return;
        }
        if (expected instanceof TypeExpr.ConcreteTypeExpr && actual instanceof ClassNovaType) {
            TypeExpr.ConcreteTypeExpr concrete = (TypeExpr.ConcreteTypeExpr) expected;
            ClassNovaType actualClass = (ClassNovaType) actual;
            if (!concrete.getName().equals(baseType(actualClass.getTypeName()))) return;
            List<TypeExpr> expectedArgs = concrete.getTypeArguments();
            List<NovaTypeArgument> actualArgs = actualClass.getTypeArgs();
            int count = Math.min(expectedArgs.size(), actualArgs.size());
            for (int i = 0; i < count; i++) {
                bind(expectedArgs.get(i), actualArgs.get(i).getType(), bindings);
            }
            return;
        }
        if (expected instanceof TypeExpr.NullableExpr) {
            bind(((TypeExpr.NullableExpr) expected).getTarget(), actual.withNullable(false), bindings);
            return;
        }
        if (expected instanceof TypeExpr.NonNullExpr) {
            bind(((TypeExpr.NonNullExpr) expected).getTarget(), actual.withNullable(false), bindings);
            return;
        }
    }

    private void mergeBinding(String name, NovaType actual, Map<String, NovaType> bindings) {
        NovaType existing = bindings.get(name);
        if (existing == null) {
            bindings.put(name, actual);
        } else {
            bindings.put(name, unifier.commonSuperType(existing, actual));
        }
    }

    private List<NovaType> projectLambdaResults(NovaContract contract, List<LambdaExpr> lambdas, List<NovaType> lambdaResults) {
        int count = Math.max(lambdas != null ? lambdas.size() : 0, lambdaResults != null ? lambdaResults.size() : 0);
        List<NovaType> projected = new ArrayList<NovaType>(count);
        for (int i = 0; i < count; i++) {
            LambdaExpr lambda = lambdas != null && i < lambdas.size() ? lambdas.get(i) : null;
            NovaType lambdaResult = lambdaResults != null && i < lambdaResults.size() ? lambdaResults.get(i) : null;
            if (i == 0 && contract != null && contract.hasTag(NovaContract.Tag.COLLECTION_MAP_NOT_NULL)) {
                projected.add(inferMapNotNullElementType(lambda));
            } else {
                projected.add(lambdaResult);
            }
        }
        return projected;
    }

    private NovaType firstLambdaResult(List<NovaType> lambdaResults) {
        return lambdaResults != null && !lambdaResults.isEmpty() ? lambdaResults.get(0) : null;
    }

    private NovaType inferMapNotNullElementType(LambdaExpr lambda) {
        if (lambda == null || lambda.getBody() == null) return NovaTypes.ANY;
        if (lambda.getBody() instanceof Expression) {
            return inferMapNotNullElementType((Expression) lambda.getBody());
        }
        if (lambda.getBody() instanceof Statement) {
            return inferMapNotNullElementType((Statement) lambda.getBody());
        }
        return NovaTypes.ANY;
    }

    private NovaType inferMapNotNullElementType(Expression expression) {
        if (expression == null) return NovaTypes.ANY;
        if (expression instanceof IfExpr) {
            IfExpr ifExpr = (IfExpr) expression;
            NovaType thenType = exprType(ifExpr.getThenExpr());
            NovaType elseType = exprType(ifExpr.getElseExpr());
            if (thenType != null && !isNullOnlyType(thenType) && isNullOnlyType(elseType)) {
                return thenType.withNullable(false);
            }
            if (elseType != null && !isNullOnlyType(elseType) && isNullOnlyType(thenType)) {
                return elseType.withNullable(false);
            }
        }
        NovaType type = exprType(expression);
        if (type == null) return NovaTypes.ANY;
        return isNullOnlyType(type) ? type : type.withNullable(false);
    }

    private NovaType inferMapNotNullElementType(Statement statement) {
        if (statement == null) return NovaTypes.ANY;
        if (statement instanceof ExpressionStmt) {
            return inferMapNotNullElementType(((ExpressionStmt) statement).getExpression());
        }
        if (statement instanceof Block) {
            Block block = (Block) statement;
            if (!block.getStatements().isEmpty()) {
                return inferMapNotNullElementType(block.getStatements().get(block.getStatements().size() - 1));
            }
        }
        if (statement instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) statement;
            NovaType thenType = inferMapNotNullElementType(ifStmt.getThenBranch());
            NovaType elseType = inferMapNotNullElementType(ifStmt.getElseBranch());
            if (thenType != null && !isNullOnlyType(thenType) && isNullOnlyType(elseType)) {
                return thenType.withNullable(false);
            }
            if (elseType != null && !isNullOnlyType(elseType) && isNullOnlyType(thenType)) {
                return elseType.withNullable(false);
            }
        }
        return NovaTypes.ANY;
    }

    private NovaType exprType(Expression expression) {
        return expression != null ? exprNovaTypeMap.get(expression) : null;
    }

    private boolean isNullOnlyType(NovaType type) {
        return type instanceof NothingType && type.isNullable();
    }

    private NovaType expectedLambdaReturnType(TypeExpr expr, ContractInvocationContext context) {
        if (!hasUnboundType(expr, context)) {
            return evaluate(expr, context);
        }
        if (expr instanceof TypeExpr.NullableExpr) {
            return expectedLambdaReturnType(((TypeExpr.NullableExpr) expr).getTarget(), context).withNullable(true);
        }
        if (expr instanceof TypeExpr.NonNullExpr) {
            return expectedLambdaReturnType(((TypeExpr.NonNullExpr) expr).getTarget(), context).withNullable(false);
        }
        return NovaTypes.ANY.withNullable(true);
    }

    private boolean isBaseType(NovaType type, String expectedBaseType) {
        return expectedBaseType != null && expectedBaseType.equals(baseType(type != null ? type.getTypeName() : null));
    }

    private boolean isCollectionLike(NovaType type) {
        String baseName = baseType(type != null ? type.getTypeName() : null);
        return "List".equals(baseName)
                || "Set".equals(baseName)
                || "Map".equals(baseName)
                || "Array".equals(baseName);
    }

    private List<String> validateConstraints(ContractValidationContext context) {
        List<String> messages = new ArrayList<String>();
        for (ConstraintExpr constraint : context.contract.getConstraints()) {
            if (constraint instanceof ConstraintExpr.ReceiverBaseTypeConstraint) {
                String baseType = ((ConstraintExpr.ReceiverBaseTypeConstraint) constraint).getBaseType();
                if (!isBaseType(context.receiverType, baseType)) {
                    messages.add("Contract violation: " + context.callDisplay
                            + " requires a " + baseType + " receiver");
                }
            } else if (constraint instanceof ConstraintExpr.LambdaReturnsBaseTypeConstraint
                    && context.lambdaResult != null
                    && !isBaseType(context.lambdaResult,
                    ((ConstraintExpr.LambdaReturnsBaseTypeConstraint) constraint).getBaseType())) {
                String baseType = ((ConstraintExpr.LambdaReturnsBaseTypeConstraint) constraint).getBaseType();
                messages.add("Contract violation: " + context.callDisplay
                        + " lambda must return " + baseType + "<...>");
            } else if (constraint instanceof ConstraintExpr.LambdaReturnsCollectionConstraint
                    && context.lambdaResult != null
                    && !isCollectionLike(context.lambdaResult)) {
                messages.add("Contract violation: " + context.callDisplay + " lambda must return a collection value");
            }
        }
        return messages;
    }

    private boolean hasUnboundType(TypeExpr expr, ContractInvocationContext context) {
        if (expr == null) return false;
        if (expr instanceof TypeExpr.TypeVarExpr) {
            return !context.bindings.containsKey(((TypeExpr.TypeVarExpr) expr).getName());
        }
        if (expr instanceof TypeExpr.LambdaResultExpr) {
            return !context.lambdaResults.containsKey(Integer.valueOf(((TypeExpr.LambdaResultExpr) expr).getArgumentIndex()));
        }
        if (expr instanceof TypeExpr.ConcreteTypeExpr) {
            for (TypeExpr typeArgument : ((TypeExpr.ConcreteTypeExpr) expr).getTypeArguments()) {
                if (hasUnboundType(typeArgument, context)) {
                    return true;
                }
            }
            return false;
        }
        if (expr instanceof TypeExpr.NullableExpr) {
            return hasUnboundType(((TypeExpr.NullableExpr) expr).getTarget(), context);
        }
        if (expr instanceof TypeExpr.NonNullExpr) {
            return hasUnboundType(((TypeExpr.NonNullExpr) expr).getTarget(), context);
        }
        if (expr instanceof TypeExpr.PayloadTypeExpr) {
            return hasUnboundType(((TypeExpr.PayloadTypeExpr) expr).getTarget(), context);
        }
        if (expr instanceof TypeExpr.CommonSuperExpr) {
            TypeExpr.CommonSuperExpr common = (TypeExpr.CommonSuperExpr) expr;
            return hasUnboundType(common.getLeft(), context) || hasUnboundType(common.getRight(), context);
        }
        return false;
    }

    private NovaType evaluate(TypeExpr expr, ContractInvocationContext context) {
        if (expr == null) return NovaTypes.ANY;
        if (expr instanceof TypeExpr.TypeVarExpr) {
            NovaType bound = context.bindings.get(((TypeExpr.TypeVarExpr) expr).getName());
            return bound != null ? bound : NovaTypes.ANY;
        }
        if (expr instanceof TypeExpr.ReceiverTypeExpr) {
            return context.receiverType != null ? context.receiverType : NovaTypes.ANY;
        }
        if (expr instanceof TypeExpr.LambdaResultExpr) {
            NovaType lambdaResult = context.lambdaResults.get(Integer.valueOf(((TypeExpr.LambdaResultExpr) expr).getArgumentIndex()));
            return lambdaResult != null ? lambdaResult : NovaTypes.ANY;
        }
        if (expr instanceof TypeExpr.NullableExpr) {
            return evaluate(((TypeExpr.NullableExpr) expr).getTarget(), context).withNullable(true);
        }
        if (expr instanceof TypeExpr.NonNullExpr) {
            return evaluate(((TypeExpr.NonNullExpr) expr).getTarget(), context).withNullable(false);
        }
        if (expr instanceof TypeExpr.PayloadTypeExpr) {
            NovaType target = evaluate(((TypeExpr.PayloadTypeExpr) expr).getTarget(), context);
            if (target instanceof ClassNovaType) {
                ClassNovaType classType = (ClassNovaType) target;
                if (classType.hasTypeArgs() && !classType.getTypeArgs().isEmpty()) {
                    NovaType payload = classType.getTypeArgs().get(0).getType();
                    return payload != null ? payload : NovaTypes.ANY;
                }
            }
            return NovaTypes.ANY;
        }
        if (expr instanceof TypeExpr.CommonSuperExpr) {
            TypeExpr.CommonSuperExpr common = (TypeExpr.CommonSuperExpr) expr;
            return unifier.commonSuperType(evaluate(common.getLeft(), context), evaluate(common.getRight(), context));
        }
        if (expr instanceof TypeExpr.ConcreteTypeExpr) {
            TypeExpr.ConcreteTypeExpr concrete = (TypeExpr.ConcreteTypeExpr) expr;
            List<NovaTypeArgument> args = new ArrayList<NovaTypeArgument>();
            for (TypeExpr argExpr : concrete.getTypeArguments()) {
                args.add(NovaTypeArgument.invariant(evaluate(argExpr, context)));
            }
            if (args.isEmpty()) {
                NovaType builtin = NovaTypes.fromName(concrete.getName());
                return builtin != null ? builtin : new ClassNovaType(concrete.getName(), false);
            }
            return new ClassNovaType(concrete.getName(), args, false);
        }
        return NovaTypes.ANY;
    }

    private String baseType(String typeName) {
        if (typeName == null) return null;
        int idx = typeName.indexOf('<');
        String base = idx > 0 ? typeName.substring(0, idx) : typeName;
        return base.replace("?", "");
    }

    private static final class ContractInvocationContext {
        private final NovaType receiverType;
        private final Map<String, NovaType> bindings = new HashMap<String, NovaType>();
        private final Map<Integer, NovaType> lambdaResults = new HashMap<Integer, NovaType>();

        private ContractInvocationContext(NovaType receiverType) {
            this.receiverType = receiverType != null ? receiverType.withNullable(false) : null;
        }
    }

    private static final class ContractValidationContext {
        private final NovaContract contract;
        private final String callDisplay;
        private final NovaType receiverType;
        private final NovaType lambdaResult;

        private ContractValidationContext(NovaContract contract, String callDisplay,
                                          NovaType receiverType, NovaType lambdaResult) {
            this.contract = contract;
            this.callDisplay = callDisplay;
            this.receiverType = receiverType;
            this.lambdaResult = lambdaResult;
        }

        private static ContractValidationContext forMember(NovaContract contract, String memberName,
                                                           NovaType receiverType, NovaType lambdaResult) {
            return new ContractValidationContext(contract, memberName, receiverType, lambdaResult);
        }

        private static ContractValidationContext forFunction(NovaContract contract, String functionName,
                                                             NovaType receiverType, NovaType lambdaResult) {
            return new ContractValidationContext(contract, functionName, receiverType, lambdaResult);
        }
    }
}
