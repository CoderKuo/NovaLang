package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.*;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语义分析器类型推断测试：双向类型推断、泛型推断、类型兼容性诊断。
 */
class SemanticAnalyzerTypeInferenceTest {

    // ============ 测试辅助方法 ============

    private AnalysisResult analyze(String source) {
        Lexer lexer = new Lexer(source, "<test>");
        Parser parser = new Parser(lexer, "<test>");
        Program program = parser.parse();
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        return analyzer.analyze(program);
    }

    /** 从全局作用域查找符号的 NovaType */
    private NovaType symbolType(AnalysisResult result, String name) {
        Symbol sym = result.getSymbolTable().getGlobalScope().resolve(name);
        assertNotNull(sym, "符号 '" + name + "' 未在全局作用域中找到");
        return sym.getResolvedNovaType();
    }

    /** 断言符号类型的 displayString */
    private void assertSymbolType(AnalysisResult result, String name, String expectedDisplay) {
        NovaType type = symbolType(result, name);
        assertNotNull(type, "符号 '" + name + "' 的 NovaType 为 null");
        assertEquals(expectedDisplay, type.toDisplayString(),
                "符号 '" + name + "' 类型推断结果");
    }

    /** 断言没有 WARNING 或 ERROR 诊断 */
    private void assertNoDiagnostics(AnalysisResult result) {
        List<SemanticDiagnostic> diags = result.getDiagnostics();
        if (!diags.isEmpty()) {
            StringBuilder sb = new StringBuilder("不应有诊断，但发现 " + diags.size() + " 条:\n");
            for (SemanticDiagnostic d : diags) {
                sb.append("  ").append(d.getSeverity()).append(": ").append(d.getMessage()).append('\n');
            }
            fail(sb.toString());
        }
    }

    /** 断言存在包含指定消息的 WARNING 诊断 */
    private void assertHasWarning(AnalysisResult result, String messageSubstring) {
        for (SemanticDiagnostic d : result.getDiagnostics()) {
            if (d.getSeverity() == SemanticDiagnostic.Severity.WARNING
                    && d.getMessage().contains(messageSubstring)) {
                return;
            }
        }
        StringBuilder sb = new StringBuilder("未找到包含 '" + messageSubstring + "' 的 WARNING。实际诊断:\n");
        for (SemanticDiagnostic d : result.getDiagnostics()) {
            sb.append("  ").append(d.getSeverity()).append(": ").append(d.getMessage()).append('\n');
        }
        fail(sb.toString());
    }

    // ============ 集合工厂函数泛型推断 ============

    @Nested
    @DisplayName("集合工厂泛型推断")
    class CollectionFactoryInference {

        @Test
        @DisplayName("listOf(Int...) → List<Int>")
        void listOfInts() {
            AnalysisResult r = analyze("val list = listOf(1, 2, 3)");
            assertSymbolType(r, "list", "List<Int>");
        }

        @Test
        @DisplayName("listOf(String...) → List<String>")
        void listOfStrings() {
            AnalysisResult r = analyze("val list = listOf(\"a\", \"b\", \"c\")");
            assertSymbolType(r, "list", "List<String>");
        }

        @Test
        @DisplayName("listOf(Boolean...) → List<Boolean>")
        void listOfBooleans() {
            AnalysisResult r = analyze("val list = listOf(true, false)");
            assertSymbolType(r, "list", "List<Boolean>");
        }

        @Test
        @DisplayName("listOf() 空列表 → List<Any>")
        void listOfEmpty() {
            AnalysisResult r = analyze("val list = listOf()");
            assertSymbolType(r, "list", "List<Any>");
        }

        @Test
        @DisplayName("listOf(Int, Long) 数值提升 → List<Long>")
        void listOfNumericPromotion() {
            AnalysisResult r = analyze("val list = listOf(1, 2L)");
            assertSymbolType(r, "list", "List<Long>");
        }

        @Test
        @DisplayName("listOf(Int, Double) 数值提升 → List<Double>")
        void listOfNumericPromotionDouble() {
            AnalysisResult r = analyze("val list = listOf(1, 1.5)");
            assertSymbolType(r, "list", "List<Double>");
        }

