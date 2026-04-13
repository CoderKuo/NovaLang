package com.novalang.runtime;

import com.novalang.runtime.NovaException.ErrorKind;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

/**
 * NovaLang 错误工厂 — 统一构建所有运行时错误。
 *
 * <p>每个方法自动附带：错误分类、友好消息、修复建议（如有）。</p>
 */
public final class NovaErrors {

    private NovaErrors() {}

    // ── 空引用 ──────────────────────────────────────────

    /** 在 null 上访问成员 */
    public static NovaException nullRef(String memberName) {
        return new NovaException(
            ErrorKind.NULL_REFERENCE,
            "无法在 null 上访问 '" + memberName + "'",
            "在访问前使用 ?. 安全调用或 ?: 提供默认值"
        );
    }

    /** 在 null 上调用方法 */
    public static NovaException nullInvoke(String methodName) {
        return new NovaException(
            ErrorKind.NULL_REFERENCE,
            "无法在 null 上调用方法 '" + methodName + "'",
            "使用 ?. 安全调用: obj?." + methodName + "(...)"
        );
    }

    /** 在 null 上设置成员 */
    public static NovaException nullSet(String memberName) {
        return new NovaException(
            ErrorKind.NULL_REFERENCE,
            "无法在 null 上设置 '" + memberName + "'",
            "确保对象不为 null 后再赋值"
        );
    }

    // ── 类型错误 ────────────────────────────────────────

    /** 类型转换失败 */
    public static NovaException typeMismatch(String fromType, String toType, String suggestion) {
        return new NovaException(
            ErrorKind.TYPE_MISMATCH,
            "无法将 " + fromType + " 转换为 " + toType,
            suggestion
        );
    }

    /** 类型转换失败（自动生成建议） */
    public static NovaException typeMismatch(String fromType, String toType) {
        String suggestion = inferCastSuggestion(fromType, toType);
        return typeMismatch(fromType, toType, suggestion);
    }

    /** 参数类型不匹配 */
    public static NovaException argTypeMismatch(String funcName, int paramIndex,
                                                String expected, String actual) {
        return new NovaException(
            ErrorKind.TYPE_MISMATCH,
            funcName + " 的第 " + (paramIndex + 1) + " 个参数需要 " + expected + "，但传入了 " + actual
        );
    }

    // ── 未定义 ──────────────────────────────────────────

    /** 未定义的成员（带可用列表） */
    public static NovaException undefinedMember(String typeName, String memberName,
                                                Collection<String> available) {
        StringBuilder msg = new StringBuilder();
        msg.append("'").append(typeName).append("' 上没有 '").append(memberName).append("'");

        String suggestion = null;
        if (available != null && !available.isEmpty()) {
            // 模糊匹配
            String closest = findClosest(memberName, available);
            if (closest != null) {
                suggestion = "你是否指的是 '" + closest + "'？";
            } else {
                List<String> sorted = new ArrayList<>(available);
                sorted.sort(String::compareTo);
                int limit = Math.min(sorted.size(), 8);
                suggestion = "可用成员: " + String.join(", ", sorted.subList(0, limit));
                if (sorted.size() > limit) suggestion += " ...共 " + sorted.size() + " 个";
            }
        }
        return new NovaException(ErrorKind.UNDEFINED, msg.toString(), suggestion);
    }

    /** 未定义的成员（无可用列表） */
    public static NovaException undefinedMember(String typeName, String memberName) {
        return undefinedMember(typeName, memberName, null);
    }

    /** 未定义的函数 */
    public static NovaException undefinedFunction(String funcName, Collection<String> available) {
        StringBuilder msg = new StringBuilder();
        msg.append("未定义的函数 '").append(funcName).append("'");
        String suggestion = null;
        if (available != null && !available.isEmpty()) {
            String closest = findClosest(funcName, available);
            if (closest != null) {
                suggestion = "你是否指的是 '" + closest + "'？";
            }
        }
        return new NovaException(ErrorKind.UNDEFINED, msg.toString(), suggestion);
    }

    /** 未定义的变量 */
    public static NovaException undefinedVariable(String varName) {
        return new NovaException(ErrorKind.UNDEFINED, "未定义的变量 '" + varName + "'");
    }

    // ── 参数不匹配 ──────────────────────────────────────

    /** 参数数量不匹配 */
    public static NovaException argCountMismatch(String funcName, int expected, int actual) {
        return new NovaException(
            ErrorKind.ARGUMENT_MISMATCH,
            funcName + " 需要 " + expected + " 个参数，但传入了 " + actual + " 个"
        );
    }

