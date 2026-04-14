package com.novalang.runtime.contract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.novalang.runtime.contract.NovaContract.Tag.*;
import static com.novalang.runtime.contract.NovaContract.LambdaShape.*;

/**
 * Central registry of declarative stdlib contracts.
 */
public final class StdlibContracts {
    private static final Map<String, NovaContract> CONTRACTS = new LinkedHashMap<String, NovaContract>();

    static {
        registerScopeContracts();
        registerResultContracts();
        registerCoreFunctionContracts();
        registerStringContracts();
        registerCollectionContracts();
        registerMapContracts();
        registerRangeContracts();
        registerArrayContracts();
    }

    private StdlibContracts() {}

    public static NovaContract get(String id) {
        return id == null || id.isEmpty() ? null : CONTRACTS.get(id);
    }

    public static Map<String, NovaContract> all() {
        return Collections.unmodifiableMap(CONTRACTS);
    }

    private static void register(NovaContract contract) {
        CONTRACTS.put(contract.getId(), contract);
    }

    private static void registerScopeContracts() {
        register(NovaContract.member("scope.let")
                .typeParams("T", "R")
                .receiver(TypeExpr.typeVar("T"))
                .lambdaArg("block",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("R"))
                .tag(SCOPE_FUNCTION, RETURNS_LAMBDA_RESULT)
                .build());

        register(NovaContract.member("scope.also")
                .typeParam("T")
                .receiver(TypeExpr.typeVar("T"))
                .lambdaArg("block",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("T"))
                .tag(SCOPE_FUNCTION, RETURNS_RECEIVER)
                .build());

        register(NovaContract.member("scope.run")
                .typeParams("T", "R")
                .receiver(TypeExpr.typeVar("T"))
                .lambdaArg("block",
                        NovaContract.receiverVariant(RECEIVER_BLOCK, TypeExpr.typeVar("T"), TypeExpr.typeVar("R")))
                .returns(TypeExpr.typeVar("R"))
                .tag(SCOPE_FUNCTION, RETURNS_LAMBDA_RESULT, SUPPORTS_RECEIVER_LAMBDA)
                .build());

        register(NovaContract.member("scope.apply")
                .typeParam("T")
                .receiver(TypeExpr.typeVar("T"))
                .lambdaArg("block",
                        NovaContract.receiverVariant(RECEIVER_BLOCK, TypeExpr.typeVar("T"), TypeExpr.concrete("Any")))
                .returns(TypeExpr.typeVar("T"))
                .tag(SCOPE_FUNCTION, RETURNS_RECEIVER, SUPPORTS_RECEIVER_LAMBDA)
                .build());

        register(NovaContract.member("scope.takeIf")
                .typeParam("T")
                .receiver(TypeExpr.typeVar("T"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .tag(SCOPE_FUNCTION, RETURNS_NULLABLE_RECEIVER)
                .build());

        register(NovaContract.member("scope.takeUnless")
                .typeParam("T")
                .receiver(TypeExpr.typeVar("T"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .tag(SCOPE_FUNCTION, RETURNS_NULLABLE_RECEIVER)
                .build());
    }

    private static void registerResultContracts() {
        register(NovaContract.function("result.ok")
                .typeParam("T")
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.function("result.err")
                .typeParam("T")
                .valueArg("error", TypeExpr.concrete("Any"))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.getOrElse")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("fallback",
                        NovaContract.variant(ZERO_ARG_BLOCK, TypeExpr.typeVar("R")))
                .returns(TypeExpr.commonSuper(TypeExpr.payloadType(TypeExpr.receiver()), TypeExpr.lambdaResult(0)))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM, RETURNS_LAMBDA_RESULT)
                .build());

        register(NovaContract.member("result.getOrNull")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.isOk")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.isErr")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.getOrThrow")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("T"))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.map")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("R")))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.onSuccess")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM, RETURNS_RECEIVER)
                .build());

        register(NovaContract.member("result.onFailure")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.concrete("Any")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.concrete("Any")))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM, RETURNS_RECEIVER)
                .build());

        register(NovaContract.member("result.unwrapOrElse")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("fallback",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.concrete("Any")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.concrete("Any")))
                .returns(TypeExpr.commonSuper(TypeExpr.payloadType(TypeExpr.receiver()), TypeExpr.lambdaResult(0)))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM, RETURNS_LAMBDA_RESULT)
                .build());

        register(NovaContract.member("result.flatMap")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Result", TypeExpr.typeVar("R")), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Result", TypeExpr.typeVar("R")), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("R")))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .constraint(ConstraintExpr.lambdaReturnsBaseType("Result"))
                .tag(RESULT_FLATMAP)
                .build());

        register(NovaContract.member("result.fold")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("onSuccess",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")))
                .lambdaArg("onFailure",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.concrete("Any")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.concrete("Any")))
                .returns(TypeExpr.typeVar("R"))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());

        register(NovaContract.member("result.mapErr")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.concrete("Any")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.concrete("Any")))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .constraint(ConstraintExpr.receiverBaseType("Result"))
                .tag(RESULT_TRANSFORM)
                .build());
    }

    private static void registerCoreFunctionContracts() {
        register(NovaContract.function("result.runCatching")
                .typeParam("T")
                .lambdaArg("block",
                        NovaContract.variant(ZERO_ARG_BLOCK, TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Result", TypeExpr.typeVar("T")))
                .tag(RESULT_TRANSFORM)
                .build());
    }

    private static void registerStringContracts() {
        register(NovaContract.member("string.length")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("string.isEmpty")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.isNotEmpty")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.isBlank")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.toUpperCase")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.toLowerCase")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.uppercase")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.lowercase")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.trim")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.trimStart")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.trimEnd")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.reverse")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.capitalize")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.decapitalize")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.split")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("separator", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("String")))
                .build());

        register(NovaContract.member("string.contains")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("substring", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.startsWith")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("prefix", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.endsWith")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("suffix", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.indexOf")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("substring", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("string.replace")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("target", TypeExpr.concrete("String"))
                .valueArg("replacement", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.substring")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("start", TypeExpr.concrete("Int"))
                .valueArg("end", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.repeat")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.take")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.drop")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.takeLast")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.dropLast")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.removePrefix")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("prefix", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.removeSuffix")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("suffix", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.padStart")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("length", TypeExpr.concrete("Int"))
                .valueArg("padChar", TypeExpr.concrete("Char"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.padEnd")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("length", TypeExpr.concrete("Int"))
                .valueArg("padChar", TypeExpr.concrete("Char"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.toList")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("Char")))
                .build());

        register(NovaContract.member("string.lines")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("String")))
                .build());

        register(NovaContract.member("string.chars")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("Char")))
                .build());

        register(NovaContract.member("string.toInt")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("string.toLong")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Long"))
                .build());

        register(NovaContract.member("string.toDouble")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Double"))
                .build());

        register(NovaContract.member("string.toBoolean")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.toIntOrNull")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.nullable(TypeExpr.concrete("Int")))
                .build());

        register(NovaContract.member("string.toLongOrNull")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.nullable(TypeExpr.concrete("Long")))
                .build());

        register(NovaContract.member("string.toDoubleOrNull")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.nullable(TypeExpr.concrete("Double")))
                .build());

        register(NovaContract.member("string.lastIndexOf")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("substring", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("string.replaceFirst")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("target", TypeExpr.concrete("String"))
                .valueArg("replacement", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.matches")
                .receiver(TypeExpr.concrete("String"))
                .valueArg("regex", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.format")
                .receiver(TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("string.any")
                .receiver(TypeExpr.concrete("String"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.all")
                .receiver(TypeExpr.concrete("String"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.none")
                .receiver(TypeExpr.concrete("String"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("string.count")
                .receiver(TypeExpr.concrete("String"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Char")))
                .returns(TypeExpr.concrete("Int"))
                .build());
    }

    private static void registerRangeContracts() {
        register(NovaContract.member("range.size")
                .receiver(TypeExpr.concrete("Range"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("range.first")
                .receiver(TypeExpr.concrete("Range"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("range.last")
                .receiver(TypeExpr.concrete("Range"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("range.contains")
                .receiver(TypeExpr.concrete("Range"))
                .valueArg("value", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("range.toList")
                .receiver(TypeExpr.concrete("Range"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("Int")))
                .build());

        register(NovaContract.member("range.forEach")
                .receiver(TypeExpr.concrete("Range"))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.concrete("Int")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.concrete("Int")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("range.map")
                .typeParam("R")
                .receiver(TypeExpr.concrete("Range"))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.concrete("Int")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.concrete("Int")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("R")))
                .tag(COLLECTION_MAP)
                .build());

        register(NovaContract.member("range.filter")
                .receiver(TypeExpr.concrete("Range"))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Int")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Int")))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("Int")))
                .tag(COLLECTION_FILTER)
                .build());
    }

    private static void registerArrayContracts() {
        register(NovaContract.member("array.size")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("array.toList")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("array.forEach")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("array.map")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("R")))
                .tag(COLLECTION_MAP)
                .build());

        register(NovaContract.member("array.filter")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("array.contains")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("array.indexOf")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Array", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Int"))
                .build());
    }

    private static void registerCollectionContracts() {
        register(NovaContract.member("list.size")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("list.isEmpty")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("list.isNotEmpty")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("list.map")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("R")))
                .tag(COLLECTION_MAP)
                .build());

        register(NovaContract.member("list.filter")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.filterNot")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.forEach")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("list.forEachIndexed")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("action",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.concrete("Int"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Unit"))
                .tag(SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("list.flatMap")
                .typeParams("T", "U")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("List", TypeExpr.typeVar("U")), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("List", TypeExpr.typeVar("U")), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("U")))
                .constraint(ConstraintExpr.lambdaReturnsCollection())
                .tag(COLLECTION_FLATMAP)
                .build());

        register(NovaContract.member("list.find")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.firstOrNull")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.lastOrNull")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.first")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("T"))
                .build());

        register(NovaContract.member("list.last")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("T"))
                .build());

        register(NovaContract.member("list.single")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("T"))
                .build());

        register(NovaContract.member("list.singleOrNull")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.contains")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("list.add")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("list.remove")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("list.removeAt")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("index", TypeExpr.concrete("Int"))
                .returns(TypeExpr.typeVar("T"))
                .build());

        register(NovaContract.member("list.clear")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("list.indexOf")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("list.joinToString")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("separator", TypeExpr.concrete("String"))
                .returns(TypeExpr.concrete("String"))
                .build());

        register(NovaContract.member("list.take")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.drop")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.takeLast")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.dropLast")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("count", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.reversed")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.distinct")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.shuffled")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.sorted")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.sortedDescending")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.sortedBy")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("selector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.findLast")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.takeWhile")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.dropWhile")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.any")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.all")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.none")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.count")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Int"))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("list.groupBy")
                .typeParams("T", "K")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("keySelector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("K"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("K"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"),
                        TypeExpr.concrete("List", TypeExpr.typeVar("T"))))
                .build());

        register(NovaContract.member("list.partition")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Pair",
                        TypeExpr.concrete("List", TypeExpr.typeVar("T")),
                        TypeExpr.concrete("List", TypeExpr.typeVar("T"))))
                .build());

        register(NovaContract.member("list.associateBy")
                .typeParams("T", "K")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("keySelector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("K"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("K"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.associateWith")
                .typeParams("T", "V")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("valueSelector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("V"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("V"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("T"), TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("list.mapIndexed")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.concrete("Int"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("R")))
                .tag(COLLECTION_MAP, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("list.filterIndexed")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.concrete("Int"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("list.mapNotNull")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.nullable(TypeExpr.typeVar("R")), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.nullable(TypeExpr.typeVar("R")), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.nonNull(TypeExpr.typeVar("R"))))
                .tag(COLLECTION_MAP, COLLECTION_MAP_NOT_NULL)
                .build());

        register(NovaContract.member("list.fold")
                .typeParams("T", "A")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("initial", TypeExpr.typeVar("A"))
                .lambdaArg("operation",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("A"), TypeExpr.typeVar("A"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("A"))
                .build());

        register(NovaContract.member("list.reduce")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("operation",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("T"), TypeExpr.typeVar("T"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.typeVar("T"))
                .build());

        register(NovaContract.member("list.foldRight")
                .typeParams("T", "A")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("initial", TypeExpr.typeVar("A"))
                .lambdaArg("operation",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("A"), TypeExpr.typeVar("T"), TypeExpr.typeVar("A")))
                .returns(TypeExpr.typeVar("A"))
                .build());

        register(NovaContract.member("list.chunked")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("size", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("List", TypeExpr.typeVar("T"))))
                .build());

        register(NovaContract.member("list.windowed")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("size", TypeExpr.concrete("Int"))
                .valueArg("step", TypeExpr.concrete("Int"))
                .returns(TypeExpr.concrete("List", TypeExpr.concrete("List", TypeExpr.typeVar("T"))))
                .build());

        register(NovaContract.member("list.zip")
                .typeParams("T", "U")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("List", TypeExpr.typeVar("U")))
                .returns(TypeExpr.concrete("List",
                        TypeExpr.concrete("Pair", TypeExpr.typeVar("T"), TypeExpr.typeVar("U"))))
                .build());

        register(NovaContract.member("list.withIndex")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List",
                        TypeExpr.concrete("Pair", TypeExpr.concrete("Int"), TypeExpr.typeVar("T"))))
                .build());

        register(NovaContract.member("list.unzip")
                .typeParams("A", "B")
                .receiver(TypeExpr.concrete("List",
                        TypeExpr.concrete("Pair", TypeExpr.typeVar("A"), TypeExpr.typeVar("B"))))
                .returns(TypeExpr.concrete("Pair",
                        TypeExpr.concrete("List", TypeExpr.typeVar("A")),
                        TypeExpr.concrete("List", TypeExpr.typeVar("B"))))
                .build());

        register(NovaContract.member("list.flatten")
                .typeParam("U")
                .receiver(TypeExpr.concrete("List", TypeExpr.concrete("List", TypeExpr.typeVar("U"))))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("U")))
                .build());

        register(NovaContract.member("list.intersect")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.subtract")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.union")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.toMutableList")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.toSet")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.toMap")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("List", TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("list.sum")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Number"))
                .build());

        register(NovaContract.member("list.average")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Double"))
                .build());

        register(NovaContract.member("list.maxOrNull")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.minOrNull")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.maxBy")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("selector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.minBy")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("selector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("list.sumBy")
                .typeParam("T")
                .receiver(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .lambdaArg("selector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Number"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Number"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Number"))
                .build());

        register(NovaContract.member("set.map")
                .typeParams("T", "R")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("R")))
                .tag(COLLECTION_MAP)
                .build());

        register(NovaContract.member("set.size")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("set.isEmpty")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.isNotEmpty")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.contains")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.add")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.remove")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .valueArg("value", TypeExpr.typeVar("T"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.clear")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("set.filter")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("set.flatMap")
                .typeParams("T", "U")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("List", TypeExpr.typeVar("U")), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("List", TypeExpr.typeVar("U")), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("U")))
                .constraint(ConstraintExpr.lambdaReturnsCollection())
                .tag(COLLECTION_FLATMAP)
                .build());

        register(NovaContract.member("set.groupBy")
                .typeParams("T", "K")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("keySelector",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("K"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("K"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"),
                        TypeExpr.concrete("List", TypeExpr.typeVar("T"))))
                .build());

        register(NovaContract.member("set.forEach")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("set.filterNot")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .tag(COLLECTION_FILTER)
                .build());

        register(NovaContract.member("set.any")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.all")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.none")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("set.count")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("set.toList")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("set.intersect")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("set.subtract")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .build());

        register(NovaContract.member("set.union")
                .typeParam("T")
                .receiver(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .valueArg("other", TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .returns(TypeExpr.concrete("Set", TypeExpr.typeVar("T")))
                .build());
    }

    private static void registerMapContracts() {
        register(NovaContract.member("map.size")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Int"))
                .build());

        register(NovaContract.member("map.isEmpty")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("map.isNotEmpty")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("map.mapValues")
                .typeParams("K", "V", "V2")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("V2"), TypeExpr.typeVar("V")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("V2"), TypeExpr.typeVar("V")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("V2"), TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V2")))
                .tag(MAP_VALUE_TRANSFORM, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.mapKeys")
                .typeParams("K", "V", "K2")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("K2"), TypeExpr.typeVar("K")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("K2"), TypeExpr.typeVar("K")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("K2"), TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K2"), TypeExpr.typeVar("V")))
                .tag(MAP_KEY_TRANSFORM, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.filterKeys")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("K")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("K")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .tag(MAP_FILTER)
                .build());

        register(NovaContract.member("map.filterValues")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("V")),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .tag(MAP_FILTER)
                .build());

        register(NovaContract.member("map.filter")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"), TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .tag(MAP_FILTER, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.flatMap")
                .typeParams("K", "V", "U")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT,
                                TypeExpr.concrete("List", TypeExpr.typeVar("U")),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS,
                                TypeExpr.concrete("List", TypeExpr.typeVar("U")),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("U")))
                .constraint(ConstraintExpr.lambdaReturnsCollection())
                .tag(COLLECTION_FLATMAP, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.map")
                .typeParams("K", "V", "R")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("transform",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.typeVar("R"),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.typeVar("R"),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("R")))
                .tag(COLLECTION_MAP, SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.keys")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("K")))
                .build());

        register(NovaContract.member("map.values")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("List", TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("map.entries")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("List",
                        TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))))
                .build());

        register(NovaContract.member("map.get")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("key", TypeExpr.typeVar("K"))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("map.getOrDefault")
                .typeParams("K", "V", "D")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("key", TypeExpr.typeVar("K"))
                .valueArg("defaultValue", TypeExpr.typeVar("D"))
                .returns(TypeExpr.commonSuper(TypeExpr.typeVar("V"), TypeExpr.typeVar("D")))
                .build());

        register(NovaContract.member("map.containsKey")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("key", TypeExpr.typeVar("K"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("map.containsValue")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("value", TypeExpr.typeVar("V"))
                .returns(TypeExpr.concrete("Boolean"))
                .build());

        register(NovaContract.member("map.put")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("key", TypeExpr.typeVar("K"))
                .valueArg("value", TypeExpr.typeVar("V"))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("map.remove")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("key", TypeExpr.typeVar("K"))
                .returns(TypeExpr.nullable(TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("map.clear")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Unit"))
                .build());

        register(NovaContract.member("map.merge")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("other", TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("map.getOrPut")
                .typeParams("K", "V", "D")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .valueArg("key", TypeExpr.typeVar("K"))
                .lambdaArg("defaultValue",
                        NovaContract.variant(ZERO_ARG_BLOCK, TypeExpr.typeVar("D")))
                .returns(TypeExpr.commonSuper(TypeExpr.typeVar("V"), TypeExpr.lambdaResult(0)))
                .build());

        register(NovaContract.member("map.toList")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("List",
                        TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))))
                .build());

        register(NovaContract.member("map.toMutableMap")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .build());

        register(NovaContract.member("map.forEach")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("action",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Any"),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Any"),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Unit"))
                .tag(SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.any")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Boolean"))
                .tag(SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.all")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Boolean"))
                .tag(SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.none")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Boolean"))
                .tag(SUPPORTS_BI_LAMBDA)
                .build());

        register(NovaContract.member("map.count")
                .typeParams("K", "V")
                .receiver(TypeExpr.concrete("Map", TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .lambdaArg("predicate",
                        NovaContract.variant(IMPLICIT_IT, TypeExpr.concrete("Boolean"),
                                TypeExpr.concrete("Pair", TypeExpr.typeVar("K"), TypeExpr.typeVar("V"))),
                        NovaContract.variant(EXPLICIT_PARAMS, TypeExpr.concrete("Boolean"),
                                TypeExpr.typeVar("K"), TypeExpr.typeVar("V")))
                .returns(TypeExpr.concrete("Int"))
                .tag(SUPPORTS_BI_LAMBDA)
                .build());
    }
}
