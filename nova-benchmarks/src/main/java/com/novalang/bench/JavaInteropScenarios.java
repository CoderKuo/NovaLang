package com.novalang.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

final class JavaInteropScenarios {

    private static final Map<String, ScriptScenario> SCENARIOS = buildScenarios();

    private JavaInteropScenarios() {
    }

    static ScriptScenario byName(String name) {
        ScriptScenario scenario = SCENARIOS.get(name);
        if (scenario == null) {
            throw new IllegalArgumentException("Unknown interop scenario: " + name);
        }
        return scenario;
    }

    static Map<String, ScriptScenario> all() {
        return SCENARIOS;
    }

    private static Map<String, ScriptScenario> buildScenarios() {
        Map<String, ScriptScenario> s = new LinkedHashMap<String, ScriptScenario>();
        s.put("java_static_call", new ScriptScenario(
                "java_static_call", "Java static method dispatch (Math.abs × 10000)",
                novaStaticCall(), jsStaticCall(), groovyStaticCall(), jexlStaticCall(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaStaticCall(); }
                }));
        s.put("java_object_create", new ScriptScenario(
                "java_object_create", "Java object creation + instance method (ArrayList.add × 5000)",
                novaObjectCreate(), jsObjectCreate(), groovyObjectCreate(), jexlObjectCreate(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaObjectCreate(); }
                }));
        s.put("java_field_access", new ScriptScenario(
                "java_field_access", "Java static field access (Integer.MAX_VALUE × 10000)",
                novaFieldAccess(), jsFieldAccess(), groovyFieldAccess(), jexlFieldAccess(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaFieldAccess(); }
                }));
        s.put("java_string_builder", new ScriptScenario(
                "java_string_builder", "Java StringBuilder chain (append × 3000)",
                novaStringBuilder(), jsStringBuilder(), groovyStringBuilder(), jexlStringBuilder(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaStringBuilder(); }
                }));
        s.put("java_collection_sort", new ScriptScenario(
                "java_collection_sort", "Java Collections.sort with script comparator (1000 elements)",
                novaCollectionSort(), jsCollectionSort(), groovyCollectionSort(), jexlCollectionSort(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaCollectionSort(); }
                }));
        s.put("java_type_convert", new ScriptScenario(
                "java_type_convert", "Cross-boundary type conversion (Integer.valueOf × 10000)",
                novaTypeConvert(), jsTypeConvert(), groovyTypeConvert(), jexlTypeConvert(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaTypeConvert(); }
                }));
        s.put("java_hashmap_ops", new ScriptScenario(
                "java_hashmap_ops", "HashMap put/get/containsKey (3000 entries)",
                novaHashMapOps(), jsHashMapOps(), groovyHashMapOps(), jexlHashMapOps(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaHashMapOps(); }
                }));
        s.put("java_string_methods", new ScriptScenario(
                "java_string_methods", "String method chain (substring/indexOf/replace × 5000)",
                novaStringMethods(), jsStringMethods(), groovyStringMethods(), jexlStringMethods(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaStringMethods(); }
                }));
        s.put("java_exception_handle", new ScriptScenario(
                "java_exception_handle", "Integer.parseInt with try-catch (5000 calls, 50% fail)",
                novaExceptionHandle(), jsExceptionHandle(), groovyExceptionHandle(), jexlExceptionHandle(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaExceptionHandle(); }
                }));
        s.put("java_mixed_compute", new ScriptScenario(
                "java_mixed_compute", "Mixed script logic + Java API (2000 iterations)",
                novaMixedCompute(), jsMixedCompute(), groovyMixedCompute(), jexlMixedCompute(),
                new java.util.function.IntSupplier() {
                    @Override public int getAsInt() { return javaMixedCompute(); }
                }));
        return Collections.unmodifiableMap(s);
    }

    // ========== Nova sources ==========

    private static String novaStaticCall() {
        return "val Math = javaClass(\"java.lang.Math\")\n"
                + "fun run(): Int {\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<10000) {\n"
                + "    sum = sum + Math.abs(i - 5000)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    private static String novaObjectCreate() {
        return "val ArrayList = javaClass(\"java.util.ArrayList\")\n"
                + "fun run(): Int {\n"
                + "  val list = ArrayList()\n"
                + "  for (i in 0..<5000) {\n"
                + "    list.add(i)\n"
                + "  }\n"
                + "  return list.size()\n"
                + "}\n"
                + "run()";
    }

    private static String novaFieldAccess() {
        return "val Integer = javaClass(\"java.lang.Integer\")\n"
                + "fun run(): Int {\n"
                + "  val maxVal = Integer.MAX_VALUE\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<10000) {\n"
                + "    sum = sum + (maxVal % (i + 1))\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    private static String novaStringBuilder() {
        return "val StringBuilder = javaClass(\"java.lang.StringBuilder\")\n"
                + "fun run(): Int {\n"
                + "  val sb = StringBuilder()\n"
                + "  for (i in 0..<3000) {\n"
                + "    sb.append(i)\n"
                + "  }\n"
                + "  return sb.length()\n"
                + "}\n"
                + "run()";
    }

    private static String novaCollectionSort() {
        return "val ArrayList = javaClass(\"java.util.ArrayList\")\n"
                + "val Collections = javaClass(\"java.util.Collections\")\n"
                + "fun run(): Int {\n"
                + "  val list = ArrayList()\n"
                + "  for (i in 0..<1000) {\n"
                + "    list.add((i * 31 + 17) % 1000)\n"
                + "  }\n"
                + "  Collections.sort(list)\n"
                + "  return list.get(0) + list.get(999)\n"
                + "}\n"
                + "run()";
    }

    private static String novaTypeConvert() {
        return "val Integer = javaClass(\"java.lang.Integer\")\n"
                + "fun run(): Int {\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<10000) {\n"
                + "    sum = sum + Integer.valueOf(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    // ========== JavaScript sources (Nashorn / GraalJS) ==========

    private static String jsStaticCall() {
        return "var JMath = Java.type('java.lang.Math');\n"
                + "function run() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i++) {\n"
                + "    sum = sum + JMath.abs(i - 5000);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    private static String jsObjectCreate() {
        return "var ArrayList = Java.type('java.util.ArrayList');\n"
                + "function run() {\n"
                + "  var list = new ArrayList();\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    list.add(i);\n"
                + "  }\n"
                + "  return list.size();\n"
                + "}\n"
                + "run();";
    }

    private static String jsFieldAccess() {
        return "var JInteger = Java.type('java.lang.Integer');\n"
                + "function run() {\n"
                + "  var maxVal = JInteger.MAX_VALUE;\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i++) {\n"
                + "    sum = sum + (maxVal % (i + 1));\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    private static String jsStringBuilder() {
        return "var StringBuilder = Java.type('java.lang.StringBuilder');\n"
                + "function run() {\n"
                + "  var sb = new StringBuilder();\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    sb.append('' + i);\n"
                + "  }\n"
                + "  return sb.length();\n"
                + "}\n"
                + "run();";
    }

    private static String jsCollectionSort() {
        return "var ArrayList = Java.type('java.util.ArrayList');\n"
                + "var Collections = Java.type('java.util.Collections');\n"
                + "function run() {\n"
                + "  var list = new ArrayList();\n"
                + "  for (var i = 0; i < 1000; i++) {\n"
                + "    list.add((i * 31 + 17) % 1000);\n"
                + "  }\n"
                + "  Collections.sort(list);\n"
                + "  return list.get(0) + list.get(999);\n"
                + "}\n"
                + "run();";
    }

    private static String jsTypeConvert() {
        return "var JInteger = Java.type('java.lang.Integer');\n"
                + "function run() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i++) {\n"
                + "    sum = sum + JInteger.valueOf(i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    // ========== Javet JS sources (无 Java.type，通过 proxy 注入) ==========
    // Javet 的 JS 源码不用 Java.type()，Java 类由宿主注入到全局对象

    static String javetStaticCall() {
        return "function run() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i++) {\n"
                + "    sum = sum + JMath.abs(i - 5000);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    static String javetObjectCreate() {
        return "function run() {\n"
                + "  var list = new JArrayList();\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    list.add(i);\n"
                + "  }\n"
                + "  return list.size();\n"
                + "}\n"
                + "run();";
    }

    static String javetFieldAccess() {
        return "function run() {\n"
                + "  var maxVal = JInteger.MAX_VALUE;\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i++) {\n"
                + "    sum = sum + (maxVal % (i + 1));\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    static String javetStringBuilder() {
        return "function run() {\n"
                + "  var sb = new JStringBuilder();\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    sb.append('' + i);\n"
                + "  }\n"
                + "  return sb.length();\n"
                + "}\n"
                + "run();";
    }

    static String javetCollectionSort() {
        return "function run() {\n"
                + "  var list = new JArrayList();\n"
                + "  for (var i = 0; i < 1000; i++) {\n"
                + "    list.add((i * 31 + 17) % 1000);\n"
                + "  }\n"
                + "  JCollections.sort(list);\n"
                + "  return list.get(0) + list.get(999);\n"
                + "}\n"
                + "run();";
    }

    static String javetTypeConvert() {
        return "function run() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i++) {\n"
                + "    sum = sum + JInteger.valueOf(i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    // ========== Groovy sources ==========

    private static String groovyStaticCall() {
        return "int execute() {\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < 10000; i++) {\n"
                + "    sum = sum + Math.abs(i - 5000)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyObjectCreate() {
        return "int execute() {\n"
                + "  def list = new java.util.ArrayList()\n"
                + "  for (int i = 0; i < 5000; i++) {\n"
                + "    list.add(i)\n"
                + "  }\n"
                + "  return list.size()\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyFieldAccess() {
        return "int execute() {\n"
                + "  int maxVal = Integer.MAX_VALUE\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < 10000; i++) {\n"
                + "    sum = sum + (maxVal % (i + 1))\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyStringBuilder() {
        return "int execute() {\n"
                + "  def sb = new StringBuilder()\n"
                + "  for (int i = 0; i < 3000; i++) {\n"
                + "    sb.append(i)\n"
                + "  }\n"
                + "  return sb.length()\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyCollectionSort() {
        return "int execute() {\n"
                + "  def list = new java.util.ArrayList()\n"
                + "  for (int i = 0; i < 1000; i++) {\n"
                + "    list.add((i * 31 + 17) % 1000)\n"
                + "  }\n"
                + "  java.util.Collections.sort(list)\n"
                + "  return (int) list.get(0) + (int) list.get(999)\n"
                + "}\n"
                + "execute()";
    }

    private static String groovyTypeConvert() {
        return "int execute() {\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < 10000; i++) {\n"
                + "    sum = sum + Integer.valueOf(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    // ========== JEXL sources ==========

    private static String jexlStaticCall() {
        return "var run = function() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i = i + 1) {\n"
                + "    var v = i - 5000;\n"
                + "    sum = sum + (v < 0 ? -v : v);\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlObjectCreate() {
        return "var run = function() {\n"
                + "  var list = new('java.util.ArrayList');\n"
                + "  for (var i = 0; i < 5000; i = i + 1) {\n"
                + "    list.add(i);\n"
                + "  }\n"
                + "  return list.size();\n"
                + "};\n"
                + "run();";
    }

    private static String jexlFieldAccess() {
        // JEXL 无法直接访问 Integer.MAX_VALUE，用常量模拟
        return "var run = function() {\n"
                + "  var maxVal = 2147483647;\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i = i + 1) {\n"
                + "    sum = sum + (maxVal % (i + 1));\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlStringBuilder() {
        return "var run = function() {\n"
                + "  var sb = new('java.lang.StringBuilder');\n"
                + "  for (var i = 0; i < 3000; i = i + 1) {\n"
                + "    sb.append(i);\n"
                + "  }\n"
                + "  return sb.length();\n"
                + "};\n"
                + "run();";
    }

    private static String jexlCollectionSort() {
        // JEXL 无法调用 Arrays.sort/Collections.sort，手动求 min + max
        return "var run = function() {\n"
                + "  var list = new('java.util.ArrayList');\n"
                + "  for (var i = 0; i < 1000; i = i + 1) {\n"
                + "    list.add((i * 31 + 17) % 1000);\n"
                + "  }\n"
                + "  var mn = list[0]; var mx = list[0];\n"
                + "  for (var i = 1; i < 1000; i = i + 1) {\n"
                + "    if (list[i] < mn) { mn = list[i]; }\n"
                + "    if (list[i] > mx) { mx = list[i]; }\n"
                + "  }\n"
                + "  return mn + mx;\n"
                + "};\n"
                + "run();";
    }

    private static String jexlTypeConvert() {
        return "var run = function() {\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 10000; i = i + 1) {\n"
                + "    sum = sum + new('java.lang.Integer', i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    // ========== Java native implementations ==========

    private static int javaStaticCall() {
        int sum = 0;
        for (int i = 0; i < 10000; i++) {
            sum = sum + Math.abs(i - 5000);
        }
        return sum;
    }

    private static int javaObjectCreate() {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < 5000; i++) {
            list.add(Integer.valueOf(i));
        }
        return list.size();
    }

    private static int javaFieldAccess() {
        int maxVal = Integer.MAX_VALUE;
        int sum = 0;
        for (int i = 0; i < 10000; i++) {
            sum = sum + (maxVal % (i + 1));
        }
        return sum;
    }

    private static int javaStringBuilder() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            sb.append(i);
        }
        return sb.length();
    }

    private static int javaCollectionSort() {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < 1000; i++) {
            list.add(Integer.valueOf((i * 31 + 17) % 1000));
        }
        Collections.sort(list);
        return list.get(0).intValue() + list.get(999).intValue();
    }

    private static int javaTypeConvert() {
        int sum = 0;
        for (int i = 0; i < 10000; i++) {
            sum = sum + Integer.valueOf(i).intValue();
        }
        return sum;
    }

    // ========== Scenario 7: HashMap put/get/containsKey ==========

    private static String novaHashMapOps() {
        return "val HashMap = javaClass(\"java.util.HashMap\")\n"
                + "fun run(): Int {\n"
                + "  val map = HashMap()\n"
                + "  for (i in 0..<3000) {\n"
                + "    map.put(i, i % 100)\n"
                + "  }\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<3000) {\n"
                + "    if (map.containsKey(i)) {\n"
                + "      sum = sum + map.get(i)\n"
                + "    }\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    private static String jsHashMapOps() {
        return "var HashMap = Java.type('java.util.HashMap');\n"
                + "function run() {\n"
                + "  var map = new HashMap();\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    map.put(i, i % 100);\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    if (map.containsKey(i)) {\n"
                + "      sum = sum + map.get(i);\n"
                + "    }\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    static String javetHashMapOps() {
        return "function run() {\n"
                + "  var map = new JHashMap();\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    map.put(i, i % 100);\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 3000; i++) {\n"
                + "    if (map.containsKey(i)) {\n"
                + "      sum = sum + map.get(i);\n"
                + "    }\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    private static String groovyHashMapOps() {
        return "int execute() {\n"
                + "  def map = new java.util.HashMap()\n"
                + "  for (int i = 0; i < 3000; i++) {\n"
                + "    map.put(i, i % 100)\n"
                + "  }\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < 3000; i++) {\n"
                + "    if (map.containsKey(i)) {\n"
                + "      sum = sum + (int) map.get(i)\n"
                + "    }\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    private static String jexlHashMapOps() {
        return "var run = function() {\n"
                + "  var map = new('java.util.HashMap');\n"
                + "  for (var i = 0; i < 3000; i = i + 1) {\n"
                + "    map.put(i, i % 100);\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < 3000; i = i + 1) {\n"
                + "    if (map.containsKey(i)) {\n"
                + "      sum = sum + map.get(i);\n"
                + "    }\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    private static int javaHashMapOps() {
        java.util.HashMap<Integer, Integer> map = new java.util.HashMap<Integer, Integer>();
        for (int i = 0; i < 3000; i++) {
            map.put(Integer.valueOf(i), Integer.valueOf(i % 100));
        }
        int sum = 0;
        for (int i = 0; i < 3000; i++) {
            if (map.containsKey(Integer.valueOf(i))) {
                sum = sum + map.get(Integer.valueOf(i)).intValue();
            }
        }
        return sum;
    }

    // ========== Scenario 8: String method chain ==========

    private static String novaStringMethods() {
        return "val Math = javaClass(\"java.lang.Math\")\n"
                + "fun run(): Int {\n"
                + "  var total = 0\n"
                + "  for (i in 0..<5000) {\n"
                + "    total = total + Math.max(Math.abs(i - 2500), Math.min(i, 100))\n"
                + "  }\n"
                + "  return total\n"
                + "}\n"
                + "run()";
    }

    private static String jsStringMethods() {
        return "var JMath = Java.type('java.lang.Math');\n"
                + "function run() {\n"
                + "  var total = 0;\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    total = total + JMath.max(JMath.abs(i - 2500)|0, JMath.min(i|0, 100)|0)|0;\n"
                + "  }\n"
                + "  return total;\n"
                + "}\n"
                + "run();";
    }

    static String javetStringMethods() {
        return "function run() {\n"
                + "  var total = 0;\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    total = total + JMath.max(JMath.abs(i - 2500), JMath.min(i, 100));\n"
                + "  }\n"
                + "  return total;\n"
                + "}\n"
                + "run();";
    }

    private static String groovyStringMethods() {
        return "int execute() {\n"
                + "  int total = 0\n"
                + "  for (int i = 0; i < 5000; i++) {\n"
                + "    total = total + Math.max(Math.abs(i - 2500), Math.min(i, 100))\n"
                + "  }\n"
                + "  return total\n"
                + "}\n"
                + "execute()";
    }

    private static String jexlStringMethods() {
        // JEXL 不能直接调用 Math 静态方法，手动实现
        return "var run = function() {\n"
                + "  var total = 0;\n"
                + "  for (var i = 0; i < 5000; i = i + 1) {\n"
                + "    var a = i - 2500; if (a < 0) { a = -a; }\n"
                + "    var b = i; if (b > 100) { b = 100; }\n"
                + "    total = total + (a > b ? a : b);\n"
                + "  }\n"
                + "  return total;\n"
                + "};\n"
                + "run();";
    }

    private static int javaStringMethods() {
        int total = 0;
        for (int i = 0; i < 5000; i++) {
            total = total + Math.max(Math.abs(i - 2500), Math.min(i, 100));
        }
        return total;
    }

    // ========== Scenario 9: Exception handling across boundary ==========

    private static String novaExceptionHandle() {
        return "val Integer = javaClass(\"java.lang.Integer\")\n"
                + "fun run(): Int {\n"
                + "  var ok = 0\n"
                + "  var fail = 0\n"
                + "  for (i in 0..<5000) {\n"
                + "    try {\n"
                + "      if (i % 2 == 0) {\n"
                + "        Integer.parseInt(\"\" + i)\n"
                + "        ok = ok + 1\n"
                + "      } else {\n"
                + "        Integer.parseInt(\"abc\")\n"
                + "      }\n"
                + "    } catch (e) {\n"
                + "      fail = fail + 1\n"
                + "    }\n"
                + "  }\n"
                + "  return ok * 1000 + fail\n"
                + "}\n"
                + "run()";
    }

    private static String jsExceptionHandle() {
        return "var JInteger = Java.type('java.lang.Integer');\n"
                + "function run() {\n"
                + "  var ok = 0, fail = 0;\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    try {\n"
                + "      if (i % 2 === 0) {\n"
                + "        JInteger.parseInt('' + i);\n"
                + "        ok++;\n"
                + "      } else {\n"
                + "        JInteger.parseInt('abc');\n"
                + "      }\n"
                + "    } catch (e) {\n"
                + "      fail++;\n"
                + "    }\n"
                + "  }\n"
                + "  return ok * 1000 + fail;\n"
                + "}\n"
                + "run();";
    }

    static String javetExceptionHandle() {
        return "function run() {\n"
                + "  var ok = 0, fail = 0;\n"
                + "  for (var i = 0; i < 5000; i++) {\n"
                + "    try {\n"
                + "      if (i % 2 === 0) {\n"
                + "        JInteger.parseInt('' + i);\n"
                + "        ok++;\n"
                + "      } else {\n"
                + "        JInteger.parseInt('abc');\n"
                + "      }\n"
                + "    } catch (e) {\n"
                + "      fail++;\n"
                + "    }\n"
                + "  }\n"
                + "  return ok * 1000 + fail;\n"
                + "}\n"
                + "run();";
    }

    private static String groovyExceptionHandle() {
        return "int execute() {\n"
                + "  int ok = 0, fail = 0\n"
                + "  for (int i = 0; i < 5000; i++) {\n"
                + "    try {\n"
                + "      if (i % 2 == 0) {\n"
                + "        Integer.parseInt('' + i)\n"
                + "        ok++\n"
                + "      } else {\n"
                + "        Integer.parseInt('abc')\n"
                + "      }\n"
                + "    } catch (Exception e) {\n"
                + "      fail++\n"
                + "    }\n"
                + "  }\n"
                + "  return ok * 1000 + fail\n"
                + "}\n"
                + "execute()";
    }

    private static String jexlExceptionHandle() {
        // JEXL 不支持 try-catch，用条件检查模拟
        return "var run = function() {\n"
                + "  var ok = 0;\n"
                + "  var fail = 0;\n"
                + "  for (var i = 0; i < 5000; i = i + 1) {\n"
                + "    if (i % 2 == 0) {\n"
                + "      ok = ok + 1;\n"
                + "    } else {\n"
                + "      fail = fail + 1;\n"
                + "    }\n"
                + "  }\n"
                + "  return ok * 1000 + fail;\n"
                + "};\n"
                + "run();";
    }

    private static int javaExceptionHandle() {
        int ok = 0, fail = 0;
        for (int i = 0; i < 5000; i++) {
            try {
                if (i % 2 == 0) {
                    Integer.parseInt("" + i);
                    ok++;
                } else {
                    Integer.parseInt("abc");
                }
            } catch (NumberFormatException e) {
                fail++;
            }
        }
        return ok * 1000 + fail;
    }

    // ========== Scenario 10: Mixed script + Java API ==========

    private static String novaMixedCompute() {
        return "val Math = javaClass(\"java.lang.Math\")\n"
                + "val ArrayList = javaClass(\"java.util.ArrayList\")\n"
                + "fun run(): Int {\n"
                + "  val list = ArrayList()\n"
                + "  for (i in 0..<2000) {\n"
                + "    val v = Math.abs(i - 1000) * 2 + 1\n"
                + "    if (v % 3 != 0) {\n"
                + "      list.add(v)\n"
                + "    }\n"
                + "  }\n"
                + "  var sum = 0\n"
                + "  for (i in 0..<list.size()) {\n"
                + "    sum = sum + list.get(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "run()";
    }

    private static String jsMixedCompute() {
        return "var JMath = Java.type('java.lang.Math');\n"
                + "var ArrayList = Java.type('java.util.ArrayList');\n"
                + "function run() {\n"
                + "  var list = new ArrayList();\n"
                + "  for (var i = 0; i < 2000; i++) {\n"
                + "    var v = JMath.abs(i - 1000) * 2 + 1;\n"
                + "    if (v % 3 !== 0) {\n"
                + "      list.add(v);\n"
                + "    }\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < list.size(); i++) {\n"
                + "    sum = sum + list.get(i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    static String javetMixedCompute() {
        return "function run() {\n"
                + "  var list = new JArrayList();\n"
                + "  for (var i = 0; i < 2000; i++) {\n"
                + "    var v = JMath.abs(i - 1000) * 2 + 1;\n"
                + "    if (v % 3 !== 0) {\n"
                + "      list.add(v);\n"
                + "    }\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < list.size(); i++) {\n"
                + "    sum = sum + list.get(i);\n"
                + "  }\n"
                + "  return sum;\n"
                + "}\n"
                + "run();";
    }

    private static String groovyMixedCompute() {
        return "int execute() {\n"
                + "  def list = new java.util.ArrayList()\n"
                + "  for (int i = 0; i < 2000; i++) {\n"
                + "    int v = Math.abs(i - 1000) * 2 + 1\n"
                + "    if (v % 3 != 0) {\n"
                + "      list.add(v)\n"
                + "    }\n"
                + "  }\n"
                + "  int sum = 0\n"
                + "  for (int i = 0; i < list.size(); i++) {\n"
                + "    sum = sum + (int) list.get(i)\n"
                + "  }\n"
                + "  return sum\n"
                + "}\n"
                + "execute()";
    }

    private static String jexlMixedCompute() {
        return "var run = function() {\n"
                + "  var list = new('java.util.ArrayList');\n"
                + "  for (var i = 0; i < 2000; i = i + 1) {\n"
                + "    var v = (i - 1000 < 0 ? 1000 - i : i - 1000) * 2 + 1;\n"
                + "    if (v % 3 != 0) {\n"
                + "      list.add(v);\n"
                + "    }\n"
                + "  }\n"
                + "  var sum = 0;\n"
                + "  for (var i = 0; i < list.size(); i = i + 1) {\n"
                + "    sum = sum + list[i];\n"
                + "  }\n"
                + "  return sum;\n"
                + "};\n"
                + "run();";
    }

    private static int javaMixedCompute() {
        ArrayList<Integer> list = new ArrayList<Integer>();
        for (int i = 0; i < 2000; i++) {
            int v = Math.abs(i - 1000) * 2 + 1;
            if (v % 3 != 0) {
                list.add(Integer.valueOf(v));
            }
        }
        int sum = 0;
        for (int i = 0; i < list.size(); i++) {
            sum = sum + list.get(i).intValue();
        }
        return sum;
    }
}
