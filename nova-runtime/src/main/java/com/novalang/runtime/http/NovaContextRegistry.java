package com.novalang.runtime.http;

import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.Interpreter;
import com.novalang.runtime.stdlib.StdlibRegistry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nova 实例上下文注册表。
 *
 * <p>供 LSP 在 YAML 嵌入式文本块中提供自动补全：
 * <pre>
 * # YAML 中
 * script: |
 *   // nova=plugin:myPlugin
 *   getPlayer("Steve").heal(20)
 * </pre>
 *
 * <p>LSP 读取 {@code // nova=plugin:myPlugin}，通过
 * {@code NovaContextRegistry.get("plugin", "myPlugin")} 获取对应 Nova 实例，
 * 再从中提取可用的函数、变量、扩展函数提供补全。</p>
 *
 * <h3>注册</h3>
 * <pre>
 * Nova nova = new Nova();
 * nova.defineFunction("getPlayer", name -> server.getPlayer(name));
 * NovaContextRegistry.register("plugin", "myPlugin", nova);
 * </pre>
 *
 * <h3>查询</h3>
 * <pre>
 * // 精确查找
 * NovaContextRegistry.Entry ctx = NovaContextRegistry.get("plugin", "myPlugin");
 *
 * // 获取补全项
 * List&lt;CompletionItem&gt; items = NovaContextRegistry.getCompletions("plugin", "myPlugin", "get");
 * </pre>
 */
public final class NovaContextRegistry {

    private NovaContextRegistry() {}

    /** 注册表：compositeKey("key:value") → Entry */
    private static final Map<String, Entry> registry = new ConcurrentHashMap<>();

    /** 注册条目 */
    public static final class Entry {
        private final String key;
        private final String value;
        private final Nova nova;

        Entry(String key, String value, Nova nova) {
            this.key = key;
            this.value = value;
            this.nova = nova;
        }

        public String getKey() { return key; }
        public String getValue() { return value; }
        public Nova getNova() { return nova; }
        public String getCompositeKey() { return key + ":" + value; }
    }

    // ============ 注册 / 注销 ============

    /** 注册 Nova 实例 */
    public static void register(String key, String value, Nova nova) {
        if (key == null || value == null || nova == null) {
            throw new IllegalArgumentException("key, value, nova must not be null");
        }
        registry.put(key + ":" + value, new Entry(key, value, nova));
    }

    /** 注销 */
    public static void unregister(String key, String value) {
        registry.remove(key + ":" + value);
    }

    /** 按 key 注销所有（如插件卸载时） */
    public static void unregisterAll(String key) {
        registry.entrySet().removeIf(e -> e.getValue().key.equals(key));
    }

    /** 清空 */
    public static void clear() {
        registry.clear();
    }

    // ============ 查找 ============

    /** 精确查找 */
    public static Entry get(String key, String value) {
        return registry.get(key + ":" + value);
    }

    /** 从 "key:value" 格式查找 */
    public static Entry get(String compositeKey) {
        return registry.get(compositeKey);
    }

    /** 列出所有已注册的条目 */
    public static List<Entry> listAll() {
        return new ArrayList<>(registry.values());
    }

    /** 列出指定 key 下的所有条目 */
    public static List<Entry> list(String key) {
        List<Entry> result = new ArrayList<>();
        for (Entry e : registry.values()) {
            if (e.key.equals(key)) result.add(e);
        }
        return result;
    }

    // ============ 补全数据提取 ============

    /** 补全项 */
    public static final class CompletionItem {
        public final String label;
        public final String kind;       // function / variable / extension / constant / namespace
        public final String detail;     // 额外描述（参数签名等）
        public final String scope;      // instance / shared / stdlib

        CompletionItem(String label, String kind, String detail, String scope) {
            this.label = label;
            this.kind = kind;
            this.detail = detail;
            this.scope = scope;
        }
    }

    /**
     * 获取指定上下文的全部补全项。
     * 合并三层：Nova 实例级 + shared() 全局 + StdlibRegistry 内置。
     */
    public static List<CompletionItem> getCompletions(String key, String value, String prefix) {
        Entry entry = get(key, value);
        if (entry == null) return Collections.emptyList();
        return collectCompletions(entry.nova, prefix);
    }

    /** 从 compositeKey 获取补全 */
    public static List<CompletionItem> getCompletions(String compositeKey, String prefix) {
        Entry entry = get(compositeKey);
        if (entry == null) return Collections.emptyList();
        return collectCompletions(entry.nova, prefix);
    }

    private static List<CompletionItem> collectCompletions(Nova nova, String prefix) {
        String lp = prefix != null ? prefix.toLowerCase() : "";
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1. Nova 实例级：从 Interpreter 的 globals 环境获取
        Interpreter interp = nova.getInterpreter();
        if (interp != null) {
            for (String name : interp.getGlobals().getLocalNames()) {
                if (!name.toLowerCase().startsWith(lp) || !seen.add(name)) continue;
                NovaValue val = interp.getGlobals().tryGet(name);
                if (val != null && val.isCallable()) {
                    int arity = (val instanceof NovaCallable) ? ((NovaCallable) val).getArity() : -1;
                    items.add(new CompletionItem(name, "function",
                            arity >= 0 ? "(" + arity + " params)" : "(vararg)", "instance"));
                } else {
                    String typeName = val != null ? val.getTypeName() : "null";
                    items.add(new CompletionItem(name, "variable", typeName, "instance"));
                }
            }
        }

        // 2. shared() 全局注册表
        for (NovaRuntime.RegisteredEntry e : NovaRuntime.shared().listFunctions()) {
            String name = e.getName();
            if (!name.toLowerCase().startsWith(lp) || !seen.add(name)) continue;
            String detail = e.getDescription() != null ? e.getDescription()
                    : e.isFunction() ? "(" + e.getParamTypes().length + " params)" : e.getReturnType().getSimpleName();
            items.add(new CompletionItem(name, e.isFunction() ? "function" : "variable", detail, "shared"));
        }

        // 3. shared() 命名空间
        for (String ns : NovaRuntime.shared().listNamespaces()) {
            if (ns.toLowerCase().startsWith(lp) && seen.add(ns)) {
                items.add(new CompletionItem(ns, "namespace", "namespace", "shared"));
            }
        }

        // 4. StdlibRegistry 内置函数
        for (StdlibRegistry.NativeFunctionInfo nf : StdlibRegistry.getNativeFunctions()) {
            if (!nf.name.toLowerCase().startsWith(lp) || !seen.add(nf.name)) continue;
            items.add(new CompletionItem(nf.name, "function",
                    "(" + (nf.arity >= 0 ? nf.arity + " params" : "vararg") + ")", "stdlib"));
        }

        // 5. StdlibRegistry 常量
        for (StdlibRegistry.ConstantInfo ci : StdlibRegistry.getConstants()) {
            if (!ci.name.toLowerCase().startsWith(lp) || !seen.add(ci.name)) continue;
            items.add(new CompletionItem(ci.name, "constant",
                    ci.value != null ? ci.value.getClass().getSimpleName() : "null", "stdlib"));
        }

        return items;
    }

    /**
     * 获取指定上下文中某类型的扩展方法补全。
     * 用于 LSP 在 {@code obj.} 后提供成员补全。
     *
     * @param compositeKey 上下文标识
     * @param typeName     接收者类型名（如 "String", "List"）
     * @param prefix       方法名前缀
     */
    public static List<CompletionItem> getExtensionCompletions(String compositeKey,
                                                                 String typeName, String prefix) {
        String lp = prefix != null ? prefix.toLowerCase() : "";
        List<CompletionItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // StdlibRegistry 扩展方法
        Map<String, Map<String, List<StdlibRegistry.ExtensionMethodInfo>>> allExt =
                StdlibRegistry.getAllExtensionMethods();
        for (Map.Entry<String, Map<String, List<StdlibRegistry.ExtensionMethodInfo>>> typeEntry : allExt.entrySet()) {
            String targetType = typeEntry.getKey();
            String simpleType = targetType.contains("/")
                    ? targetType.substring(targetType.lastIndexOf('/') + 1) : targetType;
            if (typeName != null && !simpleType.equalsIgnoreCase(typeName)) continue;
            for (Map.Entry<String, List<StdlibRegistry.ExtensionMethodInfo>> methodEntry : typeEntry.getValue().entrySet()) {
                for (StdlibRegistry.ExtensionMethodInfo info : methodEntry.getValue()) {
                    if (!info.name.toLowerCase().startsWith(lp) || !seen.add(simpleType + "." + info.name)) continue;
                    items.add(new CompletionItem(info.name, "extension",
                            simpleType + ".(" + info.arity + " params)", "stdlib"));
                }
            }
        }

        // shared() 扩展
        ExtensionRegistry sharedExt = NovaRuntime.shared().getExtensionRegistry();
        if (sharedExt != null) {
            for (Map.Entry<Class<?>, Map<String, List<ExtensionRegistry.RegisteredExtension>>> typeEntry : sharedExt.getAll().entrySet()) {
                String simpleType = typeEntry.getKey().getSimpleName();
                if (typeName != null && !simpleType.equalsIgnoreCase(typeName)) continue;
                for (Map.Entry<String, List<ExtensionRegistry.RegisteredExtension>> methodEntry : typeEntry.getValue().entrySet()) {
                    for (ExtensionRegistry.RegisteredExtension ext : methodEntry.getValue()) {
                        String name = ext.getMethodName();
                        if (!name.toLowerCase().startsWith(lp) || !seen.add(simpleType + "." + name)) continue;
                        items.add(new CompletionItem(name, "extension",
                                simpleType + ".(" + ext.getParamTypes().length + " params)", "shared"));
                    }
                }
            }
        }

        return items;
    }
}
