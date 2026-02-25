package nova.runtime.stdlib;

import nova.runtime.NovaRange;

import java.util.ArrayList;
import java.util.List;

/**
 * Number 类型扩展方法（Int/Long/Double/Float 共用）。
 */
@Ext("java/lang/Number")
public final class NumberExtensions {

    private NumberExtensions() {}

    // ========== 范围方法 ==========

    public static Object downTo(Object num, Object to) {
        int from = ((Number) num).intValue();
        int target = ((Number) to).intValue();
        List<Object> result = new ArrayList<>();
        for (int i = from; i >= target; i--) result.add(i);
        return result;
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
}
