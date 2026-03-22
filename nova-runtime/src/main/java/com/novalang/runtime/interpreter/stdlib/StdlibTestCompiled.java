package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.NovaOps;
import com.novalang.runtime.NovaValue;
import com.novalang.runtime.Function0;
import com.novalang.runtime.interpreter.NovaRuntimeException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * nova.test 模块的编译模式运行时实现。
 *
 * <p>与解释器模式的 StdlibTest 功能对齐：
 * 支持 test/testGroup/runTests + 完整断言 + Nova 语义。</p>
 */
public final class StdlibTestCompiled {

    private StdlibTestCompiled() {}

    // ========== 测试用例管理（ThreadLocal 隔离） ==========

    private static final ThreadLocal<List<TestCase>> tests = ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<List<String>> groupStack = ThreadLocal.withInitial(ArrayList::new);

    private static class TestCase {
        final String name;
        final Object block;
        TestCase(String name, Object block) { this.name = name; this.block = block; }
    }

    public static Object test(Object name, Object block) {
        List<String> gs = groupStack.get();
        String fullName = gs.isEmpty()
                ? String.valueOf(name)
                : String.join(" > ", gs) + " > " + name;
        tests.get().add(new TestCase(fullName, block));
        return null;
    }

    public static Object testGroup(Object name, Object block) {
        List<String> gs = groupStack.get();
        gs.add(String.valueOf(name));
        try {
            invokeBlock(block);
        } finally {
            gs.remove(gs.size() - 1);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static Object runTests() {
        List<TestCase> testList = tests.get();
        int passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();
        for (TestCase tc : testList) {
            try {
                invokeBlock(tc.block);
                passed++;
                System.out.println("  PASS " + tc.name);
            } catch (Exception e) {
                failed++;
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                failures.add(tc.name + ": " + msg);
                System.out.println("  FAIL " + tc.name + " - " + msg);
            }
        }
        int total = passed + failed;
        System.out.println();
        System.out.println("Results: " + passed + " passed, " + failed + " failed, " + total + " total");
        if (!failures.isEmpty()) {
            System.out.println();
            System.out.println("Failures:");
            for (String f : failures) System.out.println("  - " + f);
        }
        // 彻底释放 ThreadLocal 桶位，避免长期线程池场景内存泄漏
        tests.remove();
        groupStack.remove();

        Map<Object, Object> result = new LinkedHashMap<>();
        result.put("passed", passed);
        result.put("failed", failed);
        result.put("total", total);
        return result;
    }

    // ========== 断言函数 ==========

    public static Object assertEqual(Object expected, Object actual) {
        if (!NovaOps.equals(expected, actual)) {
            throw new NovaRuntimeException("assertEqual failed: expected " + expected + " but got " + actual);
        }
        return null;
    }

    public static Object assertNotEqual(Object a, Object b) {
        if (NovaOps.equals(a, b)) {
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

    public static Object assertThrows(Object block) {
        try {
            invokeBlock(block);
        } catch (NovaRuntimeException e) {
            return e.getMessage();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new NovaRuntimeException("assertThrows failed: no exception was thrown");
    }

    public static Object assertContains(Object collection, Object element) {
        if (collection instanceof java.util.List) {
            Object unwrapped = unwrap(element);
            for (Object item : (java.util.List<?>) collection) {
                if (NovaOps.equals(item, unwrapped)) return null;
            }
            throw new NovaRuntimeException("assertContains failed: list does not contain " + element);
        } else if (collection instanceof String) {
            if (!((String) collection).contains(String.valueOf(element))) {
                throw new NovaRuntimeException("assertContains failed: string does not contain '" + element + "'");
            }
            return null;
        }
        throw new NovaRuntimeException("assertContains failed: unsupported collection type " + collection.getClass().getSimpleName());
    }

    public static Object assertFails(Object block) {
        try {
            invokeBlock(block);
        } catch (Exception e) {
            return null;
        }
        throw new NovaRuntimeException("assertFails failed: block did not throw");
    }

    // ========== 内部辅助 ==========

    private static Object unwrap(Object o) {
        if (o instanceof NovaValue) return ((NovaValue) o).toJavaValue();
        return o;
    }

    private static boolean isTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() != 0;
        if (value instanceof String) return !((String) value).isEmpty();
        if (value instanceof NovaValue) return ((NovaValue) value).isTruthy();
        if (value instanceof java.util.Collection) return !((java.util.Collection<?>) value).isEmpty();
        if (value instanceof java.util.Map) return !((java.util.Map<?, ?>) value).isEmpty();
        return true;
    }

    @SuppressWarnings("rawtypes")
    private static void invokeBlock(Object block) {
        if (block instanceof Function0) {
            ((Function0) block).invoke();
        } else if (block instanceof com.novalang.runtime.NovaCallable) {
            ((com.novalang.runtime.NovaCallable) block).call(null, java.util.Collections.emptyList());
        } else {
            throw new NovaRuntimeException("Expected a callable block, got: "
                    + (block == null ? "null" : block.getClass().getSimpleName()));
        }
    }
}
