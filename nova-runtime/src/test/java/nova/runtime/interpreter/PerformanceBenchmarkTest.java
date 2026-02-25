package nova.runtime.interpreter;

import nova.runtime.*;
import com.novalang.ir.NovaIrCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NovaLang 性能基准测试
 *
 * <p>对比 Nova 解释执行 vs Java 原生实现，涵盖：</p>
 * <ul>
 *   <li>纯计算 (Fibonacci / 递归)</li>
 *   <li>条件密集 (质数筛 / when 表达式)</li>
 *   <li>数组操作 (冒泡排序)</li>
 *   <li>对象创建 (主构造器 / 次级构造器 / 链式委托)</li>
 *   <li>字符串操作 (拼接)</li>
 *   <li>集合高阶函数 (map / filter / forEach)</li>
 *   <li>Lambda / 闭包</li>
 *   <li>扩展函数</li>
 *   <li>运算符重载</li>
 *   <li>接口多态</li>
 *   <li>空安全操作符</li>
 *   <li>Range 迭代</li>
 *   <li>枚举操作</li>
 *   <li>Map 操作</li>
 * </ul>
 */
@DisplayName("性能基准测试")
class PerformanceBenchmarkTest {

    private static final int WARMUP = 30;
    private static final int RUNS = 30;

    // ============ 结果容器 ============

    static class BenchResult {
        final String name;
        final String desc;
        final double novaMs;      // 全流程（解析+lowering+执行）
        final double execOnlyMs;  // 纯执行（不含解析和lowering）
        final double compiledMs;
        final double javaMs;
        final double novaCv;      // Nova 全流程 CV%
        final double execOnlyCv;  // 纯执行 CV%

        BenchResult(String name, String desc, double novaMs, double execOnlyMs,
                    double compiledMs, double javaMs, double novaCv, double execOnlyCv) {
            this.name = name;
            this.desc = desc;
            this.novaMs = novaMs;
            this.execOnlyMs = execOnlyMs;
            this.compiledMs = compiledMs;
            this.javaMs = javaMs;
            this.novaCv = novaCv;
            this.execOnlyCv = execOnlyCv;
        }

        double interpRatio() {
            return javaMs > 0.001 ? novaMs / javaMs : -1;
        }

        double execOnlyRatio() {
            return javaMs > 0.001 ? execOnlyMs / javaMs : -1;
        }

        double compiledRatio() {
            return javaMs > 0.001 && compiledMs >= 0 ? compiledMs / javaMs : -1;
        }
    }

    // ============ 计时辅助 ============

    @FunctionalInterface
    interface JavaTask<T> { T run(); }

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    /** GC + 短暂等待，让 GC 线程有时间完成 */
    private static void gcQuiet() {
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    }

    /** 测量 Nova 全流程执行时间（解析+lowering+执行），返回 [截尾均值ms, 最终结果, CV%] */
    private Object[] measureNova(String code) {
        for (int i = 0; i < WARMUP; i++) {
            new Interpreter().eval(code);
        }
        gcQuiet();
        long[] times = new long[RUNS];
        NovaValue result = null;
        for (int i = 0; i < RUNS; i++) {
            Interpreter interp = new Interpreter();
            long start = System.nanoTime();
            result = interp.eval(code);
            times[i] = System.nanoTime() - start;
        }
        return new Object[]{trimmedMeanNanos(times) / 1_000_000.0, result, cvPercent(times)};
    }

    /** 测量 Nova MIR 纯执行时间（不含解析和 lowering），返回 [截尾均值ms, 最终结果, CV%] */
    private Object[] measureNovaExecOnly(String code) {
        // 预编译一次
        Interpreter compileInterp = new Interpreter();
        com.novalang.ir.mir.MirModule mir = compileInterp.precompileToMir(code);
        // warmup
        for (int i = 0; i < WARMUP; i++) {
            new Interpreter().executeMir(mir);
        }
        gcQuiet();
        // measure
        long[] times = new long[RUNS];
        NovaValue result = null;
        for (int i = 0; i < RUNS; i++) {
            Interpreter interp = new Interpreter();
            long start = System.nanoTime();
            result = interp.executeMir(mir);
            times[i] = System.nanoTime() - start;
        }
        return new Object[]{trimmedMeanNanos(times) / 1_000_000.0, result, cvPercent(times)};
    }

    /**
     * 测量编译模式执行时间 (ms)，返回 [截尾均值ms, 最终结果, CV%]。
     * compilableCode 将逻辑封装为 object 的方法（通过 INSTANCE 调用）。
     * 编译失败时返回 [-1, null, 0]。
     */
    private Object[] measureCompiled(String compilableCode, String className, String methodName) {
        if (compilableCode == null) return new Object[]{-1.0, null, 0.0};
        try {
            Map<String, Class<?>> loaded = compiler.compileAndLoad(compilableCode, "bench.nova");
            Class<?> clazz = loaded.get(className);
            if (clazz == null) return new Object[]{-1.0, null, 0.0};

            Object instance = null;
            try {
                java.lang.reflect.Field instanceField = clazz.getField("INSTANCE");
                instance = instanceField.get(null);
            } catch (NoSuchFieldException e) {
                // 没有 INSTANCE，尝试 static 调用
            }

            Method m = clazz.getDeclaredMethod(methodName);
            m.setAccessible(true);
            final Object target = instance;

            for (int i = 0; i < WARMUP; i++) {
                m.invoke(target);
            }
            gcQuiet();
            long[] times = new long[RUNS];
            Object result = null;
            for (int i = 0; i < RUNS; i++) {
                long start = System.nanoTime();
                result = m.invoke(target);
                times[i] = System.nanoTime() - start;
            }
            return new Object[]{trimmedMeanNanos(times) / 1_000_000.0, result, cvPercent(times)};
        } catch (Throwable e) {
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (e.getCause() != null) {
                msg += " <- " + e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
            }
            System.out.println("  [编译模式跳过: " + msg + "]");
            return new Object[]{-1.0, null, 0.0};
        }
    }

    /** 测量 Java 原生执行时间 (ms)，返回 [截尾均值ms, 最终结果, CV%] */
    private <T> Object[] measureJava(JavaTask<T> task) {
        for (int i = 0; i < WARMUP; i++) task.run();
        gcQuiet();
        long[] times = new long[RUNS];
        T result = null;
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            result = task.run();
            times[i] = System.nanoTime() - start;
        }
        return new Object[]{trimmedMeanNanos(times) / 1_000_000.0, result, cvPercent(times)};
    }

    /**
     * 截尾均值：排序后去掉最高/最低 20%，取中间 60% 的平均值。
     * 比中位数更稳定（利用更多数据点），比均值更抗异常值。
     */
    private static double trimmedMeanNanos(long[] times) {
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        int trim = n / 5;  // 20%
        long sum = 0;
        int count = n - 2 * trim;
        for (int i = trim; i < n - trim; i++) {
            sum += sorted[i];
        }
        return (double) sum / count;
    }

    /** 变异系数 (CV%)：标准差 / 均值 x 100，衡量结果稳定性 */
    private static double cvPercent(long[] times) {
        double mean = 0;
        for (long t : times) mean += t;
        mean /= times.length;
        if (mean == 0) return 0;
        double variance = 0;
        for (long t : times) variance += (t - mean) * (t - mean);
        variance /= times.length;
        return Math.sqrt(variance) / mean * 100.0;
    }

    // ============ 主测试入口 ============
