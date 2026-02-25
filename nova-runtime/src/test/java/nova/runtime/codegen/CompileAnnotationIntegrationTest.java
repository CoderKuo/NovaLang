package nova.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import nova.runtime.NovaAnnotations;
import nova.runtime.NovaProcessorHandle;
import nova.runtime.*;
import nova.runtime.interpreter.reflect.NovaClassInfo;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 编译模式注解 + 句柄集成测试
 *
 * <p>端到端：Nova 源码 → 编译 → 加载 → NovaAnnotations 注册/触发 → 断言</p>
 *
 * <p>放在 nova-runtime 模块（可访问 nova-compiler 和 nova-runtime 两个模块的类）</p>
 */
@DisplayName("编译模式: 运行时注解处理器 + 句柄")
class CompileAnnotationIntegrationTest {

    private static final NovaIrCompiler compiler = new NovaIrCompiler();

    private Map<String, Class<?>> compileAndLoad(String source) {
        return compiler.compileAndLoad(source, "test.nova");
    }

    // ============ 运行时注解处理器 ============

    @Nested
    @DisplayName("运行时注解处理器端到端")
    class RuntimeAnnotationProcessorE2E {

        @BeforeEach
        void setUp() {
            NovaAnnotations.clear();
        }

        @Test
        @DisplayName("运行时注解处理器在类初始化时被触发")
        void testRuntimeAnnotationProcessorTrigger() throws Exception {
            boolean[] triggered = {false};
            NovaAnnotations.register("track",
                    (BiConsumer<Object, Object>) (cls, args) -> triggered[0] = true);

            String code = "@track class Foo\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Foo", true, loaded.get("Foo").getClassLoader());

            assertTrue(triggered[0], "运行时注解处理器应被触发");
        }

        @Test
        @DisplayName("运行时注解处理器接收 NovaClassInfo")
        void testRuntimeAnnotationProcessorReceivesClassInfo() throws Exception {
            AtomicReference<Object> captured = new AtomicReference<>();
            NovaAnnotations.register("inspect",
                    (BiConsumer<Object, Object>) (cls, args) -> captured.set(cls));

            String code = "@inspect class Bar\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Bar", true, loaded.get("Bar").getClassLoader());

            assertNotNull(captured.get());
            assertTrue(captured.get() instanceof NovaClassInfo);
            assertEquals("Bar", ((NovaClassInfo) captured.get()).name);
        }

        @Test
        @DisplayName("多个处理器均被调用")
        void testMultipleProcessorsTriggered() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            NovaAnnotations.register("multi",
                    (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());
            NovaAnnotations.register("multi",
                    (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());

            String code = "@multi class Baz\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Baz", true, loaded.get("Baz").getClassLoader());

            assertEquals(2, count.get());
        }

        @Test
        @DisplayName("未注册处理器时类初始化不报错")
        void testNoProcessorNoError() throws Exception {
            String code = "@unknown class Safe\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            assertDoesNotThrow(() ->
                    Class.forName("Safe", true, loaded.get("Safe").getClassLoader()));
        }
    }

    // ============ 处理器句柄 ============

    @Nested
    @DisplayName("处理器句柄端到端")
    class ProcessorHandleE2E {

        @BeforeEach
        void setUp() {
            NovaAnnotations.clear();
        }

        @Test
        @DisplayName("register 返回 NovaProcessorHandle 实例")
        void testRegisterReturnsHandle() {
            NovaProcessorHandle handle = NovaAnnotations.register("h1",
                    (BiConsumer<Object, Object>) (cls, args) -> {});
            assertNotNull(handle);
            assertEquals("h1", handle.getName());
        }

        @Test
        @DisplayName("handle.unregister() 后处理器不再触发")
        void testHandleUnregister() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            NovaProcessorHandle handle = NovaAnnotations.register("hunreg",
                    (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());

            handle.unregister();

            String code = "@hunreg class Foo\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Foo", true, loaded.get("Foo").getClassLoader());

            assertEquals(0, count.get());
        }

        @Test
        @DisplayName("handle.register() 重新注册后处理器可触发")
        void testHandleReRegister() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            NovaProcessorHandle handle = NovaAnnotations.register("hrereg",
                    (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());

            handle.unregister();
            handle.register();

            String code = "@hrereg class Bar\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Bar", true, loaded.get("Bar").getClassLoader());

            assertEquals(1, count.get());
        }

        @Test
        @DisplayName("handle.replace() 替换 handler 后使用新 handler")
        void testHandleReplace() throws Exception {
            AtomicReference<String> captured = new AtomicReference<>("init");
            NovaProcessorHandle handle = NovaAnnotations.register("hrepl",
                    (BiConsumer<Object, Object>) (cls, args) -> captured.set("old"));

            handle.replace((BiConsumer<Object, Object>) (cls, args) -> captured.set("new"));

            String code = "@hrepl class Baz\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Baz", true, loaded.get("Baz").getClassLoader());

            assertEquals("new", captured.get());
        }

        @Test
        @DisplayName("unregister 只移除当前句柄，不影响同名其他处理器")
        void testUnregisterOnlyTargetHandle() throws Exception {
            AtomicInteger count = new AtomicInteger(0);
            NovaProcessorHandle h1 = NovaAnnotations.register("hshared",
                    (BiConsumer<Object, Object>) (cls, args) -> count.incrementAndGet());
            NovaProcessorHandle h2 = NovaAnnotations.register("hshared",
                    (BiConsumer<Object, Object>) (cls, args) -> count.addAndGet(10));

            h1.unregister();

            String code = "@hshared class Qux\n";
            Map<String, Class<?>> loaded = compileAndLoad(code);
            Class.forName("Qux", true, loaded.get("Qux").getClassLoader());

            assertEquals(10, count.get());
        }
    }
}
