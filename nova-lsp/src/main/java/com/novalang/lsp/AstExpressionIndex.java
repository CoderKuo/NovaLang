package com.novalang.lsp;

import com.novalang.compiler.ast.*;
import com.novalang.compiler.ast.decl.*;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.stmt.*;

import java.util.*;

/**
 * AST 表达式位置索引
 *
 * <p>遍历 AST 树，构建 offset → Expression 的索引。
 * 用于将源码位置（offset/length）映射到对应的 AST Expression 节点，
 * 从而桥接文本推断与 {@code AnalysisResult.getExprTypeName(Expression)} 的结构化类型查询。</p>
 */
public class AstExpressionIndex {

    /** offset → 该位置起始的表达式列表（同一 offset 可能有多个嵌套表达式） */
    private final TreeMap<Integer, List<Expression>> offsetIndex = new TreeMap<>();

    /** 从 AST Program 构建索引 */
    public AstExpressionIndex(Program program) {
        this(program, null);
    }

    /** 从 AST Program 和顶层语句构建索引 */
    public AstExpressionIndex(Program program, List<Statement> topLevelStatements) {
        Collector collector = new Collector();
        if (program != null) {
            collector.collect(program);
        }
        if (topLevelStatements != null) {
            for (Statement stmt : topLevelStatements) {
                collector.collectStmt(stmt);
            }
        }
    }

    /**
     * 查找包含指定 offset 的最内层表达式。
     * 在所有起始 offset <= 给定 offset 且范围覆盖该 offset 的表达式中，
     * 选择跨度最小的那个（即最内层）。
     */
    public Expression findInnermostAt(int offset) {
        Expression best = null;
        int bestLength = Integer.MAX_VALUE;

        // 搜索所有起始 offset <= 给定 offset 的表达式
        for (Map.Entry<Integer, List<Expression>> entry : offsetIndex.headMap(offset, true).descendingMap().entrySet()) {
            int startOffset = entry.getKey();
            // 如果起始 offset 距离太远（超过最小跨度），可以停止
            if (best != null && (offset - startOffset) >= bestLength) break;

            for (Expression expr : entry.getValue()) {
                SourceLocation loc = expr.getLocation();
                int end = loc.getOffset() + loc.getLength();
                if (offset >= startOffset && offset < end) {
                    if (loc.getLength() < bestLength) {
                        best = expr;
                        bestLength = loc.getLength();
                    }
                }
            }
        }
        return best;
    }

    /**
     * 查找精确匹配 offset 和 length 的表达式。
     * 如果有多个匹配，返回跨度最小的。
     */
    public Expression findExact(int offset, int length) {
        List<Expression> exprs = offsetIndex.get(offset);
        if (exprs == null) return null;

        Expression best = null;
        int bestLength = Integer.MAX_VALUE;
        for (Expression expr : exprs) {
            SourceLocation loc = expr.getLocation();
            if (loc.getLength() == length) {
                return expr; // 精确匹配
            }
            // 允许近似匹配（offset 相同但 length 有微小差异），选最小
            if (loc.getLength() < bestLength) {
                best = expr;
                bestLength = loc.getLength();
            }
        }
        return best;
    }

    /**
     * 查找包含指定 offset 范围 [offset, offset+length) 的最内层表达式。
     * 优先精确匹配，其次包含匹配中跨度最小的。
     */
    public Expression findBestMatch(int offset, int length) {
        // 先尝试精确匹配
        Expression exact = findExact(offset, length);
        if (exact != null) return exact;
        // 退回到最内层匹配
        return findInnermostAt(offset);
    }

    private void addExpression(Expression expr) {
        if (expr == null) return;
        SourceLocation loc = expr.getLocation();
        if (loc == null || loc.getOffset() < 0) return;
        offsetIndex.computeIfAbsent(loc.getOffset(), k -> new ArrayList<>(2)).add(expr);
    }

    /**
     * AST 遍历收集器 — 递归遍历所有节点，收集 Expression。
     * 采用 AstVisitor 接口 + 手动递归子节点（比扩展 AstTransformer 更轻量，
     * 因为不需要构建新节点）。
     */
    private class Collector implements AstVisitor<Void, Void> {

