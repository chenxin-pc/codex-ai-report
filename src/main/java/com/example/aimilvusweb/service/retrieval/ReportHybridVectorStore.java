package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;

import java.util.List;

/**
 * @Description: 研报 hybrid 向量存储接口，抽象 collection 初始化、写入、删除和 BM25+dense 检索。
 * @Logic: 生产实现使用 Milvus 原生客户端，测试适配器可包装旧 Spring VectorStore 以复用既有单测。
 * @Param: 详见各方法。
 * @Return: 无、写入副作用或召回文档列表。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
public interface ReportHybridVectorStore {

    /**
     * @Description: 确保 hybrid collection 可用。
     * @Logic: 不存在时创建 schema、BM25 function 和索引；存在但 schema 不兼容时抛出异常。
     * @Param: 无。
     * @Return: 无（仅 Milvus collection 初始化副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    void ensureCollection();

    /**
     * @Description: 删除 hybrid collection。
     * @Logic: 仅由显式清空入口调用，应用启动不得自动删除。
     * @Param: 无。
     * @Return: 无（仅 Milvus collection 删除副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    void dropCollection();

    /**
     * @Description: 写入向量文档。
     * @Logic: 生产实现生成 dense embedding 并写入 Milvus，BM25 sparse 由 collection function 生成。
     * @Param: documents 待写入文档列表。
     * @Return: 无（仅写入向量索引副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    void write(List<Document> documents);

    /**
     * @Description: 执行 hybrid 检索。
     * @Logic: dense embedding search 与 BM25 full-text search 进入 Milvus hybrid ranker，结果映射为 Document。
     * @Param: request hybrid 请求；anchors query 锚点，用于空结果诊断或扩展。
     * @Return: Milvus 召回文档列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    List<Document> search(ReportHybridSearchRequest request, QueryAnchors anchors);
}
