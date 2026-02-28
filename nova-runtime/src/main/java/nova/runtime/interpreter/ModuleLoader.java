package nova.runtime.interpreter;

import nova.runtime.types.Environment;
import com.novalang.compiler.lexer.Lexer;
import com.novalang.compiler.parser.Parser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Nova 模块加载器
 *
 * <p>负责解析模块路径、加载 .nova 文件、缓存已加载模块。</p>
 * <p>循环依赖采用 Java 式处理：先注册空环境到缓存再填充。</p>
 */
public final class ModuleLoader {
    private final Path basePath;
    private final Map<Path, Environment> moduleCache = new HashMap<>();
    private final Map<Path, Long> moduleTimestamps = new HashMap<>();

    public ModuleLoader(Path basePath) {
        this.basePath = basePath;
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
