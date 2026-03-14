package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.*;
import nova.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心语法集成测试：字面量、运算符、控制流、声明。
 * 每个测试同时执行解释器和编译器路径，验证结果一致。
 */
@DisplayName("核心语法集成测试")
class CoreSyntaxIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private NovaValue interp(String code) {
        return new Interpreter().eval(code, "test.nova");
    }

    private Object compile(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> c = loaded.get("Test");
        assertNotNull(c, "编译后应生成 Test 类");
        Object inst = c.getField("INSTANCE").get(null);
        Method m = c.getDeclaredMethod("run");
        m.setAccessible(true);
        return m.invoke(inst);
    }

    /** 包装为编译器可执行代码 */
    private String wrap(String body) {
        return "object Test {\n  fun run(): Any {\n" + body + "\n  }\n}";
    }

    private void dual(String interpCode, String compileBody, Object expected) throws Exception {
        NovaValue ir = interp(interpCode);
        Object cr = compile(compileBody);
        if (expected instanceof Integer) {
            assertEquals(expected, ir.asInt(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Boolean) {
            assertEquals(expected, ir.asBoolean(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Long) {
            assertEquals(expected, ir.asLong(), "解释器");
            assertEquals(expected, cr, "编译器");
        } else if (expected instanceof Double) {
            assertEquals((Double) expected, ir.asDouble(), 0.001, "解释器");
            assertEquals((Double) expected, ((Number) cr).doubleValue(), 0.001, "编译器");
        } else {
            assertEquals(String.valueOf(expected), ir.asString(), "解释器");
            assertEquals(String.valueOf(expected), String.valueOf(cr), "编译器");
        }
    }

    // ============ 字面量 ============

    @Nested
    @DisplayName("字面量")
    class LiteralTests {

        @Test void testInt() throws Exception {
            dual("42", wrap("return 42"), 42);
        }

        @Test void testNegativeInt() throws Exception {
            dual("-7", wrap("return -7"), -7);
        }

        @Test void testLong() throws Exception {
            dual("100L", wrap("return 100L"), 100L);
        }

        @Test void testDouble() throws Exception {
            dual("3.14", wrap("return 3.14"), 3.14);
        }

        @Test void testBooleanTrue() throws Exception {
            dual("true", wrap("return true"), true);
        }

        @Test void testBooleanFalse() throws Exception {
            dual("false", wrap("return false"), false);
        }

        @Test void testString() throws Exception {
            dual("\"hello\"", wrap("return \"hello\""), "hello");
        }

        @Test void testStringInterpolation() throws Exception {
            dual("val x = 42\n\"val=$x\"",
                 wrap("val x = 42\n    return \"val=$x\""), "val=42");
        }

        @Test void testStringInterpolationExpr() throws Exception {
            dual("val a = 3\n\"r=${a * 2}\"",
                 wrap("val a = 3\n    return \"r=${a * 2}\""), "r=6");
        }

        @Test void testNull() throws Exception {
            NovaValue ir = interp("null");
            assertTrue(ir.isNull());
        }

        @Test void testCharLiteral() throws Exception {
            dual("'A'", wrap("return 'A'"), "A");
        }
    }

    @Nested
    @DisplayName("对象方法")
    class ObjectMethodTests {

        @Test void testEqAliasOnInt() throws Exception {
            dual("1.eq(1)", wrap("return 1.eq(1)"), true);
        }

        @Test void testEqAliasOnDataClass() throws Exception {
            String logic =
                "@data class Point(val x: Int, val y: Int)\n" +
                "val a = Point(1, 2)\n" +
                "val b = Point(1, 2)\n" +
                "a.eq(b)";
            String compileCode =
                "@data class Point(val x: Int, val y: Int)\n" +
                wrap("val a = Point(1, 2)\n    val b = Point(1, 2)\n    return a.eq(b)");
            dual(logic, compileCode, true);
        }
    }

    // ============ object 单例 ============

    @Nested
    @DisplayName("object 单例")
    class ObjectSingletonTests {

        // ---- 正常值 ----

        @Test void objectFieldAccess() throws Exception {
            String obj = "object Counter {\n  var count = 0\n  fun inc() { count = count + 1 }\n}\n";
            dual(obj + "Counter.inc()\nCounter.inc()\nCounter.count",
                 obj + wrap("Counter.inc()\n    Counter.inc()\n    return Counter.count"), 2);
        }

        @Test void objectMethodCall() throws Exception {
            String obj = "object MathHelper {\n  fun square(x: Int) = x * x\n}\n";
            dual(obj + "MathHelper.square(5)",
                 obj + wrap("return MathHelper.square(5)"), 25);
        }

        @Test void objectWithValField() throws Exception {
            String obj = "object Config {\n  val name = \"Nova\"\n}\n";
            dual(obj + "Config.name",
                 obj + wrap("return Config.name"), "Nova");
        }

        // ---- 边缘值 ----

        @Test void objectFieldMutateMultipleTimes() throws Exception {
            String obj = "object Acc {\n  var total = 0\n  fun add(n: Int) { total = total + n }\n}\n";
            dual(obj + "Acc.add(10)\nAcc.add(20)\nAcc.add(30)\nAcc.total",
                 obj + wrap("Acc.add(10)\n    Acc.add(20)\n    Acc.add(30)\n    return Acc.total"), 60);
        }

        @Test void objectFieldInitExpression() throws Exception {
            String obj = "object Nums {\n  val sum = 1 + 2 + 3\n}\n";
            dual(obj + "Nums.sum",
                 obj + wrap("return Nums.sum"), 6);
        }

        @Test void objectStringInterpolation() throws Exception {
            String obj = "object Info {\n  val version = \"1.0\"\n  var hits = 0\n  fun hit() { hits = hits + 1 }\n}\n";
            dual(obj + "Info.hit()\nInfo.hit()\n\"v${Info.version}:${Info.hits}\"",
                 obj + wrap("Info.hit()\n    Info.hit()\n    return \"v${Info.version}:${Info.hits}\""), "v1.0:2");
        }

        // ---- 前缀自增 ----

        @Test void objectPrefixIncrement_nextReturnValue() throws Exception {
            // ++count 返回值正确
            String obj = "object Counter {\n  var count = 0\n  fun next() = ++count\n}\n";
            dual(obj + "Counter.next()",
                 obj + wrap("return Counter.next()"), 1);
        }

        @Test void objectPrefixIncrement_fieldAfterCall() throws Exception {
            String obj = "object Counter {\n  var count = 0\n  fun next() = ++count\n}\n";
            dual(obj + "Counter.next()\nCounter.count",
                 obj + wrap("Counter.next()\n    return Counter.count"), 1);
        }

        @Test void objectPostfixIncrement_fieldAfterCall() throws Exception {
            String obj = "object Counter {\n  var count = 0\n  fun next() = count++\n}\n";
            dual(obj + "Counter.next()\nCounter.count",
                 obj + wrap("Counter.next()\n    return Counter.count"), 1);
        }

        // ---- 多 object 交互 ----

        @Test void multipleObjects() throws Exception {
            String objs = "object A {\n  val x = 10\n}\nobject B {\n  val y = 20\n}\n";
            dual(objs + "A.x + B.y",
                 objs + wrap("return A.x + B.y"), 30);
        }
    }

    // ============ 算术运算符 ============

    @Nested
    @DisplayName("算术运算符")
    class ArithmeticTests {

        @Test void testAdd() throws Exception {
            dual("3 + 4", wrap("return 3 + 4"), 7);
        }

        @Test void testSub() throws Exception {
            dual("10 - 3", wrap("return 10 - 3"), 7);
        }

        @Test void testMul() throws Exception {
            dual("6 * 7", wrap("return 6 * 7"), 42);
        }

        @Test void testDiv() throws Exception {
            dual("20 / 4", wrap("return 20 / 4"), 5);
        }

        @Test void testMod() throws Exception {
            dual("17 % 5", wrap("return 17 % 5"), 2);
        }

        @Test void testUnaryMinus() throws Exception {
            dual("val x = 5\n-x", wrap("val x = 5\n    return -x"), -5);
        }

        @Test void testMixedPrecedence() throws Exception {
            dual("2 + 3 * 4", wrap("return 2 + 3 * 4"), 14);
        }

        @Test void testParentheses() throws Exception {
            dual("(2 + 3) * 4", wrap("return (2 + 3) * 4"), 20);
        }

        @Test void testStringConcat() throws Exception {
            dual("\"a\" + \"b\"", wrap("return \"a\" + \"b\""), "ab");
        }

        @Test void testIncrement() throws Exception {
            dual("var x = 5\nx++\nx", wrap("var x = 5\n    x++\n    return x"), 6);
        }

        @Test void testDecrement() throws Exception {
            dual("var x = 5\nx--\nx", wrap("var x = 5\n    x--\n    return x"), 4);
        }
    }

    // ============ 动态类型二元运算（Object 操作数） ============

    @Nested
    @DisplayName("动态类型二元运算")
    class DynamicBinaryOpTests {

        // ---- Issue 1: Object 类型位运算（isUnknownObjectType + BAND/BOR/BXOR） ----

        @Test void bitwiseAnd_onAnyType() throws Exception {
            dual("val a: Any = 7\nval b: Any = 3\na & b",
                 wrap("val a: Any = 7\n    val b: Any = 3\n    return a & b"), 3);
        }

        @Test void bitwiseOr_onAnyType() throws Exception {
            dual("val a: Any = 5\nval b: Any = 3\na | b",
                 wrap("val a: Any = 5\n    val b: Any = 3\n    return a | b"), 7);
        }

        @Test void bitwiseXor_onAnyType() throws Exception {
            dual("val a: Any = 6\nval b: Any = 3\na ^ b",
                 wrap("val a: Any = 6\n    val b: Any = 3\n    return a ^ b"), 5);
        }

        // ---- Issue 1b: Object 类型移位运算 ----

        @Test void shiftLeft_onAnyType() throws Exception {
            dual("val a: Any = 1\nval b: Any = 3\na << b",
                 wrap("val a: Any = 1\n    val b: Any = 3\n    return a << b"), 8);
        }

        @Test void shiftRight_onAnyType() throws Exception {
            dual("val a: Any = 8\nval b: Any = 2\na >> b",
                 wrap("val a: Any = 8\n    val b: Any = 2\n    return a >> b"), 2);
        }

        @Test void unsignedShiftRight_onAnyType() throws Exception {
            dual("val a: Any = -1\nval b: Any = 28\na >>> b",
                 wrap("val a: Any = -1\n    val b: Any = 28\n    return a >>> b"), 15);
        }

        // ---- Issue 2: Long 移位正确性 ----

        @Test void longShiftLeft() throws Exception {
            dual("val a = 1L\na << 3",
                 wrap("val a = 1L\n    return a << 3"), 8L);
        }

        @Test void longShiftRight() throws Exception {
            dual("val a = 64L\na >> 2",
                 wrap("val a = 64L\n    return a >> 2"), 16L);
        }

        // ---- Issue 1: 真正的 Object 类型（函数返回值，MIR 无法推断） ----

        @Test void bitwiseAnd_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(7) & get(3)",
                 wrap(fn + "    return get(7) & get(3)"), 3);
        }

        @Test void bitwiseOr_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(5) | get(3)",
                 wrap(fn + "    return get(5) | get(3)"), 7);
        }

        @Test void bitwiseXor_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(6) ^ get(3)",
                 wrap(fn + "    return get(6) ^ get(3)"), 5);
        }

        @Test void shiftLeft_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(1) << get(3)",
                 wrap(fn + "    return get(1) << get(3)"), 8);
        }

        @Test void shiftRight_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(8) >> get(2)",
                 wrap(fn + "    return get(8) >> get(2)"), 2);
        }

        @Test void unsignedShiftRight_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(-1) >>> get(28)",
                 wrap(fn + "    return get(-1) >>> get(28)"), 15);
        }

        // ---- Object 类型算术运算（已有路径，回归验证） ----

        @Test void add_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(3) + get(4)",
                 wrap(fn + "    return get(3) + get(4)"), 7);
        }

        @Test void mul_dynamicObject() throws Exception {
            String fn = "fun get(x: Int): Any = x\n";
            dual(fn + "get(6) * get(7)",
                 wrap(fn + "    return get(6) * get(7)"), 42);
        }

        @Test void stringAdd_dynamicObject() throws Exception {
            String fn = "fun get(x: Any): Any = x\n";
            dual(fn + "get(\"hello\") + get(\" world\")",
                 wrap(fn + "    return get(\"hello\") + get(\" world\")"), "hello world");
        }
    }

    // ============ 比较运算符 ============

    @Nested
    @DisplayName("比较运算符")
    class ComparisonTests {

        @Test void testEqual() throws Exception {
            dual("5 == 5", wrap("return 5 == 5"), true);
        }

        @Test void testNotEqual() throws Exception {
            dual("3 != 4", wrap("return 3 != 4"), true);
        }

        @Test void testLessThan() throws Exception {
            dual("3 < 5", wrap("return 3 < 5"), true);
        }

        @Test void testGreaterThan() throws Exception {
            dual("5 > 3", wrap("return 5 > 3"), true);
        }

        @Test void testLessEqual() throws Exception {
            dual("5 <= 5", wrap("return 5 <= 5"), true);
        }

        @Test void testGreaterEqual() throws Exception {
            dual("4 >= 5", wrap("return 4 >= 5"), false);
        }

        @Test void testStringEqual() throws Exception {
            dual("\"abc\" == \"abc\"", wrap("return \"abc\" == \"abc\""), true);
        }
    }

    // ============ 逻辑运算符 ============

    @Nested
    @DisplayName("逻辑运算符")
    class LogicalTests {

        @Test void testAnd() throws Exception {
            dual("true && true", wrap("return true && true"), true);
        }

        @Test void testAndFalse() throws Exception {
            dual("true && false", wrap("return true && false"), false);
        }

        @Test void testOr() throws Exception {
            dual("false || true", wrap("return false || true"), true);
        }

        @Test void testNot() throws Exception {
            dual("!false", wrap("return !false"), true);
        }

        @Test void testShortCircuit() throws Exception {
            // false && x 不求值 x
            dual("var x = 0\nfalse && { x = 1; true }()\nx",
                 wrap("var x = 0\n    false && { x = 1; true }()\n    return x"), 0);
        }
    }

    // ============ 赋值运算符 ============

    @Nested
    @DisplayName("赋值运算符")
    class AssignmentTests {

        @Test void testPlusAssign() throws Exception {
            dual("var x = 10\nx += 5\nx", wrap("var x = 10\n    x += 5\n    return x"), 15);
        }

        @Test void testMinusAssign() throws Exception {
            dual("var x = 10\nx -= 3\nx", wrap("var x = 10\n    x -= 3\n    return x"), 7);
        }

        @Test void testTimesAssign() throws Exception {
            dual("var x = 4\nx *= 3\nx", wrap("var x = 4\n    x *= 3\n    return x"), 12);
        }

        @Test void testDivAssign() throws Exception {
            dual("var x = 20\nx /= 4\nx", wrap("var x = 20\n    x /= 4\n    return x"), 5);
        }

        @Test void testModAssign() throws Exception {
            dual("var x = 17\nx %= 5\nx", wrap("var x = 17\n    x %= 5\n    return x"), 2);
        }

        @Test void testElvisAssign() throws Exception {
            dual("var x: Int? = null\nx ??= 42\nx",
                 wrap("var x: Int? = null\n    x ??= 42\n    return x"), 42);
        }
    }

    // ============ 空安全 ============

    @Nested
    @DisplayName("空安全运算符")
    class NullSafetyTests {

        @Test void testElvisNonNull() throws Exception {
            dual("val x = 5\nx ?: 10", wrap("val x = 5\n    return x ?: 10"), 5);
        }

        @Test void testElvisNull() throws Exception {
            dual("val x: Int? = null\nx ?: 10", wrap("val x: Int? = null\n    return x ?: 10"), 10);
        }

        @Test void testSafeCallNonNull() throws Exception {
            dual("val s = \"hello\"\ns?.length()", wrap("val s = \"hello\"\n    return s?.length()"), 5);
        }

        @Test void testSafeCallNull() throws Exception {
            NovaValue r = interp("val s: String? = null\ns?.length()");
            assertTrue(r.isNull());
        }

        @Test void testNotNullAssert() throws Exception {
            dual("val x: Int? = 42\nx!!", wrap("val x: Int? = 42\n    return x!!"), 42);
        }

        @Test void testSafeIndex() throws Exception {
            NovaValue r = interp("val list: List? = null\nlist?[0]");
            assertTrue(r.isNull());
        }

        @Test void testSafeIndexNonNull() throws Exception {
            dual("val list = [10, 20, 30]\nlist?[1]",
                 wrap("val list = [10, 20, 30]\n    return list?[1]"), 20);
        }
    }

    // ============ 类型运算符 ============

    @Nested
    @DisplayName("类型运算符")
    class TypeOperatorTests {

        @Test void testIsString() throws Exception {
            dual("\"abc\" is String", wrap("return \"abc\" is String"), true);
        }

        @Test void testIsInt() throws Exception {
            dual("42 is Int", wrap("return 42 is Int"), true);
        }

        @Test void testIsNotInt() throws Exception {
            dual("\"abc\" !is Int", wrap("return \"abc\" !is Int"), true);
        }

        @Test void testAsCast() throws Exception {
            dual("val x: Any = \"hello\"\n(x as String).length()",
                 wrap("val x: Any = \"hello\"\n    return (x as String).length()"), 5);
        }
    }

    // ============ 控制流 - if ============

    @Nested
    @DisplayName("if 语句/表达式")
    class IfTests {

        @Test void testIfTrue() throws Exception {
            dual("var x = 0\nif (true) { x = 1 }\nx",
                 wrap("var x = 0\n    if (true) { x = 1 }\n    return x"), 1);
        }

        @Test void testIfFalse() throws Exception {
            dual("var x = 0\nif (false) { x = 1 }\nx",
                 wrap("var x = 0\n    if (false) { x = 1 }\n    return x"), 0);
        }

        @Test void testIfElse() throws Exception {
            dual("val x = if (3 > 2) \"yes\" else \"no\"",
                 wrap("return if (3 > 2) \"yes\" else \"no\""), "yes");
        }

        @Test void testIfElseExpr() throws Exception {
            dual("val r = if (1 > 2) 10 else 20\nr",
                 wrap("return if (1 > 2) 10 else 20"), 20);
        }

        @Test void testIfElseChain() throws Exception {
            String logic = "val x = 15\nval r = if (x < 10) \"small\" else if (x < 20) \"medium\" else \"large\"\nr";
            dual(logic, wrap("val x = 15\n    return if (x < 10) \"small\" else if (x < 20) \"medium\" else \"large\""), "medium");
        }

        @Test void testNestedIf() throws Exception {
            String logic = "val a = 5\nval b = 10\nvar r = 0\nif (a > 0) { if (b > 0) { r = a + b } }\nr";
            dual(logic, wrap("val a = 5\n    val b = 10\n    var r = 0\n    if (a > 0) { if (b > 0) { r = a + b } }\n    return r"), 15);
        }
    }

    // ============ 控制流 - when ============

    @Nested
    @DisplayName("when 表达式/语句")
    class WhenTests {

        @Test void testWhenValue() throws Exception {
            String logic = "val x = 2\nwhen (x) {\n  1 -> \"one\"\n  2 -> \"two\"\n  else -> \"other\"\n}";
            dual(logic, wrap("val x = 2\n    return when (x) {\n      1 -> \"one\"\n      2 -> \"two\"\n      else -> \"other\"\n    }"), "two");
        }

        @Test void testWhenElse() throws Exception {
            String logic = "val x = 99\nwhen (x) {\n  1 -> \"one\"\n  else -> \"unknown\"\n}";
            dual(logic, wrap("val x = 99\n    return when (x) {\n      1 -> \"one\"\n      else -> \"unknown\"\n    }"), "unknown");
        }

        @Test void testWhenType() throws Exception {
            String logic = "val x: Any = \"hello\"\nwhen (x) {\n  is Int -> \"int\"\n  is String -> \"str\"\n  else -> \"?\"\n}";
            dual(logic, wrap("val x: Any = \"hello\"\n    return when (x) {\n      is Int -> \"int\"\n      is String -> \"str\"\n      else -> \"?\"\n    }"), "str");
        }

        @Test void testWhenRange() throws Exception {
            String logic = "val x = 15\nwhen (x) {\n  in 1..10 -> \"low\"\n  in 11..20 -> \"mid\"\n  else -> \"high\"\n}";
            dual(logic, wrap("val x = 15\n    return when (x) {\n      in 1..10 -> \"low\"\n      in 11..20 -> \"mid\"\n      else -> \"high\"\n    }"), "mid");
        }

        @Test void testWhenNoSubject() throws Exception {
            String logic = "val x = 5\nwhen {\n  x < 0 -> \"neg\"\n  x == 0 -> \"zero\"\n  else -> \"pos\"\n}";
            dual(logic, wrap("val x = 5\n    return when {\n      x < 0 -> \"neg\"\n      x == 0 -> \"zero\"\n      else -> \"pos\"\n    }"), "pos");
        }

        @Test void testWhenMultipleValues() throws Exception {
            String logic = "val x = 3\nwhen (x) {\n  1, 3, 5 -> \"odd\"\n  2, 4, 6 -> \"even\"\n  else -> \"?\"\n}";
            dual(logic, wrap("val x = 3\n    return when (x) {\n      1, 3, 5 -> \"odd\"\n      2, 4, 6 -> \"even\"\n      else -> \"?\"\n    }"), "odd");
        }

        @Test void testWhenResultIsOk() throws Exception {
            String interpCode = ""
                + "val r = Ok(42)\n"
                + "when (r) {\n"
                + "    is Ok -> \"ok\"\n"
                + "    is Err -> \"err\"\n"
                + "}";
            String compileCode = wrap(""
                + "    val r = Ok(42)\n"
                + "    return when (r) {\n"
                + "        is Ok -> \"ok\"\n"
                + "        is Err -> \"err\"\n"
                + "    }");
            dual(interpCode, compileCode, "ok");
        }

        @Test void testWhenResultIsErr() throws Exception {
            String interpCode = ""
                + "val r = Err(\"fail\")\n"
                + "when (r) {\n"
                + "    is Ok -> \"ok\"\n"
                + "    is Err -> \"err\"\n"
                + "}";
            String compileCode = wrap(""
                + "    val r = Err(\"fail\")\n"
                + "    return when (r) {\n"
                + "        is Ok -> \"ok\"\n"
                + "        is Err -> \"err\"\n"
                + "    }");
            dual(interpCode, compileCode, "err");
        }
    }

    // ============ 控制流 - for ============

    @Nested
    @DisplayName("for 循环")
    class ForTests {

        @Test void testForRange() throws Exception {
            String logic = "var s = 0\nfor (i in 1..5) { s = s + i }\ns";
            dual(logic, wrap("var s = 0\n    for (i in 1..5) { s = s + i }\n    return s"), 15);
        }

        @Test void testForRangeExclusive() throws Exception {
            String logic = "var s = 0\nfor (i in 0..<5) { s = s + i }\ns";
            dual(logic, wrap("var s = 0\n    for (i in 0..<5) { s = s + i }\n    return s"), 10);
        }

        @Test void testForList() throws Exception {
            String logic = "val list = [10, 20, 30]\nvar s = 0\nfor (x in list) { s = s + x }\ns";
            dual(logic, wrap("val list = [10, 20, 30]\n    var s = 0\n    for (x in list) { s = s + x }\n    return s"), 60);
        }

        @Test void testForDestructuring() throws Exception {
            String logic = "val m = #{\"a\": 1, \"b\": 2}\nvar s = 0\nfor ((k, v) in m) { s = s + v }\ns";
            dual(logic, wrap("val m = #{\"a\": 1, \"b\": 2}\n    var s = 0\n    for ((k, v) in m) { s = s + v }\n    return s"), 3);
        }

        @Test void testForBreak() throws Exception {
            String logic = "var s = 0\nfor (i in 1..10) {\n  if (i > 3) break\n  s = s + i\n}\ns";
            dual(logic, wrap("var s = 0\n    for (i in 1..10) {\n      if (i > 3) break\n      s = s + i\n    }\n    return s"), 6);
        }

        @Test void testForContinue() throws Exception {
            String logic = "var s = 0\nfor (i in 1..5) {\n  if (i == 3) continue\n  s = s + i\n}\ns";
            dual(logic, wrap("var s = 0\n    for (i in 1..5) {\n      if (i == 3) continue\n      s = s + i\n    }\n    return s"), 12);
        }

        @Test void testNestedFor() throws Exception {
            String logic = "var s = 0\nfor (i in 1..3) {\n  for (j in 1..3) {\n    s = s + i * j\n  }\n}\ns";
            dual(logic, wrap("var s = 0\n    for (i in 1..3) {\n      for (j in 1..3) {\n        s = s + i * j\n      }\n    }\n    return s"), 36);
        }
    }

    // ============ 控制流 - C 风格 for 循环 ============

    @Nested
    @DisplayName("C 风格 for 循环")
    class CStyleForTests {

        // ── 正常值 ──

        @Test void testBasicCStyleFor() throws Exception {
            String logic = "var s = 0\nfor (var i = 0; i < 5; i += 1) { s = s + i }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 5; i += 1) { s = s + i }\n    return s"), 10);
        }

        @Test void testCStyleForWithBreak() throws Exception {
            String logic = "var s = 0\nfor (var i = 0; i < 10; i += 1) {\n  if (i > 3) break\n  s = s + i\n}\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 10; i += 1) {\n      if (i > 3) break\n      s = s + i\n    }\n    return s"), 6);
        }

        @Test void testCStyleForWithContinue() throws Exception {
            // continue 必须先执行 update 再回条件检查
            String logic = "var s = 0\nfor (var i = 0; i < 5; i += 1) {\n  if (i == 2) continue\n  s = s + i\n}\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 5; i += 1) {\n      if (i == 2) continue\n      s = s + i\n    }\n    return s"), 8);
        }

        @Test void testCStyleForNested() throws Exception {
            String logic = "var s = 0\nfor (var i = 0; i < 3; i += 1) {\n  for (var j = 0; j < 3; j += 1) {\n    s = s + 1\n  }\n}\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 3; i += 1) {\n      for (var j = 0; j < 3; j += 1) {\n        s = s + 1\n      }\n    }\n    return s"), 9);
        }

        @Test void testCStyleForStepTwo() throws Exception {
            // 步长为 2
            String logic = "var s = 0\nfor (var i = 0; i < 10; i += 2) { s = s + i }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 10; i += 2) { s = s + i }\n    return s"), 20);
        }

        @Test void testCStyleForDecrement() throws Exception {
            // 递减
            String logic = "var s = 0\nfor (var i = 5; i > 0; i -= 1) { s = s + i }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 5; i > 0; i -= 1) { s = s + i }\n    return s"), 15);
        }

        @Test void testCStyleForStringConcat() throws Exception {
            String logic = "var s = \"\"\nfor (var i = 0; i < 3; i += 1) { s = s + i }\ns";
            dual(logic, wrap("var s = \"\"\n    for (var i = 0; i < 3; i += 1) { s = s + i }\n    return s"), "012");
        }

        // ── 边界值 ──

        @Test void testCStyleForZeroIterations() throws Exception {
            // 条件一开始就不满足，循环体不执行
            String logic = "var s = 0\nfor (var i = 0; i < 0; i += 1) { s = s + 1 }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 0; i += 1) { s = s + 1 }\n    return s"), 0);
        }

        @Test void testCStyleForSingleIteration() throws Exception {
            String logic = "var s = 0\nfor (var i = 0; i < 1; i += 1) { s = s + 10 }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 1; i += 1) { s = s + 10 }\n    return s"), 10);
        }

        @Test void testCStyleForBreakFirst() throws Exception {
            // 第一次迭代就 break
            String logic = "var s = 0\nfor (var i = 0; i < 100; i += 1) { s = 42; break }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 100; i += 1) { s = 42; break }\n    return s"), 42);
        }

        @Test void testCStyleForContinueAll() throws Exception {
            // 每次都 continue（通过条件），循环体的 s 累加不执行，但 update 仍然执行
            String logic = "var s = 0\nfor (var i = 0; i < 5; i += 1) {\n  if (i >= 0) continue\n  s = s + 1\n}\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 5; i += 1) {\n      if (i >= 0) continue\n      s = s + 1\n    }\n    return s"), 0);
        }

        @Test void testCStyleForNegativeRange() throws Exception {
            // 负数范围
            String logic = "var s = 0\nfor (var i = -3; i <= 0; i += 1) { s = s + i }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = -3; i <= 0; i += 1) { s = s + i }\n    return s"), -6);
        }

        @Test void testCStyleForLargeStep() throws Exception {
            // 步长大于范围，只执行一次
            String logic = "var s = 0\nfor (var i = 0; i < 5; i += 100) { s = s + 1 }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 5; i += 100) { s = s + 1 }\n    return s"), 1);
        }

        // ── 异常值 / 特殊场景 ──

        @Test void testCStyleForVarMutation() throws Exception {
            // 循环变量在 body 中可修改（var 声明）
            String logic = "var s = 0\nfor (var i = 0; i < 10; i += 1) {\n  if (i == 3) { i = 7 }\n  s = s + 1\n}\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 10; i += 1) {\n      if (i == 3) { i = 7 }\n      s = s + 1\n    }\n    return s"), 6);
        }

        @Test void testCStyleForMixedWithKotlinFor() throws Exception {
            // C 风格和 Kotlin 风格混用: (10+0)+(20+0)+(10+1)+(20+1)+(10+2)+(20+2) = 96
            String logic = "var s = 0\nfor (var i = 0; i < 3; i += 1) {\n  for (x in [10, 20]) {\n    s = s + x + i\n  }\n}\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 0; i < 3; i += 1) {\n      for (x in [10, 20]) {\n        s = s + x + i\n      }\n    }\n    return s"), 96);
        }

        @Test void testCStyleForWithReturn() throws Exception {
            // 循环中 return
            String code = "fun findFirst(): Int {\n  for (var i = 0; i < 10; i += 1) {\n    if (i == 5) return i\n  }\n  return -1\n}\nfindFirst()";
            String wrapped = wrap("fun findFirst(): Int {\n      for (var i = 0; i < 10; i += 1) {\n        if (i == 5) return i\n      }\n      return -1\n    }\n    return findFirst()");
            dual(code, wrapped, 5);
        }

        @Test void testCStyleForCompoundUpdate() throws Exception {
            // 乘法更新
            String logic = "var s = 0\nfor (var i = 1; i < 100; i *= 2) { s = s + 1 }\ns";
            dual(logic, wrap("var s = 0\n    for (var i = 1; i < 100; i *= 2) { s = s + 1 }\n    return s"), 7);
        }
    }

    // ============ 控制流 - while / do-while ============

    @Nested
    @DisplayName("while / do-while 循环")
    class WhileTests {

        @Test void testWhile() throws Exception {
            String logic = "var i = 0\nvar s = 0\nwhile (i < 5) { s = s + i; i = i + 1 }\ns";
            dual(logic, wrap("var i = 0\n    var s = 0\n    while (i < 5) { s = s + i; i = i + 1 }\n    return s"), 10);
        }

        @Test void testWhileBreak() throws Exception {
            String logic = "var i = 0\nwhile (true) { if (i >= 3) break; i = i + 1 }\ni";
            dual(logic, wrap("var i = 0\n    while (true) { if (i >= 3) break; i = i + 1 }\n    return i"), 3);
        }

        @Test void testDoWhile() throws Exception {
            String logic = "var i = 0\nvar s = 0\ndo { s = s + i; i = i + 1 } while (i < 5)\ns";
            dual(logic, wrap("var i = 0\n    var s = 0\n    do { s = s + i; i = i + 1 } while (i < 5)\n    return s"), 10);
        }

        @Test void testDoWhileAtLeastOnce() throws Exception {
            String logic = "var count = 0\ndo { count = count + 1 } while (false)\ncount";
            dual(logic, wrap("var count = 0\n    do { count = count + 1 } while (false)\n    return count"), 1);
        }
    }

    // ============ 控制流 - try-catch ============

    @Nested
    @DisplayName("try-catch-finally")
    class TryCatchTests {

        @Test void testTryCatchNoError() throws Exception {
            String logic = "val r = try { 42 } catch (e) { -1 }\nr";
            dual(logic, wrap("return try { 42 } catch (e) { -1 }"), 42);
        }

        @Test void testTryCatchWithError() throws Exception {
            String logic = "val r = try { throw \"err\"; 0 } catch (e) { 99 }\nr";
            dual(logic, wrap("return try { throw \"err\"; 0 } catch (e) { 99 }"), 99);
        }

        @Test void testTryFinally() throws Exception {
            String logic = "var x = 0\ntry { x = 1 } finally { x = x + 10 }\nx";
            dual(logic, wrap("var x = 0\n    try { x = 1 } finally { x = x + 10 }\n    return x"), 11);
        }

        @Test void testTryCatchFinally() throws Exception {
            String logic = "var x = 0\ntry { throw \"e\"; x = 1 } catch (e) { x = 2 } finally { x = x + 100 }\nx";
            dual(logic, wrap("var x = 0\n    try { throw \"e\"; x = 1 } catch (e) { x = 2 } finally { x = x + 100 }\n    return x"), 102);
        }
    }

    // ============ 声明 ============

    @Nested
    @DisplayName("变量声明")
    class DeclarationTests {

        @Test void testVal() throws Exception {
            dual("val x = 42\nx", wrap("val x = 42\n    return x"), 42);
        }

        @Test void testVar() throws Exception {
            dual("var x = 1\nx = 2\nx", wrap("var x = 1\n    x = 2\n    return x"), 2);
        }

        @Test void testValWithType() throws Exception {
            dual("val x: Int = 100\nx", wrap("val x: Int = 100\n    return x"), 100);
        }

        @Test void testDestructuring() throws Exception {
            dual("val list = [1, 2, 3]\nval (a, b, c) = list\na + b + c",
                 wrap("val list = [1, 2, 3]\n    val (a, b, c) = list\n    return a + b + c"), 6);
        }

        @Test void testDestructuringSkip() throws Exception {
            dual("val list = [10, 20, 30]\nval (_, b, _) = list\nb",
                 wrap("val list = [10, 20, 30]\n    val (_, b, _) = list\n    return b"), 20);
        }
    }

    // ============ 集合 ============

    @Nested
    @DisplayName("集合操作")
    class CollectionTests {

        @Test void testListLiteral() throws Exception {
            dual("[1, 2, 3].size()", wrap("return [1, 2, 3].size()"), 3);
        }

        @Test void testListIndex() throws Exception {
            dual("[10, 20, 30][1]", wrap("return [10, 20, 30][1]"), 20);
        }

        @Test void testListAdd() throws Exception {
            dual("val l = [1, 2]\nl.add(3)\nl.size()",
                 wrap("val l = [1, 2]\n    l.add(3)\n    return l.size()"), 3);
        }

        @Test void testSetLiteral() throws Exception {
            dual("#{1, 2, 2, 3}.size()", wrap("return #{1, 2, 2, 3}.size()"), 3);
        }

        @Test void testSetContains() throws Exception {
            dual("#{\"a\", \"b\"}.contains(\"a\")", wrap("return #{\"a\", \"b\"}.contains(\"a\")"), true);
        }

        @Test void testMapLiteral() throws Exception {
            dual("#{\"k\": 42}[\"k\"]", wrap("return #{\"k\": 42}[\"k\"]"), 42);
        }

        @Test void testMapSize() throws Exception {
            dual("#{\"a\": 1, \"b\": 2}.size()", wrap("return #{\"a\": 1, \"b\": 2}.size()"), 2);
        }

        @Test void testRange() throws Exception {
            dual("(1..5).size()", wrap("return (1..5).size()"), 5);
        }

        @Test void testRangeContains() throws Exception {
            dual("(1..10).contains(5)", wrap("return (1..10).contains(5)"), true);
        }

        @Test void testEmptyList() throws Exception {
            dual("[].size()", wrap("return [].size()"), 0);
        }

        @Test void testListSpread() throws Exception {
            dual("val a = [1, 2]\nval b = [*a, 3]\nb.size()",
                 wrap("val a = [1, 2]\n    val b = [*a, 3]\n    return b.size()"), 3);
        }
    }

    // ============ 函数基础 ============

    @Nested
    @DisplayName("函数声明与调用")
    class FunctionBasicTests {

        @Test void testSimpleFunction() throws Exception {
            String logic = "fun add(a: Int, b: Int) = a + b\nadd(3, 4)";
            dual(logic, "fun add(a: Int, b: Int) = a + b\n" + wrap("return add(3, 4)"), 7);
        }

        @Test void testFunctionBlock() throws Exception {
            String logic = "fun max(a: Int, b: Int): Int {\n  if (a > b) return a\n  return b\n}\nmax(5, 3)";
            dual(logic, "fun max(a: Int, b: Int): Int {\n  if (a > b) return a\n  return b\n}\n" + wrap("return max(5, 3)"), 5);
        }

        @Test void testDefaultParam() throws Exception {
            String logic = "fun greet(name: String = \"World\") = \"Hi \" + name\ngreet()";
            dual(logic, "fun greet(name: String = \"World\") = \"Hi \" + name\n" + wrap("return greet()"), "Hi World");
        }

        @Test void testDefaultParamOverride() throws Exception {
            String logic = "fun greet(name: String = \"World\") = \"Hi \" + name\ngreet(\"Nova\")";
            dual(logic, "fun greet(name: String = \"World\") = \"Hi \" + name\n" + wrap("return greet(\"Nova\")"), "Hi Nova");
        }

        @Test void testRecursion() throws Exception {
            String logic = "fun fib(n: Int): Int {\n  if (n <= 1) return n\n  return fib(n - 1) + fib(n - 2)\n}\nfib(10)";
            dual(logic, "fun fib(n: Int): Int {\n  if (n <= 1) return n\n  return fib(n - 1) + fib(n - 2)\n}\n" + wrap("return fib(10)"), 55);
        }

        @Test void testLocalFunction() throws Exception {
            String logic = "fun outer(): Int {\n  fun inner(x: Int) = x * 2\n  return inner(5)\n}\nouter()";
            dual(logic, "fun outer(): Int {\n  fun inner(x: Int) = x * 2\n  return inner(5)\n}\n" + wrap("return outer()"), 10);
        }

        @Test void testNestedFunctionCapturesParam() throws Exception {
            String logic = "fun outer(x: Int): Int {\n  fun inner(y: Int) = x + y\n  return inner(3)\n}\nouter(10)";
            dual(logic, "fun outer(x: Int): Int {\n  fun inner(y: Int) = x + y\n  return inner(3)\n}\n" + wrap("return outer(10)"), 13);
        }

        @Test void testNestedFunctionCapturesLocal() throws Exception {
            String logic = "fun outer(): Int {\n  val factor = 3\n  fun multiply(x: Int) = x * factor\n  return multiply(7)\n}\nouter()";
            dual(logic, "fun outer(): Int {\n  val factor = 3\n  fun multiply(x: Int) = x * factor\n  return multiply(7)\n}\n" + wrap("return outer()"), 21);
        }

        @Test void testNestedFunctionMutatesVar() throws Exception {
            String logic = "fun outer(): Int {\n  var count = 0\n  fun inc() { count = count + 1 }\n  inc()\n  inc()\n  inc()\n  return count\n}\nouter()";
            dual(logic, "fun outer(): Int {\n  var count = 0\n  fun inc() { count = count + 1 }\n  inc()\n  inc()\n  inc()\n  return count\n}\n" + wrap("return outer()"), 3);
        }

        @Test void testMultipleNestedFunctions() throws Exception {
            String logic = "fun calc(a: Int, b: Int): Int {\n  fun add(x: Int, y: Int) = x + y\n  fun mul(x: Int, y: Int) = x * y\n  return add(a, b) + mul(a, b)\n}\ncalc(3, 4)";
            dual(logic, "fun calc(a: Int, b: Int): Int {\n  fun add(x: Int, y: Int) = x + y\n  fun mul(x: Int, y: Int) = x * y\n  return add(a, b) + mul(a, b)\n}\n" + wrap("return calc(3, 4)"), 19);
        }

        @Test void testNestedFunctionCallsAnother() throws Exception {
            String logic = "fun outer(): Int {\n  fun double(x: Int) = x * 2\n  fun quadruple(x: Int) = double(double(x))\n  return quadruple(3)\n}\nouter()";
            dual(logic, "fun outer(): Int {\n  fun double(x: Int) = x * 2\n  fun quadruple(x: Int) = double(double(x))\n  return quadruple(3)\n}\n" + wrap("return outer()"), 12);
        }

        @Test void testDoubleNestedFunction() throws Exception {
            String logic = "fun outer(): Int {\n  fun middle(): Int {\n    fun inner() = 42\n    return inner()\n  }\n  return middle()\n}\nouter()";
            dual(logic, "fun outer(): Int {\n  fun middle(): Int {\n    fun inner() = 42\n    return inner()\n  }\n  return middle()\n}\n" + wrap("return outer()"), 42);
        }

        @Test void testDoubleNestedCapture() throws Exception {
            String logic = "fun outer(a: Int): Int {\n  fun middle(b: Int): Int {\n    fun inner(c: Int) = a + b + c\n    return inner(3)\n  }\n  return middle(2)\n}\nouter(1)";
            dual(logic, "fun outer(a: Int): Int {\n  fun middle(b: Int): Int {\n    fun inner(c: Int) = a + b + c\n    return inner(3)\n  }\n  return middle(2)\n}\n" + wrap("return outer(1)"), 6);
        }

        @Test void testNestedRecursion() throws Exception {
            String logic = "fun outer(n: Int): Int {\n  fun factorial(x: Int): Int {\n    if (x <= 1) return 1\n    return x * factorial(x - 1)\n  }\n  return factorial(n)\n}\nouter(5)";
            dual(logic, "fun outer(n: Int): Int {\n  fun factorial(x: Int): Int {\n    if (x <= 1) return 1\n    return x * factorial(x - 1)\n  }\n  return factorial(n)\n}\n" + wrap("return outer(5)"), 120);
        }

        @Test void testNestedFunctionDefaultParam() throws Exception {
            String logic = "fun outer(): String {\n  fun greet(name: String = \"World\") = \"Hello \" + name\n  return greet() + \", \" + greet(\"Nova\")\n}\nouter()";
            dual(logic, "fun outer(): String {\n  fun greet(name: String = \"World\") = \"Hello \" + name\n  return greet() + \", \" + greet(\"Nova\")\n}\n" + wrap("return outer()"), "Hello World, Hello Nova");
        }
    }

    // ============ Lambda ============

    @Nested
    @DisplayName("Lambda 表达式")
    class LambdaTests {

        @Test void testBasicLambda() throws Exception {
            dual("val f = { x: Int -> x * 2 }\nf(5)",
                 wrap("val f = { x: Int -> x * 2 }\n    return f(5)"), 10);
        }

        @Test void testLambdaNoParams() throws Exception {
            dual("val f = { 42 }\nf()",
                 wrap("val f = { 42 }\n    return f()"), 42);
        }

        @Test void testItParam() throws Exception {
            dual("val f = { it * 3 }\nf(7)",
                 wrap("val f = { it * 3 }\n    return f(7)"), 21);
        }

        @Test void testTrailingLambda() throws Exception {
            String logic = "fun apply(x: Int, f: (Int) -> Int) = f(x)\napply(10) { it * 2 }";
            dual(logic, "fun apply(x: Int, f: (Int) -> Int) = f(x)\n" + wrap("return apply(10) { it * 2 }"), 20);
        }

        @Test void testHigherOrderFunction() throws Exception {
            String logic = "fun apply(x: Int, f: (Int) -> Int) = f(x)\napply(5, { it * it })";
            dual(logic, "fun apply(x: Int, f: (Int) -> Int) = f(x)\n" + wrap("return apply(5, { it * it })"), 25);
        }

        @Test void testClosure() throws Exception {
            String logic = "var counter = 0\nval inc = { counter = counter + 1 }\ninc()\ninc()\ninc()\ncounter";
            dual(logic, wrap("var counter = 0\n    val inc = { counter = counter + 1 }\n    inc()\n    inc()\n    inc()\n    return counter"), 3);
        }

        @Test void testLambdaMultiParam() throws Exception {
            dual("val f = { a: Int, b: Int -> a + b }\nf(3, 4)",
                 wrap("val f = { a: Int, b: Int -> a + b }\n    return f(3, 4)"), 7);
        }
    }

    // ============ 标签循环 ============

    @Nested
    @DisplayName("标签循环")
    class LabeledLoopTests {

        @Test void testBreakLabel() throws Exception {
            String logic = "var r = 0\nouter@ for (i in 1..3) {\n  for (j in 1..3) {\n    if (j == 2) break@outer\n    r = r + 1\n  }\n}\nr";
            dual(logic, wrap("var r = 0\n    outer@ for (i in 1..3) {\n      for (j in 1..3) {\n        if (j == 2) break@outer\n        r = r + 1\n      }\n    }\n    return r"), 1);
        }

        @Test void testContinueLabel() throws Exception {
            String logic = "var r = 0\nouter@ for (i in 1..3) {\n  for (j in 1..3) {\n    if (j == 2) continue@outer\n    r = r + 1\n  }\n}\nr";
            dual(logic, wrap("var r = 0\n    outer@ for (i in 1..3) {\n      for (j in 1..3) {\n        if (j == 2) continue@outer\n        r = r + 1\n      }\n    }\n    return r"), 3);
        }

        @Test void testWhileLabel() throws Exception {
            String logic = "var i = 0\nvar count = 0\nouter@ while (i < 5) {\n  var j = 0\n  while (j < 5) {\n    if (i == 2 && j == 1) break@outer\n    j = j + 1\n    count = count + 1\n  }\n  i = i + 1\n}\ncount";
            dual(logic, wrap("var i = 0\n    var count = 0\n    outer@ while (i < 5) {\n      var j = 0\n      while (j < 5) {\n        if (i == 2 && j == 1) break@outer\n        j = j + 1\n        count = count + 1\n      }\n      i = i + 1\n    }\n    return count"), 11);
        }
    }

    // ============ 扩展函数 ============

    @Nested
    @DisplayName("扩展函数")
    class ExtensionTests {

        @Test void testStringExtension() throws Exception {
            String logic = "fun String.shout() = this + \"!\"\n\"hi\".shout()";
            dual(logic, "fun String.shout() = this + \"!\"\n" + wrap("return \"hi\".shout()"), "hi!");
        }

        @Test void testIntExtension() throws Exception {
            String logic = "fun Int.square() = this * this\n7.square()";
            dual(logic, "fun Int.square() = this * this\n" + wrap("return 7.square()"), 49);
        }

        @Test void testExtensionWithParam() throws Exception {
            String logic = "fun Int.add(n: Int) = this + n\n10.add(5)";
            dual(logic, "fun Int.add(n: Int) = this + n\n" + wrap("return 10.add(5)"), 15);
        }

        @Test void testInfixCall() throws Exception {
            String fn = "infix fun Int.times2(n: Int) = this * n\n";
            dual(fn + "3 times2 4",
                 fn + wrap("return 3 times2 4"), 12);
        }

        @Test void testInfixPower() throws Exception {
            String fn = "infix fun Int.power(exp: Int): Int {\n"
                + "  var result = 1\n"
                + "  for (i in 1..exp) { result = result * this }\n"
                + "  return result\n"
                + "}\n";
            dual(fn + "2 power 10",
                 fn + wrap("return 2 power 10"), 1024);
        }

        @Test void testExtensionThisInLoop() throws Exception {
            String fn = "fun Int.power(exp: Int): Int {\n"
                + "  var result = 1\n"
                + "  for (i in 1..exp) { result = result * this }\n"
                + "  return result\n"
                + "}\n";
            dual(fn + "2.power(10)",
                 fn + wrap("return 2.power(10)"), 1024);
        }
    }

    // ============ 方法引用 ============

    @Nested
    @DisplayName("方法引用 ::")
    class MethodRefTests {

        @Test void testGlobalFuncRef() throws Exception {
            String logic = "fun double(x: Int) = x * 2\nval f = ::double\nf(5)";
            dual(logic, "fun double(x: Int) = x * 2\n" + wrap("val f = ::double\n    return f(5)"), 10);
        }
    }

    // ============ 管道操作符 ============

    @Nested
    @DisplayName("管道操作符 |>")
    class PipeTests {

        @Test void testPipe() throws Exception {
            String logic = "fun double(x: Int) = x * 2\nfun inc(x: Int) = x + 1\n5 |> double |> inc";
            dual(logic, "fun double(x: Int) = x * 2\nfun inc(x: Int) = x + 1\n" + wrap("return 5 |> double |> inc"), 11);
        }
    }

    // ============ 字符串高级 ============

    @Nested
    @DisplayName("字符串高级特性")
    class StringAdvancedTests {

        @Test void testMultilineString() throws Exception {
            dual("val s = \"\"\"hello\"\"\"\ns", wrap("return \"\"\"hello\"\"\""), "hello");
        }

        @Test void testStringMethods() throws Exception {
            dual("\"Hello World\".length", wrap("return \"Hello World\".length"), 11);
        }

        @Test void testSubstring() throws Exception {
            dual("\"hello\".substring(1, 3)", wrap("return \"hello\".substring(1, 3)"), "el");
        }

        @Test void testToUpperCase() throws Exception {
            dual("\"hello\".toUpperCase()", wrap("return \"hello\".toUpperCase()"), "HELLO");
        }
    }

    // ============ 索引和切片 ============

    @Nested
    @DisplayName("索引和切片")
    class IndexSliceTests {

        @Test void testListIndex() throws Exception {
            dual("[\"a\", \"b\", \"c\"][2]", wrap("return [\"a\", \"b\", \"c\"][2]"), "c");
        }

        @Test void testStringIndex() throws Exception {
            dual("\"hello\"[0]", wrap("return \"hello\"[0]"), "h");
        }

        @Test void testIndexAssign() throws Exception {
            dual("val l = [1, 2, 3]\nl[1] = 20\nl[1]",
                 wrap("val l = [1, 2, 3]\n    l[1] = 20\n    return l[1]"), 20);
        }
    }

    // ============ 三元表达式 ============

    @Nested
    @DisplayName("三元表达式")
    class TernaryTests {

        @Test void testTernaryTrue() throws Exception {
            dual("true ? 1 : 2", wrap("return true ? 1 : 2"), 1);
        }

        @Test void testTernaryFalse() throws Exception {
            dual("false ? 1 : 2", wrap("return false ? 1 : 2"), 2);
        }

        @Test void testTernaryWithComparison() throws Exception {
            dual("val x = 5\nx > 0 ? \"positive\" : \"negative\"",
                 wrap("val x = 5\n    return x > 0 ? \"positive\" : \"negative\""), "positive");
        }

        @Test void testTernaryWithEquality() throws Exception {
            dual("val x = 42\nx == 42 ? \"yes\" : \"no\"",
                 wrap("val x = 42\n    return x == 42 ? \"yes\" : \"no\""), "yes");
        }

        @Test void testNestedTernaryInThen() throws Exception {
            // a ? (b ? c : d) : e
            dual("true ? false ? \"c\" : \"d\" : \"e\"",
                 wrap("return true ? false ? \"c\" : \"d\" : \"e\""), "d");
        }

        @Test void testNestedTernaryInElse() throws Exception {
            // a ? b : (c ? d : e)
            dual("false ? \"b\" : true ? \"d\" : \"e\"",
                 wrap("return false ? \"b\" : true ? \"d\" : \"e\""), "d");
        }

        @Test void testTernaryInAssignment() throws Exception {
            dual("val x = true ? 10 : 20\nx",
                 wrap("val x = true ? 10 : 20\n    return x"), 10);
        }

        @Test void testTernaryWithArithmetic() throws Exception {
            dual("val a = 10\nval b = 5\ntrue ? a + b : a - b",
                 wrap("val a = 10\n    val b = 5\n    return true ? a + b : a - b"), 15);
        }

        // ---- 边缘值 & 异常值 ----

        @Test void testTernaryNullConditionFalsy() throws Exception {
            dual("val x: Int? = null\nx ? \"yes\" : \"no\"",
                 wrap("val x: Int? = null\n    return x ? \"yes\" : \"no\""), "no");
        }

        @Test void testTernaryBranchReturnsNull() throws Exception {
            // true 分支返回 null
            NovaValue ir = interp("true ? null : 42");
            assertTrue(ir.isNull(), "解释器: true ? null : 42 应返回 null");
        }

        @Test void testTripleNestedTernary() throws Exception {
            dual("true ? true ? false ? \"a\" : \"b\" : \"c\" : \"d\"",
                 wrap("return true ? true ? false ? \"a\" : \"b\" : \"c\" : \"d\""), "b");
        }

        @Test void testTernaryEmptyStringFalsy() throws Exception {
            dual("\"\" ? \"yes\" : \"no\"",
                 wrap("return \"\" ? \"yes\" : \"no\""), "no");
        }

        @Test void testTernaryNonEmptyStringTruthy() throws Exception {
            dual("\"hello\" ? \"yes\" : \"no\"",
                 wrap("return \"hello\" ? \"yes\" : \"no\""), "yes");
        }

        @Test void testTernaryInFunctionArg() throws Exception {
            dual("fun add(a: Int, b: Int): Int = a + b\nadd(true ? 1 : 2, false ? 3 : 4)",
                 "object Test {\n  fun add(a: Int, b: Int): Int = a + b\n  fun run(): Any { return add(true ? 1 : 2, false ? 3 : 4) }\n}", 5);
        }

        @Test void testTernaryConditionFunctionCall() throws Exception {
            dual("fun isEven(n: Int): Boolean = n % 2 == 0\nisEven(4) ? \"even\" : \"odd\"",
                 "object Test {\n  fun isEven(n: Int): Boolean = n % 2 == 0\n  fun run(): Any { return isEven(4) ? \"even\" : \"odd\" }\n}", "even");
        }

        @Test void testTernaryMixedTypes() throws Exception {
            dual("true ? \"hello\" : 42",
                 wrap("return true ? \"hello\" : 42"), "hello");
        }
    }

    @Nested
    @DisplayName("尾调用消除")
    class TailCallTests {

        @Test void testTailRecursiveSum() throws Exception {
            String interpCode = "fun sum(n: Int, acc: Int): Int {\n"
                + "  if (n <= 0) return acc\n"
                + "  return sum(n - 1, acc + n)\n"
                + "}\n"
                + "sum(10000, 0)";
            String compileCode = "object Test {\n"
                + "  fun sum(n: Int, acc: Int): Int {\n"
                + "    if (n <= 0) return acc\n"
                + "    return sum(n - 1, acc + n)\n"
                + "  }\n"
                + "  fun run(): Any { return sum(10000, 0) }\n"
                + "}";
            NovaValue ir = interp(interpCode);
            assertEquals(50005000, ir.asInt(), "解释器");
            // 编译器路径单独验证（TCE 将递归转为循环，不会 StackOverflow）
            Object cr = compile(compileCode);
            assertEquals(50005000, cr, "编译器");
        }

        @Test void testTailRecursiveFactorial() throws Exception {
            // 使用较小值避免溢出问题
            String interpCode = "fun factTail(n: Int, acc: Int): Int {\n"
                + "  if (n <= 1) return acc\n"
                + "  return factTail(n - 1, n * acc)\n"
                + "}\n"
                + "factTail(10, 1)";
            String compileCode = "object Test {\n"
                + "  fun factTail(n: Int, acc: Int): Int {\n"
                + "    if (n <= 1) return acc\n"
                + "    return factTail(n - 1, n * acc)\n"
                + "  }\n"
                + "  fun run(): Any { return factTail(10, 1) }\n"
                + "}";
            dual(interpCode, compileCode, 3628800);
        }

        @Test void testTailRecursiveCountdown() throws Exception {
            String interpCode = "fun countdown(n: Int): Int {\n"
                + "  if (n <= 0) return 0\n"
                + "  return countdown(n - 1)\n"
                + "}\n"
                + "countdown(50000)";
            String compileCode = "object Test {\n"
                + "  fun countdown(n: Int): Int {\n"
                + "    if (n <= 0) return 0\n"
                + "    return countdown(n - 1)\n"
                + "  }\n"
                + "  fun run(): Any { return countdown(50000) }\n"
                + "}";
            dual(interpCode, compileCode, 0);
        }
    }

    // ============ in / !in 二元表达式运算符 ============

    @Nested
    @DisplayName("in/!in 二元表达式运算符")
    class InOperatorTests {

        @Test void inRange_true() throws Exception {
            dual("5 in 1..10", wrap("return 5 in 1..10"), true);
        }

        @Test void inRange_false() throws Exception {
            dual("15 in 1..10", wrap("return 15 in 1..10"), false);
        }

        @Test void notInRange_true() throws Exception {
            dual("15 !in 1..10", wrap("return 15 !in 1..10"), true);
        }

        @Test void notInRange_false() throws Exception {
            dual("5 !in 1..10", wrap("return 5 !in 1..10"), false);
        }

        @Test void inRange_boundary_start() throws Exception {
            dual("1 in 1..10", wrap("return 1 in 1..10"), true);
        }

        @Test void inRange_boundary_end() throws Exception {
            dual("10 in 1..10", wrap("return 10 in 1..10"), true);
        }

        @Test void inRange_exclusive_end() throws Exception {
            dual("10 in 1..<10", wrap("return 10 in 1..<10"), false);
        }

        @Test void inRange_exclusive_lastInside() throws Exception {
            dual("9 in 1..<10", wrap("return 9 in 1..<10"), true);
        }

        @Test void inList() throws Exception {
            dual("3 in [1, 2, 3, 4]", wrap("return 3 in [1, 2, 3, 4]"), true);
        }

        @Test void notInList() throws Exception {
            dual("5 !in [1, 2, 3]", wrap("return 5 !in [1, 2, 3]"), true);
        }

        @Test void inRange_negative() throws Exception {
            dual("-3 in -5..0", wrap("return -3 in -5..0"), true);
        }

        @Test void in_ifCondition() throws Exception {
            dual("val x = 5\nif (x in 1..10) \"yes\" else \"no\"",
                 wrap("val x = 5\n    return if (x in 1..10) \"yes\" else \"no\""), "yes");
        }

        @Test void in_whenNoSubject() throws Exception {
            String code = "val h = 14\nwhen {\n  h in 0..<6 -> \"night\"\n  h in 6..<12 -> \"morning\"\n  h in 12..<18 -> \"afternoon\"\n  else -> \"evening\"\n}";
            dual(code, wrap("val h = 14\n    return when {\n      h in 0..<6 -> \"night\"\n      h in 6..<12 -> \"morning\"\n      h in 12..<18 -> \"afternoon\"\n      else -> \"evening\"\n    }"), "afternoon");
        }

        @Test void in_valAssignment() throws Exception {
            dual("val b = 3 in 1..5\nb", wrap("val b = 3 in 1..5\n    return b"), true);
        }

        @Test void in_combinedWithLogical() throws Exception {
            dual("val x = 5\nx in 1..10 && x !in 3..4",
                 wrap("val x = 5\n    return x in 1..10 && x !in 3..4"), true);
        }
    }

    // ============ 可空原始类型编译 ============

    @Nested
    @DisplayName("可空原始类型")
    class NullablePrimitiveTests {

        @Test void nullableInt_withValue() throws Exception {
            dual("fun f(v: Int?): Int { return v ?: -1 }\nf(42)",
                 wrap("fun f(v: Int?): Int { return v ?: -1 }\n    return f(42)"), 42);
        }

        @Test void nullableInt_withNull() throws Exception {
            dual("fun f(v: Int?): Int { return v ?: -1 }\nf(null)",
                 wrap("fun f(v: Int?): Int { return v ?: -1 }\n    return f(null)"), -1);
        }

        @Test void nullableInt_guardVal() throws Exception {
            String fn = "fun safe(v: Int?): String {\n  guard val x = v else { return \"null\" }\n  return \"val=\" + x\n}\n";
            dual(fn + "safe(42)", wrap(fn + "    return safe(42)"), "val=42");
        }

        @Test void nullableInt_guardVal_null() throws Exception {
            String fn = "fun safe(v: Int?): String {\n  guard val x = v else { return \"null\" }\n  return \"val=\" + x\n}\n";
            dual(fn + "safe(null)", wrap(fn + "    return safe(null)"), "null");
        }

        @Test void nullableLong_withNull() throws Exception {
            dual("fun f(v: Long?): Long { return v ?: -1L }\nf(null)",
                 wrap("fun f(v: Long?): Long { return v ?: -1L }\n    return f(null)"), -1L);
        }

        @Test void nullableBoolean_withNull() throws Exception {
            dual("fun f(v: Boolean?): Boolean { return v ?: false }\nf(null)",
                 wrap("fun f(v: Boolean?): Boolean { return v ?: false }\n    return f(null)"), false);
        }

        @Test void nullableInt_arithmetic() throws Exception {
            dual("fun add(a: Int?, b: Int?): Int { return (a ?: 0) + (b ?: 0) }\nadd(3, null)",
                 wrap("fun add(a: Int?, b: Int?): Int { return (a ?: 0) + (b ?: 0) }\n    return add(3, null)"), 3);
        }
    }

    // ============ 用户类遮蔽内置类型 + nova. 限定名 ============

    @Nested
    @DisplayName("类型遮蔽与 nova. 限定名")
    class TypeShadowingTests {

        @Test void userClass_shadowsResult() throws Exception {
            String code = ""
                + "sealed class Result\n"
                + "class Success(val data: String) : Result()\n"
                + "class Error(val message: String) : Result()\n"
                + "val r: Result = Success(\"ok\")\n"
                + "r is Success";
            dual(code, wrap(code.replace("r is Success", "return r is Success")), true);
        }

        @Test void userClass_shadowResult_whenMatch() throws Exception {
            String code = ""
                + "sealed class Result\n"
                + "class Success(val data: String) : Result()\n"
                + "class Error(val msg: String) : Result()\n"
                + "fun handle(r: Result): String = when (r) {\n"
                + "    is Success -> r.data\n"
                + "    is Error -> r.msg\n"
                + "    else -> \"unknown\"\n"
                + "}\n"
                + "handle(Success(\"hello\"))";
            dual(code, wrap(code.replace("handle(Success(\"hello\"))",
                 "return handle(Success(\"hello\"))")), "hello");
        }

        @Test void userClass_shadowResult_errorBranch() throws Exception {
            String code = ""
                + "sealed class Result\n"
                + "class Success(val data: String) : Result()\n"
                + "class Error(val msg: String) : Result()\n"
                + "fun handle(r: Result): String = when (r) {\n"
                + "    is Success -> r.data\n"
                + "    is Error -> r.msg\n"
                + "    else -> \"unknown\"\n"
                + "}\n"
                + "handle(Error(\"fail\"))";
            dual(code, wrap(code.replace("handle(Error(\"fail\"))",
                 "return handle(Error(\"fail\"))")), "fail");
        }

        @Test void builtinResult_stillWorks() throws Exception {
            dual("Ok(42).value", wrap("return Ok(42).value"), 42);
        }

        @Test void builtinResult_errStillWorks() throws Exception {
            dual("Err(\"x\").error", wrap("return Err(\"x\").error"), "x");
        }

        @Test void builtinResult_isOk_afterShadow() throws Exception {
            String code = ""
                + "sealed class Result\n"
                + "class Success(val data: String) : Result()\n"
                + "Ok(100) is Ok";
            dual(code, wrap(code.replace("Ok(100) is Ok", "return Ok(100) is Ok")), true);
        }

        // ---- nova. 限定名访问内置类型 ----

        @Test void novaQualified_isInt() throws Exception {
            dual("42 is nova.Int", wrap("return 42 is nova.Int"), true);
        }

        @Test void novaQualified_isString() throws Exception {
            dual("\"hello\" is nova.String", wrap("return \"hello\" is nova.String"), true);
        }

        @Test void novaQualified_isNotInt() throws Exception {
            dual("\"abc\" !is nova.Int", wrap("return \"abc\" !is nova.Int"), true);
        }

        @Test void novaQualified_isBoolean() throws Exception {
            dual("true is nova.Boolean", wrap("return true is nova.Boolean"), true);
        }

        @Test void novaQualified_isResult_ok() throws Exception {
            dual("Ok(1) is nova.Result", wrap("return Ok(1) is nova.Result"), true);
        }

        @Test void novaQualified_isResult_err() throws Exception {
            dual("Err(\"x\") is nova.Result", wrap("return Err(\"x\") is nova.Result"), true);
        }

        @Test void novaQualified_isOk() throws Exception {
            dual("Ok(1) is nova.Ok", wrap("return Ok(1) is nova.Ok"), true);
        }

        @Test void novaQualified_isErr() throws Exception {
            dual("Err(\"x\") is nova.Err", wrap("return Err(\"x\") is nova.Err"), true);
        }

        @Test void novaQualified_intNotResult() throws Exception {
            dual("42 !is nova.Result", wrap("return 42 !is nova.Result"), true);
        }

        @Test void novaQualified_disambiguate_userAndBuiltin() throws Exception {
            String code = ""
                + "sealed class Result\n"
                + "class Success(val v: Int) : Result()\n"
                + "val userR: Result = Success(1)\n"
                + "val builtinR = Ok(2)\n"
                + "\"\" + (userR is Result) + \",\" + (builtinR is nova.Result)";
            dual(code, wrap(code.replace(
                 "\"\" + (userR is Result) + \",\" + (builtinR is nova.Result)",
                 "return \"\" + (userR is Result) + \",\" + (builtinR is nova.Result)")),
                 "true,true");
        }

        @Test void novaQualified_isList() throws Exception {
            dual("[1, 2] is nova.List", wrap("return [1, 2] is nova.List"), true);
        }

        @Test void novaQualified_isMap() throws Exception {
            dual("val m = #{\"a\": 1}\nm is nova.Map",
                 wrap("val m = #{\"a\": 1}\n    return m is nova.Map"), true);
        }
    }

    // ============ 分号语句分隔符 ============

    @Nested
    @DisplayName("分号语句分隔符")
    class SemicolonTests {

        @Test void semicolon_twoStatements() throws Exception {
            dual("var x = 1; x = x + 1; x",
                 wrap("var x = 1; x = x + 1; return x"), 2);
        }

        @Test void semicolon_multipleVals() throws Exception {
            dual("val a = 10; val b = 20; a + b",
                 wrap("val a = 10; val b = 20; return a + b"), 30);
        }

        @Test void semicolon_mixedWithNewline() throws Exception {
            dual("val a = 1; val b = 2\nval c = 3; a + b + c",
                 wrap("val a = 1; val b = 2\n    val c = 3; return a + b + c"), 6);
        }

        @Test void semicolon_inWhenBranches() throws Exception {
            String code = "val x = 2\nwhen (x) { 1 -> \"one\" ; 2 -> \"two\" ; else -> \"other\" }";
            dual(code, wrap("val x = 2\n    return when (x) { 1 -> \"one\" ; 2 -> \"two\" ; else -> \"other\" }"), "two");
        }

        @Test void semicolon_inWhenNoSubject_withIn() throws Exception {
            String code = "val h = 8\nwhen { h in 0..<6 -> \"night\" ; h in 6..<12 -> \"morning\" ; else -> \"other\" }";
            dual(code, wrap("val h = 8\n    return when { h in 0..<6 -> \"night\" ; h in 6..<12 -> \"morning\" ; else -> \"other\" }"), "morning");
        }

        @Test void semicolon_emptyBetween() throws Exception {
            dual("val a = 5;; val b = 10; a + b",
                 wrap("val a = 5;; val b = 10; return a + b"), 15);
        }

        @Test void semicolon_trailingSemicolon() throws Exception {
            dual("val x = 42;", wrap("val x = 42;\n    return x"), 42);
        }
    }

    // ============ joinToString 重载 ============

    @Nested
    @DisplayName("joinToString 重载")
    class JoinToStringTests {

        @Test void joinToString_noArgs() throws Exception {
            dual("[1, 2, 3].joinToString()",
                 wrap("return [1, 2, 3].joinToString()"), "1, 2, 3");
        }

        @Test void joinToString_separator() throws Exception {
            dual("[1, 2, 3].joinToString(\"-\")",
                 wrap("return [1, 2, 3].joinToString(\"-\")"), "1-2-3");
        }

        @Test void joinToString_separatorAndPrefix() throws Exception {
            dual("[1, 2, 3].joinToString(\", \", \"[\")",
                 wrap("return [1, 2, 3].joinToString(\", \", \"[\")"), "[1, 2, 3");
        }

        @Test void joinToString_separatorPrefixPostfix() throws Exception {
            dual("[1, 2, 3].joinToString(\", \", \"[\", \"]\")",
                 wrap("return [1, 2, 3].joinToString(\", \", \"[\", \"]\")"), "[1, 2, 3]");
        }

        @Test void joinToString_emptyList() throws Exception {
            dual("[].joinToString(\"-\")", wrap("return [].joinToString(\"-\")"), "");
        }

        @Test void joinToString_singleElement() throws Exception {
            dual("[42].joinToString(\"-\")", wrap("return [42].joinToString(\"-\")"), "42");
        }

        @Test void joinToString_emptyListWithPrefixPostfix() throws Exception {
            dual("[].joinToString(\", \", \"(\", \")\")",
                 wrap("return [].joinToString(\", \", \"(\", \")\")"), "()");
        }
    }

    // ============ toList 扩展方法 ============

    @Nested
    @DisplayName("toList 扩展方法")
    class ToListTests {

        @Test void toList_fromRange() throws Exception {
            dual("(1..3).toList().joinToString(\", \")",
                 wrap("return (1..3).toList().joinToString(\", \")"), "1, 2, 3");
        }

        @Test void toList_fromList_isCopy() throws Exception {
            dual("val a = [1, 2]\nval b = a.toList()\na.add(3)\nb.size()",
                 wrap("val a = [1, 2]\n    val b = a.toList()\n    a.add(3)\n    return b.size()"), 2);
        }
    }

    // ============ Spread 操作符 ============

    @Nested
    @DisplayName("Spread 操作符 (.. 和 *)")
    class SpreadOperatorTests {

        // ---- 正常值 ----

        @Test void dotdot_spreadMiddle() throws Exception {
            dual("val a = [1, 2]\n[0, ..a, 3].joinToString(\", \")",
                 wrap("val a = [1, 2]\n    return [0, ..a, 3].joinToString(\", \")"), "0, 1, 2, 3");
        }

        @Test void dotdot_spreadMultiple() throws Exception {
            dual("val a = [1, 2]\nval b = [3, 4]\n[..a, ..b].joinToString(\", \")",
                 wrap("val a = [1, 2]\n    val b = [3, 4]\n    return [..a, ..b].joinToString(\", \")"), "1, 2, 3, 4");
        }

        @Test void dotdot_spreadOnly() throws Exception {
            dual("val a = [10, 20]\n[..a].joinToString(\", \")",
                 wrap("val a = [10, 20]\n    return [..a].joinToString(\", \")"), "10, 20");
        }

        @Test void star_spreadStillWorks() throws Exception {
            dual("val a = [1, 2]\n[0, *a, 3].joinToString(\", \")",
                 wrap("val a = [1, 2]\n    return [0, *a, 3].joinToString(\", \")"), "0, 1, 2, 3");
        }

        @Test void dotdot_mixedWithStar() throws Exception {
            dual("val a = [1]\nval b = [2]\n[..a, *b].joinToString(\", \")",
                 wrap("val a = [1]\n    val b = [2]\n    return [..a, *b].joinToString(\", \")"), "1, 2");
        }

        // ---- 边缘值 ----

        @Test void dotdot_emptyList() throws Exception {
            dual("val a = []\n[..a].size()",
                 wrap("val a = []\n    return [..a].size()"), 0);
        }

        @Test void dotdot_emptyListInMiddle() throws Exception {
            dual("val a = []\n[1, ..a, 2].joinToString(\", \")",
                 wrap("val a = []\n    return [1, ..a, 2].joinToString(\", \")"), "1, 2");
        }

        @Test void dotdot_singleElement() throws Exception {
            dual("val a = [42]\n[..a].joinToString(\", \")",
                 wrap("val a = [42]\n    return [..a].joinToString(\", \")"), "42");
        }

        @Test void dotdot_inlineList() throws Exception {
            dual("[1, ..[2, 3], 4].joinToString(\", \")",
                 wrap("return [1, ..[2, 3], 4].joinToString(\", \")"), "1, 2, 3, 4");
        }

        @Test void dotdot_noSpreadElements() throws Exception {
            dual("[1, 2, 3].joinToString(\", \")",
                 wrap("return [1, 2, 3].joinToString(\", \")"), "1, 2, 3");
        }
    }

    // ============ 嵌套类定义（函数/方法体内声明类） ============

    @Nested
    @DisplayName("嵌套类定义提升")
    class NestedClassHoistTests {

        @Test void classInsideFunction() throws Exception {
            dual("class Foo(val x: Int)\nFoo(42).x",
                 wrap("class Foo(val x: Int)\n    return Foo(42).x"), 42);
        }

        @Test void classInsideFunction_withMethod() throws Exception {
            dual("class Calc(val v: Int) {\n  fun double(): Int = v * 2\n}\nCalc(5).double()",
                 wrap("class Calc(val v: Int) {\n      fun double(): Int = v * 2\n    }\n    return Calc(5).double()"), 10);
        }

        @Test void multipleClassesInsideFunction() throws Exception {
            String code = ""
                + "class A(val n: Int)\n"
                + "class B(val n: Int)\n"
                + "A(10).n + B(20).n";
            dual(code, wrap(code.replace("A(10).n + B(20).n", "return A(10).n + B(20).n")), 30);
        }

        @Test void inheritanceInsideFunction() throws Exception {
            String code = ""
                + "open class Base(val v: Int)\n"
                + "class Child(val extra: Int) : Base(extra * 2)\n"
                + "Child(5).v";
            dual(code, wrap(code.replace("Child(5).v", "return Child(5).v")), 10);
        }

        @Test void sealedClassInsideFunction() throws Exception {
            String code = ""
                + "sealed class Shape\n"
                + "class Circle(val r: Int) : Shape()\n"
                + "class Rect(val w: Int, val h: Int) : Shape()\n"
                + "val s: Shape = Rect(3, 4)\n"
                + "when (s) {\n"
                + "    is Circle -> s.r\n"
                + "    is Rect -> s.w * s.h\n"
                + "    else -> 0\n"
                + "}";
            dual(code, wrap(code.replace(
                 "when (s) {", "return when (s) {")), 12);
        }

        @Test void classInsideObjectMethod_isCheck() throws Exception {
            String code = ""
                + "class Tag(val name: String)\n"
                + "val t: Any = Tag(\"hello\")\n"
                + "t is Tag";
            dual(code, wrap(code.replace("t is Tag", "return t is Tag")), true);
        }

        @Test void classWithWhenMatch() throws Exception {
            String code = ""
                + "sealed class Result\n"
                + "class Ok(val v: Int) : Result()\n"
                + "class Fail(val msg: String) : Result()\n"
                + "fun check(r: Result): String = when (r) {\n"
                + "    is Ok -> \"ok:\" + r.v\n"
                + "    is Fail -> \"fail:\" + r.msg\n"
                + "    else -> \"?\"\n"
                + "}\n"
                + "check(Ok(42))";
            dual(code, wrap(code.replace("check(Ok(42))", "return check(Ok(42))")), "ok:42");
        }
    }
}
