package com.novalang.bench;

import javax.script.CompiledScript;

import nova.runtime.CompiledNova;
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

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            scriptScenario = ScriptScenarios.byName(scenario);
            expectedResult = scriptScenario.runJavaNative();

            int novaEval = ScriptBenchSupport.toInt(ScriptBenchSupport.evalNova(scriptScenario.getNovaSource()));
            if (novaEval != expectedResult) {
                throw new IllegalStateException("Nova eval result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + novaEval);
            }

            int nashornEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.newNashornEngine().eval(scriptScenario.getJsSource()));
            if (nashornEval != expectedResult) {
                throw new IllegalStateException("Nashorn eval result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + nashornEval);
            }

            novaCompiled = ScriptBenchSupport.compileNova(scriptScenario.getNovaSource());
            int novaCompiledResult = ScriptBenchSupport.toInt(novaCompiled.run());
            if (novaCompiledResult != expectedResult) {
                throw new IllegalStateException("Nova compiled result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + novaCompiledResult);
            }

            nashornCompiled = ScriptBenchSupport.compileNashorn(scriptScenario.getJsSource());
            int nashornCompiledResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalCompiledScript(nashornCompiled));
            if (nashornCompiledResult != expectedResult) {
                throw new IllegalStateException("Nashorn compiled result mismatch for " + scenario + ": expected="
                        + expectedResult + ", actual=" + nashornCompiledResult);
            }
        }
    }

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

    @Benchmark
    public int javaNative(ScenarioState state) {
        return state.scriptScenario.runJavaNative();
    }
}
