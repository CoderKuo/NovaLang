package com.novalang.runtime.interpreter.builtin;
import com.novalang.runtime.*;
import com.novalang.runtime.types.NovaClass;
import com.novalang.runtime.interpreter.NovaNativeFunction;
import com.novalang.runtime.interpreter.NovaBuilder;

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
