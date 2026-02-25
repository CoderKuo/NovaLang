package com.novalang.compiler.formatter;

import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.compiler.ast.type.*;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * NovaLang AST 代码格式化器
 *
 * <p>遍历 AST，按统一格式规则输出源码。</p>
 */
public class NovaFormatter implements AstVisitor<Void, FormatterContext> {

    /**
     * 格式化程序
     */
    public String format(Program program, FormatConfig config) {
        FormatterContext ctx = new FormatterContext(config);
        visitProgram(program, ctx);
        return ctx.getOutput();
    }

    /**
     * 使用默认配置格式化
     */
    public String format(Program program) {
        return format(program, new FormatConfig());
    }

    // ============ 声明 ============

    @Override
    public Void visitProgram(Program node, FormatterContext ctx) {
        // 包声明
        if (node.getPackageDecl() != null) {
            visitPackageDecl(node.getPackageDecl(), ctx);
            ctx.newLine();
            ctx.newLine();
        }

        // 导入声明
        if (!node.getImports().isEmpty()) {
            for (ImportDecl imp : node.getImports()) {
                visitImportDecl(imp, ctx);
                ctx.newLine();
            }
            ctx.newLine();
        }

        // 声明
        List<Declaration> decls = node.getDeclarations();
        for (int i = 0; i < decls.size(); i++) {
            decls.get(i).accept(this, ctx);
            ctx.newLine();
            // 顶层声明之间空一行
            if (i < decls.size() - 1) {
                ctx.newLine();
            }
        }

        return null;
    }

    @Override
    public Void visitPackageDecl(PackageDecl node, FormatterContext ctx) {
        ctx.append("package ");
        ctx.append(node.getName().getFullName());
        return null;
    }

    @Override
    public Void visitImportDecl(ImportDecl node, FormatterContext ctx) {
        ctx.append("import ");
        if (node.isStatic()) {
            ctx.append("static ");
        }
        ctx.append(node.getName().getFullName());
        if (node.isWildcard()) {
            ctx.append(".*");
        }
        if (node.hasAlias()) {
            ctx.append(" as ");
            ctx.append(node.getAlias());
        }
        return null;
    }

    @Override
    public Void visitClassDecl(ClassDecl node, FormatterContext ctx) {
        // 注解
        formatAnnotations(node.getAnnotations(), ctx);

        // 修饰符
        if (node.isAnnotation()) {
            ctx.append("annotation ");
        }
        formatModifiers(node.getModifiers(), ctx);
        if (node.isAbstract() && !node.hasModifier(Modifier.ABSTRACT)) {
            ctx.append("abstract ");
        }
        if (node.isSealed()) {
            ctx.append("sealed ");
        }
        if (node.isOpen()) {
            ctx.append("open ");
        }

        ctx.append("class ");
        ctx.append(node.getName());

        // 泛型参数
        formatTypeParams(node.getTypeParams(), ctx);

        // 主构造器参数
        if (node.hasPrimaryConstructor()) {
            ctx.append("(");
            formatParameterList(node.getPrimaryConstructorParams(), ctx);
            ctx.append(")");
        }

        // 父类/接口
        if (!node.getSuperTypes().isEmpty()) {
            ctx.append(" : ");
            formatTypeRefList(node.getSuperTypes(), ctx);
        }

        // 成员
        if (!node.getMembers().isEmpty()) {
            ctx.append(" {");
            ctx.newLine();
            ctx.indent();
            formatMemberList(node.getMembers(), ctx);
            ctx.dedent();
            ctx.append("}");
        }

        return null;
    }

    @Override
    public Void visitInterfaceDecl(InterfaceDecl node, FormatterContext ctx) {
        formatAnnotations(node.getAnnotations(), ctx);
        formatModifiers(node.getModifiers(), ctx);
        ctx.append("interface ");
        ctx.append(node.getName());

        formatTypeParams(node.getTypeParams(), ctx);

        if (!node.getSuperTypes().isEmpty()) {
            ctx.append(" : ");
            formatTypeRefList(node.getSuperTypes(), ctx);
        }

        if (!node.getMembers().isEmpty()) {
            ctx.append(" {");
            ctx.newLine();
            ctx.indent();
            formatMemberList(node.getMembers(), ctx);
            ctx.dedent();
            ctx.append("}");
        }

        return null;
    }

