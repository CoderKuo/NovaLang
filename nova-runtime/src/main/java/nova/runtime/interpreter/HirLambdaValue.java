package nova.runtime.interpreter;
import nova.runtime.*;
import nova.runtime.types.Environment;

import com.novalang.ir.hir.decl.HirParam;
import com.novalang.ir.hir.expr.HirLambda;

import java.util.List;

/**
 * HIR Lambda 运行时值。
 * 持有 HirLambda（HIR 节点）而非 LambdaExpr（AST 节点）。
 */
public final class HirLambdaValue extends AbstractNovaValue implements nova.runtime.NovaCallable {

    private final HirLambda expression;
    private final Environment closure;

    public HirLambdaValue(HirLambda expression, Environment closure) {
        this.expression = expression;
        this.closure = closure;
    }

    public HirLambda getExpression() { return expression; }

    public Environment getClosure() { return closure; }

    @Override
    public String getName() { return "<lambda>"; }

    @Override
    public int getArity() { return expression.getParams().size(); }

    @Override
    public String getTypeName() { return "Lambda"; }

    @Override
    public Object toJavaValue() { return this; }

    @Override
    public String toString() {
        int arity = getArity();
        if (arity == 0) return "{ -> ... }";
        if (arity == 1) return "{ " + expression.getParams().get(0).getName() + " -> ... }";
        StringBuilder sb = new StringBuilder("{ ");
        for (int i = 0; i < arity; i++) {
            if (i > 0) sb.append(", ");
            sb.append(expression.getParams().get(i).getName());
        }
        sb.append(" -> ... }");
        return sb.toString();
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        return ctx.executeHirLambda(this, args);
    }
}
