package com.novalang.bench;

import javax.script.CompiledScript;

import groovy.lang.Script;
import nova.runtime.CompiledNova;
import org.apache.commons.jexl3.JexlScript;
import org.graalvm.polyglot.Context;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Threads(1)
public class ScriptEngineComparisonJmhBenchmark {

    @State(Scope.Benchmark)
    public static class ScenarioState {
        @Param({"arith_loop", "call_loop", "object_loop", "branch_loop", "string_concat", "list_sum", "fib_recursion"})
        public String scenario;

        ScriptScenario scriptScenario;
        int expectedResult;
        CompiledNova novaCompiled;
        CompiledScript nashornCompiled;
        Context graalJsContext;
        Script groovyScript;
        JexlScript jexlScript;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            scriptScenario = ScriptScenarios.byName(scenario);
            expectedResult = scriptScenario.runJavaNative();

            // Nova eval 验证
            int novaEval = ScriptBenchSupport.toInt(ScriptBenchSupport.evalNova(scriptScenario.getNovaSource()));
            if (novaEval != expectedResult) {
                throw new IllegalStateException("Nova eval result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + novaEval);
            }

            // Nashorn eval 验证
            int nashornEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.newNashornEngine().eval(scriptScenario.getJsSource()));
            if (nashornEval != expectedResult) {
                throw new IllegalStateException("Nashorn eval result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + nashornEval);
            }

            // Nova 编译 + 验证
            novaCompiled = ScriptBenchSupport.compileNova(scriptScenario.getNovaSource());
            int novaCompiledResult = ScriptBenchSupport.toInt(novaCompiled.run());
            if (novaCompiledResult != expectedResult) {
                throw new IllegalStateException("Nova compiled result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + novaCompiledResult);
            }

            // Nashorn 编译 + 验证
            nashornCompiled = ScriptBenchSupport.compileNashorn(scriptScenario.getJsSource());
            int nashornCompiledResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalCompiledScript(nashornCompiled));
            if (nashornCompiledResult != expectedResult) {
                throw new IllegalStateException("Nashorn compiled result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + nashornCompiledResult);
            }

            // GraalJS 预热 Context + 验证
            graalJsContext = ScriptBenchSupport.newGraalJsContext();
            int graalResult = graalJsContext.eval("js", scriptScenario.getJsSource()).asInt();
            if (graalResult != expectedResult) {
                throw new IllegalStateException("GraalJS result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + graalResult);
            }

            // Groovy 解析 + 验证
            groovyScript = ScriptBenchSupport.parseGroovy(scriptScenario.getGroovySource());
            int groovyParsedResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.runGroovyScript(groovyScript));
            if (groovyParsedResult != expectedResult) {
                throw new IllegalStateException("Groovy parsed result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + groovyParsedResult);
            }

            // JEXL 脚本编译 + 验证
            jexlScript = ScriptBenchSupport.createJexlScript(scriptScenario.getJexlSource());
            int jexlResult = ScriptBenchSupport.toInt(ScriptBenchSupport.evalJexlScript(jexlScript));
            if (jexlResult != expectedResult) {
                throw new IllegalStateException("JEXL result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + jexlResult);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() {
            if (graalJsContext != null) {
                graalJsContext.close();
                graalJsContext = null;
            }
        }
    }

    // ---- Nova ----

    @Benchmark
    public Object novaEval(ScenarioState state) {
        return ScriptBenchSupport.evalNova(state.scriptScenario.getNovaSource());
    }

    @Benchmark
    public Object novaCompileOnly(ScenarioState state) {
        return ScriptBenchSupport.compileNova(state.scriptScenario.getNovaSource());
    }

    @Benchmark
    public Object novaCompiledRun(ScenarioState state) {
        return state.novaCompiled.run();
    }

    // ---- Nashorn ----

    @Benchmark
    public Object nashornEval(ScenarioState state) throws Exception {
        return ScriptBenchSupport.newNashornEngine().eval(state.scriptScenario.getJsSource());
    }

    @Benchmark
    public Object nashornCompileOnly(ScenarioState state) throws Exception {
        return ScriptBenchSupport.compileNashorn(state.scriptScenario.getJsSource());
    }

    @Benchmark
    public Object nashornCompiledRun(ScenarioState state) throws Exception {
        return ScriptBenchSupport.evalCompiledScript(state.nashornCompiled);
    }

    // ---- GraalJS ----

    @Benchmark
    public int graalJsEval(ScenarioState state) {
        return ScriptBenchSupport.evalGraalJs(state.scriptScenario.getJsSource());
    }

    @Benchmark
    public int graalJsWarmed(ScenarioState state) {
        return state.graalJsContext.eval("js", state.scriptScenario.getJsSource()).asInt();
    }

    // ---- Groovy ----

    @Benchmark
    public Object groovyEval(ScenarioState state) {
        return ScriptBenchSupport.evalGroovy(state.scriptScenario.getGroovySource());
    }

    @Benchmark
    public Object groovyParsedRun(ScenarioState state) {
        return ScriptBenchSupport.runGroovyScript(state.groovyScript);
    }

    // ---- JEXL ----

    @Benchmark
    public Object jexlEval(ScenarioState state) {
        return ScriptBenchSupport.evalJexl(state.scriptScenario.getJexlSource());
    }

    @Benchmark
    public Object jexlScriptRun(ScenarioState state) {
        return ScriptBenchSupport.evalJexlScript(state.jexlScript);
    }

    // ---- Java baseline ----

    @Benchmark
    public int javaNative(ScenarioState state) {
        return state.scriptScenario.runJavaNative();
    }
}
