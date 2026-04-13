package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import com.novalang.runtime.NovaException.ErrorKind;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.DoubleBinaryOperator;
import java.util.function.IntBinaryOperator;
import java.util.function.LongBinaryOperator;

/**
 * 二元运算的统一实现。
 *
 * <p>消除 Interpreter / HirEvaluator / MirInterpreter 之间的运算逻辑重复。
 * 性能关键的 Int×Int 快速路径仍保留在各调用方（executeBinaryRaw 等），
 * 本类只处理通用路径（Number 类型提升、字符串拼接、列表连接、运算符重载）。</p>
 */
public final class BinaryOps {

    private BinaryOps() {}

    // ============ 运算符 → 重载方法名映射 ============

    /** 二元运算符对应的运算符重载方法名。返回 null 表示不支持运算符重载。 */
    public static String operatorMethodName(String op) {
        switch (op) {
            case "ADD": return "plus";
            case "SUB": return "minus";
            case "MUL": return "times";
            case "DIV": return "div";
            case "MOD": return "rem";
            default:    return null;
        }
    }

    // ============ 运算符重载 ============

    /**
     * 尝试运算符重载：在 NovaObject / NovaEnumEntry 上查找指定方法并调用。
     * 返回 null 表示目标类型不支持该运算符。
     */
    public static NovaValue tryOperatorOverload(NovaValue target, String methodName,
                                                 Interpreter interp, NovaValue... args) {
        NovaCallable callable = null;
        if (target instanceof NovaObject) {
            callable = ((NovaObject) target).getMethod(methodName);
        } else if (target instanceof ScalarizedNovaObject) {
            callable = ((ScalarizedNovaObject) target).getMethod(methodName);
        } else if (target instanceof NovaEnumEntry) {
            callable = ((NovaEnumEntry) target).getMethod(methodName);
        }
        if (callable != null) {
            NovaBoundMethod bound = new NovaBoundMethod(target, callable);
            List<NovaValue> argList = args.length == 1
                    ? Collections.singletonList(args[0])
                    : Arrays.asList(args);
            return interp.executeBoundMethod(bound, argList, null);
        }
        return null;
    }

    // ============ 数值类型提升 ============

    /**
     * 按 Double > Float > Long > Int 优先级提升并执行二元运算。
     */
    static NovaValue numericPromote(NovaValue left, NovaValue right,
                                     IntBinaryOperator intOp,
                                     LongBinaryOperator longOp,
                                     DoubleBinaryOperator doubleOp,
                                     boolean includeFloat) {
        if (left instanceof NovaDouble || right instanceof NovaDouble) {
            return NovaDouble.of(doubleOp.applyAsDouble(left.asDouble(), right.asDouble()));
        }
        if (includeFloat && (left instanceof NovaFloat || right instanceof NovaFloat)) {
            return NovaFloat.of((float) doubleOp.applyAsDouble(left.asDouble(), right.asDouble()));
        }
        if (left instanceof NovaLong || right instanceof NovaLong) {
            return NovaLong.of(longOp.applyAsLong(left.asLong(), right.asLong()));
        }
        return NovaInt.of(intOp.applyAsInt(left.asInt(), right.asInt()));
    }

    // ============ 相等性比较（null 安全） ============

    /** null/NovaNull 安全的相等性比较。MirInterpreter 和 HirEvaluator 统一使用此方法。 */
    public static boolean novaEquals(NovaValue a, NovaValue b) {
        if (a == b) return true;
        if (a == null || a instanceof NovaNull) return b == null || b instanceof NovaNull;
        if (b == null || b instanceof NovaNull) return false;
        return a.equals(b);
    }

    // ============ 算术操作 ============

