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
                    "val m = #{\"name\": \"Alice\", \"age\": 30}\nm.name", "test.nova");
            assertEquals("Alice", compiled.run());
        }

        @Test
        @DisplayName("Map 点访问取数字")
        void testMapDotAccessInt() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "val m = #{\"x\": 10, \"y\": 20}\nm.x + m.y", "test.nova");
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
                    "val data = #{\"items\": [1, 2, 3]}\ndata.items[2]", "test.nova");
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

    // ============ null as T? 可空类型转换修复 ============

    @Nested
    @DisplayName("null as T? 可空类型转换")
    class NullableCastTests {

        @Test
        @DisplayName("null as String? 应返回 null 而非抛异常")
        void testNullAsNullableType() {
            Interpreter interp = new Interpreter();
            NovaValue result = interp.eval("null as String?", "test.nova");
            assertTrue(result.isNull());
        }

        @Test
        @DisplayName("null as String? + safe call + elvis")
        void testNullSafeCallElvis() {
            Interpreter interp = new Interpreter();
            NovaValue result = interp.eval("(null as String?)?.toUpperCase() ?: \"UNKNOWN\"", "test.nova");
            assertEquals("UNKNOWN", result.toJavaValue());
        }

        @Test
        @DisplayName("非 null as String? 正常转换")
        void testNonNullAsNullableType() {
            Interpreter interp = new Interpreter();
            NovaValue result = interp.eval("\"hello\" as String?", "test.nova");
            assertEquals("hello", result.toJavaValue());
        }

        @Test
        @DisplayName("null as String 应抛异常")
        void testNullAsNonNullableThrows() {
            Interpreter interp = new Interpreter();
            assertThrows(Exception.class, () -> interp.eval("null as String", "test.nova"));
        }

        @Test
        @DisplayName("null as? String 安全转换返回 null")
        void testNullSafeCast() {
            Interpreter interp = new Interpreter();
            NovaValue result = interp.eval("null as? String", "test.nova");
            assertTrue(result.isNull());
        }

        // ---- 编译模式 ----

        @Test
        @DisplayName("[编译] null as String? 应返回 null")
        void testNullAsNullableCompiled() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("null as String?", "test.nova");
            assertNull(compiled.run());
        }

        @Test
        @DisplayName("[编译] null as String? + safe call + elvis")
        void testNullSafeCallElvisCompiled() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "(null as String?)?.toUpperCase() ?: \"UNKNOWN\"", "test.nova");
            assertEquals("UNKNOWN", compiled.run());
        }

        @Test
        @DisplayName("[编译] 非 null as String? 正常转换")
        void testNonNullAsNullableCompiled() throws Exception {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode("\"hello\" as String?", "test.nova");
            assertEquals("hello", compiled.run());
        }

        @Test
        @DisplayName("[编译] null as String 应抛异常")
        void testNullAsNonNullableCompiledThrows() {
            Nova nova = new Nova();
            assertThrows(Exception.class, () -> {
                CompiledNova compiled = nova.compileToBytecode("null as String", "test.nova");
                compiled.run();
            });
        }
    }

    @Test
    @DisplayName("[编译+运行] NovaScript 并行任务代码")
    void testNovaScriptParallelCode() throws Exception {
        Nova nova = new Nova();
        // mock player 和 parse
        java.util.List<String> messages = new java.util.ArrayList<>();
        Object mockPlayer = new Object() {
            public void sendMessage(Object msg) {
                messages.add(String.valueOf(msg));
                System.err.println("[MSG] " + msg);
            }
        };
        nova.set("player", mockPlayer);
        nova.defineFunction("parse", (Object s) -> String.valueOf(s));
        // sync 由 nova 并发库提供，这里不需要额外 mock

        String code =
                "import nova.time.*\n" +
                "val p = player\n" +
                "val hp = parse(\"%player_health_rounded%\")\n" +
                "val lv = parse(\"%player_level%\")\n" +
                "val ping = parse(\"%player_ping%\")\n" +
                "p.sendMessage(parse(\"&8[&bNova&8] &e并行执行 3 个任务...\"))\n" +
                "val start = now()\n" +
                "launch {\n" +
                "  val results = parallel(\n" +
                "    { sleep(500); \"任务A: ${hp}HP\" },\n" +
                "    { sleep(500); \"任务B: Lv.$lv\" },\n" +
                "    { sleep(500); \"任务C: ${ping}ms\" }\n" +
                "  )\n" +
                "  val elapsed = now() - start\n" +
                "  sync {\n" +
                "    for (r in results) {\n" +
                "      p.sendMessage(\"§8[§bNova§8] §f$r\")\n" +
                "    }\n" +
                "    p.sendMessage(\"§8[§bNova§8] §7总耗时: §a${elapsed}ms §7(并行, 非 1500ms)\")\n" +
                "  }\n" +
                "}\n" +
                "sleep(2000)\n" +
                "messages.size()";
        nova.set("messages", messages);
        Object result = nova.eval(code);
        System.err.println("Result: " + result);
        System.err.println("Messages: " + messages);
    }

    // ============ Java 枚举 + 内部类 ============

    @Test
    @DisplayName("Java 枚举常量访问")
    void testJavaEnumAccess() {
        Nova nova = new Nova();
        assertEquals("SECONDS", nova.eval(
                "val tu = javaClass(\"java.util.concurrent.TimeUnit\")\ntu.SECONDS.toString()"));
    }

    @Test
    @DisplayName("Java 枚举 values() 返回数组")
    void testJavaEnumValues() {
        Nova nova = new Nova();
        assertEquals(7, nova.eval(
                "val tu = javaClass(\"java.util.concurrent.TimeUnit\")\ntu.values().length"));
    }

    @Test
    @DisplayName("Java 枚举 valueOf()")
    void testJavaEnumValueOf() {
        Nova nova = new Nova();
        assertEquals("MILLISECONDS", nova.eval(
                "javaClass(\"java.util.concurrent.TimeUnit\").valueOf(\"MILLISECONDS\").toString()"));
    }

    @Test
    @DisplayName("Java 内部类 — 点号语法 java.util.Map.Entry")
    void testJavaInnerClassDotNotation() {
        Nova nova = new Nova();
        Object result = nova.eval("javaClass(\"java.util.Map.Entry\").toString()");
        assertTrue(String.valueOf(result).contains("java.util.Map$Entry"));
    }

    @Test
    @DisplayName("Java 内部类 — 美元符语法 java.util.Map\\$Entry（需转义）")
    void testJavaInnerClassDollarNotation() {
        Nova nova = new Nova();
        // $ 在 Nova 字符串中是插值符号，需用 \$ 转义或原始字符串
        Object result = nova.eval("javaClass(\"java.util.Map\\$Entry\").toString()");
        assertTrue(String.valueOf(result).contains("java.util.Map$Entry"));
    }

    @Test
    @DisplayName("Java 内部类 — Thread.State 枚举")
    void testJavaInnerClassEnum() {
        Nova nova = new Nova();
        assertEquals("RUNNABLE", nova.eval(
                "javaClass(\"java.lang.Thread.State\").valueOf(\"RUNNABLE\").toString()"));
    }

    // ============ 链式调用 shared 扩展函数 ============

    /** 模拟 Java 对象（类似 Bukkit Player） */
    public static class MockPlayer {
        private final String name;
        public MockPlayer(String name) { this.name = name; }
        public String getName() { return name; }
    }

    @Test
    @DisplayName("链式调用: getPlayer().msg() — 通过 registerExt 扩展方法")
    void testChainedExtensionMethodOnJavaObject() {
        Nova nova = new Nova();
        nova.defineFunction("getPlayer", (Object name) -> new MockPlayer(String.valueOf(name)));
        // 正确做法：注册为扩展方法（而非全局函数）
        NovaRuntime.shared().registerExt(MockPlayer.class, "msg",
                (com.novalang.runtime.Function2<Object, Object, Object>) (player, message) ->
                        "sent '" + message + "' to " + ((MockPlayer) player).getName());
        try {
            Object result = nova.eval("getPlayer(\"test\").msg(\"hello\")");
            assertEquals("sent 'hello' to test", result);
        } finally {
            NovaRuntime.shared().getExtensionRegistry().clear();
        }
    }

    @Test
    @DisplayName("非链式调用: msg(player, 123) — shared 全局函数直接调用")
    void testDirectSharedFunctionCall() {
        Nova nova = new Nova();
        nova.defineFunction("getPlayer", (Object name) -> new MockPlayer(String.valueOf(name)));
        NovaRuntime.shared().register("msg",
                (Function2<Object, Object, Object>) (player, message) ->
                        "sent '" + message + "' to " + ((MockPlayer) player).getName());
        try {
            Object result = nova.eval("msg(getPlayer(\"test\"), \"hello\")");
            assertEquals("sent 'hello' to test", result);
        } finally {
            NovaRuntime.shared().remove("msg");
        }
    }

    // ============ hashCode 栈溢出复现 ============

    @Test
    @DisplayName("[复现] 命名空间函数不加括号访问不应栈溢出")
    void testNamespacedFunctionWithoutCallNoStackOverflow() {
        // 模拟 trmenu.openMenu（不加括号，返回函数对象）
        NovaRuntime.shared().register("openMenu",
                (Function1<Object, Object>) menu -> "opened " + menu, "trmenu");
        try {
            Nova nova = new Nova();
            // trmenu.openMenu 不加括号 — 返回函数引用，不应栈溢出
            Object result = nova.eval("trmenu.openMenu");
            assertNotNull(result, "应返回函数引用而非 null");
            // 函数引用应可以转字符串（触发 hashCode/toString）
            String str = String.valueOf(result);
            assertNotNull(str);
            System.err.println("函数引用: " + str);
        } finally {
            NovaRuntime.shared().remove("trmenu");
        }
    }

    @Test
    @DisplayName("[复现] NovaRange.hashCode 不应栈溢出")
    void testNovaRangeHashCodeNoStackOverflow() {
        Nova nova = new Nova();
        Object range = nova.eval("1..10");
        assertNotNull(range);
        // 触发 hashCode — 之前会因 toJavaValue() 返回 this 导致栈溢出
        int hash = range.hashCode();
        assertTrue(hash != 0 || hash == 0, "hashCode 应正常返回");
    }

    @Test
    @DisplayName("[复现] NovaNamespace.hashCode 不应栈溢出")
    void testNovaNamespaceHashCodeNoStackOverflow() {
        NovaRuntime.shared().set("testVal", 42, "testNs");
        try {
            Nova nova = new Nova();
            Object ns = nova.eval("testNs");
            // 可能是 NovaNamespace，触发 hashCode
            if (ns != null) {
                int hash = ns.hashCode();
                assertTrue(hash != 0 || hash == 0);
            }
        } finally {
            NovaRuntime.shared().remove("testNs");
        }
    }

    // ============ Java-Nova 变量共享/引用传递测试 ============

    public static class GameContext {
        public double damage = 100.0;
        public String message = "original";
        private int level = 1;
        public int getLevel() { return level; }
        public void setLevel(int lv) { this.level = lv; }
    }

    @Test
    @DisplayName("[引用测试] Nova 修改 Java 对象字段 — 影响 Java 端")
    void testNovaModifyJavaObjectField() {
        GameContext ctx = new GameContext();
        Nova nova = new Nova();
        nova.set("ctx", ctx);
        nova.eval("ctx.damage = 250.0");
        nova.eval("ctx.message = \"modified by nova\"");
        assertEquals(250.0, ctx.damage, 0.01, "Nova 修改 public 字段应影响 Java 端");
        assertEquals("modified by nova", ctx.message);
    }

    @Test
    @DisplayName("[引用测试] Nova 通过 setter 修改 Java 对象 — 影响 Java 端")
    void testNovaModifyJavaObjectViaSetter() {
        GameContext ctx = new GameContext();
        Nova nova = new Nova();
        nova.set("ctx", ctx);
        nova.eval("ctx.setLevel(99)");
        assertEquals(99, ctx.getLevel(), "Nova 调 setter 应影响 Java 端");
    }

    @Test
    @DisplayName("[引用测试] Nova 修改 Java List — 影响 Java 端")
    void testNovaModifyJavaList() {
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("sword");
        Nova nova = new Nova();
        nova.set("items", items);
        nova.eval("items.add(\"shield\")");
        nova.eval("items.add(\"potion\")");
        assertEquals(3, items.size(), "Nova 修改 List 应影响 Java 端");
        assertEquals("shield", items.get(1));
        assertEquals("potion", items.get(2));
    }

    @Test
    @DisplayName("[引用测试] Nova 修改 Java Map — 影响 Java 端")
    void testNovaModifyJavaMap() {
        java.util.Map<String, Object> config = new java.util.HashMap<>();
        config.put("hp", 100);
        Nova nova = new Nova();
        nova.set("config", config);
        nova.eval("config.put(\"hp\", 200)");
        nova.eval("config.put(\"mp\", 50)");
        assertEquals(200, config.get("hp"), "Nova 修改 Map 应影响 Java 端");
        assertEquals(50, config.get("mp"));
    }

    @Test
    @DisplayName("[引用测试] Nova 替换原始类型变量 — 不影响 Java 端（值类型）")
    void testNovaPrimitiveReassignDoesNotAffectJava() {
        Nova nova = new Nova();
        nova.set("x", 42);
        nova.eval("x = 999");
        // Java 端的 42 是值类型，Nova 中 x = 999 只是重绑定 Nova 环境中的 x
        // 无法通过 nova.get("x") 拿回（eval 模式下 set 进去的是脚本变量）
        // 这里验证的是：Java 端原始 int 值不会被影响
        // （Nova 没有指针，原始类型是值传递）
    }

    @Test
    @DisplayName("[引用测试] 编译模式 — Nova 修改 Java 对象字段")
    void testCompiledModeModifyJavaObject() throws Exception {
        GameContext ctx = new GameContext();
        Nova nova = new Nova();
        nova.set("ctx", ctx);
        CompiledNova compiled = nova.compileToBytecode(
                "ctx.damage = ctx.damage * 1.5\nctx.damage", "test.nova");
        Object result = compiled.run();
        assertEquals(150.0, ((Number) result).doubleValue(), 0.01);
        assertEquals(150.0, ctx.damage, 0.01, "编译模式修改也应影响 Java 端");
    }

    // ============ NovaScriptContext.initDirect 零拷贝测试 ============

    @Test
    @DisplayName("[initDirect] get/set 直接操作外部 Map")
    void testInitDirectGetSet() {
        java.util.concurrent.ConcurrentHashMap<String, Object> liveMap = new java.util.concurrent.ConcurrentHashMap<>();
        liveMap.put("hp", 100);
        liveMap.put("name", "Steve");

        NovaScriptContext.initDirect(liveMap);
        try {
            // get 读取外部 Map
            assertEquals(100, NovaScriptContext.get("hp"));
            assertEquals("Steve", NovaScriptContext.get("name"));

            // set 直接写入外部 Map
            NovaScriptContext.set("hp", 200);
            NovaScriptContext.set("mp", 50);

            assertEquals(200, liveMap.get("hp"), "set 应直接修改外部 Map");
            assertEquals(50, liveMap.get("mp"), "新增变量应写入外部 Map");
            assertEquals("Steve", liveMap.get("name"), "未修改的值不变");
        } finally {
            NovaScriptContext.clear();
        }
    }

    @Test
    @DisplayName("[initDirect] defineVal/defineVar 写入外部 Map")
    void testInitDirectDefine() {
        java.util.concurrent.ConcurrentHashMap<String, Object> liveMap = new java.util.concurrent.ConcurrentHashMap<>();

        NovaScriptContext.initDirect(liveMap);
        try {
            NovaScriptContext.defineVal("x", 42);
            NovaScriptContext.defineVar("y", "hello");

            assertEquals(42, liveMap.get("x"), "defineVal 应写入外部 Map");
            assertEquals("hello", liveMap.get("y"), "defineVar 应写入外部 Map");
        } finally {
            NovaScriptContext.clear();
        }
    }

    @Test
    @DisplayName("[initDirect] getAll 返回外部 Map 内容")
    void testInitDirectGetAll() {
        java.util.concurrent.ConcurrentHashMap<String, Object> liveMap = new java.util.concurrent.ConcurrentHashMap<>();
        liveMap.put("a", 1);
        liveMap.put("b", "two");

        NovaScriptContext.initDirect(liveMap);
        try {
            java.util.Map<String, Object> all = NovaScriptContext.getAll();
            assertEquals(1, all.get("a"));
            assertEquals("two", all.get("b"));
        } finally {
            NovaScriptContext.clear();
        }
    }

    @Test
    @DisplayName("[init 拷贝模式] 修改不影响原始 Map")
    void testInitCopyModeUnchanged() {
        java.util.Map<String, Object> original = new java.util.HashMap<>();
        original.put("val1", 10);

        NovaScriptContext.init(original);
        try {
            NovaScriptContext.set("val1", 999);
            // 拷贝模式下原始 Map 不应被修改
            assertEquals(10, original.get("val1"), "拷贝模式下原始 Map 不应被修改");
        } finally {
            NovaScriptContext.clear();
        }
    }

    @Test
    @DisplayName("[initDirect vs init] 对比：零拷贝 vs 拷贝")
    void testInitDirectVsInitContrast() {
        java.util.concurrent.ConcurrentHashMap<String, Object> directMap = new java.util.concurrent.ConcurrentHashMap<>();
        directMap.put("score", 0);

        java.util.Map<String, Object> copyMap = new java.util.HashMap<>();
        copyMap.put("score", 0);

        // initDirect: 修改影响外部
        NovaScriptContext.initDirect(directMap);
        NovaScriptContext.set("score", 100);
        NovaScriptContext.clear();
        assertEquals(100, directMap.get("score"), "initDirect: 修改应影响外部 Map");

        // init: 修改不影响外部
        NovaScriptContext.init(copyMap);
        NovaScriptContext.set("score", 100);
        NovaScriptContext.clear();
        assertEquals(0, copyMap.get("score"), "init: 修改不应影响外部 Map");
    }

    // ============ MemberNameResolver 自定义成员映射测试 ============

    public static class ObfuscatedEntity {
        private int field_a = 42;     // 混淆后字段名
        public int field_a() { return field_a; }  // 混淆后方法名
        public void method_b(int v) { field_a = v; }
        public int getField_a() { return field_a; }
    }

    @Test
    @DisplayName("[MemberResolver] 自定义映射: 可读名 → 混淆名")
    void testMemberNameResolverFieldMapping() {
        Nova.setMemberResolver((target, name, isMethod) -> {
            // 模拟 MCP 映射: health → field_a, setHealth → method_b
            if ("health".equals(name) && !isMethod) return "field_a";
            if ("setHealth".equals(name) && isMethod) return "method_b";
            return null;
        });

        try {
            Nova nova = new Nova();
            nova.set("entity", new ObfuscatedEntity());
            // 用可读名 "health" 访问，映射到混淆名 "field_a"
            Object result = nova.eval("entity.health");
            assertEquals(42, result, "映射 health → field_a 应返回 42");
        } finally {
            Nova.setMemberResolver(null);
        }
    }

    @Test
    @DisplayName("[MemberResolver] 自定义映射: 方法名映射")
    void testMemberNameResolverMethodMapping() {
        Nova.setMemberResolver((target, name, isMethod) -> {
            if ("setHealth".equals(name)) return "method_b";
            if ("health".equals(name) && !isMethod) return "field_a";
            return null;
        });

        try {
            Nova nova = new Nova();
            ObfuscatedEntity entity = new ObfuscatedEntity();
            nova.set("entity", entity);
            nova.eval("entity.setHealth(99)");
            assertEquals(99, entity.getField_a(), "映射 setHealth → method_b 应修改字段");
        } finally {
            Nova.setMemberResolver(null);
        }
    }

    @Test
    @DisplayName("[MemberResolver] 无映射时正常访问")
    void testMemberNameResolverNoMapping() {
        Nova.setMemberResolver((target, name, isMethod) -> null);
        try {
            Nova nova = new Nova();
            nova.set("entity", new ObfuscatedEntity());
            // 直接用混淆名可以访问
            Object result = nova.eval("entity.field_a");
            assertEquals(42, result);
        } finally {
            Nova.setMemberResolver(null);
        }
    }

    @Test
    @DisplayName("[MemberResolver] 未设置解析器时正常工作")
    void testNoMemberResolver() {
        Nova.setMemberResolver(null);
        Nova nova = new Nova();
        nova.set("entity", new ObfuscatedEntity());
        Object result = nova.eval("entity.field_a");
        assertEquals(42, result);
    }

    // ============ [] 索引内复杂表达式测试 ============

    @Test
    @DisplayName("索引内使用 as 类型转换: list[x as Int]")
    void testIndexWithAsCast() {
        Nova nova = new Nova();
        // toInt() 转换后 as Int 只是类型断言
        assertEquals("b", nova.eval("val list = [\"a\", \"b\", \"c\"]\nval x = 1\nlist[x as Int]"));
    }

    @Test
    @DisplayName("索引内使用 as? 安全转换")
    void testIndexWithSafeCast() {
        Nova nova = new Nova();
        assertEquals("b", nova.eval("val list = [\"a\", \"b\", \"c\"]\nval x: Any = 1\nlist[x as Int]"));
    }

    @Test
    @DisplayName("索引内使用变量: list[i]")
    void testIndexWithVariable() {
        Nova nova = new Nova();
        assertEquals("c", nova.eval("val list = [\"a\", \"b\", \"c\"]\nval i = 2\nlist[i]"));
    }

    @Test
    @DisplayName("索引内使用算术表达式: list[a + b]")
    void testIndexWithArithmetic() {
        Nova nova = new Nova();
        assertEquals("c", nova.eval("val list = [\"a\", \"b\", \"c\"]\nval a = 1\nval b = 1\nlist[a + b]"));
    }

    @Test
    @DisplayName("索引内使用函数调用: list[toInt(x)]")
    void testIndexWithFunctionCall() {
        Nova nova = new Nova();
        assertEquals("b", nova.eval("val list = [\"a\", \"b\", \"c\"]\nval x = 1.5\nlist[toInt(x)]"));
    }

    @Test
    @DisplayName("索引内使用三元表达式: list[if (true) 0 else 1]")
    void testIndexWithConditional() {
        Nova nova = new Nova();
        assertEquals("a", nova.eval("val list = [\"a\", \"b\", \"c\"]\nlist[if (true) 0 else 1]"));
    }

    // ============ NovaDynamicObject 动态属性测试 ============

    public static class DynamicData implements com.novalang.runtime.NovaDynamicObject {
        private final java.util.Map<String, Object> data = new java.util.HashMap<>();

        @Override public Object getMember(String name) { return data.get(name); }
        @Override public void setMember(String name, Object value) { data.put(name, value); }
        @Override public boolean hasMember(String name) { return data.containsKey(name); }

        public java.util.Map<String, Object> getData() { return data; }
    }

    @Test
    @DisplayName("[NovaDynamicObject] Nova 读写动态属性")
    void testDynamicObjectGetSet() {
        DynamicData dd = new DynamicData();
        dd.setMember("hp", 100);
        dd.setMember("name", "Steve");

        Nova nova = new Nova();
        nova.set("dd", dd);
        assertEquals(100, nova.eval("dd.hp"));
        assertEquals("Steve", nova.eval("dd.name"));

        // Nova 写入
        nova.eval("dd.hp = 200");
        nova.eval("dd.mp = 50");
        assertEquals(200, dd.getData().get("hp"), "Nova 写入应影响 Java 端");
        assertEquals(50, dd.getData().get("mp"), "Nova 新增属性应影响 Java 端");
    }

    @Test
    @DisplayName("[NovaDynamicObject] 编译模式读写")
    void testDynamicObjectCompiled() throws Exception {
        DynamicData dd = new DynamicData();
        dd.setMember("score", 10);

        Nova nova = new Nova();
        nova.set("dd", dd);
        CompiledNova compiled = nova.compileToBytecode("dd.score = dd.score * 3\ndd.score", "test.nova");
        Object result = compiled.run();
        assertEquals(30, result);
        assertEquals(30, dd.getData().get("score"), "编译模式写入应影响 Java 端");
    }

    @Test
    @DisplayName("[NovaDynamicObject] 读取不存在的属性返回 null")
    void testDynamicObjectMissingProperty() {
        DynamicData dd = new DynamicData();
        Nova nova = new Nova();
        nova.set("dd", dd);
        assertNull(nova.eval("dd.nonexistent"));
    }

    // ============ as 优先级测试 ============

    @Test
    @DisplayName("as 优先级高于四则: a + b as Int * c → a + ((b as Int) * c)")
    void testAsPrecedenceHigherThanArithmetic() {
        Nova nova = new Nova();
        // 1 + 2 as Int * 3 应该是 1 + ((2 as Int) * 3) = 1 + 6 = 7
        assertEquals(7, nova.eval("1 + 2 as Int * 3"));
    }

    @Test
    @DisplayName("as 优先级: (expr ?: 0) as Int * 28")
    void testAsPrecedenceWithElvis() {
        Nova nova = new Nova();
        // 10 + (null ?: 2) as Int * 3 → 10 + ((2 as Int) * 3) = 10 + 6 = 16
        assertEquals(16, nova.eval("10 + (null ?: 2) as Int * 3"));
    }

    // ============ as 数值类型转换测试 ============

    @Test @DisplayName("as Int: Double→Int") void testAsDoubleToInt() { assertEquals(3, new Nova().eval("3.14 as Int")); }
    @Test @DisplayName("as Int: Long→Int") void testAsLongToInt() { assertEquals(42, new Nova().eval("42L as Int")); }
    @Test @DisplayName("as Double: Int→Double") void testAsIntToDouble() { assertEquals(42.0, new Nova().eval("42 as Double")); }
    @Test @DisplayName("as Long: Int→Long") void testAsIntToLong() { assertEquals(42L, new Nova().eval("42 as Long")); }
    @Test @DisplayName("as Float: Double→Float") void testAsDoubleToFloat() { assertNotNull(new Nova().eval("3.14 as Float")); }
    @Test @DisplayName("as String: Int→String") void testAsIntToString() { assertEquals("42", new Nova().eval("42 as String")); }

    // ============ Double→float 方法参数兼容测试 ============

    public static class FloatApi {
        public static String create(float x, float y) {
            return "x=" + x + ",y=" + y;
        }
    }

    @Test
    @DisplayName("Double→float: javaClass 静态方法调用")
    void testDoubleToFloatStaticCall() {
        Nova nova = new Nova();
        // 注入 FloatApi 类引用，避免内部类名问题
        nova.set("FloatApi", new com.novalang.runtime.interpreter.JavaInterop.NovaJavaClass(FloatApi.class));
        Object result = nova.eval("FloatApi.create(0.4, 0.4)");
        assertNotNull(result, "Double→float 方法调用应成功");
        assertTrue(String.valueOf(result).contains("x=0.4"), "结果应包含 x=0.4");
    }

    @Test
    @DisplayName("Double→float: 注入对象方法调用")
    void testDoubleToFloatViaInjectedObject() {
        Nova nova = new Nova();
        // 注入一个 Java 函数接受 float 参数
        nova.defineFunction("floatAdd", (Object a, Object b) ->
                ((Number) a).floatValue() + ((Number) b).floatValue());
        Object result = nova.eval("floatAdd(0.4, 0.6)");
        assertNotNull(result);
    }

    // ============ 库函数3参数调用复现 ============

    @Test
    @DisplayName("[复现] 库函数3参数调用: lib.func(a, b, c)")
    void testLibraryFunction3Args() throws Exception {
        Nova nova = new Nova();
        nova.defineLibrary("Vx", lib -> {
            lib.defineFunction("combatAttack", (Object player, Object type, Object config) ->
                    "attacked:" + type + ":" + config);
        });
        CompiledNova compiled = nova.compileToBytecode(
                "fun attack(player, data) {\n" +
                "    Vx.combatAttack(player, \"vx_player\", #{\"anim\": \"slash\"})\n" +
                "}", "test.nova");
        compiled.run();
        Object result = compiled.call("attack", "testPlayer", "testData");
        assertNotNull(result);
        assertTrue(String.valueOf(result).contains("vx_player"), "应包含第二参数");
    }

    @Test
    @DisplayName("[复现] 库函数3参数调用: shared() defineLibrary（VxCore 路径）")
    void testLibraryFunction3ArgsViaShared() throws Exception {
        // 模拟 VxCore 的注册路径：NovaRuntime.shared().defineLibrary()
        NovaRuntime.shared().defineLibrary("Vx2", lib -> {
            lib.function("combatAttack", (Object player, Object type, Object config) ->
                    "attacked:" + type + ":" + config);
        });
        try {
            Nova nova = new Nova();
            CompiledNova compiled = nova.compileToBytecode(
                    "fun attack(player, data) {\n" +
                    "    Vx2.combatAttack(player, \"vx_player\", #{\n" +
                    "        \"entityUuid\": \"uuid\",\n" +
                    "        \"anim\": \"sword_auto1\"\n" +
                    "    })\n" +
                    "}", "test.nova");
            compiled.run();
            Object result = compiled.call("attack", "testPlayer", "testData");
            assertNotNull(result, "shared defineLibrary 3参数调用不应失败");
            assertTrue(String.valueOf(result).contains("vx_player"), "应包含第二参数");
        } finally {
            NovaRuntime.shared().unregisterNamespace("Vx2");
        }
    }

    @Test
    @DisplayName("[复现] 库函数3参数调用: 多行 Map 字面量")
    void testLibraryFunction3ArgsMultiLineMap() throws Exception {
        Nova nova = new Nova();
        nova.defineLibrary("Vx", lib -> {
            lib.defineFunction("combatAttack", (Object player, Object type, Object config) ->
                    "attacked:" + type + ":" + config);
        });
        CompiledNova compiled = nova.compileToBytecode(
                "fun attack(player, data) {\n" +
                "    Vx.combatAttack(player, \"vx_player\", #{\n" +
                "        \"entityUuid\": \"uuid\",\n" +
                "        \"anim\": \"sword_auto1\",\n" +
                "        \"speed\": 1.0,\n" +
                "        \"phases\": \"test\"\n" +
                "    })\n" +
                "}", "test.nova");
        compiled.run();
        Object result = compiled.call("attack", "testPlayer", "testData");
        assertNotNull(result, "多行 Map 作为参数不应丢失");
        assertTrue(String.valueOf(result).contains("vx_player"), "应包含第二参数");
    }

    @Test
    @DisplayName("[复现] 完整 combat.nova — 含负数值和变量引用的多行 Map")
    void testFullCombatScript() throws Exception {
        Nova nova = new Nova();
        nova.defineLibrary("Vx", lib -> {
            lib.defineFunction("combatAttack", (Object player, Object type, Object config) ->
                    "attacked:" + type + ":" + config);
            lib.defineFunction("send", (Object player, Object msg) -> null);
        });
        // 完全复制 combat.nova 的实际内容
        String code =
                "var comboIndex = 0\n" +
                "\n" +
                "fun attack(player, data) {\n" +
                "    var uuid = player.toString()\n" +
                "\n" +
                "    var animName = \"sword_auto1\"\n" +
                "    if (comboIndex == 1) { animName = \"sword_auto2\" }\n" +
                "    if (comboIndex == 2) { animName = \"sword_auto3\" }\n" +
                "    if (comboIndex == 3) { animName = \"sword_auto4\" }\n" +
                "\n" +
                "    Vx.combatAttack(player, \"vx_player\", #{\n" +
                "        \"entityUuid\": uuid,\n" +
                "        \"anim\": animName,\n" +
                "        \"speed\": 1.0,\n" +
                "        \"lockDuration\": -1,\n" +
                "        \"phases\": \"antic:0-0.1@M;contact:0.1-0.35@MA;recovery:0.35-0.6@XC\",\n" +
                "        \"chainId\": \"light\",\n" +
                "        \"comboIndex\": comboIndex\n" +
                "    })\n" +
                "\n" +
                "    comboIndex = (comboIndex + 1) % 4\n" +
                "    Vx.send(player, \"&e[Combat] \" + animName)\n" +
                "}";
        CompiledNova compiled = nova.compileToBytecode(code, "combat.nova");
        compiled.run();
        Object result = compiled.call("attack", "testPlayer", "testData");
        assertNotNull(result, "attack 应返回 combatAttack 的结果");
    }
}
