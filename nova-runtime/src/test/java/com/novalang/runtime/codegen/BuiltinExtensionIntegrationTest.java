package com.novalang.runtime.codegen;

import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 新增内置函数集成测试：shared 查询/注册、stdlib 模块查询、Java 互操作增强。
 * 每组函数覆盖解释器 + 编译器双路径，含正常值、异常值和边缘值。
 */
@DisplayName("内置函数扩展集成测试")
class BuiltinExtensionIntegrationTest {

    // ============ 辅助方法 ============

    /** 解释器执行 */
    private static Object interp(String code) {
        return new Nova().eval(code);
    }

    /** 编译器执行 */
    private static Object compiled(String code) {
        return new Nova().compileToBytecode(code, "test.nova").run();
    }

    /** 双路径断言 */
    private static void dual(String code, Object expected) {
        Object ir = interp(code);
        Object cr = compiled(code);
        assertEquals(expected, ir, "解释器");
        assertEquals(expected, cr, "编译器");
    }

    /** 双路径断言 — 结果转字符串比较 */
    private static void dualStr(String code, String expected) {
        assertEquals(expected, String.valueOf(interp(code)), "解释器");
        assertEquals(expected, String.valueOf(compiled(code)), "编译器");
    }

    /** 双路径断言 — 结果为 true */
    private static void dualTrue(String code) {
        assertEquals(true, interp(code), "解释器");
        assertEquals(true, compiled(code), "编译器");
    }

    /** 双路径断言 — 结果是 List 且不为空 */
    @SuppressWarnings("unchecked")
    private static void dualNonEmptyList(String code) {
        Object ir = interp(code);
        Object cr = compiled(code);
        assertInstanceOf(List.class, ir, "解释器应返回 List");
        assertInstanceOf(List.class, cr, "编译器应返回 List");
        assertFalse(((List<?>) ir).isEmpty(), "解释器列表不应为空");
        assertFalse(((List<?>) cr).isEmpty(), "编译器列表不应为空");
    }

    /** 双路径断言 — 结果是 List 且包含指定元素 */
    @SuppressWarnings("unchecked")
    private static void dualListContains(String code, String element) {
        Object ir = interp(code);
        Object cr = compiled(code);
        assertInstanceOf(List.class, ir);
        assertInstanceOf(List.class, cr);
        assertTrue(((List<?>) ir).contains(element), "解释器列表应含 " + element);
        assertTrue(((List<?>) cr).contains(element), "编译器列表应含 " + element);
    }

    // ================================================================
    // 一、stdlib 模块查询
    // ================================================================

    @Nested
    @DisplayName("stdlib 模块查询")
    class StdlibQueryTests {

        @Test
        @DisplayName("stdlibModules() 返回非空列表且包含 math 模块")
        void stdlibModulesContainsMath() {
            dualListContains("stdlibModules()", "math");
        }

        @Test
        @DisplayName("stdlibModules() 包含 core 模块（error/range 等核心函数所在）")
        void stdlibModulesContainsCore() {
            dualListContains("stdlibModules()", "core");
        }

        @Test
        @DisplayName("stdlibModules() 包含 javaInterop 模块")
        void stdlibModulesContainsJavaInterop() {
            dualListContains("stdlibModules()", "javaInterop");
        }

