package nova.runtime;

import nova.runtime.*;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import org.junit.jupiter.api.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NovaAnnotations 运行时注解注册表")
class NovaAnnotationsTest {

    @BeforeEach
    void setUp() {
        NovaAnnotations.clear();
    }

    @Test
    @DisplayName("register + trigger 基本流程")
    void testBasicRegisterAndTrigger() {
        boolean[] triggered = {false};
        NovaAnnotations.register("track",
                (BiConsumer<Object, Object>) (cls, args) -> triggered[0] = true);

        NovaAnnotations.trigger("track", String.class, Collections.emptyMap());

        assertTrue(triggered[0]);
    }

    @Test
    @DisplayName("注解参数正确传递")
    void testAnnotationArgsPassThrough() {
        AtomicReference<Object> captured = new AtomicReference<>();
        NovaAnnotations.register("config",
                (BiConsumer<Object, Object>) (cls, args) -> captured.set(args));

        Map<String, Object> args = new HashMap<>();
        args.put("name", "myService");
        args.put("version", 2);
        NovaAnnotations.trigger("config", Object.class, args);

        assertNotNull(captured.get());
        @SuppressWarnings("unchecked")
        Map<String, Object> capturedArgs = (Map<String, Object>) captured.get();
        assertEquals("myService", capturedArgs.get("name"));
        assertEquals(2, capturedArgs.get("version"));
    }

    @Test
    @DisplayName("同一注解多个处理器均被调用")
    void testMultipleProcessorsForSameAnnotation() {
        AtomicInteger count = new AtomicInteger(0);
        BiConsumer<Object, Object> handler = (cls, args) -> count.incrementAndGet();
        NovaAnnotations.register("log", handler);
        NovaAnnotations.register("log", handler);
        NovaAnnotations.register("log", handler);

        NovaAnnotations.trigger("log", Object.class, Collections.emptyMap());

        assertEquals(3, count.get());
    }

    @Test
    @DisplayName("无处理器时 trigger 不报错")
    void testTriggerWithNoProcessors() {
        assertDoesNotThrow(() ->
                NovaAnnotations.trigger("unknown", Object.class, Collections.emptyMap()));
    }

    @Test
    @DisplayName("clear 清空所有处理器")
    void testClearRemovesAllProcessors() {
        boolean[] triggered = {false};
        NovaAnnotations.register("test",
                (BiConsumer<Object, Object>) (cls, args) -> triggered[0] = true);

        NovaAnnotations.clear();
        NovaAnnotations.trigger("test", Object.class, Collections.emptyMap());

        assertFalse(triggered[0]);
    }

    @Test
    @DisplayName("处理器接收 NovaClassInfo 而非 Class 对象")
    void testProcessorReceivesClassInfo() {
        AtomicReference<Object> captured = new AtomicReference<>();
        NovaAnnotations.register("inspect",
                (BiConsumer<Object, Object>) (cls, args) -> captured.set(cls));

        NovaAnnotations.trigger("inspect", Integer.class, Collections.emptyMap());

        assertTrue(captured.get() instanceof NovaClassInfo);
        NovaClassInfo info = (NovaClassInfo) captured.get();
        assertEquals("Integer", info.name);
    }

    // ============ 句柄 (Handle) 测试 ============

    @Test
    @DisplayName("register 返回 NovaProcessorHandle")
    void testRegisterReturnsHandle() {
        NovaProcessorHandle handle = NovaAnnotations.register("test",
                (BiConsumer<Object, Object>) (cls, args) -> {});
        assertNotNull(handle);
        assertEquals("test", handle.getName());
    }

    @Test
    @DisplayName("handle.unregister() 移除处理器")
    void testHandleUnregister() {
        boolean[] triggered = {false};
        NovaProcessorHandle handle = NovaAnnotations.register("rem",
                (BiConsumer<Object, Object>) (cls, args) -> triggered[0] = true);

        handle.unregister();
        NovaAnnotations.trigger("rem", Object.class, Collections.emptyMap());

        assertFalse(triggered[0]);
    }

    @Test
    @DisplayName("handle.register() 重新注册处理器")
    void testHandleReRegister() {
        AtomicInteger count = new AtomicInteger(0);
        NovaProcessorHandle handle = NovaAnnotations.register("rereg",
                (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());

        handle.unregister();
        NovaAnnotations.trigger("rereg", Object.class, Collections.emptyMap());
        assertEquals(0, count.get());

        handle.register();
        NovaAnnotations.trigger("rereg", Object.class, Collections.emptyMap());
        assertEquals(1, count.get());
    }

    @Test
    @DisplayName("handle.replace() 替换处理器 handler")
    void testHandleReplace() {
        AtomicReference<String> captured = new AtomicReference<>("old");
        NovaProcessorHandle handle = NovaAnnotations.register("repl",
                (BiConsumer<Object, Object>) (cls, args) -> captured.set("first"));

        handle.replace((BiConsumer<Object, Object>) (cls, args) -> captured.set("second"));
        NovaAnnotations.trigger("repl", Object.class, Collections.emptyMap());

        assertEquals("second", captured.get());
    }

    @Test
    @DisplayName("replace 后不影响注册状态")
    void testReplaceKeepsRegistration() {
        AtomicInteger count = new AtomicInteger(0);
        NovaProcessorHandle handle = NovaAnnotations.register("rpk",
                (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());

        // replace 不应导致注销
        handle.replace((BiConsumer<Object, Object>) (cls, args) -> count.addAndGet(10));
        NovaAnnotations.trigger("rpk", Object.class, Collections.emptyMap());

        assertEquals(10, count.get());
    }

    @Test
    @DisplayName("多次 unregister 不报错")
    void testDoubleUnregisterSafe() {
        NovaProcessorHandle handle = NovaAnnotations.register("dup",
                (BiConsumer<Object, Object>) (cls, args) -> {});
        handle.unregister();
        assertDoesNotThrow(() -> handle.unregister());
    }

    @Test
    @DisplayName("unregister 只移除对应 handle，不影响同名其他处理器")
    void testUnregisterOnlyTargetHandle() {
        AtomicInteger count = new AtomicInteger(0);
        NovaProcessorHandle h1 = NovaAnnotations.register("shared",
                (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());
        NovaProcessorHandle h2 = NovaAnnotations.register("shared",
                (BiConsumer<Object, Object>) (cls, args) -> count.addAndGet(10));

        h1.unregister();
        NovaAnnotations.trigger("shared", Object.class, Collections.emptyMap());

        assertEquals(10, count.get());
    }
}
