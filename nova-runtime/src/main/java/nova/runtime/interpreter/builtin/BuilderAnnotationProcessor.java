package nova.runtime.interpreter.builtin;
import nova.runtime.*;
import nova.runtime.types.NovaClass;
import nova.runtime.interpreter.NovaNativeFunction;
import nova.runtime.interpreter.NovaBuilder;

import java.util.Map;

/**
 * 内置 @builder 注解处理器。
 * 为类生成 builder() 静态方法。
 */
public class BuilderAnnotationProcessor implements NovaAnnotationProcessor {

    @Override
    public String getAnnotationName() {
        return "builder";
    }

    @Override
    public void processClass(NovaValue target, Map<String, NovaValue> args, ExecutionContext ctx) {
        NovaClass clazz = (NovaClass) target;
        clazz.setBuilder(true);
        clazz.setStaticField("builder", new NovaNativeFunction("builder", 0,
                (interp, a) -> new NovaBuilder(clazz)));
    }
}
