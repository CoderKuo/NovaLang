package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * when guard condition 中各种表达式形式的全面测试。
 */
class WhenGuardExprFormTest {

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
    // 1. 布尔字面量
    // ================================================================

    @Test @DisplayName("guard: if true")
    void testGuardTrue() {
        eval("val x = 1");
        assertEquals("match", eval("when(x) { 1 if true -> \"match\"; else -> \"no\" }").asString());
    }

    @Test @DisplayName("guard: if false — 跳过")
    void testGuardFalse() {
        eval("val x = 1");
        assertEquals("fallback", eval("when(x) { 1 if false -> \"match\"; 1 -> \"fallback\"; else -> \"no\" }").asString());
    }

    // ================================================================
    // 2. 取反 !
    // ================================================================

    @Test @DisplayName("guard: if !flag")
    void testGuardNegation() {
        eval("val flag = false");
        assertEquals("yes", eval("when {\n"
            + "    true if !flag -> \"yes\"\n"
            + "    else -> \"no\"\n"
            + "}").asString());
    }

    @Test @DisplayName("guard: if !flag — flag 为 true")
    void testGuardNegationTrue() {
        eval("val flag = true");
        assertEquals("no", eval("when {\n"
            + "    true if !flag -> \"yes\"\n"
            + "    else -> \"no\"\n"
            + "}").asString());
    }

    // ================================================================
    // 3. 比较运算符 > < >= <= == !=
    // ================================================================

