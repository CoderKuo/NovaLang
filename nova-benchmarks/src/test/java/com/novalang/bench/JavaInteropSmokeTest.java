package com.novalang.bench;

import javax.script.CompiledScript;

import groovy.lang.Script;
import com.novalang.runtime.CompiledNova;
import org.apache.commons.jexl3.JexlScript;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaInteropSmokeTest {

    @Test
    void allInteropScenariosMatchAcrossAllEngines() throws Exception {
        for (java.util.Map.Entry<String, ScriptScenario> entry : JavaInteropScenarios.all().entrySet()) {
            String name = entry.getKey();
            ScriptScenario scenario = entry.getValue();
            int expected = scenario.runJavaNative();

            // Nova eval
            int novaEval = ScriptBenchSupport.toInt(ScriptBenchSupport.evalNova(scenario.getNovaSource()));
            assertEquals(expected, novaEval, name + " nova eval");

            // Nova compiled
            CompiledNova novaCompiled = ScriptBenchSupport.compileNova(scenario.getNovaSource());
            int novaCompiledResult = ScriptBenchSupport.toInt(novaCompiled.run());
            assertEquals(expected, novaCompiledResult, name + " nova compiled");

            // Nashorn eval
            int nashornEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.newNashornEngine().eval(scenario.getJsSource()));
            assertEquals(expected, nashornEval, name + " nashorn eval");

            // Nashorn compiled
            CompiledScript nashornCompiled = ScriptBenchSupport.compileNashorn(scenario.getJsSource());
            int nashornCompiledResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalCompiledScript(nashornCompiled));
            assertEquals(expected, nashornCompiledResult, name + " nashorn compiled");

            // GraalJS（启用 Java 互操作）
            try (Context ctx = ScriptBenchSupport.newGraalJsInteropContext()) {
                int graalResult = ctx.eval("js", scenario.getJsSource()).asInt();
                assertEquals(expected, graalResult, name + " graaljs");
            }

            // Groovy eval
            int groovyEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalGroovy(scenario.getGroovySource()));
            assertEquals(expected, groovyEval, name + " groovy eval");

            // Groovy parsed
            Script groovyScript = ScriptBenchSupport.parseGroovy(scenario.getGroovySource());
            int groovyResult = ScriptBenchSupport.toInt(ScriptBenchSupport.runGroovyScript(groovyScript));
            assertEquals(expected, groovyResult, name + " groovy parsed");

            // JEXL eval
            int jexlEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalJexl(scenario.getJexlSource()));
            assertEquals(expected, jexlEval, name + " jexl eval");

            // JEXL script
            JexlScript jexlScript = ScriptBenchSupport.createJexlScript(scenario.getJexlSource());
            int jexlResult = ScriptBenchSupport.toInt(ScriptBenchSupport.evalJexlScript(jexlScript));
            assertEquals(expected, jexlResult, name + " jexl script");

            // Javet interop eval
            String javetSrc = resolveJavetSource(name);
            int javetEval = ScriptBenchSupport.evalJavetInterop(javetSrc);
            assertEquals(expected, javetEval, name + " javet eval");

            // Javet interop warmed
            try (com.caoccao.javet.interop.V8Runtime v8 = ScriptBenchSupport.newJavetInteropRuntime()) {
                int javetWarmed = ScriptBenchSupport.evalJavetWarmed(v8, javetSrc);
                assertEquals(expected, javetWarmed, name + " javet warmed");
            }
        }
    }

    private static String resolveJavetSource(String scenario) {
        switch (scenario) {
            case "java_static_call":    return JavaInteropScenarios.javetStaticCall();
            case "java_object_create":  return JavaInteropScenarios.javetObjectCreate();
            case "java_field_access":   return JavaInteropScenarios.javetFieldAccess();
            case "java_string_builder": return JavaInteropScenarios.javetStringBuilder();
            case "java_collection_sort":return JavaInteropScenarios.javetCollectionSort();
            case "java_type_convert":   return JavaInteropScenarios.javetTypeConvert();
            case "java_hashmap_ops":    return JavaInteropScenarios.javetHashMapOps();
            case "java_string_methods": return JavaInteropScenarios.javetStringMethods();
            case "java_exception_handle":return JavaInteropScenarios.javetExceptionHandle();
            case "java_mixed_compute":  return JavaInteropScenarios.javetMixedCompute();
            default: throw new IllegalArgumentException("No javet source for: " + scenario);
        }
    }
}
