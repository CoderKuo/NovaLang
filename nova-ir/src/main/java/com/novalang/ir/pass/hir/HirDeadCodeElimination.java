package com.novalang.ir.pass.hir;

import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.HirModule;
import com.novalang.ir.hir.stmt.*;
import com.novalang.ir.pass.HirPass;

import java.util.ArrayList;
import java.util.List;

/**
 * HIR 死代码消除。
 * - return/throw/break/continue 后的不可达代码
 * - if(false) 分支消除（由常量折叠先处理条件）
 */
public class HirDeadCodeElimination extends HirTransformer implements HirPass {

    @Override
    public String getName() {
        return "HirDeadCodeElimination";
    }

    @Override
    public HirModule run(HirModule module) {
        return (HirModule) transform(module);
    }

    /**
     * 消除 Block 中不可达的语句。
     */
    @Override
    protected Statement transformStmt(Statement stmt) {
        Statement result = super.transformStmt(stmt);
        if (!(result instanceof Block)) return result;
        Block block = (Block) result;

        List<Statement> stmts = block.getStatements();
        if (stmts == null || stmts.isEmpty()) return block;

        // 找到第一个终止语句（return/throw/break/continue）
        int terminatorIndex = -1;
        for (int i = 0; i < stmts.size(); i++) {
            if (isTerminator(stmts.get(i))) {
                terminatorIndex = i;
                break;
            }
        }

        // 如果存在终止语句且后面还有语句，截断
        if (terminatorIndex >= 0 && terminatorIndex < stmts.size() - 1) {
            List<Statement> trimmed = new ArrayList<>(stmts.subList(0, terminatorIndex + 1));
            return new Block(block.getLocation(), trimmed);
        }

        return block;
    }

    /**
     * 判断语句是否是终止语句。
     */
    private boolean isTerminator(Statement stmt) {
        return stmt instanceof ReturnStmt
                || stmt instanceof ThrowStmt
                || stmt instanceof BreakStmt
                || stmt instanceof ContinueStmt;
    }
}
