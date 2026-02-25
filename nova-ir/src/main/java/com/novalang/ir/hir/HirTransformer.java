package com.novalang.ir.hir;

import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.ir.hir.expr.*;

import java.util.ArrayList;
import java.util.List;

/**
 * HIR 恒等变换基类（copy-on-change）。
 * 递归遍历所有节点，子节点无变化时返回原节点，否则构造新节点。
 * 子类可覆盖特定 visit 方法实现优化 pass。
 */
public class HirTransformer implements HirVisitor<HirNode, Void> {

    /**
     * 安全的变换入口。
     */
    public HirNode transform(HirNode node) {
        if (node == null) return null;
        try {
            HirNode result = node.accept(this, null);
            return result != null ? result : node;
        } catch (Exception e) {
            return node;
        }
    }

    // ==================== 辅助方法 ====================

    protected Expression transformExpr(Expression expr) {
        if (expr == null) return null;
        if (expr instanceof HirExpr) {
            return (Expression) ((HirExpr) expr).accept(this, null);
        }
        // AST expression types (HIR eliminated nodes)
        if (expr instanceof IndexExpr) {
            IndexExpr idx = (IndexExpr) expr;
            Expression target = transformExpr(idx.getTarget());
            Expression index = transformExpr(idx.getIndex());
            if (target == idx.getTarget() && index == idx.getIndex()) return expr;
            return new IndexExpr(idx.getLocation(), target, index);
        }
        if (expr instanceof RangeExpr) {
            RangeExpr range = (RangeExpr) expr;
            Expression start = transformExpr(range.getStart());
            Expression end = transformExpr(range.getEnd());
            Expression step = transformExpr(range.getStep());
            if (start == range.getStart() && end == range.getEnd() && step == range.getStep()) return expr;
            return new RangeExpr(range.getLocation(), start, end, step, range.isEndExclusive());
        }
        if (expr instanceof AwaitExpr) {
            AwaitExpr await = (AwaitExpr) expr;
            Expression operand = transformExpr(await.getOperand());
            if (operand == await.getOperand()) return expr;
            return new AwaitExpr(await.getLocation(), operand);
        }
        if (expr instanceof NotNullExpr) {
            NotNullExpr nn = (NotNullExpr) expr;
            Expression operand = transformExpr(nn.getOperand());
            if (operand == nn.getOperand()) return expr;
            return new NotNullExpr(nn.getLocation(), operand);
        }
        if (expr instanceof ErrorPropagationExpr) {
            ErrorPropagationExpr ep = (ErrorPropagationExpr) expr;
            Expression operand = transformExpr(ep.getOperand());
            if (operand == ep.getOperand()) return expr;
            return new ErrorPropagationExpr(ep.getLocation(), operand);
        }
        if (expr instanceof MemberExpr) {
            MemberExpr me = (MemberExpr) expr;
            Expression target = transformExpr(me.getTarget());
            if (target == me.getTarget()) return expr;
            MemberExpr result = new MemberExpr(me.getLocation(), target, me.getMember());
            result.setHirType(me.getHirType());
            return result;
        }
        if (expr instanceof TypeCheckExpr) {
            TypeCheckExpr tc = (TypeCheckExpr) expr;
            Expression operand = transformExpr(tc.getOperand());
            if (operand == tc.getOperand()) return expr;
            TypeCheckExpr result = new TypeCheckExpr(tc.getLocation(), operand, tc.getTargetType(), tc.isNegated());
            result.setHirType(tc.getHirType());
            result.setHirTargetType(tc.getHirTargetType());
            return result;
        }
        if (expr instanceof TypeCastExpr) {
            TypeCastExpr tc = (TypeCastExpr) expr;
            Expression operand = transformExpr(tc.getOperand());
            if (operand == tc.getOperand()) return expr;
            TypeCastExpr result = new TypeCastExpr(tc.getLocation(), operand, tc.getTargetType(), tc.isSafe());
            result.setHirType(tc.getHirType());
            result.setHirTargetType(tc.getHirTargetType());
            return result;
        }
        if (expr instanceof MethodRefExpr) {
            MethodRefExpr mr = (MethodRefExpr) expr;
            Expression target = transformExpr(mr.getTarget());
            if (target == mr.getTarget()) return expr;
            MethodRefExpr result = new MethodRefExpr(mr.getLocation(), target, mr.getTypeTarget(), mr.getMethodName(), mr.isConstructor());
            result.setHirType(mr.getHirType());
            return result;
        }
        if (expr instanceof ConditionalExpr) {
            ConditionalExpr ce = (ConditionalExpr) expr;
            Expression cond = transformExpr(ce.getCondition());
            Expression then = transformExpr(ce.getThenExpr());
            Expression els = transformExpr(ce.getElseExpr());
            if (cond == ce.getCondition() && then == ce.getThenExpr()
                    && els == ce.getElseExpr()) return expr;
            return new ConditionalExpr(ce.getLocation(), cond, then, els);
        }
        if (expr instanceof BlockExpr) {
            BlockExpr be = (BlockExpr) expr;
            List<Statement> stmts = transformStmts(be.getStatements());
            Expression result = transformExpr(be.getResult());
            if (stmts == be.getStatements() && result == be.getResult()) return expr;
            return new BlockExpr(be.getLocation(), stmts, result);
        }
        if (expr instanceof AssignExpr) {
            AssignExpr ae = (AssignExpr) expr;
            Expression target = transformExpr(ae.getTarget());
            Expression value = transformExpr(ae.getValue());
            if (target == ae.getTarget() && value == ae.getValue()) return expr;
            return new AssignExpr(ae.getLocation(), target, ae.getOperator(), value);
        }
        if (expr instanceof BinaryExpr) {
            BinaryExpr bin = (BinaryExpr) expr;
            Expression left = transformExpr(bin.getLeft());
            Expression right = transformExpr(bin.getRight());
            if (left == bin.getLeft() && right == bin.getRight()) return expr;
            return new BinaryExpr(bin.getLocation(), bin.getType(), left, bin.getOperator(), right);
        }
        if (expr instanceof UnaryExpr) {
            UnaryExpr un = (UnaryExpr) expr;
            Expression operand = transformExpr(un.getOperand());
            if (operand == un.getOperand()) return expr;
            return new UnaryExpr(un.getLocation(), un.getType(), un.getOperator(), operand, un.isPrefix());
        }
        // Identifier, ThisExpr, Literal are leaves — return as-is
        return expr;
    }

