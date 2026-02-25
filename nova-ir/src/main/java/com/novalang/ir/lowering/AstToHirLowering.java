package com.novalang.ir.lowering;

import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.expr.BinaryExpr.BinaryOp;
import com.novalang.compiler.ast.expr.Literal.LiteralKind;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;
import com.novalang.compiler.ast.type.FunctionType;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.compiler.hirtype.*;

import java.util.*;

/**
 * AST → HIR 降级（Lowering）。
 * 实现 AstVisitor，将 92 种 AST 节点转换为 38 种 HIR 节点。
 * 包含 13 条脱糖规则。
 */
public class AstToHirLowering implements AstVisitor<AstNode, LoweringContext> {

    private boolean scriptMode = false;

    public void setScriptMode(boolean scriptMode) {
        this.scriptMode = scriptMode;
    }

    // ========== 公共入口 ==========

    /**
     * 将 AST 程序降级为 HIR 模块。
     */
    public HirModule lower(Program program) {
        LoweringContext ctx = new LoweringContext();
        return (HirModule) program.accept(this, ctx);
    }

    // ========== 辅助方法 ==========

    private Expression lowerExpr(Expression expr, LoweringContext ctx) {
        if (expr == null) return null;
        return (Expression) expr.accept(this, ctx);
    }

    private Statement lowerStmt(Statement stmt, LoweringContext ctx) {
        if (stmt == null) return null;
        return (Statement) stmt.accept(this, ctx);
    }

    private HirDecl lowerDecl(Declaration decl, LoweringContext ctx) {
        if (decl == null) return null;
        return (HirDecl) decl.accept(this, ctx);
    }

    private AstNode lowerBody(AstNode body, LoweringContext ctx) {
        if (body == null) return null;
        return body.accept(this, ctx);
    }

    private List<Expression> lowerExprs(List<? extends Expression> exprs, LoweringContext ctx) {
        if (exprs == null || exprs.isEmpty()) return Collections.emptyList();
        List<Expression> result = new ArrayList<>(exprs.size());
        for (Expression e : exprs) result.add(lowerExpr(e, ctx));
        return result;
    }

    private List<Statement> lowerStmts(List<? extends Statement> stmts, LoweringContext ctx) {
        if (stmts == null || stmts.isEmpty()) return Collections.emptyList();
        List<Statement> result = new ArrayList<>(stmts.size());
        for (Statement s : stmts) result.add(lowerStmt(s, ctx));
        return result;
    }

    private List<HirDecl> lowerDecls(List<? extends Declaration> decls, LoweringContext ctx) {
        if (decls == null || decls.isEmpty()) return Collections.emptyList();
        List<HirDecl> result = new ArrayList<>(decls.size());
        for (Declaration d : decls) result.add(lowerDecl(d, ctx));
        return result;
    }