        @Test
        @DisplayName("listOf(Int, String) 不同类型 → List<Any>")
        void listOfMixedTypes() {
            AnalysisResult r = analyze("val list = listOf(1, \"hello\")");
            assertSymbolType(r, "list", "List<Any>");
        }

        @Test
        @DisplayName("setOf(String...) → Set<String>")
        void setOfStrings() {
            AnalysisResult r = analyze("val s = setOf(\"x\", \"y\", \"z\")");
            assertSymbolType(r, "s", "Set<String>");
        }

        @Test
        @DisplayName("mutableListOf(Int...) → List<Int>")
        void mutableListOfInts() {
            AnalysisResult r = analyze("val list = mutableListOf(10, 20)");
            assertSymbolType(r, "list", "List<Int>");
        }

        @Test
        @DisplayName("mutableSetOf(Double...) → Set<Double>")
        void mutableSetOfDoubles() {
            AnalysisResult r = analyze("val s = mutableSetOf(1.0, 2.0)");
            assertSymbolType(r, "s", "Set<Double>");
        }

        @Test
        @DisplayName("arrayOf(Int...) → Array<Int>")
        void arrayOfInts() {
            AnalysisResult r = analyze("val arr = arrayOf(1, 2, 3)");
            assertSymbolType(r, "arr", "Array<Int>");
        }
    }

    // ============ mapOf 泛型推断 ============

    @Nested
    @DisplayName("mapOf 泛型推断")
    class MapFactoryInference {

        @Test
        @DisplayName("mapOf(String to Int) → Map<String, Int>")
        void mapOfStringToInt() {
            AnalysisResult r = analyze("val m = mapOf(\"a\" to 1, \"b\" to 2)");
            assertSymbolType(r, "m", "Map<String, Int>");
        }

        @Test
        @DisplayName("mapOf(Int to String) → Map<Int, String>")
        void mapOfIntToString() {
            AnalysisResult r = analyze("val m = mapOf(1 to \"x\", 2 to \"y\")");
            assertSymbolType(r, "m", "Map<Int, String>");
        }

        @Test
        @DisplayName("mapOf() 空 → Map<Any, Any>")
        void mapOfEmpty() {
            AnalysisResult r = analyze("val m = mapOf()");
            assertSymbolType(r, "m", "Map<Any, Any>");
        }

        @Test
        @DisplayName("mapOf 混合 value 类型 → Map<String, Any>")
        void mapOfMixedValues() {
            AnalysisResult r = analyze("val m = mapOf(\"a\" to 1, \"b\" to \"hello\")");
            assertSymbolType(r, "m", "Map<String, Any>");
        }
    }

    // ============ to 表达式类型推断 ============

    @Nested
    @DisplayName("to 表达式 Pair 类型推断")
    class PairInference {

        @Test
        @DisplayName("String to Int → Pair<String, Int>")
        void stringToInt() {
            AnalysisResult r = analyze("val p = \"key\" to 42");
            assertSymbolType(r, "p", "Pair<String, Int>");
        }

        @Test
        @DisplayName("Int to Boolean → Pair<Int, Boolean>")
        void intToBoolean() {
            AnalysisResult r = analyze("val p = 1 to true");
            assertSymbolType(r, "p", "Pair<Int, Boolean>");
        }
    }

    // ============ 泛型函数返回类型推断 ============

    @Nested
    @DisplayName("泛型函数类型参数推断")
    class GenericFunctionInference {

        @Test
        @DisplayName("fun <T> identity(x: T): T → identity(42) 推断为 Int")
        void identityInt() {
            AnalysisResult r = analyze(
                    "fun <T> identity(x: T): T = x\n" +
                    "val n = identity(42)");
            assertSymbolType(r, "n", "Int");
        }

        @Test
        @DisplayName("fun <T> identity(x: T): T → identity(\"hello\") 推断为 String")
        void identityString() {
            AnalysisResult r = analyze(
                    "fun <T> identity(x: T): T = x\n" +
                    "val s = identity(\"hello\")");
            assertSymbolType(r, "s", "String");
        }

