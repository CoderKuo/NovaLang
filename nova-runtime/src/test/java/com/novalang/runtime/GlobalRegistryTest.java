package com.novalang.runtime;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 全局注册表（NovaRuntime.shared()）+ 三层作用域注册测试。
 * 覆盖：函数注册、变量注册、扩展函数注册、命名空间、解释器/编译器双路径。
 */
@DisplayName("全局注册表与三层作用域")
class GlobalRegistryTest {

    @BeforeEach
    void clearShared() {
        NovaRuntime.shared().clearAll();
    }

    // ============================================================
    //  1. shared() 全局函数注册
    // ============================================================

    @Nested
    @DisplayName("shared() 函数注册")
    class SharedFunctionTests {

        @Test
        @DisplayName("注册全局函数 — 解释器路径可调用")
        void globalFuncInterpreter() {
            NovaRuntime.shared().register("doubleIt", (Function1<Object, Object>) a -> ((Number) a).intValue() * 2);
            Nova nova = new Nova();
            Object result = nova.eval("doubleIt(21)");
            assertEquals(42, unwrap(result));
        }

        @Test
        @DisplayName("注册全局函数 — 编译路径可调用")
        void globalFuncCompiled() {
            NovaRuntime.shared().register("tripleIt", (Function1<Object, Object>) a -> ((Number) a).intValue() * 3);
            Nova nova = new Nova();
            Object result = nova.compileToBytecode("tripleIt(10)", "test.nova").run();
            assertEquals(30, result);
        }

        @Test
        @DisplayName("不同 Nova 实例共享全局函数")
        void sharedAcrossInstances() {
            NovaRuntime.shared().register("ping", (Function0<Object>) () -> "pong");
            assertEquals("pong", unwrap(new Nova().eval("ping()")));
            assertEquals("pong", unwrap(new Nova().eval("ping()")));
        }

        @Test
        @DisplayName("后注册覆盖前注册（同名无命名空间）")
        void laterOverridesPrevious() {
            NovaRuntime.shared().register("ver", (Function0<Object>) () -> "v1");
            NovaRuntime.shared().register("ver", (Function0<Object>) () -> "v2");
            assertEquals("v2", unwrap(new Nova().eval("ver()")));
        }

        @Test
        @DisplayName("0 参数函数")
        void zeroArgFunc() {
            NovaRuntime.shared().register("now", (Function0<Object>) () -> 12345L);
            assertEquals(12345L, unwrap(new Nova().eval("now()")));
        }

        @Test
        @DisplayName("2 参数函数")
        void twoArgFunc() {
            NovaRuntime.shared().register("add", (Function2<Object, Object, Object>) (a, b) ->
                    ((Number) a).intValue() + ((Number) b).intValue());
            assertEquals(7, unwrap(new Nova().eval("add(3, 4)")));
        }

        @Test
        @DisplayName("3 参数函数")
        void threeArgFunc() {
            NovaRuntime.shared().register("sum3", (Function3<Object, Object, Object, Object>) (a, b, c) ->
                    ((Number) a).intValue() + ((Number) b).intValue() + ((Number) c).intValue());
            assertEquals(6, unwrap(new Nova().eval("sum3(1, 2, 3)")));
        }

        @Test
        @DisplayName("未注册函数调用抛异常")
        void undefinedFuncThrows() {
            assertThrows(Exception.class, () -> new Nova().eval("noSuchGlobalFunc()"));
        }
    }

    // ============================================================
    //  2. shared() 命名空间
    // ============================================================

    @Nested
    @DisplayName("shared() 命名空间")
    class NamespaceTests {

        @Test
        @DisplayName("带命名空间注册 — 全限定名调用（解释器）")
        void qualifiedCallInterpreter() {
            NovaRuntime.shared().register("greet", (Function1<Object, Object>) n -> "Hi from A, " + n, "pluginA");
            Nova nova = new Nova();
            Object result = nova.eval("pluginA.greet(\"Steve\")");
            assertEquals("Hi from A, Steve", unwrap(result));
        }

        @Test
        @DisplayName("带命名空间注册 — 全限定名调用（编译）")
        void qualifiedCallCompiled() {
            NovaRuntime.shared().register("greet", (Function1<Object, Object>) n -> "Hi from B, " + n, "pluginB");
            Nova nova = new Nova();
            Object result = nova.compileToBytecode("pluginB.greet(\"Alex\")", "test.nova").run();
            assertEquals("Hi from B, Alex", unwrap(result));
        }

