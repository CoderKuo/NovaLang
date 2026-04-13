package com.novalang.runtime;

/**
 * 运行时算术/比较/类型运算辅助 —— 编译器和解释器共享。
 * <p>
 * 编译器在无法静态判断操作数类型时（如 lambda 捕获变量类型为 Object），
 * 生成 {@code INVOKESTATIC NovaOps.xxx(Object, Object)} 调用，
 * 在运行时统一处理 Nova 语义。
 * </p>
 */
public final class NovaOps {

    private NovaOps() {}

    // ========== 算术运算 ==========

    /**
     * 运行时动态加法：若任一操作数为 String 则拼接，否则数值加法。
     */
    public static Object add(Object a, Object b) {
        // String 拼接
        if (a instanceof String || b instanceof String
                || a instanceof NovaString || b instanceof NovaString) {
            return String.valueOf(a) + String.valueOf(b);
        }
        // List 连接
        if (a instanceof java.util.List && b instanceof java.util.List) {
            java.util.List<Object> result = new java.util.ArrayList<>((java.util.List<?>) a);
            result.addAll((java.util.List<?>) b);
            return result;
        }
        // Map 合并
        if (a instanceof java.util.Map && b instanceof java.util.Map) {
            java.util.Map<Object, Object> result = new java.util.LinkedHashMap<>((java.util.Map<?, ?>) a);
            result.putAll((java.util.Map<?, ?>) b);
            return result;
        }
        Object r = binaryOp(a, b, NumOp.ADD, "plus");
        if (r != null) return r;
        // inc 回退：x + 1 → x.inc()
        r = tryIncDec(a, b, "inc");
        if (r != null) return r;
        throw NovaErrors.typeMismatch(typeName(a) + " + " + typeName(b), "可运算类型",
                "确保操作数为数值或字符串类型，或实现 plus() 运算符重载");
    }

    public static Object sub(Object a, Object b) {
        Object r = binaryOp(a, b, NumOp.SUB, "minus");
        if (r != null) return r;
        // dec 回退：x - 1 → x.dec()
        r = tryIncDec(a, b, "dec");
        if (r != null) return r;
        throw NovaErrors.typeMismatch(typeName(a) + " - " + typeName(b), "可运算类型",
                "确保操作数为数值类型，或实现 minus() 运算符重载");
    }

