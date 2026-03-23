package com.novalang.runtime.interpreter;
import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplBugTest {
    private Interpreter interpreter;
    @BeforeEach void setUp() { interpreter = new Interpreter(); interpreter.setReplMode(true); }
    private NovaValue eval(String code) { return interpreter.evalRepl(code); }

    @Test void intMulWorks() {
        assertEquals(6, eval("2 * 3").asInt());
    }
    @Test void stringMulWorks() {
        assertEquals("ababab", eval("\"ab\" * 3").asString());
    }
    @Test void classThenMul() {
        eval("class Vec(val x: Int, val y: Int) {\n  fun times(s) = Vec(x * s, y * s)\n}");
        // 第二次 evalRepl 中使用 * 运算符
        eval("val v = Vec(2, 3) * 4");
        assertEquals(8, eval("v.x").asInt());
    }
    @Test void simpleClassThenMul() {
        // 更简单的复现：定义类后在下一次 eval 中用 * 
        eval("class W(val n: Int)");
        assertEquals(6, eval("2 * 3").asInt()); // 纯 int * int 在类定义后还正常吗？
    }
    @Test void classThenDiv() {
        eval("class Vec2(val x: Int, val y: Int) {\n  fun div(s) = Vec2(x / s, y / s)\n}");
        eval("val v = Vec2(10, 20) / 5");
        assertEquals(2, eval("v.x").asInt());
    }
    @Test void classThenTimesAndDiv() {
        eval("class V3(val x: Int) {\n  fun times(s) = V3(x * s)\n  fun div(s) = V3(x / s)\n}");
        NovaValue t = eval("val t = V3(3) * 4\nt.x");
        assertEquals(12, t.asInt());
    }
}
