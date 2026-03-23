package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Non-local break/continue 测试。
 * 在 forEach 等 lambda 中使用 break/continue 跳出外层迭代。
 */
class NonLocalBreakContinueTest {

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
    // forEach + continue
    // ================================================================

    @Test
    @DisplayName("forEach 中 continue 跳过当前元素")
    void testForEachContinue() {
        eval("var sum = 0");
        eval("[1, 2, 0, 3].forEach { if (it == 0) continue; sum = sum + it }");
        assertEquals(6, eval("sum").asInt()); // 1+2+3
    }

    @Test
    @DisplayName("forEach 中 continue 跳过多个元素")
    void testForEachContinueMultiple() {
        eval("var result = \"\"");
        eval("[1, 2, 3, 4, 5].forEach { if (it % 2 == 0) continue; result = result + it }");
        assertEquals("135", eval("result").asString());
    }

    // ================================================================
    // forEach + break
    // ================================================================

    @Test
    @DisplayName("forEach 中 break 终止遍历")
    void testForEachBreak() {
        eval("var sum = 0");
        eval("[1, 2, 3, 4, 5].forEach { if (it == 3) break; sum = sum + it }");
        assertEquals(3, eval("sum").asInt()); // 1+2
    }

    @Test
    @DisplayName("forEach 中 break 第一个元素就终止")
    void testForEachBreakImmediate() {
        eval("var called = false");
        eval("[1, 2, 3].forEach { break; called = true }");
        assertFalse(eval("called").asBool());
    }

    // ================================================================
    // forEachIndexed + break/continue
    // ================================================================

    @Test
    @DisplayName("forEachIndexed 中 continue")
    void testForEachIndexedContinue() {
        eval("var result = \"\"");
        eval("[\"a\", \"b\", \"c\", \"d\"].forEachIndexed { i, v -> if (i % 2 != 0) continue; result = result + v }");
        assertEquals("ac", eval("result").asString());
    }

    @Test
    @DisplayName("forEachIndexed 中 break")
    void testForEachIndexedBreak() {
        eval("var count = 0");
        eval("[10, 20, 30, 40].forEachIndexed { i, v -> if (i == 2) break; count = count + 1 }");
        assertEquals(2, eval("count").asInt());
    }

    // ================================================================
    // return@label 仍然正常工作（向后兼容）
    // ================================================================

    @Test
    @DisplayName("return@forEach 仍然正常工作")
    void testReturnAtLabelStillWorks() {
        eval("var sum = 0");
        eval("[1, 2, 0, 3].forEach { if (it == 0) return@forEach; sum = sum + it }");
        assertEquals(6, eval("sum").asInt());
    }

    // ================================================================
    // 嵌套场景
    // ================================================================

    @Test
    @DisplayName("嵌套 forEach — 内层 break 不影响外层")
    void testNestedForEachBreak() {
        eval("var result = \"\"");
        eval("[1, 2].forEach { outer ->\n"
            + "    [\"a\", \"b\", \"c\"].forEach { inner ->\n"
            + "        if (inner == \"b\") break\n"
            + "        result = result + outer + inner + \" \"\n"
            + "    }\n"
            + "}");
        // 内层 break 在 inner=="b" 时终止内层 forEach，外层继续
        assertEquals("1a 2a ", eval("result").asString());
    }

    @Test
    @DisplayName("for 循环内 forEach 的 break 不影响外层 for")
    void testForEachBreakInsideForLoop() {
        eval("var result = \"\"");
        eval("for (i in 1..3) {\n"
            + "    [\"x\", \"y\", \"z\"].forEach {\n"
            + "        if (it == \"y\") break\n"
            + "        result = result + i + it + \" \"\n"
            + "    }\n"
            + "}");
        assertEquals("1x 2x 3x ", eval("result").asString());
    }

    // ================================================================
    // Map forEach + break/continue
    // ================================================================

    @Test
    @DisplayName("Map forEach 中 break")
    void testMapForEachBreak() {
        eval("var count = 0");
        eval("mapOf(\"a\" to 1, \"b\" to 2, \"c\" to 3).forEach { k, v -> if (v == 2) break; count = count + 1 }");
        assertEquals(1, eval("count").asInt());
    }
}
