package nova.runtime.interpreter;
import nova.runtime.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 部分应用函数
 * 当函数调用包含占位符 _ 时，创建此对象而不是立即调用
 */
public final class NovaPartialApplication extends AbstractNovaValue implements nova.runtime.NovaCallable {

    private final nova.runtime.NovaCallable target;
    private final List<Object> partialArgs;  // NovaValue 或 PlaceholderMarker
    private final int placeholderCount;

    // 占位符标记
    public static final Object PLACEHOLDER = new Object() {
        @Override
        public String toString() {
            return "_";
        }
    };

    public NovaPartialApplication(nova.runtime.NovaCallable target, List<Object> partialArgs) {
        this.target = target;
        this.partialArgs = partialArgs;
        int count = 0;
        for (Object arg : partialArgs) {
            if (arg == PLACEHOLDER) {
                count++;
            }
        }
        this.placeholderCount = count;
    }

    @Override
    public String getTypeName() {
        return "PartialApplication";
    }

    @Override
    public Object toJavaValue() {
        return this;
    }

    @Override
    public NovaValue call(ExecutionContext ctx, List<NovaValue> args) {
        if (args.size() < placeholderCount) {
            throw new NovaRuntimeException("Expected " + placeholderCount + " arguments, got " + args.size());
        }

        // 填充占位符
        List<NovaValue> fullArgs = new ArrayList<NovaValue>();
        int argIndex = 0;
        for (Object partial : partialArgs) {
            if (partial == PLACEHOLDER) {
                fullArgs.add(args.get(argIndex++));
            } else {
                fullArgs.add((NovaValue) partial);
            }
        }

        return target.call(ctx, fullArgs);
    }

    @Override
    public int getArity() {
        return placeholderCount;
    }

    @Override
    public String getName() {
        return "<partial>";
    }

    @Override
    public String toString() {
        return "<partial " + target + ">";
    }
}