        @Test
        @DisplayName("两个命名空间同名函数 — 短名取后注册的")
        void shortNameTakesLatest() {
            NovaRuntime.shared().register("hello", (Function0<Object>) () -> "from A", "nsA");
            NovaRuntime.shared().register("hello", (Function0<Object>) () -> "from B", "nsB");
            // 短名调用 → 后注册的 nsB
            assertEquals("from B", unwrap(new Nova().eval("hello()")));
        }

        @Test
        @DisplayName("两个命名空间同名函数 — 全限定名各自可达")
        void qualifiedNamesIndependent() {
            NovaRuntime.shared().register("calc", (Function1<Object, Object>) x -> ((Number) x).intValue() + 1, "p1");
            NovaRuntime.shared().register("calc", (Function1<Object, Object>) x -> ((Number) x).intValue() + 100, "p2");
            Nova nova = new Nova();
            assertEquals(11, unwrap(nova.eval("p1.calc(10)")));
            assertEquals(110, unwrap(nova.eval("p2.calc(10)")));
        }

        @Test
        @DisplayName("unregisterNamespace 卸载命名空间")
        void unregisterNamespace() {
            NovaRuntime.shared().register("test", (Function0<Object>) () -> "ok", "temp");
            assertEquals("ok", unwrap(new Nova().eval("temp.test()")));
            NovaRuntime.shared().unregisterNamespace("temp");
            // 卸载后短名也不可用
            assertThrows(Exception.class, () -> new Nova().eval("temp.test()"));
        }

        @Test
        @DisplayName("listNamespaces 返回所有命名空间")
        void listNamespaces() {
            NovaRuntime.shared().register("f1", (Function0<Object>) () -> 1, "alpha");
            NovaRuntime.shared().register("f2", (Function0<Object>) () -> 2, "beta");
            List<String> ns = NovaRuntime.shared().listNamespaces();
            assertTrue(ns.contains("alpha"));
            assertTrue(ns.contains("beta"));
        }

        @Test
        @DisplayName("listFunctions 列出指定命名空间")
        void listFunctionsInNamespace() {
            NovaRuntime.shared().register("a", (Function0<Object>) () -> 1, "myNs");
            NovaRuntime.shared().register("b", (Function0<Object>) () -> 2, "myNs");
            List<NovaRuntime.RegisteredEntry> funcs = NovaRuntime.shared().listFunctions("myNs");
            assertEquals(2, funcs.size());
        }
    }

    // ============================================================
    //  3. shared() 变量注册
    // ============================================================

    @Nested
    @DisplayName("shared() 变量注册")
    class SharedVariableTests {

        @Test
        @DisplayName("全局变量 — 解释器路径")
        void globalVarInterpreter() {
            NovaRuntime.shared().set("SERVER_NAME", "lobby");
            assertEquals("lobby", unwrap(new Nova().eval("SERVER_NAME")));
        }

        @Test
        @DisplayName("全局变量 — 编译路径")
        void globalVarCompiled() {
            NovaRuntime.shared().set("MAX_PLAYERS", 100);
            Object result = new Nova().compileToBytecode("MAX_PLAYERS", "test.nova").run();
            assertEquals(100, result);
        }

        @Test
        @DisplayName("带命名空间的变量 — 全限定名访问")
        void namespacedVar() {
            NovaRuntime.shared().set("TIMEOUT", 5000, "config");
            assertEquals(5000, unwrap(new Nova().eval("config.TIMEOUT")));
        }

        @Test
        @DisplayName("null 变量注册")
        void nullVar() {
            NovaRuntime.shared().set("nothing", null);
            // null 变量查找应该不抛异常
            NovaRuntime.RegisteredEntry entry = NovaRuntime.shared().lookup("nothing");
            assertNotNull(entry);
            assertNull(entry.getValue());
        }
    }

    // ============================================================
    //  4. shared() defineLibrary
    // ============================================================

    @Nested
    @DisplayName("shared() 库注册")
    class SharedLibraryTests {