        void collect(AstNode node) {
            if (node != null) node.accept(this, null);
        }

        private void collectExpr(Expression expr) {
            if (expr == null) return;
            addExpression(expr);
            expr.accept(this, null);
        }

        private void collectExprs(List<? extends Expression> exprs) {
            if (exprs == null) return;
            for (Expression e : exprs) collectExpr(e);
        }

        private void collectStmt(Statement stmt) {
            if (stmt != null) stmt.accept(this, null);
        }

        private void collectStmts(List<? extends Statement> stmts) {
            if (stmts == null) return;
            for (Statement s : stmts) collectStmt(s);
        }

        private void collectDecl(Declaration decl) {
            if (decl != null) decl.accept(this, null);
        }

        private void collectDecls(List<? extends Declaration> decls) {
            if (decls == null) return;
            for (Declaration d : decls) collectDecl(d);
        }

        private void collectBody(AstNode body) {
            if (body instanceof Expression) collectExpr((Expression) body);
            else if (body instanceof Statement) collectStmt((Statement) body);
        }

        // ============ 声明 ============

        @Override
        public Void visitProgram(Program node, Void ctx) {
            collectDecls(node.getDeclarations());
            return null;
        }

        @Override
        public Void visitClassDecl(ClassDecl node, Void ctx) {
            if (node.getPrimaryConstructorParams() != null) {
                for (Parameter p : node.getPrimaryConstructorParams()) collect(p);
            }
            if (node.getSuperConstructorArgs() != null) {
                collectExprs(node.getSuperConstructorArgs());
            }
            collectDecls(node.getMembers());
            return null;
        }

        @Override
        public Void visitInterfaceDecl(InterfaceDecl node, Void ctx) {
            collectDecls(node.getMembers());
            return null;
        }

        @Override
        public Void visitObjectDecl(ObjectDecl node, Void ctx) {
            collectDecls(node.getMembers());
            return null;
        }

        @Override
        public Void visitEnumDecl(EnumDecl node, Void ctx) {
            if (node.getEntries() != null) {
                for (EnumDecl.EnumEntry entry : node.getEntries()) {
                    collectExprs(entry.getArgs());
                    collectDecls(entry.getMembers());
                }
            }
            collectDecls(node.getMembers());
            return null;
        }

        @Override
        public Void visitFunDecl(FunDecl node, Void ctx) {
            if (node.getParams() != null) {
                for (Parameter p : node.getParams()) collect(p);
            }
            collectBody(node.getBody());
            return null;
        }

        @Override
        public Void visitPropertyDecl(PropertyDecl node, Void ctx) {
            collectExpr(node.getInitializer());
            if (node.getGetter() != null) collectBody(node.getGetter().getBody());
            if (node.getSetter() != null) collectBody(node.getSetter().getBody());
            return null;
        }

        @Override
        public Void visitParameter(Parameter node, Void ctx) {
            collectExpr(node.getDefaultValue());
            return null;
        }

        @Override
        public Void visitDestructuringDecl(DestructuringDecl node, Void ctx) {
            collectExpr(node.getInitializer());
            return null;
        }

        @Override
        public Void visitConstructorDecl(ConstructorDecl node, Void ctx) {
            collectExprs(node.getDelegationArgs());
            collectStmt(node.getBody());
            return null;
        }

        // ============ 语句 ============

        @Override
        public Void visitBlock(Block node, Void ctx) {
            collectStmts(node.getStatements());
            return null;
        }

        @Override
        public Void visitExpressionStmt(ExpressionStmt node, Void ctx) {
            collectExpr(node.getExpression());
            return null;
        }

        @Override
        public Void visitDeclarationStmt(DeclarationStmt node, Void ctx) {
            collectDecl(node.getDeclaration());
            return null;
        }

        @Override
        public Void visitIfStmt(IfStmt node, Void ctx) {
            collectExpr(node.getCondition());
            collectStmt(node.getThenBranch());
            collectStmt(node.getElseBranch());
            return null;
        }

