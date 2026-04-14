package com.novalang.runtime.stdlib;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Compile-time metadata for builtin import modules such as `nova.time`.
 */
public final class BuiltinModuleExports {

    public static final class ExportedFunctionInfo {
        public final String name;
        public final int arity;
        public final String returnType;

        public ExportedFunctionInfo(String name, int arity, String returnType) {
            this.name = name;
            this.arity = arity;
            this.returnType = returnType;
        }
    }

    private static final Map<String, Map<String, String>> namespaceExports = new HashMap<String, Map<String, String>>();
    private static final Map<String, String> moduleClasses = new HashMap<String, String>();
    private static final Map<String, Map<String, ExportedFunctionInfo>> moduleFunctions =
            new HashMap<String, Map<String, ExportedFunctionInfo>>();

    private static final String LANG_DOT = "nova.";

    static {
        String className = BuiltinModuleExports.class.getName();
        String self = className.replace('.', '/');
        String stdlib = self.substring(0, self.lastIndexOf('/'))
                .replace("/stdlib", "/interpreter/stdlib") + "/";

        Map<String, String> timeNamespaces = new HashMap<String, String>();
        timeNamespaces.put("DateTime", stdlib + "StdlibTimeCompiled$DateTime");
        timeNamespaces.put("Duration", stdlib + "StdlibTimeCompiled$DurationNs");
        namespaceExports.put(LANG_DOT + "time", timeNamespaces);

        moduleClasses.put(LANG_DOT + "time", stdlib + "StdlibTimeCompiled");
        moduleClasses.put(LANG_DOT + "io", stdlib + "StdlibIOCompiled");
        moduleClasses.put(LANG_DOT + "system", stdlib + "StdlibSystemCompiled");
        moduleClasses.put(LANG_DOT + "json", stdlib + "StdlibJsonCompiled");
        moduleClasses.put(LANG_DOT + "text", stdlib + "StdlibRegexCompiled");
        moduleClasses.put(LANG_DOT + "http", stdlib + "StdlibHttpCompiled");
        moduleClasses.put(LANG_DOT + "test", stdlib + "StdlibTestCompiled");
        moduleClasses.put(LANG_DOT + "yaml", stdlib + "StdlibYamlCompiled");
        moduleClasses.put(LANG_DOT + "encoding", stdlib + "StdlibEncodingCompiled");
        moduleClasses.put(LANG_DOT + "crypto", stdlib + "StdlibCryptoCompiled");

        registerFunction(LANG_DOT + "system", "env", 1, "String?");
        registerFunction(LANG_DOT + "system", "envOrDefault", 2, "String");
        registerFunction(LANG_DOT + "system", "allEnv", 0, "Map");
        registerFunction(LANG_DOT + "system", "sysProperty", 1, "String?");
        registerFunction(LANG_DOT + "system", "exec", 1, "Int");
        registerFunction(LANG_DOT + "system", "exit", 1, "Unit");
        registerFunction(LANG_DOT + "system", "osName", 0, "String");
        registerFunction(LANG_DOT + "system", "jvmVersion", 0, "String");
        registerFunction(LANG_DOT + "system", "novaVersion", 0, "String");
        registerFunction(LANG_DOT + "system", "availableProcessors", 0, "Int");
        registerFunction(LANG_DOT + "system", "totalMemory", 0, "Long");
        registerFunction(LANG_DOT + "system", "freeMemory", 0, "Long");

        registerFunction(LANG_DOT + "test", "test", 2, "Unit");
        registerFunction(LANG_DOT + "test", "testGroup", 2, "Unit");
        registerFunction(LANG_DOT + "test", "runTests", 0, "Map");
        registerFunction(LANG_DOT + "test", "assertEqual", 2, "Unit");
        registerFunction(LANG_DOT + "test", "assertNotEqual", 2, "Unit");
        registerFunction(LANG_DOT + "test", "assertTrue", 1, "Unit");
        registerFunction(LANG_DOT + "test", "assertFalse", 1, "Unit");
        registerFunction(LANG_DOT + "test", "assertNull", 1, "Unit");
        registerFunction(LANG_DOT + "test", "assertNotNull", 1, "Unit");
        registerFunction(LANG_DOT + "test", "assertThrows", 1, "String");
        registerFunction(LANG_DOT + "test", "assertContains", 2, "Unit");
        registerFunction(LANG_DOT + "test", "assertFails", 1, "Unit");
    }

    private BuiltinModuleExports() {}

    public static String debugDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== BuiltinModuleExports Debug ===\n");
        sb.append("Class: ").append(BuiltinModuleExports.class.getName()).append('\n');
        sb.append("moduleClasses keys: ").append(moduleClasses.keySet()).append('\n');
        sb.append("moduleFunctions keys: ").append(moduleFunctions.keySet()).append('\n');
        sb.append("namespaceExports keys: ").append(namespaceExports.keySet()).append('\n');
        return sb.toString();
    }

    public static boolean has(String moduleName) {
        return namespaceExports.containsKey(moduleName)
                || moduleClasses.containsKey(moduleName)
                || moduleFunctions.containsKey(moduleName);
    }

    public static Map<String, String> getNamespaces(String moduleName) {
        Map<String, String> namespaces = namespaceExports.get(moduleName);
        return namespaces != null ? namespaces : Collections.<String, String>emptyMap();
    }

    public static String getModuleClass(String moduleName) {
        return moduleClasses.get(moduleName);
    }

    public static Map<String, ExportedFunctionInfo> getFunctions(String moduleName) {
        Map<String, ExportedFunctionInfo> functions = moduleFunctions.get(moduleName);
        return functions != null ? functions : Collections.<String, ExportedFunctionInfo>emptyMap();
    }

    public static ExportedFunctionInfo getFunction(String moduleName, String functionName) {
        Map<String, ExportedFunctionInfo> functions = moduleFunctions.get(moduleName);
        return functions != null ? functions.get(functionName) : null;
    }

    public static String resolveModuleName(String qualifiedName) {
        if (qualifiedName == null || !qualifiedName.startsWith(LANG_DOT)) return null;
        String candidate = qualifiedName;
        while (candidate.contains(".")) {
            if (has(candidate)) return candidate;
            int lastDot = candidate.lastIndexOf('.');
            candidate = candidate.substring(0, lastDot);
        }
        return null;
    }

    private static void registerFunction(String moduleName, String name, int arity, String returnType) {
        moduleFunctions
                .computeIfAbsent(moduleName, ignored -> new LinkedHashMap<String, ExportedFunctionInfo>())
                .put(name, new ExportedFunctionInfo(name, arity, returnType));
    }
}
