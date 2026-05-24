## 1. 数据模型与词库基础

- [x] 1.1 设计并新增主题、词条、主题词关系、行业、证券代码和证券别名相关表结构。
- [x] 1.2 设计并新增 `report_chunk_tag` 标签结果表，必要时新增 `report_document_tag` 报告级标签表。
- [x] 1.3 设计并新增 `report_chunk_tag_job`，用于新入库打标、失败重试、历史重打标和词库版本升级后的重算。
- [x] 1.4 设计并新增可选的 `report_vector_metadata_sync_job`，用于 Milvus metadata 与 MySQL 标签主数据最终一致同步。
- [x] 1.5 为词库表、标签表和 job 表补充 MyBatis 实体、Mapper、XML SQL 和关键索引。
- [x] 1.6 准备基础 seed 数据，至少覆盖储能、新能源、电力设备、AI 算力、港口、军工等验证主题。

## 2. 词库加载与匹配

- [x] 2.1 实现 ACTIVE 词库快照加载服务，支持主题、行业、证券和词条关系查询。
- [x] 2.2 实现高效词条匹配结构，避免逐词全量 `contains` 循环。
- [x] 2.3 实现词库刷新失败时保留上一份可用快照的降级逻辑。
- [x] 2.4 增加词库版本号、加载时间和刷新结果的可观测信息。

## 3. 入库标签抽取

- [x] 3.1 新增 chunk 标签抽取服务，基于词库快照抽取 `THEME`、`INDUSTRY`、`COMPANY` 和 `TICKER`。
- [x] 3.2 将标签抽取接入研报入库流程，在 chunk 落库后创建 `report_chunk_tag_job`。
- [x] 3.3 实现 `report_chunk_tag_job` 调度、状态流转、失败重试和错误记录。
- [x] 3.4 在标签抽取 job 成功后写入 `report_chunk_tag`，并记录置信度、来源和词库版本。
- [x] 3.5 提供历史 chunk 重打标入口或定时任务，支持批量创建 `report_chunk_tag_job`。
- [x] 3.6 扩展向量 metadata，写入 `themeCodes`、`industryCodes`、`companyNames`、`tickers` 和现有 `sectionPath`。
- [x] 3.7 实现 `report_vector_metadata_sync_job`，使向量 metadata 与 MySQL 标签主数据最终一致。
- [x] 3.8 在 `report_chunk_tag_job` 成功后创建或触发 `report_vector_metadata_sync_job`。
- [x] 3.9 使用 `tag_snapshot_hash` 或等价机制判断 metadata 是否需要重复同步。

## 4. Query 锚点抽取与混合召回

- [x] 4.1 新增 query 结构化锚点抽取服务，识别主题、行业、公司、代码和章节意图。
- [x] 4.2 将 query 结构化锚点转换为 Milvus metadata scalar filter，并作为在线检索主路径。
- [x] 4.3 实现 Milvus filter 无结果或 metadata 版本滞后时的 MySQL 标签表诊断和 fallback。
- [x] 4.4 标准化 Milvus 返回的 `score` 或 `distance` 为统一的 `relevanceScore`。
- [x] 4.5 基于向量相关性、metadata 命中、sectionPath 和主题覆盖分重排最终 TopK。
- [x] 4.6 明确不使用 `chunkText`/`parentText` 大文本 `contains` 作为正式标量查询路径。

## 5. 主题覆盖与输出降级

- [x] 5.1 在证据质量判断中新增主题覆盖状态和 `LOW_THEME_COVERAGE` 降级原因。
- [x] 5.2 对 `THEME_RESEARCH` query 执行主题覆盖校验，无覆盖时降级为 `L1_INSUFFICIENT_OR_POLLUTED`。
- [x] 5.3 无主题覆盖时禁止输出候选公司、产业链外推和实质主题分析。
- [x] 5.4 支持低相关候选作为诊断信息返回或记录，但默认不作为推荐证据展示。
- [x] 5.5 同步推荐和流式推荐共享主题覆盖、结构化锚点和降级语义。

## 6. 前端与接口展示

- [x] 6.1 扩展同步推荐响应，展示结构化锚点、主题覆盖状态和降级原因。
- [x] 6.2 扩展流式事件数据，携带结构化锚点、主题覆盖状态和低相关诊断信息。
- [x] 6.3 前端区分可支撑结论的证据和被过滤的低相关候选。
- [x] 6.4 前端在 `LOW_THEME_COVERAGE` 时展示“未检索到相关主题研报证据”的降级说明。

## 7. 测试与验证

- [x] 7.1 增加词库加载、词条匹配和版本降级单元测试。
- [x] 7.2 增加标签抽取和标签落库单元测试。
- [x] 7.3 增加混合召回、分数标准化和候选重排测试。
- [x] 7.4 增加储能主题无覆盖回归测试，确保港口、算力、军工候选触发 `LOW_THEME_COVERAGE`。
- [x] 7.5 增加储能主题有覆盖测试，确保相关证据可进入 L2 主题分析。
- [x] 7.6 增加同步推荐和流式推荐行为一致性测试。
- [x] 7.7 执行 `mvn -q test` 并通过。
- [x] 7.8 执行 `openspec validate --all --strict` 并通过。
