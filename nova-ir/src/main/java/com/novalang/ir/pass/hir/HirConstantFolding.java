package com.novalang.ir.pass.hir;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.*;
import com.novalang.compiler.ast.expr.BinaryExpr.BinaryOp;
import com.novalang.compiler.ast.expr.Literal.LiteralKind;
import com.novalang.compiler.ast.stmt.*;
import com.novalang.ir.hir.*;
import com.novalang.ir.hir.decl.HirModule;
import com.novalang.ir.hir.expr.*;
import com.novalang.ir.hir.stmt.*;
import com.novalang.compiler.hirtype.ClassType;
import com.novalang.compiler.hirtype.HirType;
import com.novalang.compiler.hirtype.PrimitiveType;
import com.novalang.ir.pass.HirPass;

/**
 * HIR 常量折叠 + 代数简化。
 * 从 ConstantFoldingOptimizer 迁移，操作 HIR 节点。
 */
public class HirConstantFolding extends HirTransformer implements HirPass {

    @Override
    public String getName() {
        return "HirConstantFolding";
    }

    @Override
    public HirModule run(HirModule module) {
        return (HirModule) transform(module);
    }

    // ==================== 表达式常量折叠 ====================

    @Override
    protected Expression transformExpr(Expression expr) {
        Expression result = super.transformExpr(expr);
        if (result instanceof BinaryExpr) {
            Expression folded = tryFoldBinaryExpr((BinaryExpr) result);
            if (folded != null) return folded;
        }
        if (result instanceof UnaryExpr) {
            Expression folded = tryFoldUnaryExpr((UnaryExpr) result);
            if (folded != null) return folded;
        }
        return result;
    }

    private Expression tryFoldBinaryExpr(BinaryExpr expr) {
        Expression left = expr.getLeft();
        Expression right = expr.getRight();
        BinaryOp op = expr.getOperator();

        // 常量折叠：左右都是字面量
        if (left instanceof Literal && right instanceof Literal) {
            Expression folded = foldBinary((Literal) left, op, (Literal) right, expr);
            if (folded != null) return folded;
        }

        // 连续常量重结合：(a op lit1) op lit2 → a op fold(lit1 op lit2)
        if ((op == BinaryOp.ADD || op == BinaryOp.MUL) && right instanceof Literal) {
            Expression reassoc = tryReassociate(left, op, (Literal) right, expr);
            if (reassoc != null) return reassoc;
        }

        // 代数简化
        return simplifyAlgebra(left, op, right, expr);
    }

    private Expression foldBinary(Literal left, BinaryOp op, Literal right, BinaryExpr expr) {
        SourceLocation loc = expr.getLocation();

        // 字符串拼接
        if (op == BinaryOp.ADD
                && left.getKind() == LiteralKind.STRING
                && right.getKind() == LiteralKind.STRING) {
            return new Literal(loc, new ClassType("String"),
                    (String) left.getValue() + (String) right.getValue(), LiteralKind.STRING);
        }

        // 逻辑运算
        if (left.getKind() == LiteralKind.BOOLEAN && right.getKind() == LiteralKind.BOOLEAN) {
            return foldBooleanOp((Boolean) left.getValue(), op, (Boolean) right.getValue(), loc);
        }

        // 数值运算
        if (isNumeric(left) && isNumeric(right)) {
            Expression arith = foldArithmetic(left, op, right, loc);
            if (arith != null) return arith;
            Expression comp = foldComparison(left, op, right, loc);
            if (comp != null) return comp;
        }

        return null;
    }

    private Expression foldBooleanOp(boolean l, BinaryOp op, boolean r, SourceLocation loc) {
        HirType boolType = new PrimitiveType(PrimitiveType.Kind.BOOLEAN);
        switch (op) {
            case AND: return new Literal(loc, boolType, l && r, LiteralKind.BOOLEAN);
            case OR:  return new Literal(loc, boolType, l || r, LiteralKind.BOOLEAN);
            case EQ:  return new Literal(loc, boolType, l == r, LiteralKind.BOOLEAN);
            case NE:  return new Literal(loc, boolType, l != r, LiteralKind.BOOLEAN);
            default:  return null;
        }
    }