    /** 无法匹配重载 */
    public static NovaException noMatchingOverload(String methodName, String targetType,
                                                   String argTypes) {
        return new NovaException(
            ErrorKind.ARGUMENT_MISMATCH,
            "无法在 " + targetType + " 上找到匹配的 '" + methodName + "' 重载",
            "传入的参数类型: " + argTypes
        );
    }

    // ── Java 互操作 ─────────────────────────────────────

    /** Java 方法调用失败 */
    public static NovaException javaInvokeFailed(String methodName, String targetType,
                                                 Throwable cause) {
        String msg = "调用 " + targetType + "." + methodName + "() 失败";
        if (cause != null && cause.getMessage() != null) {
            msg += ": " + cause.getMessage();
        }
        return new NovaException(ErrorKind.JAVA_INTEROP, msg, null, cause);
    }

    /** Java 成员访问失败 */
    public static NovaException javaMemberNotFound(String memberName, String targetType) {
        return new NovaException(
            ErrorKind.JAVA_INTEROP,
            "在 Java 类型 " + targetType + " 上找不到 '" + memberName + "'",
            "检查方法名或属性名是否正确，注意 Java 的大小写约定"
        );
    }

    /** Java 类找不到 */
    public static NovaException javaClassNotFound(String className, Throwable cause) {
        return new NovaException(
            ErrorKind.JAVA_INTEROP,
            "找不到 Java 类 '" + className + "'",
            "检查类名拼写和 classpath 是否正确",
            cause
        );
    }

    // ── 索引越界 ────────────────────────────────────────

    public static NovaException indexOutOfBounds(int index, int size) {
        return new NovaException(
            ErrorKind.INDEX_OUT_OF_BOUNDS,
            "索引 " + index + " 越界（大小 " + size + "）",
            size > 0 ? "有效范围: 0.." + (size - 1) : "集合为空"
        );
    }

    // ── 内部错误 ────────────────────────────────────────

    /** 包装外部异常，保留 cause 链 */
    public static NovaException wrap(Throwable e) {
        if (e instanceof NovaException) return (NovaException) e;
        String msg = e.getClass().getSimpleName();
        if (e.getMessage() != null) msg += ": " + e.getMessage();
        return new NovaException(ErrorKind.INTERNAL, msg, null, e);
    }

    /** 包装外部异常，附加上下文描述 */
    public static NovaException wrap(String context, Throwable e) {
        if (e instanceof NovaException) return (NovaException) e;
        String msg = context;
        if (e.getMessage() != null) msg += ": " + e.getMessage();
        return new NovaException(ErrorKind.INTERNAL, msg, null, e);
    }

    /** 内部错误（不应该发生） */
    public static NovaException internal(String message) {
        return new NovaException(ErrorKind.INTERNAL, "[内部错误] " + message);
    }

    // ── 工具方法 ────────────────────────────────────────

    /**
     * 在候选列表中找到与 target 最接近的名称。
     * 使用 Levenshtein 距离，阈值为名称长度的一半（至少 2）。
     */
    public static String findClosest(String target, Collection<String> candidates) {
        if (target == null || candidates == null || candidates.isEmpty()) return null;
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int threshold = Math.max(2, target.length() / 2);
        for (String c : candidates) {
            int d = levenshtein(target.toLowerCase(), c.toLowerCase());
            if (d < bestDist && d <= threshold) {
                bestDist = d;
                best = c;
            }
        }
        return best;
    }

    private static int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev; prev = curr; curr = tmp;
        }
        return prev[n];
    }

    /** 根据源类型和目标类型推断转换建议 */
    private static String inferCastSuggestion(String from, String to) {
        if (from == null || to == null) return null;
        String fl = from.toLowerCase();
        String tl = to.toLowerCase();

        // 数值互转
        if (isNumericType(fl) && isNumericType(tl)) {
            return "使用 to" + capitalize(tl) + "() 进行转换";
        }
        // → String
        if ("string".equals(tl)) {
            return "使用 toString() 或字符串模板 \"${value}\"";
        }
        // String → 数值
        if ("string".equals(fl) && isNumericType(tl)) {
            return "使用 to" + capitalize(tl) + "() 解析字符串";
        }
        return null;
    }

    private static boolean isNumericType(String t) {
        return "int".equals(t) || "long".equals(t) || "double".equals(t)
            || "float".equals(t) || "number".equals(t);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
