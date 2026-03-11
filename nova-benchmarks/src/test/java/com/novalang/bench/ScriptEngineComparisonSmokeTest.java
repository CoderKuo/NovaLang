package com.novalang.bench;

import javax.script.CompiledScript;

import nova.runtime.CompiledNova;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptEngineComparisonSmokeTest {

    @Test
    void allScenariosMatchAcrossNovaNashornAndJava() throws Exception {
        for (ScriptScenario scenario : ScriptScenarios.all().values()) {
            int expected = scenario.runJavaNative();

            int novaEval = ScriptBenchSupport.toInt(ScriptBenchSupport.evalNova(scenario.getNovaSource()));
            int nashornEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.newNashornEngine().eval(scenario.getJsSource()));

            CompiledNova novaCompiled = ScriptBenchSupport.compileNova(scenario.getNovaSource());
            CompiledScript nashornCompiled = ScriptBenchSupport.compileNashorn(scenario.getJsSource());
            int novaCompiledResult = ScriptBenchSupport.toInt(novaCompiled.run());
            int nashornCompiledResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalCompiledScript(nashornCompiled));

            assertEquals(expected, novaEval, scenario.getName() + " nova eval");
            assertEquals(expected, nashornEval, scenario.getName() + " nashorn eval");
            assertEquals(expected, novaCompiledResult, scenario.getName() + " nova compiled");
            assertEquals(expected, nashornCompiledResult, scenario.getName() + " nashorn compiled");
        }
    }
}