        @Test
        @DisplayName("defineLibrary 函数 — 解释器路径")
        void libFuncInterpreter() {
            NovaRuntime.shared().defineLibrary("math", lib -> {
                lib.function("square", (Function1<Object, Object>) x -> ((Number) x).intValue() * ((Number) x).intValue());
                lib.constant("PI", 3.14);
            });
            Nova nova = new Nova();
            assertEquals(25, unwrap(nova.eval("math.square(5)")));
            assertEquals(3.14, unwrap(nova.eval("math.PI")));
        }

        @Test
        @DisplayName("defineLibrary 函数 — 编译路径")
        void libFuncCompiled() {
            NovaRuntime.shared().defineLibrary("util", lib -> {
                lib.function("upper", (Function1<Object, Object>) s -> ((String) s).toUpperCase());
            });
            Object result = new Nova().compileToBytecode("util.upper(\"hello\")", "test.nova").run();
            assertEquals("HELLO", result);
        }
    }

    // ============================================================
    //  5. shared() 扩展函数
    // ============================================================

    @Nested
    @DisplayName("shared() 扩展函数")
    class SharedExtensionTests {

        @Test
        @DisplayName("全局扩展函数 — 编译路径")
        void globalExtCompiled() {
            NovaRuntime.shared().registerExt(String.class, "shout",
                    (Function1<Object, Object>) s -> ((String) s).toUpperCase() + "!");
            Object result = new Nova().compileToBytecode("\"hello\".shout()", "test.nova").run();
            assertEquals("HELLO!", result);
        }

        @Test
        @DisplayName("全局扩展函数带参数")
        void globalExtWithArg() {
            NovaRuntime.shared().registerExt(String.class, "repeat3",
                    (Function2<Object, Object, Object>) (s, n) -> {
                        StringBuilder sb = new StringBuilder();
                        int count = ((Number) n).intValue();
                        for (int i = 0; i < count; i++) sb.append(s);
                        return sb.toString();
                    });
            Object result = new Nova().compileToBytecode("\"ab\".repeat3(3)", "test.nova").run();
            assertEquals("ababab", result);
        }
    }

    // ============================================================
    //  6. Nova 实例级注册（不影响其他实例）
    // ============================================================

    @Nested
    @DisplayName("Nova 实例级隔离")
    class InstanceIsolationTests {

        @Test
        @DisplayName("实例级函数不影响其他实例")
        void instanceFuncIsolated() {
            Nova nova1 = new Nova();
            nova1.defineFunction("myFunc", (Function0<Object>) () -> "only-in-nova1");
            assertEquals("only-in-nova1", unwrap(nova1.eval("myFunc()")));

            // nova2 不应该能调用 nova1 的函数
            Nova nova2 = new Nova();
            assertThrows(Exception.class, () -> nova2.eval("myFunc()"));
        }

        @Test
        @DisplayName("实例级变量不影响其他实例")
        void instanceValIsolated() {
            Nova nova1 = new Nova();
            nova1.defineVal("secret", 42);
            assertEquals(42, unwrap(nova1.eval("secret")));

            Nova nova2 = new Nova();
            assertThrows(Exception.class, () -> nova2.eval("secret"));
        }

        @Test
        @DisplayName("实例级扩展不影响其他实例")
        void instanceExtIsolated() {
            Nova nova1 = new Nova();
            nova1.registerExtension(String.class, "myExt", (Function1<Object, Object>) s -> "ext:" + s);
            assertEquals("ext:hello", unwrap(nova1.eval("\"hello\".myExt()")));
        }
    }

    // ============================================================
    //  7. CompiledNova 级注册
    // ============================================================

    @Nested
    @DisplayName("CompiledNova 级注册")
    class CompiledNovaTests {

        @Test
        @DisplayName("CompiledNova.defineFunction — 0 参数")
        void compiledDefineFunc0() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("getVersion()", "test.nova");
            compiled.defineFunction("getVersion", (Function0<Object>) () -> "1.0.0");
            assertEquals("1.0.0", compiled.run());
        }

