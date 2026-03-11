package com.novalang.bench;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class ScriptScenarios {

    private static final Map<String, ScriptScenario> SCENARIOS = buildScenarios();

    private ScriptScenarios() {
    }

    static ScriptScenario byName(String name) {
        ScriptScenario scenario = SCENARIOS.get(name);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown scenario: " + name);
        }
        return scenario;
    }

    static Map<String, ScriptScenario> all() {
        return SCENARIOS;
    }

    private static Map<String, ScriptScenario> buildScenarios() {
        Map<String, ScriptScenario> scenarios = new LinkedHashMap<String, ScriptScenario>();
        scenarios.put("arith_loop", new ScriptScenario(
                "arith_loop",
                "integer arithmetic hot loop",
                novaArithLoop(),
                jsArithLoop(),
                groovyArithLoop(),
                jexlArithLoop(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaArithLoop();
                    }
                }));
        scenarios.put("call_loop", new ScriptScenario(
                "call_loop",
                "small function call hot loop",
                novaCallLoop(),
                jsCallLoop(),
                groovyCallLoop(),
                jexlCallLoop(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaCallLoop();
                    }
                }));
        scenarios.put("object_loop", new ScriptScenario(
                "object_loop",
                "object allocation and method dispatch",
                novaObjectLoop(),
                jsObjectLoop(),
                groovyObjectLoop(),
                jexlObjectLoop(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaObjectLoop();
                    }
                }));
        scenarios.put("branch_loop", new ScriptScenario(
                "branch_loop",
                "branch-heavy integer classification loop",
                novaBranchLoop(),
                jsBranchLoop(),
                groovyBranchLoop(),
                jexlBranchLoop(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaBranchLoop();
                    }
                }));
        scenarios.put("string_concat", new ScriptScenario(
                "string_concat",
                "string concatenation and length",
                novaStringConcat(),
                jsStringConcat(),
                groovyStringConcat(),
                jexlStringConcat(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaStringConcat();
                    }
                }));
        scenarios.put("list_sum", new ScriptScenario(
                "list_sum",
                "list append and indexed sum",
                novaListSum(),
                jsListSum(),
                groovyListSum(),
                jexlListSum(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaListSum();
                    }
                }));
        scenarios.put("fib_recursion", new ScriptScenario(
                "fib_recursion",
                "recursive fibonacci with varying inputs",
                novaFibRecursion(),
                jsFibRecursion(),
                groovyFibRecursion(),
                jexlFibRecursion(),
                new java.util.function.IntSupplier() {
                    @Override
                    public int getAsInt() {
                        return javaFibRecursionBenchmark();
                    }
                }));
        return Collections.unmodifiableMap(scenarios);
    }

    // ========== Nova sources ==========

    private static String novaArithLoop() {
        return "fun run(): Int {\n"
                + "  var s = 0\n"
                + "  for (i in 0..<20000) {\n"
                + "    s = s + i * 2 - 1\n"
                + "  }\n"
                + "  return s\n"
                + "}\n"
                + "run()";
    }

    private static String novaCallLoop() {
        return "fun inc(n: Int): Int = n + 1\n"
                + "fun run(): Int {\n"
                + "  var s = 0\n"
                + "  for (i in 0..<50000) {\n"
                + "    s = s + inc(i)\n"
                + "  }\n"
                + "  return s\n"
                + "}\n"
                + "run()";
    }

    private static String novaObjectLoop() {
        return "class Pt(var x: Int, var y: Int) {\n"
                + "  fun sum(): Int = x + y\n"
                + "}\n"
                + "fun run(): Int {\n"
                + "  var total = 0\n"
                + "  for (i in 0..<5000) {\n"
                + "    val p = Pt(i, i * 2)\n"
                + "    total = total + p.sum()\n"
                + "  }\n"
                + "  return total\n"
                + "}\n"
                + "run()";
    }

    private static String novaBranchLoop() {
        return "fun classify(n: Int): Int {\n"
                + "  if (n % 15 == 0) return 3\n"
                + "  if (n % 5 == 0) return 2\n"
                + "  if (n % 3 == 0) return 1\n"
                + "  return 0\n"
                + "}\n"
                + "fun run(): Int {\n"
                + "  var sum = 0\n"
                + "  for (i in 1..20000) {\n"
                + "    sum = sum + classify(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    private static String novaStringConcat() {
        return "fun run(): Int {\n"
                + "  var s = \"\"\n"
                + "  for (i in 0..<3000) {\n"
                + "    s = s + \"ab\" + i\n"
                + "  }\n"
                + "  return s.length()\n"
                + "}\n"
                + "run()";
    }

    private static String novaListSum() {
        return "fun run(): Int {\n"
                + "  var list = []\n"
                + "  for (i in 0..<3000) {\n"
                + "    list.add(i)\n"
                + "  }\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<3000) {\n"
                + "    sum = sum + list[i]\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    private static String novaFibRecursion() {
        return "fun fib(n: Int): Int {\n"
                + "  if (n <= 1) return n\n"
                + "  return fib(n - 1) + fib(n - 2)\n"
                + "}\n"
                + "fun run(): Int {\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<20) {\n"
                + "    val n = 18 + (i % 5)\n"
                + "    sum = sum + fib(n)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    // ========== JavaScript sources ==========

    private static String jsArithLoop() {
        return "function run() {\n"
                + "  var s = 0;\n"
                + "  for (var i = 0; i < 20000; i++) {\n"
                + "    s = s + i * 2 - 1;\n"
                + "  }\n"
                + "  return s;\n"
                + "}\n"
                + "run();";
    }

    private static String jsCallLoop() {
        return "function inc(n) {\n"
                + "  return n + 1;\n"
                + "}\n"
                + "function run() {\n"
                + "  var s = 0;\n"
                + "  for (var i = 0; i < 50000; i++) {\n"
                + "    s = s + inc(i);\n"
                + "  }\n"
                + "  return s;\n"
                + "}\n"
                + "run();";
    }

    private static String jsObjectLoop() {
        return "function Pt(x, y) {\n"
                + "  this.x = x;\n"
                + "  this.y = y;\n"
                + "}\n"
                + "Pt.prototype.sum = function() {\n"
                + "  return this.x + this.y;\n"
                + "};\n"
                + "function run() {\n"
                + "  var total = 0;\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    var p = new Pt(i, i * 2);\n"
                + "    total = total + p.sum();\n"
                + "  }\n"
                + "  return total;\n"
                + "}\n"
                + "run();";
    }

    private static String jsBranchLoop() {
        return "function classify(n) {\n"
                + "  if (n % 15 === 0) return 3;\n"
                + "  if (n % 5 === 0) return 2;\n"
                + "  if (n % 3 === 0) return 1;\n"
                + "  return 0;\n"
                + "}\n"
                + "function run() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 1; i <= 20000; i++) {\n"
                + "    sum = sum + classify(i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    private static String jsStringConcat() {
        return "function run() {\n"
                + "  var s = \"\";\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    s = s + \"ab\" + i;\n"
                + "  }\n"
                + "  return s.length;\n"
                + "}\n"
                + "run();";
    }

    private static String jsListSum() {
        return "function run() {\n"
                + "  var list = [];\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    list.push(i);\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var j = 0; j < 3000; j++) {\n"
                + "    sum = sum + list[j];\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    private static String jsFibRecursion() {
        return "function fib(n) {\n"
                + "  if (n <= 1) return n;\n"
                + "  return fib(n - 1) + fib(n - 2);\n"
                + "}\n"
                + "function run() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 20; i++) {\n"
                + "    var n = 18 + (i % 5);\n"
                + "    sum = sum + fib(n);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    // ========== Groovy sources ==========

    private static String groovyArithLoop() {
        return "int execute() {\n"
                + "  int s = 0\n"
                + "  for (int i = 0; i < 20000; i++) {\n"
                + "    s = s + i * 2 - 1\n"
                + "  }\n"
                + "  return s\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyCallLoop() {
        return "int inc(int n) { return n + 1 }\n"
                + "int execute() {\n"
                + "  int s = 0\n"
                + "  for (int i = 0; i < 50000; i++) {\n"
                + "    s = s + inc(i)\n"
                + "  }\n"
                + "  return s\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyObjectLoop() {
        return "class Pt {\n"
                + "  int x\n"
                + "  int y\n"
                + "  Pt(int x, int y) { this.x = x; this.y = y }\n"
                + "  int sum() { return x + y }\n"
                + "}\n"
                + "int execute() {\n"
                + "  int total = 0\n"
                + "  for (int i = 0; i < 5000; i++) {\n"
                + "    Pt p = new Pt(i, i * 2)\n"
                + "    total = total + p.sum()\n"
                + "  }\n"
                + "  return total\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyBranchLoop() {
        return "int classify(int n) {\n"
                + "  if (n % 15 == 0) return 3\n"
                + "  if (n % 5 == 0) return 2\n"
                + "  if (n % 3 == 0) return 1\n"
                + "  return 0\n"
                + "}\n"
                + "int execute() {\n"
                + "  int sum = 0\n"
                + "  for (int i = 1; i <= 20000; i++) {\n"
                + "    sum = sum + classify(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyStringConcat() {
        return "int execute() {\n"
                + "  String s = \"\"\n"
                + "  for (int i = 0; i < 3000; i++) {\n"
                + "    s = s + \"ab\" + i\n"
                + "  }\n"
                + "  return s.length()\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyListSum() {
        return "int execute() {\n"
                + "  def list = []\n"
                + "  for (int i = 0; i < 3000; i++) {\n"
                + "    list.add(i)\n"
                + "  }\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < 3000; i++) {\n"
                + "    sum = sum + (int) list.get(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyFibRecursion() {
        return "int fib(int n) {\n"
                + "  if (n <= 1) return n\n"
                + "  return fib(n - 1) + fib(n - 2)\n"
                + "}\n"
                + "int execute() {\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < 20; i++) {\n"
                + "    int n = 18 + (i % 5)\n"
                + "    sum = sum + fib(n)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    // ========== JEXL sources ==========

    private static String jexlArithLoop() {
        return "var run = function() {\n"
                + "  var s = 0;\n"
                + "  for (var i = 0; i < 20000; i = i + 1) {\n"
                + "    s = s + i * 2 - 1;\n"
                + "  }\n"
                + "  return s;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlCallLoop() {
        return "var inc = function(n) { return n + 1; };\n"
                + "var run = function() {\n"
                + "  var s = 0;\n"
                + "  for (var i = 0; i < 50000; i = i + 1) {\n"
                + "    s = s + inc(i);\n"
                + "  }\n"
                + "  return s;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlObjectLoop() {
        // JEXL 不支持类定义，使用 Map 模拟对象创建和属性访问
        return "var run = function() {\n"
                + "  var total = 0;\n"
                + "  for (var i = 0; i < 5000; i = i + 1) {\n"
                + "    var p = { 'x' : i, 'y' : i * 2 };\n"
                + "    total = total + p.x + p.y;\n"
                + "  }\n"
                + "  return total;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlBranchLoop() {
        return "var classify = function(n) {\n"
                + "  if (n % 15 == 0) { return 3; }\n"
                + "  if (n % 5 == 0) { return 2; }\n"
                + "  if (n % 3 == 0) { return 1; }\n"
                + "  return 0;\n"
                + "};\n"
                + "var run = function() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 1; i <= 20000; i = i + 1) {\n"
                + "    sum = sum + classify(i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlStringConcat() {
        return "var run = function() {\n"
                + "  var s = '';\n"
                + "  for (var i = 0; i < 3000; i = i + 1) {\n"
                + "    s = s + 'ab' + i;\n"
                + "  }\n"
                + "  return s.length();\n"
                + "};\n"
                + "run();";
    }

    private static String jexlListSum() {
        return "var run = function() {\n"
                + "  var list = new('java.util.ArrayList');\n"
                + "  for (var i = 0; i < 3000; i = i + 1) {\n"
                + "    list.add(i);\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 3000; i = i + 1) {\n"
                + "    sum = sum + list[i];\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlFibRecursion() {
        return "var fib = function(n) {\n"
                + "  if (n <= 1) { return n; }\n"
                + "  return fib(n - 1) + fib(n - 2);\n"
                + "};\n"
                + "var run = function() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 20; i = i + 1) {\n"
                + "    var n = 18 + (i % 5);\n"
                + "    sum = sum + fib(n);\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    // ========== Java native implementations ==========

    private static int javaArithLoop() {
        int s = 0;
        for (int i = 0; i < 20000; i++) {
            s = s + i * 2 - 1;
        }
        return s;
    }

    private static int javaCallLoop() {
        int s = 0;
        for (int i = 0; i < 50000; i++) {
            s = s + javaInc(i);
        }
        return s;
    }

    private static int javaInc(int n) {
        return n + 1;
    }

    private static int javaObjectLoop() {
        int total = 0;
        for (int i = 0; i < 5000; i++) {
            JavaPt p = new JavaPt(i, i * 2);
            total = total + p.sum();
        }
        return total;
    }

    private static int javaBranchLoop() {
        int sum = 0;
        for (int i = 1; i <= 20000; i++) {
            sum = sum + javaClassify(i);
        }
        return sum;
    }

    private static int javaClassify(int n) {
        if (n % 15 == 0) return 3;
        if (n % 5 == 0) return 2;
        if (n % 3 == 0) return 1;
        return 0;
    }

    private static int javaStringConcat() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append("ab").append(i);
        }
        return sb.length();
    }

    private static int javaListSum() {
        java.util.ArrayList<Integer> list = new java.util.ArrayList<Integer>();
        for (int i = 0; i < 3000; i++) {
            list.add(Integer.valueOf(i));
        }
        int sum = 0;
        for (int i = 0; i < 3000; i++) {
            sum = sum + list.get(i).intValue();
        }
        return sum;
    }

    private static int javaFibRecursionBenchmark() {
        int sum = 0;
        for (int i = 0; i < 20; i++) {
            int n = 18 + (i % 5);
            sum = sum + javaFib(n);
        }
        return sum;
    }

    private static int javaFib(int n) {
        if (n <= 1) {
            return n;
        }
        return javaFib(n - 1) + javaFib(n - 2);
    }

    private static final class JavaPt {
        private final int x;
        private final int y;

        private JavaPt(int x, int y) {
            this.x = x;
            this.y = y;
        }

        private int sum() {
            return x + y;
        }
    }
}
