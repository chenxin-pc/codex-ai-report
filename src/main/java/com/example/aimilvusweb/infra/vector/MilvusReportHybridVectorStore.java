package com.example.aimilvusweb.infra.vector;

import com.example.aimilvusweb.config.MilvusHybridProperties;
import com.example.aimilvusweb.service.taxonomy.ResearchQueryAnchorService.QueryAnchors;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.BaseRanker;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.request.ranker.WeightedRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @Description: Milvus 原生研报 hybrid 向量存储，实现 BM25+dense collection 初始化、写入和检索。
 * @Logic: 使用 MilvusClientV2 管理 schema/function/index/load；使用 Qwen EmbeddingModel 生成 dense 向量；BM25 sparse 由 Milvus function 生成。
 * @Param: 详见各方法。
 * @Return: 无或召回文档列表。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Component
public class MilvusReportHybridVectorStore implements ReportHybridVectorStore {

    /** BM25 function 名称，用于 schema 兼容性检查。 */
    private static final String BM25_FUNCTION_NAME = "chunk_text_bm25";
    /** Milvus 中 chunkId 字段名。 */
    private static final String FIELD_CHUNK_ID = "chunkId";
    /** Milvus 中 reportId 字段名。 */
    private static final String FIELD_REPORT_ID = "reportId";
    /** Milvus 中 parentChunkUid 字段名。 */
    private static final String FIELD_PARENT_CHUNK_UID = "parentChunkUid";
    /** Milvus 中 chunkType 字段名。 */
    private static final String FIELD_CHUNK_TYPE = "chunkType";
    /** Milvus 中 chunkIndex 字段名。 */
    private static final String FIELD_CHUNK_INDEX = "chunkIndex";
    /** Milvus 中 sectionPath 字段名。 */
    private static final String FIELD_SECTION_PATH = "sectionPath";
    /** Milvus 中 title 字段名。 */
    private static final String FIELD_TITLE = "title";
    /** Milvus 中 source 字段名。 */
    private static final String FIELD_SOURCE = "source";
    /** Milvus 中 institution 字段名。 */
    private static final String FIELD_INSTITUTION = "institution";
    /** Milvus 中 publishDate 字段名。 */
    private static final String FIELD_PUBLISH_DATE = "publishDate";
    /** Milvus 中 author 字段名。 */
    private static final String FIELD_AUTHOR = "author";
    /** Milvus 中 normalizedAuthor 字段名。 */
    private static final String FIELD_NORMALIZED_AUTHOR = "normalizedAuthor";
    /** Milvus 中 authorText 字段名，保存 |author1|author2| 形式的多作者过滤文本。 */
    private static final String FIELD_AUTHOR_TEXT = "authorText";
    /** Milvus 中 reportThemeCode 字段名。 */
    private static final String FIELD_REPORT_THEME_CODE = "reportThemeCode";
    /** Milvus 中 themeCode 字段名。 */
    private static final String FIELD_THEME_CODE = "themeCode";
    /** Milvus 中 industryCode 字段名。 */
    private static final String FIELD_INDUSTRY_CODE = "industryCode";
    /** Milvus 中 companyName 字段名。 */
    private static final String FIELD_COMPANY_NAME = "companyName";
    /** Milvus 中 ticker 字段名。 */
    private static final String FIELD_TICKER = "ticker";

    /** Milvus hybrid 配置。 */
    private final MilvusHybridProperties properties;
    /** Embedding 模型提供器，用于运行时获取 Qwen embedding。 */
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    /** 延迟创建的 Milvus V2 客户端，避免应用启动时立即连接外部服务。 */
    private volatile MilvusClientV2 client;

