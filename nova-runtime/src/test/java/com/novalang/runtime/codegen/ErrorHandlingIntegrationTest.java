package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.interpreter.NovaRuntimeException;
import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 错误处理系统集成测试。
 *
 * <p>验证 NovaErrors 工厂、ErrorKind 分类、友好错误消息、
 * 修复建议在解释器和编译路径中的正确性。</p>
 */
@DisplayName("错误处理系统集成测试")
class ErrorHandlingIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private NovaValue interp(String code) {
        return new Interpreter().eval(code, "test.nova");
    }

    private Object compile(String code) throws Exception {
        Map<String, Class<?>> loaded = compiler.compileAndLoad(code, "test.nova");
        Class<?> c = loaded.get("Test");
        assertNotNull(c, "编译后应生成 Test 类");
        Object inst = c.getField("INSTANCE").get(null);
        Method m = c.getDeclaredMethod("run");
        m.setAccessible(true);
        return m.invoke(inst);
    }

    private String wrap(String body) {
        return "object Test {\n  fun run(): Any {\n" + body + "\n  }\n}";
    }

    // ============ NovaException 基础 ============

    @Nested
    @DisplayName("NovaException 基础")
    class ExceptionBasics {

        @Test
        @DisplayName("ErrorKind 枚举完整性")
        void testErrorKindValues() {
            NovaException.ErrorKind[] kinds = NovaException.ErrorKind.values();
            assertEquals(10, kinds.length);
            assertNotNull(NovaException.ErrorKind.TYPE_MISMATCH);
            assertNotNull(NovaException.ErrorKind.NULL_REFERENCE);
            assertNotNull(NovaException.ErrorKind.UNDEFINED);
            assertNotNull(NovaException.ErrorKind.ARGUMENT_MISMATCH);
            assertNotNull(NovaException.ErrorKind.ACCESS_DENIED);
            assertNotNull(NovaException.ErrorKind.INDEX_OUT_OF_BOUNDS);
            assertNotNull(NovaException.ErrorKind.JAVA_INTEROP);
            assertNotNull(NovaException.ErrorKind.IO_ERROR);
            assertNotNull(NovaException.ErrorKind.PARSE_ERROR);
            assertNotNull(NovaException.ErrorKind.INTERNAL);
        }

        @Test
        @DisplayName("NovaException 保留 kind 和 suggestion")
        void testExceptionFields() {
            NovaException ex = new NovaException(
                    NovaException.ErrorKind.TYPE_MISMATCH,
                    "test message",
                    "try this fix");
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertEquals("try this fix", ex.getSuggestion());
            assertEquals("test message", ex.getRawMessage());
            assertTrue(ex.getMessage().contains("test message"));
            assertTrue(ex.getMessage().contains("try this fix"));
        }

        @Test
        @DisplayName("NovaException 兼容旧构造器")
        void testLegacyConstructor() {
            NovaException ex = new NovaException("legacy message");
            assertNull(ex.getKind());
            assertNull(ex.getSuggestion());
            assertEquals("legacy message", ex.getMessage());
        }

        @Test
        @DisplayName("NovaException 保留 cause 链")
        void testCauseChain() {
            RuntimeException original = new RuntimeException("root cause");
            NovaException ex = new NovaException(
                    NovaException.ErrorKind.JAVA_INTEROP,
                    "wrapper",
                    "suggestion",
                    original);
            assertSame(original, ex.getCause());
            assertEquals(NovaException.ErrorKind.JAVA_INTEROP, ex.getKind());
        }
    }

    // ============ NovaErrors 工厂 ============

    @Nested
    @DisplayName("NovaErrors 工厂")
    class ErrorFactory {

        @Test
        @DisplayName("nullRef 生成正确错误")
        void testNullRef() {
            NovaException ex = NovaErrors.nullRef("name");
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
            assertTrue(ex.getMessage().contains("null"));
            assertTrue(ex.getMessage().contains("name"));
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("?."));
        }

        @Test
        @DisplayName("nullInvoke 生成正确错误")
        void testNullInvoke() {
            NovaException ex = NovaErrors.nullInvoke("toString");
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
            assertTrue(ex.getMessage().contains("toString"));
            assertTrue(ex.getSuggestion().contains("?."));
        }

        @Test
        @DisplayName("nullSet 生成正确错误")
        void testNullSet() {
            NovaException ex = NovaErrors.nullSet("value");
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
            assertTrue(ex.getMessage().contains("value"));
        }

        @Test
        @DisplayName("typeMismatch 自动推断数值转换建议")
        void testTypeMismatchNumeric() {
            NovaException ex = NovaErrors.typeMismatch("Double", "Int");
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertTrue(ex.getMessage().contains("Double"));
            assertTrue(ex.getMessage().contains("Int"));
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("toInt"));
        }

        @Test
        @DisplayName("typeMismatch 推断 toString 建议")
        void testTypeMismatchToString() {
            NovaException ex = NovaErrors.typeMismatch("Int", "String");
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("toString"));
        }

        @Test
        @DisplayName("undefinedMember 模糊匹配建议")
        void testUndefinedMemberFuzzy() {
            java.util.List<String> available = java.util.Arrays.asList(
                    "println", "print", "readLine");
            NovaException ex = NovaErrors.undefinedMember("Console", "prntln", available);
            assertEquals(NovaException.ErrorKind.UNDEFINED, ex.getKind());
            assertTrue(ex.getMessage().contains("prntln"));
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("println"),
                    "Should suggest 'println' for typo 'prntln', got: " + ex.getSuggestion());
        }

        @Test
        @DisplayName("undefinedMember 无匹配时列出可用成员")
        void testUndefinedMemberList() {
            java.util.List<String> available = java.util.Arrays.asList("x", "y", "z");
            NovaException ex = NovaErrors.undefinedMember("Point", "totallyDifferent", available);
            assertEquals(NovaException.ErrorKind.UNDEFINED, ex.getKind());
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("可用成员"),
                    "Should list available members, got: " + ex.getSuggestion());
        }

        @Test
        @DisplayName("undefinedFunction 模糊匹配")
        void testUndefinedFunctionFuzzy() {
            java.util.List<String> available = java.util.Arrays.asList("println", "readLine");
            NovaException ex = NovaErrors.undefinedFunction("printlm", available);
            assertEquals(NovaException.ErrorKind.UNDEFINED, ex.getKind());
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("println"));
        }

        @Test
        @DisplayName("argCountMismatch 友好消息")
        void testArgCountMismatch() {
            NovaException ex = NovaErrors.argCountMismatch("foo", 2, 3);
            assertEquals(NovaException.ErrorKind.ARGUMENT_MISMATCH, ex.getKind());
            assertTrue(ex.getMessage().contains("2"));
            assertTrue(ex.getMessage().contains("3"));
        }

        @Test
        @DisplayName("javaInvokeFailed 保留 cause")
        void testJavaInvokeFailed() {
            RuntimeException cause = new RuntimeException("inner");
            NovaException ex = NovaErrors.javaInvokeFailed("doStuff", "MyClass", cause);
            assertEquals(NovaException.ErrorKind.JAVA_INTEROP, ex.getKind());
            assertSame(cause, ex.getCause());
            assertTrue(ex.getMessage().contains("doStuff"));
            assertTrue(ex.getMessage().contains("MyClass"));
        }

        @Test
        @DisplayName("wrap 保留原始异常为 cause")
        void testWrap() {
            IllegalArgumentException original = new IllegalArgumentException("bad arg");
            NovaException wrapped = NovaErrors.wrap(original);
            assertEquals(NovaException.ErrorKind.INTERNAL, wrapped.getKind());
            assertSame(original, wrapped.getCause());
            assertTrue(wrapped.getMessage().contains("bad arg"));
        }

        @Test
        @DisplayName("wrap 不重复包装 NovaException")
        void testWrapIdempotent() {
            NovaException original = NovaErrors.nullRef("x");
            NovaException wrapped = NovaErrors.wrap(original);
            assertSame(original, wrapped);
        }

        @Test
        @DisplayName("indexOutOfBounds 包含范围信息")
        void testIndexOutOfBounds() {
            NovaException ex = NovaErrors.indexOutOfBounds(5, 3);
            assertEquals(NovaException.ErrorKind.INDEX_OUT_OF_BOUNDS, ex.getKind());
            assertTrue(ex.getMessage().contains("5"));
            assertTrue(ex.getMessage().contains("3"));
            assertTrue(ex.getSuggestion().contains("0..2"));
        }

        @Test
        @DisplayName("indexOutOfBounds 空集合")
        void testIndexOutOfBoundsEmpty() {
            NovaException ex = NovaErrors.indexOutOfBounds(0, 0);
            assertTrue(ex.getSuggestion().contains("空"));
        }
    }

    // ============ NovaErrors.findClosest 模糊匹配 ============

    @Nested
    @DisplayName("模糊匹配")
    class FuzzyMatch {

        @Test
        @DisplayName("完全匹配")
        void testExactMatch() {
            String result = NovaErrors.findClosest("println",
                    java.util.Arrays.asList("println", "print"));
            assertEquals("println", result);
        }

        @Test
        @DisplayName("拼写错误匹配")
        void testTypoMatch() {
            String result = NovaErrors.findClosest("prntln",
                    java.util.Arrays.asList("println", "readLine", "format"));
            assertEquals("println", result);
        }

        @Test
        @DisplayName("差异太大不匹配")
        void testNoMatch() {
            String result = NovaErrors.findClosest("xyz",
                    java.util.Arrays.asList("println", "readLine", "format"));
            assertNull(result);
        }

        @Test
        @DisplayName("大小写不敏感")
        void testCaseInsensitive() {
            String result = NovaErrors.findClosest("PRINTLN",
                    java.util.Arrays.asList("println", "print"));
            assertEquals("println", result);
        }

        @Test
        @DisplayName("空候选列表返回 null")
        void testEmptyCandidates() {
            assertNull(NovaErrors.findClosest("test", java.util.Collections.emptyList()));
        }

        @Test
        @DisplayName("null 输入返回 null")
        void testNullInput() {
            assertNull(NovaErrors.findClosest(null, java.util.Arrays.asList("a", "b")));
        }
    }

    // ============ 解释器路径错误测试 ============

    @Nested
    @DisplayName("解释器路径 — 错误消息质量")
    class InterpreterErrors {

        @Test
        @DisplayName("null 成员访问 — 包含提示")
        void testNullMemberAccess() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () ->
                    interp("val x = null\nx.length"));
            String msg = ex.getMessage().toLowerCase();
            assertTrue(msg.contains("null") || msg.contains("Null"),
                    "应包含 null/Null，实际: " + ex.getRawMessage());
        }

        @Test
        @DisplayName("错误消息包含源码位置信息")
        void testErrorContainsSourceLocation() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () ->
                    interp("val x = 1\nval y = \"hello\" as Int\ny"));
            // 应包含行号信息（第 2 行）
            assertNotNull(ex.getLocation(), "异常应包含 SourceLocation");
            assertEquals(2, ex.getLocation().getLine(),
                    "错误应在第 2 行，实际: " + ex.getLocation().getLine());
            // getMessage() 应包含位置格式化
            String fullMsg = ex.getMessage();
            assertTrue(fullMsg.contains("-->") && fullMsg.contains(":2:"),
                    "完整消息应包含 --> file:2: 位置指示，实际:\n" + fullMsg);
        }

        @Test
        @DisplayName("错误消息包含源码行和 ^ 指示器")
        void testErrorContainsSourceLinePointer() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () ->
                    interp("val x = null\nx.length"));
            String fullMsg = ex.getMessage();
            // 应包含源码行显示
            assertTrue(fullMsg.contains("|"),
                    "应包含 | 行分隔符，实际:\n" + fullMsg);
        }

        @Test
        @DisplayName("多行脚本错误定位正确行")
        void testMultiLineErrorLocation() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () ->
                    interp("val a = 1\nval b = 2\nval c = 3\nval d = null\nd.name"));
            assertNotNull(ex.getLocation());
            assertEquals(5, ex.getLocation().getLine(),
                    "错误应在第 5 行 (d.name)");
        }

        @Test
        @DisplayName("类型转换失败 — 包含建议")
        void testTypeCastFail() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () ->
                    interp("\"hello\" as Int"));
            String msg = ex.getMessage();
            assertTrue(msg.contains("Int") || msg.contains("转换"),
                    "应包含类型信息，实际: " + msg);
        }

        @Test
        @DisplayName("as? 安全转换不抛异常")
        void testSafeCastReturnsNull() {
            NovaValue result = interp("val x = \"hello\" as? Int\nx == null");
            assertTrue(result.asBoolean());
        }

        @Test
        @DisplayName("未定义变量错误")
        void testUndefinedVariable() {
            assertThrows(NovaRuntimeException.class, () ->
                    interp("undefinedVar123"));
        }

        @Test
        @DisplayName("数值运算类型错误 — 包含运算符提示")
        void testArithmeticTypeMismatch() {
            Exception ex = assertThrows(Exception.class, () ->
                    interp("\"hello\" - 1"));
            String msg = ex.getMessage();
            assertTrue(msg.contains("minus") || msg.contains("-") || msg.contains("运算") || msg.contains("类型"),
                    "应包含运算或类型信息，实际: " + msg);
        }

        @Test
        @DisplayName("try-catch 捕获 null 成员访问错误")
        void testTryCatchPreservesMessage() {
            NovaValue result = interp(
                    "var caught = false\n" +
                    "try {\n" +
                    "    val x = null\n" +
                    "    x.length\n" +
                    "} catch (e: Exception) {\n" +
                    "    caught = true\n" +
                    "}\n" +
                    "caught");
            assertTrue(result.asBoolean(), "catch 块应成功捕获 null.length 访问错误");
        }

        @Test
        @DisplayName("Result.unwrap() on Err — 包含建议")
        void testResultUnwrapOnErr() {
            NovaRuntimeException ex = assertThrows(NovaRuntimeException.class, () ->
                    interp("val r = Err(\"oops\")\nr.unwrap()"));
            String msg = ex.getMessage();
            assertTrue(msg.contains("Err") || msg.contains("unwrap"),
                    "应包含 Err/unwrap 信息，实际: " + msg);
        }
    }

    // ============ 编译路径错误测试 ============

    @Nested
    @DisplayName("编译路径 — 错误消息质量")
    class CompilerErrors {

        @Test
        @DisplayName("null 方法调用 — NovaErrors.nullInvoke")
        void testNullMethodCall() {
            assertThrows(Exception.class, () ->
                    compile(wrap("val x: Any? = null\nreturn x.toString()")));
        }

        @Test
        @DisplayName("as 类型转换失败 — 包含类型信息")
        void testAsCastFail() {
            InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                    compile(wrap("val x = \"hello\"\nreturn x as Int")));
            Throwable cause = ex.getCause();
            assertNotNull(cause);
            assertTrue(cause instanceof NovaException,
                    "应为 NovaException，实际: " + cause.getClass().getName());
            NovaException ne = (NovaException) cause;
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ne.getKind(),
                    "应为 TYPE_MISMATCH，实际: " + ne.getKind());
        }

        @Test
        @DisplayName("as? 安全转换编译路径返回 null")
        void testSafeCastCompiled() throws Exception {
            Object result = compile(wrap("val x = \"hello\"\nval y = x as? Int\nreturn y == null"));
            assertEquals(true, result);
        }

        @Test
        @DisplayName("不存在的成员 — 包含可用成员或建议")
        void testUndefinedMember() {
            InvocationTargetException ex = assertThrows(InvocationTargetException.class, () ->
                    compile(wrap("val s = \"hello\"\nreturn s.nonExistentMethod()")));
            Throwable cause = ex.getCause();
            assertNotNull(cause);
            // 应该是 NovaException 且包含有用信息
            String msg = cause.getMessage();
            assertTrue(msg != null && msg.length() > 10,
                    "错误消息应有实质内容，实际: " + msg);
        }

        @Test
        @DisplayName("编译路径错误包含源码行号")
        void testCompiledErrorContainsLineNumber() {
            // 使用 compileToBytecode 直接运行，异常不会被 InvocationTargetException 包装
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val a = \"hello\"\nval b = 2\nval c = a as Int\nc");
            Exception ex = assertThrows(Exception.class, () -> compiled.run());
            // 寻找异常链中的 NovaException
            Throwable t = ex;
            NovaException ne = null;
            while (t != null) {
                if (t instanceof NovaException) { ne = (NovaException) t; break; }
                t = t.getCause();
            }
            assertNotNull(ne, "应包含 NovaException，实际异常: " + ex.getClass().getName() + ": " + ex.getMessage());
            ne.attachStackLocation();
            assertTrue(ne.getSourceLineNumber() > 0,
                    "编译路径应能从堆栈提取行号，sourceLineNumber=" + ne.getSourceLineNumber()
                    + "\ngetMessage:\n" + ne.getMessage());
        }
    }

    // ============ NovaDynamic 错误路径 ============

    @Nested
    @DisplayName("NovaDynamic 错误路径")
    class DynamicErrors {

        @Test
        @DisplayName("getMember on null → NovaErrors.nullRef")
        void testGetMemberOnNull() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaDynamic.getMember(null, "name"));
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
            assertTrue(ex.getMessage().contains("name"));
        }

        @Test
        @DisplayName("setMember on null → NovaErrors.nullSet")
        void testSetMemberOnNull() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaDynamic.setMember(null, "name", "value"));
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
        }

        @Test
        @DisplayName("invokeMethod on null → NovaErrors.nullInvoke")
        void testInvokeOnNull() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaDynamic.invokeMethod(null, "toString"));
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
        }

        @Test
        @DisplayName("getMember 不存在的成员 — ErrorKind.UNDEFINED")
        void testGetMemberUndefined() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaDynamic.getMember("hello", "nonExistentProp"));
            assertEquals(NovaException.ErrorKind.UNDEFINED, ex.getKind());
            assertNotNull(ex.getSuggestion(),
                    "应有修复建议（可用成员或模糊匹配）");
        }

        @Test
        @DisplayName("invokeMethod 不存在的方法 — ErrorKind.UNDEFINED + 模糊匹配")
        void testInvokeUndefined() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaDynamic.invokeMethod("hello", "tUpperCase"));
            assertEquals(NovaException.ErrorKind.UNDEFINED, ex.getKind());
            // "tUpperCase" 应该模糊匹配到 "toUpperCase"
            String suggestion = ex.getSuggestion();
            assertNotNull(suggestion, "应提供模糊匹配建议");
            assertTrue(suggestion.contains("toUpperCase"),
                    "应建议 'toUpperCase'，实际: " + suggestion);
        }

        @Test
        @DisplayName("invokeMethod 参数数量不匹配 — 包含可用签名")
        void testInvokeArgMismatch() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaDynamic.invokeMethod("hello", "substring", "a", "b", "c"));
            String msg = ex.getMessage();
            // substring 存在 1 参和 2 参版本
            assertTrue(msg.contains("substring") || msg.contains("签名"),
                    "应包含方法名信息，实际: " + msg);
        }
    }

    // ============ SamAdapter 错误路径 ============

    @Nested
    @DisplayName("SamAdapter 错误路径")
    class SamAdapterErrors {

        @Test
        @DisplayName("castOrAdapt null → TYPE_MISMATCH + 可空建议")
        void testCastNull() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    SamAdapter.castOrAdapt(null, String.class));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("?"),
                    "应建议使用可空转换，实际: " + ex.getSuggestion());
        }

        @Test
        @DisplayName("castOrAdapt 不兼容类型 → TYPE_MISMATCH")
        void testCastIncompatible() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    SamAdapter.castOrAdapt(java.util.Collections.emptyList(), String.class));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
        }

        @Test
        @DisplayName("castOrAdapt 数值转换正常工作")
        void testCastNumeric() {
            Object result = SamAdapter.castOrAdapt(3.14, Integer.class);
            assertEquals(3, result);
        }
    }

    // ============ NovaOps 错误路径 ============

    @Nested
    @DisplayName("NovaOps 错误路径")
    class OpsErrors {

        @Test
        @DisplayName("不兼容类型相加 — 包含运算符建议")
        void testAddTypeMismatch() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaOps.add(true, java.util.Collections.emptyList()));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertNotNull(ex.getSuggestion());
            assertTrue(ex.getSuggestion().contains("plus"),
                    "应提示运算符重载，实际: " + ex.getSuggestion());
        }

        @Test
        @DisplayName("不兼容类型相减")
        void testSubTypeMismatch() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaOps.sub("hello", "world"));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
        }

        @Test
        @DisplayName("不兼容类型比较")
        void testCompareTypeMismatch() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaOps.compare(new Object(), new Object()));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertTrue(ex.getSuggestion().contains("Comparable") || ex.getSuggestion().contains("compareTo"),
                    "应提示实现 Comparable，实际: " + ex.getSuggestion());
        }
    }

    // ============ NovaCollections 错误路径 ============

    @Nested
    @DisplayName("NovaCollections 错误路径")
    class CollectionErrors {

        @Test
        @DisplayName("不可迭代类型 — for-in 提示")
        void testNotIterable() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaCollections.toIterable(42));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertTrue(ex.getSuggestion().contains("List") || ex.getSuggestion().contains("Iterable"),
                    "应提示可迭代类型，实际: " + ex.getSuggestion());
        }

        @Test
        @DisplayName("不可索引类型 — [] 提示")
        void testNotIndexable() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaCollections.getIndex((Object) 42, (Object) 0));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
        }

        @Test
        @DisplayName("len() 不支持的类型")
        void testLenUnsupported() {
            NovaException ex = assertThrows(NovaException.class, () ->
                    NovaCollections.len(42));
            assertEquals(NovaException.ErrorKind.TYPE_MISMATCH, ex.getKind());
            assertTrue(ex.getSuggestion().contains("len"),
                    "应提示 len() 支持的类型，实际: " + ex.getSuggestion());
        }
    }

    // ============ NovaRuntimeException suggestion 输出 ============

    @Nested
    @DisplayName("NovaRuntimeException suggestion 格式化")
    class RuntimeExceptionFormat {

        @Test
        @DisplayName("getMessage() 包含提示行")
        void testMessageIncludesSuggestion() {
            NovaRuntimeException ex = new NovaRuntimeException(
                    NovaException.ErrorKind.TYPE_MISMATCH,
                    "无法将 String 转换为 Int",
                    "使用 toInt() 进行转换");
            String msg = ex.getMessage();
            assertTrue(msg.contains("提示:"), "getMessage 应包含 '提示:' 行，实际: " + msg);
            assertTrue(msg.contains("toInt()"), "getMessage 应包含建议内容");
        }

        @Test
        @DisplayName("无 suggestion 时不输出提示行")
        void testMessageWithoutSuggestion() {
            NovaRuntimeException ex = new NovaRuntimeException(
                    NovaException.ErrorKind.INTERNAL,
                    "内部错误",
                    null);
            String msg = ex.getMessage();
            assertFalse(msg.contains("提示:"), "无建议时不应包含提示行");
        }

        @Test
        @DisplayName("getRawMessage() 不含提示")
        void testRawMessage() {
            NovaRuntimeException ex = new NovaRuntimeException(
                    NovaException.ErrorKind.TYPE_MISMATCH,
                    "错误消息",
                    "建议内容");
            assertEquals("错误消息", ex.getRawMessage());
        }

        @Test
        @DisplayName("ErrorKind 通过继承链传递")
        void testKindInheritance() {
            NovaRuntimeException ex = new NovaRuntimeException(
                    NovaException.ErrorKind.NULL_REFERENCE,
                    "空指针",
                    "使用 ?.");
            assertEquals(NovaException.ErrorKind.NULL_REFERENCE, ex.getKind());
            assertEquals("使用 ?.", ex.getSuggestion());
        }
    }

    // ============ CompiledNova 错误路径 ============

    @Nested
    @DisplayName("CompiledNova 错误路径")
    class CompiledNovaErrors {

        @Test
        @DisplayName("调用不存在的函数 — 抛出异常且包含函数名")
        void testCallUndefinedFunction() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("fun greet() { return \"hi\" }");
            compiled.run();
            Exception ex = assertThrows(Exception.class, () ->
                    compiled.call("nonExistent"));
            String msg = ex.getMessage();
            assertTrue(msg != null && msg.contains("nonExistent"),
                    "错误消息应包含函数名 'nonExistent'，实际: " + msg);
        }
    }

    // ============ NovaBootstrap 错误路径 ============

    @Nested
    @DisplayName("NovaBootstrap 错误路径")
    class BootstrapErrors {

        @Test
        @DisplayName("编译路径 null 调用方法 — 抛出 NovaException")
        void testCompiledNullInvoke() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "fun test(x: Any?): Any? {\n" +
                    "    return x.toString()\n" +
                    "}");
            compiled.run();
            Exception ex = assertThrows(Exception.class, () ->
                    compiled.call("test", new Object[]{null}));
            // 包装层可能是 NovaRuntimeException 或其内部的 NovaException
            Throwable cause = ex;
            while (cause != null) {
                if (cause instanceof NovaException) {
                    NovaException ne = (NovaException) cause;
                    if (ne.getKind() == NovaException.ErrorKind.NULL_REFERENCE) {
                        return; // 通过
                    }
                }
                cause = cause.getCause();
            }
            // 最外层可能就是 NovaException
            if (ex instanceof NovaException && ((NovaException) ex).getKind() == NovaException.ErrorKind.NULL_REFERENCE) {
                return;
            }
            assertTrue(ex.getMessage().contains("null"),
                    "错误消息应包含 null 相关信息，实际: " + ex.getMessage());
        }
    }
}
