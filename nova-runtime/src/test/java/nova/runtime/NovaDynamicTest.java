package nova.runtime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NovaDynamic 动态成员访问测试
 */
class NovaDynamicTest {

    // 测试辅助类
    public static class TestBean {
        public String name = "hello";
        public int count = 42;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String greet(String who) {
            return "Hello, " + who + "!";
        }

        public int add(int a, int b) {
            return a + b;
        }
    }

    public static class GetterOnlyBean {
        private String secret = "hidden";

        public String getSecret() {
            return secret;
        }

        public boolean isActive() {
            return true;
        }
    }

    public static class VarArgBean {
        public String format(String fmt, Object... args) {
            return String.format(fmt, args);
        }

        public int sum(int... nums) {
            int total = 0;
            for (int n : nums) total += n;
            return total;
        }

        public String join(String sep, String... parts) {
            return String.join(sep, parts);
        }
    }

    public static class NumericBean {
        public long toLong(long v) { return v; }
        public double toDouble(double v) { return v; }
        public float toFloat(float v) { return v; }
    }

    public static class OverloadBean {
        public String accept(String s) { return "String:" + s; }
        public String accept(Object o) { return "Object:" + o; }
        public String compute(int a) { return "int:" + a; }
        public String compute(long a) { return "long:" + a; }
    }

    @Test
    @DisplayName("公共字段读取")
    void testGetPublicField() {
        TestBean bean = new TestBean();
        assertEquals("hello", NovaDynamic.getMember(bean, "name"));
        assertEquals(42, NovaDynamic.getMember(bean, "count"));
    }

    @Test
    @DisplayName("getter 方法回退")
    void testGetterFallback() {
        GetterOnlyBean bean = new GetterOnlyBean();
        assertEquals("hidden", NovaDynamic.getMember(bean, "secret"));
    }

    @Test
    @DisplayName("isXxx getter 回退")
    void testIsGetterFallback() {
        GetterOnlyBean bean = new GetterOnlyBean();
        assertEquals(true, NovaDynamic.getMember(bean, "active"));
    }

    @Test
    @DisplayName("null target 抛异常")
    void testNullTargetThrows() {
        assertThrows(NullPointerException.class, () ->
                NovaDynamic.getMember(null, "name"));
    }

    @Test
    @DisplayName("未找到成员抛异常")
    void testMissingMemberThrows() {
        assertThrows(RuntimeException.class, () ->
                NovaDynamic.getMember(new TestBean(), "nonExistent"));
    }

    @Test
    @DisplayName("setMember 写入公共字段")
    void testSetPublicField() {
        TestBean bean = new TestBean();
        NovaDynamic.setMember(bean, "name", "world");
        assertEquals("world", bean.name);
    }

    @Test
    @DisplayName("setMember setter 回退")
    void testSetterFallback() {
        TestBean bean = new TestBean();
        NovaDynamic.setMember(bean, "name", "setter-test");
        assertEquals("setter-test", bean.name);
    }

    @Test
    @DisplayName("invokeMethod 调用正确方法")
    void testInvokeMethod() {
        TestBean bean = new TestBean();
        Object result = NovaDynamic.invokeMethod(bean, "greet", "World");
        assertEquals("Hello, World!", result);
    }

    @Test
    @DisplayName("invokeMethod 多参数")
    void testInvokeMethodMultiArgs() {
        TestBean bean = new TestBean();
        Object result = NovaDynamic.invokeMethod(bean, "add", 3, 4);
        assertEquals(7, result);
    }

    @Test
    @DisplayName("invokeMethod null target 抛异常")
    void testInvokeMethodNullThrows() {
        assertThrows(NullPointerException.class, () ->
                NovaDynamic.invokeMethod(null, "greet", "test"));
    }

    @Test
    @DisplayName("invokeMethod 未找到方法抛异常")
    void testInvokeMethodMissingThrows() {
        assertThrows(RuntimeException.class, () ->
                NovaDynamic.invokeMethod(new TestBean(), "nonExistent"));
    }

    // ============ JavaBean setter 测试（编译路径 setMember） ============

    /** 纯 JavaBean: 字段名与属性名不同，只能通过 getter/setter 访问 */
    public static class SetterBean {
        private String _label = "initial";
        private boolean _enabled = false;
        private int _count = 0;

        public String getLabel() { return _label; }
        public void setLabel(String label) { this._label = label; }
        public boolean isEnabled() { return _enabled; }
        public void setEnabled(boolean enabled) { this._enabled = enabled; }
        public int getCount() { return _count; }
        public void setCount(int count) { this._count = count; }
    }

    @Nested
    @DisplayName("JavaBean setter（setMember）")
    class JavaBeanSetterTests {

        // ---- 正常值 ----

        @Test
        @DisplayName("setMember — setXxx() 字符串属性")
        void testSetMemberStringProperty() {
            SetterBean bean = new SetterBean();
            NovaDynamic.setMember(bean, "label", "new-value");
            assertEquals("new-value", bean.getLabel());
        }

        @Test
        @DisplayName("setMember — setXxx() 布尔属性")
        void testSetMemberBooleanProperty() {
            SetterBean bean = new SetterBean();
            NovaDynamic.setMember(bean, "enabled", true);
            assertTrue(bean.isEnabled());
        }

        @Test
        @DisplayName("setMember — setXxx() int 属性")
        void testSetMemberIntProperty() {
            SetterBean bean = new SetterBean();
            NovaDynamic.setMember(bean, "count", 42);
            assertEquals(42, bean.getCount());
        }

