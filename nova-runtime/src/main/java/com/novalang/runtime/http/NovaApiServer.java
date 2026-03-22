package com.novalang.runtime.http;

import com.novalang.runtime.ExtensionRegistry;
import com.novalang.runtime.NovaRuntime;
import com.novalang.runtime.stdlib.StdlibRegistry;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.util.*;

/**
 * NovaLang 运行时 HTTP API 服务。
 *
 * <p>为 LSP 补全、调试器、运行时编辑器提供注册表查询接口。
 * 基于 NanoHTTPD 轻量实现，daemon 线程不阻塞 JVM 退出。</p>
 *
 * <h3>启动</h3>
 * <pre>
 * NovaApiServer.startDefault();        // 默认 9621 端口
 * NovaApiServer.startDefault(8080);    // 自定义端口
 * NovaApiServer.stopDefault();         // 停止
 * </pre>
 *
 * <h3>端点</h3>
 * <pre>
 * GET /api/health                      → 健康检查
 * GET /api/functions                   → 全部函数
 * GET /api/functions?ns=pluginA        → 指定命名空间
 * GET /api/variables                   → 全部变量
 * GET /api/extensions                  → 全部扩展函数
 * GET /api/extensions?type=String      → 按目标类型
 * GET /api/namespaces                  → 所有命名空间
 * GET /api/describe?name=getPlayer     → 描述函数/变量
 * GET /api/completions?prefix=get      → 前缀补全
 * </pre>
 */
public class NovaApiServer extends NanoHTTPD {

    private static volatile NovaApiServer defaultInstance;

    public NovaApiServer(int port) {
        super(port);
        setAsyncRunner(new DaemonRunner());
    }

    private static final String GLOBAL_SERVER_KEY = "nova.api.server.instance";
    private static final String GLOBAL_VERSION_KEY = "nova.api.server.version";

