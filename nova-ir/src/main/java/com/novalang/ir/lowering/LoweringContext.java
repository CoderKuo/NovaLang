package com.novalang.ir.lowering;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.HirExpr;
import com.novalang.ir.hir.HirStmt;
import com.novalang.ir.hir.decl.HirField;
import com.novalang.compiler.ast.expr.Identifier;
import com.novalang.compiler.ast.expr.Literal.LiteralKind;
import com.novalang.compiler.ast.expr.Literal;
import com.novalang.ir.hir.stmt.HirDeclStmt;
import com.novalang.compiler.hirtype.HirType;
import com.novalang.compiler.hirtype.PrimitiveType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AST → HIR 降级上下文。
 * 用于生成临时变量名，以及在脱糖时收集额外的语句。
 */
public class LoweringContext {

    private int tempCounter = 0;

    /**
     * 生成唯一的临时变量名。
     */
    public String freshTemp() {
        return "$tmp" + (tempCounter++);
    }

    /**
     * 创建一个临时 val 声明语句。
     */
    public Statement makeTempVal(SourceLocation loc, String name, HirType type, Expression init) {
        HirField field = new HirField(loc, name, Collections.emptySet(),
                Collections.emptyList(), type, init, true);
        return new HirDeclStmt(loc, field);
    }

    /**
     * 创建一个临时 var 声明语句（可变）。
     */
    public Statement makeTempVar(SourceLocation loc, String name, HirType type, Expression init) {
        HirField field = new HirField(loc, name, Collections.emptySet(),
                Collections.emptyList(), type, init, false);
        return new HirDeclStmt(loc, field);
    }

    /**
     * 创建临时变量引用。
     */
    public Identifier tempRef(SourceLocation loc, String name, HirType type) {
        Identifier id = new Identifier(loc, name);
        id.setHirType(type);
        return id;
    }

    /**
     * 创建 null 字面量。
     */
    public Literal nullLiteral(SourceLocation loc) {
        return new Literal(loc, null, null, LiteralKind.NULL);
    }

    /**
     * 创建 boolean 字面量。
     */
    public Literal boolLiteral(SourceLocation loc, boolean value) {
        return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                value, LiteralKind.BOOLEAN);
    }

    /**
     * 将多个语句包裹在一个 block 中（如果只有一个语句则直接返回）。
     */
    public Statement wrapBlock(SourceLocation loc, List<Statement> stmts) {
        if (stmts.size() == 1) return stmts.get(0);
        return new Block(loc, stmts);
    }
}
