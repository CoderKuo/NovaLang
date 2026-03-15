package com.novalang.runtime.codegen;

import com.novalang.ir.NovaIrCompiler;
import com.novalang.ir.mir.*;
import com.novalang.ir.pass.PassPipeline;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.Interpreter;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复现字节码编译路径算术精度问题：
 *
 * <p>当两个操作数的编译时类型都是 OBJECT 时（来自函数参数、注入函数返回值、NovaOps.add 返回值），
 * MirCodeGenerator.resolveNumericKind 默认返回 INT，导致 unboxInt 截断 Double 值。</p>
 *
 * <p>实际场景：ctx.getDamage() 返回 11.0，乘以 (1.0 + critDmg) 应得 ~18.87，实际得到 11。</p>
 *
 * <p>根因：ADD 有 isUnknownObjectType 保护委托给 NovaOps.add()，
 * 但 MUL/SUB/DIV/MOD 没有这个保护，OBJECT*OBJECT 直接走 INT 路径。</p>
 */
@DisplayName("OBJECT*OBJECT 算术截断 bug 复现")
public class CustomTest {

    // ============ 模拟 DamageContext ============

    public static class DamageCtx {
        private double damage;

        public DamageCtx(double damage) {
            this.damage = damage;
        }

        public double getDamage() {
            return damage;
        }

        public void setDamage(double value) {
            this.damage = value;
        }
    }

    public static DamageCtx newCtx(double damage) {
        return new DamageCtx(damage);
    }

    // ============ 核心 bug 复现：通过 Nova.defineFunction + compileToBytecode ============

    @Nested
    @DisplayName("核心 bug 复现 — Nova.defineFunction + compileToBytecode.call()")
    class CoreBugTests {

        /**
         * 最小复现：两个函数参数（OBJECT 类型）直接相乘。
         * a=11.0, b=1.7156 → 应得 18.87，实际得到 11（int截断）。
         */
        @Test
        @DisplayName("函数参数 OBJECT * OBJECT — 最小复现")
        void testParamMultiply() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(a, b) {\n" +
                "    return a * b\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("test", 11.0, 1.7156);
            assertEquals(18.87, ((Number) result).doubleValue(), 0.1,
                "OBJECT * OBJECT: 11.0 * 1.7156 应约等于 18.87，而非截断为 11");
        }

