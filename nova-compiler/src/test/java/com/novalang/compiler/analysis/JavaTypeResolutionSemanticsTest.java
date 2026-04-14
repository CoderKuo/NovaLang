package com.novalang.compiler.analysis;

import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

@DisplayName("Java type resolution semantics")
class JavaTypeResolutionSemanticsTest {

    private AnalysisResult analyze(String source) {
        Lexer lexer = new Lexer(source, "<java-type-semantics>");
        Parser parser = new Parser(lexer, "<java-type-semantics>");
        Program program = parser.parse();
        return new SemanticAnalyzer().analyze(program);
    }

    private AnalysisResult analyzeWithKnownTypes(String source, String... knownTypes) {
        Lexer lexer = new Lexer(source, "<java-type-semantics>");
        Parser parser = new Parser(lexer, "<java-type-semantics>");
        Program program = parser.parse();
        SemanticAnalyzer analyzer = new SemanticAnalyzer();
        if (knownTypes != null) {
            for (String knownType : knownTypes) {
                analyzer.registerKnownType(knownType);
            }
        }
        return analyzer.analyze(program);
    }

    private static boolean hasError(AnalysisResult result) {
        for (SemanticDiagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getSeverity() == SemanticDiagnostic.Severity.ERROR) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("fully-qualified java reference types should not be rejected just because they are not whitelisted")
    void fullyQualifiedJavaReferenceTypesShouldBeAccepted() {
        AnalysisResult result = analyze("val sb: java.lang.StringBuilder? = null");

        assertFalse(hasError(result),
                "A fully-qualified Java type like java.lang.StringBuilder should be accepted by static semantic analysis");
    }

    @Test
    @DisplayName("non-whitelisted generic java reference types should still be accepted as declared types")
    void nonWhitelistedGenericJavaReferenceTypesShouldBeAccepted() {
        AnalysisResult result = analyze("val deque: java.util.ArrayDeque<String>? = null");

        assertFalse(hasError(result),
                "A generic Java reference type outside the compiler whitelist should still be accepted as a declared type");
    }

    @Test
    @DisplayName("explicit java imports should make non-default Java types visible to semantic analysis")
    void explicitJavaImportsShouldAffectTypeResolution() {
        AnalysisResult result = analyze(
                "import java javax.swing.JFrame\n" +
                "val frame: JFrame? = null");

        assertFalse(hasError(result),
                "An explicit java import should make a non-default Java type visible to semantic analysis");
    }

    @Test
    @DisplayName("java wildcard imports should support package segments that are lexer keywords")
    void javaWildcardImportsShouldSupportKeywordPackageSegments() {
        AnalysisResult result = analyze(
                "import java java.util.function.*\n" +
                "val op: UnaryOperator? = null");

        assertFalse(hasError(result),
                "A java wildcard import should resolve types from packages like java.util.function");
    }

    @Test
    @DisplayName("current package types should be visible by simple name during semantic analysis")
    void currentPackageTypesShouldResolveBySimpleName() {
        AnalysisResult result = analyzeWithKnownTypes(
                "package demo\n" +
                "val user: User? = null",
                "demo.User");

        assertFalse(hasError(result),
                "A type known in the current package should be visible by its simple name");
    }

    @Test
    @DisplayName("fully-qualified references to same-package declarations should still resolve locally")
    void fullyQualifiedSamePackageTypesShouldResolveToLocalDeclarations() {
        AnalysisResult result = analyze(
                "package demo\n" +
                "class User(val name: String)\n" +
                "val user: demo.User? = null");

        assertFalse(hasError(result),
                "A fully-qualified reference to a same-package declaration should be accepted");
    }

    @Test
    @DisplayName("explicit Nova imports should make external Nova types visible to semantic analysis")
    void explicitNovaImportsShouldAffectTypeResolution() {
        AnalysisResult result = analyzeWithKnownTypes(
                "import models.User\n" +
                "val user: User? = null",
                "models.User");

        assertFalse(hasError(result),
                "An explicit Nova import should make an external Nova type visible to semantic analysis");
    }

    @Test
    @DisplayName("aliased Nova imports should resolve the alias as a visible type name")
    void aliasedNovaImportsShouldResolveAliases() {
        AnalysisResult result = analyzeWithKnownTypes(
                "import models.User as AccountUser\n" +
                "val user: AccountUser? = null",
                "models.User");

        assertFalse(hasError(result),
                "An aliased Nova import should expose the alias as a valid type name");
    }

    @Test
    @DisplayName("wildcard Nova imports should make matching external types visible by simple name")
    void wildcardNovaImportsShouldResolveSimpleNames() {
        AnalysisResult result = analyzeWithKnownTypes(
                "import models.*\n" +
                "val user: User? = null",
                "models.User");

        assertFalse(hasError(result),
                "A Nova wildcard import should make matching external types visible by simple name");
    }
}
