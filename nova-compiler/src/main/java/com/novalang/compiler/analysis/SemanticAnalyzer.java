package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.contracts.ContractEngine;
import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;
import com.novalang.runtime.StdlibContractMetadata;
import com.novalang.runtime.NovaTypeRegistry;
import com.novalang.runtime.contract.NovaContract;
import com.novalang.runtime.stdlib.BuiltinModuleExports;
import com.novalang.runtime.stdlib.StdlibRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 语义分析器：遍历 AST 构建符号表并收集诊断。
 * 同时并行填充结构化 NovaType 信息用于类型兼容性检查。
 *
 * <p>类型推断委托给 {@link TypeInferenceEngine}，
 * 泛型统一委托给 {@link TypeUnifier}，
 * 语义检查委托给 {@link SemanticChecker}。</p>
 */
public final class SemanticAnalyzer implements AstVisitor<Void, Void> {

    private final SymbolTable symbolTable;
    private Scope currentScope;
    private final List<SemanticDiagnostic> diagnostics = new ArrayList<SemanticDiagnostic>();

    // 结构化类型系统
    private final TypeResolver typeResolver = new TypeResolver();
    private final SuperTypeRegistry superTypeRegistry = new SuperTypeRegistry();
    private final Map<Expression, NovaType> exprNovaTypeMap;
    private final Map<LambdaExpr, FunctionNovaType> contextualLambdaTypes;
    private final Map<Expression, NovaType> contextualExpectedTypes;
    private final Map<ObjectLiteralExpr, Symbol> anonymousObjectSymbols;
    private final Map<Declaration, Symbol> declarationSymbols;
    private final Set<String> externalKnownTypeNames;

    // 委托
    private final TypeUnifier unifier;
    private final TypeInferenceEngine inference;
    private final SemanticChecker checker;
    private final ContractEngine contractEngine;

    /** 诊断专用模式：跳过 exprNovaTypeMap / nodeToScope / scopeRanges 记录，节省内存 */
    private boolean diagnosticsOnly = false;
    private int loopDepth = 0;
    private int lambdaDepth = 0;

    public SemanticAnalyzer() {
        this.symbolTable = new SymbolTable();
        this.currentScope = symbolTable.getGlobalScope();
        this.exprNovaTypeMap = new HashMap<Expression, NovaType>();
        this.contextualLambdaTypes = new java.util.IdentityHashMap<LambdaExpr, FunctionNovaType>();
        this.contextualExpectedTypes = new java.util.IdentityHashMap<Expression, NovaType>();
        this.anonymousObjectSymbols = new java.util.IdentityHashMap<ObjectLiteralExpr, Symbol>();
        this.declarationSymbols = new java.util.IdentityHashMap<Declaration, Symbol>();
        this.externalKnownTypeNames = new java.util.LinkedHashSet<String>();
        this.unifier = new TypeUnifier(exprNovaTypeMap, superTypeRegistry, typeResolver);
        this.inference = new TypeInferenceEngine(exprNovaTypeMap, unifier);
        this.checker = new SemanticChecker(diagnostics, superTypeRegistry, exprNovaTypeMap);
        this.contractEngine = new ContractEngine(unifier, exprNovaTypeMap);
        registerBuiltins();
    }

    /**
     * 设置诊断专用模式。启用后跳过 exprNovaTypeMap / 位置索引记录，
     * 适用于编译管线只需要诊断输出的场景。
     */
    public void setDiagnosticsOnly(boolean diagnosticsOnly) {
        this.diagnosticsOnly = diagnosticsOnly;
        symbolTable.setRecordPositionInfo(!diagnosticsOnly);
    }

    public void registerKnownType(String typeName) {
        if (typeName == null || typeName.isEmpty()) return;
        externalKnownTypeNames.add(typeName);
        typeResolver.registerKnownType(typeName);
    }

    /** 分析入口 */
    public AnalysisResult analyze(Program program) {
        predeclareTopLevelTypes(program);
        program.accept(this, null);
        return new AnalysisResult(symbolTable, diagnostics, exprNovaTypeMap);
    }

    /**
     * 分析 Program 及顶层语句（容错解析时，非声明语句存在于 ParseResult.topLevelStatements）
     */
    public AnalysisResult analyze(Program program, List<Statement> topLevelStatements) {
        predeclareTopLevelTypes(program);
        program.accept(this, null);
        if (topLevelStatements != null) {
            for (Statement stmt : topLevelStatements) {
                stmt.accept(this, null);
            }
        }
        return new AnalysisResult(symbolTable, diagnostics, exprNovaTypeMap);
    }

    /** 获取 TypeResolver（供外部如 VarianceChecker 使用）*/
    public TypeResolver getTypeResolver() {
        return typeResolver;
    }

    /** 获取 SuperTypeRegistry */
    public SuperTypeRegistry getSuperTypeRegistry() {
        return superTypeRegistry;
    }

    // ============ NovaType 辅助方法 ============

    private void setNovaType(Expression expr, NovaType type) {
        // exprNovaTypeMap 始终写入：SemanticChecker 的类型兼容/实参检查依赖此映射
        // diagnosticsOnly 只跳过 nodeToScope/scopeRanges（由 SymbolTable.recordPositionInfo 控制）
        if (expr != null && type != null) {
            exprNovaTypeMap.put(expr, type);
        }
    }

    private NovaType getNovaType(Expression expr) {
        return expr != null ? exprNovaTypeMap.get(expr) : null;
    }

    // ============ 内置符号注册 ============

    private void registerBuiltins() {
        for (NovaTypeRegistry.FunctionInfo f : NovaTypeRegistry.getBuiltinFunctions()) {
            Symbol sym = new Symbol(f.name, SymbolKind.BUILTIN_FUNCTION,
                    f.returnType, false, null, null, Modifier.PUBLIC);
            sym.setResolvedNovaType(inference.resolveNovaTypeFromName(f.returnType));
            currentScope.define(sym);
        }
        for (NovaTypeRegistry.ConstantInfo c : NovaTypeRegistry.getBuiltinConstants()) {
            Symbol sym = new Symbol(c.name, SymbolKind.BUILTIN_CONSTANT,
                    c.type, false, null, null, Modifier.PUBLIC);
            sym.setResolvedNovaType(inference.resolveNovaTypeFromName(c.type));
            currentScope.define(sym);
        }
    }

    // ============ 作用域管理 ============

    private Scope enterScope(Scope.ScopeType type, AstNode node) {
        Scope newScope = new Scope(type, currentScope, node);
        currentScope.addChild(newScope);
        symbolTable.mapNodeToScope(node, newScope);
        currentScope = newScope;
        return newScope;
    }

    private void exitScope(AstNode node) {
        if (node != null && node.getLocation() != null) {
            SourceLocation end = estimateEndLocation(node);
            symbolTable.registerScopeRange(currentScope, node.getLocation(), end);
        }
        currentScope = currentScope.getParent();
    }

    /** 估算作用域结束位置的行数偏移（精确的结束位置需要 AST 提供 end-location） */
    private static final int SCOPE_END_LINE_ESTIMATE = 100;
    /** 估算作用域结束位置的字节偏移 */
    private static final int SCOPE_END_OFFSET_ESTIMATE = 1000;

    private SourceLocation estimateEndLocation(AstNode node) {
        SourceLocation loc = node.getLocation();
        if (loc == null) return SourceLocation.UNKNOWN;
        return new SourceLocation(loc.getFile(),
                loc.getLine() + SCOPE_END_LINE_ESTIMATE, 0,
                loc.getOffset() + SCOPE_END_OFFSET_ESTIMATE, 0);
    }

    // ============ 辅助方法 ============

    private String resolveTypeName(TypeRef ref) {
        if (ref == null) return null;
        return ref.accept(new TypeRefVisitor<String>() {
            @Override
            public String visitSimple(SimpleType type) {
                return type.getName().getFullName();
            }

            @Override
            public String visitNullable(NullableType type) {
                String inner = resolveTypeName(type.getInnerType());
                return inner != null ? inner + "?" : null;
            }

            @Override
            public String visitGeneric(GenericType gt) {
                StringBuilder sb = new StringBuilder(gt.getName().getFullName());
                sb.append('<');
                for (int i = 0; i < gt.getTypeArgs().size(); i++) {
                    if (i > 0) sb.append(", ");
                    TypeRef argType = gt.getTypeArgs().get(i).getType();
                    sb.append(argType != null ? resolveTypeName(argType) : "*");
                }
                sb.append('>');
                return sb.toString();
            }

            @Override
            public String visitFunction(FunctionType type) {
                return "(Function)";
            }
        });
    }

    private Modifier extractVisibility(List<Modifier> modifiers) {
        if (modifiers == null) return Modifier.PUBLIC;
        for (Modifier m : modifiers) {
            switch (m) {
                case PUBLIC: case PRIVATE: case PROTECTED: case INTERNAL:
                    return m;
                default: break;
            }
        }
        return Modifier.PUBLIC;
    }