    @Override
    public Void visitObjectDecl(ObjectDecl node, FormatterContext ctx) {
        formatAnnotations(node.getAnnotations(), ctx);
        formatModifiers(node.getModifiers(), ctx);
        if (node.isCompanion()) {
            ctx.append("companion ");
        }
        ctx.append("object ");
        if (node.getName() != null) {
            ctx.append(node.getName());
        }

        if (!node.getSuperTypes().isEmpty()) {
            ctx.append(" : ");
            formatTypeRefList(node.getSuperTypes(), ctx);
        }

        if (!node.getMembers().isEmpty()) {
            ctx.append(" {");
            ctx.newLine();
            ctx.indent();
            formatMemberList(node.getMembers(), ctx);
            ctx.dedent();
            ctx.append("}");
        }

        return null;
    }

    @Override
    public Void visitEnumDecl(EnumDecl node, FormatterContext ctx) {
        formatAnnotations(node.getAnnotations(), ctx);
        formatModifiers(node.getModifiers(), ctx);
        ctx.append("enum class ");
        ctx.append(node.getName());

        if (node.getPrimaryConstructorParams() != null && !node.getPrimaryConstructorParams().isEmpty()) {
            ctx.append("(");
            formatParameterList(node.getPrimaryConstructorParams(), ctx);
            ctx.append(")");
        }

        if (!node.getSuperTypes().isEmpty()) {
            ctx.append(" : ");
            formatTypeRefList(node.getSuperTypes(), ctx);
        }

        ctx.append(" {");
        ctx.newLine();
        ctx.indent();

        // 枚举条目
        List<EnumDecl.EnumEntry> entries = node.getEntries();
        for (int i = 0; i < entries.size(); i++) {
            EnumDecl.EnumEntry entry = entries.get(i);
            ctx.append(entry.getName());
            if (entry.getArgs() != null && !entry.getArgs().isEmpty()) {
                ctx.append("(");
                formatExpressionList(entry.getArgs(), ctx);
                ctx.append(")");
            }
            if (entry.getMembers() != null && !entry.getMembers().isEmpty()) {
                ctx.append(" {");
                ctx.newLine();
                ctx.indent();
                formatMemberList(entry.getMembers(), ctx);
                ctx.dedent();
                ctx.append("}");
            }
            if (i < entries.size() - 1) {
                ctx.append(",");
                ctx.newLine();
            } else if (!node.getMembers().isEmpty()) {
                ctx.append(";");
                ctx.newLine();
            }
        }

        // 成员
        if (!node.getMembers().isEmpty()) {
            ctx.newLine();
            formatMemberList(node.getMembers(), ctx);
        }

        ctx.dedent();
        ctx.append("}");
        return null;
    }

    @Override
    public Void visitFunDecl(FunDecl node, FormatterContext ctx) {
        formatAnnotations(node.getAnnotations(), ctx);
        formatModifiers(node.getModifiers(), ctx);

        if (node.isInline()) ctx.append("inline ");
        if (node.isOperator()) ctx.append("operator ");
        if (node.isSuspend()) ctx.append("suspend ");

        ctx.append("fun ");

        // 泛型参数
        formatTypeParams(node.getTypeParams(), ctx);
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            ctx.space();
        }

        // 扩展函数接收者
        if (node.isExtensionFunction()) {
            formatTypeRef(node.getReceiverType(), ctx);
            ctx.append(".");
        }

        ctx.append(node.getName());
        ctx.append("(");
        formatParameterList(node.getParams(), ctx);
        ctx.append(")");

        // 返回类型
        if (node.getReturnType() != null) {
            ctx.append(": ");
            formatTypeRef(node.getReturnType(), ctx);
        }

        // 函数体
        if (node.getBody() != null) {
            if (node.getBody() instanceof Block) {
                ctx.append(" ");
                formatBlock((Block) node.getBody(), ctx);
            } else {
                // 表达式体
                ctx.append(" = ");
                formatExpression((Expression) node.getBody(), ctx);
            }
        }

