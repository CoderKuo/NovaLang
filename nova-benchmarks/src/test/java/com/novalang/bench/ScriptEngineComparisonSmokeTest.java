package com.novalang.bench;

import javax.script.CompiledScript;

import groovy.lang.Script;
import nova.runtime.CompiledNova;
import org.apache.commons.jexl3.JexlScript;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptEngineComparisonSmokeTest {

    @Test
    void allScenariosMatchAcrossAllEngines() throws Exception {
        for (ScriptScenario scenario : ScriptScenarios.all().values()) {
            int expected = scenario.runJavaNative();

            // Nova eval
            int novaEval = ScriptBenchSupport.toInt(ScriptBenchSupport.evalNova(scenario.getNovaSource()));
            assertEquals(expected, novaEval, scenario.getName() + " nova eval");

            // Nashorn eval
            int nashornEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.newNashornEngine().eval(scenario.getJsSource()));
            assertEquals(expected, nashornEval, scenario.getName() + " nashorn eval");

            // Nova compiled
            CompiledNova novaCompiled = ScriptBenchSupport.compileNova(scenario.getNovaSource());
            int novaCompiledResult = ScriptBenchSupport.toInt(novaCompiled.run());
            assertEquals(expected, novaCompiledResult, scenario.getName() + " nova compiled");

            // Nashorn compiled
            CompiledScript nashornCompiled = ScriptBenchSupport.compileNashorn(scenario.getJsSource());
            int nashornCompiledResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalCompiledScript(nashornCompiled));
            assertEquals(expected, nashornCompiledResult, scenario.getName() + " nashorn compiled");

            // GraalJS
            try (Context ctx = ScriptBenchSupport.newGraalJsContext()) {
                int graalResult = ctx.eval("js", scenario.getJsSource()).asInt();
                assertEquals(expected, graalResult, scenario.getName() + " graaljs");
            }

            // Groovy eval
            int groovyEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalGroovy(scenario.getGroovySource()));
            assertEquals(expected, groovyEval, scenario.getName() + " groovy eval");

            // Groovy parsed script
            Script groovyScript = ScriptBenchSupport.parseGroovy(scenario.getGroovySource());
            int groovyScriptResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.runGroovyScript(groovyScript));
            assertEquals(expected, groovyScriptResult, scenario.getName() + " groovy parsed");

            // JEXL eval
            int jexlEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalJexl(scenario.getJexlSource()));
            assertEquals(expected, jexlEval, scenario.getName() + " jexl eval");

            // JEXL pre-compiled script
            JexlScript jexlScript = ScriptBenchSupport.createJexlScript(scenario.getJexlSource());
            int jexlScriptResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalJexlScript(jexlScript));
            assertEquals(expected, jexlScriptResult, scenario.getName() + " jexl script");
        }
    }
}