    private boolean looksLikeTypeName(String name) {
        return name != null && !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private Symbol builtinImportedFunctionSymbol(String localName,
                                                 BuiltinModuleExports.ExportedFunctionInfo functionInfo,
                                                 ImportDecl importDecl) {
        NovaType returnType = functionInfo.returnType != null
                ? inference.resolveNovaTypeFromName(functionInfo.returnType)
                : NovaTypes.DYNAMIC;
        List<NovaType> paramTypes = anyParameterTypes(Math.max(functionInfo.arity, 0));
        FunctionNovaType functionType = new FunctionNovaType(null, paramTypes,
                returnType != null ? returnType : NovaTypes.DYNAMIC, false);
        Symbol symbol = new Symbol(localName, SymbolKind.IMPORT,
                functionType.toDisplayString(), false,
                importDecl.getLocation(), importDecl, Modifier.PUBLIC);
        symbol.setResolvedNovaType(functionType);
        if (functionInfo.arity >= 0) {
            List<Symbol> params = new ArrayList<Symbol>();
            for (int i = 0; i < functionInfo.arity; i++) {
                Symbol param = new Symbol("arg" + i, SymbolKind.PARAMETER,
                        NovaTypes.ANY.toDisplayString(), false,
                        importDecl.getLocation(), importDecl, Modifier.PUBLIC);
                param.setResolvedNovaType(NovaTypes.ANY);
                params.add(param);
            }
            symbol.setParameters(params);
        }
        return symbol;
    }

    private void defineImportedSymbol(Symbol symbol) {
        if (symbol == null) return;
        currentScope.define(symbol);
    }

    private String baseType(String typeName) {
        if (typeName == null) return null;
        int idx = typeName.indexOf('<');
        String base = idx > 0 ? typeName.substring(0, idx) : typeName;
        return base.replace("?", "");
    }

    private List<Symbol> buildParamSymbols(List<Parameter> params) {
        List<Symbol> result = new ArrayList<Symbol>();
        for (Parameter p : params) {
            Symbol pSym = new Symbol(p.getName(), SymbolKind.PARAMETER,
                    resolveTypeName(p.getType()), false, p.getLocation(), p, Modifier.PUBLIC);
            pSym.setResolvedNovaType(typeResolver.resolve(p.getType()));
            result.add(pSym);
        }
        return result;
    }

    private boolean isLoopControlAllowed() {
        return loopDepth > 0 || lambdaDepth > 0;
    }

    private boolean statementAlwaysTransfersControl(Statement statement) {
        if (statement == null) return false;
        if (statement instanceof ReturnStmt
                || statement instanceof ThrowStmt
                || statement instanceof BreakStmt
                || statement instanceof ContinueStmt) {
            return true;
        }
        if (statement instanceof Block) {
            List<Statement> statements = ((Block) statement).getStatements();
            if (statements == null || statements.isEmpty()) return false;
            for (Statement child : statements) {
                if (statementAlwaysTransfersControl(child)) {
                    return true;
                }
            }
            return false;
        }
        if (statement instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) statement;
            return ifStmt.getThenBranch() != null
                    && ifStmt.getElseBranch() != null
                    && statementAlwaysTransfersControl(ifStmt.getThenBranch())
                    && statementAlwaysTransfersControl(ifStmt.getElseBranch());
        }
        if (statement instanceof WhenStmt) {
            WhenStmt whenStmt = (WhenStmt) statement;
            if (whenStmt.getElseBranch() == null || !statementAlwaysTransfersControl(whenStmt.getElseBranch())) {
                return false;
            }
            if (whenStmt.getBranches() == null || whenStmt.getBranches().isEmpty()) {
                return false;
            }
            for (WhenBranch branch : whenStmt.getBranches()) {
                if (branch == null || !statementAlwaysTransfersControl(branch.getBody())) {
                    return false;
                }
            }
            return true;
        }
        if (statement instanceof TryStmt) {
            TryStmt tryStmt = (TryStmt) statement;
            if (tryStmt.getFinallyBlock() != null && statementAlwaysTransfersControl(tryStmt.getFinallyBlock())) {
                return true;
            }
            if (tryStmt.getTryBlock() == null || !statementAlwaysTransfersControl(tryStmt.getTryBlock())) {
                return false;
            }
            if (tryStmt.getCatchClauses() != null) {
                for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                    if (catchClause == null || catchClause.getBody() == null
                            || !statementAlwaysTransfersControl(catchClause.getBody())) {
                        return false;
                    }
                }
            }
            return true;
        }
        return false;
    }

    private boolean allowsNullableReceiverAccess(NovaType receiverType, String memberName) {
        if (receiverType == null || memberName == null) return false;
        if (NovaTypes.isDynamicType(receiverType)) return true;
        NovaContract contract = StdlibContractMetadata.memberContract(baseType(receiverType.getTypeName()), memberName);
        if (contract == null) {
            contract = StdlibContractMetadata.memberContract("Any", memberName);
        }
        return contract != null && contract.getTags().contains(NovaContract.Tag.SCOPE_FUNCTION);
    }

    private NovaType requireNonNullableReceiver(NovaType receiverType, String memberName, AstNode node) {
        if (NovaTypes.isDynamicType(receiverType)) {
            return receiverType;
        }
        if (receiverType != null && receiverType.isNullable()) {
            if (allowsNullableReceiverAccess(receiverType, memberName)
                    || isNullOnlyType(receiverType)
                    || "toString".equals(memberName)) {
                return receiverType;
            }
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "Cannot access member '" + memberName + "' on nullable receiver of type '"
                            + receiverType.toDisplayString() + "'. Use ?. or !!",
                    node);
            return receiverType.withNullable(false);
        }
        return receiverType;
    }

    private FunctionNovaType asFunctionType(NovaType type) {
        if (type instanceof FunctionNovaType) {
            return (FunctionNovaType) type;
        }
        if (type instanceof JavaClassNovaType) {
            JavaTypeDescriptor descriptor = ((JavaClassNovaType) type).getDescriptor();
            return descriptor != null ? descriptor.toSamFunctionType(type.isNullable()) : null;
        }
        if (!(type instanceof ClassNovaType)) {
            return null;
        }
        String baseName = baseType(type.getTypeName());
        if ("Runnable".equals(baseName)) {
            return new FunctionNovaType(null, java.util.Collections.<NovaType>emptyList(), NovaTypes.UNIT, false);
        }
        if ("Supplier".equals(baseName) || "Callable".equals(baseName)) {
            return new FunctionNovaType(null, java.util.Collections.<NovaType>emptyList(), NovaTypes.ANY, false);
        }
        if ("Consumer".equals(baseName)) {
            return new FunctionNovaType(null, java.util.Collections.singletonList(NovaTypes.ANY), NovaTypes.UNIT, false);
        }
        if ("Function".equals(baseName)) {
            return new FunctionNovaType(null, java.util.Collections.singletonList(NovaTypes.ANY), NovaTypes.ANY, false);
        }
        if ("Predicate".equals(baseName)) {
            return new FunctionNovaType(null, java.util.Collections.singletonList(NovaTypes.ANY), NovaTypes.BOOLEAN, false);
        }
        if ("Comparator".equals(baseName)) {
            return new FunctionNovaType(null, java.util.Arrays.asList(NovaTypes.ANY, NovaTypes.ANY), NovaTypes.INT, false);
        }
        return null;
    }

    private void analyzeExpressionWithExpectedType(Expression expression, NovaType expectedType, Void ctx) {
        if (expression == null) return;
        FunctionNovaType expectedFunctionType = asFunctionType(expectedType);
        NovaType previousExpectedType = contextualExpectedTypes.put(expression, expectedType);
        try {
            if (expression instanceof LambdaExpr && expectedFunctionType != null) {
                LambdaExpr lambda = (LambdaExpr) expression;
                FunctionNovaType previous = contextualLambdaTypes.put(lambda, expectedFunctionType);
                try {
                    lambda.accept(this, ctx);
                } finally {
                    if (previous != null) {
                        contextualLambdaTypes.put(lambda, previous);
                    } else {
                        contextualLambdaTypes.remove(lambda);
                    }
                }
                return;
            }
            expression.accept(this, ctx);
        } finally {
            if (previousExpectedType != null) {
                contextualExpectedTypes.put(expression, previousExpectedType);
            } else {
                contextualExpectedTypes.remove(expression);
            }
        }
    }

    private NovaType expectedCallArgType(Symbol callableSymbol, CallExpr call, int argumentIndex) {
        Parameter parameter = expectedCallParameter(callableSymbol, call, argumentIndex);
        if (parameter != null) {
            return parameter.getType() != null ? typeResolver.resolve(parameter.getType()) : null;
        }
        if (callableSymbol == null || callableSymbol.getParameters() == null) return null;
        if (argumentIndex < 0 || argumentIndex >= callableSymbol.getParameters().size()) return null;
        return callableSymbol.getParameters().get(argumentIndex).getResolvedNovaType();
    }

    private Parameter expectedCallParameter(Symbol callableSymbol, CallExpr call, int argumentIndex) {
        if (callableSymbol == null || call == null || argumentIndex < 0) return null;

        List<Parameter> declaredParams = declaredCallParameters(callableSymbol, call);
        if (declaredParams == null || declaredParams.isEmpty()) return null;

        java.util.Map<String, Integer> paramIndexByName = new java.util.LinkedHashMap<String, Integer>();
        for (int i = 0; i < declaredParams.size(); i++) {
            paramIndexByName.put(declaredParams.get(i).getName(), Integer.valueOf(i));
        }

        boolean seenNamed = false;
        int positionalParamIndex = 0;
        java.util.Set<Integer> boundParamIndices = new java.util.LinkedHashSet<Integer>();
        for (int i = 0; i <= argumentIndex; i++) {
            boolean trailing = i == call.getArgs().size();
            if (trailing) {
                if (call.getTrailingLambda() == null) return null;
            } else if (i >= call.getArgs().size()) {
                return null;
            }

            CallExpr.Argument arg = trailing ? null : call.getArgs().get(i);
            if (!trailing && arg.isNamed()) {
                seenNamed = true;
                Integer paramIndex = paramIndexByName.get(arg.getName());
                if (paramIndex == null) return null;
                if (i == argumentIndex) {
                    return declaredParams.get(paramIndex.intValue());
                }
                boundParamIndices.add(paramIndex);
                continue;
            }

            if (!trailing && seenNamed) {
                return null;
            }

            while (positionalParamIndex < declaredParams.size()) {
                Parameter parameter = declaredParams.get(positionalParamIndex);
                if (!parameter.isVararg() && boundParamIndices.contains(Integer.valueOf(positionalParamIndex))) {
                    positionalParamIndex++;
                    continue;
                }
                break;
            }

            if (positionalParamIndex >= declaredParams.size()) {
                return null;
            }

            Parameter parameter = declaredParams.get(positionalParamIndex);
            if (i == argumentIndex) {
                return parameter;
            }
            if (!parameter.isVararg()) {
                boundParamIndices.add(Integer.valueOf(positionalParamIndex));
                positionalParamIndex++;
            }
        }
        return null;
    }

    private List<Parameter> declaredCallParameters(Symbol callableSymbol, CallExpr call) {
        if (callableSymbol == null) return null;
        AstNode declaration = callableSymbol.getDeclaration();
        if (declaration instanceof FunDecl) {
            return ((FunDecl) declaration).getParams();
        }
        if (declaration instanceof ConstructorDecl) {
            return ((ConstructorDecl) declaration).getParams();
        }
        if ((callableSymbol.getKind() == SymbolKind.CLASS || callableSymbol.getKind() == SymbolKind.ENUM)
                && declaration instanceof ClassDecl) {
            return resolveConstructorParametersForCall((ClassDecl) declaration, call);
        }
        return null;
    }

    private List<NovaType> analyzedCallArgumentTypes(CallExpr node) {
        List<NovaType> types = new ArrayList<NovaType>();
        if (node == null) return types;
        for (CallExpr.Argument arg : node.getArgs()) {
            types.add(getNovaType(arg.getValue()));
        }
        if (node.getTrailingLambda() != null) {
            types.add(getNovaType(node.getTrailingLambda()));
        }
        return types;
    }

    private JavaTypeDescriptor.JavaExecutableDescriptor resolveJavaMemberCall(MemberExpr memberExpr, CallExpr node) {
        if (memberExpr == null || node == null) return null;
        NovaType receiverType = getNovaType(memberExpr.getTarget());
        if (!(receiverType instanceof JavaClassNovaType)) return null;
        JavaTypeDescriptor descriptor = ((JavaClassNovaType) receiverType).getDescriptor();
        if (descriptor == null) return null;
        return descriptor.resolveMethod(memberExpr.getMember(), analyzedCallArgumentTypes(node), false);
    }

    private FunctionNovaType expectedFunctionType(Expression expression) {
        if (expression == null) return null;
        return asFunctionType(contextualExpectedTypes.get(expression));
    }

    private static final class JavaExecutableResolution {
        private final JavaTypeDescriptor.JavaExecutableDescriptor executable;
        private final boolean ambiguous;

        private JavaExecutableResolution(JavaTypeDescriptor.JavaExecutableDescriptor executable, boolean ambiguous) {
            this.executable = executable;
            this.ambiguous = ambiguous;
        }
    }

    private JavaExecutableResolution resolveJavaMethodReference(JavaClassNovaType receiverType, String memberName,
                                                                boolean staticOnly, boolean bound,
                                                                FunctionNovaType expectedType) {
        if (receiverType == null || memberName == null) return null;
        JavaTypeDescriptor descriptor = receiverType.getDescriptor();
        if (descriptor == null) return null;

        if (expectedType != null) {
            if (!bound && expectedType.hasReceiverType()
                    && !TypeCompatibility.isAssignable(receiverType, expectedType.getReceiverType(), superTypeRegistry)
                    && !TypeCompatibility.isAssignable(expectedType.getReceiverType(), receiverType, superTypeRegistry)) {
                return null;
            }
            List<NovaType> argTypes = expectedType.getParamTypes();
            if (!bound && !expectedType.hasReceiverType() && !argTypes.isEmpty()) {
                NovaType first = argTypes.get(0);
                if (first != null
                        && (TypeCompatibility.isAssignable(receiverType, first, superTypeRegistry)
                        || TypeCompatibility.isAssignable(first, receiverType, superTypeRegistry))) {
                    argTypes = argTypes.subList(1, argTypes.size());
                }
            }
            JavaTypeDescriptor.JavaExecutableDescriptor resolved =
                    descriptor.resolveMethod(memberName, argTypes, staticOnly);
            if (resolved != null) {
                NovaType expectedReturnType = expectedType.getReturnType();
                if (expectedReturnType == null
                        || TypeCompatibility.isAssignable(expectedReturnType, resolved.getReturnType(), superTypeRegistry)) {
                    return new JavaExecutableResolution(resolved, false);
                }
            }
        }

        List<JavaTypeDescriptor.JavaExecutableDescriptor> overloads = descriptor.methodOverloads(memberName, staticOnly);
        if (overloads.isEmpty()) return null;
        if (overloads.size() == 1) {
            return new JavaExecutableResolution(overloads.get(0), false);
        }
        return new JavaExecutableResolution(null, true);
    }

    private FunctionNovaType javaExecutableToFunctionType(NovaType receiverType,
                                                          JavaTypeDescriptor.JavaExecutableDescriptor executable,
                                                          boolean bound) {
        if (executable == null) return null;
        NovaType returnType = executable.getReturnType() != null ? executable.getReturnType() : NovaTypes.ANY;
        return new FunctionNovaType(bound ? null : receiverType, executable.getParamTypes(), returnType, false);
    }

    private FunctionNovaType inferJavaMemberReferenceType(NovaType receiverType, String memberName, boolean bound,
                                                          FunctionNovaType expectedType, AstNode errorNode) {
        if (!(receiverType instanceof JavaClassNovaType)) return null;
        JavaExecutableResolution resolution =
                resolveJavaMethodReference((JavaClassNovaType) receiverType, memberName, false, bound, expectedType);
        if (resolution == null) return null;
        if (resolution.ambiguous) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "Ambiguous Java method reference '" + memberName + "' requires an expected function type",
                    errorNode);
            return null;
        }
        return javaExecutableToFunctionType(receiverType, resolution.executable, bound);
    }

    private static final class ConditionSmartCast {
        private final Map<String, NovaType> whenTrue;
        private final Map<String, NovaType> whenFalse;

        private ConditionSmartCast() {
            this(new java.util.LinkedHashMap<String, NovaType>(),
                    new java.util.LinkedHashMap<String, NovaType>());
        }

        private ConditionSmartCast(Map<String, NovaType> whenTrue, Map<String, NovaType> whenFalse) {
            this.whenTrue = whenTrue;
            this.whenFalse = whenFalse;
        }
    }

    private NovaType mergeSmartCastTypes(NovaType existing, NovaType incoming) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;
        if (TypeCompatibility.isAssignable(existing, incoming, superTypeRegistry)) {
            return incoming;
        }
        if (TypeCompatibility.isAssignable(incoming, existing, superTypeRegistry)) {
            return existing;
        }
        return unifier.commonSuperType(existing, incoming);
    }

    private Map<String, NovaType> mergeSmartCastMaps(Map<String, NovaType> base, Map<String, NovaType> extra) {
        Map<String, NovaType> merged = new java.util.LinkedHashMap<String, NovaType>();
        if (base != null) {
            merged.putAll(base);
        }
        if (extra != null) {
            for (Map.Entry<String, NovaType> entry : extra.entrySet()) {
                NovaType mergedType = mergeSmartCastTypes(merged.get(entry.getKey()), entry.getValue());
                if (mergedType != null) {
                    merged.put(entry.getKey(), mergedType);
                }
            }
        }
        return merged;
    }

    private boolean isNullLiteral(Expression expression) {
        return expression instanceof Literal && ((Literal) expression).getKind() == Literal.LiteralKind.NULL;
    }

    private String identifierName(Expression expression) {
        return expression instanceof Identifier ? ((Identifier) expression).getName() : null;
    }

    private NovaType resolveSymbolNovaType(String name) {
        if (name == null) return null;
        Symbol symbol = currentScope.resolve(name);
        if (symbol == null) return null;
        NovaType type = symbol.getResolvedNovaType();
        if (type == null && symbol.getTypeName() != null) {
            type = inference.resolveNovaTypeFromName(symbol.getTypeName());
        }
        return type;
    }

    private ConditionSmartCast inferNullCheckSmartCast(BinaryExpr binaryExpr) {
        String identifier = null;
        if (isNullLiteral(binaryExpr.getLeft())) {
            identifier = identifierName(binaryExpr.getRight());
        } else if (isNullLiteral(binaryExpr.getRight())) {
            identifier = identifierName(binaryExpr.getLeft());
        }
        if (identifier == null) {
            return null;
        }

        NovaType identifierType = resolveSymbolNovaType(identifier);
        if (identifierType == null || !identifierType.isNullable()) {
            return null;
        }

        NovaType narrowedType = identifierType.withNullable(false);
        ConditionSmartCast smartCast = new ConditionSmartCast();
        switch (binaryExpr.getOperator()) {
            case NE:
            case REF_NE:
                smartCast.whenTrue.put(identifier, narrowedType);
                return smartCast;
            case EQ:
            case REF_EQ:
                smartCast.whenFalse.put(identifier, narrowedType);
                return smartCast;
            default:
                return null;
        }
    }

    private ConditionSmartCast inferConditionSmartCast(Expression condition) {
        ConditionSmartCast result = new ConditionSmartCast();
        if (condition instanceof TypeCheckExpr) {
            TypeCheckExpr typeCheckExpr = (TypeCheckExpr) condition;
            if (typeCheckExpr.getOperand() instanceof Identifier) {
                String name = ((Identifier) typeCheckExpr.getOperand()).getName();
                NovaType narrowedType = typeResolver.resolve(typeCheckExpr.getTargetType());
                if (narrowedType != null) {
                    if (typeCheckExpr.isNegated()) {
                        result.whenFalse.put(name, narrowedType);
                    } else {
                        result.whenTrue.put(name, narrowedType);
                    }
                }
            }
            return result;
        }
        if (condition instanceof UnaryExpr && ((UnaryExpr) condition).getOperator() == UnaryExpr.UnaryOp.NOT) {
            ConditionSmartCast inner = inferConditionSmartCast(((UnaryExpr) condition).getOperand());
            return new ConditionSmartCast(
                    new java.util.LinkedHashMap<String, NovaType>(inner.whenFalse),
                    new java.util.LinkedHashMap<String, NovaType>(inner.whenTrue));
        }
        if (condition instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) condition;
            ConditionSmartCast nullCheckSmartCast = inferNullCheckSmartCast(binaryExpr);
            if (nullCheckSmartCast != null) {
                return nullCheckSmartCast;
            }
            ConditionSmartCast left = inferConditionSmartCast(binaryExpr.getLeft());
            ConditionSmartCast right = inferConditionSmartCast(binaryExpr.getRight());
            if (binaryExpr.getOperator() == BinaryExpr.BinaryOp.AND) {
                return new ConditionSmartCast(
                        mergeSmartCastMaps(left.whenTrue, right.whenTrue),
                        new java.util.LinkedHashMap<String, NovaType>());
            }
            if (binaryExpr.getOperator() == BinaryExpr.BinaryOp.OR) {
                return new ConditionSmartCast(
                        new java.util.LinkedHashMap<String, NovaType>(),
                        mergeSmartCastMaps(left.whenFalse, right.whenFalse));
            }
        }
        return result;
    }

    private void defineSmartCastSymbols(Scope branchScope, Map<String, NovaType> narrowings, AstNode owner) {
        if (narrowings == null || narrowings.isEmpty()) return;
        for (Map.Entry<String, NovaType> entry : narrowings.entrySet()) {
            defineWhenBranchSmartCast(branchScope, entry.getKey(), entry.getValue(), owner);
        }
    }

    private void analyzeExpressionInSmartCastScope(Expression expression, Map<String, NovaType> narrowings, Void ctx) {
        if (expression == null) return;
        if (narrowings == null || narrowings.isEmpty()) {
            expression.accept(this, ctx);
            return;
        }
        Scope scope = enterScope(Scope.ScopeType.BLOCK, expression);
        try {
            defineSmartCastSymbols(scope, narrowings, expression);
            expression.accept(this, ctx);
        } finally {
            exitScope(expression);
        }
    }

    private void analyzeConditionExpression(Expression condition, Map<String, NovaType> incomingNarrowings, Void ctx) {
        if (condition == null) return;
        if (condition instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) condition;
            if (binaryExpr.getOperator() == BinaryExpr.BinaryOp.AND
                    || binaryExpr.getOperator() == BinaryExpr.BinaryOp.OR) {
                analyzeConditionExpression(binaryExpr.getLeft(), incomingNarrowings, ctx);
                ConditionSmartCast leftSmartCast = inferConditionSmartCast(binaryExpr.getLeft());
                Map<String, NovaType> rightNarrowings = incomingNarrowings;
                if (binaryExpr.getOperator() == BinaryExpr.BinaryOp.AND) {
                    rightNarrowings = mergeSmartCastMaps(incomingNarrowings, leftSmartCast.whenTrue);
                } else {
                    rightNarrowings = mergeSmartCastMaps(incomingNarrowings, leftSmartCast.whenFalse);
                }
                analyzeConditionExpression(binaryExpr.getRight(), rightNarrowings, ctx);
                setNovaType(binaryExpr, NovaTypes.BOOLEAN);
                return;
            }
        }
        analyzeExpressionInSmartCastScope(condition, incomingNarrowings, ctx);
    }

    private Scope enterConditionalBranchScope(AstNode scopeOwner, String bindingName, NovaType bindingType,
                                              AstNode bindingOwner, Map<String, NovaType> narrowings) {
        boolean needsScope = bindingName != null || (narrowings != null && !narrowings.isEmpty());
        if (!needsScope) return null;
        Scope branchScope = enterScope(Scope.ScopeType.BLOCK, scopeOwner);
        if (bindingName != null) {
            String bindingTypeName = bindingType != null ? bindingType.toDisplayString() : null;
            Symbol bindingSymbol = new Symbol(bindingName, SymbolKind.VARIABLE, bindingTypeName,
                    false, bindingOwner.getLocation(), bindingOwner, Modifier.PUBLIC);
            bindingSymbol.setResolvedNovaType(bindingType);
            branchScope.define(bindingSymbol);
        }
        defineSmartCastSymbols(branchScope, narrowings, scopeOwner);
        return branchScope;
    }

    private Symbol resolveGlobalSymbol(String name) {
        return name != null ? symbolTable.getGlobalScope().resolve(name) : null;
    }

    private Symbol resolveGlobalTypeSymbol(String name) {
        return name != null ? symbolTable.getGlobalScope().resolveType(name) : null;
    }

    private Symbol resolveTypeSymbol(String typeName) {
        return resolveGlobalTypeSymbol(baseType(typeName));
    }

    private boolean isTypeLikeSymbol(Symbol symbol) {
        if (symbol == null) return false;
        switch (symbol.getKind()) {
            case CLASS:
            case INTERFACE:
            case ENUM:
                return true;
            default:
                return false;
        }
    }

    private NovaType resolvedSymbolType(Symbol symbol) {
        if (symbol == null) return null;
        NovaType type = symbol.getResolvedNovaType();
        if (type == null && symbol.getTypeName() != null) {
            type = inference.resolveNovaTypeFromName(symbol.getTypeName());
        }
        return type;
    }

    private List<NovaType> parameterTypesFromSymbols(List<Symbol> params) {
        List<NovaType> types = new ArrayList<NovaType>();
        if (params == null) return types;
        for (Symbol param : params) {
            NovaType type = resolvedSymbolType(param);
            types.add(type != null ? type : NovaTypes.ANY);
        }
        return types;
    }

    private List<NovaType> anyParameterTypes(int count) {
        List<NovaType> types = new ArrayList<NovaType>();
        for (int i = 0; i < count; i++) {
            types.add(NovaTypes.ANY);
        }
        return types;
    }

    private List<NovaType> resolveDeclaredParameterTypes(List<Parameter> params, List<TypeParameter> typeParams) {
        List<NovaType> types = new ArrayList<NovaType>();
        if (params == null) return types;
        if (typeParams != null && !typeParams.isEmpty()) {
            typeResolver.enterTypeParams(typeParams);
        }
        try {
            for (Parameter parameter : params) {
                NovaType type = typeResolver.resolve(parameter.getType());
                types.add(type != null ? type : NovaTypes.ANY);
            }
        } finally {
            if (typeParams != null && !typeParams.isEmpty()) {
                typeResolver.exitTypeParams();
            }
        }
        return types;
    }

    private int actualCallArgumentCount(CallExpr node) {
        return (node != null ? node.getArgs().size() : 0) + (node != null && node.getTrailingLambda() != null ? 1 : 0);
    }

    private boolean canMatchParameters(List<Parameter> params, int actualCount) {
        if (params == null) {
            return actualCount == 0;
        }
        int required = 0;
        boolean hasVararg = false;
        for (Parameter parameter : params) {
            if (parameter.isVararg()) {
                hasVararg = true;
            } else if (!parameter.hasDefaultValue()) {
                required++;
            }
        }
        if (actualCount < required) return false;
        if (!hasVararg && actualCount > params.size()) return false;
        return true;
    }

    private List<Parameter> resolveConstructorParametersForCall(ClassDecl classDecl, CallExpr node) {
        if (classDecl == null) return null;
        int actualCount = actualCallArgumentCount(node);
        if (canMatchParameters(classDecl.getPrimaryConstructorParams(), actualCount)) {
            return classDecl.getPrimaryConstructorParams();
        }
        for (Declaration member : classDecl.getMembers()) {
            if (!(member instanceof ConstructorDecl)) continue;
            List<Parameter> params = ((ConstructorDecl) member).getParams();
            if (canMatchParameters(params, actualCount)) {
                return params;
            }
        }
        return classDecl.getPrimaryConstructorParams();
    }

    private FunctionNovaType functionTypeFromCallableSymbol(Symbol callableSymbol, NovaType receiverType) {
        if (callableSymbol == null) return null;
        if (callableSymbol.getResolvedNovaType() instanceof FunctionNovaType && receiverType == null) {
            return (FunctionNovaType) callableSymbol.getResolvedNovaType();
        }

        NovaType returnType = resolvedSymbolType(callableSymbol);
        if (returnType == null) returnType = NovaTypes.ANY;

        List<NovaType> paramTypes = parameterTypesFromSymbols(callableSymbol.getParameters());
        if (paramTypes.isEmpty() && callableSymbol.getKind() == SymbolKind.BUILTIN_FUNCTION) {
            for (NovaTypeRegistry.FunctionInfo info : NovaTypeRegistry.getBuiltinFunctions()) {
                if (info.name.equals(callableSymbol.getName())) {
                    int arity = Math.max(info.arity, 0);
                    paramTypes = anyParameterTypes(arity);
                    break;
                }
            }
        }

        return new FunctionNovaType(receiverType, paramTypes, returnType, false);
    }

    private FunctionNovaType functionTypeFromMemberRegistry(NovaType receiverType, String memberName, boolean bound) {
        if (receiverType == null || memberName == null) return null;
        List<NovaTypeRegistry.MethodInfo> methods =
                inference.lookupMemberMethods(baseType(receiverType.getTypeName()), memberName);
        if (methods.isEmpty()) return null;

        Integer arity = uniqueRegistryMemberArity(methods);
        if (arity == null) return null;

        NovaType returnType = inference.lookupMemberType(receiverType, memberName);
        return new FunctionNovaType(bound ? null : receiverType,
                anyParameterTypes(arity.intValue()), returnType != null ? returnType : NovaTypes.ANY, false);
    }

    private Integer uniqueRegistryMemberArity(List<NovaTypeRegistry.MethodInfo> methods) {
        Integer arity = null;
        for (NovaTypeRegistry.MethodInfo method : methods) {
            int currentArity = Math.max(method.arity, 0);
            if (arity == null) {
                arity = Integer.valueOf(currentArity);
            } else if (arity.intValue() != currentArity) {
                return null;
            }
        }
        return arity;
    }

    private FunctionNovaType functionTypeFromMemberSymbol(NovaType receiverType, Symbol memberSymbol, boolean bound) {
        if (memberSymbol == null) return null;
        NovaType returnType = resolvedSymbolType(memberSymbol);
        if (returnType == null) returnType = NovaTypes.ANY;
        return new FunctionNovaType(bound ? null : receiverType,
                parameterTypesFromSymbols(memberSymbol.getParameters()), returnType, false);
    }

    private FunctionNovaType inferMemberReferenceType(NovaType receiverType, String memberName, boolean bound) {
        if (receiverType == null || memberName == null) return null;
        Symbol receiverSymbol = resolveTypeSymbol(receiverType.getTypeName());
        if (receiverSymbol != null && receiverSymbol.getMembers() != null) {
            Symbol memberSymbol = receiverSymbol.getMembers().get(memberName);
            if (memberSymbol != null) {
                return functionTypeFromMemberSymbol(receiverType, memberSymbol, bound);
            }
        }
        return functionTypeFromMemberRegistry(receiverType, memberName, bound);
    }

    private FunctionNovaType inferBoundMemberValueType(NovaType receiverType, String memberName,
                                                       FunctionNovaType expectedType) {
        if (receiverType == null || memberName == null || expectedType == null) return null;

        Symbol receiverSymbol = resolveTypeSymbol(receiverType.getTypeName());
        if (receiverSymbol != null && receiverSymbol.getMembers() != null) {
            Symbol memberSymbol = receiverSymbol.getMembers().get(memberName);
            if (memberSymbol != null && memberSymbol.getKind() == SymbolKind.FUNCTION) {
                FunctionNovaType candidate = functionTypeFromMemberSymbol(receiverType, memberSymbol, true);
                if (candidate != null && TypeCompatibility.isAssignable(expectedType, candidate, superTypeRegistry)) {
                    return candidate;
                }
            }
        }

        return inferRegistryBoundMemberValueType(receiverType, memberName, expectedType);
    }

    private FunctionNovaType inferRegistryBoundMemberValueType(NovaType receiverType, String memberName,
                                                               FunctionNovaType expectedType) {
        List<NovaTypeRegistry.MethodInfo> methods =
                inference.lookupMemberMethods(baseType(receiverType.getTypeName()), memberName);
        if (methods.isEmpty()) return null;

        List<NovaType> expectedParamTypes = expectedType.getParamTypes();
        NovaType mergedReturnType = null;
        boolean matched = false;

        for (NovaTypeRegistry.MethodInfo method : methods) {
            if (method.isProperty) continue;
            int arity = Math.max(method.arity, 0);
            if (arity != expectedParamTypes.size()) continue;

            NovaType returnType = inference.resolveMemberReturnType(receiverType, method);
            FunctionNovaType candidate = new FunctionNovaType(
                    null,
                    new ArrayList<NovaType>(expectedParamTypes),
                    returnType != null ? returnType : NovaTypes.ANY,
                    false);
            if (!TypeCompatibility.isAssignable(expectedType, candidate, superTypeRegistry)) {
                continue;
            }

            mergedReturnType = mergeSmartCastTypes(mergedReturnType, candidate.getReturnType());
            matched = true;
        }

        if (!matched) return null;
        return new FunctionNovaType(null,
                new ArrayList<NovaType>(expectedParamTypes),
                mergedReturnType != null ? mergedReturnType : NovaTypes.ANY,
                false);
    }

    private Symbol anonymousObjectSymbol(ObjectLiteralExpr node, NovaType resultType) {
        Symbol symbol = new Symbol("<anonymous>", SymbolKind.OBJECT,
                resultType != null ? resultType.toDisplayString() : null,
                false, node.getLocation(), node, Modifier.PUBLIC);
        symbol.setResolvedNovaType(resultType != null ? resultType : NovaTypes.ANY);
        return symbol;
    }

    private void copyAnonymousObjectMembers(Symbol target, ObjectLiteralExpr initializer) {
        if (target == null || initializer == null) return;
        Symbol anonymousSymbol = anonymousObjectSymbols.get(initializer);
        if (anonymousSymbol == null || anonymousSymbol.getMembers() == null) return;
        for (Symbol member : anonymousSymbol.getMembers().values()) {
            target.addMember(member);
        }
    }

    private void defineReceiverMembers(Scope scope, NovaType receiverType, AstNode owner) {
        if (scope == null || receiverType == null) return;
        Symbol receiverSymbol = resolveTypeSymbol(receiverType.getTypeName());
        if (receiverSymbol != null && receiverSymbol.getMembers() != null) {
            for (Symbol member : receiverSymbol.getMembers().values()) {
                if (scope.resolveLocal(member.getName()) != null) continue;
                NovaType memberType = resolvedSymbolType(member);
                scope.define(narrowedShadowSymbol(member, member.getName(),
                        memberType != null ? memberType : NovaTypes.ANY, owner));
            }
            return;
        }
        String baseName = baseType(receiverType.getTypeName());
        List<NovaTypeRegistry.MethodInfo> methods = NovaTypeRegistry.getMethodsForType(baseName);
        if (methods == null) return;
        for (NovaTypeRegistry.MethodInfo method : methods) {
            if (scope.resolveLocal(method.name) != null) continue;
            String typeName = method.returnType != null ? method.returnType : receiverType.toDisplayString();
            SymbolKind kind = method.isProperty ? SymbolKind.PROPERTY : SymbolKind.FUNCTION;
            Symbol memberSymbol = new Symbol(method.name, kind, typeName, false,
                    owner != null ? owner.getLocation() : null, owner, Modifier.PUBLIC);
            NovaType methodType = method.returnType != null
                    ? inference.resolveNovaTypeFromName(method.returnType)
                    : receiverType.withNullable(false);
            memberSymbol.setResolvedNovaType(methodType != null ? methodType : NovaTypes.ANY);
            if (!method.isProperty && method.arity > 0) {
                List<Symbol> params = new ArrayList<Symbol>();
                for (int i = 0; i < method.arity; i++) {
                    Symbol param = new Symbol("arg" + i, SymbolKind.PARAMETER, NovaTypes.ANY.toDisplayString(),
                            false, owner != null ? owner.getLocation() : null, owner, Modifier.PUBLIC);
                    param.setResolvedNovaType(NovaTypes.ANY);
                    params.add(param);
                }
                memberSymbol.setParameters(params);
            }
            scope.define(memberSymbol);
        }
    }

    private NovaType singleInvariantTypeArg(NovaType type) {
        if (!(type instanceof ClassNovaType)) return null;
        ClassNovaType classType = (ClassNovaType) type;
        if (!classType.hasTypeArgs() || classType.getTypeArgs().isEmpty()) return null;
        return classType.getTypeArgs().get(0).getType();
    }

    private boolean isResultType(NovaType type) {
        return type != null && "Result".equals(baseType(type.getTypeName()));
    }

    private boolean isCollectionType(NovaType type, String baseName) {
        return type != null && baseName.equals(baseType(type.getTypeName()));
    }

    private NovaType firstTypeArg(NovaType type) {
        return singleInvariantTypeArg(type);
    }

    private NovaType secondTypeArg(NovaType type) {
        if (!(type instanceof ClassNovaType)) return null;
        ClassNovaType classType = (ClassNovaType) type;
        if (!classType.hasTypeArgs() || classType.getTypeArgs().size() < 2) return null;
        return classType.getTypeArgs().get(1).getType();
    }

    private NovaType destructuringEntryType(NovaType sourceType, DestructuringEntry entry, int index) {
        if (sourceType == null || entry == null || entry.isSkip()) return null;
        if (entry.isNameBased()) {
            String propertyName = entry.getPropertyName();
            if ("Pair".equals(baseType(sourceType.getTypeName()))) {
                if ("first".equals(propertyName)) return firstTypeArg(sourceType);
                if ("second".equals(propertyName)) return secondTypeArg(sourceType);
            }
            Symbol typeSymbol = resolveTypeSymbol(sourceType.getTypeName());
            if (typeSymbol != null && typeSymbol.getMembers() != null) {
                Symbol member = typeSymbol.getMembers().get(propertyName);
                if (member != null) {
                    NovaType memberType = resolvedSymbolType(member);
                    if (memberType != null) return memberType;
                }
            }
            return sourceType;
        }

        if ("Pair".equals(baseType(sourceType.getTypeName()))) {
            NovaType pairedType = index == 0 ? firstTypeArg(sourceType) : secondTypeArg(sourceType);
            if (pairedType != null) return pairedType;
        }

        Symbol typeSymbol = resolveTypeSymbol(sourceType.getTypeName());
        if (typeSymbol != null && typeSymbol.getDeclaration() instanceof ClassDecl) {
            ClassDecl classDecl = (ClassDecl) typeSymbol.getDeclaration();
            List<Parameter> params = classDecl.getPrimaryConstructorParams();
            if (params != null && index < params.size()) {
                NovaType resolved = typeResolver.resolve(params.get(index).getType());
                if (resolved != null) return resolved;
            }
        }
        return sourceType;
    }

    private NovaType collectionElementType(NovaType type) {
        NovaType elementType = firstTypeArg(type);
        return elementType != null ? elementType : NovaTypes.ANY;
    }

    private NovaType mapKeyType(NovaType type) {
        NovaType keyType = firstTypeArg(type);
        return keyType != null ? keyType : NovaTypes.ANY;
    }

    private NovaType mapValueType(NovaType type) {
        NovaType valueType = secondTypeArg(type);
        return valueType != null ? valueType : NovaTypes.ANY;
    }

    private int explicitLambdaParamCount(LambdaExpr lambda) {
        return lambda != null && lambda.hasExplicitParams() ? lambda.getParams().size() : -1;
    }

    private FunctionNovaType expectedMemberLambdaType(MemberExpr memberExpr, LambdaExpr lambda) {
        return expectedMemberLambdaType(memberExpr, java.util.Collections.<NovaType>emptyList(), lambda);
    }

    private FunctionNovaType expectedMemberLambdaType(MemberExpr memberExpr, List<NovaType> argumentTypes, LambdaExpr lambda) {
        if (memberExpr == null) return null;
        NovaType receiverType = getNovaType(memberExpr.getTarget());
        if (receiverType == null) return null;
        return contractEngine.expectedMemberLambdaType(memberExpr, receiverType, argumentTypes, lambda);
    }

    private FunctionNovaType expectedMemberLambdaType(MemberExpr memberExpr, List<NovaType> argumentTypes,
                                                      int lambdaOrdinal, LambdaExpr lambda) {
        if (memberExpr == null) return null;
        NovaType receiverType = getNovaType(memberExpr.getTarget());
        if (receiverType == null) return null;
        return contractEngine.expectedMemberLambdaType(memberExpr, receiverType, argumentTypes, lambdaOrdinal, lambda);
    }

    private NovaType inferSpecialMemberCallType(MemberExpr memberExpr, CallExpr node) {
        if (memberExpr == null) return null;
        NovaType receiverType = getNovaType(memberExpr.getTarget());
        if (receiverType == null) return null;
        java.util.List<LambdaExpr> lambdas = new java.util.ArrayList<LambdaExpr>();
        java.util.List<NovaType> argumentTypes = new java.util.ArrayList<NovaType>();
        for (CallExpr.Argument arg : node.getArgs()) {
            if (arg.getValue() instanceof LambdaExpr) {
                lambdas.add((LambdaExpr) arg.getValue());
            } else {
                argumentTypes.add(getNovaType(arg.getValue()));
            }
        }
        if (node.getTrailingLambda() != null) {
            lambdas.add(node.getTrailingLambda());
        }
        java.util.List<NovaType> lambdaResults = new java.util.ArrayList<NovaType>();
        for (LambdaExpr lambda : lambdas) {
            lambdaResults.add(inferLambdaResultType(lambda));
        }
        for (String message : contractEngine.validateMemberCall(memberExpr, receiverType, argumentTypes, lambdas, lambdaResults)) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR, message, !lambdas.isEmpty() ? lambdas.get(0) : node);
        }
        return contractEngine.inferMemberCallType(memberExpr, receiverType, argumentTypes, lambdas, lambdaResults);
    }

    private NovaType resolveMethodReferenceTypeTarget(MethodRefExpr node) {
        if (node == null || !node.hasTarget()) return null;
        if (node.getTypeTarget() != null) {
            return typeResolver.resolve(node.getTypeTarget());
        }
        if (node.getTarget() instanceof Identifier) {
            String name = ((Identifier) node.getTarget()).getName();
            Symbol symbol = currentScope.resolve(name);
            if (isTypeLikeSymbol(symbol)) {
                NovaType type = resolvedSymbolType(symbol);
                return type != null ? type : new ClassNovaType(name, false);
            }
            if ((symbol == null || symbol.getKind() == SymbolKind.IMPORT) && typeResolver.knowsTypeName(name)) {
                NovaType resolved = typeResolver.resolveTypeNameReference(name);
                if (resolved != null) {
                    return resolved;
                }
                return inference.resolveNovaTypeFromName(name);
            }
        }
        return null;
    }

    private String whenSubjectIdentifierName(Expression subject) {
        return subject instanceof Identifier ? ((Identifier) subject).getName() : null;
    }

    private NovaType resolveWhenBranchSmartCastType(WhenBranch branch) {
        if (branch == null || branch.getConditions() == null || branch.getConditions().size() != 1) {
            return null;
        }
        WhenBranch.WhenCondition condition = branch.getConditions().get(0);
        if (!(condition instanceof WhenBranch.TypeCondition)) {
            return null;
        }
        WhenBranch.TypeCondition typeCondition = (WhenBranch.TypeCondition) condition;
        if (typeCondition.isNegated()) {
            return null;
        }
        return typeResolver.resolve(typeCondition.getType());
    }

    private Symbol narrowedShadowSymbol(Symbol original, String name, NovaType narrowedType, AstNode owner) {
        SymbolKind kind = original != null ? original.getKind() : SymbolKind.VARIABLE;
        boolean mutable = original != null && original.isMutable();
        SourceLocation location = original != null ? original.getLocation() : owner.getLocation();
        AstNode declaration = original != null ? original.getDeclaration() : owner;
        Modifier visibility = original != null ? original.getVisibility() : Modifier.PUBLIC;
        Symbol narrowed = new Symbol(name, kind,
                narrowedType != null ? narrowedType.toDisplayString() : null,
                mutable, location, declaration, visibility);
        narrowed.setResolvedNovaType(narrowedType);
        if (original != null) {
            narrowed.setParameters(original.getParameters());
            narrowed.setSuperClass(original.getSuperClass());
            narrowed.setInterfaces(original.getInterfaces());
            if (original.getMembers() != null) {
                for (Symbol member : original.getMembers().values()) {
                    narrowed.addMember(member);
                }
            }
        }
        return narrowed;
    }

    private void defineWhenBranchSmartCast(Scope branchScope, String name, NovaType narrowedType, AstNode owner) {
        if (name == null || narrowedType == null || branchScope.resolveLocal(name) != null) {
            return;
        }
        Symbol original = currentScope.resolve(name);
        branchScope.define(narrowedShadowSymbol(original, name, narrowedType, owner));
    }

    private void analyzeWhenBranch(WhenBranch branch, Expression subject, String bindingName, Void ctx) {
        if (branch == null) return;
        if (branch.getConditions() != null) {
            for (WhenBranch.WhenCondition condition : branch.getConditions()) {
                if (condition instanceof WhenBranch.ExpressionCondition) {
                    Expression expression = ((WhenBranch.ExpressionCondition) condition).getExpression();
                    if (expression != null) expression.accept(this, ctx);
                } else if (condition instanceof WhenBranch.RangeCondition) {
                    Expression range = ((WhenBranch.RangeCondition) condition).getRange();
                    if (range != null) range.accept(this, ctx);
                } else if (condition instanceof WhenBranch.TypeCondition) {
                    TypeRef type = ((WhenBranch.TypeCondition) condition).getType();
                    if (type != null) {
                        typeResolver.resolve(type);
                    }
                }
            }
        }
        NovaType narrowedType = resolveWhenBranchSmartCastType(branch);
        String subjectIdentifier = whenSubjectIdentifierName(subject);
        boolean needsBranchScope = narrowedType != null
                && (subjectIdentifier != null || bindingName != null);
        if (needsBranchScope) {
            Scope branchScope = enterScope(Scope.ScopeType.BLOCK, branch);
            try {
                if (subjectIdentifier != null) {
                    defineWhenBranchSmartCast(branchScope, subjectIdentifier, narrowedType, branch);
                }
                if (bindingName != null) {
                    defineWhenBranchSmartCast(branchScope, bindingName, narrowedType, branch);
                }
                if (branch.getGuardExpr() != null) {
                    branch.getGuardExpr().accept(this, ctx);
                }
                if (branch.getBody() != null) {
                    branch.getBody().accept(this, ctx);
                }
            } finally {
                exitScope(branch);
            }
            return;
        }
        if (branch.getGuardExpr() != null) {
            branch.getGuardExpr().accept(this, ctx);
        }
        if (branch.getBody() != null) {
            branch.getBody().accept(this, ctx);
        }
    }

    private NovaType inferStatementResultType(Statement statement) {
        if (statement == null) return null;
        if (statement instanceof ExpressionStmt) {
            return getNovaType(((ExpressionStmt) statement).getExpression());
        }
        if (statement instanceof Block) {
            return inferBlockResultType((Block) statement);
        }
        return null; /*
/*
/*
/*
/*
/*
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粰 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
        } else if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粰绱㈠紩璁块棶");
            }
        }
        setNovaType(node, valueType);
*/
    }

    private NovaType inferBlockResultType(Block block) {
        if (block == null || block.getStatements().isEmpty()) return null;
        Statement lastStatement = block.getStatements().get(block.getStatements().size() - 1);
        return inferStatementResultType(lastStatement);
    }

    private void collectFunctionReturnTypes(Statement statement, List<NovaType> returnTypes) {
        if (statement == null) return;
        if (statement instanceof ReturnStmt) {
            ReturnStmt returnStmt = (ReturnStmt) statement;
            if (returnStmt.getValue() != null) {
                returnTypes.add(getNovaType(returnStmt.getValue()));
            } else {
                returnTypes.add(NovaTypes.UNIT);
            }
            return;
        }
        if (statement instanceof Block) {
            for (Statement nested : ((Block) statement).getStatements()) {
                collectFunctionReturnTypes(nested, returnTypes);
            }
            return;
        }
        if (statement instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) statement;
            collectFunctionReturnTypes(ifStmt.getThenBranch(), returnTypes);
            collectFunctionReturnTypes(ifStmt.getElseBranch(), returnTypes);
            return;
        }
        if (statement instanceof WhenStmt) {
            WhenStmt whenStmt = (WhenStmt) statement;
            for (WhenBranch branch : whenStmt.getBranches()) {
                collectFunctionReturnTypes(branch.getBody(), returnTypes);
            }
            collectFunctionReturnTypes(whenStmt.getElseBranch(), returnTypes);
            return;
        }
        if (statement instanceof TryStmt) {
            TryStmt tryStmt = (TryStmt) statement;
            collectFunctionReturnTypes(tryStmt.getTryBlock(), returnTypes);
            for (CatchClause catchClause : tryStmt.getCatchClauses()) {
                collectFunctionReturnTypes(catchClause.getBody(), returnTypes);
            }
            collectFunctionReturnTypes(tryStmt.getFinallyBlock(), returnTypes);
            return;
        }
        if (statement instanceof GuardStmt) {
            collectFunctionReturnTypes(((GuardStmt) statement).getElseBody(), returnTypes);
            return;
        }
        if (statement instanceof ForStmt) {
            collectFunctionReturnTypes(((ForStmt) statement).getBody(), returnTypes);
            return;
        }
        if (statement instanceof CStyleForStmt) {
            collectFunctionReturnTypes(((CStyleForStmt) statement).getBody(), returnTypes);
            return;
        }
        if (statement instanceof WhileStmt) {
            collectFunctionReturnTypes(((WhileStmt) statement).getBody(), returnTypes);
            return;
        }
        if (statement instanceof DoWhileStmt) {
            collectFunctionReturnTypes(((DoWhileStmt) statement).getBody(), returnTypes);
            return;
        }
        if (statement instanceof UseStmt) {
            collectFunctionReturnTypes(((UseStmt) statement).getBody(), returnTypes);
        }
    }

    private NovaType inferFunctionBlockReturnType(Block block) {
        List<NovaType> returnTypes = new ArrayList<NovaType>();
        collectFunctionReturnTypes(block, returnTypes);
        if (returnTypes.isEmpty()) {
            return NovaTypes.UNIT;
        }
        NovaType result = null;
        for (NovaType returnType : returnTypes) {
            result = result == null ? returnType : unifier.commonSuperType(result, returnType);
        }
        return result != null ? result : NovaTypes.UNIT;
    }

    private boolean hasTrailingExpression(Block block) {
        if (block == null || block.getStatements().isEmpty()) return false;
        Statement lastStatement = block.getStatements().get(block.getStatements().size() - 1);
        if (lastStatement instanceof ExpressionStmt) {
            return true;
        }
        if (lastStatement instanceof Block) {
            return hasTrailingExpression((Block) lastStatement);
        }
        return false;
    }

    private boolean isTerminatingStatement(Statement statement) {
        if (statement == null) return false;
        if (statement instanceof ReturnStmt
                || statement instanceof ThrowStmt
                || statement instanceof BreakStmt
                || statement instanceof ContinueStmt) {
            return true;
        }
        if (statement instanceof Block) {
            Block block = (Block) statement;
            return !block.getStatements().isEmpty()
                    && isTerminatingStatement(block.getStatements().get(block.getStatements().size() - 1));
        }
        if (statement instanceof IfStmt) {
            IfStmt ifStmt = (IfStmt) statement;
            return isTerminatingStatement(ifStmt.getThenBranch())
                    && isTerminatingStatement(ifStmt.getElseBranch());
        }
        return false;
    }

    private void applyPostConditionSmartCast(Map<String, NovaType> narrowedTypes, AstNode owner) {
        if (narrowedTypes == null || narrowedTypes.isEmpty()) return;
        for (Map.Entry<String, NovaType> entry : narrowedTypes.entrySet()) {
            Symbol existing = currentScope.resolve(entry.getKey());
            if (existing == null) continue;
            currentScope.define(narrowedShadowSymbol(existing, entry.getKey(), entry.getValue(), owner));
        }
    }

    private NovaType inferLambdaResultType(LambdaExpr lambda) {
        if (lambda == null || lambda.getBody() == null) return null;
        if (lambda.getBody() instanceof Expression) {
            return getNovaType((Expression) lambda.getBody());
        }
        if (lambda.getBody() instanceof Block) {
            return inferBlockResultType((Block) lambda.getBody());
        }
        return null;
    }

    private boolean isNullOnlyType(NovaType type) {
        return type instanceof NothingType && type.isNullable();
    }

    private void checkWhenEnumExhaustiveness(WhenExpr node) {
        if (node.hasElse() || node.getSubject() == null) return;
        NovaType subjectType = getNovaType(node.getSubject());
        if (!(subjectType instanceof ClassNovaType)) return;

        Symbol subjectSymbol = resolveTypeSymbol(subjectType.getTypeName());
        if (subjectSymbol == null || subjectSymbol.getKind() != SymbolKind.ENUM || subjectSymbol.getMembers() == null) {
            return;
        }

        java.util.Set<String> remainingEntries = new java.util.LinkedHashSet<String>(subjectSymbol.getMembers().keySet());
        for (WhenBranch branch : node.getBranches()) {
            if (branch.getConditions() == null) continue;
            for (WhenBranch.WhenCondition condition : branch.getConditions()) {
                if (!(condition instanceof WhenBranch.ExpressionCondition)) continue;
                Expression expression = ((WhenBranch.ExpressionCondition) condition).getExpression();
                if (!(expression instanceof MemberExpr)) continue;
                MemberExpr memberExpr = (MemberExpr) expression;
                if (memberExpr.getTarget() instanceof Identifier
                        && subjectType.getTypeName().equals(((Identifier) memberExpr.getTarget()).getName())) {
                    remainingEntries.remove(memberExpr.getMember());
                }
            }
        }

        if (!remainingEntries.isEmpty()) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "when 琛ㄨ揪寮忓 enum '" + subjectType.getTypeName() + "' 涓嶇┓灏斤紝缂哄皯: " + remainingEntries,
                    node);
        }
    }

    private void checkWhenSealedExhaustiveness(WhenExpr node) {
        if (node.hasElse() || node.getSubject() == null) return;
        NovaType subjectType = getNovaType(node.getSubject());
        if (!(subjectType instanceof ClassNovaType)) return;

        Symbol subjectSymbol = resolveTypeSymbol(subjectType.getTypeName());
        if (subjectSymbol == null || !isSealedType(subjectSymbol)) {
            return;
        }

        java.util.Set<String> remainingSubtypes = findDirectSealedSubtypes(subjectSymbol);
        if (remainingSubtypes.isEmpty()) {
            return;
        }

        for (WhenBranch branch : node.getBranches()) {
            if (branch.getConditions() == null) continue;
            for (WhenBranch.WhenCondition condition : branch.getConditions()) {
                if (condition instanceof WhenBranch.TypeCondition) {
                    WhenBranch.TypeCondition typeCondition = (WhenBranch.TypeCondition) condition;
                    if (!typeCondition.isNegated()) {
                        String matched = resolveTypeName(typeCondition.getType());
                        if (matched != null) {
                            remainingSubtypes.remove(baseType(matched));
                        }
                    }
                } else if (condition instanceof WhenBranch.ExpressionCondition) {
                    Expression expression = ((WhenBranch.ExpressionCondition) condition).getExpression();
                    if (expression instanceof Identifier) {
                        remainingSubtypes.remove(((Identifier) expression).getName());
                    }
                }
            }
        }

        if (!remainingSubtypes.isEmpty()) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "when 琛ㄨ揪寮忓 sealed '" + subjectType.getTypeName() + "' 涓嶇┓灏斤紝缂哄皯: " + remainingSubtypes,
                    node);
        }
    }

    private boolean isSealedType(Symbol symbol) {
        if (symbol == null) return false;
        AstNode declaration = symbol.getDeclaration();
        if (declaration instanceof ClassDecl) {
            return ((ClassDecl) declaration).isSealed();
        }
        if (declaration instanceof InterfaceDecl) {
            return ((InterfaceDecl) declaration).hasModifier(Modifier.SEALED);
        }
        return false;
    }

    private java.util.Set<String> findDirectSealedSubtypes(Symbol sealedSymbol) {
        java.util.Set<String> result = new java.util.LinkedHashSet<String>();
        String sealedName = sealedSymbol.getName();
        for (Symbol candidate : symbolTable.getGlobalScope().getDeclaredSymbols()) {
            if (candidate == sealedSymbol) continue;
            String superClass = candidate.getSuperClass();
            if (superClass != null && sealedName.equals(baseType(superClass))) {
                result.add(candidate.getName());
                continue;
            }
            List<String> interfaces = candidate.getInterfaces();
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (sealedName.equals(baseType(iface))) {
                        result.add(candidate.getName());
                        break;
                    }
                }
            }
        }
        for (Symbol candidate : symbolTable.getGlobalScope().getTypeSymbols().values()) {
            if (candidate == sealedSymbol) continue;
            String superClass = candidate.getSuperClass();
            if (superClass != null && sealedName.equals(baseType(superClass))) {
                result.add(candidate.getName());
                continue;
            }
            List<String> interfaces = candidate.getInterfaces();
            if (interfaces != null) {
                for (String iface : interfaces) {
                    if (sealedName.equals(baseType(iface))) {
                        result.add(candidate.getName());
                        break;
                    }
                }
            }
        }
        return result;
    }

    private void predeclareTopLevelTypes(Program program) {
        if (program == null) return;
        String packageName = program.getPackageDecl() != null
                ? program.getPackageDecl().getName().getFullName() : "";
        typeResolver.setCurrentPackageName(packageName);
        for (String knownTypeName : externalKnownTypeNames) {
            typeResolver.registerKnownType(knownTypeName);
        }
        for (Declaration declaration : program.getDeclarations()) {
            if (declaration instanceof ClassDecl) {
                ClassDecl classDecl = (ClassDecl) declaration;
                String qualifiedName = qualifyTopLevelTypeName(packageName, classDecl.getName());
                typeResolver.registerKnownType(classDecl.getName());
                typeResolver.registerKnownType(qualifiedName);
                if (classDecl.getTypeParams() != null && !classDecl.getTypeParams().isEmpty()) {
                    typeResolver.registerTypeDeclaration(classDecl.getName(), classDecl.getTypeParams());
                    typeResolver.registerTypeDeclaration(qualifiedName, classDecl.getTypeParams());
                }
            } else if (declaration instanceof InterfaceDecl) {
                InterfaceDecl interfaceDecl = (InterfaceDecl) declaration;
                String qualifiedName = qualifyTopLevelTypeName(packageName, interfaceDecl.getName());
                typeResolver.registerKnownType(interfaceDecl.getName());
                typeResolver.registerKnownType(qualifiedName);
                if (interfaceDecl.getTypeParams() != null && !interfaceDecl.getTypeParams().isEmpty()) {
                    typeResolver.registerTypeDeclaration(interfaceDecl.getName(), interfaceDecl.getTypeParams());
                    typeResolver.registerTypeDeclaration(qualifiedName, interfaceDecl.getTypeParams());
                }
            } else if (declaration instanceof ObjectDecl) {
                String qualifiedName = qualifyTopLevelTypeName(packageName, declaration.getName());
                typeResolver.registerKnownType(declaration.getName());
                typeResolver.registerKnownType(qualifiedName);
            } else if (declaration instanceof EnumDecl) {
                String qualifiedName = qualifyTopLevelTypeName(packageName, declaration.getName());
                typeResolver.registerKnownType(declaration.getName());
                typeResolver.registerKnownType(qualifiedName);
            } else if (declaration instanceof TypeAliasDecl) {
                TypeAliasDecl aliasDecl = (TypeAliasDecl) declaration;
                typeResolver.registerTypeAlias(aliasDecl.getName(), aliasDecl.getTypeParams(), aliasDecl.getAliasedType());
                typeResolver.registerTypeAlias(
                        qualifyTopLevelTypeName(packageName, aliasDecl.getName()),
                        aliasDecl.getTypeParams(), aliasDecl.getAliasedType());
            }
        }
    }

    private String qualifyTopLevelTypeName(String packageName, String simpleName) {
        if (simpleName == null || simpleName.isEmpty() || packageName == null || packageName.isEmpty()) {
            return simpleName;
        }
        return packageName + "." + simpleName;
    }

    private void validateTypeRef(TypeRef ref, AstNode node) {
        if (ref == null) return;
        ref.accept(new TypeRefVisitor<Void>() {
            @Override
            public Void visitSimple(SimpleType type) {
                String name = type.getName().getFullName();
                if (!typeResolver.knowsTypeName(name)) {
                    checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "鏈煡绫诲瀷: '" + name + "'", node);
                }
                return null;
            }

            @Override
            public Void visitNullable(NullableType type) {
                validateTypeRef(type.getInnerType(), node);
                return null;
            }

            @Override
            public Void visitGeneric(GenericType type) {
                String name = type.getName().getFullName();
                if (!typeResolver.knowsTypeName(name)) {
                    checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "鏈煡绫诲瀷: '" + name + "'", node);
                }
                for (TypeArgument arg : type.getTypeArgs()) {
                    if (!arg.isWildcard()) {
                        validateTypeRef(arg.getType(), node);
                    }
                }
                return null;
            }

            @Override
            public Void visitFunction(FunctionType type) {
                if (type.hasReceiverType()) {
                    validateTypeRef(type.getReceiverType(), node);
                }
                for (TypeRef paramType : type.getParamTypes()) {
                    validateTypeRef(paramType, node);
                }
                validateTypeRef(type.getReturnType(), node);
                return null;
            }
        });
    }

    // ============ 声明 visitor ============

    @Override
    public Void visitProgram(Program node, Void ctx) {
        for (ImportDecl imp : node.getImports()) {
            imp.accept(this, ctx);
        }
        for (Declaration decl : node.getDeclarations()) {
            decl.accept(this, ctx);
        }
        return null;
    }

    @Override
    public Void visitPackageDecl(PackageDecl node, Void ctx) {
        typeResolver.setCurrentPackageName(node.getName().getFullName());
        return null;
    }

    @Override
    public Void visitImportDecl(ImportDecl node, Void ctx) {
        if (node.isStringModule()) {
            return null;
        }
        String name = node.hasAlias() ? node.getAlias() : node.getName().getSimpleName();
        String qualifiedName = node.getName() != null ? node.getName().getFullName() : null;
        String builtinModule = !node.isJava() && !node.isStatic()
                ? BuiltinModuleExports.resolveModuleName(qualifiedName)
                : null;
        if (builtinModule != null) {
            if (node.isWildcard()) {
                for (BuiltinModuleExports.ExportedFunctionInfo functionInfo
                        : BuiltinModuleExports.getFunctions(builtinModule).values()) {
                    defineImportedSymbol(builtinImportedFunctionSymbol(functionInfo.name, functionInfo, node));
                }
            } else {
                String importedSymbolName = qualifiedName != null && qualifiedName.startsWith(builtinModule + ".")
                        ? qualifiedName.substring(builtinModule.length() + 1)
                        : null;
                BuiltinModuleExports.ExportedFunctionInfo functionInfo =
                        importedSymbolName != null ? BuiltinModuleExports.getFunction(builtinModule, importedSymbolName) : null;
                if (functionInfo != null && name != null) {
                    defineImportedSymbol(builtinImportedFunctionSymbol(name, functionInfo, node));
                    return null;
                }
            }
        }
        String importedSimpleName = node.getName() != null ? node.getName().getSimpleName() : null;
        boolean registerAsType = looksLikeTypeName(name) || looksLikeTypeName(importedSimpleName);
        NovaType importedSymbolType = null;
        if (node.isJava() && !node.isStatic()) {
            if (node.isWildcard()) {
                typeResolver.registerJavaWildcardImport(node.getName().getFullName());
            } else if (name != null && registerAsType) {
                typeResolver.registerJavaImport(name, node.getName().getFullName());
                importedSymbolType = typeResolver.resolveTypeNameReference(name);
            }
        } else if (!node.isStatic()) {
            if (node.isWildcard()) {
                typeResolver.registerNovaWildcardImport(node.getName().getFullName());
            } else if (name != null && registerAsType) {
                typeResolver.registerNovaImport(name, node.getName().getFullName());
                importedSymbolType = typeResolver.resolveTypeNameReference(name);
            }
        }
        if (name != null && !node.isWildcard()) {
            if (importedSymbolType == null && !typeResolver.knowsTypeName(name)) {
                importedSymbolType = NovaTypes.DYNAMIC;
            }
            Symbol sym = new Symbol(name, SymbolKind.IMPORT,
                    importedSymbolType != null ? importedSymbolType.toDisplayString() : null, false,
                    node.getLocation(), node, Modifier.PUBLIC);
            sym.setResolvedNovaType(importedSymbolType);
            currentScope.define(sym);
        }
        return null;
    }

    @Override
    public Void visitPropertyDecl(PropertyDecl node, Void ctx) {
        if (node.getType() != null) {
            validateTypeRef(node.getType(), node);
        }
        // const 规则检查
        if (node.isConst()) {
            if (!node.isVal()) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "const must be val", node);
            }
            if (!node.hasInitializer()) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "const val must have an initializer", node);
            } else if (!checker.isCompileTimeConstant(node.getInitializer(), currentScope)) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "const val initializer must be a compile-time constant", node);
            }
        }

        // 扩展属性不注册为当前作用域的符号
        if (node.isExtensionProperty()) {
            if (node.getInitializer() != null) {
                node.getInitializer().accept(this, ctx);
            }
            return null;
        }

        String typeName = resolveTypeName(node.getType());
        NovaType declaredNovaType = typeResolver.resolve(node.getType());

        // 先分析初始化器
        if (node.getInitializer() != null) {
            analyzeExpressionWithExpectedType(node.getInitializer(), declaredNovaType, ctx);
        }

        if (typeName == null && node.getInitializer() != null) {
            NovaType initNovaType = getNovaType(node.getInitializer());
            if (initNovaType != null) typeName = initNovaType.toDisplayString();
            if (declaredNovaType == null) {
                declaredNovaType = initNovaType;
            }
        }
        if (node.getType() == null && !node.isVal() && isNullOnlyType(declaredNovaType)) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "Mutable variable '" + node.getName()
                            + "' initialized with null requires an explicit nullable type",
                    node);
            declaredNovaType = NovaTypes.ERROR;
            typeName = declaredNovaType.toDisplayString();
        }

        SymbolKind kind = (currentScope.getType() == Scope.ScopeType.CLASS ||
                           currentScope.getType() == Scope.ScopeType.ENUM)
                ? SymbolKind.PROPERTY : SymbolKind.VARIABLE;

        checker.checkRedefinition(currentScope, node.getName(), node);
        Symbol sym = new Symbol(node.getName(), kind,
                typeName, !node.isVal(), node.getLocation(), node, extractVisibility(node.getModifiers()));
        sym.setResolvedNovaType(declaredNovaType);
        currentScope.define(sym);
        declarationSymbols.put(node, sym);
        if (node.getInitializer() instanceof ObjectLiteralExpr) {
            copyAnonymousObjectMembers(sym, (ObjectLiteralExpr) node.getInitializer());
        }

        // 类型兼容性检查
        if (node.getType() != null && node.getInitializer() != null) {
            NovaType targetType = typeResolver.resolve(node.getType());
            NovaType initType = getNovaType(node.getInitializer());
            checker.checkTypeCompatibility(targetType, initType, node, "变量 '" + node.getName() + "' 初始化");
        }

        return null;
    }

    @Override
    public Void visitFunDecl(FunDecl node, Void ctx) {
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.enterTypeParams(node.getTypeParams());
        }
        if (node.getReturnType() != null) {
            validateTypeRef(node.getReturnType(), node);
        }
        if (node.getReceiverType() != null) {
            validateTypeRef(node.getReceiverType(), node);
        }
        for (Parameter p : node.getParams()) {
            if (p.getType() != null) {
                validateTypeRef(p.getType(), p);
            }
        }
        String returnType = resolveTypeName(node.getReturnType());

        // 进入泛型类型参数作用域
        NovaType returnNovaType = typeResolver.resolve(node.getReturnType());

        Symbol funSym = null;
        if (!node.isExtensionFunction()) {
            Symbol existingLocal = currentScope.resolveLocal(node.getName());
            boolean allowPropertyCollision = (currentScope.getType() == Scope.ScopeType.CLASS
                    || currentScope.getType() == Scope.ScopeType.ENUM)
                    && existingLocal != null
                    && existingLocal.getKind() == SymbolKind.PROPERTY;
            if (!allowPropertyCollision) {
                checker.checkRedefinition(currentScope, node.getName(), node);
            }
            funSym = new Symbol(node.getName(), SymbolKind.FUNCTION,
                    returnType, false, node.getLocation(), node, extractVisibility(node.getModifiers()));
            funSym.setResolvedNovaType(returnNovaType);
            List<Symbol> paramSymbols = buildParamSymbols(node.getParams());
            funSym.setParameters(paramSymbols);
            declarationSymbols.put(node, funSym);
            if (!allowPropertyCollision) {
                currentScope.define(funSym);
            }
        }

        // 进入函数体作用域
        if (node.getBody() != null) {
            Scope funScope = enterScope(Scope.ScopeType.FUNCTION, node);
            if (node.isExtensionFunction()) {
                String receiverTypeName = resolveTypeName(node.getReceiverType());
                NovaType receiverNovaType = typeResolver.resolve(node.getReceiverType());
                if (receiverTypeName != null) {
                    Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE, receiverTypeName,
                            false, node.getLocation(), node, Modifier.PUBLIC);
                    thisSym.setResolvedNovaType(receiverNovaType);
                    funScope.define(thisSym);
                }
            }
            for (Parameter p : node.getParams()) {
                checker.checkRedefinition(funScope, p.getName(), p);
                String pType = resolveTypeName(p.getType());
                NovaType pNovaType = typeResolver.resolve(p.getType());
                Symbol pSym = new Symbol(p.getName(), SymbolKind.PARAMETER, pType,
                        false, p.getLocation(), p, Modifier.PUBLIC);
                pSym.setResolvedNovaType(pNovaType);
                funScope.define(pSym);
            }
            node.getBody().accept(this, ctx);
            if (returnNovaType == null) {
                if (node.getBody() instanceof Expression) {
                    returnNovaType = getNovaType((Expression) node.getBody());
                } else if (node.getBody() instanceof Block) {
                    returnNovaType = inferFunctionBlockReturnType((Block) node.getBody());
                }
                if (funSym != null && returnNovaType != null) {
                    funSym.setResolvedNovaType(returnNovaType);
                    funSym.setTypeName(returnNovaType.toDisplayString());
                }
            }
            exitScope(node);
        }

        // 退出泛型类型参数作用域
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.exitTypeParams();
        }

        return null;
    }

    @Override
    public Void visitClassDecl(ClassDecl node, Void ctx) {
        checker.checkTypeRedefinition(currentScope, node.getName(), node);
        Symbol classSym = new Symbol(node.getName(), SymbolKind.CLASS,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        classSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.defineType(classSym);

        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.registerTypeDeclaration(node.getName(), node.getTypeParams());
            typeResolver.enterTypeParams(node.getTypeParams());
        }

        // 注册继承关系到 SuperTypeRegistry
        String superClass = null;
        List<String> ifaceNames = new ArrayList<String>();
        if (node.getSuperTypes() != null && !node.getSuperTypes().isEmpty()) {
            superClass = resolveTypeName(node.getSuperTypes().get(0));
            if (superClass != null) superClass = baseType(superClass);
            for (int i = 1; i < node.getSuperTypes().size(); i++) {
                String ifName = resolveTypeName(node.getSuperTypes().get(i));
                if (ifName != null) ifaceNames.add(baseType(ifName));
            }
        }
        superTypeRegistry.registerClass(node.getName(), superClass, ifaceNames);

        Scope classScope = enterScope(Scope.ScopeType.CLASS, node);
        classScope.setOwnerTypeName(node.getName());

        Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE, node.getName(),
                false, node.getLocation(), node, Modifier.PUBLIC);
        thisSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        classScope.define(thisSym);

        if (node.getPrimaryConstructorParams() != null) {
            for (Parameter p : node.getPrimaryConstructorParams()) {
                checker.checkRedefinition(classScope, p.getName(), p);
                String pType = resolveTypeName(p.getType());
                NovaType pNovaType = typeResolver.resolve(p.getType());
                boolean isProperty = p.isProperty();
                SymbolKind propKind = isProperty ? SymbolKind.PROPERTY : SymbolKind.PARAMETER;
                Symbol propSym = new Symbol(p.getName(), propKind, pType,
                        isProperty && !p.hasModifier(Modifier.FINAL), p.getLocation(), p, extractVisibility(p.getModifiers()));
                propSym.setResolvedNovaType(pNovaType);
                classScope.define(propSym);
                if (isProperty) {
                    classSym.addMember(propSym);
                }
            }
        }

        if (node.getSuperTypes() != null && !node.getSuperTypes().isEmpty()) {
            String superName = resolveTypeName(node.getSuperTypes().get(0));
            classSym.setSuperClass(superName);
            if (node.getSuperTypes().size() > 1) {
                List<String> ifaces = new ArrayList<String>();
                for (int i = 1; i < node.getSuperTypes().size(); i++) {
                    String ifName = resolveTypeName(node.getSuperTypes().get(i));
                    if (ifName != null) ifaces.add(ifName);
                }
                classSym.setInterfaces(ifaces);
            }
        }

        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            List<SemanticDiagnostic> varianceDiags = VarianceChecker.check(node, typeResolver);
            diagnostics.addAll(varianceDiags);
        }

        for (Declaration member : node.getMembers()) {
            member.accept(this, ctx);
            if (member.getName() != null) {
                Symbol memberSym = declarationSymbols.get(member);
                if (memberSym == null) {
                    memberSym = classScope.resolveLocal(member.getName());
                }
                if (memberSym != null) classSym.addMember(memberSym);
            }
        }
        exitScope(node);

        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.exitTypeParams();
        }

        return null;
    }

    @Override
    public Void visitInterfaceDecl(InterfaceDecl node, Void ctx) {
        checker.checkTypeRedefinition(currentScope, node.getName(), node);
        Symbol ifSym = new Symbol(node.getName(), SymbolKind.INTERFACE,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        ifSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.defineType(ifSym);

        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.registerTypeDeclaration(node.getName(), node.getTypeParams());
            typeResolver.enterTypeParams(node.getTypeParams());
        }

        List<String> superInterfaces = new ArrayList<String>();
        if (node.getSuperTypes() != null) {
            for (TypeRef st : node.getSuperTypes()) {
                String name = resolveTypeName(st);
                if (name != null) superInterfaces.add(baseType(name));
            }
        }
        ifSym.setInterfaces(superInterfaces);
        superTypeRegistry.registerClass(node.getName(), null, superInterfaces);

        Scope ifScope = enterScope(Scope.ScopeType.CLASS, node);
        ifScope.setOwnerTypeName(node.getName());

        for (Declaration member : node.getMembers()) {
            member.accept(this, ctx);
            if (member.getName() != null) {
                Symbol memberSym = declarationSymbols.get(member);
                if (memberSym == null) {
                    memberSym = ifScope.resolveLocal(member.getName());
                }
                if (memberSym != null) ifSym.addMember(memberSym);
            }
        }
        exitScope(node);

        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.exitTypeParams();
        }

        return null;
    }

    @Override
    public Void visitObjectDecl(ObjectDecl node, Void ctx) {
        checker.checkRedefinition(currentScope, node.getName(), node);
        checker.checkTypeRedefinition(currentScope, node.getName(), node);
        Symbol objSym = new Symbol(node.getName(), SymbolKind.OBJECT,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        objSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.define(objSym);
        currentScope.defineType(objSym);

        Scope objScope = enterScope(Scope.ScopeType.CLASS, node);
        objScope.setOwnerTypeName(node.getName());

        Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE, node.getName(),
                false, node.getLocation(), node, Modifier.PUBLIC);
        thisSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        objScope.define(thisSym);

        if (node.getSuperTypes() != null && !node.getSuperTypes().isEmpty()) {
            objSym.setSuperClass(baseType(resolveTypeName(node.getSuperTypes().get(0))));
            if (node.getSuperTypes().size() > 1) {
                List<String> ifaces = new ArrayList<String>();
                for (int i = 1; i < node.getSuperTypes().size(); i++) {
                    String ifName = resolveTypeName(node.getSuperTypes().get(i));
                    if (ifName != null) ifaces.add(baseType(ifName));
                }
                objSym.setInterfaces(ifaces);
            } else {
                objSym.setInterfaces(java.util.Collections.<String>emptyList());
            }
        }

        for (Declaration member : node.getMembers()) {
            member.accept(this, ctx);
            if (member.getName() != null) {
                Symbol memberSym = declarationSymbols.get(member);
                if (memberSym == null) {
                    memberSym = objScope.resolveLocal(member.getName());
                }
                if (memberSym != null) objSym.addMember(memberSym);
            }
        }
        exitScope(node);
        return null;
    }

    @Override
    public Void visitEnumDecl(EnumDecl node, Void ctx) {
        checker.checkTypeRedefinition(currentScope, node.getName(), node);
        Symbol enumSym = new Symbol(node.getName(), SymbolKind.ENUM,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        enumSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.defineType(enumSym);

        superTypeRegistry.registerClass(node.getName(), null, new ArrayList<String>());

        Scope enumScope = enterScope(Scope.ScopeType.ENUM, node);
        enumScope.setOwnerTypeName(node.getName());

        for (EnumDecl.EnumEntry entry : node.getEntries()) {
            Symbol entrySym = new Symbol(entry.getName(), SymbolKind.ENUM_ENTRY,
                    node.getName(), false, entry.getLocation(), null, Modifier.PUBLIC);
            entrySym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
            enumScope.define(entrySym);
            enumSym.addMember(entrySym);
        }

        for (Declaration member : node.getMembers()) {
            member.accept(this, ctx);
            if (member.getName() != null) {
                Symbol memberSym = declarationSymbols.get(member);
                if (memberSym == null) {
                    memberSym = enumScope.resolveLocal(member.getName());
                }
                if (memberSym != null) enumSym.addMember(memberSym);
            }
        }
        exitScope(node);
        return null;
    }

    @Override
    public Void visitTypeAliasDecl(TypeAliasDecl node, Void ctx) {
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.enterTypeParams(node.getTypeParams());
        }
        validateTypeRef(node.getAliasedType(), node);
        String targetType = resolveTypeName(node.getAliasedType());
        checker.checkTypeRedefinition(currentScope, node.getName(), node);
        Symbol sym = new Symbol(node.getName(), SymbolKind.TYPE_ALIAS,
                targetType, false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        sym.setResolvedNovaType(typeResolver.resolve(node.getAliasedType()));
        currentScope.defineType(sym);
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.exitTypeParams();
        }
        return null;
    }

    @Override
    public Void visitConstructorDecl(ConstructorDecl node, Void ctx) {
        if (node.getBody() != null) {
            Scope ctorScope = enterScope(Scope.ScopeType.FUNCTION, node);
            for (Parameter p : node.getParams()) {
                String pType = resolveTypeName(p.getType());
                Symbol pSym = new Symbol(p.getName(), SymbolKind.PARAMETER, pType,
                        false, p.getLocation(), p, Modifier.PUBLIC);
                pSym.setResolvedNovaType(typeResolver.resolve(p.getType()));
                ctorScope.define(pSym);
            }
            node.getBody().accept(this, ctx);
            exitScope(node);
        }
        return null;
    }

    @Override
    public Void visitDestructuringDecl(DestructuringDecl node, Void ctx) {
        if (node.getInitializer() != null) {
            node.getInitializer().accept(this, ctx);
        }
        for (DestructuringEntry entry : node.getEntries()) {
            String name = entry.getLocalName();
            if (name != null && !"_".equals(name)) {
                checker.checkRedefinition(currentScope, name, node);
                Symbol sym = new Symbol(name, SymbolKind.VARIABLE, null,
                        !node.isVal(), node.getLocation(), node, Modifier.PUBLIC);
                currentScope.define(sym);
            }
        }
        return null;
    }

    @Override
    public Void visitParameter(Parameter node, Void ctx) {
        return null;
    }

    @Override
    public Void visitQualifiedName(QualifiedName node, Void ctx) {
        return null;
    }

    // ============ 语句 visitor ============

    @Override
    public Void visitBlock(Block node, Void ctx) {
        Scope blockScope = enterScope(Scope.ScopeType.BLOCK, node);
        for (Statement stmt : node.getStatements()) {
            stmt.accept(this, ctx);
        }
        exitScope(node);
        return null;
    }

    @Override
    public Void visitExpressionStmt(ExpressionStmt node, Void ctx) {
        node.getExpression().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitDeclarationStmt(DeclarationStmt node, Void ctx) {
        node.getDeclaration().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitIfStmt(IfStmt node, Void ctx) {
        analyzeConditionExpression(node.getCondition(), java.util.Collections.<String, NovaType>emptyMap(), ctx);
        ConditionSmartCast conditionSmartCast = inferConditionSmartCast(node.getCondition());
        NovaType bindingNovaType = node.getCondition() != null ? getNovaType(node.getCondition()) : null;

        AstNode thenOwner = node.getThenBranch() != null ? node.getThenBranch() : node;
        Scope thenScope = enterConditionalBranchScope(thenOwner, node.getBindingName(), bindingNovaType,
                node, conditionSmartCast.whenTrue);
        try {
            if (node.getThenBranch() != null) node.getThenBranch().accept(this, ctx);
        } finally {
            if (thenScope != null) {
                exitScope(thenOwner);
            }
        }

        if (node.getElseBranch() != null) {
            AstNode elseOwner = node.getElseBranch();
            Scope elseScope = enterConditionalBranchScope(elseOwner, null, null, node,
                    conditionSmartCast.whenFalse);
            try {
                node.getElseBranch().accept(this, ctx);
            } finally {
                if (elseScope != null) {
                    exitScope(elseOwner);
                }
            }
        }
        if (isTerminatingStatement(node.getThenBranch()) && !isTerminatingStatement(node.getElseBranch())) {
            applyPostConditionSmartCast(conditionSmartCast.whenFalse, node);
        } else if (isTerminatingStatement(node.getElseBranch()) && !isTerminatingStatement(node.getThenBranch())) {
            applyPostConditionSmartCast(conditionSmartCast.whenTrue, node);
        }
        return null;
    }

    @Override
    public Void visitWhenStmt(WhenStmt node, Void ctx) {
        if (node.getSubject() != null) node.getSubject().accept(this, ctx);
        if (node.getBindingName() != null) {
            Scope whenScope = enterScope(Scope.ScopeType.BLOCK, node);
            NovaType subjectNovaType = node.getSubject() != null ? getNovaType(node.getSubject()) : null;
            String bindingType = subjectNovaType != null ? subjectNovaType.toDisplayString() : null;
            Symbol bindingSym = new Symbol(node.getBindingName(), SymbolKind.VARIABLE, bindingType,
                    false, node.getLocation(), node, Modifier.PUBLIC);
            bindingSym.setResolvedNovaType(subjectNovaType);
            whenScope.define(bindingSym);
            for (WhenBranch branch : node.getBranches()) {
                analyzeWhenBranch(branch, node.getSubject(), node.getBindingName(), ctx);
            }
            if (node.getElseBranch() != null) node.getElseBranch().accept(this, ctx);
            exitScope(node);
        } else {
            for (WhenBranch branch : node.getBranches()) {
                analyzeWhenBranch(branch, node.getSubject(), null, ctx);
            }
            if (node.getElseBranch() != null) node.getElseBranch().accept(this, ctx);
        }
/*
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粰 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
        } else if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粰绱㈠紩璁块棶");
            }
        }
        setNovaType(node, valueType);
*/
        return null;
    }

    @Override
    public Void visitForStmt(ForStmt node, Void ctx) {
        node.getIterable().accept(this, ctx);
        Scope forScope = enterScope(Scope.ScopeType.BLOCK, node);

        NovaType iterableNovaType = getNovaType(node.getIterable());
        NovaType elemNovaType = inference.inferElementNovaType(iterableNovaType);
        for (int i = 0; i < node.getEntries().size(); i++) {
            DestructuringEntry entry = node.getEntries().get(i);
            String varName = entry.getLocalName();
            if (varName != null && !"_".equals(varName)) {
                NovaType entryNovaType = destructuringEntryType(elemNovaType, entry, i);
                String elemType = entryNovaType != null ? entryNovaType.toDisplayString() : null;
                Symbol varSym = new Symbol(varName, SymbolKind.VARIABLE, elemType,
                        false, node.getLocation(), node, Modifier.PUBLIC);
                varSym.setResolvedNovaType(entryNovaType);
                forScope.define(varSym);
            }
        }
        loopDepth++;
        try {
            if (node.getBody() != null) node.getBody().accept(this, ctx);
        } finally {
            loopDepth--;
            exitScope(node);
        }
/*
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粰 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
        } else if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粰绱㈠紩璁块棶");
            }
        }
        setNovaType(node, valueType);
*/
        return null;
    }

    @Override
    public Void visitCStyleForStmt(CStyleForStmt node, Void ctx) {
        Scope forScope = enterScope(Scope.ScopeType.BLOCK, node);
        if (node.getInit() != null) node.getInit().accept(this, ctx);
        if (node.getVarName() != null) {
            NovaType initType = getNovaType(node.getInit());
            Symbol varSym = new Symbol(node.getVarName(), SymbolKind.VARIABLE,
                    initType != null ? initType.toDisplayString() : null,
                    !node.isVal(), node.getLocation(), node, Modifier.PUBLIC);
            varSym.setResolvedNovaType(initType);
            forScope.define(varSym);
        }
        if (node.getCondition() != null) node.getCondition().accept(this, ctx);
        if (node.getUpdate() != null) node.getUpdate().accept(this, ctx);
        loopDepth++;
        try {
            if (node.getBody() != null) node.getBody().accept(this, ctx);
        } finally {
            loopDepth--;
            exitScope(node);
        }
/*
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粰 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
        } else if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粰绱㈠紩璁块棶");
            }
        }
        setNovaType(node, valueType);
*/
        return null;
    }

    @Override
    public Void visitWhileStmt(WhileStmt node, Void ctx) {
        node.getCondition().accept(this, ctx);
        loopDepth++;
        try {
            if (node.getBody() != null) node.getBody().accept(this, ctx);
        } finally {
            loopDepth--;
        }
/*
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粨 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
        } else if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粨绱㈠紩璁块棶");
            }
        }
        setNovaType(node, valueType);
*/
        return null;
    }

    @Override
    public Void visitDoWhileStmt(DoWhileStmt node, Void ctx) {
        loopDepth++;
        try {
            if (node.getBody() != null) node.getBody().accept(this, ctx);
            node.getCondition().accept(this, ctx);
        } finally {
            loopDepth--;
        }
/*
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粨 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
        } else if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粨绱㈠紩璁块棶");
            }
        }
        setNovaType(node, valueType);
*/
        return null;
    }

    @Override
    public Void visitTryStmt(TryStmt node, Void ctx) {
        if (node.getTryBlock() != null) node.getTryBlock().accept(this, ctx);
        for (CatchClause cc : node.getCatchClauses()) {
            Scope catchScope = enterScope(Scope.ScopeType.BLOCK, cc);
            String excType = cc.getParamType() != null ? resolveTypeName(cc.getParamType()) : "Exception";
            Symbol catchSymbol = new Symbol(cc.getParamName(), SymbolKind.VARIABLE, excType,
                    false, cc.getLocation(), cc, Modifier.PUBLIC);
            catchSymbol.setResolvedNovaType(inference.resolveNovaTypeFromName(excType));
            catchScope.define(catchSymbol);
            if (cc.getBody() != null) cc.getBody().accept(this, ctx);
            exitScope(cc);
        }
        if (node.getFinallyBlock() != null) node.getFinallyBlock().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitReturnStmt(ReturnStmt node, Void ctx) {
        if (node.getValue() != null) node.getValue().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitBreakStmt(BreakStmt node, Void ctx) {
        if (!isLoopControlAllowed()) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "break 只能出现在循环体或支持非局部跳转的 lambda 中", node);
        }
        return null;
    }

    @Override
    public Void visitContinueStmt(ContinueStmt node, Void ctx) {
        if (!isLoopControlAllowed()) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "continue 只能出现在循环体或支持非局部跳转的 lambda 中", node);
        }
        return null;
    }

    @Override
    public Void visitThrowStmt(ThrowStmt node, Void ctx) {
        if (node.getException() != null) node.getException().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitGuardStmt(GuardStmt node, Void ctx) {
        if (node.getExpression() != null) node.getExpression().accept(this, ctx);
        if (node.getElseBody() != null) node.getElseBody().accept(this, ctx);
        boolean elseTransfersControl = statementAlwaysTransfersControl(node.getElseBody());
        if (!elseTransfersControl) {
            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                    "Guard else branch must exit control flow",
                    node);
        }
        if (node.getBindingName() != null && elseTransfersControl) {
            NovaType expressionType = node.getExpression() != null ? getNovaType(node.getExpression()) : null;
            NovaType bindingType = expressionType;
            if (bindingType == null) {
                bindingType = NovaTypes.ANY;
            } else if (isNullOnlyType(bindingType)) {
                bindingType = NovaTypes.ANY;
            } else if (bindingType.isNullable()) {
                bindingType = bindingType.withNullable(false);
            }
            Symbol guardBinding = new Symbol(node.getBindingName(), SymbolKind.VARIABLE,
                    bindingType != null ? bindingType.toDisplayString() : null,
                    false, node.getLocation(), node, Modifier.PUBLIC);
            guardBinding.setResolvedNovaType(bindingType);
            currentScope.define(guardBinding);
        }
        return null;
    }

    @Override
    public Void visitUseStmt(UseStmt node, Void ctx) {
        Scope useScope = enterScope(Scope.ScopeType.BLOCK, node);
        for (UseStmt.UseBinding binding : node.getBindings()) {
            if (binding.getInitializer() != null) binding.getInitializer().accept(this, ctx);
            if (binding.getName() != null) {
                NovaType initNovaType = binding.getInitializer() != null
                        ? getNovaType(binding.getInitializer()) : null;
                String initType = initNovaType != null ? initNovaType.toDisplayString() : null;
                Symbol bindSym = new Symbol(binding.getName(), SymbolKind.VARIABLE, initType,
                        false, binding.getLocation(), binding, Modifier.PUBLIC);
                bindSym.setResolvedNovaType(initNovaType);
                useScope.define(bindSym);
            }
        }
        if (node.getBody() != null) node.getBody().accept(this, ctx);
        exitScope(node);
        return null;
    }

    // ============ 表达式 visitor ============

    @Override
    public Void visitLiteral(Literal node, Void ctx) {
        NovaType novaType;
        switch (node.getKind()) {
            case STRING:  novaType = NovaTypes.STRING;           break;
            case INT:     novaType = NovaTypes.INT;              break;
            case LONG:    novaType = NovaTypes.LONG;             break;
            case FLOAT:   novaType = NovaTypes.FLOAT;            break;
            case DOUBLE:  novaType = NovaTypes.DOUBLE;           break;
            case BOOLEAN: novaType = NovaTypes.BOOLEAN;          break;
            case CHAR:    novaType = NovaTypes.CHAR;             break;
            case UNIT:    novaType = NovaTypes.UNIT;             break;
            case NULL:    novaType = NovaTypes.NOTHING_NULLABLE; break;
            default:      novaType = null;
        }
        setNovaType(node, novaType);
        return null;
    }

    @Override
    public Void visitIdentifier(Identifier node, Void ctx) {
        Symbol sym = currentScope.resolve(node.getName());
        if (sym == null) {
            sym = currentScope.resolveType(node.getName());
        }
        if (sym != null) {
            NovaType nt = sym.getResolvedNovaType();
            if (nt == null && sym.getTypeName() != null) {
                nt = inference.resolveNovaTypeFromName(sym.getTypeName());
            }
            setNovaType(node, nt);
        }
        return null;
    }

    @Override
    public Void visitThisExpr(ThisExpr node, Void ctx) {
        Symbol thisSymbol = currentScope.resolve("this");
        if (thisSymbol != null) {
            NovaType thisType = resolvedSymbolType(thisSymbol);
            if (thisType != null) {
                setNovaType(node, thisType);
                return null;
            }
        }
        String enclosingType = currentScope.getEnclosingTypeName();
        if (enclosingType != null) {
            setNovaType(node, new ClassNovaType(enclosingType, false));
        }
        return null;
    }

    @Override
    public Void visitSuperExpr(SuperExpr node, Void ctx) {
        Symbol enclosingType = resolveGlobalTypeSymbol(currentScope.getEnclosingTypeName());
        if (enclosingType != null && enclosingType.getSuperClass() != null) {
            String superName = baseType(enclosingType.getSuperClass());
            Symbol superSymbol = resolveGlobalTypeSymbol(superName);
            NovaType superType = resolvedSymbolType(superSymbol);
            if (superType == null && superName != null) {
                superType = new ClassNovaType(superName, false);
            }
            setNovaType(node, superType);
        }
        return null;
    }

    @Override
    public Void visitCallExpr(CallExpr node, Void ctx) {
        node.getCallee().accept(this, ctx);
        Symbol callableSymbol = null;
        MemberExpr memberCallee = node.getCallee() instanceof MemberExpr ? (MemberExpr) node.getCallee() : null;
        NovaType contextualExpectedType = contextualExpectedTypes.get(node);
        String identifierCalleeName = node.getCallee() instanceof Identifier
                ? ((Identifier) node.getCallee()).getName()
                : null;
        if (node.getCallee() instanceof Identifier) {
            callableSymbol = currentScope.resolve(identifierCalleeName);
            if (callableSymbol == null) {
                callableSymbol = currentScope.resolveType(identifierCalleeName);
            }
        }
        java.util.List<NovaType> analyzedValueArgTypes = new java.util.ArrayList<NovaType>();
        int lambdaOrdinal = 0;
        for (int i = 0; i < node.getArgs().size(); i++) {
            CallExpr.Argument arg = node.getArgs().get(i);
            NovaType expectedType = expectedCallArgType(callableSymbol, node, i);
            if (expectedType == null && memberCallee != null && arg.getValue() instanceof LambdaExpr) {
                expectedType = expectedMemberLambdaType(memberCallee, analyzedValueArgTypes, lambdaOrdinal, (LambdaExpr) arg.getValue());
            }
            if (expectedType == null && identifierCalleeName != null && arg.getValue() instanceof LambdaExpr) {
                expectedType = contractEngine.expectedFunctionLambdaType(identifierCalleeName, analyzedValueArgTypes, lambdaOrdinal, (LambdaExpr) arg.getValue());
            }
            analyzeExpressionWithExpectedType(arg.getValue(), expectedType, ctx);
            if (arg.getValue() instanceof LambdaExpr) {
                lambdaOrdinal++;
            } else {
                analyzedValueArgTypes.add(getNovaType(arg.getValue()));
            }
        }
        if (node.getTrailingLambda() != null) {
            NovaType expectedType = expectedCallArgType(callableSymbol, node, node.getArgs().size());
            if (expectedType == null && memberCallee != null) {
                expectedType = expectedMemberLambdaType(memberCallee, analyzedValueArgTypes, lambdaOrdinal, node.getTrailingLambda());
            }
            if (expectedType == null && identifierCalleeName != null) {
                expectedType = contractEngine.expectedFunctionLambdaType(identifierCalleeName, analyzedValueArgTypes, lambdaOrdinal, node.getTrailingLambda());
            }
            analyzeExpressionWithExpectedType(node.getTrailingLambda(), expectedType, ctx);
        }

        // 推断调用返回类型
        if (node.getCallee() instanceof Identifier) {
            String funcName = ((Identifier) node.getCallee()).getName();
            Symbol funcSym = currentScope.resolve(funcName);
            if (funcSym == null) {
                funcSym = currentScope.resolveType(funcName);
            }
            if (funcSym != null) {
                NovaType importedType = funcSym.getKind() == SymbolKind.IMPORT
                        ? typeResolver.resolveTypeNameReference(funcName) : null;
                if (importedType instanceof JavaClassNovaType) {
                    JavaTypeDescriptor descriptor = ((JavaClassNovaType) importedType).getDescriptor();
                    JavaTypeDescriptor.JavaExecutableDescriptor ctor = descriptor != null
                            ? descriptor.resolveConstructor(analyzedCallArgumentTypes(node))
                            : null;
                    if (ctor != null) {
                        setNovaType(node, importedType.withNullable(false));
                    } else {
                        checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                "No matching Java constructor found for '" + funcName + "'",
                                node);
                    }
                } else if (funcSym.getKind() == SymbolKind.IMPORT
                        && funcSym.getResolvedNovaType() instanceof FunctionNovaType) {
                    FunctionNovaType importedFunction = (FunctionNovaType) funcSym.getResolvedNovaType();
                    setNovaType(node, importedFunction.getReturnType());
                } else if (funcSym.getKind() == SymbolKind.IMPORT && importedType instanceof ClassNovaType) {
                    setNovaType(node, importedType.withNullable(false));
                } else if (funcSym.getKind() == SymbolKind.FUNCTION || funcSym.getKind() == SymbolKind.BUILTIN_FUNCTION) {
                    NovaType retType = funcSym.getResolvedNovaType();
                    if (retType == null && funcSym.getTypeName() != null) {
                        retType = inference.resolveNovaTypeFromName(funcSym.getTypeName());
                    }
                    NovaType inferred = unifier.inferGenericReturnType(funcSym, node.getArgs());
                    if (inferred != null) retType = inferred;
                    setNovaType(node, retType);
                    if (funcSym.getKind() != SymbolKind.BUILTIN_FUNCTION
                            && funcSym.getDeclaration() instanceof FunDecl) {
                        checker.checkCallArguments(node, funcSym.getName(),
                                ((FunDecl) funcSym.getDeclaration()).getParams(),
                                parameterTypesFromSymbols(funcSym.getParameters()));
                    }
                } else if (funcSym.getKind() == SymbolKind.CLASS || funcSym.getKind() == SymbolKind.ENUM) {
                    NovaType ctorType = unifier.inferGenericConstructorType(funcSym, node.getArgs());
                    if (ctorType != null) {
                        setNovaType(node, ctorType);
                    } else {
                        setNovaType(node, new ClassNovaType(funcSym.getName(), false));
                    }
                    if (funcSym.getDeclaration() instanceof ClassDecl) {
                        ClassDecl classDecl = (ClassDecl) funcSym.getDeclaration();
                        List<Parameter> ctorParams = resolveConstructorParametersForCall(classDecl, node);
                        checker.checkCallArguments(node, funcSym.getName(),
                                ctorParams,
                                resolveDeclaredParameterTypes(ctorParams,
                                        classDecl.getTypeParams()));
                    }
                } else if (funcSym.getKind() == SymbolKind.IMPORT && NovaTypes.isDynamicType(funcSym.getResolvedNovaType())) {
                    setNovaType(node, NovaTypes.DYNAMIC);
                }
            } else {
                NovaType typeCallee = typeResolver.resolveTypeNameReference(funcName);
                if (typeCallee instanceof JavaClassNovaType) {
                    JavaTypeDescriptor descriptor = ((JavaClassNovaType) typeCallee).getDescriptor();
                    JavaTypeDescriptor.JavaExecutableDescriptor ctor = descriptor != null
                            ? descriptor.resolveConstructor(analyzedCallArgumentTypes(node))
                            : null;
                    if (ctor != null) {
                        setNovaType(node, typeCallee.withNullable(false));
                    } else {
                        checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                "No matching Java constructor found for '" + funcName + "'",
                                node);
                    }
                }
            }
            // 集合工厂函数泛型推断
            NovaType calleeType = getNovaType(node.getCallee());
            if (NovaTypes.isDynamicType(calleeType)) {
                setNovaType(node, NovaTypes.DYNAMIC.withNullable(calleeType.isNullable()));
            }
            NovaType collType = inference.inferCollectionFactoryType(funcName, node.getArgs());
            if (collType != null) {
                setNovaType(node, collType);
            }
            // stdlib Supplier Lambda 函数类型推导
            StdlibRegistry.SupplierLambdaInfo slInfo = StdlibRegistry.getSupplierLambda(funcName);
            if (slInfo != null) {
                setNovaType(node, inference.resolveNovaTypeFromName(slInfo.novaReturnType));
            }
            if ("async".equals(funcName)) {
                LambdaExpr asyncLambda = node.getTrailingLambda();
                if (asyncLambda == null && !node.getArgs().isEmpty()
                        && node.getArgs().get(0).getValue() instanceof LambdaExpr) {
                    asyncLambda = (LambdaExpr) node.getArgs().get(0).getValue();
                }
                if (asyncLambda != null) {
                    NovaType payloadType = inferLambdaResultType(asyncLambda);
                    if (payloadType == null) payloadType = NovaTypes.ANY;
                    setNovaType(node, new ClassNovaType("Future",
                            java.util.Collections.singletonList(NovaTypeArgument.invariant(payloadType)), false));
                }
            }
            if ("abs".equals(funcName) && !node.getArgs().isEmpty()) {
                NovaType absArgType = getNovaType(node.getArgs().get(0).getValue());
                if (NovaTypes.isNumericType(absArgType)) {
                    setNovaType(node, absArgType.withNullable(false));
                }
            }
            java.util.List<NovaType> argumentTypes = new java.util.ArrayList<NovaType>();
            java.util.List<LambdaExpr> functionLambdas = new java.util.ArrayList<LambdaExpr>();
            for (CallExpr.Argument arg : node.getArgs()) {
                if (arg.getValue() instanceof LambdaExpr) {
                    functionLambdas.add((LambdaExpr) arg.getValue());
                } else {
                    argumentTypes.add(getNovaType(arg.getValue()));
                }
            }
            if (node.getTrailingLambda() != null) {
                functionLambdas.add(node.getTrailingLambda());
            }
            java.util.List<NovaType> functionLambdaResults = new java.util.ArrayList<NovaType>();
            for (LambdaExpr functionLambda : functionLambdas) {
                functionLambdaResults.add(inferLambdaResultType(functionLambda));
            }
            for (String message : contractEngine.validateFunctionCall(funcName, argumentTypes, functionLambdas, functionLambdaResults, contextualExpectedType)) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR, message, node);
            }
            NovaType functionContractType = contractEngine.inferFunctionCallType(funcName, argumentTypes, functionLambdas, functionLambdaResults, contextualExpectedType);
            if (functionContractType != null) {
                setNovaType(node, functionContractType);
            }
        } else if (node.getCallee() instanceof MemberExpr) {
            NovaType specialType = inferSpecialMemberCallType(memberCallee, node);
            if (specialType != null) {
                setNovaType(node, specialType);
            } else {
                JavaTypeDescriptor.JavaExecutableDescriptor javaMethod = resolveJavaMemberCall(memberCallee, node);
                if (javaMethod != null) {
                    setNovaType(node, javaMethod.getReturnType());
                } else {
                    NovaType receiverType = memberCallee != null ? getNovaType(memberCallee.getTarget()) : null;
                    if (receiverType instanceof JavaClassNovaType) {
                        checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                "No matching Java method overload found for '" + memberCallee.getMember() + "'",
                                node);
                    }
                }
                NovaType calleeNovaType = getNovaType(node.getCallee());
                if (calleeNovaType != null) {
                    setNovaType(node, calleeNovaType);
                }
            }
        }
        return null;
    }

    @Override
    public Void visitMemberExpr(MemberExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        NovaType receiverNovaType = requireNonNullableReceiver(getNovaType(node.getTarget()), node.getMember(), node);
        FunctionNovaType expectedFunctionType = expectedFunctionType(node);
        if (node.getTarget() instanceof Identifier) {
            Symbol targetSymbol = currentScope.resolve(((Identifier) node.getTarget()).getName());
            if (targetSymbol != null && targetSymbol.getMembers() != null) {
                Symbol member = targetSymbol.getMembers().get(node.getMember());
                if (member != null) {
                    if (member.getKind() == SymbolKind.FUNCTION
                            && (expectedFunctionType != null
                            || (member.getParameters() != null && !member.getParameters().isEmpty()))) {
                        NovaType boundReceiverType = getNovaType(node.getTarget());
                        FunctionNovaType functionType = functionTypeFromMemberSymbol(boundReceiverType, member, true);
                        setNovaType(node, functionType);
                        return null;
                    }
                    NovaType memberType = resolvedSymbolType(member);
                    setNovaType(node, memberType);
                    return null;
                }
            }
        }
        if (receiverNovaType != null) {
            if (NovaTypes.isDynamicType(receiverNovaType)) {
                setNovaType(node, NovaTypes.DYNAMIC);
                return null;
            }
            if (expectedFunctionType != null) {
                FunctionNovaType javaMethodValue =
                        inferJavaMemberReferenceType(receiverNovaType, node.getMember(), true, expectedFunctionType, node);
                if (javaMethodValue != null) {
                    setNovaType(node, javaMethodValue);
                    return null;
                }
                FunctionNovaType boundMemberValue =
                        inferBoundMemberValueType(receiverNovaType, node.getMember(), expectedFunctionType);
                if (boundMemberValue != null) {
                    setNovaType(node, boundMemberValue);
                    return null;
                }
            }
            NovaType contractMemberType = contractEngine.inferMemberAccessType(receiverNovaType, node.getMember());
            if (contractMemberType != null) {
                setNovaType(node, contractMemberType);
            }
            NovaType registryMemberType = inference.lookupMemberType(receiverNovaType, node.getMember());
            if (registryMemberType != null) {
                setNovaType(node, registryMemberType);
            }
            String baseName = receiverNovaType.getTypeName();
            Symbol receiverSym = resolveTypeSymbol(baseName);
            if (receiverSym != null && receiverSym.getMembers() != null) {
                Symbol member = receiverSym.getMembers().get(node.getMember());
                if (member != null) {
                    NovaType memberType = member.getResolvedNovaType();
                    if (memberType == null && member.getTypeName() != null) {
                        memberType = inference.resolveNovaTypeFromName(member.getTypeName());
                    }
                    setNovaType(node, memberType);
                }
            }
        }
        return null;
    }

    @Override
    public Void visitAssignExpr(AssignExpr node, Void ctx) {
        node.getValue().accept(this, ctx);
        if (node.getTarget() != null) {
            node.getTarget().accept(this, ctx);
        }

        NovaType valueType = getNovaType(node.getValue());
        if (node.getTarget() instanceof MemberExpr) {
            MemberExpr memberExpr = (MemberExpr) node.getTarget();
            NovaType receiverType = getNovaType(memberExpr.getTarget());
            if (receiverType != null) {
                Symbol receiverSym = resolveTypeSymbol(receiverType.getTypeName());
                if (receiverSym != null && receiverSym.getMembers() != null) {
                    Symbol memberSym = receiverSym.getMembers().get(memberExpr.getMember());
                    if (memberSym != null) {
                        if (!memberSym.isMutable() &&
                                (memberSym.getKind() == SymbolKind.VARIABLE || memberSym.getKind() == SymbolKind.PROPERTY)) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "涓嶅彲閲嶆柊璧嬪€? '" + memberExpr.getMember() + "' 鏄?val锛堜笉鍙彉灞炴€э級", node);
                        }
                        if (memberSym.getResolvedNovaType() != null) {
                            checker.checkTypeCompatibility(memberSym.getResolvedNovaType(), valueType, node,
                                    "璧嬪€肩粨 '" + memberExpr.getMember() + "'");
                        }
                    }
                }
            }
            setNovaType(node, valueType);
            return null;
        }
        if (node.getTarget() instanceof IndexExpr) {
            IndexExpr indexExpr = (IndexExpr) node.getTarget();
            NovaType targetType = getNovaType(indexExpr.getTarget());
            NovaType elementType = inference.inferElementNovaType(targetType);
            if (elementType != null) {
                checker.checkTypeCompatibility(elementType, valueType, node, "璧嬪€肩粨绱㈠紩璁块棶");
            }
            setNovaType(node, valueType);
            return null;
        }
        if (node.getTarget() instanceof Identifier) {
            String name = ((Identifier) node.getTarget()).getName();
            Symbol sym = currentScope.resolve(name);
            if (sym != null && !sym.isMutable() &&
                    (sym.getKind() == SymbolKind.VARIABLE || sym.getKind() == SymbolKind.PROPERTY)) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "不可重新赋值: '" + name + "' 是 val（不可变变量）", node);
            }
            if (sym != null && sym.getResolvedNovaType() != null) {
                    checker.checkTypeCompatibility(sym.getResolvedNovaType(), valueType, node,
                            "赋值给 '" + name + "'");
            }
        }
        return null;
    }

    @Override
    public Void visitBinaryExpr(BinaryExpr node, Void ctx) {
        node.getLeft().accept(this, ctx);
        node.getRight().accept(this, ctx);

        NovaType leftType = getNovaType(node.getLeft());
        NovaType rightType = getNovaType(node.getRight());
        BinaryExpr.BinaryOp op = node.getOperator();

        NovaType resultType = null;
            switch (op) {
            case ADD:
                if (NovaTypes.isDynamicType(leftType) || NovaTypes.isDynamicType(rightType)) {
                    resultType = NovaTypes.DYNAMIC;
                    break;
                }
                if (NovaTypes.STRING.equals(leftType) || NovaTypes.STRING.equals(rightType)) {
                    resultType = NovaTypes.STRING;
                } else if (NovaTypes.isNumericType(leftType) && NovaTypes.isNumericType(rightType)) {
                    resultType = NovaTypes.promoteNumeric(leftType, rightType);
                }
                break;
            case SUB: case MUL: case DIV: case MOD:
                if (NovaTypes.isDynamicType(leftType) || NovaTypes.isDynamicType(rightType)) {
                    resultType = NovaTypes.DYNAMIC;
                    break;
                }
                if (NovaTypes.isNumericType(leftType) && NovaTypes.isNumericType(rightType)) {
                    resultType = NovaTypes.promoteNumeric(leftType, rightType);
                }
                break;
            case EQ: case NE: case LT: case GT: case LE: case GE:
            case REF_EQ: case REF_NE: case AND: case OR:
            case IN: case NOT_IN:
                resultType = NovaTypes.BOOLEAN;
                break;
            case RANGE_INCLUSIVE: case RANGE_EXCLUSIVE:
                resultType = new ClassNovaType("Range", false);
                break;
            case TO:
                if (leftType != null && rightType != null) {
                    resultType = new ClassNovaType("Pair",
                            java.util.Arrays.asList(NovaTypeArgument.invariant(leftType),
                                                     NovaTypeArgument.invariant(rightType)), false);
                } else {
                    resultType = new ClassNovaType("Pair", false);
                }
                break;
            default: break;
        }
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitUnaryExpr(UnaryExpr node, Void ctx) {
        node.getOperand().accept(this, ctx);
        NovaType operandNovaType = getNovaType(node.getOperand());
        switch (node.getOperator()) {
            case NOT:
                setNovaType(node, NovaTypes.BOOLEAN);
                break;
            case NEG: case POS: case INC: case DEC:
                setNovaType(node, operandNovaType);
                break;
        }
        return null;
    }

    @Override
    public Void visitIndexExpr(IndexExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        node.getIndex().accept(this, ctx);
        NovaType targetType = getNovaType(node.getTarget());
        if (targetType != null) {
            if (NovaTypes.isDynamicType(targetType)) {
                setNovaType(node, NovaTypes.DYNAMIC);
                return null;
            }
            String baseName = targetType.getTypeName();
            if ("String".equals(baseName)) {
                setNovaType(node, NovaTypes.STRING);
            } else if ("List".equals(baseName) || "Array".equals(baseName)) {
                NovaType elemType = inference.inferElementNovaType(targetType);
                setNovaType(node, elemType);
            }
        }
        return null;
    }

    @Override
    public Void visitLambdaExpr(LambdaExpr node, Void ctx) {
        FunctionNovaType expectedType = contextualLambdaTypes.get(node);
        List<NovaType> parameterTypes = new ArrayList<NovaType>();
        Scope lambdaScope = enterScope(Scope.ScopeType.LAMBDA, node);
        lambdaDepth++;
        try {
            if (expectedType != null && expectedType.hasReceiverType()) {
                NovaType receiverType = expectedType.getReceiverType();
                Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE,
                        receiverType != null ? receiverType.toDisplayString() : null,
                        false, node.getLocation(), node, Modifier.PUBLIC);
                thisSym.setResolvedNovaType(receiverType);
                lambdaScope.define(thisSym);
                defineReceiverMembers(lambdaScope, receiverType, node);
            }
            if (node.hasExplicitParams()) {
                if (expectedType != null && expectedType.getParamTypes().size() != node.getParams().size()) {
                    checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "Lambda parameter count mismatch: expected " + expectedType.getParamTypes().size()
                                    + " but found " + node.getParams().size(),
                            node);
                }
                for (int i = 0; i < node.getParams().size(); i++) {
                    LambdaExpr.LambdaParam p = node.getParams().get(i);
                    checker.checkRedefinition(lambdaScope, p.getName(), node);
                    if (p.hasType()) {
                        validateTypeRef(p.getType(), p);
                    }
                    NovaType explicitType = p.hasType() ? typeResolver.resolve(p.getType()) : null;
                    NovaType expectedParamType = expectedType != null && i < expectedType.getParamTypes().size()
                            ? expectedType.getParamTypes().get(i)
                            : null;
                    if (explicitType != null && expectedParamType != null
                            && !TypeCompatibility.isAssignable(explicitType, expectedParamType, superTypeRegistry)) {
                        checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                "Lambda parameter '" + p.getName() + "' expects values of type '"
                                        + expectedParamType.toDisplayString() + "' but is declared as '"
                                        + explicitType.toDisplayString() + "'",
                                p);
                    }
                    NovaType parameterType = explicitType != null
                            ? explicitType
                            : (expectedParamType != null ? expectedParamType : NovaTypes.ANY);
                    parameterTypes.add(parameterType);
                    String pType = parameterType != null ? parameterType.toDisplayString() : null;
                    Symbol pSym = new Symbol(p.getName(), SymbolKind.PARAMETER, pType,
                            false, node.getLocation(), node, Modifier.PUBLIC);
                    pSym.setResolvedNovaType(parameterType);
                    lambdaScope.define(pSym);
                }
            } else {
                int expectedParamCount = expectedType != null ? expectedType.getParamTypes().size() : 1;
                boolean implicitItAllowed = expectedType == null || expectedParamCount >= 1;
                if (expectedType != null && !expectedType.hasReceiverType() && expectedParamCount > 1) {
                    checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "Lambda parameter count mismatch: expected " + expectedParamCount
                                    + " but implicit 'it' form provides 1",
                            node);
                } else if (expectedType != null && expectedType.hasReceiverType() && expectedParamCount > 1) {
                    checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                            "Lambda parameter count mismatch: expected " + expectedParamCount
                                    + " but implicit form supports at most one value parameter",
                            node);
                }
                if (expectedType != null) {
                    parameterTypes.addAll(expectedType.getParamTypes());
                } else {
                    parameterTypes.add(NovaTypes.ANY);
                }
                if (implicitItAllowed) {
                    NovaType implicitType = expectedType != null && !expectedType.getParamTypes().isEmpty()
                            ? expectedType.getParamTypes().get(0)
                            : NovaTypes.ANY;
                    Symbol itSymbol = new Symbol("it", SymbolKind.PARAMETER,
                            implicitType != null ? implicitType.toDisplayString() : null,
                            false, node.getLocation(), node, Modifier.PUBLIC);
                    itSymbol.setResolvedNovaType(implicitType);
                    lambdaScope.define(itSymbol);
                }
            }
            if (node.getBody() != null) node.getBody().accept(this, ctx);
            NovaType returnType = inferLambdaResultType(node);
            if (returnType == null
                    && expectedType != null
                    && expectedType.hasReceiverType()
                    && expectedType.getReturnType() != null
                    && node.getBody() instanceof Block
                    && hasTrailingExpression((Block) node.getBody())) {
                returnType = expectedType.getReturnType();
            }
            if (returnType == null) {
                returnType = NovaTypes.UNIT;
            }
            if (expectedType != null && expectedType.getReturnType() != null
                    && !TypeCompatibility.isAssignable(expectedType.getReturnType(), returnType, superTypeRegistry)) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "Lambda return type mismatch: expected '" + expectedType.getReturnType().toDisplayString()
                                + "' but found '" + returnType.toDisplayString() + "'",
                        node);
            }
            setNovaType(node, new FunctionNovaType(
                    expectedType != null ? expectedType.getReceiverType() : null,
                    parameterTypes,
                    returnType,
                    false));
        } finally {
            lambdaDepth--;
            exitScope(node);
        }
        return null;
    }

    @Override
    public Void visitIfExpr(IfExpr node, Void ctx) {
        analyzeConditionExpression(node.getCondition(), java.util.Collections.<String, NovaType>emptyMap(), ctx);
        ConditionSmartCast conditionSmartCast = inferConditionSmartCast(node.getCondition());
        NovaType thenType;
        NovaType bindingNovaType = getNovaType(node.getCondition());
        AstNode thenOwner = node.getThenExpr() != null ? node.getThenExpr() : node;
        Scope thenScope = enterConditionalBranchScope(thenOwner, node.getBindingName(), bindingNovaType,
                node, conditionSmartCast.whenTrue);
        try {
            if (node.getThenExpr() != null) node.getThenExpr().accept(this, ctx);
            thenType = node.getThenExpr() != null ? getNovaType(node.getThenExpr()) : null;
        } finally {
            if (thenScope != null) {
                exitScope(thenOwner);
            }
        }
        NovaType elseType = null;
        if (node.getElseExpr() != null) {
            AstNode elseOwner = node.getElseExpr();
            Scope elseScope = enterConditionalBranchScope(elseOwner, null, null, node,
                    conditionSmartCast.whenFalse);
            try {
                node.getElseExpr().accept(this, ctx);
                elseType = getNovaType(node.getElseExpr());
            } finally {
                if (elseScope != null) {
                    exitScope(elseOwner);
                }
            }
        }
        NovaType resultType = thenType;
        if (elseType != null) {
            resultType = resultType == null ? elseType : unifier.commonSuperType(resultType, elseType);
        }
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitWhenExpr(WhenExpr node, Void ctx) {
        if (node.getSubject() != null) node.getSubject().accept(this, ctx);
        NovaType resultType = null;
        if (node.getBindingName() != null) {
            Scope whenScope = enterScope(Scope.ScopeType.BLOCK, node);
            NovaType subjectNovaType = node.getSubject() != null ? getNovaType(node.getSubject()) : null;
            String bindingType = subjectNovaType != null ? subjectNovaType.toDisplayString() : null;
            Symbol bindingSym = new Symbol(node.getBindingName(), SymbolKind.VARIABLE, bindingType,
                    false, node.getLocation(), node, Modifier.PUBLIC);
            bindingSym.setResolvedNovaType(subjectNovaType);
            whenScope.define(bindingSym);
            for (WhenBranch branch : node.getBranches()) {
                analyzeWhenBranch(branch, node.getSubject(), node.getBindingName(), ctx);
                NovaType branchType = inferStatementResultType(branch.getBody());
                resultType = resultType == null ? branchType : unifier.commonSuperType(resultType, branchType);
            }
            if (node.getElseExpr() != null) {
                node.getElseExpr().accept(this, ctx);
                NovaType elseType = getNovaType(node.getElseExpr());
                resultType = resultType == null ? elseType : unifier.commonSuperType(resultType, elseType);
            }
            exitScope(node);
        } else {
            for (WhenBranch branch : node.getBranches()) {
                analyzeWhenBranch(branch, node.getSubject(), null, ctx);
                NovaType branchType = inferStatementResultType(branch.getBody());
                resultType = resultType == null ? branchType : unifier.commonSuperType(resultType, branchType);
            }
            if (node.getElseExpr() != null) {
                node.getElseExpr().accept(this, ctx);
                NovaType elseType = getNovaType(node.getElseExpr());
                resultType = resultType == null ? elseType : unifier.commonSuperType(resultType, elseType);
            }
        }
        checkWhenEnumExhaustiveness(node);
        checkWhenSealedExhaustiveness(node);
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitTryExpr(TryExpr node, Void ctx) {
        NovaType resultType = null;
        if (node.getTryBlock() != null) {
            node.getTryBlock().accept(this, ctx);
            resultType = inferBlockResultType(node.getTryBlock());
        }
        for (CatchClause cc : node.getCatchClauses()) {
            Scope catchScope = enterScope(Scope.ScopeType.BLOCK, cc);
            String excType = cc.getParamType() != null ? resolveTypeName(cc.getParamType()) : "Exception";
            Symbol catchSymbol = new Symbol(cc.getParamName(), SymbolKind.VARIABLE, excType,
                    false, cc.getLocation(), cc, Modifier.PUBLIC);
            catchSymbol.setResolvedNovaType(inference.resolveNovaTypeFromName(excType));
            catchScope.define(catchSymbol);
            if (cc.getBody() != null) cc.getBody().accept(this, ctx);
            NovaType catchType = inferBlockResultType(cc.getBody());
            resultType = resultType == null ? catchType : unifier.commonSuperType(resultType, catchType);
            exitScope(cc);
        }
        if (node.getFinallyBlock() != null) node.getFinallyBlock().accept(this, ctx);
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitAwaitExpr(AwaitExpr node, Void ctx) {
        if (node.getOperand() != null) node.getOperand().accept(this, ctx);
        NovaType operandType = getNovaType(node.getOperand());
        if (operandType == null) return null;
        if (operandType instanceof ClassNovaType) {
            ClassNovaType classType = (ClassNovaType) operandType;
            String baseName = classType.getName();
            if (("Future".equals(baseName) || "Deferred".equals(baseName)) &&
                    classType.hasTypeArgs() && !classType.getTypeArgs().isEmpty()) {
                setNovaType(node, classType.getTypeArgs().get(0).getType());
                return null;
            }
            if ("Future".equals(baseName) || "Deferred".equals(baseName)) {
                setNovaType(node, NovaTypes.ANY);
                return null;
            }
        }
        setNovaType(node, operandType);
        return null;
    }

    @Override
    public Void visitCollectionLiteral(CollectionLiteral node, Void ctx) {
        switch (node.getKind()) {
            case LIST:
                NovaType listElementType = null;
                for (Expression elem : node.getElements()) {
                    elem.accept(this, ctx);
                    NovaType elemType = getNovaType(elem);
                    listElementType = listElementType == null ? elemType : unifier.commonSuperType(listElementType, elemType);
                }
                setNovaType(node, NovaTypes.listOf(listElementType != null ? listElementType : NovaTypes.ANY));
                break;
            case SET:
                NovaType setElementType = null;
                for (Expression elem : node.getElements()) {
                    elem.accept(this, ctx);
                    NovaType elemType = getNovaType(elem);
                    setElementType = setElementType == null ? elemType : unifier.commonSuperType(setElementType, elemType);
                }
                setNovaType(node, NovaTypes.setOf(setElementType != null ? setElementType : NovaTypes.ANY));
                break;
            case MAP:
                NovaType keyType = null;
                NovaType valueType = null;
                for (CollectionLiteral.MapEntry entry : node.getMapEntries()) {
                    entry.getKey().accept(this, ctx);
                    entry.getValue().accept(this, ctx);
                    NovaType entryKeyType = getNovaType(entry.getKey());
                    NovaType entryValueType = getNovaType(entry.getValue());
                    keyType = keyType == null ? entryKeyType : unifier.commonSuperType(keyType, entryKeyType);
                    valueType = valueType == null ? entryValueType : unifier.commonSuperType(valueType, entryValueType);
                }
                setNovaType(node, NovaTypes.mapOf(
                        keyType != null ? keyType : NovaTypes.ANY,
                        valueType != null ? valueType : NovaTypes.ANY));
                break;
        }
        return null;
    }

    @Override
    public Void visitRangeExpr(RangeExpr node, Void ctx) {
        node.getStart().accept(this, ctx);
        node.getEnd().accept(this, ctx);
        setNovaType(node, new ClassNovaType("Range", false));
        return null;
    }

    @Override
    public Void visitStringInterpolation(StringInterpolation node, Void ctx) {
        for (StringInterpolation.StringPart part : node.getParts()) {
            if (part instanceof StringInterpolation.ExprPart) {
                ((StringInterpolation.ExprPart) part).getExpression().accept(this, ctx);
            }
        }
        setNovaType(node, NovaTypes.STRING);
        return null;
    }

    @Override
    public Void visitTypeCheckExpr(TypeCheckExpr node, Void ctx) {
        node.getOperand().accept(this, ctx);
        validateTypeRef(node.getTargetType(), node);
        setNovaType(node, NovaTypes.BOOLEAN);
        return null;
    }

    @Override
    public Void visitTypeCastExpr(TypeCastExpr node, Void ctx) {
        node.getOperand().accept(this, ctx);
        validateTypeRef(node.getTargetType(), node);
        NovaType targetType = typeResolver.resolve(node.getTargetType());
        if (targetType != null && node.isSafe()) {
            targetType = targetType.withNullable(true);
        }
        setNovaType(node, targetType);
        return null;
    }

    @Override
    public Void visitSliceExpr(SliceExpr node, Void ctx) {
        if (node.getTarget() != null) node.getTarget().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitSpreadExpr(SpreadExpr node, Void ctx) {
        if (node.getOperand() != null) node.getOperand().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitPipelineExpr(PipelineExpr node, Void ctx) {
        node.getLeft().accept(this, ctx);
        node.getRight().accept(this, ctx);
        setNovaType(node, getNovaType(node.getRight()));
        return null;
    }

    @Override
    public Void visitBlockExpr(BlockExpr node, Void ctx) {
        if (node.getStatements() != null) {
            for (Statement statement : node.getStatements()) {
                if (statement != null) {
                    statement.accept(this, ctx);
                }
            }
        }
        if (node.getResult() != null) {
            node.getResult().accept(this, ctx);
            setNovaType(node, getNovaType(node.getResult()));
        } else {
            setNovaType(node, NovaTypes.UNIT);
        }
        return null;
    }

    @Override
    public Void visitMethodRefExpr(MethodRefExpr node, Void ctx) {
        if (node.getTarget() != null) {
            node.getTarget().accept(this, ctx);
        }
        if (node.getTypeTarget() != null) {
            validateTypeRef(node.getTypeTarget(), node);
        }

        FunctionNovaType methodRefType = null;
        FunctionNovaType expectedType = expectedFunctionType(node);
        if (!node.hasTarget()) {
            Symbol callable = currentScope.resolve(node.getMethodName());
            methodRefType = functionTypeFromCallableSymbol(callable, null);
        } else {
            NovaType typeTarget = resolveMethodReferenceTypeTarget(node);
            if (typeTarget != null) {
                if (node.isConstructor() && typeTarget instanceof JavaClassNovaType) {
                    JavaTypeDescriptor descriptor = ((JavaClassNovaType) typeTarget).getDescriptor();
                    JavaTypeDescriptor.JavaExecutableDescriptor ctor = null;
                    if (descriptor != null && expectedType != null) {
                        ctor = descriptor.resolveConstructor(expectedType.getParamTypes());
                    } else if (descriptor != null) {
                        java.util.List<JavaTypeDescriptor.JavaExecutableDescriptor> overloads = descriptor.constructorOverloads();
                        if (overloads.size() == 1) {
                            ctor = overloads.get(0);
                        } else if (overloads.size() > 1) {
                            checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                                    "Ambiguous Java constructor reference requires an expected function type",
                                    node);
                        }
                    }
                    methodRefType = javaExecutableToFunctionType(null, ctor, true);
                } else {
                    FunctionNovaType javaMemberReference =
                            inferJavaMemberReferenceType(typeTarget, node.getMethodName(), false, expectedType, node);
                    methodRefType = javaMemberReference != null
                            ? javaMemberReference
                            : inferMemberReferenceType(typeTarget, node.getMethodName(), false);
                }
            } else {
                NovaType boundTargetType = getNovaType(node.getTarget());
                if (boundTargetType != null) {
                    FunctionNovaType javaMemberReference =
                            inferJavaMemberReferenceType(boundTargetType, node.getMethodName(), true, expectedType, node);
                    methodRefType = javaMemberReference != null
                            ? javaMemberReference
                            : inferMemberReferenceType(boundTargetType, node.getMethodName(), true);
                }
            }
        }
        setNovaType(node, methodRefType);
        return null;
    }

    @Override
    public Void visitObjectLiteralExpr(ObjectLiteralExpr node, Void ctx) {
        NovaType resultType = NovaTypes.ANY;
        if (node.getSuperTypes() != null) {
            for (TypeRef superType : node.getSuperTypes()) {
                validateTypeRef(superType, node);
            }
        }
        if (node.getSuperConstructorArgs() != null) {
            for (Expression arg : node.getSuperConstructorArgs()) {
                if (arg != null) arg.accept(this, ctx);
            }
        }
        if (node.getSuperTypes() != null && !node.getSuperTypes().isEmpty()) {
            NovaType resolvedSuperType = typeResolver.resolve(node.getSuperTypes().get(0));
            if (resolvedSuperType != null) {
                resultType = resolvedSuperType;
            }
        }

        Scope objectScope = enterScope(Scope.ScopeType.CLASS, node);
        objectScope.setOwnerTypeName(resultType.getTypeName() != null ? resultType.getTypeName() : "Any");
        Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE, resultType.toDisplayString(),
                false, node.getLocation(), node, Modifier.PUBLIC);
        thisSym.setResolvedNovaType(resultType);
        objectScope.define(thisSym);
        Symbol anonymousSymbol = anonymousObjectSymbol(node, resultType);
        try {
            if (node.getMembers() != null) {
                for (Declaration member : node.getMembers()) {
                    member.accept(this, ctx);
                    if (member.getName() != null) {
                        Symbol memberSymbol = objectScope.resolveLocal(member.getName());
                        if (memberSymbol != null) {
                            anonymousSymbol.addMember(memberSymbol);
                        }
                    }
                }
            }
        } finally {
            exitScope(node);
        }
        anonymousObjectSymbols.put(node, anonymousSymbol);
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitPlaceholderExpr(PlaceholderExpr node, Void ctx) {
        return null;
    }

    @Override
    public Void visitElvisExpr(ElvisExpr node, Void ctx) {
        node.getLeft().accept(this, ctx);
        node.getRight().accept(this, ctx);
        NovaType leftNovaType = getNovaType(node.getLeft());
        NovaType rightNovaType = getNovaType(node.getRight());
        NovaType resultType = rightNovaType;
        if (leftNovaType != null) {
            NovaType leftNonNullType = leftNovaType.withNullable(false);
            resultType = rightNovaType == null ? leftNonNullType : unifier.commonSuperType(leftNonNullType, rightNovaType);
        }
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitSafeCallExpr(SafeCallExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        if (node.getArgs() != null) {
            for (CallExpr.Argument argument : node.getArgs()) {
                if (argument.getValue() != null) {
                    argument.getValue().accept(this, ctx);
                }
            }
        }

        NovaType receiverType = getNovaType(node.getTarget());
        if (receiverType == null) return null;
        if (NovaTypes.isDynamicType(receiverType)) {
            setNovaType(node, NovaTypes.DYNAMIC.withNullable(true));
            return null;
        }

        NovaType nonNullReceiverType = receiverType.withNullable(false);
        NovaType resultType = null;

        NovaType registryMemberType = inference.lookupMemberType(nonNullReceiverType, node.getMember());
        if (registryMemberType != null) {
            resultType = registryMemberType;
        }

        Symbol receiverSym = resolveTypeSymbol(nonNullReceiverType.getTypeName());
        if (receiverSym != null && receiverSym.getMembers() != null) {
            Symbol member = receiverSym.getMembers().get(node.getMember());
            if (member != null) {
                NovaType memberType = member.getResolvedNovaType();
                if (memberType == null && member.getTypeName() != null) {
                    memberType = inference.resolveNovaTypeFromName(member.getTypeName());
                }
                if (memberType != null) {
                    resultType = memberType;
                }
            }
        }

        if (resultType != null) {
            setNovaType(node, resultType.withNullable(true));
        }
        return null;
    }

    @Override
    public Void visitSafeIndexExpr(SafeIndexExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        node.getIndex().accept(this, ctx);
        NovaType targetType = getNovaType(node.getTarget());
        if (targetType != null) {
            if (NovaTypes.isDynamicType(targetType)) {
                setNovaType(node, NovaTypes.DYNAMIC.withNullable(true));
                return null;
            }
            NovaType elementType = inference.inferElementNovaType(targetType.withNullable(false));
            if (elementType != null) {
                setNovaType(node, elementType.withNullable(true));
            }
        }
        return null;
    }

    @Override
    public Void visitNotNullExpr(NotNullExpr node, Void ctx) {
        node.getOperand().accept(this, ctx);
        NovaType operandNovaType = getNovaType(node.getOperand());
        if (operandNovaType != null) {
            setNovaType(node, operandNovaType.withNullable(false));
        }
        return null;
    }

    @Override
    public Void visitErrorPropagationExpr(ErrorPropagationExpr node, Void ctx) {
        node.getOperand().accept(this, ctx);
        NovaType operandType = getNovaType(node.getOperand());
        NovaType resultType = operandType;
        if (operandType instanceof ClassNovaType) {
            ClassNovaType classType = (ClassNovaType) operandType;
            if ("Result".equals(baseType(classType.getTypeName()))) {
                if (classType.hasTypeArgs() && !classType.getTypeArgs().isEmpty()
                        && classType.getTypeArgs().get(0).getType() != null) {
                    resultType = classType.getTypeArgs().get(0).getType();
                } else {
                    resultType = NovaTypes.ANY;
                }
            } else {
                resultType = operandType.withNullable(false);
            }
        } else if (operandType != null) {
            resultType = operandType.withNullable(false);
        }
        setNovaType(node, resultType);
        return null;
    }

    @Override
    public Void visitScopeShorthandExpr(ScopeShorthandExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        NovaType targetType = getNovaType(node.getTarget());
        if (targetType == null) return null;

        NovaType receiverType = targetType.withNullable(false);
        Scope receiverScope = enterScope(Scope.ScopeType.BLOCK, node);
        Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE, receiverType.toDisplayString(),
                false, node.getLocation(), node, Modifier.PUBLIC);
        thisSym.setResolvedNovaType(receiverType);
        receiverScope.define(thisSym);
        defineReceiverMembers(receiverScope, receiverType, node);
        try {
            if (node.getBlock() != null) {
                node.getBlock().accept(this, ctx);
            }
        } finally {
            exitScope(node);
        }
        setNovaType(node, targetType);
        return null;
    }

    @Override
    public Void visitJumpExpr(JumpExpr node, Void ctx) {
        if (node.getStatement() != null) {
            node.getStatement().accept(this, ctx);
        }
        setNovaType(node, NovaTypes.NOTHING);
        return null;
    }

    // ============ 类型 visitor ============

    @Override
    public Void visitSimpleType(SimpleType node, Void ctx) { return null; }
    @Override
    public Void visitNullableType(NullableType node, Void ctx) { return null; }
    @Override
    public Void visitFunctionType(FunctionType node, Void ctx) { return null; }
    @Override
    public Void visitGenericType(GenericType node, Void ctx) { return null; }
}