        /**
         * 注入函数返回 Double，乘以另一个注入函数返回值。
         * 模拟 getAttrRandom() * getDamage() 场景。
         */
        @Test
        @DisplayName("注入函数返回值相乘 — 模拟 getAttrRandom * getDamage")
        void testInjectedFunctionMultiply() throws Exception {
            Nova nova = new Nova();
            nova.defineFunction("getDouble", (Object val) -> ((Number) val).doubleValue());
            CompiledNova compiled = nova.compileToBytecode(
                "fun test() {\n" +
                "    var a = getDouble(11.0)\n" +
                "    var b = getDouble(1.7156)\n" +
                "    return a * b\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("test");
            assertEquals(18.87, ((Number) result).doubleValue(), 0.1,
                "注入函数返回 Double 相乘应正确");
        }

        /**
         * 精确复现暴击计算链：
         * critDmg = getAttrRandom(...) → OBJECT
         * multiplier = 1.0 + critDmg → NovaOps.add → OBJECT
         * before = ctx.getDamage() → OBJECT
         * newDmg = before * multiplier → OBJECT * OBJECT → INT (bug!)
         */
        @Test
        @DisplayName("完整暴击计算链 — 精确复现 NovaAttribute 场景")
        void testFullCriticalChain() throws Exception {
            Nova nova = new Nova();
            nova.defineFunction("getAttrValue", (Object val) -> ((Number) val).doubleValue());
            CompiledNova compiled = nova.compileToBytecode(
                "fun execute(ctx, attrValue) {\n" +
                "    var critDmg = getAttrValue(0.7156)\n" +
                "    var before = ctx.getDamage()\n" +
                "    var multiplier = 1.0 + critDmg\n" +
                "    var newDmg = before * multiplier\n" +
                "    return newDmg\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("execute", new DamageCtx(11.0), 0.5);
            assertEquals(18.87, ((Number) result).doubleValue(), 0.1,
                "完整暴击链: 11.0 * (1.0 + 0.7156) 应约等于 18.87");
        }

        /**
         * OBJECT - OBJECT（减法）也应该受同样的 bug 影响。
         */
        @Test
        @DisplayName("函数参数 OBJECT - OBJECT — 减法截断")
        void testParamSubtract() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(a, b) {\n" +
                "    return a - b\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("test", 11.5, 1.3);
            assertEquals(10.2, ((Number) result).doubleValue(), 0.1,
                "OBJECT - OBJECT: 11.5 - 1.3 应约等于 10.2");
        }

        /**
         * OBJECT / OBJECT（除法）也应该受同样的 bug 影响。
         */
        @Test
        @DisplayName("函数参数 OBJECT / OBJECT — 除法截断")
        void testParamDivide() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(a, b) {\n" +
                "    return a / b\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("test", 11.0, 3.0);
            assertEquals(3.667, ((Number) result).doubleValue(), 0.1,
                "OBJECT / OBJECT: 11.0 / 3.0 应约等于 3.667");
        }
    }

    // ============ 对象方法调用参数类型匹配 bug 复现 ============

    /**
     * 复现 AffixResult.set(String, Object) 无法被脚本调用的问题。
     * 错误: "No method 'set' found on XxxResult with 2 argument(s). 'set' exists with 2 argument(s)"
     * 原因: 方法存在且参数数量匹配，但参数类型匹配逻辑有问题。
     */
    public static class AffixResult {
        public String lastKey;
        public double lastValue;
        public String debugInfo;

        public void set(String key, Object value) {
            this.lastKey = key;
            this.lastValue = ((Number) value).doubleValue();
        }

        /** 诊断方法：接受 (Object, Object) 避免类型匹配问题，记录实际类型 */
        public void debugSet(Object key, Object value) {
            this.debugInfo = "key=" + (key != null ? key.getClass().getName() : "null") + ":" + key
                    + " value=" + (value != null ? value.getClass().getName() : "null") + ":" + value;
            if (key instanceof String) this.lastKey = (String) key;
            if (value instanceof Number) this.lastValue = ((Number) value).doubleValue();
        }
    }

    @Nested
    @DisplayName("方法参数类型匹配 — AffixResult.set(String, Object) 复现")
    class MethodArgTypeTests {

        @Test
        @DisplayName("result.set(字符串字面量, 整数字面量)")
        void testSetWithLiterals() throws Exception {
            Nova nova = new Nova();
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(result) {\n" +
                "    result.set(\"fire_damage\", 42)\n" +
                "}", "test.nova");
            compiled.run();
            compiled.call("test", result);
            assertEquals("fire_damage", result.lastKey);
            assertEquals(42.0, result.lastValue, 0.01);
        }

        @Test
        @DisplayName("result.set(字符串字面量, 浮点数计算结果)")
        void testSetWithDoubleExpr() throws Exception {
            Nova nova = new Nova();
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(result) {\n" +
                "    var level = 3.0\n" +
                "    result.set(\"fire_damage\", level * 15)\n" +
                "}", "test.nova");
            compiled.run();
            compiled.call("test", result);
            assertEquals("fire_damage", result.lastKey);
            assertEquals(45.0, result.lastValue, 0.01);
        }

        @Test
        @DisplayName("result.set(if表达式返回的字符串, toNumber结果 * 整数)")
        void testSetWithIfExprAndToNumber() throws Exception {
            Nova nova = new Nova();
            nova.defineFunction("toNumber", (Object val) -> {
                if (val instanceof Number) return ((Number) val).doubleValue();
                if (val instanceof String) {
                    try { return Double.parseDouble((String) val); }
                    catch (Exception e) { return 0.0; }
                }
                return 0.0;
            });
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun resolve(result, groups) {\n" +
                "    var element = groups.get(0)\n" +
                "    var level = toNumber(groups.get(1))\n" +
                "    var attrId = if (element == \"火焰\") { \"fire_damage\" }\n" +
                "        else if (element == \"冰霜\") { \"ice_damage\" }\n" +
                "        else if (element == \"雷电\") { \"lightning_damage\" }\n" +
                "        else { \"poison_damage\" }\n" +
                "    result.set(attrId, level * 15)\n" +
                "}", "test.nova");
            compiled.run();
            java.util.List<String> groups = java.util.List.of("火焰", "3");
            compiled.call("resolve", result, groups);
            assertEquals("fire_damage", result.lastKey);
            assertEquals(45.0, result.lastValue, 0.01);
        }

        /**
         * 诊断测试 1：直接调用 NovaDynamic.invoke2 验证方法解析本身是否正常。
         * 如果通过 → 问题在字节码生成阶段。如果失败 → 问题在 NovaDynamic 方法解析。
         */
        @Test
        @DisplayName("诊断：NovaDynamic.invoke2 直接调用 set(String, Object)")
        void testDirectInvoke2() throws Exception {
            AffixResult result = new AffixResult();
            com.novalang.runtime.NovaDynamic.invoke2(result, "set", "fire_damage", 42);
            assertEquals("fire_damage", result.lastKey);
            assertEquals(42.0, result.lastValue, 0.01);
        }

        /**
         * 诊断测试 2：使用 debugSet(Object, Object) 替代 set(String, Object)。
         * debugSet 参数全为 Object，一定能匹配。用来捕获 if-expression 实际产出的类型。
         */
        @Test
        @DisplayName("诊断：debugSet 捕获 if 表达式实际参数类型")
        void testDebugSetCaptureTypes() throws Exception {
            Nova nova = new Nova();
            nova.defineFunction("toNumber", (Object val) -> {
                if (val instanceof Number) return ((Number) val).doubleValue();
                if (val instanceof String) {
                    try { return Double.parseDouble((String) val); }
                    catch (Exception e) { return 0.0; }
                }
                return 0.0;
            });
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun resolve(result, groups) {\n" +
                "    var element = groups.get(0)\n" +
                "    var level = toNumber(groups.get(1))\n" +
                "    var attrId = if (element == \"火焰\") { \"fire_damage\" }\n" +
                "        else if (element == \"冰霜\") { \"ice_damage\" }\n" +
                "        else if (element == \"雷电\") { \"lightning_damage\" }\n" +
                "        else { \"poison_damage\" }\n" +
                "    result.debugSet(attrId, level * 15)\n" +
                "}", "test.nova");
            compiled.run();
            java.util.List<String> groups = java.util.List.of("火焰", "3");
            compiled.call("resolve", result, groups);
            System.err.println("debugInfo: " + result.debugInfo);
            assertEquals("fire_damage", result.lastKey,
                "debugSet 应成功, debugInfo=" + result.debugInfo);
            assertEquals(45.0, result.lastValue, 0.01);
        }

        /**
         * 诊断测试 3：简化版 — 只有 if-expression，无乘法，调用 set。
         * 排除乘法运算干扰。
         */
        @Test
        @DisplayName("诊断：if 表达式结果直接传给 set（无乘法）")
        void testSetWithIfExprOnly() throws Exception {
            Nova nova = new Nova();
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(result, element) {\n" +
                "    var attrId = if (element == \"火焰\") { \"fire_damage\" }\n" +
                "        else { \"poison_damage\" }\n" +
                "    result.set(attrId, 42)\n" +
                "}", "test.nova");
            compiled.run();
            compiled.call("test", result, "火焰");
            assertEquals("fire_damage", result.lastKey);
            assertEquals(42.0, result.lastValue, 0.01);
        }

        /**
         * 诊断测试 4：字符串变量（非 if-expression）+ 乘法结果调用 set。
         * 排除 if-expression 干扰。
         */
        @Test
        @DisplayName("诊断：字符串变量 + 乘法结果调用 set")
        void testSetWithVarAndMul() throws Exception {
            Nova nova = new Nova();
            nova.defineFunction("toNumber", (Object val) -> {
                if (val instanceof Number) return ((Number) val).doubleValue();
                if (val instanceof String) {
                    try { return Double.parseDouble((String) val); }
                    catch (Exception e) { return 0.0; }
                }
                return 0.0;
            });
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(result) {\n" +
                "    var attrId = \"fire_damage\"\n" +
                "    var level = toNumber(\"3\")\n" +
                "    result.set(attrId, level * 15)\n" +
                "}", "test.nova");
            compiled.run();
            compiled.call("test", result);
            assertEquals("fire_damage", result.lastKey);
            assertEquals(45.0, result.lastValue, 0.01);
        }

        @Test
        @DisplayName("result.set(变量字符串, 变量数值) — 最小复现")
        void testSetWithVariables() throws Exception {
            Nova nova = new Nova();
            AffixResult result = new AffixResult();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(result) {\n" +
                "    var key = \"physical_damage\"\n" +
                "    var v = 100.0\n" +
                "    result.set(key, v)\n" +
                "}", "test.nova");
            compiled.run();
            compiled.call("test", result);
            assertEquals("physical_damage", result.lastKey);
            assertEquals(100.0, result.lastValue, 0.01);
        }
    }

    // ============ 编译路径方法调用 bug 复现 ============

    @Nested
    @DisplayName("编译路径 obj.method(arg) 被当成成员访问")
    class MethodCallBugTests {

        /**
         * 编译路径中 ctx.setDamage(newDmg) 被拆解为：
         * 1. getMember("setDamage") → 失败，因为 setDamage 不是 getter
         * 2. 而不是识别为 method call setDamage(1 arg)
         *
         * 错误信息：No member 'setDamage' found on DamageCtx.
         *          Available: damage, getDamage(0), setDamage(1)
         */
        @Test
        @DisplayName("ctx.setDamage(x) — 编译路径应识别为方法调用而非成员访问")
        void testSetDamageMethodCall() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(ctx) {\n" +
                "    ctx.setDamage(99.5)\n" +
                "    return ctx.getDamage()\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("test", new DamageCtx(11.0));
            assertEquals(99.5, ((Number) result).doubleValue(), 0.1,
                "ctx.setDamage(99.5) 后 getDamage 应返回 99.5");
        }

        /**
         * 带计算结果的 setDamage 调用。
         */
        @Test
        @DisplayName("ctx.setDamage(getDamage() * 1.5) — 编译路径方法调用+算术")
        void testSetDamageWithArithmetic() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                "fun test(ctx) {\n" +
                "    var newDmg = ctx.getDamage() * 1.5\n" +
                "    ctx.setDamage(newDmg)\n" +
                "    return ctx.getDamage()\n" +
                "}", "test.nova");
            compiled.run();
            Object result = compiled.call("test", new DamageCtx(10.0));
            assertEquals(15.0, ((Number) result).doubleValue(), 0.1,
                "setDamage(getDamage()*1.5): 10.0*1.5=15.0");
        }
    }

