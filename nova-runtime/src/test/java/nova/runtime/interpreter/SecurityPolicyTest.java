package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全策略测试
 */
class SecurityPolicyTest {

    /** 创建带策略的解释器（REPL 模式，支持裸表达式和语句） */
    private Interpreter createInterpreter(NovaSecurityPolicy policy) {
        Interpreter interp = new Interpreter(policy);
        interp.setReplMode(true);
        return interp;
    }

    // ============ 预定义级别测试 ============

    @Nested
    @DisplayName("UNRESTRICTED 级别")
    class UnrestrictedTests {

        @Test
        @DisplayName("允许加载任意 Java 类")
        void testAllowAnyClass() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.unrestricted());
            NovaValue result = interp.evalRepl(
                    "val rt = Java.type(\"java.lang.Runtime\")\n" +
                    "typeof(rt)");
            assertTrue(result.asString().contains("JavaClass"));
        }

        @Test
        @DisplayName("允许 println")
        void testAllowStdio() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.unrestricted());
            interp.evalRepl("println(\"hello\")");
        }
    }

    @Nested
    @DisplayName("STRICT 级别")
    class StrictTests {

        @Test
        @DisplayName("禁止 Java 互操作 - Java.type 不可用")
        void testNoJavaType() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.strict());
            assertThrows(Exception.class, () ->
                    interp.evalRepl("Java.type(\"java.util.ArrayList\")"));
        }

        @Test
        @DisplayName("禁止 javaClass 函数")
        void testNoJavaClassFunction() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.strict());
            assertThrows(Exception.class, () ->
                    interp.evalRepl("javaClass(\"java.util.ArrayList\")"));
        }

        @Test
        @DisplayName("允许 println")
        void testAllowStdio() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.strict());
            interp.evalRepl("println(\"hello from strict\")");
        }

        @Test
        @DisplayName("允许基本运算")
        void testAllowBasicOps() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.strict());
            NovaValue result = interp.evalRepl("1 + 2 * 3");
            assertEquals(7, result.asInt());
        }
    }

    @Nested
    @DisplayName("STANDARD 级别")
    class StandardTests {

        @Test
        @DisplayName("阻止危险类 - Runtime")
        void testBlockRuntime() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.standard());
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl("Java.type(\"java.lang.Runtime\")"));
            assertTrue(ex.getMessage().contains("denied") || ex.getMessage().contains("Runtime"));
        }

        @Test
        @DisplayName("允许安全类 - ArrayList")
        void testAllowArrayList() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.standard());
            NovaValue result = interp.evalRepl(
                    "val list = Java.type(\"java.util.ArrayList\")()\n" +
                    "list.add(\"hello\")\n" +
                    "list.size()");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("阻止 IO 类")
        void testBlockIO() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.standard());
            assertThrows(Exception.class, () ->
                    interp.evalRepl("Java.type(\"java.io.File\")"));
        }

        @Test
        @DisplayName("阻止网络类")
        void testBlockNetwork() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.standard());
            assertThrows(Exception.class, () ->
                    interp.evalRepl("Java.type(\"java.net.Socket\")"));
        }

        @Test
        @DisplayName("阻止反射包")
        void testBlockReflect() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.standard());
            assertThrows(Exception.class, () ->
                    interp.evalRepl("Java.type(\"java.lang.reflect.Method\")"));
        }

        @Test
        @DisplayName("方法黑名单 - System.exit")
        void testBlockSystemExit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.standard();
            // 策略层面检查
            assertFalse(policy.isMethodAllowed("java.lang.System", "exit"));
            assertFalse(policy.isMethodAllowed("java.lang.System", "load"));
            assertFalse(policy.isMethodAllowed("java.lang.System", "loadLibrary"));
            // 安全方法允许
            assertTrue(policy.isMethodAllowed("java.lang.System", "currentTimeMillis"));
        }
    }

    // ============ 资源限制测试 ============

    @Nested
    @DisplayName("资源限制")
    class ResourceLimitTests {

        @Test
        @DisplayName("递归深度限制")
        void testRecursionLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxRecursionDepth(10)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "fun recurse(n: Int): Int {\n" +
                            "    return recurse(n + 1)\n" +
                            "}\n" +
                            "recurse(0)"));
            assertTrue(ex.getMessage().contains("recursion") || ex.getMessage().contains("depth"));
        }

        @Test
        @DisplayName("循环次数限制")
        void testLoopLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(100)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "var i = 0\n" +
                            "while (true) {\n" +
                            "    i = i + 1\n" +
                            "}"));
            assertTrue(ex.getMessage().contains("loop") || ex.getMessage().contains("iterations"));
        }

        @Test
        @DisplayName("执行超时")
        void testTimeout() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxExecutionTime(100)  // 100ms
                    .maxLoopIterations(0)   // 不限制循环次数，依靠超时
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "var i = 0\n" +
                            "while (true) {\n" +
                            "    i = i + 1\n" +
                            "}"));
            assertTrue(ex.getMessage().contains("timeout") || ex.getMessage().contains("Timeout"));
        }

        @Test
        @DisplayName("正常递归不触发限制")
        void testNormalRecursion() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxRecursionDepth(100)
                    .build();
            Interpreter interp = createInterpreter(policy);
            NovaValue result = interp.evalRepl(
                    "fun fib(n: Int): Int {\n" +
                    "    if (n <= 1) return n\n" +
                    "    return fib(n - 1) + fib(n - 2)\n" +
                    "}\n" +
                    "fib(10)");
            assertEquals(55, result.asInt());
        }

        @Test
        @DisplayName("正常循环不触发限制")
        void testNormalLoop() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(1000)
                    .build();
            Interpreter interp = createInterpreter(policy);
            NovaValue result = interp.evalRepl(
                    "var sum = 0\n" +
                    "for (i in 1..100) {\n" +
                    "    sum = sum + i\n" +
                    "}\n" +
                    "sum");
            assertEquals(5050, result.asInt());
        }
    }

    // ============ 自定义策略测试 ============

    @Nested
    @DisplayName("自定义策略")
    class CustomPolicyTests {

        @Test
        @DisplayName("自定义包白名单")
        void testCustomAllowedPackage() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowPackage("java.util")
                    .allowPackage("java.lang")
                    .denyClass("java.lang.Runtime")
                    .denyClass("java.lang.ProcessBuilder")
                    .build();
            Interpreter interp = createInterpreter(policy);

            // java.util 可用
            interp.evalRepl("val list = Java.type(\"java.util.ArrayList\")()");

            // java.lang.Runtime 被拒
            assertThrows(Exception.class, () ->
                    interp.evalRepl("Java.type(\"java.lang.Runtime\")"));
        }

        @Test
        @DisplayName("自定义方法黑名单")
        void testCustomDeniedMethod() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowPackage("java.util")
                    .allowPackage("java.lang")
                    .denyMethod("java.util.ArrayList", "clear")
                    .build();
            Interpreter interp = createInterpreter(policy);

            // 创建 ArrayList 实例，add 可用
            interp.evalRepl(
                    "val list = Java.type(\"java.util.ArrayList\")()\n" +
                    "list.add(\"hello\")");

            // clear 被方法黑名单拒绝
            assertThrows(Exception.class, () ->
                    interp.evalRepl("list.clear()"));

            // 其他方法可用
            NovaValue result = interp.evalRepl("list.size()");
            assertEquals(1, result.asInt());
        }

        @Test
        @DisplayName("禁止 stdio")
        void testDisableStdio() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowStdio(false)
                    .build();
            Interpreter interp = createInterpreter(policy);

            // println 未注册
            assertThrows(Exception.class, () ->
                    interp.evalRepl("println(\"should fail\")"));
        }
    }

    // ============ NovaSecurityPolicy 单元测试 ============

    @Nested
    @DisplayName("策略查询方法")
    class PolicyQueryTests {

        @Test
        @DisplayName("UNRESTRICTED 允许一切")
        void testUnrestrictedAllowsAll() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.unrestricted();
            assertTrue(policy.isClassAllowed("java.lang.Runtime"));
            assertTrue(policy.isClassAllowed("java.io.File"));
            assertTrue(policy.isMethodAllowed("java.lang.System", "exit"));
            assertTrue(policy.isJavaInteropAllowed());
            assertTrue(policy.isStdioAllowed());
            assertEquals(0, policy.getMaxExecutionTimeMs());
            assertEquals(0, policy.getMaxRecursionDepth());
            assertEquals(0, policy.getMaxLoopIterations());
        }

        @Test
        @DisplayName("STRICT 禁止 Java 互操作")
        void testStrictDeniesJava() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.strict();
            assertFalse(policy.isJavaInteropAllowed());
            assertFalse(policy.isClassAllowed("java.util.ArrayList"));
            assertTrue(policy.isStdioAllowed());
            assertTrue(policy.getMaxExecutionTimeMs() > 0);
            assertTrue(policy.getMaxRecursionDepth() > 0);
            assertTrue(policy.getMaxLoopIterations() > 0);
        }

        @Test
        @DisplayName("STANDARD deny 优先于 allow")
        void testDenyOverrideAllow() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.standard();
            assertFalse(policy.isClassAllowed("java.lang.Runtime"));
            assertTrue(policy.isClassAllowed("java.lang.String"));
            assertTrue(policy.isClassAllowed("java.lang.Integer"));
            assertFalse(policy.isClassAllowed("java.io.File"));
        }

        @Test
        @DisplayName("denied 方法创建正确异常")
        void testDeniedFactory() {
            NovaRuntimeException ex = NovaSecurityPolicy.denied("test action");
            assertTrue(ex.getMessage().contains("Security policy denied"));
            assertTrue(ex.getMessage().contains("test action"));
        }
    }
}
