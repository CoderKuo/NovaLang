package com.novalang.runtime.interpreter;

import com.novalang.ir.mir.BasicBlock;
import com.novalang.ir.mir.MirClass;
import com.novalang.ir.mir.MirFunction;
import com.novalang.ir.mir.MirInst;
import com.novalang.ir.mir.MirModule;
import com.novalang.ir.mir.MirOp;
import com.novalang.ir.mir.MirTerminator;
import com.novalang.ir.mir.MirLocal;
import com.novalang.runtime.NovaValue;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"-Xms512m", "-Xmx512m"})
@Threads(1)
public class InterpreterJmhBenchmark {

    @State(Scope.Benchmark)
    public static class ScenarioState {
        @Param({
                "arith_loop",
                "call_loop",
                "object_loop",
                "collection_hof",
                "lambda_closure",
                "mixed_stress",
                "string_concat",
                "recursion_plain",
                "recursion_memoized",
                "tail_recursion",
                "tail_recursion3",
                "operator_overload"
        })
        public String scenario;

        String source;
        String expected;
        MirModule mir;
        Interpreter reusableExecInterpreter;
        Interpreter.PreparedMirModule preparedMir;
        Path mirDumpPath;

        @Setup(Level.Trial)
        public void setUp() throws Exception {
            source = sourceFor(scenario);

            Interpreter evalInterpreter = new Interpreter();
            expected = render(evalInterpreter.eval(source));

            Interpreter compileInterpreter = new Interpreter();
            mir = compileInterpreter.precompileToMir(source);

            reusableExecInterpreter = new Interpreter();
            preparedMir = reusableExecInterpreter.prepareMirForReuse(mir);
            clearMemoCaches(mir);
            String actual = render(reusableExecInterpreter.executePreparedMir(preparedMir));
            if (!expected.equals(actual)) {
                throw new IllegalStateException("MIR execute result mismatch for " + scenario
                        + ": expected=" + expected + ", actual=" + actual);
            }

            mirDumpPath = dumpMir(scenario, source, mir);
        }

        @Setup(Level.Invocation)
        public void beforeInvocation() {
            clearMemoCaches(mir);
        }
    }

    @Benchmark
    public Object evalFull(ScenarioState state) {
        return new Interpreter().eval(state.source);
    }

    @Benchmark
    public Object compileToMirOnly(ScenarioState state) {
        return new Interpreter().precompileToMir(state.source);
    }

    @Benchmark
    public Object executeMirOnly(ScenarioState state) {
        return state.reusableExecInterpreter.executePreparedMir(state.preparedMir);
    }

    private static String sourceFor(String scenario) {
        switch (scenario) {
            case "arith_loop":
                return "var s = 0\n"
                        + "for (i in 0..<200000) {\n"
                        + "  s = s + i * 2 - 1\n"
                        + "}\n"
                        + "s";
            case "call_loop":
                return "fun noop(n: Int): Int = n + 1\n"
                        + "var s = 0\n"
                        + "for (i in 0..<100000) {\n"
                        + "  s = s + noop(i)\n"
                        + "}\n"
                        + "s";
            case "object_loop":
                return "class Pt(var x: Int, var y: Int) {\n"
                        + "  fun sum() = x + y\n"
                        + "}\n"
                        + "var total = 0\n"
                        + "for (i in 0..<5000) {\n"
                        + "  val p = Pt(i, i * 2)\n"
                        + "  total = total + p.sum()\n"
                        + "}\n"
                        + "total";
            case "collection_hof":
                return "val list = mutableListOf()\n"
                        + "for (i in 0..<1000) {\n"
                        + "  list.add(i)\n"
                        + "}\n"
                        + "val evens = list.filter { n -> n % 2 == 0 }\n"
                        + "val doubled = evens.map { n -> n * 2 }\n"
                        + "var sum = 0\n"
                        + "for (n in doubled) {\n"
                        + "  sum = sum + n\n"
                        + "}\n"
                        + "sum";
            case "lambda_closure":
                return "var total = 0\n"
                        + "for (i in 0..<5000) {\n"
                        + "  val adder = { x -> x + i }\n"
                        + "  total = total + adder(1)\n"
                        + "}\n"
                        + "total";
            case "mixed_stress":
                return "class Item(val name: String, val price: Int) {\n"
                        + "  fun discount(pct: Int): Int = price - price * pct / 100\n"
                        + "}\n"
                        + "var items = []\n"
                        + "for (i in 0..<2000) {\n"
                        + "  items.add(Item(\"item_\" + i, i * 3 + 10))\n"
                        + "}\n"
                        + "val expensive = items.filter { item -> item.price > 500 }\n"
                        + "var sum = 0\n"
                        + "for (item in expensive) {\n"
                        + "  sum = sum + item.discount(10)\n"
                        + "}\n"
                        + "var nameLen = 0\n"
                        + "for (i in 0..<500) {\n"
                        + "  nameLen = nameLen + items[i].name.length()\n"
                        + "}\n"
                        + "sum + nameLen";
            case "string_concat":
                return "fun run(): Int {\n"
                        + "  var s = \"\"\n"
                        + "  for (i in 0..<3000) {\n"
                        + "    s = s + \"ab\" + i\n"
                        + "  }\n"
                        + "  return s.length()\n"
                        + "}\n"
                        + "run()";
            case "recursion_plain":
                return "fun fib(n: Int): Int {\n"
                        + "  if (n <= 1) return n\n"
                        + "  return fib(n - 1) + fib(n - 2)\n"
                        + "}\n"
                        + "var sum = 0\n"
                        + "for (i in 0..<100) {\n"
                        + "  val n = 18 + (i % 5)\n"
                        + "  sum = sum + fib(n)\n"
                        + "}\n"
                        + "sum";
            case "recursion_memoized":
                return "annotation class memoized\n"
                        + "@memoized fun fib(n: Int): Int {\n"
                        + "  if (n <= 1) return n\n"
                        + "  return fib(n - 1) + fib(n - 2)\n"
                        + "}\n"
                        + "var sum = 0\n"
                        + "for (i in 0..<100) {\n"
                        + "  val n = 18 + (i % 5)\n"
                        + "  sum = sum + fib(n)\n"
                        + "}\n"
                        + "sum";
            case "tail_recursion":
                return "fun sumDown(n: Int, acc: Int): Int = "
                        + "  if (n == 0) acc else sumDown(n - 1, acc + n)\n"
                        + "var sum = 0\n"
                        + "for (i in 0..<100) {\n"
                        + "  val n = 4000 + (i % 5) * 250\n"
                        + "  sum = sum + sumDown(n, 0)\n"
                        + "}\n"
                        + "sum";
            case "tail_recursion3":
                return "fun fibTail(n: Int, a: Int, b: Int): Int = "
                        + "  if (n == 0) a else fibTail(n - 1, b, a + b)\n"
                        + "var sum = 0\n"
                        + "for (i in 0..<100) {\n"
                        + "  val n = 4000 + (i % 5) * 250\n"
                        + "  sum = sum + fibTail(n, 0, 1)\n"
                        + "}\n"
                        + "sum";
            case "operator_overload":
                return "class Vec2(val x: Int, val y: Int) {\n"
                        + "  fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)\n"
                        + "}\n"
                        + "var v = Vec2(0, 0)\n"
                        + "for (i in 1..5000) {\n"
                        + "  v = v + Vec2(i, i)\n"
                        + "}\n"
                        + "v.x + v.y";
            default:
                throw new IllegalArgumentException("Unknown scenario: " + scenario);
        }
    }

