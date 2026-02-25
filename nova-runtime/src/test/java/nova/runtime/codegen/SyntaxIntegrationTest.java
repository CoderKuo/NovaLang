package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语法集成测试：解释器和编译器双路径执行，验证结果一致。
 *
 * <p>覆盖 Bug 10-14 修复的语法特性：
 * <ul>
 *   <li>SET 字面量 #{}</li>
 *   <li>循环标签 label@ for/while/do</li>
 *   <li>扩展函数接收器类型 List&lt;Int&gt;.sum()</li>
 *   <li>属性 getter/setter</li>
 *   <li>修饰符排他性（仅解析器检查）</li>
 * </ul>
 */
@DisplayName("语法集成测试: 解释器 vs 编译器")
class SyntaxIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    // ============ 辅助方法 ============

    /**
     * 解释器路径执行
     */
    private NovaValue interp(String code) {
        Interpreter interpreter = new Interpreter();
        return interpreter.eval(code, "test.nova");
    }

    /**
     * 编译器路径执行：代码包在 object Test { fun run(): ... } 中
     */
    private Object compile(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> testClass = loaded.get("Test");
        assertNotNull(testClass, "编译后应生成 Test 类");
        Object instance = testClass.getField("INSTANCE").get(null);
        Method runMethod = testClass.getDeclaredMethod("run");
        runMethod.setAccessible(true);
        return runMethod.invoke(instance);
    }

    /**
     * 双路径执行并断言结果一致
     *
     * @param interpCode  解释器执行的完整代码（最后一个表达式作为结果）
     * @param compileCode 编译器执行的代码（包含 object Test { fun run() = ... }）
     * @param expected    期望值
     */
    private void assertBothPaths(String interpCode, String compileCode, Object expected) throws Exception {
        NovaValue interpResult = interp(interpCode);
        Object compiledResult = compile(compileCode);

        if (expected instanceof Integer) {
            assertEquals(expected, interpResult.asInt(), "解释器结果不匹配");
            assertEquals(expected, compiledResult, "编译器结果不匹配");
        } else if (expected instanceof String) {
            assertEquals(expected, interpResult.asString(), "解释器结果不匹配");
            assertEquals(expected, String.valueOf(compiledResult), "编译器结果不匹配");
        } else if (expected instanceof Boolean) {
            assertEquals(expected, interpResult.asBoolean(), "解释器结果不匹配");
            assertEquals(expected, compiledResult, "编译器结果不匹配");
        } else {
            assertEquals(String.valueOf(expected), interpResult.asString(), "解释器结果不匹配");
            assertEquals(String.valueOf(expected), String.valueOf(compiledResult), "编译器结果不匹配");
        }
    }

    // ============ SET 字面量 ============

    @Nested
    @DisplayName("SET 字面量 #{}")
    class SetLiteralTests {

        @Test
        @DisplayName("基本 SET 字面量创建和大小")
        void testSetLiteralSize() throws Exception {
            assertBothPaths(
                "val s = #{1, 2, 3, 2, 1}\ns.size()",
                "object Test {\n  fun run(): Any {\n    val s = #{1, 2, 3, 2, 1}\n    return s.size()\n  }\n}",
                3
            );
        }

        @Test
        @DisplayName("SET 字面量 contains 检查")
        void testSetContains() throws Exception {
            assertBothPaths(
                "val s = #{\"a\", \"b\", \"c\"}\ns.contains(\"b\")",
                "object Test {\n  fun run(): Any {\n    val s = #{\"a\", \"b\", \"c\"}\n    return s.contains(\"b\")\n  }\n}",
                true
            );
        }

        @Test
        @DisplayName("SET 字面量去重")
        void testSetDedup() throws Exception {
            assertBothPaths(
                "val s = #{1, 1, 1, 1}\ns.size()",
                "object Test {\n  fun run(): Any {\n    val s = #{1, 1, 1, 1}\n    return s.size()\n  }\n}",
                1
            );
        }

        @Test
        @DisplayName("空 SET 字面量")
        void testEmptySet() throws Exception {
            String interpCode = "val s = #{}\ns.size()";
            // 空 #{} 解析为空 SET
            NovaValue interpResult = interp(interpCode);
            assertEquals(0, interpResult.asInt());
        }

        @Test
        @DisplayName("MAP 字面量正常工作")
        void testMapLiteral() throws Exception {
            assertBothPaths(
                "val m = #{\"a\": 1, \"b\": 2}\nm.size()",
                "object Test {\n  fun run(): Any {\n    val m = #{\"a\": 1, \"b\": 2}\n    return m.size()\n  }\n}",
                2
            );
        }

        @Test
        @DisplayName("MAP 字面量值访问")
        void testMapGet() throws Exception {
            assertBothPaths(
                "val m = #{\"x\": 42}\nm[\"x\"]",
                "object Test {\n  fun run(): Any {\n    val m = #{\"x\": 42}\n    return m[\"x\"]\n  }\n}",
                42
            );
        }
    }

    // ============ 循环标签 ============

    @Nested
    @DisplayName("循环标签 label@")
    class LoopLabelTests {

        @Test
        @DisplayName("break@label 跳出外层 for 循环")
        void testBreakOuterFor() throws Exception {
            String logic =
                "var result = 0\n" +
                "outer@ for (i in 1..3) {\n" +
                "  for (j in 1..3) {\n" +
                "    if (j == 2) break@outer\n" +
                "    result = result + i * 10 + j\n" +
                "  }\n" +
                "}\n" +
                "result";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    var result = 0\n" +
                "    outer@ for (i in 1..3) {\n" +
                "      for (j in 1..3) {\n" +
                "        if (j == 2) break@outer\n" +
                "        result = result + i * 10 + j\n" +
                "      }\n" +
                "    }\n" +
                "    return result\n" +
                "  }\n}";
            // i=1, j=1 → result=11, j=2 → break@outer → 停止
            assertBothPaths(logic, compileCode, 11);
        }

        @Test
        @DisplayName("continue@label 继续外层 for 循环")
        void testContinueOuterFor() throws Exception {
            String logic =
                "var result = 0\n" +
                "outer@ for (i in 1..3) {\n" +
                "  for (j in 1..3) {\n" +
                "    if (j == 2) continue@outer\n" +
                "    result = result + 1\n" +
                "  }\n" +
                "}\n" +
                "result";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    var result = 0\n" +
                "    outer@ for (i in 1..3) {\n" +
                "      for (j in 1..3) {\n" +
                "        if (j == 2) continue@outer\n" +
                "        result = result + 1\n" +
                "      }\n" +
                "    }\n" +
                "    return result\n" +
                "  }\n}";
            // 每次 i 迭代中 j=1 通过(+1), j=2 → continue@outer → 跳过 j=3
            // i=1: +1, i=2: +1, i=3: +1 = 3
            assertBothPaths(logic, compileCode, 3);
        }

        @Test
        @DisplayName("break@label 跳出外层 while 循环")
        void testBreakOuterWhile() throws Exception {
            String logic =
                "var i = 0\n" +
                "var count = 0\n" +
                "outer@ while (i < 5) {\n" +
                "  var j = 0\n" +
                "  while (j < 5) {\n" +
                "    if (i == 2 && j == 1) break@outer\n" +
                "    j = j + 1\n" +
                "    count = count + 1\n" +
                "  }\n" +
                "  i = i + 1\n" +
                "}\n" +
                "count";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    var i = 0\n" +
                "    var count = 0\n" +
                "    outer@ while (i < 5) {\n" +
                "      var j = 0\n" +
                "      while (j < 5) {\n" +
                "        if (i == 2 && j == 1) break@outer\n" +
                "        j = j + 1\n" +
                "        count = count + 1\n" +
                "      }\n" +
                "      i = i + 1\n" +
                "    }\n" +
                "    return count\n" +
                "  }\n}";
            // i=0: j 循环 5 次(count=5), i=1: j 循环 5 次(count=10), i=2: j=0→j+1,count=11, j=1→break@outer
            assertBothPaths(logic, compileCode, 11);
        }

        @Test
        @DisplayName("无标签循环正常工作")
        void testUnlabeledLoop() throws Exception {
            String logic =
                "var sum = 0\n" +
                "for (i in 1..5) {\n" +
                "  sum = sum + i\n" +
                "}\n" +
                "sum";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    var sum = 0\n" +
                "    for (i in 1..5) {\n" +
                "      sum = sum + i\n" +
                "    }\n" +
                "    return sum\n" +
                "  }\n}";
            assertBothPaths(logic, compileCode, 15);
        }
    }

    // ============ 扩展函数 ============

    @Nested
    @DisplayName("扩展函数接收器类型")
    class ExtensionFunctionTests {

        @Test
        @DisplayName("简单扩展函数")
        void testSimpleExtension() throws Exception {
            String interpCode =
                "fun String.exclaim() = this + \"!\"\n" +
                "\"hello\".exclaim()";
            String compileCode =
                "fun String.exclaim() = this + \"!\"\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    return \"hello\".exclaim()\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, "hello!");
        }

        @Test
        @DisplayName("带参数的扩展函数")
        void testExtensionWithParam() throws Exception {
            String interpCode =
                "fun String.repeat(n: Int): String {\n" +
                "  var result = \"\"\n" +
                "  for (i in 1..n) {\n" +
                "    result = result + this\n" +
                "  }\n" +
                "  return result\n" +
                "}\n" +
                "\"ab\".repeat(3)";
            String compileCode =
                "fun String.repeat(n: Int): String {\n" +
                "  var result = \"\"\n" +
                "  for (i in 1..n) {\n" +
                "    result = result + this\n" +
                "  }\n" +
                "  return result\n" +
                "}\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    return \"ab\".repeat(3)\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, "ababab");
        }

        @Test
        @DisplayName("Int 类型扩展函数")
        void testIntExtension() throws Exception {
            String interpCode =
                "fun Int.doubled() = this * 2\n" +
                "5.doubled()";
            String compileCode =
                "fun Int.doubled() = this * 2\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    return 5.doubled()\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 10);
        }
    }

    // ============ 属性 getter/setter ============

    @Nested
    @DisplayName("属性 getter/setter")
    class PropertyAccessorTests {

        @Test
        @DisplayName("自定义 getter（表达式体）")
        void testCustomGetterExpression() throws Exception {
            String interpCode =
                "class Rect(val width: Int, val height: Int) {\n" +
                "  val area: Int\n" +
                "    get() = width * height\n" +
                "}\n" +
                "val r = Rect(3, 4)\n" +
                "r.area";
            String compileCode =
                "class Rect(val width: Int, val height: Int) {\n" +
                "  val area: Int\n" +
                "    get() = width * height\n" +
                "}\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    val r = Rect(3, 4)\n" +
                "    return r.area\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 12);
        }

        @Test
        @DisplayName("自定义 getter（块体）")
        void testCustomGetterBlock() throws Exception {
            String interpCode =
                "class Temp(val celsius: Int) {\n" +
                "  val fahrenheit: Int\n" +
                "    get() {\n" +
                "      return celsius * 9 / 5 + 32\n" +
                "    }\n" +
                "}\n" +
                "val t = Temp(100)\n" +
                "t.fahrenheit";
            String compileCode =
                "class Temp(val celsius: Int) {\n" +
                "  val fahrenheit: Int\n" +
                "    get() {\n" +
                "      return celsius * 9 / 5 + 32\n" +
                "    }\n" +
                "}\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    val t = Temp(100)\n" +
                "    return t.fahrenheit\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 212);
        }

        @Test
        @DisplayName("自定义 setter")
        void testCustomSetter() throws Exception {
            String interpCode =
                "class Counter {\n" +
                "  var value: Int = 0\n" +
                "  var count: Int = 0\n" +
                "    set(v) {\n" +
                "      value = v * 2\n" +
                "    }\n" +
                "}\n" +
                "val c = Counter()\n" +
                "c.count = 5\n" +
                "c.value";
            String compileCode =
                "class Counter {\n" +
                "  var value: Int = 0\n" +
                "  var count: Int = 0\n" +
                "    set(v) {\n" +
                "      value = v * 2\n" +
                "    }\n" +
                "}\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    val c = Counter()\n" +
                "    c.count = 5\n" +
                "    return c.value\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 10);
        }

        @Test
        @DisplayName("getter + setter 组合")
        void testGetterAndSetter() throws Exception {
            String interpCode =
                "class Clamped {\n" +
                "  var raw: Int = 0\n" +
                "  var value: Int = 0\n" +
                "    get() = raw\n" +
                "    set(v) {\n" +
                "      raw = if (v > 100) 100 else if (v < 0) 0 else v\n" +
                "    }\n" +
                "}\n" +
                "val c = Clamped()\n" +
                "c.value = 150\n" +
                "c.value";
            String compileCode =
                "class Clamped {\n" +
                "  var raw: Int = 0\n" +
                "  var value: Int = 0\n" +
                "    get() = raw\n" +
                "    set(v) {\n" +
                "      raw = if (v > 100) 100 else if (v < 0) 0 else v\n" +
                "    }\n" +
                "}\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    val c = Clamped()\n" +
                "    c.value = 150\n" +
                "    return c.value\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 100);
        }

        @Test
        @DisplayName("无 accessor 的普通属性仍正常工作")
        void testPlainProperty() throws Exception {
            String interpCode =
                "class Box(var x: Int)\n" +
                "val b = Box(10)\n" +
                "b.x = 20\n" +
                "b.x";
            String compileCode =
                "class Box(var x: Int)\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    val b = Box(10)\n" +
                "    b.x = 20\n" +
                "    return b.x\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 20);
        }
    }

    // ============ 综合场景 ============

    @Nested
    @DisplayName("综合场景")
    class CombinedTests {

        @Test
        @DisplayName("SET + for 循环迭代")
        void testSetIteration() throws Exception {
            String interpCode =
                "val s = #{10, 20, 30}\n" +
                "var sum = 0\n" +
                "for (x in s) {\n" +
                "  sum = sum + x\n" +
                "}\n" +
                "sum";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    val s = #{10, 20, 30}\n" +
                "    var sum = 0\n" +
                "    for (x in s) {\n" +
                "      sum = sum + x\n" +
                "    }\n" +
                "    return sum\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 60);
        }

        @Test
        @DisplayName("标签循环 + 扩展函数")
        void testLabelLoopWithExtension() throws Exception {
            String interpCode =
                "fun Int.isEven() = this % 2 == 0\n" +
                "var firstEven = -1\n" +
                "search@ for (i in 1..10) {\n" +
                "  if (i.isEven()) {\n" +
                "    firstEven = i\n" +
                "    break@search\n" +
                "  }\n" +
                "}\n" +
                "firstEven";
            String compileCode =
                "fun Int.isEven() = this % 2 == 0\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    var firstEven = -1\n" +
                "    search@ for (i in 1..10) {\n" +
                "      if (i.isEven()) {\n" +
                "        firstEven = i\n" +
                "        break@search\n" +
                "      }\n" +
                "    }\n" +
                "    return firstEven\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 2);
        }

        @Test
        @DisplayName("类 + 自定义 getter + 方法调用")
        void testClassWithGetter() throws Exception {
            String interpCode =
                "class Circle(val radius: Int) {\n" +
                "  val diameter: Int\n" +
                "    get() = radius * 2\n" +
                "  fun describe(): String = \"Circle(d=\" + diameter + \")\"\n" +
                "}\n" +
                "val c = Circle(5)\n" +
                "c.describe()";
            String compileCode =
                "class Circle(val radius: Int) {\n" +
                "  val diameter: Int\n" +
                "    get() = radius * 2\n" +
                "  fun describe(): String = \"Circle(d=\" + diameter + \")\"\n" +
                "}\n" +
                "object Test {\n  fun run(): Any {\n" +
                "    val c = Circle(5)\n" +
                "    return c.describe()\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, "Circle(d=10)");
        }

        @Test
        @DisplayName("嵌套循环 + 标签 + 累加")
        void testNestedLabeledLoops() throws Exception {
            String logic =
                "var sum = 0\n" +
                "outer@ for (i in 1..4) {\n" +
                "  for (j in 1..4) {\n" +
                "    if (i + j > 5) continue@outer\n" +
                "    sum = sum + i + j\n" +
                "  }\n" +
                "}\n" +
                "sum";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    var sum = 0\n" +
                "    outer@ for (i in 1..4) {\n" +
                "      for (j in 1..4) {\n" +
                "        if (i + j > 5) continue@outer\n" +
                "        sum = sum + i + j\n" +
                "      }\n" +
                "    }\n" +
                "    return sum\n" +
                "  }\n}";
            // i=1: j=1(2),j=2(3),j=3(4),j=4(5) → sum=14
            // i=2: j=1(3),j=2(4),j=3(5) → j=4 → 6>5 continue@outer → sum=14+12=26
            // i=3: j=1(4),j=2(5) → j=3 → 6>5 continue@outer → sum=26+9=35
            // i=4: j=1(5) → j=2 → 6>5 continue@outer → sum=35+5=40
            assertBothPaths(logic, compileCode, 40);
        }

        @Test
        @DisplayName("MAP 字面量 + 迭代")
        void testMapIteration() throws Exception {
            String interpCode =
                "val m = #{\"a\": 1, \"b\": 2, \"c\": 3}\n" +
                "var sum = 0\n" +
                "for ((k, v) in m) {\n" +
                "  sum = sum + v\n" +
                "}\n" +
                "sum";
            String compileCode =
                "object Test {\n  fun run(): Any {\n" +
                "    val m = #{\"a\": 1, \"b\": 2, \"c\": 3}\n" +
                "    var sum = 0\n" +
                "    for ((k, v) in m) {\n" +
                "      sum = sum + v\n" +
                "    }\n" +
                "    return sum\n" +
                "  }\n}";
            assertBothPaths(interpCode, compileCode, 6);
        }
    }
}
