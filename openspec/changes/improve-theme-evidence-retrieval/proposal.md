## Why

当前主题类投研问题只依赖 Milvus 向量召回和较弱的字符重合判断，容易把“储能板块”这类问题召回到港口、算力、军工等泛投研片段，并错误标记为证据质量通过。系统需要在模型生成前用结构化主题、行业、公司、代码和章节锚点验证召回证据是否真正覆盖用户问题，避免无关证据进入 L2/L3 输出。

## What Changes

- 新增可落库、可版本化的结构化词库，覆盖主题、行业、公司、股票代码及主题词关系，支持后续几十万词条维护。
- 新增研报/切片标签结果存储，将入库阶段抽取出的 `THEME`、`INDUSTRY`、`COMPANY`、`TICKER` 等标签落到数据库。
- 新增标签抽取 job 化机制：使用 `report_chunk_tag_job` 管理新入库打标、失败重试、历史重打标和词库版本升级后的重算；使用可选的 `report_vector_metadata_sync_job` 管理 Milvus metadata 与 MySQL 标签主数据的最终一致同步。
- 扩展向量入库 metadata，在现有 `sectionPath` 基础上同步主题、行业、公司和代码标签摘要，供 Milvus 过滤、召回后加权和前端解释使用。
- 新增 query 结构化锚点抽取，识别用户问题中的主题、行业、公司、代码和章节意图。
- 推荐检索升级为 `Milvus ANN + metadata scalar filter` 的在线主路径；MySQL 标签表作为标签主数据、metadata 同步来源、诊断和 fallback，不使用 `chunkText`/`parentText` 的大文本 `contains` 作为正式标量查询路径。
- 新增主题覆盖判断：主题类 query 若召回证据未覆盖对应主题、行业或结构化锚点，必须标记 `LOW_THEME_COVERAGE` 并降级为 L1。
- 同步推荐和流式推荐必须共享相同的主题覆盖、结构化标签和输出降级语义。

## Capabilities

### New Capabilities

- `structured-research-taxonomy`: 定义主题、行业、公司、代码词库及标签结果的落库、版本化、加载和查询语义。

### Modified Capabilities

- `report-retrieval-quality`: 推荐检索从纯向量召回升级为向量召回与结构化锚点融合，并要求主题类 query 在生成前通过主题覆盖校验。
- `report-recommendation-streaming`: 流式推荐需要暴露主题覆盖、结构化锚点和降级原因，且无主题覆盖时不得进入实质主题分析输出。

## Impact

- 影响数据库 schema：新增词库表、词条关系表、切片标签表、标签抽取 job 表，可选新增报告级标签表和 vector metadata 同步 job 表；需要索引支持几十万词条和高频标签查询。
- 影响入库链路：OCR/切片后创建 `report_chunk_tag_job` 执行异步标签抽取与落库，并在向量入库或后续 metadata sync job 中写入标签摘要。
- 影响检索链路：`ReportRetrievalService` 需要支持 query 锚点到 Milvus metadata filter 的转换、向量召回、重排和证据覆盖判断；MySQL 标签表用于 metadata 不可用或不同步时的诊断与 fallback。
- 影响推荐护栏：`RecommendationEvidenceGuardrailService` 需要把主题覆盖纳入 evidence quality，并在失败时返回 `LOW_THEME_COVERAGE`。
- 影响接口响应和前端展示：同步与流式推荐需要展示主题覆盖状态、结构化锚点、降级原因和被过滤的低相关候选信息。
- 依赖现有 MySQL、Milvus、Qwen embedding 和 Spring AI；不引入新的外部搜索引擎，不把 MySQL 大文本 `LIKE` 作为正式检索能力。
