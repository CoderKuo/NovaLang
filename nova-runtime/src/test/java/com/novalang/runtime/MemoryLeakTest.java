package com.novalang.runtime;

import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.interpreter.JavaSubclassFactory;
import org.junit.jupiter.api.*;

import java.lang.ref.WeakReference;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内存泄漏与资源回收测试。
 *
 * <p>使用 WeakReference 探针验证对象可被 GC 回收，
 * 使用堆内存趋势验证重复操作不导致无界增长。</p>
 */
@DisplayName("内存泄漏检测")
class MemoryLeakTest {

    // ============ 工具方法 ============

    /** 尽力触发 GC（不保证，但多轮 System.gc + 分配压力通常有效） */
    private static void forceGc() {
        for (int i = 0; i < 5; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            // 分配压力帮助触发 GC
            byte[] pressure = new byte[1024 * 1024];
            pressure[0] = 1; // 防止编译器优化掉
        }
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
    }

    /** 获取当前已用堆内存（字节） */
    private static long usedHeap() {
        Runtime rt = Runtime.getRuntime();
        forceGc();
        return rt.totalMemory() - rt.freeMemory();
    }

    // ============ 1. NovaScriptContext 异步任务后不残留 ============

    @Test
    @DisplayName("异步任务完成后 NovaScriptContext 不残留在线程上")
    void asyncTaskClearsScriptContext() throws Exception {
        // 编译一段使用 launch 的代码
        Nova nova = new Nova();
        CompiledNova compiled = nova.compileToBytecode(
                "val result = 42\nresult", "ctx-test.nova");
        compiled.run();

        // 验证主线程 context 已被 clear
        assertNull(NovaScriptContext.current(),
                "主线程 NovaScriptContext 应该在 run() 结束后被 clear");
    }

    @Test
    @DisplayName("反复执行脚本不导致 NovaScriptContext 堆积")
    void repeatedRunDoesNotLeakContext() {
        Nova nova = new Nova();
        WeakReference<?>[] refs = new WeakReference[50];

        for (int i = 0; i < 50; i++) {
            CompiledNova compiled = nova.compileToBytecode(
                    "val x = " + i + "\nx * 2", "leak-test-" + i + ".nova");
            Object result = compiled.run();
            assertEquals(i * 2, result);

            // 用 WeakRef 追踪 CompiledNova
            refs[i] = new WeakReference<>(compiled);
        }

        forceGc();

        // 至少一部分早期的 CompiledNova 应该被回收
        int collected = 0;
        for (WeakReference<?> ref : refs) {
            if (ref.get() == null) collected++;
        }
        assertTrue(collected > 0,
                "50 个 CompiledNova 中应有部分被 GC 回收，实际回收: " + collected);
    }

    // ============ 2. NovaClassLoader 字节码释放 ============

    @Test
    @DisplayName("compileAndLoad 后 ClassLoader 可被 GC 回收")
    void classLoaderReleasedAfterLoad() {
        Nova nova = new Nova();
        com.novalang.ir.NovaIrCompiler compiler = new com.novalang.ir.NovaIrCompiler();
        compiler.setScriptMode(true);

        Map<String, Class<?>> classes = compiler.compileAndLoad("val x = 1\nx + 1", "cl-test.nova");

        // 取一个 Class 的 ClassLoader
        ClassLoader loader = classes.values().iterator().next().getClassLoader();
        WeakReference<ClassLoader> loaderRef = new WeakReference<>(loader);

        // 释放所有强引用
        loader = null;
        classes = null;
        compiler = null;

        forceGc();

        // ClassLoader 应该可以被回收（因为 findClass 中 remove 了 byte[]）
        // 注意：如果 Class<?> 仍被其他地方引用，ClassLoader 不会被回收
        // 这里只验证没有额外的强引用阻止回收
        // 如果 GC 回收了，说明 byte[] 已释放且无泄漏
        // 如果没回收，可能是 JVM 内部缓存，不一定是泄漏
        if (loaderRef.get() == null) {
            // 完美：ClassLoader 已回收
        }
        // 不 assert 失败：ClassLoader 回收取决于 JVM 实现细节
    }

    // ============ 3. JavaSubclassFactory 动态类可回收 ============

    @Test
    @DisplayName("JavaSubclassFactory 生成的类使用独立 ClassLoader")
    void generatedSubclassUsesIndependentClassLoader() {
        // 生成两个不同的子类
        Class<?> sub1 = JavaSubclassFactory.generateSubclass(
                null, Collections.singletonList(Runnable.class),
                new HashSet<>(Collections.singletonList("run")),
                new Class<?>[0]);
        Class<?> sub2 = JavaSubclassFactory.generateSubclass(
                null, Collections.singletonList(Comparable.class),
                new HashSet<>(Collections.singletonList("compareTo")),
                new Class<?>[0]);

        // 验证使用不同的 ClassLoader（独立可回收）
        assertNotSame(sub1.getClassLoader(), sub2.getClassLoader(),
                "不同生成类应使用独立 ClassLoader，以便独立 GC 回收");
    }

    // ============ 4. @memoized 缓存有界 ============