//
//    @Test @DisplayName("01 Fibonacci 迭代") void bench01() { benchFibonacci(1); }
//    @Test @DisplayName("02 质数计数") void bench02() { benchPrimeCounting(2); }
//    @Test @DisplayName("03 冒泡排序") void bench03() { benchBubbleSort(3); }
//    @Test @DisplayName("04 主构造器") void bench04() { benchPrimaryConstructor(4); }
//    @Test @DisplayName("05 次级构造器") void bench05() { benchSecondaryConstructor(5); }
//    @Test @DisplayName("06 链式委托") void bench06() { benchChainedDelegation(6); }
//    @Test @DisplayName("07 字符串拼接") void bench07() { benchStringConcat(7); }
//    @Test @DisplayName("08 集合高阶函数") void bench08() { benchCollectionHigherOrder(8); }
//    @Test @DisplayName("09 Lambda 闭包") void bench09() { benchLambdaClosure(9); }
//    @Test @DisplayName("10 递归调用") void bench10() { benchRecursion(10); }
//    @Test @DisplayName("11 尾递归") void bench11() { benchTailRecursion(11); }
//    @Test @DisplayName("12 When 表达式") void bench12() { benchWhenExpression(12); }
//    @Test @DisplayName("13 扩展函数") void bench13() { benchExtensionFunctions(13); }
    @Test @DisplayName("14 运算符重载") void bench14() { benchOperatorOverloading(14); }