        @Test
        @DisplayName("fun <T> first(a: T, b: T): T → first(1, 2L) 推断为 Long（数值提升）")
        void firstNumericPromotion() {
            AnalysisResult r = analyze(
                    "fun <T> first(a: T, b: T): T = a\n" +
                    "val n = first(1, 2L)");
            assertSymbolType(r, "n", "Long");
        }

        @Test
        @DisplayName("fun <T> first(a: T, b: T): T → first(1, \"x\") 推断为 Any")
        void firstMixedTypes() {
            AnalysisResult r = analyze(
                    "fun <T> first(a: T, b: T): T = a\n" +
                    "val n = first(1, \"x\")");
            assertSymbolType(r, "n", "Any");
        }

        @Test
        @DisplayName("泛型函数返回可空类型 fun <T> nullable(x: T): T? → nullable(42) 推断为 Int?")
        void genericNullableReturn() {
            AnalysisResult r = analyze(
                    "fun <T> nullable(x: T): T? = x\n" +
                    "val n = nullable(42)");
            assertSymbolType(r, "n", "Int?");
        }
    }

    // ============ 泛型构造器类型推断 ============

    @Nested
    @DisplayName("泛型构造器类型参数推断")
    class GenericConstructorInference {

        @Test
        @DisplayName("class Box<T>(val value: T) → Box(42) 推断为 Box<Int>")
        void boxInt() {
            AnalysisResult r = analyze(
                    "class Box<T>(val value: T)\n" +
                    "val box = Box(42)");
            assertSymbolType(r, "box", "Box<Int>");
        }

        @Test
        @DisplayName("class Box<T>(val value: T) → Box(\"hello\") 推断为 Box<String>")
        void boxString() {
            AnalysisResult r = analyze(
                    "class Box<T>(val value: T)\n" +
                    "val box = Box(\"hello\")");
            assertSymbolType(r, "box", "Box<String>");
        }

        @Test
        @DisplayName("class Pair<A, B>(val first: A, val second: B) → Pair(1, \"x\")")
        void pairMultiTypeParams() {
            AnalysisResult r = analyze(
                    "class MyPair<A, B>(val first: A, val second: B)\n" +
                    "val p = MyPair(1, \"x\")");
            assertSymbolType(r, "p", "MyPair<Int, String>");
        }

        @Test
        @DisplayName("class Wrapper<T>(val items: List<T>) → 未匹配则回退 Any")
        void constructorNoDirectTypeParam() {
            // items 的类型是 List<T>，实参是 listOf(1) → List<Int>
            // collectBindings 应递归匹配 List<T> vs List<Int> → T=Int
            AnalysisResult r = analyze(
                    "class Wrapper<T>(val items: List<T>)\n" +
                    "val w = Wrapper(listOf(1, 2))");
            assertSymbolType(r, "w", "Wrapper<Int>");
        }
    }

    // ============ 显式类型注解保留 ============

    @Nested
    @DisplayName("显式类型注解")
    class ExplicitTypeAnnotation {

        @Test
        @DisplayName("val list: List<Int> = listOf(1, 2, 3) 使用声明类型")
        void explicitListType() {
            AnalysisResult r = analyze("val list: List<Int> = listOf(1, 2, 3)");
            NovaType type = symbolType(r, "list");
            assertNotNull(type);
            // 声明了显式类型时应使用声明的类型
            assertEquals("List<Int>", type.toDisplayString());
        }

        @Test
        @DisplayName("val n: Int = 42 基本类型")
        void explicitPrimitiveType() {
            AnalysisResult r = analyze("val n: Int = 42");
            assertSymbolType(r, "n", "Int");
        }

        @Test
        @DisplayName("val s: String? = null 可空类型")
        void explicitNullableType() {
            AnalysisResult r = analyze("val s: String? = null");
            assertSymbolType(r, "s", "String?");
        }
    }

    // ============ 类型兼容性诊断 ============

    @Nested
    @DisplayName("类型兼容性检查")
    class TypeCompatibilityDiagnostics {

        @Test
        @DisplayName("val x: Int = \"hello\" → WARNING 类型不匹配")
        void intAssignString() {
            AnalysisResult r = analyze("val x: Int = \"hello\"");
            assertHasWarning(r, "类型不匹配");
        }

