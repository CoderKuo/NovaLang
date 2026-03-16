package com.novalang.bench;

import javax.script.CompiledScript;

import groovy.lang.Script;
import com.novalang.runtime.CompiledNova;
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
public class JavaInteropJmhBenchmark {

    @State(Scope.Benchmark)
    public static class ScenarioState {
        @Param({"java_static_call", "java_object_create", "java_field_access",
                "java_string_builder", "java_collection_sort", "java_type_convert",
                "java_hashmap_ops", "java_string_methods", "java_exception_handle", "java_mixed_compute"})
        public String scenario;

        ScriptScenario scriptScenario;
        int expectedResult;
        CompiledNova novaCompiled;
        CompiledScript nashornCompiled;
        Context graalJsContext;
        Script groovyScript;
        JexlScript jexlScript;
        com.caoccao.javet.interop.V8Runtime javetRuntime;
        String javetSource;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            scriptScenario = JavaInteropScenarios.byName(scenario);
            expectedResult = scriptScenario.runJavaNative();

            // Nova eval 验证
            int novaEval = ScriptBenchSupport.toInt(ScriptBenchSupport.evalNova(scriptScenario.getNovaSource()));
            if (novaEval != expectedResult) {
                throw new IllegalStateException("Nova eval mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + novaEval);
            }

            // Nova 编译 + 验证
            novaCompiled = ScriptBenchSupport.compileNova(scriptScenario.getNovaSource());
            int novaCompiledResult = ScriptBenchSupport.toInt(novaCompiled.run());
            if (novaCompiledResult != expectedResult) {
                throw new IllegalStateException("Nova compiled mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + novaCompiledResult);
            }

            // Nashorn eval 验证
            int nashornEval = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.newNashornEngine().eval(scriptScenario.getJsSource()));
            if (nashornEval != expectedResult) {
                throw new IllegalStateException("Nashorn eval mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + nashornEval);
            }

            // Nashorn 编译 + 验证
            nashornCompiled = ScriptBenchSupport.compileNashorn(scriptScenario.getJsSource());
            int nashornCompiledResult = ScriptBenchSupport.toInt(
                    ScriptBenchSupport.evalCompiledScript(nashornCompiled));
            if (nashornCompiledResult != expectedResult) {
                throw new IllegalStateException("Nashorn compiled mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + nashornCompiledResult);
            }

            // GraalJS 预热 + 验证（需启用 Java 互操作）
            graalJsContext = ScriptBenchSupport.newGraalJsInteropContext();
            int graalResult = graalJsContext.eval("js", scriptScenario.getJsSource()).asInt();
            if (graalResult != expectedResult) {
                throw new IllegalStateException("GraalJS mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + graalResult);
            }

            // Groovy 解析 + 验证
            groovyScript = ScriptBenchSupport.parseGroovy(scriptScenario.getGroovySource());
            int groovyResult = ScriptBenchSupport.toInt(ScriptBenchSupport.runGroovyScript(groovyScript));
            if (groovyResult != expectedResult) {
                throw new IllegalStateException("Groovy mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + groovyResult);
            }

            // JEXL 编译 + 验证
            jexlScript = ScriptBenchSupport.createJexlScript(scriptScenario.getJexlSource());
            int jexlResult = ScriptBenchSupport.toInt(ScriptBenchSupport.evalJexlScript(jexlScript));
            if (jexlResult != expectedResult) {
                throw new IllegalStateException("JEXL mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + jexlResult);
            }

            // Javet 预热 + 验证（通过 proxy 注入 Java 类）
            javetSource = resolveJavetSource(scenario);
            javetRuntime = ScriptBenchSupport.newJavetInteropRuntime();
            int javetResult = ScriptBenchSupport.evalJavetWarmed(javetRuntime, javetSource);
            if (javetResult != expectedResult) {
                throw new IllegalStateException("Javet mismatch for " + scenario
                        + ": expected=" + expectedResult + ", actual=" + javetResult);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() throws Exception {
            if (graalJsContext != null) {
                graalJsContext.close();
                graalJsContext = null;
            }
            if (javetRuntime != null) {
                javetRuntime.close();
                javetRuntime = null;
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

    // ---- Nova ----

    @Benchmark
    public Object novaEval(ScenarioState state) {
        return ScriptBenchSupport.evalNova(state.scriptScenario.getNovaSource());
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
    public Object nashornCompiledRun(ScenarioState state) throws Exception {
        return ScriptBenchSupport.evalCompiledScript(state.nashornCompiled);
    }

    // ---- GraalJS ----

    @Benchmark
    public int graalJsEval(ScenarioState state) {
        return ScriptBenchSupport.evalGraalJsInterop(state.scriptScenario.getJsSource());
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

    // ---- Javet (V8 + proxy interop) ----

    @Benchmark
    public int javetEval(ScenarioState state) {
        return ScriptBenchSupport.evalJavetInterop(state.javetSource);
    }

    @Benchmark
    public int javetWarmed(ScenarioState state) {
        return ScriptBenchSupport.evalJavetWarmed(state.javetRuntime, state.javetSource);
    }

    // ---- Java baseline ----

    @Benchmark
    public int javaNative(ScenarioState state) {
        return state.scriptScenario.runJavaNative();
    }
}
