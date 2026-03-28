package com.novalang.runtime.interpreter.stdlib.spi;

import com.novalang.runtime.interpreter.stdlib.StdlibJsonCompiled;
import com.novalang.runtime.stdlib.spi.JsonProvider;

/**
 * 内置 JSON 提供者 — 手写递归下降 parser，零外部依赖。
 */
public final class BuiltinJsonProvider implements JsonProvider {

    @Override public String name() { return "builtin"; }
    @Override public int priority() { return 0; }

    @Override
    public Object parse(String text) {
        return StdlibJsonCompiled.builtinParse(text);
    }

    @Override
    public String stringify(Object value) {
        return StdlibJsonCompiled.builtinStringify(value, false, 0, 2);
    }

    @Override
    public String stringifyPretty(Object value, int indent) {
        return StdlibJsonCompiled.builtinStringify(value, true, 0, indent);
    }
}
