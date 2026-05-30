package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.ReportRetrievalService.RetrievedChunk;

import java.util.List;

/**
 * @Description: 不改变候选顺序的重排策略。
 * @Logic: 在 rerankEnabled=false 时直接返回过滤和去重后的候选，保持 Milvus 原始相关性顺序。
 * @Param: 无。
 * @Return: 无（无状态策略组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class NoopRerankStrategy implements RerankStrategy {

    /**
     * @Description: 返回原始候选顺序。
     * @Logic: 不对候选执行排序、复制或截断，确保关闭重排时顺序稳定。
     * @Param: query 用户投研问题；candidates 去重后的召回候选。
     * @Return: 原始候选列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        return candidates;
    }
}
