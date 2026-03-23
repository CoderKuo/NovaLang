package com.novalang.runtime.interpreter;

import com.novalang.runtime.NovaValue;

import java.util.List;

/**
 * Nova 调用帧：记录一次函数/lambda 调用的信息。
 */
public final class NovaCallFrame {

    private static final int MAX_PARAM_LEN = 20;
    private static final int MAX_SUMMARY_LEN = 60;

    private final String functionName;
    private final String fileName;
    private final int line;
    private final int column;
    private String paramSummary;
    /** 惰性参数：仅在 getParamSummary() 时才 toString（MIR 热路径优化） */
    private List<NovaValue> lazyArgs;

    public NovaCallFrame(String functionName, String fileName, int line, int column, String paramSummary) {
        this.functionName = functionName;
        this.fileName = fileName;
        this.line = line;
        this.column = column;
        this.paramSummary = paramSummary;
    }

    public String getFunctionName() { return functionName; }
    public String getFileName() { return fileName; }
    public int getLine() { return line; }
    public int getColumn() { return column; }
    public String getParamSummary() {
        if (paramSummary == null && lazyArgs != null) {
            paramSummary = truncateArgs(lazyArgs);
            lazyArgs = null;
        }
        return paramSummary != null ? paramSummary : "";
    }

    public static NovaCallFrame fromBoundMethod(NovaBoundMethod bound, List<NovaValue> args) {
        // Native/other callable - no source location
        return new NovaCallFrame(bound.getName(), "<native>", 0, 0, truncateArgs(args));
    }

    /** MIR 函数调用帧（惰性参数摘要 — 仅异常时计算） */
    public static NovaCallFrame emptyMirCallable(String funcName) {
        return new NovaCallFrame(funcName, "<mir>", 0, 0, "");
    }

    public static NovaCallFrame fromMirCallable(String funcName, List<NovaValue> args) {
        NovaCallFrame frame = new NovaCallFrame(funcName, "<mir>", 0, 0, null);
        frame.lazyArgs = args;
        return frame;
    }

    private static String truncateArgs(List<NovaValue> args) {
        if (args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(truncValue(args.get(i)));
            if (sb.length() > MAX_SUMMARY_LEN) {
                return sb.substring(0, MAX_SUMMARY_LEN) + "...";
            }
        }
        return sb.toString();
    }

    private static String truncValue(NovaValue val) {
        String s = val.toString();
        if (s.length() > MAX_PARAM_LEN) {
            return s.substring(0, MAX_PARAM_LEN) + "...";
        }
        return s;
    }
}