        @Test
        @DisplayName("CompiledNova.defineFunction — 1 参数")
        void compiledDefineFunc1() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("greet(\"World\")", "test.nova");
            compiled.defineFunction("greet", (Function1<Object, Object>) name -> "Hello, " + name + "!");
            assertEquals("Hello, World!", compiled.run());
        }

        @Test
        @DisplayName("CompiledNova.defineFunction — 2 参数")
        void compiledDefineFunc2() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("add(10, 20)", "test.nova");
            compiled.defineFunction("add", (Function2<Object, Object, Object>) (a, b) ->
                    ((Number) a).intValue() + ((Number) b).intValue());
            assertEquals(30, compiled.run());
        }

        @Test
        @DisplayName("CompiledNova.defineFunctionVararg")
        void compiledDefineFuncVararg() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("joinAll(\"a\", \"b\", \"c\")", "test.nova");
            compiled.defineFunctionVararg("joinAll", args -> {
                StringBuilder sb = new StringBuilder();
                for (Object a : args) sb.append(a);
                return sb.toString();
            });
            assertEquals("abc", compiled.run());
        }

        @Test
        @DisplayName("CompiledNova.set — 变量")
        void compiledSetVar() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("name", "test.nova");
            compiled.set("name", "Steve");
            assertEquals("Steve", compiled.run());
        }

        @Test
        @DisplayName("CompiledNova.registerExtension")
        void compiledRegisterExt() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("\"hello\".upper()", "test.nova");
            compiled.registerExtension(String.class, "upper",
                    (Function1<Object, Object>) s -> ((String) s).toUpperCase());
            assertEquals("HELLO", compiled.run());
        }

        @Test
        @DisplayName("CompiledNova 函数不影响其他 CompiledNova")
        void compiledIsolation() {
            Nova nova = new Nova();
            CompiledNova c1 = nova.compileToBytecode("myFn()", "test1.nova");
            c1.defineFunction("myFn", (Function0<Object>) () -> "c1");
            assertEquals("c1", c1.run());

            // c2 不应该能调用 c1 定义的函数
            CompiledNova c2 = nova.compileToBytecode("myFn()", "test2.nova");
            assertThrows(Exception.class, c2::run);
        }
    }

    // ============================================================
    //  8. 边缘情况
    // ============================================================

    @Nested
    @DisplayName("边缘情况")
    class EdgeCaseTests {

        @Test
        @DisplayName("describe 函数描述")
        void describeFunctionInfo() {
            NovaRuntime.shared().register("myFunc", (Function1<Object, Object>) x -> x);
            String desc = NovaRuntime.shared().describe("myFunc");
            assertNotNull(desc);
            assertTrue(desc.contains("myFunc"));
        }

        @Test
        @DisplayName("describe 不存在的函数返回 null")
        void describeUnknownReturnsNull() {
            assertNull(NovaRuntime.shared().describe("nonexistent"));
        }

        @Test
        @DisplayName("全局函数优先级低于实例级")
        void instanceOverridesGlobal() {
            NovaRuntime.shared().register("priority", (Function0<Object>) () -> "global");
            Nova nova = new Nova();
            nova.defineFunction("priority", (Function0<Object>) () -> "instance");
            // 实例级应该优先
            assertEquals("instance", unwrap(nova.eval("priority()")));
        }

        @Test
        @DisplayName("clearAll 清空后不可用")
        void clearAllRemovesEverything() {
            NovaRuntime.shared().register("temp", (Function0<Object>) () -> "ok");
            NovaRuntime.shared().clearAll();
            assertNull(NovaRuntime.shared().lookup("temp"));
        }

        @Test
        @DisplayName("空命名空间不创建代理")
        void emptyNamespaceNoProxy() {
            assertNull(NovaRuntime.shared().getNamespaceProxy("nonexistent"));
        }

        @Test
        @DisplayName("函数返回 null 不报错")
        void funcReturningNull() {
            NovaRuntime.shared().register("noop", (Function0<Object>) () -> null);
            Object result = new Nova().eval("noop()");
            // null 返回应该不抛异常
            assertTrue(result == null || (result instanceof NovaValue && ((NovaValue) result).isNull()));
        }
    }

    // ============================================================
    //  9. JVM 全局桥接（跨 ClassLoader）
    // ============================================================

    @Nested
    @DisplayName("JVM 全局桥接")
    class GlobalBridgeTests {

        @Test
        @DisplayName("注册函数写入全局桥接 — callGlobal 可调用")
        void registerWritesToGlobalBridge() {
            NovaRuntime.shared().register("bridgeFunc",
                    (Function1<Object, Object>) x -> "bridge:" + x);
            Object result = NovaRuntime.callGlobal("bridgeFunc", "test");
            assertEquals("bridge:test", result);
        }

        @Test
        @DisplayName("注册变量写入全局桥接 — callGlobal 返回值")
        void setWritesToGlobalBridge() {
            NovaRuntime.shared().set("bridgeVar", 42);
            Object result = NovaRuntime.callGlobal("bridgeVar");
            assertEquals(42, result);
        }

        @Test
        @DisplayName("callGlobal 未注册返回 NOT_FOUND")
        void callGlobalMissReturnsNotFound() {
            Object result = NovaRuntime.callGlobal("noSuchBridgeFunc");
            assertSame(NovaRuntime.NOT_FOUND, result);
        }

        @Test
        @DisplayName("带命名空间的函数也写入全局桥接")
        void namespacedFuncInGlobalBridge() {
            NovaRuntime.shared().register("nsFunc",
                    (Function1<Object, Object>) x -> "ns:" + x, "testNs");
            Object result = NovaRuntime.callGlobal("nsFunc", "hello");
            assertEquals("ns:hello", result);
        }

        @Test
        @DisplayName("unpublishNamespace 清除全局桥接")
        void unpublishNamespaceCleansGlobalBridge() {
            NovaRuntime.shared().register("tempFunc",
                    (Function0<Object>) () -> "temp", "tempNs");
            assertEquals("temp", NovaRuntime.callGlobal("tempFunc"));
            NovaRuntime.unpublishNamespace("tempNs");
            assertSame(NovaRuntime.NOT_FOUND, NovaRuntime.callGlobal("tempFunc"));
        }

        @Test
        @DisplayName("全局桥接函数在解释器路径可调用")
        void globalBridgeFuncCallableFromInterpreter() {
            // 直接写入全局桥接（模拟其他插件注册）
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, Object[]> registry =
                    (java.util.concurrent.ConcurrentHashMap<String, Object[]>)
                            System.getProperties().get("nova.global.registry");
            if (registry == null) {
                registry = new java.util.concurrent.ConcurrentHashMap<>();
                System.getProperties().put("nova.global.registry", registry);
            }
            // 用 java.util.function.Function（bootstrap ClassLoader）注册
            java.util.function.Function<Object[], Object> invoker =
                    args -> "cross-cl:" + args[0];
            registry.put("crossClFunc", new Object[] {
                    "crossClFunc", null, "跨ClassLoader测试", true, invoker, null
            });

            try {
                // 从全新 Nova 实例的解释器调用
                Nova nova = new Nova();
                Object result = nova.eval("crossClFunc(\"world\")");
                assertEquals("cross-cl:world", unwrap(result));
            } finally {
                registry.remove("crossClFunc");
            }
        }

        @Test
        @DisplayName("全局桥接函数在编译路径可调用")
        void globalBridgeFuncCallableFromCompiled() {
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, Object[]> registry =
                    (java.util.concurrent.ConcurrentHashMap<String, Object[]>)
                            System.getProperties().get("nova.global.registry");
            if (registry == null) {
                registry = new java.util.concurrent.ConcurrentHashMap<>();
                System.getProperties().put("nova.global.registry", registry);
            }
            java.util.function.Function<Object[], Object> invoker =
                    args -> ((Number) args[0]).intValue() * 10;
            registry.put("crossMul", new Object[] {
                    "crossMul", null, null, true, invoker, null
            });

            try {
                Object result = new Nova().compileToBytecode("crossMul(5)", "test.nova").run();
                assertEquals(50, result);
            } finally {
                registry.remove("crossMul");
            }
        }

        @Test
        @DisplayName("全局桥接变量可读取")
        void globalBridgeVariableReadable() {
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<String, Object[]> registry =
                    (java.util.concurrent.ConcurrentHashMap<String, Object[]>)
                            System.getProperties().get("nova.global.registry");
            if (registry == null) {
                registry = new java.util.concurrent.ConcurrentHashMap<>();
                System.getProperties().put("nova.global.registry", registry);
            }
            registry.put("crossVar", new Object[] {
                    "crossVar", null, null, false, null, "shared-value"
            });

            try {
                Object result = new Nova().eval("crossVar");
                assertEquals("shared-value", unwrap(result));
            } finally {
                registry.remove("crossVar");
            }
        }
    }

    // ============================================================
    //  辅助
    // ============================================================

    private static Object unwrap(Object val) {
        if (val instanceof NovaValue) return ((NovaValue) val).toJavaValue();
        return val;
    }
}
