package nova.runtime.interpreter.stdlib;
import nova.runtime.*;

import nova.runtime.interpreter.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * nova.http — HTTP 客户端（使用 HttpURLConnection，兼容 Java 8+）
 */
public final class StdlibHttp {

    private StdlibHttp() {}

    private static void checkNetwork(Interpreter interp) {
        if (!interp.getSecurityPolicy().isNetworkAllowed()) {
            throw NovaSecurityPolicy.denied("network access is not allowed");
        }
    }

    public static void register(Environment env, Interpreter interp) {
        // httpGet(url) → HttpResponse
        env.defineVal("httpGet", NovaNativeFunction.create("httpGet", (urlVal) -> {
            checkNetwork(interp);
            return doRequest("GET", urlVal.asString(), null, null);
        }));

        // httpPost(url, body) → HttpResponse
        env.defineVal("httpPost", NovaNativeFunction.create("httpPost", (urlVal, body) -> {
            checkNetwork(interp);
            return doRequest("POST", urlVal.asString(), body.asString(), null);
        }));

        // httpPut(url, body) → HttpResponse
        env.defineVal("httpPut", NovaNativeFunction.create("httpPut", (urlVal, body) -> {
            checkNetwork(interp);
            return doRequest("PUT", urlVal.asString(), body.asString(), null);
        }));

        // httpDelete(url) → HttpResponse
        env.defineVal("httpDelete", NovaNativeFunction.create("httpDelete", (urlVal) -> {
            checkNetwork(interp);
            return doRequest("DELETE", urlVal.asString(), null, null);
        }));

        // HttpRequest builder
        env.defineVal("HttpRequest", NovaNativeFunction.create("HttpRequest", (urlVal) -> {
            NovaMap req = new NovaMap();
            NovaMap headerStore = new NovaMap();
            req.put(NovaString.of("url"), NovaString.of(urlVal.asString()));
            req.put(NovaString.of("_headers"), headerStore);

            // method(m) → 设置 HTTP 方法
            req.put(NovaString.of("method"), NovaNativeFunction.create("method", (m) -> {
                req.put(NovaString.of("_method"), NovaString.of(m.asString()));
                return req;
            }));

            // header(name, value) → 添加请求头
            req.put(NovaString.of("header"), NovaNativeFunction.create("header", (name, value) -> {
                headerStore.put(NovaString.of(name.asString()), NovaString.of(value.asString()));
                return req;
            }));

            // headers(map) → 批量设置请求头
            req.put(NovaString.of("headers"), NovaNativeFunction.create("headers", (map) -> {
                NovaMap input = (NovaMap) map;
                for (Map.Entry<NovaValue, NovaValue> e : input.getEntries().entrySet()) {
                    headerStore.put(NovaString.of(e.getKey().asString()), NovaString.of(e.getValue().asString()));
                }
                return req;
            }));

            // body(content) → 设置请求体
            req.put(NovaString.of("body"), NovaNativeFunction.create("body", (content) -> {
                req.put(NovaString.of("_body"), NovaString.of(content.asString()));
                return req;
            }));

            // timeout(millis) → 设置超时
            req.put(NovaString.of("timeout"), NovaNativeFunction.create("timeout", (millis) -> {
                req.put(NovaString.of("_timeout"), NovaInt.of(millis.asInt()));
                return req;
            }));

            // send() → 发送请求
            req.put(NovaString.of("send"), NovaNativeFunction.create("send", () -> {
                checkNetwork(interp);
                String url = req.get(NovaString.of("url")).asString();
                NovaValue methodVal = req.get(NovaString.of("_method"));
                String method = methodVal.isNull() ? "GET" : methodVal.asString();
                NovaValue bodyVal = req.get(NovaString.of("_body"));
                String body = bodyVal.isNull() ? null : bodyVal.asString();
                return doRequest(method, url, body, headerStore);
            }));

            return req;
        }));
    }

    private static NovaMap doRequest(String method, String urlStr, String body, NovaMap headers) {
        try {
            URI uri = URI.create(urlStr);
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(30000);

            if (headers != null) {
                for (Map.Entry<NovaValue, NovaValue> e : headers.getEntries().entrySet()) {
                    conn.setRequestProperty(e.getKey().asString(), e.getValue().asString());
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

            // json() 方法 — 解析 body 为 JSON
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
}
