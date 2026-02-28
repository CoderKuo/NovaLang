package nova.runtime.interpreter;

import nova.runtime.types.Environment;
import nova.runtime.interpreter.stdlib.*;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 内置模块注册表 — 映射 import 路径到注册函数
 *
 * <p>用法：{@code import nova.io.*} 或 {@code import nova.json.jsonParse}</p>
 */
public final class BuiltinModuleRegistry {

    private static final Map<String, BiConsumer<Environment, Interpreter>> modules = new HashMap<>();

    static {
        modules.put("nova.io", StdlibIO::register);
        modules.put("nova.text", StdlibRegex::register);
        modules.put("nova.json", StdlibJson::register);
        modules.put("nova.time", StdlibTime::register);
        modules.put("nova.http", StdlibHttp::register);
        modules.put("nova.test", StdlibTest::register);
        modules.put("nova.system", StdlibSystem::register);
        modules.put("nova.concurrent", StdlibConcurrent::register);
    }

    private BuiltinModuleRegistry() {}

    public static boolean has(String name) {
        return modules.containsKey(name);
    }

    public static void load(String name, Environment env, Interpreter interp) {
        BiConsumer<Environment, Interpreter> registrar = modules.get(name);
        if (registrar != null) {
            registrar.accept(env, interp);
        }
    }

    /**
     * 从 import 路径部分解析内置模块名。
     * 例如 ["nova", "io", "readFile"] → "nova.io"
     *      ["nova", "json"] → "nova.json"
     */
    public static String resolveModuleName(java.util.List<String> parts) {
        if (parts.size() < 2 || !"nova".equals(parts.get(0))) return null;
        // 尝试最长前缀匹配
        for (int len = Math.min(parts.size(), 3); len >= 2; len--) {
            String candidate = String.join(".", parts.subList(0, len));
            if (modules.containsKey(candidate)) return candidate;
        }
        return null;
    }
}
