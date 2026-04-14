package com.novalang.compiler.analysis.contracts;

import com.novalang.compiler.analysis.TypeUnifier;
import com.novalang.compiler.analysis.types.ClassNovaType;
import com.novalang.compiler.analysis.types.FunctionNovaType;
import com.novalang.compiler.analysis.types.NovaType;
import com.novalang.compiler.analysis.types.NovaTypeArgument;
import com.novalang.compiler.analysis.types.NovaTypes;
import com.novalang.compiler.analysis.types.SuperTypeRegistry;
import com.novalang.compiler.analysis.types.TypeResolver;
import com.novalang.compiler.ast.SourceLocation;
import com.novalang.compiler.ast.expr.Expression;
import com.novalang.compiler.ast.expr.Identifier;
import com.novalang.compiler.ast.expr.IfExpr;
import com.novalang.compiler.ast.expr.Literal;
import com.novalang.compiler.ast.expr.LambdaExpr;
import com.novalang.compiler.ast.expr.MemberExpr;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Compiler stdlib contract engine")
class ContractEngineTest {

    private static final SourceLocation LOC = SourceLocation.UNKNOWN;
    private final HashMap<Expression, NovaType> exprTypes = new HashMap<Expression, NovaType>();

    private final ContractEngine engine = new ContractEngine(
            new TypeUnifier(exprTypes,
                    new SuperTypeRegistry(),
                    new TypeResolver()),
            exprTypes);