    @Test @DisplayName("guard: if n > 10")
    void testGuardGreaterThan() {
        eval("fun f(n: Int) = when {\n"
            + "    n > 0 if n > 10 -> \"big\"\n"
            + "    n > 0 -> \"small\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("big", eval("f(20)").asString());
        assertEquals("small", eval("f(5)").asString());
    }

    @Test @DisplayName("guard: if n == 0")
    void testGuardEquals() {
        eval("fun f(n: Int) = when {\n"
            + "    n >= 0 if n == 0 -> \"zero\"\n"
            + "    n >= 0 -> \"positive\"\n"
            + "    else -> \"negative\"\n"
            + "}");
        assertEquals("zero", eval("f(0)").asString());
        assertEquals("positive", eval("f(5)").asString());
    }

    @Test @DisplayName("guard: if n != 1")
    void testGuardNotEquals() {
        eval("fun f(n: Int) = when {\n"
            + "    n > 0 if n != 1 -> \"not-one\"\n"
            + "    n > 0 -> \"one\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("not-one", eval("f(5)").asString());
        assertEquals("one", eval("f(1)").asString());
    }

    // ================================================================
    // 4. 成员访问
    // ================================================================

    @Test @DisplayName("guard: if obj.field")
    void testGuardMemberAccess() {
        eval("class Item(val active: Boolean, val name: String)");
        eval("fun f(item: Item) = when {\n"
            + "    true if item.active -> \"active: \" + item.name\n"
            + "    else -> \"inactive\"\n"
            + "}");
        assertEquals("active: A", eval("f(Item(true, \"A\"))").asString());
        assertEquals("inactive", eval("f(Item(false, \"B\"))").asString());
    }

    @Test @DisplayName("guard: if obj.field > value")
    void testGuardMemberComparison() {
        eval("@data class User(val name: String, val age: Int)");
        eval("fun f(u: User) = when {\n"
            + "    true if u.age >= 18 -> \"adult\"\n"
            + "    else -> \"minor\"\n"
            + "}");
        assertEquals("adult", eval("f(User(\"A\", 25))").asString());
        assertEquals("minor", eval("f(User(\"B\", 15))").asString());
    }

    // ================================================================
    // 5. 方法调用
    // ================================================================

    @Test @DisplayName("guard: if str.isEmpty()")
    void testGuardMethodCall() {
        eval("fun f(s: String) = when {\n"
            + "    true if s.isEmpty() -> \"empty\"\n"
            + "    else -> \"not-empty\"\n"
            + "}");
        assertEquals("empty", eval("f(\"\")").asString());
        assertEquals("not-empty", eval("f(\"hi\")").asString());
    }

    @Test @DisplayName("guard: if list.contains(x)")
    void testGuardMethodCallWithArg() {
        eval("val allowed = [\"admin\", \"root\"]");
        eval("fun f(name: String) = when {\n"
            + "    true if allowed.contains(name) -> \"allowed\"\n"
            + "    else -> \"denied\"\n"
            + "}");
        assertEquals("allowed", eval("f(\"admin\")").asString());
        assertEquals("denied", eval("f(\"guest\")").asString());
    }

    @Test @DisplayName("guard: if str.length > 5")
    void testGuardChainedMemberMethod() {
        eval("fun f(s: String) = when {\n"
            + "    true if s.length > 5 -> \"long\"\n"
            + "    else -> \"short\"\n"
            + "}");
        assertEquals("long", eval("f(\"abcdef\")").asString());
        assertEquals("short", eval("f(\"abc\")").asString());
    }

    // ================================================================
    // 6. 逻辑运算符 && ||
    // ================================================================

    @Test @DisplayName("guard: if a && b")
    void testGuardLogicalAnd() {
        eval("fun f(a: Boolean, b: Boolean) = when {\n"
            + "    true if a && b -> \"both\"\n"
            + "    true if a -> \"only-a\"\n"
            + "    else -> \"none\"\n"
            + "}");
        assertEquals("both", eval("f(true, true)").asString());
        assertEquals("only-a", eval("f(true, false)").asString());
        assertEquals("none", eval("f(false, false)").asString());
    }

    @Test @DisplayName("guard: if a || b")
    void testGuardLogicalOr() {
        eval("fun f(a: Boolean, b: Boolean) = when {\n"
            + "    true if a || b -> \"any\"\n"
            + "    else -> \"none\"\n"
            + "}");
        assertEquals("any", eval("f(true, false)").asString());
        assertEquals("any", eval("f(false, true)").asString());
        assertEquals("none", eval("f(false, false)").asString());
    }

    // ================================================================
    // 7. 复合表达式
    // ================================================================

    @Test @DisplayName("guard: if x > 0 && x < 100")
    void testGuardComplex() {
        eval("fun f(x: Int) = when {\n"
            + "    x > 0 if x > 0 && x < 100 -> \"in-range\"\n"
            + "    x > 0 -> \"out-range\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("in-range", eval("f(50)").asString());
        assertEquals("out-range", eval("f(200)").asString());
    }

    // ================================================================
    // 8. 函数调用
    // ================================================================

    @Test @DisplayName("guard: if customFunction(x)")
    void testGuardFunctionCall() {
        eval("fun isEven(n: Int) = n % 2 == 0");
        eval("fun f(n: Int) = when {\n"
            + "    n > 0 if isEven(n) -> \"even-pos\"\n"
            + "    n > 0 -> \"odd-pos\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("even-pos", eval("f(4)").asString());
        assertEquals("odd-pos", eval("f(3)").asString());
    }

    // ================================================================
    // 9. is 类型检查（在 guard 内）
    // ================================================================

    @Test @DisplayName("guard: if subject is SubType")
    void testGuardIsTypeCheck() {
        eval("open class Base");
        eval("class A : Base()");
        eval("class B : Base()");
        eval("fun f(x: Base, flag: Boolean) = when {\n"
            + "    flag if x is A -> \"flag+A\"\n"
            + "    flag -> \"flag+other\"\n"
            + "    else -> \"no-flag\"\n"
            + "}");
        assertEquals("flag+A", eval("f(A(), true)").asString());
        assertEquals("flag+other", eval("f(B(), true)").asString());
        assertEquals("no-flag", eval("f(A(), false)").asString());
    }

    // ================================================================
    // 10. in 范围检查（在 guard 内）
    // ================================================================

    @Test @DisplayName("guard: if x in range")
    void testGuardInRange() {
        eval("fun f(x: Int) = when {\n"
            + "    x > 0 if x in 1..10 -> \"1-10\"\n"
            + "    x > 0 -> \"big\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("1-10", eval("f(5)").asString());
        assertEquals("big", eval("f(20)").asString());
    }

    // ================================================================
    // 11. 字符串操作
    // ================================================================

    @Test @DisplayName("guard: if str.startsWith(...)")
    void testGuardStartsWith() {
        eval("fun f(s: String) = when {\n"
            + "    s.length > 0 if s.startsWith(\"admin\") -> \"admin\"\n"
            + "    s.length > 0 -> \"user\"\n"
            + "    else -> \"empty\"\n"
            + "}");
        assertEquals("admin", eval("f(\"admin_root\")").asString());
        assertEquals("user", eval("f(\"guest\")").asString());
    }

    // ================================================================
    // 12. null 检查
    // ================================================================

    @Test @DisplayName("guard: if x != null")
    void testGuardNullCheck() {
        eval("fun f(x: Any?) = when {\n"
            + "    true if x != null -> \"has-value\"\n"
            + "    else -> \"null\"\n"
            + "}");
        assertEquals("has-value", eval("f(42)").asString());
        assertEquals("null", eval("f(null)").asString());
    }

    // ================================================================
    // 13. 与 is Type 主条件组合
    // ================================================================

    @Test @DisplayName("is Type if member — 类型安全的 guard")
    void testIsTypeWithMemberGuard() {
        eval("open class Shape(val name: String)");
        eval("class Circle(name: String, val radius: Double) : Shape(name)");
        eval("class Rect(name: String, val w: Double, val h: Double) : Shape(name)");
        eval("fun f(s: Shape) = when(s) {\n"
            + "    is Circle if s.radius > 10 -> \"big circle\"\n"
            + "    is Circle -> \"small circle\"\n"
            + "    is Rect if s.w == s.h -> \"square\"\n"
            + "    is Rect -> \"rect\"\n"
            + "    else -> \"?\"\n"
            + "}");
        assertEquals("big circle", eval("f(Circle(\"c1\", 15.0))").asString());
        assertEquals("small circle", eval("f(Circle(\"c2\", 3.0))").asString());
        assertEquals("square", eval("f(Rect(\"r1\", 5.0, 5.0))").asString());
        assertEquals("rect", eval("f(Rect(\"r2\", 3.0, 7.0))").asString());
    }

    // ================================================================
    // 14. 与 in range 主条件组合
    // ================================================================

    @Test @DisplayName("in range if guard")
    void testInRangeWithGuard() {
        eval("fun f(score: Int, honors: Boolean) = when(score) {\n"
            + "    in 90..100 if honors -> \"A+\"\n"
            + "    in 90..100 -> \"A\"\n"
            + "    in 80..89 -> \"B\"\n"
            + "    else -> \"C\"\n"
            + "}");
        assertEquals("A+", eval("f(95, true)").asString());
        assertEquals("A", eval("f(95, false)").asString());
        assertEquals("B", eval("f(85, true)").asString());
    }

    // ================================================================
    // 15. 三元表达式作为 guard
    // ================================================================

    @Test @DisplayName("guard: if 三元表达式")
    void testGuardTernary() {
        eval("fun f(x: Int, mode: String) = when {\n"
            + "    x > 0 if (if (mode == \"strict\") x > 10 else true) -> \"pass\"\n"
            + "    x > 0 -> \"fail\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("pass", eval("f(20, \"strict\")").asString());
        assertEquals("fail", eval("f(5, \"strict\")").asString());
        assertEquals("pass", eval("f(5, \"lenient\")").asString());
    }

    // ================================================================
    // 16. lambda 调用作为 guard
    // ================================================================

    @Test @DisplayName("guard: if lambda()")
    void testGuardLambdaCall() {
        eval("val checker = { x: Int -> x % 2 == 0 }");
        eval("fun f(n: Int) = when {\n"
            + "    n > 0 if checker(n) -> \"even\"\n"
            + "    n > 0 -> \"odd\"\n"
            + "    else -> \"neg\"\n"
            + "}");
        assertEquals("even", eval("f(4)").asString());
        assertEquals("odd", eval("f(3)").asString());
    }

    // ================================================================
    // 17. 多条件 + guard（逗号分隔条件 + guard）
    // ================================================================

    @Test @DisplayName("多条件 + guard: 1, 2 if flag — 表达式路径")
    void testMultiConditionWithGuard() {
        eval("fun f(x: Int, flag: Boolean) = when(x) {\n"
            + "    1, 2 if flag -> \"1or2-flagged\"\n"
            + "    1, 2 -> \"1or2\"\n"
            + "    else -> \"other\"\n"
            + "}");
        assertEquals("1or2-flagged", eval("f(1, true)").asString());
        assertEquals("1or2-flagged", eval("f(2, true)").asString());
        assertEquals("1or2", eval("f(1, false)").asString());
        assertEquals("other", eval("f(3, true)").asString());
    }

    @Test @DisplayName("单条件 + guard — 函数表达式体")
    void testSingleConditionGuardFunExpr() {
        eval("fun f(x: Int, flag: Boolean) = when(x) {\n"
            + "    1 if flag -> \"1-flagged\"\n"
            + "    1 -> \"1\"\n"
            + "    else -> \"other\"\n"
            + "}");
        assertEquals("1-flagged", eval("f(1, true)").asString());
        assertEquals("1", eval("f(1, false)").asString());
    }

    @Test @DisplayName("单条件 + guard — 顶层 eval 表达式")
    void testSingleConditionGuardTopLevel() {
        eval("val x = 1");
        eval("val flag = true");
        NovaValue result = eval("when(x) {\n"
            + "    1 if flag -> \"1-flagged\"\n"
            + "    1 -> \"1\"\n"
            + "    else -> \"other\"\n"
            + "}");
        assertEquals("1-flagged", result.asString());
    }

    @Test @DisplayName("多条件无 guard — 表达式路径（隔离测试）")
    void testMultiConditionNoGuardExprPath() {
        // 先排除多条件表达式本身的问题
        eval("fun g(x: Int) = when(x) {\n"
            + "    1, 2 -> \"1or2\"\n"
            + "    3 -> \"3\"\n"
            + "    else -> \"other\"\n"
            + "}");
        assertEquals("1or2", eval("g(1)").asString());
        assertEquals("1or2", eval("g(2)").asString());
        assertEquals("3", eval("g(3)").asString());
        assertEquals("other", eval("g(4)").asString());
    }
}
