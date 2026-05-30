package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

/**
 * @Description: Milvus 文档到召回候选的映射组件。
 * @Logic: 遍历向量库返回的 Document，标准化相关性分数并保留命中 CHILD 文本，输出后续过滤、去重和策略处理所需的 RetrievedChunk。
 * @Param: 无。
 * @Return: 无（Spring 管理或手动装配的无状态组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievalCandidateMapper {

    /**
     * @Description: 将 Milvus 返回文档映射为召回候选。
     * @Logic: 每个 Document 解析统一相关性分数，正文为空时兜底为空字符串，候选阶段 evidenceText 暂时等于命中 CHILD 文本。
     * @Param: documents Milvus ANN 返回的文档列表。
     * @Return: 召回候选列表；输入为空时返回空列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public List<RetrievedChunk> map(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> candidates = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Double relevanceScore = resolveRelevanceScore(document);
            String chunkText = document.getText() == null ? "" : document.getText();
            candidates.add(new RetrievedChunk(document, relevanceScore, chunkText, chunkText));
        }
        return candidates;
    }

    /**
     * @Description: 解析向量库返回的相关性分数。
     * @Logic: 优先读取 metadata.score；其次将 metadata.distance 转为越大越相关的相似度；最后读取 Document 原生 score。
     * @Param: document Milvus 返回的候选文档。
     * @Return: 统一相关性分数；无法解析时返回 null。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public Double resolveRelevanceScore(Document document) {
        Object score = document.getMetadata().get("score");
        if (score instanceof Number number) {
            return number.doubleValue();
        }
        Object distance = document.getMetadata().get("distance");
        if (distance instanceof Number number) {
            return 1.0D / (1.0D + Math.max(0D, number.doubleValue()));
        }
        if (document.getScore() != null) {
            return document.getScore();
        }
        return null;
    }
}