    /** 获取当前 NovaLang 版本号（用于比较谁更新） */
    private static int getVersionCode() {
        // 从 Implementation-Version 解析，如 "0.1.8" → 108, "0.2.0" → 200
        try {
            Package pkg = NovaApiServer.class.getPackage();
            String ver = pkg != null ? pkg.getImplementationVersion() : null;
            if (ver != null) {
                String[] parts = ver.split("[.-]");
                int code = 0;
                for (int i = 0; i < Math.min(parts.length, 3); i++) {
                    code = code * 100 + Integer.parseInt(parts[i]);
                }
                return code;
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** 启动默认实例（9621 端口） */
    public static NovaApiServer startDefault() {
        return startDefault(9621);
    }

    /**
     * 启动默认实例（跨 ClassLoader 唯一：JVM 内只运行版本最高的）。
     * 如果已有更低版本的服务在运行，停止旧的，启动新的。
     */
    public static synchronized NovaApiServer startDefault(int port) {
        if (defaultInstance != null && defaultInstance.isAlive()) {
            return defaultInstance;
        }

        int myVersion = getVersionCode();

        synchronized (System.getProperties()) {
            Object existingVersion = System.getProperties().get(GLOBAL_VERSION_KEY);
            Object existingServer = System.getProperties().get(GLOBAL_SERVER_KEY);

            if (existingVersion instanceof Number) {
                int runningVersion = ((Number) existingVersion).intValue();
                if (runningVersion > myVersion) {
                    // 已有更高版本在运行，跳过
                    return null;
                }
                if (runningVersion == myVersion && existingServer != null) {
                    // 同版本已运行，跳过
                    return null;
                }
                // 旧版本在运行 → 尝试停掉（通过反射调用 stop，因为跨 ClassLoader 类型不同）
                if (existingServer != null) {
                    try {
                        existingServer.getClass().getMethod("stop").invoke(existingServer);
                    } catch (Exception ignored) {}
                }
            }

            // 启动新实例
            defaultInstance = new NovaApiServer(port);
            try {
                defaultInstance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
                defaultInstance.warmupClassPathCache();
            } catch (IOException e) {
                throw new RuntimeException("Failed to start Nova API server on port " + port, e);
            }

            System.getProperties().put(GLOBAL_SERVER_KEY, defaultInstance);
            System.getProperties().put(GLOBAL_VERSION_KEY, myVersion);
        }
        return defaultInstance;
    }

    /** 停止默认实例 */
    public static synchronized void stopDefault() {
        if (defaultInstance != null) {
            defaultInstance.stop();
            synchronized (System.getProperties()) {
                // 只清理自己注册的（防止误清别人的）
                if (System.getProperties().get(GLOBAL_SERVER_KEY) == defaultInstance) {
                    System.getProperties().remove(GLOBAL_SERVER_KEY);
                    System.getProperties().remove(GLOBAL_VERSION_KEY);
                }
            }
            defaultInstance = null;
        }
    }

    /** 获取默认实例（可能为 null） */
    public static NovaApiServer getDefault() {
        return defaultInstance;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();

        // CORS
        Response response;
        try {
            response = route(uri, params);
        } catch (Exception e) {
            response = jsonResponse(Response.Status.INTERNAL_ERROR,
                    "{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }

    private Response route(String uri, Map<String, String> params) {
        switch (uri) {
            case "/api/health":       return handleHealth();
            case "/api/functions":    return handleFunctions(params);
            case "/api/variables":    return handleVariables(params);
            case "/api/extensions":   return handleExtensions(params);
            case "/api/namespaces":   return handleNamespaces();
            case "/api/describe":     return handleDescribe(params);
            case "/api/completions":  return handleCompletions(params);
            case "/api/contexts":     return handleContexts(params);
            case "/api/context/completions": return handleContextCompletions(params);
            case "/api/context/extensions":  return handleContextExtensions(params);
            case "/api/members":      return handleMembers(params);
            case "/api/chain-members": return handleChainMembers(params);
            case "/api/java-classes":  return handleJavaClasses(params);
            case "/api/resolve-type":  return handleResolveType(params);
            default:
                return jsonResponse(Response.Status.NOT_FOUND,
                        "{\"error\":\"Not found: " + escapeJson(uri) + "\"}");
        }
    }

    // ============ 端点实现 ============

    private Response handleHealth() {
        return jsonResponse(Response.Status.OK, "{\"status\":\"ok\"}");
    }

    private Response handleFunctions(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        String ns = params.get("ns");

        // 1. shared() 注册的函数
        List<NovaRuntime.RegisteredEntry> entries = ns != null
                ? NovaRuntime.shared().listFunctions(ns)
                : NovaRuntime.shared().listFunctions();
        for (NovaRuntime.RegisteredEntry e : entries) {
            if (!e.isFunction()) continue;
            if (!first) sb.append(',');
            first = false;
            appendFunctionJson(sb, e.getName(), e.getNamespace(), e.getSource(),
                    e.getParamTypes().length, "shared");
        }

        // 2. StdlibRegistry 内置函数（仅全量查询时）
        if (ns == null) {
            for (StdlibRegistry.NativeFunctionInfo nf : StdlibRegistry.getNativeFunctions()) {
                if (!first) sb.append(',');
                first = false;
                appendFunctionJson(sb, nf.name, null, null, nf.arity, "stdlib");
            }
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    private Response handleVariables(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        String ns = params.get("ns");

        // shared() 注册的变量
        List<NovaRuntime.RegisteredEntry> entries = ns != null
                ? NovaRuntime.shared().listFunctions(ns)
                : NovaRuntime.shared().listFunctions();
        for (NovaRuntime.RegisteredEntry e : entries) {
            if (e.isFunction()) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"name\":\"").append(escapeJson(e.getName())).append('"');
            if (e.getNamespace() != null) {
                sb.append(",\"namespace\":\"").append(escapeJson(e.getNamespace())).append('"');
            }
            Object val = e.getValue();
            sb.append(",\"type\":\"").append(val != null ? val.getClass().getSimpleName() : "null").append('"');
            sb.append(",\"scope\":\"shared\"");
            sb.append('}');
        }

        // StdlibRegistry 常量
        if (ns == null) {
            for (StdlibRegistry.ConstantInfo ci : StdlibRegistry.getConstants()) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(escapeJson(ci.name)).append('"');
                sb.append(",\"type\":\"").append(ci.value != null ? ci.value.getClass().getSimpleName() : "null").append('"');
                sb.append(",\"scope\":\"stdlib\"");
                sb.append('}');
            }
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    private Response handleExtensions(Map<String, String> params) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        String typeFilter = params.get("type");

        // StdlibRegistry 扩展方法
        Map<String, Map<String, List<StdlibRegistry.ExtensionMethodInfo>>> allExt =
                StdlibRegistry.getAllExtensionMethods();
        for (Map.Entry<String, Map<String, List<StdlibRegistry.ExtensionMethodInfo>>> typeEntry : allExt.entrySet()) {
            String targetType = typeEntry.getKey();
            String simpleType = targetType.contains("/")
                    ? targetType.substring(targetType.lastIndexOf('/') + 1) : targetType;
            if (typeFilter != null && !simpleType.equalsIgnoreCase(typeFilter)
                    && !targetType.equalsIgnoreCase(typeFilter)) {
                continue;
            }
            for (Map.Entry<String, List<StdlibRegistry.ExtensionMethodInfo>> methodEntry : typeEntry.getValue().entrySet()) {
                for (StdlibRegistry.ExtensionMethodInfo info : methodEntry.getValue()) {
                    if (!first) sb.append(',');
                    first = false;
                    sb.append("{\"targetType\":\"").append(escapeJson(simpleType)).append('"');
                    sb.append(",\"name\":\"").append(escapeJson(info.name)).append('"');
                    sb.append(",\"arity\":").append(info.arity);
                    sb.append(",\"scope\":\"stdlib\"");
                    sb.append('}');
                }
            }
        }

        // shared() ExtensionRegistry
        ExtensionRegistry sharedExt = NovaRuntime.shared().getExtensionRegistry();
        if (sharedExt != null) {
            for (Map.Entry<Class<?>, Map<String, List<ExtensionRegistry.RegisteredExtension>>> typeEntry : sharedExt.getAll().entrySet()) {
                String simpleType = typeEntry.getKey().getSimpleName();
                if (typeFilter != null && !simpleType.equalsIgnoreCase(typeFilter)) continue;
                for (Map.Entry<String, List<ExtensionRegistry.RegisteredExtension>> methodEntry : typeEntry.getValue().entrySet()) {
                    for (ExtensionRegistry.RegisteredExtension ext : methodEntry.getValue()) {
                        if (!first) sb.append(',');
                        first = false;
                        sb.append("{\"targetType\":\"").append(escapeJson(simpleType)).append('"');
                        sb.append(",\"name\":\"").append(escapeJson(ext.getMethodName())).append('"');
                        sb.append(",\"arity\":").append(ext.getParamTypes().length);
                        sb.append(",\"scope\":\"shared\"");
                        sb.append('}');
                    }
                }
            }
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    private Response handleNamespaces() {
        List<String> namespaces = NovaRuntime.shared().listNamespaces();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < namespaces.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(namespaces.get(i))).append('"');
        }
        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    private Response handleDescribe(Map<String, String> params) {
        String name = params.get("name");
        if (name == null || name.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Missing 'name' parameter\"}");
        }

        // shared() 查找
        NovaRuntime.RegisteredEntry entry = NovaRuntime.shared().lookupQualified(name);
        if (entry != null) {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"name\":\"").append(escapeJson(entry.getName())).append('"');
            if (entry.getNamespace() != null) {
                sb.append(",\"namespace\":\"").append(escapeJson(entry.getNamespace())).append('"');
            }
            if (entry.getSource() != null) {
                sb.append(",\"source\":\"").append(escapeJson(entry.getSource())).append('"');
            }
            sb.append(",\"isFunction\":").append(entry.isFunction());
            sb.append(",\"qualifiedName\":\"").append(escapeJson(entry.getQualifiedName())).append('"');
            if (entry.getDescription() != null) {
                sb.append(",\"description\":\"").append(escapeJson(entry.getDescription())).append('"');
            }
            if (entry.isFunction()) {
                sb.append(",\"arity\":").append(entry.getParamTypes().length);
            } else {
                Object val = entry.getValue();
                sb.append(",\"valueType\":\"").append(val != null ? val.getClass().getSimpleName() : "null").append('"');
            }
            sb.append(",\"scope\":\"shared\"");
            sb.append('}');
            return jsonResponse(Response.Status.OK, sb.toString());
        }

        // StdlibRegistry 查找
        com.novalang.runtime.stdlib.StdlibFunction sf = StdlibRegistry.get(name);
        if (sf != null) {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"name\":\"").append(escapeJson(sf.name)).append('"');
            sb.append(",\"arity\":").append(sf.arity);
            sb.append(",\"scope\":\"stdlib\"");
            if (sf instanceof StdlibRegistry.NativeFunctionInfo) {
                sb.append(",\"isFunction\":true");
            } else if (sf instanceof StdlibRegistry.ConstantInfo) {
                sb.append(",\"isFunction\":false");
            }
            sb.append('}');
            return jsonResponse(Response.Status.OK, sb.toString());
        }

        return jsonResponse(Response.Status.NOT_FOUND,
                "{\"error\":\"Not found: " + escapeJson(name) + "\"}");
    }

    private Response handleCompletions(Map<String, String> params) {
        String prefix = params.get("prefix");
        if (prefix == null) prefix = "";
        String lowerPrefix = prefix.toLowerCase();

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        Set<String> seen = new HashSet<>();

        // shared() 函数 + 变量（运行时注册，优先于 stdlib）
        for (NovaRuntime.RegisteredEntry e : NovaRuntime.shared().listFunctions()) {
            if (e.getName().toLowerCase().startsWith(lowerPrefix) && seen.add(e.getName())) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"label\":\"").append(escapeJson(e.getName())).append('"');
                sb.append(",\"kind\":\"").append(e.isFunction() ? "function" : "variable").append('"');
                sb.append(",\"scope\":\"shared\"");
                if (e.getNamespace() != null) {
                    sb.append(",\"namespace\":\"").append(escapeJson(e.getNamespace())).append('"');
                }
                if (e.getDescription() != null) {
                    sb.append(",\"detail\":\"").append(escapeJson(e.getDescription())).append('"');
                } else if (e.getSource() != null) {
                    sb.append(",\"detail\":\"").append(escapeJson(e.getSource())).append('"');
                }
                sb.append('}');
            }
        }

        // StdlibRegistry 函数（跳过已被 shared() 覆盖的同名函数）
        for (StdlibRegistry.NativeFunctionInfo nf : StdlibRegistry.getNativeFunctions()) {
            if (nf.name.toLowerCase().startsWith(lowerPrefix) && !seen.contains(nf.name) && seen.add(nf.name)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"label\":\"").append(escapeJson(nf.name)).append('"');
                sb.append(",\"kind\":\"function\",\"scope\":\"stdlib\"");
                sb.append('}');
            }
        }

        // StdlibRegistry 常量
        for (StdlibRegistry.ConstantInfo ci : StdlibRegistry.getConstants()) {
            if (ci.name.toLowerCase().startsWith(lowerPrefix) && seen.add(ci.name)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"label\":\"").append(escapeJson(ci.name)).append('"');
                sb.append(",\"kind\":\"constant\",\"scope\":\"stdlib\"");
                sb.append('}');
            }
        }

        // 命名空间名
        for (String ns : NovaRuntime.shared().listNamespaces()) {
            if (ns.toLowerCase().startsWith(lowerPrefix) && seen.add(ns)) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"label\":\"").append(escapeJson(ns)).append('"');
                sb.append(",\"kind\":\"namespace\"");
                sb.append('}');
            }
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    // ============ Context 端点 ============

    /** GET /api/contexts — 列出所有已注册的 Nova 上下文 */
    private Response handleContexts(Map<String, String> params) {
        String key = params.get("key");
        List<NovaContextRegistry.Entry> entries = key != null
                ? NovaContextRegistry.list(key)
                : NovaContextRegistry.listAll();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(',');
            NovaContextRegistry.Entry e = entries.get(i);
            sb.append("{\"key\":\"").append(escapeJson(e.getKey())).append('"');
            sb.append(",\"value\":\"").append(escapeJson(e.getValue())).append('"');
            sb.append(",\"compositeKey\":\"").append(escapeJson(e.getCompositeKey())).append('"');
            sb.append('}');
        }
        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    /**
     * GET /api/context/completions?ctx=plugin:myPlugin&prefix=get
     * — 获取指定上下文的补全项（合并实例级 + shared + stdlib）
     */
    private Response handleContextCompletions(Map<String, String> params) {
        String ctx = params.get("ctx");
        if (ctx == null || ctx.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Missing 'ctx' parameter (format: key:value)\"}");
        }
        String prefix = params.get("prefix");
        List<NovaContextRegistry.CompletionItem> items = NovaContextRegistry.getCompletions(ctx, prefix != null ? prefix : "");
        return completionItemsToJson(items);
    }

