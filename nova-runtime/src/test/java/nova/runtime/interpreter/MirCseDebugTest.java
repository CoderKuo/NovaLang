package nova.runtime.interpreter;

import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.ir.mir.*;
import com.novalang.ir.pass.PassPipeline;
import com.novalang.ir.hir.decl.HirModule;
import com.novalang.ir.lowering.AstToHirLowering;
import com.novalang.ir.lowering.HirToMirLowering;
import com.novalang.ir.pass.HirPass;
import com.novalang.ir.pass.MirPass;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MirCseDebugTest {

    @Test
    void dumpBubbleSortMir() throws Exception {
        Interpreter interp = new Interpreter();

        String novaCode =
            "val arr = Array<Int>(300) { i -> 300 - i }\n" +
            "val n = arr.size\n" +
            "var i = 0\n" +
            "while (i < n) {\n" +
            "  var j = 0\n" +
            "  val limit = n - 1 - i\n" +
            "  while (j < limit) {\n" +
            "    if (arr[j] > arr[j + 1]) {\n" +
            "      val temp = arr[j]\n" +
            "      arr[j] = arr[j + 1]\n" +
            "      arr[j + 1] = temp\n" +
            "    }\n" +
            "    j = j + 1\n" +
            "  }\n" +
            "  i = i + 1\n" +
            "}\n" +
            "arr[0] * 10000 + arr[299]";

        MirModule mir = interp.precompileToMir(novaCode);

        File outFile = new File(System.getProperty("java.io.tmpdir"), "mir_cse_debug.txt");
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            for (MirFunction func : mir.getTopLevelFunctions()) {
                out.println("=== fun " + func.getName() + " ===");
                int totalInsts = 0;
                for (BasicBlock block : func.getBlocks()) {
                    out.println("  B" + block.getId() + ":");
                    for (MirInst inst : block.getInstructions()) {
                        out.println("    " + inst);
                        totalInsts++;
                    }
                    MirTerminator term = block.getTerminator();
                    if (term != null) {
                        out.println("    -> " + term);
                    }
                }
                out.println("  总指令数: " + totalInsts);
            }
        }
        System.out.println("MIR dump written to: " + outFile.getAbsolutePath());
    }

    @Test
    void dumpTailFibMir() throws Exception {
        Interpreter interp = new Interpreter();

        String novaCode =
            "fun fib(n: Int, a: Int = 0, b: Int = 1): Int = " +
            "  if (n == 0) a else fib(n - 1, b, a + b)\n" +
            "var sum = 0\n" +
            "for (i in 0..<100) {\n" +
            "  sum = sum + fib(20)\n" +
            "}\n" +
            "sum";

        MirModule mir = interp.precompileToMir(novaCode);

        File outFile = new File(System.getProperty("java.io.tmpdir"), "mir_tailfib_debug.txt");
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(outFile), StandardCharsets.UTF_8))) {
            for (MirClass cls : mir.getClasses()) {
                for (MirFunction func : cls.getMethods()) {
                    out.println("=== " + cls.getName() + "." + func.getName() + " ===");
                    dumpFunction(out, func);
                }
            }
            for (MirFunction func : mir.getTopLevelFunctions()) {
                out.println("=== fun " + func.getName() + " ===");
                dumpFunction(out, func);
            }
        }
        System.out.println("MIR dump written to: " + outFile.getAbsolutePath());
    }

    @Test
    void profileCompilePipeline() {
        String fibCode =
            "fun fib(n: Int): Int {\n" +
            "  var a = 0\n  var b = 1\n" +
            "  for (i in 0..<n) { val temp = b\n    b = a + b\n    a = temp }\n" +
            "  return a\n}\n" +
            "var sum = 0\nfor (iter in 0..<1000) { sum = sum + fib(25) }\nsum";

        String collectionCode =
            "val list = List(1000) { i -> i * 2 + 1 }\n" +
            "val result = list.filter { it % 3 == 0 }.map { it * it }.sum()\n" +
            "result";

        // warmup
        for (int i = 0; i < 3; i++) {
            new Interpreter().eval(fibCode);
            new Interpreter().eval(collectionCode);
        }

        System.out.println("=== Fibonacci 迭代 ===");
        profileCode(fibCode);
        System.out.println("\n=== 集合高阶函数 ===");
        profileCode(collectionCode);
    }

    private void profileCode(String code) {
        int runs = 20;
        long[] tLex = new long[runs], tHir = new long[runs], tHirOpt = new long[runs];
        long[] tMir = new long[runs], tTotal = new long[runs];

        // 获取 Interpreter 使用的 pipeline 配置
        Interpreter sampleInterp = new Interpreter();
        PassPipeline samplePipeline = sampleInterp.getMirPipeline();
        List<MirPass> mirPasses = samplePipeline.getMirPasses();
        int numMirPasses = mirPasses.size();
        // 每个 MIR pass 的耗时数组
        long[][] tMirPass = new long[numMirPasses][runs];

        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            Lexer lexer = new Lexer(code, "<bench>");
            Parser parser = new Parser(lexer, "<bench>");
            Program program = parser.parse();
            long t1 = System.nanoTime();

            AstToHirLowering astLowering = new AstToHirLowering();
            astLowering.setScriptMode(true);
            HirModule hir = astLowering.lower(program);
            long t2 = System.nanoTime();

            for (HirPass pass : samplePipeline.getHirPasses()) {
                hir = pass.run(hir);
            }
            long t3 = System.nanoTime();

            HirToMirLowering lowering = new HirToMirLowering();
            lowering.setScriptMode(true);
            lowering.setInterpreterMode(true);
            MirModule mir = lowering.lower(hir);
            long t4 = System.nanoTime();

            for (int p = 0; p < numMirPasses; p++) {
                long pt0 = System.nanoTime();
                mir = mirPasses.get(p).run(mir);
                tMirPass[p][i] = System.nanoTime() - pt0;
            }
            long t5 = System.nanoTime();

            tLex[i] = t1 - t0;
            tHir[i] = t2 - t1;
            tHirOpt[i] = t3 - t2;
            tMir[i] = t4 - t3;
            tTotal[i] = t5 - t0;
        }

        System.out.printf("  Lex+Parse:    %6.3f ms%n", median(tLex) / 1e6);
        System.out.printf("  AST→HIR:      %6.3f ms%n", median(tHir) / 1e6);
        System.out.printf("  HIR passes:   %6.3f ms%n", median(tHirOpt) / 1e6);
        System.out.printf("  HIR→MIR:      %6.3f ms%n", median(tMir) / 1e6);
        System.out.println("  MIR passes (detail):");
        double mirTotal = 0;
        for (int p = 0; p < numMirPasses; p++) {
            double ms = median(tMirPass[p]) / 1e6;
            mirTotal += ms;
            System.out.printf("    %-24s %6.3f ms%n", mirPasses.get(p).getName(), ms);
        }
        System.out.printf("  MIR passes:   %6.3f ms%n", mirTotal);
        System.out.printf("  Total:        %6.3f ms%n", median(tTotal) / 1e6);
    }

    private double median(long[] arr) {
        long[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private void dumpFunction(PrintWriter out, MirFunction func) {
        out.println("  locals: " + func.getLocals());
        int totalInsts = 0;
        for (BasicBlock block : func.getBlocks()) {
            out.println("  B" + block.getId() + ":");
            for (MirInst inst : block.getInstructions()) {
                out.println("    " + inst);
                totalInsts++;
            }
            MirTerminator term = block.getTerminator();
            if (term != null) {
                out.println("    -> " + term);
            }
        }
        out.println("  总指令数: " + totalInsts);
        out.println();
    }
}
