## Context

当前研报推荐检索链路以 Qwen dense embedding 和 Milvus ANN 召回为主，metadata scalar filter 只承担有限约束。该模式对语义近似问题有效，但在股票代码、公司名、研报主题、章节意图、财务指标和专有术语上容易召回偏移。

Milvus 已升级到 2.6.17，具备 analyzer、BM25 function、sparse vector、dense/sparse hybrid search 和 ranker 能力。用户已明确允许删除全部历史导入研报数据，包括 MySQL 中的研报导入域数据和旧 Milvus collection，因此本变更不做旧数据迁移、不做旧 collection 双读，也不保留 dense-only 索引兼容。

## Goals / Non-Goals

**Goals:**

- 将研报导入域数据和旧 Milvus collection 视为可重建资产，提供明确的清空与重建路径。
- 在 MySQL 中保存新导入研报的作者信息，并以 MySQL 作为 Milvus author metadata 的事实来源。
- 建立 Milvus 2.6 原生 BM25+dense hybrid collection schema。
- 继续使用 Qwen embedding 作为 dense 向量来源。
- 将推荐召回升级为 dense ANN + BM25 full-text + hybrid ranker 融合。
- 增强 metadata filter：支持多值 OR，并区分强约束过滤与弱信号加权。
- 保持现有推荐 API、PARENT 上下文聚合、证据护栏和输入可分析性判定语义。

**Non-Goals:**

- 不迁移旧 MySQL 研报导入数据。
- 不迁移或兼容旧 Milvus dense-only collection。
- 不在本期建设完整评测平台。
- 不引入 LLM reranker 作为排序主链路。
- 不重写 OCR、语义切片、Prompt 或推荐响应结构。

## Decisions

1. **破坏式重建导入域数据**
   - 清空范围限定为研报导入与检索索引相关数据，包括报告、OCR 页、段落 atom、chunk、chunk 诊断、导入任务、阶段事件、失败记录、报告/切片标签、标签任务和向量 metadata sync job。
   - 结构化投研词库、taxonomy 主数据、Prompt 和系统配置不属于清空范围。
   - 清空动作必须是显式脚本、管理接口或运维命令，不随应用启动自动执行。

2. **Milvus hybrid 能力使用原生客户端封装**
   - Spring/Qwen embedding 继续负责生成 dense embedding。
   - Milvus collection schema、BM25 function、index、load、insert、delete 和 hybrid search 使用 Milvus 原生客户端封装。
   - 不把 Spring AI `VectorStore` 作为 hybrid collection 的搜索抽象，避免 schema、BM25 function 和 ranker 能力被抽象层限制。

3. **新建 hybrid collection schema**
   - 新导入研报的作者信息必须先保存到 MySQL 研报元数据表或关联表中，作为业务事实来源。
   - collection 以 chunk 为检索粒度，主键使用稳定 chunk id 或 chunk uid。
   - `chunkText` 使用 `VARCHAR` 并启用 analyzer，作为 BM25 输入文本。
   - collection 包含 dense `FLOAT_VECTOR`、BM25 sparse `SPARSE_FLOAT_VECTOR`、BM25 function 和对应索引。
   - collection 包含常用 scalar metadata：report id、chunk uid、parent chunk uid、chunk type、chunk index、title、source、institution、author、publish date、section path、report theme code、theme code、industry code、company name、ticker 等。
   - author 来源于 MySQL 中导入阶段解析或录入的研报作者信息；多作者研报必须在 MySQL 保留结构化表达，并在 Milvus 中投影为可检索的多值表达，便于按作者过滤或加权。

4. **召回与排序链路改为 hybrid pipeline**
   - 推荐 query 先抽取 QueryAnchors。
   - dense embedding search 与 BM25 full-text search 并行进入 Milvus hybrid search。
   - Milvus ranker 初期支持 RRF 或 Weighted ranker，并通过配置控制默认策略和权重。
   - Milvus 返回结果后，应用层执行去重、metadata boost、PARENT 聚合、证据护栏和推荐生成。

5. **过滤策略分层**
   - 强约束过滤只用于确定性条件：`chunkType=CHILD`、指定 report id、唯一匹配的 ticker/company。
   - 作者、主题、行业、研报主题、章节意图和匹配词等信号默认作为 boost 或可配置过滤。
   - metadata filter 必须支持多值 OR，避免单值过滤误杀召回。

6. **分数保留与归一**
   - 检索结果保留 dense score、BM25/sparse score、Milvus fused score 和应用层 normalized score。
   - 推荐生成和主题覆盖校验使用 normalized score 与证据覆盖信息，不直接依赖某一路 raw score。

## Risks / Trade-offs

- **中文 analyzer 质量风险**：BM25 效果依赖分词与字段文本质量。初期需要配置化 analyzer，并通过真实研报 query 观察股票名、简称、代码、指标词和章节词命中情况。
- **Milvus 原生客户端复杂度增加**：绕过 `VectorStore` 会增加 schema 和 hybrid search 代码量。需要用单独 adapter/service 隔离 Milvus API。
- **破坏式清空风险**：清空动作不可逆。必须要求显式触发，并在文档、脚本输出或管理接口中明确影响范围。
- **schema 变更需要重新导入**：hybrid collection schema 一旦调整，历史导入数据需要再次重建。本期接受该代价，因为历史导入数据已允许删除。
- **排序权重需要迭代**：RRF/Weighted 默认值无法一次性保证最优，必须配置化并保留分数诊断字段。

## Migration Plan

1. 确认运行环境使用 Milvus 2.6.17。
2. 停止研报导入任务和推荐检索流量。
3. 可选备份 MySQL 和旧 Milvus collection。
4. 显式执行导入域数据清空，删除旧 Milvus collection。
5. 应用启动或运维命令创建新的 hybrid collection、BM25 function 和索引。
6. 重新上传或重新导入研报数据，并将作者信息保存到 MySQL。
7. 向 Milvus 写入 chunk 时从 MySQL 研报元数据投影 author metadata。
8. 使用代表性 query 验证 dense、BM25、hybrid 融合、metadata filter、PARENT 聚合和证据护栏。

## Open Questions

- 初期中文 analyzer 是否使用 Milvus 默认配置，还是同步维护投研词典、股票简称词典和作者别名词典？
- hybrid ranker 首期默认使用 RRF 还是 Weighted？是否按 query 类型切换？
- 清空导入域数据以脚本形式提供，还是同时提供受保护的管理接口？
- 是否完全禁用 Spring AI Milvus `VectorStore` 自动配置，避免误连旧 collection？
