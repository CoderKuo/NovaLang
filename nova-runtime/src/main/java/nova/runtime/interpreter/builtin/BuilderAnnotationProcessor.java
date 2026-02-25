package nova.runtime.interpreter.builtin;
import nova.runtime.*;

import nova.runtime.interpreter.*;

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
    public void processClass(NovaClass target, Map<String, NovaValue> args, Interpreter interpreter) {
        target.setBuilder(true);
        target.setStaticField("builder", new NovaNativeFunction("builder", 0,
                (interp, a) -> new NovaBuilder(target)));
    }
}
