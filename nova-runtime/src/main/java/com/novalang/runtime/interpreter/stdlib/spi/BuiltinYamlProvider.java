package com.novalang.runtime.interpreter.stdlib.spi;

import com.novalang.runtime.interpreter.stdlib.StdlibYamlCompiled;
import com.novalang.runtime.stdlib.spi.YamlProvider;

/**
 * 内置 YAML 提供者 — 手写 parser，零外部依赖。
 */
public final class BuiltinYamlProvider implements YamlProvider {

    @Override public String name() { return "builtin"; }
    @Override public int priority() { return 0; }

    @Override
    public Object parse(String text) {
        return StdlibYamlCompiled.builtinParse(text);
    }

    @Override
    public String stringify(Object value) {
        return StdlibYamlCompiled.builtinStringify(value);
    }
}
