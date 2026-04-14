package com.novalang.compiler.analysis;

import com.novalang.compiler.analysis.types.NovaType;
import com.novalang.compiler.ast.AstNode;
import com.novalang.compiler.ast.decl.Declaration;
import com.novalang.compiler.ast.decl.PropertyDecl;
import com.novalang.compiler.ast.decl.Program;
import com.novalang.compiler.ast.expr.BinaryExpr;
import com.novalang.compiler.ast.expr.CallExpr;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.expr.IfExpr;
import com.novalang.compiler.ast.expr.LambdaExpr;
import com.novalang.compiler.ast.expr.MemberExpr;
import com.novalang.compiler.ast.expr.ScopeShorthandExpr;
import com.novalang.compiler.ast.expr.TypeCheckExpr;
import com.novalang.compiler.ast.expr.UnaryExpr;
import com.novalang.compiler.ast.stmt.Block;
import com.novalang.compiler.ast.stmt.ExpressionStmt;
import com.novalang.compiler.ast.stmt.Statement;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;
import com.novalang.runtime.NovaTypeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SemanticAnalyzer language semantics")
class SemanticAnalyzerLanguageSemanticsTest {

    private static final class AnalyzedSource {
        private final Program program;
        private final AnalysisResult result;

        private AnalyzedSource(Program program, AnalysisResult result) {
            this.program = program;
            this.result = result;
        }
    }

    private AnalyzedSource analyzeSource(String source) {
        Lexer lexer = new Lexer(source, "<language-semantics>");
        Parser parser = new Parser(lexer, "<language-semantics>");
        Program program = parser.parse();
        return new AnalyzedSource(program, new SemanticAnalyzer().analyze(program));
    }

    private AnalysisResult analyze(String source) {
        return analyzeSource(source).result;
    }

