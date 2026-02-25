package nova.runtime.interpreter;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.FileWriter;

/**
 * fib(20) 性能剖析测试。
 * 隔离各子系统开销，定位解释器瓶颈。
 * 结果写入 nova-runtime/build/bench-output.txt
 */
class FibProfilingTest {

    private static final int WARMUP = 15;
    private static final int RUNS = 20;

    @Test
    @Disabled("太耗时 先关闭")
    void microBenchmarks() throws Exception {
        PrintWriter out = new PrintWriter(new FileWriter("build/bench-output.txt"));
        out.println("=== 微基准测试 ===");
        out.println();

        // 1. 纯函数调用（表达式体，无 ControlFlow）
        bench(out, "1. 纯函数调用 x100K",
            "fun noop(n: Int): Int = n\n" +
            "var s = 0\n" +
            "for (i in 0..<100000) { s = s + noop(1) }\n" +
            "s");

        // 2. 块体函数调用（有 ControlFlow throw）
        bench(out, "2. 块体函数调用 x100K",
            "fun noop(n: Int): Int { return n }\n" +
            "var s = 0\n" +
            "for (i in 0..<100000) { s = s + noop(1) }\n" +
            "s");

        // 3. fib — 块体（有 return，走 ControlFlow）
        bench(out, "3. fib(20)x100 块体",
            "fun fib(n: Int): Int {\n" +
            "  if (n <= 1) return n\n" +
            "  return fib(n - 1) + fib(n - 2)\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 0..<100) { sum = sum + fib(20) }\n" +
            "sum");

        // 4. fib — 表达式体（无 return，无 ControlFlow）
        bench(out, "4. fib(20)x100 表达式体",
            "fun fib(n: Int): Int = if (n <= 1) n else fib(n - 1) + fib(n - 2)\n" +
            "var sum = 0\n" +
            "for (i in 0..<100) { sum = sum + fib(20) }\n" +
            "sum");

        // 5. 浅递归 — 块体
        bench(out, "5. 浅递归(3)x100K 块体",
            "fun f(n: Int): Int {\n" +
            "  if (n <= 0) return 0\n" +
            "  return f(n - 1)\n" +
            "}\n" +
            "var s = 0\n" +
            "for (i in 0..<100000) { s = s + f(3) }\n" +
            "s");

        // 6. 浅递归 — 表达式体
        bench(out, "6. 浅递归(3)x100K 表达式体",
            "fun f(n: Int): Int = if (n <= 0) 0 else f(n - 1)\n" +
            "var s = 0\n" +
            "for (i in 0..<100000) { s = s + f(3) }\n" +
            "s");

        // 7. Int 算术
        bench(out, "7. Int算术 x100K",
            "var s = 0\n" +
            "for (i in 0..<100000) { s = s + i * 2 - 1 }\n" +
            "s");

        // 8. 空循环
        bench(out, "8. 空循环 x1M",
            "var s = 0\n" +
            "for (i in 0..<1000000) { s = s + 1 }\n" +
            "s");

        // 9. Java 原生
        for (int i = 0; i < WARMUP; i++) javaFib20x100();
        System.gc();
        long[] javaTimes = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            javaFib20x100();
            javaTimes[i] = System.nanoTime() - start;
        }
        out.printf("%-35s %8.4f ms%n", "9. Java fib(20)x100", median(javaTimes) / 1_000_000.0);

        out.println();
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
        out.printf("%-35s %8.2f ms%n", label, median(times) / 1_000_000.0);
        out.flush();
    }

    private static long median(long[] arr) {
        long[] sorted = arr.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static int javaFib(int n) {
        if (n <= 1) return n;
        return javaFib(n - 1) + javaFib(n - 2);
    }

    private static int javaFib20x100() {
        int sum = 0;
        for (int i = 0; i < 100; i++) sum += javaFib(20);
        return sum;
    }
}
