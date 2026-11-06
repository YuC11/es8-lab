package com.eslab.seed;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** 一条商品文档，字段与 v2 的显式 mapping 一一对应 */
public record ProductDoc(
        String sku,
        String title,
        String description,
        String category,
        String brand,
        double price,
        int stock,
        int status,
        List<String> tags,
        @JsonProperty("created_at") String createdAt,
        double rating,
        int sales,
        @JsonProperty("seller_id") String sellerId,
        Specs specs) {

    public record Specs(
            String color,
            String material,
            @JsonProperty("weight_g") int weightG) {
    }
}
