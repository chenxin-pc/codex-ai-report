# report-retrieval-quality Specification

## Purpose
TBD - created by archiving change optimize-report-ingest-retrieval-quality. Update Purpose after archive.
## Requirements
### Requirement: 向量检索 MUST 使用 Qwen embedding 和 Milvus ANN

系统 MUST 使用 `application.yml` 中配置的 Qwen embedding 模型生成向量，并使用 Milvus ANN 搜索执行相似性召回。系统 MUST 不使用绕过 Milvus 的内存相似度搜索替代主检索链路。

#### Scenario: 执行推荐搜索

- **GIVEN** 系统已配置 Qwen embedding 模型和 Milvus VectorStore
- **WHEN** 用户提交推荐 query
- **THEN** 系统 MUST 使用 Milvus ANN 执行向量召回
- **AND** 系统 MUST 基于召回证据生成推荐结果

#### Scenario: Milvus 未配置

- **GIVEN** 系统未配置可用的 Milvus VectorStore
- **WHEN** 用户提交推荐 query
- **THEN** 系统 MUST 返回明确的 VectorStore 未配置错误
- **AND** 系统 MUST 不伪造搜索结果

### Requirement: 检索文本 MUST 优先使用 CHILD chunk

系统 MUST 优先将 CHILD chunk 写入 Milvus 作为检索文档，PARENT chunk MUST 作为上下文扩展来源。写入 Milvus 的 metadata MUST 包含 reportId、chunkUid、parentChunkUid、chunkType、chunkIndex、sectionPath、tokenCount、title、source、institution 和 publishDate。

#### Scenario: CHILD chunk 入向量库

- **GIVEN** 一篇研报已生成 PARENT 和 CHILD chunk
- **WHEN** 系统执行向量入库
- **THEN** 系统 MUST 仅将符合质量要求的 CHILD chunk 写入 Milvus
- **AND** 每个向量文档 MUST 包含可追溯 metadata

#### Scenario: 推荐时扩展 PARENT 上下文

- **GIVEN** Milvus 返回的 CHILD chunk metadata 包含 parentChunkUid
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 查询对应 PARENT chunk 作为上下文扩展
- **AND** 系统 MUST 在上下文过长时按 token 上限截断

### Requirement: 搜索结果 MUST 支持初召回和最终结果分离

系统 MUST 支持配置初召回 topK 和最终返回 topK。初召回数量 MUST 可以大于最终返回数量，以便去重、阈值过滤和可选重排。

#### Scenario: 初召回大于最终结果数

- **GIVEN** 配置的初召回 topK 为 20
- **AND** 配置的最终返回 topK 为 5
- **WHEN** 系统执行搜索
- **THEN** 系统 MUST 从 Milvus 获取最多 20 个候选
- **AND** 系统 MUST 经过去重、过滤或重排后返回最多 5 个最终结果

#### Scenario: 候选结果不足

- **GIVEN** 配置的最终返回 topK 为 5
- **WHEN** Milvus 仅返回 3 个候选结果
- **THEN** 系统 MUST 返回这 3 个可用结果
- **AND** 推荐输出 MUST 基于实际可用证据说明不确定性

### Requirement: 推荐输出 MUST 严格基于召回证据

系统 MUST 将召回 chunk 和扩展上下文作为推荐生成的唯一事实依据。同步结构化推荐和流式自然语言推荐均 MUST 不编造报告中不存在的结论、数字或投资建议。证据不足时，推荐 MUST 明确提示不确定性。

#### Scenario: 证据充分时生成同步建议

- **GIVEN** 检索结果包含与 query 高相关的多个 chunk
- **WHEN** 系统调用 Qwen 生成同步推荐
- **THEN** 推荐输出 MUST 包含 analysis、recommendation、risks 和 citations
- **AND** citations MUST 能对应到召回证据

#### Scenario: 证据充分时生成流式建议

- **GIVEN** 检索结果包含与 query 高相关的多个 chunk
- **WHEN** 系统调用 Qwen 生成流式推荐
- **THEN** 流式正文 MUST 仅基于召回证据进行分析
- **AND** 流式正文 MUST 引用或指向可识别的 Chunk 编号、标题或证据来源

#### Scenario: 证据不足时提示不确定性

- **GIVEN** 搜索结果为空或召回 chunk 与 query 相关性不足
- **WHEN** 系统生成推荐输出
- **THEN** recommendation 或流式正文 MUST 明确说明证据不足
- **AND** risks 或流式正文 MUST 包含不确定性提示

### Requirement: 搜索质量优化参数 MUST 可配置

系统 MUST 通过配置管理 OCR 渲染参数、切片 token 参数、搜索初召回 topK、最终 topK、相似度阈值和重排开关。共享默认值 MUST 放在 `application.yml`，profile 文件 MUST 只覆盖环境差异。

#### Scenario: 使用默认搜索质量配置

- **GIVEN** 环境 profile 未覆盖搜索质量参数
- **WHEN** 系统启动
- **THEN** 系统 MUST 使用 `application.yml` 中的默认配置

#### Scenario: 使用环境变量覆盖配置

- **GIVEN** 部署环境通过环境变量设置搜索 topK
- **WHEN** 系统启动
- **THEN** 系统 MUST 使用环境变量值覆盖默认配置
- **AND** 系统 MUST 不在业务代码中硬编码 endpoint、model、key 或 topK 参数

### Requirement: 推荐检索 MUST 抽取结构化 query 锚点

系统 MUST 在推荐召回前从用户 query 中抽取结构化锚点。结构化锚点 MUST 至少支持主题、行业、公司、股票代码和章节意图。结构化锚点用于构建 Milvus metadata scalar filter、向量候选重排和证据覆盖判断。

