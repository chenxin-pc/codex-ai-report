package com.example.aimilvusweb.service.retrieval;

import org.springframework.ai.vectorstore.filter.Filter;

/**
 * @Description: hybrid 检索请求对象，承载 query、TopK 和 metadata filter。
 * @Logic: 生产 Milvus 原生实现读取 milvusFilter；旧测试适配器读取 legacyFilterExpression 转换为 Spring SearchRequest。
 * @Param: query 用户问题；topK 初始召回数量；milvusFilter Milvus 表达式；legacyFilterExpression Spring AI 兼容表达式。
 * @Return: 无（仅数据载体）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
public record ReportHybridSearchRequest(
        /** 用户投研问题，同时用于 dense embedding 和 BM25 text search。 */
        String query,
        /** 初始召回数量。 */
        int topK,
        /** Milvus 原生 filter 表达式。 */
        String milvusFilter,
        /** 兼容旧单元测试的 Spring AI filter 表达式。 */
        Filter.Expression legacyFilterExpression
) {
}
