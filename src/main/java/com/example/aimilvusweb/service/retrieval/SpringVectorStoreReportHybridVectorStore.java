package com.example.aimilvusweb.service.retrieval;

import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

/**
 * @Description: Spring VectorStore 兼容适配器，仅用于旧单元测试和局部构造的过渡路径。
 * @Logic: 生产构造器不会使用该适配器；旧测试通过它继续验证去重、PARENT 聚合和证据护栏行为。
 * @Param: 详见各方法。
 * @Return: 无或召回文档列表。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
public class SpringVectorStoreReportHybridVectorStore implements ReportHybridVectorStore {

    /** Spring AI VectorStore 提供器，用于兼容旧测试 mock。 */
    private final ObjectProvider<VectorStore> vectorStoreProvider;

    /**
     * @Description: 初始化兼容适配器。
     * @Logic: 保存 VectorStore 提供器，执行写入或检索时再懒获取。
     * @Param: vectorStoreProvider 旧 VectorStore 提供器。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public SpringVectorStoreReportHybridVectorStore(ObjectProvider<VectorStore> vectorStoreProvider) {
        this.vectorStoreProvider = vectorStoreProvider;
    }

    /**
     * @Description: 兼容路径不初始化 collection。
     * @Logic: 旧测试不连接真实 Milvus，因此该方法保持空实现。
     * @Param: 无。
     * @Return: 无。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public void ensureCollection() {
        // 旧测试适配器不访问真实 Milvus collection。
    }

    /**
     * @Description: 兼容路径不删除 collection。
     * @Logic: 旧测试不连接真实 Milvus，因此该方法保持空实现。
     * @Param: 无。
     * @Return: 无。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public void dropCollection() {
        // 旧测试适配器不访问真实 Milvus collection。
    }

    /**
     * @Description: 使用旧 VectorStore 写入文档。
     * @Logic: 仅用于兼容旧单元测试；生产写入路径使用 MilvusReportHybridVectorStore。
     * @Param: documents 待写入文档。
     * @Return: 无（仅调用 VectorStore.add）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public void write(List<Document> documents) {
        // 获取旧 VectorStore mock 或实例。
        VectorStore vectorStore = requireVectorStore();
        // 复用旧写入行为，保证已有测试的 verify(vectorStore).add(...) 仍成立。
        vectorStore.add(documents);
    }

    /**
     * @Description: 使用旧 VectorStore 执行相似度检索。
     * @Logic: 将 hybrid 请求转换为 Spring SearchRequest，仅用于兼容旧测试。
     * @Param: request hybrid 请求；anchors query 锚点。
     * @Return: VectorStore 返回文档列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public List<Document> search(ReportHybridSearchRequest request, QueryAnchors anchors) {
        // 创建旧 SearchRequest，保留 query 和 topK。
        SearchRequest.Builder builder = SearchRequest.builder().query(request.query()).topK(request.topK());
        // 存在兼容 filter 时附加给旧 VectorStore。
        if (request.legacyFilterExpression() != null) {
            builder.filterExpression(request.legacyFilterExpression());
        }
        // 执行旧 similaritySearch，mock 测试会在这里返回候选。
        List<Document> documents = requireVectorStore().similaritySearch(builder.build());
        // 旧 VectorStore 可能返回 null，这里归一为空列表。
        return documents == null ? List.of() : documents;
    }

    /**
     * @Description: 获取旧 VectorStore。
     * @Logic: provider 无可用实例时抛出与旧链路一致的配置错误。
     * @Param: 无。
     * @Return: VectorStore 实例。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private VectorStore requireVectorStore() {
        // 从 ObjectProvider 懒获取 VectorStore。
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        // 缺失时给出明确配置提示。
        if (vectorStore == null) {
            throw new IllegalStateException("VectorStore is not configured. Set spring.ai.vectorstore.type=milvus and Milvus properties.");
        }
        // 返回可用 VectorStore。
        return vectorStore;
    }
}
