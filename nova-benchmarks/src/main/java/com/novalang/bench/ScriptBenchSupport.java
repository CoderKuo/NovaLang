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

    /** 创建启用 Java 互操作的 GraalJS Context（支持 Java.type()） */
    static Context newGraalJsInteropContext() {
        return Context.newBuilder("js")
                .allowAllAccess(true)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
    }

    static int evalGraalJs(String source) {
        try (Context ctx = newGraalJsContext()) {
            Value result = ctx.eval("js", source);
            return result.asInt();
        }
    }

    static int evalGraalJsInterop(String source) {
        try (Context ctx = newGraalJsInteropContext()) {
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

    // ---- Javet (V8) ----

    static int evalJavet(String source) {
        try (com.caoccao.javet.interop.V8Runtime v8 =
                     com.caoccao.javet.interop.V8Host.getV8Instance().createV8Runtime()) {
            return v8.getExecutor(source).executeInteger();
        } catch (com.caoccao.javet.exceptions.JavetException e) {
            throw new RuntimeException("Javet eval failed", e);
        }
    }

    static com.caoccao.javet.interop.V8Runtime newJavetRuntime() {
        try {
            return com.caoccao.javet.interop.V8Host.getV8Instance().createV8Runtime();
        } catch (com.caoccao.javet.exceptions.JavetException e) {
            throw new RuntimeException("Failed to create Javet V8Runtime", e);
        }
    }

    static int evalJavetWarmed(com.caoccao.javet.interop.V8Runtime v8, String source) {
        try {
            return v8.getExecutor(source).executeInteger();
        } catch (com.caoccao.javet.exceptions.JavetException e) {
            throw new RuntimeException("Javet eval failed", e);
        }
    }

    /** 创建带 JavetProxyConverter 的 V8Runtime（Java 互操作场景用） */
    static com.caoccao.javet.interop.V8Runtime newJavetInteropRuntime() {
        try {
            com.caoccao.javet.interop.V8Runtime v8 =
                    com.caoccao.javet.interop.V8Host.getV8Instance().createV8Runtime();
            com.caoccao.javet.interop.converters.JavetProxyConverter converter =
                    new com.caoccao.javet.interop.converters.JavetProxyConverter();
            converter.getConfig().setProxyListEnabled(true);
            converter.getConfig().setProxyMapEnabled(true);
            converter.getConfig().setProxySetEnabled(true);
            v8.setConverter(converter);
            // 注入常用 Java 类到全局对象
            v8.getGlobalObject().set("JMath", Math.class);
            v8.getGlobalObject().set("JArrayList", java.util.ArrayList.class);
            v8.getGlobalObject().set("JInteger", Integer.class);
            v8.getGlobalObject().set("JStringBuilder", StringBuilder.class);
            v8.getGlobalObject().set("JCollections", java.util.Collections.class);
            v8.getGlobalObject().set("JHashMap", java.util.HashMap.class);
            v8.getGlobalObject().set("JString", String.class);
            return v8;
        } catch (com.caoccao.javet.exceptions.JavetException e) {
            throw new RuntimeException("Failed to create Javet interop runtime", e);
        }
    }

    /** 冷启动 Javet 互操作 eval（每次创建新 runtime + proxy） */
    static int evalJavetInterop(String source) {
        try (com.caoccao.javet.interop.V8Runtime v8 = newJavetInteropRuntime()) {
            return v8.getExecutor(source).executeInteger();
        } catch (com.caoccao.javet.exceptions.JavetException e) {
            throw new RuntimeException("Javet interop eval failed", e);
        }
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