    private static boolean hasDiagnostic(AnalysisResult result, SemanticDiagnostic.Severity severity) {
        for (SemanticDiagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getSeverity() == severity) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDiagnosticContaining(AnalysisResult result, SemanticDiagnostic.Severity severity, String fragment) {
        for (SemanticDiagnostic diagnostic : result.getDiagnostics()) {
            if (diagnostic.getSeverity() == severity
                    && diagnostic.getMessage() != null
                    && diagnostic.getMessage().contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private void assertHasDiagnostic(AnalysisResult result, SemanticDiagnostic.Severity severity, String message) {
        assertTrue(hasDiagnostic(result, severity), message + " Actual diagnostics: " + result.getDiagnostics());
    }

    private void assertHasDiagnosticContaining(AnalysisResult result, SemanticDiagnostic.Severity severity,
                                               String fragment, String message) {
        assertTrue(hasDiagnosticContaining(result, severity, fragment),
                message + " Actual diagnostics: " + result.getDiagnostics());
    }

    private void assertNoDiagnostics(AnalysisResult result, String message) {
        assertFalse(hasDiagnostic(result, SemanticDiagnostic.Severity.ERROR)
                        || hasDiagnostic(result, SemanticDiagnostic.Severity.WARNING),
                message + " Actual diagnostics: " + result.getDiagnostics());
    }

    private void assertSymbolType(AnalysisResult result, String name, String expectedDisplay) {
        Symbol sym = result.getSymbolTable().getGlobalScope().resolveAny(name);
        assertNotNull(sym, "Symbol '" + name + "' should exist");
        NovaType type = sym.getResolvedNovaType();
        assertNotNull(type, "Symbol '" + name + "' should have an inferred type");
        assertEquals(expectedDisplay, type.toDisplayString(), "Unexpected inferred type for '" + name + "'");
    }

    private void assertClassMemberType(AnalysisResult result, String className, String memberName, String expectedDisplay) {
        Symbol classSymbol = result.getSymbolTable().getGlobalScope().resolveType(className);
        assertNotNull(classSymbol, "Class symbol '" + className + "' should exist");
        assertNotNull(classSymbol.getMembers(), "Class symbol '" + className + "' should have members");
        Symbol memberSymbol = classSymbol.getMembers().get(memberName);
        assertNotNull(memberSymbol, "Member '" + memberName + "' should exist on class '" + className + "'");
        NovaType type = memberSymbol.getResolvedNovaType();
        assertNotNull(type, "Member '" + memberName + "' on class '" + className + "' should have an inferred type");
        assertEquals(expectedDisplay, type.toDisplayString(),
                "Unexpected inferred type for member '" + memberName + "' on class '" + className + "'");
    }

    private PropertyDecl findProperty(Program program, String propertyName) {
        for (Declaration declaration : program.getDeclarations()) {
            if (declaration instanceof PropertyDecl && propertyName.equals(declaration.getName())) {
                return (PropertyDecl) declaration;
            }
        }
        return null;
    }

    private MemberExpr findFirstMemberExpr(Expression expression, String memberName) {
        if (expression == null) return null;
        if (expression instanceof MemberExpr) {
            MemberExpr memberExpr = (MemberExpr) expression;
            if (memberName.equals(memberExpr.getMember())) {
                return memberExpr;
            }
            return findFirstMemberExpr(memberExpr.getTarget(), memberName);
        }
        if (expression instanceof IfExpr) {
            IfExpr ifExpr = (IfExpr) expression;
            MemberExpr inCondition = findFirstMemberExpr(ifExpr.getCondition(), memberName);
            if (inCondition != null) return inCondition;
            MemberExpr inThen = findFirstMemberExpr(ifExpr.getThenExpr(), memberName);
            if (inThen != null) return inThen;
            return findFirstMemberExpr(ifExpr.getElseExpr(), memberName);
        }
        if (expression instanceof BinaryExpr) {
            BinaryExpr binaryExpr = (BinaryExpr) expression;
            MemberExpr inLeft = findFirstMemberExpr(binaryExpr.getLeft(), memberName);
            if (inLeft != null) return inLeft;
            return findFirstMemberExpr(binaryExpr.getRight(), memberName);
        }
        if (expression instanceof TypeCheckExpr) {
            return findFirstMemberExpr(((TypeCheckExpr) expression).getOperand(), memberName);
        }
        if (expression instanceof UnaryExpr) {
            return findFirstMemberExpr(((UnaryExpr) expression).getOperand(), memberName);
        }
        if (expression instanceof CallExpr) {
            CallExpr callExpr = (CallExpr) expression;
            MemberExpr inCallee = findFirstMemberExpr(callExpr.getCallee(), memberName);
            if (inCallee != null) return inCallee;
            for (CallExpr.Argument argument : callExpr.getArgs()) {
                MemberExpr inArg = findFirstMemberExpr(argument.getValue(), memberName);
                if (inArg != null) return inArg;
            }
            return callExpr.getTrailingLambda() != null
                    ? findFirstMemberExpr(callExpr.getTrailingLambda(), memberName)
                    : null;
        }
        if (expression instanceof LambdaExpr) {
            AstNode body = ((LambdaExpr) expression).getBody();
            if (body instanceof Expression) {
                return findFirstMemberExpr((Expression) body, memberName);
            }
        }
        if (expression instanceof ScopeShorthandExpr) {
            return findFirstMemberExprInBlock(((ScopeShorthandExpr) expression).getBlock(), memberName);
        }
        return null;
    }

    private MemberExpr findFirstMemberExprInBlock(Block block, String memberName) {
        if (block == null || block.getStatements() == null) return null;
        for (Statement statement : block.getStatements()) {
            if (statement instanceof ExpressionStmt) {
                MemberExpr memberExpr = findFirstMemberExpr(((ExpressionStmt) statement).getExpression(), memberName);
                if (memberExpr != null) return memberExpr;
            }
        }
        return null;
    }

    private void assertInitializerMemberType(AnalyzedSource analyzed, String propertyName,
                                             String memberName, String expectedDisplay) {
        PropertyDecl property = findProperty(analyzed.program, propertyName);
        assertNotNull(property, "Property '" + propertyName + "' should exist");
        MemberExpr memberExpr = findFirstMemberExpr(property.getInitializer(), memberName);
        assertNotNull(memberExpr, "Expected to find member '" + memberName + "' in initializer of '" + propertyName + "'");
        NovaType type = analyzed.result.getExprNovaTypeMap().get(memberExpr);
        assertNotNull(type, "Member expression '" + memberName + "' should have an inferred type");
        assertEquals(expectedDisplay, type.toDisplayString(),
                "Unexpected inferred type for member '" + memberName + "' in initializer of '" + propertyName + "'");
    }

    @Test
    @DisplayName("typealias should behave as the aliased type during assignment compatibility checks")
    void typeAliasShouldNotTriggerFalseMismatchDiagnostics() {
        AnalysisResult result = analyze(
                "typealias UserId = Int\n" +
                "val id: UserId = 1");

        assertNoDiagnostics(result,
                "Assigning an Int to a UserId alias should not produce a type mismatch diagnostic");
    }

    @Test
    @DisplayName("generic typealias should substitute type parameters into the aliased type")
    void genericTypeAliasShouldSubstituteTypeArguments() {
        AnalysisResult result = analyze(
                "typealias Box<T> = List<T>\n" +
                "val xs: Box<Int> = listOf(1, 2)");

        assertNoDiagnostics(result,
                "A generic typealias should behave like its expanded generic target type");
    }

    @Test
    @DisplayName("unknown declared types should be rejected instead of becoming ad-hoc nominal types")
    void unknownTypesShouldReportSemanticErrors() {
        AnalysisResult result = analyze("val x: MissingType = 1");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Using an undeclared type name should report a semantic error");
    }

    @Test
    @DisplayName("type declarations and value declarations should not conflict across namespaces")
    void typeAndValueDeclarationsShouldUseSeparateNamespaces() {
        AnalysisResult result = analyze(
                "class User(val name: String)\n" +
                "val User = 1\n" +
                "fun current(): User? = null");

        assertNoDiagnostics(result,
                "A type declaration and a value declaration with the same name should not conflict");
    }

    @Test
    @DisplayName("local value shadowing should not break type references")
    void localValueShadowingShouldNotBreakTypeReferences() {
        AnalysisResult result = analyze(
                "class User(val name: String)\n" +
                "fun current(): User? {\n" +
                "    val User = 1\n" +
                "    return null\n" +
                "}");

        assertNoDiagnostics(result,
                "A local value should not shadow the type namespace");
    }

    @Test
    @DisplayName("visible symbols should include both value and type namespaces")
    void visibleSymbolsShouldIncludeValuesAndTypesSeparately() {
        AnalysisResult result = analyze(
                "class User(val name: String)\n" +
                "val User = 1\n" +
                "fun current(): User? = null");

        List<Symbol> visible = result.getSymbolTable().getVisibleSymbols(3, 10);
        boolean hasValueUser = false;
        boolean hasTypeUser = false;
        for (Symbol symbol : visible) {
            if (!"User".equals(symbol.getName())) {
                continue;
            }
            if (symbol.getKind() == SymbolKind.VARIABLE || symbol.getKind() == SymbolKind.PROPERTY) {
                hasValueUser = true;
            }
            if (symbol.getKind() == SymbolKind.CLASS) {
                hasTypeUser = true;
            }
        }

        assertTrue(hasValueUser, "Visible symbols should include the value namespace entry");
        assertTrue(hasTypeUser, "Visible symbols should include the type namespace entry");
    }

    @Test
    @DisplayName("registry-backed member access should merge overload return types")
    void registryBackedMemberAccessShouldMergeOverloadReturnTypes() {
        String typeName = "__RegistryBackedMemberAccess__";
        NovaTypeRegistry.registerType(typeName, Arrays.asList(
                NovaTypeRegistry.MethodInfo.method("pick", 1, "pick an int", "Int"),
                NovaTypeRegistry.MethodInfo.method("pick", 1, "pick a string", "String")));

        AnalysisResult result = analyze(
                "class " + typeName + "\n" +
                "val chooser = " + typeName + "().pick");

        assertNoDiagnostics(result,
                "Registry-backed members should still infer a stable type when multiple overloads share a name");
        assertSymbolType(result, "chooser", "Any");
    }

    @Test
    @DisplayName("explicit dynamic types should allow controlled escape from static checking")
    void explicitDynamicTypesShouldActAsOptInDynamicValues() {
        AnalysisResult result = analyze(
                "var value: dynamic = 1\n" +
                "value = \"hello\"\n" +
                "val text: String = value");

        assertNoDiagnostics(result,
                "Explicit dynamic types should allow heterogeneous reassignment and assignment into typed slots");
        assertSymbolType(result, "value", "dynamic");
        assertSymbolType(result, "text", "String");
    }

    @Test
    @DisplayName("member access and calls on explicit dynamic values should remain dynamically typed")
    void explicitDynamicValuesShouldPropagateDynamicMemberResults() {
        AnalyzedSource analyzed = analyzeSource(
                "val value: dynamic = \"hello\"\n" +
                "val len = value.length\n" +
                "val upper = value.toUpperCase()");

        assertNoDiagnostics(analyzed.result,
                "Explicit dynamic receivers should allow member access and method calls");
        assertSymbolType(analyzed.result, "len", "dynamic");
        assertSymbolType(analyzed.result, "upper", "dynamic");
    }

    @Test
    @DisplayName("explicit imported values should behave as dynamic placeholders during semantic analysis")
    void explicitImportedValuesShouldBehaveAsDynamicPlaceholders() {
        AnalysisResult result = analyze(
                "import counter.getCount\n" +
                "val ref = getCount\n" +
                "val value = getCount()");

        assertNoDiagnostics(result,
                "Imported value symbols should at least be modeled as dynamic placeholders instead of disappearing from type inference");
        assertSymbolType(result, "ref", "dynamic");
        assertSymbolType(result, "value", "dynamic");
    }

    @Test
    @DisplayName("explicit imported Nova types should be usable as constructor callees")
    void explicitImportedNovaTypesShouldBeUsableAsConstructors() {
        AnalysisResult result = analyze(
                "import models.User\n" +
                "val user = User(\"Alice\")");

        assertNoDiagnostics(result,
                "An imported Nova type should be treated like a constructor callee in expression position");
        assertSymbolType(result, "user", "User");
    }

    @Test
    @DisplayName("explicit builtin module function imports should preserve callable type information")
    void explicitBuiltinModuleFunctionImportsShouldPreserveCallableTypeInformation() {
        AnalysisResult result = analyze(
                "import nova.system.osName\n" +
                "val getName = osName\n" +
                "val name = osName()");

        assertNoDiagnostics(result,
                "Builtin module function imports should register callable symbols during semantic analysis");
        assertSymbolType(result, "getName", "() -> String");
        assertSymbolType(result, "name", "String");
    }

    @Test
    @DisplayName("wildcard builtin module imports should register exported function symbols")
    void wildcardBuiltinModuleImportsShouldRegisterExportedFunctionSymbols() {
        AnalysisResult result = analyze(
                "import nova.test.*\n" +
                "val outcome = assertTrue(true)");

        assertNoDiagnostics(result,
                "Wildcard builtin module imports should expose their exported functions to semantic analysis");
        assertSymbolType(result, "outcome", "Unit");
    }

    @Test
    @DisplayName("inferred mutable variables should keep their original static type")
    void inferredMutableVariablesShouldNotWidenAfterReassignment() {
        AnalysisResult result = analyze(
                "var value = 1\n" +
                "value = \"oops\"");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Reassigning an inferred mutable variable to an incompatible type should be rejected");
    }

    @Test
    @DisplayName("mutable variables initialized with null should require an explicit nullable type")
    void nullInitializedMutableVariablesShouldRequireExplicitType() {
        AnalysisResult result = analyze("var value = null");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "A mutable variable initialized with null should require an explicit nullable type");
    }

    @Test
    @DisplayName("await should infer the payload type of async computations")
    void awaitShouldInferPayloadType() {
        AnalysisResult result = analyze(
                "val f = async { 42 }\n" +
                "val x = await f");

        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("java constructors should be resolved against overloads by argument list")
    void javaConstructorsShouldResolveAgainstOverloads() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val sb: StringBuilder = StringBuilder(\"hi\")");

        assertNoDiagnostics(result,
                "A matching Java constructor overload should be accepted by semantic analysis");
        assertSymbolType(result, "sb", "StringBuilder");
    }

    @Test
    @DisplayName("java constructor overload mismatches should report semantic errors")
    void javaConstructorOverloadMismatchesShouldReportErrors() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val sb = StringBuilder(1, 2, 3)");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR, "No matching Java constructor",
                "A Java constructor call with no matching overload should be rejected");
    }

    @Test
    @DisplayName("java member calls should resolve overloads and infer the return type")
    void javaMemberCallsShouldResolveOverloads() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val sb = StringBuilder(\"hello\")\n" +
                "val text = sb.substring(1)");

        assertNoDiagnostics(result,
                "A matching Java instance method overload should be accepted");
        assertSymbolType(result, "text", "String");
    }

