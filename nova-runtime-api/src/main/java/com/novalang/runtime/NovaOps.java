package com.novalang.runtime;

/**
 * 运行时算术运算辅助。
 * <p>
 * 编译器在无法静态判断 ADD 操作数类型时（如 lambda 捕获变量类型为 Object），
 * 生成 {@code INVOKESTATIC NovaOps.add(Object, Object)} 调用，
 * 在运行时判断是字符串拼接还是数值加法。
 * </p>
 */
public final class NovaOps {

    private NovaOps() {}

    /**
     * 运行时动态加法：若任一操作数为 String 则拼接，否则数值加法。
     */
    public static Object add(Object a, Object b) {
        // NovaString → 字符串拼接
        if (a instanceof String || b instanceof String
                || a instanceof NovaString || b instanceof NovaString) {
            return String.valueOf(a) + String.valueOf(b);
        }
        return numericOp(a, b, NumOp.ADD);
    }

    /**
     * 运行时动态减法。
     */
    public static Object sub(Object a, Object b) {
        return numericOp(a, b, NumOp.SUB);
    }

    /**
     * 运行时动态乘法。
     */
    public static Object mul(Object a, Object b) {
        return numericOp(a, b, NumOp.MUL);
    }

    /**
     * 运行时动态除法。
     */
    public static Object div(Object a, Object b) {
        return numericOp(a, b, NumOp.DIV);
    }

    /**
     * 运行时动态取模。
     */
    public static Object mod(Object a, Object b) {
        return numericOp(a, b, NumOp.MOD);
    }

    private enum NumOp { ADD, SUB, MUL, DIV, MOD }

    private static Object toJavaNumber(Object v) {
        if (v instanceof Number) return v;
        if (v instanceof NovaValue) return ((NovaValue) v).toJavaValue();
        throw new RuntimeException("Cannot convert " + (v == null ? "null" : v.getClass().getSimpleName()) + " to Number");
    }

    private static Object numericOp(Object a, Object b, NumOp op) {
        if (!(a instanceof Number)) a = toJavaNumber(a);
        if (!(b instanceof Number)) b = toJavaNumber(b);
        if (a instanceof Double || b instanceof Double) {
            double da = ((Number) a).doubleValue(), db = ((Number) b).doubleValue();
            switch (op) {
                case ADD: return da + db;
                case SUB: return da - db;
                case MUL: return da * db;
                case DIV: return da / db;
                case MOD: return da % db;
            }
        }
        if (a instanceof Long || b instanceof Long) {
            long la = ((Number) a).longValue(), lb = ((Number) b).longValue();
            switch (op) {
                case ADD: return la + lb;
                case SUB: return la - lb;
                case MUL: return la * lb;
                case DIV: return la / lb;
                case MOD: return la % lb;
            }
        }
        int ia = ((Number) a).intValue(), ib = ((Number) b).intValue();
        switch (op) {
            case ADD: return ia + ib;
            case SUB: return ia - ib;
            case MUL: return ia * ib;
            case DIV: return ia / ib;
            case MOD: return ia % ib;
        }
        return ia + ib;
    }
}
