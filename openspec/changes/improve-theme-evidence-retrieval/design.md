## Context

当前推荐链路已经具备投研输入判定、Milvus ANN 召回、父 chunk 上下文扩展和输出降级能力，但主题类 query 的证据相关性仍主要依赖向量召回和较弱的字符重合判断。实际测试中，“哪些研报看好储能板块，核心逻辑和风险是什么？”被识别为 `THEME_RESEARCH` 后召回到了港口、算力、军工等泛投研证据，证据质量却仍显示通过。

当前系统已有 `sectionPath`、`title`、`source`、`institution`、`publishDate` 等字段，并且 `sectionPath` 已保存到 MySQL 与 Milvus metadata。缺口在于主题、行业、公司、代码尚未结构化落库，也没有用于查询和证据覆盖判断的标签体系。

## Goals / Non-Goals

**Goals:**

- 建立可落库、可版本化、可扩展到几十万词条的投研结构化词库。
- 在入库阶段为 report/chunk 生成主题、行业、公司、代码等结构化标签。
- 在向量 metadata 中同步结构化标签摘要和 `sectionPath`，支持在线检索过滤、加权和解释。
- 在检索阶段引入 query 结构化锚点抽取，并优先使用 Milvus metadata scalar filter 约束 ANN 召回。
- 在生成前执行主题覆盖判断，主题类 query 无覆盖时降级为 L1 并返回 `LOW_THEME_COVERAGE`。
- 同步推荐和流式推荐共享相同的结构化锚点、证据覆盖和降级语义。

**Non-Goals:**

- 不引入 Elasticsearch、OpenSearch 或新的外部检索引擎。
- 不把 `chunkText contains ...` 或 `parentText contains ...` 作为正式标量查询路径。
- 不让 Milvus 承担词库主存储；Milvus 只保存向量和查询用 metadata 摘要。
- 不在没有主题覆盖的情况下让 Prompt 自行判断并生成实质主题分析。

## Decisions

### 1. 词库必须落库，YAML 仅作为 seed 或测试数据

考虑后续可能维护几十万词条，正式词库使用 MySQL 表保存。YAML 文件只用于初始化 seed、本地开发或测试样例，不作为运行时主存储。

核心表分为概念、词条和关系：

- `theme_dictionary`: 主题概念，例如 `STORAGE`、`AI_COMPUTE`。
- `theme_term`: 词条，例如“新型储能”“储能PCS”。
- `theme_term_relation`: 主题与词条关系，区分 `REQUIRED`、`ALIAS`、`EXCLUDE` 等。
- `industry_dictionary`: 行业概念和层级。
- `security_dictionary` / `security_alias`: 公司、股票代码、交易所和别名。

这样可以避免把几十万词条塞进配置文件，也能支持版本、审核、回滚和审计。

### 2. 标签结果与词库分离

词库是规则来源，标签是规则运行后的结果。入库阶段应把抽取结果写入标签表：

- `report_chunk_tag`: chunk 级标签，包含 `THEME`、`INDUSTRY`、`COMPANY`、`TICKER`。
- `report_document_tag`: 可选报告级聚合标签，用于报告列表筛选和快速诊断。

标签字段包含 `tag_type`、`tag_code`、`tag_name`、`confidence`、`source`、`dictionary_version`。这样可以解释“某条证据为什么被认为属于储能”，也能在词库版本变化后重新打标或回滚。

### 3. 标签抽取和 Milvus metadata 同步采用 job 化

标签抽取不应强绑定在请求线程或单次入库同步流程中。新增 `report_chunk_tag_job` 作为 chunk 标签抽取的执行队列和状态账本，覆盖四类场景：

- 新 chunk 入库后的首次打标。
- 抽取失败后的重试。
- 历史数据批量重打标。
- 词库 ACTIVE 版本变化后的标签重算。

`report_chunk_tag_job` 至少记录 `chunk_uid`、`report_id`、`dictionary_version`、`status`、`attempt_count`、`last_error`、`started_at`、`finished_at`。状态建议包含 `PENDING`、`PROCESSING`、`SUCCEEDED`、`FAILED`、`SKIPPED`。标签抽取成功后写入 `report_chunk_tag`，并记录标签来源、置信度和词库版本。

Milvus metadata 同步可以作为独立 job：`report_vector_metadata_sync_job`。它以 MySQL 标签表为主数据来源，将 chunk 的 `themeCodes`、`industryCodes`、`companyNames`、`tickers` 和 `sectionPath` 同步到 Milvus metadata。若 Milvus 当前能力支持 metadata 原地更新，则执行 metadata update；若不支持，则通过删除重建、重新 upsert 或批量重建向量实现最终一致。

`report_vector_metadata_sync_job` 至少记录 `chunk_uid`、`report_id`、`metadata_version`、`tag_snapshot_hash`、`status`、`attempt_count`、`last_error`。`tag_snapshot_hash` 用于判断 MySQL 标签主数据与 Milvus metadata 是否一致，避免重复同步。

标签抽取 job 与 metadata sync job 的关系如下：

```text
chunk 入库 / 历史重打标 / 词库版本升级
        │
        ▼
report_chunk_tag_job
        │ 成功写入 report_chunk_tag
        ▼
report_vector_metadata_sync_job
        │ 成功更新 Milvus metadata
        ▼
Milvus ANN + metadata scalar filter 可用
```

这样可以把“标签主数据生成”和“向量检索索引同步”解耦。即使 metadata 同步失败，MySQL 标签表仍保持正确，系统可以继续诊断、重试或触发重建，不应把失败的 Milvus metadata 当作标签真相。

