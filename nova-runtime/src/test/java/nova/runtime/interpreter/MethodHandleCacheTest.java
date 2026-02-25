package nova.runtime.interpreter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MethodHandleCache 单元测试 — varargs / 重载解析 / 构造器
 */
class MethodHandleCacheTest {

    private MethodHandleCache cache;

    @BeforeEach
    void setUp() {
        cache = MethodHandleCache.getInstance();
    }

    // ============ 测试辅助类 ============

    public static class VarArgHelper {
        public static String format(String fmt, Object... args) {
            return String.format(fmt, args);
        }

        public static int sum(int... nums) {
            int total = 0;
            for (int n : nums) total += n;
            return total;
        }

        public static String join(String sep, String... parts) {
            return String.join(sep, parts);
        }
    }

    public static class OverloadHelper {
        public static String accept(String s) { return "String:" + s; }
        public static String accept(Object o) { return "Object:" + o; }
        public static String num(int v) { return "int:" + v; }
        public static String num(long v) { return "long:" + v; }
        public static String num(double v) { return "double:" + v; }
    }

    public static class VarArgCtor {
        public final String result;
        public VarArgCtor(String... parts) {
            result = String.join(",", parts);
        }
    }

    public static class WideBean {
        public static long asLong(long v) { return v; }
        public static double asDouble(double v) { return v; }
    }

    // ============ Varargs 静态方法测试 ============

    @Nested
    @DisplayName("Varargs 静态方法")
    class VarargsStaticTests {

        @Test
        @DisplayName("varargs 多参数")
        void testVarargsMultipleArgs() throws Throwable {
            Object result = cache.invokeStatic(VarArgHelper.class, "format",
                    new Object[]{"hello %s %s", "world", "!"});
            assertEquals("hello world !", result);
        }

        @Test
        @DisplayName("varargs 零变参")
        void testVarargsZeroArgs() throws Throwable {
            Object result = cache.invokeStatic(VarArgHelper.class, "sum", new Object[]{});
            assertEquals(0, result);
        }

        @Test
        @DisplayName("varargs 单变参")
        void testVarargsSingleArg() throws Throwable {
            Object result = cache.invokeStatic(VarArgHelper.class, "sum", new Object[]{42});
            assertEquals(42, result);
        }

        @Test
        @DisplayName("varargs 多个 int 变参")
        void testVarargsMultipleInts() throws Throwable {
            Object result = cache.invokeStatic(VarArgHelper.class, "sum",
                    new Object[]{1, 2, 3, 4, 5});
            assertEquals(15, result);
        }

        @Test
        @DisplayName("varargs 带固定参数")
        void testVarargsWithFixedArg() throws Throwable {
            Object result = cache.invokeStatic(VarArgHelper.class, "join",
                    new Object[]{"-", "a", "b", "c"});
            assertEquals("a-b-c", result);
        }

        @Test
        @DisplayName("String.format varargs")
        void testStringFormatVarargs() throws Throwable {
            Object result = cache.invokeStatic(String.class, "format",
                    new Object[]{"%d + %d = %d", 1, 2, 3});
            assertEquals("1 + 2 = 3", result);
        }
    }

    // ============ Varargs 实例方法测试 ============

    @Nested
    @DisplayName("Varargs 实例方法")
    class VarargsInstanceTests {

        @Test
        @DisplayName("StringBuilder append 后 toString")
        void testInstanceMethodBasic() throws Throwable {
            StringBuilder sb = new StringBuilder("hello");
            cache.invokeMethod(sb, "append", new Object[]{" world"});
            assertEquals("hello world", sb.toString());
        }
    }

    // ============ Varargs 构造器测试 ============

    @Nested
    @DisplayName("Varargs 构造器")
    class VarargsConstructorTests {

        @Test
        @DisplayName("varargs 构造器 — 多参数")
        void testVarargsConstructor() throws Throwable {
            Object instance = cache.newInstance(VarArgCtor.class, new Object[]{"a", "b", "c"});
            assertTrue(instance instanceof VarArgCtor);
            assertEquals("a,b,c", ((VarArgCtor) instance).result);
        }

        @Test
        @DisplayName("varargs 构造器 — 零变参")
        void testVarargsConstructorZeroArgs() throws Throwable {
            Object instance = cache.newInstance(VarArgCtor.class, new Object[]{});
            assertTrue(instance instanceof VarArgCtor);
            assertEquals("", ((VarArgCtor) instance).result);
        }
    }

    // ============ 方法重载最佳匹配测试 ============

    @Nested
    @DisplayName("方法重载最佳匹配")
    class OverloadResolutionTests {

        @Test
        @DisplayName("String 参数选 String 重载而非 Object")
        void testStringOverObject() throws Throwable {
            Object result = cache.invokeStatic(OverloadHelper.class, "accept",
                    new Object[]{"hello"});
            assertEquals("String:hello", result);
        }

        @Test
        @DisplayName("Integer 参数选 Object 重载")
        void testIntegerFallsToObject() throws Throwable {
            Object result = cache.invokeStatic(OverloadHelper.class, "accept",
                    new Object[]{123});
            assertEquals("Object:123", result);
        }

