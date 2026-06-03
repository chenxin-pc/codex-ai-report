package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.service.retrieval.RetrievedChunk;

import java.util.List;

/**
 * @Description: 召回候选相关性阈值过滤组件。
 * @Logic: 按检索配置中的 minSimilarityScore 过滤低分候选；缺失分数时保留候选交给后续护栏判断。
 * @Param: 无。
 * @Return: 无（无状态过滤组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievalCandidateFilter {

    /** 研报质量配置，用于读取召回最小相似度阈值。 */
    private final ReportQualityProperties reportQualityProperties;

    /**
     * @Description: 初始化召回候选过滤组件。
     * @Logic: 保存质量配置，过滤时读取最新 minSimilarityScore 阈值。
     * @Param: reportQualityProperties 研报质量配置。
     * @Return: 无（仅初始化组件依赖）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public RetrievalCandidateFilter(ReportQualityProperties reportQualityProperties) {
        this.reportQualityProperties = reportQualityProperties;
    }

    /**
     * @Description: 过滤低相关召回候选。
     * @Logic: 仅当候选分数非空且阈值启用时过滤低于阈值的候选，保持无分数候选兼容旧链路。
     * @Param: candidates 原始召回候选列表。
     * @Return: 过滤后的候选列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public List<RetrievedChunk> filter(List<RetrievedChunk> candidates) {
        return candidates.stream()
                .filter(candidate -> !shouldFilterByScore(candidate.score()))
                .toList();
    }

    /**
     * @Description: 判断单个分数是否低于配置阈值。
     * @Logic: 分数为空或阈值小于等于 0 时不过滤；否则低于阈值的候选被丢弃。
     * @Param: score 召回相关性分数。
     * @Return: 需要过滤时返回 true。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    boolean shouldFilterByScore(Double score) {
        return score != null
                && reportQualityProperties.getRetrieval().getMinSimilarityScore() > 0D
                && score < reportQualityProperties.getRetrieval().getMinSimilarityScore();
    }
}
