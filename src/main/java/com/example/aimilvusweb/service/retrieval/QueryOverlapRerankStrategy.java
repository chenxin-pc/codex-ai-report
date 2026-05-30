package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;

import java.util.Comparator;
import java.util.List;

/**
 * @Description: 基于 query 与 CHILD 文本字符重合度的轻量重排策略。
 * @Logic: 规范化 query 和候选 chunkText 后计算字符命中数量，分数越高越靠前，用于修正向量召回后的候选顺序。
 * @Param: 无。
 * @Return: 无（无状态策略组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class QueryOverlapRerankStrategy implements RerankStrategy {

    /**
     * @Description: 按 query overlap 分数重排候选。
     * @Logic: 对候选子切片文本计算 overlapScore 并降序排序，分数相同场景保持 Java stream sorted 的稳定性。
     * @Param: query 用户投研问题；candidates 去重后的召回候选。
     * @Return: 重排后的候选列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        String normalizedQuery = RetrievalMetadataUtils.normalizeForOverlap(query);
        return candidates.stream()
                .sorted(Comparator.comparingInt((RetrievedChunk candidate) ->
                        overlapScore(normalizedQuery, RetrievalMetadataUtils.normalizeForOverlap(candidate.chunkText()))).reversed())
                .toList();
    }

    /**
     * @Description: 计算 query 与候选文本的字符重合分。
     * @Logic: query 或文本为空时返回 0；否则逐字符判断 query 字符是否出现在候选文本中。
     * @Param: query 规范化 query；text 规范化候选文本。
     * @Return: 字符命中数量。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    int overlapScore(String query, String text) {
        if (query.isBlank() || text.isBlank()) {
            return 0;
        }
        int score = 0;
        for (int i = 0; i < query.length(); i++) {
            if (text.indexOf(query.charAt(i)) >= 0) {
                score++;
            }
        }
        return score;
    }
}