    protected Statement transformStmt(Statement stmt) {
        if (stmt == null) return null;
        if (stmt instanceof HirStmt) {
            return (Statement) ((HirStmt) stmt).accept(this, null);
        }
        // AST statement types (HIR eliminated nodes)
        if (stmt instanceof ExpressionStmt) {
            ExpressionStmt es = (ExpressionStmt) stmt;
            Expression expr = transformExpr(es.getExpression());
            if (expr == es.getExpression()) return stmt;
            return new ExpressionStmt(es.getLocation(), expr);
        }
        if (stmt instanceof ReturnStmt) {
            ReturnStmt ret = (ReturnStmt) stmt;
            Expression value = transformExpr(ret.getValue());
            if (value == ret.getValue()) return stmt;
            return new ReturnStmt(ret.getLocation(), value, ret.getLabel());
        }
        if (stmt instanceof ThrowStmt) {
            ThrowStmt thr = (ThrowStmt) stmt;
            Expression exc = transformExpr(thr.getException());
            if (exc == thr.getException()) return stmt;
            return new ThrowStmt(thr.getLocation(), exc);
        }
        if (stmt instanceof ForStmt) {
            ForStmt fs = (ForStmt) stmt;
            Expression iter = transformExpr(fs.getIterable());
            Statement body = transformStmt(fs.getBody());
            if (iter == fs.getIterable() && body == fs.getBody()) return stmt;
            return new ForStmt(fs.getLocation(), fs.getLabel(), fs.getVariables(), iter, body);
        }
        if (stmt instanceof IfStmt) {
            IfStmt is = (IfStmt) stmt;
            Expression cond = transformExpr(is.getCondition());
            Statement then = transformStmt(is.getThenBranch());
            Statement els = transformStmt(is.getElseBranch());
            if (cond == is.getCondition() && then == is.getThenBranch()
                    && els == is.getElseBranch()) return stmt;
            return new IfStmt(is.getLocation(), cond, null, then, els);
        }
        if (stmt instanceof Block) {
            Block blk = (Block) stmt;
            List<Statement> stmts = transformStmts(blk.getStatements());
            if (stmts == blk.getStatements()) return stmt;
            return new Block(blk.getLocation(), stmts, blk.isTransparent());
        }
        return stmt;
    }

    @SuppressWarnings("unchecked")
    protected <T extends HirDecl> T transformDecl(T decl) {
        if (decl == null) return null;
        return (T) decl.accept(this, null);
    }