        @Test
        @DisplayName("int 参数选 int 重载")
        void testIntOverload() throws Throwable {
            Object result = cache.invokeStatic(OverloadHelper.class, "num",
                    new Object[]{5});
            assertEquals("int:5", result);
        }

        @Test
        @DisplayName("long 参数选 long 重载")
        void testLongOverload() throws Throwable {
            Object result = cache.invokeStatic(OverloadHelper.class, "num",
                    new Object[]{5L});
            assertEquals("long:5", result);
        }

        @Test
        @DisplayName("double 参数选 double 重载")
        void testDoubleOverload() throws Throwable {
            Object result = cache.invokeStatic(OverloadHelper.class, "num",
                    new Object[]{5.0});
            assertEquals("double:5.0", result);
        }
    }

    // ============ JavaBean 属性访问测试 ============

    /** 纯 JavaBean: 字段名与属性名不同，只能通过 getter/setter 访问 */
    public static class PureJavaBean {
        private String _name = "default";
        private boolean _active = true;
        private int _value = 0;

        public String getName() { return _name; }
        public void setName(String name) { this._name = name; }
        public boolean isActive() { return _active; }
        public void setActive(boolean active) { this._active = active; }
        public int getValue() { return _value; }
        public void setValue(int value) { this._value = value; }
    }

    /** 公共字段 + getter 同名: 字段应优先 */
    public static class FieldPriorityBean {
        public String data = "field-value";
        public String getData() { return "getter-value"; }
    }

    @Nested
    @DisplayName("JavaBean 属性访问")
    class JavaBeanPropertyTests {

        // ---- getter 正常值 ----

        @Test
        @DisplayName("getField — getXxx() 回退")
        void testGetFieldViaGetter() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            Object result = cache.getField(bean, "name");
            assertEquals("default", result);
        }

        @Test
        @DisplayName("getField — isXxx() 布尔 getter 回退")
        void testGetFieldViaBooleanGetter() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            Object result = cache.getField(bean, "active");
            assertEquals(true, result);
        }

        @Test
        @DisplayName("getField — int 类型 getter")
        void testGetFieldViaIntGetter() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            Object result = cache.getField(bean, "value");
            assertEquals(0, result);
        }

        // ---- getter 边缘值 ----

        @Test
        @DisplayName("getField — 公共字段优先于 getter")
        void testFieldPriorityOverGetter() throws Throwable {
            FieldPriorityBean bean = new FieldPriorityBean();
            Object result = cache.getField(bean, "data");
            assertEquals("field-value", result);
        }

        // ---- getter 异常值 ----

        @Test
        @DisplayName("getField — 无字段无 getter 抛异常")
        void testGetFieldNonExistentThrows() {
            PureJavaBean bean = new PureJavaBean();
            assertThrows(Exception.class, () -> cache.getField(bean, "nonExistent"));
        }

        // ---- setter 正常值 ----

        @Test
        @DisplayName("setField — setXxx() 回退")
        void testSetFieldViaSetter() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            cache.setField(bean, "name", "new-value");
            assertEquals("new-value", bean.getName());
        }

        @Test
        @DisplayName("setField — boolean setter")
        void testSetFieldViaBooleanSetter() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            cache.setField(bean, "active", false);
            assertFalse(bean.isActive());
        }

        @Test
        @DisplayName("setField — int setter")
        void testSetFieldViaIntSetter() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            cache.setField(bean, "value", 99);
            assertEquals(99, bean.getValue());
        }

        // ---- setter 边缘值 ----

        @Test
        @DisplayName("setField — 设置为 null")
        void testSetFieldNull() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            cache.setField(bean, "name", null);
            assertNull(bean.getName());
        }

        @Test
        @DisplayName("setField — 设置空字符串")
        void testSetFieldEmptyString() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            cache.setField(bean, "name", "");
            assertEquals("", bean.getName());
        }

        // ---- setter 异常值 ----

        @Test
        @DisplayName("setField — 无字段无 setter 抛异常")
        void testSetFieldNonExistentThrows() {
            PureJavaBean bean = new PureJavaBean();
            assertThrows(Exception.class, () -> cache.setField(bean, "nonExistent", "x"));
        }

        // ---- getter + setter 联合 ----

        @Test
        @DisplayName("setField 后 getField 读回")
        void testSetThenGet() throws Throwable {
            PureJavaBean bean = new PureJavaBean();
            cache.setField(bean, "name", "round-trip");
            Object result = cache.getField(bean, "name");
            assertEquals("round-trip", result);
        }
    }

    // ============ 数值宽化测试 ============

    @Nested
    @DisplayName("数值宽化")
    class NumericWideningTests {

        @Test
        @DisplayName("int → long 宽化")
        void testIntToLongWidening() throws Throwable {
            Object result = cache.invokeStatic(WideBean.class, "asLong", new Object[]{42});
            assertEquals(42L, result);
        }

        @Test
        @DisplayName("int → double 宽化")
        void testIntToDoubleWidening() throws Throwable {
            Object result = cache.invokeStatic(WideBean.class, "asDouble", new Object[]{42});
            assertEquals(42.0, result);
        }

        @Test
        @DisplayName("long → double 宽化")
        void testLongToDoubleWidening() throws Throwable {
            Object result = cache.invokeStatic(WideBean.class, "asDouble", new Object[]{100L});
            assertEquals(100.0, result);
        }
    }
}