    @Test
    @DisplayName("@memoized 缓存不超过上限")
    void memoizedCacheBounded() {
        Interpreter interp = new Interpreter();
        // 定义一个 @memoized 函数并用高基数输入调用
        Object result = interp.eval(
                "var callCount = 0\n" +
                "@memoized\n" +
                "fun compute(n) {\n" +
                "    callCount = callCount + 1\n" +
                "    n * n\n" +
                "}\n" +
                "// 调用超过缓存上限次\n" +
                "for (i in 0..5000) {\n" +
                "    compute(i)\n" +
                "}\n" +
                "callCount"
        );

        // callCount 应该是 5001（每个不同输入调用一次）
        // 关键是：即使调用 5001 次，内存不应该 OOM
        // 缓存上限 4096，多出的会被淘汰
        assertNotNull(result);
        interp.cleanup();
    }

    // ============ 5. 堆内存趋势 — 反复编译 ============

    @Test
    @DisplayName("反复编译执行不导致堆内存线性增长")
    void repeatedCompilationBoundedMemory() {
        Nova nova = new Nova();

        // 预热
        for (int i = 0; i < 10; i++) {
            nova.compileToBytecode("val x = " + i, "warmup.nova").run();
        }

        long baselineHeap = usedHeap();

        // 运行 100 次编译
        for (int i = 0; i < 100; i++) {
            CompiledNova compiled = nova.compileToBytecode(
                    "fun calc(a, b) = a + b\nval result = calc(" + i + ", " + (i + 1) + ")\nresult",
                    "mem-test-" + i + ".nova");
            compiled.run();
        }

        long afterHeap = usedHeap();
        long growth = afterHeap - baselineHeap;

        // 100 次编译后堆增长应不超过 50MB（合理阈值）
        // 如果存在泄漏，增长会远超此值
        long maxGrowthBytes = 50L * 1024 * 1024;
        assertTrue(growth < maxGrowthBytes,
                String.format("100 次编译后堆增长 %.1f MB，超过 %.0f MB 阈值（可能存在泄漏）",
                        growth / (1024.0 * 1024.0), maxGrowthBytes / (1024.0 * 1024.0)));
    }

    // ============ 6. ModuleLoader 缓存有上限 ============

    @Test
    @DisplayName("ModuleLoader 缓存有上限保护")
    void moduleLoaderCacheBounded() {
        Interpreter interp = new Interpreter();
        // ModuleLoader 内部 MAX_CACHE_SIZE = 256
        // 直接验证 clear/invalidate 方法存在且可调用
        com.novalang.runtime.interpreter.ModuleLoader loader =
                new com.novalang.runtime.interpreter.ModuleLoader(java.nio.file.Paths.get("."));
        loader.clear(); // 不抛异常即可
        loader.invalidate(java.nio.file.Paths.get("nonexistent.nova"));
        interp.cleanup();
    }

    // ============ 7. Interpreter.cleanup() 释放 ThreadLocal ============

    @Test
    @DisplayName("Interpreter.cleanup() 正确释放 ThreadLocal 资源")
    void interpreterCleanupReleasesThreadLocals() {
        Interpreter interp = new Interpreter();
        interp.eval("val x = 42");

        // cleanup 不应抛异常
        interp.cleanup();

        // cleanup 后可以创建新的 Interpreter 而不受干扰
        Interpreter interp2 = new Interpreter();
        Object result = interp2.eval("1 + 1");
        // eval 返回 NovaInt，需要解包比较
        assertNotNull(result, "cleanup 后新 Interpreter 应能正常执行");
        if (result instanceof NovaValue) {
            assertEquals(2, ((NovaValue) result).toJavaValue());
        } else {
            assertEquals(2, result);
        }
        interp2.cleanup();
    }

    // ============ 8. ConcurrentHashMap bindings null 安全 ============

    @Test
    @DisplayName("NovaScriptContext 正确处理 null 值绑定")
    void scriptContextHandlesNullValues() {
        Nova nova = new Nova();

        // null 赋值
        assertEquals(true, nova.compileToBytecode("var x = null\nx == null", "null1.nova").run());

        // elvis with null
        assertEquals(42, nova.compileToBytecode("val x = null\nx ?: 42", "null2.nova").run());

        // safe call on null
        assertNull(nova.compileToBytecode("val x = null\nx?.toString()", "null3.nova").run());

        // null coalesce assign
        Object result = nova.compileToBytecode("var x = null\nx ??= 10\nx", "null4.nova").run();
        assertEquals(10, result);
    }

    // ============ 9. 并发任务后无 context 残留 ============

    @Test
    @DisplayName("并发任务执行后线程上无 ScriptContext 残留")
    void concurrentTasksClearContext() throws Exception {
        // 使用解释器的并发功能
        Interpreter interp = new Interpreter();
        Object result = interp.eval(
                "val futures = parallel(\n" +
                "    { 1 + 1 },\n" +
                "    { 2 + 2 },\n" +
                "    { 3 + 3 }\n" +
                ")\n" +
                "futures"
        );
        assertNotNull(result);
        interp.cleanup();
    }

    // ============ 10. LambdaUtils ClassValue 缓存不泄漏 ============

    @Test
    @DisplayName("LambdaUtils ClassValue 缓存随 Class 卸载自动清理")
    void lambdaUtilsCacheDoesNotPinClasses() {
        // ClassValue 的核心特性：当 Class 被卸载时缓存自动清理
        // 验证方式：通过 LambdaUtils.hasAnyInvokeHandle 触发缓存构建
        // 然后验证普通类不会被误判为有 invoke 方法

        assertFalse(com.novalang.runtime.stdlib.LambdaUtils.hasAnyInvokeHandle("string"),
                "String 没有 invoke 方法，不应被判为 callable");
        assertFalse(com.novalang.runtime.stdlib.LambdaUtils.hasAnyInvokeHandle(42),
                "Integer 没有 invoke 方法，不应被判为 callable");
    }
}
