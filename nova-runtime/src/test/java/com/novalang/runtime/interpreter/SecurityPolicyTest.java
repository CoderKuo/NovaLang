package com.novalang.runtime.interpreter;

import com.novalang.runtime.*;
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
            NovaException ex = NovaSecurityPolicy.denied("test action");
            assertEquals(NovaException.ErrorKind.ACCESS_DENIED, ex.getKind());
            assertTrue(ex.getMessage().contains("test action"));
        }
    }

    // ============ ThreadLocal 上下文测试 ============

    @Nested
    @DisplayName("ThreadLocal 上下文")
    class ThreadLocalContextTests {

        @Test
        @DisplayName("setCurrent/current/clearCurrent 基本生命周期")
        void testBasicLifecycle() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.strict();
            assertNull(NovaSecurityPolicy.current());

            NovaSecurityPolicy.setCurrent(policy);
            assertSame(policy, NovaSecurityPolicy.current());

            NovaSecurityPolicy.clearCurrent();
            assertNull(NovaSecurityPolicy.current());
        }

        @Test
        @DisplayName("不同线程隔离")
        void testThreadIsolation() throws Exception {
            NovaSecurityPolicy main = NovaSecurityPolicy.strict();
            NovaSecurityPolicy.setCurrent(main);

            Thread child = new Thread(() -> {
                // 子线程看不到主线程的策略
                assertNull(NovaSecurityPolicy.current());

                NovaSecurityPolicy childPolicy = NovaSecurityPolicy.standard();
                NovaSecurityPolicy.setCurrent(childPolicy);
                assertSame(childPolicy, NovaSecurityPolicy.current());
                NovaSecurityPolicy.clearCurrent();
            });
            child.start();
            child.join();

            // 主线程策略不受影响
            assertSame(main, NovaSecurityPolicy.current());
            NovaSecurityPolicy.clearCurrent();
        }

        @Test
        @DisplayName("Interpreter.eval 自动设置 ThreadLocal")
        void testInterpreterSetsContext() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.strict();
            Interpreter interp = new Interpreter(policy);
            interp.setReplMode(true);

            // eval 后 ThreadLocal 应该被设置
            interp.evalRepl("1 + 1");
            assertSame(policy, NovaSecurityPolicy.current());

            NovaSecurityPolicy.clearCurrent();
        }

        @Test
        @DisplayName("多次 eval 复用同一策略")
        void testMultipleEvalsReuseSamePolicy() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(10000)
                    .build();
            Interpreter interp = createInterpreter(policy);

            interp.evalRepl("var x = 1");
            assertSame(policy, NovaSecurityPolicy.current());

            interp.evalRepl("var y = 2");
            assertSame(policy, NovaSecurityPolicy.current());

            NovaSecurityPolicy.clearCurrent();
        }
    }

    // ============ 静态检查入口测试 ============

    @Nested
    @DisplayName("静态检查入口（编译模式）")
    class StaticCheckTests {

        // ---- checkLoop ----

        @Test
        @DisplayName("checkLoop - 无策略时不抛异常")
        void testCheckLoopNullPolicy() {
            NovaSecurityPolicy.clearCurrent();
            assertDoesNotThrow(NovaSecurityPolicy::checkLoop);
        }

        @Test
        @DisplayName("checkLoop - UNRESTRICTED 策略不抛异常")
        void testCheckLoopUnrestricted() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.unrestricted());
            try {
                for (int i = 0; i < 100_000; i++) {
                    NovaSecurityPolicy.checkLoop(); // 不应有任何开销
                }
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkLoop - 未超限时正常通过")
        void testCheckLoopWithinLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(100)
                    .build();
            policy.resetCounters();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                for (int i = 0; i < 99; i++) {
                    NovaSecurityPolicy.checkLoop();
                }
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkLoop - 超过迭代限制时抛异常")
        void testCheckLoopExceedsIterations() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(50)
                    .build();
            policy.resetCounters();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                NovaException ex = assertThrows(NovaException.class, () -> {
                    for (int i = 0; i < 100; i++) {
                        NovaSecurityPolicy.checkLoop();
                    }
                });
                assertTrue(ex.getMessage().contains("iterations"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkLoop - 边界值：恰好等于限制不抛异常")
        void testCheckLoopExactlyAtLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(10)
                    .build();
            policy.resetCounters();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                // 恰好 10 次不应抛异常
                for (int i = 0; i < 10; i++) {
                    NovaSecurityPolicy.checkLoop();
                }
                // 第 11 次应抛异常
                assertThrows(NovaException.class, NovaSecurityPolicy::checkLoop);
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkLoop - 仅设置超时无迭代限制")
        void testCheckLoopTimeoutOnly() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(0) // 无迭代限制
                    .maxExecutionTime(50) // 50ms
                    .build();
            policy.resetCounters();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                NovaException ex = assertThrows(NovaException.class, () -> {
                    // 连续调用直到超时
                    for (long i = 0; i < 100_000_000L; i++) {
                        NovaSecurityPolicy.checkLoop();
                    }
                });
                assertTrue(ex.getMessage().contains("timeout") || ex.getMessage().contains("Timeout"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkLoop - resetCounters 清零后重新计数")
        void testCheckLoopResetCounters() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(10)
                    .build();
            policy.resetCounters();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                for (int i = 0; i < 10; i++) {
                    NovaSecurityPolicy.checkLoop();
                }
                // 第 11 次会抛异常
                assertThrows(NovaException.class, NovaSecurityPolicy::checkLoop);

                // 重置后可以重新计数
                policy.resetCounters();
                for (int i = 0; i < 10; i++) {
                    NovaSecurityPolicy.checkLoop();
                }
                // 第 11 次再次抛异常
                assertThrows(NovaException.class, NovaSecurityPolicy::checkLoop);
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkLoop - 迭代限制为 1 时首次通过第二次抛异常")
        void testCheckLoopLimitOne() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(1)
                    .build();
            policy.resetCounters();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                NovaSecurityPolicy.checkLoop(); // 第 1 次 OK
                assertThrows(NovaException.class, NovaSecurityPolicy::checkLoop); // 第 2 次抛异常
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        // ---- checkClass ----

        @Test
        @DisplayName("checkClass - 无策略时不抛异常")
        void testCheckClassNullPolicy() {
            NovaSecurityPolicy.clearCurrent();
            assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.lang.Runtime"));
        }

        @Test
        @DisplayName("checkClass - UNRESTRICTED 允许所有类")
        void testCheckClassUnrestricted() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.unrestricted());
            try {
                assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.lang.Runtime"));
                assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.io.File"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkClass - STRICT 拒绝所有 Java 类")
        void testCheckClassStrict() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.strict());
            try {
                NovaException ex = assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass("java.util.ArrayList"));
                assertTrue(ex.getMessage().contains("Cannot access class"));
                assertTrue(ex.getMessage().contains("ArrayList"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkClass - STANDARD 允许白名单类")
        void testCheckClassStandardAllowed() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.standard());
            try {
                assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.util.ArrayList"));
                assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.lang.String"));
                assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.math.BigDecimal"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkClass - STANDARD 拒绝黑名单类")
        void testCheckClassStandardDenied() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.standard());
            try {
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass("java.lang.Runtime"));
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass("java.io.File"));
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass("java.net.Socket"));
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass("java.lang.reflect.Method"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkClass - 自定义策略 deny 优先于 allow")
        void testCheckClassDenyOverrideAllow() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowPackage("java.lang")
                    .denyClass("java.lang.Runtime")
                    .build();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                assertDoesNotThrow(() -> NovaSecurityPolicy.checkClass("java.lang.String"));
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass("java.lang.Runtime"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkClass - 空字符串类名不崩溃")
        void testCheckClassEmptyClassName() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.standard());
            try {
                // 空字符串不在白名单中，应被拒绝
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkClass(""));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        // ---- checkMethod ----

        @Test
        @DisplayName("checkMethod - 无策略时不抛异常")
        void testCheckMethodNullPolicy() {
            NovaSecurityPolicy.clearCurrent();
            assertDoesNotThrow(() ->
                    NovaSecurityPolicy.checkMethod("java.lang.System", "exit"));
        }

        @Test
        @DisplayName("checkMethod - UNRESTRICTED 允许所有方法")
        void testCheckMethodUnrestricted() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.unrestricted());
            try {
                assertDoesNotThrow(() ->
                        NovaSecurityPolicy.checkMethod("java.lang.System", "exit"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkMethod - STANDARD 拒绝黑名单方法")
        void testCheckMethodStandardDenied() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.standard());
            try {
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkMethod("java.lang.System", "exit"));
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkMethod("java.lang.System", "load"));
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkMethod("java.lang.System", "loadLibrary"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkMethod - STANDARD 允许安全方法")
        void testCheckMethodStandardAllowed() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.standard());
            try {
                assertDoesNotThrow(() ->
                        NovaSecurityPolicy.checkMethod("java.lang.System", "currentTimeMillis"));
                assertDoesNotThrow(() ->
                        NovaSecurityPolicy.checkMethod("java.util.ArrayList", "add"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkMethod - STRICT 拒绝一切（Java 互操作禁止）")
        void testCheckMethodStrictDeniesAll() {
            NovaSecurityPolicy.setCurrent(NovaSecurityPolicy.strict());
            try {
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkMethod("java.util.ArrayList", "add"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }

        @Test
        @DisplayName("checkMethod - 自定义方法黑名单精确匹配")
        void testCheckMethodCustomDeny() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .denyMethod("com.example.Foo", "dangerousMethod")
                    .build();
            NovaSecurityPolicy.setCurrent(policy);
            try {
                assertThrows(NovaException.class,
                        () -> NovaSecurityPolicy.checkMethod("com.example.Foo", "dangerousMethod"));
                // 同类其他方法不受影响
                assertDoesNotThrow(() ->
                        NovaSecurityPolicy.checkMethod("com.example.Foo", "safeMethod"));
                // 其他类同名方法不受影响
                assertDoesNotThrow(() ->
                        NovaSecurityPolicy.checkMethod("com.example.Bar", "dangerousMethod"));
            } finally {
                NovaSecurityPolicy.clearCurrent();
            }
        }
    }

    // ============ 解释器集成测试（编译模式安全策略） ============

    @Nested
    @DisplayName("解释器安全策略集成")
    class InterpreterIntegrationTests {

        @Test
        @DisplayName("while 循环触发迭代限制")
        void testWhileLoopLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(50)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "var i = 0\n" +
                            "while (i < 1000) {\n" +
                            "    i = i + 1\n" +
                            "}"));
            assertTrue(ex.getMessage().contains("iterations") || ex.getMessage().contains("loop"));
        }

        @Test
        @DisplayName("for-range 循环触发迭代限制")
        void testForRangeLoopLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(50)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "var sum = 0\n" +
                            "for (i in 1..1000) {\n" +
                            "    sum = sum + i\n" +
                            "}"));
            assertTrue(ex.getMessage().contains("iterations") || ex.getMessage().contains("loop"));
        }

        @Test
        @DisplayName("for-in 集合循环触发迭代限制")
        void testForInLoopLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(3)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "val list = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)\n" +
                            "var sum = 0\n" +
                            "for (x in list) {\n" +
                            "    sum = sum + x\n" +
                            "}"));
            assertTrue(ex.getMessage().contains("iterations") || ex.getMessage().contains("loop"));
        }

        @Test
        @DisplayName("do-while 循环触发迭代限制")
        void testDoWhileLoopLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(20)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "var i = 0\n" +
                            "do {\n" +
                            "    i = i + 1\n" +
                            "} while (i < 1000)"));
            assertTrue(ex.getMessage().contains("iterations") || ex.getMessage().contains("loop"));
        }

        @Test
        @DisplayName("嵌套循环累加计数")
        void testNestedLoopCountsCombined() {
            // 外层 10 * 内层 10 = 100 次，限制 50 → 应在嵌套中触发
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(50)
                    .build();
            Interpreter interp = createInterpreter(policy);
            Exception ex = assertThrows(Exception.class, () ->
                    interp.evalRepl(
                            "var sum = 0\n" +
                            "for (i in 1..10) {\n" +
                            "    for (j in 1..10) {\n" +
                            "        sum = sum + 1\n" +
                            "    }\n" +
                            "}"));
            assertTrue(ex.getMessage().contains("iterations") || ex.getMessage().contains("loop"));
        }

        @Test
        @DisplayName("正常循环在限制内完成")
        void testLoopWithinLimit() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(200)
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

        @Test
        @DisplayName("UNRESTRICTED 无循环限制")
        void testUnrestrictedNoLoopLimit() {
            Interpreter interp = createInterpreter(NovaSecurityPolicy.unrestricted());
            NovaValue result = interp.evalRepl(
                    "var sum = 0\n" +
                    "for (i in 1..10000) {\n" +
                    "    sum = sum + 1\n" +
                    "}\n" +
                    "sum");
            assertEquals(10000, result.asInt());
        }

        @Test
        @DisplayName("每次 eval 重置计数器")
        void testCounterResetBetweenEvals() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(100)
                    .build();
            Interpreter interp = createInterpreter(policy);

            // 第一次 eval: 90 次循环（在限制内）
            interp.evalRepl(
                    "var sum = 0\n" +
                    "for (i in 1..90) { sum = sum + 1 }\n" +
                    "sum");

            // 第二次 eval: 又 90 次循环（应该也在限制内，因为计数器被重置）
            NovaValue result = interp.evalRepl(
                    "var sum = 0\n" +
                    "for (i in 1..90) { sum = sum + 1 }\n" +
                    "sum");
            assertEquals(90, result.asInt());
        }
    }

    // ============ Builder 边界值测试 ============

    @Nested
    @DisplayName("Builder 边界值")
    class BuilderEdgeCaseTests {

        @Test
        @DisplayName("所有限制为 0 等同无限制")
        void testZeroLimitsNoRestriction() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(0)
                    .maxExecutionTime(0)
                    .maxRecursionDepth(0)
                    .maxAsyncTasks(0)
                    .build();
            assertEquals(0, policy.getMaxLoopIterations());
            assertEquals(0, policy.getMaxExecutionTimeMs());
            assertEquals(0, policy.getMaxRecursionDepth());
            assertEquals(0, policy.getMaxAsyncTasks());
        }

        @Test
        @DisplayName("非常大的限制值不溢出")
        void testLargeLimitValues() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .maxLoopIterations(Long.MAX_VALUE)
                    .maxExecutionTime(Long.MAX_VALUE)
                    .build();
            assertEquals(Long.MAX_VALUE, policy.getMaxLoopIterations());
            assertEquals(Long.MAX_VALUE, policy.getMaxExecutionTimeMs());
        }

        @Test
        @DisplayName("多重 deny 叠加生效")
        void testMultipleDenyRules() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .denyClass("java.lang.Runtime")
                    .denyClass("java.lang.ProcessBuilder")
                    .denyPackage("java.io")
                    .denyMethod("java.lang.System", "exit")
                    .denyMethod("java.lang.System", "gc")
                    .build();
            assertFalse(policy.isClassAllowed("java.lang.Runtime"));
            assertFalse(policy.isClassAllowed("java.lang.ProcessBuilder"));
            assertFalse(policy.isClassAllowed("java.io.File"));
            assertFalse(policy.isMethodAllowed("java.lang.System", "exit"));
            assertFalse(policy.isMethodAllowed("java.lang.System", "gc"));
            // 未列入黑名单的仍然允许
            assertTrue(policy.isClassAllowed("java.lang.String"));
            assertTrue(policy.isMethodAllowed("java.lang.System", "currentTimeMillis"));
        }

        @Test
        @DisplayName("hasMethodRestrictions 正确反映状态")
        void testHasMethodRestrictions() {
            NovaSecurityPolicy noRestrictions = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .build();
            assertFalse(noRestrictions.hasMethodRestrictions());

            NovaSecurityPolicy withRestrictions = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .denyMethod("Foo", "bar")
                    .build();
            assertTrue(withRestrictions.hasMethodRestrictions());
        }

        @Test
        @DisplayName("功能开关独立控制")
        void testFeatureTogglesIndependent() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowSetAccessible(false)
                    .allowStdio(true)
                    .allowFileIO(false)
                    .allowNetwork(false)
                    .allowProcessExec(true)
                    .build();
            assertTrue(policy.isJavaInteropAllowed());
            assertFalse(policy.isSetAccessibleAllowed());
            assertTrue(policy.isStdioAllowed());
            assertFalse(policy.isFileIOAllowed());
            assertFalse(policy.isNetworkAllowed());
            assertTrue(policy.isProcessExecAllowed());
        }

        @Test
        @DisplayName("预定义级别不可变（工厂方法每次返回新实例）")
        void testPresetLevelsAreImmutable() {
            NovaSecurityPolicy s1 = NovaSecurityPolicy.strict();
            NovaSecurityPolicy s2 = NovaSecurityPolicy.strict();
            assertNotSame(s1, s2);
            assertEquals(s1.getLevel(), s2.getLevel());
            assertEquals(s1.getMaxLoopIterations(), s2.getMaxLoopIterations());
        }

        @Test
        @DisplayName("空白名单 + 无黑名单 = 允许所有类")
        void testEmptyWhitelistAllowsAll() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    // 不设白名单也不设黑名单
                    .build();
            assertTrue(policy.isClassAllowed("any.random.ClassName"));
            assertTrue(policy.isClassAllowed("java.lang.Runtime"));
        }

        @Test
        @DisplayName("有白名单时，不在白名单中的类被拒")
        void testWhitelistRejectsOthers() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowPackage("java.util")
                    .build();
            assertTrue(policy.isClassAllowed("java.util.ArrayList"));
            assertFalse(policy.isClassAllowed("java.lang.Runtime"));
            assertFalse(policy.isClassAllowed("com.example.Foo"));
        }
    }

    // ============ isClassAllowed 优先级链完整测试 ============

    @Nested
    @DisplayName("isClassAllowed 优先级链")
    class ClassAllowedPriorityTests {

        @Test
        @DisplayName("优先级 1: 精确类黑名单最高")
        void testDenyClassHighestPriority() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowPackage("java.lang")
                    .allowClass("java.lang.Runtime") // 显式白名单
                    .denyClass("java.lang.Runtime")  // 黑名单优先
                    .build();
            assertFalse(policy.isClassAllowed("java.lang.Runtime"));
        }

        @Test
        @DisplayName("优先级 2: 精确类白名单 > 包黑名单")
        void testAllowClassOverridesDenyPackage() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .denyPackage("java.io")
                    .allowClass("java.io.Serializable") // 精确白名单覆盖包黑名单
                    .build();
            assertTrue(policy.isClassAllowed("java.io.Serializable"));
            assertFalse(policy.isClassAllowed("java.io.File"));
        }

        @Test
        @DisplayName("优先级 3: 包黑名单 > 包白名单")
        void testDenyPackageOverridesAllowPackage() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(true)
                    .allowPackage("java.lang")
                    .denyPackage("java.lang.reflect")
                    .build();
            assertTrue(policy.isClassAllowed("java.lang.String"));
            assertFalse(policy.isClassAllowed("java.lang.reflect.Method"));
        }

        @Test
        @DisplayName("allowJavaInterop=false 拒绝一切类")
        void testNoJavaInteropDeniesAll() {
            NovaSecurityPolicy policy = NovaSecurityPolicy.custom()
                    .allowJavaInterop(false)
                    .allowPackage("java.util")
                    .allowClass("java.lang.String")
                    .build();
            assertFalse(policy.isClassAllowed("java.util.ArrayList"));
            assertFalse(policy.isClassAllowed("java.lang.String"));
        }
    }
}
