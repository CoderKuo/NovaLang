package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;

import java.util.List;

/**
 * 命名空间 Builder DSL，供 {@code Nova.defineLibrary(name, builder -> { ... })} 使用。
 *
 * <pre>
 * nova.defineLibrary("utils", lib -> {
 *     lib.defineVal("PI", 3.14159);
 *     lib.defineFunction("double", n -> (int) n * 2);
 *     lib.defineFunctionVararg("sum", args -> {
 *         int total = 0;
 *         for (Object a : args) total += (int) a;
 *         return total;
 *     });
 * });
 * </pre>
 */
public final class LibraryBuilder {

    private final NovaLibrary library;

    public LibraryBuilder(NovaLibrary library) {
        this.library = library;
    }

    // ── 值注册 ──

    public LibraryBuilder defineVal(String name, Object value) {
        library.putMember(name, AbstractNovaValue.fromJava(value));
        return this;
    }

    // ── 函数注册 ──

    public LibraryBuilder defineFunction(String name, Function0<Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 0, (ctx, args) ->
                wrapReturn(func.invoke())));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function1<Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 1, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function2<Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 2, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function3<Object, Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 3, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1), safeArg(args, 2)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function4<Object, Object, Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 4, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1), safeArg(args, 2), safeArg(args, 3)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function5<Object, Object, Object, Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 5, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1), safeArg(args, 2), safeArg(args, 3), safeArg(args, 4)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function6<Object, Object, Object, Object, Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 6, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1), safeArg(args, 2), safeArg(args, 3), safeArg(args, 4), safeArg(args, 5)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function7<Object, Object, Object, Object, Object, Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 7, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1), safeArg(args, 2), safeArg(args, 3), safeArg(args, 4), safeArg(args, 5), safeArg(args, 6)))));
        return this;
    }

    public LibraryBuilder defineFunction(String name, Function8<Object, Object, Object, Object, Object, Object, Object, Object, Object> func) {
        library.putMember(name, new NovaNativeFunction(name, 8, (ctx, args) ->
                wrapReturn(func.invoke(safeArg(args, 0), safeArg(args, 1), safeArg(args, 2), safeArg(args, 3), safeArg(args, 4), safeArg(args, 5), safeArg(args, 6), safeArg(args, 7)))));
        return this;
    }

    public LibraryBuilder defineFunctionVararg(String name, Function1<Object[], Object> func) {
        library.putMember(name, new NovaNativeFunction(name, -1, (ctx, args) -> {
            Object[] javaArgs = new Object[args.size()];
            for (int i = 0; i < args.size(); i++) javaArgs[i] = unwrap(args.get(i));
            return wrapReturn(func.invoke(javaArgs));
        }));
        return this;
    }

    // ── 内部工具 ──

    private static Object unwrap(NovaValue v) {
        return v == null || v.isNull() ? null : v.toJavaValue();
    }

    /** 安全取参数，越界返回 null（防御性处理） */
    private static Object safeArg(java.util.List<NovaValue> args, int index) {
        return index < args.size() ? unwrap(args.get(index)) : null;
    }

    private static NovaValue wrapReturn(Object result) {
        if (result == null) return NovaNull.UNIT;
        return AbstractNovaValue.fromJava(result);
    }
}
