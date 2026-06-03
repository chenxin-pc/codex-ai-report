package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.service.retrieval.RetrievedChunk;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Description: 推荐召回 Pipeline 编排组件。
 * @Logic: 串联锚点抽取、请求构造、向量搜索、候选映射、分数过滤、去重、可选重排和证据上下文策略，输出最终推荐证据。
 * @Param: 无。
 * @Return: 无（召回编排组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class RetrievalPipeline {

    /** 研报质量配置，用于读取 TopK 与策略开关。 */
    private final ReportQualityProperties reportQualityProperties;
    /** query 锚点抽取组件。 */
    private final RetrievalAnchorExtractor anchorExtractor;
    /** SearchRequest 构建组件。 */
    private final RetrievalSearchRequestBuilder searchRequestBuilder;
    /** 向量库召回执行组件。 */
    private final VectorSearchExecutor vectorSearchExecutor;
    /** Milvus 文档到 RetrievedChunk 的映射组件。 */
    private final RetrievalCandidateMapper candidateMapper;
    /** 低分候选过滤组件。 */
    private final RetrievalCandidateFilter candidateFilter;
    /** 候选去重组件。 */
    private final RetrievedChunkDeduplicator deduplicator;
    /** 业务 metadata 加权排序组件。 */
    private final RetrievalBusinessBoostRanker businessBoostRanker = new RetrievalBusinessBoostRanker();
    /** 关闭重排时的原顺序策略。 */
    private final RerankStrategy noopRerankStrategy;
    /** 开启重排时的 query overlap 策略。 */
    private final RerankStrategy queryOverlapRerankStrategy;
    /** 关闭 PARENT 聚合时的逐 CHILD 扩展策略。 */
    private final EvidenceContextStrategy childExpansionStrategy;
    /** 开启 PARENT 聚合时的分组聚合策略。 */
    private final EvidenceContextStrategy parentAggregationStrategy;

    /**
     * @Description: 初始化推荐召回 Pipeline。
     * @Logic: 保存所有召回步骤组件和策略组件，运行时按配置开关选择重排与证据上下文策略。
     * @Param: reportQualityProperties 质量配置；anchorExtractor 锚点抽取；searchRequestBuilder 请求构建；vectorSearchExecutor 向量搜索；candidateMapper 候选映射；candidateFilter 候选过滤；deduplicator 候选去重；noopRerankStrategy 无重排策略；queryOverlapRerankStrategy overlap 重排策略；childExpansionStrategy CHILD 扩展策略；parentAggregationStrategy PARENT 聚合策略。
     * @Return: 无（仅初始化 Pipeline 依赖）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public RetrievalPipeline(ReportQualityProperties reportQualityProperties,
                             RetrievalAnchorExtractor anchorExtractor,
                             RetrievalSearchRequestBuilder searchRequestBuilder,
                             VectorSearchExecutor vectorSearchExecutor,
                             RetrievalCandidateMapper candidateMapper,
                             RetrievalCandidateFilter candidateFilter,
                             RetrievedChunkDeduplicator deduplicator,
                             RerankStrategy noopRerankStrategy,
                             RerankStrategy queryOverlapRerankStrategy,
                             EvidenceContextStrategy childExpansionStrategy,
                             EvidenceContextStrategy parentAggregationStrategy) {
        this.reportQualityProperties = reportQualityProperties;
        this.anchorExtractor = anchorExtractor;
        this.searchRequestBuilder = searchRequestBuilder;
        this.vectorSearchExecutor = vectorSearchExecutor;
        this.candidateMapper = candidateMapper;
        this.candidateFilter = candidateFilter;
        this.deduplicator = deduplicator;
        this.noopRerankStrategy = noopRerankStrategy;
        this.queryOverlapRerankStrategy = queryOverlapRerankStrategy;
        this.childExpansionStrategy = childExpansionStrategy;
        this.parentAggregationStrategy = parentAggregationStrategy;
    }

    /**
     * @Description: 执行完整推荐召回 Pipeline。
     * @Logic: 按固定顺序完成锚点抽取、Milvus 搜索、候选处理和证据上下文构建，策略开关仅影响重排和上下文构建步骤。
     * @Param: query 用户规范化后的投研问题。
     * @Return: 最终推荐证据列表；无 Milvus 结果或全部过滤时返回空列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public List<RetrievedChunk> retrieve(String query) {
        int initialTopK = Math.max(reportQualityProperties.getRetrieval().getInitialTopK(), 1);
        int finalTopK = Math.max(reportQualityProperties.getRetrieval().getFinalTopK(), 1);
        QueryAnchors anchors = anchorExtractor.extract(query);
        ReportHybridSearchRequest searchRequest = searchRequestBuilder.build(query, initialTopK, anchors);
        List<Document> documents = vectorSearchExecutor.search(searchRequest, anchors);
        if (documents.isEmpty()) {
            return List.of();
        }
        List<RetrievedChunk> candidates = candidateMapper.map(documents);
        List<RetrievedChunk> filtered = candidateFilter.filter(candidates);
        List<RetrievedChunk> deduplicated = deduplicator.deduplicate(filtered);
        List<RetrievedChunk> reranked = rerankStrategy().rerank(query, deduplicated);
        List<RetrievedChunk> boosted = businessBoostRanker.rank(reranked, anchors);
        return evidenceContextStrategy().build(boosted, finalTopK);
    }

    /**
     * @Description: 根据配置选择重排策略。
     * @Logic: rerankEnabled=true 时选择 query overlap 重排，否则保持过滤和去重后的原顺序。
     * @Param: 无。
     * @Return: 当前请求使用的重排策略。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private RerankStrategy rerankStrategy() {
        return reportQualityProperties.getRetrieval().isRerankEnabled() ? queryOverlapRerankStrategy : noopRerankStrategy;
    }

    /**
     * @Description: 根据配置选择证据上下文策略。
     * @Logic: parentAggregationEnabled=true 时按 PARENT 聚合，否则逐 CHILD 扩展 PARENT 上下文。
     * @Param: 无。
     * @Return: 当前请求使用的证据上下文策略。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private EvidenceContextStrategy evidenceContextStrategy() {
        return reportQualityProperties.getRetrieval().isParentAggregationEnabled() ? parentAggregationStrategy : childExpansionStrategy;
    }
}
