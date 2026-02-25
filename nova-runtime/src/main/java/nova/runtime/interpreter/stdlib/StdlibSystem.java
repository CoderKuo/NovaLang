package nova.runtime.interpreter.stdlib;
import nova.runtime.*;

import nova.runtime.interpreter.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * nova.system — 系统操作
 */
public final class StdlibSystem {

    private StdlibSystem() {}

    public static void register(Environment env, Interpreter interp) {
        // env(name) → 环境变量
        env.defineVal("env", NovaNativeFunction.create("env", (name) -> {
            String val = System.getenv(name.asString());
            return val != null ? NovaString.of(val) : NovaNull.NULL;
        }));

        // envOrDefault(name, default) → 环境变量（带默认值）
        env.defineVal("envOrDefault", NovaNativeFunction.create("envOrDefault", (name, defaultVal) -> {
            String val = System.getenv(name.asString());
            return val != null ? NovaString.of(val) : defaultVal;
        }));

        // allEnv() → 所有环境变量
        env.defineVal("allEnv", NovaNativeFunction.create("allEnv", () -> {
            NovaMap result = new NovaMap();
            for (Map.Entry<String, String> e : System.getenv().entrySet()) {
                result.put(NovaString.of(e.getKey()), NovaString.of(e.getValue()));
            }
            return result;
        }));

        // sysProperty(name) → 系统属性
        env.defineVal("sysProperty", NovaNativeFunction.create("sysProperty", (name) -> {
            String val = System.getProperty(name.asString());
            return val != null ? NovaString.of(val) : NovaNull.NULL;
        }));

        // args → 命令行参数列表
        env.defineVal("args", NovaNativeFunction.create("args", () -> {
            String[] cliArgs = interp.getCliArgs();
            NovaList result = new NovaList();
            if (cliArgs != null) {
                for (String arg : cliArgs) result.add(NovaString.of(arg));
            }
            return result;
        }));

        // exec(command...) → ProcessResult {exitCode, stdout, stderr}
        env.defineVal("exec", new NovaNativeFunction("exec", -1, (interpreter, args) -> {
            if (!interp.getSecurityPolicy().isProcessExecAllowed()) {
                throw NovaSecurityPolicy.denied("process execution is not allowed");
            }
            String[] cmd;
            if (args.size() == 1 && args.get(0) instanceof NovaList) {
                NovaList list = (NovaList) args.get(0);
                cmd = new String[list.size()];
                for (int i = 0; i < list.size(); i++) cmd[i] = list.get(i).asString();
            } else {
                cmd = new String[args.size()];
                for (int i = 0; i < args.size(); i++) cmd[i] = args.get(i).asString();
            }
            try {
                ProcessBuilder pb = new ProcessBuilder(cmd);
                Process proc = pb.start();
                String stdout = readStream(proc.getInputStream());
                String stderr = readStream(proc.getErrorStream());
                int exitCode = proc.waitFor();

                NovaMap result = new NovaMap();
                result.put(NovaString.of("exitCode"), NovaInt.of(exitCode));
                result.put(NovaString.of("stdout"), NovaString.of(stdout));
                result.put(NovaString.of("stderr"), NovaString.of(stderr));
                return result;
            } catch (Exception e) {
                throw new NovaRuntimeException("exec failed: " + e.getMessage());
            }
        }));

        // exit(code)
        env.defineVal("exit", NovaNativeFunction.create("exit", (code) -> {
            if (!interp.getSecurityPolicy().isProcessExecAllowed()) {
                throw NovaSecurityPolicy.denied("System.exit is not allowed");
            }
            System.exit(code.asInt());
            return NovaNull.UNIT;
        }));

        // osName
        env.defineVal("osName", NovaString.of(System.getProperty("os.name", "unknown")));
        // jvmVersion
        env.defineVal("jvmVersion", NovaString.of(System.getProperty("java.version", "unknown")));
        // novaVersion
        env.defineVal("novaVersion", NovaString.of("1.0.0"));
        // availableProcessors
        env.defineVal("availableProcessors", NovaInt.of(Runtime.getRuntime().availableProcessors()));

        // totalMemory()
        env.defineVal("totalMemory", NovaNativeFunction.create("totalMemory", () ->
            NovaLong.of(Runtime.getRuntime().totalMemory())));

        // freeMemory()
        env.defineVal("freeMemory", NovaNativeFunction.create("freeMemory", () ->
            NovaLong.of(Runtime.getRuntime().freeMemory())));
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