    // ============ defineLibrary 编译模式测试 ============

    @Nested
    @DisplayName("defineLibrary — 编译模式")
    class DefineLibraryCompileTests {

        @Test
        @DisplayName("defineLibrary 值访问")
        void testLibraryVal() throws Exception {
            Nova nova = new Nova();
            nova.defineLibrary("config", lib -> {
                lib.defineVal("VERSION", "2.0");
            });
            CompiledNova compiled = nova.compileToBytecode("config.VERSION", "test.nova");
            Object result = compiled.run();
            assertEquals("2.0", String.valueOf(result));
        }

        @Test
        @DisplayName("defineLibrary 无参函数调用")
        void testLibraryFunction0() throws Exception {
            Nova nova = new Nova();
            nova.defineLibrary("util", lib -> {
                lib.defineFunction("hello", () -> "world");
            });
            CompiledNova compiled = nova.compileToBytecode("util.hello()", "test.nova");
            Object result = compiled.run();
            assertEquals("world", String.valueOf(result));
        }

        @Test
        @DisplayName("defineLibrary 带参函数调用")
        void testLibraryFunction1() throws Exception {
            Nova nova = new Nova();
            nova.defineLibrary("math", lib -> {
                lib.defineFunction("double", (Object x) -> ((Number) x).intValue() * 2);
            });
            CompiledNova compiled = nova.compileToBytecode("math.double(21)", "test.nova");
            Object result = compiled.run();
            assertEquals(42, result);
        }

