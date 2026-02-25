package nova.runtime.interpreter;
import nova.runtime.*;

import com.novalang.ir.hir.decl.HirFunction;
import com.novalang.ir.hir.decl.HirParam;

import java.util.List;
import java.util.Map;

/**
 * HIR 函数运行时值。
 * 持有 HirFunction（HIR 节点）而非 FunDecl（AST 节点）。
 */
public final class HirFunctionValue extends NovaValue implements NovaCallable {

    private final String name;
    private final HirFunction declaration;
    private final Environment closure;

    public HirFunctionValue(String name, HirFunction declaration, Environment closure) {
        this.name = name;
        this.declaration = declaration;
        this.closure = closure;
    }

    @Override
    public String getName() { return name; }

    public HirFunction getDeclaration() { return declaration; }

    public Environment getClosure() { return closure; }

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
    public boolean isCallable() { return true; }

    @Override
    public String toString() { return "fun " + name + "(...)"; }

    @Override
    public boolean supportsNamedArgs() { return true; }

    @Override
    public NovaValue call(Interpreter interpreter, List<NovaValue> args) {
        return interpreter.executeHirFunction(this, args, null);
    }

    @Override
    public NovaValue callWithNamed(Interpreter interpreter, List<NovaValue> args,
                                    Map<String, NovaValue> namedArgs) {
        return interpreter.executeHirFunction(this, args, namedArgs);
    }

    public NovaBoundMethod bind(NovaValue receiver) {
        return new NovaBoundMethod(receiver, this);
    }
}
