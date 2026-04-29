package com.novalang.script;

import com.novalang.runtime.CompiledNova;

import javax.script.Bindings;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

/**
 * NovaLang precompiled script backed by {@link CompiledNova}.
 */
public class NovaCompiledScript extends CompiledScript {

    private final NovaScriptEngine engine;
    private final CompiledNova compiled;

    NovaCompiledScript(NovaScriptEngine engine, CompiledNova compiled) {
        this.engine = engine;
        this.compiled = compiled;
    }

    @Override
    public Object eval(ScriptContext context) throws ScriptException {
        Bindings bindings = context.getBindings(ScriptContext.ENGINE_SCOPE);
        try {
            return bindings != null ? compiled.runIsolated(bindings) : compiled.run();
        } catch (Exception e) {
            throw new ScriptException(e);
        }
    }

    @Override
    public ScriptEngine getEngine() {
        return engine;
    }
}
