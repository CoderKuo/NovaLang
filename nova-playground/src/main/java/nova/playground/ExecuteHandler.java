package nova.playground;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import nova.runtime.Nova;
import nova.runtime.interpreter.NovaSecurityPolicy;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 处理 POST /api/execute 请求。
 *
 * <p>每次请求创建独立的 Nova 实例，使用 strict 沙箱执行，
 * 捕获 stdout/stderr 输出并截断后返回 JSON 结果。</p>
 */
public class ExecuteHandler implements Handler {

    private static final Logger log = LoggerFactory.getLogger(ExecuteHandler.class);

    /** 代码最大长度（字符） */
    private static final int MAX_CODE_LENGTH = 10_000;

    /** stdout 输出最大捕获量（字节） */
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;

    @Override
    public void handle(@NotNull Context ctx) {
        ExecuteRequest req = ctx.bodyAsClass(ExecuteRequest.class);

        // 校验
        if (req.getCode() == null || req.getCode().trim().isEmpty()) {
            ctx.status(400).json(ExecuteResponse.fail("", "Code is required", 0));
            return;
        }
        if (req.getCode().length() > MAX_CODE_LENGTH) {
            ctx.status(400).json(ExecuteResponse.fail("",
                    "Code too long (max " + MAX_CODE_LENGTH + " chars)", 0));
            return;
        }

        // 准备输出捕获
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream capture = new PrintStream(baos, true);

        // 创建沙箱 Nova 实例
        NovaSecurityPolicy policy = NovaSecurityPolicy.strict();
        Nova nova = new Nova(policy);
        nova.setStdout(capture);
        nova.setStderr(capture);

        // 执行
        long start = System.currentTimeMillis();
        try {
            Object result = nova.eval(req.getCode());
            long elapsed = System.currentTimeMillis() - start;
            String output = truncateOutput(baos);
            String resultStr = result == null ? "null" : String.valueOf(result);
            ctx.json(ExecuteResponse.ok(output, resultStr, elapsed));
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            String output = truncateOutput(baos);
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Code execution failed ({}ms): {}", elapsed, errorMsg, e);
            ctx.json(ExecuteResponse.fail(output, errorMsg, elapsed));
        }
    }

    private String truncateOutput(ByteArrayOutputStream baos) {
        byte[] bytes = baos.toByteArray();
        if (bytes.length <= MAX_OUTPUT_BYTES) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return new String(bytes, 0, MAX_OUTPUT_BYTES, StandardCharsets.UTF_8)
                + "\n... (output truncated, " + bytes.length + " bytes total)";
    }
}
