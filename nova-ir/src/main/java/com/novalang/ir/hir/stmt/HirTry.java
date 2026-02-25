package com.novalang.ir.hir.stmt;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.stmt.Statement;
import com.novalang.ir.hir.*;
import com.novalang.compiler.hirtype.HirType;

import java.util.List;

/**
 * Try-catch-finally 语句。use 语句脱糖后也变为 HirTry。
 */
public class HirTry extends HirStmt {

    private final Statement tryBlock;
    private final List<CatchClause> catches;
    private final Statement finallyBlock;  // nullable

    public HirTry(SourceLocation location, Statement tryBlock,
                  List<CatchClause> catches, Statement finallyBlock) {
        super(location);
        this.tryBlock = tryBlock;
        this.catches = catches;
        this.finallyBlock = finallyBlock;
    }

    public Statement getTryBlock() {
        return tryBlock;
    }

    public List<CatchClause> getCatches() {
        return catches;
    }

    public Statement getFinallyBlock() {
        return finallyBlock;
    }

    public boolean hasFinally() {
        return finallyBlock != null;
    }

    @Override
    public <R, C> R accept(HirVisitor<R, C> visitor, C context) {
        return visitor.visitTry(this, context);
    }

    /**
     * Catch 子句。
     */
    public static class CatchClause {
        private final String paramName;
        private final HirType exceptionType;
        private final Statement body;

        public CatchClause(String paramName, HirType exceptionType, Statement body) {
            this.paramName = paramName;
            this.exceptionType = exceptionType;
            this.body = body;
        }

        public String getParamName() {
            return paramName;
        }

        public HirType getExceptionType() {
            return exceptionType;
        }

        public Statement getBody() {
            return body;
        }
    }
}
