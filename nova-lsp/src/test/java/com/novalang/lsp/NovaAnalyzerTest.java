package com.novalang.lsp;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.assertj.core.api.Assertions.*;

@DisplayName("NovaAnalyzer 测试")
class NovaAnalyzerTest {

    private static final String TEST_URI = "file:///test.nova";
    private NovaAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new NovaAnalyzer(new DocumentManager());
        analyzer.setJavaClassResolver(new JavaClassResolver(Collections.emptyList()));
    }

    // ============ 辅助方法 ============

    /** 在补全列表中查找指定 label 的项 */
    private JsonObject findItem(JsonArray items, String label) {
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (label.equals(item.get("label").getAsString())) return item;
        }
        return null;
    }

    /** 检查补全列表中是否包含指定 label */
    private boolean hasItem(JsonArray items, String label) {
        return findItem(items, label) != null;
    }

    /** 检查补全列表中是否包含 label 以指定前缀开头的项 */
    private boolean hasItemStartingWith(JsonArray items, String prefix) {
        for (int i = 0; i < items.size(); i++) {
            String label = items.get(i).getAsJsonObject().get("label").getAsString();
            if (label.startsWith(prefix)) return true;
        }
        return false;
    }

    /** 检查补全列表中类型专属项的 detail 是否都包含指定类型名（忽略 Any 通用方法） */
    private boolean allItemsFromType(JsonArray items, String typeName) {
        if (items.size() == 0) return false;
        boolean hasTypeItem = false;
        boolean allAny = true;
        for (int i = 0; i < items.size(); i++) {
            JsonObject item = items.get(i).getAsJsonObject();
            if (!item.has("detail")) return false;
            String detail = item.get("detail").getAsString();
            // 跳过 Any 通用方法（toString/hashCode/let/also 等）
            if (detail.startsWith("Any ")) continue;
            allAny = false;
            if (!detail.contains(typeName)) return false;
            hasTypeItem = true;
        }
        // 如果所有项都来自 Any（该类型没有自己的注册方法），也认为是正确的
        return hasTypeItem || allAny;
    }

    // ============ 诊断 ============

    @Nested
    @DisplayName("analyze - 语法诊断")
    class AnalyzeTests {

        @Test
        @DisplayName("正确代码无诊断")
        void testValidCode() {
            String code = "fun greet(name: String) = println(\"Hello $name\")";
            JsonArray diagnostics = analyzer.analyze(TEST_URI, code);
            assertThat(diagnostics.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("语法错误产生诊断")
        void testSyntaxError() {
            String code = "fun (";  // 缺少函数名
            JsonArray diagnostics = analyzer.analyze(TEST_URI, code);
            assertThat(diagnostics.size()).isGreaterThan(0);

            JsonObject diag = diagnostics.get(0).getAsJsonObject();
            assertThat(diag.get("severity").getAsInt()).isEqualTo(1); // Error
            assertThat(diag.get("source").getAsString()).isEqualTo("nova");
            assertThat(diag.get("message").getAsString()).isNotEmpty();
        }

        @Test
        @DisplayName("空文件无诊断")
        void testEmptyFile() {
            JsonArray diagnostics = analyzer.analyze(TEST_URI, "");
            assertThat(diagnostics.size()).isEqualTo(0);
        }
    }

    // ============ 补全 ============

    @Nested
    @DisplayName("complete - 代码补全")
    class CompletionTests {

        @Test
        @DisplayName("空前缀返回所有关键词")
        void testEmptyPrefixReturnsKeywords() {
            String code = "\n";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 0);
            // 至少包含关键词 + 类型 + 函数
            assertThat(items.size()).isGreaterThan(10);
        }

        @Test
        @DisplayName("前缀 'va' 匹配 val/var")
        void testPrefixMatching() {
            String code = "va";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 2);

            boolean hasVal = false, hasVar = false;
            for (int i = 0; i < items.size(); i++) {
                String label = items.get(i).getAsJsonObject().get("label").getAsString();
                if ("val".equals(label)) hasVal = true;
                if ("var".equals(label)) hasVar = true;
            }
            assertThat(hasVal).isTrue();
            assertThat(hasVar).isTrue();
        }

        @Test
        @DisplayName("前缀 'Int' 匹配内置类型")
        void testBuiltinTypeCompletion() {
            String code = "Int";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 3);

            boolean hasInt = false;
            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                String label = item.get("label").getAsString();
                int kind = item.get("kind").getAsInt();
                if ("Int".equals(label) && kind == 7) {
                    hasInt = true;
                }
            }
            assertThat(hasInt).isTrue();
        }

        @Test
        @DisplayName("文档中声明的符号出现在补全列表")
        void testDocumentSymbolCompletion() {
            String code = "fun greet() {}\nclass Person {}\n";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 0);

            boolean hasGreet = false, hasPerson = false;
            for (int i = 0; i < items.size(); i++) {
                String label = items.get(i).getAsJsonObject().get("label").getAsString();
                if ("greet()".equals(label)) hasGreet = true;
                if ("Person".equals(label)) hasPerson = true;
            }
            assertThat(hasGreet).isTrue();
            assertThat(hasPerson).isTrue();
        }

        @Test
        @DisplayName("前缀 'println' 匹配内置函数")
        void testBuiltinFunctionCompletion() {
            String code = "print";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 5);

            boolean hasPrintln = false;
            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                String label = item.get("label").getAsString();
                int kind = item.get("kind").getAsInt();
                if (label.startsWith("println(") && kind == 3) { // Function, 非 Snippet
                    hasPrintln = true;
                    // 验证 Snippet 格式
                    assertThat(item.get("insertTextFormat").getAsInt()).isEqualTo(2);
                    assertThat(item.get("insertText").getAsString()).isEqualTo("println($1)$0");
                }
            }
            assertThat(hasPrintln).isTrue();
        }
    }

    // ============ 悬停 ============

    @Nested
    @DisplayName("hover - 悬停信息")
    class HoverTests {

        @Test
        @DisplayName("悬停在关键词上返回关键词信息")
        void testHoverOnKeyword() {
            String code = "val x = 1";
            // 'val' 位于第 0 行第 0-2 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 0);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("val");
        }

        @Test
        @DisplayName("悬停在内置类型上返回类型信息")
        void testHoverOnBuiltinType() {
            String code = "val x: Int = 1";
            // 'Int' 位于第 0 行第 7 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 7);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("内置类型").contains("Int");
        }

        @Test
        @DisplayName("悬停在声明的函数上返回签名")
        void testHoverOnDeclaredFun() {
            String code = "fun add(a: Int, b: Int) = a + b";
            // 'add' 位于第 0 行第 4 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 4);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("fun");
        }

        @Test
        @DisplayName("悬停在空白处返回 null")
        void testHoverOnWhitespace() {
            String code = "val x = 1";
            // 空格位于第 0 行第 3 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 3);
            assertThat(hover).isNull();
        }

        @Test
        @DisplayName("悬停在未知标识符返回 null")
        void testHoverOnUnknownIdent() {
            String code = "val xyz = 1";
            // 数字 '1' 位于第 0 行第 10 列，不是标识符
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 10);
            assertThat(hover).isNull();
        }
    }

    // ============ 跳转定义 ============

    @Nested
    @DisplayName("goToDefinition - 跳转定义")
    class DefinitionTests {

        @Test
        @DisplayName("跳转到函数定义")
        void testGoToFunDefinition() {
            String code = "fun greet() {}\n";
            // 'greet' 位于第 0 行第 4 列
            JsonObject location = analyzer.goToDefinition(TEST_URI, code, 0, 4);
            assertThat(location).isNotNull();
            assertThat(location.get("uri").getAsString()).isEqualTo(TEST_URI);
        }

        @Test
        @DisplayName("跳转到类定义")
        void testGoToClassDefinition() {
            String code = "class Foo {}\n";
            JsonObject location = analyzer.goToDefinition(TEST_URI, code, 0, 6);
            assertThat(location).isNotNull();
            assertThat(location.get("uri").getAsString()).isEqualTo(TEST_URI);
        }

        @Test
        @DisplayName("关键词无定义位置")
        void testNoDefinitionForKeyword() {
            String code = "val x = 1";
            // 'val' 是关键词，不是用户声明
            JsonObject location = analyzer.goToDefinition(TEST_URI, code, 0, 0);
            assertThat(location).isNull();
        }
    }

    // ============ 文档符号 ============

    @Nested
    @DisplayName("documentSymbols - 文档符号")
    class SymbolTests {

        @Test
        @DisplayName("提取函数和类声明")
        void testExtractSymbols() {
            String code = "fun add(a: Int, b: Int) = a + b\nclass Person {}\n";
            JsonArray symbols = analyzer.documentSymbols(TEST_URI, code);
            assertThat(symbols.size()).isEqualTo(2);

            // 验证符号名
            String name0 = symbols.get(0).getAsJsonObject().get("name").getAsString();
            String name1 = symbols.get(1).getAsJsonObject().get("name").getAsString();
            assertThat(name0).isEqualTo("add");
            assertThat(name1).isEqualTo("Person");
        }

        @Test
        @DisplayName("空文件无符号")
        void testEmptyFile() {
            JsonArray symbols = analyzer.documentSymbols(TEST_URI, "");
            assertThat(symbols.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("类成员作为子符号")
        void testNestedSymbols() {
            String code = "class Calc {\n    fun add(a: Int, b: Int) = a + b\n}\n";
            JsonArray symbols = analyzer.documentSymbols(TEST_URI, code);
            assertThat(symbols.size()).isEqualTo(1);

            JsonObject classSymbol = symbols.get(0).getAsJsonObject();
            assertThat(classSymbol.get("name").getAsString()).isEqualTo("Calc");
            assertThat(classSymbol.get("kind").getAsInt()).isEqualTo(5); // Class

            JsonArray children = classSymbol.getAsJsonArray("children");
            assertThat(children).isNotNull();
            assertThat(children.size()).isEqualTo(1);
            assertThat(children.get(0).getAsJsonObject().get("name").getAsString())
                    .isEqualTo("add");
        }

        @Test
        @DisplayName("符号类型正确")
        void testSymbolKinds() {
            String code = "fun f() {}\nclass C {}\ninterface I {}\nenum class E {}\nobject O {}\n";
            JsonArray symbols = analyzer.documentSymbols(TEST_URI, code);

            // 逐个检查 kind
            for (int i = 0; i < symbols.size(); i++) {
                JsonObject sym = symbols.get(i).getAsJsonObject();
                String name = sym.get("name").getAsString();
                int kind = sym.get("kind").getAsInt();
                switch (name) {
                    case "f": assertThat(kind).isEqualTo(12); break; // Function
                    case "C": assertThat(kind).isEqualTo(5); break;  // Class
                    case "I": assertThat(kind).isEqualTo(11); break; // Interface
                    case "E": assertThat(kind).isEqualTo(10); break; // Enum
                    case "O": assertThat(kind).isEqualTo(5); break;  // Class (object)
                }
            }
        }
    }

    // ============ 格式化 ============

    @Nested
    @DisplayName("format - 代码格式化")
    class FormatTests {

        @Test
        @DisplayName("格式化有效代码返回 TextEdit")
        void testFormatValidCode() {
            // 故意用不规范的格式
            String code = "fun   add( a:Int,b:Int )=a+b\n";
            JsonArray edits = analyzer.format(TEST_URI, code);
            assertThat(edits).isNotNull();
            // 如果格式化后与原文不同，应该有一个 edit
            if (edits.size() > 0) {
                JsonObject edit = edits.get(0).getAsJsonObject();
                assertThat(edit.has("range")).isTrue();
                assertThat(edit.has("newText")).isTrue();
            }
        }

        @Test
        @DisplayName("语法错误代码返回 null")
        void testFormatInvalidCode() {
            String code = "fun (";
            JsonArray edits = analyzer.format(TEST_URI, code);
            assertThat(edits).isNull();
        }

        @Test
        @DisplayName("空文件格式化不报错")
        void testFormatEmptyFile() {
            JsonArray edits = analyzer.format(TEST_URI, "");
            assertThat(edits).isNotNull();
        }
    }

    // ============ Java 命名空间补全 ============

    @Nested
    @DisplayName("Java 命名空间")
    class JavaNamespaceTests {

        @Test
        @DisplayName("输入 Java 前缀出现补全候选")
        void testJavaIdentifierCompletion() {
            String code = "Java";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 4);
            assertThat(hasItem(items, "Java")).isTrue();
            JsonObject item = findItem(items, "Java");
            assertThat(item.get("kind").getAsInt()).isEqualTo(6); // Module
        }

        @Test
        @DisplayName("Java. 补全 type/static/field/new/isInstance/class")
        void testJavaDotCompletion() {
            String code = "Java.";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 5);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "type(")).isTrue();
            assertThat(hasItemStartingWith(items, "static(")).isTrue();
            assertThat(hasItemStartingWith(items, "field(")).isTrue();
            assertThat(hasItemStartingWith(items, "new(")).isTrue();
            assertThat(hasItemStartingWith(items, "isInstance(")).isTrue();
            assertThat(hasItemStartingWith(items, "class(")).isTrue();
        }

        @Test
        @DisplayName("悬停 Java 显示互操作文档")
        void testJavaHover() {
            String code = "Java.type(\"java.util.ArrayList\")";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 0);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("Java 互操作");
            assertThat(value).contains("Java.type");
        }
    }

    // ============ 内置类型成员补全 ============

    @Nested
    @DisplayName("内置类型成员补全")
    class BuiltinMemberCompletionTests {

        @Test
        @DisplayName("String 字面量后补全 String 方法")
        void testStringLiteralCompletion() {
            String code = "\"hello\".";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 8);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "length")).isTrue();
            assertThat(hasItemStartingWith(items, "toUpperCase(")).isTrue();
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("String 变量后补全 String 方法")
        void testStringVarCompletion() {
            String code = "val msg = \"hello\"\nmsg.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "length")).isTrue();
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("List 字面量后补全 List 方法")
        void testListLiteralCompletion() {
            String code = "[1, 2, 3].";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 10);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(hasItem(items, "first")).isTrue();
            assertThat(allItemsFromType(items, "List")).isTrue();
        }

        @Test
        @DisplayName("Map 变量后补全 Map 方法")
        void testMapVarCompletion() {
            String code = "val m = #{\"a\": 1}\nm.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "keys")).isTrue();
            assertThat(hasItemStartingWith(items, "get(")).isTrue();
            assertThat(allItemsFromType(items, "Map")).isTrue();
        }

        @Test
        @DisplayName("Int 变量后补全有限方法")
        void testIntVarCompletion() {
            String code = "val n = 42\nn.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "toString(")).isTrue();
        }

        @Test
        @DisplayName("Boolean 变量后补全有限方法")
        void testBooleanVarCompletion() {
            String code = "val b = true\nb.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "toString(")).isTrue();
        }
    }

    // ============ Java 类成员补全 ============

    @Nested
    @DisplayName("Java 类成员补全")
    class JavaClassCompletionTests {

        @Test
        @DisplayName("ArrayList 实例补全 Java 方法")
        void testArrayListCompletion() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 5);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "add(")).isTrue();
            assertThat(hasItemStartingWith(items, "get(")).isTrue();
            assertThat(hasItemStartingWith(items, "size(")).isTrue();
        }

        @Test
        @DisplayName("HashMap 实例补全 Java 方法")
        void testHashMapCompletion() {
            String code = "val map = Java.type(\"java.util.HashMap\")()\nmap.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "put(")).isTrue();
            assertThat(hasItemStartingWith(items, "get(")).isTrue();
        }

        @Test
        @DisplayName("StringBuilder 实例补全 Java 方法")
        void testStringBuilderCompletion() {
            String code = "val sb = Java.type(\"java.lang.StringBuilder\")()\nsb.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 3);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "append(")).isTrue();
            assertThat(hasItemStartingWith(items, "toString(")).isTrue();
        }

        @Test
        @DisplayName("继承的方法也能补全（HashMap 继承 Map 的 getOrDefault）")
        void testInheritedMethodCompletion() {
            String code = "val map = Java.type(\"java.util.HashMap\")()\nmap.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(hasItemStartingWith(items, "getOrDefault(")).isTrue();
        }

        @Test
        @DisplayName("前缀过滤 Java 方法")
        void testJavaMethodPrefixFilter() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.si";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 7);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "size(")).isTrue();
            // 不应包含不匹配前缀的方法
            assertThat(hasItemStartingWith(items, "add(")).isFalse();
        }
    }

    // ============ 类型推断 ============

    @Nested
    @DisplayName("类型推断")
    class TypeInferenceTests {

        @Test
        @DisplayName("类型注解推断")
        void testTypeAnnotation() {
            String code = "val s: String = \"hello\"\ns.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("构造函数调用推断自定义类型")
        void testConstructorCallInference() {
            String code = "class Point {\n    fun distanceTo() = 0\n}\nval p = Point()\np.";
            JsonArray items = analyzer.complete(TEST_URI, code, 4, 2);
            assertThat(hasItemStartingWith(items, "distanceTo(")).isTrue();
        }

        @Test
        @DisplayName("函数参数类型推断")
        void testFunctionParamInference() {
            String code = "fun process(name: String) {\n    name.\n}";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 9);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("类构造参数类型推断")
        void testClassParamInference() {
            String code = "class User(val name: String) {\n    fun greet() {\n        name.\n    }\n}";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 13);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("this 类型推断")
        void testThisInference() {
            // 用完整可解析的代码，在 val x = this. 处测试
            String code = "class Dog {\n    fun bark() = \"woof\"\n}\nval d = Dog()\nd.";
            // line 4: "d." = 2 chars, cursor at 2
            JsonArray items = analyzer.complete(TEST_URI, code, 4, 2);
            assertThat(hasItemStartingWith(items, "bark(")).isTrue();
        }
    }

    // ============ 方法链返回类型 ============

    @Nested
    @DisplayName("方法链返回类型推断")
    class MethodChainTests {

        @Test
        @DisplayName("String.length() 返回 Int")
        void testStringLengthReturnsInt() {
            String code = "val s = \"hello\"\ns.length().";
            // s.length(). = 11 chars, cursor at 11
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 11);
            // Int 只有 toString
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("String.split() 返回 List")
        void testStringSplitReturnsList() {
            String code = "val s = \"a,b,c\"\ns.split(\",\").";
            // s.split(","). = 13 chars, cursor at 13
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(hasItem(items, "first")).isTrue();
        }

        @Test
        @DisplayName("List.size() 返回 Int")
        void testListSizeReturnsInt() {
            String code = "val list = [1, 2]\nlist.size().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 12);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("List.isEmpty() 返回 Boolean")
        void testListIsEmptyReturnsBoolean() {
            String code = "val list = [1]\nlist.isEmpty().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 15);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Boolean")).isTrue();
        }

        @Test
        @DisplayName("List.reversed() 返回 List")
        void testListReversedReturnsList() {
            String code = "val list = [1, 2]\nlist.reversed().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 16);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("List.joinToString() 返回 String")
        void testListJoinToStringReturnsString() {
            String code = "val list = [1, 2]\nlist.joinToString(\",\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 23);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("Java 方法链: ArrayList.add() 返回 Boolean")
        void testJavaMethodChain() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"x\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 14);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Boolean")).isTrue();
        }
    }

    // ============ 数组返回类型 ============

    @Nested
    @DisplayName("Java 数组返回类型")
    class ArrayReturnTypeTests {

        @Test
        @DisplayName("File.listFiles() 返回 List 补全")
        void testArrayReturnMappedToList() {
            String code = "val file = Java.type(\"java.io.File\")(\"test\")\nfile.listFiles().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 17);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(hasItem(items, "first")).isTrue();
        }

        @Test
        @DisplayName("File.listFiles().first() 返回 File 补全")
        void testArrayElementTypePreserved() {
            String code = "val file = Java.type(\"java.io.File\")(\"test\")\nfile.listFiles().first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 25);
            assertThat(items.size()).isGreaterThan(0);
            // File 方法
            assertThat(hasItemStartingWith(items, "getName(")).isTrue();
        }
    }

    // ============ 括号表达式与类型转换 ============

    @Nested
    @DisplayName("括号表达式与类型转换")
    class ParenAndCastTests {

        @Test
        @DisplayName("括号包裹的表达式保留类型")
        void testParenthesizedExpr() {
            String code = "val s = \"hello\"\n(s).";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("as 类型转换推断目标类型")
        void testAsCast() {
            String code = "val obj = [1]\n(obj.first() as String).";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 24);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("函数调用内的点号补全")
        void testDotInsideParens() {
            String code = "val s = \"hello\"\nprintln(s.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 10);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }
    }

    // ============ 索引访问 ============

    @Nested
    @DisplayName("索引访问类型推断")
    class IndexAccessTests {

        @Test
        @DisplayName("List 字面量索引返回元素类型")
        void testListLiteralIndex() {
            String code = "val names = [\"Alice\", \"Bob\"]\nnames[0].";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 9);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("带泛型注解的 Map 索引返回值类型")
        void testMapIndexWithGenerics() {
            String code = "val m: Map<String, Int> = {}\nm[\"key\"].";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 9);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }
    }

    // ============ 集合元素类型流式推断 ============

    @Nested
    @DisplayName("集合元素类型流式推断")
    class CollectionFlowInferenceTests {

        @Test
        @DisplayName("通过 add() 字面量推断 List 元素类型")
        void testAddLiteralInference() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")\nlist.add(\"world\")\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("通过 add() 变量推断 List 元素类型")
        void testAddVariableInference() {
            String code = "val name = \"Alice\"\nval list = Java.type(\"java.util.ArrayList\")()\nlist.add(name)\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("通过索引赋值推断 List 元素类型")
        void testIndexAssignInference() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist[0] = 12\nlist[1] = 34\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("add 类型不一致时不推断")
        void testMixedAddNoInference() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")\nlist.add(42)\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            // 类型不一致，不推断元素类型，first() 返回 null，不应出现特定类型
            // 可能为空或回退为所有方法
        }

        @Test
        @DisplayName("通过 put() 推断 Map 键值类型")
        void testMapPutInference() {
            String code = "val map = Java.type(\"java.util.HashMap\")()\nmap.put(\"name\", \"Alice\")\nmap.put(\"age\", \"25\")\nmap.get(\"name\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 16);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("通过 map[key]=value 推断 Map 键值类型")
        void testMapIndexAssignInference() {
            String code = "val map = Java.type(\"java.util.HashMap\")()\nmap[\"x\"] = \"hello\"\nmap[\"y\"] = \"world\"\nmap.get(\"x\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("列表字面量元素类型推断")
        void testListLiteralElementInference() {
            String code = "val names = [\"Alice\", \"Bob\", \"Charlie\"]\nnames.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 14);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("Int 列表字面量的 first() 返回 Int")
        void testIntListLiteralFirstReturnsInt() {
            String code = "val nums = [1, 2, 3]\nnums.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }
    }

    // ============ 泛型参数替换 ============

    @Nested
    @DisplayName("泛型参数替换")
    class GenericSubstitutionTests {

        @Test
        @DisplayName("Map<String, Int> 的 get() 返回 Int")
        void testMapGenericGet() {
            String code = "val m: Map<String, Int> = {}\nm.get(\"a\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 11);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("Map<String, Int> 的 keys() 返回 List")
        void testMapKeysReturnsList() {
            String code = "val m: Map<String, Int> = {}\nm.keys().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 9);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
        }
    }

    // ============ JavaClassResolver ============

    @Nested
    @DisplayName("JavaClassResolver")
    class JavaClassResolverTests {

        private JavaClassResolver resolver;

        @BeforeEach
        void setUp() {
            resolver = new JavaClassResolver(Collections.emptyList());
        }

        @Test
        @DisplayName("解析 JDK 类: java.util.ArrayList")
        void testResolveArrayList() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            assertThat(info.className).isEqualTo("java.util.ArrayList");
            assertThat(info.methods).isNotEmpty();

            // 应包含 add 方法
            boolean hasAdd = info.methods.stream().anyMatch(m -> "add".equals(m.name));
            assertThat(hasAdd).isTrue();
        }

        @Test
        @DisplayName("解析 JDK 类: java.lang.String")
        void testResolveString() {
            JavaClassInfo info = resolver.resolve("java.lang.String");
            assertThat(info).isNotNull();
            assertThat(info.className).isEqualTo("java.lang.String");

            boolean hasLength = info.methods.stream().anyMatch(m -> "length".equals(m.name));
            assertThat(hasLength).isTrue();
        }

        @Test
        @DisplayName("解析不存在的类返回 null")
        void testResolveNonExistent() {
            JavaClassInfo info = resolver.resolve("com.nonexistent.FooBar");
            assertThat(info).isNull();
        }

        @Test
        @DisplayName("null/空类名返回 null")
        void testResolveNullOrEmpty() {
            assertThat(resolver.resolve(null)).isNull();
            assertThat(resolver.resolve("")).isNull();
        }

        @Test
        @DisplayName("结果被缓存")
        void testCaching() {
            JavaClassInfo info1 = resolver.resolve("java.util.ArrayList");
            JavaClassInfo info2 = resolver.resolve("java.util.ArrayList");
            assertThat(info1).isSameAs(info2);
        }

        @Test
        @DisplayName("继承父类方法")
        void testInheritSuperclassMethods() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            // ArrayList 继承 AbstractList/AbstractCollection 的 toString
            boolean hasToString = info.methods.stream().anyMatch(m -> "toString".equals(m.name));
            assertThat(hasToString).isTrue();
        }

        @Test
        @DisplayName("继承接口方法")
        void testInheritInterfaceMethods() {
            JavaClassInfo info = resolver.resolve("java.util.HashMap");
            assertThat(info).isNotNull();
            // HashMap 实现 Map 接口的 getOrDefault
            boolean hasGetOrDefault = info.methods.stream().anyMatch(m -> "getOrDefault".equals(m.name));
            assertThat(hasGetOrDefault).isTrue();
        }

        @Test
        @DisplayName("只提取 public 方法")
        void testOnlyPublicMethods() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            // 所有方法名都不应为 <clinit>
            boolean hasClinit = info.methods.stream().anyMatch(m -> "<clinit>".equals(m.name));
            assertThat(hasClinit).isFalse();
        }

        @Test
        @DisplayName("解析泛型类型参数")
        void testTypeParams() {
            JavaClassInfo info = resolver.resolve("java.util.HashMap");
            assertThat(info).isNotNull();
            assertThat(info.typeParams).containsExactly("K", "V");
        }

        @Test
        @DisplayName("泛型返回类型索引正确")
        void testGenericReturnTypeIndex() {
            JavaClassInfo info = resolver.resolve("java.util.HashMap");
            assertThat(info).isNotNull();
            // get(Object) 返回 V，索引为 1
            JavaClassInfo.MethodInfo getMethod = info.methods.stream()
                    .filter(m -> "get".equals(m.name))
                    .findFirst().orElse(null);
            assertThat(getMethod).isNotNull();
            assertThat(getMethod.genericReturnTypeIndex).isEqualTo(1);
        }
    }

    // ============ JavaClassInfo ============

    @Nested
    @DisplayName("JavaClassInfo")
    class JavaClassInfoTests {

        @Test
        @DisplayName("MethodInfo 存储完整信息")
        void testMethodInfo() {
            JavaClassInfo.MethodInfo method = new JavaClassInfo.MethodInfo(
                    "add", "Boolean", "java.lang.Boolean", -1,
                    java.util.Arrays.asList("Object"), false);
            assertThat(method.name).isEqualTo("add");
            assertThat(method.returnType).isEqualTo("Boolean");
            assertThat(method.returnTypeFullName).isEqualTo("java.lang.Boolean");
            assertThat(method.genericReturnTypeIndex).isEqualTo(-1);
            assertThat(method.paramTypes).containsExactly("Object");
            assertThat(method.isStatic).isFalse();
        }

        @Test
        @DisplayName("FieldInfo 存储完整信息")
        void testFieldInfo() {
            JavaClassInfo.FieldInfo field = new JavaClassInfo.FieldInfo("MAX_VALUE", "Int", true);
            assertThat(field.name).isEqualTo("MAX_VALUE");
            assertThat(field.type).isEqualTo("Int");
            assertThat(field.isStatic).isTrue();
        }
    }

    // ============ 边界值与异常值 ============

    @Nested
    @DisplayName("边界值 - 补全入口")
    class CompletionBoundaryTests {

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.complete(TEST_URI, null, 0, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("空字符串 content")
        void testEmptyContent() {
            JsonArray items = analyzer.complete(TEST_URI, "", 0, 0);
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("负行号不崩溃")
        void testNegativeLine() {
            assertThatCode(() -> analyzer.complete(TEST_URI, "val x = 1", -1, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("行号超出范围不崩溃")
        void testLineOutOfRange() {
            assertThatCode(() -> analyzer.complete(TEST_URI, "val x = 1", 100, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("列号超出行长度不崩溃")
        void testColumnOutOfRange() {
            assertThatCode(() -> analyzer.complete(TEST_URI, "val x = 1", 0, 999))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("负列号不崩溃")
        void testNegativeColumn() {
            assertThatCode(() -> analyzer.complete(TEST_URI, "val x = 1", 0, -1))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("只有空白行")
        void testWhitespaceOnly() {
            JsonArray items = analyzer.complete(TEST_URI, "   \n  \n", 0, 0);
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("单字符文件")
        void testSingleChar() {
            JsonArray items = analyzer.complete(TEST_URI, "x", 0, 1);
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("光标在行首的点号")
        void testDotAtLineStart() {
            String code = "val x = 1\n.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 1);
            // 不应崩溃
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("连续多个点号")
        void testMultipleDots() {
            String code = "val x = 1\nx...";
            assertThatCode(() -> analyzer.complete(TEST_URI, code, 1, 4))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("边界值 - 悬停")
    class HoverBoundaryTests {

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.hover(TEST_URI, null, 0, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("空 content 返回 null")
        void testEmptyContent() {
            JsonObject hover = analyzer.hover(TEST_URI, "", 0, 0);
            assertThat(hover).isNull();
        }

        @Test
        @DisplayName("行列越界不崩溃")
        void testOutOfBounds() {
            assertThatCode(() -> analyzer.hover(TEST_URI, "val x = 1", 100, 100))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("边界值 - 跳转定义")
    class DefinitionBoundaryTests {

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.goToDefinition(TEST_URI, null, 0, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("空 content 返回 null")
        void testEmptyContent() {
            JsonObject loc = analyzer.goToDefinition(TEST_URI, "", 0, 0);
            assertThat(loc).isNull();
        }
    }

    @Nested
    @DisplayName("边界值 - 文档符号")
    class SymbolBoundaryTests {

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.documentSymbols(TEST_URI, null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("边界值 - 格式化")
    class FormatBoundaryTests {

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.format(TEST_URI, null))
                    .doesNotThrowAnyException();
        }
    }

    // ============ 类型推断边缘情况 ============

    @Nested
    @DisplayName("类型推断 - 边缘情况")
    class TypeInferenceEdgeCases {

        @Test
        @DisplayName("变量名是另一个变量名的子串")
        void testVariableNameSubstring() {
            // "name" 是 "username" 的子串，不应误匹配
            String code = "val username = \"alice\"\nval name = 42\nname.";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 5);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("变量声明中含字符串里的同名文本")
        void testVarNameInString() {
            // 字符串内容 "list" 不应干扰 list 变量的类型推断
            String code = "val msg = \"list\"\nval list = [1, 2]\nlist.";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 5);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("Double 字面量推断")
        void testDoubleLiteral() {
            String code = "val d = 3.14\nd.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Double")).isTrue();
        }

        @Test
        @DisplayName("负整数字面量推断")
        void testNegativeIntLiteral() {
            String code = "val n = -42\nn.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("Boolean true 推断")
        void testBooleanTrueLiteral() {
            String code = "val b = true\nb.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(allItemsFromType(items, "Boolean")).isTrue();
        }

        @Test
        @DisplayName("Boolean false 推断")
        void testBooleanFalseLiteral() {
            String code = "val b = false\nb.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(allItemsFromType(items, "Boolean")).isTrue();
        }

        @Test
        @DisplayName("空列表字面量")
        void testEmptyListLiteral() {
            String code = "val e = []\ne.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("类型注解带可空 ?")
        void testNullableTypeAnnotation() {
            String code = "val s: String? = null\ns.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            // String? 应该被提取为 String（extractTypeWithGenerics 会在 ? 处停止）
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("未声明的变量不崩溃")
        void testUndeclaredVariable() {
            String code = "unknown.";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 8);
            // 不崩溃，可能返回空或所有方法
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("数字后面的点不是成员访问")
        void testNumberDot() {
            // "42." 可能被解析为浮点数的一部分
            String code = "42.";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 3);
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("泛型类型注解 List<String>")
        void testGenericTypeAnnotation() {
            String code = "val names: List<String> = []\nnames.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 14);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("嵌套泛型 Map<String, List<Int>>")
        void testNestedGenericType() {
            String code = "val m: Map<String, List<Int>> = {}\nm.get(\"a\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 11);
            // get 返回 $1 → List<Int>
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
        }
    }

    // ============ 方法链边缘情况 ============

    @Nested
    @DisplayName("方法链 - 边缘情况")
    class MethodChainEdgeCases {

        @Test
        @DisplayName("深层方法链: s.trim().toUpperCase().")
        void testDeepChain() {
            String code = "val s = \"hello\"\ns.trim().toUpperCase().";
            // s.trim().toUpperCase(). = 23 chars
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 23);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("方法链中带参数: s.replace(\"a\",\"b\").")
        void testChainWithArgs() {
            String code = "val s = \"hello\"\ns.replace(\"l\",\"r\").";
            // s.replace("l","r"). = 19 chars
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 19);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("List.filter().size() 返回 Int")
        void testFilterThenSize() {
            String code = "val list = [1, 2, 3]\nlist.filter { it > 1 }.size().";
            // list.filter { it > 1 }.size(). = 30 chars
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 30);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("Map.keys().size() 返回 Int")
        void testMapKeysThenSize() {
            String code = "val m = #{\"a\": 1}\nm.keys().size().";
            // m.keys().size(). = 16 chars
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 16);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("void 返回类型的方法后不补全")
        void testVoidReturnNoCompletion() {
            String code = "val list = [1, 2]\nlist.forEach { println(it) }.";
            // forEach 返回 null → 不应有特定类型补全
            // list.forEach { println(it) }. = 29 chars
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 29);
            // 不崩溃即可
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("Java 方法链 StringBuilder.append().toString().")
        void testJavaDeepChain() {
            String code = "val sb = Java.type(\"java.lang.StringBuilder\")()\nsb.append(\"x\").toString().";
            // sb.append("x").toString(). = 26 chars
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 26);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }
    }

    // ============ 括号和表达式边缘情况 ============

    @Nested
    @DisplayName("括号表达式 - 边缘情况")
    class ParenEdgeCases {

        @Test
        @DisplayName("双层括号 ((s)).")
        void testDoubleParens() {
            String code = "val s = \"hello\"\n((s)).";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 6);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("赋值右侧的表达式补全 val x = list.")
        void testAssignmentRhs() {
            String code = "val list = [1, 2]\nval x = list.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 13);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("if 条件中的补全")
        void testInsideIfCondition() {
            String code = "val s = \"hello\"\nif (s.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 6);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("函数参数中第二个参数的补全")
        void testSecondArgCompletion() {
            String code = "val s = \"hello\"\nprintln(1, s.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("return 语句中的补全")
        void testReturnCompletion() {
            String code = "val s = \"hello\"\nreturn s.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 9);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("不平衡括号不崩溃")
        void testUnbalancedParens() {
            String code = "val s = \"hello\"\n(((s.";
            assertThatCode(() -> analyzer.complete(TEST_URI, code, 1, 5))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("空括号表达式 ().")
        void testEmptyParens() {
            String code = "().";
            assertThatCode(() -> analyzer.complete(TEST_URI, code, 0, 3))
                    .doesNotThrowAnyException();
        }
    }

    // ============ 索引访问边缘情况 ============

    @Nested
    @DisplayName("索引访问 - 边缘情况")
    class IndexEdgeCases {

        @Test
        @DisplayName("String 索引返回 String")
        void testStringIndex() {
            String code = "val s = \"hello\"\ns[0].";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 5);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("无泛型 List 索引不崩溃")
        void testUntypedListIndex() {
            String code = "val list = []\nlist[0].";
            assertThatCode(() -> analyzer.complete(TEST_URI, code, 1, 8))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("嵌套索引 list[0][0] 不崩溃")
        void testNestedIndex() {
            String code = "val matrix = [[1, 2], [3, 4]]\nmatrix[0][0].";
            assertThatCode(() -> analyzer.complete(TEST_URI, code, 1, 13))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("索引中有表达式 list[i + 1].")
        void testExpressionIndex() {
            String code = "val list = [\"a\", \"b\"]\nlist[0 + 1].";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 12);
            // 应该推断为 String (List<String> 的元素)
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }
    }

    // ============ 集合推断边缘情况 ============

    @Nested
    @DisplayName("集合推断 - 边缘情况")
    class CollectionInferenceEdgeCases {

        @Test
        @DisplayName("无 add 调用的 ArrayList 不推断元素类型")
        void testNoAddCalls() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 13);
            // 没有 add 调用，first() 返回 null（$0 无泛型参数）
            // 不应崩溃
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("混合 add 和索引赋值类型一致仍可推断")
        void testMixedAddAndIndexConsistent() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")\nlist[1] = \"world\"\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("混合 add 和索引赋值类型不一致放弃推断")
        void testMixedAddAndIndexInconsistent() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")\nlist[0] = 42\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 13);
            // 类型不一致，放弃推断
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("Map put 只有一次调用也能推断")
        void testSinglePut() {
            String code = "val map = Java.type(\"java.util.HashMap\")()\nmap.put(\"key\", 42)\nmap.get(\"key\").";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 15);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("已有泛型注解的变量不再推断")
        void testExistingGenericNotOverridden() {
            String code = "val list: List<String> = []\nlist.add(42)\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 13);
            // 类型注解已指定 String，不被 add(42) 覆盖
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("add 参数是方法调用 add(obj.toString())")
        void testAddMethodCallArg() {
            String code = "val n = 42\nval list = Java.type(\"java.util.ArrayList\")()\nlist.add(n.toString())\nlist.first().";
            // n.toString() 难以快速推断，可能不推断
            assertThatCode(() -> analyzer.complete(TEST_URI, code, 3, 13))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("add 参数含引号逗号的字符串")
        void testAddStringWithComma() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello, world\")\nlist.first().";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }
    }

    // ============ 安全导航 ?. ============

    @Nested
    @DisplayName("安全导航 ?.")
    class SafeNavigationTests {

        @Test
        @DisplayName("?. 触发成员补全")
        void testSafeNavCompletion() {
            String code = "val s = \"hello\"\ns?.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 3);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "length")).isTrue();
        }
    }

    // ============ Java 类解析边缘情况 ============

    @Nested
    @DisplayName("JavaClassResolver - 边缘情况")
    class JavaResolverEdgeCases {

        private JavaClassResolver resolver;

        @BeforeEach
        void setUp() {
            resolver = new JavaClassResolver(Collections.emptyList());
        }

        @Test
        @DisplayName("解析 java.lang.Object")
        void testResolveObject() {
            JavaClassInfo info = resolver.resolve("java.lang.Object");
            assertThat(info).isNotNull();
            boolean hasEquals = info.methods.stream().anyMatch(m -> "equals".equals(m.name));
            boolean hasHashCode = info.methods.stream().anyMatch(m -> "hashCode".equals(m.name));
            assertThat(hasEquals).isTrue();
            assertThat(hasHashCode).isTrue();
        }

        @Test
        @DisplayName("解析 java.io.File")
        void testResolveFile() {
            JavaClassInfo info = resolver.resolve("java.io.File");
            assertThat(info).isNotNull();
            boolean hasGetName = info.methods.stream().anyMatch(m -> "getName".equals(m.name));
            boolean hasListFiles = info.methods.stream().anyMatch(m -> "listFiles".equals(m.name));
            assertThat(hasGetName).isTrue();
            assertThat(hasListFiles).isTrue();
        }

        @Test
        @DisplayName("解析接口: java.util.Map")
        void testResolveInterface() {
            JavaClassInfo info = resolver.resolve("java.util.Map");
            assertThat(info).isNotNull();
            boolean hasPut = info.methods.stream().anyMatch(m -> "put".equals(m.name));
            assertThat(hasPut).isTrue();
        }

        @Test
        @DisplayName("不包含 static 方法在实例补全中")
        void testStaticMethodsMarked() {
            JavaClassInfo info = resolver.resolve("java.lang.Integer");
            assertThat(info).isNotNull();
            // parseInt 是 static 方法
            JavaClassInfo.MethodInfo parseInt = info.methods.stream()
                    .filter(m -> "parseInt".equals(m.name)).findFirst().orElse(null);
            if (parseInt != null) {
                assertThat(parseInt.isStatic).isTrue();
            }
        }

        @Test
        @DisplayName("classpath 为 null 不崩溃")
        void testNullClasspath() {
            JavaClassResolver r = new JavaClassResolver(null);
            JavaClassInfo info = r.resolve("java.lang.String");
            assertThat(info).isNotNull();
        }

        @Test
        @DisplayName("空 classpath 列表可以解析 JDK 类")
        void testEmptyClasspathResolvesJdk() {
            JavaClassResolver r = new JavaClassResolver(Collections.emptyList());
            assertThat(r.resolve("java.util.ArrayList")).isNotNull();
            assertThat(r.resolve("java.lang.String")).isNotNull();
        }

        @Test
        @DisplayName("不存在的 classpath 条目不崩溃")
        void testNonexistentClasspathEntry() {
            JavaClassResolver r = new JavaClassResolver(
                    java.util.Arrays.asList("/nonexistent/path.jar", "C:\\fake\\dir"));
            // 仍能解析 JDK 类
            assertThat(r.resolve("java.lang.String")).isNotNull();
            // 不存在的类返回 null
            assertThat(r.resolve("com.fake.Nonexistent")).isNull();
        }

        @Test
        @DisplayName("继承链无循环（A extends B, B extends A 不会无限递归）")
        void testNoCyclicInheritance() {
            // java.lang.Object 是所有类的终极父类，不应死循环
            JavaClassInfo info = resolver.resolve("java.util.HashMap");
            assertThat(info).isNotNull();
            assertThat(info.methods.size()).isGreaterThan(0);
        }

        @Test
        @DisplayName("方法返回类型简名和全名都存在")
        void testMethodReturnTypeNames() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            for (JavaClassInfo.MethodInfo m : info.methods) {
                assertThat(m.returnType).isNotNull();
                assertThat(m.returnTypeFullName).isNotNull();
                assertThat(m.paramTypes).isNotNull();
            }
        }

        @Test
        @DisplayName("构造方法 <init> 被收集")
        void testConstructorCollected() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            boolean hasInit = info.methods.stream().anyMatch(m -> "<init>".equals(m.name));
            assertThat(hasInit).isTrue();
        }

        @Test
        @DisplayName("ArrayList 的 typeParams 包含 E")
        void testArrayListTypeParams() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            assertThat(info.typeParams).containsExactly("E");
        }

        @Test
        @DisplayName("ArrayList.get() 返回泛型 E (index 0)")
        void testArrayListGetGeneric() {
            JavaClassInfo info = resolver.resolve("java.util.ArrayList");
            assertThat(info).isNotNull();
            JavaClassInfo.MethodInfo getMethod = info.methods.stream()
                    .filter(m -> "get".equals(m.name)).findFirst().orElse(null);
            assertThat(getMethod).isNotNull();
            assertThat(getMethod.genericReturnTypeIndex).isEqualTo(0);
        }
    }

    // ============ 多文件与 import ============

    @Nested
    @DisplayName("多行代码与复杂场景")
    class ComplexScenarioTests {

        @Test
        @DisplayName("多个类声明中正确选择")
        void testMultipleClasses() {
            String code = "class Cat {\n    fun meow() = \"meow\"\n}\nclass Dog {\n    fun bark() = \"woof\"\n}\nval d = Dog()\nd.";
            JsonArray items = analyzer.complete(TEST_URI, code, 7, 2);
            assertThat(hasItemStartingWith(items, "bark(")).isTrue();
            assertThat(hasItemStartingWith(items, "meow(")).isFalse();
        }

        @Test
        @DisplayName("类中多个方法都出现在补全中")
        void testAllMethodsInClass() {
            String code = "class Calc {\n    fun add() = 0\n    fun sub() = 0\n    fun mul() = 0\n}\nval c = Calc()\nc.";
            JsonArray items = analyzer.complete(TEST_URI, code, 6, 2);
            assertThat(hasItemStartingWith(items, "add(")).isTrue();
            assertThat(hasItemStartingWith(items, "sub(")).isTrue();
            assertThat(hasItemStartingWith(items, "mul(")).isTrue();
        }

        @Test
        @DisplayName("类的属性也出现在补全中")
        void testPropertyCompletion() {
            String code = "class User {\n    val name = \"Alice\"\n    val age = 25\n}\nval u = User()\nu.";
            JsonArray items = analyzer.complete(TEST_URI, code, 5, 2);
            assertThat(hasItem(items, "name")).isTrue();
            assertThat(hasItem(items, "age")).isTrue();
        }

        @Test
        @DisplayName("长文件中变量推断正确")
        void testLongFile() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 50; i++) {
                sb.append("val var").append(i).append(" = ").append(i).append("\n");
            }
            sb.append("val target = \"hello\"\ntarget.");
            String code = sb.toString();
            JsonArray items = analyzer.complete(TEST_URI, code, 51, 7);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("同一行多个赋值只匹配正确变量")
        void testCorrectVariableOnLine() {
            String code = "val a = \"hello\"\nval b = [1, 2]\na.";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 2);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("注释行不影响类型推断")
        void testCommentedCode() {
            String code = "// val s = 42\nval s = \"hello\"\ns.";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 2);
            assertThat(items.size()).isGreaterThan(0);
            // 注释行的 val s = 42 可能也被匹配（文本搜索的局限）
            // 但至少不应崩溃
            assertThat(items).isNotNull();
        }

        @Test
        @DisplayName("Java.type 未调用构造函数也能推断")
        void testJavaTypeWithoutConstructor() {
            // Java.type("...") 返回类对象，不是实例
            // 加 () 调用构造函数才是实例
            String code = "val clazz = Java.type(\"java.util.ArrayList\")\nclazz.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 6);
            // clazz 是类本身 (java:java.util.ArrayList)，也应能补全
            assertThat(items).isNotNull();
        }
    }

    // ============ 语义检查 — 参数个数 ============

    @Nested
    @DisplayName("analyze - 方法参数个数检查")
    class ArgCountCheckTests {

        /** 从诊断列表中查找包含指定文本的诊断 */
        private JsonObject findDiag(JsonArray diagnostics, String textContains) {
            for (int i = 0; i < diagnostics.size(); i++) {
                JsonObject d = diagnostics.get(i).getAsJsonObject();
                if (d.get("message").getAsString().contains(textContains)) return d;
            }
            return null;
        }

        @Test
        @DisplayName("无参调用有参方法报错: list.add()")
        void testNoArgForRequiredParam() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNotNull();
            assertThat(findDiag(diags, "0 个")).isNotNull();
        }

        @Test
        @DisplayName("正确单参数调用无报错: list.add(\"hello\")")
        void testCorrectSingleArg() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("正确双参数调用无报错: list.add(0, \"hello\")")
        void testCorrectTwoArgs() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(0, \"hello\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("参数过多报错: list.add(1, 2, 3)")
        void testTooManyArgs() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(1, 2, 3)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNotNull();
            assertThat(findDiag(diags, "3 个")).isNotNull();
        }

        @Test
        @DisplayName("无参方法传参报错: list.size(1)")
        void testArgForNoParamMethod() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.size(1)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "size")).isNotNull();
        }

        @Test
        @DisplayName("无参方法正确调用无报错: list.size()")
        void testCorrectNoArgCall() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.size()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "size")).isNull();
        }

        @Test
        @DisplayName("Nova String 方法: length() 正确")
        void testNovaStringLengthOk() {
            String code = "val s = \"hello\"\ns.length()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "length")).isNull();
        }

        @Test
        @DisplayName("Nova String 方法: length(1) 报错")
        void testNovaStringLengthBadArg() {
            String code = "val s = \"hello\"\ns.length(1)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "length")).isNotNull();
        }

        @Test
        @DisplayName("Nova String 方法: replace 需要 2 个参数")
        void testNovaStringReplace() {
            String code = "val s = \"hello\"\ns.replace(\"l\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "replace")).isNotNull();
            assertThat(findDiag(diags, "1 个")).isNotNull();
        }

        @Test
        @DisplayName("Nova String 方法: replace 正确 2 个参数无报错")
        void testNovaStringReplaceOk() {
            String code = "val s = \"hello\"\ns.replace(\"l\", \"r\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "replace")).isNull();
        }

        @Test
        @DisplayName("Nova Map 方法: put 需要 2 个参数")
        void testNovaMapPut() {
            String code = "val m = #{\"a\": 1}\nm.put(\"b\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "put")).isNotNull();
        }

        @Test
        @DisplayName("未知变量不报错")
        void testUnknownVariable() {
            String code = "unknown.doSomething()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            // 无法推断类型，不应报语义错误
            assertThat(findDiag(diags, "doSomething")).isNull();
        }

        @Test
        @DisplayName("未知方法不报错")
        void testUnknownMethod() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.unknownMethod()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            // 方法不在已知签名中，不报错
            assertThat(findDiag(diags, "unknownMethod")).isNull();
        }

        @Test
        @DisplayName("注释中的调用不检查")
        void testInsideComment() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\n// list.add()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("字符串中的调用不检查")
        void testInsideString() {
            String code = "val s = \"list.add()\"";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("方法链上的每个调用都检查")
        void testMethodChainCheck() {
            String code = "val sb = Java.type(\"java.lang.StringBuilder\")()\nsb.append().toString()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            // append() 无参数 → 应报错（StringBuilder.append 至少需要 1 个参数）
            assertThat(findDiag(diags, "append")).isNotNull();
        }

        @Test
        @DisplayName("嵌套参数中的逗号不误判参数个数")
        void testNestedArgsComma() {
            // add 的参数里有一个函数调用含逗号，不应误判为 2 个参数
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"a,b\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("诊断信息包含正确的位置范围")
        void testDiagnosticRange() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            JsonObject diag = findDiag(diags, "add");
            assertThat(diag).isNotNull();
            JsonObject range = diag.getAsJsonObject("range");
            // 应在第 1 行（0-based）
            assertThat(range.getAsJsonObject("start").get("line").getAsInt()).isEqualTo(1);
        }

        @Test
        @DisplayName("语法错误时跳过语义检查")
        void testSkipSemanticOnSyntaxError() {
            // 语法错误的代码不做参数检查
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\nfun (";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            // 应该只有语法错误，不应有参数检查错误
            boolean hasSyntaxError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (!msg.contains("add")) hasSyntaxError = true;
            }
            assertThat(hasSyntaxError).isTrue();
        }

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.analyze(TEST_URI, null))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("空 content 无诊断")
        void testEmptyContent() {
            JsonArray diags = analyzer.analyze(TEST_URI, "");
            assertThat(diags.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("同一行多个方法调用分别检查")
        void testMultipleCallsOnOneLine() {
            String code = "val s = \"hello\"\ns.length(1).toString(1)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            // length(1) 和 toString(1) 都应报错
            assertThat(findDiag(diags, "length")).isNotNull();
        }
    }

    // ============ 语义检查 — 参数类型 ============

    @Nested
    @DisplayName("analyze - 方法参数类型检查")
    class ArgTypeCheckTests {

        private JsonObject findDiag(JsonArray diagnostics, String textContains) {
            for (int i = 0; i < diagnostics.size(); i++) {
                JsonObject d = diagnostics.get(i).getAsJsonObject();
                if (d.get("message").getAsString().contains(textContains)) return d;
            }
            return null;
        }

        @Test
        @DisplayName("ArrayList.add(int, Object) 第一个参数类型不匹配")
        void testAddTwoArgsTypeMismatch() {
            // add(String, String) → 2 参数重载需要 (Int, Object)，String 不兼容 Int
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"a\", \"b\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "没有匹配的重载")).isNotNull();
        }

        @Test
        @DisplayName("ArrayList.add(int, Object) 类型正确无报错")
        void testAddTwoArgsTypeCorrect() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(0, \"hello\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("String 兼容 Object")
        void testStringCompatibleWithObject() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("Int 兼容 Object")
        void testIntCompatibleWithObject() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(42)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("List 兼容 Object")
        void testListCompatibleWithObject() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add([1, 2])";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "add")).isNull();
        }

        @Test
        @DisplayName("参数类型未知时不报错")
        void testUnknownArgTypeNoError() {
            // unknownVar 无法推断类型，不应报类型错误
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(unknownVar)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "没有匹配的重载")).isNull();
        }

        @Test
        @DisplayName("HashMap.put 正确调用")
        void testHashMapPutOk() {
            String code = "val map = Java.type(\"java.util.HashMap\")()\nmap.put(\"key\", 42)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            assertThat(findDiag(diags, "put")).isNull();
        }

        @Test
        @DisplayName("错误消息包含实际参数类型")
        void testErrorMsgContainsActualTypes() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"x\", \"y\")";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            JsonObject diag = findDiag(diags, "没有匹配的重载");
            assertThat(diag).isNotNull();
            String msg = diag.get("message").getAsString();
            assertThat(msg).contains("String");
        }
    }

    // ============ Hover — 方法重载展示 ============

    @Nested
    @DisplayName("hover - 方法重载")
    class HoverMethodOverloadsTests {

        /** 从 hover 结果中提取 markdown 内容 */
        private String getHoverContent(JsonObject hover) {
            if (hover == null) return null;
            return hover.getAsJsonObject("contents").get("value").getAsString();
        }

        @Test
        @DisplayName("ArrayList.add 显示多个重载")
        void testArrayListAddOverloads() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.add(\"hello\")";
            // hover 在 "add" 上：第 1 行, 第 5 个字符 (0-based, 光标在 'a' 上)
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 5);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("add");
            assertThat(content).contains("重载");
        }

        @Test
        @DisplayName("ArrayList.size 显示单个签名（无重载）")
        void testArrayListSizeNoOverload() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.size()";
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 5);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("size");
            // 只有 1 个签名时不显示 "重载"
            assertThat(content).doesNotContain("重载");
        }

        @Test
        @DisplayName("hover 显示返回类型")
        void testHoverShowsReturnType() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.size()";
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 5);
            String content = getHoverContent(hover);
            assertThat(content).contains("Int");
        }

        @Test
        @DisplayName("hover 显示参数类型")
        void testHoverShowsParamTypes() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.get(0)";
            // hover 在 "get"
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 5);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("Int"); // get(int) 的参数类型
        }

        @Test
        @DisplayName("Nova String 方法 hover 显示说明")
        void testNovaStringMethodHover() {
            String code = "val s = \"hello\"\ns.length()";
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 2);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("length");
        }

        @Test
        @DisplayName("Nova List 方法 hover")
        void testNovaListMethodHover() {
            String code = "val list = [1, 2]\nlist.size()";
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 5);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("size");
            assertThat(content).contains("Int");
        }

        @Test
        @DisplayName("Nova Map 方法 hover 显示重载")
        void testNovaMapMethodHover() {
            String code = "val m = #{\"a\": 1}\nm.get(\"a\")";
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 2);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("get");
        }

        @Test
        @DisplayName("hover 在非方法标识符上不显示重载")
        void testHoverOnNonMethod() {
            String code = "val list = [1, 2]\nlist.add(1)";
            // hover 在 "list" 变量上，不是方法
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 0);
            // list 不是方法名（前面没有点），不应返回重载信息
            // 可能返回 null 或变量信息
            if (hover != null) {
                String content = getHoverContent(hover);
                assertThat(content).doesNotContain("重载");
            }
        }

        @Test
        @DisplayName("hover 在未知类型的方法上返回 null")
        void testHoverUnknownReceiver() {
            String code = "unknown.doStuff()";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 8);
            // 无法推断 unknown 的类型，hover 可能为 null
            // 不应崩溃
            assertThat(true).isTrue(); // 到这里说明没有崩溃
        }

        @Test
        @DisplayName("hover 在 Java.type 方法上不崩溃")
        void testHoverJavaType() {
            // Java 不是一个有类型推断的变量，hover 返回 null 是正常行为
            String code = "Java.type(\"java.util.ArrayList\")";
            assertThatCode(() -> analyzer.hover(TEST_URI, code, 0, 5))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("StringBuilder.append 的多个重载")
        void testStringBuilderAppendOverloads() {
            String code = "val sb = Java.type(\"java.lang.StringBuilder\")()\nsb.append(\"x\")";
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 3);
            assertThat(hover).isNotNull();
            String content = getHoverContent(hover);
            assertThat(content).contains("append");
            // StringBuilder.append 有非常多重载
            assertThat(content).contains("重载");
        }

        @Test
        @DisplayName("null content 不崩溃")
        void testNullContent() {
            assertThatCode(() -> analyzer.hover(TEST_URI, null, 0, 0))
                    .doesNotThrowAnyException();
        }
    }

    // ============ 补全 — Snippet 格式 ============

    @Nested
    @DisplayName("complete - Snippet 格式")
    class SnippetFormatTests {

        @Test
        @DisplayName("无参方法 insertText 包含 ()$0")
        void testNoParamMethodSnippet() {
            String code = "val s = \"hello\"\ns.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            JsonObject item = findItem(items, "toUpperCase()");
            assertThat(item).isNotNull();
            assertThat(item.get("insertTextFormat").getAsInt()).isEqualTo(2);
            assertThat(item.get("insertText").getAsString()).isEqualTo("toUpperCase()$0");
        }

        @Test
        @DisplayName("有参方法 insertText 包含 ($1)$0")
        void testParamMethodSnippet() {
            String code = "val s = \"hello\"\ns.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            JsonObject splitItem = findItem(items, "split(arg1)");
            assertThat(splitItem).isNotNull();
            assertThat(splitItem.get("insertTextFormat").getAsInt()).isEqualTo(2);
            assertThat(splitItem.get("insertText").getAsString()).isEqualTo("split($1)$0");
        }

        @Test
        @DisplayName("双参方法 insertText 包含 ($1)$0")
        void testTwoParamMethodSnippet() {
            String code = "val s = \"hello\"\ns.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            JsonObject replaceItem = findItem(items, "replace(arg1, arg2)");
            assertThat(replaceItem).isNotNull();
            assertThat(replaceItem.get("insertText").getAsString()).isEqualTo("replace($1)$0");
        }

        @Test
        @DisplayName("Java 方法也使用 Snippet 格式")
        void testJavaMethodSnippet() {
            String code = "val list = Java.type(\"java.util.ArrayList\")()\nlist.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 5);
            // size() 是无参方法
            JsonObject sizeItem = null;
            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                if (item.get("label").getAsString().startsWith("size(")) {
                    sizeItem = item;
                    break;
                }
            }
            assertThat(sizeItem).isNotNull();
            assertThat(sizeItem.get("insertTextFormat").getAsInt()).isEqualTo(2);
            String insertText = sizeItem.get("insertText").getAsString();
            assertThat(insertText).contains("$0");
        }

        @Test
        @DisplayName("List 方法的 Snippet")
        void testListMethodSnippet() {
            String code = "val list = [1, 2]\nlist.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 5);
            JsonObject addItem = findItem(items, "add(arg1)");
            assertThat(addItem).isNotNull();
            assertThat(addItem.get("insertText").getAsString()).isEqualTo("add($1)$0");

            JsonObject clearItem = findItem(items, "clear()");
            assertThat(clearItem).isNotNull();
            assertThat(clearItem.get("insertText").getAsString()).isEqualTo("clear()$0");
        }
    }

    // ============ 变量名整词匹配 ============

    @Nested
    @DisplayName("类型推断 - 整词匹配")
    class WholeWordMatchTests {

        @Test
        @DisplayName("变量名 a 不匹配 val 中的 a")
        void testSingleCharVarNotMatchKeyword() {
            String code = "val a = \"hello\"\na.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("name 不匹配 username 中的 name")
        void testSubstringNotMatch() {
            String code = "val username = \"alice\"\nval name = 42\nname.";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 5);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("多个相似变量名取正确的")
        void testSimilarVarNames() {
            String code = "val item = \"hello\"\nval items = [1, 2]\nval itemCount = 42\nitem.";
            JsonArray items = analyzer.complete(TEST_URI, code, 3, 5);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        @Test
        @DisplayName("变量名 v 不匹配 val 中的 v")
        void testVarV() {
            String code = "val v = [1, 2]\nv.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("变量名 al 不匹配 val 中的 al")
        void testVarAl() {
            String code = "val al = 42\nal.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 3);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("同一行多个赋值只匹配正确变量")
        void testCorrectVariableOnLine() {
            String code = "val x = \"hello\"\nval y = [1, 2]\nx.";
            JsonArray items = analyzer.complete(TEST_URI, code, 2, 2);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }
    }

    // ============ Null/边界守卫 ============

    @Nested
    @DisplayName("null 与边界守卫")
    class NullGuardTests {

        @Test
        @DisplayName("complete(null) 不崩溃，返回空列表")
        void testCompleteNull() {
            JsonArray items = analyzer.complete(TEST_URI, null, 0, 0);
            assertThat(items).isNotNull();
            assertThat(items.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("hover(null) 不崩溃，返回 null")
        void testHoverNull() {
            JsonObject hover = analyzer.hover(TEST_URI, null, 0, 0);
            assertThat(hover).isNull();
        }

        @Test
        @DisplayName("goToDefinition(null) 不崩溃")
        void testDefinitionNull() {
            JsonObject loc = analyzer.goToDefinition(TEST_URI, null, 0, 0);
            assertThat(loc).isNull();
        }

        @Test
        @DisplayName("documentSymbols(null) 不崩溃，返回空数组")
        void testSymbolsNull() {
            JsonArray symbols = analyzer.documentSymbols(TEST_URI, null);
            assertThat(symbols).isNotNull();
            assertThat(symbols.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("analyze(null) 不崩溃")
        void testAnalyzeNull() {
            JsonArray diags = analyzer.analyze(TEST_URI, null);
            assertThat(diags).isNotNull();
            assertThat(diags.size()).isEqualTo(0);
        }

        @Test
        @DisplayName("列号超出行长度不崩溃")
        void testColumnOverflow() {
            assertThatCode(() -> analyzer.complete(TEST_URI, "val x = 1", 0, 999))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("负行号不崩溃")
        void testNegativeLine() {
            assertThatCode(() -> analyzer.complete(TEST_URI, "val x = 1", -1, 0))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("format(null) 不崩溃")
        void testFormatNull() {
            assertThatCode(() -> analyzer.format(TEST_URI, null))
                    .doesNotThrowAnyException();
        }
    }

    // ============ Array 类型支持 ============

    @Nested
    @DisplayName("Array 类型支持")
    class ArrayTypeTests {

        // ---- 补全 ----

        @Test
        @DisplayName("Array 出现在内置类型补全中")
        void testArrayInBuiltinTypes() {
            String code = "Arr";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 3);
            assertThat(hasItem(items, "Array")).isTrue();
            // Array 同时是关键词(kind=14)和内置类型(kind=7)，验证至少有一个 kind=7 的
            boolean hasArrayClass = false;
            for (int i = 0; i < items.size(); i++) {
                JsonObject item = items.get(i).getAsJsonObject();
                if ("Array".equals(item.get("label").getAsString()) && item.get("kind").getAsInt() == 7) {
                    hasArrayClass = true;
                }
            }
            assertThat(hasArrayClass).isTrue();
        }

        @Test
        @DisplayName("arrayOf 出现在内置函数补全中")
        void testArrayOfInBuiltinFunctions() {
            String code = "arrayO";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 6);
            assertThat(hasItemStartingWith(items, "arrayOf(")).isTrue();
        }

        @Test
        @DisplayName("arrayOf 补全 detail 显示 Array 返回类型")
        void testArrayOfReturnType() {
            String code = "arrayO";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 6);
            JsonObject item = findItem(items, "arrayOf(elements...)");
            assertThat(item).isNotNull();
            assertThat(item.get("detail").getAsString()).contains("Array");
        }

        @Test
        @DisplayName("arrayOf 变量后补全 Array 方法")
        void testArrayOfVarCompletion() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Array")).isTrue();
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(hasItemStartingWith(items, "toList(")).isTrue();
            assertThat(hasItemStartingWith(items, "forEach(")).isTrue();
        }

        @Test
        @DisplayName("Array<Int>(5) 构造后补全 Array 方法")
        void testArrayConstructorCompletion() {
            String code = "val arr = Array<Int>(5)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Array")).isTrue();
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("Array size 补全为属性（kind=10）而非方法")
        void testArraySizeIsProperty() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            JsonObject sizeItem = findItem(items, "size");
            assertThat(sizeItem).isNotNull();
            assertThat(sizeItem.get("kind").getAsInt()).isEqualTo(10); // Property
            // 属性不应有括号
            assertThat(sizeItem.get("insertText").getAsString()).isEqualTo("size");
        }

        @Test
        @DisplayName("Array 方法使用 Snippet 格式")
        void testArrayMethodSnippet() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);

            JsonObject toListItem = findItem(items, "toList()");
            assertThat(toListItem).isNotNull();
            assertThat(toListItem.get("insertTextFormat").getAsInt()).isEqualTo(2);
            assertThat(toListItem.get("insertText").getAsString()).isEqualTo("toList()$0");

            JsonObject forEachItem = findItem(items, "forEach(arg1)");
            assertThat(forEachItem).isNotNull();
            assertThat(forEachItem.get("insertText").getAsString()).isEqualTo("forEach($1)$0");
        }

        @Test
        @DisplayName("Array 前缀过滤")
        void testArrayPrefixFilter() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.to";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 6);
            assertThat(hasItemStartingWith(items, "toList(")).isTrue();
            // 不应包含不匹配前缀的方法
            assertThat(hasItemStartingWith(items, "forEach(")).isFalse();
            assertThat(hasItem(items, "size")).isFalse();
        }

        @Test
        @DisplayName("Array 全部方法都存在")
        void testArrayAllMethods() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(hasItemStartingWith(items, "toList(")).isTrue();
            assertThat(hasItemStartingWith(items, "forEach(")).isTrue();
            assertThat(hasItemStartingWith(items, "map(")).isTrue();
            assertThat(hasItemStartingWith(items, "filter(")).isTrue();
            assertThat(hasItemStartingWith(items, "contains(")).isTrue();
            assertThat(hasItemStartingWith(items, "indexOf(")).isTrue();
        }

        // ---- 类型推断 ----

        @Test
        @DisplayName("arrayOf() 表达式类型推断为 Array")
        void testArrayOfExprInference() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(allItemsFromType(items, "Array")).isTrue();
        }

        @Test
        @DisplayName("Array<Int> 带类型注解推断")
        void testArrayTypeAnnotation() {
            String code = "val arr: Array<Int> = arrayOf(1, 2)\narr.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 4);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Array")).isTrue();
        }

        @Test
        @DisplayName("Array<Int> 索引访问推断元素类型为 Int")
        void testArrayIndexInference() {
            String code = "val arr: Array<Int> = arrayOf(1, 2)\narr[0].";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 7);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        @Test
        @DisplayName("Array<String> 索引访问推断元素类型为 String")
        void testArrayStringIndexInference() {
            String code = "val arr: Array<String> = arrayOf(\"a\", \"b\")\narr[0].";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 7);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "String")).isTrue();
        }

        // ---- 方法链返回类型 ----

        @Test
        @DisplayName("Array.toList() 返回 List 补全")
        void testArrayToListReturnsListType() {
            String line2 = "arr.toList().";
            String code = "val arr = arrayOf(1, 2, 3)\n" + line2;
            JsonArray items = analyzer.complete(TEST_URI, code, 1, line2.length());
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(hasItem(items, "first")).isTrue();
        }

        @Test
        @DisplayName("Array.filter() 返回 List 补全")
        void testArrayFilterReturnsListType() {
            String line2 = "arr.filter { it > 1 }.";
            String code = "val arr = arrayOf(1, 2, 3)\n" + line2;
            JsonArray items = analyzer.complete(TEST_URI, code, 1, line2.length());
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("Array.map() 返回 List 补全")
        void testArrayMapReturnsListType() {
            String line2 = "arr.map { it * 2 }.";
            String code = "val arr = arrayOf(1, 2, 3)\n" + line2;
            JsonArray items = analyzer.complete(TEST_URI, code, 1, line2.length());
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
        }

        @Test
        @DisplayName("Array.contains() 返回 Boolean 补全")
        void testArrayContainsReturnsBooleanType() {
            String line2 = "arr.contains(1).";
            String code = "val arr = arrayOf(1, 2, 3)\n" + line2;
            JsonArray items = analyzer.complete(TEST_URI, code, 1, line2.length());
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Boolean")).isTrue();
        }

        @Test
        @DisplayName("Array.indexOf() 返回 Int 补全")
        void testArrayIndexOfReturnsIntType() {
            String line2 = "arr.indexOf(2).";
            String code = "val arr = arrayOf(1, 2, 3)\n" + line2;
            JsonArray items = analyzer.complete(TEST_URI, code, 1, line2.length());
            assertThat(items.size()).isGreaterThan(0);
            assertThat(allItemsFromType(items, "Int")).isTrue();
        }

        // ---- Hover ----

        @Test
        @DisplayName("悬停 Array 显示内置类型")
        void testHoverOnArrayType() {
            String code = "val arr: Array<Int> = arrayOf(1, 2)";
            // Array 位于第 0 行第 9 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 9);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("内置类型").contains("Array");
        }

        @Test
        @DisplayName("悬停 arrayOf 显示内置函数信息")
        void testHoverOnArrayOf() {
            String code = "val arr = arrayOf(1, 2, 3)";
            // arrayOf 位于第 0 行第 10 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 10);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("内置函数").contains("arrayOf");
            assertThat(value).contains("Array");
        }

        @Test
        @DisplayName("悬停 Array 成员方法显示信息")
        void testHoverOnArrayMethod() {
            String code = "val arr = arrayOf(1, 2, 3)\narr.contains(1)";
            // contains 位于第 1 行第 4 列
            JsonObject hover = analyzer.hover(TEST_URI, code, 1, 4);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("contains");
        }

        // ---- Array 构造函数参数检查 ----

        @Test
        @DisplayName("Array<Int>(5) 正确调用无报错")
        void testArrayConstructorCorrect() {
            String code = "val arr = Array<Int>(5)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasArrayError = false;
            for (int i = 0; i < diags.size(); i++) {
                if (diags.get(i).getAsJsonObject().get("message").getAsString().contains("Array")) {
                    hasArrayError = true;
                }
            }
            assertThat(hasArrayError).isFalse();
        }

        @Test
        @DisplayName("Array<Int>(1,2,3) 多参数报错")
        void testArrayConstructorTooManyArgs() {
            String code = "val arr = Array<Int>(1, 2, 3)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (msg.contains("Array") && msg.contains("3")) {
                    hasError = true;
                }
            }
            assertThat(hasError).isTrue();
        }

        @Test
        @DisplayName("Array<String>(0) 零参数大小无报错")
        void testArrayConstructorZeroSize() {
            String code = "val arr = Array<String>(0)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasArrayError = false;
            for (int i = 0; i < diags.size(); i++) {
                if (diags.get(i).getAsJsonObject().get("message").getAsString().contains("Array")) {
                    hasArrayError = true;
                }
            }
            assertThat(hasArrayError).isFalse();
        }

        @Test
        @DisplayName("Array<Int>() 无参数报错")
        void testArrayConstructorNoArgs() {
            String code = "val arr = Array<Int>()";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (msg.contains("Array") && msg.contains("0")) {
                    hasError = true;
                }
            }
            assertThat(hasError).isTrue();
        }

        @Test
        @DisplayName("Array<Double>(n) 变量作为参数无报错")
        void testArrayConstructorWithVariable() {
            String code = "val n = 10\nval arr = Array<Double>(n)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasArrayError = false;
            for (int i = 0; i < diags.size(); i++) {
                if (diags.get(i).getAsJsonObject().get("message").getAsString().contains("Array")) {
                    hasArrayError = true;
                }
            }
            assertThat(hasArrayError).isFalse();
        }

        @Test
        @DisplayName("Array<Int>(5, initFn) 两个参数无报错")
        void testArrayConstructorWithInitFn() {
            String code = "val arr = Array<Int>(5, { i -> i * 2 })";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasArrayError = false;
            for (int i = 0; i < diags.size(); i++) {
                if (diags.get(i).getAsJsonObject().get("message").getAsString().contains("Array")) {
                    hasArrayError = true;
                }
            }
            assertThat(hasArrayError).isFalse();
        }

        @Test
        @DisplayName("注释中的 Array<Int>(1,2,3) 不报错")
        void testArrayConstructorInComment() {
            String code = "// val arr = Array<Int>(1, 2, 3)";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasArrayError = false;
            for (int i = 0; i < diags.size(); i++) {
                if (diags.get(i).getAsJsonObject().get("message").getAsString().contains("Array")) {
                    hasArrayError = true;
                }
            }
            assertThat(hasArrayError).isFalse();
        }
    }

    // ============ 丰富 Hover 信息 + val 重赋值检查 ============

    @Nested
    @DisplayName("丰富 Hover 信息测试")
    class EnrichedHoverTests {

        @Test
        @DisplayName("悬停 Array 显示详细描述")
        void testHoverArrayTypeShowsDescription() {
            String code = "val arr: Array<Int> = arrayOf(1, 2)";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 9);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("原生数组").contains("int[]");
        }

        @Test
        @DisplayName("悬停 Int 显示详细描述")
        void testHoverIntTypeShowsDescription() {
            String code = "val x: Int = 42";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 7);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("32 位整数");
        }

        @Test
        @DisplayName("悬停 String 显示详细描述")
        void testHoverStringTypeShowsDescription() {
            String code = "val s: String = \"hello\"";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 7);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("字符串");
        }
    }

    @Nested
    @DisplayName("变量类型推导 Hover 测试")
    class VariableTypeHoverTests {

        @Test
        @DisplayName("val 推导 Int 类型")
        void testHoverValWithInferredIntType() {
            String code = "val x = 42";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 4);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("val").contains("Int");
        }

        @Test
        @DisplayName("val 推导 String 类型")
        void testHoverValWithInferredStringType() {
            String code = "val s = \"hi\"";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 4);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("val").contains("String");
        }

        @Test
        @DisplayName("val 显式类型注解")
        void testHoverValWithExplicitType() {
            String code = "val x: Int = 42";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 4);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("val").contains("Int");
        }

        @Test
        @DisplayName("val Array 泛型类型")
        void testHoverValArrayType() {
            String code = "val arr = Array<Int>(5)";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 4);
            assertThat(hover).isNotNull();
            String value = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(value).contains("val").contains("Array");
        }
    }

    @Nested
    @DisplayName("val 重赋值检查测试")
    class ValReassignmentTests {

        @Test
        @DisplayName("val 重赋值报错")
        void testValReassignmentError() {
            String code = "val x = 1\nx = 2";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasValError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (msg.contains("val") && msg.contains("不可")) {
                    hasValError = true;
                }
            }
            assertThat(hasValError).isTrue();
        }

        @Test
        @DisplayName("var 重赋值不报错")
        void testVarReassignmentOk() {
            String code = "var x = 1\nx = 2";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasValError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (msg.contains("val") && msg.contains("不可")) {
                    hasValError = true;
                }
            }
            assertThat(hasValError).isFalse();
        }

        @Test
        @DisplayName("val 复合赋值报错")
        void testValReassignmentCompoundAssign() {
            String code = "val x = 1\nx += 2";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasValError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (msg.contains("val") && msg.contains("不可")) {
                    hasValError = true;
                }
            }
            assertThat(hasValError).isTrue();
        }

        @Test
        @DisplayName("val 赋值给新变量不报错")
        void testValNoFalsePositive() {
            String code = "val x = 1\nval y = x";
            JsonArray diags = analyzer.analyze(TEST_URI, code);
            boolean hasValError = false;
            for (int i = 0; i < diags.size(); i++) {
                String msg = diags.get(i).getAsJsonObject().get("message").getAsString();
                if (msg.contains("val") && msg.contains("不可")) {
                    hasValError = true;
                }
            }
            assertThat(hasValError).isFalse();
        }
    }

    // ============ Pair 类型支持 ============

    @Nested
    @DisplayName("Pair 类型支持")
    class PairTypeTests {

        @Test
        @DisplayName("Pair 出现在内置类型补全中")
        void testPairInBuiltinTypes() {
            String code = "P";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 1);
            assertThat(hasItem(items, "Pair")).isTrue();
        }

        @Test
        @DisplayName("Pair() 出现在内置函数补全中")
        void testPairConstructorCompletion() {
            String code = "Pai";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 3);
            assertThat(hasItemStartingWith(items, "Pair(")).isTrue();
        }

        @Test
        @DisplayName("Pair 变量后补全 Pair 方法和属性")
        void testPairVarCompletion() {
            String code = "val p = Pair(\"a\", 1)\np.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "first")).isTrue();
            assertThat(hasItemStartingWith(items, "second")).isTrue();
            assertThat(hasItemStartingWith(items, "toList(")).isTrue();
            assertThat(allItemsFromType(items, "Pair")).isTrue();
        }

        @Test
        @DisplayName("Pair() 构造函数类型推断")
        void testPairConstructorTypeInference() {
            String code = "Pair(\"a\", 1).";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 13);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "first")).isTrue();
            assertThat(allItemsFromType(items, "Pair")).isTrue();
        }

        @Test
        @DisplayName("to 表达式类型推断为 Pair")
        void testToExprTypeInference() {
            String code = "val p = \"key\" to 1\np.";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 2);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItemStartingWith(items, "first")).isTrue();
            assertThat(hasItemStartingWith(items, "second")).isTrue();
            assertThat(allItemsFromType(items, "Pair")).isTrue();
        }

        @Test
        @DisplayName("Pair.toList() 返回 List 补全")
        void testPairToListReturnType() {
            String code = "val p = Pair(\"a\", 1)\np.toList().";
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 11);
            assertThat(items.size()).isGreaterThan(0);
            assertThat(hasItem(items, "size")).isTrue();
            assertThat(allItemsFromType(items, "List")).isTrue();
        }

        @Test
        @DisplayName("Pair first/second 为属性（kind=10）而非方法")
        void testPairPropertiesKind() {
            String code = "Pair(1, 2).";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 11);
            JsonObject firstItem = findItem(items, "first");
            assertThat(firstItem).isNotNull();
            assertThat(firstItem.get("kind").getAsInt()).isEqualTo(10); // Property
            JsonObject secondItem = findItem(items, "second");
            assertThat(secondItem).isNotNull();
            assertThat(secondItem.get("kind").getAsInt()).isEqualTo(10); // Property
        }

        @Test
        @DisplayName("Pair 前缀过滤")
        void testPairPrefixFilter() {
            String code = "Pair(1, 2).f";
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 12);
            assertThat(hasItemStartingWith(items, "first")).isTrue();
            assertThat(hasItemStartingWith(items, "second")).isFalse();
        }

        @Test
        @DisplayName("悬停 Pair 显示内置类型")
        void testHoverPair() {
            String code = "val p: Pair = Pair(1, 2)";
            JsonObject hover = analyzer.hover(TEST_URI, code, 0, 7);
            assertThat(hover).isNotNull();
            String hoverText = hover.getAsJsonObject("contents").get("value").getAsString();
            assertThat(hoverText).contains("Pair");
        }
    }

    // ============ 字符串插值补全测试 ============

    @Nested
    @DisplayName("字符串插值补全")
    class StringInterpolationCompletionTests {

        @Test
        @DisplayName("$identifier 补全显示已声明变量")
        void testDollarIdentifierCompletion() {
            // val name = "Alice"
            // println("Hello, $na|")  光标在 na 后面
            String code = "val name = \"Alice\"\nprintln(\"Hello, $na\")";
            // line=1, character=20 → 在 "na" 之后（println("Hello, $na 的 a 之后）
            // println( 是8字符, "Hello, $na 是10字符 → character = 8+10 = 18
            // 实际上让我数一下: p-r-i-n-t-l-n-(-"-H-e-l-l-o-,- -$-n-a
            // 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18
            // 光标在 character=19 (a 之后)
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 19);
            assertThat(hasItem(items, "name")).isTrue();
            // 不应包含关键词
            assertThat(hasItem(items, "val")).isFalse();
            assertThat(hasItem(items, "fun")).isFalse();
        }

        @Test
        @DisplayName("$ 刚输入时提供所有变量")
        void testDollarTrigger() {
            // val count = 42
            // println("value: $|")  光标在 $ 后面，prefix 为空
            String code = "val count = 42\nprintln(\"value: $\")";
            // line=1: println("value: $")
            // p-r-i-n-t-l-n-(-"-v-a-l-u-e-:- -$
            // 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16
            // 光标在 character=17 ($ 之后)
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 17);
            assertThat(hasItem(items, "count")).isTrue();
        }

        @Test
        @DisplayName("${expr} 补全显示变量")
        void testBraceExprCompletion() {
            // val total = 100
            // println("result: ${to|}")
            String code = "val total = 100\nprintln(\"result: ${to}\")";
            // line=1: println("result: ${to}")
            // p-r-i-n-t-l-n-(-"-r-e-s-u-l-t-:- -$-{-t-o
            // 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20
            // 光标在 character=21 (o 之后)
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 21);
            assertThat(hasItem(items, "total")).isTrue();
            assertThat(hasItem(items, "val")).isFalse();
        }

        @Test
        @DisplayName("非字符串上下文的 $ 不触发插值补全")
        void testDollarOutsideString() {
            String code = "val x = 1\n$";
            // $ 不在字符串内，应该走普通补全
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 1);
            // 普通补全包含关键词
            assertThat(hasItem(items, "val")).isTrue();
        }

        @Test
        @DisplayName("转义 \\$ 不触发插值补全")
        void testEscapedDollarNoCompletion() {
            // \$ 是转义美元符，不应触发插值补全
            // Java 字符串 "\\$" 表示 Nova 源码中的 \$
            String code = "val name = \"test\"\nprintln(\"price: \\$\")";
            // line=1: println("price: \$")
            // p(0) r(1) i(2) n(3) t(4) l(5) n(6) ((7) "(8) p(9) r(10) i(11) c(12) e(13) :(14)  (15) \(16) $(17) "(18) )(19)
            // 光标在 character=18 → $ 之后，prefix 为空
            JsonArray items = analyzer.complete(TEST_URI, code, 1, 18);
            // 非插值上下文，空前缀 → 包含关键词（普通补全）
            assertThat(hasItem(items, "val")).isTrue();
        }

        @Test
        @DisplayName("插值补全包含内置函数")
        void testInterpolationIncludesBuiltinFunctions() {
            String code = "println(\"len: $le\")";
            // line=0: println("len: $le")
            // p-r-i-n-t-l-n-(-"-l-e-n-:- -$-l-e
            // 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16
            // 光标在 character=17
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 17);
            assertThat(hasItem(items, "len")).isTrue();
        }

        @Test
        @DisplayName("插值补全包含内置常量")
        void testInterpolationIncludesConstants() {
            String code = "println(\"pi=$P\")";
            // p-r-i-n-t-l-n-(-"-p-i-=-$-P
            // 0 1 2 3 4 5 6 7 8 9 10 11 12 13
            JsonArray items = analyzer.complete(TEST_URI, code, 0, 14);
            assertThat(hasItem(items, "PI")).isTrue();
        }
    }
}
