package com.example.aimilvusweb.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @Description: Milvus hybrid 检索配置，集中管理 BM25+dense collection、索引和排序参数。
 * @Logic: 通过 app.milvus-hybrid 前缀绑定配置，生产链路使用这些参数初始化 collection、写入向量和执行 hybrid search。
 * @Param: 无。
 * @Return: 无（配置属性载体）。
 * @author: cx
 * @Date: 2026-05-31 00:00:00
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.milvus-hybrid")
public class MilvusHybridProperties {

    /** Milvus hybrid 原生链路开关，关闭时运行期会抛出明确配置错误。 */
    private boolean enabled = true;
    /** Milvus 服务地址，默认读取本地 Docker 暴露端口。 */
    private String host = "localhost";
    /** Milvus gRPC 端口。 */
    private int port = 19530;
    /** Milvus database 名称。 */
    private String databaseName = "default";
    /** 新 hybrid collection 名称。 */
    private String collectionName = "report_chunks_hybrid";
    /** 需要随显式清空动作删除的旧 dense-only collection 名称。 */
    private List<String> legacyCollectionNames = List.of("report_chunks");
    /** dense embedding 维度，需要与 Qwen embedding 配置一致。 */
    private int embeddingDimension = 1024;
    /** dense 向量字段名。 */
    private String denseVectorField = "denseVector";
    /** BM25 sparse 向量字段名。 */
    private String sparseVectorField = "sparseVector";
    /** BM25 输入文本字段名。 */
    private String textField = "chunkText";
    /** Milvus analyzer 类型，默认使用 standard，后续可按中文研报语料调整。 */
    private String textAnalyzerType = "standard";
    /** 主键字段名，使用稳定 chunkUid。 */
    private String primaryKeyField = "chunkUid";
    /** dense 向量索引类型。 */
    private String denseIndexType = "AUTOINDEX";
    /** sparse 向量索引类型。 */
    private String sparseIndexType = "SPARSE_INVERTED_INDEX";
    /** dense 向量度量类型。 */
    private String denseMetricType = "COSINE";
    /** sparse 向量度量类型。 */
    private String sparseMetricType = "BM25";
    /** hybrid ranker 类型，支持 RRF 或 WEIGHTED。 */
    private String rankerType = "RRF";
    /** RRF ranker 的 k 参数。 */
    private int rrfK = 60;
    /** Weighted ranker 下 dense 召回权重。 */
    private float denseWeight = 0.6F;
    /** Weighted ranker 下 BM25 召回权重。 */
    private float bm25Weight = 0.4F;
    /** 是否在写入或检索前自动确保 collection schema 与索引存在。 */
    private boolean autoInitialize = true;
    /** 是否额外执行 dense/sparse 单路检索以回填诊断分数。 */
    private boolean routeScoreDiagnosticsEnabled = true;
    /** metadata filter 策略描述，BALANCED 表示高置信锚点 hard filter、宽泛锚点 soft boost。 */
    private String metadataFilterStrategy = "BALANCED";
    /** 是否启用作者 metadata hard filter。 */
    private boolean authorFilterEnabled = true;
    /** 是否启用唯一股票代码 hard filter。 */
    private boolean uniqueTickerHardFilterEnabled = true;
    /** 是否启用唯一公司名 hard filter。 */
    private boolean uniqueCompanyHardFilterEnabled = true;
    /** 是否将主题和行业锚点下推为 hard filter；默认关闭以降低宽泛 query 漏召回风险。 */
    private boolean themeIndustryHardFilterEnabled = false;
}
