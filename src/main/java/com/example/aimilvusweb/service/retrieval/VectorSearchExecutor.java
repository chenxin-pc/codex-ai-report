package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.infra.vector.ReportHybridSearchRequest;
import com.example.aimilvusweb.infra.vector.ReportHybridVectorStore;
import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Description: hybrid 召回执行组件。
 * @Logic: 调用 ReportHybridVectorStore 执行 Milvus BM25+dense hybrid 检索；空结果时触发 MySQL 标签主数据诊断但不伪造证据。
 * @Param: 无。
 * @Return: 无（向量召回执行组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class VectorSearchExecutor {

    /** hybrid 向量存储，生产实现使用 Milvus 原生 BM25+dense search。 */
    private final ReportHybridVectorStore hybridVectorStore;
    /** chunk 标签 Mapper，用于 metadata 不同步时执行主数据诊断。 */
    private final ReportChunkTagMapper reportChunkTagMapper;
    /** 报告级标签 Mapper，用于父标签 metadata 不同步时执行主数据诊断。 */
    private final ReportDocumentTagMapper reportDocumentTagMapper;

    /**
     * @Description: 初始化向量召回执行组件。
     * @Logic: 保存 VectorStore 提供器和可选标签 Mapper，执行搜索时统一处理未配置和空结果诊断。
     * @Param: vectorStoreProvider 向量库提供器；reportChunkTagMapper chunk 标签 Mapper；reportDocumentTagMapper 报告级标签 Mapper。
     * @Return: 无（仅初始化组件依赖）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public VectorSearchExecutor(ReportHybridVectorStore hybridVectorStore,
                                ReportChunkTagMapper reportChunkTagMapper,
                                ReportDocumentTagMapper reportDocumentTagMapper) {
        this.hybridVectorStore = hybridVectorStore;
        this.reportChunkTagMapper = reportChunkTagMapper;
        this.reportDocumentTagMapper = reportDocumentTagMapper;
    }

    /**
     * @Description: 执行 hybrid 检索。
     * @Logic: 先获取可用 hybrid store；无结果时执行 metadata fallback 诊断并返回空列表，保持上层降级语义。
     * @Param: searchRequest hybrid 检索请求；anchors query 结构化锚点。
     * @Return: Milvus hybrid 返回的文档列表；无结果时返回空列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public List<Document> search(ReportHybridSearchRequest searchRequest, QueryAnchors anchors) {
        ReportHybridVectorStore vectorStore = requireHybridVectorStore();
        List<Document> documents = vectorStore.search(searchRequest, anchors);
        if (documents == null || documents.isEmpty()) {
            diagnoseMetadataFallback(anchors);
            return List.of();
        }
        return documents;
    }

    /**
     * @Description: 获取可用 hybrid 向量存储实例。
     * @Logic: 未配置时抛出带配置提示的异常。
     * @Param: 无。
     * @Return: 可执行 hybrid 检索的存储实例。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private ReportHybridVectorStore requireHybridVectorStore() {
        if (hybridVectorStore == null) {
            throw new IllegalStateException("ReportHybridVectorStore is not configured. Check app.milvus-hybrid and embedding properties.");
        }
        return hybridVectorStore;
    }

    /**
     * @Description: Milvus metadata filter 无结果时执行 MySQL 主数据诊断。
     * @Logic: 仅确认主题标签主数据是否存在，不把 MySQL 标签结果包装为推荐证据。
     * @Param: anchors query 结构化锚点。
     * @Return: 无（仅诊断副作用）。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private void diagnoseMetadataFallback(QueryAnchors anchors) {
        if (anchors.themeCodes().isEmpty()) {
            return;
        }
        if (reportDocumentTagMapper != null) {
            reportDocumentTagMapper.countByTagCodes("THEME", anchors.themeCodes());
        }
        if (reportChunkTagMapper != null) {
            reportChunkTagMapper.countByTagCodes("THEME", anchors.themeCodes());
        }
    }
}