        // ---- 边缘值 ----

        @Test
        @DisplayName("setMember — 设置为 null")
        void testSetMemberNull() {
            SetterBean bean = new SetterBean();
            NovaDynamic.setMember(bean, "label", null);
            assertNull(bean.getLabel());
        }

        @Test
        @DisplayName("setMember — 设置空字符串")
        void testSetMemberEmptyString() {
            SetterBean bean = new SetterBean();
            NovaDynamic.setMember(bean, "label", "");
            assertEquals("", bean.getLabel());
        }

        @Test
        @DisplayName("setMember 后 getMember 读回")
        void testSetThenGetRoundTrip() {
            SetterBean bean = new SetterBean();
            NovaDynamic.setMember(bean, "label", "round-trip");
            assertEquals("round-trip", NovaDynamic.getMember(bean, "label"));
        }

        // ---- 异常值 ----

        @Test
        @DisplayName("setMember — 不存在的属性抛异常")
        void testSetMemberNonExistentThrows() {
            SetterBean bean = new SetterBean();
            assertThrows(RuntimeException.class, () ->
                NovaDynamic.setMember(bean, "nonExistent", "x"));
        }

        @Test
        @DisplayName("setMember — null target 抛异常")
        void testSetMemberNullTargetThrows() {
            assertThrows(NullPointerException.class, () ->
                NovaDynamic.setMember(null, "label", "x"));
        }
    }

    // ============ Varargs 支持测试 ============

    @Nested
    @DisplayName("Varargs 支持")
    class VarargsTests {

        @Test
        @DisplayName("varargs 实例方法 — 多参数")
        void testVarargsMultiArgs() {
            VarArgBean bean = new VarArgBean();
            Object result = NovaDynamic.invokeMethod(bean, "format", "hello %s %s", "world", "!");
            assertEquals("hello world !", result);
        }

        @Test
        @DisplayName("varargs 实例方法 — 零个变参")
        void testVarargsZeroVarArgs() {
            VarArgBean bean = new VarArgBean();
            Object result = NovaDynamic.invokeMethod(bean, "sum");
            assertEquals(0, result);
        }

        @Test
        @DisplayName("varargs 实例方法 — 单个变参")
        void testVarargsSingleArg() {
            VarArgBean bean = new VarArgBean();
            Object result = NovaDynamic.invokeMethod(bean, "sum", 42);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("varargs 带固定参数")
        void testVarargsWithFixedArgs() {
            VarArgBean bean = new VarArgBean();
            Object result = NovaDynamic.invokeMethod(bean, "join", "-", "a", "b", "c");
            assertEquals("a-b-c", result);
        }

        @Test
        @DisplayName("String.format 静态调用")
        void testStringFormatViaInvoke() {
            VarArgBean bean = new VarArgBean();
            Object result = NovaDynamic.invokeMethod(bean, "format", "%d + %d = %d", 1, 2, 3);
            assertEquals("1 + 2 = 3", result);
        }
    }

    // ============ 数值宽化（isAssignable 统一）测试 ============

    @Nested
    @DisplayName("数值宽化")
    class NumericWideningTests {

        @Test
        @DisplayName("int → long 宽化")
        void testIntToLong() {
            NumericBean bean = new NumericBean();
            Object result = NovaDynamic.invokeMethod(bean, "toLong", 42);
            assertEquals(42L, result);
        }

        @Test
        @DisplayName("int → double 宽化")
        void testIntToDouble() {
            NumericBean bean = new NumericBean();
            Object result = NovaDynamic.invokeMethod(bean, "toDouble", 42);
            assertEquals(42.0, result);
        }

        @Test
        @DisplayName("int → float 宽化")
        void testIntToFloat() {
            NumericBean bean = new NumericBean();
            Object result = NovaDynamic.invokeMethod(bean, "toFloat", 42);
            assertEquals(42.0f, result);
        }

        @Test
        @DisplayName("long → double 宽化")
        void testLongToDouble() {
            NumericBean bean = new NumericBean();
            Object result = NovaDynamic.invokeMethod(bean, "toDouble", 100L);
            assertEquals(100.0, result);
        }
    }

    // ============ 方法重载最佳匹配测试 ============

    @Nested
    @DisplayName("方法重载最佳匹配")
    class OverloadResolutionTests {

        @Test
        @DisplayName("String 参数选 String 重载而非 Object")
        void testStringOverObject() {
            OverloadBean bean = new OverloadBean();
            Object result = NovaDynamic.invokeMethod(bean, "accept", "hello");
            assertEquals("String:hello", result);
        }

        @Test
        @DisplayName("非 String 参数选 Object 重载")
        void testObjectFallback() {
            OverloadBean bean = new OverloadBean();
            Object result = NovaDynamic.invokeMethod(bean, "accept", 123);
            assertEquals("Object:123", result);
        }

        @Test
        @DisplayName("int 参数选 int 重载而非 long")
        void testIntOverLong() {
            OverloadBean bean = new OverloadBean();
            Object result = NovaDynamic.invokeMethod(bean, "compute", 5);
            assertEquals("int:5", result);
        }

        @Test
        @DisplayName("long 参数选 long 重载")
        void testLongExact() {
            OverloadBean bean = new OverloadBean();
            Object result = NovaDynamic.invokeMethod(bean, "compute", 5L);
            assertEquals("long:5", result);
        }
    }
}