    public static Object mul(Object a, Object b) {
        // String × Int repeat
        if (a instanceof String && b instanceof Number) {
            int n = ((Number) b).intValue();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(a);
            return sb.toString();
        }
        if (b instanceof String && a instanceof Number) {
            int n = ((Number) a).intValue();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) sb.append(b);
            return sb.toString();
        }
        Object r = binaryOp(a, b, NumOp.MUL, "times");
        if (r != null) return r;
        throw NovaErrors.typeMismatch(typeName(a) + " * " + typeName(b), "可运算类型",
                "确保操作数为数值类型，或实现 times() 运算符重载");
    }

    public static Object div(Object a, Object b) {
        Object r = binaryOp(a, b, NumOp.DIV, "div");
        if (r != null) return r;
        throw NovaErrors.typeMismatch(typeName(a) + " / " + typeName(b), "可运算类型",
                "确保操作数为数值类型，或实现 div() 运算符重载");
    }

    public static Object mod(Object a, Object b) {
        Object r = binaryOp(a, b, NumOp.MOD, "rem");
        if (r != null) return r;
        throw NovaErrors.typeMismatch(typeName(a) + " % " + typeName(b), "可运算类型",
                "确保操作数为数值类型，或实现 rem() 运算符重载");
    }

    /**
     * 统一二元运算：先尝试运算符重载，再回退到数值运算。
     * 对所有非原生 Number 的对象（包括 NovaValue/NovaObject）尝试方法分派。
     */
    /**
     * 通用二元运算：原生 Number 快速路径 → 运算符重载 → 数值解包回退。
     * 返回 null 表示所有路径都无法处理，由调用方决定回退策略。
     */
    private static Object binaryOp(Object a, Object b, NumOp op, String operatorName) {
        // 原生 Number → 直接数值运算（快速路径）
        if (a instanceof Number && !(a instanceof NovaValue)) {
            try { return numericOp(a, b, op); } catch (RuntimeException e) { return null; }
        }
        // 非原生 Number → 先尝试运算符重载
        try {
            return NovaDynamic.invokeMethod(a, operatorName, b);
        } catch (RuntimeException ignored) {
        }
        // 回退到数值运算（NovaInt/NovaDouble 等会被 toJavaNumber 解包）
        try { return numericOp(a, b, op); } catch (RuntimeException e) { return null; }
    }

    // ========== 比较运算 ==========

    /**
     * Nova 语义相等比较：null 安全 + 跨类型数值相等（Int==Long==Double）。
     */
    public static boolean equals(Object a, Object b) {
        if (a == b) return true;
        if (a == null) return b == null;
        if (b == null) return false;
        // NovaValue 自身的 equals（NovaInt.equals 已处理跨类型数值相等）
        if (a instanceof NovaValue && b instanceof NovaValue) {
            return a.equals(b);
        }
        // 混合比较：解包后比较
        Object ja = a instanceof NovaValue ? ((NovaValue) a).toJavaValue() : a;
        Object jb = b instanceof NovaValue ? ((NovaValue) b).toJavaValue() : b;
        if (ja instanceof Number && jb instanceof Number) {
            return ((Number) ja).doubleValue() == ((Number) jb).doubleValue();
        }
        return ja.equals(jb);
    }

    /**
     * Nova 语义排序比较：先尝试运算符重载，再数值比较，最后 Comparable。
     */
    public static int compare(Object a, Object b) {
        // 先尝试运算符重载（对 NovaObject 等自定义类型）
        if (!(a instanceof Number) || a instanceof NovaValue) {
            try {
                Object result = NovaDynamic.invokeMethod(a, "compareTo", b);
                if (result instanceof Number) return ((Number) result).intValue();
                if (result instanceof NovaValue) {
                    Object jv = ((NovaValue) result).toJavaValue();
                    if (jv instanceof Number) return ((Number) jv).intValue();
                }
            } catch (RuntimeException ignored) {
            }
        }
        // 解包后比较
        Object ja = a instanceof NovaValue ? ((NovaValue) a).toJavaValue() : a;
        Object jb = b instanceof NovaValue ? ((NovaValue) b).toJavaValue() : b;
        if (ja instanceof Number && jb instanceof Number) {
            return Double.compare(((Number) ja).doubleValue(), ((Number) jb).doubleValue());
        }
        if (ja instanceof Comparable) {
            @SuppressWarnings("unchecked")
            Comparable<Object> ca = (Comparable<Object>) ja;
            return ca.compareTo(jb);
        }
        throw NovaErrors.typeMismatch(a.getClass().getSimpleName(), "Comparable",
                "确保对象实现了 Comparable 接口或 compareTo() 运算符重载");
    }

    // ========== 一元运算 ==========

    /**
     * 一元正号：先尝试运算符重载，再数值恒等。
     */
    public static Object unaryPlus(Object a) {
        // 原生 Number → 直接返回
        if (a instanceof Number && !(a instanceof NovaValue)) return a;
        // NovaValue/其他 → 先尝试 unaryPlus 运算符重载
        try {
            return NovaDynamic.invokeMethod(a, "unaryPlus");
        } catch (RuntimeException ignored) {
        }
        // 数值型 NovaValue → 返回原值
        if (a instanceof NovaValue) {
            Object jv = ((NovaValue) a).toJavaValue();
            if (jv instanceof Number) return a;
        }
        throw NovaErrors.typeMismatch(a.getClass().getSimpleName(), "Number",
                "一元 + 需要数值类型，或实现 unaryPlus() 运算符重载");
    }

    // ========== inc/dec 回退 ==========

    /** x + 1 → x.inc()，x - 1 → x.dec() */
    private static Object tryIncDec(Object target, Object other, String methodName) {
        if (other instanceof Number && ((Number) other).intValue() == 1) {
            try {
                return NovaDynamic.invokeMethod(target, methodName);
            } catch (RuntimeException ignored) {}
        }
        if (other instanceof NovaValue) {
            Object jv = ((NovaValue) other).toJavaValue();
            if (jv instanceof Number && ((Number) jv).intValue() == 1) {
                try {
                    return NovaDynamic.invokeMethod(target, methodName);
                } catch (RuntimeException ignored) {}
            }
        }
        return null;
    }

    private static String typeName(Object o) {
        if (o == null) return "null";
        if (o instanceof NovaValue) return ((NovaValue) o).getTypeName();
        return o.getClass().getSimpleName();
    }

    // ========== 内部辅助 ==========

    private enum NumOp { ADD, SUB, MUL, DIV, MOD }

    private static Object toJavaNumber(Object v) {
        if (v instanceof Number) return v;
        if (v instanceof NovaValue) return ((NovaValue) v).toJavaValue();
        throw NovaErrors.typeMismatch(v == null ? "null" : v.getClass().getSimpleName(), "Number");
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
        if (a instanceof Float || b instanceof Float) {
            float fa = ((Number) a).floatValue(), fb = ((Number) b).floatValue();
            switch (op) {
                case ADD: return fa + fb;
                case SUB: return fa - fb;
                case MUL: return fa * fb;
                case DIV: return fa / fb;
                case MOD: return fa % fb;
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
