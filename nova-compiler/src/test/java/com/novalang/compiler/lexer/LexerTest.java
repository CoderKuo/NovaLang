package com.novalang.compiler.lexer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lexer 单元测试
 */
class LexerTest {

    /** 扫描源码，返回所有 token（含 EOF） */
    private List<Token> scan(String source) {
        return new Lexer(source, "<test>").scanTokens();
    }

    /** 扫描源码，返回非 EOF 非 NEWLINE 的 token 列表 */
    private List<Token> tokens(String source) {
        return scan(source).stream()
                .filter(t -> t.getType() != TokenType.EOF && t.getType() != TokenType.NEWLINE)
                .collect(Collectors.toList());
    }

    /** 扫描源码（保留 NEWLINE），返回非 EOF 的 token 列表 */
    private List<Token> tokensWithNewline(String source) {
        return scan(source).stream()
                .filter(t -> t.getType() != TokenType.EOF)
                .collect(Collectors.toList());
    }

    /** 扫描源码，捕获 stderr 错误输出 */
    private String scanWithErrors(String source) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        new Lexer(source, "<test>", ps).scanTokens();
        return baos.toString(StandardCharsets.UTF_8);
    }

    /** 断言单个 token 的类型 */
    private void assertSingleToken(String source, TokenType expected) {
        List<Token> toks = tokens(source);
        assertEquals(1, toks.size(), "Expected single token from: " + source);
        assertEquals(expected, toks.get(0).getType());
    }

    /** 断言单个 token 的类型和字面量 */
    private void assertSingleToken(String source, TokenType expectedType, Object expectedLiteral) {
        List<Token> toks = tokens(source);
        assertEquals(1, toks.size(), "Expected single token from: " + source);
        assertEquals(expectedType, toks.get(0).getType());
        assertEquals(expectedLiteral, toks.get(0).getLiteral());
    }

    // ================================================================
    // 单字符 Token
    // ================================================================

    @Nested
    @DisplayName("单字符 Token")
    class SingleCharTokenTests {

        @Test
        @DisplayName("括号类 token")
        void testBrackets() {
            assertSingleToken("(", TokenType.LPAREN);
            assertSingleToken(")", TokenType.RPAREN);
            assertSingleToken("{", TokenType.LBRACE);
            assertSingleToken("}", TokenType.RBRACE);
            assertSingleToken("[", TokenType.LBRACKET);
            assertSingleToken("]", TokenType.RBRACKET);
        }

        @Test
        @DisplayName("分隔符 token")
        void testDelimiters() {
            assertSingleToken(",", TokenType.COMMA);
            assertSingleToken(";", TokenType.SEMICOLON);
            assertSingleToken("@", TokenType.AT);
            assertSingleToken("#", TokenType.HASH);
            assertSingleToken("$", TokenType.DOLLAR);
        }

        @Test
        @DisplayName("_ 占位符 token")
        void testUnderscore() {
            assertSingleToken("_", TokenType.UNDERSCORE);
        }

        @Test
        @DisplayName("换行产生 NEWLINE token")
        void testNewline() {
            List<Token> toks = tokensWithNewline("\n");
            assertEquals(1, toks.size());
            assertEquals(TokenType.NEWLINE, toks.get(0).getType());
        }
    }

    // ================================================================
    // 点和范围操作符
    // ================================================================

    @Nested
    @DisplayName("点和范围操作符")
    class DotRangeTests {

        @Test
        @DisplayName(". 点操作符")
        void testDot() {
            assertSingleToken(".", TokenType.DOT);
        }

        @Test
        @DisplayName(".. 范围操作符")
        void testRange() {
            assertSingleToken("..", TokenType.RANGE);
        }

        @Test
        @DisplayName("..< 半开范围操作符")
        void testRangeExclusive() {
            assertSingleToken("..<", TokenType.RANGE_EXCLUSIVE);
        }

        @Test
        @DisplayName("a.b 成员访问")
        void testMemberAccess() {
            List<Token> toks = tokens("a.b");
            assertEquals(3, toks.size());
            assertEquals(TokenType.IDENTIFIER, toks.get(0).getType());
            assertEquals(TokenType.DOT, toks.get(1).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(2).getType());
        }

        @Test
        @DisplayName("0..10 整数范围")
        void testIntRange() {
            List<Token> toks = tokens("0..10");
            assertEquals(3, toks.size());
            assertEquals(TokenType.INT_LITERAL, toks.get(0).getType());
            assertEquals(TokenType.RANGE, toks.get(1).getType());
            assertEquals(TokenType.INT_LITERAL, toks.get(2).getType());
        }
    }

    // ================================================================
    // 冒号操作符
    // ================================================================

    @Nested
    @DisplayName("冒号操作符")
    class ColonTests {

        @Test
        @DisplayName(": 单冒号")
        void testColon() {
            assertSingleToken(":", TokenType.COLON);
        }

        @Test
        @DisplayName(":: 双冒号（方法引用）")
        void testDoubleColon() {
            assertSingleToken("::", TokenType.DOUBLE_COLON);
        }

        @Test
        @DisplayName("a::b 方法引用表达式")
        void testMethodRef() {
            List<Token> toks = tokens("a::b");
            assertEquals(3, toks.size());
            assertEquals(TokenType.DOUBLE_COLON, toks.get(1).getType());
        }
    }

    // ================================================================
    // 算术操作符
    // ================================================================

    @Nested
    @DisplayName("算术操作符")
    class ArithmeticTests {

        @Test
        @DisplayName("+ - * / % 基本操作符")
        void testBasicArithmetic() {
            assertSingleToken("+", TokenType.PLUS);
            assertSingleToken("-", TokenType.MINUS);
            assertSingleToken("*", TokenType.MUL);
            assertSingleToken("/", TokenType.DIV);
            assertSingleToken("%", TokenType.MOD);
        }

        @Test
        @DisplayName("+= -= *= /= %= 复合赋值")
        void testCompoundAssignment() {
            assertSingleToken("+=", TokenType.PLUS_ASSIGN);
            assertSingleToken("-=", TokenType.MINUS_ASSIGN);
            assertSingleToken("*=", TokenType.MUL_ASSIGN);
            assertSingleToken("/=", TokenType.DIV_ASSIGN);
            assertSingleToken("%=", TokenType.MOD_ASSIGN);
        }

        @Test
        @DisplayName("++ -- 递增递减")
        void testIncrementDecrement() {
            assertSingleToken("++", TokenType.INC);
            assertSingleToken("--", TokenType.DEC);
        }

        @Test
        @DisplayName("-> 箭头操作符")
        void testArrow() {
            assertSingleToken("->", TokenType.ARROW);
        }

        @Test
        @DisplayName("复合表达式 a+b*c")
        void testArithmeticExpression() {
            List<Token> toks = tokens("a+b*c");
            assertEquals(5, toks.size());
            assertEquals(TokenType.PLUS, toks.get(1).getType());
            assertEquals(TokenType.MUL, toks.get(3).getType());
        }
    }

    // ================================================================
    // 比较和等值操作符
    // ================================================================

    @Nested
    @DisplayName("比较和等值操作符")
    class ComparisonTests {

        @Test
        @DisplayName("= 赋值")
        void testAssign() {
            assertSingleToken("=", TokenType.ASSIGN);
        }

        @Test
        @DisplayName("== != 相等比较")
        void testEquality() {
            assertSingleToken("==", TokenType.EQ);
            assertSingleToken("!=", TokenType.NE);
        }

        @Test
        @DisplayName("=== !== 引用相等")
        void testReferenceEquality() {
            assertSingleToken("===", TokenType.REF_EQ);
            assertSingleToken("!==", TokenType.REF_NE);
        }

        @Test
        @DisplayName("< > <= >= 大小比较")
        void testComparison() {
            assertSingleToken("<", TokenType.LT);
            assertSingleToken(">", TokenType.GT);
            assertSingleToken("<=", TokenType.LE);
            assertSingleToken(">=", TokenType.GE);
        }

        @Test
        @DisplayName("=> 双箭头")
        void testDoubleArrow() {
            assertSingleToken("=>", TokenType.DOUBLE_ARROW);
        }
    }

    // ================================================================
    // 逻辑操作符
    // ================================================================

    @Nested
    @DisplayName("逻辑操作符")
    class LogicalTests {

        @Test
        @DisplayName("! 逻辑非")
        void testNot() {
            assertSingleToken("!", TokenType.NOT);
        }

        @Test
        @DisplayName("!! 非空断言")
        void testNotNull() {
            assertSingleToken("!!", TokenType.NOT_NULL);
        }

        @Test
        @DisplayName("&& 逻辑与")
        void testAnd() {
            assertSingleToken("&&", TokenType.AND);
        }

        @Test
        @DisplayName("|| 逻辑或")
        void testOr() {
            assertSingleToken("||", TokenType.OR);
        }

        @Test
        @DisplayName("&&= ||= 逻辑复合赋值")
        void testLogicalAssign() {
            assertSingleToken("&&=", TokenType.AND_ASSIGN);
            assertSingleToken("||=", TokenType.OR_ASSIGN);
        }

        @Test
        @DisplayName("|> 管道操作符")
        void testPipeline() {
            assertSingleToken("|>", TokenType.PIPELINE);
        }

        @Test
        @DisplayName("单个 & 报错")
        void testSingleAmpersandError() {
            String errors = scanWithErrors("&");
            assertTrue(errors.contains("&"));
            assertTrue(errors.contains("&&"));
        }

        @Test
        @DisplayName("单个 | 报错")
        void testSinglePipeError() {
            String errors = scanWithErrors("|");
            assertTrue(errors.contains("|"));
            assertTrue(errors.contains("||"));
        }
    }

    // ================================================================
    // ? 系列操作符
    // ================================================================

    @Nested
    @DisplayName("? 系列操作符")
    class QuestionMarkTests {

        @Test
        @DisplayName("? 可空标记")
        void testQuestion() {
            assertSingleToken("?", TokenType.QUESTION);
        }

        @Test
        @DisplayName("?. 安全调用")
        void testSafeDot() {
            List<Token> toks = tokens("a?.b");
            assertEquals(3, toks.size());
            assertEquals(TokenType.SAFE_DOT, toks.get(1).getType());
        }

        @Test
        @DisplayName("?[ 安全索引")
        void testSafeIndex() {
            List<Token> toks = tokens("a?[0]");
            assertEquals(4, toks.size());
            assertEquals(TokenType.SAFE_LBRACKET, toks.get(1).getType());
            assertEquals(TokenType.INT_LITERAL, toks.get(2).getType());
            assertEquals(TokenType.RBRACKET, toks.get(3).getType());
        }

        @Test
        @DisplayName("?: Elvis 操作符")
        void testElvis() {
            List<Token> toks = tokens("a ?: b");
            assertEquals(3, toks.size());
            assertEquals(TokenType.ELVIS, toks.get(1).getType());
        }

        @Test
        @DisplayName("??= 空值合并赋值")
        void testNullCoalesceAssign() {
            List<Token> toks = tokens("a ??= b");
            assertEquals(3, toks.size());
            assertEquals(TokenType.NULL_COALESCE_ASSIGN, toks.get(1).getType());
        }

        @Test
        @DisplayName("?? 给出明确错误提示")
        void testDoubleQuestionMarkError() {
            String errors = scanWithErrors("a ?? b");
            assertTrue(errors.contains("??"));
            assertTrue(errors.contains("?:"));
        }

        @Test
        @DisplayName("? 系列连续使用")
        void testChained() {
            // a?.b ?: c
            List<Token> toks = tokens("a?.b ?: c");
            assertEquals(5, toks.size());
            assertEquals(TokenType.SAFE_DOT, toks.get(1).getType());
            assertEquals(TokenType.ELVIS, toks.get(3).getType());
        }
    }

    // ================================================================
    // 整数字面量
    // ================================================================

    @Nested
    @DisplayName("整数字面量")
    class IntLiteralTests {

        // ---------- 正常值 ----------

        @Test
        @DisplayName("十进制整数")
        void testDecimal() {
            assertSingleToken("0", TokenType.INT_LITERAL, 0);
            assertSingleToken("42", TokenType.INT_LITERAL, 42);
            assertSingleToken("2147483647", TokenType.INT_LITERAL, Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("十六进制整数")
        void testHex() {
            assertSingleToken("0xFF", TokenType.INT_LITERAL, 255);
            assertSingleToken("0x0", TokenType.INT_LITERAL, 0);
            assertSingleToken("0xDEAD", TokenType.INT_LITERAL, 0xDEAD);
        }

        @Test
        @DisplayName("二进制整数")
        void testBinary() {
            assertSingleToken("0b0", TokenType.INT_LITERAL, 0);
            assertSingleToken("0b1010", TokenType.INT_LITERAL, 10);
            assertSingleToken("0b11111111", TokenType.INT_LITERAL, 255);
        }

        @Test
        @DisplayName("八进制整数")
        void testOctal() {
            assertSingleToken("0o0", TokenType.INT_LITERAL, 0);
            assertSingleToken("0o77", TokenType.INT_LITERAL, 63);
            assertSingleToken("0o777", TokenType.INT_LITERAL, 511);
        }

        @Test
        @DisplayName("Long 后缀")
        void testLongSuffix() {
            assertSingleToken("42L", TokenType.LONG_LITERAL, 42L);
            assertSingleToken("0l", TokenType.LONG_LITERAL, 0L);
            assertSingleToken("0xFFL", TokenType.LONG_LITERAL, 255L);
            assertSingleToken("0b1010L", TokenType.LONG_LITERAL, 10L);
            assertSingleToken("0o77L", TokenType.LONG_LITERAL, 63L);
        }

        // ---------- 下划线分隔符 ----------

        @Test
        @DisplayName("十进制下划线")
        void testDecimalUnderscore() {
            assertSingleToken("1_000_000", TokenType.INT_LITERAL, 1_000_000);
            assertSingleToken("1_000_000L", TokenType.LONG_LITERAL, 1_000_000L);
        }

        @Test
        @DisplayName("十六进制下划线")
        void testHexUnderscore() {
            assertSingleToken("0xFF_FF", TokenType.INT_LITERAL, 0xFFFF);
            assertSingleToken("0xFF_FF_FF_FFL", TokenType.LONG_LITERAL, 0xFFFFFFFFL);
        }

        @Test
        @DisplayName("二进制下划线")
        void testBinaryUnderscore() {
            assertSingleToken("0b1010_0101", TokenType.INT_LITERAL, 0b10100101);
        }

        @Test
        @DisplayName("八进制下划线")
        void testOctalUnderscore() {
            assertSingleToken("0o77_77", TokenType.INT_LITERAL, 07777);
        }

        @Test
        @DisplayName("连续下划线")
        void testConsecutiveUnderscores() {
            assertSingleToken("1__000", TokenType.INT_LITERAL, 1000);
        }

        @Test
        @DisplayName("0 开头的十进制数不误判为八进制")
        void testLeadingZeroDecimal() {
            // 0 后面跟非 x/b/o 的数字，当前行为是 0 + 后续数字
            List<Token> toks = tokens("0");
            assertEquals(1, toks.size());
            assertEquals(TokenType.INT_LITERAL, toks.get(0).getType());
            assertEquals(0, toks.get(0).getLiteral());
        }
    }

    // ================================================================
    // 浮点数字面量
    // ================================================================

    @Nested
    @DisplayName("浮点数字面量")
    class FloatLiteralTests {

        @Test
        @DisplayName("Double 字面量")
        void testDouble() {
            List<Token> toks = tokens("3.14");
            assertEquals(1, toks.size());
            assertEquals(TokenType.DOUBLE_LITERAL, toks.get(0).getType());
            assertEquals(3.14, (Double) toks.get(0).getLiteral(), 0.001);
        }

        @Test
        @DisplayName("Float 后缀")
        void testFloatSuffix() {
            List<Token> toks = tokens("3.14f");
            assertEquals(1, toks.size());
            assertEquals(TokenType.FLOAT_LITERAL, toks.get(0).getType());
            assertEquals(3.14f, (Float) toks.get(0).getLiteral(), 0.001f);

            toks = tokens("1.0F");
            assertEquals(TokenType.FLOAT_LITERAL, toks.get(0).getType());
        }

        @Test
        @DisplayName("科学计数法")
        void testScientificNotation() {
            List<Token> toks = tokens("1e10");
            assertEquals(TokenType.DOUBLE_LITERAL, toks.get(0).getType());
            assertEquals(1e10, (Double) toks.get(0).getLiteral(), 1.0);

            toks = tokens("2.5e-3");
            assertEquals(2.5e-3, (Double) toks.get(0).getLiteral(), 1e-6);

            toks = tokens("1.0e+2");
            assertEquals(100.0, (Double) toks.get(0).getLiteral(), 0.1);
        }

        @Test
        @DisplayName("浮点下划线")
        void testFloatUnderscore() {
            List<Token> toks = tokens("1_234.567_89");
            assertEquals(1, toks.size());
            assertEquals(TokenType.DOUBLE_LITERAL, toks.get(0).getType());
            assertEquals(1234.56789, (Double) toks.get(0).getLiteral(), 0.0001);
        }

        @Test
        @DisplayName("Float 后缀 + 下划线")
        void testFloatSuffixUnderscore() {
            List<Token> toks = tokens("1_000.5f");
            assertEquals(TokenType.FLOAT_LITERAL, toks.get(0).getType());
            assertEquals(1000.5f, (Float) toks.get(0).getLiteral(), 0.01f);
        }

        @Test
        @DisplayName("科学计数法 + 下划线")
        void testScientificUnderscore() {
            List<Token> toks = tokens("1_0e1_0");
            assertEquals(TokenType.DOUBLE_LITERAL, toks.get(0).getType());
            assertEquals(10e10, (Double) toks.get(0).getLiteral(), 1.0);
        }

        @Test
        @DisplayName("科学计数法 + Float 后缀")
        void testScientificFloat() {
            List<Token> toks = tokens("1.0e+2f");
            assertEquals(1, toks.size());
            assertEquals(TokenType.FLOAT_LITERAL, toks.get(0).getType());
            assertEquals(100.0f, (Float) toks.get(0).getLiteral(), 0.1f);
        }

        @Test
        @DisplayName("无小数点科学计数法 + Float 后缀")
        void testScientificNoDecimalFloat() {
            List<Token> toks = tokens("1e10f");
            assertEquals(1, toks.size());
            assertEquals(TokenType.FLOAT_LITERAL, toks.get(0).getType());
        }

        @Test
        @DisplayName("负指数科学计数法")
        void testNegativeExponent() {
            List<Token> toks = tokens("5e-3");
            assertEquals(1, toks.size());
            assertEquals(TokenType.DOUBLE_LITERAL, toks.get(0).getType());
            assertEquals(5e-3, (Double) toks.get(0).getLiteral(), 1e-6);
        }

        @Test
        @DisplayName("0.0 零值")
        void testZero() {
            List<Token> toks = tokens("0.0");
            assertEquals(TokenType.DOUBLE_LITERAL, toks.get(0).getType());
            assertEquals(0.0, (Double) toks.get(0).getLiteral(), 0.0);
        }

        @Test
        @DisplayName(". 后无数字不是浮点数（是成员访问）")
        void testDotWithoutDigitIsNotFloat() {
            // 42.toString → INT DOT IDENTIFIER
            List<Token> toks = tokens("42.toString");
            assertEquals(3, toks.size());
            assertEquals(TokenType.INT_LITERAL, toks.get(0).getType());
            assertEquals(TokenType.DOT, toks.get(1).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(2).getType());
        }
    }

    // ================================================================
    // 字符串字面量
    // ================================================================

    @Nested
    @DisplayName("字符串字面量")
    class StringLiteralTests {

        // ---------- 正常值 ----------

        @Test
        @DisplayName("基本字符串")
        void testBasicString() {
            assertSingleToken("\"hello\"", TokenType.STRING_LITERAL, "hello");
            assertSingleToken("\"\"", TokenType.STRING_LITERAL, "");
        }

        @Test
        @DisplayName("转义字符")
        void testEscapeSequences() {
            assertSingleToken("\"\\n\"", TokenType.STRING_LITERAL, "\n");
            assertSingleToken("\"\\t\"", TokenType.STRING_LITERAL, "\t");
            assertSingleToken("\"\\r\"", TokenType.STRING_LITERAL, "\r");
            assertSingleToken("\"\\\\\"", TokenType.STRING_LITERAL, "\\");
            assertSingleToken("\"\\\"\"", TokenType.STRING_LITERAL, "\"");
            assertSingleToken("\"\\'\"", TokenType.STRING_LITERAL, "'");
            assertSingleToken("\"\\$\"", TokenType.STRING_LITERAL, "$");
        }

        @Test
        @DisplayName("Unicode 转义")
        void testUnicodeEscape() {
            assertSingleToken("\"\\u0041\"", TokenType.STRING_LITERAL, "A");
            assertSingleToken("\"\\u4F60\"", TokenType.STRING_LITERAL, "\u4F60");
        }

        @Test
        @DisplayName("\\b \\f 转义字符")
        void testMoreEscapes() {
            assertSingleToken("\"\\b\"", TokenType.STRING_LITERAL, "\b");
            assertSingleToken("\"\\f\"", TokenType.STRING_LITERAL, "\f");
        }

        @Test
        @DisplayName("字符串插值语法保留在 token 中")
        void testStringInterpolation() {
            // 插值 ${expr} 在字符串 token 内部保留
            List<Token> toks = tokens("\"x = ${x}\"");
            assertEquals(1, toks.size());
            assertEquals(TokenType.STRING_LITERAL, toks.get(0).getType());
            assertTrue(((String) toks.get(0).getLiteral()).contains("${x}"));
        }

        // ---------- 异常值 ----------

        @Test
        @DisplayName("未终止字符串报错")
        void testUnterminatedString() {
            String errors = scanWithErrors("\"hello");
            assertTrue(errors.contains("Unterminated"));
        }

        @Test
        @DisplayName("字符串中包含换行报错")
        void testStringWithNewline() {
            String errors = scanWithErrors("\"hello\nworld\"");
            assertTrue(errors.contains("Unterminated"));
        }

        @Test
        @DisplayName("无效转义字符报错")
        void testInvalidEscape() {
            String errors = scanWithErrors("\"\\z\"");
            assertTrue(errors.contains("Invalid escape"));
        }

        @Test
        @DisplayName("无效 Unicode 转义报错")
        void testInvalidUnicodeEscape() {
            String errors = scanWithErrors("\"\\uGGGG\"");
            assertTrue(errors.contains("Invalid unicode escape"));
        }

        @Test
        @DisplayName("不完整的 Unicode 转义报错")
        void testIncompleteUnicodeEscape() {
            String errors = scanWithErrors("\"\\u00\"");
            assertTrue(errors.length() > 0);
        }
    }

    // ================================================================
    // 原始字符串
    // ================================================================

    @Nested
    @DisplayName("原始字符串")
    class RawStringTests {

        @Test
        @DisplayName("基本原始字符串")
        void testBasicRawString() {
            assertSingleToken("r\"hello\"", TokenType.RAW_STRING, "hello");
        }

        @Test
        @DisplayName("原始字符串中反斜杠不转义")
        void testRawStringNoEscape() {
            assertSingleToken("r\"\\n\\t\"", TokenType.RAW_STRING, "\\n\\t");
        }

        @Test
        @DisplayName("空原始字符串")
        void testEmptyRawString() {
            assertSingleToken("r\"\"", TokenType.RAW_STRING, "");
        }

        @Test
        @DisplayName("原始字符串含换行")
        void testRawStringWithNewline() {
            List<Token> toks = tokens("r\"line1\nline2\"");
            assertEquals(1, toks.size());
            assertEquals(TokenType.RAW_STRING, toks.get(0).getType());
            String value = (String) toks.get(0).getLiteral();
            assertTrue(value.contains("line1"));
            assertTrue(value.contains("line2"));
        }

        @Test
        @DisplayName("原始字符串中反斜杠保留原样")
        void testRawStringBackslashPreserved() {
            assertSingleToken("r\"C:\\Users\\admin\"", TokenType.RAW_STRING, "C:\\Users\\admin");
        }

        @Test
        @DisplayName("未终止原始字符串报错")
        void testUnterminatedRawString() {
            String errors = scanWithErrors("r\"hello");
            assertTrue(errors.contains("Unterminated"));
        }

        @Test
        @DisplayName("r 后面无引号是标识符")
        void testRAsIdentifier() {
            assertSingleToken("r", TokenType.IDENTIFIER);
            List<Token> toks = tokens("result");
            assertEquals(1, toks.size());
            assertEquals(TokenType.IDENTIFIER, toks.get(0).getType());
            assertEquals("result", toks.get(0).getLexeme());
        }
    }

    // ================================================================
    // 多行字符串
    // ================================================================

    @Nested
    @DisplayName("多行字符串")
    class MultilineStringTests {

        // ---------- 正常值 ----------

        @Test
        @DisplayName("基本多行字符串")
        void testBasic() {
            assertSingleToken("\"\"\"hello\"\"\"", TokenType.MULTILINE_STRING, "hello");
        }

        @Test
        @DisplayName("空多行字符串")
        void testEmpty() {
            assertSingleToken("\"\"\"\"\"\"", TokenType.MULTILINE_STRING, "");
        }

        @Test
        @DisplayName("含换行的多行字符串")
        void testWithNewlines() {
            List<Token> toks = tokens("\"\"\"\nline1\nline2\n\"\"\"");
            assertEquals(1, toks.size());
            String value = (String) toks.get(0).getLiteral();
            assertTrue(value.contains("line1"));
            assertTrue(value.contains("line2"));
        }

        @Test
        @DisplayName("含缩进的多行字符串")
        void testWithIndentation() {
            String src = "\"\"\"\n    hello\n    world\n\"\"\"";
            List<Token> toks = tokens(src);
            assertEquals(1, toks.size());
            String value = (String) toks.get(0).getLiteral();
            assertTrue(value.contains("    hello"));
        }

        // ---------- 边缘值：内部引号 ----------

        @Test
        @DisplayName("内容含单个双引号")
        void testSingleQuoteInContent() {
            // """a"b"""
            List<Token> toks = tokens("\"\"\"a\"b\"\"\"");
            assertEquals(1, toks.size());
            assertEquals("a\"b", toks.get(0).getLiteral());
        }

        @Test
        @DisplayName("内容含两个连续双引号")
        void testDoubleQuoteInContent() {
            // """a""b"""
            List<Token> toks = tokens("\"\"\"a\"\"b\"\"\"");
            assertEquals(1, toks.size());
            assertEquals("a\"\"b", toks.get(0).getLiteral());
        }

        @Test
        @DisplayName("两个连续双引号在开头")
        void testDoubleQuoteAtStart() {
            // """""b"""  →  ""b
            List<Token> toks = tokens("\"\"\"\"\"b\"\"\"");
            assertEquals(1, toks.size());
            assertEquals("\"\"b", toks.get(0).getLiteral());
        }

        @Test
        @DisplayName("贪心匹配：第一个 \"\"\" 闭合字符串")
        void testGreedyClose() {
            // """ab """" → content "ab ", remaining "
            List<Token> toks = tokens("\"\"\"ab \"\"\"\"");
            assertTrue(toks.size() >= 1);
            assertEquals(TokenType.MULTILINE_STRING, toks.get(0).getType());
            assertEquals("ab ", toks.get(0).getLiteral());
        }

        // ---------- 异常值 ----------

        @Test
        @DisplayName("未终止多行字符串报错")
        void testUnterminated() {
            String errors = scanWithErrors("\"\"\"hello");
            assertTrue(errors.contains("Unterminated"));
        }

        @Test
        @DisplayName("只有两个引号不终止")
        void testTwoQuotesNotTerminator() {
            String errors = scanWithErrors("\"\"\"hello\"\"");
            assertTrue(errors.contains("Unterminated"));
        }

        @Test
        @DisplayName("开头三个引号紧接 EOF")
        void testJustOpenQuotes() {
            String errors = scanWithErrors("\"\"\"");
            assertTrue(errors.contains("Unterminated"));
        }
    }

    // ================================================================
    // 字符字面量
    // ================================================================

    @Nested
    @DisplayName("字符字面量")
    class CharLiteralTests {

        @Test
        @DisplayName("普通字符")
        void testBasicChar() {
            assertSingleToken("'a'", TokenType.CHAR_LITERAL, 'a');
            assertSingleToken("'Z'", TokenType.CHAR_LITERAL, 'Z');
            assertSingleToken("'0'", TokenType.CHAR_LITERAL, '0');
        }

        @Test
        @DisplayName("转义字符")
        void testEscapedChar() {
            assertSingleToken("'\\n'", TokenType.CHAR_LITERAL, '\n');
            assertSingleToken("'\\t'", TokenType.CHAR_LITERAL, '\t');
            assertSingleToken("'\\''", TokenType.CHAR_LITERAL, '\'');
            assertSingleToken("'\\\\'", TokenType.CHAR_LITERAL, '\\');
        }

        @Test
        @DisplayName("\\b \\f 转义字符")
        void testMoreCharEscapes() {
            assertSingleToken("'\\b'", TokenType.CHAR_LITERAL, '\b');
            assertSingleToken("'\\f'", TokenType.CHAR_LITERAL, '\f');
        }

        @Test
        @DisplayName("Unicode 字符")
        void testUnicodeChar() {
            assertSingleToken("'\\u0041'", TokenType.CHAR_LITERAL, 'A');
        }

        @Test
        @DisplayName("未终止字符报错")
        void testUnterminatedChar() {
            String errors = scanWithErrors("'a");
            assertTrue(errors.contains("Unterminated"));
        }

        @Test
        @DisplayName("空字符报错")
        void testEmptyChar() {
            String errors = scanWithErrors("''");
            assertTrue(errors.length() > 0);
        }
    }

    // ================================================================
    // 关键词
    // ================================================================

    @Nested
    @DisplayName("关键词")
    class KeywordTests {

        @Test
        @DisplayName("声明关键词")
        void testDeclarationKeywords() {
            assertSingleToken("val", TokenType.KW_VAL);
            assertSingleToken("var", TokenType.KW_VAR);
            assertSingleToken("fun", TokenType.KW_FUN);
            assertSingleToken("class", TokenType.KW_CLASS);
            assertSingleToken("interface", TokenType.KW_INTERFACE);
            assertSingleToken("object", TokenType.KW_OBJECT);
            assertSingleToken("enum", TokenType.KW_ENUM);
            // constructor/init 是软关键词，Lexer 层面为 IDENTIFIER
            assertSingleToken("constructor", TokenType.IDENTIFIER);
            assertSingleToken("init", TokenType.IDENTIFIER);
            assertSingleToken("package", TokenType.KW_PACKAGE);
            assertSingleToken("import", TokenType.KW_IMPORT);
        }

        @Test
        @DisplayName("修饰符关键词")
        void testModifierKeywords() {
            assertSingleToken("public", TokenType.KW_PUBLIC);
            assertSingleToken("private", TokenType.KW_PRIVATE);
            assertSingleToken("protected", TokenType.KW_PROTECTED);
            assertSingleToken("internal", TokenType.KW_INTERNAL);
            assertSingleToken("abstract", TokenType.KW_ABSTRACT);
            assertSingleToken("sealed", TokenType.KW_SEALED);
            assertSingleToken("open", TokenType.KW_OPEN);
            assertSingleToken("override", TokenType.KW_OVERRIDE);
            assertSingleToken("inline", TokenType.KW_INLINE);
            assertSingleToken("static", TokenType.KW_STATIC);
        }

        @Test
        @DisplayName("控制流关键词")
        void testControlFlowKeywords() {
            assertSingleToken("if", TokenType.KW_IF);
            assertSingleToken("else", TokenType.KW_ELSE);
            assertSingleToken("when", TokenType.KW_WHEN);
            assertSingleToken("for", TokenType.KW_FOR);
            assertSingleToken("while", TokenType.KW_WHILE);
            assertSingleToken("do", TokenType.KW_DO);
            assertSingleToken("return", TokenType.KW_RETURN);
            assertSingleToken("break", TokenType.KW_BREAK);
            assertSingleToken("continue", TokenType.KW_CONTINUE);
            assertSingleToken("throw", TokenType.KW_THROW);
            assertSingleToken("try", TokenType.KW_TRY);
            assertSingleToken("catch", TokenType.KW_CATCH);
            assertSingleToken("finally", TokenType.KW_FINALLY);
        }

        @Test
        @DisplayName("类型操作关键词")
        void testTypeKeywords() {
            assertSingleToken("is", TokenType.KW_IS);
            assertSingleToken("as", TokenType.KW_AS);
            assertSingleToken("in", TokenType.KW_IN);
            // out/where 是软关键词，Lexer 层面为 IDENTIFIER
            assertSingleToken("out", TokenType.IDENTIFIER);
            assertSingleToken("where", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("字面量关键词")
        void testLiteralKeywords() {
            assertSingleToken("true", TokenType.KW_TRUE);
            assertSingleToken("false", TokenType.KW_FALSE);
            assertSingleToken("null", TokenType.KW_NULL);
        }

        @Test
        @DisplayName("内置类型关键词")
        void testBuiltinTypeKeywords() {
            assertSingleToken("Int", TokenType.KW_INT);
            assertSingleToken("Long", TokenType.KW_LONG);
            assertSingleToken("Float", TokenType.KW_FLOAT);
            assertSingleToken("Double", TokenType.KW_DOUBLE);
            assertSingleToken("Boolean", TokenType.KW_BOOLEAN);
            assertSingleToken("Char", TokenType.KW_CHAR);
            assertSingleToken("String", TokenType.KW_STRING);
            assertSingleToken("Any", TokenType.KW_ANY);
            assertSingleToken("Unit", TokenType.KW_UNIT);
            assertSingleToken("Nothing", TokenType.KW_NOTHING);
        }

        @Test
        @DisplayName("特殊关键词")
        void testSpecialKeywords() {
            assertSingleToken("this", TokenType.KW_THIS);
            assertSingleToken("super", TokenType.KW_SUPER);
            // it 是软关键词，Lexer 层面为 IDENTIFIER
            assertSingleToken("it", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("更多声明关键词")
        void testMoreDeclarationKeywords() {
            assertSingleToken("typealias", TokenType.KW_TYPEALIAS);
            assertSingleToken("module", TokenType.KW_MODULE);
        }

        @Test
        @DisplayName("更多修饰符关键词")
        void testMoreModifierKeywords() {
            assertSingleToken("final", TokenType.KW_FINAL);
            assertSingleToken("const", TokenType.KW_CONST);
            assertSingleToken("crossinline", TokenType.KW_CROSSINLINE);
            assertSingleToken("reified", TokenType.KW_REIFIED);
            assertSingleToken("operator", TokenType.KW_OPERATOR);
            assertSingleToken("vararg", TokenType.KW_VARARG);
            assertSingleToken("suspend", TokenType.KW_SUSPEND);
            assertSingleToken("companion", TokenType.KW_COMPANION);
        }

        @Test
        @DisplayName("更多控制流关键词")
        void testMoreControlFlowKeywords() {
            // guard/step 是软关键词，Lexer 层面为 IDENTIFIER
            assertSingleToken("guard", TokenType.IDENTIFIER);
            assertSingleToken("use", TokenType.KW_USE);
            assertSingleToken("step", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("Array 内置类型")
        void testArrayKeyword() {
            assertSingleToken("Array", TokenType.KW_ARRAY);
        }

        @Test
        @DisplayName("更多特殊关键词")
        void testMoreSpecialKeywords() {
            assertSingleToken("await", TokenType.KW_AWAIT);
            // launch/scope 是普通标识符，通过 Builtins 注册为全局函数
            assertSingleToken("launch", TokenType.IDENTIFIER);
            assertSingleToken("scope", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("关键词前缀的标识符不误识别")
        void testKeywordPrefixAsIdentifier() {
            assertSingleToken("value", TokenType.IDENTIFIER);
            assertSingleToken("variable", TokenType.IDENTIFIER);
            assertSingleToken("function", TokenType.IDENTIFIER);
            assertSingleToken("classify", TokenType.IDENTIFIER);
            assertSingleToken("internal2", TokenType.IDENTIFIER);
            assertSingleToken("ifElse", TokenType.IDENTIFIER);
            assertSingleToken("forLoop", TokenType.IDENTIFIER);
        }
    }

    // ================================================================
    // 标识符
    // ================================================================

    @Nested
    @DisplayName("标识符")
    class IdentifierTests {

        @Test
        @DisplayName("普通标识符")
        void testBasicIdentifier() {
            assertSingleToken("foo", TokenType.IDENTIFIER);
            assertSingleToken("bar123", TokenType.IDENTIFIER);
            assertSingleToken("camelCase", TokenType.IDENTIFIER);
            assertSingleToken("PascalCase", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("下划线开头标识符")
        void testUnderscorePrefix() {
            List<Token> toks = tokens("_private");
            assertEquals(1, toks.size());
            assertEquals(TokenType.IDENTIFIER, toks.get(0).getType());
            assertEquals("_private", toks.get(0).getLexeme());
        }

        @Test
        @DisplayName("下划线中间标识符")
        void testUnderscoreMiddle() {
            assertSingleToken("my_var", TokenType.IDENTIFIER);
            assertSingleToken("UPPER_CASE", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("下划线+数字标识符")
        void testUnderscoreDigit() {
            assertSingleToken("_123", TokenType.IDENTIFIER);
            List<Token> toks = tokens("_123");
            assertEquals("_123", toks.get(0).getLexeme());
        }

        @Test
        @DisplayName("多个下划线标识符")
        void testMultipleUnderscores() {
            assertSingleToken("__init__", TokenType.IDENTIFIER);
            assertSingleToken("__", TokenType.IDENTIFIER);
        }

        @Test
        @DisplayName("独立 _ 是占位符不是标识符")
        void testStandaloneUnderscore() {
            assertSingleToken("_", TokenType.UNDERSCORE);
        }

        @Test
        @DisplayName("数字后跟标识符分开解析")
        void testNumberThenIdentifier() {
            List<Token> toks = tokens("42 + _x");
            assertEquals(3, toks.size());
            assertEquals(TokenType.INT_LITERAL, toks.get(0).getType());
            assertEquals(TokenType.PLUS, toks.get(1).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(2).getType());
            assertEquals("_x", toks.get(2).getLexeme());
        }
    }

    // ================================================================
    // 注释
    // ================================================================

    @Nested
    @DisplayName("注释")
    class CommentTests {

        @Test
        @DisplayName("单行注释被忽略")
        void testSingleLineComment() {
            List<Token> toks = tokens("42 // this is a comment");
            assertEquals(1, toks.size());
            assertEquals(TokenType.INT_LITERAL, toks.get(0).getType());
        }

        @Test
        @DisplayName("多行注释被忽略")
        void testBlockComment() {
            List<Token> toks = tokens("a /* comment */ b");
            assertEquals(2, toks.size());
            assertEquals("a", toks.get(0).getLexeme());
            assertEquals("b", toks.get(1).getLexeme());
        }

        @Test
        @DisplayName("嵌套多行注释")
        void testNestedBlockComment() {
            List<Token> toks = tokens("a /* outer /* inner */ still comment */ b");
            assertEquals(2, toks.size());
            assertEquals("a", toks.get(0).getLexeme());
            assertEquals("b", toks.get(1).getLexeme());
        }

        @Test
        @DisplayName("注释后还有代码")
        void testCommentThenCode() {
            List<Token> toks = tokens("// comment\n42");
            assertEquals(1, toks.size());
            assertEquals(42, toks.get(0).getLiteral());
        }

        @Test
        @DisplayName("// 在字符串内不是注释")
        void testCommentInsideString() {
            assertSingleToken("\"a // b\"", TokenType.STRING_LITERAL, "a // b");
        }

        @Test
        @DisplayName("未终止块注释到达 EOF")
        void testUnterminatedBlockComment() {
            List<Token> toks = tokens("a /* unterminated");
            // a 被扫描出，块注释未闭合产生 ERROR token
            assertEquals(2, toks.size());
            assertEquals("a", toks.get(0).getLexeme());
            assertEquals(TokenType.ERROR, toks.get(1).getType());
        }

        @Test
        @DisplayName("空块注释")
        void testEmptyBlockComment() {
            List<Token> toks = tokens("a /**/ b");
            assertEquals(2, toks.size());
            assertEquals("a", toks.get(0).getLexeme());
            assertEquals("b", toks.get(1).getLexeme());
        }
    }

    // ================================================================
    // 空白处理
    // ================================================================

    @Nested
    @DisplayName("空白处理")
    class WhitespaceTests {

        @Test
        @DisplayName("空格和制表符被忽略")
        void testSpacesAndTabs() {
            List<Token> toks = tokens("  a  \t  b  ");
            assertEquals(2, toks.size());
            assertEquals("a", toks.get(0).getLexeme());
            assertEquals("b", toks.get(1).getLexeme());
        }

        @Test
        @DisplayName("空源码只产生 EOF")
        void testEmptySource() {
            List<Token> all = scan("");
            assertEquals(1, all.size());
            assertEquals(TokenType.EOF, all.get(0).getType());
        }

        @Test
        @DisplayName("纯空白只产生 EOF")
        void testPureWhitespace() {
            List<Token> toks = tokens("   \t  ");
            assertEquals(0, toks.size());
        }
    }

    // ================================================================
    // 行号和列号
    // ================================================================

    @Nested
    @DisplayName("位置信息")
    class PositionTests {

        @Test
        @DisplayName("第一行 token 的行号为 1")
        void testFirstLineNumber() {
            List<Token> toks = tokens("hello");
            assertEquals(1, toks.get(0).getLine());
        }

        @Test
        @DisplayName("换行后行号递增")
        void testLineIncrement() {
            List<Token> all = scan("a\nb");
            // a, NEWLINE, b, EOF
            Token b = all.stream()
                    .filter(t -> "b".equals(t.getLexeme()))
                    .findFirst().orElseThrow();
            assertEquals(2, b.getLine());
        }

        @Test
        @DisplayName("多行字符串内换行追踪行号")
        void testMultilineStringLineTracking() {
            List<Token> all = scan("\"\"\"\nline1\nline2\n\"\"\" + x");
            Token plus = all.stream()
                    .filter(t -> t.getType() == TokenType.PLUS)
                    .findFirst().orElseThrow();
            assertEquals(4, plus.getLine());
        }
    }

    // ================================================================
    // 复合语句扫描
    // ================================================================

    @Nested
    @DisplayName("复合语句")
    class CompoundStatementTests {

        @Test
        @DisplayName("变量声明")
        void testValDeclaration() {
            List<Token> toks = tokens("val x = 42");
            assertEquals(4, toks.size());
            assertEquals(TokenType.KW_VAL, toks.get(0).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(1).getType());
            assertEquals(TokenType.ASSIGN, toks.get(2).getType());
            assertEquals(TokenType.INT_LITERAL, toks.get(3).getType());
        }

        @Test
        @DisplayName("函数声明")
        void testFunDeclaration() {
            List<Token> toks = tokens("fun add(a: Int, b: Int): Int");
            assertEquals(TokenType.KW_FUN, toks.get(0).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(1).getType());
            assertEquals("add", toks.get(1).getLexeme());
            assertEquals(TokenType.LPAREN, toks.get(2).getType());
        }

        @Test
        @DisplayName("if-else 语句")
        void testIfElse() {
            List<Token> toks = tokens("if (x > 0) a else b");
            assertEquals(TokenType.KW_IF, toks.get(0).getType());
            assertEquals(TokenType.LPAREN, toks.get(1).getType());
            assertTrue(toks.stream().anyMatch(t -> t.getType() == TokenType.KW_ELSE));
        }

        @Test
        @DisplayName("for-in 循环")
        void testForIn() {
            List<Token> toks = tokens("for (i in 0..10)");
            assertEquals(TokenType.KW_FOR, toks.get(0).getType());
            assertTrue(toks.stream().anyMatch(t -> t.getType() == TokenType.KW_IN));
            assertTrue(toks.stream().anyMatch(t -> t.getType() == TokenType.RANGE));
        }

        @Test
        @DisplayName("try-catch-finally 语句 token 序列")
        void testTryCatchFinally() {
            List<Token> toks = tokens("try { } catch (e: Exception) { } finally { }");
            assertEquals(TokenType.KW_TRY, toks.get(0).getType());
            assertTrue(toks.stream().anyMatch(t -> t.getType() == TokenType.KW_CATCH));
            assertTrue(toks.stream().anyMatch(t -> t.getType() == TokenType.KW_FINALLY));
        }

        @Test
        @DisplayName("类声明含泛型")
        void testClassWithGenerics() {
            List<Token> toks = tokens("class Box<out T>(val value: T)");
            assertEquals(TokenType.KW_CLASS, toks.get(0).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(1).getType());
            assertEquals("Box", toks.get(1).getLexeme());
            assertEquals(TokenType.LT, toks.get(2).getType());
            // out 是软关键词，Lexer 层面为 IDENTIFIER
            assertEquals(TokenType.IDENTIFIER, toks.get(3).getType());
            assertEquals("out", toks.get(3).getLexeme());
        }

        @Test
        @DisplayName("Lambda 表达式")
        void testLambda() {
            List<Token> toks = tokens("{ x -> x + 1 }");
            assertEquals(TokenType.LBRACE, toks.get(0).getType());
            assertEquals(TokenType.ARROW, toks.get(2).getType());
            assertEquals(TokenType.RBRACE, toks.get(toks.size() - 1).getType());
        }

        @Test
        @DisplayName("链式安全调用")
        void testChainedSafeCall() {
            List<Token> toks = tokens("a?.b?.c ?: d");
            assertEquals(TokenType.IDENTIFIER, toks.get(0).getType());
            assertEquals(TokenType.SAFE_DOT, toks.get(1).getType());
            assertEquals(TokenType.SAFE_DOT, toks.get(3).getType());
            assertEquals(TokenType.ELVIS, toks.get(5).getType());
        }

        @Test
        @DisplayName("注解语法")
        void testAnnotation() {
            List<Token> toks = tokens("@data class Point(val x: Int)");
            assertEquals(TokenType.AT, toks.get(0).getType());
            assertEquals(TokenType.IDENTIFIER, toks.get(1).getType());
            assertEquals("data", toks.get(1).getLexeme());
            assertEquals(TokenType.KW_CLASS, toks.get(2).getType());
        }

        @Test
        @DisplayName("方法引用")
        void testMethodReference() {
            List<Token> toks = tokens("list.map(::toString)");
            assertTrue(toks.stream().anyMatch(t -> t.getType() == TokenType.DOUBLE_COLON));
        }

        @Test
        @DisplayName("when 表达式")
        void testWhenExpression() {
            List<Token> toks = tokens("when (x) { 1 -> a; 2 -> b; else -> c }");
            assertEquals(TokenType.KW_WHEN, toks.get(0).getType());
            long arrowCount = toks.stream().filter(t -> t.getType() == TokenType.ARROW).count();
            assertEquals(3, arrowCount);
        }
    }

    // ================================================================
    // 异常字符
    // ================================================================

    @Nested
    @DisplayName("异常字符")
    class UnexpectedCharTests {

        @Test
        @DisplayName("未知字符报错")
        void testUnexpectedChar() {
            String errors = scanWithErrors("~");
            assertTrue(errors.contains("Unexpected character"));
        }

        @Test
        @DisplayName("中文字符作为标识符")
        void testChineseCharAsIdentifier() {
            // 中文字符是合法标识符
            List<Token> toks = tokens("你好");
            assertEquals(1, toks.size());
            assertEquals(TokenType.IDENTIFIER, toks.get(0).getType());
            assertEquals("你好", toks.get(0).getLexeme());
        }
    }

    // ================================================================
    // 流式 API (nextToken)
    // ================================================================

    @Nested
    @DisplayName("流式 API (nextToken)")
    class StreamingApiTests {

        @Test
        @DisplayName("逐个获取 token")
        void testNextToken() {
            Lexer lexer = new Lexer("val x = 42", "<test>");
            assertEquals(TokenType.KW_VAL, lexer.nextToken().getType());
            assertEquals(TokenType.IDENTIFIER, lexer.nextToken().getType());
            assertEquals(TokenType.ASSIGN, lexer.nextToken().getType());
            assertEquals(TokenType.INT_LITERAL, lexer.nextToken().getType());
            assertEquals(TokenType.EOF, lexer.nextToken().getType());
        }

        @Test
        @DisplayName("nextToken 跳过注释")
        void testNextTokenSkipsComment() {
            Lexer lexer = new Lexer("a // comment\nb", "<test>");
            assertEquals(TokenType.IDENTIFIER, lexer.nextToken().getType());
            assertEquals(TokenType.NEWLINE, lexer.nextToken().getType());
            Token b = lexer.nextToken();
            assertEquals(TokenType.IDENTIFIER, b.getType());
            assertEquals("b", b.getLexeme());
        }

        @Test
        @DisplayName("空输入返回 EOF")
        void testNextTokenEmpty() {
            Lexer lexer = new Lexer("", "<test>");
            assertEquals(TokenType.EOF, lexer.nextToken().getType());
        }

        @Test
        @DisplayName("多次 EOF 不抛异常")
        void testMultipleEof() {
            Lexer lexer = new Lexer("a", "<test>");
            lexer.nextToken(); // a
            assertEquals(TokenType.EOF, lexer.nextToken().getType());
            assertEquals(TokenType.EOF, lexer.nextToken().getType());
        }
    }

    // ================================================================
    // 字符串插值嵌套花括号
    // ================================================================

    @Nested
    @DisplayName("字符串插值")
    class StringInterpolationTests {

        @Test
        @DisplayName("简单插值 $name")
        void testSimpleInterpolation() {
            List<Token> toks = tokens("\"hello $name\"");
            assertEquals(1, toks.size());
            assertTrue(((String) toks.get(0).getLiteral()).contains("$name"));
        }

        @Test
        @DisplayName("块插值 ${expr}")
        void testBlockInterpolation() {
            List<Token> toks = tokens("\"result: ${1 + 2}\"");
            assertEquals(1, toks.size());
            assertTrue(((String) toks.get(0).getLiteral()).contains("${1 + 2}"));
        }

        @Test
        @DisplayName("嵌套花括号 ${map{k}}  ")
        void testNestedBraces() {
            List<Token> toks = tokens("\"${a{b}}end\"");
            assertEquals(1, toks.size());
            String value = (String) toks.get(0).getLiteral();
            assertTrue(value.contains("${a{b}}"));
            assertTrue(value.endsWith("end"));
        }
    }
}
