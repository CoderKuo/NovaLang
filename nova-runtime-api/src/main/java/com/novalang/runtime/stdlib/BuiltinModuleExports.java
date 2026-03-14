package com.novalang.runtime.stdlib;

import java.util.*;

/**
 * 内置模块编译期元数据注册表。
 *
 * <p>供 HirToMirLowering（nova-ir 模块）在编译模式下识别内置模块导入，
 * 将命名空间符号映射为 Java 类（INVOKESTATIC），将顶层函数映射为静态方法。</p>
 *
 * <p>运行时实现在 nova-runtime 的 StdlibXxxCompiled 类中。
 * 顶层函数通过 {@link #getModuleClass(String)} 获取类名后，由 HirToMirLowering 反射自动发现，
 * 无需逐个注册。</p>
 */
public final class BuiltinModuleExports {

    // moduleName → (symbolName → JVM internal class name)  — 命名空间（内部类）
    private static final Map<String, Map<String, String>> namespaceExports = new HashMap<>();
    // moduleName → JVM internal class name  — 顶层函数所在的编译类（反射自动发现）
    private static final Map<String, String> moduleClasses = new HashMap<>();

    // Nova 语言级模块前缀（用户代码中 import nova.time 等）
    private static final String LANG_NAME = "nova";
    private static final String LANG_DOT = "nova.";

    static {
        // 从自身类名推导 stdlib 包路径（兼容 shadow relocate）
        String className = BuiltinModuleExports.class.getName();
        String self = className.replace('.', '/');
        String stdlib = self.substring(0, self.lastIndexOf('/'))
                .replace("/stdlib", "/interpreter/stdlib") + "/";

        // nova.time — 命名空间
        Map<String, String> timeNs = new HashMap<>();
        timeNs.put("DateTime", stdlib + "StdlibTimeCompiled$DateTime");
        timeNs.put("Duration", stdlib + "StdlibTimeCompiled$DurationNs");
        namespaceExports.put(LANG_DOT + "time", timeNs);

        // 模块 → 编译类映射（顶层函数由 HirToMirLowering 反射发现）
        moduleClasses.put(LANG_DOT + "time", stdlib + "StdlibTimeCompiled");
        moduleClasses.put(LANG_DOT + "io", stdlib + "StdlibIOCompiled");
        moduleClasses.put(LANG_DOT + "system", stdlib + "StdlibSystemCompiled");
        moduleClasses.put(LANG_DOT + "json", stdlib + "StdlibJsonCompiled");
        moduleClasses.put(LANG_DOT + "text", stdlib + "StdlibRegexCompiled");
        moduleClasses.put(LANG_DOT + "http", stdlib + "StdlibHttpCompiled");
        moduleClasses.put(LANG_DOT + "test", stdlib + "StdlibTestCompiled");
    }

    private BuiltinModuleExports() {}

    /** 诊断方法：打印模块注册表状态（排查 shadow relocate 问题） */
    public static String debugDump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== BuiltinModuleExports Debug ===\n");
        sb.append("Class: ").append(BuiltinModuleExports.class.getName()).append('\n');
        sb.append("LANG_DOT: ").append(LANG_DOT).append('\n');
        sb.append("moduleClasses keys: ").append(moduleClasses.keySet()).append('\n');
        sb.append("moduleClasses values: ").append(moduleClasses.values()).append('\n');
        sb.append("namespaceExports keys: ").append(namespaceExports.keySet()).append('\n');
        sb.append("has(\"").append(LANG_DOT).append("time\"): ").append(has(LANG_DOT + "time")).append('\n');
        sb.append("getModuleClass(\"").append(LANG_DOT).append("time\"): ").append(getModuleClass(LANG_DOT + "time")).append('\n');
        // 尝试加载类
        String cls = getModuleClass(LANG_DOT + "time");
        if (cls != null) {
            try {
                Class.forName(cls.replace('/', '.'));
                sb.append("Class.forName OK\n");
            } catch (ClassNotFoundException e) {
                sb.append("Class.forName FAILED: ").append(e.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    /** 检查是否为已注册的内置模块 */
    public static boolean has(String moduleName) {
        return namespaceExports.containsKey(moduleName) || moduleClasses.containsKey(moduleName);
    }

    /** 获取模块的命名空间导出：symbolName → JVM internal class name */
    public static Map<String, String> getNamespaces(String moduleName) {
        Map<String, String> ns = namespaceExports.get(moduleName);
        return ns != null ? ns : Collections.emptyMap();
    }

    /** 获取模块顶层函数所在的 JVM 类（内部名格式），未注册返回 null */
    public static String getModuleClass(String moduleName) {
        return moduleClasses.get(moduleName);
    }

    /**
     * 从 import 的 qualifiedName 解析内置模块名。
     * <p>例如 "nova.time" → "nova.time"，"nova.time.DateTime" → "nova.time"</p>
     *
     * @return 模块名，未匹配返回 null
     */
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
}
