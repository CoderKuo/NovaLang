package com.novalang.ir.hir;

import com.novalang.compiler.ast.SourceLocation;

/**
 * HIR（High-level IR）节点接口。
 * HIR 是 AST 脱糖后的中间表示，保留结构化控制流。
 * <p>
 * Phase 0 迁移：从 abstract class 改为 interface，
 * 使 HirExpr/HirStmt/HirDecl 可以同时继承 AST 基类。
 */
public interface HirNode {

    SourceLocation getLocation();

    <R, C> R accept(HirVisitor<R, C> visitor, C context);
}
