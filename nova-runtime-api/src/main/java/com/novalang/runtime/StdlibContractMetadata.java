package com.novalang.runtime;

import com.novalang.runtime.contract.NovaContract;
import com.novalang.runtime.contract.StdlibContracts;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bridge that binds existing stdlib metadata/annotations to declarative contracts.
 */
public final class StdlibContractMetadata {
    private static final Map<String, NovaContract> FUNCTION_CONTRACTS = new LinkedHashMap<String, NovaContract>();
    private static final Map<String, Map<String, NovaContract>> TYPE_MEMBER_CONTRACTS =
            new LinkedHashMap<String, Map<String, NovaContract>>();

    static {
        registerCoreContracts();
    }

    private StdlibContractMetadata() {}

    public static NovaContract functionContract(String functionName) {
        return FUNCTION_CONTRACTS.get(functionName);
    }

    public static NovaContract memberContract(String typeName, String memberName) {
        Map<String, NovaContract> members = TYPE_MEMBER_CONTRACTS.get(typeName);
        return members != null ? members.get(memberName) : null;
    }

    public static Map<String, NovaContract> functionContracts() {
        return Collections.unmodifiableMap(FUNCTION_CONTRACTS);
    }

    public static Map<String, Map<String, NovaContract>> memberContracts() {
        Map<String, Map<String, NovaContract>> snapshot = new LinkedHashMap<String, Map<String, NovaContract>>();
        for (Map.Entry<String, Map<String, NovaContract>> entry : TYPE_MEMBER_CONTRACTS.entrySet()) {
            snapshot.put(entry.getKey(), Collections.unmodifiableMap(entry.getValue()));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    public static void registerFunctionContract(String functionName, String contractId) {
        NovaContract contract = StdlibContracts.get(contractId);
        if (contract != null) {
            FUNCTION_CONTRACTS.put(functionName, contract);
        }
    }

    public static void registerMemberContract(String typeName, String memberName, String contractId) {
        NovaContract contract = StdlibContracts.get(contractId);
        if (contract == null) return;
        TYPE_MEMBER_CONTRACTS
                .computeIfAbsent(typeName, ignored -> new LinkedHashMap<String, NovaContract>())
                .put(memberName, contract);
    }

    public static void scanAnnotatedClass(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            NovaType typeAnnotation = clazz.getAnnotation(NovaType.class);
            if (typeAnnotation == null) return;
            String typeName = typeAnnotation.name();
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                NovaMember memberAnnotation = method.getAnnotation(NovaMember.class);
                if (memberAnnotation != null && !memberAnnotation.contract().isEmpty()) {
                    registerMemberContract(typeName, method.getName(), memberAnnotation.contract());
                }
            }
        } catch (ClassNotFoundException ignored) {
        }
    }

