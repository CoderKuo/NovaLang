package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.List;
import java.util.Map;

/**
 * 绑定方法（对象方法调用）
 */
public final class NovaBoundMethod extends AbstractNovaValue implements nova.runtime.NovaCallable {

    private final NovaValue receiver;
    private final nova.runtime.NovaCallable method;

    public NovaBoundMethod(NovaValue receiver, nova.runtime.NovaCallable method) {
        this.receiver = receiver;
        this.method = method;
    }

    public NovaValue getReceiver() {
        return receiver;
    }

    public nova.runtime.NovaCallable getMethod() {
        return method;
    }

    @Override
    public String getName() {
        return method.getName();
    }

    @Override
    public int getArity() {
        return method.getArity();
    }

    @Override
    public String getTypeName() {
        return "BoundMethod";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public String toString() {
        return "<bound method " + method.getName() + ">";
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        return ctx.executeBoundMethod(this, args, null);
    }

    @Override
    public boolean supportsNamedArgs() {
        return method.supportsNamedArgs();
    }

    @Override
    public NovaValue callWithNamed(ExecutionContext ctx, List<NovaValue> args,
                                    Map<String, NovaValue> namedArgs) {
        return ctx.executeBoundMethod(this, args, namedArgs);
    }
}
