package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.*;
import com.novalang.runtime.types.Environment;
import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.interpreter.NovaNativeFunction;

/**
 * nova.yaml — YAML 解析与生成（解释器路径）
 * <p>委托 StdlibYamlCompiled 实现，包装为 NovaValue。</p>
 */
public final class StdlibYaml {

    private StdlibYaml() {}

    public static void register(Environment env, Interpreter interp) {
        env.defineVal("yamlParse", NovaNativeFunction.create("yamlParse", text -> {
            Object result = StdlibYamlCompiled.yamlParse(text.asString());
            return AbstractNovaValue.fromJava(result);
        }));

        env.defineVal("yamlStringify", NovaNativeFunction.create("yamlStringify", value -> {
            Object javaVal = value.toJavaValue();
            return NovaString.of((String) StdlibYamlCompiled.yamlStringify(javaVal));
        }));
    }
}
