package com.novalang.runtime.codegen;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Non-local break/continue 编译模式测试。
 */
@DisplayName("Non-local break/continue 编译模式")
class NonLocalBreakContinueCodegenTest {

    private static Object run(String code) {
        return new Nova().compileToBytecode(code, "test.nova").run();
    }

    @Test void forEachContinue() {
        assertEquals(6, run(
                "var sum = 0\n" +
                "[1, 2, 0, 3].forEach { if (it == 0) continue; sum = sum + it }\nsum"));
    }

    @Test void forEachBreak() {
        assertEquals(3, run(
                "var sum = 0\n" +
                "[1, 2, 3, 4, 5].forEach { if (it == 3) break; sum = sum + it }\nsum"));
    }

    @Test void forEachContinueMultiple() {
        assertEquals("135", run(
                "var r = \"\"\n" +
                "[1,2,3,4,5].forEach { if (it % 2 == 0) continue; r = r + it }\nr"));
    }

    @Test void forEachBreakImmediate() {
        assertEquals(false, run(
                "var called = false\n" +
                "[1,2,3].forEach { val x = it; break; called = true }\ncalled"));
    }

    @Test void nestedForEachBreak() {
        assertEquals("1a 2a ", run(
                "var result = \"\"\n" +
                "[1, 2].forEach { outer ->\n" +
                "    [\"a\", \"b\", \"c\"].forEach { inner ->\n" +
                "        if (inner == \"b\") break\n" +
                "        result = result + outer + inner + \" \"\n" +
                "    }\n" +
                "}\nresult"));
    }

    @Test void returnAtLabelStillWorks() {
        assertEquals(6, run(
                "var sum = 0\n" +
                "[1, 2, 0, 3].forEach { if (it == 0) return@forEach; sum = sum + it }\nsum"));
    }
}