    private Set<Modifier> toModifierSet(List<Modifier> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) return Collections.emptySet();
        return EnumSet.copyOf(modifiers);
    }

    private List<HirAnnotation> lowerAnnotations(List<Annotation> annotations, LoweringContext ctx) {
        if (annotations == null || annotations.isEmpty()) return Collections.emptyList();
        List<HirAnnotation> result = new ArrayList<>(annotations.size());
        for (Annotation a : annotations) {
            Map<String, Expression> args = new LinkedHashMap<>();
            if (a.getArgs() != null) {
                for (Annotation.AnnotationArg arg : a.getArgs()) {
                    String name = arg.getName() != null ? arg.getName() : "value";
                    args.put(name, lowerExpr(arg.getValue(), ctx));
                }
            }
            result.add(new HirAnnotation(a.getLocation(), a.getName(), args));
        }
        return result;
    }

    /**
     * 将 AST TypeRef 转换为 HIR HirType。
     */
    private HirType lowerType(TypeRef typeRef) {
        if (typeRef == null) return null;
        return typeRef.accept(new TypeRefVisitor<HirType>() {
            @Override
            public HirType visitSimple(SimpleType type) {
                return resolveSimpleTypeName(type.getName().toString(), false);
            }

            @Override
            public HirType visitNullable(NullableType type) {
                HirType inner = lowerType(type.getInnerType());
                if (inner != null) return inner.withNullable(true);
                return new UnresolvedType("?", true);
            }

            @Override
            public HirType visitGeneric(GenericType gt) {
                String name = gt.getName().toString();
                List<HirType> typeArgs = new ArrayList<>();
                if (gt.getTypeArgs() != null) {
                    for (TypeArgument ta : gt.getTypeArgs()) {
                        typeArgs.add(lowerType(ta.getType()));
                    }
                }
                return new ClassType(name, typeArgs, false);
            }

            @Override
            public HirType visitFunction(FunctionType ft) {
                HirType receiver = lowerType(ft.getReceiverType());
                List<HirType> params = new ArrayList<>();
                if (ft.getParamTypes() != null) {
                    for (TypeRef p : ft.getParamTypes()) params.add(lowerType(p));
                }
                HirType ret = lowerType(ft.getReturnType());
                return new com.novalang.compiler.hirtype.FunctionType(receiver, params, ret, false);
            }
        });
    }

    private HirType resolveSimpleTypeName(String name, boolean nullable) {
        switch (name) {
            case "Int": return new PrimitiveType(PrimitiveType.Kind.INT, nullable);
            case "Long": return new PrimitiveType(PrimitiveType.Kind.LONG, nullable);
            case "Float": return new PrimitiveType(PrimitiveType.Kind.FLOAT, nullable);
            case "Double": return new PrimitiveType(PrimitiveType.Kind.DOUBLE, nullable);
            case "Boolean": return new PrimitiveType(PrimitiveType.Kind.BOOLEAN, nullable);
            case "Char": return new PrimitiveType(PrimitiveType.Kind.CHAR, nullable);
            case "Unit": return new PrimitiveType(PrimitiveType.Kind.UNIT, nullable);
            case "Nothing": return new PrimitiveType(PrimitiveType.Kind.NOTHING, nullable);
            default: return new ClassType(name, nullable);
        }
    }

    private List<String> lowerTypeParams(List<TypeParameter> typeParams) {
        if (typeParams == null || typeParams.isEmpty()) return Collections.emptyList();
        List<String> result = new ArrayList<>(typeParams.size());
        for (TypeParameter tp : typeParams) result.add(tp.getName());
        return result;
    }

    private Set<String> extractReifiedTypeParams(List<TypeParameter> typeParams) {
        if (typeParams == null || typeParams.isEmpty()) return Collections.emptySet();
        Set<String> result = new HashSet<>();
        for (TypeParameter tp : typeParams) {
            if (tp.isReified()) result.add(tp.getName());
        }
        return result.isEmpty() ? Collections.emptySet() : result;
    }

    private List<HirType> lowerSuperTypes(List<TypeRef> superTypes) {
        if (superTypes == null || superTypes.isEmpty()) return Collections.emptyList();
        List<HirType> result = new ArrayList<>(superTypes.size());
        for (TypeRef t : superTypes) result.add(lowerType(t));
        return result;
    }

    // ========== 声明 ==========

    @Override
    public AstNode visitProgram(Program node, LoweringContext ctx) {
        String pkg = node.getPackageDecl() != null ? node.getPackageDecl().getName().toString() : "";
        List<HirImport> imports = new ArrayList<>();
        for (ImportDecl imp : node.getImports()) {
            imports.add((HirImport) imp.accept(this, ctx));
        }
        List<HirDecl> decls = new ArrayList<>();
        for (Declaration d : node.getDeclarations()) {
            AstNode lowered = d.accept(this, ctx);
            if (lowered instanceof HirDecl) {
                decls.add((HirDecl) lowered);
            } else if (lowered instanceof Block) {
                // DestructuringDecl 返回 Block（包含多个 HirDeclStmt）
                for (Statement stmt : ((Block) lowered).getStatements()) {
                    if (stmt instanceof HirDeclStmt) {
                        decls.add(((HirDeclStmt) stmt).getDeclaration());
                    }
                }
            }
        }

        // 将顶层 HirField 合并到合成 main 函数体中（作为局部变量声明）
        List<HirField> topLevelFields = new ArrayList<>();
        HirFunction mainFunc = null;
        List<HirDecl> filteredDecls = new ArrayList<>();
        for (HirDecl decl : decls) {
            if (decl instanceof HirField && !((HirField) decl).isExtensionProperty()) {
                topLevelFields.add((HirField) decl);
            } else if (decl instanceof HirFunction && "main".equals(decl.getName())) {
                mainFunc = (HirFunction) decl;
            } else {
                filteredDecls.add(decl);
            }
        }

        if (!topLevelFields.isEmpty()) {
            // 将字段转为 DeclStmt
            List<Statement> fieldStmts = new ArrayList<>();
            for (HirField field : topLevelFields) {
                fieldStmts.add(new HirDeclStmt(field.getLocation(), field));
            }

            if (mainFunc != null) {
                // 将顶层字段声明与 main 函数体按源位置交织（保持原始代码顺序）
                List<Statement> mainBodyStmts = new ArrayList<>();
                if (mainFunc.getBody() instanceof Block) {
                    mainBodyStmts.addAll(((Block) mainFunc.getBody()).getStatements());
                } else if (mainFunc.getBody() != null) {
                    mainBodyStmts.add(new ExpressionStmt(mainFunc.getLocation(), (Expression) mainFunc.getBody()));
                }
                List<Statement> allStmts = new ArrayList<>(fieldStmts);
                allStmts.addAll(mainBodyStmts);
                allStmts.sort((a, b) -> {
                    SourceLocation locA = a.getLocation();
                    SourceLocation locB = b.getLocation();
                    if (locA == null && locB == null) return 0;
                    if (locA == null) return 1;
                    if (locB == null) return -1;
                    int lineCmp = Integer.compare(locA.getLine(), locB.getLine());
                    if (lineCmp != 0) return lineCmp;
                    return Integer.compare(locA.getColumn(), locB.getColumn());
                });
                Block newMainBody = new Block(mainFunc.getLocation(), allStmts);
                HirFunction newMain = new HirFunction(mainFunc.getLocation(), mainFunc.getName(),
                        mainFunc.getModifiers(), mainFunc.getAnnotations(), mainFunc.getTypeParams(),
                        mainFunc.getReceiverType(), mainFunc.getParams(), mainFunc.getReturnType(),
                        newMainBody, mainFunc.isConstructor());
                filteredDecls.add(newMain);
            } else if (scriptMode) {
                // 脚本模式（JSR-223）：合成 main 函数包含所有顶层字段声明
                SourceLocation loc = topLevelFields.get(0).getLocation();
                Block mainBody = new Block(loc, fieldStmts);
                HirFunction syntheticMain = new HirFunction(loc, "main",
                        Collections.emptySet(), Collections.emptyList(), Collections.emptyList(),
                        null, Collections.emptyList(), null,
                        mainBody, false);
                filteredDecls.add(syntheticMain);
            } else {
                // 非脚本模式：顶层字段保持原样（模块导入等场景）
                filteredDecls.addAll(topLevelFields);
            }
        } else {
            if (mainFunc != null) filteredDecls.add(mainFunc);
        }

        return new HirModule(node.getLocation(), pkg, imports, filteredDecls);
    }

    @Override
    public AstNode visitPackageDecl(PackageDecl node, LoweringContext ctx) {
        return null; // 已合并到 HirModule
    }

    @Override
    public AstNode visitImportDecl(ImportDecl node, LoweringContext ctx) {
        String qn = node.getName().toString();
        return new HirImport(node.getLocation(), qn, node.getAlias(), node.isWildcard(),
                node.isJava(), node.isStatic());
    }

    @Override
    public AstNode visitClassDecl(ClassDecl node, LoweringContext ctx) {
        ClassKind kind = ClassKind.CLASS;
        if (node.isAnnotation()) kind = ClassKind.ANNOTATION;
        else if (node.isAbstract() && node.hasModifier(Modifier.ABSTRACT)) kind = ClassKind.CLASS;

        List<HirField> fields = new ArrayList<>();
        List<HirFunction> methods = new ArrayList<>();
        List<HirFunction> constructors = new ArrayList<>();

        // 主构造器参数变为字段 + 构造器
        if (node.getPrimaryConstructorParams() != null && !node.getPrimaryConstructorParams().isEmpty()) {
            List<HirParam> ctorParams = new ArrayList<>();
            for (Parameter p : node.getPrimaryConstructorParams()) {
                HirParam param = (HirParam) p.accept(this, ctx);
                ctorParams.add(param);
                // 属性参数同时生成字段
                if (p.isProperty()) {
                    fields.add(new HirField(p.getLocation(), p.getName(),
                            toModifierSet(p.getModifiers()),
                            Collections.emptyList(), lowerType(p.getType()), null,
                            p.hasModifier(Modifier.FINAL)));
                }
            }
            constructors.add(new HirFunction(node.getLocation(), "<init>",
                    Collections.emptySet(), Collections.emptyList(),
                    Collections.emptyList(), null, ctorParams,
                    new PrimitiveType(PrimitiveType.Kind.UNIT), null, true));
        }

        // 有序实例初始化列表（HirField + init 块 Block，按声明顺序）
        List<AstNode> instanceInitializers = new ArrayList<>();

        // 成员
        for (Declaration member : node.getMembers()) {
            // 伴生对象：将其方法和字段扁平化为父类的静态成员
            if (member instanceof ObjectDecl && ((ObjectDecl) member).isCompanion()) {
                ObjectDecl companion = (ObjectDecl) member;
                for (Declaration companionMember : companion.getMembers()) {
                    HirDecl lowered = lowerDecl(companionMember, ctx);
                    if (lowered instanceof HirField) {
                        // 添加 STATIC 修饰符
                        HirField f = (HirField) lowered;
                        Set<Modifier> mods = new java.util.HashSet<>(f.getModifiers());
                        mods.add(Modifier.STATIC);
                        fields.add(new HirField(f.getLocation(), f.getName(), mods,
                                f.getAnnotations(), f.getType(), f.getInitializer(), f.isVal(),
                                f.getReceiverType(), f.getGetterBody(), f.getSetterBody(), f.getSetterParam()));
                    } else if (lowered instanceof HirFunction) {
                        // 添加 STATIC 修饰符
                        HirFunction fn = (HirFunction) lowered;
                        Set<Modifier> mods = new java.util.HashSet<>(fn.getModifiers());
                        mods.add(Modifier.STATIC);
                        methods.add(new HirFunction(fn.getLocation(), fn.getName(), mods,
                                fn.getAnnotations(), fn.getTypeParams(), fn.getReceiverType(),
                                fn.getParams(), fn.getReturnType(), fn.getBody(), false));
                    }
                }
                continue;
            }

            // init 块：lower body 并加入有序列表
            if (member instanceof InitBlockDecl) {
                AstNode body = lowerBody(((InitBlockDecl) member).getBody(), ctx);
                if (body != null) {
                    instanceInitializers.add(body);
                }
                continue;
            }

            HirDecl lowered = lowerDecl(member, ctx);
            if (lowered instanceof HirField) {
                HirField f = (HirField) lowered;
                fields.add(f);
                // 有初始化器的非静态字段加入有序列表
                if (f.hasInitializer() && !f.getModifiers().contains(Modifier.STATIC)) {
                    instanceInitializers.add(f);
                }
            } else if (lowered instanceof HirFunction) {
                HirFunction fn = (HirFunction) lowered;
                if (fn.isConstructor()) {
                    constructors.add(fn);
                } else {
                    methods.add(fn);
                }
            }
        }

        HirType superClass = null;
        List<HirType> interfaces = new ArrayList<>();
        if (node.getSuperTypes() != null) {
            for (TypeRef st : node.getSuperTypes()) {
                HirType t = lowerType(st);
                // 简化：第一个作为 superClass，其余作为 interfaces
                if (superClass == null) {
                    superClass = t;
                } else {
                    interfaces.add(t);
                }
            }
        }

        // 超类构造器参数
        List<Expression> superCtorArgs = new ArrayList<>();
        if (node.getSuperConstructorArgs() != null) {
            for (Expression arg : node.getSuperConstructorArgs()) {
                superCtorArgs.add(lowerExpr(arg, ctx));
            }
        }

        return new HirClass(node.getLocation(), node.getName(),
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx), kind,
                lowerTypeParams(node.getTypeParams()), fields, methods, constructors,
                superClass, interfaces, Collections.emptyList(), superCtorArgs,
                instanceInitializers);
    }

    @Override
    public AstNode visitInterfaceDecl(InterfaceDecl node, LoweringContext ctx) {
        List<HirField> fields = new ArrayList<>();
        List<HirFunction> methods = new ArrayList<>();
        for (Declaration member : node.getMembers()) {
            HirDecl lowered = lowerDecl(member, ctx);
            if (lowered instanceof HirField) fields.add((HirField) lowered);
            else if (lowered instanceof HirFunction) methods.add((HirFunction) lowered);
        }
        return new HirClass(node.getLocation(), node.getName(),
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx), ClassKind.INTERFACE,
                lowerTypeParams(node.getTypeParams()), fields, methods,
                Collections.emptyList(), null, lowerSuperTypes(node.getSuperTypes()),
                Collections.emptyList());
    }

    @Override
    public AstNode visitObjectDecl(ObjectDecl node, LoweringContext ctx) {
        List<HirField> fields = new ArrayList<>();
        List<HirFunction> methods = new ArrayList<>();
        for (Declaration member : node.getMembers()) {
            HirDecl lowered = lowerDecl(member, ctx);
            if (lowered instanceof HirField) fields.add((HirField) lowered);
            else if (lowered instanceof HirFunction) methods.add((HirFunction) lowered);
        }
        return new HirClass(node.getLocation(), node.getName(),
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx), ClassKind.OBJECT,
                Collections.emptyList(), fields, methods, Collections.emptyList(),
                null, lowerSuperTypes(node.getSuperTypes()), Collections.emptyList());
    }

    @Override
    public AstNode visitEnumDecl(EnumDecl node, LoweringContext ctx) {
        List<HirField> fields = new ArrayList<>();
        List<HirFunction> methods = new ArrayList<>();
        List<HirFunction> constructors = new ArrayList<>();
        List<HirEnumEntry> entries = new ArrayList<>();

        for (EnumDecl.EnumEntry entry : node.getEntries()) {
            List<Expression> args = lowerExprs(entry.getArgs(), ctx);
            List<HirDecl> members = lowerDecls(entry.getMembers(), ctx);
            entries.add(new HirEnumEntry(entry.getLocation(), entry.getName(), args, members));
        }

        if (node.getPrimaryConstructorParams() != null) {
            List<HirParam> ctorParams = new ArrayList<>();
            for (Parameter p : node.getPrimaryConstructorParams()) {
                ctorParams.add((HirParam) p.accept(this, ctx));
                // 属性参数同时生成字段（与 visitClassDecl 一致）
                if (p.isProperty()) {
                    fields.add(new HirField(p.getLocation(), p.getName(),
                            toModifierSet(p.getModifiers()),
                            Collections.emptyList(), lowerType(p.getType()), null,
                            p.hasModifier(Modifier.FINAL)));
                }
            }
            constructors.add(new HirFunction(node.getLocation(), "<init>",
                    Collections.emptySet(), Collections.emptyList(),
                    Collections.emptyList(), null, ctorParams,
                    new PrimitiveType(PrimitiveType.Kind.UNIT), null, true));
        }

        for (Declaration member : node.getMembers()) {
            HirDecl lowered = lowerDecl(member, ctx);
            if (lowered instanceof HirField) fields.add((HirField) lowered);
            else if (lowered instanceof HirFunction) {
                HirFunction fn = (HirFunction) lowered;
                if (fn.isConstructor()) constructors.add(fn);
                else methods.add(fn);
            }
        }

        return new HirClass(node.getLocation(), node.getName(),
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx), ClassKind.ENUM,
                Collections.emptyList(), fields, methods, constructors,
                null, Collections.emptyList(), entries);
    }

    @Override
    public AstNode visitFunDecl(FunDecl node, LoweringContext ctx) {
        List<HirParam> params = new ArrayList<>();
        for (Parameter p : node.getParams()) {
            params.add((HirParam) p.accept(this, ctx));
        }
        HirType receiverType = node.getReceiverType() != null ? lowerType(node.getReceiverType()) : null;
        HirType returnType = lowerType(node.getReturnType());
        AstNode body = lowerBody(node.getBody(), ctx);
        Set<String> reifiedParams = extractReifiedTypeParams(node.getTypeParams());
        return new HirFunction(node.getLocation(), node.getName(),
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx),
                lowerTypeParams(node.getTypeParams()), receiverType, params,
                returnType, body, false, null, reifiedParams);
    }

    @Override
    public AstNode visitConstructorDecl(ConstructorDecl node, LoweringContext ctx) {
        List<HirParam> params = new ArrayList<>();
        for (Parameter p : node.getParams()) {
            params.add((HirParam) p.accept(this, ctx));
        }
        AstNode body = lowerBody(node.getBody(), ctx);
        // 处理 this(...) 委托参数
        List<Expression> delegationArgs = null;
        if (node.hasDelegation()) {
            delegationArgs = new ArrayList<>();
            for (Expression arg : node.getDelegationArgs()) {
                delegationArgs.add(lowerExpr(arg, ctx));
            }
        }
        return new HirFunction(node.getLocation(), "<init>",
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx),
                Collections.emptyList(), null, params,
                new PrimitiveType(PrimitiveType.Kind.UNIT), body, true, delegationArgs);
    }

    @Override
    public AstNode visitInitBlockDecl(InitBlockDecl node, LoweringContext ctx) {
        return lowerBody(node.getBody(), ctx);
    }

    @Override
    public AstNode visitPropertyDecl(PropertyDecl node, LoweringContext ctx) {
        Expression init = lowerExpr(node.getInitializer(), ctx);
        HirType type = lowerType(node.getType());
        HirType receiverType = node.getReceiverType() != null ? lowerType(node.getReceiverType()) : null;

        // 自定义 getter/setter
        AstNode getterBody = null;
        AstNode setterBody = null;
        HirParam setterParam = null;
        if (node.getGetter() != null && node.getGetter().getBody() != null) {
            getterBody = lowerBody(node.getGetter().getBody(), ctx);
        }
        if (node.getSetter() != null && node.getSetter().getBody() != null) {
            setterBody = lowerBody(node.getSetter().getBody(), ctx);
            if (node.getSetter().getParam() != null) {
                setterParam = (HirParam) node.getSetter().getParam().accept(this, ctx);
            }
        }

        return new HirField(node.getLocation(), node.getName(),
                toModifierSet(node.getModifiers()),
                lowerAnnotations(node.getAnnotations(), ctx),
                type, init, node.isVal(), receiverType,
                getterBody, setterBody, setterParam);
    }

    @Override
    public AstNode visitParameter(Parameter node, LoweringContext ctx) {
        return new HirParam(node.getLocation(), node.getName(),
                lowerType(node.getType()),
                lowerExpr(node.getDefaultValue(), ctx),
                node.isVararg());
    }

    @Override
    public AstNode visitTypeAliasDecl(TypeAliasDecl node, LoweringContext ctx) {
        return new HirTypeAlias(node.getLocation(), node.getName(),
                lowerTypeParams(node.getTypeParams()),
                lowerType(node.getAliasedType()));
    }

    @Override
    public AstNode visitQualifiedName(QualifiedName node, LoweringContext ctx) {
        return null; // 已内联处理
    }

    /**
     * 脱糖：val (a, b) = e → val tmp = e; val a = tmp.component1(); val b = tmp.component2()
     */
    @Override
    public AstNode visitDestructuringDecl(DestructuringDecl node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        String tmp = ctx.freshTemp();
        Expression init = lowerExpr(node.getInitializer(), ctx);

        List<Statement> stmts = new ArrayList<>();
        stmts.add(ctx.makeTempVal(loc, tmp, null, init));

        List<String> names = node.getNames();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || "_".equals(name)) continue;
            // val name = tmp.componentN()
            Identifier tmpRef = ctx.tempRef(loc, tmp, null);
            String methodName = "component" + (i + 1);
            Expression call = new HirCall(loc, null,
                    new MemberExpr(loc, tmpRef, methodName),
                    Collections.emptyList(), Collections.emptyList());
            stmts.add(ctx.makeTempVal(loc, name, null, call));
        }
        return new Block(loc, stmts, true);
    }

    // ========== 语句 ==========

    @Override
    public AstNode visitBlock(Block node, LoweringContext ctx) {
        return new Block(node.getLocation(), lowerStmts(node.getStatements(), ctx));
    }

    @Override
    public AstNode visitExpressionStmt(ExpressionStmt node, LoweringContext ctx) {
        return new ExpressionStmt(node.getLocation(), lowerExpr(node.getExpression(), ctx));
    }

    @Override
    public AstNode visitDeclarationStmt(DeclarationStmt node, LoweringContext ctx) {
        AstNode lowered = node.getDeclaration().accept(this, ctx);
        // DestructuringDecl 返回 Block（多语句）
        if (lowered instanceof Statement) return lowered;
        return new HirDeclStmt(node.getLocation(), (HirDecl) lowered);
    }

    @Override
    public AstNode visitIfStmt(IfStmt node, LoweringContext ctx) {
        // if-let: if (val x = expr) { ... } → val tmp = expr; if (tmp != null) { val x = tmp; ... }
        if (node.isIfLet()) {
            return lowerIfLet(node, ctx);
        }
        return new IfStmt(node.getLocation(),
                lowerExpr(node.getCondition(), ctx), null,
                lowerStmt(node.getThenBranch(), ctx),
                lowerStmt(node.getElseBranch(), ctx));
    }

    private Statement lowerIfLet(IfStmt node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        String tmp = ctx.freshTemp();
        Expression condExpr = lowerExpr(node.getCondition(), ctx);

        List<Statement> outer = new ArrayList<>();
        outer.add(ctx.makeTempVal(loc, tmp, null, condExpr));

        // condition: tmp != null
        Expression nullCheck = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                ctx.tempRef(loc, tmp, null), BinaryOp.NE, ctx.nullLiteral(loc));

        // then: { val x = tmp; originalBody }
        List<Statement> thenStmts = new ArrayList<>();
        thenStmts.add(ctx.makeTempVal(loc, node.getBindingName(), null,
                ctx.tempRef(loc, tmp, null)));
        Statement thenBody = lowerStmt(node.getThenBranch(), ctx);
        if (thenBody instanceof Block) {
            thenStmts.addAll(((Block) thenBody).getStatements());
        } else {
            thenStmts.add(thenBody);
        }

        IfStmt ifStmt = new IfStmt(loc, nullCheck, null,
                new Block(loc, thenStmts),
                lowerStmt(node.getElseBranch(), ctx));
        outer.add(ifStmt);
        return new Block(loc, outer);
    }

    /**
     * 脱糖：when → if-else if 链
     */
    @Override
    public AstNode visitWhenStmt(WhenStmt node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        List<Statement> outer = new ArrayList<>();
        String subjectVar = null;

        // 绑定 subject 到临时变量
        if (node.hasSubject()) {
            subjectVar = node.hasBinding() ? node.getBindingName() : ctx.freshTemp();
            outer.add(ctx.makeTempVal(loc, subjectVar, null, lowerExpr(node.getSubject(), ctx)));
        }

        Statement result = lowerWhenBranches(node.getBranches(), node.getElseBranch(),
                subjectVar, loc, ctx, false);
        if (outer.isEmpty()) return result;
        outer.add(result);
        return new Block(loc, outer);
    }

    private Statement lowerWhenBranches(List<WhenBranch> branches, Statement elseBranch,
                                      String subjectVar, SourceLocation loc,
                                      LoweringContext ctx, boolean isExpr) {
        if (branches.isEmpty()) {
            return elseBranch != null ? lowerStmt(elseBranch, ctx) : null;
        }

        // 从最后一个分支开始，逆序构建 if-else 链
        Statement elsePart = elseBranch != null ? lowerStmt(elseBranch, ctx) : null;

        for (int i = branches.size() - 1; i >= 0; i--) {
            WhenBranch branch = branches.get(i);
            Expression cond = lowerWhenConditions(branch.getConditions(), subjectVar, loc, ctx);
            Statement body = lowerStmt(branch.getBody(), ctx);
            elsePart = new IfStmt(branch.getLocation(), cond, null, body, elsePart);
        }
        return elsePart;
    }

    private Expression lowerWhenConditions(List<WhenBranch.WhenCondition> conditions,
                                        String subjectVar, SourceLocation loc,
                                        LoweringContext ctx) {
        Expression result = null;
        for (WhenBranch.WhenCondition cond : conditions) {
            Expression condExpr = lowerSingleWhenCondition(cond, subjectVar, loc, ctx);
            if (result == null) {
                result = condExpr;
            } else {
                // 多条件用 OR 连接
                result = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                        result, BinaryOp.OR, condExpr);
            }
        }
        return result;
    }

    private Expression lowerSingleWhenCondition(WhenBranch.WhenCondition cond,
                                             String subjectVar, SourceLocation loc,
                                             LoweringContext ctx) {
        if (cond instanceof WhenBranch.ExpressionCondition) {
            Expression expr = ((WhenBranch.ExpressionCondition) cond).getExpression();
            Expression lowered = lowerExpr(expr, ctx);
            if (subjectVar != null) {
                // when(x) { value -> ... }  ⇒  x == value
                return new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                        ctx.tempRef(loc, subjectVar, null), BinaryOp.EQ, lowered);
            }
            return lowered;
        }
        if (cond instanceof WhenBranch.TypeCondition) {
            WhenBranch.TypeCondition tc = (WhenBranch.TypeCondition) cond;
            Expression target = subjectVar != null ? ctx.tempRef(loc, subjectVar, null) : null;
            TypeCheckExpr tce = new TypeCheckExpr(loc, target, null, tc.isNegated());
            tce.setHirType(new PrimitiveType(PrimitiveType.Kind.BOOLEAN));
            tce.setHirTargetType(lowerType(tc.getType()));
            return tce;
        }
        if (cond instanceof WhenBranch.RangeCondition) {
            WhenBranch.RangeCondition rc = (WhenBranch.RangeCondition) cond;
            Expression range = lowerExpr(rc.getRange(), ctx);
            Expression target = subjectVar != null ? ctx.tempRef(loc, subjectVar, null) : null;
            BinaryOp op = rc.isNegated() ? BinaryOp.NOT_IN : BinaryOp.IN;
            return new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                    target, op, range);
        }
        // fallback
        return ctx.boolLiteral(loc, true);
    }

    /**
     * 脱糖：guard val x = expr else { ... }
     * → val tmp = expr; if (tmp == null) { else } else { val x = tmp }
     */
    @Override
    public AstNode visitGuardStmt(GuardStmt node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        String tmp = ctx.freshTemp();
        Expression init = lowerExpr(node.getExpression(), ctx);

        List<Statement> stmts = new ArrayList<>();
        stmts.add(ctx.makeTempVal(loc, tmp, null, init));

        Expression isNull = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                ctx.tempRef(loc, tmp, null), BinaryOp.EQ, ctx.nullLiteral(loc));

        // if (tmp == null) { elseBody } — elseBody 应包含 return/throw
        Statement elseBody = lowerStmt(node.getElseBody(), ctx);
        stmts.add(new IfStmt(loc, isNull, null, elseBody, null));

        // val x = tmp — 仅在 tmp 非 null 时到达
        stmts.add(ctx.makeTempVal(loc, node.getBindingName(), null,
                ctx.tempRef(loc, tmp, null)));

        return new Block(loc, stmts, true);
    }

    /**
     * 脱糖：use(val r = e) { body } → val r = e; try { body } finally { r.close() }
     */
    @Override
    public AstNode visitUseStmt(UseStmt node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        List<Statement> stmts = new ArrayList<>();

        // 声明所有绑定
        List<String> bindingNames = new ArrayList<>();
        for (UseStmt.UseBinding binding : node.getBindings()) {
            String name = binding.getName();
            bindingNames.add(name);
            stmts.add(ctx.makeTempVal(binding.getLocation(), name, null,
                    lowerExpr(binding.getInitializer(), ctx)));
        }

        // try { body } finally { close all }
        Statement body = lowerStmt(node.getBody(), ctx);
        List<Statement> finallyStmts = new ArrayList<>();
        for (String name : bindingNames) {
            Expression closeCall = new HirCall(loc, null,
                    new MemberExpr(loc, ctx.tempRef(loc, name, null), "close"),
                    Collections.emptyList(), Collections.emptyList());
            Statement closeStmt = new ExpressionStmt(loc, closeCall);
            // 包装在 try-catch 中：对象可能没有 close() 方法（如 StringBuilder）
            Statement emptyBlock = new Block(loc, Collections.emptyList());
            HirTry safeClose = new HirTry(loc, closeStmt,
                    Collections.singletonList(new HirTry.CatchClause("_e", null, emptyBlock)),
                    null);
            finallyStmts.add(safeClose);
        }
        Statement finallyBlock = new Block(loc, finallyStmts);
        stmts.add(new HirTry(loc, body, Collections.emptyList(), finallyBlock));
        return new Block(loc, stmts);
    }

    @Override
    public AstNode visitForStmt(ForStmt node, LoweringContext ctx) {
        return new ForStmt(node.getLocation(), node.getLabel(),
                node.getVariables(), lowerExpr(node.getIterable(), ctx),
                lowerStmt(node.getBody(), ctx));
    }

    @Override
    public AstNode visitWhileStmt(WhileStmt node, LoweringContext ctx) {
        return new HirLoop(node.getLocation(), node.getLabel(),
                lowerExpr(node.getCondition(), ctx),
                lowerStmt(node.getBody(), ctx), false);
    }

    @Override
    public AstNode visitDoWhileStmt(DoWhileStmt node, LoweringContext ctx) {
        return new HirLoop(node.getLocation(), node.getLabel(),
                lowerExpr(node.getCondition(), ctx),
                lowerStmt(node.getBody(), ctx), true);
    }

    @Override
    public AstNode visitTryStmt(TryStmt node, LoweringContext ctx) {
        Statement tryBlock = lowerStmt(node.getTryBlock(), ctx);
        List<HirTry.CatchClause> catches = new ArrayList<>();
        if (node.getCatchClauses() != null) {
            for (CatchClause cc : node.getCatchClauses()) {
                catches.add(new HirTry.CatchClause(
                        cc.getParamName(), lowerType(cc.getParamType()),
                        lowerStmt(cc.getBody(), ctx)));
            }
        }
        Statement finallyBlock = lowerStmt(node.getFinallyBlock(), ctx);
        return new HirTry(node.getLocation(), tryBlock, catches, finallyBlock);
    }

    @Override
    public AstNode visitReturnStmt(ReturnStmt node, LoweringContext ctx) {
        return new ReturnStmt(node.getLocation(),
                lowerExpr(node.getValue(), ctx), node.getLabel());
    }

    @Override
    public AstNode visitBreakStmt(BreakStmt node, LoweringContext ctx) {
        return node;
    }

    @Override
    public AstNode visitContinueStmt(ContinueStmt node, LoweringContext ctx) {
        return node;
    }

    @Override
    public AstNode visitThrowStmt(ThrowStmt node, LoweringContext ctx) {
        return new ThrowStmt(node.getLocation(), lowerExpr(node.getException(), ctx));
    }

    // ========== 表达式 ==========

    @Override
    public AstNode visitLiteral(Literal node, LoweringContext ctx) {
        LiteralKind kind = LiteralKind.valueOf(node.getKind().name());
        HirType type = literalType(node.getKind());
        return new Literal(node.getLocation(), type, node.getValue(), kind);
    }

    private HirType literalType(Literal.LiteralKind kind) {
        switch (kind) {
            case INT: return new PrimitiveType(PrimitiveType.Kind.INT);
            case LONG: return new PrimitiveType(PrimitiveType.Kind.LONG);
            case FLOAT: return new PrimitiveType(PrimitiveType.Kind.FLOAT);
            case DOUBLE: return new PrimitiveType(PrimitiveType.Kind.DOUBLE);
            case CHAR: return new PrimitiveType(PrimitiveType.Kind.CHAR);
            case STRING: return new ClassType("String");
            case BOOLEAN: return new PrimitiveType(PrimitiveType.Kind.BOOLEAN);
            case NULL: return null;
            default: return null;
        }
    }

    @Override
    public AstNode visitIdentifier(Identifier node, LoweringContext ctx) {
        return new Identifier(node.getLocation(), node.getName());
    }

    @Override
    public AstNode visitThisExpr(ThisExpr node, LoweringContext ctx) {
        return new ThisExpr(node.getLocation(), null);
    }

    @Override
    public AstNode visitSuperExpr(SuperExpr node, LoweringContext ctx) {
        ThisExpr superExpr = new ThisExpr(node.getLocation(), null);
        superExpr.setIsSuper(true);
        return superExpr;
    }

    @Override
    public AstNode visitBinaryExpr(BinaryExpr node, LoweringContext ctx) {
        // Range 表达式（.. 和 ..<）转为 RangeExpr
        if (node.getOperator() == BinaryExpr.BinaryOp.RANGE_INCLUSIVE) {
            return new RangeExpr(node.getLocation(),
                    lowerExpr(node.getLeft(), ctx), lowerExpr(node.getRight(), ctx),
                    null, false);
        }
        if (node.getOperator() == BinaryExpr.BinaryOp.RANGE_EXCLUSIVE) {
            return new RangeExpr(node.getLocation(),
                    lowerExpr(node.getLeft(), ctx), lowerExpr(node.getRight(), ctx),
                    null, true);
        }

        BinaryOp op = node.getOperator();
        return new BinaryExpr(node.getLocation(), null,
                lowerExpr(node.getLeft(), ctx), op, lowerExpr(node.getRight(), ctx));
    }

    @Override
    public AstNode visitUnaryExpr(UnaryExpr node, LoweringContext ctx) {
        return new UnaryExpr(node.getLocation(), null,
                node.getOperator(), lowerExpr(node.getOperand(), ctx), node.isPrefix());
    }

    @Override
    public AstNode visitCallExpr(CallExpr node, LoweringContext ctx) {
        Expression callee = lowerExpr(node.getCallee(), ctx);
        List<HirType> typeArgs = Collections.emptyList();
        if (node.getTypeArgs() != null && !node.getTypeArgs().isEmpty()) {
            typeArgs = new ArrayList<>();
            for (TypeRef t : node.getTypeArgs()) typeArgs.add(lowerType(t));
        }
        List<Expression> args = new ArrayList<>();
        Map<String, Expression> namedArgs = null;
        java.util.Set<Integer> spreadIndices = null;
        if (node.getArgs() != null) {
            for (CallExpr.Argument arg : node.getArgs()) {
                if (arg.isNamed()) {
                    if (namedArgs == null) namedArgs = new java.util.LinkedHashMap<>();
                    namedArgs.put(arg.getName(), lowerExpr(arg.getValue(), ctx));
                } else {
                    if (arg.isSpread()) {
                        if (spreadIndices == null) spreadIndices = new java.util.HashSet<>();
                        spreadIndices.add(args.size());
                    }
                    args.add(lowerExpr(arg.getValue(), ctx));
                }
            }
        }
        // 尾随 Lambda
        if (node.hasTrailingLambda()) {
            args.add(lowerExpr(node.getTrailingLambda(), ctx));
        }
        return new HirCall(node.getLocation(), null, callee, typeArgs, args, namedArgs, spreadIndices);
    }

    @Override
    public AstNode visitIndexExpr(IndexExpr node, LoweringContext ctx) {
        return new IndexExpr(node.getLocation(),
                lowerExpr(node.getTarget(), ctx),
                lowerExpr(node.getIndex(), ctx));
    }

    @Override
    public AstNode visitMemberExpr(MemberExpr node, LoweringContext ctx) {
        return new MemberExpr(node.getLocation(),
                lowerExpr(node.getTarget(), ctx), node.getMember());
    }

    /**
     * 脱糖：复合赋值 x += 1 → x = x + 1
     */
    @Override
    public AstNode visitAssignExpr(AssignExpr node, LoweringContext ctx) {
        Expression target = lowerExpr(node.getTarget(), ctx);
        Expression value = lowerExpr(node.getValue(), ctx);

        if (node.getOperator() == AssignExpr.AssignOp.ASSIGN) {
            return new AssignExpr(node.getLocation(), target, AssignExpr.AssignOp.ASSIGN, value);
        }

        // x ??= v → x = (x != null ? x : v)
        if (node.getOperator() == AssignExpr.AssignOp.NULL_COALESCE) {
            SourceLocation loc = node.getLocation();
            Expression targetAgain = lowerExpr(node.getTarget(), ctx);
            Expression nullCheck = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                    targetAgain, BinaryOp.NE, ctx.nullLiteral(loc));
            Expression elvis = new ConditionalExpr(loc,nullCheck,
                    lowerExpr(node.getTarget(), ctx), value);
            return new AssignExpr(loc, target, AssignExpr.AssignOp.ASSIGN, elvis);
        }

        // 复合赋值 → 简单赋值
        BinaryOp binOp;
        switch (node.getOperator()) {
            case ADD_ASSIGN: binOp = BinaryOp.ADD; break;
            case SUB_ASSIGN: binOp = BinaryOp.SUB; break;
            case MUL_ASSIGN: binOp = BinaryOp.MUL; break;
            case DIV_ASSIGN: binOp = BinaryOp.DIV; break;
            case MOD_ASSIGN: binOp = BinaryOp.MOD; break;
            case OR_ASSIGN:  binOp = BinaryOp.OR;  break;
            case AND_ASSIGN: binOp = BinaryOp.AND; break;
            default:
                throw new IllegalStateException("Unhandled compound assign operator: " + node.getOperator());
        }
        Expression expanded = new BinaryExpr(node.getLocation(), null,
                lowerExpr(node.getTarget(), ctx), binOp, value);
        return new AssignExpr(node.getLocation(), target, AssignExpr.AssignOp.ASSIGN, expanded);
    }

    @Override
    public AstNode visitLambdaExpr(LambdaExpr node, LoweringContext ctx) {
        List<HirParam> params = new ArrayList<>();
        if (node.getParams() != null) {
            for (LambdaExpr.LambdaParam p : node.getParams()) {
                params.add(new HirParam(p.getLocation(), p.getName(),
                        lowerType(p.getType()), null, false));
            }
        }
        AstNode body = lowerBody(node.getBody(), ctx);
        return new HirLambda(node.getLocation(), null, params, body, Collections.emptyList());
    }

    /**
     * if 表达式 → 同 IfStmt（在表达式上下文中使用）
     * HIR 不区分 if 语句和 if 表达式，统一为 IfStmt。
     */
    @Override
    public AstNode visitIfExpr(IfExpr node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        Expression cond;
        if (node.hasBinding()) {
            Expression condExpr = lowerExpr(node.getCondition(), ctx);
            cond = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                    condExpr, BinaryOp.NE, ctx.nullLiteral(loc));
        } else {
            cond = lowerExpr(node.getCondition(), ctx);
        }
        Expression thenExpr = lowerExpr(node.getThenExpr(), ctx);
        Expression elseExpr = node.getElseExpr() != null
                ? lowerExpr(node.getElseExpr(), ctx) : ctx.nullLiteral(loc);
        return new ConditionalExpr(loc,cond, thenExpr, elseExpr);
    }

    /**
     * 三元表达式（Parser 直接产生的 ConditionalExpr）→ 递归 lower 子表达式。
     */
    @Override
    public AstNode visitConditionalExpr(ConditionalExpr node, LoweringContext ctx) {
        Expression cond = lowerExpr(node.getCondition(), ctx);
        Expression thenExpr = lowerExpr(node.getThenExpr(), ctx);
        Expression elseExpr = lowerExpr(node.getElseExpr(), ctx);
        return new ConditionalExpr(node.getLocation(), cond, thenExpr, elseExpr);
    }

    /**
     * when 表达式 → ConditionalExpr 链。
     * 表达式上下文必须返回 HirExpr，因此内联 subject（不包装为 Block）。
     */
    @Override
    public AstNode visitWhenExpr(WhenExpr node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();

        // 内联 subject 表达式，避免返回 Block（非 HirExpr 会导致 ClassCastException）
        Expression subjectExpr = node.hasSubject() ? lowerExpr(node.getSubject(), ctx) : null;

        // 用 ConditionalExpr 链表示
        Expression elseExpr = node.getElseExpr() != null
                ? lowerExpr(node.getElseExpr(), ctx) : ctx.nullLiteral(loc);

        for (int i = node.getBranches().size() - 1; i >= 0; i--) {
            WhenBranch branch = node.getBranches().get(i);
            Expression cond = lowerWhenConditionsInline(branch.getConditions(), subjectExpr, loc, ctx);
            Expression bodyExpr = lowerBranchBodyAsExpr(branch.getBody(), ctx);
            elseExpr = new ConditionalExpr(branch.getLocation(), cond, bodyExpr, elseExpr);
        }
        return elseExpr;
    }

    /**
     * 内联版本的 when 条件降级：直接使用 subject 表达式而非变量引用。
     */
    private Expression lowerWhenConditionsInline(List<WhenBranch.WhenCondition> conditions,
                                              Expression subjectExpr, SourceLocation loc,
                                              LoweringContext ctx) {
        Expression result = null;
        for (WhenBranch.WhenCondition cond : conditions) {
            Expression condExpr;
            if (cond instanceof WhenBranch.ExpressionCondition) {
                Expression expr = ((WhenBranch.ExpressionCondition) cond).getExpression();
                Expression lowered = lowerExpr(expr, ctx);
                condExpr = subjectExpr != null
                        ? new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                                subjectExpr, BinaryOp.EQ, lowered)
                        : lowered;
            } else if (cond instanceof WhenBranch.TypeCondition) {
                WhenBranch.TypeCondition tc = (WhenBranch.TypeCondition) cond;
                TypeCheckExpr tce2 = new TypeCheckExpr(loc, subjectExpr, null, tc.isNegated());
                tce2.setHirType(new PrimitiveType(PrimitiveType.Kind.BOOLEAN));
                tce2.setHirTargetType(lowerType(tc.getType()));
                condExpr = tce2;
            } else if (cond instanceof WhenBranch.RangeCondition) {
                WhenBranch.RangeCondition rc = (WhenBranch.RangeCondition) cond;
                Expression range = lowerExpr(rc.getRange(), ctx);
                BinaryOp op = rc.isNegated() ? BinaryOp.NOT_IN : BinaryOp.IN;
                condExpr = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                        subjectExpr, op, range);
            } else {
                condExpr = ctx.boolLiteral(loc, true);
            }
            if (result == null) {
                result = condExpr;
            } else {
                result = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                        result, BinaryOp.OR, condExpr);
            }
        }
        return result;
    }

    /**
     * 将分支体转为表达式。ExpressionStmt → 取内部表达式，Block → 取最后一个表达式。
     */
    private Expression lowerBranchBodyAsExpr(Statement body, LoweringContext ctx) {
        if (body instanceof ExpressionStmt) {
            return lowerExpr(((ExpressionStmt) body).getExpression(), ctx);
        }
        if (body instanceof Block) {
            Block block = (Block) body;
            List<Statement> stmts = block.getStatements();
            if (!stmts.isEmpty()) {
                Statement last = stmts.get(stmts.size() - 1);
                if (last instanceof ExpressionStmt) {
                    return lowerExpr(((ExpressionStmt) last).getExpression(), ctx);
                }
            }
        }
        // 回退：作为语句降级，返回 null
        return new Literal(body.getLocation(), null, null, LiteralKind.NULL);
    }

    @Override
    public AstNode visitTryExpr(TryExpr node, LoweringContext ctx) {
        // try 表达式：val x = try { ... } catch (e) { ... }
        // 降级为: { var __tmp; try { __tmp = tryResult } catch (e) { __tmp = catchResult }; __tmp }
        SourceLocation loc = node.getLocation();
        String tmp = ctx.freshTemp();
        List<Statement> outerStmts = new java.util.ArrayList<>();

        // try 块体：最后一条语句的值赋给 tmp
        Statement tryBody = lowerTryExprBlock(node.getTryBlock(), tmp, ctx);

        // catch 子句
        List<HirTry.CatchClause> catches = new java.util.ArrayList<>();
        if (node.getCatchClauses() != null) {
            for (CatchClause cc : node.getCatchClauses()) {
                Statement catchBody = lowerTryExprBlock(cc.getBody(), tmp, ctx);
                catches.add(new HirTry.CatchClause(
                        cc.getParamName(), lowerType(cc.getParamType()), catchBody));
            }
        }

        // finally 块
        Statement finallyBlock = node.hasFinally() ? lowerStmt(node.getFinallyBlock(), ctx) : null;

        // var __tmp = null; try { __tmp = ... } catch { __tmp = ... }; __tmp
        outerStmts.add(ctx.makeTempVar(loc, tmp, null, ctx.nullLiteral(loc)));
        outerStmts.add(new HirTry(loc, tryBody, catches, finallyBlock));
        return new BlockExpr(loc,outerStmts, ctx.tempRef(loc, tmp, null));
    }

    /**
     * 将 try/catch 块体降级为赋值语句：将最后一条语句的结果赋值给 tmp。
     */
    private Statement lowerTryExprBlock(Statement block, String tmp, LoweringContext ctx) {
        if (block instanceof Block) {
            List<Statement> stmts = ((Block) block).getStatements();
            List<Statement> hirStmts = new java.util.ArrayList<>();
            for (int i = 0; i < stmts.size(); i++) {
                Statement s = stmts.get(i);
                if (i == stmts.size() - 1 && s instanceof ExpressionStmt) {
                    // 最后一条表达式语句 → tmp = expr
                    Expression value = lowerExpr(((ExpressionStmt) s).getExpression(), ctx);
                    hirStmts.add(new ExpressionStmt(s.getLocation(),
                            new AssignExpr(s.getLocation(),
                                    ctx.tempRef(s.getLocation(), tmp, null), AssignExpr.AssignOp.ASSIGN, value)));
                } else {
                    hirStmts.add(lowerStmt(s, ctx));
                }
            }
            return new Block(block.getLocation(), hirStmts);
        }
        // 单语句
        Statement lowered = lowerStmt(block, ctx);
        return lowered;
    }

    @Override
    public AstNode visitAwaitExpr(AwaitExpr node, LoweringContext ctx) {
        return new AwaitExpr(node.getLocation(), lowerExpr(node.getOperand(), ctx));
    }

    @Override
    public AstNode visitTypeCheckExpr(TypeCheckExpr node, LoweringContext ctx) {
        TypeCheckExpr result = new TypeCheckExpr(node.getLocation(),
                lowerExpr(node.getOperand(), ctx), null, node.isNegated());
        result.setHirType(new PrimitiveType(PrimitiveType.Kind.BOOLEAN));
        result.setHirTargetType(lowerType(node.getTargetType()));
        return result;
    }

    @Override
    public AstNode visitTypeCastExpr(TypeCastExpr node, LoweringContext ctx) {
        HirType loweredType = lowerType(node.getTargetType());
        TypeCastExpr result = new TypeCastExpr(node.getLocation(),
                lowerExpr(node.getOperand(), ctx), null, node.isSafe());
        result.setHirType(loweredType);
        result.setHirTargetType(loweredType);
        return result;
    }

    @Override
    public AstNode visitRangeExpr(RangeExpr node, LoweringContext ctx) {
        return new RangeExpr(node.getLocation(),
                lowerExpr(node.getStart(), ctx), lowerExpr(node.getEnd(), ctx),
                lowerExpr(node.getStep(), ctx), node.isEndExclusive());
    }

    @Override
    public AstNode visitSliceExpr(SliceExpr node, LoweringContext ctx) {
        // 脱糖：target[start..end] → target[Range(start, end)]
        SourceLocation loc = node.getLocation();
        Expression target = lowerExpr(node.getTarget(), ctx);
        Expression start = node.getStart() != null ? lowerExpr(node.getStart(), ctx)
                : new Literal(loc, new PrimitiveType(PrimitiveType.Kind.INT), 0, LiteralKind.INT);
        boolean endExclusive = node.isEndExclusive();
        Expression end;
        if (node.getEnd() != null) {
            end = lowerExpr(node.getEnd(), ctx);
        } else {
            // end 省略 → target.size()，已经是 exclusive bound
            end = new HirCall(loc, null,
                    new MemberExpr(loc, target, "size"),
                    Collections.<HirType>emptyList(), Collections.<Expression>emptyList());
            endExclusive = true;
        }
        RangeExpr range = new RangeExpr(loc, start, end, null, endExclusive);
        return new IndexExpr(loc, target, range);
    }

    @Override
    public AstNode visitSpreadExpr(SpreadExpr node, LoweringContext ctx) {
        // spread 由调用点处理，这里直接下降
        return lowerExpr(node.getOperand(), ctx);
    }

    /**
     * 脱糖：a |> f → f(a)；a |> f(b) → f(a, b)
     */
    @Override
    public AstNode visitPipelineExpr(PipelineExpr node, LoweringContext ctx) {
        Expression left = lowerExpr(node.getLeft(), ctx);
        Expression rightExpr = node.getRight();

        // a |> f(b) → f(a, b): 右边是调用表达式时，将左边作为第一个参数
        // 但如果有占位符 _，则使用部分应用: a |> f(_, b) → f(_, b)(a)
        if (rightExpr instanceof CallExpr) {
            CallExpr callRight = (CallExpr) rightExpr;
            boolean hasPlaceholder = false;
            if (callRight.getArgs() != null) {
                for (CallExpr.Argument arg : callRight.getArgs()) {
                    if (arg.getValue() instanceof PlaceholderExpr) {
                        hasPlaceholder = true;
                        break;
                    }
                }
            }
            if (!hasPlaceholder) {
                // 特殊情况: a |> f { lambda } → a.f(lambda) (方法调用风格)
                // 当右侧只有尾随 lambda 没有常规参数时，视为方法调用
                // 例如: list |> filter { it > 0 } → list.filter({ it > 0 })
                boolean noRegularArgs = (callRight.getArgs() == null || callRight.getArgs().isEmpty());
                if (noRegularArgs && callRight.hasTrailingLambda() && callRight.getCallee() instanceof Identifier) {
                    String methodName = ((Identifier) callRight.getCallee()).getName();
                    MemberExpr member = new MemberExpr(node.getLocation(), left, methodName);
                    List<Expression> args = new ArrayList<>();
                    args.add(lowerExpr(callRight.getTrailingLambda(), ctx));
                    return new HirCall(node.getLocation(), null, member, Collections.emptyList(), args);
                }
                // a |> f(b) → f(a, b): 常规参数形式
                Expression callee = lowerExpr(callRight.getCallee(), ctx);
                List<Expression> args = new ArrayList<>();
                args.add(left);
                if (callRight.getArgs() != null) {
                    for (CallExpr.Argument arg : callRight.getArgs()) {
                        args.add(lowerExpr(arg.getValue(), ctx));
                    }
                }
                if (callRight.hasTrailingLambda()) {
                    args.add(lowerExpr(callRight.getTrailingLambda(), ctx));
                }
                return new HirCall(node.getLocation(), null, callee, Collections.emptyList(), args);
            }
            // 有占位符: a |> f(_, b) → f(_, b)(a) — 部分应用后调用
        }

        // a |> f → f(a): 函数调用形式（运行时会回退尝试 a.f() 方法调用）
        Expression right = lowerExpr(rightExpr, ctx);
        return new HirCall(node.getLocation(), null,
                right, Collections.emptyList(), Collections.singletonList(left));
    }

    @Override
    public AstNode visitMethodRefExpr(MethodRefExpr node, LoweringContext ctx) {
        Expression target = node.getTarget() != null ? lowerExpr(node.getTarget(), ctx) : null;
        return new MethodRefExpr(node.getLocation(),
                target, null, node.getMethodName(), node.isConstructor());
    }

    @Override
    public AstNode visitObjectLiteralExpr(ObjectLiteralExpr node, LoweringContext ctx) {
        List<HirType> superTypes = lowerSuperTypes(node.getSuperTypes());
        List<HirDecl> members = lowerDecls(node.getMembers(), ctx);
        List<Expression> superCtorArgs = new ArrayList<>();
        for (Expression arg : node.getSuperConstructorArgs()) {
            superCtorArgs.add(lowerExpr(arg, ctx));
        }
        return new HirObjectLiteral(node.getLocation(), null, superTypes, members, superCtorArgs);
    }

    @Override
    public AstNode visitCollectionLiteral(CollectionLiteral node, LoweringContext ctx) {
        HirCollectionLiteral.Kind kind = HirCollectionLiteral.Kind.valueOf(node.getKind().name());
        List<Expression> elements;
        java.util.Set<Integer> spreadIndices = null;
        if (node.getKind() == CollectionLiteral.CollectionKind.MAP && node.getMapEntries() != null) {
            // Map entries → pairs as elements
            elements = new ArrayList<>();
            for (CollectionLiteral.MapEntry entry : node.getMapEntries()) {
                Expression key = lowerExpr(entry.getKey(), ctx);
                Expression value = lowerExpr(entry.getValue(), ctx);
                // key to value → BinaryExpr(key, TO, value)
                elements.add(new BinaryExpr(entry.getLocation(), null,
                        key, BinaryOp.TO, value));
            }
        } else {
            elements = new ArrayList<>();
            for (int i = 0; i < node.getElements().size(); i++) {
                Expression elem = node.getElements().get(i);
                if (elem instanceof SpreadExpr) {
                    if (spreadIndices == null) spreadIndices = new java.util.HashSet<>();
                    spreadIndices.add(elements.size());
                }
                elements.add(lowerExpr(elem, ctx));
            }
        }
        return new HirCollectionLiteral(node.getLocation(), null, kind, elements, spreadIndices);
    }

    /**
     * 脱糖：字符串插值 "...$x..." → StringBuilder 链
     * 简化处理：用二元 ADD 链表示字符串连接
     */
    @Override
    public AstNode visitStringInterpolation(StringInterpolation node, LoweringContext ctx) {
        Expression result = null;
        for (StringInterpolation.StringPart part : node.getParts()) {
            Expression partExpr;
            if (part instanceof StringInterpolation.LiteralPart) {
                String value = ((StringInterpolation.LiteralPart) part).getValue();
                partExpr = new Literal(part.getLocation(), new ClassType("String"),
                        value, LiteralKind.STRING);
            } else {
                Expression expr = ((StringInterpolation.ExprPart) part).getExpression();
                partExpr = lowerExpr(expr, ctx);
            }
            if (result == null) {
                result = partExpr;
            } else {
                result = new BinaryExpr(node.getLocation(), new ClassType("String"),
                        result, BinaryOp.ADD, partExpr);
            }
        }
        return result != null ? result : new Literal(node.getLocation(),
                new ClassType("String"), "", LiteralKind.STRING);
    }

    @Override
    public AstNode visitPlaceholderExpr(PlaceholderExpr node, LoweringContext ctx) {
        return new Identifier(node.getLocation(), "_");
    }

    /**
     * 脱糖：a ?: b → { val tmp = a; if (tmp != null) tmp else b }
     */
    @Override
    public AstNode visitElvisExpr(ElvisExpr node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        Expression left = lowerExpr(node.getLeft(), ctx);
        Expression right = lowerExpr(node.getRight(), ctx);

        Expression nullCheck = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                left, BinaryOp.NE, ctx.nullLiteral(loc));

        return new ConditionalExpr(loc,nullCheck, left, right);
    }

    /**
     * 脱糖：a?.method() → { val tmp = a; if (tmp != null) tmp.method() else null }
     */
    @Override
    public AstNode visitSafeCallExpr(SafeCallExpr node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        Expression target = lowerExpr(node.getTarget(), ctx);

        Expression nullCheck = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                target, BinaryOp.NE, ctx.nullLiteral(loc));

        Expression memberAccess = new MemberExpr(loc, target, node.getMember());
        Expression thenExpr;
        if (node.isMethodCall()) {
            List<Expression> args = new ArrayList<>();
            for (CallExpr.Argument arg : node.getArgs()) {
                args.add(lowerExpr(arg.getValue(), ctx));
            }
            thenExpr = new HirCall(loc, null, memberAccess,
                    Collections.emptyList(), args);
        } else {
            thenExpr = memberAccess;
        }

        return new ConditionalExpr(loc,nullCheck, thenExpr, ctx.nullLiteral(loc));
    }

    /**
     * 脱糖：a?[i] → { val tmp = a; if (tmp != null) tmp[i] else null }
     */
    @Override
    public AstNode visitSafeIndexExpr(SafeIndexExpr node, LoweringContext ctx) {
        SourceLocation loc = node.getLocation();
        Expression target = lowerExpr(node.getTarget(), ctx);
        Expression index = lowerExpr(node.getIndex(), ctx);

        Expression nullCheck = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                target, BinaryOp.NE, ctx.nullLiteral(loc));

        return new ConditionalExpr(loc,nullCheck,
                new IndexExpr(loc, target, index), ctx.nullLiteral(loc));
    }

    @Override
    public AstNode visitNotNullExpr(NotNullExpr node, LoweringContext ctx) {
        return new NotNullExpr(node.getLocation(), lowerExpr(node.getOperand(), ctx));
    }

    /**
     * 脱糖：result? → { val tmp = result; if (tmp.isErr()) return tmp; tmp.value }
     */
    @Override
    public AstNode visitErrorPropagationExpr(ErrorPropagationExpr node, LoweringContext ctx) {
        Expression operand = lowerExpr(node.getOperand(), ctx);
        return new ErrorPropagationExpr(node.getLocation(), operand);
    }

    @Override
    public AstNode visitScopeShorthandExpr(ScopeShorthandExpr node, LoweringContext ctx) {
        // obj?.{ block } → if (obj != null) obj.apply { block } else null
        // 脱糖为三元条件表达式
        SourceLocation loc = node.getLocation();
        String tmp = ctx.freshTemp();
        Expression target = lowerExpr(node.getTarget(), ctx);

        // 降级 block 为 lambda
        AstNode blockNode = node.getBlock().accept(this, ctx);
        HirLambda lambda = new HirLambda(loc, null, Collections.emptyList(), blockNode, Collections.emptyList());

        // val tmp = target; if (tmp != null) tmp.apply(lambda) else null
        Expression nullCheck = new BinaryExpr(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                ctx.tempRef(loc, tmp, null), BinaryOp.NE, ctx.nullLiteral(loc));
        // tmp.apply(lambda) — 使用 apply scope function
        Expression applyCall = new HirCall(loc, null,
                new MemberExpr(loc, ctx.tempRef(loc, tmp, null), "apply"),
                Collections.<HirType>emptyList(),
                Collections.<Expression>singletonList(lambda));
        Expression conditional = new ConditionalExpr(loc,nullCheck,
                applyCall, ctx.nullLiteral(loc));

        List<Statement> stmts = Collections.singletonList(ctx.makeTempVal(loc, tmp, null, target));
        return new BlockExpr(loc,stmts, conditional);
    }

    @Override
    public AstNode visitJumpExpr(JumpExpr node, LoweringContext ctx) {
        // return/break/continue 在表达式位置（如 ?: return Err(...)）
        // 包装为 BlockExpr: { stmt; null }（null 不可达，因为 stmt 会跳转）
        AstNode lowered = node.getStatement().accept(this, ctx);
        if (lowered instanceof Statement) {
            return new BlockExpr(node.getLocation(),
                    Collections.singletonList((Statement) lowered),
                    ctx.nullLiteral(node.getLocation()));
        }
        return lowered;
    }

    // ========== 类型节点（不直接转换，由 lowerType 处理） ==========

    @Override
    public AstNode visitSimpleType(SimpleType node, LoweringContext ctx) {
        return null; // 通过 lowerType() 处理
    }

    @Override
    public AstNode visitNullableType(NullableType node, LoweringContext ctx) {
        return null;
    }

    @Override
    public AstNode visitFunctionType(FunctionType node, LoweringContext ctx) {
        return null;
    }

    @Override
    public AstNode visitGenericType(GenericType node, LoweringContext ctx) {
        return null;
    }
}
