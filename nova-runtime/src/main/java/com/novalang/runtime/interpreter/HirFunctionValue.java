package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;
import com.novalang.runtime.types.Environment;

import com.novalang.ir.hir.HirAnnotation;
import com.novalang.ir.hir.decl.HirFunction;
import com.novalang.ir.hir.decl.HirParam;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * HIR 函数运行时值。
 * 持有 HirFunction（HIR 节点）而非 FunDecl（AST 节点）。
 */
public final class HirFunctionValue extends AbstractNovaValue implements com.novalang.runtime.NovaCallable {

    private final String name;
    private final HirFunction declaration;
    private final Environment closure;
    private final boolean memoized;
    private Map<MemoKey, NovaValue> memoCache;

    public HirFunctionValue(String name, HirFunction declaration, Environment closure) {
        this.name = name;
        this.declaration = declaration;
        this.closure = closure;
        this.memoized = isMemoizedAnnotation(declaration);
    }

    @Override
    public String getName() { return name; }

    public HirFunction getDeclaration() { return declaration; }

    public Environment getClosure() { return closure; }

    boolean isMemoized() { return memoized; }

    Map<MemoKey, NovaValue> getMemoCache() {
        if (memoCache == null) memoCache = new HashMap<>();
        return memoCache;
    }

    @Override
    public int getArity() {
        for (HirParam param : declaration.getParams()) {
            if (param.isVararg()) return -1;
        }
        return declaration.getParams().size();
    }

    @Override
    public String getTypeName() { return "Function"; }

    @Override
    public Object toJavaValue() { return this; }

    @Override
    public String toString() { return "fun " + name + "(...)"; }

    @Override
    public boolean supportsNamedArgs() { return true; }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        return ctx.executeHirFunction(this, args, null);
    }

    @Override
    public NovaValue callWithNamed(ExecutionContext ctx, List<NovaValue> args,
                                    Map<String, NovaValue> namedArgs) {
        return ctx.executeHirFunction(this, args, namedArgs);
    }

    public NovaBoundMethod bind(NovaValue receiver) {
        return new NovaBoundMethod(receiver, this);
    }

    private static boolean isMemoizedAnnotation(HirFunction declaration) {
        if (declaration == null || declaration.isConstructor() || declaration.hasReifiedTypeParams()) {
            return false;
        }
        List<HirAnnotation> annotations = declaration.getAnnotations();
        if (annotations == null || annotations.isEmpty()) {
            return false;
        }
        for (HirAnnotation annotation : annotations) {
            String name = annotation.getName();
            if ("memoized".equalsIgnoreCase(name) || "memoize".equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