        return null;
    }

    @Override
    public Void visitPropertyDecl(PropertyDecl node, FormatterContext ctx) {
        formatAnnotations(node.getAnnotations(), ctx);
        formatModifiers(node.getModifiers(), ctx);

        if (node.isConst()) ctx.append("const ");
        ctx.append(node.isVal() ? "val " : "var ");

        // 泛型参数
        formatTypeParams(node.getTypeParams(), ctx);
        if (node.getTypeParams() != null && !node.getTypeParams().isEmpty()) {
            ctx.space();
        }

        // 扩展属性
        if (node.isExtensionProperty()) {
            formatTypeRef(node.getReceiverType(), ctx);
            ctx.append(".");
        }

        ctx.append(node.getName());

        // 类型
        if (node.getType() != null) {
            ctx.append(": ");
            formatTypeRef(node.getType(), ctx);
        }

        // 初始化器
        if (node.hasInitializer()) {
            ctx.append(" = ");
            formatExpression(node.getInitializer(), ctx);
        }

        // getter/setter
        if (node.getGetter() != null) {
            ctx.newLine();
            ctx.indent();
            formatPropertyAccessor(node.getGetter(), ctx);
            ctx.dedent();
        }
        if (node.getSetter() != null) {
            ctx.newLine();
            ctx.indent();
            formatPropertyAccessor(node.getSetter(), ctx);
            ctx.dedent();
        }

        return null;
    }

    @Override
    public Void visitTypeAliasDecl(TypeAliasDecl node, FormatterContext ctx) {
        formatModifiers(node.getModifiers(), ctx);
        ctx.append("typealias ");
        ctx.append(node.getName());
        formatTypeParams(node.getTypeParams(), ctx);
        ctx.append(" = ");
        formatTypeRef(node.getAliasedType(), ctx);
        return null;
    }

    @Override
    public Void visitParameter(Parameter node, FormatterContext ctx) {
        // 修饰符
        for (Modifier mod : node.getModifiers()) {
            ctx.append(mod.toSourceString());
            ctx.space();
        }

        if (node.isProperty()) {
            ctx.append(node.hasModifier(Modifier.FINAL) ? "val " : "var ");
        }

        if (node.isVararg()) {
            ctx.append("vararg ");
        }

        ctx.append(node.getName());

        if (node.getType() != null) {
            ctx.append(": ");
            formatTypeRef(node.getType(), ctx);
        }

        if (node.hasDefaultValue()) {
            ctx.append(" = ");
            formatExpression(node.getDefaultValue(), ctx);
        }

        return null;
    }

    @Override
    public Void visitQualifiedName(QualifiedName node, FormatterContext ctx) {
        ctx.append(node.getFullName());
        return null;
    }

    @Override
    public Void visitDestructuringDecl(DestructuringDecl node, FormatterContext ctx) {
        ctx.append(node.isVal() ? "val (" : "var (");
        formatJoined(node.getNames(), ctx, ", ", (name, c) -> c.append(name != null ? name : "_"));
        ctx.append(") = ");
        formatExpression(node.getInitializer(), ctx);
        return null;
    }

    // ============ 语句 ============

    @Override
    public Void visitBlock(Block node, FormatterContext ctx) {
        formatBlock(node, ctx);
        return null;
    }

    @Override
    public Void visitExpressionStmt(ExpressionStmt node, FormatterContext ctx) {
        formatExpression(node.getExpression(), ctx);
        return null;
    }

    @Override
    public Void visitIfStmt(IfStmt node, FormatterContext ctx) {
        ctx.append("if (");
        formatExpression(node.getCondition(), ctx);
        ctx.append(") ");

        if (node.getThenBranch() instanceof Block) {
            formatBlock((Block) node.getThenBranch(), ctx);
        } else {
            node.getThenBranch().accept(this, ctx);
        }

        if (node.hasElse()) {
            ctx.append(" else ");
            if (node.getElseBranch() instanceof Block) {
                formatBlock((Block) node.getElseBranch(), ctx);
            } else if (node.getElseBranch() instanceof IfStmt) {
                visitIfStmt((IfStmt) node.getElseBranch(), ctx);
            } else {
                node.getElseBranch().accept(this, ctx);
            }
        }

        return null;
    }

    @Override
    public Void visitWhenStmt(WhenStmt node, FormatterContext ctx) {
        ctx.append("when");
        if (node.getSubject() != null) {
            ctx.append(" (");
            formatExpression(node.getSubject(), ctx);
            ctx.append(")");
        }
        ctx.append(" {");
        ctx.newLine();
        ctx.indent();

        for (WhenBranch branch : node.getBranches()) {
            formatWhenBranch(branch, ctx);
            ctx.newLine();
        }

        if (node.getElseBranch() != null) {
            ctx.append("else -> ");
            node.getElseBranch().accept(this, ctx);
            ctx.newLine();
        }

        ctx.dedent();
        ctx.append("}");
        return null;
    }

    @Override
    public Void visitForStmt(ForStmt node, FormatterContext ctx) {
        if (node.getLabel() != null) {
            ctx.append(node.getLabel());
            ctx.append("@ ");
        }
        ctx.append("for (");
        List<String> vars = node.getVariables();
        if (vars.size() == 1) {
            ctx.append(vars.get(0));
        } else {
            ctx.append("(");
            formatJoined(vars, ctx, ", ", (v, c) -> c.append(v));
            ctx.append(")");
        }
        ctx.append(" in ");
        formatExpression(node.getIterable(), ctx);
        ctx.append(") ");

        if (node.getBody() instanceof Block) {
            formatBlock((Block) node.getBody(), ctx);
        } else {
            node.getBody().accept(this, ctx);
        }

        return null;
    }

    @Override
    public Void visitWhileStmt(WhileStmt node, FormatterContext ctx) {
        if (node.getLabel() != null) {
            ctx.append(node.getLabel());
            ctx.append("@ ");
        }
        ctx.append("while (");
        formatExpression(node.getCondition(), ctx);
        ctx.append(") ");

        if (node.getBody() instanceof Block) {
            formatBlock((Block) node.getBody(), ctx);
        } else {
            node.getBody().accept(this, ctx);
        }

        return null;
    }

    @Override
    public Void visitDoWhileStmt(DoWhileStmt node, FormatterContext ctx) {
        if (node.getLabel() != null) {
            ctx.append(node.getLabel());
            ctx.append("@ ");
        }
        ctx.append("do ");

        if (node.getBody() instanceof Block) {
            formatBlock((Block) node.getBody(), ctx);
        } else {
            node.getBody().accept(this, ctx);
        }

        ctx.append(" while (");
        formatExpression(node.getCondition(), ctx);
        ctx.append(")");

        return null;
    }

    @Override
    public Void visitTryStmt(TryStmt node, FormatterContext ctx) {
        ctx.append("try ");
        formatBlock(node.getTryBlock(), ctx);

        for (CatchClause catchClause : node.getCatchClauses()) {
            ctx.append(" catch (");
            ctx.append(catchClause.getParamName());
            if (catchClause.getParamType() != null) {
                ctx.append(": ");
                formatTypeRef(catchClause.getParamType(), ctx);
            }
            ctx.append(") ");
            formatBlock(catchClause.getBody(), ctx);
        }

        if (node.getFinallyBlock() != null) {
            ctx.append(" finally ");
            formatBlock(node.getFinallyBlock(), ctx);
        }

        return null;
    }

    @Override
    public Void visitReturnStmt(ReturnStmt node, FormatterContext ctx) {
        ctx.append("return");
        if (node.getLabel() != null) {
            ctx.append("@");
            ctx.append(node.getLabel());
        }
        if (node.hasValue()) {
            ctx.space();
            formatExpression(node.getValue(), ctx);
        }
        return null;
    }

    @Override
    public Void visitBreakStmt(BreakStmt node, FormatterContext ctx) {
        ctx.append("break");
        if (node.getLabel() != null) {
            ctx.append("@");
            ctx.append(node.getLabel());
        }
        return null;
    }

    @Override
    public Void visitContinueStmt(ContinueStmt node, FormatterContext ctx) {
        ctx.append("continue");
        if (node.getLabel() != null) {
            ctx.append("@");
            ctx.append(node.getLabel());
        }
        return null;
    }

    @Override
    public Void visitThrowStmt(ThrowStmt node, FormatterContext ctx) {
        ctx.append("throw ");
        formatExpression(node.getException(), ctx);
        return null;
    }

    @Override
    public Void visitGuardStmt(GuardStmt node, FormatterContext ctx) {
        ctx.append("guard ");
        if (node.getBindingName() != null) {
            ctx.append("val ");
            ctx.append(node.getBindingName());
            ctx.append(" = ");
        }
        formatExpression(node.getExpression(), ctx);
        ctx.append(" else ");
        if (node.getElseBody() instanceof Block) {
            formatBlock((Block) node.getElseBody(), ctx);
        } else {
            node.getElseBody().accept(this, ctx);
        }
        return null;
    }

    @Override
    public Void visitUseStmt(UseStmt node, FormatterContext ctx) {
        ctx.append("use");
        // UseStmt 结构取决于实际实现
        return null;
    }

    @Override
    public Void visitDeclarationStmt(DeclarationStmt node, FormatterContext ctx) {
        node.getDeclaration().accept(this, ctx);
        return null;
    }

    // ============ 表达式 ============

    @Override
    public Void visitBinaryExpr(BinaryExpr node, FormatterContext ctx) {
        formatExpression(node.getLeft(), ctx);
        ctx.append(" ");
        ctx.append(node.getOperator().toSourceString());
        ctx.append(" ");
        formatExpression(node.getRight(), ctx);
        return null;
    }

    @Override
    public Void visitUnaryExpr(UnaryExpr node, FormatterContext ctx) {
        if (node.isPrefix()) {
            ctx.append(node.getOperator().toSourceString());
            formatExpression(node.getOperand(), ctx);
        } else {
            formatExpression(node.getOperand(), ctx);
            ctx.append(node.getOperator().toSourceString());
        }
        return null;
    }

    @Override
    public Void visitCallExpr(CallExpr node, FormatterContext ctx) {
        formatExpression(node.getCallee(), ctx);

        // 类型参数
        if (node.getTypeArgs() != null && !node.getTypeArgs().isEmpty()) {
            ctx.append("<");
            formatJoined(node.getTypeArgs(), ctx, ", ", this::formatTypeRef);
            ctx.append(">");
        }

        ctx.append("(");
        formatJoined(node.getArgs(), ctx, ", ", (arg, c) -> {
            if (arg.isSpread()) c.append("*");
            if (arg.getName() != null) {
                c.append(arg.getName());
                c.append(" = ");
            }
            formatExpression(arg.getValue(), c);
        });
        ctx.append(")");

        // 尾随 Lambda
        if (node.getTrailingLambda() != null) {
            ctx.append(" ");
            formatLambda(node.getTrailingLambda(), ctx);
        }

        return null;
    }

    @Override
    public Void visitIndexExpr(IndexExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append("[");
        formatExpression(node.getIndex(), ctx);
        ctx.append("]");
        return null;
    }

    @Override
    public Void visitMemberExpr(MemberExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append(".");
        ctx.append(node.getMember());
        return null;
    }

    @Override
    public Void visitAssignExpr(AssignExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append(" ");
        ctx.append(node.getOperator().toSourceString());
        ctx.append(" ");
        formatExpression(node.getValue(), ctx);
        return null;
    }

    @Override
    public Void visitLambdaExpr(LambdaExpr node, FormatterContext ctx) {
        formatLambda(node, ctx);
        return null;
    }

    @Override
    public Void visitIfExpr(IfExpr node, FormatterContext ctx) {
        ctx.append("if (");
        formatExpression(node.getCondition(), ctx);
        ctx.append(") ");
        formatExpression(node.getThenExpr(), ctx);
        ctx.append(" else ");
        formatExpression(node.getElseExpr(), ctx);
        return null;
    }

    @Override
    public Void visitWhenExpr(WhenExpr node, FormatterContext ctx) {
        ctx.append("when");
        if (node.getSubject() != null) {
            ctx.append(" (");
            if (node.hasBinding()) {
                ctx.append("val " + node.getBindingName() + " = ");
            }
            formatExpression(node.getSubject(), ctx);
            ctx.append(")");
        }
        ctx.append(" {");
        ctx.newLine();
        ctx.indent();

        for (WhenBranch branch : node.getBranches()) {
            formatWhenBranch(branch, ctx);
            ctx.newLine();
        }

        if (node.getElseExpr() != null) {
            ctx.append("else -> ");
            formatExpression(node.getElseExpr(), ctx);
            ctx.newLine();
        }

        ctx.dedent();
        ctx.append("}");
        return null;
    }

    @Override
    public Void visitTryExpr(TryExpr node, FormatterContext ctx) {
        // try 表达式和 try 语句格式化类似
        ctx.append("try");
        return null;
    }

    @Override
    public Void visitAwaitExpr(AwaitExpr node, FormatterContext ctx) {
        ctx.append("await ");
        formatExpression(node.getOperand(), ctx);
        return null;
    }

    @Override
    public Void visitIdentifier(Identifier node, FormatterContext ctx) {
        ctx.append(node.getName());
        return null;
    }

    @Override
    public Void visitLiteral(Literal node, FormatterContext ctx) {
        Object value = node.getValue();
        switch (node.getKind()) {
            case STRING:
                ctx.append("\"");
                ctx.append(NovaStringUtils.escapeString(value.toString()));
                ctx.append("\"");
                break;
            case CHAR:
                ctx.append("'");
                ctx.append(NovaStringUtils.escapeChar((Character) value));
                ctx.append("'");
                break;
            case BOOLEAN:
                ctx.append(value.toString());
                break;
            case NULL:
                ctx.append("null");
                break;
            case LONG:
                ctx.append(value.toString());
                ctx.append("L");
                break;
            case FLOAT:
                ctx.append(value.toString());
                ctx.append("f");
                break;
            case DOUBLE:
                ctx.append(value.toString());
                break;
            case INT:
                ctx.append(value.toString());
                break;
        }
        return null;
    }

    @Override
    public Void visitThisExpr(ThisExpr node, FormatterContext ctx) {
        ctx.append("this");
        return null;
    }

    @Override
    public Void visitSuperExpr(SuperExpr node, FormatterContext ctx) {
        ctx.append("super");
        return null;
    }

    @Override
    public Void visitTypeCheckExpr(TypeCheckExpr node, FormatterContext ctx) {
        formatExpression(node.getOperand(), ctx);
        ctx.append(node.isNegated() ? " !is " : " is ");
        formatTypeRef(node.getTargetType(), ctx);
        return null;
    }

    @Override
    public Void visitTypeCastExpr(TypeCastExpr node, FormatterContext ctx) {
        formatExpression(node.getOperand(), ctx);
        ctx.append(node.isSafe() ? " as? " : " as ");
        formatTypeRef(node.getTargetType(), ctx);
        return null;
    }

    @Override
    public Void visitRangeExpr(RangeExpr node, FormatterContext ctx) {
        formatExpression(node.getStart(), ctx);
        ctx.append(node.isEndExclusive() ? "..<" : "..");
        formatExpression(node.getEnd(), ctx);
        if (node.getStep() != null) {
            ctx.append(" step ");
            formatExpression(node.getStep(), ctx);
        }
        return null;
    }

    @Override
    public Void visitSliceExpr(SliceExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append("[");
        if (node.hasStart()) {
            formatExpression(node.getStart(), ctx);
        }
        ctx.append(node.isEndExclusive() ? "..<" : "..");
        if (node.hasEnd()) {
            formatExpression(node.getEnd(), ctx);
        }
        ctx.append("]");
        return null;
    }

    @Override
    public Void visitSpreadExpr(SpreadExpr node, FormatterContext ctx) {
        ctx.append("*");
        formatExpression(node.getOperand(), ctx);
        return null;
    }

    @Override
    public Void visitPipelineExpr(PipelineExpr node, FormatterContext ctx) {
        formatExpression(node.getLeft(), ctx);
        ctx.append(" |> ");
        formatExpression(node.getRight(), ctx);
        return null;
    }

    @Override
    public Void visitMethodRefExpr(MethodRefExpr node, FormatterContext ctx) {
        if (node.getTarget() != null) {
            formatExpression(node.getTarget(), ctx);
        }
        ctx.append("::");
        ctx.append(node.isConstructor() ? "new" : node.getMethodName());
        return null;
    }

    @Override
    public Void visitObjectLiteralExpr(ObjectLiteralExpr node, FormatterContext ctx) {
        ctx.append("object");
        if (!node.getSuperTypes().isEmpty()) {
            ctx.append(" : ");
            // 输出第一个父类型
            node.getSuperTypes().get(0).accept(this, ctx);
            // 如果有构造器参数，在第一个父类型后输出
            if (!node.getSuperConstructorArgs().isEmpty()) {
                ctx.append("(");
                formatExpressionList(node.getSuperConstructorArgs(), ctx);
                ctx.append(")");
            }
            // 输出后续父类型
            for (int i = 1; i < node.getSuperTypes().size(); i++) {
                ctx.append(", ");
                node.getSuperTypes().get(i).accept(this, ctx);
            }
        }
        ctx.append(" {");
        ctx.newLine();
        ctx.indent();
        formatMemberList(node.getMembers(), ctx);
        ctx.dedent();
        ctx.append("}");
        return null;
    }

    @Override
    public Void visitCollectionLiteral(CollectionLiteral node, FormatterContext ctx) {
        switch (node.getKind()) {
            case LIST:
                ctx.append("[");
                formatExpressionList(node.getElements(), ctx);
                ctx.append("]");
                break;
            case SET:
                ctx.append("#{");
                formatExpressionList(node.getElements(), ctx);
                ctx.append("}");
                break;
            case MAP:
                ctx.append("#{");
                formatJoined(node.getMapEntries(), ctx, ", ", (entry, c) -> {
                    formatExpression(entry.getKey(), c);
                    c.append(": ");
                    formatExpression(entry.getValue(), c);
                });
                ctx.append("}");
                break;
        }
        return null;
    }

    @Override
    public Void visitStringInterpolation(StringInterpolation node, FormatterContext ctx) {
        ctx.append("\"");
        for (StringInterpolation.StringPart part : node.getParts()) {
            if (part instanceof StringInterpolation.LiteralPart) {
                ctx.append(NovaStringUtils.escapeString(((StringInterpolation.LiteralPart) part).getValue()));
            } else if (part instanceof StringInterpolation.ExprPart) {
                ctx.append("${");
                formatExpression(((StringInterpolation.ExprPart) part).getExpression(), ctx);
                ctx.append("}");
            }
        }
        ctx.append("\"");
        return null;
    }

    @Override
    public Void visitPlaceholderExpr(PlaceholderExpr node, FormatterContext ctx) {
        ctx.append("_");
        return null;
    }

    @Override
    public Void visitConditionalExpr(ConditionalExpr node, FormatterContext ctx) {
        formatExpression(node.getCondition(), ctx);
        ctx.append(" ? ");
        formatExpression(node.getThenExpr(), ctx);
        ctx.append(" : ");
        formatExpression(node.getElseExpr(), ctx);
        return null;
    }

    @Override
    public Void visitElvisExpr(ElvisExpr node, FormatterContext ctx) {
        formatExpression(node.getLeft(), ctx);
        ctx.append(" ?: ");
        formatExpression(node.getRight(), ctx);
        return null;
    }

    @Override
    public Void visitSafeCallExpr(SafeCallExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append("?.");
        ctx.append(node.getMember());
        return null;
    }

    @Override
    public Void visitSafeIndexExpr(SafeIndexExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append("?[");
        formatExpression(node.getIndex(), ctx);
        ctx.append("]");
        return null;
    }

    @Override
    public Void visitNotNullExpr(NotNullExpr node, FormatterContext ctx) {
        formatExpression(node.getOperand(), ctx);
        ctx.append("!!");
        return null;
    }

    @Override
    public Void visitErrorPropagationExpr(ErrorPropagationExpr node, FormatterContext ctx) {
        formatExpression(node.getOperand(), ctx);
        ctx.append("?");
        return null;
    }

    @Override
    public Void visitScopeShorthandExpr(ScopeShorthandExpr node, FormatterContext ctx) {
        formatExpression(node.getTarget(), ctx);
        ctx.append("?.{");
        ctx.newLine();
        ctx.indent();
        for (Statement stmt : node.getBlock().getStatements()) {
            stmt.accept(this, ctx);
            ctx.newLine();
        }
        ctx.dedent();
        ctx.append("}");
        return null;
    }

    // ============ 类型 ============

    @Override
    public Void visitSimpleType(SimpleType node, FormatterContext ctx) {
        ctx.append(node.getName().getFullName());
        return null;
    }

    @Override
    public Void visitNullableType(NullableType node, FormatterContext ctx) {
        formatTypeRef(node.getInnerType(), ctx);
        ctx.append("?");
        return null;
    }

    @Override
    public Void visitFunctionType(FunctionType node, FormatterContext ctx) {
        if (node.isSuspend()) {
            ctx.append("suspend ");
        }
        if (node.getReceiverType() != null) {
            formatTypeRef(node.getReceiverType(), ctx);
            ctx.append(".");
        }
        ctx.append("(");
        formatJoined(node.getParamTypes(), ctx, ", ", this::formatTypeRef);
        ctx.append(") -> ");
        formatTypeRef(node.getReturnType(), ctx);
        return null;
    }

    @Override
    public Void visitGenericType(GenericType node, FormatterContext ctx) {
        ctx.append(node.getName().getFullName());
        ctx.append("<");
        formatJoined(node.getTypeArgs(), ctx, ", ", (arg, c) -> {
            if (arg.getVariance() != null) {
                c.append(arg.getVariance().toString().toLowerCase());
                c.space();
            }
            formatTypeRef(arg.getType(), c);
        });
        ctx.append(">");
        return null;
    }

    // ============ 辅助方法 ============

    private <T> void formatJoined(List<T> items, FormatterContext ctx, String separator,
                                   BiConsumer<T, FormatterContext> formatter) {
        for (int i = 0; i < items.size(); i++) {
            formatter.accept(items.get(i), ctx);
            if (i < items.size() - 1) {
                ctx.append(separator);
            }
        }
    }

    private void formatExpression(Expression expr, FormatterContext ctx) {
        if (expr != null) {
            expr.accept(this, ctx);
        }
    }

    private void formatBlock(Block block, FormatterContext ctx) {
        ctx.append("{");
        if (block.getStatements().isEmpty()) {
            ctx.append("}");
            return;
        }
        ctx.newLine();
        ctx.indent();
        for (Statement stmt : block.getStatements()) {
            stmt.accept(this, ctx);
            ctx.newLine();
        }
        ctx.dedent();
        ctx.append("}");
    }

    private void formatAnnotations(List<Annotation> annotations, FormatterContext ctx) {
        if (annotations == null || annotations.isEmpty()) return;
        for (Annotation ann : annotations) {
            ctx.append("@");
            ctx.append(ann.getName());
            if (ann.getArgs() != null && !ann.getArgs().isEmpty()) {
                ctx.append("(");
                formatJoined(ann.getArgs(), ctx, ", ", (arg, c) -> {
                    if (arg.getName() != null) {
                        c.append(arg.getName());
                        c.append(" = ");
                    }
                    formatExpression(arg.getValue(), c);
                });
                ctx.append(")");
            }
            ctx.newLine();
        }
    }

    private void formatModifiers(List<Modifier> modifiers, FormatterContext ctx) {
        if (modifiers == null) return;
        for (Modifier mod : modifiers) {
            // 跳过一些在特定上下文中已处理的修饰符
            if (mod == Modifier.ABSTRACT || mod == Modifier.SEALED || mod == Modifier.OPEN ||
                mod == Modifier.FINAL || mod == Modifier.CONST || mod == Modifier.INLINE ||
                mod == Modifier.OPERATOR || mod == Modifier.SUSPEND) {
                continue;
            }
            ctx.append(mod.toSourceString());
            ctx.space();
        }
    }

    private void formatTypeParams(List<TypeParameter> typeParams, FormatterContext ctx) {
        if (typeParams == null || typeParams.isEmpty()) return;
        ctx.append("<");
        formatJoined(typeParams, ctx, ", ", (tp, c) -> {
            if (tp.isReified()) c.append("reified ");
            if (tp.getVariance() != null) {
                c.append(tp.getVariance().toString().toLowerCase());
                c.space();
            }
            c.append(tp.getName());
            if (tp.getUpperBound() != null) {
                c.append(" : ");
                formatTypeRef(tp.getUpperBound(), c);
            }
        });
        ctx.append(">");
    }

    private void formatTypeRef(TypeRef typeRef, FormatterContext ctx) {
        if (typeRef != null) {
            typeRef.accept(this, ctx);
        }
    }

    private void formatTypeRefList(List<TypeRef> types, FormatterContext ctx) {
        formatJoined(types, ctx, ", ", this::formatTypeRef);
    }

    private void formatParameterList(List<Parameter> params, FormatterContext ctx) {
        if (params == null) return;
        formatJoined(params, ctx, ", ", this::visitParameter);
    }

    private void formatExpressionList(List<Expression> exprs, FormatterContext ctx) {
        if (exprs == null) return;
        formatJoined(exprs, ctx, ", ", this::formatExpression);
    }

    private void formatMemberList(List<Declaration> members, FormatterContext ctx) {
        for (int i = 0; i < members.size(); i++) {
            members.get(i).accept(this, ctx);
            ctx.newLine();
            if (i < members.size() - 1) {
                ctx.newLine();
            }
        }
    }

    private void formatLambda(LambdaExpr lambda, FormatterContext ctx) {
        ctx.append("{");
        if (lambda.getParams() != null && !lambda.getParams().isEmpty()) {
            ctx.append(" ");
            formatJoined(lambda.getParams(), ctx, ", ", (p, c) -> {
                c.append(p.getName());
                if (p.getType() != null) {
                    c.append(": ");
                    formatTypeRef(p.getType(), c);
                }
            });
            ctx.append(" ->");
        }
        if (lambda.getBody() instanceof Block) {
            Block block = (Block) lambda.getBody();
            if (block.getStatements().size() == 1) {
                ctx.append(" ");
                block.getStatements().get(0).accept(this, ctx);
                ctx.append(" ");
            } else {
                ctx.newLine();
                ctx.indent();
                for (Statement stmt : block.getStatements()) {
                    stmt.accept(this, ctx);
                    ctx.newLine();
                }
                ctx.dedent();
            }
        } else if (lambda.getBody() instanceof Expression) {
            ctx.append(" ");
            formatExpression((Expression) lambda.getBody(), ctx);
            ctx.append(" ");
        }
        ctx.append("}");
    }

    private void formatPropertyAccessor(PropertyAccessor accessor, FormatterContext ctx) {
        if (accessor.isGetter()) {
            ctx.append("get()");
        } else {
            ctx.append("set(");
            if (accessor.getParam() != null) {
                ctx.append(accessor.getParam().getName());
            } else {
                ctx.append("value");
            }
            ctx.append(")");
        }
        if (accessor.getBody() != null) {
            if (accessor.getBody() instanceof Block) {
                ctx.append(" ");
                formatBlock((Block) accessor.getBody(), ctx);
            } else {
                ctx.append(" = ");
                formatExpression((Expression) accessor.getBody(), ctx);
            }
        }
    }

    private void formatWhenBranch(WhenBranch branch, FormatterContext ctx) {
        List<WhenBranch.WhenCondition> conditions = branch.getConditions();
        for (int i = 0; i < conditions.size(); i++) {
            WhenBranch.WhenCondition cond = conditions.get(i);
            if (cond instanceof WhenBranch.ExpressionCondition) {
                formatExpression(((WhenBranch.ExpressionCondition) cond).getExpression(), ctx);
            } else if (cond instanceof WhenBranch.TypeCondition) {
                WhenBranch.TypeCondition tc = (WhenBranch.TypeCondition) cond;
                ctx.append(tc.isNegated() ? "!is " : "is ");
                formatTypeRef(tc.getType(), ctx);
            } else if (cond instanceof WhenBranch.RangeCondition) {
                WhenBranch.RangeCondition rc = (WhenBranch.RangeCondition) cond;
                ctx.append(rc.isNegated() ? "!in " : "in ");
                formatExpression(rc.getRange(), ctx);
            }
            if (i < conditions.size() - 1) {
                ctx.append(", ");
            }
        }
        ctx.append(" -> ");
        if (branch.getBody() instanceof Block) {
            formatBlock((Block) branch.getBody(), ctx);
        } else {
            branch.getBody().accept(this, ctx);
        }
    }

}
