package nova.runtime.interpreter.stdlib;

import nova.runtime.*;
import nova.runtime.interpreter.NovaNativeFunction;
import nova.runtime.interpreter.NovaRuntimeException;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * nova.http 模块的编译模式运行时实现。
 *
 * <p>返回 NovaMap 响应对象，成员通过 NovaDynamic 分派。
 * 编译模式下无安全策略检查。</p>
 */
public final class StdlibHttpCompiled {

    private StdlibHttpCompiled() {}

    public static Object httpGet(Object url) {
        return doRequest("GET", str(url), null);
    }

    public static Object httpPost(Object url, Object body) {
        return doRequest("POST", str(url), str(body));
    }

    public static Object httpPut(Object url, Object body) {
        return doRequest("PUT", str(url), str(body));
    }

    public static Object httpDelete(Object url) {
        return doRequest("DELETE", str(url), null);
    }

    private static NovaMap doRequest(String method, String urlStr, String body) {
        try {
            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            if (body != null) {
                conn.setDoOutput(true);
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                }
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = conn.getResponseCode();
            String responseBody;
            try (InputStream is = statusCode < 400 ? conn.getInputStream() : conn.getErrorStream()) {
                responseBody = is != null ? readStream(is) : "";
            }

            NovaMap respHeaders = new NovaMap();
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                if (entry.getKey() != null) {
                    respHeaders.put(NovaString.of(entry.getKey()),
                            NovaString.of(String.join(", ", entry.getValue())));
                }
            }

            NovaMap response = new NovaMap();
            response.put(NovaString.of("statusCode"), NovaInt.of(statusCode));
            response.put(NovaString.of("body"), NovaString.of(responseBody));
            response.put(NovaString.of("headers"), respHeaders);
            response.put(NovaString.of("isOk"), NovaBoolean.of(statusCode >= 200 && statusCode < 300));

            final String finalBody = responseBody;
            response.put(NovaString.of("json"), NovaNativeFunction.create("json", () -> {
                try {
                    return new StdlibJson.JsonParser(finalBody).parse();
                } catch (Exception e) {
                    throw new NovaRuntimeException("Failed to parse response as JSON: " + e.getMessage());
                }
            }));

            conn.disconnect();
            return response;
        } catch (IOException e) {
            throw new NovaRuntimeException("HTTP request failed: " + e.getMessage());
        }
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

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return String.valueOf(o);
    }
}
