package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.interpreter.NovaRuntimeException;

import java.util.Objects;

/**
 * nova.test 模块的编译模式运行时实现。
 *
 * <p>仅包含断言函数。test/testGroup/runTests 等有状态函数
 * 依赖 Interpreter，编译模式下不支持。</p>
 */
public final class StdlibTestCompiled {

    private StdlibTestCompiled() {}

    public static Object assertEqual(Object expected, Object actual) {
        if (!Objects.equals(unwrap(expected), unwrap(actual))) {
            throw new NovaRuntimeException("assertEqual failed: expected " + expected + " but got " + actual);
        }
        return null;
    }

    public static Object assertNotEqual(Object a, Object b) {
        if (Objects.equals(unwrap(a), unwrap(b))) {
            throw new NovaRuntimeException("assertNotEqual failed: both values are " + a);
        }
        return null;
    }

    public static Object assertTrue(Object value) {
        if (!isTruthy(value)) {
            throw new NovaRuntimeException("assertTrue failed: value is " + value);
        }
        return null;
    }

    public static Object assertFalse(Object value) {
        if (isTruthy(value)) {
            throw new NovaRuntimeException("assertFalse failed: value is " + value);
        }
        return null;
    }

    public static Object assertNull(Object value) {
        if (value != null) {
            throw new NovaRuntimeException("assertNull failed: value is " + value);
        }
        return null;
    }

    public static Object assertNotNull(Object value) {
        if (value == null) {
            throw new NovaRuntimeException("assertNotNull failed: value is null");
        }
        return null;
    }

    private static Object unwrap(Object o) {
        if (o instanceof com.novalang.runtime.NovaValue) return ((com.novalang.runtime.NovaValue) o).toJavaValue();
        return o;
    }

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof com.novalang.runtime.NovaValue) return ((com.novalang.runtime.NovaValue) value).isTruthy();
        return true;
    }
}
