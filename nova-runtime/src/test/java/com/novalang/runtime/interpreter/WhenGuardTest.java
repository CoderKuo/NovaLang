package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * when 表达式 Guard Conditions 测试。
 * 语法: is Type if condition -> body
 */
class WhenGuardTest {

    private Interpreter interpreter;

    @BeforeEach
    void setUp() {
        interpreter = new Interpreter();
        interpreter.setReplMode(true);
    }

    private NovaValue eval(String code) {
        return interpreter.evalRepl(code);
    }

    @Test
    @DisplayName("基本 guard: is Type if condition")
    void testBasicGuard() {
        eval("open class Animal(val name: String)");
        eval("class Cat(name: String, val isHungry: Boolean) : Animal(name)");
        eval("class Dog(name: String) : Animal(name)");
        eval("fun handle(animal: Animal) = when (animal) {\n"
            + "    is Cat if !animal.isHungry -> \"feed cat\"\n"
            + "    is Cat -> \"hungry cat\"\n"
            + "    is Dog -> \"walk dog\"\n"
            + "    else -> \"unknown\"\n"
            + "}");
        assertEquals("feed cat", eval("handle(Cat(\"Kitty\", false))").asString());
        assertEquals("hungry cat", eval("handle(Cat(\"Tom\", true))").asString());
        assertEquals("walk dog", eval("handle(Dog(\"Rex\"))").asString());
    }

    @Test
    @DisplayName("guard 条件为 false 时跳到下一分支")
    void testGuardFallthrough() {
        eval("fun classify(n: Int) = when {\n"
            + "    n > 0 if n < 10 -> \"small positive\"\n"
            + "    n > 0 -> \"large positive\"\n"
            + "    else -> \"non-positive\"\n"
            + "}");
        assertEquals("small positive", eval("classify(5)").asString());
        assertEquals("large positive", eval("classify(100)").asString());
        assertEquals("non-positive", eval("classify(-1)").asString());
    }

    @Test
    @DisplayName("guard 在值匹配中使用 — 语句路径")
    void testGuardWithValueMatch() {
        eval("fun describe(x: Int): String {\n"
            + "    var result = \"\"\n"
            + "    when(x) {\n"
            + "        1 if false -> result = \"one-guarded\"\n"
            + "        1 -> result = \"one\"\n"
            + "        2 -> result = \"two\"\n"
            + "        else -> result = \"other\"\n"
            + "    }\n"
            + "    return result\n"
            + "}");
        // guard 为 false，应跳过第一个分支，匹配第二个
        assertEquals("one", eval("describe(1)").asString());
        assertEquals("two", eval("describe(2)").asString());
    }

    @Test
    @DisplayName("guard 在值匹配中使用 — 表达式路径")
    void testGuardWithValueMatchExpr() {
        // 直接用 when 表达式（非函数体）
        eval("val x = 1");
        NovaValue result = eval("when(x) {\n"
            + "    1 if false -> \"one-guarded\"\n"
            + "    1 -> \"one\"\n"
            + "    else -> \"other\"\n"
            + "}");
        assertEquals("one", result.asString());
    }

    @Test
    @DisplayName("guard 在 in range 中使用")
    void testGuardWithRange() {
        eval("fun grade(score: Int, bonus: Boolean) = when(score) {\n"
            + "    in 90..100 if bonus -> \"A+\"\n"
            + "    in 90..100 -> \"A\"\n"
            + "    in 80..89 -> \"B\"\n"
            + "    else -> \"C\"\n"
            + "}");
        assertEquals("A+", eval("grade(95, true)").asString());
        assertEquals("A", eval("grade(95, false)").asString());
        assertEquals("B", eval("grade(85, true)").asString());
    }

    @Test
    @DisplayName("guard 访问对象成员")
    void testGuardAccessMember() {
        eval("@data class User(val name: String, val age: Int)");
        eval("fun check(u: User) = when {\n"
            + "    u.age >= 18 if u.name == \"admin\" -> \"admin access\"\n"
            + "    u.age >= 18 -> \"normal access\"\n"
            + "    else -> \"denied\"\n"
            + "}");
        assertEquals("admin access", eval("check(User(\"admin\", 25))").asString());
        assertEquals("normal access", eval("check(User(\"bob\", 25))").asString());
        assertEquals("denied", eval("check(User(\"admin\", 15))").asString());
    }

    @Test
    @DisplayName("sealed + data object + guard")
    void testSealedWithGuard() {
        eval("sealed interface Event");
        eval("@data class Click(val x: Int, val y: Int) : Event");
        eval("@data object Dismiss : Event");
        eval("fun handle(e: Event) = when(e) {\n"
            + "    is Click if e.x > 100 -> \"right click\"\n"
            + "    is Click -> \"left click\"\n"
            + "    is Dismiss -> \"dismissed\"\n"
            + "    else -> \"?\"\n"
            + "}");
        assertEquals("right click", eval("handle(Click(200, 50))").asString());
        assertEquals("left click", eval("handle(Click(50, 50))").asString());
        assertEquals("dismissed", eval("handle(Dismiss)").asString());
    }

    @Test
    @DisplayName("向后兼容 — 无 guard 的 when 不受影响")
    void testBackwardCompatibility() {
        eval("fun test(x: Int) = when(x) {\n"
            + "    1 -> \"one\"\n"
            + "    2 -> \"two\"\n"
            + "    else -> \"other\"\n"
            + "}");
        assertEquals("one", eval("test(1)").asString());
        assertEquals("other", eval("test(3)").asString());
    }
}
