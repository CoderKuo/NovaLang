package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 基于名称的解构声明测试。
 * 语法: val (localVar = propertyName, ...) = expr
 */
class NameBasedDestructuringTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    // ================================================================
    // 1. 基本名称解构
    // ================================================================

    @Nested
    @DisplayName("基本名称解构")
    class BasicNameBased {

        @Test
        @DisplayName("按名称解构 data class — 重命名")
        void testNameBasedRename() {
            eval("@data class User(val username: String, val email: String)");
            eval("val user = User(\"alice\", \"alice@example.com\")");
            eval("val (mail = email, name = username) = user");
            assertEquals("alice", eval("name").asString());
            assertEquals("alice@example.com", eval("mail").asString());
        }

        @Test
        @DisplayName("按名称解构 — 同名绑定")
        void testNameBasedSameName() {
            eval("@data class Point(val x: Int, val y: Int)");
            eval("val p = Point(10, 20)");
            eval("val (x = x, y = y) = p");
            assertEquals(10, eval("x").asInt());
            assertEquals(20, eval("y").asInt());
        }

        @Test
        @DisplayName("按名称解构 — 三个字段")
        void testNameBasedThreeFields() {
            eval("@data class Vec3(val x: Int, val y: Int, val z: Int)");
            eval("val v = Vec3(1, 2, 3)");
            eval("val (a = x, b = y, c = z) = v");
            assertEquals(1, eval("a").asInt());
            assertEquals(2, eval("b").asInt());
            assertEquals(3, eval("c").asInt());
        }
    }

    // ================================================================
    // 2. 不同顺序（核心优势）
    // ================================================================

    @Nested
    @DisplayName("顺序无关")
    class OrderIndependent {

        @Test
        @DisplayName("反转顺序")
        void testReversedOrder() {
            eval("@data class User(val username: String, val email: String)");
            eval("val user = User(\"alice\", \"alice@example.com\")");
            // 按反转顺序访问
            eval("val (name = username, mail = email) = user");
            assertEquals("alice", eval("name").asString());
            assertEquals("alice@example.com", eval("mail").asString());
        }

        @Test
        @DisplayName("三字段任意顺序")
        void testArbitraryOrder() {
            eval("@data class Point(val x: Int, val y: Int, val z: Int)");
            eval("val p = Point(1, 2, 3)");
            eval("val (c = z, a = x, b = y) = p");
            assertEquals(1, eval("a").asInt());
            assertEquals(2, eval("b").asInt());
            assertEquals(3, eval("c").asInt());
        }

        @Test
        @DisplayName("只取部分字段 — 按名称")
        void testPartialFields() {
            eval("@data class Config(val host: String, val port: Int, val debug: Boolean)");
            eval("val cfg = Config(\"localhost\", 8080, true)");
            eval("val (p = port, d = debug) = cfg");
            assertEquals(8080, eval("p").asInt());
            assertTrue(eval("d").asBool());
        }
    }

    // ================================================================
    // 3. 混合模式（名称 + 位置）
    // ================================================================

    @Nested
    @DisplayName("混合模式")
    class MixedMode {

        @Test
        @DisplayName("第一个名称，第二个位置")
        void testNameThenPosition() {
            eval("@data class Pair(val first: Int, val second: Int)");
            eval("val p = Pair(10, 20)");
            eval("val (a = first, b) = p");
            assertEquals(10, eval("a").asInt());
            // b 是位置解构 component2 = second = 20
            assertEquals(20, eval("b").asInt());
        }

        @Test
        @DisplayName("第一个位置，第二个名称")
        void testPositionThenName() {
            eval("@data class User(val username: String, val email: String)");
            eval("val user = User(\"alice\", \"alice@example.com\")");
            eval("val (first, mail = email) = user");
            // first = component1 = username = "alice"
            assertEquals("alice", eval("first").asString());
            assertEquals("alice@example.com", eval("mail").asString());
        }
    }

    // ================================================================
    // 4. 带跳过 (_)
    // ================================================================

    @Nested
    @DisplayName("带跳过")
    class WithSkip {

        @Test
        @DisplayName("跳过 + 名称解构")
        void testSkipThenNameBased() {
            eval("@data class User(val username: String, val email: String)");
            eval("val user = User(\"alice\", \"alice@example.com\")");
            eval("val (_, mail = email) = user");
            assertEquals("alice@example.com", eval("mail").asString());
        }

        @Test
        @DisplayName("名称解构 + 跳过")
        void testNameBasedThenSkip() {
            eval("@data class User(val username: String, val email: String)");
            eval("val user = User(\"alice\", \"alice@example.com\")");
            eval("val (name = username, _) = user");
            assertEquals("alice", eval("name").asString());
        }
    }

    // ================================================================
    // 5. For 循环名称解构
    // ================================================================

    @Nested
    @DisplayName("For 循环")
    class ForLoop {

        @Test
        @DisplayName("for 循环名称解构")
        void testForLoopNameBased() {
            eval("@data class User(val username: String, val email: String)");
            eval("val users = listOf(User(\"alice\", \"a@e\"), User(\"bob\", \"b@e\"))");
            eval("var result = \"\"");
            eval("for ((name = username, mail = email) in users) {\n"
                + "    result = result + name + \":\" + mail + \" \"\n"
                + "}");
            assertTrue(eval("result").asString().contains("alice:a@e"));
            assertTrue(eval("result").asString().contains("bob:b@e"));
        }

        @Test
        @DisplayName("for 循环反转顺序")
        void testForLoopReversedOrder() {
            eval("@data class Item(val name: String, val price: Int)");
            eval("val items = listOf(Item(\"a\", 10), Item(\"b\", 20))");
            eval("var total = 0");
            eval("for ((cost = price, label = name) in items) {\n"
                + "    total = total + cost\n"
                + "}");
            assertEquals(30, eval("total").asInt());
        }
    }

    // ================================================================
    // 6. 向后兼容 — 位置解构不受影响
    // ================================================================

    @Nested
    @DisplayName("向后兼容")
    class BackwardCompatibility {

        @Test
        @DisplayName("位置解构 Pair")
        void testPositionPair() {
            eval("val (a, b) = Pair(1, 2)");
            assertEquals(1, eval("a").asInt());
            assertEquals(2, eval("b").asInt());
        }

        @Test
        @DisplayName("位置解构 data class")
        void testPositionDataClass() {
            eval("@data class Point(val x: Int, val y: Int)");
            eval("val (a, b) = Point(10, 20)");
            assertEquals(10, eval("a").asInt());
            assertEquals(20, eval("b").asInt());
        }

        @Test
        @DisplayName("位置解构带跳过")
        void testPositionWithSkip() {
            eval("@data class Triple(val a: Int, val b: Int, val c: Int)");
            eval("val (_, second, _) = Triple(1, 2, 3)");
            assertEquals(2, eval("second").asInt());
        }

        @Test
        @DisplayName("for 循环位置解构 Map")
        void testForPositionMap() {
            eval("var result = \"\"");
            eval("for ((k, v) in mapOf(\"x\" to 1)) result = k + \"=\" + v");
            assertEquals("x=1", eval("result").asString());
        }

        @Test
        @DisplayName("for 循环单变量")
        void testForSingleVariable() {
            eval("var sum = 0");
            eval("for (i in [1,2,3]) sum = sum + i");
            assertEquals(6, eval("sum").asInt());
        }
    }

    // ================================================================
    // 7. 非 data class 的名称解构（普通类字段访问）
    // ================================================================

    @Nested
    @DisplayName("非 data class")
    class NonDataClass {

        @Test
        @DisplayName("普通类名称解构")
        void testNonDataClassNameBased() {
            eval("class Config(val host: String, val port: Int)");
            eval("val cfg = Config(\"localhost\", 8080)");
            eval("val (h = host, p = port) = cfg");
            assertEquals("localhost", eval("h").asString());
            assertEquals(8080, eval("p").asInt());
        }
    }
}