        @Override
        public Void visitWhenStmt(WhenStmt node, Void ctx) {
            collectExpr(node.getSubject());
            if (node.getBranches() != null) {
                for (WhenBranch branch : node.getBranches()) {
                    collectWhenBranch(branch);
                }
            }
            collectStmt(node.getElseBranch());
            return null;
        }

        @Override
        public Void visitForStmt(ForStmt node, Void ctx) {
            collectExpr(node.getIterable());
            collectStmt(node.getBody());
            return null;
        }

        @Override
        public Void visitWhileStmt(WhileStmt node, Void ctx) {
            collectExpr(node.getCondition());
            collectStmt(node.getBody());
            return null;
        }

        @Override
        public Void visitDoWhileStmt(DoWhileStmt node, Void ctx) {
            collectStmt(node.getBody());
            collectExpr(node.getCondition());
            return null;
        }

        @Override
        public Void visitTryStmt(TryStmt node, Void ctx) {
            collectStmt(node.getTryBlock());
            if (node.getCatchClauses() != null) {
                for (CatchClause cc : node.getCatchClauses()) {
                    collectStmt(cc.getBody());
                }
            }
            collectStmt(node.getFinallyBlock());
            return null;
        }

        @Override
        public Void visitReturnStmt(ReturnStmt node, Void ctx) {
            collectExpr(node.getValue());
            return null;
        }

        @Override
        public Void visitThrowStmt(ThrowStmt node, Void ctx) {
            collectExpr(node.getException());
            return null;
        }

        @Override
        public Void visitGuardStmt(GuardStmt node, Void ctx) {
            collectExpr(node.getExpression());
            collectStmt(node.getElseBody());
            return null;
        }

        @Override
        public Void visitUseStmt(UseStmt node, Void ctx) {
            collectStmt(node.getBody());
            return null;
        }

        // ============ 表达式 ============

        @Override
        public Void visitBinaryExpr(BinaryExpr node, Void ctx) {
            collectExpr(node.getLeft());
            collectExpr(node.getRight());
            return null;
        }

