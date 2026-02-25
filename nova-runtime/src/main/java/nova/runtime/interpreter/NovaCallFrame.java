package nova.runtime.interpreter;

import com.novalang.compiler.ast.SourceLocation;
import com.novalang.ir.hir.decl.HirFunction;
import com.novalang.ir.hir.decl.HirParam;
import com.novalang.ir.hir.expr.HirLambda;
import nova.runtime.NovaValue;

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

    public static NovaCallFrame fromHirFunction(HirFunctionValue func, List<NovaValue> args) {
        HirFunction decl = func.getDeclaration();
        SourceLocation loc = decl.getLocation();
        String name = decl.isConstructor() ? "<init>" : func.getName();
        String summary = buildParamSummary(decl.getParams(), args);
        return new NovaCallFrame(name, loc.getFile(), loc.getLine(), loc.getColumn(), summary);
    }

    public static NovaCallFrame fromHirLambda(HirLambdaValue lambda, List<NovaValue> args, String parentFuncName) {
        HirLambda expr = lambda.getExpression();
        SourceLocation loc = expr.getLocation();
        String name = parentFuncName != null ? "<lambda in " + parentFuncName + ">" : "<lambda>";
        String summary = buildParamSummary(expr.getParams(), args);
        return new NovaCallFrame(name, loc.getFile(), loc.getLine(), loc.getColumn(), summary);
    }

    public static NovaCallFrame fromBoundMethod(NovaBoundMethod bound, List<NovaValue> args) {
        NovaCallable method = bound.getMethod();
        if (method instanceof HirFunctionValue) {
            HirFunctionValue func = (HirFunctionValue) method;
            HirFunction decl = func.getDeclaration();
            SourceLocation loc = decl.getLocation();
            String name = decl.isConstructor() ? "<init>" : bound.getName();
            String summary = buildParamSummary(decl.getParams(), args);
            return new NovaCallFrame(name, loc.getFile(), loc.getLine(), loc.getColumn(), summary);
        }
        if (method instanceof HirLambdaValue) {
            HirLambdaValue lambda = (HirLambdaValue) method;
            HirLambda expr = lambda.getExpression();
            SourceLocation loc = expr.getLocation();
            String summary = buildParamSummary(expr.getParams(), args);
            return new NovaCallFrame("<lambda>", loc.getFile(), loc.getLine(), loc.getColumn(), summary);
        }
        // Native/other callable - no source location
        return new NovaCallFrame(bound.getName(), "<native>", 0, 0, truncateArgs(args));
    }

    /** MIR 函数调用帧（惰性参数摘要 — 仅异常时计算） */
    public static NovaCallFrame fromMirCallable(String funcName, List<NovaValue> args) {
        NovaCallFrame frame = new NovaCallFrame(funcName, "<mir>", 0, 0, null);
        frame.lazyArgs = args;
        return frame;
    }

    private static String buildParamSummary(List<HirParam> params, List<NovaValue> args) {
        if (params.isEmpty() && args.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int count = Math.min(params.size(), args.size());
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append(params.get(i).getName()).append(": ");
            String val = truncValue(args.get(i));
            sb.append(val);
            if (sb.length() > MAX_SUMMARY_LEN) {
                return sb.substring(0, MAX_SUMMARY_LEN) + "...";
            }
        }
        // Handle extra args beyond param count (varargs)
        for (int i = count; i < args.size(); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(truncValue(args.get(i)));
            if (sb.length() > MAX_SUMMARY_LEN) {
                return sb.substring(0, MAX_SUMMARY_LEN) + "...";
            }
        }
        return sb.toString();
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
