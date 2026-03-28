package com.novalang.runtime.interpreter.stdlib;

import com.novalang.runtime.*;
import com.novalang.runtime.interpreter.NovaNativeFunction;
import com.novalang.runtime.interpreter.NovaRuntimeException;

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

    // ============ HttpRequest builder（编译模式对齐） ============

    /**
     * 创建 HTTP 请求 builder。返回 NovaMap，支持链式调用。
     * <pre>
     * val req = HttpRequest("https://api.example.com")
     *     .method("POST")
     *     .header("Authorization", "Bearer xxx")
     *     .body("{\"key\": \"value\"}")
     *     .timeout(5000)
     * val resp = req.send()
     * </pre>
     */
    public static Object HttpRequest(Object url) {
        NovaMap builder = new NovaMap();
        builder.put(NovaString.of("url"), NovaString.of(str(url)));
        builder.put(NovaString.of("_method"), NovaString.of("GET"));
        builder.put(NovaString.of("_headers"), new NovaMap());
        builder.put(NovaString.of("_body"), NovaNull.NULL);
        builder.put(NovaString.of("_timeout"), NovaInt.of(30000));

        // method(m) → 返回 builder 自身
        builder.put(NovaString.of("method"), NovaNativeFunction.create("method", m -> {
            builder.put(NovaString.of("_method"), NovaString.of(str(m.toJavaValue())));
            return builder;
        }));

        // header(name, value) → 返回 builder 自身
        builder.put(NovaString.of("header"), new NovaNativeFunction("header", 2, (ctx, args) -> {
            NovaMap headers = (NovaMap) builder.get(NovaString.of("_headers"));
            headers.put(NovaString.of(str(args.get(0).toJavaValue())), NovaString.of(str(args.get(1).toJavaValue())));
            return builder;
        }));

        // headers(map) → 合并 headers，返回 builder
        builder.put(NovaString.of("headers"), NovaNativeFunction.create("headers", h -> {
            NovaMap headers = (NovaMap) builder.get(NovaString.of("_headers"));
            if (h instanceof NovaMap) {
                for (Map.Entry<NovaValue, NovaValue> entry : ((NovaMap) h).getEntries().entrySet()) {
                    headers.put(entry.getKey(), entry.getValue());
                }
            }
            return builder;
        }));

        // body(content) → 返回 builder
        builder.put(NovaString.of("body"), NovaNativeFunction.create("body", b -> {
            builder.put(NovaString.of("_body"), NovaString.of(str(b.toJavaValue())));
            return builder;
        }));

        // timeout(ms) → 返回 builder
        builder.put(NovaString.of("timeout"), NovaNativeFunction.create("timeout", t -> {
            builder.put(NovaString.of("_timeout"), NovaInt.of(((Number) t.toJavaValue()).intValue()));
            return builder;
        }));

        // send() → 执行请求
        builder.put(NovaString.of("send"), NovaNativeFunction.create("send", () -> {
            String reqUrl = ((NovaString) builder.get(NovaString.of("url"))).asString();
            String reqMethod = ((NovaString) builder.get(NovaString.of("_method"))).asString();
            NovaValue bodyVal = builder.get(NovaString.of("_body"));
            String reqBody = (bodyVal == null || bodyVal.isNull()) ? null : bodyVal.asString();
            int timeout = ((NovaInt) builder.get(NovaString.of("_timeout"))).asInt();
            NovaMap reqHeaders = (NovaMap) builder.get(NovaString.of("_headers"));

            return doRequestFull(reqMethod, reqUrl, reqBody, timeout, reqHeaders);
        }));

        return builder;
    }

    private static NovaMap doRequestFull(String method, String urlStr, String body, int timeout, NovaMap headers) {
        try {
            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);

            // 设置自定义 headers
            if (headers != null) {
                for (Map.Entry<NovaValue, NovaValue> entry : headers.getEntries().entrySet()) {
                    conn.setRequestProperty(entry.getKey().asString(), entry.getValue().asString());
                }
            }

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

    private static String str(Object o) {
        if (o instanceof String) return (String) o;
        return String.valueOf(o);
    }
}
