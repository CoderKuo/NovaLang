package nova.runtime.stdlib;

import java.util.*;

/**
 * 内置模块编译期元数据注册表。
 *
 * <p>供 HirToMirLowering（nova-ir 模块）在编译模式下识别内置模块导入，
 * 将命名空间符号映射为 Java 类（INVOKESTATIC），将顶层函数映射为静态方法。</p>
 *
 * <p>运行时实现在 nova-runtime 的 StdlibXxxCompiled 类中。</p>
 */
public final class BuiltinModuleExports {

    /** 模块函数导出信息 */
    public static final class FunctionExport {
        public final String name;
        public final String jvmOwner;
        public final String jvmMethodName;
        public final String jvmDescriptor;
        public final int arity;

        public FunctionExport(String name, String jvmOwner, String jvmMethodName,
                              String jvmDescriptor, int arity) {
            this.name = name;
            this.jvmOwner = jvmOwner;
            this.jvmMethodName = jvmMethodName;
            this.jvmDescriptor = jvmDescriptor;
            this.arity = arity;
        }
    }

    // moduleName → (symbolName → JVM internal class name)
    private static final Map<String, Map<String, String>> namespaceExports = new HashMap<>();
    // moduleName → list of FunctionExport
    private static final Map<String, List<FunctionExport>> functionExports = new HashMap<>();

    private static final String TIME_OWNER = "nova/runtime/interpreter/stdlib/StdlibTimeCompiled";
    private static final String TIME_DT = TIME_OWNER + "$DateTime";
    private static final String TIME_DUR = TIME_OWNER + "$DurationNs";

    static {
        // nova.time
        Map<String, String> timeNs = new HashMap<>();
        timeNs.put("DateTime", TIME_DT);
        timeNs.put("Duration", TIME_DUR);
        namespaceExports.put("nova.time", timeNs);

        List<FunctionExport> timeFns = new ArrayList<>();
        timeFns.add(new FunctionExport("now", TIME_OWNER, "now", "()Ljava/lang/Object;", 0));
        timeFns.add(new FunctionExport("nowNanos", TIME_OWNER, "nowNanos", "()Ljava/lang/Object;", 0));
        timeFns.add(new FunctionExport("sleep", TIME_OWNER, "sleep",
                "(Ljava/lang/Object;)Ljava/lang/Object;", 1));
        functionExports.put("nova.time", timeFns);
    }

    private BuiltinModuleExports() {}

    /** 检查是否为已注册的内置模块 */
    public static boolean has(String moduleName) {
        return namespaceExports.containsKey(moduleName) || functionExports.containsKey(moduleName);
    }

    /** 获取模块的命名空间导出：symbolName → JVM internal class name */
    public static Map<String, String> getNamespaces(String moduleName) {
        Map<String, String> ns = namespaceExports.get(moduleName);
        return ns != null ? ns : Collections.emptyMap();
    }

    /** 获取模块的函数导出 */
    public static List<FunctionExport> getFunctions(String moduleName) {
        List<FunctionExport> fns = functionExports.get(moduleName);
        return fns != null ? fns : Collections.emptyList();
    }

    /**
     * 从 import 的 qualifiedName 解析内置模块名。
     * <p>例如 "nova.time" → "nova.time"，"nova.time.DateTime" → "nova.time"</p>
     *
     * @return 模块名，未匹配返回 null
     */
    public static String resolveModuleName(String qualifiedName) {
        if (qualifiedName == null || !qualifiedName.startsWith("nova.")) return null;
        // 尝试最长前缀匹配（从 qualifiedName 本身到 nova.xxx）
        String candidate = qualifiedName;
        while (candidate.contains(".")) {
            if (has(candidate)) return candidate;
            int lastDot = candidate.lastIndexOf('.');
            candidate = candidate.substring(0, lastDot);
        }
        return null;
    }
}