        @Test
        @DisplayName("val s: String = null → WARNING 非空类型赋 null")
        void nonNullAssignNull() {
            AnalysisResult r = analyze("val s: String = null");
            assertHasWarning(r, "类型不匹配");
        }

        @Test
        @DisplayName("val s: String? = null → 无警告")
        void nullableAssignNull() {
            AnalysisResult r = analyze("val s: String? = null");
            assertNoDiagnostics(r);
        }

        @Test
        @DisplayName("val n: Int = 42 → 无警告")
        void correctAssignment() {
            AnalysisResult r = analyze("val n: Int = 42");
            assertNoDiagnostics(r);
        }

        @Test
        @DisplayName("val n: Long = 42 → 无警告（数值拓宽）")
        void numericWidening() {
            AnalysisResult r = analyze("val n: Long = 42");
            assertNoDiagnostics(r);
        }

        @Test
        @DisplayName("val d: Double = 1 → 无警告（数值拓宽）")
        void numericWideningIntToDouble() {
            AnalysisResult r = analyze("val d: Double = 1");
            assertNoDiagnostics(r);
        }
    }

    // ============ Variance 位置检查 ============

    @Nested
    @DisplayName("Variance 位置检查")
    class VarianceChecks {

        @Test
        @DisplayName("class Producer<out T> { fun get(): T } → 无警告（协变正确位置）")
        void outInCovariantPosition() {
            AnalysisResult r = analyze(
                    "class Producer<out T>(val value: T) {\n" +
                    "    fun get(): T = value\n" +
                    "}");
            assertNoDiagnostics(r);
        }

        @Test
        @DisplayName("class Consumer<in T> { fun accept(t: T) } → 无警告（逆变正确位置）")
        void inInContravariantPosition() {
            AnalysisResult r = analyze(
                    "class Consumer<in T> {\n" +
                    "    fun accept(t: T) { }\n" +
                    "}");
            assertNoDiagnostics(r);
        }

        @Test
        @DisplayName("class Bad<out T> { fun put(t: T) } → WARNING（out 出现在逆变位置）")
        void outInContravariantPosition() {
            AnalysisResult r = analyze(
                    "class Bad<out T> {\n" +
                    "    fun put(t: T) { }\n" +
                    "}");
            assertHasWarning(r, "out");
        }

        @Test
        @DisplayName("class Bad<in T> { fun get(): T } → WARNING（in 出现在协变位置）")
        void inInCovariantPosition() {
            AnalysisResult r = analyze(
                    "class Bad<in T> {\n" +
                    "    fun get(): T = error(\"\")\n" +
                    "}");
            assertHasWarning(r, "in");
        }
    }

    // ============ commonSuperType 间接测试 ============

    @Nested
    @DisplayName("commonSuperType 通过集合推断间接验证")
    class CommonSuperType {

        @Test
        @DisplayName("listOf(1, 2, 3) 全同类型 → List<Int>")
        void allSameType() {
            AnalysisResult r = analyze("val list = listOf(1, 2, 3)");
            assertSymbolType(r, "list", "List<Int>");
        }

        @Test
        @DisplayName("listOf(1, 2.0) Int+Double → List<Double>")
        void intPlusDouble() {
            AnalysisResult r = analyze("val list = listOf(1, 2.0)");
            assertSymbolType(r, "list", "List<Double>");
        }

        @Test
        @DisplayName("listOf(1.0f, 2.0) Float+Double → List<Double>")
        void floatPlusDouble() {
            AnalysisResult r = analyze("val list = listOf(1.0f, 2.0)");
            assertSymbolType(r, "list", "List<Double>");
        }

        @Test
        @DisplayName("listOf(1, 2L, 3.0f) Int+Long+Float → List<Float>")
        void intLongFloat() {
            AnalysisResult r = analyze("val list = listOf(1, 2L, 3.0f)");
            assertSymbolType(r, "list", "List<Float>");
        }

        @Test
        @DisplayName("listOf(true, 1) Boolean+Int → List<Any>")
        void booleanPlusInt() {
            AnalysisResult r = analyze("val list = listOf(true, 1)");
            assertSymbolType(r, "list", "List<Any>");
        }
    }
}
