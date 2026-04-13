package com.novalang.runtime.codegen;

import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.NovaNativeFunction;
import org.junit.jupiter.api.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试 CompiledNova 缓存复用场景：编译一次，runDirect 多次。
 *
 * <p>模拟 DragonNova 的使用模式：
 * <ul>
 *   <li>Nova 表达式编译后缓存 CompiledNova 实例</li>
 *   <li>每次 GUI 渲染帧通过 runDirect(liveMap) 执行，liveMap 包含最新的命名空间代理</li>
 *   <li>GUI 关闭再打开时复用同一个 CompiledNova</li>
 * </ul>
 *
 * <p>关键问题：$Module 的 public static 字段在多次 runDirect 间是否导致状态污染？</p>
 */
@DisplayName("CompiledNova 缓存复用: runDirect 多次调用")
class RunDirectCacheTest {

    // ========== 场景1: 纯表达式（无 val/var），runDirect 多次 ==========

    @Nested
    @DisplayName("纯表达式 — 无顶层变量声明")
    class PureExpressionTests {

        @Test
        @DisplayName("简单算术表达式，不同 liveMap 值")
        void testSimpleArithmetic() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("x + y * 2", "<test>");

            // 第一次执行
            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("x", 10);
            map1.put("y", 5);
            Object result1 = compiled.runDirect(map1);
            assertEquals(20, ((Number) result1).intValue(), "第一次: 10 + 5*2 = 20");

            // 第二次执行 — 不同值
            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("x", 100);
            map2.put("y", 50);
            Object result2 = compiled.runDirect(map2);
            assertEquals(200, ((Number) result2).intValue(), "第二次: 100 + 50*2 = 200");

            // 第三次执行 — 再次不同值，确认无残留
            Map<String, Object> map3 = new ConcurrentHashMap<>();
            map3.put("x", 1);
            map3.put("y", 1);
            Object result3 = compiled.runDirect(map3);
            assertEquals(3, ((Number) result3).intValue(), "第三次: 1 + 1*2 = 3");
        }

        @Test
        @DisplayName("字符串拼接表达式")
        void testStringConcat() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("prefix + \"_\" + suffix", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("prefix", "hello");
            map1.put("suffix", "world");
            assertEquals("hello_world", compiled.runDirect(map1));

            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("prefix", "foo");
            map2.put("suffix", "bar");
            assertEquals("foo_bar", compiled.runDirect(map2));
        }

        @Test
        @DisplayName("条件表达式 — if/else 无副作用")
        void testConditionalExpression() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "if (flag > 0) \"active\" else \"inactive\"", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("flag", 1);
            assertEquals("active", compiled.runDirect(map1));

            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("flag", 0);
            assertEquals("inactive", compiled.runDirect(map2));