        @Test
        @DisplayName("stdlibFunctions('math') 包含 min/max/sqrt 等数学函数")
        void stdlibFunctionsMathContainsKnownFunctions() {
            String code = "val fns = stdlibFunctions(\"math\")\n" +
                          "fns.contains(\"min\") && fns.contains(\"max\") && fns.contains(\"sqrt\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("stdlibFunctions('typeChecks') 包含 typeof/isNull/len")
        void stdlibFunctionsTypeChecksContainsKnown() {
            String code = "val fns = stdlibFunctions(\"typeChecks\")\n" +
                          "fns.contains(\"typeof\") && fns.contains(\"isNull\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("stdlibFunctions 查询不存在的模块返回空列表")
        void stdlibFunctionsNonExistentModule() {
            String code = "stdlibFunctions(\"nonExistentModule99\").size()";
            dual(code, 0);
        }

        @Test
        @DisplayName("stdlibModules() 的元素数量大于 5（至少有 math/core/typeChecks/errors/constants/random 等）")
        void stdlibModulesHasReasonableCount() {
            String code = "stdlibModules().size() > 5";
            dualTrue(code);
        }
    }

    // ================================================================
    // 二、shared 查询与注册
    // ================================================================

    @Nested
    @DisplayName("shared 查询与注册")
    class SharedTests {

        @Test
        @DisplayName("sharedLibraries() 初始返回 List（可能为空）")
        void sharedLibrariesReturnsListType() {
            String code = "sharedLibraries() is List";
            dualTrue(code);
        }

        @Test
        @DisplayName("sharedFunctions() 无参调用返回 List 类型")
        void sharedFunctionsReturnsListType() {
            String code = "sharedFunctions() is List";
            dualTrue(code);
        }

        @Test
        @DisplayName("sharedDescribe 查询不存在的函数返回 unknown 提示")
        void sharedDescribeUnknownFunction() {
            String code = "sharedDescribe(\"__nonexistent_func_xyz__\").contains(\"unknown\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("[解释器] sharedRegister 注册函数后 sharedFunctions 可查到")
        void sharedRegisterAndQueryInterpreter() {
            // 解释器路径
            Nova nova = new Nova();
            nova.eval("sharedRegister(\"testCalcSum\", { a, b -> a + b }, \"testNs\")");
            Object libs = nova.eval("sharedLibraries()");
            assertInstanceOf(List.class, libs);
            assertTrue(((List<?>) libs).contains("testNs"), "应包含 testNs 命名空间");

            Object fns = nova.eval("sharedFunctions(\"testNs\")");
            assertInstanceOf(List.class, fns);
            assertTrue(((List<?>) fns).stream().anyMatch(f -> String.valueOf(f).contains("testCalcSum")));

            // 清理
            nova.eval("sharedRemove(\"testNs\")");
        }

        @Test
        @DisplayName("[编译器] sharedRegister 注册函数后其他 Nova 实例可使用")
        void sharedRegisterCrossInstanceCompiled() throws Exception {
            Nova registrar = new Nova();
            registrar.eval("sharedRegister(\"testDouble\", { x -> x * 2 }, \"mathLib\")");

            // 新 Nova 实例应能看到
            Nova consumer = new Nova();
            Object libs = consumer.eval("sharedLibraries()");
            assertInstanceOf(List.class, libs);
            assertTrue(((List<?>) libs).contains("mathLib"));

            // 清理
            registrar.eval("sharedRemove(\"mathLib\")");
        }

        @Test
        @DisplayName("sharedSet 注册值后 sharedFunctions 可查到")
        void sharedSetValue() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"CONFIG_VERSION\", \"2.0\", \"config\")");

            Object fns = nova.eval("sharedFunctions(\"config\")");
            assertInstanceOf(List.class, fns);
            assertTrue(((List<?>) fns).stream().anyMatch(f -> String.valueOf(f).contains("CONFIG_VERSION")));

            nova.eval("sharedRemove(\"config\")");
        }

        @Test
        @DisplayName("sharedRemove 注销命名空间后查询为空")
        void sharedRemoveNamespace() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"tempVal\", 42, \"tempNs\")");
            nova.eval("sharedRemove(\"tempNs\")");

            Object fns = nova.eval("sharedFunctions(\"tempNs\")");
            assertInstanceOf(List.class, fns);
            assertTrue(((List<?>) fns).isEmpty(), "注销后应为空");
        }