        @Override
        public Void visitUnaryExpr(UnaryExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitCallExpr(CallExpr node, Void ctx) {
            collectExpr(node.getCallee());
            if (node.getArgs() != null) {
                for (CallExpr.Argument arg : node.getArgs()) {
                    collectExpr(arg.getValue());
                }
            }
            collectExpr(node.getTrailingLambda());
            return null;
        }

        @Override
        public Void visitIndexExpr(IndexExpr node, Void ctx) {
            collectExpr(node.getTarget());
            collectExpr(node.getIndex());
            return null;
        }

        @Override
        public Void visitMemberExpr(MemberExpr node, Void ctx) {
            collectExpr(node.getTarget());
            return null;
        }

        @Override
        public Void visitAssignExpr(AssignExpr node, Void ctx) {
            collectExpr(node.getTarget());
            collectExpr(node.getValue());
            return null;
        }

        @Override
        public Void visitLambdaExpr(LambdaExpr node, Void ctx) {
            collectBody(node.getBody());
            return null;
        }

        @Override
        public Void visitIfExpr(IfExpr node, Void ctx) {
            collectExpr(node.getCondition());
            collectExpr(node.getThenExpr());
            collectExpr(node.getElseExpr());
            return null;
        }

        @Override
        public Void visitWhenExpr(WhenExpr node, Void ctx) {
            collectExpr(node.getSubject());
            if (node.getBranches() != null) {
                for (WhenBranch branch : node.getBranches()) {
                    collectWhenBranch(branch);
                }
            }
            collectExpr(node.getElseExpr());
            return null;
        }

        @Override
        public Void visitTryExpr(TryExpr node, Void ctx) {
            collectStmt(node.getTryBlock());
            if (node.getCatchClauses() != null) {
                for (CatchClause cc : node.getCatchClauses()) {
                    collectStmt(cc.getBody());
                }
            }
            collectStmt(node.getFinallyBlock());
            return null;
        }

        @Override
        public Void visitAwaitExpr(AwaitExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitIdentifier(Identifier node, Void ctx) {
            // 叶节点，无子表达式
            return null;
        }

        @Override
        public Void visitLiteral(Literal node, Void ctx) {
            return null;
        }

        @Override
        public Void visitThisExpr(ThisExpr node, Void ctx) {
            return null;
        }

        @Override
        public Void visitSuperExpr(SuperExpr node, Void ctx) {
            return null;
        }

        @Override
        public Void visitTypeCheckExpr(TypeCheckExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitTypeCastExpr(TypeCastExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitRangeExpr(RangeExpr node, Void ctx) {
            collectExpr(node.getStart());
            collectExpr(node.getEnd());
            collectExpr(node.getStep());
            return null;
        }

        @Override
        public Void visitSliceExpr(SliceExpr node, Void ctx) {
            collectExpr(node.getTarget());
            collectExpr(node.getStart());
            collectExpr(node.getEnd());
            return null;
        }

        @Override
        public Void visitSpreadExpr(SpreadExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitPipelineExpr(PipelineExpr node, Void ctx) {
            collectExpr(node.getLeft());
            collectExpr(node.getRight());
            return null;
        }

        @Override
        public Void visitMethodRefExpr(MethodRefExpr node, Void ctx) {
            collectExpr(node.getTarget());
            return null;
        }

        @Override
        public Void visitObjectLiteralExpr(ObjectLiteralExpr node, Void ctx) {
            collectExprs(node.getSuperConstructorArgs());
            collectDecls(node.getMembers());
            return null;
        }

        @Override
        public Void visitCollectionLiteral(CollectionLiteral node, Void ctx) {
            collectExprs(node.getElements());
            if (node.getMapEntries() != null) {
                for (CollectionLiteral.MapEntry entry : node.getMapEntries()) {
                    collectExpr(entry.getKey());
                    collectExpr(entry.getValue());
                }
            }
            return null;
        }

        @Override
        public Void visitStringInterpolation(StringInterpolation node, Void ctx) {
            if (node.getParts() != null) {
                for (StringInterpolation.StringPart part : node.getParts()) {
                    if (part instanceof StringInterpolation.ExprPart) {
                        collectExpr(((StringInterpolation.ExprPart) part).getExpression());
                    }
                }
            }
            return null;
        }

        @Override
        public Void visitElvisExpr(ElvisExpr node, Void ctx) {
            collectExpr(node.getLeft());
            collectExpr(node.getRight());
            return null;
        }

        @Override
        public Void visitSafeCallExpr(SafeCallExpr node, Void ctx) {
            collectExpr(node.getTarget());
            if (node.getArgs() != null) {
                for (CallExpr.Argument arg : node.getArgs()) {
                    collectExpr(arg.getValue());
                }
            }
            return null;
        }

        @Override
        public Void visitSafeIndexExpr(SafeIndexExpr node, Void ctx) {
            collectExpr(node.getTarget());
            collectExpr(node.getIndex());
            return null;
        }

        @Override
        public Void visitNotNullExpr(NotNullExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitErrorPropagationExpr(ErrorPropagationExpr node, Void ctx) {
            collectExpr(node.getOperand());
            return null;
        }

        @Override
        public Void visitScopeShorthandExpr(ScopeShorthandExpr node, Void ctx) {
            collectExpr(node.getTarget());
            collectStmt(node.getBlock());
            return null;
        }

        @Override
        public Void visitPlaceholderExpr(PlaceholderExpr node, Void ctx) {
            return null;
        }

        @Override
        public Void visitJumpExpr(JumpExpr node, Void ctx) {
            return null;
        }

        // ============ 辅助 ============

        private void collectWhenBranch(WhenBranch branch) {
            if (branch == null) return;
            if (branch.getConditions() != null) {
                for (WhenBranch.WhenCondition cond : branch.getConditions()) {
                    if (cond instanceof WhenBranch.ExpressionCondition) {
                        collectExpr(((WhenBranch.ExpressionCondition) cond).getExpression());
                    } else if (cond instanceof WhenBranch.RangeCondition) {
                        collectExpr(((WhenBranch.RangeCondition) cond).getRange());
                    }
                }
            }
            collectStmt(branch.getBody());
        }
    }
}
