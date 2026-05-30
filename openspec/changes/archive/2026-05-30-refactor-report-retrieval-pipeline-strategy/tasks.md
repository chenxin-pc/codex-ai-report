## 1. 召回链路边界准备

- [x] 1.1 梳理 `ReportRetrievalService.retrieve` 当前流程，确认 Milvus 召回、metadata filter、分数过滤、去重、重排、PARENT 聚合和 CHILD 扩展的现有行为。
- [x] 1.2 新建 `service/retrieval` 包，确定 Pipeline、策略和 helper 的包内可见边界。
- [x] 1.3 保留 `ReportRetrievalService.retrieve(String query)` 对外入口和现有 `RetrievedChunk` 结果语义。

## 2. 候选处理组件

- [x] 2.1 抽取 `RetrievalCandidateFilter`，保持相关性分数阈值过滤行为不变。
- [x] 2.2 抽取 `RetrievedChunkDeduplicator`，保持 identity key 与 content key 双重去重行为不变。
- [x] 2.3 抽取 `RerankStrategy` 接口、`NoopRerankStrategy` 和 `QueryOverlapRerankStrategy`，保持 `rerankEnabled` 开关行为不变。
- [x] 2.4 为过滤、去重和 query overlap 重排补充单元测试，覆盖顺序稳定性和重复候选场景。

## 3. 证据上下文策略

- [x] 3.1 抽取 `EvidenceContextStrategy` 接口，定义候选列表到最终证据列表的构建边界。
- [x] 3.2 抽取 `ChildExpansionEvidenceContextStrategy`，保持 `parentAggregationEnabled=false` 时逐 CHILD 扩展 PARENT 上下文的行为。
- [x] 3.3 抽取 `ParentAggregationEvidenceContextStrategy`，保持 PARENT 分组、排序、token 预算、截断和 CHILD fallback 行为。
- [x] 3.4 为 PARENT 完整上下文、截断上下文、CHILD_WINDOW、CHILD_FALLBACK 和空 evidenceText 跳过场景补充测试。

## 4. Pipeline 编排

- [x] 4.1 抽取 `RetrievalAnchorExtractor`，保留锚点服务缺失时返回空锚点的兼容行为。
- [x] 4.2 抽取 `RetrievalSearchRequestBuilder`，保留 query、initialTopK 和结构化锚点到 `SearchRequest` 与 metadata scalar filter 的构造逻辑。
- [x] 4.3 抽取 `VectorSearchExecutor`，保留 VectorStore 未配置错误、Milvus 空结果返回空证据和 metadata fallback 诊断行为。
- [x] 4.4 新增 `RetrievalPipeline` 串联锚点抽取、请求构造、向量搜索、候选映射、过滤、去重、重排和证据上下文策略。
- [x] 4.5 将 `ReportRetrievalService.retrieve` 委托给 `RetrievalPipeline`，并保持调用方无感知。

## 5. 回归与验证

- [x] 5.1 调整或补充 `ReportRetrievalServiceTests`，验证外部召回行为、异常语义和 TopK 语义不变。
- [x] 5.2 补充配置组合测试，覆盖 `rerankEnabled` 与 `parentAggregationEnabled` 的四种组合。
- [x] 5.3 确认不修改 `schema.sql`、Controller、前端、推荐请求/响应 DTO 和 Prompt。
- [x] 5.4 执行 `mvn -q test`。
- [x] 5.5 执行 `openspec validate --all --strict`。