    private static String render(NovaValue value) {
        return value == null ? "<null>" : String.valueOf(value);
    }

    private static void clearMemoCaches(MirModule mir) {
        for (MirFunction fn : mir.getTopLevelFunctions()) {
            fn.clearMemoCaches();
        }
        for (MirClass cls : mir.getClasses()) {
            for (MirFunction fn : cls.getMethods()) {
                fn.clearMemoCaches();
            }
        }
    }

    private static Path dumpMir(String scenario, String source, MirModule mir) throws IOException {
        Path dir = Paths.get("build", "reports", "jmh", "interpreter-mir");
        Files.createDirectories(dir);
        Path path = dir.resolve(scenario + ".mir.txt");
        StringBuilder out = new StringBuilder();
        out.append("SCENARIO: ").append(scenario).append('\n');
        out.append("===== SOURCE =====\n");
        out.append(source).append("\n\n");
        out.append("===== SUMMARY =====\n");
        appendModuleSummary(out, mir);
        out.append("\n===== MIR =====\n");
        appendMir(out, mir);
        Files.write(path, out.toString().getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private static void appendModuleSummary(StringBuilder out, MirModule mir) {
        int functionCount = 0;
        int blockCount = 0;
        int instCount = 0;
        Map<MirOp, Integer> opCounts = new EnumMap<>(MirOp.class);

        for (MirClass cls : mir.getClasses()) {
            for (MirFunction fn : cls.getMethods()) {
                functionCount++;
                blockCount += fn.getBlocks().size();
                for (BasicBlock block : fn.getBlocks()) {
                    instCount += block.getInstructions().size();
                    for (MirInst inst : block.getInstructions()) {
                        increment(opCounts, inst.getOp());
                    }
                }
            }
        }
        for (MirFunction fn : mir.getTopLevelFunctions()) {
            functionCount++;
            blockCount += fn.getBlocks().size();
            for (BasicBlock block : fn.getBlocks()) {
                instCount += block.getInstructions().size();
                for (MirInst inst : block.getInstructions()) {
                    increment(opCounts, inst.getOp());
                }
            }
        }

        out.append("functions=").append(functionCount)
                .append(", blocks=").append(blockCount)
                .append(", instructions=").append(instCount)
                .append('\n');
        TreeMap<String, Integer> byName = new TreeMap<>();
        for (Map.Entry<MirOp, Integer> entry : opCounts.entrySet()) {
            byName.put(entry.getKey().name(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : byName.entrySet()) {
            out.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
    }

    private static void appendMir(StringBuilder out, MirModule mir) {
        for (MirClass cls : mir.getClasses()) {
            out.append("class ").append(cls.getName()).append(" {\n");
            for (MirFunction fn : cls.getMethods()) {
                appendFunction(out, fn);
            }
            out.append("}\n");
        }
        for (MirFunction fn : mir.getTopLevelFunctions()) {
            appendFunction(out, fn);
        }
    }

    private static void appendFunction(StringBuilder out, MirFunction fn) {
        out.append("fun ").append(fn.getName()).append(" -> ").append(fn.getReturnType()).append('\n');
        out.append("  locals:\n");
        for (MirLocal local : fn.getLocals()) {
            out.append("    %").append(local.getIndex())
                    .append(' ').append(local.getName())
                    .append(" : ").append(local.getType())
                    .append('\n');
        }
        for (BasicBlock block : fn.getBlocks()) {
            out.append("  B").append(block.getId()).append(':').append('\n');
            for (MirInst inst : block.getInstructions()) {
                out.append("    ").append(inst).append('\n');
            }
            MirTerminator terminator = block.getTerminator();
            if (terminator != null) {
                out.append("    ").append(terminator).append('\n');
            }
        }
        out.append('\n');
    }

    private static void increment(Map<MirOp, Integer> counts, MirOp op) {
        Integer value = counts.get(op);
        counts.put(op, value == null ? 1 : value + 1);
    }
}
