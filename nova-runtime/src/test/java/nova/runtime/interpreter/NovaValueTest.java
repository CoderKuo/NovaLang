package nova.runtime.interpreter;

import nova.runtime.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NovaValue 类型系统单元测试
 */
class NovaValueTest {

    // ============ NovaInt 测试 ============

    @Nested
    @DisplayName("NovaInt")
    class NovaIntTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            NovaInt i = new NovaInt(42);
            assertEquals(42, i.getValue());
            assertEquals(42, i.asInt());
            assertTrue(i.isInt());
        }

        @Test
        @DisplayName("类型转换")
        void testConversion() {
            NovaInt i = new NovaInt(42);
            assertEquals(42L, i.asLong());
            assertEquals(42.0, i.asDouble(), 0.001);
            assertEquals("42", i.asString());
        }

        @Test
        @DisplayName("相等性")
        void testEquality() {
            NovaInt a = new NovaInt(42);
            NovaInt b = new NovaInt(42);
            NovaInt c = new NovaInt(99);

            assertEquals(a, b);
            assertNotEquals(a, c);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("算术运算")
        void testArithmetic() {
            NovaInt a = new NovaInt(10);
            NovaInt b = new NovaInt(3);

            assertEquals(13, a.add(b).asInt());
            assertEquals(7, a.subtract(b).asInt());
            assertEquals(30, a.multiply(b).asInt());
            assertEquals(3, a.divide(b).asInt());
            assertEquals(1, a.modulo(b).asInt());
            assertEquals(-10, a.negate().asInt());
        }

        @Test
        @DisplayName("比较运算")
        void testComparison() {
            NovaInt a = new NovaInt(10);
            NovaInt b = new NovaInt(5);
            NovaInt c = new NovaInt(10);

            assertTrue(a.greaterThan(b));
            assertFalse(b.greaterThan(a));
            assertTrue(b.lessThan(a));
            assertTrue(a.greaterOrEqual(c));
            assertTrue(a.lessOrEqual(c));
        }
    }

    // ============ NovaDouble 测试 ============

    @Nested
    @DisplayName("NovaDouble")
    class NovaDoubleTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            NovaDouble d = new NovaDouble(3.14);
            assertEquals(3.14, d.getValue(), 0.001);
            assertEquals(3.14, d.asDouble(), 0.001);
            assertTrue(d.isDouble());
        }

        @Test
        @DisplayName("类型转换")
        void testConversion() {
            NovaDouble d = new NovaDouble(3.7);
            assertEquals(3, d.asInt());
            assertEquals(3L, d.asLong());
            assertEquals("3.7", d.asString());
        }

        @Test
        @DisplayName("算术运算")
        void testArithmetic() {
            NovaDouble a = new NovaDouble(10.0);
            NovaDouble b = new NovaDouble(3.0);

            assertEquals(13.0, a.add(b).asDouble(), 0.001);
            assertEquals(7.0, a.subtract(b).asDouble(), 0.001);
            assertEquals(30.0, a.multiply(b).asDouble(), 0.001);
            assertEquals(3.333, a.divide(b).asDouble(), 0.01);
        }
    }

    // ============ NovaString 测试 ============

    @Nested
    @DisplayName("NovaString")
    class NovaStringTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            NovaString s = NovaString.of("hello");
            assertEquals("hello", s.getValue());
            assertEquals("hello", s.asString());
            assertTrue(s.isString());
        }

        @Test
        @DisplayName("字符串拼接")
        void testConcatenation() {
            NovaString a = NovaString.of("hello");
            NovaString b = NovaString.of(" world");

            assertEquals("hello world", a.concat(b).asString());
        }

        @Test
        @DisplayName("字符串方法")
        void testMethods() {
            NovaString s = NovaString.of("Hello World");

            assertEquals(11, s.length());
            assertEquals("HELLO WORLD", s.toUpperCase().asString());
            assertEquals("hello world", s.toLowerCase().asString());
            assertTrue(s.contains("World"));
            assertFalse(s.contains("xyz"));
            assertTrue(s.startsWith("Hello"));
            assertTrue(s.endsWith("World"));
            assertEquals("Hello", s.substring(0, 5).asString());
            assertEquals("Jello World", s.replace("H", "J").asString());
        }

        @Test
        @DisplayName("相等性")
        void testEquality() {
            NovaString a = NovaString.of("test");
            NovaString b = NovaString.of("test");
            NovaString c = NovaString.of("other");

            assertEquals(a, b);
            assertNotEquals(a, c);
        }
    }

    // ============ NovaBoolean 测试 ============

    @Nested
    @DisplayName("NovaBoolean")
    class NovaBooleanTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            assertTrue(NovaBoolean.TRUE.asBool());
            assertFalse(NovaBoolean.FALSE.asBool());
            assertTrue(NovaBoolean.TRUE.isBoolean());
        }

        @Test
        @DisplayName("逻辑运算")
        void testLogicalOps() {
            assertEquals(NovaBoolean.TRUE, NovaBoolean.TRUE.and(NovaBoolean.TRUE));
            assertEquals(NovaBoolean.FALSE, NovaBoolean.TRUE.and(NovaBoolean.FALSE));
            assertEquals(NovaBoolean.TRUE, NovaBoolean.FALSE.or(NovaBoolean.TRUE));
            assertEquals(NovaBoolean.FALSE, NovaBoolean.TRUE.not());
            assertEquals(NovaBoolean.TRUE, NovaBoolean.FALSE.not());
        }

        @Test
        @DisplayName("字符串转换")
        void testToString() {
            assertEquals("true", NovaBoolean.TRUE.asString());
            assertEquals("false", NovaBoolean.FALSE.asString());
        }
    }

    // ============ NovaNull 测试 ============

    @Nested
    @DisplayName("NovaNull")
    class NovaValueNullTest {

        @Test
        @DisplayName("null 值")
        void testNull() {
            assertTrue(NovaNull.NULL.isNull());
            assertEquals("null", NovaNull.NULL.asString());
        }

        @Test
        @DisplayName("Unit 值")
        void testUnit() {
            assertFalse(NovaNull.UNIT.isNull());
            assertEquals("Unit", NovaNull.UNIT.asString());
        }
    }

    // ============ NovaList 测试 ============

    @Nested
    @DisplayName("NovaList")
    class NovaListTest {

        @Test
        @DisplayName("创建空列表")
        void testEmptyList() {
            NovaList list = new NovaList();
            assertEquals(0, list.size());
            assertTrue(list.isEmpty());
        }

        @Test
        @DisplayName("添加和获取元素")
        void testAddAndGet() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            list.add(new NovaInt(3));

            assertEquals(3, list.size());
            assertEquals(1, list.get(0).asInt());
            assertEquals(2, list.get(1).asInt());
            assertEquals(3, list.get(2).asInt());
        }

        @Test
        @DisplayName("设置元素")
        void testSet() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));

            list.set(1, new NovaInt(99));
            assertEquals(99, list.get(1).asInt());
        }

        @Test
        @DisplayName("移除元素")
        void testRemove() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            list.add(new NovaInt(3));

            list.removeAt(1);
            assertEquals(2, list.size());
            assertEquals(3, list.get(1).asInt());
        }

        @Test
        @DisplayName("first 和 last")
        void testFirstLast() {
            NovaList list = new NovaList();
            list.add(new NovaInt(10));
            list.add(new NovaInt(20));
            list.add(new NovaInt(30));

            assertEquals(10, list.first().asInt());
            assertEquals(30, list.last().asInt());
        }

        @Test
        @DisplayName("contains")
        void testContains() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));

            assertTrue(list.contains(new NovaInt(1)));
            assertFalse(list.contains(new NovaInt(99)));
        }

        @Test
        @DisplayName("indexOf")
        void testIndexOf() {
            NovaList list = new NovaList();
            list.add(NovaString.of("a"));
            list.add(NovaString.of("b"));
            list.add(NovaString.of("c"));

            assertEquals(1, list.indexOf(NovaString.of("b")));
            assertEquals(-1, list.indexOf(NovaString.of("z")));
        }

        @Test
        @DisplayName("slice")
        void testSlice() {
            NovaList list = new NovaList();
            for (int i = 0; i < 5; i++) {
                list.add(new NovaInt(i));
            }

            NovaList sliced = list.slice(1, 4);
            assertEquals(3, sliced.size());
            assertEquals(1, sliced.get(0).asInt());
            assertEquals(3, sliced.get(2).asInt());
        }

        @Test
        @DisplayName("迭代")
        void testIteration() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            list.add(new NovaInt(3));

            int sum = 0;
            for (NovaValue v : list) {
                sum += v.asInt();
            }
            assertEquals(6, sum);
        }
    }

    // ============ NovaMap 测试 ============

    @Nested
    @DisplayName("NovaMap")
    class NovaMapTest {

        @Test
        @DisplayName("创建空 Map")
        void testEmptyMap() {
            NovaMap map = new NovaMap();
            assertEquals(0, map.size());
            assertTrue(map.isEmpty());
        }

        @Test
        @DisplayName("put 和 get")
        void testPutAndGet() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("name"), NovaString.of("Alice"));
            map.put(NovaString.of("age"), new NovaInt(30));

            assertEquals(2, map.size());
            assertEquals("Alice", map.get(NovaString.of("name")).asString());
            assertEquals(30, map.get(NovaString.of("age")).asInt());
        }

        @Test
        @DisplayName("containsKey")
        void testContainsKey() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("key"), new NovaInt(1));

            assertTrue(map.containsKey(NovaString.of("key")));
            assertFalse(map.containsKey(NovaString.of("other")));
        }

        @Test
        @DisplayName("remove")
        void testRemove() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("a"), new NovaInt(1));
            map.put(NovaString.of("b"), new NovaInt(2));

            map.remove(NovaString.of("a"));
            assertEquals(1, map.size());
            assertFalse(map.containsKey(NovaString.of("a")));
        }

        @Test
        @DisplayName("keys 和 values")
        void testKeysAndValues() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("x"), new NovaInt(10));
            map.put(NovaString.of("y"), new NovaInt(20));

            NovaList keys = map.keysList();
            NovaList values = map.valuesList();

            assertEquals(2, keys.size());
            assertEquals(2, values.size());
        }

        @Test
        @DisplayName("clear")
        void testClear() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("a"), new NovaInt(1));
            map.put(NovaString.of("b"), new NovaInt(2));
            map.clear();
            assertEquals(0, map.size());
            assertTrue(map.isEmpty());
        }

        @Test
        @DisplayName("containsValue")
        void testContainsValue() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("a"), new NovaInt(42));
            assertTrue(map.containsValue(new NovaInt(42)));
            assertFalse(map.containsValue(new NovaInt(99)));
        }

        @Test
        @DisplayName("getOrDefault")
        void testGetOrDefault() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("a"), new NovaInt(1));
            assertEquals(1, map.getOrDefault(NovaString.of("a"), new NovaInt(99)).asInt());
            assertEquals(99, map.getOrDefault(NovaString.of("z"), new NovaInt(99)).asInt());
        }

        @Test
        @DisplayName("putAll")
        void testPutAll() {
            NovaMap m1 = new NovaMap();
            m1.put(NovaString.of("a"), new NovaInt(1));
            NovaMap m2 = new NovaMap();
            m2.put(NovaString.of("b"), new NovaInt(2));
            m2.put(NovaString.of("c"), new NovaInt(3));
            m1.putAll(m2);
            assertEquals(3, m1.size());
        }

        @Test
        @DisplayName("equals 和 hashCode")
        void testEqualsAndHashCode() {
            NovaMap m1 = new NovaMap();
            m1.put(NovaString.of("x"), new NovaInt(1));
            NovaMap m2 = new NovaMap();
            m2.put(NovaString.of("x"), new NovaInt(1));
            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        @DisplayName("toString 输出")
        void testToString() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("a"), new NovaInt(1));
            String str = map.toString();
            assertTrue(str.contains("a") && str.contains("1"));
        }

        @Test
        @DisplayName("toJavaValue")
        void testToJavaValue() {
            NovaMap map = new NovaMap();
            map.put(NovaString.of("k"), new NovaInt(42));
            Object javaVal = map.toJavaValue();
            assertTrue(javaVal instanceof java.util.Map);
        }

        @Test
        @DisplayName("isTruthy")
        void testIsTruthy() {
            NovaMap empty = new NovaMap();
            assertFalse(empty.isTruthy());
            NovaMap nonEmpty = new NovaMap();
            nonEmpty.put(NovaString.of("a"), new NovaInt(1));
            assertTrue(nonEmpty.isTruthy());
        }

        @Test
        @DisplayName("isMap 标识")
        void testIsMap() {
            NovaMap map = new NovaMap();
            assertTrue(map.isMap());
        }
    }

    // ============ NovaLong 测试 ============

    @Nested
    @DisplayName("NovaLong")
    class NovaLongTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            NovaLong l = NovaLong.of(1000000000000L);
            assertEquals(1000000000000L, l.getValue());
            assertEquals(1000000000000L, l.asLong());
            assertEquals("Long", l.getTypeName());
        }

        @Test
        @DisplayName("缓存命中")
        void testCacheHit() {
            NovaLong a = NovaLong.of(100);
            NovaLong b = NovaLong.of(100);
            assertSame(a, b); // 缓存范围内应该是同一个对象
        }

        @Test
        @DisplayName("类型转换")
        void testConversion() {
            NovaLong l = NovaLong.of(42L);
            assertEquals(42, l.asInt());
            assertEquals(42.0, l.asDouble(), 0.001);
            assertEquals("42", l.asString());
        }

        @Test
        @DisplayName("算术运算")
        void testArithmetic() {
            NovaLong a = NovaLong.of(10L);
            NovaLong b = NovaLong.of(3L);
            assertEquals(13L, a.add(b).asLong());
            assertEquals(7L, a.subtract(b).asLong());
            assertEquals(30L, a.multiply(b).asLong());
            assertEquals(3L, a.divide(b).asLong());
            assertEquals(1L, a.modulo(b).asLong());
            assertEquals(-10L, a.negate().asLong());
        }

        @Test
        @DisplayName("相等性")
        void testEquality() {
            NovaLong a = NovaLong.of(42L);
            NovaLong b = NovaLong.of(42L);
            NovaLong c = NovaLong.of(99L);
            assertEquals(a, b);
            assertNotEquals(a, c);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("与 Int 比较")
        void testEqualsWithInt() {
            NovaLong l = NovaLong.of(42L);
            NovaInt i = new NovaInt(42);
            assertTrue(l.equals(i));
        }

        @Test
        @DisplayName("isNumber 标识")
        void testIsNumber() {
            assertTrue(NovaLong.of(1L).isNumber());
        }
    }

    // ============ NovaFloat 测试 ============

    @Nested
    @DisplayName("NovaFloat")
    class NovaFloatTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            NovaFloat f = NovaFloat.of(3.14f);
            assertEquals(3.14f, f.getValue(), 0.001f);
            assertEquals("Float", f.getTypeName());
        }

        @Test
        @DisplayName("类型转换")
        void testConversion() {
            NovaFloat f = NovaFloat.of(3.7f);
            assertEquals(3, f.asInt());
            assertEquals(3L, f.asLong());
            assertEquals(3.7, f.asDouble(), 0.01);
        }

        @Test
        @DisplayName("算术运算")
        void testArithmetic() {
            NovaFloat a = NovaFloat.of(10.0f);
            NovaFloat b = NovaFloat.of(3.0f);
            assertEquals(13.0f, a.add(b).getValue(), 0.001f);
            assertEquals(7.0f, a.subtract(b).getValue(), 0.001f);
            assertEquals(30.0f, a.multiply(b).getValue(), 0.001f);
            assertEquals(3.333f, a.divide(b).getValue(), 0.01f);
        }

        @Test
        @DisplayName("取负")
        void testNegate() {
            NovaFloat f = NovaFloat.of(5.0f);
            assertEquals(-5.0f, f.negate().getValue(), 0.001f);
        }

        @Test
        @DisplayName("相等性")
        void testEquality() {
            NovaFloat a = NovaFloat.of(1.5f);
            NovaFloat b = NovaFloat.of(1.5f);
            NovaFloat c = NovaFloat.of(2.5f);
            assertEquals(a, b);
            assertNotEquals(a, c);
        }

        @Test
        @DisplayName("与其他数字类型比较")
        void testCrossTypeEquals() {
            NovaFloat f = NovaFloat.of(3.0f);
            NovaInt i = new NovaInt(3);
            NovaDouble d = new NovaDouble(3.0);
            assertTrue(f.equals(i));
            assertTrue(f.equals(d));
        }

        @Test
        @DisplayName("isNumber 标识")
        void testIsNumber() {
            assertTrue(NovaFloat.of(1.0f).isNumber());
        }

        @Test
        @DisplayName("toJavaValue")
        void testToJavaValue() {
            NovaFloat f = NovaFloat.of(1.5f);
            assertEquals(1.5f, (Float) f.toJavaValue(), 0.001f);
        }

        @Test
        @DisplayName("toString 带 f 后缀")
        void testToString() {
            String str = NovaFloat.of(3.14f).toString();
            assertTrue(str.contains("3.14"));
        }
    }

    // ============ NovaChar 直接单元测试 ============

    @Nested
    @DisplayName("NovaChar")
    class NovaCharTest {

        @Test
        @DisplayName("创建和获取值")
        void testCreateAndGet() {
            NovaChar c = NovaChar.of('A');
            assertEquals('A', c.getValue());
            assertEquals("Char", c.getTypeName());
        }

        @Test
        @DisplayName("ASCII 缓存")
        void testCache() {
            NovaChar a = NovaChar.of('A');
            NovaChar b = NovaChar.of('A');
            assertSame(a, b);
        }

        @Test
        @DisplayName("asInt 返回 Unicode 值")
        void testAsInt() {
            assertEquals(65, NovaChar.of('A').asInt());
            assertEquals(48, NovaChar.of('0').asInt());
        }

        @Test
        @DisplayName("asString 返回字符")
        void testAsString() {
            assertEquals("A", NovaChar.of('A').asString());
        }

        @Test
        @DisplayName("toString 带引号")
        void testToString() {
            String str = NovaChar.of('A').toString();
            assertTrue(str.contains("A"));
        }

        @Test
        @DisplayName("increment/decrement")
        void testIncDec() {
            NovaChar a = NovaChar.of('A');
            assertEquals('B', a.increment().getValue());
            assertEquals('A', NovaChar.of('B').decrement().getValue());
        }

        @Test
        @DisplayName("isDigit/isLetter/isWhitespace")
        void testCharChecks() {
            assertTrue(NovaChar.of('5').isDigit());
            assertFalse(NovaChar.of('A').isDigit());
            assertTrue(NovaChar.of('A').isLetter());
            assertFalse(NovaChar.of('5').isLetter());
            assertTrue(NovaChar.of(' ').isWhitespace());
            assertFalse(NovaChar.of('A').isWhitespace());
        }

        @Test
        @DisplayName("toUpperCase/toLowerCase")
        void testCaseConversion() {
            assertEquals('A', NovaChar.of('a').toUpperCase().getValue());
            assertEquals('a', NovaChar.of('A').toLowerCase().getValue());
        }

        @Test
        @DisplayName("相等性")
        void testEquality() {
            assertEquals(NovaChar.of('A'), NovaChar.of('A'));
            assertNotEquals(NovaChar.of('A'), NovaChar.of('B'));
        }

        @Test
        @DisplayName("toJavaValue")
        void testToJavaValue() {
            Object val = NovaChar.of('X').toJavaValue();
            assertTrue(val instanceof Character);
            assertEquals('X', (Character) val);
        }
    }

    // ============ NovaList 高级操作 ============

    @Nested
    @DisplayName("NovaList 高级操作")
    class NovaListAdvancedTest {

        @Test
        @DisplayName("toJavaValue")
        void testToJavaValue() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(NovaString.of("hello"));
            Object javaVal = list.toJavaValue();
            assertTrue(javaVal instanceof java.util.List);
        }

        @Test
        @DisplayName("equals 深度比较")
        void testEquals() {
            NovaList a = new NovaList();
            a.add(new NovaInt(1));
            a.add(new NovaInt(2));
            NovaList b = new NovaList();
            b.add(new NovaInt(1));
            b.add(new NovaInt(2));
            assertEquals(a, b);
        }

        @Test
        @DisplayName("hashCode 一致")
        void testHashCode() {
            NovaList a = new NovaList();
            a.add(new NovaInt(1));
            NovaList b = new NovaList();
            b.add(new NovaInt(1));
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("isTruthy")
        void testIsTruthy() {
            NovaList empty = new NovaList();
            assertFalse(empty.isTruthy());
            NovaList nonEmpty = new NovaList();
            nonEmpty.add(new NovaInt(1));
            assertTrue(nonEmpty.isTruthy());
        }

        @Test
        @DisplayName("toString 输出")
        void testToString() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(NovaString.of("a"));
            String str = list.toString();
            assertTrue(str.contains("1") && str.contains("a"));
        }

        @Test
        @DisplayName("getTypeName")
        void testTypeName() {
            assertEquals("List", new NovaList().getTypeName());
        }

        @Test
        @DisplayName("reversed")
        void testReversed() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            list.add(new NovaInt(3));
            NovaList reversed = list.reversed();
            assertEquals(3, reversed.get(0).asInt());
            assertEquals(1, reversed.get(2).asInt());
        }

        @Test
        @DisplayName("sorted")
        void testSorted() {
            NovaList list = new NovaList();
            list.add(new NovaInt(3));
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            NovaList sorted = list.sorted();
            assertEquals(1, sorted.get(0).asInt());
            assertEquals(3, sorted.get(2).asInt());
        }

        @Test
        @DisplayName("distinct")
        void testDistinct() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            list.add(new NovaInt(2));
            list.add(new NovaInt(3));
            NovaList distinct = list.distinct();
            assertEquals(3, distinct.size());
        }

        @Test
        @DisplayName("joinToString")
        void testJoinToString() {
            NovaList list = new NovaList();
            list.add(new NovaInt(1));
            list.add(new NovaInt(2));
            list.add(new NovaInt(3));
            assertEquals("1-2-3", list.joinToString("-").asString());
        }
    }
}
