package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.retrieval.RetrievedChunk;

import java.util.List;

/**
 * @Description: 召回候选重排策略接口。
 * @Logic: 定义 query 与候选列表到重排后候选列表的转换边界，供 rerankEnabled 开关选择具体实现。
 * @Param: 无。
 * @Return: 无（策略接口）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public interface RerankStrategy {

    /**
     * @Description: 对候选列表执行重排。
     * @Logic: 具体策略决定是否保持原顺序或按 query 与候选文本相关性重新排序。
     * @Param: query 用户投研问题；candidates 去重后的召回候选。
     * @Return: 重排后的候选列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates);
}
