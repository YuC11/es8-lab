package com.eslab.lib;

import com.fasterxml.jackson.databind.JsonNode;

/** ES 返回非 2xx 时抛出，保留状态码与原始 payload，便于页面展示「ES 说了什么」 */
public class EsException extends RuntimeException {
    private final int status;
    private final String path;
    private final transient JsonNode payload;

    public EsException(int status, String path, JsonNode payload, String message) {
        super(message);
        this.status = status;
        this.path = path;
        this.payload = payload;
    }

    public int status() {
        return status;
    }

    public String path() {
        return path;
    }

    public JsonNode payload() {
        return payload;
    }
}
