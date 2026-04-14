package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.NovaType;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SemanticAnalyzer regression coverage")
class SemanticAnalyzerRegressionTest {

    private AnalysisResult analyze(String source) {
        Lexer lexer = new Lexer(source, "<regression>");
        Parser parser = new Parser(lexer, "<regression>");
        Program program = parser.parse();
        return new SemanticAnalyzer().analyze(program);
    }

    private static boolean hasDiagnostic(AnalysisResult result, SemanticDiagnostic.Severity severity) {
        for (SemanticDiagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getSeverity() == severity) {
                return true;
            }
        }
        return false;
    }

    private void assertHasDiagnostic(AnalysisResult result, SemanticDiagnostic.Severity severity, String message) {
        assertTrue(hasDiagnostic(result, severity), message + " Actual diagnostics: " + result.getDiagnostics());
    }

    private void assertSymbolType(AnalysisResult result, String name, String expectedDisplay) {
        Symbol sym = result.getSymbolTable().getGlobalScope().resolve(name);
        assertNotNull(sym, "Symbol '" + name + "' should exist");
        NovaType type = sym.getResolvedNovaType();
        assertNotNull(type, "Symbol '" + name + "' should have an inferred type");
        assertEquals(expectedDisplay, type.toDisplayString(), "Unexpected inferred type for '" + name + "'");
    }

    @Test
    @DisplayName("break outside loop should report a semantic error")
    void breakOutsideLoopShouldReportError() {
        AnalysisResult result = analyze("break");
        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "break outside a loop should be rejected during semantic analysis");
    }

    @Test
    @DisplayName("continue outside loop should report a semantic error")
    void continueOutsideLoopShouldReportError() {
        AnalysisResult result = analyze("continue");
        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "continue outside a loop should be rejected during semantic analysis");
    }

    @Test
    @DisplayName("if expression should use the common super type of both branches")
    void ifExpressionShouldUseCommonSuperType() {
        AnalysisResult result = analyze("val x = if (true) 1 else 2.0");
        assertSymbolType(result, "x", "Double");
    }

    @Test
    @DisplayName("when expression should infer the common type of branch results")
    void whenExpressionShouldInferBranchType() {
        AnalysisResult result = analyze("val x = when (1) { 1 -> 1 else -> 2.0 }");
        assertSymbolType(result, "x", "Double");
    }

    @Test
    @DisplayName("try expression should infer the common type of try and catch branches")
    void tryExpressionShouldInferBranchType() {
        AnalysisResult result = analyze("val x = try { 1 } catch (e: Exception) { 2.0 }");
        assertSymbolType(result, "x", "Double");
    }

    @Test
    @DisplayName("when guard expressions should participate in semantic checks")
    void whenGuardExpressionsShouldBeAnalyzed() {
        AnalysisResult result = analyze(
                "val locked = 1\n" +
                "val out = when (1) {\n" +
                "    1 if ((locked = 2) == 2) -> 1\n" +
                "    else -> 0\n" +
                "}");
        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Assignments inside when guards should still be semantically validated");
    }

    @Test
    @DisplayName("list literals should preserve element type information for indexing")
    void listLiteralShouldPreserveElementTypeForIndexing() {
        AnalysisResult result = analyze("val xs = [1, 2]\nval x = xs[0]");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("member assignments should respect property mutability")
    void memberAssignmentShouldCheckMutability() {
        AnalysisResult result = analyze(
                "class Box(val x: Int)\n" +
                "val b = Box(1)\n" +
                "b.x = 2");
        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Assigning to a read-only property through member access should report an error");
    }

    @Test
    @DisplayName("index assignments should validate the element type")
    void indexAssignmentShouldCheckElementType() {
        AnalysisResult result = analyze(
                "val xs = listOf(1, 2)\n" +
                "xs[0] = \"oops\"");
        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Index assignments should be type-checked against the collection element type");
    }

    @Test
    @DisplayName("guard else branches should be required to exit control flow")
    void guardElseBranchesShouldBeRequiredToExitControlFlow() {
        AnalysisResult result = analyze(
                "fun parse(v: Int?): Int {\n" +
                "    guard val x = v else {\n" +
                "        println(\"missing\")\n" +
                "    }\n" +
                "    return x\n" +
                "}");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "A guard statement should reject else branches that can fall through");
    }
}