    @Test
    @DisplayName("java member overload mismatches should report semantic errors")
    void javaMemberOverloadMismatchesShouldReportErrors() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val sb = StringBuilder(\"hello\")\n" +
                "val text = sb.substring()");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR, "No matching Java method overload",
                "A Java instance method call with no matching overload should be rejected");
    }

    @Test
    @DisplayName("named arguments should be matched against declared parameters")
    void namedArgumentsShouldBeValidatedAgainstParameterNames() {
        AnalysisResult result = analyze(
                "fun greet(name: String, age: Int = 1) { }\n" +
                "greet(age = 2, name = \"Nova\")");

        assertNoDiagnostics(result,
                "Named arguments should be reordered against the declared parameter list");
    }

    @Test
    @DisplayName("unknown named arguments should report semantic errors")
    void unknownNamedArgumentsShouldReportErrors() {
        AnalysisResult result = analyze(
                "fun greet(name: String) { }\n" +
                "greet(who = \"Nova\")");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR, "Unknown named argument",
                "Unknown named arguments should be rejected");
    }

    @Test
    @DisplayName("duplicate argument bindings should report semantic errors")
    void duplicateArgumentBindingsShouldReportErrors() {
        AnalysisResult result = analyze(
                "fun greet(name: String, age: Int) { }\n" +
                "greet(\"Nova\", name = \"Other\", age = 2)");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR, "already provided",
                "A parameter should not be bound by both positional and named arguments");
    }

    @Test
    @DisplayName("vararg parameter checking should validate every provided argument")
    void varargParameterCheckingShouldValidateEveryArgument() {
        AnalysisResult result = analyze(
                "fun sum(vararg numbers: Int) { }\n" +
                "sum(1, \"oops\")");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Vararg calls should still validate the type of each supplied argument");
    }

    @Test
    @DisplayName("constructor calls should validate named arguments and defaults")
    void constructorCallsShouldValidateNamedArgumentsAndDefaults() {
        AnalysisResult result = analyze(
                "class User(val name: String, val age: Int = 0)\n" +
                "val u = User(name = \"Nova\")");

        assertNoDiagnostics(result,
                "Constructor calls should support the same named/default-argument rules as functions");
    }

    @Test
    @DisplayName("block expressions without a trailing expression should evaluate to Unit")
    void blockExpressionsWithoutTrailingExpressionShouldEvaluateToUnit() {
        AnalysisResult result = analyze(
                "val x = if (true) { val a = 1 } else { val b = 2 }");

        assertNoDiagnostics(result,
                "Blocks without a trailing expression should still be valid expressions");
        assertSymbolType(result, "x", "Unit");
    }

    @Test
    @DisplayName("when on enums should require exhaustiveness or an else branch")
    void whenOnEnumsShouldCheckExhaustiveness() {
        AnalysisResult result = analyze(
                "enum class Color { RED, BLUE }\n" +
                "val x = when (Color.RED) {\n" +
                "    Color.RED -> 1\n" +
                "}");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Enum-based when expressions should report missing cases when no else branch is present");
    }

    @Test
    @DisplayName("when on sealed hierarchies should require exhaustiveness or an else branch")
    void whenOnSealedTypesShouldCheckExhaustiveness() {
        AnalysisResult result = analyze(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "class Add(val l: Expr, val r: Expr) : Expr\n" +
                "val x = when (Lit(1) as Expr) {\n" +
                "    is Lit -> 1\n" +
                "}");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Sealed when expressions should report missing subclasses when no else branch is present");
    }

    @Test
    @DisplayName("nullable receivers should not allow plain member access")
    void nullableReceiverMemberAccessShouldReportError() {
        AnalysisResult result = analyze(
                "val s: String? = null\n" +
                "val n = s.length");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Accessing a member through a nullable receiver should require a safe-call or non-null assertion");
    }

    @Test
    @DisplayName("nullable receivers should not allow plain method calls")
    void nullableReceiverMethodCallShouldReportError() {
        AnalysisResult result = analyze(
                "val s: String? = null\n" +
                "val upper = s.toUpperCase()");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "Calling a method through a nullable receiver should require a safe-call or non-null assertion");
    }

    @Test
    @DisplayName("lambda return types should respect an explicitly declared function type")
    void lambdaReturnTypeShouldRespectDeclaredFunctionType() {
        AnalysisResult result = analyze(
                "val f: (Int) -> String = { it + 1 }");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "A lambda body should be checked against the declared function return type");
    }

    @Test
    @DisplayName("lambda parameter counts should respect an explicitly declared function type")
    void lambdaArityShouldRespectDeclaredFunctionType() {
        AnalysisResult result = analyze(
                "val f: (Int, Int) -> Int = { it }");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "A lambda should not silently accept a mismatched declared function arity");
    }

    @Test
    @DisplayName("lambda return types should respect higher-order function parameter types")
    void lambdaReturnTypeShouldRespectHigherOrderParameterType() {
        AnalysisResult result = analyze(
                "fun apply(f: (Int) -> String) { }\n" +
                "apply { it + 1 }");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "A lambda passed to a higher-order function should be checked against the expected return type");
    }

    @Test
    @DisplayName("lambda parameter counts should respect higher-order function parameter types")
    void lambdaArityShouldRespectHigherOrderParameterType() {
        AnalysisResult result = analyze(
                "fun combine(f: (Int, Int) -> Int) { }\n" +
                "combine { it }");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "A lambda passed to a higher-order function should be checked against the expected arity");
    }

    @Test
    @DisplayName("when type conditions should smart-cast identifier subjects inside the matching branch")
    void whenTypeConditionShouldSmartCastIdentifierSubject() {
        AnalysisResult result = analyze(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "val e: Expr = Lit(1)\n" +
                "val x = when (e) {\n" +
                "    is Lit -> e.n\n" +
                "}");

        assertNoDiagnostics(result,
                "A matching when type condition should allow branch-local access to members of the narrowed subtype");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("when type conditions should smart-cast bound subjects inside the matching branch")
    void whenTypeConditionShouldSmartCastBoundSubject() {
        AnalysisResult result = analyze(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "val e: Expr = Lit(1)\n" +
                "val x = when (val subject = e) {\n" +
                "    is Lit -> subject.n\n" +
                "}");

        assertNoDiagnostics(result,
                "A matching when type condition should narrow the bound subject within that branch");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("if type checks should smart-cast identifier subjects in the then branch")
    void ifTypeCheckShouldSmartCastThenBranch() {
        AnalyzedSource analyzed = analyzeSource(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "val e: Expr = Lit(1)\n" +
                "val x = if (e is Lit) e.n else \"oops\"");

        assertNoDiagnostics(analyzed.result,
                "A positive if type check should narrow the subject inside the then branch");
        assertInitializerMemberType(analyzed, "x", "n", "Int");
        assertSymbolType(analyzed.result, "x", "Any");
    }

    @Test
    @DisplayName("negated if type checks should smart-cast identifier subjects in the else branch")
    void negatedIfTypeCheckShouldSmartCastElseBranch() {
        AnalyzedSource analyzed = analyzeSource(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "val e: Expr = Lit(1)\n" +
                "val x = if (e !is Lit) \"oops\" else e.n");

        assertNoDiagnostics(analyzed.result,
                "A negated if type check should narrow the subject inside the else branch");
        assertInitializerMemberType(analyzed, "x", "n", "Int");
        assertSymbolType(analyzed.result, "x", "Any");
    }

    @Test
    @DisplayName("and conditions should smart-cast the right-hand side after a positive type check")
    void andConditionShouldSmartCastRightHandSide() {
        AnalyzedSource analyzed = analyzeSource(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "val e: Expr = Lit(1)\n" +
                "val x = if (e is Lit && e.n > 0) 1 else 0");

        assertNoDiagnostics(analyzed.result,
                "The right-hand side of && should see the narrowing established by the left-hand type check");
        assertInitializerMemberType(analyzed, "x", "n", "Int");
    }

    @Test
    @DisplayName("or conditions should smart-cast the right-hand side after a negated type check")
    void orConditionShouldSmartCastRightHandSideAfterNegatedCheck() {
        AnalyzedSource analyzed = analyzeSource(
                "sealed interface Expr\n" +
                "class Lit(val n: Int) : Expr\n" +
                "val e: Expr = Lit(1)\n" +
                "val x = if (e !is Lit || e.n > 0) 1 else 0");

        assertNoDiagnostics(analyzed.result,
                "The right-hand side of || should see the narrowing implied when a negated type check is false");
        assertInitializerMemberType(analyzed, "x", "n", "Int");
    }

    @Test
    @DisplayName("if not-null checks should smart-cast nullable subjects in the then branch")
    void ifNotNullCheckShouldSmartCastThenBranch() {
        AnalyzedSource analyzed = analyzeSource(
                "val s: String? = \"hi\"\n" +
                "val x = if (s != null) s.length else 0");

        assertNoDiagnostics(analyzed.result,
                "A positive null check should narrow the subject inside the then branch");
        assertInitializerMemberType(analyzed, "x", "length", "Int");
        assertSymbolType(analyzed.result, "x", "Int");
    }

    @Test
    @DisplayName("if equals-null checks should smart-cast nullable subjects in the else branch")
    void ifEqualsNullCheckShouldSmartCastElseBranch() {
        AnalyzedSource analyzed = analyzeSource(
                "val s: String? = \"hi\"\n" +
                "val x = if (s == null) 0 else s.length");

        assertNoDiagnostics(analyzed.result,
                "An equals-null check should narrow the subject inside the else branch");
        assertInitializerMemberType(analyzed, "x", "length", "Int");
        assertSymbolType(analyzed.result, "x", "Int");
    }

    @Test
    @DisplayName("and conditions should smart-cast nullable subjects after not-null checks")
    void andConditionShouldSmartCastAfterNotNullCheck() {
        AnalyzedSource analyzed = analyzeSource(
                "val s: String? = \"hi\"\n" +
                "val x = if (s != null && s.length > 0) 1 else 0");

        assertNoDiagnostics(analyzed.result,
                "The right-hand side of && should see the narrowing established by a not-null check");
        assertInitializerMemberType(analyzed, "x", "length", "Int");
        assertSymbolType(analyzed.result, "x", "Int");
    }

    @Test
    @DisplayName("or conditions should smart-cast nullable subjects after equals-null checks")
    void orConditionShouldSmartCastAfterEqualsNullCheck() {
        AnalyzedSource analyzed = analyzeSource(
                "val s: String? = \"hi\"\n" +
                "val x = if (s == null || s.length > 0) 1 else 0");

        assertNoDiagnostics(analyzed.result,
                "The right-hand side of || should see the narrowing implied when an equals-null check is false");
        assertInitializerMemberType(analyzed, "x", "length", "Int");
        assertSymbolType(analyzed.result, "x", "Int");
    }

    @Test
    @DisplayName("elvis expressions should use the common type of the non-null left side and right side")
    void elvisShouldUseCommonTypeOfBothSides() {
        AnalysisResult result = analyze(
                "val s: String? = \"hi\"\n" +
                "val x = s ?: 1");

        assertNoDiagnostics(result,
                "An Elvis expression should consider both sides when inferring its result type");
        assertSymbolType(result, "x", "Any");
    }

    @Test
    @DisplayName("safe calls should infer a nullable member type")
    void safeCallShouldInferNullableMemberType() {
        AnalysisResult result = analyze(
                "val s: String? = \"hi\"\n" +
                "val x = s?.length");

        assertNoDiagnostics(result,
                "A safe call should infer the member type and make it nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("safe index expressions should infer a nullable element type")
    void safeIndexShouldInferNullableElementType() {
        AnalysisResult result = analyze(
                "val xs: List<Int>? = listOf(1, 2)\n" +
                "val x = xs?[0]");

        assertNoDiagnostics(result,
                "A safe index should infer the element type and make it nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("type checks should validate their target type references")
    void typeCheckShouldValidateTargetType() {
        AnalysisResult result = analyze("val x = 1 is MissingType");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "An unknown target type in an 'is' check should report a semantic error");
    }

    @Test
    @DisplayName("type casts should validate their target type references")
    void typeCastShouldValidateTargetType() {
        AnalysisResult result = analyze("val x = 1 as MissingType");

        assertHasDiagnostic(result, SemanticDiagnostic.Severity.ERROR,
                "An unknown target type in an 'as' cast should report a semantic error");
    }

    @Test
    @DisplayName("top-level method references should infer a function type")
    void topLevelMethodReferenceShouldInferFunctionType() {
        AnalysisResult result = analyze(
                "fun foo(x: Int): Int = x\n" +
                "val ref = ::foo");

        assertNoDiagnostics(result,
                "A top-level method reference should infer a callable function type");
        assertSymbolType(result, "ref", "(Int) -> Int");
    }

    @Test
    @DisplayName("bound method references should infer a function type without an explicit receiver")
    void boundMethodReferenceShouldInferBoundFunctionType() {
        AnalysisResult result = analyze(
                "val s = \"hi\"\n" +
                "val ref = s::toUpperCase");

        assertNoDiagnostics(result,
                "A bound method reference should infer a zero-arg callable type");
        assertSymbolType(result, "ref", "() -> String");
    }

    @Test
    @DisplayName("type-target method references should infer a receiver function type")
    void typeTargetMethodReferenceShouldInferReceiverFunctionType() {
        AnalysisResult result = analyze("val ref = String::length");

        assertNoDiagnostics(result,
                "A type-target method reference should infer a receiver-style function type");
        assertSymbolType(result, "ref", "String.() -> Int");
    }

    @Test
    @DisplayName("java bound method references should use overload-aware function typing")
    void javaBoundMethodReferencesShouldUseOverloadAwareFunctionTyping() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val ref: (Int) -> String = StringBuilder(\"hello\")::substring");

        assertNoDiagnostics(result,
                "A bound Java method reference should resolve against the expected function signature");
        assertSymbolType(result, "ref", "(Int) -> String");
    }

    @Test
    @DisplayName("java type-target method references should use overload-aware receiver function typing")
    void javaTypeTargetMethodReferencesShouldUseOverloadAwareReceiverFunctionTyping() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val ref: StringBuilder.(Int) -> String = StringBuilder::substring");

        assertNoDiagnostics(result,
                "An unbound Java method reference should resolve against the expected receiver-style function signature");
        assertSymbolType(result, "ref", "StringBuilder.(Int) -> String");
    }

    @Test
    @DisplayName("ambiguous java method references should report semantic errors when no expected type disambiguates them")
    void ambiguousJavaMethodReferencesShouldReportErrors() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val ref = StringBuilder::substring");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR, "Ambiguous Java method reference",
                "An overloaded Java method reference without an expected type should be rejected");
    }

    @Test
    @DisplayName("java member values should infer bound function types when used in function-typed contexts")
    void javaMemberValuesShouldInferBoundFunctionTypesInFunctionContexts() {
        AnalysisResult result = analyze(
                "import java java.lang.StringBuilder\n" +
                "val ref: (Int) -> String = StringBuilder(\"hello\").substring");

        assertNoDiagnostics(result,
                "A Java member used as a value in a function-typed context should infer a bound callable type");
        assertSymbolType(result, "ref", "(Int) -> String");
    }

    @Test
    @DisplayName("stdlib member values should infer bound function types in function-typed contexts")
    void stdlibMemberValuesShouldInferBoundFunctionTypesInFunctionContexts() {
        AnalysisResult result = analyze(
                "val ref: (Int) -> String = \"hello\".substring");

        assertNoDiagnostics(result,
                "A stdlib member used as a value in a function-typed context should infer a bound callable type");
        assertSymbolType(result, "ref", "(Int) -> String");
    }

    @Test
    @DisplayName("member values on arbitrary receiver expressions should infer bound function types in function contexts")
    void arbitraryReceiverMemberValuesShouldInferBoundFunctionTypesInFunctionContexts() {
        AnalysisResult result = analyze(
                "class Greeter { fun greet(name: String): String = name }\n" +
                "fun makeGreeter(): Greeter = Greeter()\n" +
                "val ref: (String) -> String = makeGreeter().greet");

        assertNoDiagnostics(result,
                "Member values should use the receiver type, not just identifier targets, when inferring bound callables");
        assertSymbolType(result, "ref", "(String) -> String");
    }

    @Test
    @DisplayName("super calls should preserve member return types for property inference")
    void superCallsShouldPreserveMemberReturnTypes() {
        AnalysisResult result = analyze(
                "open class A { open fun f(): Int = 1 }\n" +
                "class B : A() { val y = super.f() }");

        assertNoDiagnostics(result,
                "A super call should carry through the super member return type");
        assertClassMemberType(result, "B", "y", "Int");
    }

    @Test
    @DisplayName("object literals without explicit supertypes should still infer a stable type")
    void objectLiteralWithoutSuperTypeShouldInferAny() {
        AnalysisResult result = analyze("val x = object { val n = 1 }");

        assertNoDiagnostics(result,
                "An object literal without explicit supertypes should still infer a stable expression type");
        assertSymbolType(result, "x", "Any");
    }

    @Test
    @DisplayName("object literals with an explicit supertype should infer that supertype")
    void objectLiteralWithSuperTypeShouldInferDeclaredSuperType() {
        AnalysisResult result = analyze(
                "interface Named\n" +
                "val x = object : Named { }");

        assertNoDiagnostics(result,
                "An object literal with a declared supertype should infer that supertype");
        assertSymbolType(result, "x", "Named");
    }

    @Test
    @DisplayName("scope shorthand should expose receiver members and return the nullable receiver type")
    void scopeShorthandShouldExposeReceiverMembersAndReturnNullableReceiverType() {
        AnalyzedSource analyzed = analyzeSource(
                "class Person(val name: String)\n" +
                "val p: Person? = Person(\"Alice\")\n" +
                "val x = p?.{ name.length }");

        assertNoDiagnostics(analyzed.result,
                "A scope shorthand block should see receiver members and keep the receiver as the result type");
        assertInitializerMemberType(analyzed, "x", "length", "Int");
        assertSymbolType(analyzed.result, "x", "Person?");
    }

    @Test
    @DisplayName("error propagation should unwrap Result payload types when they are known")
    void errorPropagationShouldUnwrapKnownResultPayloadType() {
        AnalysisResult result = analyze(
                "val r: Result<Int> = Ok(42)\n" +
                "val x = r?");

        assertNoDiagnostics(result,
                "Error propagation should unwrap the payload type of a Result<T> value");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("expression-bodied functions should infer their return type for later calls")
    void expressionBodiedFunctionsShouldInferReturnTypes() {
        AnalysisResult result = analyze(
                "fun g() = 1\n" +
                "val x = g()");

        assertNoDiagnostics(result,
                "An expression-bodied function should infer its return type for subsequent calls");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("anonymous object members should remain visible through local values")
    void anonymousObjectMembersShouldRemainVisibleThroughLocalValues() {
        AnalysisResult result = analyze(
                "val x = object { val n = 1 }\n" +
                "val y = x.n");

        assertNoDiagnostics(result,
                "A local value initialized from an anonymous object should expose that object's members");
        assertSymbolType(result, "y", "Int");
    }

    @Test
    @DisplayName("error propagation should preserve Result payload types created from Ok without explicit annotations")
    void errorPropagationShouldPreservePayloadFromOkInferredResult() {
        AnalysisResult result = analyze(
                "val r = Ok(42)\n" +
                "val x = r?");

        assertNoDiagnostics(result,
                "A Result created from Ok(...) should preserve its payload type through error propagation");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("error propagation should preserve Result payload types through map")
    void errorPropagationShouldPreservePayloadThroughResultMap() {
        AnalysisResult result = analyze(
                "val r = Ok(42).map { it + 1 }\n" +
                "val x = r?");

        assertNoDiagnostics(result,
                "A mapped Result should preserve the payload type visible to the error-propagation operator");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("let should infer the lambda result type")
    void letShouldInferLambdaResultType() {
        AnalysisResult result = analyze("val x = 5.let { it * 2 }");

        assertNoDiagnostics(result,
                "let should infer and expose the lambda result type");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("run should infer the lambda result type")
    void runShouldInferLambdaResultType() {
        AnalysisResult result = analyze("val x = 5.run { this + 1 }");

        assertNoDiagnostics(result,
                "run should infer and expose the lambda result type");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("apply should preserve the receiver type")
    void applyShouldPreserveReceiverType() {
        AnalysisResult result = analyze(
                "class Counter(var n: Int)\n" +
                "val x = Counter(1).apply { n = 2 }");

        assertNoDiagnostics(result,
                "apply should return the original receiver type");
        assertSymbolType(result, "x", "Counter");
    }

    @Test
    @DisplayName("also should preserve the receiver type")
    void alsoShouldPreserveReceiverType() {
        AnalysisResult result = analyze("val x = 5.also { println(it) }");

        assertNoDiagnostics(result,
                "also should return the original receiver type");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("scope shorthand should behave like apply")
    void scopeShorthandShouldBehaveLikeApply() {
        AnalysisResult result = analyze(
                "class Person(val name: String)\n" +
                "val p: Person? = Person(\"Alice\")\n" +
                "val x = p?.{ name.length }");

        assertNoDiagnostics(result,
                "Scope shorthand should stay consistent with the apply-style lowering used by the language");
        assertSymbolType(result, "x", "Person?");
    }

    @Test
    @DisplayName("Result.getOrElse should infer the common type of payload and fallback")
    void resultGetOrElseShouldInferCommonType() {
        AnalysisResult result = analyze("val x = Ok(42).getOrElse { 0 }");

        assertNoDiagnostics(result,
                "getOrElse should expose the common type of the success payload and fallback expression");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("Result.flatMap should propagate the inner Result payload type")
    void resultFlatMapShouldPropagateInnerPayloadType() {
        AnalysisResult result = analyze("val x = Ok(42).flatMap { Ok(it + 1) }");

        assertNoDiagnostics(result,
                "flatMap should preserve the payload type of the returned Result");
        assertSymbolType(result, "x", "Result<Int>");
    }

    @Test
    @DisplayName("Result.flatMap should require a Result-returning lambda")
    void resultFlatMapShouldRequireResultReturningLambda() {
        AnalysisResult result = analyze("val x = Ok(42).flatMap { it + 1 }");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR,
                "must return Result",
                "Result.flatMap should report a contract-aware error when the lambda does not return Result");
    }

    @Test
    @DisplayName("Result.fold should infer the common lambda result type")
    void resultFoldShouldInferLambdaResultType() {
        AnalysisResult result = analyze("val x = Ok(42).fold({ it + 1 }, { 0 })");

        assertNoDiagnostics(result,
                "Result.fold should infer the common result type of its success and failure lambdas");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("Result.mapErr should preserve the success payload type")
    void resultMapErrShouldPreserveSuccessPayloadType() {
        AnalysisResult result = analyze(
                "val r: Result<Int> = Err(\"x\")\n" +
                "val x = r.mapErr { \"mapped\" }");

        assertNoDiagnostics(result,
                "mapErr should keep the original success payload type intact");
        assertSymbolType(result, "x", "Result<Int>");
    }

    @Test
    @DisplayName("runCatching should infer a Result payload from its lambda")
    void runCatchingShouldInferResultPayloadType() {
        AnalysisResult result = analyze("val x = runCatching { 42 }");

        assertNoDiagnostics(result,
                "runCatching should infer the lambda result as the Result payload type");
        assertSymbolType(result, "x", "Result<Int>");
    }

    @Test
    @DisplayName("Result.getOrNull should return a nullable payload type")
    void resultGetOrNullShouldReturnNullablePayloadType() {
        AnalysisResult result = analyze("val x = Ok(42).getOrNull()");

        assertNoDiagnostics(result,
                "getOrNull should widen the success payload to nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("Result.getOrThrow should return the payload type")
    void resultGetOrThrowShouldReturnPayloadType() {
        AnalysisResult result = analyze("val x = Ok(42).getOrThrow()");

        assertNoDiagnostics(result,
                "getOrThrow should preserve the success payload type");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("Result.onSuccess should preserve the Result payload type")
    void resultOnSuccessShouldPreserveResultPayloadType() {
        AnalysisResult result = analyze("val x = Ok(42).onSuccess { println(it) }");

        assertNoDiagnostics(result,
                "onSuccess should preserve the original Result payload type");
        assertSymbolType(result, "x", "Result<Int>");
    }

    @Test
    @DisplayName("Result.onFailure should preserve the Result payload type")
    void resultOnFailureShouldPreserveResultPayloadType() {
        AnalysisResult result = analyze(
                "val r: Result<Int> = Err(\"x\")\n" +
                "val x = r.onFailure { println(it) }");

        assertNoDiagnostics(result,
                "onFailure should preserve the original Result payload type");
        assertSymbolType(result, "x", "Result<Int>");
    }

    @Test
    @DisplayName("Result.unwrapOrElse should use the common type of payload and fallback lambda")
    void resultUnwrapOrElseShouldUseCommonType() {
        AnalysisResult result = analyze("val x = Ok(42).unwrapOrElse { 0 }");

        assertNoDiagnostics(result,
                "unwrapOrElse should use the common type of the payload and fallback lambda");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("takeIf should return a nullable receiver type")
    void takeIfShouldReturnNullableReceiverType() {
        AnalysisResult result = analyze("val x = 42.takeIf { it > 0 }");

        assertNoDiagnostics(result,
                "takeIf should return the receiver type widened to nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("takeUnless should return a nullable receiver type")
    void takeUnlessShouldReturnNullableReceiverType() {
        AnalysisResult result = analyze("val x = 42.takeUnless { it < 0 }");

        assertNoDiagnostics(result,
                "takeUnless should return the receiver type widened to nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("List.map should propagate the mapped element type")
    void listMapShouldPropagateMappedElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).map { it.toString() }");

        assertNoDiagnostics(result,
                "List.map should expose the lambda result type as the new element type");
        assertSymbolType(result, "x", "List<String>");
    }

    @Test
    @DisplayName("List.find should return a nullable element type")
    void listFindShouldReturnNullableElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).find { it > 1 }");

        assertNoDiagnostics(result,
                "List.find should return the matching element widened to nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("List.findLast should return a nullable element type")
    void listFindLastShouldReturnNullableElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).findLast { it > 1 }");

        assertNoDiagnostics(result,
                "List.findLast should return the matching element widened to nullable");
        assertSymbolType(result, "x", "Int?");
    }

    @Test
    @DisplayName("List.filter should preserve the original element type")
    void listFilterShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).filter { it > 1 }");

        assertNoDiagnostics(result,
                "List.filter should preserve the original element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.filterNot should preserve the original element type")
    void listFilterNotShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).filterNot { it > 1 }");

        assertNoDiagnostics(result,
                "List.filterNot should preserve the original element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.forEach should infer Unit")
    void listForEachShouldInferUnit() {
        AnalysisResult result = analyze("val x = listOf(1, 2).forEach { println(it) }");

        assertNoDiagnostics(result,
                "List.forEach should infer Unit");
        assertSymbolType(result, "x", "Unit");
    }

    @Test
    @DisplayName("List.forEachIndexed should infer Unit")
    void listForEachIndexedShouldInferUnit() {
        AnalysisResult result = analyze("val x = listOf(1, 2).forEachIndexed { i, v -> println(i + v) }");

        assertNoDiagnostics(result,
                "List.forEachIndexed should infer Unit");
        assertSymbolType(result, "x", "Unit");
    }

    @Test
    @DisplayName("List.takeWhile should preserve the original element type")
    void listTakeWhileShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).takeWhile { it < 2 }");

        assertNoDiagnostics(result,
                "List.takeWhile should preserve the original element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.dropWhile should preserve the original element type")
    void listDropWhileShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).dropWhile { it < 2 }");

        assertNoDiagnostics(result,
                "List.dropWhile should preserve the original element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.any, all, none and count should infer scalar result types")
    void listPredicateAggregatesShouldInferScalarTypes() {
        AnalysisResult result = analyze(
                "val a = listOf(1, 2).any { it > 1 }\n" +
                "val b = listOf(1, 2).all { it > 0 }\n" +
                "val c = listOf(1, 2).none { it < 0 }\n" +
                "val d = listOf(1, 2).count { it > 1 }");

        assertNoDiagnostics(result,
                "List predicate aggregates should infer scalar result types");
        assertSymbolType(result, "a", "Boolean");
        assertSymbolType(result, "b", "Boolean");
        assertSymbolType(result, "c", "Boolean");
        assertSymbolType(result, "d", "Int");
    }

    @Test
    @DisplayName("List.flatMap should flatten and propagate the inner element type")
    void listFlatMapShouldPropagateFlattenedElementType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).flatMap { listOf(it.toString()) }");

        assertNoDiagnostics(result,
                "List.flatMap should infer the element type of the returned collection");
        assertSymbolType(result, "x", "List<String>");
    }

    @Test
    @DisplayName("List.flatMap should require a collection-returning lambda")
    void listFlatMapShouldRequireCollectionReturningLambda() {
        AnalysisResult result = analyze("val x = listOf(1, 2).flatMap { it.toString() }");

        assertHasDiagnosticContaining(result, SemanticDiagnostic.Severity.ERROR,
                "must return a collection",
                "List.flatMap should report a contract-aware error when the lambda does not return a collection");
    }

    @Test
    @DisplayName("Set.map should propagate the mapped element type")
    void setMapShouldPropagateMappedElementType() {
        AnalysisResult result = analyze("val x = setOf(1, 2).map { it.toString() }");

        assertNoDiagnostics(result,
                "Set.map should expose the lambda result type as the new set element type");
        assertSymbolType(result, "x", "Set<String>");
    }

    @Test
    @DisplayName("Map.mapValues should preserve key type and update value type")
    void mapValuesShouldPreserveKeyTypeAndUpdateValueType() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).mapValues { it + 1 }");

        assertNoDiagnostics(result,
                "mapValues should preserve the key type while propagating the mapped value type");
        assertSymbolType(result, "x", "Map<String, Int>");
    }

    @Test
    @DisplayName("List.mapIndexed should propagate the mapped element type")
    void listMapIndexedShouldPropagateMappedElementType() {
        AnalysisResult result = analyze("val x = listOf(10, 20).mapIndexed { i, v -> i + v }");

        assertNoDiagnostics(result,
                "mapIndexed should expose the lambda result type as the new element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.filterIndexed should preserve the original element type")
    void listFilterIndexedShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = listOf(10, 20).filterIndexed { i, v -> i == 0 }");

        assertNoDiagnostics(result,
                "filterIndexed should preserve the original element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.mapNotNull should drop nullability from the mapped element type")
    void listMapNotNullShouldDropNullabilityFromMappedType() {
        AnalysisResult result = analyze("val x = listOf(1, 2, 3).mapNotNull { if (it % 2 == 0) it.toString() else null }");

        assertNoDiagnostics(result,
                "mapNotNull should infer a non-null element type for the kept values");
        assertSymbolType(result, "x", "List<String>");
    }

    @Test
    @DisplayName("List.groupBy should infer map keys and grouped element lists")
    void listGroupByShouldInferGroupedMapType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).groupBy { it.toString() }");

        assertNoDiagnostics(result,
                "List.groupBy should infer the grouping key type and preserve grouped elements");
        assertSymbolType(result, "x", "Map<String, List<Int>>");
    }

    @Test
    @DisplayName("List.partition should infer a pair of typed lists")
    void listPartitionShouldInferPairOfLists() {
        AnalysisResult result = analyze("val x = listOf(1, 2).partition { it > 1 }");

        assertNoDiagnostics(result,
                "List.partition should infer a pair of typed lists");
        assertSymbolType(result, "x", "Pair<List<Int>, List<Int>>");
    }

    @Test
    @DisplayName("List.associateBy should infer map keys and preserve values")
    void listAssociateByShouldInferMapType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).associateBy { it.toString() }");

        assertNoDiagnostics(result,
                "List.associateBy should infer the key type while preserving list element values");
        assertSymbolType(result, "x", "Map<String, Int>");
    }

    @Test
    @DisplayName("List.associateWith should preserve keys and infer values")
    void listAssociateWithShouldInferMapType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).associateWith { it.toString() }");

        assertNoDiagnostics(result,
                "List.associateWith should preserve list elements as keys and infer value types");
        assertSymbolType(result, "x", "Map<Int, String>");
    }

    @Test
    @DisplayName("List.fold should infer the accumulator type from the initial value")
    void listFoldShouldInferAccumulatorType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).fold(0) { acc, v -> acc + v }");

        assertNoDiagnostics(result,
                "List.fold should infer the accumulator type from its initial value");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("List.foldRight should infer the accumulator type from the initial value")
    void listFoldRightShouldInferAccumulatorType() {
        AnalysisResult result = analyze("val x = listOf(1, 2).foldRight(0) { v, acc -> v + acc }");

        assertNoDiagnostics(result,
                "List.foldRight should infer the accumulator type from its initial value");
        assertSymbolType(result, "x", "Int");
    }

    @Test
    @DisplayName("List.chunked and windowed should infer nested list types")
    void listChunkedAndWindowedShouldInferNestedLists() {
        AnalysisResult result = analyze(
                "val a = listOf(1, 2, 3).chunked(2)\n" +
                "val b = listOf(1, 2, 3).windowed(2)");

        assertNoDiagnostics(result,
                "List.chunked and windowed should infer nested list types");
        assertSymbolType(result, "a", "List<List<Int>>");
        assertSymbolType(result, "b", "List<List<Int>>");
    }

    @Test
    @DisplayName("List helper methods should preserve and project element types")
    void listHelperMethodsShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val a = listOf(1, 2).take(1)\n" +
                "val b = listOf(1, 2).drop(1)\n" +
                "val c = listOf(1, 2).takeLast(1)\n" +
                "val d = listOf(1, 2).dropLast(1)\n" +
                "val e = listOf(1, 2).reversed()\n" +
                "val f = listOf(1, 2).distinct()\n" +
                "val g = listOf(1, 2).sorted()\n" +
                "val h = listOf(1, 2).sortedDescending()\n" +
                "val i = listOf(1, 2).sortedBy { it }\n" +
                "val j = listOf(1, 2).joinToString(\",\")\n" +
                "val k = listOf(1, 2).contains(1)\n" +
                "val l = listOf(1, 2).indexOf(2)\n" +
                "val m = listOf(1, 2).firstOrNull()\n" +
                "val n = listOf(1, 2).lastOrNull()\n" +
                "val o = listOf(1, 2).first()\n" +
                "val p = listOf(1, 2).last()\n" +
                "val q = listOf(1).single()\n" +
                "val r = listOf(1, 2).singleOrNull()\n" +
                "val s = listOf(1, 2).toMutableList()\n" +
                "val t = listOf(1, 2).toSet()\n" +
                "val u = listOf(1 to \"a\").toMap()\n" +
                "val v = listOf(1, 2).intersect(listOf(2, 3))\n" +
                "val w = listOf(1, 2).subtract(listOf(2))\n" +
                "val x = listOf(1, 2).union(listOf(3))\n" +
                "val y = listOf(1, 2).sum()\n" +
                "val z = listOf(1, 2).average()\n" +
                "val aa = listOf(1, 2).maxOrNull()\n" +
                "val ab = listOf(1, 2).minOrNull()\n" +
                "val ac = listOf(1, 2).size\n" +
                "val ad = listOf(1, 2).isEmpty\n" +
                "val ae = listOf(1, 2).isNotEmpty\n" +
                "val af = listOf(1, 2).add(3)\n" +
                "val ag = listOf(1, 2).remove(2)\n" +
                "val ah = listOf(1, 2).removeAt(0)\n" +
                "val ai = listOf(1, 2).clear()\n" +
                "val aj = listOf(1, 2).reduce { a, b -> a + b }");

        assertNoDiagnostics(result,
                "List helper methods should preserve and project element types correctly");
        assertSymbolType(result, "a", "List<Int>");
        assertSymbolType(result, "b", "List<Int>");
        assertSymbolType(result, "c", "List<Int>");
        assertSymbolType(result, "d", "List<Int>");
        assertSymbolType(result, "e", "List<Int>");
        assertSymbolType(result, "f", "List<Int>");
        assertSymbolType(result, "g", "List<Int>");
        assertSymbolType(result, "h", "List<Int>");
        assertSymbolType(result, "i", "List<Int>");
        assertSymbolType(result, "j", "String");
        assertSymbolType(result, "k", "Boolean");
        assertSymbolType(result, "l", "Int");
        assertSymbolType(result, "m", "Int?");
        assertSymbolType(result, "n", "Int?");
        assertSymbolType(result, "o", "Int");
        assertSymbolType(result, "p", "Int");
        assertSymbolType(result, "q", "Int");
        assertSymbolType(result, "r", "Int?");
        assertSymbolType(result, "s", "List<Int>");
        assertSymbolType(result, "t", "Set<Int>");
        assertSymbolType(result, "u", "Map<Int, String>");
        assertSymbolType(result, "v", "List<Int>");
        assertSymbolType(result, "w", "List<Int>");
        assertSymbolType(result, "x", "List<Int>");
        assertSymbolType(result, "y", "Number");
        assertSymbolType(result, "z", "Double");
        assertSymbolType(result, "aa", "Int?");
        assertSymbolType(result, "ab", "Int?");
        assertSymbolType(result, "ac", "Int");
        assertSymbolType(result, "ad", "Boolean");
        assertSymbolType(result, "ae", "Boolean");
        assertSymbolType(result, "af", "Boolean");
        assertSymbolType(result, "ag", "Boolean");
        assertSymbolType(result, "ah", "Int");
        assertSymbolType(result, "ai", "Unit");
        assertSymbolType(result, "aj", "Int");
    }

    @Test
    @DisplayName("List.zip should infer paired element types")
    void listZipShouldInferPairElementTypes() {
        AnalysisResult result = analyze("val x = listOf(1, 2).zip(listOf(\"a\", \"b\"))");

        assertNoDiagnostics(result,
                "List.zip should infer the paired element types");
        assertSymbolType(result, "x", "List<Pair<Int, String>>");
    }

    @Test
    @DisplayName("List.withIndex should infer indexed pairs")
    void listWithIndexShouldInferPairType() {
        AnalysisResult result = analyze("val x = listOf(\"a\", \"b\").withIndex()");

        assertNoDiagnostics(result,
                "List.withIndex should infer indexed pair elements");
        assertSymbolType(result, "x", "List<Pair<Int, String>>");
    }

    @Test
    @DisplayName("List.unzip should infer pair-of-lists return type")
    void listUnzipShouldInferPairOfListsType() {
        AnalysisResult result = analyze("val x = listOf(1 to \"a\", 2 to \"b\").unzip()");

        assertNoDiagnostics(result,
                "List.unzip should infer the pair of projected lists");
        assertSymbolType(result, "x", "Pair<List<Int>, List<String>>");
    }

    @Test
    @DisplayName("List.flatten should infer the inner element type")
    void listFlattenShouldInferInnerElementType() {
        AnalysisResult result = analyze("val x = listOf(listOf(1), listOf(2)).flatten()");

        assertNoDiagnostics(result,
                "List.flatten should infer the inner element type");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("List.maxBy, minBy and sumBy should infer scalar result types")
    void listSelectionAggregatesShouldInferTypes() {
        AnalysisResult result = analyze(
                "val a = listOf(1, 2).maxBy { it }\n" +
                "val b = listOf(1, 2).minBy { it }\n" +
                "val c = listOf(1, 2).sumBy { it }");

        assertNoDiagnostics(result,
                "List selection aggregates should infer their result types");
        assertSymbolType(result, "a", "Int?");
        assertSymbolType(result, "b", "Int?");
        assertSymbolType(result, "c", "Number");
    }

    @Test
    @DisplayName("Set.filter should preserve the original element type")
    void setFilterShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = setOf(1, 2).filter { it > 1 }");

        assertNoDiagnostics(result,
                "Set.filter should preserve the original element type");
        assertSymbolType(result, "x", "Set<Int>");
    }

    @Test
    @DisplayName("Set.flatMap should flatten and propagate the inner element type")
    void setFlatMapShouldPropagateFlattenedElementType() {
        AnalysisResult result = analyze("val x = setOf(1, 2).flatMap { listOf(it.toString()) }");

        assertNoDiagnostics(result,
                "Set.flatMap should infer the element type of the returned collection");
        assertSymbolType(result, "x", "List<String>");
    }

    @Test
    @DisplayName("Set.groupBy should infer grouped map types")
    void setGroupByShouldInferGroupedMapType() {
        AnalysisResult result = analyze("val x = setOf(1, 2).groupBy { it.toString() }");

        assertNoDiagnostics(result,
                "Set.groupBy should infer the grouping key type and grouped list element type");
        assertSymbolType(result, "x", "Map<String, List<Int>>");
    }

    @Test
    @DisplayName("Set.filterNot should preserve the original element type")
    void setFilterNotShouldPreserveElementType() {
        AnalysisResult result = analyze("val x = setOf(1, 2).filterNot { it > 1 }");

        assertNoDiagnostics(result,
                "Set.filterNot should preserve the original element type");
        assertSymbolType(result, "x", "Set<Int>");
    }

    @Test
    @DisplayName("Set.any, all, none and count should infer scalar result types")
    void setPredicateAggregatesShouldInferScalarTypes() {
        AnalysisResult result = analyze(
                "val a = setOf(1, 2).any { it > 1 }\n" +
                "val b = setOf(1, 2).all { it > 0 }\n" +
                "val c = setOf(1, 2).none { it < 0 }\n" +
                "val d = setOf(1, 2).count { it > 1 }");

        assertNoDiagnostics(result,
                "Set predicate aggregates should infer scalar result types");
        assertSymbolType(result, "a", "Boolean");
        assertSymbolType(result, "b", "Boolean");
        assertSymbolType(result, "c", "Boolean");
        assertSymbolType(result, "d", "Int");
    }

    @Test
    @DisplayName("Set helper methods should preserve and project element types")
    void setHelperMethodsShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val a = setOf(1, 2).forEach { println(it) }\n" +
                "val b = setOf(1, 2).toList()\n" +
                "val c = setOf(1, 2).intersect(setOf(2, 3))\n" +
                "val d = setOf(1, 2).subtract(setOf(2))\n" +
                "val e = setOf(1, 2).union(setOf(3))");

        assertNoDiagnostics(result,
                "Set helper methods should preserve and project element types correctly");
        assertSymbolType(result, "a", "Unit");
        assertSymbolType(result, "b", "List<Int>");
        assertSymbolType(result, "c", "Set<Int>");
        assertSymbolType(result, "d", "Set<Int>");
        assertSymbolType(result, "e", "Set<Int>");
    }

    @Test
    @DisplayName("Map.mapKeys should update the key type and preserve the value type")
    void mapKeysShouldUpdateKeyTypeAndPreserveValueType() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).mapKeys { k, v -> k + \"!\" }");

        assertNoDiagnostics(result,
                "mapKeys should propagate the mapped key type while preserving the value type");
        assertSymbolType(result, "x", "Map<String, Int>");
    }

    @Test
    @DisplayName("Map.filterKeys should preserve both key and value types")
    void mapFilterKeysShouldPreserveMapTypes() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).filterKeys { it != \"b\" }");

        assertNoDiagnostics(result,
                "filterKeys should preserve the original map key and value types");
        assertSymbolType(result, "x", "Map<String, Int>");
    }

    @Test
    @DisplayName("Map.filterValues should preserve both key and value types")
    void mapFilterValuesShouldPreserveMapTypes() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).filterValues { it > 0 }");

        assertNoDiagnostics(result,
                "filterValues should preserve the original map key and value types");
        assertSymbolType(result, "x", "Map<String, Int>");
    }

    @Test
    @DisplayName("Map.filter should preserve both key and value types")
    void mapFilterShouldPreserveMapTypes() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).filter { k, v -> v > 0 }");

        assertNoDiagnostics(result,
                "filter should preserve the original map key and value types");
        assertSymbolType(result, "x", "Map<String, Int>");
    }

    @Test
    @DisplayName("Map.flatMap should infer the flattened element type")
    void mapFlatMapShouldInferFlattenedElementType() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).flatMap { k, v -> listOf(v) }");

        assertNoDiagnostics(result,
                "Map.flatMap should infer the element type of the returned collection");
        assertSymbolType(result, "x", "List<Int>");
    }

    @Test
    @DisplayName("Map.map should infer the mapped element type")
    void mapMapShouldInferMappedElementType() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).map { k, v -> k + v.toString() }");

        assertNoDiagnostics(result,
                "Map.map should infer the mapped element type");
        assertSymbolType(result, "x", "List<String>");
    }

    @Test
    @DisplayName("Map.forEach should infer Unit")
    void mapForEachShouldInferUnit() {
        AnalysisResult result = analyze("val x = mapOf(\"a\" to 1).forEach { k, v -> println(k + v.toString()) }");

        assertNoDiagnostics(result,
                "Map.forEach should infer Unit");
        assertSymbolType(result, "x", "Unit");
    }

    @Test
    @DisplayName("Map.any, all, none and count should infer scalar result types")
    void mapPredicateAggregatesShouldInferScalarTypes() {
        AnalysisResult result = analyze(
                "val a = mapOf(\"a\" to 1).any { k, v -> v > 0 }\n" +
                "val b = mapOf(\"a\" to 1).all { k, v -> k == \"a\" }\n" +
                "val c = mapOf(\"a\" to 1).none { k, v -> v < 0 }\n" +
                "val d = mapOf(\"a\" to 1).count { k, v -> v > 0 }");

        assertNoDiagnostics(result,
                "Map predicate aggregates should infer scalar result types");
        assertSymbolType(result, "a", "Boolean");
        assertSymbolType(result, "b", "Boolean");
        assertSymbolType(result, "c", "Boolean");
        assertSymbolType(result, "d", "Int");
    }

    @Test
    @DisplayName("Map helper methods should infer keys, values and lookup result types")
    void mapHelperMethodsShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val a = mapOf(\"a\" to 1).keys()\n" +
                "val b = mapOf(\"a\" to 1).values()\n" +
                "val c = mapOf(\"a\" to 1).entries()\n" +
                "val d = mapOf(\"a\" to 1).get(\"a\")\n" +
                "val e = mapOf(\"a\" to 1).getOrDefault(\"b\", 0)\n" +
                "val f = mapOf(\"a\" to 1).containsKey(\"a\")\n" +
                "val g = mapOf(\"a\" to 1).containsValue(1)\n" +
                "val h = mapOf(\"a\" to 1).merge(mapOf(\"b\" to 2))\n" +
                "val i = mapOf(\"a\" to 1).toList()\n" +
                "val j = mapOf(\"a\" to 1).toMutableMap()\n" +
                "val k = mapOf(\"a\" to 1).size\n" +
                "val l = mapOf(\"a\" to 1).isEmpty\n" +
                "val m = mapOf(\"a\" to 1).isNotEmpty\n" +
                "val n = mapOf(\"a\" to 1).getOrPut(\"b\") { 2 }\n" +
                "val o = mapOf(\"a\" to 1).put(\"b\", 2)\n" +
                "val p = mapOf(\"a\" to 1).remove(\"a\")\n" +
                "val q = mapOf(\"a\" to 1).clear()\n" +
                "val r = runCatching { 42 }.isOk\n" +
                "val s = runCatching { 42 }.isErr");

        assertNoDiagnostics(result,
                "Map helper methods should infer keys, values and lookup result types");
        assertSymbolType(result, "a", "List<String>");
        assertSymbolType(result, "b", "List<Int>");
        assertSymbolType(result, "c", "List<Pair<String, Int>>");
        assertSymbolType(result, "d", "Int?");
        assertSymbolType(result, "e", "Int");
        assertSymbolType(result, "f", "Boolean");
        assertSymbolType(result, "g", "Boolean");
        assertSymbolType(result, "h", "Map<String, Int>");
        assertSymbolType(result, "i", "List<Pair<String, Int>>");
        assertSymbolType(result, "j", "Map<String, Int>");
        assertSymbolType(result, "k", "Int");
        assertSymbolType(result, "l", "Boolean");
        assertSymbolType(result, "m", "Boolean");
        assertSymbolType(result, "n", "Int");
        assertSymbolType(result, "o", "Unit");
        assertSymbolType(result, "p", "Int?");
        assertSymbolType(result, "q", "Unit");
        assertSymbolType(result, "r", "Boolean");
        assertSymbolType(result, "s", "Boolean");
    }

    @Test
    @DisplayName("Range helper methods should infer scalar, list and lambda-driven result types")
    void rangeHelperMethodsShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val a = (1..3).size\n" +
                "val b = (1..3).first\n" +
                "val c = (1..3).last\n" +
                "val d = (1..3).contains(2)\n" +
                "val e = (1..3).toList()\n" +
                "val f = (1..3).map { it + 1 }\n" +
                "val g = (1..3).filter { it > 1 }\n" +
                "val h = (1..3).forEach { println(it) }");

        assertNoDiagnostics(result,
                "Range helper methods should infer scalar, list and lambda-driven result types");
        assertSymbolType(result, "a", "Int");
        assertSymbolType(result, "b", "Int");
        assertSymbolType(result, "c", "Int");
        assertSymbolType(result, "d", "Boolean");
        assertSymbolType(result, "e", "List<Int>");
        assertSymbolType(result, "f", "List<Int>");
        assertSymbolType(result, "g", "List<Int>");
        assertSymbolType(result, "h", "Unit");
    }

    @Test
    @DisplayName("String helper methods should infer scalar, list and lambda-driven result types")
    void stringHelperMethodsShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val a = \"abc\".length\n" +
                "val b = \"abc\".isEmpty\n" +
                "val c = \"abc\".isNotEmpty\n" +
                "val d = \" \".isBlank\n" +
                "val e = \"a,b\".split(\",\")\n" +
                "val f = \"abc\".contains(\"b\")\n" +
                "val g = \"abc\".startsWith(\"a\")\n" +
                "val h = \"abc\".endsWith(\"c\")\n" +
                "val i = \"abc\".indexOf(\"b\")\n" +
                "val j = \"abc\".take(2)\n" +
                "val k = \"abc\".drop(1)\n" +
                "val l = \"abc\".takeLast(2)\n" +
                "val m = \"abc\".dropLast(1)\n" +
                "val n = \"abc\".toList()\n" +
                "val o = \"a\\nb\".lines()\n" +
                "val p = \"abc\".chars()\n" +
                "val q = \"abc\".any { it == 'a' }\n" +
                "val r = \"abc\".all { it != 'z' }\n" +
                "val s = \"abc\".none { it == 'z' }\n" +
                "val t = \"abc\".count { it != 'z' }");

        assertNoDiagnostics(result,
                "String helper methods should infer scalar, list and lambda-driven result types");
        assertSymbolType(result, "a", "Int");
        assertSymbolType(result, "b", "Boolean");
        assertSymbolType(result, "c", "Boolean");
        assertSymbolType(result, "d", "Boolean");
        assertSymbolType(result, "e", "List<String>");
        assertSymbolType(result, "f", "Boolean");
        assertSymbolType(result, "g", "Boolean");
        assertSymbolType(result, "h", "Boolean");
        assertSymbolType(result, "i", "Int");
        assertSymbolType(result, "j", "String");
        assertSymbolType(result, "k", "String");
        assertSymbolType(result, "l", "String");
        assertSymbolType(result, "m", "String");
        assertSymbolType(result, "n", "List<Char>");
        assertSymbolType(result, "o", "List<String>");
        assertSymbolType(result, "p", "List<Char>");
        assertSymbolType(result, "q", "Boolean");
        assertSymbolType(result, "r", "Boolean");
        assertSymbolType(result, "s", "Boolean");
        assertSymbolType(result, "t", "Int");
    }

    @Test
    @DisplayName("String transformation and conversion helpers should infer expected types")
    void stringTransformationHelpersShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val a = \"abc\".toUpperCase()\n" +
                "val b = \"ABC\".toLowerCase()\n" +
                "val c = \"abc\".uppercase()\n" +
                "val d = \"ABC\".lowercase()\n" +
                "val e = \" abc \".trim()\n" +
                "val f = \" abc\".trimStart()\n" +
                "val g = \"abc \".trimEnd()\n" +
                "val h = \"abc\".reverse()\n" +
                "val i = \"abc\".capitalize()\n" +
                "val j = \"Abc\".decapitalize()\n" +
                "val k = \"abc\".replace(\"a\", \"z\")\n" +
                "val l = \"abc\".substring(1)\n" +
                "val m = \"abc\".repeat(2)\n" +
                "val n = \"prefixName\".removePrefix(\"prefix\")\n" +
                "val o = \"nameSuffix\".removeSuffix(\"Suffix\")\n" +
                "val p = \"7\".padStart(3, '0')\n" +
                "val q = \"7\".padEnd(3, '0')\n" +
                "val r = \"42\".toInt()\n" +
                "val s = \"42\".toLong()\n" +
                "val t = \"4.2\".toDouble()\n" +
                "val u = \"true\".toBoolean()\n" +
                "val v = \"42\".toIntOrNull()\n" +
                "val w = \"42\".toLongOrNull()\n" +
                "val x = \"4.2\".toDoubleOrNull()\n" +
                "val y = \"ababa\".lastIndexOf(\"a\")\n" +
                "val z = \"ababa\".replaceFirst(\"a\", \"z\")\n" +
                "val aa = \"123\".matches(\"\\\\d+\")\n" +
                "val ab = \"%s-%d\".format(\"x\", 1)");

        assertNoDiagnostics(result,
                "String transformation and conversion helpers should infer expected types");
        assertSymbolType(result, "a", "String");
        assertSymbolType(result, "b", "String");
        assertSymbolType(result, "c", "String");
        assertSymbolType(result, "d", "String");
        assertSymbolType(result, "e", "String");
        assertSymbolType(result, "f", "String");
        assertSymbolType(result, "g", "String");
        assertSymbolType(result, "h", "String");
        assertSymbolType(result, "i", "String");
        assertSymbolType(result, "j", "String");
        assertSymbolType(result, "k", "String");
        assertSymbolType(result, "l", "String");
        assertSymbolType(result, "m", "String");
        assertSymbolType(result, "n", "String");
        assertSymbolType(result, "o", "String");
        assertSymbolType(result, "p", "String");
        assertSymbolType(result, "q", "String");
        assertSymbolType(result, "r", "Int");
        assertSymbolType(result, "s", "Long");
        assertSymbolType(result, "t", "Double");
        assertSymbolType(result, "u", "Boolean");
        assertSymbolType(result, "v", "Int?");
        assertSymbolType(result, "w", "Long?");
        assertSymbolType(result, "x", "Double?");
        assertSymbolType(result, "y", "Int");
        assertSymbolType(result, "z", "String");
        assertSymbolType(result, "aa", "Boolean");
        assertSymbolType(result, "ab", "String");
    }

    @Test
    @DisplayName("Array helper methods should infer scalar, list and lambda-driven result types")
    void arrayHelperMethodsShouldInferExpectedTypes() {
        AnalysisResult result = analyze(
                "val arr = arrayOf(1, 2)\n" +
                "val a = arr.size\n" +
                "val b = arr.toList()\n" +
                "val c = arr.map { it + 1 }\n" +
                "val d = arr.filter { it > 1 }\n" +
                "val e = arr.contains(1)\n" +
                "val f = arr.indexOf(2)\n" +
                "val g = arr.forEach { println(it) }");

        assertNoDiagnostics(result,
                "Array helper methods should infer scalar, list and lambda-driven result types");
        assertSymbolType(result, "a", "Int");
        assertSymbolType(result, "b", "List<Int>");
        assertSymbolType(result, "c", "List<Int>");
        assertSymbolType(result, "d", "List<Int>");
        assertSymbolType(result, "e", "Boolean");
        assertSymbolType(result, "f", "Int");
        assertSymbolType(result, "g", "Unit");
    }
}