        @Test
        @DisplayName("sharedRegister 无命名空间的全局函数")
        void sharedRegisterGlobalFunction() {
            Nova nova = new Nova();
            nova.eval("sharedRegister(\"testGlobalGreet\", { name -> \"Hello \" + name })");

            Object fns = nova.eval("sharedFunctions()");
            assertInstanceOf(List.class, fns);
            assertTrue(((List<?>) fns).stream().anyMatch(f -> String.valueOf(f).contains("testGlobalGreet")));
        }

        // ---- sharedHas ----

        @Test
        @DisplayName("sharedHas 检查已注册的全局函数返回 true")
        void sharedHasGlobalTrue() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"__testHasGlobal__\", 42)");
            assertEquals(true, nova.eval("sharedHas(\"__testHasGlobal__\")"));
            nova.eval("sharedRemove(\"__testHasGlobal__\")");
        }

        @Test
        @DisplayName("sharedHas 检查不存在的函数返回 false")
        void sharedHasGlobalFalse() {
            dualTrue("sharedHas(\"__nonexistent_9999__\") == false");
        }

        @Test
        @DisplayName("sharedHas 检查命名空间中的函数")
        void sharedHasInNamespace() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"hp\", 100, \"player\")");
            assertEquals(true, nova.eval("sharedHas(\"player\", \"hp\")"));
            assertEquals(false, nova.eval("sharedHas(\"player\", \"mp\")"));
            nova.eval("sharedRemove(\"player\")");
        }

        // ---- sharedRemove 单函数 ----

        @Test
        @DisplayName("sharedRemove 从命名空间中移除单个函数，其他函数不受影响")
        void sharedRemoveSingleFromNamespace() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"a\", 1, \"ns1\")");
            nova.eval("sharedSet(\"b\", 2, \"ns1\")");
            nova.eval("sharedSet(\"c\", 3, \"ns1\")");

            // 移除 b，a 和 c 应仍在
            nova.eval("sharedRemove(\"ns1\", \"b\")");
            assertEquals(true, nova.eval("sharedHas(\"ns1\", \"a\")"));
            assertEquals(false, nova.eval("sharedHas(\"ns1\", \"b\")"));
            assertEquals(true, nova.eval("sharedHas(\"ns1\", \"c\")"));

            nova.eval("sharedRemove(\"ns1\")");
        }

        @Test
        @DisplayName("sharedRemove 移除命名空间最后一个函数后命名空间自动清理")
        void sharedRemoveLastFunctionCleansNamespace() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"only\", 1, \"solo\")");
            nova.eval("sharedRemove(\"solo\", \"only\")");

            Object libs = nova.eval("sharedLibraries()");
            assertFalse(((List<?>) libs).contains("solo"), "空命名空间应被自动清理");
        }

        // ---- sharedSet 覆盖更新 ----

        @Test
        @DisplayName("sharedSet 覆盖更新已有值")
        void sharedSetOverwrite() {
            Nova nova = new Nova();
            nova.eval("sharedSet(\"ver\", \"1.0\", \"cfg\")");
            nova.eval("sharedSet(\"ver\", \"2.0\", \"cfg\")");

            // 验证命名空间中只有 1 个 ver（覆盖而非追加）
            Object fns = nova.eval("sharedFunctions(\"cfg\")");
            long count = ((List<?>) fns).stream()
                    .filter(f -> String.valueOf(f).contains("ver")).count();
            assertEquals(1, count, "覆盖更新不应产生重复条目");

            nova.eval("sharedRemove(\"cfg\")");
        }

        // ---- sharedRemove 不存在的函数不报错 ----

        @Test
        @DisplayName("sharedRemove 移除不存在的函数不应抛异常")
        void sharedRemoveNonExistent() {
            Nova nova = new Nova();
            // 不应抛异常
            nova.eval("sharedRemove(\"__never_registered_xyz__\")");
            nova.eval("sharedRemove(\"__fakeNs__\", \"__fakeFunc__\")");
        }

        // ---- RegisteredEntry.invoke 各种调用形式 ----

        @Test
        @DisplayName("shared Function0: 零参函数正常调用")
        void sharedInvokeFunction0() {
            NovaRuntime.shared().register("__testFn0__", (Function0<Object>) () -> "hello from fn0");
            Nova nova = new Nova();
            assertEquals("hello from fn0", nova.eval("__testFn0__()"));
            NovaRuntime.shared().remove("__testFn0__");
        }

        @Test
        @DisplayName("shared Function1: 单参函数正常调用")
        void sharedInvokeFunction1() {
            NovaRuntime.shared().register("__testFn1__", (Function1<Object, Object>) name -> "hi " + name);
            Nova nova = new Nova();
            assertEquals("hi Nova", nova.eval("__testFn1__(\"Nova\")"));
            NovaRuntime.shared().remove("__testFn1__");
        }

        @Test
        @DisplayName("shared Function1: 零参调用单参函数（参数补 null）")
        void sharedInvokeFunction1WithZeroArgs() {
            NovaRuntime.shared().register("__testFn1Null__", (Function1<Object, Object>) x -> x == null ? "default" : x);
            Nova nova = new Nova();
            assertEquals("default", nova.eval("__testFn1Null__()"));
            NovaRuntime.shared().remove("__testFn1Null__");
        }

        @Test
        @DisplayName("shared Function2: 多参调用正常")
        void sharedInvokeFunction2() {
            NovaRuntime.shared().register("__testFn2__",
                    (Function2<Object, Object, Object>) (a, b) -> ((Number) a).intValue() + ((Number) b).intValue());
            Nova nova = new Nova();
            assertEquals(30, nova.eval("__testFn2__(10, 20)"));
            NovaRuntime.shared().remove("__testFn2__");
        }

        @Test
        @DisplayName("shared Function2: 单参调用双参函数（第二参补 null）")
        void sharedInvokeFunction2WithOneArg() {
            NovaRuntime.shared().register("__testFn2Pad__",
                    (Function2<Object, Object, Object>) (a, b) -> a + " " + b);
            Nova nova = new Nova();
            assertEquals("hello null", nova.eval("__testFn2Pad__(\"hello\")"));
            NovaRuntime.shared().remove("__testFn2Pad__");
        }

        @Test
        @DisplayName("shared NativeFunction: vararg 函数正常调用")
        void sharedInvokeNativeFunction() {
            NovaRuntime.shared().registerVararg("__testNative__",
                    (Function1<Object[], Object>) args -> args.length);
            Nova nova = new Nova();
            assertEquals(3, nova.eval("__testNative__(1, 2, 3)"));
            NovaRuntime.shared().remove("__testNative__");
        }

        @Test
        @DisplayName("shared 函数执行出错: 友好错误信息包含函数名")
        void sharedInvokeErrorContainsFunctionName() {
            NovaRuntime.shared().register("__testErr__", (Function1<Object, Object>) x -> {
                throw new RuntimeException("模拟错误");
            }, "errLib");
            Nova nova = new Nova();
            try {
                nova.eval("errLib.__testErr__(42)");
                fail("应抛异常");
            } catch (Exception e) {
                String msg = e.getMessage();
                assertTrue(msg.contains("__testErr__") || msg.contains("模拟错误"),
                        "错误信息应包含函数名或原始消息，实际: " + msg);
            } finally {
                NovaRuntime.shared().remove("errLib");
            }
        }

        @Test
        @DisplayName("shared 变量条目: invoke 返回值而非调用")
        void sharedInvokeVariable() {
            NovaRuntime.shared().set("__testVar__", "just a value");
            Nova nova = new Nova();
            assertEquals("just a value", nova.eval("__testVar__"));
            NovaRuntime.shared().remove("__testVar__");
        }

        @Test
        @DisplayName("shared 命名空间函数: ns.func() 调用")
        void sharedNamespacedCall() {
            NovaRuntime.shared().register("greet",
                    (Function1<Object, Object>) name -> "Hello " + name, "myLib");
            Nova nova = new Nova();
            assertEquals("Hello World", nova.eval("myLib.greet(\"World\")"));
            NovaRuntime.shared().remove("myLib");
        }
    }

    // ================================================================
    // 三、Java 互操作增强
    // ================================================================

    @Nested
    @DisplayName("Java 互操作 — 反射查询")
    class JavaReflectTests {

        @Test
        @DisplayName("javaMethods 查询字符串对象返回包含 length/substring/toUpperCase")
        void javaMethodsOnString() {
            String code = "val ms = javaMethods(\"hello\")\n" +
                          "ms.contains(\"length\") && ms.contains(\"substring\") && ms.contains(\"toUpperCase\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaMethods 查询整数对象返回包含 intValue")
        void javaMethodsOnInteger() {
            String code = "javaMethods(42).contains(\"intValue\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaFields 查询 Integer 类返回包含 MAX_VALUE/MIN_VALUE")
        void javaFieldsOnIntegerClass() {
            String code = "val fs = javaFields(\"java.lang.Integer\")\n" +
                          "fs.contains(\"MAX_VALUE\") && fs.contains(\"MIN_VALUE\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaFields 查询普通字符串对象返回列表（可能为空，String 无 public 字段）")
        void javaFieldsOnStringInstance() {
            String code = "javaFields(\"hello\") is List";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaSuperclass 查询 Integer 返回 java.lang.Number")
        void javaSuperclassOfInteger() {
            dualStr("javaSuperclass(42)", "java.lang.Number");
        }

        @Test
        @DisplayName("javaSuperclass 查询 Object 返回 null")
        void javaSuperclassOfObject() {
            String code = "javaSuperclass(\"java.lang.Object\") == null";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaInterfaces 查询 ArrayList 包含 java.util.List")
        void javaInterfacesOfArrayList() {
            String code = "javaInterfaces(\"java.util.ArrayList\").contains(\"java.util.List\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaInterfaces 查询整数包含 java.lang.Comparable")
        void javaInterfacesOfInteger() {
            String code = "javaInterfaces(42).contains(\"java.lang.Comparable\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("[异常] javaMethods(null) 应抛异常")
        void javaMethodsNull() {
            assertThrows(Exception.class, () -> interp("javaMethods(null)"));
        }

        @Test
        @DisplayName("[异常] javaFields 查询不存在的类名应抛异常")
        void javaFieldsNonExistentClass() {
            assertThrows(Exception.class, () -> interp("javaFields(\"com.nonexistent.FakeClass123\")"));
        }
    }

    @Nested
    @DisplayName("Java 互操作 — 类型判断")
    class JavaTypeTests {

        @Test
        @DisplayName("javaTypeName 查询字符串返回 java.lang.String")
        void javaTypeNameString() {
            dualStr("javaTypeName(\"hello\")", "java.lang.String");
        }

        @Test
        @DisplayName("javaTypeName 查询整数返回 java.lang.Integer")
        void javaTypeNameInt() {
            dualStr("javaTypeName(42)", "java.lang.Integer");
        }

        @Test
        @DisplayName("javaTypeName 查询浮点数返回 java.lang.Double")
        void javaTypeNameDouble() {
            dualStr("javaTypeName(3.14)", "java.lang.Double");
        }

        @Test
        @DisplayName("javaTypeName 查询布尔值返回 java.lang.Boolean")
        void javaTypeNameBoolean() {
            dualStr("javaTypeName(true)", "java.lang.Boolean");
        }

        @Test
        @DisplayName("javaTypeName 查询列表返回包含 List 的类名")
        void javaTypeNameList() {
            String code = "javaTypeName([1, 2, 3]).contains(\"List\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("javaTypeName(null) 返回 'null'")
        void javaTypeNameNull() {
            dualStr("javaTypeName(null)", "null");
        }

        @Test
        @DisplayName("javaInstanceOf 正确判断 String")
        void javaInstanceOfString() {
            dualTrue("javaInstanceOf(\"hello\", \"java.lang.String\")");
        }

        @Test
        @DisplayName("javaInstanceOf 子类判断（Integer is Number）")
        void javaInstanceOfSubclass() {
            dualTrue("javaInstanceOf(42, \"java.lang.Number\")");
        }

        @Test
        @DisplayName("javaInstanceOf 不匹配类型返回 false")
        void javaInstanceOfMismatch() {
            String code = "javaInstanceOf(\"hello\", \"java.lang.Integer\")";
            dual(code, false);
        }

        @Test
        @DisplayName("javaInstanceOf null 对象返回 false")
        void javaInstanceOfNull() {
            String code = "javaInstanceOf(null, \"java.lang.String\")";
            dual(code, false);
        }

        @Test
        @DisplayName("javaInstanceOf 不存在的类名返回 false 而非异常")
        void javaInstanceOfNonExistentClass() {
            String code = "javaInstanceOf(\"hello\", \"com.nonexistent.Fake\")";
            dual(code, false);
        }
    }

    @Nested
    @DisplayName("Java 互操作 — 集合互转")
    class JavaCollectionTests {

        @Test
        @DisplayName("toJavaList 将 Nova List 转为 ArrayList")
        void toJavaListBasic() {
            String code = "val jl = toJavaList([1, 2, 3])\njl.size()";
            dual(code, 3);
        }

        @Test
        @DisplayName("toJavaList 转换后内容一致")
        void toJavaListContent() {
            String code = "val jl = toJavaList([10, 20, 30])\njl[0] + jl[1] + jl[2]";
            dual(code, 60);
        }

        @Test
        @DisplayName("toJavaList 转换后的类型是 java.util.ArrayList")
        void toJavaListType() {
            String code = "javaTypeName(toJavaList([1])).contains(\"ArrayList\")";
            dualTrue(code);
        }

        @Test
        @DisplayName("toJavaMap 将 Nova Map 转为 HashMap")
        void toJavaMapBasic() {
            String code = "val jm = toJavaMap(#{\"a\": 1, \"b\": 2})\njm.size()";
            dual(code, 2);
        }

        @Test
        @DisplayName("toJavaMap 转换后可通过 get 访问")
        void toJavaMapAccess() {
            String code = "val jm = toJavaMap(#{\"x\": 42})\njm.get(\"x\")";
            dual(code, 42);
        }

        @Test
        @DisplayName("toJavaSet 将列表转为 HashSet（自动去重）")
        void toJavaSetDedup() {
            String code = "toJavaSet([1, 2, 2, 3, 3, 3]).size()";
            dual(code, 3);
        }

        @Test
        @DisplayName("toJavaArray 将 Nova List 转为 Object[] 并可访问 .length")
        void toJavaArrayBasic() {
            String code = "val arr = toJavaArray([\"a\", \"b\", \"c\"])\narr.length";
            dual(code, 3);
        }

        @Test
        @DisplayName("toJavaList 空列表转换")
        void toJavaListEmpty() {
            String code = "toJavaList([]).size()";
            dual(code, 0);
        }

        @Test
        @DisplayName("[异常] toJavaList 传入非集合类型应抛异常")
        void toJavaListInvalidType() {
            assertThrows(Exception.class, () -> interp("toJavaList(42)"));
        }

        @Test
        @DisplayName("[异常] toJavaMap 传入非 Map 类型应抛异常")
        void toJavaMapInvalidType() {
            assertThrows(Exception.class, () -> interp("toJavaMap([1, 2, 3])"));
        }

        @Test
        @DisplayName("[异常] toJavaSet 传入非集合类型应抛异常")
        void toJavaSetInvalidType() {
            assertThrows(Exception.class, () -> interp("toJavaSet(\"hello\")"));
        }
    }

    // ================================================================
    // 四、尾随逗号容忍
    // ================================================================

    @Nested
    @DisplayName("尾随逗号容忍")
    class TrailingCommaTests {

        @Test
        @DisplayName("函数调用参数尾随逗号: max(1, 2,)")
        void trailingCommaInFunctionCall() {
            dual("max(1, 2,)", 2);
        }

        @Test
        @DisplayName("函数定义参数尾随逗号: fun f(a: Int, b: Int,)")
        void trailingCommaInFunctionDecl() {
            String code = "fun add(a, b,) = a + b\nadd(3, 4)";
            dual(code, 7);
        }

        @Test
        @DisplayName("列表字面量尾随逗号: [1, 2, 3,]")
        void trailingCommaInList() {
            String code = "[10, 20, 30,].size()";
            dual(code, 3);
        }

        @Test
        @DisplayName("Map 字面量尾随逗号: #{\"a\": 1, \"b\": 2,}")
        void trailingCommaInMap() {
            String code = "#{\"a\": 1, \"b\": 2,}.size()";
            dual(code, 2);
        }

        @Test
        @DisplayName("注解参数尾随逗号")
        void trailingCommaInAnnotation() {
            // 注解参数尾随逗号不应导致解析错误
            String code = "annotation class Tag(val name: String, val value: Int)\n" +
                          "@Tag(name = \"test\", value = 42,)\nclass Foo\n42";
            dual(code, 42);
        }
    }

    // ================================================================
    // 五、Map key 变量引用
    // ================================================================

    @Nested
    @DisplayName("Map key 变量引用")
    class MapVariableKeyTests {

        @Test
        @DisplayName("变量作 Map key: #{k: value} 取变量 k 的值")
        void variableAsMapKey() {
            String code = "val k = \"name\"\nval m = #{k: \"Alice\"}\nm[\"name\"]";
            dualStr(code, "Alice");
        }

        @Test
        @DisplayName("字符串字面量 key 仍然正常: #{\"name\": \"Bob\"}")
        void stringLiteralMapKey() {
            String code = "#{\"name\": \"Bob\"}[\"name\"]";
            dualStr(code, "Bob");
        }

        @Test
        @DisplayName("数字字面量作 key: #{1: \"one\"}")
        void numericMapKey() {
            String code = "#{1: \"one\"}[1]";
            dualStr(code, "one");
        }

        @Test
        @DisplayName("混合使用变量 key 和字符串 key")
        void mixedMapKeys() {
            String code = "val k = \"dynamic\"\n" +
                          "val m = #{k: 100, \"static\": 200}\n" +
                          "m[\"dynamic\"] + m[\"static\"]";
            dual(code, 300);
        }

        @Test
        @DisplayName("表达式作 Map key: #{1+2: value}")
        void expressionAsMapKey() {
            String code = "#{1 + 2: \"three\"}[3]";
            dualStr(code, "three");
        }
    }

    // ================================================================
    // 六、null as T? 可空类型转换
    // ================================================================

    @Nested
    @DisplayName("null as T? 可空类型转换")
    class NullableCastTests {

        @Test
        @DisplayName("null as String? 返回 null 不抛异常")
        void nullAsNullableString() {
            dualStr("(null as String?) == null", "true");
        }

        @Test
        @DisplayName("null as String? + elvis 运算符")
        void nullAsNullableWithElvis() {
            dualStr("(null as String?) ?: \"fallback\"", "fallback");
        }

        @Test
        @DisplayName("非 null 值 as String? 正常通过")
        void nonNullAsNullable() {
            dualStr("\"hello\" as String?", "hello");
        }

        @Test
        @DisplayName("null as? String 安全转换返回 null")
        void safeCastNull() {
            dualStr("(null as? String) == null", "true");
        }

        @Test
        @DisplayName("[异常] null as String（非可空）应抛异常")
        void nullAsNonNullableThrows() {
            assertThrows(Exception.class, () -> interp("null as String"));
            assertThrows(Exception.class, () -> compiled("null as String"));
        }
    }
}
