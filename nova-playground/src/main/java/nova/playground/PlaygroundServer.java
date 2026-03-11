package nova.playground;

import io.javalin.Javalin;

import java.util.Collections;

/**
 * NovaLang Playground HTTP 服务入口。
 *
 * <p>启动方式：</p>
 * <ul>
 *   <li>命令行参数：{@code java -jar nova-playground.jar 8080}</li>
 *   <li>环境变量：{@code PORT=3000 java -jar nova-playground.jar}</li>
 *   <li>默认端口：8080</li>
 * </ul>
 */
public class PlaygroundServer {

    private static final int DEFAULT_PORT = 8090;

    private final Javalin app;

    public PlaygroundServer(int port) {
        this.app = Javalin.create(config -> {
            config.enableCorsForAllOrigins();
        });

        app.post("/api/execute", new ExecuteHandler());
        app.get("/api/health", ctx -> ctx.json(Collections.singletonMap("status", "ok")));

        app.start(port);
    }

    public int port() {
        return app.port();
    }

    public void stop() {
        app.stop();
    }

    public static void main(String[] args) {
        int port = resolvePort(args);
        new PlaygroundServer(port);
        System.out.println("Nova Playground started on http://localhost:" + port);
    }

    private static int resolvePort(String[] args) {
        // 优先级：命令行参数 > 环境变量 > 默认值
        if (args.length > 0) {
            try {
                return Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0] + ", using default " + DEFAULT_PORT);
            }
        }
        String envPort = System.getenv("PORT");
        if (envPort != null) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_PORT;
    }
}