    public AstNode transformBody(AstNode body) {
        if (body == null) return null;
        if (body instanceof Expression) return transformExpr((Expression) body);
        if (body instanceof Statement) return transformStmt((Statement) body);
        if (body instanceof HirDecl) return transformDecl((HirDecl) body);
        return body;
    }

    protected List<Expression> transformExprs(List<Expression> exprs) {
        if (exprs == null || exprs.isEmpty()) return exprs;
        List<Expression> result = null;
        for (int i = 0; i < exprs.size(); i++) {
            Expression original = exprs.get(i);
            Expression transformed = transformExpr(original);
            if (transformed != original && result == null) {
                result = new ArrayList<>(exprs.size());
                for (int j = 0; j < i; j++) result.add(exprs.get(j));
            }
            if (result != null) result.add(transformed);
        }
        return result != null ? result : exprs;
    }

    protected List<Statement> transformStmts(List<Statement> stmts) {
        if (stmts == null || stmts.isEmpty()) return stmts;
        List<Statement> result = null;
        for (int i = 0; i < stmts.size(); i++) {
            Statement original = stmts.get(i);
            Statement transformed = transformStmt(original);
            if (transformed != original && result == null) {
                result = new ArrayList<>(stmts.size());
                for (int j = 0; j < i; j++) result.add(stmts.get(j));
            }
            if (result != null) result.add(transformed);
        }
        return result != null ? result : stmts;
    }

    protected <T extends HirDecl> List<T> transformDecls(List<T> decls) {
        if (decls == null || decls.isEmpty()) return decls;
        List<T> result = null;
        for (int i = 0; i < decls.size(); i++) {
            T original = decls.get(i);
            T transformed = transformDecl(original);
            if (transformed != original && result == null) {
                result = new ArrayList<>(decls.size());
                for (int j = 0; j < i; j++) result.add(decls.get(j));
            }
            if (result != null) result.add(transformed);
        }
        return result != null ? result : decls;
    }

    // ==================== 叶节点（直接返回） ====================

    @Override
    public HirNode visitAnnotation(HirAnnotation node, Void ctx) {
        return node;
    }

    @Override
    public HirNode visitImport(HirImport node, Void ctx) {
        return node;
    }

    @Override
    public HirNode visitTypeAlias(HirTypeAlias node, Void ctx) {
        return node;
    }

    // BreakStmt/ContinueStmt are leaf nodes, handled as default in transformStmt()

    // ==================== 声明 ====================

    @Override
    public HirNode visitModule(HirModule node, Void ctx) {
        List<HirImport> imports = transformDecls(node.getImports());
        List<HirDecl> decls = transformDecls(node.getDeclarations());
        if (imports == node.getImports() && decls == node.getDeclarations()) return node;
        return new HirModule(node.getLocation(), node.getPackageName(), imports, decls);
    }

    @Override
    public HirNode visitClass(HirClass node, Void ctx) {
        List<HirField> fields = transformDecls(node.getFields());
        List<HirFunction> methods = transformDecls(node.getMethods());
        List<HirFunction> constructors = transformDecls(node.getConstructors());
        List<HirEnumEntry> entries = transformDecls(node.getEnumEntries());
        if (fields == node.getFields() && methods == node.getMethods()
                && constructors == node.getConstructors()
                && entries == node.getEnumEntries()) return node;
        return new HirClass(node.getLocation(), node.getName(), node.getModifiers(),
                node.getAnnotations(), node.getClassKind(), node.getTypeParams(),
                fields, methods, constructors, node.getSuperClass(),
                node.getInterfaces(), entries, node.getSuperConstructorArgs());
    }

    @Override
    public HirNode visitFunction(HirFunction node, Void ctx) {
        List<HirParam> params = transformDecls(node.getParams());
        AstNode body = transformBody(node.getBody());
        if (params == node.getParams() && body == node.getBody()) return node;
        return new HirFunction(node.getLocation(), node.getName(), node.getModifiers(),
                node.getAnnotations(), node.getTypeParams(), node.getReceiverType(),
                params, node.getReturnType(), body, node.isConstructor(),
                node.getDelegationArgs(), node.getReifiedTypeParams());
    }

    @Override
    public HirNode visitField(HirField node, Void ctx) {
        Expression init = transformExpr(node.getInitializer());
        if (init == node.getInitializer()) return node;
        return new HirField(node.getLocation(), node.getName(), node.getModifiers(),
                node.getAnnotations(), node.getType(), init, node.isVal(), node.getReceiverType(),
                node.getGetterBody(), node.getSetterBody(), node.getSetterParam());
    }

