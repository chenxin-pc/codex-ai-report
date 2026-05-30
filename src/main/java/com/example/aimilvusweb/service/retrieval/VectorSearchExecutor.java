package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.repository.ReportChunkTagMapper;
import com.example.aimilvusweb.repository.ReportDocumentTagMapper;
import com.example.aimilvusweb.service.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * @Description: 向量库召回执行组件。
 * @Logic: 懒获取 VectorStore 并执行 Milvus ANN 检索；未配置时抛明确异常，空结果时触发 MySQL 标签主数据诊断但不伪造证据。
 * @Param: 无。
 * @Return: 无（向量召回执行组件）。
 * @author: cx
 * @Date: 2026-05-30 16:20:00
 */
public class VectorSearchExecutor {

    /** 向量库提供器，用于按需获取 Milvus VectorStore。 */
    private final ObjectProvider<VectorStore> vectorStoreProvider;
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
    public VectorSearchExecutor(ObjectProvider<VectorStore> vectorStoreProvider,
                                ReportChunkTagMapper reportChunkTagMapper,
                                ReportDocumentTagMapper reportDocumentTagMapper) {
        this.vectorStoreProvider = vectorStoreProvider;
        this.reportChunkTagMapper = reportChunkTagMapper;
        this.reportDocumentTagMapper = reportDocumentTagMapper;
    }

    /**
     * @Description: 执行向量相似度检索。
     * @Logic: 先获取可用 VectorStore；无结果时执行 metadata fallback 诊断并返回空列表，保持上层降级语义。
     * @Param: searchRequest 检索请求；anchors query 结构化锚点。
     * @Return: Milvus 返回的文档列表；无结果时返回空列表。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    public List<Document> search(SearchRequest searchRequest, QueryAnchors anchors) {
        VectorStore vectorStore = requireVectorStore();
        List<Document> documents = vectorStore.similaritySearch(searchRequest);
        if (documents == null || documents.isEmpty()) {
            diagnoseMetadataFallback(anchors);
            return List.of();
        }
        return documents;
    }

    /**
     * @Description: 获取可用向量库实例。
     * @Logic: 从 ObjectProvider 懒获取 VectorStore；未配置时抛出带配置提示的异常。
     * @Param: 无。
     * @Return: 可执行相似度检索的 VectorStore。
     * @author: cx
     * @Date: 2026-05-30 16:20:00
     */
    private VectorStore requireVectorStore() {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        return vectorStore;
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
