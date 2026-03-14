package com.novalang.bench;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleScriptContext;

import groovy.lang.Binding;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlEngine;
import org.apache.commons.jexl3.JexlScript;
import org.apache.commons.jexl3.MapContext;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import com.novalang.runtime.CompiledNova;
import com.novalang.runtime.Nova;

final class ScriptBenchSupport {

    private static final ScriptEngineManager SCRIPT_ENGINE_MANAGER = new ScriptEngineManager();

    private ScriptBenchSupport() {
    }

    // ---- Nova ----

    static Object evalNova(String source) {
        return new Nova().eval(source);
    }

    static CompiledNova compileNova(String source) {
        return new Nova().compileToBytecode(source);
    }

    // ---- Nashorn (JSR 223) ----

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

    // ---- GraalJS (Polyglot API) ----

    static Context newGraalJsContext() {
        return Context.newBuilder("js")
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    static int evalGraalJs(String source) {
        try (Context ctx = newGraalJsContext()) {
            Value result = ctx.eval("js", source);
            return result.asInt();
        }
    }

    // ---- Groovy (GroovyShell) ----

    static Object evalGroovy(String source) {
        GroovyShell shell = new GroovyShell(new Binding());
        return shell.evaluate(source);
    }

    static Script parseGroovy(String source) {
        GroovyShell shell = new GroovyShell(new Binding());
        return shell.parse(source);
    }

    static Object runGroovyScript(Script script) {
        return script.run();
    }

    // ---- JEXL ----

    private static final JexlEngine JEXL_ENGINE = new JexlBuilder()
            .cache(512)
            .strict(true)
            .silent(false)
            .create();

    static JexlEngine getJexlEngine() {
        return JEXL_ENGINE;
    }

    static JexlScript createJexlScript(String source) {
        return JEXL_ENGINE.createScript(source);
    }

    static Object evalJexl(String source) {
        JexlScript script = JEXL_ENGINE.createScript(source);
        return script.execute(new MapContext());
    }

    static Object evalJexlScript(JexlScript script) {
        return script.execute(new MapContext());
    }

    // ---- Shared utilities ----

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