#### Scenario: 抽取主题锚点

- **GIVEN** 用户提交“哪些研报看好储能板块，核心逻辑和风险是什么？”
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 抽取主题锚点 `STORAGE`
- **AND** 系统 MUST 将该锚点用于 Milvus metadata filter 和主题覆盖判断

#### Scenario: 抽取代码锚点

- **GIVEN** 用户提交“分析 300750.SZ 的风险”
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 抽取股票代码锚点 `300750.SZ`
- **AND** 系统 MUST 优先使用该代码约束或加权召回证据

#### Scenario: 无结构化锚点

- **GIVEN** 用户提交“风险是什么？”
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 标记 query 缺少可检索结构化锚点
- **AND** 系统 MUST 按输入可分析性规则返回引导或降级，不得把随机召回包装成可靠证据

### Requirement: 推荐检索 MUST 使用 Milvus metadata scalar filter

系统 MUST 继续使用 Milvus ANN 作为主向量召回链路，并 MUST 优先将结构化 query 锚点转换为 Milvus metadata scalar filter。MySQL 标签表 MUST 作为标签主数据、metadata 同步来源、诊断和 fallback，而不是在线推荐检索的必经路径。

#### Scenario: 使用主题 metadata filter

- **GIVEN** 用户 query 抽取到 `STORAGE` 主题锚点
- **AND** Milvus metadata 已同步 `themeCodes`
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 构造包含 `STORAGE` 的 metadata filter
- **AND** 系统 MUST 在满足 filter 的向量候选中执行 ANN 召回或排序

#### Scenario: Milvus filter 无结果时诊断

- **GIVEN** 用户 query 抽取到 `STORAGE` 主题锚点
- **AND** Milvus metadata filter 返回 0 个候选
- **WHEN** 系统需要判断无结果原因
- **THEN** 系统 MAY 查询 MySQL 标签表进行诊断或 fallback
- **AND** 系统 MUST NOT 绕过主题覆盖校验直接生成主题分析

#### Scenario: metadata 不同步时以 MySQL 主数据诊断

- **GIVEN** MySQL 标签表存在 `STORAGE` 标签
- **AND** Milvus metadata 缺少对应 `themeCodes`
- **WHEN** 系统执行检索诊断
- **THEN** 系统 MUST 能识别 metadata 同步滞后
- **AND** 系统 MUST 将该情况作为同步问题处理，而不是判定词库主数据不存在

#### Scenario: 不使用大文本 contains 作为正式标量查询

- **GIVEN** 用户 query 包含“储能系统”
- **WHEN** 系统执行结构化标量查询
- **THEN** 系统 MUST 使用 Milvus metadata 中的主题、行业、公司、代码和 sectionPath 等结构化字段
- **AND** 系统 MUST NOT 依赖 `chunkText LIKE '%储能系统%'` 或 `parentText LIKE '%储能系统%'` 作为正式标量检索路径

### Requirement: 主题类 query MUST 在生成前通过主题覆盖校验

系统 MUST 对 `THEME_RESEARCH` query 执行主题覆盖校验。若 query 抽取出的主题、行业或结构化锚点未被召回证据覆盖，系统 MUST 标记 `LOW_THEME_COVERAGE` 并降级为 L1。

#### Scenario: 储能主题无覆盖时降级

- **GIVEN** 用户提交“哪些研报看好储能板块，核心逻辑和风险是什么？”
- **AND** 召回 TopK 证据均未命中 `STORAGE` 主题标签或相关行业标签
- **WHEN** 系统执行证据质量校验
- **THEN** 系统 MUST 标记 `themeCovered=false`
- **AND** 系统 MUST 将 `LOW_THEME_COVERAGE` 加入降级原因
- **AND** 系统 MUST 将输出等级降为 `L1_INSUFFICIENT_OR_POLLUTED`

#### Scenario: 储能主题覆盖时允许主题分析

- **GIVEN** 用户提交“哪些研报看好储能板块，核心逻辑和风险是什么？”
- **AND** 召回 TopK 至少包含一个 `STORAGE` 主题标签命中的证据
- **WHEN** 系统执行证据质量校验
- **THEN** 系统 MUST 标记 `themeCovered=true`
- **AND** 系统 MAY 在其他证据质量条件通过时输出 L2 主题分析

#### Scenario: 相关行业弱覆盖不足以单独通过

- **GIVEN** 用户 query 抽取到 `STORAGE` 主题
- **AND** 召回证据只命中宽泛行业 `NEW_ENERGY`
- **AND** 未命中储能主题或强相关主题标签
- **WHEN** 系统执行主题覆盖校验
- **THEN** 系统 MUST 计算为低覆盖或弱覆盖
- **AND** 系统 MUST 在覆盖分低于阈值时降级为 L1

### Requirement: 检索相关性分数 MUST 标准化

系统 MUST 将 Milvus 或 Spring AI 返回的 `score`、`distance` 等原始分数字段标准化为统一的 `relevanceScore`。后续过滤、重排和证据质量判断 MUST 使用标准化后的相关性分数，避免距离和相似度方向混淆。

#### Scenario: 使用 score 字段

- **GIVEN** Milvus 返回 metadata 中的 `score`
- **WHEN** 系统构建检索候选
- **THEN** 系统 MUST 记录原始分数字段
- **AND** 系统 MUST 转换为越大越相关的 `relevanceScore`

#### Scenario: 使用 distance 字段

- **GIVEN** Milvus 返回 metadata 中的 `distance`
- **WHEN** 系统构建检索候选
- **THEN** 系统 MUST 识别 distance 的排序方向
- **AND** 系统 MUST 转换为统一的 `relevanceScore`

