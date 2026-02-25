package nova.runtime.interpreter;

import nova.runtime.*;

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

    // ============ 算术操作 ============

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
        NovaValue result = tryOperatorOverload(left, "plus", interp, right);
        if (result != null) return result;
        // inc 回退：x + 1 → x.inc()
        result = tryIncDec(left, right, "inc", interp);
        if (result != null) return result;
        throw new NovaRuntimeException("Cannot add " + left.getTypeName() + " and " + right.getTypeName());
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
        // dec 回退：x - 1 → x.dec()
        result = tryIncDec(left, right, "dec", interp);
        if (result != null) return result;
        throw new NovaRuntimeException("Cannot subtract " + right.getTypeName() + " from " + left.getTypeName());
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
        throw new NovaRuntimeException("Cannot multiply " + left.getTypeName() + " and " + right.getTypeName());
    }

    public static NovaValue div(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int rv = ((NovaInt) right).getValue();
            if (rv == 0) throw new NovaRuntimeException("Division by zero");
            return NovaInt.of(((NovaInt) left).getValue() / rv);
        }
        if (left.isNumber() && right.isNumber()) {
            if (right.asDouble() == 0) throw new NovaRuntimeException("Division by zero");
            return numericPromote(left, right, (a, b) -> a / b, (a, b) -> a / b, (a, b) -> a / b, true);
        }
        NovaValue result = tryOperatorOverload(left, "div", interp, right);
        if (result != null) return result;
        throw new NovaRuntimeException("Cannot divide " + left.getTypeName() + " by " + right.getTypeName());
    }

    public static NovaValue mod(NovaValue left, NovaValue right, Interpreter interp) {
        if (left instanceof NovaInt && right instanceof NovaInt) {
            int rv = ((NovaInt) right).getValue();
            if (rv == 0) throw new NovaRuntimeException("Modulo by zero");
            return NovaInt.of(((NovaInt) left).getValue() % rv);
        }
        if (left.isNumber() && right.isNumber()) {
            if (right.asDouble() == 0) throw new NovaRuntimeException("Modulo by zero");
            return numericPromote(left, right, (a, b) -> a % b, (a, b) -> a % b, (a, b) -> a % b, false);
        }
        NovaValue result = tryOperatorOverload(left, "rem", interp, right);
        if (result != null) return result;
        throw new NovaRuntimeException("Cannot modulo " + left.getTypeName() + " by " + right.getTypeName());
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
        NovaValue result = tryOperatorOverload(left, "compareTo", interp, right);
        if (result != null) return result.asInt();
        throw new NovaRuntimeException("Cannot compare " + left.getTypeName() + " and " + right.getTypeName());
    }

    // ============ inc/dec 回退 ============

    /** x + 1 → x.inc()，x - 1 → x.dec() */
    private static NovaValue tryIncDec(NovaValue left, NovaValue right, String methodName, Interpreter interp) {
        if (left instanceof NovaObject && right instanceof NovaInt && ((NovaInt) right).getValue() == 1) {
            NovaCallable method = ((NovaObject) left).getMethod(methodName);
            if (method != null) {
                NovaBoundMethod bound = new NovaBoundMethod(left, method);
                return interp.executeBoundMethod(bound, Collections.emptyList(), null);
            }
        }
        return null;
    }
}
