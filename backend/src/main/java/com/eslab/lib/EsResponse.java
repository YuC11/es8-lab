package com.eslab.lib;

import com.fasterxml.jackson.databind.JsonNode;

/** 一次 ES HTTP 调用的结果：原始响应体、HTTP 状态、客户端往返耗时 */
public record EsResponse(JsonNode body, int status, double ms) {
}
