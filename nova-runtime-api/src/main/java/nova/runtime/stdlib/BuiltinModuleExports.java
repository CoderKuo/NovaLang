package nova.runtime.stdlib;

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

    static {
        // nova.time — 命名空间
        Map<String, String> timeNs = new HashMap<>();
        timeNs.put("DateTime", "nova/runtime/interpreter/stdlib/StdlibTimeCompiled$DateTime");
        timeNs.put("Duration", "nova/runtime/interpreter/stdlib/StdlibTimeCompiled$DurationNs");
        namespaceExports.put("nova.time", timeNs);

        // 模块 → 编译类映射（顶层函数由 HirToMirLowering 反射发现）
        moduleClasses.put("nova.time", "nova/runtime/interpreter/stdlib/StdlibTimeCompiled");
        moduleClasses.put("nova.io", "nova/runtime/interpreter/stdlib/StdlibIOCompiled");
        moduleClasses.put("nova.system", "nova/runtime/interpreter/stdlib/StdlibSystemCompiled");
        moduleClasses.put("nova.json", "nova/runtime/interpreter/stdlib/StdlibJsonCompiled");
        moduleClasses.put("nova.text", "nova/runtime/interpreter/stdlib/StdlibRegexCompiled");
        moduleClasses.put("nova.http", "nova/runtime/interpreter/stdlib/StdlibHttpCompiled");
        moduleClasses.put("nova.test", "nova/runtime/interpreter/stdlib/StdlibTestCompiled");
    }

    private BuiltinModuleExports() {}

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
        if (qualifiedName == null || !qualifiedName.startsWith("nova.")) return null;
        String candidate = qualifiedName;
        while (candidate.contains(".")) {
            if (has(candidate)) return candidate;
            int lastDot = candidate.lastIndexOf('.');
            candidate = candidate.substring(0, lastDot);
        }
        return null;
    }
}
