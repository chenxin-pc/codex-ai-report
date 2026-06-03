package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.retrieval.RetrievedChunk;

import java.util.List;

/**
 * @Description: 召回候选到推荐证据上下文的构建策略接口。
 * @Logic: 定义去重和重排后的 CHILD 候选如何转换为最终 TopK 证据列表，屏蔽 CHILD 扩展与 PARENT 聚合差异。
 * @Param: 无。
 * @Return: 无（策略接口）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public interface EvidenceContextStrategy {

    /**
     * @Description: 构建最终推荐证据上下文。
     * @Logic: 具体策略决定逐 CHILD 扩展或按 PARENT 聚合，并负责 finalTopK、预算和 fallback 语义。
     * @Param: candidates 去重和可选重排后的候选；finalTopK 最终返回证据数量上限。
     * @Return: 最终推荐证据列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    List<RetrievedChunk> build(List<RetrievedChunk> candidates, int finalTopK);
}