    @Override
    public HirNode visitParam(HirParam node, Void ctx) {
        Expression def = transformExpr(node.getDefaultValue());
        if (def == node.getDefaultValue()) return node;
        return new HirParam(node.getLocation(), node.getName(), node.getType(),
                def, node.isVararg());
    }

    @Override
    public HirNode visitEnumEntry(HirEnumEntry node, Void ctx) {
        List<Expression> args = transformExprs(node.getArgs());
        List<HirDecl> members = transformDecls(node.getMembers());
        if (args == node.getArgs() && members == node.getMembers()) return node;
        return new HirEnumEntry(node.getLocation(), node.getName(), args, members);
    }

    // ==================== 语句 ====================

    // Block handling moved to transformStmt()

    @Override
    public HirNode visitDeclStmt(HirDeclStmt node, Void ctx) {
        HirDecl decl = transformDecl(node.getDeclaration());
        if (decl == node.getDeclaration()) return node;
        return new HirDeclStmt(node.getLocation(), decl);
    }

    @Override
    public HirNode visitLoop(HirLoop node, Void ctx) {
        Expression cond = transformExpr(node.getCondition());
        Statement body = transformStmt(node.getBody());
        if (cond == node.getCondition() && body == node.getBody()) return node;
        return new HirLoop(node.getLocation(), node.getLabel(), cond, body, node.isDoWhile());
    }

    @Override
    public HirNode visitTry(HirTry node, Void ctx) {
        Statement tryBlock = transformStmt(node.getTryBlock());
        Statement finallyBlock = transformStmt(node.getFinallyBlock());
        // catches 需特殊处理
        List<HirTry.CatchClause> catches = node.getCatches();
        List<HirTry.CatchClause> newCatches = null;
        for (int i = 0; i < catches.size(); i++) {
            HirTry.CatchClause cc = catches.get(i);
            Statement body = transformStmt(cc.getBody());
            if (body != cc.getBody() && newCatches == null) {
                newCatches = new ArrayList<>(catches.size());
                for (int j = 0; j < i; j++) newCatches.add(catches.get(j));
            }
            if (newCatches != null) {
                newCatches.add(body != cc.getBody()
                        ? new HirTry.CatchClause(cc.getParamName(), cc.getExceptionType(), body)
                        : cc);
            }
        }
        List<HirTry.CatchClause> finalCatches = newCatches != null ? newCatches : catches;
        if (tryBlock == node.getTryBlock() && finalCatches == catches
                && finallyBlock == node.getFinallyBlock()) return node;
        return new HirTry(node.getLocation(), tryBlock, finalCatches, finallyBlock);
    }

    // ==================== 表达式 ====================

    @Override
    public HirNode visitCall(HirCall node, Void ctx) {
        Expression callee = transformExpr(node.getCallee());
        List<Expression> args = transformExprs(node.getArgs());
        if (callee == node.getCallee() && args == node.getArgs()) return node;
        return new HirCall(node.getLocation(), node.getType(),
                callee, node.getTypeArgs(), args, node.getNamedArgs());
    }

    @Override
    public HirNode visitLambda(HirLambda node, Void ctx) {
        List<HirParam> params = transformDecls(node.getParams());
        AstNode body = transformBody(node.getBody());
        if (params == node.getParams() && body == node.getBody()) return node;
        return new HirLambda(node.getLocation(), node.getType(),
                params, body, node.getCapturedVars());
    }

    @Override
    public HirNode visitCollectionLiteral(HirCollectionLiteral node, Void ctx) {
        List<Expression> elements = transformExprs(node.getElements());
        if (elements == node.getElements()) return node;
        return new HirCollectionLiteral(node.getLocation(), node.getType(),
                node.getKind(), elements);
    }

    @Override
    public HirNode visitObjectLiteral(HirObjectLiteral node, Void ctx) {
        List<HirDecl> members = transformDecls(node.getMembers());
        if (members == node.getMembers()) return node;
        return new HirObjectLiteral(node.getLocation(), node.getType(),
                node.getSuperTypes(), members);
    }

    @Override
    public HirNode visitNew(HirNew node, Void ctx) {
        List<Expression> args = transformExprs(node.getArgs());
        if (args == node.getArgs()) return node;
        return new HirNew(node.getLocation(), node.getType(),
                node.getClassName(), node.getTypeArgs(), args);
    }


}
