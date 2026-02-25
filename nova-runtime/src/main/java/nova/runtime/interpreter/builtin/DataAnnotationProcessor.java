package nova.runtime.interpreter.builtin;
import nova.runtime.*;

import nova.runtime.interpreter.*;

import java.util.Map;

/**
 * 内置 @data 注解处理器。
 * 将类标记为 data class，自动生成 toString/equals/copy/componentN 等方法。
 */
public class DataAnnotationProcessor implements NovaAnnotationProcessor {

    @Override
    public String getAnnotationName() {
        return "data";
    }

    @Override
    public void processClass(NovaClass target, Map<String, NovaValue> args, Interpreter interpreter) {
        target.setData(true);
    }
}