            // 再次检查第一种情况
            Map<String, Object> map3 = new ConcurrentHashMap<>();
            map3.put("flag", 1);
            assertEquals("active", compiled.runDirect(map3));
        }
    }

    // ========== 场景2: 带 val/var 声明（会生成 static 字段） ==========

    @Nested
    @DisplayName("带顶层 val/var 声明 — 生成 $Module static 字段")
    class TopLevelVarTests {

        @Test
        @DisplayName("val 声明 — 每次 runDirect 应该重新初始化")
        void testValDeclaration() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val result = x * 2 + 1\nresult", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("x", 10);
            Object result1 = compiled.runDirect(map1);
            assertEquals(21, ((Number) result1).intValue(), "第一次: 10*2+1 = 21");

            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("x", 50);
            Object result2 = compiled.runDirect(map2);
            assertEquals(101, ((Number) result2).intValue(), "第二次: 50*2+1 = 101 (不应残留21)");
        }

        @Test
        @DisplayName("var 声明 + 修改 — 下次调用不应残留")
        void testVarDeclarationAndModification() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "var counter = start\ncounter = counter + 10\ncounter", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("start", 0);
            Object result1 = compiled.runDirect(map1);
            assertEquals(10, ((Number) result1).intValue(), "第一次: 0 + 10 = 10");

            // 第二次应从新的 start 开始，不是从上次的 counter 残留值
            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("start", 100);
            Object result2 = compiled.runDirect(map2);
            assertEquals(110, ((Number) result2).intValue(), "第二次: 100 + 10 = 110 (不应是 20)");
        }

        @Test
        @DisplayName("多个 var 交互 — 确保所有 static 字段都被重置")
        void testMultipleVars() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "var a = x\nvar b = y\nvar c = a + b\nc", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("x", 3);
            map1.put("y", 4);
            assertEquals(7, ((Number) compiled.runDirect(map1)).intValue());

            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("x", 30);
            map2.put("y", 40);
            assertEquals(70, ((Number) compiled.runDirect(map2)).intValue(), "不应残留上次的值");
        }

        @Test
        @DisplayName("循环中的 var — 循环计数器跨调用不残留")
        void testVarInLoop() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "var sum = 0\nfor (i in 1..n) { sum = sum + i }\nsum", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("n", 5);
            assertEquals(15, ((Number) compiled.runDirect(map1)).intValue(), "1+2+3+4+5 = 15");

            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("n", 3);
            assertEquals(6, ((Number) compiled.runDirect(map2)).intValue(), "1+2+3 = 6 (不应是 15+6=21)");
        }
    }

    // ========== 场景3: NovaDynamicObject 代理（模拟 DC 命名空间） ==========

    /** 模拟 DragonNova 的 ScopePrefixProxy */
    static class MockScopeProxy implements NovaDynamicObject {
        private final Map<String, Object> data;

        MockScopeProxy(Map<String, Object> data) {
            this.data = data;
        }

        @Override
        public Object getMember(String name) {
            return data.get(name);
        }

        @Override
        public void setMember(String name, Object value) {
            data.put(name, value);
        }
    }

    /** 模拟 DragonNova 的 ComponentPropertyProxy */
    static class MockComponentProxy implements NovaDynamicObject {
        private double x, y, width, height;

        MockComponentProxy(double x, double y, double w, double h) {
            this.x = x; this.y = y; this.width = w; this.height = h;
        }

        @Override
        public Object getMember(String name) {
            switch (name) {
                case "x": return x;
                case "y": return y;
                case "width": case "w": return width;
                case "height": case "h": return height;
                default: return null;
            }
        }

        @Override
        public void setMember(String name, Object value) {}
    }

    @Nested
    @DisplayName("NovaDynamicObject 代理 — 模拟 DragonCore 命名空间")
    class DynamicObjectProxyTests {

        @Test
        @DisplayName("动态代理属性读取 — 不同代理实例返回不同值")
        void testDynamicProxyMemberAccess() {
            Nova nova = new Nova();
            // 模拟: 局部变量.当前标签
            CompiledNova compiled = nova.compileToBytecode("scope.value", "<test>");

            // 第一次 — scope.value = 42
            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("scope", new MockScopeProxy(new HashMap<String, Object>() {{
                put("value", 42);
            }}));
            assertEquals(42, compiled.runDirect(map1));

            // 第二次 — scope.value = 99
            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("scope", new MockScopeProxy(new HashMap<String, Object>() {{
                put("value", 99);
            }}));
            assertEquals(99, compiled.runDirect(map2), "应该读取新代理的值，不是残留 42");
        }

        @Test
        @DisplayName("多个动态代理组合 — 模拟 DC 多命名空间")
        void testMultipleProxies() {
            Nova nova = new Nova();
            // 模拟: 局部变量.当前标签 + 背景.x
            CompiledNova compiled = nova.compileToBytecode(
                    "comp.x + offset", "<test>");

            // 第一次 — comp.x=10, offset=5
            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("comp", new MockComponentProxy(10, 20, 100, 50));
            map1.put("offset", 5);
            assertEquals(15.0, ((Number) compiled.runDirect(map1)).doubleValue(), 0.001);

            // 第二次 — comp.x=200, offset=50
            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("comp", new MockComponentProxy(200, 300, 400, 500));
            map2.put("offset", 50);
            assertEquals(250.0, ((Number) compiled.runDirect(map2)).doubleValue(), 0.001,
                    "应使用新的 ComponentProxy，不残留旧值");
        }

        @Test
        @DisplayName("val 引用动态代理 — 静态字段缓存代理引用")
        void testValWithDynamicProxy() {
            Nova nova = new Nova();
            // 这个 val 会变成 $Module 的 static 字段
            CompiledNova compiled = nova.compileToBytecode(
                    "val v = scope.value\nv + 1", "<test>");

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("scope", new MockScopeProxy(new HashMap<String, Object>() {{
                put("value", 10);
            }}));
            assertEquals(11, ((Number) compiled.runDirect(map1)).intValue());

            Map<String, Object> map2 = new ConcurrentHashMap<>();
            map2.put("scope", new MockScopeProxy(new HashMap<String, Object>() {{
                put("value", 99);
            }}));
            assertEquals(100, ((Number) compiled.runDirect(map2)).intValue(),
                    "val v 应重新从新 proxy 读取，不缓存旧值 10");
        }
    }

    // ========== 场景4: 带注入函数的缓存复用 ==========

    @Nested
    @DisplayName("注入函数 — 编译时注册函数，运行时复用")
    class InjectedFunctionTests {

        @Test
        @DisplayName("函数通过 bindings 注入 — runDirect 传入不同函数实现")
        void testFunctionFromBindings() {
            Nova nova = new Nova();
            nova.defineFunctionVararg("compute", args -> {
                int a = ((Number) args[0]).intValue();
                int b = ((Number) args[1]).intValue();
                return a + b;
            });
            CompiledNova compiled = nova.compileToBytecode("compute(x, y)", "<test>");

            // 从 compiled 获取 bindings（包含 compute 函数）
            Map<String, Object> bindings = compiled.getBindings();

            // 第一次
            Map<String, Object> map1 = new ConcurrentHashMap<>(bindings);
            map1.put("x", 3);
            map1.put("y", 4);
            assertEquals(7, ((Number) compiled.runDirect(map1)).intValue());

            // 第二次
            Map<String, Object> map2 = new ConcurrentHashMap<>(bindings);
            map2.put("x", 30);
            map2.put("y", 40);
            assertEquals(70, ((Number) compiled.runDirect(map2)).intValue());
        }

        @Test
        @DisplayName("有状态的注入函数 — 状态不应影响缓存安全性")
        void testStatefulInjectedFunction() {
            AtomicInteger callCount = new AtomicInteger(0);

            Nova nova = new Nova();
            nova.defineFunctionVararg("track", args -> {
                callCount.incrementAndGet();
                return ((Number) args[0]).intValue() * 2;
            });
            CompiledNova compiled = nova.compileToBytecode("track(x)", "<test>");
            Map<String, Object> bindings = compiled.getBindings();

            // 多次调用
            for (int i = 0; i < 5; i++) {
                Map<String, Object> map = new ConcurrentHashMap<>(bindings);
                map.put("x", i);
                Object result = compiled.runDirect(map);
                assertEquals(i * 2, ((Number) result).intValue(),
                        "第 " + (i + 1) + " 次调用结果应正确");
            }
            assertEquals(5, callCount.get(), "函数应被调用 5 次");
        }
    }

    // ========== 场景5: 完整 DragonNova 模拟 ==========

    @Nested
    @DisplayName("DragonNova 完整模拟 — 编译+多帧渲染+GUI重开")
    class FullDragonNovaSimulation {

        /** 模拟 DragonNova 的完整使用流程 */
        @Test
        @DisplayName("编译一次，模拟多帧渲染 + GUI 关闭再打开")
        void testFullLifecycle() {
            // === 编译阶段（只做一次） ===
            Nova nova = new Nova();
            // 模拟注入 DC 函数
            nova.defineFunctionVararg("getSlotItem", args -> {
                int slot = ((Number) args[0]).intValue();
                return "item_" + slot;
            });
            nova.defineFunctionVararg("max", args -> {
                double a = ((Number) args[0]).doubleValue();
                double b = ((Number) args[1]).doubleValue();
                return Math.max(a, b);
            });

            CompiledNova compiled = nova.compileToBytecode(
                    "val item = getSlotItem(slotId)\n" +
                    "val posX = comp.x + offset\n" +
                    "if (item != \"item_0\") posX else max(posX, 100)",
                    "<dragoncore>");
            Map<String, Object> baseBindings = compiled.getBindings();

            // === 第一次打开 GUI：模拟多帧渲染 ===
            for (int frame = 0; frame < 3; frame++) {
                Map<String, Object> liveMap = new ConcurrentHashMap<>(baseBindings);
                // 每帧注入新的代理（模拟 ScopePrefixProxy 每帧重建）
                liveMap.put("comp", new MockComponentProxy(
                        10 + frame, 20, 100, 50));
                liveMap.put("offset", 5);
                liveMap.put("slotId", 1);
                liveMap.put("w", 1920);
                liveMap.put("h", 1080);

                Object result = compiled.runDirect(liveMap);
                double expected = 10.0 + frame + 5;
                assertEquals(expected, ((Number) result).doubleValue(), 0.001,
                        "GUI1 frame " + frame + ": comp.x=" + (10 + frame) + " + offset=5");
            }

            // === 关闭 GUI ===
            // （实际中会 remove scopeMap，这里无需操作）

            // === 第二次打开 GUI：不同的代理，不同的值 ===
            for (int frame = 0; frame < 3; frame++) {
                Map<String, Object> liveMap = new ConcurrentHashMap<>(baseBindings);
                liveMap.put("comp", new MockComponentProxy(
                        200 + frame, 300, 400, 500));
                liveMap.put("offset", 50);
                liveMap.put("slotId", 0);  // item_0 → 走 else 分支
                liveMap.put("w", 1920);
                liveMap.put("h", 1080);

                Object result = compiled.runDirect(liveMap);
                double posX = 200.0 + frame + 50;
                double expected = Math.max(posX, 100);
                assertEquals(expected, ((Number) result).doubleValue(), 0.001,
                        "GUI2 frame " + frame + ": max(comp.x + offset, 100)");
            }
        }

        @Test
        @DisplayName("多个不同表达式共享函数注册 — 模拟同一 GUI 多个属性")
        void testMultipleExpressionsSharedFunctions() {
            // 模拟: 同一个 GUI 中有多个属性使用 nova| 前缀
            Nova nova = new Nova();
            nova.defineFunctionVararg("clamp", args -> {
                double val = ((Number) args[0]).doubleValue();
                double min = ((Number) args[1]).doubleValue();
                double max = ((Number) args[2]).doubleValue();
                return Math.max(min, Math.min(max, val));
            });

            // 编译多个表达式
            CompiledNova exprX = nova.compileToBytecode("clamp(rawX, 0, w)", "<x>");
            CompiledNova exprY = nova.compileToBytecode("clamp(rawY, 0, h)", "<y>");
            CompiledNova exprW = nova.compileToBytecode("w / 2", "<width>");

            Map<String, Object> bindingsX = exprX.getBindings();
            Map<String, Object> bindingsY = exprY.getBindings();
            Map<String, Object> bindingsW = exprW.getBindings();

            // 模拟多帧渲染
            for (int frame = 0; frame < 5; frame++) {
                int rawX = frame * 100;
                int rawY = frame * 50;

                Map<String, Object> mapX = new ConcurrentHashMap<>(bindingsX);
                mapX.put("rawX", rawX);
                mapX.put("w", 800);
                double resultX = ((Number) exprX.runDirect(mapX)).doubleValue();
                assertEquals(Math.max(0, Math.min(800, rawX)), resultX, 0.001,
                        "frame " + frame + " X");

                Map<String, Object> mapY = new ConcurrentHashMap<>(bindingsY);
                mapY.put("rawY", rawY);
                mapY.put("h", 600);
                double resultY = ((Number) exprY.runDirect(mapY)).doubleValue();
                assertEquals(Math.max(0, Math.min(600, rawY)), resultY, 0.001,
                        "frame " + frame + " Y");

                Map<String, Object> mapW = new ConcurrentHashMap<>(bindingsW);
                mapW.put("w", 800);
                double resultW = ((Number) exprW.runDirect(mapW)).doubleValue();
                assertEquals(400.0, resultW, 0.001, "frame " + frame + " width");
            }
        }

        @Test
        @DisplayName("嵌套 runDirect — 模拟 ComponentPropertyProxy 触发内层求值")
        void testNestedRunDirect() {
            Nova nova = new Nova();
            // 内层表达式
            CompiledNova innerExpr = nova.compileToBytecode("base + 10", "<inner>");
            Map<String, Object> innerBindings = innerExpr.getBindings();

            // 外层的 NovaDynamicObject 在 getMember 时触发内层 runDirect
            Nova nova2 = new Nova();
            CompiledNova outerExpr = nova2.compileToBytecode("comp.x + offset", "<outer>");
            Map<String, Object> outerBindings = outerExpr.getBindings();

            for (int i = 0; i < 3; i++) {
                final int base = i * 100;

                // 创建一个 proxy，getMember("x") 时触发 innerExpr.runDirect
                NovaDynamicObject nestedProxy = new NovaDynamicObject() {
                    @Override
                    public Object getMember(String name) {
                        if ("x".equals(name)) {
                            Map<String, Object> innerMap = new ConcurrentHashMap<>(innerBindings);
                            innerMap.put("base", base);
                            return innerExpr.runDirect(innerMap);
                        }
                        return null;
                    }
                    @Override
                    public void setMember(String name, Object value) {}
                };

                Map<String, Object> outerMap = new ConcurrentHashMap<>(outerBindings);
                outerMap.put("comp", nestedProxy);
                outerMap.put("offset", 5);

                Object result = outerExpr.runDirect(outerMap);
                double expected = base + 10 + 5;
                assertEquals(expected, ((Number) result).doubleValue(), 0.001,
                        "嵌套 runDirect i=" + i + ": inner=" + (base + 10) + " + 5");
            }
        }
    }

    // ========== 场景6: run() vs runDirect() 对 bindings 的副作用 ==========

    @Nested
    @DisplayName("run() vs runDirect() — bindings 副作用差异")
    class RunVsRunDirectSideEffects {

        @Test
        @DisplayName("run() 回写 bindings — 第二次 run 是否受污染")
        void testRunWritesBackBindings() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "var x = input + 1\nx", "<test>");
            compiled.set("input", 10);

            Object result1 = compiled.run();
            assertEquals(11, ((Number) result1).intValue());

            // run() 把 x=11 写回 bindings，第二次 run 时 input 应该仍有效
            compiled.set("input", 20);
            Object result2 = compiled.run();
            assertEquals(21, ((Number) result2).intValue(), "第二次 run 应使用新的 input=20");
        }

        @Test
        @DisplayName("runDirect() 不污染 CompiledNova.bindings")
        void testRunDirectDoesNotPollute() {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("x + 1", "<test>");

            // 记录初始 bindings
            Map<String, Object> before = compiled.getBindings();

            Map<String, Object> map1 = new ConcurrentHashMap<>();
            map1.put("x", 10);
            compiled.runDirect(map1);

            // runDirect 后 bindings 不应被修改
            Map<String, Object> after = compiled.getBindings();
            assertEquals(before.size(), after.size(),
                    "runDirect 不应改变 CompiledNova 的 bindings 大小");
        }
    }

    // ========== 场景7: 并发安全 ==========

    @Nested
    @DisplayName("并发 runDirect — 多线程同时使用同一个 CompiledNova")
    class ConcurrencyTests {

        @Test
        @DisplayName("多线程并发 runDirect 不互相干扰")
        void testConcurrentRunDirect() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("x * 2 + y", "<test>");

            int threadCount = 4;
            int iterations = 100;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();

            for (int t = 0; t < threadCount; t++) {
                final int threadId = t;
                new Thread(() -> {
                    try {
                        for (int i = 0; i < iterations; i++) {
                            Map<String, Object> map = new ConcurrentHashMap<>();
                            int x = threadId * 1000 + i;
                            int y = threadId;
                            map.put("x", x);
                            map.put("y", y);
                            Object result = compiled.runDirect(map);
                            int expected = x * 2 + y;
                            assertEquals(expected, ((Number) result).intValue(),
                                    "thread=" + threadId + " i=" + i);
                        }
                    } catch (Throwable e) {
                        error.compareAndSet(null, e);
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            if (error.get() != null) {
                fail("并发测试失败: " + error.get().getMessage(), error.get());
            }
        }
    }
}