    private static void registerCoreContracts() {
        registerFunctionContract("Ok", "result.ok");
        registerFunctionContract("Err", "result.err");
        registerFunctionContract("runCatching", "result.runCatching");

        registerMemberContract("String", "length", "string.length");
        registerMemberContract("String", "isEmpty", "string.isEmpty");
        registerMemberContract("String", "isNotEmpty", "string.isNotEmpty");
        registerMemberContract("String", "isBlank", "string.isBlank");
        registerMemberContract("String", "toUpperCase", "string.toUpperCase");
        registerMemberContract("String", "toLowerCase", "string.toLowerCase");
        registerMemberContract("String", "uppercase", "string.uppercase");
        registerMemberContract("String", "lowercase", "string.lowercase");
        registerMemberContract("String", "trim", "string.trim");
        registerMemberContract("String", "trimStart", "string.trimStart");
        registerMemberContract("String", "trimEnd", "string.trimEnd");
        registerMemberContract("String", "reverse", "string.reverse");
        registerMemberContract("String", "capitalize", "string.capitalize");
        registerMemberContract("String", "decapitalize", "string.decapitalize");
        registerMemberContract("String", "split", "string.split");
        registerMemberContract("String", "contains", "string.contains");
        registerMemberContract("String", "startsWith", "string.startsWith");
        registerMemberContract("String", "endsWith", "string.endsWith");
        registerMemberContract("String", "indexOf", "string.indexOf");
        registerMemberContract("String", "replace", "string.replace");
        registerMemberContract("String", "substring", "string.substring");
        registerMemberContract("String", "repeat", "string.repeat");
        registerMemberContract("String", "take", "string.take");
        registerMemberContract("String", "drop", "string.drop");
        registerMemberContract("String", "takeLast", "string.takeLast");
        registerMemberContract("String", "dropLast", "string.dropLast");
        registerMemberContract("String", "removePrefix", "string.removePrefix");
        registerMemberContract("String", "removeSuffix", "string.removeSuffix");
        registerMemberContract("String", "padStart", "string.padStart");
        registerMemberContract("String", "padEnd", "string.padEnd");
        registerMemberContract("String", "toList", "string.toList");
        registerMemberContract("String", "lines", "string.lines");
        registerMemberContract("String", "chars", "string.chars");
        registerMemberContract("String", "toInt", "string.toInt");
        registerMemberContract("String", "toLong", "string.toLong");
        registerMemberContract("String", "toDouble", "string.toDouble");
        registerMemberContract("String", "toBoolean", "string.toBoolean");
        registerMemberContract("String", "toIntOrNull", "string.toIntOrNull");
        registerMemberContract("String", "toLongOrNull", "string.toLongOrNull");
        registerMemberContract("String", "toDoubleOrNull", "string.toDoubleOrNull");
        registerMemberContract("String", "lastIndexOf", "string.lastIndexOf");
        registerMemberContract("String", "replaceFirst", "string.replaceFirst");
        registerMemberContract("String", "matches", "string.matches");
        registerMemberContract("String", "format", "string.format");
        registerMemberContract("String", "any", "string.any");
        registerMemberContract("String", "all", "string.all");
        registerMemberContract("String", "none", "string.none");
        registerMemberContract("String", "count", "string.count");

        registerMemberContract("Range", "size", "range.size");
        registerMemberContract("Range", "first", "range.first");
        registerMemberContract("Range", "last", "range.last");
        registerMemberContract("Range", "contains", "range.contains");
        registerMemberContract("Range", "toList", "range.toList");
        registerMemberContract("Range", "forEach", "range.forEach");
        registerMemberContract("Range", "map", "range.map");
        registerMemberContract("Range", "filter", "range.filter");

        registerMemberContract("Array", "size", "array.size");
        registerMemberContract("Array", "toList", "array.toList");
        registerMemberContract("Array", "forEach", "array.forEach");
        registerMemberContract("Array", "map", "array.map");
        registerMemberContract("Array", "filter", "array.filter");
        registerMemberContract("Array", "contains", "array.contains");
        registerMemberContract("Array", "indexOf", "array.indexOf");

        registerMemberContract("Any", "let", "scope.let");
        registerMemberContract("Any", "also", "scope.also");
        registerMemberContract("Any", "run", "scope.run");
        registerMemberContract("Any", "apply", "scope.apply");
        registerMemberContract("Any", "takeIf", "scope.takeIf");
        registerMemberContract("Any", "takeUnless", "scope.takeUnless");

        registerMemberContract("Result", "getOrNull", "result.getOrNull");
        registerMemberContract("Result", "isOk", "result.isOk");
        registerMemberContract("Result", "isErr", "result.isErr");
        registerMemberContract("Result", "getOrElse", "result.getOrElse");
        registerMemberContract("Result", "getOrThrow", "result.getOrThrow");
        registerMemberContract("Result", "map", "result.map");
        registerMemberContract("Result", "flatMap", "result.flatMap");
        registerMemberContract("Result", "fold", "result.fold");
        registerMemberContract("Result", "mapErr", "result.mapErr");
        registerMemberContract("Result", "onSuccess", "result.onSuccess");
        registerMemberContract("Result", "onFailure", "result.onFailure");
        registerMemberContract("Result", "unwrapOrElse", "result.unwrapOrElse");

        registerMemberContract("List", "size", "list.size");
        registerMemberContract("List", "isEmpty", "list.isEmpty");
        registerMemberContract("List", "isNotEmpty", "list.isNotEmpty");
        registerMemberContract("List", "find", "list.find");
        registerMemberContract("List", "findLast", "list.findLast");
        registerMemberContract("List", "first", "list.first");
        registerMemberContract("List", "last", "list.last");
        registerMemberContract("List", "firstOrNull", "list.firstOrNull");
        registerMemberContract("List", "lastOrNull", "list.lastOrNull");
        registerMemberContract("List", "single", "list.single");
        registerMemberContract("List", "singleOrNull", "list.singleOrNull");
        registerMemberContract("List", "contains", "list.contains");
        registerMemberContract("List", "add", "list.add");
        registerMemberContract("List", "remove", "list.remove");
        registerMemberContract("List", "removeAt", "list.removeAt");
        registerMemberContract("List", "clear", "list.clear");
        registerMemberContract("List", "indexOf", "list.indexOf");
        registerMemberContract("List", "map", "list.map");
        registerMemberContract("List", "filter", "list.filter");
        registerMemberContract("List", "filterNot", "list.filterNot");
        registerMemberContract("List", "forEach", "list.forEach");
        registerMemberContract("List", "forEachIndexed", "list.forEachIndexed");
        registerMemberContract("List", "joinToString", "list.joinToString");
        registerMemberContract("List", "flatMap", "list.flatMap");
        registerMemberContract("List", "take", "list.take");
        registerMemberContract("List", "drop", "list.drop");
        registerMemberContract("List", "takeLast", "list.takeLast");
        registerMemberContract("List", "dropLast", "list.dropLast");
        registerMemberContract("List", "reversed", "list.reversed");
        registerMemberContract("List", "distinct", "list.distinct");
        registerMemberContract("List", "shuffled", "list.shuffled");
        registerMemberContract("List", "sorted", "list.sorted");
        registerMemberContract("List", "sortedDescending", "list.sortedDescending");
        registerMemberContract("List", "sortedBy", "list.sortedBy");
        registerMemberContract("List", "takeWhile", "list.takeWhile");
        registerMemberContract("List", "dropWhile", "list.dropWhile");
        registerMemberContract("List", "any", "list.any");
        registerMemberContract("List", "all", "list.all");
        registerMemberContract("List", "none", "list.none");
        registerMemberContract("List", "count", "list.count");
        registerMemberContract("List", "groupBy", "list.groupBy");
        registerMemberContract("List", "associateBy", "list.associateBy");
        registerMemberContract("List", "associateWith", "list.associateWith");
        registerMemberContract("List", "mapIndexed", "list.mapIndexed");
        registerMemberContract("List", "filterIndexed", "list.filterIndexed");
        registerMemberContract("List", "mapNotNull", "list.mapNotNull");
        registerMemberContract("List", "fold", "list.fold");
        registerMemberContract("List", "foldRight", "list.foldRight");
        registerMemberContract("List", "reduce", "list.reduce");
        registerMemberContract("List", "partition", "list.partition");
        registerMemberContract("List", "chunked", "list.chunked");
        registerMemberContract("List", "windowed", "list.windowed");
        registerMemberContract("List", "zip", "list.zip");
        registerMemberContract("List", "withIndex", "list.withIndex");
        registerMemberContract("List", "unzip", "list.unzip");
        registerMemberContract("List", "flatten", "list.flatten");
        registerMemberContract("List", "intersect", "list.intersect");
        registerMemberContract("List", "subtract", "list.subtract");
        registerMemberContract("List", "union", "list.union");
        registerMemberContract("List", "toMutableList", "list.toMutableList");
        registerMemberContract("List", "toSet", "list.toSet");
        registerMemberContract("List", "toMap", "list.toMap");
        registerMemberContract("List", "sum", "list.sum");
        registerMemberContract("List", "average", "list.average");
        registerMemberContract("List", "maxOrNull", "list.maxOrNull");
        registerMemberContract("List", "minOrNull", "list.minOrNull");
        registerMemberContract("List", "maxBy", "list.maxBy");
        registerMemberContract("List", "minBy", "list.minBy");
        registerMemberContract("List", "sumBy", "list.sumBy");

        registerMemberContract("Set", "size", "set.size");
        registerMemberContract("Set", "isEmpty", "set.isEmpty");
        registerMemberContract("Set", "isNotEmpty", "set.isNotEmpty");
        registerMemberContract("Set", "map", "set.map");
        registerMemberContract("Set", "filter", "set.filter");
        registerMemberContract("Set", "forEach", "set.forEach");
        registerMemberContract("Set", "filterNot", "set.filterNot");
        registerMemberContract("Set", "flatMap", "set.flatMap");
        registerMemberContract("Set", "groupBy", "set.groupBy");
        registerMemberContract("Set", "any", "set.any");
        registerMemberContract("Set", "all", "set.all");
        registerMemberContract("Set", "none", "set.none");
        registerMemberContract("Set", "count", "set.count");
        registerMemberContract("Set", "contains", "set.contains");
        registerMemberContract("Set", "add", "set.add");
        registerMemberContract("Set", "remove", "set.remove");
        registerMemberContract("Set", "clear", "set.clear");
        registerMemberContract("Set", "toList", "set.toList");
        registerMemberContract("Set", "intersect", "set.intersect");
        registerMemberContract("Set", "subtract", "set.subtract");
        registerMemberContract("Set", "union", "set.union");

        registerMemberContract("Map", "size", "map.size");
        registerMemberContract("Map", "isEmpty", "map.isEmpty");
        registerMemberContract("Map", "isNotEmpty", "map.isNotEmpty");
        registerMemberContract("Map", "map", "map.map");
        registerMemberContract("Map", "keys", "map.keys");
        registerMemberContract("Map", "values", "map.values");
        registerMemberContract("Map", "entries", "map.entries");
        registerMemberContract("Map", "get", "map.get");
        registerMemberContract("Map", "getOrDefault", "map.getOrDefault");
        registerMemberContract("Map", "getOrPut", "map.getOrPut");
        registerMemberContract("Map", "containsKey", "map.containsKey");
        registerMemberContract("Map", "containsValue", "map.containsValue");
        registerMemberContract("Map", "put", "map.put");
        registerMemberContract("Map", "remove", "map.remove");
        registerMemberContract("Map", "clear", "map.clear");
        registerMemberContract("Map", "merge", "map.merge");
        registerMemberContract("Map", "toList", "map.toList");
        registerMemberContract("Map", "toMutableMap", "map.toMutableMap");
        registerMemberContract("Map", "mapValues", "map.mapValues");
        registerMemberContract("Map", "mapKeys", "map.mapKeys");
        registerMemberContract("Map", "filterKeys", "map.filterKeys");
        registerMemberContract("Map", "filterValues", "map.filterValues");
        registerMemberContract("Map", "filter", "map.filter");
        registerMemberContract("Map", "flatMap", "map.flatMap");
        registerMemberContract("Map", "forEach", "map.forEach");
        registerMemberContract("Map", "any", "map.any");
        registerMemberContract("Map", "all", "map.all");
        registerMemberContract("Map", "none", "map.none");
        registerMemberContract("Map", "count", "map.count");
    }
}