//    @Test @DisplayName("15 接口多态") void bench15() { benchInterfaceDispatch(15); }
//    @Test @DisplayName("16 空安全操作符") void bench16() { benchNullSafety(16); }
//    @Test @DisplayName("17 Range 迭代") void bench17() { benchRangeIteration(17); }
//    @Test @DisplayName("18 枚举操作") void bench18() { benchEnumOperations(18); }
//    @Test @DisplayName("19 Map 操作") void bench19() { benchMapOperations(19); }
//    @Test @DisplayName("20 高迭代循环") void bench20() { benchHighIterationLoop(20); }
//    @Test @DisplayName("21 深层嵌套作用域") void bench21() { benchDeepNestedScopes(21); }
//    @Test @DisplayName("22 闭包工厂") void bench22() { benchClosureFactory(22); }
//    @Test @DisplayName("23 数值装箱") void bench23() { benchNumericBoxing(23); }
//    @Test @DisplayName("24 字符串插值") void bench24() { benchStringInterpolation(24); }
//    @Test @DisplayName("25 深继承链分派") void bench25() { benchDeepInheritanceDispatch(25); }
//    @Test @DisplayName("26 Try-catch 热路径") void bench26() { benchTryCatchHotPath(26); }
//    @Test @DisplayName("27 大列表链式操作") void bench27() { benchLargeListChain(27); }
//    @Test @DisplayName("28 递归数据结构") void bench28() { benchRecursiveDataStructure(28); }
//    @Test @DisplayName("29 Map 密集读写") void bench29() { benchMapIntensiveReadWrite(29); }
//    @Test @DisplayName("30 综合混合压力") void bench30() { benchMixedStress(30); }

    @DisplayName("综合性能基准测试")
    @Test
    void runAllBenchmarks() {
        List<BenchResult> results = new ArrayList<BenchResult>();

        System.out.println();
        printLine('=', 100);
        System.out.println("  NovaLang 性能基准测试");
        System.out.println("  Nova 解释执行 vs 编译执行 vs Java 原生实现");
        System.out.println("  Warmup: " + WARMUP + " 轮, 测量: " + RUNS + " 轮取截尾均值");
        printLine('=', 100);
        System.out.println();

        results.add(benchFibonacci(1));
        results.add(benchPrimeCounting(2));
        results.add(benchBubbleSort(3));
        results.add(benchPrimaryConstructor(4));
        results.add(benchSecondaryConstructor(5));
        results.add(benchChainedDelegation(6));
        results.add(benchStringConcat(7));
        results.add(benchCollectionHigherOrder(8));
        results.add(benchLambdaClosure(9));
        results.add(benchRecursion(10));
        results.add(benchTailRecursion(11));
        results.add(benchWhenExpression(12));
        results.add(benchExtensionFunctions(13));
        results.add(benchOperatorOverloading(14));
        results.add(benchInterfaceDispatch(15));
        results.add(benchNullSafety(16));
        results.add(benchRangeIteration(17));
        results.add(benchEnumOperations(18));
        results.add(benchMapOperations(19));
        results.add(benchHighIterationLoop(20));
        results.add(benchDeepNestedScopes(21));
        results.add(benchClosureFactory(22));
        results.add(benchNumericBoxing(23));
        results.add(benchStringInterpolation(24));
        results.add(benchDeepInheritanceDispatch(25));
        results.add(benchTryCatchHotPath(26));
        results.add(benchLargeListChain(27));
        results.add(benchRecursiveDataStructure(28));
        results.add(benchMapIntensiveReadWrite(29));
        results.add(benchMixedStress(30));

        printSummaryTable(results);
    }

    // ============ 1. Fibonacci 迭代 ============

    private BenchResult benchFibonacci(int idx) {
        System.out.printf("[%d/30] Fibonacci 迭代 — fib(25) x 1000 ...%n", idx);

        String novaCode =
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
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun fib(n: Int): Int {\n" +
            "    var a = 0\n" +
            "    var b = 1\n" +
            "    for (i in 0..<n) {\n" +
            "      val temp = b\n" +
            "      b = a + b\n" +
            "      a = temp\n" +
            "    }\n" +
            "    return a\n" +
            "  }\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (iter in 0..<1000) {\n" +
            "      sum = sum + fib(25)\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaFibBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "Fibonacci 结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "Fibonacci 编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("Fibonacci 迭代", "fib(25) x 1000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaFib(int n) {
        int a = 0, b = 1;
        for (int i = 0; i < n; i++) {
            int temp = b;
            b = a + b;
            a = temp;
        }
        return a;
    }

    private static int javaFibBenchmark() {
        int sum = 0;
        for (int iter = 0; iter < 1000; iter++) {
            sum += javaFib(25);
        }
        return sum;
    }

    // ============ 2. 质数计数 ============

    private BenchResult benchPrimeCounting(int idx) {
        System.out.printf("[%d/30] 质数计数 — isPrime(2..5000) ...%n", idx);

        String novaCode =
            "fun isPrime(n: Int): Boolean {\n" +
            "  if (n < 2) return false\n" +
            "  var d = 2\n" +
            "  while (d * d <= n) {\n" +
            "    if (n % d == 0) return false\n" +
            "    d = d + 1\n" +
            "  }\n" +
            "  return true\n" +
            "}\n" +
            "var count = 0\n" +
            "for (n in 2..5000) {\n" +
            "  if (isPrime(n)) count = count + 1\n" +
            "}\n" +
            "count";

        String compiledCode =
            "object Bench {\n" +
            "  fun isPrime(n: Int): Boolean {\n" +
            "    if (n < 2) return false\n" +
            "    var d = 2\n" +
            "    while (d * d <= n) {\n" +
            "      if (n % d == 0) return false\n" +
            "      d = d + 1\n" +
            "    }\n" +
            "    return true\n" +
            "  }\n" +
            "  fun run(): Int {\n" +
            "    var count = 0\n" +
            "    for (n in 2..5000) {\n" +
            "      if (isPrime(n)) count = count + 1\n" +
            "    }\n" +
            "    return count\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaPrimeBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "质数计数结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "质数计数编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("质数计数", "isPrime(2..5000)", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static boolean javaIsPrime(int n) {
        if (n < 2) return false;
        for (int d = 2; d * d <= n; d++) {
            if (n % d == 0) return false;
        }
        return true;
    }

    private static int javaPrimeBenchmark() {
        int count = 0;
        for (int n = 2; n <= 5000; n++) {
            if (javaIsPrime(n)) count++;
        }
        return count;
    }

    // ============ 3. 冒泡排序 ============

    private BenchResult benchBubbleSort(int idx) {
        System.out.printf("[%d/30] 冒泡排序 — 300 元素逆序 ...%n", idx);

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

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    val arr = Array<Int>(300)\n" +
            "    var k = 0\n" +
            "    while (k < 300) {\n" +
            "      arr[k] = 300 - k\n" +
            "      k = k + 1\n" +
            "    }\n" +
            "    val n = arr.size\n" +
            "    var i = 0\n" +
            "    while (i < n) {\n" +
            "      var j = 0\n" +
            "      val limit = n - 1 - i\n" +
            "      while (j < limit) {\n" +
            "        if (arr[j] > arr[j + 1]) {\n" +
            "          val temp = arr[j]\n" +
            "          arr[j] = arr[j + 1]\n" +
            "          arr[j + 1] = temp\n" +
            "        }\n" +
            "        j = j + 1\n" +
            "      }\n" +
            "      i = i + 1\n" +
            "    }\n" +
            "    return arr[0] * 10000 + arr[299]\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaBubbleSortBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "冒泡排序结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("冒泡排序", "300 元素逆序", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaBubbleSortBenchmark() {
        int[] arr = new int[300];
        for (int k = 0; k < 300; k++) arr[k] = 300 - k;
        int n = arr.length;
        for (int i = 0; i < n; i++) {
            int limit = n - 1 - i;
            for (int j = 0; j < limit; j++) {
                if (arr[j] > arr[j + 1]) {
                    int temp = arr[j];
                    arr[j] = arr[j + 1];
                    arr[j + 1] = temp;
                }
            }
        }
        return arr[0] * 10000 + arr[299]; // 1*10000 + 300 = 10300
    }

    // ============ 4. 对象创建 — 主构造器 ============

    private BenchResult benchPrimaryConstructor(int idx) {
        System.out.printf("[%d/30] 对象创建(主构造器) — 5000 个 Point ...%n", idx);

        String novaCode =
            "class PtA(var x: Int, var y: Int) {\n" +
            "  fun sum() = x + y\n" +
            "}\n" +
            "var total = 0\n" +
            "for (i in 0..<5000) {\n" +
            "  val p = PtA(i, i * 2)\n" +
            "  total = total + p.sum()\n" +
            "}\n" +
            "total";

        String compiledCode =
            "class PtA(var x: Int, var y: Int) {\n" +
            "  fun sum(): Int = x + y\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var total = 0\n" +
            "    for (i in 0..<5000) {\n" +
            "      val p = PtA(i, i * 2)\n" +
            "      total = total + p.sum()\n" +
            "    }\n" +
            "    return total\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaPrimaryCtorBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "主构造器结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("主构造器创建", "5000 个 Point", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaPtA {
        int x, y;
        JavaPtA(int x, int y) { this.x = x; this.y = y; }
        int sum() { return x + y; }
    }

    private static int javaPrimaryCtorBenchmark() {
        int total = 0;
        for (int i = 0; i < 5000; i++) {
            JavaPtA p = new JavaPtA(i, i * 2);
            total += p.sum();
        }
        return total;
    }

    // ============ 5. 对象创建 — 次级构造器(委托) ============

    private BenchResult benchSecondaryConstructor(int idx) {
        System.out.printf("[%d/30] 对象创建(次级构造器) — 5000 个 Vec ...%n", idx);

        String novaCode =
            "class VecB(var x: Int, var y: Int) {\n" +
            "  constructor(v: Int) : this(v, v)\n" +
            "  fun sum() = x + y\n" +
            "}\n" +
            "var total = 0\n" +
            "for (i in 0..<5000) {\n" +
            "  val v = VecB(i)\n" +
            "  total = total + v.sum()\n" +
            "}\n" +
            "total";

        String compiledCode =
            "class VecB(var x: Int, var y: Int) {\n" +
            "  constructor(v: Int) : this(v, v)\n" +
            "  fun sum(): Int = x + y\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var total = 0\n" +
            "    for (i in 0..<5000) {\n" +
            "      val v = VecB(i)\n" +
            "      total = total + v.sum()\n" +
            "    }\n" +
            "    return total\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaSecondaryCtorBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "次级构造器结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("次级构造器创建", "5000 个 Vec", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaVecB {
        int x, y;
        JavaVecB(int v) { this(v, v); }
        JavaVecB(int x, int y) { this.x = x; this.y = y; }
        int sum() { return x + y; }
    }

    private static int javaSecondaryCtorBenchmark() {
        int total = 0;
        for (int i = 0; i < 5000; i++) {
            JavaVecB v = new JavaVecB(i);
            total += v.sum();
        }
        return total;
    }

    // ============ 6. 对象创建 — 链式委托(3 层) ============

    private BenchResult benchChainedDelegation(int idx) {
        System.out.printf("[%d/30] 对象创建(链式委托) — 5000 个 Box ...%n", idx);

        String novaCode =
            "class BoxC(var x: Int, var y: Int, var z: Int) {\n" +
            "  constructor(x: Int, y: Int) : this(x, y, 0)\n" +
            "  constructor(v: Int) : this(v, v)\n" +
            "  fun total() = x + y + z\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 1..5000) {\n" +
            "  val b = BoxC(i)\n" +
            "  sum = sum + b.total()\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "class BoxC(var x: Int, var y: Int, var z: Int) {\n" +
            "  constructor(x: Int, y: Int) : this(x, y, 0)\n" +
            "  constructor(v: Int) : this(v, v)\n" +
            "  fun total(): Int = x + y + z\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 1..5000) {\n" +
            "      val b = BoxC(i)\n" +
            "      sum = sum + b.total()\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaChainedCtorBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "链式委托结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("链式委托创建", "5000 个 Box(3 层)", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaBoxC {
        int x, y, z;
        JavaBoxC(int v) { this(v, v); }
        JavaBoxC(int x, int y) { this(x, y, 0); }
        JavaBoxC(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        int total() { return x + y + z; }
    }

    private static int javaChainedCtorBenchmark() {
        int sum = 0;
        for (int i = 1; i <= 5000; i++) {
            JavaBoxC b = new JavaBoxC(i);
            sum += b.total();
        }
        return sum;
    }

    // ============ 7. 字符串拼接 ============

    private BenchResult benchStringConcat(int idx) {
        System.out.printf("[%d/30] 字符串拼接 — 1000 次累加 ...%n", idx);

        String novaCode =
            "var s = \"\"\n" +
            "for (i in 0..<1000) {\n" +
            "  s = s + \"a\"\n" +
            "}\n" +
            "s.length()";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Any {\n" +
            "    var s = \"\"\n" +
            "    for (i in 0..<1000) {\n" +
            "      s = s + \"a\"\n" +
            "    }\n" +
            "    return s.length()\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaStringConcatBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "字符串拼接结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("字符串拼接", "1000 次累加", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaStringConcatBenchmark() {
        String s = "";
        for (int i = 0; i < 1000; i++) {
            s = s + "a";
        }
        return s.length();
    }

    // ============ 8. 集合高阶函数 ============

    private BenchResult benchCollectionHigherOrder(int idx) {
        System.out.printf("[%d/30] 集合高阶函数 — map+filter 1000 元素 ...%n", idx);

        String novaCode =
            "var list = []\n" +
            "for (i in 0..<1000) {\n" +
            "  list.add(i)\n" +
            "}\n" +
            "val evens = list.filter { n -> n % 2 == 0 }\n" +
            "val doubled = evens.map { n -> n * 2 }\n" +
            "var sum = 0\n" +
            "doubled.forEach { n -> sum = sum + n }\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Any {\n" +
            "    var list = []\n" +
            "    for (i in 0..<1000) {\n" +
            "      list.add(i)\n" +
            "    }\n" +
            "    val evens = list.filter { n -> n % 2 == 0 }\n" +
            "    val doubled = evens.map { n -> n * 2 }\n" +
            "    var sum = 0\n" +
            "    for (n in doubled) {\n" +
            "      sum = sum + n\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaCollectionHOBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "集合高阶函数结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("集合高阶函数", "map+filter 1000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaCollectionHOBenchmark() {
        List<Object> list = new ArrayList<Object>();
        for (int i = 0; i < 1000; i++) list.add(i);
        // 等价写法：filter 创建新 List + map 创建新 List + 遍历求和
        List<Object> evens = new ArrayList<Object>();
        for (Object n : list) { if ((int) n % 2 == 0) evens.add(n); }
        List<Object> doubled = new ArrayList<Object>();
        for (Object n : evens) { doubled.add((int) n * 2); }
        int sum = 0;
        for (Object n : doubled) {
            sum += (int) n;
        }
        return sum;
    }

    // ============ 9. Lambda / 闭包 ============

    private BenchResult benchLambdaClosure(int idx) {
        System.out.printf("[%d/30] Lambda 闭包 — 闭包捕获 x 5000 ...%n", idx);

        String novaCode =
            "var total = 0\n" +
            "for (i in 0..<5000) {\n" +
            "  val adder = { x -> x + i }\n" +
            "  total = total + adder(1)\n" +
            "}\n" +
            "total";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var total = 0\n" +
            "    for (i in 0..<5000) {\n" +
            "      val adder = { x -> x + i }\n" +
            "      total = total + adder(1)\n" +
            "    }\n" +
            "    return total\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaLambdaClosureBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "Lambda 闭包结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("Lambda 闭包", "闭包捕获 x 5000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaLambdaClosureBenchmark() {
        int total = 0;
        for (int i = 0; i < 5000; i++) {
            final int captured = i;
            java.util.function.IntUnaryOperator adder = x -> x + captured;
            total += adder.applyAsInt(1);
        }
        return total;
    }

    // ============ 10. 递归 ============

    private BenchResult benchRecursion(int idx) {
        System.out.printf("[%d/30] 递归调用 — fib(20) 递归 x 100 ...%n", idx);

        String novaCode =
            "fun fib(n: Int): Int {\n" +
            "  if (n <= 1) return n\n" +
            "  return fib(n - 1) + fib(n - 2)\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 0..<100) {\n" +
            "  sum = sum + fib(20)\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun fib(n: Int): Int {\n" +
            "    if (n <= 1) return n\n" +
            "    return fib(n - 1) + fib(n - 2)\n" +
            "  }\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<100) {\n" +
            "      sum = sum + fib(20)\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaRecursionBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "递归结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("递归调用", "fib(20) 递归 x 100", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaFibRecursive(int n) {
        if (n <= 1) return n;
        return javaFibRecursive(n - 1) + javaFibRecursive(n - 2);
    }

    private static int javaRecursionBenchmark() {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += javaFibRecursive(20);
        }
        return sum;
    }

    // ============ 11. 尾递归 (TCO) ============

    private BenchResult benchTailRecursion(int idx) {
        System.out.printf("[%d/30] 尾递归 (TCO) — tail-fib(20) x 100 ...%n", idx);

        String novaCode =
            "fun fib(n: Int, a: Int = 0, b: Int = 1): Int = " +
            "  if (n == 0) a else fib(n - 1, b, a + b)\n" +
            "var sum = 0\n" +
            "for (i in 0..<100) {\n" +
            "  sum = sum + fib(20)\n" +
            "}\n" +
            "sum";

        // 编译模式：不使用默认参数，显式传参
        String compiledCode =
            "object Bench {\n" +
            "  fun fib(n: Int, a: Int, b: Int): Int = if (n == 0) a else fib(n - 1, b, a + b)\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<100) {\n" +
            "      sum = sum + fib(20, 0, 1)\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaTailFibBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "尾递归结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "尾递归编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("尾递归 (TCO)", "tail-fib(20) x 100", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaTailFib(int n, int a, int b) {
        while (n > 0) {
            int temp = a + b;
            a = b;
            b = temp;
            n--;
        }
        return a;
    }

    private static int javaTailFibBenchmark() {
        int sum = 0;
        for (int i = 0; i < 100; i++) {
            sum += javaTailFib(20, 0, 1);
        }
        return sum;
    }

    // ============ 12. When 表达式 ============

    private BenchResult benchWhenExpression(int idx) {
        System.out.printf("[%d/30] When 表达式 — 分支匹配 x 10000 ...%n", idx);

        String novaCode =
            "fun classify(n: Int): Int {\n" +
            "  return when {\n" +
            "    n % 15 == 0 -> 3\n" +
            "    n % 5 == 0 -> 2\n" +
            "    n % 3 == 0 -> 1\n" +
            "    else -> 0\n" +
            "  }\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 1..10000) {\n" +
            "  sum = sum + classify(i)\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun classify(n: Int): Int {\n" +
            "    return when {\n" +
            "      n % 15 == 0 -> 3\n" +
            "      n % 5 == 0 -> 2\n" +
            "      n % 3 == 0 -> 1\n" +
            "      else -> 0\n" +
            "    }\n" +
            "  }\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 1..10000) {\n" +
            "      sum = sum + classify(i)\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaWhenBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "When 表达式结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("When 表达式", "分支匹配 x 10000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaWhenBenchmark() {
        int sum = 0;
        for (int i = 1; i <= 10000; i++) {
            if (i % 15 == 0) sum += 3;
            else if (i % 5 == 0) sum += 2;
            else if (i % 3 == 0) sum += 1;
        }
        return sum;
    }

    // ============ 13. 扩展函数 ============

    private BenchResult benchExtensionFunctions(int idx) {
        System.out.printf("[%d/30] 扩展函数 — Int.double() x 10000 ...%n", idx);

        String novaCode =
            "fun Int.double() = this * 2\n" +
            "fun Int.square() = this * this\n" +
            "var sum = 0\n" +
            "for (i in 1..10000) {\n" +
            "  sum = sum + i.double() + i.square()\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun Int.double(): Int = this * 2\n" +
            "  fun Int.square(): Int = this * this\n" +
            "  fun run(): Any {\n" +
            "    var sum = 0\n" +
            "    for (i in 1..10000) {\n" +
            "      sum = sum + i.double() + i.square()\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaExtensionBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "扩展函数结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("扩展函数", "Int.double()/square() x 10000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int extDouble(int x) { return x * 2; }
    private static int extSquare(int x) { return x * x; }

    private static int javaExtensionBenchmark() {
        int sum = 0;
        for (int i = 1; i <= 10000; i++) {
            sum += extDouble(i) + extSquare(i);
        }
        return sum;
    }

    // ============ 14. 运算符重载 ============

    private BenchResult benchOperatorOverloading(int idx) {
        System.out.printf("[%d/30] 运算符重载 — Vec2 加法 x 5000 ...%n", idx);

        String novaCode =
            "class Vec2(val x: Int, val y: Int) {\n" +
            "  fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)\n" +
            "}\n" +
            "var v = Vec2(0, 0)\n" +
            "for (i in 1..5000) {\n" +
            "  v = v + Vec2(i, i)\n" +
            "}\n" +
            "v.x + v.y";

        String compiledCode =
            "class Vec2(val x: Int, val y: Int) {\n" +
            "  fun plus(other: Vec2): Vec2 = Vec2(x + other.x, y + other.y)\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var v = Vec2(0, 0)\n" +
            "    for (i in 1..5000) {\n" +
            "      v = v + Vec2(i, i)\n" +
            "    }\n" +
            "    return v.x + v.y\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaOperatorOverloadBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "运算符重载结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("运算符重载", "Vec2 加法 x 5000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaVec2Op {
        final int x, y;
        JavaVec2Op(int x, int y) { this.x = x; this.y = y; }
        JavaVec2Op plus(JavaVec2Op o) { return new JavaVec2Op(x + o.x, y + o.y); }
    }

    private static int javaOperatorOverloadBenchmark() {
        JavaVec2Op v = new JavaVec2Op(0, 0);
        for (int i = 1; i <= 5000; i++) {
            v = v.plus(new JavaVec2Op(i, i));
        }
        return v.x + v.y;
    }

    // ============ 15. 接口多态 ============

    private BenchResult benchInterfaceDispatch(int idx) {
        System.out.printf("[%d/30] 接口多态 — 虚方法调用 x 5000 ...%n", idx);

        String novaCode =
            "interface Shape {\n" +
            "  fun area(): Int\n" +
            "}\n" +
            "class Square(val side: Int) : Shape {\n" +
            "  fun area() = side * side\n" +
            "}\n" +
            "class Rect(val w: Int, val h: Int) : Shape {\n" +
            "  fun area() = w * h\n" +
            "}\n" +
            "var total = 0\n" +
            "for (i in 1..5000) {\n" +
            "  val s: Shape = if (i % 2 == 0) Square(i) else Rect(i, i + 1)\n" +
            "  total = total + s.area()\n" +
            "}\n" +
            "total";

        String compiledCode =
            "interface Shape {\n" +
            "  fun area(): Int\n" +
            "}\n" +
            "class Square(val side: Int) : Shape {\n" +
            "  fun area(): Int = side * side\n" +
            "}\n" +
            "class Rect(val w: Int, val h: Int) : Shape {\n" +
            "  fun area(): Int = w * h\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var total = 0\n" +
            "    for (i in 1..5000) {\n" +
            "      val s: Shape = if (i % 2 == 0) Square(i) else Rect(i, i + 1)\n" +
            "      total = total + s.area()\n" +
            "    }\n" +
            "    return total\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaInterfaceDispatchBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "接口多态结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("接口多态", "虚方法调用 x 5000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    interface JavaShape { int area(); }
    static class JavaSquare implements JavaShape {
        final int side;
        JavaSquare(int s) { this.side = s; }
        public int area() { return side * side; }
    }
    static class JavaRect implements JavaShape {
        final int w, h;
        JavaRect(int w, int h) { this.w = w; this.h = h; }
        public int area() { return w * h; }
    }

    private static int javaInterfaceDispatchBenchmark() {
        int total = 0;
        for (int i = 1; i <= 5000; i++) {
            JavaShape s = (i % 2 == 0) ? new JavaSquare(i) : new JavaRect(i, i + 1);
            total += s.area();
        }
        return total;
    }

    // ============ 16. 空安全操作符 ============

    private BenchResult benchNullSafety(int idx) {
        System.out.printf("[%d/30] 空安全操作符 — ?. 和 ?: x 10000 ...%n", idx);

        String novaCode =
            "class Box(val value: Int)\n" +
            "var sum = 0\n" +
            "for (i in 0..<10000) {\n" +
            "  val b: Box? = if (i % 3 == 0) null else Box(i)\n" +
            "  val v = b?.value ?: 0\n" +
            "  sum = sum + v\n" +
            "}\n" +
            "sum";

        // 编译版本：不依赖自定义 class，用 elvis 测试 null 安全
        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<10000) {\n" +
            "      val v: Any? = if (i % 3 == 0) null else i\n" +
            "      sum = sum + (v ?: 0)\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaNullSafetyBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "空安全操作符结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("空安全操作符", "?. 和 ?: x 10000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaNullSafetyBenchmark() {
        int sum = 0;
        for (int i = 0; i < 10000; i++) {
            Integer v = (i % 3 == 0) ? null : i;
            sum += (v != null) ? v : 0;
        }
        return sum;
    }

    // ============ 17. Range 迭代 ============

    private BenchResult benchRangeIteration(int idx) {
        System.out.printf("[%d/30] Range 迭代 — 嵌套 range 求和 ...%n", idx);

        String novaCode =
            "var sum = 0\n" +
            "for (i in 1..100) {\n" +
            "  for (j in 1..100) {\n" +
            "    sum = sum + i + j\n" +
            "  }\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 1..100) {\n" +
            "      for (j in 1..100) {\n" +
            "        sum = sum + i + j\n" +
            "      }\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaRangeIterationBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "Range 迭代结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("Range 迭代", "100x100 嵌套求和", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaRangeIterationBenchmark() {
        int sum = 0;
        for (int i = 1; i <= 100; i++) {
            for (int j = 1; j <= 100; j++) {
                sum += i + j;
            }
        }
        return sum;
    }

    // ============ 18. 枚举操作 ============

    private BenchResult benchEnumOperations(int idx) {
        System.out.printf("[%d/30] 枚举操作 — enum when 匹配 x 5000 ...%n", idx);

        String novaCode =
            "enum class Color { RED, GREEN, BLUE }\n" +
            "fun score(c: Color): Int {\n" +
            "  return when (c) {\n" +
            "    Color.RED -> 1\n" +
            "    Color.GREEN -> 2\n" +
            "    Color.BLUE -> 3\n" +
            "    else -> 0\n" +
            "  }\n" +
            "}\n" +
            "val colors = Color.values()\n" +
            "var sum = 0\n" +
            "for (i in 0..<5000) {\n" +
            "  sum = sum + score(colors[i % 3])\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "enum class Color { RED, GREEN, BLUE }\n" +
            "object Bench {\n" +
            "  fun score(c: Color): Int {\n" +
            "    return when (c) {\n" +
            "      Color.RED -> 1\n" +
            "      Color.GREEN -> 2\n" +
            "      Color.BLUE -> 3\n" +
            "      else -> 0\n" +
            "    }\n" +
            "  }\n" +
            "  fun run(): Int {\n" +
            "    val colors = Color.values()\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<5000) {\n" +
            "      sum = sum + score(colors[i % 3])\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaEnumBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "枚举操作结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("枚举操作", "enum when x 5000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    enum JavaColor { RED, GREEN, BLUE }

    private static int javaEnumBenchmark() {
        JavaColor[] colors = JavaColor.values();
        int sum = 0;
        for (int i = 0; i < 5000; i++) {
            JavaColor c = colors[i % 3];
            switch (c) {
                case RED: sum += 1; break;
                case GREEN: sum += 2; break;
                case BLUE: sum += 3; break;
            }
        }
        return sum;
    }

    // ============ 19. Map 操作 ============

    private BenchResult benchMapOperations(int idx) {
        System.out.printf("[%d/30] Map 操作 — 插入+查找 1000 项 ...%n", idx);

        String novaCode =
            "val m = #{\"_\": 0}\n" +
            "for (i in 0..<1000) {\n" +
            "  m[\"key\" + i] = i\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 0..<1000) {\n" +
            "  sum = sum + m[\"key\" + i]\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    val m = #{\"_\": 0}\n" +
            "    for (i in 0..<1000) {\n" +
            "      m[\"key\" + i] = i\n" +
            "    }\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<1000) {\n" +
            "      sum = sum + m[\"key\" + i]\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaMapBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "Map 操作结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("Map 操作", "插入+查找 1000 项", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaMapBenchmark() {
        java.util.Map<String, Integer> m = new java.util.HashMap<String, Integer>();
        for (int i = 0; i < 1000; i++) {
            m.put("key" + i, i);
        }
        int sum = 0;
        for (int i = 0; i < 1000; i++) {
            sum += m.get("key" + i);
        }
        return sum;
    }

    // ============ 20. 高迭代循环 (resetForLoop) ============

    private BenchResult benchHighIterationLoop(int idx) {
        System.out.printf("[%d/30] 高迭代循环 — 10 万次 for 循环 ...%n", idx);

        String novaCode =
            "var sum = 0\n" +
            "for (i in 0..<100000) {\n" +
            "  sum = sum + i % 7\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<100000) {\n" +
            "      sum = sum + i % 7\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaHighIterationBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "高迭代循环结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("高迭代循环", "10 万次 for", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaHighIterationBenchmark() {
        int sum = 0;
        for (int i = 0; i < 100000; i++) {
            sum += i % 7;
        }
        return sum;
    }

    // ============ 21. 深层嵌套作用域 ============

    private BenchResult benchDeepNestedScopes(int idx) {
        System.out.printf("[%d/30] 深层嵌套作用域 — 4 层嵌套 20x20x20x5 ...%n", idx);

        String novaCode =
            "var sum = 0\n" +
            "for (a in 0..<20) {\n" +
            "  for (b in 0..<20) {\n" +
            "    for (c in 0..<20) {\n" +
            "      for (d in 0..<5) {\n" +
            "        sum = sum + (a + b + c + d) % 10\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (a in 0..<20) {\n" +
            "      for (b in 0..<20) {\n" +
            "        for (c in 0..<20) {\n" +
            "          for (d in 0..<5) {\n" +
            "            sum = sum + (a + b + c + d) % 10\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaDeepNestedBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "深层嵌套作用域结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("深层嵌套作用域", "4 层 20x20x20x5", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaDeepNestedBenchmark() {
        int sum = 0;
        for (int a = 0; a < 20; a++) {
            for (int b = 0; b < 20; b++) {
                for (int c = 0; c < 20; c++) {
                    for (int d = 0; d < 5; d++) {
                        sum += (a + b + c + d) % 10;
                    }
                }
            }
        }
        return sum;
    }

    // ============ 22. 闭包工厂 (createMinimalClosure) ============

    private BenchResult benchClosureFactory(int idx) {
        System.out.printf("[%d/30] 闭包工厂 — 批量创建+调用 5000 闭包 ...%n", idx);

        String novaCode =
            "var adders = []\n" +
            "for (i in 0..<5000) {\n" +
            "  adders.add({ x -> x + i })\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 0..<5000) {\n" +
            "  sum = sum + adders[i](10)\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var adders = []\n" +
            "    for (i in 0..<5000) {\n" +
            "      adders.add({ x -> x + i })\n" +
            "    }\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<5000) {\n" +
            "      sum = sum + adders[i](10)\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaClosureFactoryBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "闭包工厂结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("闭包工厂", "5000 闭包创建+调用", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaClosureFactoryBenchmark() {
        List<java.util.function.IntUnaryOperator> adders = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            final int captured = i;
            adders.add(x -> x + captured);
        }
        int sum = 0;
        for (int i = 0; i < 5000; i++) {
            sum += adders.get(i).applyAsInt(10);
        }
        return sum;
    }

    // ============ 23. 数值装箱密集 ============

    private BenchResult benchNumericBoxing(int idx) {
        System.out.printf("[%d/30] 数值装箱密集 — Long 累加取模 50000 次 ...%n", idx);

        String novaCode =
            "var sum = 0\n" +
            "for (i in 0..<500) {\n" +
            "  for (j in 0..<100) {\n" +
            "    sum = sum + (i * j) % 1000\n" +
            "  }\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<500) {\n" +
            "      for (j in 0..<100) {\n" +
            "        sum = sum + (i * j) % 1000\n" +
            "      }\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaNumericBoxingBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "数值装箱结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("数值装箱密集", "Long 累加 50000 次", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaNumericBoxingBenchmark() {
        int sum = 0;
        for (int i = 0; i < 500; i++) {
            for (int j = 0; j < 100; j++) {
                sum += (i * j) % 1000;
            }
        }
        return sum;
    }

    // ============ 24. 字符串插值 ============

    private BenchResult benchStringInterpolation(int idx) {
        System.out.printf("[%d/30] 字符串插值 — 模板字符串 x 3000 ...%n", idx);

        String novaCode =
            "var total = 0\n" +
            "for (i in 0..<3000) {\n" +
            "  val s = \"item_${i}_end\"\n" +
            "  total = total + s.length()\n" +
            "}\n" +
            "total";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Any {\n" +
            "    var total = 0\n" +
            "    for (i in 0..<3000) {\n" +
            "      val s = \"item_${i}_end\"\n" +
            "      total = total + s.length()\n" +
            "    }\n" +
            "    return total\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaStringInterpolationBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "字符串插值结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("字符串插值", "模板字符串 x 3000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaStringInterpolationBenchmark() {
        int total = 0;
        for (int i = 0; i < 3000; i++) {
            String s = "item_" + i + "_end";
            total += s.length();
        }
        return total;
    }

    // ============ 25. 深继承链方法分派 ============

    private BenchResult benchDeepInheritanceDispatch(int idx) {
        System.out.printf("[%d/30] 深继承链分派 — 3 层继承方法调用 x 5000 ...%n", idx);

        String novaCode =
            "class A(val v: Int) {\n" +
            "  fun compute(): Int = v * 2\n" +
            "}\n" +
            "class B(v: Int) : A(v) {\n" +
            "  fun compute(): Int = v * 3\n" +
            "}\n" +
            "class C(v: Int) : B(v) {\n" +
            "  fun compute(): Int = v * 5\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (i in 1..5000) {\n" +
            "  val obj = C(i)\n" +
            "  sum = sum + obj.compute()\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "class A(val v: Int) {\n" +
            "  fun compute(): Int = v * 2\n" +
            "}\n" +
            "class B(v: Int) : A(v) {\n" +
            "  fun compute(): Int = v * 3\n" +
            "}\n" +
            "class C(v: Int) : B(v) {\n" +
            "  fun compute(): Int = v * 5\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 1..5000) {\n" +
            "      val obj = C(i)\n" +
            "      sum = sum + obj.compute()\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaDeepInheritanceBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "深继承链分派结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("深继承链分派", "3 层继承 x 5000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaA { final int v; JavaA(int v) { this.v = v; } int compute() { return v * 2; } }
    static class JavaB extends JavaA { JavaB(int v) { super(v); } int compute() { return v * 3; } }
    static class JavaC extends JavaB { JavaC(int v) { super(v); } int compute() { return v * 5; } }

    private static int javaDeepInheritanceBenchmark() {
        int sum = 0;
        for (int i = 1; i <= 5000; i++) {
            JavaC obj = new JavaC(i);
            sum += obj.compute();
        }
        return sum;
    }

    // ============ 26. Try-catch 热路径 ============

    private BenchResult benchTryCatchHotPath(int idx) {
        System.out.printf("[%d/30] Try-catch 热路径 — 无异常循环 x 10000 ...%n", idx);

        String novaCode =
            "var sum = 0\n" +
            "for (i in 0..<10000) {\n" +
            "  try {\n" +
            "    sum = sum + i % 13\n" +
            "  } catch (e) {\n" +
            "    sum = sum + 0\n" +
            "  }\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var sum = 0\n" +
            "    for (i in 0..<10000) {\n" +
            "      try {\n" +
            "        sum = sum + i % 13\n" +
            "      } catch (e) {\n" +
            "        sum = sum + 0\n" +
            "      }\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaTryCatchBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "Try-catch 热路径结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("Try-catch 热路径", "无异常循环 x 10000", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaTryCatchBenchmark() {
        int sum = 0;
        for (int i = 0; i < 10000; i++) {
            try {
                sum += i % 13;
            } catch (Exception e) {
                sum += 0;
            }
        }
        return sum;
    }

    // ============ 27. 大列表链式操作 ============

    private BenchResult benchLargeListChain(int idx) {
        System.out.printf("[%d/30] 大列表链式操作 — 5000 元素 map→filter→map→forEach ...%n", idx);

        String novaCode =
            "var list = []\n" +
            "for (i in 0..<5000) { list.add(i) }\n" +
            "val mapped1 = list.map { x -> x * 3 }\n" +
            "val filtered = mapped1.filter { x -> x % 2 == 0 }\n" +
            "val mapped2 = filtered.map { x -> x + 1 }\n" +
            "var sum = 0\n" +
            "mapped2.forEach { x -> sum = sum + x }\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var list = []\n" +
            "    for (i in 0..<5000) { list.add(i) }\n" +
            "    val mapped1 = list.map { x -> x * 3 }\n" +
            "    val filtered = mapped1.filter { x -> x % 2 == 0 }\n" +
            "    val mapped2 = filtered.map { x -> x + 1 }\n" +
            "    var sum = 0\n" +
            "    for (x in mapped2) {\n" +
            "      sum = sum + x\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaLargeListChainBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "大列表链式操作结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("大列表链式操作", "5000 元素 4 步链", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaLargeListChainBenchmark() {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < 5000; i++) list.add(i);
        List<Object> mapped1 = new ArrayList<>();
        for (Object x : list) mapped1.add((int) x * 3);
        List<Object> filtered = new ArrayList<>();
        for (Object x : mapped1) { if ((int) x % 2 == 0) filtered.add(x); }
        List<Object> mapped2 = new ArrayList<>();
        for (Object x : filtered) mapped2.add((int) x + 1);
        int sum = 0;
        for (Object x : mapped2) sum += (int) x;
        return sum;
    }

    // ============ 28. 递归数据结构 ============

    private BenchResult benchRecursiveDataStructure(int idx) {
        System.out.printf("[%d/30] 递归数据结构 — 链表 500 节点递归求和 ...%n", idx);

        String novaCode =
            "class Node(val value: Int, val next: Any?) {\n" +
            "}\n" +
            "fun sumList(node: Any?): Int {\n" +
            "  if (node == null) return 0\n" +
            "  return node.value + sumList(node.next)\n" +
            "}\n" +
            "var head: Any? = null\n" +
            "for (i in 0..<500) {\n" +
            "  head = Node(i, head)\n" +
            "}\n" +
            "sumList(head)";

        String compiledCode =
            "class Node(val value: Int, val next: Any?) {\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun sumList(node: Any?): Int {\n" +
            "    if (node == null) return 0\n" +
            "    return node.value + sumList(node.next)\n" +
            "  }\n" +
            "  fun run(): Int {\n" +
            "    var head: Any? = null\n" +
            "    for (i in 0..<500) {\n" +
            "      head = Node(i, head)\n" +
            "    }\n" +
            "    return sumList(head)\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaRecursiveDataBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "递归数据结构结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("递归数据结构", "链表 500 节点", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaNode {
        final int value;
        final JavaNode next;
        JavaNode(int value, JavaNode next) { this.value = value; this.next = next; }
    }

    private static int javaSumList(JavaNode node) {
        if (node == null) return 0;
        return node.value + javaSumList(node.next);
    }

    private static int javaRecursiveDataBenchmark() {
        JavaNode head = null;
        for (int i = 0; i < 500; i++) {
            head = new JavaNode(i, head);
        }
        return javaSumList(head);
    }

    // ============ 29. Map 密集读写 ============

    private BenchResult benchMapIntensiveReadWrite(int idx) {
        System.out.printf("[%d/30] Map 密集读写 — 5000 项写入 + 3 轮读写 ...%n", idx);

        String novaCode =
            "val m = #{\"_\": 0}\n" +
            "for (i in 0..<5000) {\n" +
            "  m[\"k\" + i] = i\n" +
            "}\n" +
            "var sum = 0\n" +
            "for (round in 0..<3) {\n" +
            "  for (i in 0..<5000) {\n" +
            "    sum = sum + m[\"k\" + i]\n" +
            "    m[\"k\" + i] = m[\"k\" + i] + 1\n" +
            "  }\n" +
            "}\n" +
            "sum";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    val m = #{\"_\": 0}\n" +
            "    for (i in 0..<5000) {\n" +
            "      m[\"k\" + i] = i\n" +
            "    }\n" +
            "    var sum = 0\n" +
            "    for (round in 0..<3) {\n" +
            "      for (i in 0..<5000) {\n" +
            "        sum = sum + m[\"k\" + i]\n" +
            "        m[\"k\" + i] = m[\"k\" + i] + 1\n" +
            "      }\n" +
            "    }\n" +
            "    return sum\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaMapIntensiveBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "Map 密集读写结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("Map 密集读写", "5000 项 3 轮读写", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaMapIntensiveBenchmark() {
        java.util.Map<String, Integer> m = new java.util.HashMap<>();
        for (int i = 0; i < 5000; i++) {
            m.put("k" + i, i);
        }
        int sum = 0;
        for (int round = 0; round < 3; round++) {
            for (int i = 0; i < 5000; i++) {
                sum += m.get("k" + i);
                m.put("k" + i, m.get("k" + i) + 1);
            }
        }
        return sum;
    }

    // ============ 30. 综合混合压力 ============

    private BenchResult benchMixedStress(int idx) {
        System.out.printf("[%d/30] 综合混合压力 — 对象+闭包+集合+字符串+方法 ...%n", idx);

        String novaCode =
            "class Item(val name: String, val price: Int) {\n" +
            "  fun discount(pct: Int): Int = price - price * pct / 100\n" +
            "}\n" +
            "var items = []\n" +
            "for (i in 0..<2000) {\n" +
            "  items.add(Item(\"item_\" + i, i * 3 + 10))\n" +
            "}\n" +
            "val discounter = { item, pct -> item.discount(pct) }\n" +
            "val expensive = items.filter { item -> item.price > 500 }\n" +
            "var sum = 0\n" +
            "expensive.forEach { item ->\n" +
            "  sum = sum + discounter(item, 10)\n" +
            "}\n" +
            "var nameLen = 0\n" +
            "for (i in 0..<500) {\n" +
            "  nameLen = nameLen + items[i].name.length()\n" +
            "}\n" +
            "sum + nameLen";

        String compiledCode =
            "class Item(val name: String, val price: Int) {\n" +
            "  fun discount(pct: Int): Int = price - price * pct / 100\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var items = []\n" +
            "    for (i in 0..<2000) {\n" +
            "      items.add(Item(\"item_\" + i, i * 3 + 10))\n" +
            "    }\n" +
            "    val expensive = items.filter { item -> item.price > 500 }\n" +
            "    var sum = 0\n" +
            "    for (item in expensive) {\n" +
            "      sum = sum + item.discount(10)\n" +
            "    }\n" +
            "    var nameLen = 0\n" +
            "    for (i in 0..<500) {\n" +
            "      nameLen = nameLen + items[i].name.length()\n" +
            "    }\n" +
            "    return sum + nameLen\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(PerformanceBenchmarkTest::javaMixedStressBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "综合混合压力结果不一致");
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("综合混合压力", "对象+闭包+集合+字符串", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaItem {
        final String name;
        final int price;
        JavaItem(String name, int price) { this.name = name; this.price = price; }
        int discount(int pct) { return price - price * pct / 100; }
    }

    private static int javaMixedStressBenchmark() {
        List<JavaItem> items = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            items.add(new JavaItem("item_" + i, i * 3 + 10));
        }
        List<JavaItem> expensive = new ArrayList<>();
        for (JavaItem item : items) {
            if (item.price > 500) expensive.add(item);
        }
        int sum = 0;
        for (JavaItem item : expensive) {
            sum += item.discount(10);
        }
        int nameLen = 0;
        for (int i = 0; i < 500; i++) {
            nameLen += items.get(i).name.length();
        }
        return sum + nameLen;
    }

    // ============ 输出格式化 ============

    private void printBenchResult(double novaMs, double execOnlyMs, double compiledMs, double javaMs,
                                   double novaCv, double execOnlyCv) {
        System.out.printf("  Nova 全流程: %10.2f ms  (CV %.1f%%)%n", novaMs, novaCv);
        System.out.printf("  Nova 纯执行: %10.2f ms  (CV %.1f%%)%n", execOnlyMs, execOnlyCv);
        if (compiledMs >= 0) {
            System.out.printf("  Nova 编译:   %10.4f ms%n", compiledMs);
        } else {
            System.out.printf("  Nova 编译:   %10s%n", "N/A");
        }
        System.out.printf("  Java 原生:   %10.4f ms%n", javaMs);
        double interpRatio = javaMs > 0.001 ? novaMs / javaMs : -1;
        double execOnlyRatio = javaMs > 0.001 ? execOnlyMs / javaMs : -1;
        if (interpRatio > 0) {
            System.out.printf("  全流程/原生: %10.1fx%n", interpRatio);
        }
        if (execOnlyRatio > 0) {
            System.out.printf("  纯执行/原生: %10.1fx%n", execOnlyRatio);
        }
        if (compiledMs >= 0 && javaMs > 0.001) {
            System.out.printf("  编译/原生:   %10.1fx%n", compiledMs / javaMs);
        }
        System.out.println();
    }

    private void printSummaryTable(List<BenchResult> results) {
        // 动态计算名称列宽度（按显示宽度，中文=2列）
        int nameColWidth = 8;
        for (BenchResult r : results) {
            nameColWidth = Math.max(nameColWidth, displayWidth(r.name));
        }
        nameColWidth += 2; // 右侧留 2 格间距

        int totalWidth = 2 + nameColWidth + 10 * 7 + 7;
        printLine('=', totalWidth);
        System.out.println("  汇总");
        printLine('=', totalWidth);
        System.out.println();
        System.out.printf("  %s %10s %10s %10s %10s %10s %10s %10s%n",
                padRight("测试项", nameColWidth), "全流程(ms)", "纯执行(ms)", "编译(ms)", "原生(ms)",
                "全流程/原生", "纯执行/原生", "编译/原生");
        printLine('-', totalWidth);

        for (BenchResult r : results) {
            String interpR = r.interpRatio() > 0 ? String.format("%.1fx", r.interpRatio()) : "N/A";
            String execOnlyR = r.execOnlyRatio() > 0 ? String.format("%.1fx", r.execOnlyRatio()) : "N/A";
            String compiledR = r.compiledRatio() > 0 ? String.format("%.1fx", r.compiledRatio()) : "N/A";
            String compiledMs = r.compiledMs >= 0 ? String.format("%.4f", r.compiledMs) : "N/A";
            System.out.printf("  %s %10.2f %10.2f %10s %10.4f %10s %10s %10s%n",
                    padRight(r.name, nameColWidth), r.novaMs, r.execOnlyMs, compiledMs, r.javaMs,
                    interpR, execOnlyR, compiledR);
        }

        printLine('-', totalWidth);

        // 平均倍率
        double totalInterpRatio = 0, totalExecOnlyRatio = 0, totalCompiledRatio = 0;
        int interpCount = 0, execOnlyCount = 0, compiledCount = 0;
        for (BenchResult r : results) {
            if (r.interpRatio() > 0) { totalInterpRatio += r.interpRatio(); interpCount++; }
            if (r.execOnlyRatio() > 0) { totalExecOnlyRatio += r.execOnlyRatio(); execOnlyCount++; }
            if (r.compiledRatio() > 0) { totalCompiledRatio += r.compiledRatio(); compiledCount++; }
        }
        String avgInterp = interpCount > 0 ? String.format("%.1fx", totalInterpRatio / interpCount) : "N/A";
        String avgExecOnly = execOnlyCount > 0 ? String.format("%.1fx", totalExecOnlyRatio / execOnlyCount) : "N/A";
        String avgCompiled = compiledCount > 0 ? String.format("%.1fx", totalCompiledRatio / compiledCount) : "N/A";
        System.out.printf("  %s %10s %10s %10s %10s %10s %10s %10s%n",
                padRight("平均", nameColWidth), "", "", "", "", avgInterp, avgExecOnly, avgCompiled);

        System.out.println();
        System.out.println("  注: 全流程 = 词法分析 + 语法分析 + HIR/MIR lowering + MIR 解释执行");
        System.out.println("      纯执行 = 预编译 MIR 后仅执行（不含解析和编译）");
        System.out.println("      编译   = 预编译为 JVM 字节码后执行（不含编译时间）");
        System.out.println("      原生   = Java JIT 编译后的原生执行");
        System.out.println("      CV%    = 变异系数（越低越稳定，<10% 为良好）");
        printLine('=', totalWidth);
        System.out.println();
    }

    /** 计算字符串在终端的显示宽度（CJK 字符占 2 列，ASCII 占 1 列） */
    private static int displayWidth(String s) {
        int width = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF
                    || c >= 0x3000 && c <= 0x303F
                    || c >= 0xFF01 && c <= 0xFF60
                    || c >= 0xFFE0 && c <= 0xFFE6) {
                width += 2;
            } else {
                width += 1;
            }
        }
        return width;
    }

    /** 按显示宽度右填充空格 */
    private static String padRight(String s, int targetWidth) {
        int pad = targetWidth - displayWidth(s);
        if (pad <= 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + pad);
        sb.append(s);
        for (int i = 0; i < pad; i++) sb.append(' ');
        return sb.toString();
    }

    private static void printLine(char ch, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ch);
        System.out.println(sb.toString());
    }
}
