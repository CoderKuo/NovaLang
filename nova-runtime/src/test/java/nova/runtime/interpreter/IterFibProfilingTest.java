package nova.runtime.interpreter;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.FileWriter;

/**
 * 迭代 fib(25) x 1000 性能剖析测试。
 * 结果写入 nova-runtime/build/bench-output.txt
 */
class IterFibProfilingTest {

    private static final int WARMUP = 20;
    private static final int RUNS = 30;

    @Test
    void microBenchmarks() throws Exception {
        PrintWriter out = new PrintWriter(new FileWriter("build/bench-output.txt"));
        out.println("=== 迭代 fib 瓶颈分析 v3 ===");
        out.println();

        // 1. 完整迭代 fib(25) x 1000
        bench(out, "1. fib(25)x1000 完整",
            "fun fib(n: Int): Int {\n" +
            "  var a = 0\n" +
            "  var b = 1\n" +
            "  for (i in 0..<n) {\n" +
            "    val temp = b\n" +
            "    b = a + b\n" +
            "    a = temp\n" +
            "  }\n" +
            "  return a\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (iter in 0..<1000) {\n" +
            "  sum = sum + fib(25)\n" +
            "}\n" +
            "sum");

        // 2. 空for循环 x25K — 循环基础开销
        bench(out, "2. 空for x25K (s=s+1)",
            "var s = 0\n" +
            "for (i in 0..<25000) { s = s + 1 }\n" +
            "s");

        // 3. for + var 赋值 + 加法 x25K
        bench(out, "3. for+2var赋值+加法 x25K",
            "var a = 0\n" +
            "var b = 1\n" +
            "for (i in 0..<25000) {\n" +
            "  val temp = b\n" +
            "  b = a + b\n" +
            "  a = temp\n" +
            "}\n" +
            "a");

        // 4. for + 纯读取 x25K
        bench(out, "4. for+读变量 x25K",
            "var a = 1\n" +
            "var s = 0\n" +
            "for (i in 0..<25000) { s = s + a }\n" +
            "s");

        // 5. for + var赋值外层 x25K
        bench(out, "5. for+写外层var x25K",
            "var a = 0\n" +
            "for (i in 0..<25000) { a = i }\n" +
            "a");

        // 6. for + val定义 x25K
        bench(out, "6. for+val定义 x25K",
            "var s = 0\n" +
            "for (i in 0..<25000) {\n" +
            "  val x = i\n" +
            "  s = s + x\n" +
            "}\n" +
            "s");

        // 7. 纯加法 x25K（无变量写入，只读 i）
        bench(out, "7. 纯加法 x25K",
            "var s = 0\n" +
            "for (i in 0..<25000) { s = s + i }\n" +
            "s");

        // 8. 函数调用+内部for x1000
        bench(out, "8. 函数(for25) x1000",
            "fun work(): Int {\n" +
            "  var a = 0\n" +
            "  var b = 1\n" +
            "  for (i in 0..<25) {\n" +
            "    val temp = b\n" +
            "    b = a + b\n" +
            "    a = temp\n" +
            "  }\n" +
            "  return a\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (iter in 0..<1000) {\n" +
            "  sum = sum + work()\n" +
            "}\n" +
            "sum");

        // 9. Java 原生
        for (int i = 0; i < WARMUP; i++) javaIterFib25x1000();
        System.gc();
        long[] javaTimes = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            javaIterFib25x1000();
            javaTimes[i] = System.nanoTime() - start;
        }
        out.printf("%-42s %8.4f ms%n", "9. Java iterFib(25)x1000", median(javaTimes) / 1_000_000.0);

        out.println();
        // 计算开销分解
        out.println("--- 开销分解 ---");

        out.flush();
        out.close();
    }

    private void bench(PrintWriter out, String label, String code) {
        for (int i = 0; i < WARMUP; i++) {
            new Interpreter().eval(code);
        }
        System.gc();
        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            Interpreter interp = new Interpreter();
            long start = System.nanoTime();
            interp.eval(code);
            times[i] = System.nanoTime() - start;
        }
        double ms = median(times) / 1_000_000.0;
        double nsPerIter = median(times) / 25_000.0;
        out.printf("%-42s %8.2f ms  (%5.1f ns/iter)%n", label, ms, nsPerIter);
        out.flush();
    }

    private static long median(long[] arr) {
        long[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static int javaIterFib(int n) {
        int a = 0, b = 1;
        for (int i = 0; i < n; i++) {
            int temp = b;
            b = a + b;
            a = temp;
        }
        return a;
    }

    private static int javaIterFib25x1000() {
        int sum = 0;
        for (int i = 0; i < 1000; i++) sum += javaIterFib(25);
        return sum;
    }
}
