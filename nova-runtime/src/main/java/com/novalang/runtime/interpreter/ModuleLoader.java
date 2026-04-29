package com.novalang.runtime.interpreter;

import com.novalang.runtime.types.Environment;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nova 模块加载器
 *
 * <p>负责解析模块路径、加载 .nova 文件、缓存已加载模块。</p>
 * <p>循环依赖采用 Java 式处理：先注册空环境到缓存再填充。</p>
 */
public final class ModuleLoader {
    /** 模块缓存上限，防止无限增长 */
    private static final int MAX_CACHE_SIZE = 256;

    private final Path basePath;
    private final Map<Path, Environment> moduleCache = new HashMap<>();
    private final Map<Path, Long> moduleTimestamps = new HashMap<>();
    private final Map<String, String> virtualModules = new HashMap<>();
    private static final Pattern STRING_IMPORT = Pattern.compile(
            "^\\s*import\\s+\"((?:\\\\.|[^\"\\\\])*)\"\\s*(?:(?:;\\s*(.*))|(?://.*)?)$");

    public ModuleLoader(Path basePath) {
        this.basePath = basePath;
    }

    /** 清空所有已缓存的模块 */
    public void clear() {
        moduleCache.clear();
        moduleTimestamps.clear();
    }

    public void registerVirtualModule(String moduleId, String source) {
        if (moduleId == null || source == null) {
            return;
        }
        virtualModules.put(moduleId, source);
    }

    public void copyVirtualModulesFrom(ModuleLoader other) {
        if (other != null) {
            virtualModules.putAll(other.virtualModules);
        }
    }

    public boolean hasVirtualModule(String moduleId) {
        return virtualModules.containsKey(moduleId);
    }

    public Environment loadVirtualModule(String moduleId, Interpreter interpreter) {
        String source = virtualModules.get(moduleId);
        if (source == null) {
            return null;
        }
        Environment moduleEnv = new Environment(interpreter.getGlobals());
        interpreter.executeModule(source, moduleId, moduleEnv);
        return moduleEnv;
    }

    public String expandVirtualImports(String source, String fileName) {
        if (source == null || virtualModules.isEmpty()) {
            return source;
        }
        return expandVirtualImports(source, fileName, new HashSet<String>(), new HashSet<String>());
    }

    private String expandVirtualImports(String source, String fileName,
                                        Set<String> loading, Set<String> included) {
        StringBuilder out = new StringBuilder(source.length());
        String[] lines = source.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher matcher = STRING_IMPORT.matcher(line);
            if (matcher.matches()) {
                String moduleId = unescapeModuleId(matcher.group(1));
                String moduleSource = virtualModules.get(moduleId);
                if (moduleSource == null) {
                    throw new NovaRuntimeException("Cannot resolve module import '" + moduleId + "'");
                }
                if (loading.contains(moduleId)) {
                    throw new NovaRuntimeException("Cyclic module import: " + moduleId);
                }
                if (included.add(moduleId)) {
                    loading.add(moduleId);
                    out.append("// import: ").append(moduleId).append('\n');
                    out.append(expandVirtualImports(moduleSource, moduleId, loading, included));
                    if (!moduleSource.endsWith("\n")) {
                        out.append('\n');
                    }
                    loading.remove(moduleId);
                }
                String trailing = matcher.group(2);
                if (trailing != null && !trailing.trim().isEmpty()
                        && !trailing.trim().startsWith("//")) {
                    out.append(trailing);
                    if (i < lines.length - 1) {
                        out.append('\n');
                    }
                }
            } else {
                out.append(line);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String unescapeModuleId(String text) {
        return text.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    /** 使指定模块缓存失效 */
    public void invalidate(Path modulePath) {
        Path absolute = modulePath.toAbsolutePath().normalize();
        moduleCache.remove(absolute);
        moduleTimestamps.remove(absolute);
    }

    /**
     * 根据 import parts 解析 .nova 文件路径。
     * <pre>
     * import a.b.C → pathParts=[a, b] → a/b.nova
     * import a.b.* → pathParts=[a, b] → a/b.nova
     * </pre>
     */
    public Path resolveModulePath(List<String> pathParts) {
        String relativePath = String.join(File.separator, pathParts) + ".nova";
        Path resolved = basePath.resolve(relativePath).normalize();
        return Files.exists(resolved) ? resolved : null;
    }

    /**
     * 加载模块，返回模块的环境（包含所有顶层定义）。
     * 使用 Java 式循环依赖处理：先注册空环境再填充。
     */
    public Environment loadModule(Path modulePath, Interpreter interpreter) {
        Path absolute = modulePath.toAbsolutePath().normalize();

        // 缓存命中：检查文件是否已修改
        if (moduleCache.containsKey(absolute)) {
            long cachedTime = moduleTimestamps.getOrDefault(absolute, 0L);
            try {
                long currentTime = Files.getLastModifiedTime(absolute).toMillis();
                if (currentTime <= cachedTime) {
                    return moduleCache.get(absolute);
                }
                // 文件已修改，移除旧缓存，重新加载
                moduleCache.remove(absolute);
                moduleTimestamps.remove(absolute);
            } catch (IOException e) {
                // 无法读取修改时间，使用缓存
                return moduleCache.get(absolute);
            }
        }

        try {
            String source = new String(Files.readAllBytes(absolute), StandardCharsets.UTF_8);
            // 先注册空环境到缓存（允许循环引用拿到同一个环境引用）
            Environment moduleEnv = new Environment(interpreter.getGlobals());
            // 缓存上限保护：超限时清空旧缓存
            if (moduleCache.size() >= MAX_CACHE_SIZE) {
                moduleCache.clear();
                moduleTimestamps.clear();
            }
            moduleCache.put(absolute, moduleEnv);
            // 记录文件修改时间
            moduleTimestamps.put(absolute, Files.getLastModifiedTime(absolute).toMillis());
            // 再执行模块，填充环境
            interpreter.executeModule(source, absolute.toString(), moduleEnv);
            return moduleEnv;
        } catch (IOException e) {
            moduleCache.remove(absolute);
            moduleTimestamps.remove(absolute);
            throw new NovaRuntimeException("Cannot read module: " + absolute);
        } catch (RuntimeException | Error e) {
            // 执行失败：移除半成品环境，防止后续 import 拿到不完整模块
            moduleCache.remove(absolute);
            moduleTimestamps.remove(absolute);
            throw e;
        }
    }
}
