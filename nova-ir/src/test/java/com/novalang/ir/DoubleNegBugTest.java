package com.novalang.ir;

import com.novalang.compiler.lexer.*;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("!!true bug 调查")
class DoubleNegBugTest {

    @Test
    @DisplayName("Lexer: !!true 的 token 序列")
    void testLexer() {
        Lexer lexer = new Lexer("!!true", "test");
        List<Token> tokens = lexer.scanTokens();
        for (Token t : tokens) {
            System.out.println("  " + t.getType() + " : '" + t.getLexeme() + "'");
        }
        // !! 被贪心匹配为 NOT_NULL
        assertEquals(TokenType.NOT_NULL, tokens.get(0).getType());
    }

    @Test
    @DisplayName("Lexer: ! !true 的 token 序列")
    void testLexerWithSpace() {
        Lexer lexer = new Lexer("! !true", "test");
        List<Token> tokens = lexer.scanTokens();
        for (Token t : tokens) {
            System.out.println("  " + t.getType() + " : '" + t.getLexeme() + "'");
        }
        assertEquals(TokenType.NOT, tokens.get(0).getType());
        assertEquals(TokenType.NOT, tokens.get(1).getType());
    }

    @Test
    @DisplayName("编译器: !!true 应返回 true（双重否定）")
    void testCompiledDoubleNeg() throws Exception {
        NovaIrCompiler compiler = new NovaIrCompiler();
        Map<String, Class<?>> loaded = compiler.compileAndLoad(
                "object Test { fun run(): Any { return !!true } }", "test.nova");
        Class<?> c = loaded.get("Test");
        Object inst = c.getField("INSTANCE").get(null);
        Object result = c.getDeclaredMethod("run").invoke(inst);
        assertEquals(true, result, "!!true 应返回 true");
    }

    @Test
    @DisplayName("编译器: !(!true) 应返回 true")
    void testCompiledExplicit() throws Exception {
        NovaIrCompiler compiler = new NovaIrCompiler();
        Map<String, Class<?>> loaded = compiler.compileAndLoad(
                "object Test { fun run(): Any { return !(!true) } }", "test.nova");
        Class<?> c = loaded.get("Test");
        Object inst = c.getField("INSTANCE").get(null);
        Object result = c.getDeclaredMethod("run").invoke(inst);
        assertEquals(true, result);
    }
}
