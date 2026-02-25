package com.novalang.ir.hir;

import com.novalang.ir.hir.decl.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.ir.hir.expr.*;

/**
 * HIR 访问者接口，16 个 visit 方法。
 *
 * @param <R> 返回类型
 * @param <C> 上下文类型
 */
public interface HirVisitor<R, C> {

    // ===== 声明 (9) =====
    R visitModule(HirModule node, C context);
    R visitClass(HirClass node, C context);
    R visitFunction(HirFunction node, C context);
    R visitField(HirField node, C context);
    R visitParam(HirParam node, C context);
    R visitEnumEntry(HirEnumEntry node, C context);
    R visitTypeAlias(HirTypeAlias node, C context);
    R visitAnnotation(HirAnnotation node, C context);
    R visitImport(HirImport node, C context);

    // ===== 语句 (3) =====
    R visitDeclStmt(HirDeclStmt node, C context);

    R visitLoop(HirLoop node, C context);
    R visitTry(HirTry node, C context);

    // ===== 表达式 (4) =====
    R visitCall(HirCall node, C context);
    R visitLambda(HirLambda node, C context);
    R visitCollectionLiteral(HirCollectionLiteral node, C context);
    R visitObjectLiteral(HirObjectLiteral node, C context);
    R visitNew(HirNew node, C context);
}
