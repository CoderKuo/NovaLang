package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;
import nova.runtime.NovaTypeRegistry;
import nova.runtime.stdlib.StdlibRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final Map<Expression, NovaType> exprNovaTypeMap = new HashMap<Expression, NovaType>();

    // 委托
    private final TypeUnifier unifier;
    private final TypeInferenceEngine inference;
    private final SemanticChecker checker;

    public SemanticAnalyzer() {
        this.symbolTable = new SymbolTable();
        this.currentScope = symbolTable.getGlobalScope();
        this.unifier = new TypeUnifier(exprNovaTypeMap, superTypeRegistry, typeResolver);
        this.inference = new TypeInferenceEngine(exprNovaTypeMap, unifier);
        this.checker = new SemanticChecker(diagnostics, superTypeRegistry, exprNovaTypeMap);
        registerBuiltins();
    }

    /** 分析入口 */
    public AnalysisResult analyze(Program program) {
        program.accept(this, null);
        return new AnalysisResult(symbolTable, diagnostics, exprNovaTypeMap);
    }

    /**
     * 分析 Program 及顶层语句（容错解析时，非声明语句存在于 ParseResult.topLevelStatements）
     */
    public AnalysisResult analyze(Program program, List<Statement> topLevelStatements) {
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
        return null;
    }

    @Override
    public Void visitImportDecl(ImportDecl node, Void ctx) {
        String name = node.hasAlias() ? node.getAlias() : node.getName().getSimpleName();
        if (name != null && !node.isWildcard()) {
            Symbol sym = new Symbol(name, SymbolKind.IMPORT, null, false,
                    node.getLocation(), node, Modifier.PUBLIC);
            currentScope.define(sym);
        }
        return null;
    }

    @Override
    public Void visitPropertyDecl(PropertyDecl node, Void ctx) {
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

        // 先分析初始化器
        if (node.getInitializer() != null) {
            node.getInitializer().accept(this, ctx);
        }

        String typeName = resolveTypeName(node.getType());
        NovaType declaredNovaType = typeResolver.resolve(node.getType());
        if (typeName == null && node.getInitializer() != null) {
            NovaType initNovaType = getNovaType(node.getInitializer());
            if (initNovaType != null) typeName = initNovaType.toDisplayString();
            if (declaredNovaType == null) {
                declaredNovaType = initNovaType;
            }
        }

        SymbolKind kind = (currentScope.getType() == Scope.ScopeType.CLASS ||
                           currentScope.getType() == Scope.ScopeType.ENUM)
                ? SymbolKind.PROPERTY : SymbolKind.VARIABLE;

        checker.checkRedefinition(currentScope, node.getName(), node);
        Symbol sym = new Symbol(node.getName(), kind,
                typeName, !node.isVal(), node.getLocation(), node, extractVisibility(node.getModifiers()));
        sym.setResolvedNovaType(declaredNovaType);
        currentScope.define(sym);

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
        String returnType = resolveTypeName(node.getReturnType());

        // 进入泛型类型参数作用域
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            typeResolver.enterTypeParams(node.getTypeParams());
        }

        NovaType returnNovaType = typeResolver.resolve(node.getReturnType());

        if (!node.isExtensionFunction()) {
            checker.checkRedefinition(currentScope, node.getName(), node);
            Symbol funSym = new Symbol(node.getName(), SymbolKind.FUNCTION,
                    returnType, false, node.getLocation(), node, extractVisibility(node.getModifiers()));
            funSym.setResolvedNovaType(returnNovaType);
            List<Symbol> paramSymbols = buildParamSymbols(node.getParams());
            funSym.setParameters(paramSymbols);
            currentScope.define(funSym);
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
        checker.checkRedefinition(currentScope, node.getName(), node);
        Symbol classSym = new Symbol(node.getName(), SymbolKind.CLASS,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        classSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.define(classSym);

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
                        !p.hasModifier(Modifier.FINAL) && !isProperty, p.getLocation(), p, extractVisibility(p.getModifiers()));
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
                Symbol memberSym = classScope.resolveLocal(member.getName());
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
        checker.checkRedefinition(currentScope, node.getName(), node);
        Symbol ifSym = new Symbol(node.getName(), SymbolKind.INTERFACE,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        ifSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.define(ifSym);

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
        superTypeRegistry.registerClass(node.getName(), null, superInterfaces);

        Scope ifScope = enterScope(Scope.ScopeType.CLASS, node);
        ifScope.setOwnerTypeName(node.getName());

        for (Declaration member : node.getMembers()) {
            member.accept(this, ctx);
            if (member.getName() != null) {
                Symbol memberSym = ifScope.resolveLocal(member.getName());
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
        Symbol objSym = new Symbol(node.getName(), SymbolKind.OBJECT,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        objSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.define(objSym);

        Scope objScope = enterScope(Scope.ScopeType.CLASS, node);
        objScope.setOwnerTypeName(node.getName());

        Symbol thisSym = new Symbol("this", SymbolKind.VARIABLE, node.getName(),
                false, node.getLocation(), node, Modifier.PUBLIC);
        thisSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        objScope.define(thisSym);

        for (Declaration member : node.getMembers()) {
            member.accept(this, ctx);
            if (member.getName() != null) {
                Symbol memberSym = objScope.resolveLocal(member.getName());
                if (memberSym != null) objSym.addMember(memberSym);
            }
        }
        exitScope(node);
        return null;
    }

    @Override
    public Void visitEnumDecl(EnumDecl node, Void ctx) {
        checker.checkRedefinition(currentScope, node.getName(), node);
        Symbol enumSym = new Symbol(node.getName(), SymbolKind.ENUM,
                node.getName(), false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        enumSym.setResolvedNovaType(new ClassNovaType(node.getName(), false));
        currentScope.define(enumSym);

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
                Symbol memberSym = enumScope.resolveLocal(member.getName());
                if (memberSym != null) enumSym.addMember(memberSym);
            }
        }
        exitScope(node);
        return null;
    }

    @Override
    public Void visitTypeAliasDecl(TypeAliasDecl node, Void ctx) {
        String targetType = resolveTypeName(node.getAliasedType());
        checker.checkRedefinition(currentScope, node.getName(), node);
        Symbol sym = new Symbol(node.getName(), SymbolKind.TYPE_ALIAS,
                targetType, false, node.getLocation(), node, extractVisibility(node.getModifiers()));
        sym.setResolvedNovaType(typeResolver.resolve(node.getAliasedType()));
        currentScope.define(sym);
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
        for (String name : node.getNames()) {
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
        node.getCondition().accept(this, ctx);
        if (node.getBindingName() != null) {
            Scope ifScope = enterScope(Scope.ScopeType.BLOCK, node);
            ifScope.define(new Symbol(node.getBindingName(), SymbolKind.VARIABLE, null,
                    false, node.getLocation(), node, Modifier.PUBLIC));
            if (node.getThenBranch() != null) node.getThenBranch().accept(this, ctx);
            exitScope(node);
        } else {
            if (node.getThenBranch() != null) node.getThenBranch().accept(this, ctx);
        }
        if (node.getElseBranch() != null) node.getElseBranch().accept(this, ctx);
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
                if (branch.getBody() != null) branch.getBody().accept(this, ctx);
            }
            if (node.getElseBranch() != null) node.getElseBranch().accept(this, ctx);
            exitScope(node);
        } else {
            for (WhenBranch branch : node.getBranches()) {
                if (branch.getBody() != null) branch.getBody().accept(this, ctx);
            }
            if (node.getElseBranch() != null) node.getElseBranch().accept(this, ctx);
        }
        return null;
    }

    @Override
    public Void visitForStmt(ForStmt node, Void ctx) {
        node.getIterable().accept(this, ctx);
        Scope forScope = enterScope(Scope.ScopeType.BLOCK, node);

        NovaType iterableNovaType = getNovaType(node.getIterable());
        NovaType elemNovaType = inference.inferElementNovaType(iterableNovaType);
        String elemType = elemNovaType != null ? elemNovaType.toDisplayString() : null;

        for (String varName : node.getVariables()) {
            if (varName != null && !"_".equals(varName)) {
                Symbol varSym = new Symbol(varName, SymbolKind.VARIABLE, elemType,
                        false, node.getLocation(), node, Modifier.PUBLIC);
                varSym.setResolvedNovaType(elemNovaType);
                forScope.define(varSym);
            }
        }
        if (node.getBody() != null) node.getBody().accept(this, ctx);
        exitScope(node);
        return null;
    }

    @Override
    public Void visitWhileStmt(WhileStmt node, Void ctx) {
        node.getCondition().accept(this, ctx);
        if (node.getBody() != null) node.getBody().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitDoWhileStmt(DoWhileStmt node, Void ctx) {
        if (node.getBody() != null) node.getBody().accept(this, ctx);
        node.getCondition().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitTryStmt(TryStmt node, Void ctx) {
        if (node.getTryBlock() != null) node.getTryBlock().accept(this, ctx);
        for (CatchClause cc : node.getCatchClauses()) {
            Scope catchScope = enterScope(Scope.ScopeType.BLOCK, cc);
            String excType = cc.getParamType() != null ? resolveTypeName(cc.getParamType()) : "Exception";
            catchScope.define(new Symbol(cc.getParamName(), SymbolKind.VARIABLE, excType,
                    false, cc.getLocation(), cc, Modifier.PUBLIC));
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
    public Void visitBreakStmt(BreakStmt node, Void ctx) { return null; }

    @Override
    public Void visitContinueStmt(ContinueStmt node, Void ctx) { return null; }

    @Override
    public Void visitThrowStmt(ThrowStmt node, Void ctx) {
        if (node.getException() != null) node.getException().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitGuardStmt(GuardStmt node, Void ctx) {
        if (node.getExpression() != null) node.getExpression().accept(this, ctx);
        if (node.getElseBody() != null) node.getElseBody().accept(this, ctx);
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
            case NULL:    novaType = NovaTypes.NOTHING_NULLABLE; break;
            default:      novaType = null;
        }
        setNovaType(node, novaType);
        return null;
    }

    @Override
    public Void visitIdentifier(Identifier node, Void ctx) {
        Symbol sym = currentScope.resolve(node.getName());
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
        String enclosingType = currentScope.getEnclosingTypeName();
        if (enclosingType != null) {
            setNovaType(node, new ClassNovaType(enclosingType, false));
        }
        return null;
    }

    @Override
    public Void visitSuperExpr(SuperExpr node, Void ctx) {
        return null;
    }

    @Override
    public Void visitCallExpr(CallExpr node, Void ctx) {
        node.getCallee().accept(this, ctx);
        for (CallExpr.Argument arg : node.getArgs()) {
            arg.getValue().accept(this, ctx);
        }
        if (node.getTrailingLambda() != null) {
            node.getTrailingLambda().accept(this, ctx);
        }

        // 推断调用返回类型
        if (node.getCallee() instanceof Identifier) {
            String funcName = ((Identifier) node.getCallee()).getName();
            Symbol funcSym = currentScope.resolve(funcName);
            if (funcSym != null) {
                if (funcSym.getKind() == SymbolKind.FUNCTION || funcSym.getKind() == SymbolKind.BUILTIN_FUNCTION) {
                    NovaType retType = funcSym.getResolvedNovaType();
                    if (retType == null && funcSym.getTypeName() != null) {
                        retType = inference.resolveNovaTypeFromName(funcSym.getTypeName());
                    }
                    NovaType inferred = unifier.inferGenericReturnType(funcSym, node.getArgs());
                    if (inferred != null) retType = inferred;
                    setNovaType(node, retType);
                    checker.checkCallArgCount(node, funcSym);
                    checker.checkCallArgTypes(node, funcSym);
                } else if (funcSym.getKind() == SymbolKind.CLASS || funcSym.getKind() == SymbolKind.ENUM) {
                    NovaType ctorType = unifier.inferGenericConstructorType(funcSym, node.getArgs());
                    if (ctorType != null) {
                        setNovaType(node, ctorType);
                    } else {
                        setNovaType(node, new ClassNovaType(funcSym.getName(), false));
                    }
                }
            }
            // 集合工厂函数泛型推断
            NovaType collType = inference.inferCollectionFactoryType(funcName, node.getArgs());
            if (collType != null) {
                setNovaType(node, collType);
            }
            // stdlib Supplier Lambda 函数类型推导
            StdlibRegistry.SupplierLambdaInfo slInfo = StdlibRegistry.getSupplierLambda(funcName);
            if (slInfo != null) {
                setNovaType(node, inference.resolveNovaTypeFromName(slInfo.novaReturnType));
            }
        } else if (node.getCallee() instanceof MemberExpr) {
            NovaType calleeNovaType = getNovaType(node.getCallee());
            if (calleeNovaType != null) {
                setNovaType(node, calleeNovaType);
            }
        }
        return null;
    }

    @Override
    public Void visitMemberExpr(MemberExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        NovaType receiverNovaType = getNovaType(node.getTarget());
        if (receiverNovaType != null) {
            String baseName = receiverNovaType.getTypeName();
            String returnType = inference.lookupMemberType(baseName, node.getMember());
            if (returnType != null) {
                setNovaType(node, inference.resolveNovaTypeFromName(returnType));
            }
            Symbol receiverSym = currentScope.resolve(baseName);
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

        if (node.getTarget() instanceof Identifier) {
            String name = ((Identifier) node.getTarget()).getName();
            Symbol sym = currentScope.resolve(name);
            if (sym != null && !sym.isMutable() &&
                    (sym.getKind() == SymbolKind.VARIABLE || sym.getKind() == SymbolKind.PROPERTY)) {
                checker.addDiagnostic(SemanticDiagnostic.Severity.ERROR,
                        "不可重新赋值: '" + name + "' 是 val（不可变变量）", node);
            }
            if (sym != null && sym.getResolvedNovaType() != null) {
                NovaType valueType = getNovaType(node.getValue());
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
                if (NovaTypes.STRING.equals(leftType) || NovaTypes.STRING.equals(rightType)) {
                    resultType = NovaTypes.STRING;
                } else if (NovaTypes.isNumericType(leftType) && NovaTypes.isNumericType(rightType)) {
                    resultType = NovaTypes.promoteNumeric(leftType, rightType);
                }
                break;
            case SUB: case MUL: case DIV: case MOD:
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
        Scope lambdaScope = enterScope(Scope.ScopeType.LAMBDA, node);
        if (node.hasExplicitParams()) {
            for (LambdaExpr.LambdaParam p : node.getParams()) {
                checker.checkRedefinition(lambdaScope, p.getName(), node);
                String pType = p.hasType() ? resolveTypeName(p.getType()) : null;
                Symbol pSym = new Symbol(p.getName(), SymbolKind.PARAMETER, pType,
                        false, node.getLocation(), node, Modifier.PUBLIC);
                if (p.hasType()) {
                    pSym.setResolvedNovaType(typeResolver.resolve(p.getType()));
                }
                lambdaScope.define(pSym);
            }
        } else {
            lambdaScope.define(new Symbol("it", SymbolKind.PARAMETER, null,
                    false, node.getLocation(), node, Modifier.PUBLIC));
        }
        if (node.getBody() != null) node.getBody().accept(this, ctx);
        exitScope(node);
        return null;
    }

    @Override
    public Void visitIfExpr(IfExpr node, Void ctx) {
        node.getCondition().accept(this, ctx);
        if (node.getThenExpr() != null) node.getThenExpr().accept(this, ctx);
        if (node.getElseExpr() != null) node.getElseExpr().accept(this, ctx);
        setNovaType(node, node.getThenExpr() != null ? getNovaType(node.getThenExpr()) : null);
        return null;
    }

    @Override
    public Void visitWhenExpr(WhenExpr node, Void ctx) {
        if (node.getSubject() != null) node.getSubject().accept(this, ctx);
        for (WhenBranch branch : node.getBranches()) {
            if (branch.getBody() != null) branch.getBody().accept(this, ctx);
        }
        if (node.getElseExpr() != null) node.getElseExpr().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitTryExpr(TryExpr node, Void ctx) {
        if (node.getTryBlock() != null) node.getTryBlock().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitAwaitExpr(AwaitExpr node, Void ctx) {
        if (node.getOperand() != null) node.getOperand().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitCollectionLiteral(CollectionLiteral node, Void ctx) {
        switch (node.getKind()) {
            case LIST:
                for (Expression elem : node.getElements()) elem.accept(this, ctx);
                setNovaType(node, new ClassNovaType("List", false));
                break;
            case SET:
                for (Expression elem : node.getElements()) elem.accept(this, ctx);
                setNovaType(node, new ClassNovaType("Set", false));
                break;
            case MAP:
                for (CollectionLiteral.MapEntry entry : node.getMapEntries()) {
                    entry.getKey().accept(this, ctx);
                    entry.getValue().accept(this, ctx);
                }
                setNovaType(node, new ClassNovaType("Map", false));
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
        setNovaType(node, NovaTypes.BOOLEAN);
        return null;
    }

    @Override
    public Void visitTypeCastExpr(TypeCastExpr node, Void ctx) {
        node.getOperand().accept(this, ctx);
        setNovaType(node, typeResolver.resolve(node.getTargetType()));
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
    public Void visitMethodRefExpr(MethodRefExpr node, Void ctx) {
        return null;
    }

    @Override
    public Void visitObjectLiteralExpr(ObjectLiteralExpr node, Void ctx) {
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
        if (leftNovaType != null) {
            setNovaType(node, leftNovaType.withNullable(false));
        }
        return null;
    }

    @Override
    public Void visitSafeCallExpr(SafeCallExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        return null;
    }

    @Override
    public Void visitSafeIndexExpr(SafeIndexExpr node, Void ctx) {
        node.getTarget().accept(this, ctx);
        node.getIndex().accept(this, ctx);
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
        return null;
    }

    @Override
    public Void visitScopeShorthandExpr(ScopeShorthandExpr node, Void ctx) {
        return null;
    }

    @Override
    public Void visitJumpExpr(JumpExpr node, Void ctx) {
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
