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
 * 集成业务场景基准测试
 *
 * <p>模拟真实业务代码，融合多种算法和语言特性，三方对比：Nova 解释 / 编译 / Java 原生</p>
 * <ul>
 *   <li>电商订单处理 — class + method + when + for + if + 算术</li>
 *   <li>学生成绩排名 — list + 冒泡排序 + if/else 分级 + 统计</li>
 *   <li>矩阵乘法 — 三重嵌套循环 + list + 密集算术</li>
 *   <li>银行交易模拟 — list + for + if/else 分支 + 余额校验</li>
 * </ul>
 */
@DisplayName("集成业务场景基准测试")
class IntegrationBenchmarkTest {

    private static final int WARMUP = 10;
    private static final int RUNS = 15;

    // ============ 结果容器 ============

    static class BenchResult {
        final String name;
        final String desc;
        final double novaMs;
        final double execOnlyMs;
        final double compiledMs;
        final double javaMs;
        final double novaCv;
        final double execOnlyCv;

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

    private static void gcQuiet() {
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}
    }

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

    private Object[] measureNovaExecOnly(String code) {
        Interpreter compileInterp = new Interpreter();
        com.novalang.ir.mir.MirModule mir = compileInterp.precompileToMir(code);
        for (int i = 0; i < WARMUP; i++) {
            new Interpreter().executeMir(mir);
        }
        gcQuiet();
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
                // no INSTANCE
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
        } catch (Exception e) {
            System.out.println("  [编译模式跳过: " + e.getMessage() + "]");
            return new Object[]{-1.0, null, 0.0};
        }
    }

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

    private static double trimmedMeanNanos(long[] times) {
        long[] sorted = times.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        int trim = n / 5;
        long sum = 0;
        int count = n - 2 * trim;
        for (int i = trim; i < n - trim; i++) {
            sum += sorted[i];
        }
        return (double) sum / count;
    }

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

    @Test
    @DisplayName("综合业务场景基准测试")
    void runAllIntegrationBenchmarks() {
        List<BenchResult> results = new ArrayList<BenchResult>();

        System.out.println();
        printLine('=', 100);
        System.out.println("  NovaLang 集成业务场景基准测试");
        System.out.println("  真实业务逻辑: 多算法融合 | 三方对比: 解释 vs 编译 vs Java 原生");
        System.out.println("  Warmup: " + WARMUP + " 轮, 测量: " + RUNS + " 轮取截尾均值");
        printLine('=', 100);
        System.out.println();

        results.add(benchEcommerce(1));
        results.add(benchStudentRanking(2));
        results.add(benchMatrixMultiply(3));
        results.add(benchBankTransaction(4));

        printSummaryTable(results);
    }

    // ============ 1. 电商订单处理 ============

    private BenchResult benchEcommerce(int idx) {
        System.out.printf("[%d/4] 电商订单处理 — 2000 商品过滤+折扣+税 ...%n", idx);

        // 融合: class 定义 + method(when 表达式) + for 循环 + if 过滤 + 算术
        String novaCode =
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return when (category) {\n" +
            "      0 -> price * 90 / 100\n" +
            "      2 -> price * 80 / 100\n" +
            "      else -> price\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "var totalPrice = 0\n" +
            "var itemCount = 0\n" +
            "for (i in 0..<2000) {\n" +
            "  val p = Product(10 + i % 50, i % 3)\n" +
            "  if (p.category != 1) {\n" +
            "    totalPrice = totalPrice + p.discountedPrice()\n" +
            "    itemCount = itemCount + 1\n" +
            "  }\n" +
            "}\n" +
            "val taxedTotal = totalPrice * 108 / 100\n" +
            "taxedTotal * 1000 + itemCount";

        String compiledCode =
            "class Product(val price: Int, val category: Int) {\n" +
            "  fun discountedPrice(): Int {\n" +
            "    return when (category) {\n" +
            "      0 -> price * 90 / 100\n" +
            "      2 -> price * 80 / 100\n" +
            "      else -> price\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    var totalPrice = 0\n" +
            "    var itemCount = 0\n" +
            "    for (i in 0..<2000) {\n" +
            "      val p = Product(10 + i % 50, i % 3)\n" +
            "      if (p.category != 1) {\n" +
            "        totalPrice = totalPrice + p.discountedPrice()\n" +
            "        itemCount = itemCount + 1\n" +
            "      }\n" +
            "    }\n" +
            "    val taxedTotal = totalPrice * 108 / 100\n" +
            "    return taxedTotal * 1000 + itemCount\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(IntegrationBenchmarkTest::javaEcommerceBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "电商订单结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "电商订单编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("电商订单处理", "2000 商品过滤+折扣+税", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    static class JavaProduct {
        final int price, category;
        JavaProduct(int price, int category) { this.price = price; this.category = category; }
        int discountedPrice() {
            switch (category) {
                case 0: return price * 90 / 100;
                case 2: return price * 80 / 100;
                default: return price;
            }
        }
    }

    private static int javaEcommerceBenchmark() {
        int totalPrice = 0;
        int itemCount = 0;
        for (int i = 0; i < 2000; i++) {
            JavaProduct p = new JavaProduct(10 + i % 50, i % 3);
            if (p.category != 1) {
                totalPrice += p.discountedPrice();
                itemCount++;
            }
        }
        int taxedTotal = totalPrice * 108 / 100;
        return taxedTotal * 1000 + itemCount;
    }

    // ============ 2. 学生成绩排名 ============

    private BenchResult benchStudentRanking(int idx) {
        System.out.printf("[%d/4] 学生成绩排名 — 500 学生排序+分级统计 ...%n", idx);

        // 融合: list 构建 + 冒泡排序(while 嵌套) + if/else 分级 + 聚合统计
        String novaCode =
            "val scores = []\n" +
            "for (i in 0..<500) {\n" +
            "  scores.add((i * 37 + 13) % 100)\n" +
            "}\n" +
            "var i = 0\n" +
            "while (i < 500) {\n" +
            "  var j = 0\n" +
            "  val limit = 500 - 1 - i\n" +
            "  while (j < limit) {\n" +
            "    if (scores[j] < scores[j + 1]) {\n" +
            "      val temp = scores[j]\n" +
            "      scores[j] = scores[j + 1]\n" +
            "      scores[j + 1] = temp\n" +
            "    }\n" +
            "    j = j + 1\n" +
            "  }\n" +
            "  i = i + 1\n" +
            "}\n" +
            "var gradeA = 0\n" +
            "var gradeB = 0\n" +
            "var gradeC = 0\n" +
            "var gradeD = 0\n" +
            "var gradeF = 0\n" +
            "var totalScore = 0\n" +
            "for (k in 0..<500) {\n" +
            "  val s = scores[k]\n" +
            "  totalScore = totalScore + s\n" +
            "  if (s >= 90) { gradeA = gradeA + 1 }\n" +
            "  else if (s >= 80) { gradeB = gradeB + 1 }\n" +
            "  else if (s >= 70) { gradeC = gradeC + 1 }\n" +
            "  else if (s >= 60) { gradeD = gradeD + 1 }\n" +
            "  else { gradeF = gradeF + 1 }\n" +
            "}\n" +
            "val topScore = scores[0]\n" +
            "val average = totalScore / 500\n" +
            "topScore * 100000 + average * 1000 + gradeA * 100 + gradeB";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    val scores = Array<Int>(500)\n" +
            "    var idx = 0\n" +
            "    while (idx < 500) {\n" +
            "      scores[idx] = (idx * 37 + 13) % 100\n" +
            "      idx = idx + 1\n" +
            "    }\n" +
            "    var i = 0\n" +
            "    while (i < 500) {\n" +
            "      var j = 0\n" +
            "      val limit = 500 - 1 - i\n" +
            "      while (j < limit) {\n" +
            "        if (scores[j] < scores[j + 1]) {\n" +
            "          val temp = scores[j]\n" +
            "          scores[j] = scores[j + 1]\n" +
            "          scores[j + 1] = temp\n" +
            "        }\n" +
            "        j = j + 1\n" +
            "      }\n" +
            "      i = i + 1\n" +
            "    }\n" +
            "    var gradeA = 0\n" +
            "    var gradeB = 0\n" +
            "    var gradeC = 0\n" +
            "    var gradeD = 0\n" +
            "    var gradeF = 0\n" +
            "    var totalScore = 0\n" +
            "    var k = 0\n" +
            "    while (k < 500) {\n" +
            "      val s = scores[k]\n" +
            "      totalScore = totalScore + s\n" +
            "      if (s >= 90) { gradeA = gradeA + 1 }\n" +
            "      else if (s >= 80) { gradeB = gradeB + 1 }\n" +
            "      else if (s >= 70) { gradeC = gradeC + 1 }\n" +
            "      else if (s >= 60) { gradeD = gradeD + 1 }\n" +
            "      else { gradeF = gradeF + 1 }\n" +
            "      k = k + 1\n" +
            "    }\n" +
            "    val topScore = scores[0]\n" +
            "    val average = totalScore / 500\n" +
            "    return topScore * 100000 + average * 1000 + gradeA * 100 + gradeB\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(IntegrationBenchmarkTest::javaStudentRankingBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "学生成绩结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "学生成绩编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("学生成绩排名", "500 学生排序+分级", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaStudentRankingBenchmark() {
        int[] scores = new int[500];
        for (int i = 0; i < 500; i++) {
            scores[i] = (i * 37 + 13) % 100;
        }
        // 冒泡排序（降序）
        for (int i = 0; i < 500; i++) {
            int limit = 500 - 1 - i;
            for (int j = 0; j < limit; j++) {
                if (scores[j] < scores[j + 1]) {
                    int temp = scores[j];
                    scores[j] = scores[j + 1];
                    scores[j + 1] = temp;
                }
            }
        }
        // 分级统计
        int gradeA = 0, gradeB = 0, gradeC = 0, gradeD = 0, gradeF = 0;
        int totalScore = 0;
        for (int k = 0; k < 500; k++) {
            int s = scores[k];
            totalScore += s;
            if (s >= 90) gradeA++;
            else if (s >= 80) gradeB++;
            else if (s >= 70) gradeC++;
            else if (s >= 60) gradeD++;
            else gradeF++;
        }
        int topScore = scores[0];
        int average = totalScore / 500;
        return topScore * 100000 + average * 1000 + gradeA * 100 + gradeB;
    }

    // ============ 3. 矩阵乘法 ============

    private BenchResult benchMatrixMultiply(int idx) {
        System.out.printf("[%d/4] 矩阵乘法 — 40x40 矩阵 C=A*B ...%n", idx);

        // 融合: 三重嵌套循环 + list 构建/索引 + 密集算术运算
        String novaCode =
            "val n = 40\n" +
            "val size = n * n\n" +
            "val a = []\n" +
            "val b = []\n" +
            "for (i in 0..<size) {\n" +
            "  a.add((i % 7) + 1)\n" +
            "  b.add((i % 5) + 1)\n" +
            "}\n" +
            "val c = []\n" +
            "var row = 0\n" +
            "while (row < n) {\n" +
            "  var col = 0\n" +
            "  while (col < n) {\n" +
            "    var sum = 0\n" +
            "    var k = 0\n" +
            "    while (k < n) {\n" +
            "      sum = sum + a[row * n + k] * b[k * n + col]\n" +
            "      k = k + 1\n" +
            "    }\n" +
            "    c.add(sum)\n" +
            "    col = col + 1\n" +
            "  }\n" +
            "  row = row + 1\n" +
            "}\n" +
            "var trace = 0\n" +
            "var d = 0\n" +
            "while (d < n) {\n" +
            "  trace = trace + c[d * n + d]\n" +
            "  d = d + 1\n" +
            "}\n" +
            "trace";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    val n = 40\n" +
            "    val size = n * n\n" +
            "    val a = Array<Int>(size)\n" +
            "    val b = Array<Int>(size)\n" +
            "    var idx = 0\n" +
            "    while (idx < size) {\n" +
            "      a[idx] = (idx % 7) + 1\n" +
            "      b[idx] = (idx % 5) + 1\n" +
            "      idx = idx + 1\n" +
            "    }\n" +
            "    val c = Array<Int>(size)\n" +
            "    var row = 0\n" +
            "    while (row < n) {\n" +
            "      var col = 0\n" +
            "      while (col < n) {\n" +
            "        var sum = 0\n" +
            "        var k = 0\n" +
            "        while (k < n) {\n" +
            "          sum = sum + a[row * n + k] * b[k * n + col]\n" +
            "          k = k + 1\n" +
            "        }\n" +
            "        c[row * n + col] = sum\n" +
            "        col = col + 1\n" +
            "      }\n" +
            "      row = row + 1\n" +
            "    }\n" +
            "    var trace = 0\n" +
            "    var d = 0\n" +
            "    while (d < n) {\n" +
            "      trace = trace + c[d * n + d]\n" +
            "      d = d + 1\n" +
            "    }\n" +
            "    return trace\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(IntegrationBenchmarkTest::javaMatrixBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "矩阵乘法结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "矩阵乘法编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("矩阵乘法", "40x40 C=A*B", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaMatrixBenchmark() {
        int n = 40;
        int size = n * n;
        int[] a = new int[size];
        int[] b = new int[size];
        for (int i = 0; i < size; i++) {
            a[i] = (i % 7) + 1;
            b[i] = (i % 5) + 1;
        }
        int[] c = new int[size];
        for (int row = 0; row < n; row++) {
            for (int col = 0; col < n; col++) {
                int sum = 0;
                for (int k = 0; k < n; k++) {
                    sum += a[row * n + k] * b[k * n + col];
                }
                c[row * n + col] = sum;
            }
        }
        int trace = 0;
        for (int d = 0; d < n; d++) {
            trace += c[d * n + d];
        }
        return trace;
    }

    // ============ 4. 银行交易模拟 ============

    private BenchResult benchBankTransaction(int idx) {
        System.out.printf("[%d/4] 银行交易模拟 — 100 账户 5000 笔交易 ...%n", idx);

        // 融合: list 构建/读写 + for 循环 + if/else 多分支 + 余额校验 + 算术
        String novaCode =
            "val accounts = []\n" +
            "for (i in 0..<100) {\n" +
            "  accounts.add(1000 + i * 10)\n" +
            "}\n" +
            "var totalVolume = 0\n" +
            "var successCount = 0\n" +
            "for (t in 0..<5000) {\n" +
            "  val fromIdx = t % 100\n" +
            "  val toIdx = (t * 7 + 13) % 100\n" +
            "  val amount = (t % 30) + 1\n" +
            "  val txType = t % 3\n" +
            "  if (txType == 0) {\n" +
            "    accounts[fromIdx] = accounts[fromIdx] + amount\n" +
            "    totalVolume = totalVolume + amount\n" +
            "    successCount = successCount + 1\n" +
            "  } else if (txType == 1) {\n" +
            "    if (accounts[fromIdx] >= amount) {\n" +
            "      accounts[fromIdx] = accounts[fromIdx] - amount\n" +
            "      totalVolume = totalVolume + amount\n" +
            "      successCount = successCount + 1\n" +
            "    }\n" +
            "  } else {\n" +
            "    if (fromIdx != toIdx) {\n" +
            "      if (accounts[fromIdx] >= amount) {\n" +
            "        accounts[fromIdx] = accounts[fromIdx] - amount\n" +
            "        accounts[toIdx] = accounts[toIdx] + amount\n" +
            "        totalVolume = totalVolume + amount\n" +
            "        successCount = successCount + 1\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}\n" +
            "var totalBalance = 0\n" +
            "for (i in 0..<100) {\n" +
            "  totalBalance = totalBalance + accounts[i]\n" +
            "}\n" +
            "totalBalance * 100 + successCount";

        String compiledCode =
            "object Bench {\n" +
            "  fun run(): Int {\n" +
            "    val accounts = Array<Int>(100)\n" +
            "    var idx = 0\n" +
            "    while (idx < 100) {\n" +
            "      accounts[idx] = 1000 + idx * 10\n" +
            "      idx = idx + 1\n" +
            "    }\n" +
            "    var totalVolume = 0\n" +
            "    var successCount = 0\n" +
            "    var t = 0\n" +
            "    while (t < 5000) {\n" +
            "      val fromIdx = t % 100\n" +
            "      val toIdx = (t * 7 + 13) % 100\n" +
            "      val amount = (t % 30) + 1\n" +
            "      val txType = t % 3\n" +
            "      if (txType == 0) {\n" +
            "        accounts[fromIdx] = accounts[fromIdx] + amount\n" +
            "        totalVolume = totalVolume + amount\n" +
            "        successCount = successCount + 1\n" +
            "      } else if (txType == 1) {\n" +
            "        if (accounts[fromIdx] >= amount) {\n" +
            "          accounts[fromIdx] = accounts[fromIdx] - amount\n" +
            "          totalVolume = totalVolume + amount\n" +
            "          successCount = successCount + 1\n" +
            "        }\n" +
            "      } else {\n" +
            "        if (fromIdx != toIdx) {\n" +
            "          if (accounts[fromIdx] >= amount) {\n" +
            "            accounts[fromIdx] = accounts[fromIdx] - amount\n" +
            "            accounts[toIdx] = accounts[toIdx] + amount\n" +
            "            totalVolume = totalVolume + amount\n" +
            "            successCount = successCount + 1\n" +
            "          }\n" +
            "        }\n" +
            "      }\n" +
            "      t = t + 1\n" +
            "    }\n" +
            "    var totalBalance = 0\n" +
            "    var tb = 0\n" +
            "    while (tb < 100) {\n" +
            "      totalBalance = totalBalance + accounts[tb]\n" +
            "      tb = tb + 1\n" +
            "    }\n" +
            "    return totalBalance * 100 + successCount\n" +
            "  }\n" +
            "}";

        Object[] nova = measureNova(novaCode);
        Object[] execOnly = measureNovaExecOnly(novaCode);
        Object[] compiled = measureCompiled(compiledCode, "Bench", "run");
        Object[] java = measureJava(IntegrationBenchmarkTest::javaBankBenchmark);

        double novaMs = (double) nova[0];
        double execOnlyMs = (double) execOnly[0];
        double compiledMs = (double) compiled[0];
        double javaMs = (double) java[0];
        double novaCv = (double) nova[2];
        double execOnlyCv = (double) execOnly[2];
        int novaResult = ((NovaValue) nova[1]).asInt();
        int javaResult = (int) java[1];

        assertEquals(javaResult, novaResult, "银行交易结果不一致");
        if (compiledMs >= 0 && compiled[1] != null) {
            assertEquals(javaResult, compiled[1], "银行交易编译结果不一致");
        }
        printBenchResult(novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
        return new BenchResult("银行交易模拟", "100 账户 5000 笔交易", novaMs, execOnlyMs, compiledMs, javaMs, novaCv, execOnlyCv);
    }

    private static int javaBankBenchmark() {
        int[] accounts = new int[100];
        for (int i = 0; i < 100; i++) {
            accounts[i] = 1000 + i * 10;
        }
        int totalVolume = 0;
        int successCount = 0;
        for (int t = 0; t < 5000; t++) {
            int fromIdx = t % 100;
            int toIdx = (t * 7 + 13) % 100;
            int amount = (t % 30) + 1;
            int txType = t % 3;
            if (txType == 0) {
                accounts[fromIdx] += amount;
                totalVolume += amount;
                successCount++;
            } else if (txType == 1) {
                if (accounts[fromIdx] >= amount) {
                    accounts[fromIdx] -= amount;
                    totalVolume += amount;
                    successCount++;
                }
            } else {
                if (fromIdx != toIdx) {
                    if (accounts[fromIdx] >= amount) {
                        accounts[fromIdx] -= amount;
                        accounts[toIdx] += amount;
                        totalVolume += amount;
                        successCount++;
                    }
                }
            }
        }
        int totalBalance = 0;
        for (int i = 0; i < 100; i++) {
            totalBalance += accounts[i];
        }
        return totalBalance * 100 + successCount;
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
        int nameColWidth = 8;
        for (BenchResult r : results) {
            nameColWidth = Math.max(nameColWidth, displayWidth(r.name));
        }
        nameColWidth += 2;

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
