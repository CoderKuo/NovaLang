package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.interpreter.NovaRuntimeException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * nova.system 模块的编译模式运行时实现。
 *
 * <p>编译模式下无安全策略检查。
 * 解释器版的常量（osName/jvmVersion 等）在编译版中改为无参函数。</p>
 */
public final class StdlibSystemCompiled {

    private StdlibSystemCompiled() {}

    public static Object env(Object name) {
        return System.getenv(str(name));
    }

    public static Object envOrDefault(Object name, Object defaultVal) {
        String val = System.getenv(str(name));
        return val != null ? val : defaultVal;
    }

    public static Object allEnv() {
        return new LinkedHashMap<>(System.getenv());
    }

    public static Object sysProperty(Object name) {
        return System.getProperty(str(name));
    }

    public static Object exec(Object cmd) {
        try {
            String cmdStr = str(cmd);
            Process proc = new ProcessBuilder(cmdStr.split("\\s+")).start();
            String stdout = readStream(proc.getInputStream());
            String stderr = readStream(proc.getErrorStream());
            int exitCode = proc.waitFor();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exitCode", exitCode);
            result.put("stdout", stdout);
            result.put("stderr", stderr);
            return result;
        } catch (Exception e) {
            throw new NovaRuntimeException("exec failed: " + e.getMessage());
        }
    }

    public static Object exit(Object code) {
        int c = code instanceof Number ? ((Number) code).intValue() : Integer.parseInt(str(code));
        System.exit(c);
        return null;
    }

    public static Object osName() {
        return System.getProperty("os.name", "unknown");
    }

    public static Object jvmVersion() {
        return System.getProperty("java.version", "unknown");
    }

    public static Object novaVersion() {
        return "1.0.0";
    }

    public static Object availableProcessors() {
        return Runtime.getRuntime().availableProcessors();
    }

    public static Object totalMemory() {
        return Runtime.getRuntime().totalMemory();
    }

    public static Object freeMemory() {
        return Runtime.getRuntime().freeMemory();
    }

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return String.valueOf(o);
    }

    private static String readStream(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) {
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