    private Expression foldArithmetic(Literal left, BinaryOp op, Literal right, SourceLocation loc) {
        switch (op) {
            case ADD: case SUB: case MUL: case DIV: case MOD: break;
            default: return null;
        }

        int rank = Math.max(numericRank(left.getKind()), numericRank(right.getKind()));

        if (rank == 3) { // Double
            double l = toDouble(left), r = toDouble(right);
            if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && r == 0.0) return null;
            double res;
            switch (op) {
                case ADD: res = l + r; break;
                case SUB: res = l - r; break;
                case MUL: res = l * r; break;
                case DIV: res = l / r; break;
                case MOD: res = l % r; break;
                default: return null;
            }
            return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.DOUBLE), res, LiteralKind.DOUBLE);
        } else if (rank == 2) { // Float
            float l = toFloat(left), r = toFloat(right);
            if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && r == 0.0f) return null;
            float res;
            switch (op) {
                case ADD: res = l + r; break;
                case SUB: res = l - r; break;
                case MUL: res = l * r; break;
                case DIV: res = l / r; break;
                case MOD: res = l % r; break;
                default: return null;
            }
            return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.FLOAT), res, LiteralKind.FLOAT);
        } else if (rank == 1) { // Long
            long l = toLong(left), r = toLong(right);
            if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && r == 0) return null;
            long res;
            switch (op) {
                case ADD: res = l + r; break;
                case SUB: res = l - r; break;
                case MUL: res = l * r; break;
                case DIV: res = l / r; break;
                case MOD: res = l % r; break;
                default: return null;
            }
            return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.LONG), res, LiteralKind.LONG);
        } else { // Int
            int l = toInt(left), r = toInt(right);
            if ((op == BinaryOp.DIV || op == BinaryOp.MOD) && r == 0) return null;
            int res;
            switch (op) {
                case ADD: res = l + r; break;
                case SUB: res = l - r; break;
                case MUL: res = l * r; break;
                case DIV: res = l / r; break;
                case MOD: res = l % r; break;
                default: return null;
            }
            return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.INT), res, LiteralKind.INT);
        }
    }

    private Expression foldComparison(Literal left, BinaryOp op, Literal right, SourceLocation loc) {
        switch (op) {
            case EQ: case NE: case LT: case GT: case LE: case GE: break;
            default: return null;
        }
        // INT/LONG 直接用整数比较，避免 double 精度丢失
        int rank = Math.max(numericRank(left.getKind()), numericRank(right.getKind()));
        int cmp;
        if (rank <= 1) {
            // INT 或 LONG：用 long 比较（涵盖 int）
            long l = toLong(left), r = toLong(right);
            cmp = Long.compare(l, r);
        } else {
            // FLOAT 或 DOUBLE：用 double 比较
            double l = toDouble(left), r = toDouble(right);
            cmp = Double.compare(l, r);
        }
        HirType boolType = new PrimitiveType(PrimitiveType.Kind.BOOLEAN);
        switch (op) {
            case EQ: return new Literal(loc, boolType, cmp == 0, LiteralKind.BOOLEAN);
            case NE: return new Literal(loc, boolType, cmp != 0, LiteralKind.BOOLEAN);
            case LT: return new Literal(loc, boolType, cmp < 0, LiteralKind.BOOLEAN);
            case GT: return new Literal(loc, boolType, cmp > 0, LiteralKind.BOOLEAN);
            case LE: return new Literal(loc, boolType, cmp <= 0, LiteralKind.BOOLEAN);
            case GE: return new Literal(loc, boolType, cmp >= 0, LiteralKind.BOOLEAN);
            default: return null;
        }
    }

    private Expression simplifyAlgebra(Expression left, BinaryOp op, Expression right, BinaryExpr expr) {
        SourceLocation loc = expr.getLocation();
        switch (op) {
            case ADD:
                if (isZero(right)) return left;
                if (isZero(left)) return right;
                break;
            case SUB:
                if (isZero(right)) return left;
                // x - x → 0（仅简单变量引用，避免有副作用的表达式）
                if (isSameVarRef(left, right)) {
                    return zeroLiteral(loc, expr.getType());
                }
                break;
            case MUL:
                if (isOne(right)) return left;
                if (isOne(left)) return right;
                // x * 0 → 0（仅整数零，排除 float NaN * 0 = NaN）
                if (isIntZero(right)) return right;
                if (isIntZero(left)) return left;
                break;
            case DIV:
                if (isOne(right)) return left;
                break;
            case AND:
                if (isBoolLiteral(left, false)) return left;
                break;
            case OR:
                if (isBoolLiteral(left, true)) return left;
                break;
        }
        return null;
    }

    private Expression tryFoldUnaryExpr(UnaryExpr expr) {
        if (!(expr.getOperand() instanceof Literal)) return null;
        Literal lit = (Literal) expr.getOperand();
        SourceLocation loc = expr.getLocation();

        switch (expr.getOperator()) {
            case NEG:
                return foldNeg(lit, loc);
            case POS:
                if (isNumeric(lit)) return lit;
                break;
            case NOT:
                if (lit.getKind() == LiteralKind.BOOLEAN) {
                    return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.BOOLEAN),
                            !(Boolean) lit.getValue(), LiteralKind.BOOLEAN);
                }
                break;
        }
        return null;
    }

    private Expression foldNeg(Literal lit, SourceLocation loc) {
        switch (lit.getKind()) {
            case INT:
                return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.INT),
                        -toInt(lit), LiteralKind.INT);
            case LONG:
                return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.LONG),
                        -toLong(lit), LiteralKind.LONG);
            case FLOAT:
                return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.FLOAT),
                        -toFloat(lit), LiteralKind.FLOAT);
            case DOUBLE:
                return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.DOUBLE),
                        -toDouble(lit), LiteralKind.DOUBLE);
            default: return lit;
        }
    }

    // ==================== if 死分支消除 ====================

    @Override
    protected Statement transformStmt(Statement stmt) {
        Statement result = super.transformStmt(stmt);
        if (result instanceof IfStmt) {
            IfStmt ifNode = (IfStmt) result;
            if (ifNode.getCondition() instanceof Literal) {
                Literal cond = (Literal) ifNode.getCondition();
                if (cond.getKind() == LiteralKind.BOOLEAN) {
                    if ((Boolean) cond.getValue()) {
                        return ifNode.getThenBranch();
                    } else if (ifNode.hasElse()) {
                        return ifNode.getElseBranch();
                    }
                }
            }
        }
        return result;
    }

    // ==================== 字段访问折叠 ====================

    // 注意: 不折叠 "hello".length → 5, 因为当 MemberExpr 作为
    // HirCall 的 callee 时（如 "hello".length()），折叠会导致 callee 变成
    // Literal(5)，后续 lowerCall 将错误地对整数 5 调用 invoke()。

    // ==================== 辅助方法 ====================

    private static boolean isNumeric(Literal lit) {
        switch (lit.getKind()) {
            case INT: case LONG: case FLOAT: case DOUBLE: return true;
            default: return false;
        }
    }

    private static int numericRank(LiteralKind kind) {
        switch (kind) {
            case INT: return 0;
            case LONG: return 1;
            case FLOAT: return 2;
            case DOUBLE: return 3;
            default: return -1;
        }
    }

    private static int toInt(Literal lit) {
        Object v = lit.getValue();
        return (v instanceof Number) ? ((Number) v).intValue() : 0;
    }

    private static long toLong(Literal lit) {
        Object v = lit.getValue();
        return (v instanceof Number) ? ((Number) v).longValue() : 0;
    }

    private static float toFloat(Literal lit) {
        Object v = lit.getValue();
        return (v instanceof Number) ? ((Number) v).floatValue() : 0;
    }

    private static double toDouble(Literal lit) {
        Object v = lit.getValue();
        return (v instanceof Number) ? ((Number) v).doubleValue() : 0;
    }

    private static boolean isZero(Expression expr) {
        if (!(expr instanceof Literal)) return false;
        Literal lit = (Literal) expr;
        return isNumeric(lit) && toDouble(lit) == 0.0;
    }

    private static boolean isOne(Expression expr) {
        if (!(expr instanceof Literal)) return false;
        Literal lit = (Literal) expr;
        return isNumeric(lit) && toDouble(lit) == 1.0;
    }

    private static boolean isBoolLiteral(Expression expr, boolean value) {
        if (!(expr instanceof Literal)) return false;
        Literal lit = (Literal) expr;
        return lit.getKind() == LiteralKind.BOOLEAN && (Boolean) lit.getValue() == value;
    }

    /** 整数零（INT 或 LONG），不匹配 FLOAT/DOUBLE 的 0.0（因为 NaN * 0.0 = NaN） */
    private static boolean isIntZero(Expression expr) {
        if (!(expr instanceof Literal)) return false;
        Literal lit = (Literal) expr;
        return (lit.getKind() == LiteralKind.INT && toInt(lit) == 0)
                || (lit.getKind() == LiteralKind.LONG && toLong(lit) == 0);
    }

    /** 两个表达式是否引用同一变量（已解析时比较 depth+slot，否则比较名称） */
    private static boolean isSameVarRef(Expression a, Expression b) {
        if (!(a instanceof Identifier) || !(b instanceof Identifier)) return false;
        Identifier ra = (Identifier) a;
        Identifier rb = (Identifier) b;
        if (ra.isResolved() && rb.isResolved()) {
            return ra.getResolvedDepth() == rb.getResolvedDepth()
                    && ra.getResolvedSlot() == rb.getResolvedSlot();
        }
        return ra.getName().equals(rb.getName());
    }

    /** 根据表达式类型返回对应的零值字面量 */
    private static Expression zeroLiteral(SourceLocation loc, HirType type) {
        if (type instanceof PrimitiveType) {
            switch (((PrimitiveType) type).getKind()) {
                case LONG:
                    return new Literal(loc, type, 0L, LiteralKind.LONG);
                case FLOAT:
                    return new Literal(loc, type, 0.0f, LiteralKind.FLOAT);
                case DOUBLE:
                    return new Literal(loc, type, 0.0, LiteralKind.DOUBLE);
                default:
                    break;
            }
        }
        // 默认 INT
        return new Literal(loc, new PrimitiveType(PrimitiveType.Kind.INT), 0, LiteralKind.INT);
    }

    /**
     * 连续常量重结合：(a op lit1) op lit2 → a op fold(lit1 op lit2)。
     * 也处理交换形式：(lit1 op a) op lit2 → fold(lit1 op lit2) op a。
     */
    private Expression tryReassociate(Expression left, BinaryOp op, Literal rightLit, BinaryExpr expr) {
        if (!(left instanceof BinaryExpr)) return null;
        BinaryExpr inner = (BinaryExpr) left;
        if (inner.getOperator() != op) return null;

        Expression innerLeft = inner.getLeft();
        Expression innerRight = inner.getRight();
        SourceLocation loc = expr.getLocation();

        // 形式 (a + lit1) + lit2
        if (innerRight instanceof Literal && isNumeric((Literal) innerRight) && isNumeric(rightLit)) {
            Expression folded = foldArithmetic((Literal) innerRight, op, rightLit, loc);
            if (folded != null) {
                return new BinaryExpr(loc, expr.getType(), innerLeft, op, folded);
            }
        }
        // 形式 (lit1 + a) + lit2（ADD/MUL 可交换）
        if (innerLeft instanceof Literal && isNumeric((Literal) innerLeft) && isNumeric(rightLit)) {
            Expression folded = foldArithmetic((Literal) innerLeft, op, rightLit, loc);
            if (folded != null) {
                return new BinaryExpr(loc, expr.getType(), innerRight, op, folded);
            }
        }
        return null;
    }
}