        @Test
        @DisplayName("defineLibrary 多参数函数")
        void testLibraryFunction2() throws Exception {
            Nova nova = new Nova();
            nova.defineLibrary("calc", lib -> {
                lib.defineFunction("add", (Object a, Object b) ->
                        ((Number) a).intValue() + ((Number) b).intValue());
            });
            CompiledNova compiled = nova.compileToBytecode("calc.add(10, 20)", "test.nova");
            Object result = compiled.run();
            assertEquals(30, result);
        }
    }

    // ============ registerExtension 编译模式测试 ============

    @Nested
    @DisplayName("registerExtension — 编译模式")
    class RegisterExtensionCompileTests {

        @Test
        @DisplayName("String 扩展方法 — 无额外参数")
        void testStringExtension0() throws Exception {
            Nova nova = new Nova();
            nova.registerExtension(String.class, "shout",
                    (Object s) -> ((String) s).toUpperCase() + "!");
            CompiledNova compiled = nova.compileToBytecode("\"hello\".shout()", "test.nova");
            assertEquals("HELLO!", compiled.run());
        }

        @Test
        @DisplayName("String 扩展方法 — 带1个参数")
        void testStringExtension1() throws Exception {
            Nova nova = new Nova();
            nova.registerExtension(String.class, "surround",
                    (Object s, Object ch) -> ch.toString() + s.toString() + ch.toString());
            CompiledNova compiled = nova.compileToBytecode("\"hello\".surround(\"*\")", "test.nova");
            assertEquals("*hello*", compiled.run());
        }

