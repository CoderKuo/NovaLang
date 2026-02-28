package nova.runtime.interpreter;

import com.novalang.ir.mir.MirFunction;
import com.novalang.ir.mir.MirLocal;
import com.novalang.ir.mir.MirParam;
import nova.runtime.AbstractNovaValue;
import nova.runtime.ExecutionContext;
import nova.runtime.NovaValue;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MIR 函数的 {@link nova.runtime.NovaCallable} 适配器。
 *
 * <p>让 MIR 函数能被现有运行时（如 NovaClass.instantiate、NovaBoundMethod）调用。
 * 继承 AbstractNovaValue 使其可存入 Environment。</p>
 *
 * <p>Lambda 匿名类的 invoke 方法：locals[0]=this, locals[1..N]=参数。
 * 捕获变量通过 GET_FIELD(this, name) 访问，存储在 captureFields 中。</p>
 */
final class MirCallable extends AbstractNovaValue implements nova.runtime.NovaCallable {

    private final MirInterpreter mirInterp;
    private final MirFunction function;
    private final Map<String, NovaValue> captureFields;

    MirCallable(MirInterpreter mirInterp, MirFunction function, Map<String, NovaValue> captureFields) {
        this.mirInterp = mirInterp;
        this.function = function;
        this.captureFields = captureFields != null ? captureFields : Collections.emptyMap();
    }

    @Override
    public String getName() {
        return function.getName();
    }

    @Override
    public int getArity() {
        List<MirParam> params = function.getParams();
        // 隐式 it lambda：MIR 将 it 显式化为参数，但 HIR 约定 arity=0
        // 用于 CallableBridge.Implicit 包装，使 MapExtensions.isImplicitIt 正确判断
        if (params.size() == 1 && "it".equals(params.get(0).getName())) {
            return 0;
        }
        return params.size();
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        // 实例方法（lambda invoke / class method）：locals[0]=this, locals[1..N]=params
        // 调用约定：
        //   - executeBoundMethod 已 prepend receiver → args.size() == params.size() + 1
        //   - lambda invoke 直接调用 → args.size() == params.size()，需要 prepend this
        List<MirLocal> locals = function.getLocals();
        boolean hasThis = !locals.isEmpty() && "this".equals(locals.get(0).getName());
        boolean needsPrependThis;
        if (hasThis && "invoke".equals(function.getName())) {
            // Lambda invoke: this = MirCallable 自身（用于捕获字段访问）
            // executeBoundMethod 会 prepend this → 通过 identity 检查判断
            needsPrependThis = args.isEmpty() || args.get(0) != this;
        } else {
            // 类方法：receiver 由 executeBoundMethod prepend
            needsPrependThis = hasThis && args.size() == function.getParams().size();
        }

        NovaValue[] allArgs;
        if (needsPrependThis) {
            allArgs = new NovaValue[1 + args.size()];
            allArgs[0] = this;
            for (int i = 0; i < args.size(); i++) {
                allArgs[i + 1] = args.get(i);
            }
        } else {
            allArgs = args.toArray(new NovaValue[0]);
        }
        // 递归深度检查 + 惰性调用帧（参数摘要仅在异常时计算）
        String funcName = function.getName();
        // async 线程传入子 Interpreter，使用其 mirInterpreter 保证线程安全
        MirInterpreter targetMirInterp = ctx.getMirInterpreter() != null
                ? (MirInterpreter) ctx.getMirInterpreter() : mirInterp;
        if (!"<init>".equals(funcName) && !"<clinit>".equals(funcName)) {
            int maxDepth = ctx.getMaxRecursionDepth();
            if (maxDepth > 0 && ctx.getCallDepth() >= maxDepth) {
                throw new NovaRuntimeException("Maximum recursion depth exceeded (" + maxDepth + ")");
            }
            String displayName = "invoke".equals(funcName) ? "<lambda>" : funcName;
            ctx.pushCallFrame(displayName, args);
            ctx.incrementCallDepth();
            try {
                return targetMirInterp.executeFunction(function, allArgs);
            } catch (NovaRuntimeException e) {
                List<String> stackList = ctx.captureStackTrace();
                e.setNovaStackTrace(stackList != null ? String.join("\n", stackList) : null);
                throw e;
            } finally {
                ctx.decrementCallDepth();
                ctx.popCallFrame();
            }
        }
        return targetMirInterp.executeFunction(function, allArgs);
    }

