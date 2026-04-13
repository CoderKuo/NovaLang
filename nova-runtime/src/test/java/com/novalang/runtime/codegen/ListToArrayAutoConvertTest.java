package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * List 字面量 → Java 数组参数自动转换测试。
 *
 * 目标：在 Nova 中传 [1,2,3] 给期望 int[]/String[]/Object[] 参数的 Java 方法时，
 * 自动完成 List → Array 转换，用户无需手动调用 arrayOf / toJavaValue。
 */
@DisplayName("List → Java Array 自动转换")
class ListToArrayAutoConvertTest {

    // ============ 测试用 Java 类（模拟 Java 互操作目标） ============

    public static class JavaTarget {
        /** int[] 参数 */
        public static int sumIntArray(int[] arr) {
            int sum = 0;
            for (int v : arr) sum += v;
            return sum;
        }

        /** String[] 参数 */
        public static String joinStringArray(String[] arr) {
            return String.join(",", arr);
        }

        /** Object[] 参数 */
        public static int objectArrayLength(Object[] arr) {
            return arr.length;
        }

        /** double[] 参数 */
        public static double sumDoubleArray(double[] arr) {
            double sum = 0;
            for (double v : arr) sum += v;
            return sum;
        }

        /** 混合参数: String + int[] */
        public static String prefixSum(String prefix, int[] arr) {
            int sum = 0;
            for (int v : arr) sum += v;
            return prefix + sum;
        }

        /** 实例方法 + int[] 参数 */
        public int instanceSum(int[] arr) {
            int sum = 0;
            for (int v : arr) sum += v;
            return sum;
        }
    }

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private NovaValue interp(String code) {
        Interpreter i = new Interpreter();
        // 注入 JavaTarget 类，使解释器通过 NovaJavaClass 包装器访问静态方法
        i.getGlobals().define("JavaTarget",
                new com.novalang.runtime.interpreter.JavaInterop.NovaJavaClass(JavaTarget.class), false);
        return i.eval(code, "test.nova");
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

    // ============ 解释器路径 ============

    @Nested
    @DisplayName("解释器路径")
    class InterpreterPath {

        @Test
        @DisplayName("List → int[] 自动转换")
        void testListToIntArray() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "cls.sumIntArray([1, 2, 3])");
            assertEquals(6, r.asInt());
        }

        @Test
        @DisplayName("List → String[] 自动转换")
        void testListToStringArray() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "cls.joinStringArray([\"a\", \"b\", \"c\"])");
            assertEquals("a,b,c", r.asString());
        }

        @Test
        @DisplayName("List → Object[] 自动转换")
        void testListToObjectArray() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "cls.objectArrayLength([1, \"two\", 3])");
            assertEquals(3, r.asInt());
        }

        @Test
        @DisplayName("List → double[] 自动转换")
        void testListToDoubleArray() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "cls.sumDoubleArray([1.5, 2.5, 3.0])");
            assertEquals(7.0, r.asDouble(), 0.001);
        }

        @Test
        @DisplayName("混合参数: String + List → int[]")
        void testMixedParams() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "cls.prefixSum(\"total:\", [10, 20, 30])");
            assertEquals("total:60", r.asString());
        }

        @Test
        @DisplayName("空 List → 空 int[]")
        void testEmptyListToArray() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "cls.sumIntArray([])");
            assertEquals(0, r.asInt());
        }

        @Test
        @DisplayName("实例方法 + List → int[]")
        void testInstanceMethodArray() {
            NovaValue r = interp(
                "val cls = JavaTarget\n" +
                "val obj = cls()\n" +
                "obj.instanceSum([5, 10, 15])");
            assertEquals(30, r.asInt());
        }
    }

    // ============ 编译路径 ============

    @Nested
    @DisplayName("编译路径")
    class CompiledPath {

        private Object compileWithTarget(String code) {
            Nova nova = new Nova();
            nova.set("JavaTarget", JavaTarget.class);
            CompiledNova compiled = nova.compileToBytecode(code);
            return compiled.run();
        }

        @Test
        @DisplayName("List → int[] 自动转换（编译模式）")
        void testCompiledListToIntArray() {
            Object r = compileWithTarget(
                "val cls = JavaTarget\ncls.sumIntArray([1, 2, 3])");
            assertEquals(6, r);
        }

        @Test
        @DisplayName("List → String[] 自动转换（编译模式）")
        void testCompiledListToStringArray() {
            Object r = compileWithTarget(
                "val cls = JavaTarget\ncls.joinStringArray([\"a\", \"b\", \"c\"])");
            assertEquals("a,b,c", r);
        }

        @Test
        @DisplayName("混合参数（编译模式）")
        void testCompiledMixedParams() {
            Object r = compileWithTarget(
                "val cls = JavaTarget\ncls.prefixSum(\"total:\", [10, 20, 30])");
            assertEquals("total:60", r);
        }
    }

    // ============ NovaDynamic 直接调用测试 ============

    @Nested
    @DisplayName("NovaDynamic 直接调用")
    class DirectDynamic {

        @Test
        @DisplayName("NovaDynamic.invokeMethod 传 List 给 int[] 参数")
        void testDynamicListToIntArray() {
            Object r = NovaDynamic.invokeMethod(JavaTarget.class, "sumIntArray",
                    java.util.Arrays.asList(1, 2, 3));
            assertEquals(6, r);
        }

        @Test
        @DisplayName("NovaDynamic.invokeMethod 传 List 给 String[] 参数")
        void testDynamicListToStringArray() {
            Object r = NovaDynamic.invokeMethod(JavaTarget.class, "joinStringArray",
                    java.util.Arrays.asList("x", "y"));
            assertEquals("x,y", r);
        }
    }
}
