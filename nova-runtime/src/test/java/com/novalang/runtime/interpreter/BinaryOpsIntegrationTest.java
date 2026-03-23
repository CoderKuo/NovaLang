package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BinaryOps 集成测试 — 通过 Nova 引擎 eval 执行，验证二元运算在完整解释器路径上的正确性。
 */
class BinaryOpsIntegrationTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    // ============ 1. 混合类型算术 ============

    @Nested
    @DisplayName("混合类型算术")
    class MixedTypeArithmeticTests {

        @Test
        @DisplayName("Int + Double 提升为 Double")
        void testIntPlusDouble() {
            NovaValue result = eval("2 + 3.5");
            assertEquals(5.5, result.asDouble(), 0.001);
        }

        @Test
        @DisplayName("Long + Int 提升为 Long")
        void testLongPlusInt() {
            NovaValue result = eval("10L + 5");
            assertEquals(15L, result.asLong());
        }

        @Test
        @DisplayName("Float + Int 提升为 Float")
        void testFloatPlusInt() {
            NovaValue result = eval("1.5f + 2");
            assertEquals(3.5, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("Float + Double 提升为 Double")
        void testFloatPlusDouble() {
            NovaValue result = eval("1.5f + 2.5");
            assertEquals(4.0, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("Long * Double 提升为 Double")
        void testLongTimesDouble() {
            NovaValue result = eval("3L * 2.5");
            assertEquals(7.5, result.asDouble(), 0.001);
        }

        @Test
        @DisplayName("Int - Double 提升为 Double")
        void testIntMinusDouble() {
            NovaValue result = eval("10 - 3.5");
            assertEquals(6.5, result.asDouble(), 0.001);
        }

        @Test
        @DisplayName("Float * Float 保持 Float")
        void testFloatTimesFloat() {
            NovaValue result = eval("2.0f * 3.0f");
            assertEquals(6.0, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("Int / Double 提升为 Double")
        void testIntDivDouble() {
            NovaValue result = eval("7 / 2.0");
            assertEquals(3.5, result.asDouble(), 0.001);
        }
    }

    // ============ 2. 运算符重载 ============

    @Nested
    @DisplayName("运算符重载")
    class OperatorOverloadTests {

        @BeforeEach
        void defineVec() {
            eval("class Vec(val x: Int, val y: Int) {\n"
                    + "    fun plus(o) = Vec(x + o.x, y + o.y)\n"
                    + "    fun minus(o) = Vec(x - o.x, y - o.y)\n"
                    + "    fun times(s) = Vec(x * s, y * s)\n"
                    + "    fun div(s) = Vec(x / s, y / s)\n"
                    + "    fun rem(s) = Vec(x % s, y % s)\n"
                    + "    fun compareTo(o): Int = (x * x + y * y) - (o.x * o.x + o.y * o.y)\n"
                    + "    fun unaryPlus() = Vec(abs(x), abs(y))\n"
                    + "}");
        }

        @Test
        @DisplayName("plus 重载")
        void testPlus() {
            eval("val v = Vec(1, 2) + Vec(3, 4)");
            assertEquals(4, eval("v.x").asInt());
            assertEquals(6, eval("v.y").asInt());
        }

        @Test
        @DisplayName("minus 重载")
        void testMinus() {
            eval("val v = Vec(5, 7) - Vec(2, 3)");
            assertEquals(3, eval("v.x").asInt());
            assertEquals(4, eval("v.y").asInt());
        }

        @Test
        @DisplayName("times 运算符重载")
        void testTimes() {
            eval("val v = Vec(2, 3) * 4");
            assertEquals(8, eval("v.x").asInt());
            assertEquals(12, eval("v.y").asInt());
        }

        @Test
        @DisplayName("div 运算符重载")
        void testDiv() {
            eval("val v = Vec(10, 20) / 5");
            assertEquals(2, eval("v.x").asInt());
            assertEquals(4, eval("v.y").asInt());
        }

        @Test
        @DisplayName("rem 重载")
        void testRem() {
            eval("val v = Vec(10, 7) % 3");
            assertEquals(1, eval("v.x").asInt());
            assertEquals(1, eval("v.y").asInt());
        }

        @Test
        @DisplayName("compareTo 重载 — 比较运算符")
        void testCompareTo() {
            eval("val a = Vec(1, 0)");
            eval("val b = Vec(2, 0)");
            assertTrue(eval("a < b").asBool());
            assertTrue(eval("b > a").asBool());
            assertFalse(eval("a >= b").asBool());
        }

        @Test
        @DisplayName("unaryPlus 重载")
        void testUnaryPlus() {
            eval("val v = Vec(-3, -4)");
            eval("val pos = +v");
            assertEquals(3, eval("pos.x").asInt());
            assertEquals(4, eval("pos.y").asInt());
        }
    }

    // ============ 3. inc/dec 回退 ============

    @Nested
    @DisplayName("inc/dec 回退")
    class IncDecFallbackTests {

        @BeforeEach
        void defineCounter() {
            eval("class Counter(val value: Int) {\n"
                    + "    fun inc() = Counter(value + 1)\n"
                    + "    fun dec() = Counter(value - 1)\n"
                    + "}");
        }

        @Test
        @DisplayName("x + 1 调用 inc()")
        void testIncViaAdd() {
            eval("val c = Counter(5)");
            eval("val c2 = c + 1");
            assertEquals(6, eval("c2.value").asInt());
        }

        @Test
        @DisplayName("x - 1 调用 dec()")
        void testDecViaSub() {
            eval("val c = Counter(5)");
            eval("val c2 = c - 1");
            assertEquals(4, eval("c2.value").asInt());
        }

        @Test
        @DisplayName("后缀 ++ 调用 inc()")
        void testPostfixInc() {
            eval("var c = Counter(10)");
            eval("c++");
            assertEquals(11, eval("c.value").asInt());
        }

        @Test
        @DisplayName("后缀 -- 调用 dec()")
        void testPostfixDec() {
            eval("var c = Counter(10)");
            eval("c--");
            assertEquals(9, eval("c.value").asInt());
        }
    }

    // ============ 4. 字符串重复 ============

    @Nested
    @DisplayName("字符串重复")
    class StringRepeatTests {

        @Test
        @DisplayName("字符串 * 整数")
        void testStringTimesInt() {
            assertEquals("ababab", eval("\"ab\" * 3").asString());
        }

        @Test
        @DisplayName("整数 * 字符串")
        void testIntTimesString() {
            assertEquals("xyzxyz", eval("2 * \"xyz\"").asString());
        }

        @Test
        @DisplayName("字符串 * 0 返回空字符串")
        void testStringTimesZero() {
            // 诊断：先测试 repeat 方法直接调用
            assertEquals("", eval("\"ab\".repeat(0)").asString());
            // 然后测试运算符
            assertEquals("", eval("\"ab\" * 0").asString());
        }

        @Test
        @DisplayName("字符串 * 1 返回原字符串")
        void testStringTimesOne() {
            assertEquals("abc", eval("\"abc\" * 1").asString());
        }
    }

    // ============ 5. 列表连接 ============

    @Nested
    @DisplayName("列表连接")
    class ListConcatTests {

        @Test
        @DisplayName("[1,2] + [3,4] 返回 [1,2,3,4]")
        void testBasicConcat() {
            eval("val result = [1, 2] + [3, 4]");
            assertEquals(4, eval("result.size()").asInt());
            assertEquals(1, eval("result[0]").asInt());
            assertEquals(4, eval("result[3]").asInt());
        }

        @Test
        @DisplayName("空列表连接")
        void testEmptyConcat() {
            eval("val result = [] + [1, 2]");
            assertEquals(2, eval("result.size()").asInt());
        }

        @Test
        @DisplayName("混合类型列表连接")
        void testMixedTypeConcat() {
            eval("val result = [1, \"a\"] + [true, 3.14]");
            assertEquals(4, eval("result.size()").asInt());
            assertEquals("a", eval("result[1]").asString());
            assertTrue(eval("result[2]").asBool());
        }
    }

    // ============ 6. Map 合并 ============

    @Nested
    @DisplayName("Map 合并")
    class MapMergeTests {

        @Test
        @DisplayName("mutableMapOf putAll 合并")
        void testMapPutAll() {
            eval("val m1 = mutableMapOf(\"a\" to 1)");
            eval("val m2 = mapOf(\"b\" to 2)");
            eval("m1.putAll(m2)");
            assertEquals(2, eval("m1.size()").asInt());
            assertEquals(1, eval("m1[\"a\"]").asInt());
            assertEquals(2, eval("m1[\"b\"]").asInt());
        }

        @Test
        @DisplayName("putAll 覆盖已有键")
        void testMapPutAllOverwrite() {
            eval("val m = mutableMapOf(\"a\" to 1, \"b\" to 2)");
            eval("m.putAll(mapOf(\"b\" to 99, \"c\" to 3))");
            assertEquals(3, eval("m.size()").asInt());
            assertEquals(99, eval("m[\"b\"]").asInt());
            assertEquals(3, eval("m[\"c\"]").asInt());
        }
    }

    // ============ 7. 比较运算符 ============

    @Nested
    @DisplayName("比较运算符（混合数值类型）")
    class ComparisonTests {

        @Test
        @DisplayName("Int < Double")
        void testIntLtDouble() {
            assertTrue(eval("2 < 3.5").asBool());
            assertFalse(eval("4 < 3.5").asBool());
        }

        @Test
        @DisplayName("Long > Int")
        void testLongGtInt() {
            assertTrue(eval("10L > 5").asBool());
            assertFalse(eval("3L > 5").asBool());
        }

        @Test
        @DisplayName("Float <= Double")
        void testFloatLeDouble() {
            assertTrue(eval("1.0f <= 1.0").asBool());
            assertTrue(eval("0.5f <= 1.0").asBool());
            assertFalse(eval("1.5f <= 1.0").asBool());
        }

        @Test
        @DisplayName("Long >= Long")
        void testLongGeLong() {
            assertTrue(eval("10L >= 10L").asBool());
            assertTrue(eval("11L >= 10L").asBool());
            assertFalse(eval("9L >= 10L").asBool());
        }

        @Test
        @DisplayName("Float < Int")
        void testFloatLtInt() {
            assertTrue(eval("0.5f < 1").asBool());
            assertFalse(eval("1.5f < 1").asBool());
        }

        @Test
        @DisplayName("Int > Long")
        void testIntGtLong() {
            assertFalse(eval("5 > 10L").asBool());
            assertTrue(eval("15 > 10L").asBool());
        }
    }

    // ============ 8. 相等性 ============

    @Nested
    @DisplayName("跨类型相等性")
    class EqualityTests {

        @Test
        @DisplayName("Int == Long 值相等")
        void testIntEqualsLong() {
            assertTrue(eval("42 == 42L").asBool());
        }

        @Test
        @DisplayName("Int != Long 值不等")
        void testIntNotEqualsLong() {
            assertTrue(eval("42 != 43L").asBool());
            assertFalse(eval("42 != 42L").asBool());
        }

        @Test
        @DisplayName("Float == Double 值相等")
        void testFloatEqualsDouble() {
            assertTrue(eval("1.0f == 1.0").asBool());
        }

        @Test
        @DisplayName("Int == Double 值相等")
        void testIntEqualsDouble() {
            assertTrue(eval("5 == 5.0").asBool());
        }

        @Test
        @DisplayName("不同值 != 返回 true")
        void testNotEquals() {
            assertTrue(eval("1 != 2.0").asBool());
            assertTrue(eval("1L != 2").asBool());
        }

        @Test
        @DisplayName("null 相等性")
        void testNullEquality() {
            assertTrue(eval("null == null").asBool());
            assertFalse(eval("null == 1").asBool());
            assertFalse(eval("1 == null").asBool());
            assertTrue(eval("1 != null").asBool());
        }
    }

    // ============ 9. 一元正号 ============

    @Nested
    @DisplayName("一元正号")
    class UnaryPlusTests {

        @Test
        @DisplayName("+Int 返回自身")
        void testUnaryPlusInt() {
            assertEquals(42, eval("+42").asInt());
        }

        @Test
        @DisplayName("+(-Int) 保持负值")
        void testUnaryPlusNegativeInt() {
            eval("val x = -5");
            assertEquals(-5, eval("+x").asInt());
        }

        @Test
        @DisplayName("+Double 返回自身")
        void testUnaryPlusDouble() {
            assertEquals(3.14, eval("+3.14").asDouble(), 0.001);
        }

        @Test
        @DisplayName("自定义类 unaryPlus 重载")
        void testUnaryPlusOverload() {
            eval("class Abs(val v: Int) { fun unaryPlus() = Abs(abs(v)) }");
            eval("val a = Abs(-10)");
            eval("val b = +a");
            assertEquals(10, eval("b.v").asInt());
        }
    }

    // ============ 10. 取负 ============

    @Nested
    @DisplayName("一元取负")
    class NegationTests {

        @Test
        @DisplayName("-Int")
        void testNegateInt() {
            assertEquals(-5, eval("-5").asInt());
            eval("val x = 10");
            assertEquals(-10, eval("-x").asInt());
        }

        @Test
        @DisplayName("-Double")
        void testNegateDouble() {
            assertEquals(-3.14, eval("-3.14").asDouble(), 0.001);
        }

        @Test
        @DisplayName("-Long")
        void testNegateLong() {
            assertEquals(-100L, eval("-100L").asLong());
        }

        @Test
        @DisplayName("-Float")
        void testNegateFloat() {
            NovaValue result = eval("-2.5f");
            assertTrue(result.asDouble() < 0);
            assertEquals(-2.5, result.asDouble(), 0.01);
        }

        @Test
        @DisplayName("双重取负还原")
        void testDoubleNegation() {
            assertEquals(42, eval("--42").asInt());
        }

        @Test
        @DisplayName("自定义类 unaryMinus 重载")
        void testUnaryMinusOverload() {
            eval("class Vec(val x: Int, val y: Int) { fun unaryMinus() = Vec(-x, -y) }");
            eval("val v = Vec(3, -4)");
            eval("val neg = -v");
            assertEquals(-3, eval("neg.x").asInt());
            assertEquals(4, eval("neg.y").asInt());
        }
    }
}
