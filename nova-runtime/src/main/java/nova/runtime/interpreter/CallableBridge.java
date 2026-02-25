package nova.runtime.interpreter;

import nova.runtime.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * NovaCallable 的互操作桥接包装。
 * <p>
 * 继承 {@link NovaValue}（使 fromJava 能原样返回）、实现 {@link NovaCallable}（供 HirEvaluator 调用）、
 * 实现 {@link Function1} 或 {@link Function2}（供 ListExtensions.invoke1/invoke2 调用）。
 * <p>
 * 解决 StdlibRegistry Java 互操作层中 NovaCallable 的身份丢失问题：
 * 当 lambda 作为参数传递给 List.add 等方法时，经过 NovaListView → fromJava 能保持可调用性。
 */
class CallableBridge extends NovaValue implements Function1<Object, Object>, NovaCallable {

    final NovaCallable original;
    final Interpreter interp;
    CallableBridge(NovaCallable original, Interpreter interp) {
        this.original = original;
        this.interp = interp;
    }

    // ---- Function1 (供 ListExtensions.invoke1) ----

    @Override
    public Object invoke(Object arg1) {
        NovaValue result = original.call(interp, Collections.singletonList(NovaValue.fromJava(arg1)));
        return toJava(result);
    }

    // ---- NovaCallable (供 HirEvaluator 直接调用) ----

    @Override
    public String getName() { return original.getName(); }

    @Override
    public int getArity() { return original.getArity(); }

    @Override
    public NovaValue call(Interpreter interpreter, List<NovaValue> args) {
        return original.call(interpreter, args);
    }

    @Override
    public boolean supportsNamedArgs() { return original.supportsNamedArgs(); }

    @Override
    public NovaValue callWithNamed(Interpreter interpreter, List<NovaValue> args,
                                    Map<String, NovaValue> namedArgs) {
        return original.callWithNamed(interpreter, args, namedArgs);
    }

    // ---- NovaValue ----

    @Override
    public String getTypeName() { return "Function"; }

    @Override
    public Object toJavaValue() { return this; }

    @Override
    public String asString() { return original.toString(); }

    // ---- 辅助 ----

    /** 保持 NovaObject/NovaPair 身份，避免有损 toJavaValue 转换 */
    static Object toJava(NovaValue val) {
        if (val instanceof NovaObject || val instanceof NovaPair) return val;
        return val.toJavaValue();
    }

    /** arity-0 隐式 it 参数（供 MapExtensions 区分 arity） */
    static final class Implicit extends CallableBridge implements ImplicitItFunction<Object, Object> {
        Implicit(NovaCallable original, Interpreter interp) {
            super(original, interp);
        }
    }

    /** arity-2 桥接（实现 Function2 而非 Function1，因为 andThen 签名冲突不能同时实现） */
    static final class Arity2 extends NovaValue implements Function2<Object, Object, Object>, NovaCallable {
        final NovaCallable original;
        private final Interpreter interp;

        Arity2(NovaCallable original, Interpreter interp) {
            this.original = original;
            this.interp = interp;
        }

        @Override
        public Object invoke(Object arg1, Object arg2) {
            List<NovaValue> args = new ArrayList<>();
            args.add(NovaValue.fromJava(arg1));
            args.add(NovaValue.fromJava(arg2));
            return toJava(original.call(interp, args));
        }

        @Override public String getName() { return original.getName(); }
        @Override public int getArity() { return original.getArity(); }
        @Override public NovaValue call(Interpreter interpreter, List<NovaValue> args) {
            return original.call(interpreter, args);
        }
        @Override public boolean supportsNamedArgs() { return original.supportsNamedArgs(); }
        @Override public NovaValue callWithNamed(Interpreter interpreter, List<NovaValue> args,
                                                  Map<String, NovaValue> namedArgs) {
            return original.callWithNamed(interpreter, args, namedArgs);
        }
        @Override public String getTypeName() { return "Function"; }
        @Override public Object toJavaValue() { return this; }
        @Override public String asString() { return original.toString(); }
    }
}