    /**
     * GET /api/context/extensions?ctx=plugin:myPlugin&type=String&prefix=
     * — 获取指定上下文中某类型的扩展方法补全
     */
    private Response handleContextExtensions(Map<String, String> params) {
        String ctx = params.get("ctx");
        if (ctx == null || ctx.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Missing 'ctx' parameter\"}");
        }
        String typeName = params.get("type");
        String prefix = params.get("prefix");
        List<NovaContextRegistry.CompletionItem> items =
                NovaContextRegistry.getExtensionCompletions(ctx, typeName, prefix != null ? prefix : "");
        return completionItemsToJson(items);
    }

    private Response completionItemsToJson(List<NovaContextRegistry.CompletionItem> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            NovaContextRegistry.CompletionItem item = items.get(i);
            sb.append("{\"label\":\"").append(escapeJson(item.label)).append('"');
            sb.append(",\"kind\":\"").append(escapeJson(item.kind)).append('"');
            if (item.detail != null) {
                sb.append(",\"detail\":\"").append(escapeJson(item.detail)).append('"');
            }
            sb.append(",\"scope\":\"").append(escapeJson(item.scope)).append('"');
            sb.append('}');
        }
        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    // ============ 成员反射 ============

    /**
     * GET /api/members?name=Bukkit&prefix=get
     * 根据 shared() 中注册的变量名，反射其 Java 类型的 public 成员。
     * 也支持 type 参数直接指定类名：/api/members?type=org.bukkit.Server&prefix=get
     */
    private Response handleMembers(Map<String, String> params) {
        String prefix = params.get("prefix");
        if (prefix == null) prefix = "";
        String lowerPrefix = prefix.toLowerCase();

        Class<?> targetClass = null;

        // 方式1：按变量名从 shared() 查找实际对象类型
        String name = params.get("name");
        if (name != null) {
            NovaRuntime.RegisteredEntry entry = NovaRuntime.shared().lookup(name);
            if (entry != null) {
                Object val = entry.getValue();
                if (val instanceof Class) {
                    // 注册的是 Class 对象（如 Material.class）→ 反射静态成员
                    targetClass = (Class<?>) val;
                } else if (val != null) {
                    targetClass = val.getClass();
                    // 优先使用接口类型（如 CraftServer → Server）
                    for (Class<?> iface : targetClass.getInterfaces()) {
                        if (iface.getName().startsWith("org.bukkit")) {
                            targetClass = iface;
                            break;
                        }
                    }
                }
            }
            // 命名空间代理 → 返回命名空间内的函数列表
            if (targetClass == null) {
                NovaRuntime.NovaNamespace ns = NovaRuntime.shared().getNamespaceProxy(name);
                if (ns != null) {
                    return handleNamespaceMembers(ns.getNamespaceName(), lowerPrefix);
                }
            }
        }

        // 方式2：直接指定类名
        String typeName = params.get("type");
        if (targetClass == null && typeName != null) {
            try { targetClass = Class.forName(typeName); } catch (ClassNotFoundException ignored) {}
        }

        if (targetClass == null) {
            return jsonResponse(Response.Status.NOT_FOUND,
                    "{\"error\":\"Cannot resolve type for: " + escapeJson(name != null ? name : typeName) + "\"}");
        }

        // 判断是否反射静态成员（注册的是 Class 对象）
        NovaRuntime.RegisteredEntry entry2 = name != null ? NovaRuntime.shared().lookup(name) : null;
        boolean staticOnly = entry2 != null && entry2.getValue() instanceof Class;

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        Set<String> seen = new HashSet<>();

        // 方法
        for (java.lang.reflect.Method m : targetClass.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
            if (staticOnly && !isStatic) continue;
            if (!staticOnly && isStatic) continue;

            String mName = m.getName();
            String key = mName + ":" + m.getParameterCount();
            if (!mName.toLowerCase().startsWith(lowerPrefix) || !seen.add(key)) continue;

            if (!first) sb.append(',');
            first = false;
            sb.append("{\"label\":\"").append(escapeJson(mName)).append('"');
            sb.append(",\"kind\":\"method\"");

            // 参数签名
            StringBuilder sig = new StringBuilder(mName).append('(');
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(pts[i].getSimpleName());
            }
            sig.append(") → ").append(m.getReturnType().getSimpleName());
            sb.append(",\"detail\":\"").append(escapeJson(sig.toString())).append('"');
            sb.append('}');
        }

        // 字段
        for (java.lang.reflect.Field f : targetClass.getFields()) {
            boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
            if (staticOnly && !isStatic) continue;
            if (!staticOnly && isStatic) continue;

            String fName = f.getName();
            if (!fName.toLowerCase().startsWith(lowerPrefix) || !seen.add(fName)) continue;

            if (!first) sb.append(',');
            first = false;
            sb.append("{\"label\":\"").append(escapeJson(fName)).append('"');
            sb.append(",\"kind\":\"field\"");
            sb.append(",\"detail\":\"").append(escapeJson(f.getType().getSimpleName())).append('"');
            sb.append('}');
        }

        // 枚举常量（静态模式）
        if (staticOnly && targetClass.isEnum()) {
            for (Object ec : targetClass.getEnumConstants()) {
                String eName = ((Enum<?>) ec).name();
                if (!eName.toLowerCase().startsWith(lowerPrefix) || !seen.add(eName)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"label\":\"").append(escapeJson(eName)).append('"');
                sb.append(",\"kind\":\"enumMember\"");
                sb.append(",\"detail\":\"").append(escapeJson(targetClass.getSimpleName())).append('"');
                sb.append('}');
            }
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    /** 命名空间的成员列表 */
    private Response handleNamespaceMembers(String namespace, String lowerPrefix) {
        List<NovaRuntime.RegisteredEntry> entries = NovaRuntime.shared().listFunctions(namespace);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (NovaRuntime.RegisteredEntry e : entries) {
            if (!e.getName().toLowerCase().startsWith(lowerPrefix)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"label\":\"").append(escapeJson(e.getName())).append('"');
            sb.append(",\"kind\":\"").append(e.isFunction() ? "method" : "field").append('"');
            if (e.getDescription() != null) {
                sb.append(",\"detail\":\"").append(escapeJson(e.getDescription())).append('"');
            }
            sb.append('}');
        }
        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    /**
     * GET /api/chain-members?chain=Bukkit.spigot()&prefix=get
     * 链式类型推导：从根变量开始，逐步解析每个成员调用的返回类型，
     * 最终反射最后一个类型的成员。
     * chain 格式：root.method1().method2().field  （最后一段是要补全的前缀，不参与解析）
     */
    private Response handleChainMembers(Map<String, String> params) {
        String chain = params.get("chain");
        String prefix = params.get("prefix");
        if (chain == null || chain.isEmpty()) {
            return jsonResponse(Response.Status.BAD_REQUEST, "{\"error\":\"Missing 'chain' parameter\"}");
        }
        if (prefix == null) prefix = "";

        // 解析链：Bukkit.spigot().getPlayer("x") → ["Bukkit", "spigot()", "getPlayer()"]
        // 去掉参数内容，只保留方法名和是否是调用
        List<String> segments = parseChain(chain);
        if (segments.isEmpty()) {
            return jsonResponse(Response.Status.NOT_FOUND, "{\"error\":\"Empty chain\"}");
        }

        // 从根变量开始解析类型
        String root = segments.get(0);
        Class<?> currentType = resolveRootType(root);
        if (currentType == null) {
            return jsonResponse(Response.Status.NOT_FOUND,
                    "{\"error\":\"Cannot resolve root: " + escapeJson(root) + "\"}");
        }

        boolean isStatic = false;
        NovaRuntime.RegisteredEntry rootEntry = NovaRuntime.shared().lookup(root);
        if (rootEntry != null && rootEntry.getValue() instanceof Class) {
            isStatic = true;
        }

        // 逐段解析返回类型
        for (int i = 1; i < segments.size(); i++) {
            String seg = segments.get(i);
            boolean isCall = seg.endsWith("()");
            String memberName = isCall ? seg.substring(0, seg.length() - 2) : seg;

            Class<?> nextType = null;

            if (isCall) {
                // 查找方法返回类型
                for (java.lang.reflect.Method m : currentType.getMethods()) {
                    if (m.getName().equals(memberName)) {
                        boolean mStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                        if ((isStatic && mStatic) || (!isStatic && !mStatic)) {
                            nextType = m.getReturnType();
                            break;
                        }
                    }
                }
            } else {
                // 查找字段类型
                try {
                    java.lang.reflect.Field f = currentType.getField(memberName);
                    nextType = f.getType();
                } catch (NoSuchFieldException ignored) {
                    // 尝试 getter
                    String getter = "get" + memberName.substring(0, 1).toUpperCase() + memberName.substring(1);
                    for (java.lang.reflect.Method m : currentType.getMethods()) {
                        if (m.getName().equals(getter) && m.getParameterCount() == 0) {
                            nextType = m.getReturnType();
                            break;
                        }
                    }
                }
            }

            if (nextType == null) {
                return jsonResponse(Response.Status.NOT_FOUND,
                        "{\"error\":\"Cannot resolve member '" + escapeJson(memberName) + "' on " + currentType.getName() + "\"}");
            }

            currentType = nextType;
            isStatic = false; // 方法调用后回到实例成员
        }

        // 用解析出的类型调用 handleMembers
        return membersOfClass(currentType, isStatic, prefix.toLowerCase());
    }

    /** 解析调用链字符串为段列表 */
    private List<String> parseChain(String chain) {
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int parenDepth = 0;

        for (int i = 0; i < chain.length(); i++) {
            char c = chain.charAt(i);
            if (c == '(') {
                parenDepth++;
                if (parenDepth == 1) {
                    // 标记为方法调用
                    current.append("()");
                }
            } else if (c == ')') {
                parenDepth--;
            } else if (c == '.' && parenDepth == 0) {
                if (current.length() > 0) {
                    segments.add(current.toString());
                    current.setLength(0);
                }
            } else if (parenDepth == 0) {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            segments.add(current.toString());
        }
        return segments;
    }

    /** 从根变量名解析类型 */
    private Class<?> resolveRootType(String name) {
        NovaRuntime.RegisteredEntry entry = NovaRuntime.shared().lookup(name);
        if (entry != null) {
            Object val = entry.getValue();
            if (val instanceof Class) return (Class<?>) val;
            if (val != null) {
                Class<?> cls = val.getClass();
                for (Class<?> iface : cls.getInterfaces()) {
                    if (iface.getName().startsWith("org.bukkit")) return iface;
                }
                return cls;
            }
        }
        return null;
    }

    /** 对给定类型反射成员，返回 JSON */
    private Response membersOfClass(Class<?> targetClass, boolean staticOnly, String lowerPrefix) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        Set<String> seen = new HashSet<>();

        for (java.lang.reflect.Method m : targetClass.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
            if (staticOnly && !isStatic) continue;
            if (!staticOnly && isStatic) continue;

            String mName = m.getName();
            String key = mName + ":" + m.getParameterCount();
            if (!mName.toLowerCase().startsWith(lowerPrefix) || !seen.add(key)) continue;

            if (!first) sb.append(',');
            first = false;
            sb.append("{\"label\":\"").append(escapeJson(mName)).append('"');
            sb.append(",\"kind\":\"method\"");
            StringBuilder sig = new StringBuilder(mName).append('(');
            Class<?>[] pts = m.getParameterTypes();
            for (int i = 0; i < pts.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(pts[i].getSimpleName());
            }
            sig.append(") → ").append(m.getReturnType().getSimpleName());
            sb.append(",\"detail\":\"").append(escapeJson(sig.toString())).append('"');
            sb.append('}');
        }

        for (java.lang.reflect.Field f : targetClass.getFields()) {
            boolean isStatic = java.lang.reflect.Modifier.isStatic(f.getModifiers());
            if (staticOnly && !isStatic) continue;
            if (!staticOnly && isStatic) continue;
            String fName = f.getName();
            if (!fName.toLowerCase().startsWith(lowerPrefix) || !seen.add(fName)) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"label\":\"").append(escapeJson(fName)).append('"');
            sb.append(",\"kind\":\"field\"");
            sb.append(",\"detail\":\"").append(escapeJson(f.getType().getSimpleName())).append('"');
            sb.append('}');
        }

        if (staticOnly && targetClass.isEnum()) {
            for (Object ec : targetClass.getEnumConstants()) {
                String eName = ((Enum<?>) ec).name();
                if (!eName.toLowerCase().startsWith(lowerPrefix) || !seen.add(eName)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"label\":\"").append(escapeJson(eName)).append('"');
                sb.append(",\"kind\":\"enumMember\"");
                sb.append(",\"detail\":\"").append(escapeJson(targetClass.getSimpleName())).append('"');
                sb.append('}');
            }
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    /** 后台预热 ClassPath 缓存 */
    private void warmupClassPathCache() {
        Thread t = new Thread(() -> {
            try {
                classNameCache = scanClassPath();
            } catch (Exception ignored) {}
        }, "nova-classpath-scan");
        t.setDaemon(true);
        t.start();
    }

    private static final java.util.regex.Pattern JAVA_TYPE_PATTERN =
            java.util.regex.Pattern.compile("^(?:Java\\.type|javaClass)\\s*\\(\\s*\"([^\"]+)\"\\s*\\)(.*)$");

    /** ClassPath 扫描缓存（首次请求时构建） */
    private volatile List<String> classNameCache;

    /**
     * GET /api/java-classes?prefix=org.bukkit.&limit=50
     * 扫描 ClassPath 上所有 JAR/目录，返回匹配前缀的类名。
     */
    private Response handleJavaClasses(Map<String, String> params) {
        String prefix = params.get("prefix");
        if (prefix == null) prefix = "";
        int limit = 100;
        try { if (params.get("limit") != null) limit = Integer.parseInt(params.get("limit")); }
        catch (NumberFormatException ignored) {}

        // 缓存未就绪时返回空（后台 warmup 正在扫描）
        if (classNameCache == null) {
            return jsonResponse(Response.Status.OK, "[]");
        }

        String lp = prefix.toLowerCase();
        int prefixLen = prefix.length();

        // 按层级收集：prefix="org.bukkit." → 下一段是包名或类名
        // 用 TreeSet 去重并排序
        Set<String> segments = new java.util.TreeSet<>();

        for (String className : classNameCache) {
            if (!className.toLowerCase().startsWith(lp)) continue;
            // 截取前缀之后的部分
            String rest = className.substring(prefixLen);
            int dot = rest.indexOf('.');
            if (dot >= 0) {
                // 还有下级包 → 返回包名（如 "bukkit"）
                segments.add(rest.substring(0, dot));
            } else {
                // 这是最终的类名（如 "Material"）
                segments.add(rest);
            }
            if (segments.size() >= limit * 2) break; // 粗过滤防止扫描过多
        }

        StringBuilder sb = new StringBuilder("[");
        int count = 0;
        for (String seg : segments) {
            if (count >= limit) break;
            // 判断是包名还是类名：拼接完整名后看是否有更多子项
            String full = prefix + seg;
            boolean isPackage = false;
            for (String cn : classNameCache) {
                if (cn.startsWith(full + ".")) { isPackage = true; break; }
            }

            if (count > 0) sb.append(',');
            if (isPackage) {
                // 包名：补全后自动加点
                sb.append("{\"label\":\"").append(escapeJson(seg)).append('"');
                sb.append(",\"kind\":\"namespace\"");
                sb.append(",\"detail\":\"").append(escapeJson(full)).append('"');
                sb.append('}');
            } else {
                // 类名：补全完整限定名
                sb.append("{\"label\":\"").append(escapeJson(seg)).append('"');
                sb.append(",\"kind\":\"class\"");
                sb.append(",\"detail\":\"").append(escapeJson(full)).append('"');
                sb.append('}');
            }
            count++;
        }

        sb.append(']');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    /**
     * GET /api/resolve-type?chain=Java.type("org.bukkit.Material").getCategories()
     * 解析链式表达式的最终返回类型名。
     * 也支持 ?type=org.bukkit.Material&method=getCategories 直接查询。
     */
    private Response handleResolveType(Map<String, String> params) {
        String chain = params.get("chain");
        String typeName = params.get("type");
        String methodName = params.get("method");

        Class<?> resultType = null;

        if (chain != null && !chain.isEmpty()) {
            // 解析 Java.type("xxx").method() 形式
            java.util.regex.Matcher jtm = JAVA_TYPE_PATTERN.matcher(chain.trim());
            if (jtm.matches()) {
                String className = jtm.group(1);
                String rest = jtm.group(2).trim();
                try {
                    Class<?> cls = Class.forName(className);
                    if (rest.isEmpty()) {
                        resultType = cls;
                    } else {
                        // 逐段解析
                        resultType = resolveChainType(cls, true, rest);
                    }
                } catch (ClassNotFoundException ignored) {}
            } else {
                // 普通链：从 shared() 根变量开始
                List<String> segments = parseChain(chain);
                if (!segments.isEmpty()) {
                    Class<?> current = resolveRootType(segments.get(0));
                    boolean isStatic = false;
                    NovaRuntime.RegisteredEntry re = NovaRuntime.shared().lookup(segments.get(0));
                    if (re != null && re.getValue() instanceof Class) isStatic = true;
                    if (current != null) {
                        for (int i = 1; i < segments.size(); i++) {
                            String seg = segments.get(i);
                            boolean isCall = seg.endsWith("()");
                            String name = isCall ? seg.substring(0, seg.length() - 2) : seg;
                            current = resolveNextType(current, name, isCall, isStatic);
                            if (current == null) break;
                            isStatic = false;
                        }
                        resultType = current;
                    }
                }
            }
        } else if (typeName != null && methodName != null) {
            try {
                Class<?> cls = Class.forName(typeName);
                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (m.getName().equals(methodName)) {
                        resultType = m.getReturnType();
                        break;
                    }
                }
            } catch (ClassNotFoundException ignored) {}
        }

        if (resultType == null) {
            return jsonResponse(Response.Status.NOT_FOUND, "{\"error\":\"Cannot resolve type\"}");
        }
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"type\":\"").append(escapeJson(resultType.getName())).append('"');
        sb.append(",\"simpleName\":\"").append(escapeJson(resultType.getSimpleName())).append('"');
        sb.append(",\"isEnum\":").append(resultType.isEnum());
        sb.append(",\"isInterface\":").append(resultType.isInterface());
        sb.append('}');
        return jsonResponse(Response.Status.OK, sb.toString());
    }

    /** 从点号分隔的剩余链解析最终类型 */
    private Class<?> resolveChainType(Class<?> start, boolean isStatic, String restChain) {
        if (restChain.startsWith(".")) restChain = restChain.substring(1);
        if (restChain.isEmpty()) return start;
        List<String> segments = parseChain(restChain);
        Class<?> current = start;
        for (String seg : segments) {
            boolean isCall = seg.endsWith("()");
            String name = isCall ? seg.substring(0, seg.length() - 2) : seg;
            current = resolveNextType(current, name, isCall, isStatic);
            if (current == null) return null;
            isStatic = false;
        }
        return current;
    }

    /** 解析单步成员的返回类型 */
    private Class<?> resolveNextType(Class<?> cls, String memberName, boolean isCall, boolean isStatic) {
        if (isCall) {
            for (java.lang.reflect.Method m : cls.getMethods()) {
                if (m.getName().equals(memberName)) {
                    boolean mStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    if ((isStatic && mStatic) || (!isStatic && !mStatic)) {
                        return m.getReturnType();
                    }
                }
            }
        } else {
            try {
                return cls.getField(memberName).getType();
            } catch (NoSuchFieldException ignored) {
                String getter = "get" + memberName.substring(0, 1).toUpperCase() + memberName.substring(1);
                for (java.lang.reflect.Method m : cls.getMethods()) {
                    if (m.getName().equals(getter) && m.getParameterCount() == 0) return m.getReturnType();
                }
            }
        }
        return null;
    }

    /** 扫描当前 ClassPath 上所有 JAR 和目录，收集 .class 文件对应的类名 */
    private static List<String> scanClassPath() {
        Set<String> names = new java.util.TreeSet<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = NovaApiServer.class.getClassLoader();

        // 收集所有 URL（含 URLClassLoader 的 URL 和 java.class.path）
        Set<String> paths = new java.util.LinkedHashSet<>();
        // java.class.path
        String cp = System.getProperty("java.class.path", "");
        for (String p : cp.split(java.io.File.pathSeparator)) {
            if (!p.isEmpty()) paths.add(p);
        }
        // URLClassLoader
        collectUrls(cl, paths);

        // Bukkit 插件：扫描 plugins/ 目录下所有 JAR（PluginClassLoader 不一定是 URLClassLoader）
        java.io.File pluginsDir = new java.io.File("plugins");
        if (pluginsDir.isDirectory()) {
            java.io.File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
            if (jars != null) {
                for (java.io.File jar : jars) paths.add(jar.getAbsolutePath());
            }
        }
        // 服务端核心 JAR（工作目录下的 *.jar）
        java.io.File cwd = new java.io.File(".");
        java.io.File[] rootJars = cwd.listFiles((dir, name) -> name.endsWith(".jar"));
        if (rootJars != null) {
            for (java.io.File jar : rootJars) paths.add(jar.getAbsolutePath());
        }

        for (String path : paths) {
            java.io.File file = new java.io.File(path);
            if (!file.exists()) continue;
            if (file.isDirectory()) {
                scanDirectory(file, file, names);
            } else if (path.endsWith(".jar")) {
                scanJar(file, names);
            }
        }
        return new ArrayList<>(names);
    }

    private static void collectUrls(ClassLoader cl, Set<String> paths) {
        if (cl == null) return;
        if (cl instanceof java.net.URLClassLoader) {
            for (java.net.URL url : ((java.net.URLClassLoader) cl).getURLs()) {
                if ("file".equals(url.getProtocol())) {
                    try { paths.add(new java.io.File(url.toURI()).getAbsolutePath()); }
                    catch (Exception ignored) {}
                }
            }
        }
        collectUrls(cl.getParent(), paths);
    }

    private static void scanJar(java.io.File jarFile, Set<String> names) {
        try (java.util.jar.JarFile jar = new java.util.jar.JarFile(jarFile)) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (entry.endsWith(".class") && !entry.contains("$") && !entry.startsWith("META-INF")) {
                    String className = entry.substring(0, entry.length() - 6).replace('/', '.');
                    if (isPublicClassName(className)) {
                        names.add(className);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static void scanDirectory(java.io.File root, java.io.File dir, Set<String> names) {
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                scanDirectory(root, f, names);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
                String relative = root.toPath().relativize(f.toPath()).toString();
                String className = relative.substring(0, relative.length() - 6)
                        .replace(java.io.File.separatorChar, '.');
                if (isPublicClassName(className)) {
                    names.add(className);
                }
            }
        }
    }

    /** 过滤明显的内部类/私有实现（小写开头的最后一段通常是包名不是类名） */
    private static boolean isPublicClassName(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot < 0) return false;
        char firstChar = className.charAt(lastDot + 1);
        return Character.isUpperCase(firstChar);
    }

    // ============ 辅助 ============

    private static void appendFunctionJson(StringBuilder sb, String name, String namespace,
                                            String source, int arity, String scope) {
        sb.append("{\"name\":\"").append(escapeJson(name)).append('"');
        if (namespace != null) {
            sb.append(",\"namespace\":\"").append(escapeJson(namespace)).append('"');
        }
        if (source != null) {
            sb.append(",\"source\":\"").append(escapeJson(source)).append('"');
        }
        sb.append(",\"arity\":").append(arity);
        sb.append(",\"scope\":\"").append(scope).append('"');
        sb.append('}');
    }

    private Response jsonResponse(Response.Status status, String json) {
        return newFixedLengthResponse(status, "application/json", json);
    }

    private static String escapeJson(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    // 转义控制字符 (U+0000 ~ U+001F)
                    if (c < 0x20) {
                        sb.append("\\u");
                        sb.append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    /** Daemon 线程运行器，不阻塞 JVM 退出 */
    private static class DaemonRunner implements AsyncRunner {
        private final List<ClientHandler> running = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void closeAll() {
            for (ClientHandler h : new ArrayList<>(running)) {
                h.close();
            }
        }

        @Override
        public void closed(ClientHandler clientHandler) {
            running.remove(clientHandler);
        }

        @Override
        public void exec(ClientHandler clientHandler) {
            Thread t = new Thread(clientHandler, "nova-api-" + running.size());
            t.setDaemon(true);
            t.start();
            running.add(clientHandler);
        }
    }
}
