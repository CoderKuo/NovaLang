package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.List;

/**
 * 原生（Java）函数
 */
public final class NovaNativeFunction extends AbstractNovaValue implements nova.runtime.NovaCallable {

    /**
     * 原生函数接口
     */
    @FunctionalInterface
    public interface NativeFunc {
        NovaValue apply(ExecutionContext ctx, List<NovaValue> args);
    }

    private final String name;
    private final int arity;
    private final NativeFunc function;

    public NovaNativeFunction(String name, int arity, NativeFunc function) {
        this.name = name;
        this.arity = arity;
        this.function = function;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public int getArity() {
        return arity;
    }

    @Override
    public String getTypeName() {
        return "NativeFunction";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "<native fun " + name + ">";
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        return function.apply(ctx, args);
    }

    @Override
    public Object dynamicInvoke(NovaValue[] args) {
        return function.apply(null, java.util.Arrays.asList(args));
    }

    // ============ 便捷工厂方法 ============

    public static NovaNativeFunction create(String name, NativeFunc0 func) {
        return new NovaNativeFunction(name, 0, (interp, args) -> func.apply());
    }

    public static NovaNativeFunction create(String name, NativeFunc1 func) {
        return new NovaNativeFunction(name, 1, (interp, args) -> func.apply(args.get(0)));
    }

    public static NovaNativeFunction create(String name, NativeFunc2 func) {
        return new NovaNativeFunction(name, 2, (interp, args) -> func.apply(args.get(0), args.get(1)));
    }

    public static NovaNativeFunction create(String name, NativeFunc3 func) {
        return new NovaNativeFunction(name, 3, (interp, args) ->
                func.apply(args.get(0), args.get(1), args.get(2)));
    }

    public static NovaNativeFunction createVararg(String name, NativeFunc func) {
        return new NovaNativeFunction(name, -1, func);
    }

    // 函数接口
    @FunctionalInterface
    public interface NativeFunc0 {
        NovaValue apply();
    }

    @FunctionalInterface
    public interface NativeFunc1 {
        NovaValue apply(NovaValue arg1);
    }

    @FunctionalInterface
    public interface NativeFunc2 {
        NovaValue apply(NovaValue arg1, NovaValue arg2);
    }

    @FunctionalInterface
    public interface NativeFunc3 {
        NovaValue apply(NovaValue arg1, NovaValue arg2, NovaValue arg3);
    }
}