    /**
     * 数组直通调用（MirInterpreter 内部热路径使用，跳过 List→Array 转换）。
     */
    NovaValue callDirect(Interpreter interpreter, NovaValue[] args) {
        List<MirLocal> locals = function.getLocals();
        boolean hasThis = !locals.isEmpty() && "this".equals(locals.get(0).getName());
        NovaValue[] allArgs;
        if (hasThis && "invoke".equals(function.getName())) {
            boolean needsPrepend = args.length == 0 || args[0] != this;
            if (needsPrepend) {
                allArgs = new NovaValue[1 + args.length];
                allArgs[0] = this;
                System.arraycopy(args, 0, allArgs, 1, args.length);
            } else {
                allArgs = args;
            }
        } else if (hasThis && args.length == function.getParams().size()) {
            allArgs = new NovaValue[1 + args.length];
            allArgs[0] = this;
            System.arraycopy(args, 0, allArgs, 1, args.length);
        } else {
            allArgs = args;
        }
        String funcName = function.getName();
        MirInterpreter targetMirInterp = interpreter.mirInterpreter != null
                ? interpreter.mirInterpreter : mirInterp;
        if (!"<init>".equals(funcName) && !"<clinit>".equals(funcName)) {
            int maxDepth = interpreter.getSecurityPolicy().getMaxRecursionDepth();
            if (maxDepth > 0 && interpreter.callDepth >= maxDepth) {
                throw new NovaRuntimeException("Maximum recursion depth exceeded (" + maxDepth + ")");
            }
            String displayName = "invoke".equals(funcName) ? "<lambda>" : funcName;
            interpreter.callStack.push(NovaCallFrame.fromMirCallable(displayName,
                    java.util.Arrays.asList(args)));
            interpreter.callDepth++;
            try {
                return targetMirInterp.executeFunction(function, allArgs);
            } catch (NovaRuntimeException e) {
                e.setNovaStackTrace(interpreter.captureStackTraceString());
                throw e;
            } finally {
                interpreter.callDepth--;
                interpreter.callStack.pop();
            }
        }
        return targetMirInterp.executeFunction(function, allArgs);
    }

    /** 获取捕获变量（供 GET_FIELD 使用） */
    NovaValue getCaptureField(String name) {
        return captureFields.get(name);
    }

    /** 设置捕获变量（供 SET_FIELD 使用，用于可变捕获） */
    void setCaptureField(String name, NovaValue value) {
        if (captureFields instanceof java.util.LinkedHashMap) {
            captureFields.put(name, value);
        }
    }

    @Override
    public String getTypeName() {
        return "MirFunction";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "<mir fun " + function.getName() + ">";
    }

    @Override
    public java.util.List<String> getParamNames() {
        List<MirParam> params = function.getParams();
        java.util.List<String> names = new java.util.ArrayList<>(params.size());
        for (MirParam p : params) {
            names.add(p.getName());
        }
        return names;
    }

    @Override
    public java.util.List<String> getParamTypeNames() {
        List<MirParam> params = function.getParams();
        java.util.List<String> types = new java.util.ArrayList<>(params.size());
        for (MirParam p : params) {
            if (p.getType() != null) {
                String raw = p.getType().toString();
                // JVM 内部名 "java/lang/String" → 简单名 "String"
                int slash = raw.lastIndexOf('/');
                types.add(slash >= 0 ? raw.substring(slash + 1) : raw);
            } else {
                types.add(null);
            }
        }
        return types;
    }

    /** 获取各参数是否有默认值 */
    public java.util.List<Boolean> getParamHasDefaults() {
        List<MirParam> params = function.getParams();
        java.util.List<Boolean> defaults = new java.util.ArrayList<>(params.size());
        for (MirParam p : params) {
            defaults.add(p.hasDefault());
        }
        return defaults;
    }

    MirFunction getFunction() {
        return function;
    }
}
