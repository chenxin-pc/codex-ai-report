package com.example.aimilvusweb.service.retrieval;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Description: 召回结果值对象，保存 Milvus 文档、相关性分数、子切片文本和聚合后的生成上下文。
 * @Logic: 推荐生成和证据护栏读取该对象，分别用于前端展示、Prompt 拼接和输出前质量判断。
 * @Param: document 原始召回文档；score 相关性分数；chunkText 命中子切片文本；evidenceText 聚合后的生成上下文；contextType 上下文来源类型；hitChildren 命中 CHILD 明细；hitCount 命中数量；maxScore 最高分；averageScore 平均分；truncated 是否截断；diagnosticOnly 是否仅诊断。
 * @Return: 无（record 仅承载最终召回证据）。
 * @author: cx
 * @Date: 2026-06-03 00:00:00
 */
public record RetrievedChunk(
        /** Milvus 返回的原始 Document，包含正文和 metadata。 */
        Document document,
        /** 召回相关性分数，可能来自 score、distance 转换或 Document score。 */
        Double score,
        /** 命中的 CHILD 子切片文本，作为前端主展示文本和引用定位。 */
        String chunkText,
        /** 扩展后的证据文本，优先为 PARENT 聚合上下文，用于模型和护栏判断。 */
        String evidenceText,
        /** 生成上下文来源类型。 */
        EvidenceContextType contextType,
        /** 当前 PARENT 证据组内实际命中的 CHILD 明细。 */
        List<RetrievedChild> hitChildren,
        /** 当前证据组命中的 CHILD 数量。 */
        int hitCount,
        /** 当前证据组内最高相关性分数。 */
        Double maxScore,
        /** 当前证据组内平均相关性分数。 */
        Double averageScore,
        /** 当前证据上下文是否被截断或退化。 */
        boolean truncated,
        /** 是否仅用于诊断而非有效推荐证据。 */
        boolean diagnosticOnly
) {
    /**
     * @Description: 构建仅包含单个 CHILD 兜底上下文的召回结果。
     * @Logic: 将命中 CHILD 同时作为展示文本和证据文本，分数缺失时用 0 填充聚合诊断字段。
     * @Param: document 原始召回文档；score 相关性分数；chunkText 命中 CHILD 文本；evidenceText 证据文本。
     * @Return: 单 CHILD 召回结果。
     * @author: cx
     * @Date: 2026-06-03 00:00:00
     */
    public RetrievedChunk(Document document, Double score, String chunkText, String evidenceText) {
        this(document, score, chunkText, evidenceText, EvidenceContextType.CHILD_FALLBACK,
                List.of(new RetrievedChild(document, score, chunkText, 0)), 1,
                score == null ? 0D : score, score == null ? 0D : score, false, false);
    }
}
