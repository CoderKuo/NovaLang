package com.novalang.runtime.stdlib;

import com.novalang.runtime.NovaRange;

import java.util.ArrayList;
import java.util.List;

/**
 * Number 类型扩展方法（Int/Long/Double/Float 共用）。
 */
@Ext("java/lang/Number")
public final class NumberExtensions {

    private NumberExtensions() {}

    // ========== 范围方法 ==========

    /** 倒序范围（惰性 Range，step=-1） */
    public static Object downTo(Object num, Object to) {
        int from = ((Number) num).intValue();
        int target = ((Number) to).intValue();
        return new NovaRange(from, target, true, -1);
    }

    public static Object until(Object num, Object to) {
        return new NovaRange(((Number) num).intValue(), ((Number) to).intValue(), false);
    }

    // ========== 类型转换 ==========

    public static Object toInt(Object num) {
        return ((Number) num).intValue();
    }

    public static Object toLong(Object num) {
        return ((Number) num).longValue();
    }

    public static Object toDouble(Object num) {
        return ((Number) num).doubleValue();
    }

    public static Object toFloat(Object num) {
        return ((Number) num).floatValue();
    }

    // ========== 数学运算 ==========

    public static Object abs(Object num) {
        if (num instanceof Integer) return Math.abs((Integer) num);
        if (num instanceof Long) return Math.abs((Long) num);
        if (num instanceof Double) return Math.abs((Double) num);
        if (num instanceof Float) return Math.abs((Float) num);
        return Math.abs(((Number) num).doubleValue());
    }

    public static Object coerceIn(Object num, Object min, Object max) {
        double v = ((Number) num).doubleValue();
        double lo = ((Number) min).doubleValue();
        double hi = ((Number) max).doubleValue();
        double result = Math.max(lo, Math.min(hi, v));
        if (num instanceof Integer) return (int) result;
        if (num instanceof Long) return (long) result;
        return result;
    }

    public static Object coerceAtLeast(Object num, Object min) {
        double v = ((Number) num).doubleValue();
        double lo = ((Number) min).doubleValue();
        double result = Math.max(lo, v);
        if (num instanceof Integer) return (int) result;
        if (num instanceof Long) return (long) result;
        return result;
    }

    public static Object coerceAtMost(Object num, Object max) {
        double v = ((Number) num).doubleValue();
        double hi = ((Number) max).doubleValue();
        double result = Math.min(hi, v);
        if (num instanceof Integer) return (int) result;
        if (num instanceof Long) return (long) result;
        return result;
    }

    // ========== 舍入 ==========

    public static Object roundToInt(Object num) {
        return (int) Math.round(((Number) num).doubleValue());
    }

    // ========== 检查 ==========

    public static Object isNaN(Object num) {
        if (num instanceof Double) return Double.isNaN((Double) num);
        if (num instanceof Float) return Float.isNaN((Float) num);
        return false;
    }

    public static Object isInfinite(Object num) {
        if (num instanceof Double) return Double.isInfinite((Double) num);
        if (num instanceof Float) return Float.isInfinite((Float) num);
        return false;
    }

    public static Object isFinite(Object num) {
        if (num instanceof Double) return Double.isFinite((Double) num);
        if (num instanceof Float) return Float.isFinite((Float) num);
        return true;
    }

    // ========== Hutool 启发 ==========

    /** 精确四舍五入到 N 位小数 */
    public static Object round(Object num, Object scale) {
        double d = ((Number) num).doubleValue();
        int s = ((Number) scale).intValue();
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(d);
        return bd.setScale(s, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    /** 格式化为百分比字符串，如 0.156 → "15.6%" */
    public static Object formatPercent(Object num, Object scale) {
        double d = ((Number) num).doubleValue();
        int s = ((Number) scale).intValue();
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(d * 100);
        return bd.setScale(s, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** 判断是否为质数 */
    public static Object isPrime(Object num) {
        int n = ((Number) num).intValue();
        if (n < 2) return false;
        if (n < 4) return true;
        if (n % 2 == 0 || n % 3 == 0) return false;
        for (int i = 5; i * i <= n; i += 6) {
            if (n % i == 0 || n % (i + 2) == 0) return false;
        }
        return true;
    }

    /** 向上取整除法 */
    public static Object ceilDiv(Object num, Object divisor) {
        int a = ((Number) num).intValue();
        int b = ((Number) divisor).intValue();
        return (a + b - 1) / b;
    }
}
