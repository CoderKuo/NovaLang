package nova.runtime.interpreter.stdlib;
import nova.runtime.*;

import nova.runtime.interpreter.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * nova.test — 测试框架
 */
public final class StdlibTest {

    private StdlibTest() {}

    public static void register(Environment env, Interpreter interp) {
        List<TestCase> tests = new ArrayList<>();
        List<String> groupStack = new ArrayList<>();

        // test(name, block)
        env.defineVal("test", new NovaNativeFunction("test", 2, (interpreter, args) -> {
            String name = args.get(0).asString();
            String fullName = groupStack.isEmpty() ? name : String.join(" > ", groupStack) + " > " + name;
            tests.add(new TestCase(fullName, interpreter.asCallable(args.get(1), "test")));
            return NovaNull.UNIT;
        }));

        // testGroup(name, block)
        env.defineVal("testGroup", new NovaNativeFunction("testGroup", 2, (interpreter, args) -> {
            String name = args.get(0).asString();
            groupStack.add(name);
            interpreter.asCallable(args.get(1), "testGroup").call(interpreter, Collections.emptyList());
            groupStack.remove(groupStack.size() - 1);
            return NovaNull.UNIT;
        }));

        // runTests() → 执行并输出结果
        env.defineVal("runTests", new NovaNativeFunction("runTests", 0, (interpreter, args) -> {
            int passed = 0, failed = 0;
            List<String> failures = new ArrayList<>();

            for (TestCase tc : tests) {
                try {
                    tc.block.call(interpreter, Collections.emptyList());
                    passed++;
                    interpreter.getStdout().println("  PASS " + tc.name);
                } catch (Exception e) {
                    failed++;
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    failures.add(tc.name + ": " + msg);
                    interpreter.getStdout().println("  FAIL " + tc.name + " - " + msg);
                }
            }

            interpreter.getStdout().println();
            interpreter.getStdout().println("Results: " + passed + " passed, " + failed + " failed, " + tests.size() + " total");

            if (!failures.isEmpty()) {
                interpreter.getStdout().println();
                interpreter.getStdout().println("Failures:");
                for (String f : failures) {
                    interpreter.getStdout().println("  - " + f);
                }
            }

            tests.clear();

            NovaMap result = new NovaMap();
            result.put(NovaString.of("passed"), NovaInt.of(passed));
            result.put(NovaString.of("failed"), NovaInt.of(failed));
            result.put(NovaString.of("total"), NovaInt.of(passed + failed));
            return result;
        }));

        // 断言函数
        env.defineVal("assertEqual", NovaNativeFunction.create("assertEqual", (expected, actual) -> {
            if (!expected.equals(actual)) {
                throw new NovaRuntimeException("assertEqual failed: expected " + expected.asString() + " but got " + actual.asString());
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertNotEqual", NovaNativeFunction.create("assertNotEqual", (a, b) -> {
            if (a.equals(b)) {
                throw new NovaRuntimeException("assertNotEqual failed: both values are " + a.asString());
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertTrue", NovaNativeFunction.create("assertTrue", (value) -> {
            if (!value.isTruthy()) {
                throw new NovaRuntimeException("assertTrue failed: value is " + value.asString());
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertFalse", NovaNativeFunction.create("assertFalse", (value) -> {
            if (value.isTruthy()) {
                throw new NovaRuntimeException("assertFalse failed: value is " + value.asString());
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertNull", NovaNativeFunction.create("assertNull", (value) -> {
            if (!value.isNull()) {
                throw new NovaRuntimeException("assertNull failed: value is " + value.asString());
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertNotNull", NovaNativeFunction.create("assertNotNull", (value) -> {
            if (value.isNull()) {
                throw new NovaRuntimeException("assertNotNull failed: value is null");
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertThrows", new NovaNativeFunction("assertThrows", 1, (interpreter, args) -> {
            NovaCallable block = interpreter.asCallable(args.get(0), "Test method");
            try {
                block.call(interpreter, Collections.emptyList());
                throw new NovaRuntimeException("assertThrows failed: no exception was thrown");
            } catch (NovaRuntimeException e) {
                // 检查不是我们自己抛的 assertThrows 错误
                if (e.getMessage().startsWith("assertThrows failed:")) throw e;
                return NovaString.of(e.getMessage());
            }
        }));

        env.defineVal("assertContains", NovaNativeFunction.create("assertContains", (collection, element) -> {
            if (collection instanceof NovaList) {
                if (!((NovaList) collection).contains(element)) {
                    throw new NovaRuntimeException("assertContains failed: list does not contain " + element.asString());
                }
            } else if (collection instanceof NovaString) {
                if (!collection.asString().contains(element.asString())) {
                    throw new NovaRuntimeException("assertContains failed: string does not contain '" + element.asString() + "'");
                }
            } else {
                throw new NovaRuntimeException("assertContains: unsupported type " + collection.getTypeName());
            }
            return NovaNull.UNIT;
        }));

        env.defineVal("assertFails", new NovaNativeFunction("assertFails", 1, (interpreter, args) -> {
            NovaCallable block = interpreter.asCallable(args.get(0), "Test method");
            try {
                block.call(interpreter, Collections.emptyList());
                throw new NovaRuntimeException("assertFails failed: block did not throw");
            } catch (NovaRuntimeException e) {
                if (e.getMessage().startsWith("assertFails failed:")) throw e;
                return NovaNull.UNIT;
            }
        }));
    }

    private static class TestCase {
        final String name;
        final NovaCallable block;
        TestCase(String name, NovaCallable block) {
            this.name = name;
            this.block = block;
        }
    }
}
