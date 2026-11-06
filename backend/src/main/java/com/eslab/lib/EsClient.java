package com.eslab.lib;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.apache.http.HttpHost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ES HTTP 客户端封装（基于官方 co.elastic.clients:elasticsearch-java 的低层 RestClient）
 *
 * 设计：
 * 1) 统一使用官方 elasticsearch-java 依赖（版本与服务端 8.18.5 一致）。
 *    这里刻意用它的低层 RestClient 而不是高层强类型 API ——
 *    学习项目里「请求长什么样」比「SDK 怎么包装」更重要，
 *    每关的实现代码展示出来就是一次真实的 method + path + JSON body，
 *    读代码即可对应到 curl。后续要演进成强类型 API，只需替换本类。
 * 2) 对外 API 是「一次调用返回一个 JsonNode」：20 关、4 实验、seed 全都只依赖这一层，
 *    4xx/5xx 一律转成项目统一的 EsException（RestClient 默认抛 ResponseException，这里做适配）。
 */
@Component
public class EsClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    /** 超时档位 → RestClient。RestClient 的超时只能建在 RequestConfig 上，所以按档位各建一个 */
    private final Map<Integer, RestClient> pool = new ConcurrentHashMap<>();

    public EsClient(ObjectMapper mapper,
                    @Value("${eslab.es-url:http://127.0.0.1:9200}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private RestClient restFor(int timeoutMs) {
        int t = Math.max(1_000, timeoutMs);
        // 官方 RestClient：自带连接池。不读系统代理环境变量，localhost 直连，
        // 规避本机曾出现的代理劫持 127.0.0.1 问题。
        return pool.computeIfAbsent(t, k -> RestClient.builder(HttpHost.create(baseUrl))
                .setRequestConfigCallback(rc -> rc
                        .setConnectTimeout(5_000)
                        .setSocketTimeout(k)
                        .setConnectionRequestTimeout(2_000))
                .build());
    }

    public String baseUrl() {
        return baseUrl;
    }

    // ── 便捷方法：只要结果，异常时抛 EsException ──────────────

    public JsonNode get(String path) {
        return call("GET", path, null, null, 60_000).body();
    }

    public JsonNode post(String path, Object body) {
        return call("POST", path, body, null, 60_000).body();
    }

    public JsonNode put(String path, Object body) {
        return call("PUT", path, body, null, 60_000).body();
    }

    public JsonNode delete(String path) {
        return call("DELETE", path, null, null, 60_000).body();
    }

    /** _bulk 用：body 是 NDJSON 文本，不能用 JSON 序列化 */
    public JsonNode bulk(String ndjson) {
        return bulk(ndjson, 120_000);
    }

    public JsonNode bulk(String ndjson, int timeoutMs) {
        return call("POST", "/_bulk", null, ndjson, timeoutMs).body();
    }

    public JsonNode post(String path, Object body, int timeoutMs) {
        return call("POST", path, body, null, timeoutMs).body();
    }

    // ── 核心 ────────────────────────────────────────────────

    public EsResponse call(String method, String path, Object body) {
        return call(method, path, body, null, 60_000);
    }

    public EsResponse call(String method, String path, Object body, String ndjson, int timeoutMs) {
        String p = path.startsWith("/") ? path : "/" + path;
        Request req = new Request(method, p);

        if (ndjson != null) {
            String payload = ndjson.endsWith("\n") ? ndjson : ndjson + "\n";
            req.setEntity(new StringEntity(payload,
                    ContentType.create("application/x-ndjson", StandardCharsets.UTF_8)));
        } else if (body != null) {
            String payload;
            try {
                payload = mapper.writeValueAsString(body);
            } catch (IOException e) {
                throw new EsException(0, path, null, "请求体序列化失败：" + e.getMessage());
            }
            req.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        }

        long started = System.nanoTime();
        Response res;
        try {
            res = restFor(timeoutMs).performRequest(req);
        } catch (ResponseException e) {
            // >=400：RestClient 抛 ResponseException，这里统一转成 EsException
            Response r = e.getResponse();
            int status = r.getStatusLine().getStatusCode();
            JsonNode node = parse(readEntityQuietly(r));
            throw new EsException(status, path, node, describe(status, path, node));
        } catch (IOException e) {
            throw new EsException(0, path, null,
                    "无法连接 ES（" + baseUrl + "）：" + e.getMessage() + "。请确认 Elasticsearch 已启动。");
        }
        double ms = (System.nanoTime() - started) / 1_000_000.0;

        int status = res.getStatusLine().getStatusCode();
        JsonNode node = parse(readEntityQuietly(res));
        if (status >= 400) {
            throw new EsException(status, path, node, describe(status, path, node));
        }
        return new EsResponse(node, status, ms);
    }

    private String readEntityQuietly(Response res) {
        if (res == null || res.getEntity() == null) return null; // HEAD/204 等无实体响应
        try {
            return EntityUtils.toString(res.getEntity());
        } catch (IOException e) {
            return null;
        }
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) return NullNode.instance;
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            return mapper.getNodeFactory().textNode(text);
        }
    }

    /** 把 ES 的错误 payload 翻译成一句人能读的话 */
    private String describe(int status, String path, JsonNode node) {
        String reason = "";
        if (node != null && node.has("error")) {
            JsonNode err = node.get("error");
            if (err.isTextual()) {
                reason = err.asText();
            } else if (err.has("root_cause") && err.get("root_cause").isArray()
                    && !err.get("root_cause").isEmpty()
                    && err.get("root_cause").get(0).hasNonNull("reason")) {
                // root_cause 比顶层的 "all shards failed" 具体得多，优先用它
                reason = err.get("root_cause").get(0).get("reason").asText();
            } else if (err.hasNonNull("reason")) {
                reason = err.get("reason").asText();
            }
        }
        return "ES " + status + " " + path + " → " + (reason.isBlank() ? "请求失败" : reason);
    }
}