### 4. 查询阶段只使用结构化锚点，不扫描大文本

正式标量查询限定为主题、行业、公司、代码和 `sectionPath`。不对 `chunkText`、`parentText` 做大文本 `LIKE` 或 `contains` 查询。

原因：

- 大文本 contains 性能不可控，且容易演变成不可维护的伪全文搜索。
- 主题、行业、公司、代码应在入库阶段完成结构化抽取。
- 查询阶段只消费结构化标签和向量召回结果，职责边界更清晰。

### 5. Milvus metadata 是在线检索索引，MySQL 标签表是主数据

向量文档 metadata 继续包含现有字段，并新增标签摘要：

- `themeCodes`
- `industryCodes`
- `companyNames` 或 `companyCodes`
- `tickers`
- `sectionPath`

在线推荐检索优先把 query 结构化锚点转换为 Milvus metadata filter，并在过滤后的候选中执行 ANN 召回。MySQL 词库和标签表仍是主数据，用于审计、重打标、metadata 重建、同步校验、诊断和 fallback。

若 Milvus/Spring AI 对数组过滤支持稳定，可用数组或 JSON 存储多值标签；否则需要设计可过滤的标量字段或编码策略。无论采用哪种 metadata 形态，推荐正确性必须能在 MySQL 标签表中追溯到来源。

### 6. 在线检索优先使用 Milvus ANN + metadata scalar filter

在线主路径：

1. 从 query 抽取结构化锚点。
2. 将主题、行业、公司、代码和 `sectionPath` 意图转换为 Milvus metadata filter 或过滤/加权条件。
3. Milvus 执行 ANN + scalar filter，返回 `initialTopK` 候选。
4. 基于向量分、metadata 命中、`sectionPath` 匹配和主题覆盖分重排。
5. 生成最终 TopK。

当 Milvus metadata filter 返回 0 或 metadata 版本落后时，系统可以查询 MySQL 标签表进行诊断和 fallback：判断是库内确实没有相关主题证据，还是 metadata 同步滞后。fallback 结果不得绕过主题覆盖校验。

### 7. 主题覆盖是生成前硬闸门

对 `THEME_RESEARCH` query，系统必须计算主题覆盖结果。若 query 识别出 `STORAGE`，但 TopK 证据没有 `STORAGE` 主题标签，也没有足够相关行业/章节锚点，则：

- `evidenceQuality.themeCovered = false`
- `issues += LOW_THEME_COVERAGE`
- `outputLevel = L1_INSUFFICIENT_OR_POLLUTED`
- 不进入实质主题分析生成，不输出候选公司，不做产业链外推。

主题覆盖可以用加权分数实现，但对外语义必须可解释。

### 8. 召回分数需要标准化后再参与融合

当前代码同时读取 `score` 和 `distance`，但未统一“越大越相关”或“越小越相关”的语义。检索融合前应转换为统一的 `relevanceScore`，并记录原始字段和来源，避免阈值方向错误。

## Risks / Trade-offs

- 标签抽取初期不准 → Milvus filter 返回 0 时使用 MySQL 标签表诊断是否存在主数据标签，并保留低相关候选用于调试；不得把无主题覆盖候选直接作为证据。
- 词库规模大导致启动慢 → 使用词库版本快照、本地内存索引和增量刷新；必要时将构建过程异步化。
- 词库变更影响历史标签 → 标签记录 `dictionary_version`，支持重打标任务和版本回滚。
- 标签抽取成功但 metadata 同步失败 → MySQL 标签表仍作为主数据，`report_vector_metadata_sync_job` 负责重试和诊断，不允许直接相信过期 Milvus metadata。
- Milvus metadata filter 支持受限 → 需要先确认数组、JSON 或字符串编码过滤能力；若不稳定，仍以 MySQL 标签表作为主数据与 fallback，但在线检索目标形态保持 metadata filter。
- 主题词过宽导致误命中 → 使用 `REQUIRED`、`ALIAS`、`EXCLUDE` 和权重关系，并在覆盖分里惩罚排除词。
- Metadata 过滤过窄导致漏召回 → 保留诊断召回和 MySQL 标签表 fallback，但输出必须通过主题覆盖校验。

## Migration Plan

1. 新增词库和标签表，保留现有 report/chunk 表结构不破坏现有入库数据。
2. 提供基础 seed 数据，至少覆盖储能、新能源、电力设备、AI 算力、港口、军工等测试主题。
3. 新增 `report_chunk_tag_job`，先对新入库数据异步打标签。
4. 提供历史数据重打标入口，通过批量创建 `report_chunk_tag_job` 将已有 chunk 补充 `report_chunk_tag`。
5. 扩展向量入库 metadata；新增 `report_vector_metadata_sync_job`，历史向量可通过重建向量或补充 metadata 的方式迁移。
6. 标签抽取成功后创建或触发 metadata sync job，使 Milvus 标签摘要与 MySQL 标签主数据最终一致。
7. 检索启用 Milvus metadata filter 与主题覆盖降级，并保留 MySQL 标签表诊断/fallback。
8. 验证储能 case、港口行业 case、明确代码 case 后，再评估 filter 粒度和 fallback 策略。

## Open Questions

- Milvus 当前版本与 Spring AI metadata filter 对数组、JSON 和字符串包含的支持边界是什么？
- 是否需要第一版就支持词库后台维护，还是先通过 SQL/seed 管理 ACTIVE 词库？
- 公司/代码词库是否接入外部证券基础资料，还是先维护本地小型 `security_dictionary`？
- 历史数据重打标和 metadata 同步是否需要前端观测页展示 job 状态？