    @Test
    @DisplayName("scope let should supply an it-style lambda signature from contract metadata")
    void scopeLetShouldProvideImplicitItLambdaType() {
        FunctionNovaType functionType = engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "let"),
                NovaTypes.INT,
                implicitLambda());

        assertNotNull(functionType);
        assertNull(functionType.getReceiverType());
        assertEquals(Collections.singletonList(NovaTypes.INT), functionType.getParamTypes());
        assertEquals(NovaTypes.ANY.withNullable(true), functionType.getReturnType());
    }

    @Test
    @DisplayName("scope run should supply a receiver lambda signature from contract metadata")
    void scopeRunShouldProvideReceiverLambdaType() {
        FunctionNovaType functionType = engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "run"),
                NovaTypes.INT,
                implicitLambda());

        assertNotNull(functionType);
        assertEquals(NovaTypes.INT, functionType.getReceiverType());
        assertEquals(Collections.emptyList(), functionType.getParamTypes());
        assertEquals(NovaTypes.ANY.withNullable(true), functionType.getReturnType());
    }

    @Test
    @DisplayName("scope let should infer the lambda result as the call result through the contract engine")
    void scopeLetShouldInferLambdaResult() {
        NovaType resultType = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "let"),
                NovaTypes.INT,
                implicitLambda(),
                NovaTypes.STRING);

        assertEquals(NovaTypes.STRING, resultType);
    }

    @Test
    @DisplayName("scope apply and takeIf should preserve the receiver semantics declared by their contracts")
    void scopeApplyAndTakeIfShouldPreserveReceiverSemantics() {
        NovaType applyType = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "apply"),
                NovaTypes.INT,
                implicitLambda(),
                NovaTypes.UNIT);
        NovaType takeIfType = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "takeIf"),
                NovaTypes.INT,
                implicitLambda(),
                NovaTypes.BOOLEAN);

        assertEquals(NovaTypes.INT, applyType);
        assertEquals(NovaTypes.INT.withNullable(true), takeIfType);
    }

    @Test
    @DisplayName("result contracts should infer lambda signatures and return types")
    void resultContractsShouldInferLambdaSignaturesAndReturnTypes() {
        NovaType resultInt = resultOf(NovaTypes.INT);

        FunctionNovaType mapType = engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "map"),
                resultInt,
                implicitLambda());
        FunctionNovaType fallbackType = engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "getOrElse"),
                resultInt,
                implicitLambda());

        NovaType mappedResult = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "map"),
                resultInt,
                implicitLambda(),
                NovaTypes.STRING);
        NovaType flatMappedResult = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "flatMap"),
                resultInt,
                implicitLambda(),
                resultOf(NovaTypes.STRING));
        NovaType fallbackResult = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "getOrElse"),
                resultInt,
                implicitLambda(),
                NovaTypes.STRING);

        assertEquals(Collections.singletonList(NovaTypes.INT), mapType.getParamTypes());
        assertEquals(Collections.emptyList(), fallbackType.getParamTypes());
        assertEquals(resultOf(NovaTypes.STRING), mappedResult);
        assertEquals(resultOf(NovaTypes.STRING), flatMappedResult);
        assertEquals(NovaTypes.ANY, fallbackResult);
    }

    @Test
    @DisplayName("function contracts should infer top-level Result constructors")
    void functionContractsShouldInferTopLevelResultConstructors() {
        NovaType okType = engine.inferFunctionCallType("Ok",
                Collections.singletonList((NovaType) NovaTypes.INT),
                null);
        java.util.List<String> okDiagnostics = engine.validateFunctionCall("Ok",
                Collections.singletonList((NovaType) NovaTypes.INT),
                null);
        NovaType errDefaultType = engine.inferFunctionCallType("Err",
                Collections.singletonList((NovaType) NovaTypes.STRING),
                null);
        java.util.List<String> errDiagnostics = engine.validateFunctionCall("Err",
                Collections.singletonList((NovaType) NovaTypes.STRING),
                resultOf(NovaTypes.INT));
        NovaType errContextualType = engine.inferFunctionCallType("Err",
                Collections.singletonList((NovaType) NovaTypes.STRING),
                resultOf(NovaTypes.INT));

        assertEquals(resultOf(NovaTypes.INT), okType);
        assertEquals(resultOf(NovaTypes.ANY), errDefaultType);
        assertEquals(resultOf(NovaTypes.INT), errContextualType);
        assertEquals(Collections.emptyList(), okDiagnostics);
        assertEquals(Collections.emptyList(), errDiagnostics);
    }

    @Test
    @DisplayName("collection contracts should infer lambda signatures and transformed container types")
    void collectionContractsShouldInferLambdaSignaturesAndContainerTypes() {
        NovaType listInt = NovaTypes.listOf(NovaTypes.INT);
        NovaType setInt = NovaTypes.setOf(NovaTypes.INT);
        NovaType mapStringInt = NovaTypes.mapOf(NovaTypes.STRING, NovaTypes.INT);

        FunctionNovaType mapIndexedType = engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "mapIndexed"),
                listInt,
                explicitLambda("index", "value"));
        FunctionNovaType mapValuesType = engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "mapValues"),
                mapStringInt,
                explicitLambda("value"));

        NovaType listMapped = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "map"),
                listInt,
                implicitLambda(),
                NovaTypes.STRING);
        NovaType listMapNotNull = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "mapNotNull"),
                listInt,
                mapNotNullLambda(),
                NovaTypes.STRING.withNullable(true));
        NovaType setFlatMapped = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "flatMap"),
                setInt,
                implicitLambda(),
                NovaTypes.listOf(NovaTypes.STRING));
        NovaType mapValuesMapped = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "mapValues"),
                mapStringInt,
                explicitLambda("value"),
                NovaTypes.BOOLEAN);
        NovaType mapKeysMapped = engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "mapKeys"),
                mapStringInt,
                explicitLambda("key", "value"),
                NovaTypes.INT);

        assertEquals(java.util.Arrays.asList(NovaTypes.INT, NovaTypes.INT), mapIndexedType.getParamTypes());
        assertEquals(Collections.singletonList(NovaTypes.INT), mapValuesType.getParamTypes());
        assertEquals(NovaTypes.listOf(NovaTypes.STRING), listMapped);
        assertEquals(NovaTypes.listOf(NovaTypes.STRING), listMapNotNull);
        assertEquals(NovaTypes.listOf(NovaTypes.STRING), setFlatMapped);
        assertEquals(NovaTypes.mapOf(NovaTypes.STRING, NovaTypes.BOOLEAN), mapValuesMapped);
        assertEquals(NovaTypes.mapOf(NovaTypes.INT, NovaTypes.INT), mapKeysMapped);
    }

    @Test
    @DisplayName("non-contracted members should fall back to the existing analyzer logic")
    void unknownMembersShouldReturnNull() {
        assertNull(engine.expectedMemberLambdaType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "unknown"),
                NovaTypes.INT,
                implicitLambda()));
        assertNull(engine.inferMemberCallType(
                new MemberExpr(LOC, new Identifier(LOC, "value"), "unknown"),
                NovaTypes.INT,
                implicitLambda(),
                NovaTypes.STRING));
    }

    private static LambdaExpr implicitLambda() {
        return new LambdaExpr(LOC, Collections.<LambdaExpr.LambdaParam>emptyList(), new Identifier(LOC, "it"));
    }

    @SuppressWarnings("unused")
    private static LambdaExpr explicitLambda(String... names) {
        java.util.List<LambdaExpr.LambdaParam> params = new java.util.ArrayList<LambdaExpr.LambdaParam>();
        for (String name : names) {
            params.add(new LambdaExpr.LambdaParam(LOC, name, null));
        }
        return new LambdaExpr(LOC, params, new Identifier(LOC, names.length > 0 ? names[0] : "x"));
    }

    private LambdaExpr mapNotNullLambda() {
        Literal thenExpr = new Literal(LOC, "value", Literal.LiteralKind.STRING);
        Literal elseExpr = new Literal(LOC, null, Literal.LiteralKind.NULL);
        IfExpr ifExpr = new IfExpr(LOC, new Identifier(LOC, "cond"), null, thenExpr, elseExpr);
        exprTypes.put(thenExpr, NovaTypes.STRING);
        exprTypes.put(elseExpr, NovaTypes.NOTHING_NULLABLE);
        exprTypes.put(ifExpr, NovaTypes.ANY);
        return new LambdaExpr(LOC, Collections.<LambdaExpr.LambdaParam>emptyList(), ifExpr);
    }

    private static NovaType resultOf(NovaType payloadType) {
        return new ClassNovaType("Result",
                Collections.singletonList(NovaTypeArgument.invariant(payloadType)),
                false);
    }
}