        @Test
        @DisplayName("String 扩展方法 — 带2个参数")
        void testStringExtension2() throws Exception {
            Nova nova = new Nova();
            nova.registerExtension(String.class, "between",
                    (Object s, Object left, Object right) ->
                            left.toString() + s.toString() + right.toString());
            CompiledNova compiled = nova.compileToBytecode("\"hi\".between(\"[\", \"]\")", "test.nova");
            assertEquals("[hi]", compiled.run());
        }

        @Test
        @DisplayName("Integer 扩展方法")
        void testIntExtension() throws Exception {
            Nova nova = new Nova();
            nova.registerExtension(Integer.class, "isEven",
                    (Object n) -> ((Number) n).intValue() % 2 == 0);
            CompiledNova compiled = nova.compileToBytecode("42.isEven()", "test.nova");
            assertEquals(true, compiled.run());
        }

        @Test
        @DisplayName("扩展方法在函数体内使用")
        void testExtensionInFunction() throws Exception {
            Nova nova = new Nova();
            nova.registerExtension(String.class, "wrap",
                    (Object s, Object prefix, Object suffix) ->
                            prefix.toString() + s.toString() + suffix.toString());
            CompiledNova compiled = nova.compileToBytecode(
                    "fun test(s: String) = s.wrap(\"[\", \"]\")\ntest(\"hi\")", "test.nova");
            assertEquals("[hi]", compiled.run());
        }