    /**
     * @Description: 初始化 Milvus hybrid 存储。
     * @Logic: 保存配置和 embedding provider，Milvus client 在首次 collection 操作时懒创建。
     * @Param: properties hybrid 配置；embeddingModelProvider embedding 模型提供器。
     * @Return: 无（仅初始化对象状态）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    public MilvusReportHybridVectorStore(MilvusHybridProperties properties,
                                         ObjectProvider<EmbeddingModel> embeddingModelProvider) {
        this.properties = properties;
        this.embeddingModelProvider = embeddingModelProvider;
    }

    /**
     * @Description: 确保 Milvus hybrid collection 存在且可检索。
     * @Logic: 开关关闭时报错；collection 不存在则创建 schema/index/function；存在则校验关键字段和 BM25 function；最后同步 load。
     * @Param: 无。
     * @Return: 无（仅 Milvus schema 和 load 副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public void ensureCollection() {
        // 禁用开关用于测试或故障诊断，生产检索不得静默退回旧 collection。
        if (!properties.isEnabled()) {
            throw new IllegalStateException("Milvus hybrid retrieval is disabled");
        }
        // 不存在 collection 时创建完整 hybrid schema。
        if (!client().hasCollection(HasCollectionReq.builder().collectionName(properties.getCollectionName()).build())) {
            createCollection();
        } else {
            // 已存在 collection 必须校验关键 schema，避免写入旧 dense-only collection。
            validateCollectionSchema();
        }
        // 同步 load collection，确保后续检索请求可执行。
        client().loadCollection(LoadCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .sync(Boolean.TRUE)
                .build());
    }

    /**
     * @Description: 显式删除 hybrid collection。
     * @Logic: 仅清空入口调用；不存在时直接返回保持幂等。
     * @Param: 无。
     * @Return: 无（仅 Milvus collection 删除副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public void dropCollection() {
        // 收集新 hybrid collection 和历史 dense-only collection 名称。
        for (String collectionName : resetCollectionNames()) {
            // collection 不存在时清空动作视为已完成。
            if (!client().hasCollection(HasCollectionReq.builder().collectionName(collectionName).build())) {
                continue;
            }
            // 删除 collection，历史索引数据由重新导入重建。
            client().dropCollection(DropCollectionReq.builder()
                    .collectionName(collectionName)
                    .build());
        }
    }

    /**
     * @Description: 写入研报向量文档。
     * @Logic: 确保 collection 后批量生成 dense embedding，并把 chunk 文本与 metadata 写入 Milvus；sparse 字段由 BM25 function 自动生成。
     * @Param: documents 待写入文档。
     * @Return: 无（仅 Milvus insert 副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public void write(List<Document> documents) {
        // 空列表不访问 Milvus，保持向量阶段幂等。
        if (documents == null || documents.isEmpty()) {
            return;
        }
        // 写入前确保 schema、function、index 和 load 状态正确。
        if (properties.isAutoInitialize()) {
            ensureCollection();
        }
        // 批量提取文本用于 Qwen dense embedding。
        List<String> texts = documents.stream().map(document -> safeText(document.getText())).toList();
        // 调用 Spring AI embedding 模型生成 dense 向量。
        List<float[]> embeddings = requireEmbeddingModel().embed(texts);
        // embedding 返回数量必须与文档数量一致，否则无法安全写入。
        if (embeddings.size() != documents.size()) {
            throw new IllegalStateException("Embedding result size does not match document size");
        }
        // 逐条构造 Milvus insert row。
        List<JsonObject> rows = new ArrayList<>(documents.size());
        // 遍历文档并绑定对应 dense embedding。
        for (int index = 0; index < documents.size(); index++) {
            rows.add(toMilvusRow(documents.get(index), embeddings.get(index)));
        }
        // 执行 Milvus upsert；初次导入是插入，metadata 同步时按 chunkUid 覆盖。
        client().upsert(UpsertReq.builder()
                .collectionName(properties.getCollectionName())
                .data(rows)
                .build());
    }

    /**
     * @Description: 执行 Milvus BM25+dense hybrid search。
     * @Logic: 同一个 query 同时构造 dense FloatVec 和 BM25 EmbeddedText 两路请求，再用配置 ranker 融合。
     * @Param: request hybrid 检索请求；anchors query 锚点。
     * @Return: Milvus 返回文档列表；无命中时返回空列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    @Override
    public List<Document> search(ReportHybridSearchRequest request, QueryAnchors anchors) {
        // 检索前确保 collection 已创建并加载。
        if (properties.isAutoInitialize()) {
            ensureCollection();
        }
        // query 为空时没有可执行检索语义。
        if (request.query() == null || request.query().isBlank()) {
            return List.of();
        }
        // 生成 dense query embedding。
        float[] denseEmbedding = requireEmbeddingModel().embed(request.query());
        // 构造 dense ANN 子请求。
        AnnSearchReq denseRequest = AnnSearchReq.builder()
                .vectorFieldName(properties.getDenseVectorField())
                .vectors(List.of(new FloatVec(denseEmbedding)))
                .topK(request.topK())
                .expr(blankToNull(request.milvusFilter()))
                .metricType(metricType(properties.getDenseMetricType()))
                .build();
        // 构造 BM25 sparse 子请求，EmbeddedText 交给 Milvus BM25 function 处理。
        AnnSearchReq sparseRequest = AnnSearchReq.builder()
                .vectorFieldName(properties.getSparseVectorField())
                .vectors(List.of(new EmbeddedText(request.query())))
                .topK(request.topK())
                .expr(blankToNull(request.milvusFilter()))
                .metricType(metricType(properties.getSparseMetricType()))
                .build();
        // 构造 hybrid search 请求，并指定输出字段供上层映射为 RetrievedChunk。
        HybridSearchReq hybridSearchReq = HybridSearchReq.builder()
                .collectionName(properties.getCollectionName())
                .searchRequests(List.of(denseRequest, sparseRequest))
                .ranker(ranker())
                .topK(request.topK())
                .outFields(outputFields())
                .build();
        // 执行 Milvus hybrid search。
        SearchResp response = client().hybridSearch(hybridSearchReq);
        // 将第一路 query 的结果映射为 Spring Document，保持上层 Pipeline 结构稳定。
        List<Document> documents = toDocuments(response);
        // 按配置补充 dense 和 BM25 单路诊断分数。
        attachRouteScores(documents, request, denseEmbedding);
        // 返回带诊断分数的候选文档。
        return documents;
    }

    /**
     * @Description: 创建 Milvus hybrid collection。
     * @Logic: 组装主键、文本、dense、sparse、metadata 字段和 BM25 function，并在创建时附带 dense/sparse/scalar 索引。
     * @Param: 无。
     * @Return: 无（仅创建 collection 副作用）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void createCollection() {
        // 构建 collection schema，关闭动态字段，避免 metadata 漂移。
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder()
                .enableDynamicField(false)
                .fieldSchemaList(fieldSchemas())
                .functionList(List.of(bm25Function()))
                .build();
        // 创建 collection 并同时创建必要索引。
        client().createCollection(CreateCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .description("Report chunk BM25+dense hybrid retrieval collection")
                .collectionSchema(schema)
                .indexParams(indexParams())
                .build());
    }

    /**
     * @Description: 校验已有 collection schema。
     * @Logic: 检查关键字段和 BM25 function 是否存在，发现旧 dense-only collection 时显式失败。
     * @Param: 无。
     * @Return: 无；不兼容时抛出异常。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void validateCollectionSchema() {
        // 读取 collection 描述信息。
        DescribeCollectionResp description = client().describeCollection(DescribeCollectionReq.builder()
                .collectionName(properties.getCollectionName())
                .build());
        // 提取字段名集合便于校验。
        Set<String> fieldNames = new LinkedHashSet<>(description.getFieldNames());
        // 校验所有必须字段。
        for (String fieldName : requiredFieldNames()) {
            if (!fieldNames.contains(fieldName)) {
                throw new IllegalStateException("Milvus hybrid collection missing field: " + fieldName);
            }
        }
        // 读取 schema function 列表。
        List<CreateCollectionReq.Function> functions = description.getCollectionSchema().getFunctionList();
        // 校验 BM25 function 是否存在。
        boolean hasBm25 = functions != null && functions.stream().anyMatch(function -> FunctionType.BM25.equals(function.getFunctionType()));
        // 缺少 BM25 function 时说明不是 hybrid schema。
        if (!hasBm25) {
            throw new IllegalStateException("Milvus hybrid collection missing BM25 function");
        }
    }

    /**
     * @Description: 构造 collection 字段列表。
     * @Logic: 字段覆盖主键、文本、dense/sparse 向量和推荐过滤所需 metadata。
     * @Param: 无。
     * @Return: 字段 schema 列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private List<CreateCollectionReq.FieldSchema> fieldSchemas() {
        // 初始化字段集合。
        List<CreateCollectionReq.FieldSchema> fields = new ArrayList<>();
        // 主键使用 chunkUid，保证 MySQL chunk 与 Milvus 记录一一对应。
        fields.add(varcharField(properties.getPrimaryKeyField(), 64, true));
        // 基础标识字段。
        fields.add(int64Field(FIELD_REPORT_ID));
        fields.add(int64Field(FIELD_CHUNK_ID));
        fields.add(varcharField(FIELD_PARENT_CHUNK_UID, 64, false));
        fields.add(varcharField(FIELD_CHUNK_TYPE, 16, false));
        fields.add(int64Field(FIELD_CHUNK_INDEX));
        // 文本字段启用 analyzer 和 match，作为 BM25 function 输入。
        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(properties.getTextField())
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .enableAnalyzer(Boolean.TRUE)
                .analyzerParams(Map.of("type", properties.getTextAnalyzerType()))
                .enableMatch(Boolean.TRUE)
                .build());
        // dense 向量字段保存 Qwen embedding。
        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(properties.getDenseVectorField())
                .dataType(DataType.FloatVector)
                .dimension(properties.getEmbeddingDimension())
                .build());
        // sparse 向量字段由 BM25 function 生成。
        fields.add(CreateCollectionReq.FieldSchema.builder()
                .name(properties.getSparseVectorField())
                .dataType(DataType.SparseFloatVector)
                .build());
        // 常用 metadata 字段。
        fields.add(varcharField(FIELD_SECTION_PATH, 512, false));
        fields.add(varcharField(FIELD_TITLE, 512, false));
        fields.add(varcharField(FIELD_SOURCE, 255, false));
        fields.add(varcharField(FIELD_INSTITUTION, 255, false));
        fields.add(varcharField(FIELD_PUBLISH_DATE, 32, false));
        fields.add(varcharField(FIELD_AUTHOR, 128, false));
        fields.add(varcharField(FIELD_NORMALIZED_AUTHOR, 128, false));
        fields.add(varcharField(FIELD_AUTHOR_TEXT, 1024, false));
        fields.add(varcharField(FIELD_REPORT_THEME_CODE, 64, false));
        fields.add(varcharField(FIELD_THEME_CODE, 64, false));
        fields.add(varcharField(FIELD_INDUSTRY_CODE, 64, false));
        fields.add(varcharField(FIELD_COMPANY_NAME, 128, false));
        fields.add(varcharField(FIELD_TICKER, 32, false));
        // 返回完整字段集合。
        return fields;
    }

    /**
     * @Description: 构造 BM25 function。
     * @Logic: 以 chunkText 为输入，输出 sparseVector，Milvus 在 insert/search 时负责 sparse 表达。
     * @Param: 无。
     * @Return: BM25 function schema。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private CreateCollectionReq.Function bm25Function() {
        // 创建 BM25 function，绑定文本输入和 sparse 输出字段。
        return CreateCollectionReq.Function.builder()
                .name(BM25_FUNCTION_NAME)
                .functionType(FunctionType.BM25)
                .inputFieldNames(List.of(properties.getTextField()))
                .outputFieldNames(List.of(properties.getSparseVectorField()))
                .build();
    }

    /**
     * @Description: 构造索引参数列表。
     * @Logic: dense/sparse 向量字段创建检索索引，常用 scalar filter 字段创建 inverted index。
     * @Param: 无。
     * @Return: 索引参数列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private List<IndexParam> indexParams() {
        // 初始化索引集合。
        List<IndexParam> indexes = new ArrayList<>();
        // dense 向量索引。
        indexes.add(IndexParam.builder()
                .fieldName(properties.getDenseVectorField())
                .indexName("idx_dense_vector")
                .indexType(indexType(properties.getDenseIndexType()))
                .metricType(metricType(properties.getDenseMetricType()))
                .build());
        // sparse BM25 索引。
        indexes.add(IndexParam.builder()
                .fieldName(properties.getSparseVectorField())
                .indexName("idx_sparse_bm25")
                .indexType(indexType(properties.getSparseIndexType()))
                .metricType(metricType(properties.getSparseMetricType()))
                .build());
        // scalar inverted indexes 提升常用 metadata filter 性能。
        for (String fieldName : scalarIndexFields()) {
            indexes.add(IndexParam.builder()
                    .fieldName(fieldName)
                    .indexName("idx_" + fieldName)
                    .indexType(IndexParam.IndexType.INVERTED)
                    .build());
        }
        // 返回索引列表。
        return indexes;
    }

    /**
     * @Description: 构造 Milvus insert row。
     * @Logic: 将 Spring Document metadata 投影为 Milvus schema 字段，并附加 denseVector。
     * @Param: document 向量文档；embedding dense embedding。
     * @Return: Milvus JsonObject row。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private JsonObject toMilvusRow(Document document, float[] embedding) {
        // 读取 metadata 便于后续字段转换。
        Map<String, Object> metadata = document.getMetadata();
        // 初始化 Milvus row。
        JsonObject row = new JsonObject();
        // 写入主键和基础字段。
        row.addProperty(properties.getPrimaryKeyField(), safeText(document.getId()));
        row.addProperty(FIELD_REPORT_ID, metadataLong(metadata, FIELD_REPORT_ID));
        row.addProperty(FIELD_CHUNK_ID, metadataLong(metadata, FIELD_CHUNK_ID));
        row.addProperty(FIELD_PARENT_CHUNK_UID, metadataText(metadata, FIELD_PARENT_CHUNK_UID));
        row.addProperty(FIELD_CHUNK_TYPE, metadataText(metadata, FIELD_CHUNK_TYPE));
        row.addProperty(FIELD_CHUNK_INDEX, metadataLong(metadata, FIELD_CHUNK_INDEX));
        // 写入 chunk 文本，BM25 function 会读取该字段生成 sparse vector。
        row.addProperty(properties.getTextField(), safeText(document.getText()));
        // 写入 dense vector。
        row.add(properties.getDenseVectorField(), toJsonArray(embedding));
        // 写入可过滤和可展示 metadata。
        row.addProperty(FIELD_SECTION_PATH, metadataText(metadata, FIELD_SECTION_PATH));
        row.addProperty(FIELD_TITLE, metadataText(metadata, FIELD_TITLE));
        row.addProperty(FIELD_SOURCE, metadataText(metadata, FIELD_SOURCE));
        row.addProperty(FIELD_INSTITUTION, metadataText(metadata, FIELD_INSTITUTION));
        row.addProperty(FIELD_PUBLISH_DATE, metadataText(metadata, FIELD_PUBLISH_DATE));
        row.addProperty(FIELD_AUTHOR, metadataText(metadata, FIELD_AUTHOR));
        row.addProperty(FIELD_NORMALIZED_AUTHOR, metadataText(metadata, FIELD_NORMALIZED_AUTHOR));
        row.addProperty(FIELD_AUTHOR_TEXT, metadataText(metadata, FIELD_AUTHOR_TEXT));
        row.addProperty(FIELD_REPORT_THEME_CODE, metadataText(metadata, FIELD_REPORT_THEME_CODE));
        row.addProperty(FIELD_THEME_CODE, metadataText(metadata, FIELD_THEME_CODE));
        row.addProperty(FIELD_INDUSTRY_CODE, metadataText(metadata, FIELD_INDUSTRY_CODE));
        row.addProperty(FIELD_COMPANY_NAME, metadataText(metadata, FIELD_COMPANY_NAME));
        row.addProperty(FIELD_TICKER, metadataText(metadata, FIELD_TICKER));
        // 返回完整 row。
        return row;
    }

    /**
     * @Description: 将 Milvus 搜索响应转换为 Document。
     * @Logic: 读取第一组搜索结果，把 entity 字段作为 metadata，并保留 fused score 供分数标准化。
     * @Param: response Milvus hybrid search 响应。
     * @Return: Document 列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private List<Document> toDocuments(SearchResp response) {
        // 响应为空时返回空列表。
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return List.of();
        }
        // 只处理单 query 的第一组结果。
        List<SearchResp.SearchResult> results = response.getSearchResults().get(0);
        // 结果为空时返回空列表。
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        // 初始化 Document 列表。
        List<Document> documents = new ArrayList<>(results.size());
        // 遍历 Milvus hit。
        for (SearchResp.SearchResult result : results) {
            // 复制 entity 字段作为 metadata。
            Map<String, Object> metadata = result.getEntity() == null ? new HashMap<>() : new HashMap<>(result.getEntity());
            // 写入 hybrid fused score 诊断字段。
            metadata.put("fusedScore", result.getScore());
            // 写入 Milvus fused score 的归一化诊断值；dense/BM25 子路分数若由 SDK entity 返回会随原 metadata 一起保留。
            metadata.put("normalizedScore", normalizeScore(result.getScore()));
            // 写入统一 score 字段，复用现有候选映射逻辑。
            metadata.put("score", result.getScore());
            // 读取 chunk 文本字段。
            String text = String.valueOf(metadata.getOrDefault(properties.getTextField(), ""));
            // 读取主键，缺失时使用 Milvus 返回 id。
            String id = String.valueOf(metadata.getOrDefault(properties.getPrimaryKeyField(), result.getId()));
            // 构建 Spring Document 供上层 Pipeline 复用。
            documents.add(new Document(id, text, metadata));
        }
        // 返回映射后的候选文档。
        return documents;
    }

    /**
     * @Description: 回填 dense 和 BM25 单路诊断分数。
     * @Logic: hybrid search 只决定主排序；诊断分数通过同 query 的 dense/sparse 单路 search 按 chunkUid 合并。
     * @Param: documents hybrid 候选文档；request hybrid 请求；denseEmbedding dense query embedding。
     * @Return: 无（仅修改候选 metadata）。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private void attachRouteScores(List<Document> documents, ReportHybridSearchRequest request, float[] denseEmbedding) {
        // 诊断关闭或无候选时不执行额外 search。
        if (!properties.isRouteScoreDiagnosticsEnabled() || documents.isEmpty()) {
            return;
        }
        try {
            // 执行 dense 单路检索并按 chunkUid 收集分数。
            Map<String, Float> denseScores = routeScores(SearchReq.builder()
                    .collectionName(properties.getCollectionName())
                    .annsField(properties.getDenseVectorField())
                    .data(List.of(new FloatVec(denseEmbedding)))
                    .topK(request.topK())
                    .filter(blankToNull(request.milvusFilter()))
                    .metricType(metricType(properties.getDenseMetricType()))
                    .outputFields(List.of(properties.getPrimaryKeyField()))
                    .build());
            // 执行 BM25 sparse 单路检索并按 chunkUid 收集分数。
            Map<String, Float> sparseScores = routeScores(SearchReq.builder()
                    .collectionName(properties.getCollectionName())
                    .annsField(properties.getSparseVectorField())
                    .data(List.of(new EmbeddedText(request.query())))
                    .topK(request.topK())
                    .filter(blankToNull(request.milvusFilter()))
                    .metricType(metricType(properties.getSparseMetricType()))
                    .outputFields(List.of(properties.getPrimaryKeyField()))
                    .build());
            // 遍历 hybrid 候选并回填子路分数。
            for (Document document : documents) {
                // 使用 Document id 对齐 Milvus 主键。
                String chunkUid = document.getId();
                // 回填 dense 分数，未进入 dense topK 时为 null。
                document.getMetadata().put("denseScore", denseScores.get(chunkUid));
                // 回填 BM25 分数，未进入 sparse topK 时为 null。
                document.getMetadata().put("bm25Score", sparseScores.get(chunkUid));
                // sparseScore 是 bm25Score 的同义诊断字段，便于后续不同命名消费。
                document.getMetadata().put("sparseScore", sparseScores.get(chunkUid));
            }
        } catch (RuntimeException e) {
            // 诊断分数失败不影响主召回，只把错误摘要写入 metadata 便于排障。
            for (Document document : documents) {
                document.getMetadata().put("routeScoreDiagnosticsError", abbreviate(e.getMessage()));
            }
        }
    }

    /**
     * @Description: 执行单路 Milvus search 并返回分数字典。
     * @Logic: 只读取主键和 score，用于补充诊断字段，不影响 hybrid 主排序结果。
     * @Param: searchReq 单路检索请求。
     * @Return: chunkUid 到 score 的映射。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private Map<String, Float> routeScores(SearchReq searchReq) {
        // 执行单路 search。
        SearchResp response = client().search(searchReq);
        // 响应为空时返回空分数字典。
        if (response == null || response.getSearchResults() == null || response.getSearchResults().isEmpty()) {
            return Map.of();
        }
        // 读取单 query 结果。
        List<SearchResp.SearchResult> results = response.getSearchResults().get(0);
        // 结果为空时返回空分数字典。
        if (results == null || results.isEmpty()) {
            return Map.of();
        }
        // 初始化分数字典。
        Map<String, Float> scores = new HashMap<>();
        // 遍历单路 hit。
        for (SearchResp.SearchResult result : results) {
            // 读取 entity 主键，缺失时回退 Milvus id。
            Map<String, Object> entity = result.getEntity();
            // 转换为 chunkUid 字符串。
            String chunkUid = String.valueOf(entity == null ? result.getId() : entity.getOrDefault(properties.getPrimaryKeyField(), result.getId()));
            // 保存该路 score。
            scores.put(chunkUid, result.getScore());
        }
        // 返回分数字典。
        return scores;
    }

    /**
     * @Description: 懒创建 Milvus V2 客户端。
     * @Logic: 首次使用时根据 host/port/database 创建客户端，避免 Spring 启动阶段连接外部服务。
     * @Param: 无。
     * @Return: MilvusClientV2。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private MilvusClientV2 client() {
        // 快速读取已创建客户端。
        MilvusClientV2 existing = client;
        // 已创建时直接复用。
        if (existing != null) {
            return existing;
        }
        // 同步创建客户端，避免并发初始化多个连接。
        synchronized (this) {
            // 二次检查，避免等待锁期间其他线程已初始化。
            if (client == null) {
                // 构造 Milvus HTTP URI。
                String uri = "http://" + properties.getHost() + ":" + properties.getPort();
                // 创建 V2 客户端，database 通过连接配置绑定。
                client = new MilvusClientV2(ConnectConfig.builder()
                        .uri(uri)
                        .dbName(properties.getDatabaseName())
                        .build());
            }
            // 返回共享客户端。
            return client;
        }
    }

    /**
     * @Description: 获取 embedding 模型。
     * @Logic: 未配置 Qwen embedding 时抛出明确异常，避免写入空向量或伪造 dense 召回。
     * @Param: 无。
     * @Return: EmbeddingModel。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private EmbeddingModel requireEmbeddingModel() {
        // 从 Spring 容器懒获取 embedding 模型。
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        // 缺失时直接失败，调用方记录导入或检索错误。
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel is not configured for Milvus hybrid retrieval");
        }
        // 返回可用 embedding 模型。
        return embeddingModel;
    }

    /**
     * @Description: 构造 ranker。
     * @Logic: rankerType=WEIGHTED 时使用 dense/BM25 权重，否则使用 RRF。
     * @Param: 无。
     * @Return: Milvus ranker。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private BaseRanker ranker() {
        // Weighted ranker 使用配置权重融合 dense 与 BM25。
        if ("WEIGHTED".equalsIgnoreCase(properties.getRankerType())) {
            return new WeightedRanker(List.of(properties.getDenseWeight(), properties.getBm25Weight()));
        }
        // 默认使用 RRF，降低初期权重调参风险。
        return new RRFRanker(properties.getRrfK());
    }

    /**
     * @Description: 构造输出字段列表。
     * @Logic: 不输出向量字段，仅返回上层映射和证据构建需要的文本与 metadata。
     * @Param: 无。
     * @Return: 输出字段列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private List<String> outputFields() {
        // 返回稳定字段顺序，便于诊断。
        return List.of(
                properties.getPrimaryKeyField(),
                properties.getTextField(),
                FIELD_REPORT_ID,
                FIELD_CHUNK_ID,
                FIELD_PARENT_CHUNK_UID,
                FIELD_CHUNK_TYPE,
                FIELD_CHUNK_INDEX,
                FIELD_SECTION_PATH,
                FIELD_TITLE,
                FIELD_SOURCE,
                FIELD_INSTITUTION,
                FIELD_PUBLISH_DATE,
                FIELD_AUTHOR,
                FIELD_NORMALIZED_AUTHOR,
                FIELD_AUTHOR_TEXT,
                FIELD_REPORT_THEME_CODE,
                FIELD_THEME_CODE,
                FIELD_INDUSTRY_CODE,
                FIELD_COMPANY_NAME,
                FIELD_TICKER
        );
    }

    /**
     * @Description: 返回 scalar 索引字段列表。
     * @Logic: 覆盖 hard filter 和常用 soft boost 的候选字段。
     * @Param: 无。
     * @Return: scalar 字段列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private List<String> scalarIndexFields() {
        // scalar inverted index 主要服务 metadata filter。
        return List.of(
                FIELD_REPORT_ID,
                FIELD_CHUNK_TYPE,
                FIELD_TICKER,
                FIELD_COMPANY_NAME,
                FIELD_NORMALIZED_AUTHOR,
                FIELD_AUTHOR_TEXT,
                FIELD_REPORT_THEME_CODE,
                FIELD_THEME_CODE,
                FIELD_INDUSTRY_CODE
        );
    }

    /**
     * @Description: 返回显式清空时需要删除的 collection 名称。
     * @Logic: 包含当前 hybrid collection 和配置的旧 dense-only collection，并去重过滤空名称。
     * @Param: 无。
     * @Return: collection 名称列表。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private List<String> resetCollectionNames() {
        // 使用 LinkedHashSet 去重并保持先删 hybrid、再删 legacy 的稳定顺序。
        Set<String> collectionNames = new LinkedHashSet<>();
        // 添加当前 hybrid collection。
        collectionNames.add(properties.getCollectionName());
        // 添加历史 dense-only collection 配置。
        if (properties.getLegacyCollectionNames() != null) {
            collectionNames.addAll(properties.getLegacyCollectionNames());
        }
        // 过滤空 collection 名称。
        return collectionNames.stream()
                .filter(collectionName -> collectionName != null && !collectionName.isBlank())
                .toList();
    }

    /**
     * @Description: 返回 collection 必须字段名。
     * @Logic: schema 兼容性检查复用该列表，避免误写旧 collection。
     * @Param: 无。
     * @Return: 必须字段名集合。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private Set<String> requiredFieldNames() {
        // required 字段从输出字段开始收集，覆盖所有上层映射依赖的 metadata。
        Set<String> fieldNames = new LinkedHashSet<>(outputFields());
        // dense 字段虽然不输出，但写入和 dense ANN 检索必须存在。
        fieldNames.add(properties.getDenseVectorField());
        // sparse 字段虽然不输出，但 BM25 function 与 sparse search 必须存在。
        fieldNames.add(properties.getSparseVectorField());
        // 返回完整必需字段集合。
        return fieldNames;
    }

    /**
     * @Description: 构造 VarChar 字段。
     * @Logic: 统一设置字段名、类型、长度和主键属性，降低 schema 重复代码。
     * @Param: name 字段名；maxLength 最大长度；primaryKey 是否主键。
     * @Return: 字段 schema。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private CreateCollectionReq.FieldSchema varcharField(String name, int maxLength, boolean primaryKey) {
        // 创建 VarChar 字段 schema。
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.VarChar)
                .maxLength(maxLength)
                .isPrimaryKey(primaryKey)
                .autoID(Boolean.FALSE)
                .build();
    }

    /**
     * @Description: 构造 Int64 字段。
     * @Logic: reportId、chunkId 和 chunkIndex 统一用 Int64，减少 Java Long 到 Milvus 类型转换问题。
     * @Param: name 字段名。
     * @Return: 字段 schema。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private CreateCollectionReq.FieldSchema int64Field(String name) {
        // 创建 Int64 字段 schema。
        return CreateCollectionReq.FieldSchema.builder()
                .name(name)
                .dataType(DataType.Int64)
                .build();
    }

    /**
     * @Description: 转换向量索引类型。
     * @Logic: 配置值不合法时抛出明确异常，避免 Milvus 请求到运行期才难以定位。
     * @Param: value 配置文本。
     * @Return: IndexType 枚举。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private IndexParam.IndexType indexType(String value) {
        // 使用大写枚举名匹配 Milvus SDK。
        return IndexParam.IndexType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * @Description: 转换向量度量类型。
     * @Logic: 配置值不合法时抛出明确异常，避免错误 metric 静默生效。
     * @Param: value 配置文本。
     * @Return: MetricType 枚举。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private IndexParam.MetricType metricType(String value) {
        // 使用大写枚举名匹配 Milvus SDK。
        return IndexParam.MetricType.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * @Description: 将 float 数组转换为 JsonArray。
     * @Logic: Milvus insert row 使用 JSON 表达向量字段。
     * @Param: embedding dense 向量。
     * @Return: JSON 数组。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private JsonArray toJsonArray(float[] embedding) {
        // 初始化向量 JSON 数组。
        JsonArray array = new JsonArray();
        // 按维度顺序写入向量值。
        for (float value : embedding) {
            array.add(value);
        }
        // 返回 JSON 向量。
        return array;
    }

    /**
     * @Description: 标准化 Milvus fused score。
     * @Logic: 小于等于 1 的分数保持原值；大于 1 的分数压缩到 0 到 1，便于诊断横向比较。
     * @Param: score Milvus 返回 fused score。
     * @Return: 归一化分数。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private double normalizeScore(Float score) {
        // 缺失或非正分数按 0 处理。
        if (score == null || score <= 0F) {
            return 0D;
        }
        // 小于等于 1 的分数直接作为相似度诊断。
        if (score <= 1F) {
            return score.doubleValue();
        }
        // 大于 1 的 ranker 分数压缩到 0 到 1 区间。
        return score / (1D + score);
    }

    /**
     * @Description: 读取 metadata 文本字段。
     * @Logic: 缺失字段统一为空字符串，并裁剪首尾空白。
     * @Param: metadata 文档 metadata；key 字段名。
     * @Return: 文本值。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String metadataText(Map<String, Object> metadata, String key) {
        // 从 metadata 读取原始值。
        Object value = metadata.get(key);
        // null 统一转为空字符串。
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * @Description: 读取 metadata 长整型字段。
     * @Logic: Number 直接转 long，字符串尝试解析，失败时回退 0。
     * @Param: metadata 文档 metadata；key 字段名。
     * @Return: long 值。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private long metadataLong(Map<String, Object> metadata, String key) {
        // 从 metadata 读取原始值。
        Object value = metadata.get(key);
        // 数字类型直接转 long。
        if (value instanceof Number number) {
            return number.longValue();
        }
        // 字符串类型尝试解析。
        try {
            return value == null ? 0L : Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /**
     * @Description: 安全文本兜底。
     * @Logic: null 转为空字符串，非空只做 trim。
     * @Param: value 原始文本。
     * @Return: 非 null 文本。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String safeText(String value) {
        // null 值统一为空字符串。
        return value == null ? "" : value.trim();
    }

    /**
     * @Description: 空字符串转 null。
     * @Logic: Milvus AnnSearchReq expr 为空时使用 null，避免提交空表达式。
     * @Param: value 原始表达式。
     * @Return: null 或原表达式。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String blankToNull(String value) {
        // 空白表达式不传给 Milvus。
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @Description: 截断诊断错误信息。
     * @Logic: metadata 中只保留短错误摘要，避免异常堆栈污染召回结果。
     * @Param: message 原始错误消息。
     * @Return: 短错误摘要。
     * @author: cx
     * @Date: 2026-05-31 00:00:00
     */
    private String abbreviate(String message) {
        // 空消息使用固定文本。
        String safeMessage = message == null || message.isBlank() ? "route score diagnostics failed" : message;
        // 错误摘要限制到 256 字符。
        return safeMessage.length() > 256 ? safeMessage.substring(0, 256) : safeMessage;
    }
}