    /**
     * 通用算术：NovaInt 快速路径保留，通用路径委托 NovaOps（统一语义）。
     */
    public static NovaValue add(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() + ((NovaInt) right).getValue());
        }
        if (left.isString() || right.isString()) {
            return NovaString.of(left.asString() + right.asString());
        }
        if (left.isList() && right.isList()) {
            return ((NovaList) left).concat((NovaList) right);
        }
        if (left.isNumber() && right.isNumber()) {
            return numericPromote(left, right, (a, b) -> a + b, (a, b) -> a + b, (a, b) -> a + b, true);
        }
        // 通用路径：先尝试 Nova 运算符重载，再 inc 回退，最后委托 NovaOps
        NovaValue result = tryOperatorOverload(left, "plus", interp, right);
        if (result != null) return result;
        result = tryIncDecLocal(left, right, "inc", interp);
        if (result != null) return result;
        return wrapResult(com.novalang.runtime.NovaOps.add(left, right));
    }

    public static NovaValue sub(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() - ((NovaInt) right).getValue());
        }
        if (left.isNumber() && right.isNumber()) {
            return numericPromote(left, right, (a, b) -> a - b, (a, b) -> a - b, (a, b) -> a - b, true);
        }
        NovaValue result = tryOperatorOverload(left, "minus", interp, right);
        if (result != null) return result;
        result = tryIncDecLocal(left, right, "dec", interp);
        if (result != null) return result;
        return wrapResult(com.novalang.runtime.NovaOps.sub(left, right));
    }

    public static NovaValue mul(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() * ((NovaInt) right).getValue());
        }
        if (left.isString() && right.isInteger()) {
            return ((NovaString) left).repeat(right.asInt());
        }
        if (left.isInteger() && right.isString()) {
            return ((NovaString) right).repeat(left.asInt());
        }
        if (left.isNumber() && right.isNumber()) {
            return numericPromote(left, right, (a, b) -> a * b, (a, b) -> a * b, (a, b) -> a * b, true);
        }
        NovaValue result = tryOperatorOverload(left, "times", interp, right);
        if (result != null) return result;
        return wrapResult(com.novalang.runtime.NovaOps.mul(left, right));
    }

    public static NovaValue div(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int rv = ((NovaInt) right).getValue();
            if (rv == 0) throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "除数不能为零", null);
            return NovaInt.of(((NovaInt) left).getValue() / rv);
        }
        if (left.isNumber() && right.isNumber()) {
            if (right.asDouble() == 0) throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "除数不能为零", null);
            return numericPromote(left, right, (a, b) -> a / b, (a, b) -> a / b, (a, b) -> a / b, true);
        }
        NovaValue result = tryOperatorOverload(left, "div", interp, right);
        if (result != null) return result;
        return wrapResult(com.novalang.runtime.NovaOps.div(left, right));
    }

    public static NovaValue mod(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int rv = ((NovaInt) right).getValue();
            if (rv == 0) throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "取模运算除数不能为零", null);
            return NovaInt.of(((NovaInt) left).getValue() % rv);
        }
        if (left.isNumber() && right.isNumber()) {
            if (right.asDouble() == 0) throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "取模运算除数不能为零", null);
            return numericPromote(left, right, (a, b) -> a % b, (a, b) -> a % b, (a, b) -> a % b, false);
        }
        NovaValue result = tryOperatorOverload(left, "rem", interp, right);
        if (result != null) return result;
        return wrapResult(com.novalang.runtime.NovaOps.mod(left, right));
    }

    // ============ 比较操作 ============

    public static int compare(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return Integer.compare(((NovaInt) left).getValue(), ((NovaInt) right).getValue());
        }
        if (left.isNumber() && right.isNumber()) {
            return Double.compare(left.asDouble(), right.asDouble());
        }
        if (left.isString() && right.isString()) {
            return ((NovaString) left).compareTo((NovaString) right);
        }
        if (left instanceof NovaChar && right instanceof NovaChar) {
            return Character.compare(((NovaChar) left).getValue(), ((NovaChar) right).getValue());
        }
        // Nova 运算符重载
        NovaValue cmpResult = tryOperatorOverload(left, "compareTo", interp, right);
        if (cmpResult != null) return cmpResult.asInt();
        // 委托 NovaOps（Comparable 回退、Java 互操作）
        return com.novalang.runtime.NovaOps.compare(left, right);
    }

    /** 将 NovaOps 返回的 Object 包装回 NovaValue */
    private static NovaValue wrapResult(Object result) {
        if (result instanceof NovaValue) return (NovaValue) result;
        return AbstractNovaValue.fromJava(result);
    }

    /** x + 1 → x.inc()，x - 1 → x.dec()（解释器路径，直接方法分派） */
    private static NovaValue tryIncDecLocal(NovaValue left, NovaValue right, String methodName, Interpreter interp) {
        if (right instanceof NovaInt && ((NovaInt) right).getValue() == 1) {
            return tryOperatorOverload(left, methodName, interp);
        }
        return null;
    }

    // ============ 位运算操作 ============

    public static NovaValue bitwiseAnd(NovaValue left, NovaValue right) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() & ((NovaInt) right).getValue());
        }
        if (left instanceof NovaLong || right instanceof NovaLong) {
            return NovaLong.of(left.asLong() & right.asLong());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + left.getTypeName() + " 和 " + right.getTypeName() + " 进行按位与运算", null);
    }

    public static NovaValue bitwiseOr(NovaValue left, NovaValue right) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() | ((NovaInt) right).getValue());
        }
        if (left instanceof NovaLong || right instanceof NovaLong) {
            return NovaLong.of(left.asLong() | right.asLong());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + left.getTypeName() + " 和 " + right.getTypeName() + " 进行按位或运算", null);
    }

    public static NovaValue bitwiseXor(NovaValue left, NovaValue right) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() ^ ((NovaInt) right).getValue());
        }
        if (left instanceof NovaLong || right instanceof NovaLong) {
            return NovaLong.of(left.asLong() ^ right.asLong());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + left.getTypeName() + " 和 " + right.getTypeName() + " 进行按位异或运算", null);
    }

    public static NovaValue shiftLeft(NovaValue left, NovaValue right) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() << ((NovaInt) right).getValue());
        }
        if (left instanceof NovaLong) {
            return NovaLong.of(left.asLong() << right.asInt());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + left.getTypeName() + " 进行左移运算", null);
    }

    public static NovaValue shiftRight(NovaValue left, NovaValue right) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() >> ((NovaInt) right).getValue());
        }
        if (left instanceof NovaLong) {
            return NovaLong.of(left.asLong() >> right.asInt());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + left.getTypeName() + " 进行右移运算", null);
    }

    public static NovaValue unsignedShiftRight(NovaValue left, NovaValue right) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            return NovaInt.of(((NovaInt) left).getValue() >>> ((NovaInt) right).getValue());
        }
        if (left instanceof NovaLong) {
            return NovaLong.of(left.asLong() >>> right.asInt());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + left.getTypeName() + " 进行无符号右移运算", null);
    }

    public static NovaValue bitwiseNot(NovaValue operand) {
        if (operand instanceof NovaInt) {
            return NovaInt.of(~((NovaInt) operand).getValue());
        }
        if (operand instanceof NovaLong) {
            return NovaLong.of(~((NovaLong) operand).getValue());
        }
        throw new NovaRuntimeException(ErrorKind.TYPE_MISMATCH, "无法对 " + operand.getTypeName() + " 进行按位取反运算", null);
    }
}
