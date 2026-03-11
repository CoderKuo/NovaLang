package com.novalang.bench;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import nova.runtime.CompiledNova;
import nova.runtime.Nova;

final class ScriptBenchSupport {

    private static final ScriptEngineManager SCRIPT_ENGINE_MANAGER = new ScriptEngineManager();

    private ScriptBenchSupport() {
    }

    static Object evalNova(String source) {
        return new Nova().eval(source);
    }

    static CompiledNova compileNova(String source) {
        return new Nova().compileToBytecode(source);
    }

    static ScriptEngine newNashornEngine() {
        ScriptEngine engine = SCRIPT_ENGINE_MANAGER.getEngineByName("nashorn");
        if (engine == null) {
            engine = SCRIPT_ENGINE_MANAGER.getEngineByName("Nashorn");
        }
        if (engine == null) {
            throw new IllegalStateException("Nashorn engine is not available. Ensure org.openjdk.nashorn:nashorn-core is on the runtime classpath.");
        }
        return engine;
    }

    static CompiledScript compileNashorn(String source) throws ScriptException {
        ScriptEngine engine = newNashornEngine();
        if (!(engine instanceof Compilable)) {
            throw new IllegalStateException("Nashorn engine does not implement Compilable: " + engine.getClass().getName());
        }
        return ((Compilable) engine).compile(source);
    }

    static Object evalCompiledScript(CompiledScript script) throws ScriptException {
        return script.eval(new SimpleScriptContext());
    }

    static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new IllegalArgumentException("Expected numeric result, got: " + value);
    }
}
