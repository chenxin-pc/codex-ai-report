package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.config.MilvusHybridProperties;
import com.example.aimilvusweb.config.ReportQualityProperties;
import com.example.aimilvusweb.infra.vector.ReportHybridVectorStore;
import com.example.aimilvusweb.infra.vector.SpringVectorStoreReportHybridVectorStore;
import com.example.aimilvusweb.repository.ReportChunkMapper;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description: 研报召回门面服务，对外提供统一 retrieve 入口并隐藏 Milvus hybrid pipeline 组装细节。
 * @Logic: 构造阶段组装锚点抽取、hybrid 检索、候选过滤、去重、重排和证据上下文策略；调用阶段只委托 RetrievalPipeline 执行。
 * @Param: 无。
 * @Return: 召回结果列表，每条结果包含命中 CHILD 文本、PARENT 证据上下文和质量诊断字段。
 * @author: cx
 * @Date: 2026-05-24 18:40:00
 */
@Service
public class ReportRetrievalService {

    /** 推荐召回 Pipeline，负责按固定顺序串联召回候选处理和证据上下文策略。 */
    private final RetrievalPipeline retrievalPipeline;

    /**
     * @Description: 初始化生产研报召回门面。
     * @Logic: 使用 Milvus 原生 hybrid 存储、query 锚点抽取、候选过滤、去重、可选重排和 PARENT 聚合策略组装 RetrievalPipeline。
     * @Param: vectorStoreProvider 旧 VectorStore 兼容提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置；researchQueryAnchorService query 锚点服务；reportChunkTagMapper chunk 标签 Mapper；reportDocumentTagMapper 报告级标签 Mapper；hybridVectorStore hybrid 向量存储；milvusHybridProperties Milvus hybrid 检索配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    @Autowired
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper,
                                  ReportDocumentTagMapper reportDocumentTagMapper,
                                  ReportHybridVectorStore hybridVectorStore,
                                  MilvusHybridProperties milvusHybridProperties) {
        // vectorStoreProvider 仅保留在构造签名中兼容旧测试，生产检索由 hybridVectorStore 执行。
        this.retrievalPipeline = buildPipeline(reportChunkMapper, reportQualityProperties, researchQueryAnchorService,
                reportChunkTagMapper, reportDocumentTagMapper, hybridVectorStore, milvusHybridProperties);
    }

    /**
     * @Description: 兼容未显式传入 hybrid 配置的检索服务构造器。
     * @Logic: 使用默认 MilvusHybridProperties，保持旧测试和手动构造路径稳定。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置；researchQueryAnchorService query 锚点服务；reportChunkTagMapper chunk 标签 Mapper；reportDocumentTagMapper 报告级标签 Mapper；hybridVectorStore hybrid 向量存储。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper,
                                  ReportDocumentTagMapper reportDocumentTagMapper,
                                  ReportHybridVectorStore hybridVectorStore) {
        // 委托完整构造器并使用默认 hybrid filter 配置。
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, researchQueryAnchorService,
                reportChunkTagMapper, reportDocumentTagMapper, hybridVectorStore, new MilvusHybridProperties());
    }

    /**
     * @Description: 兼容旧测试的检索服务构造器。
     * @Logic: 未注入 hybrid 存储时用旧 VectorStore 适配器包装，生产构造器不会走该路径。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置；researchQueryAnchorService query 锚点服务；reportChunkTagMapper chunk 标签 Mapper；reportDocumentTagMapper 报告级标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper,
                                  ReportDocumentTagMapper reportDocumentTagMapper) {
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, researchQueryAnchorService,
                reportChunkTagMapper, reportDocumentTagMapper, new SpringVectorStoreReportHybridVectorStore(vectorStoreProvider));
    }

    /**
     * @Description: 兼容旧测试的检索服务构造器。
     * @Logic: 未注入报告级标签 Mapper 时仅保留 chunk 标签诊断能力。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置；researchQueryAnchorService query 锚点服务；reportChunkTagMapper chunk 标签 Mapper。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties,
                                  ResearchQueryAnchorService researchQueryAnchorService,
                                  ReportChunkTagMapper reportChunkTagMapper) {
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, researchQueryAnchorService, reportChunkTagMapper, null);
    }

    /**
     * @Description: 兼容旧测试的检索服务构造器。
     * @Logic: 未注入 query 锚点和标签 Mapper 时退化为纯向量召回，保持旧单元测试可独立构造。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkMapper chunk Mapper；reportQualityProperties 检索配置。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-24 00:00:00
     */
    public ReportRetrievalService(ObjectProvider<VectorStore> vectorStoreProvider,
                                  ReportChunkMapper reportChunkMapper,
                                  ReportQualityProperties reportQualityProperties) {
        this(vectorStoreProvider, reportChunkMapper, reportQualityProperties, null, null, null);
    }

    /**
     * @Description: 执行研报召回并返回最终 TopK 证据。
     * @Logic: 委托 RetrievalPipeline 按固定顺序执行锚点抽取、Milvus hybrid 召回、候选处理、证据上下文策略和 TopK 截断。
     * @Param: query 用户规范化后的投研问题。
     * @Return: 最终召回证据列表；无候选或全部过滤时返回空列表。
     * @author: cx
     * @Date: 2026-05-24 18:40:00
     */
    public List<RetrievedChunk> retrieve(String query) {
        // 委托 Pipeline 按固定顺序执行召回链路，门面层不再保留旧实现分支。
        return retrievalPipeline.retrieve(query);
    }

    /**
     * @Description: 组装研报召回 Pipeline。
     * @Logic: 创建去重器、锚点抽取器、请求构建器、搜索执行器、候选过滤器、重排器和 PARENT/CHILD 上下文策略。
     * @Param: reportChunkMapper chunk Mapper；reportQualityProperties 检索配置；researchQueryAnchorService query 锚点服务；reportChunkTagMapper chunk 标签 Mapper；reportDocumentTagMapper 报告级标签 Mapper；hybridVectorStore hybrid 向量存储；milvusHybridProperties Milvus hybrid 配置。
     * @Return: 可直接执行 retrieve 的 RetrievalPipeline。
     * @author: cx
     * @Date: 2026-06-03 00:00:00
     */
    private RetrievalPipeline buildPipeline(ReportChunkMapper reportChunkMapper,
                                            ReportQualityProperties reportQualityProperties,
                                            ResearchQueryAnchorService researchQueryAnchorService,
                                            ReportChunkTagMapper reportChunkTagMapper,
                                            ReportDocumentTagMapper reportDocumentTagMapper,
                                            ReportHybridVectorStore hybridVectorStore,
                                            MilvusHybridProperties milvusHybridProperties) {
        RetrievedChunkDeduplicator deduplicator = new RetrievedChunkDeduplicator();
        return new RetrievalPipeline(
                reportQualityProperties,
                new RetrievalAnchorExtractor(researchQueryAnchorService),
                new RetrievalSearchRequestBuilder(milvusHybridProperties),
                new VectorSearchExecutor(hybridVectorStore, reportChunkTagMapper, reportDocumentTagMapper),
                new RetrievalCandidateMapper(),
                new RetrievalCandidateFilter(reportQualityProperties),
                deduplicator,
                new NoopRerankStrategy(),
                new QueryOverlapRerankStrategy(),
                new ChildExpansionEvidenceContextStrategy(reportChunkMapper, reportQualityProperties),
                new ParentAggregationEvidenceContextStrategy(reportChunkMapper, reportQualityProperties, deduplicator)
        );
    }
}
