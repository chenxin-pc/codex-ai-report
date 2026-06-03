package com.example.aimilvusweb.service.retrieval;

import org.springframework.ai.document.Document;

/**
 * @Description: PARENT 聚合组内命中的 CHILD 明细。
 * @Logic: 保留实际召回命中的 CHILD 文本、分数和原始顺序，供引用、展示和诊断使用。
 * @Param: document 命中 CHILD 的 Milvus 文档；score 相关性分数；chunkText CHILD 文本；originalRank 原始召回顺序。
 * @Return: 无（record 仅承载召回命中明细）。
 * @author: cx
 * @Date: 2026-06-03 00:00:00
 */
public record RetrievedChild(
        /** 命中 CHILD 的 Milvus 文档，包含正文和 metadata。 */
        Document document,
        /** 召回相关性分数，可能为空。 */
        Double score,
        /** 命中的 CHILD 子切片文本。 */
        String chunkText,
        /** 当前 CHILD 在原始召回结果中的顺序。 */
        int originalRank
) {
}