        @Test
        @DisplayName("defineFunction + registerExtension 混合使用")
        void testMixedDefineAndExtension() throws Exception {
            Nova nova = new Nova();
            nova.defineFunction("greet", (Object name) -> "Hello, " + name);
            nova.registerExtension(String.class, "exclaim",
                    (Object s) -> s.toString() + "!!!");
            CompiledNova compiled = nova.compileToBytecode(
                    "greet(\"Nova\").exclaim()", "test.nova");
            assertEquals("Hello, Nova!!!", compiled.run());
        }
    }

    // ============ Map 点访问 + List 索引访问（编译模式） ============

    @Nested
    @DisplayName("Map/List 编译模式访问")
    class MapListCompileTests {

        @Test
        @DisplayName("Map 点访问取值")
        void testMapDotAccess() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val m = #{name: \"Alice\", age: 30}\nm.name", "test.nova");
            assertEquals("Alice", compiled.run());
        }

        @Test
        @DisplayName("Map 点访问取数字")
        void testMapDotAccessInt() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val m = #{x: 10, y: 20}\nm.x + m.y", "test.nova");
            assertEquals(30, compiled.run());
        }

        @Test
        @DisplayName("Map [] 访问")
        void testMapBracketAccess() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val m = #{\"key\": \"value\"}\nm[\"key\"]", "test.nova");
            assertEquals("value", compiled.run());
        }

        @Test
        @DisplayName("Map 注入后点访问")
        void testInjectedMapDotAccess() throws Exception {
            Nova nova = new Nova();
            java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("name", "Bob");
            data.put("score", 95);
            nova.defineVal("data", data);
            CompiledNova compiled = nova.compileToBytecode("data.name", "test.nova");
            assertEquals("Bob", compiled.run());
        }

        @Test
        @DisplayName("List [] 索引访问")
        void testListIndexAccess() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val list = [10, 20, 30]\nlist[1]", "test.nova");
            assertEquals(20, compiled.run());
        }

        @Test
        @DisplayName("List 负索引访问")
        void testListNegativeIndex() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val list = [\"a\", \"b\", \"c\"]\nlist[-1]", "test.nova");
            assertEquals("c", compiled.run());
        }

        @Test
        @DisplayName("List 注入后索引访问")
        void testInjectedListIndexAccess() throws Exception {
            Nova nova = new Nova();
            java.util.List<Object> items = java.util.Arrays.asList("x", "y", "z");
            nova.defineVal("items", items);
            CompiledNova compiled = nova.compileToBytecode("items[0]", "test.nova");
            assertEquals("x", compiled.run());
        }

        @Test
        @DisplayName("嵌套 Map.List 混合访问")
        void testNestedMapListAccess() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val data = #{items: [1, 2, 3]}\ndata.items[2]", "test.nova");
            assertEquals(3, compiled.run());
        }
    }

    // ============ 全局变量函数内写回测试 ============

    @Nested
    @DisplayName("全局变量函数内修改 — 编译模式")
    class GlobalVarMutationTests {

        @Test
        @DisplayName("run() 内函数修改全局变量后可读取新值")
        void testFunctionMutatesGlobalVar() throws Exception {
            Nova nova = new Nova();
            String code =
                    "var counter = 0\n" +
                    "fun increment() { counter = counter + 1 }\n" +
                    "increment()\n" +
                    "increment()\n" +
                    "increment()\n" +
                    "counter";
            CompiledNova compiled = nova.compileToBytecode(code, "test.nova");
            assertEquals(3, compiled.run());
        }

        @Test
        @DisplayName("run() 内函数修改全局变量 — 模拟 showTime 场景")
        void testShowTimeScenario() throws Exception {
            Nova nova = new Nova();
            String code =
                    "var showTime = 0\n" +
                    "fun updateTime() { showTime = 42 }\n" +
                    "updateTime()\n" +
                    "showTime";
            CompiledNova compiled = nova.compileToBytecode(code, "test.nova");
            assertEquals(42, compiled.run(), "函数内赋值 showTime=42 应写回全局");
        }

        @Test
        @DisplayName("run() 内多函数共享全局变量")
        void testMultiFunctionShareGlobal() throws Exception {
            Nova nova = new Nova();
            String code =
                    "var value = 0\n" +
                    "fun set(v) { value = v }\n" +
                    "fun get() = value\n" +
                    "set(100)\n" +
                    "get()";
            CompiledNova compiled = nova.compileToBytecode(code, "test.nova");
            assertEquals(100, compiled.run());
        }

        @Test
        @DisplayName("call() 调用函数修改全局变量后可通过 call() 读取")
        void testCallMutatesGlobal() throws Exception {
            Nova nova = new Nova();
            String code =
                    "var showTime = 0\n" +
                    "fun onNotify() {\n" +
                    "    showTime = 999\n" +
                    "}\n" +
                    "fun getShowTime() = showTime\n";
            CompiledNova compiled = nova.compileToBytecode(code, "test.nova");
            compiled.run();  // 注册函数
            compiled.call("onNotify");
            Object result = compiled.call("getShowTime");
            assertEquals(999, result, "onNotify 修改 showTime 后 getShowTime 应返回 999");
        }
    }

    // ============ MIR Dump 调试 ============

    @Test
    @DisplayName("MIR dump: lazy mutable capture")
    void dumpLazyMutableCaptureMir() {
        String code =
                "var counter = 0\n" +
                "val x by lazy { counter = counter + 1; 42 }\n" +
                "val before = counter\n" +
                "val value = x\n" +
                "val after = counter\n" +
                "\"before=$before,after=$after,value=$value\"";

        // 解释器模式 MIR dump（无优化 Pass，纯 lowering 输出）
        PassPipeline pipeline = new PassPipeline();
        pipeline.setInterpreterMode(true);
        // 不添加任何 MIR Pass，观察 lowering 原始输出
        Program program = new Parser(new Lexer(code, "test.nova"), "test.nova").parse();
        MirModule mir = pipeline.executeToMir(program);

        System.err.println("===== INTERPRETER MODE MIR =====");
        for (MirClass cls : mir.getClasses()) {
            System.err.println("class " + cls.getName() + " {");
            for (MirFunction m : cls.getMethods()) printFunc(m);
            System.err.println("}");
        }
        for (MirFunction f : mir.getTopLevelFunctions()) printFunc(f);
        System.err.println("===== END =====");

        // 跑一下确认结果
        Interpreter interp = new Interpreter();
        NovaValue result = interp.eval(code, "test.nova");
        System.err.println("Lazy Result: " + result);

        // 嵌套闭包 MIR dump（匹配实际 Interpreter 配置）
        String nestedCode = "var x = 0\nval outer = {\n    val inner = { x = x + 1 }\n    inner()\n}\nouter()\nx";
        PassPipeline p3 = new PassPipeline();
        p3.setInterpreterMode(true);
        p3.setScriptMode(true);
        MirModule mir3 = p3.executeToMir(new Parser(new Lexer(nestedCode, "t"), "t").parse());
        System.err.println("===== NESTED CLOSURE MIR =====");
        for (MirClass cls : mir3.getClasses()) {
            System.err.println("class " + cls.getName() + " {");
            for (MirFunction m : cls.getMethods()) printFunc(m);
            System.err.println("}");
        }
        System.err.println("===== END =====");
        NovaValue nestedResult = new Interpreter().eval(nestedCode, "t.nova");
        System.err.println("Nested closure result: " + nestedResult);
    }

    private void printFunc(MirFunction func) {
        System.err.println("  fun " + func.getName() + " -> " + func.getReturnType());
        System.err.println("    locals:");
        for (MirLocal local : func.getLocals()) {
            System.err.println("      %" + local.getIndex() + " " + local.getName() + " : " + local.getType());
        }
        for (BasicBlock block : func.getBlocks()) {
            System.err.println("    block_" + block.getId() + ":");
            for (MirInst inst : block.getInstructions()) {
                System.err.println("      " + inst);
            }
            if (block.getTerminator() != null) {
                System.err.println("      " + block.getTerminator());
            }
        }
    }
}
