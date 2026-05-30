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

系统 MUST 继续使用 Milvus ANN 作为主向量召回链路，并 MUST 优先将报告级父标签和结构化 query 锚点转换为 Milvus metadata scalar filter。报告级父标签 MUST 作为报告集合约束参与查询；query 锚点 MUST 用于在受限报告集合内约束或加权召回证据。MySQL 标签表 MUST 作为标签主数据、metadata 同步来源、诊断和 fallback，而不是在线推荐检索的必经路径。

#### Scenario: 使用主题 metadata filter

- **GIVEN** 用户 query 抽取到 `STORAGE` 主题锚点
- **AND** Milvus metadata 已同步 `themeCodes`
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 构造包含 `STORAGE` 的 metadata filter
- **AND** 系统 MUST 在满足 filter 的向量候选中执行 ANN 召回或排序

#### Scenario: 使用报告级父标签约束查询

- **GIVEN** 查询上下文需要限定父标签 `THEME=STORAGE`
- **AND** Milvus metadata 已同步报告级父标签字段
- **WHEN** 系统执行推荐检索
- **THEN** 系统 MUST 将报告级父标签作为 metadata filter 强约束
- **AND** 系统 MUST 只在属于该父标签报告集合的向量候选中执行 ANN 召回或排序

#### Scenario: 父标签与 query 锚点组合过滤

- **GIVEN** 查询上下文需要限定报告级父标签 `THEME=STORAGE`
- **AND** 用户 query 抽取到行业锚点 `POWER_EQUIPMENT`
- **WHEN** 系统构造 metadata filter
- **THEN** 系统 MUST 将报告级父标签约束与 query 锚点约束组合
- **AND** 报告级父标签 MUST 不被 query 锚点的 OR 条件绕过

#### Scenario: Milvus filter 无结果时诊断

- **GIVEN** 用户 query 抽取到 `STORAGE` 主题锚点
- **AND** Milvus metadata filter 返回 0 个候选
- **WHEN** 系统需要判断无结果原因
- **THEN** 系统 MAY 查询 MySQL 标签表进行诊断或 fallback
- **AND** 系统 MUST NOT 绕过主题覆盖校验直接生成主题分析

#### Scenario: 父标签 metadata 不同步时以 MySQL 主数据诊断

- **GIVEN** MySQL `report_document_tag` 存在父标签 `THEME=STORAGE`
- **AND** Milvus metadata 缺少对应报告级父标签字段
- **WHEN** 系统执行检索诊断
- **THEN** 系统 MUST 能识别 metadata 同步滞后
- **AND** 系统 MUST 将该情况作为同步问题处理，而不是判定父标签主数据不存在

#### Scenario: metadata 不同步时以 MySQL 主数据诊断

- **GIVEN** MySQL 标签表存在 `STORAGE` 标签
- **AND** Milvus metadata 缺少对应 `themeCodes`
- **WHEN** 系统执行检索诊断
- **THEN** 系统 MUST 能识别 metadata 同步滞后
- **AND** 系统 MUST 将该情况作为同步问题处理，而不是判定词库主数据不存在

#### Scenario: 不使用大文本 contains 作为正式标量查询

- **GIVEN** 用户 query 包含“储能系统”
- **WHEN** 系统执行结构化标量查询
- **THEN** 系统 MUST 使用 Milvus metadata 中的报告级父标签、主题、行业、公司、代码和 sectionPath 等结构化字段
- **AND** 系统 MUST NOT 依赖 `chunkText LIKE '%储能系统%'` 或 `parentText LIKE '%储能系统%'` 作为正式标量检索路径

### Requirement: 主题类 query MUST 在生成前通过主题覆盖校验

系统 MUST 对 `THEME_RESEARCH` query 执行主题覆盖校验。若 query 抽取出的主题、行业或结构化锚点未被召回证据覆盖，系统 MUST 标记 `LOW_THEME_COVERAGE` 并降级为 L1。报告级父标签 MAY 证明证据来自正确报告集合，但 MUST NOT 单独替代 chunk 级主题覆盖证据。

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

#### Scenario: 父标签不能单独通过主题覆盖

- **GIVEN** 用户 query 抽取到 `STORAGE` 主题
- **AND** 召回证据所属报告的父标签为 `STORAGE`
- **AND** 召回 chunk 本身没有命中 `STORAGE` 主题标签、强相关行业标签或可解释主题证据
- **WHEN** 系统执行主题覆盖校验
- **THEN** 系统 MUST NOT 仅因报告级父标签命中而判定 `themeCovered=true`
- **AND** 系统 MUST 按证据覆盖不足处理

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

### Requirement: 推荐检索 MUST 遵守投研输入可分析性判定

系统 MUST 在执行 Milvus ANN 召回前检查投研输入可分析性。不可分析输入 MUST 短路返回投研模式引导，不得执行向量召回；直接分析和主题分析输入 MAY 进入召回链路。

#### Scenario: 不可分析输入不执行召回

- **GIVEN** 用户提交“你好”作为推荐 query
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MUST 不调用 Milvus ANN 搜索
- **AND** 系统 MUST 返回投研模式引导响应

#### Scenario: 主题分析输入允许召回

- **GIVEN** 用户提交“分析港口行业近期景气度”
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MAY 调用 Milvus ANN 搜索
- **AND** 系统 MAY 基于召回证据输出相关公司候选
- **AND** 系统 MUST 将后续输出限制为主题分析、相关公司候选或证据不足提示

#### Scenario: LLM 兜底分类失败不执行召回

- **GIVEN** 本地规则无法高置信判定用户输入
- **AND** LLM 兜底分类失败或低置信
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MUST 不调用 Milvus ANN 搜索
- **AND** 系统 MUST 返回投研模式引导响应

### Requirement: 推荐证据 MUST 在生成前执行证据质量校验

系统 MUST 在把召回证据交给模型生成前检查证据质量。证据质量校验 MUST 至少覆盖证据存在性、query 相关性、主体一致性、数据一致性和引用完整性。若主体一致性或引用完整性失败，系统 MUST 降级输出，不得生成完整投研结论。

#### Scenario: 无证据降级

- **GIVEN** Milvus 未返回可用召回结果
- **OR** 召回结果缺少有效 chunkText、evidenceText 或 citation
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 标记证据存在性失败
- **AND** 系统 MUST 降级为信息不足输出

#### Scenario: 低相关召回降级

- **GIVEN** 召回结果与 query 的向量分数、标题、sectionPath、关键词或实体重合度低于配置阈值
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 标记 query 相关性不足
- **AND** 系统 MUST 降级为低相关或主题分析输出

#### Scenario: 多标的混杂证据降级

- **GIVEN** 召回结果同时包含北部湾港、医药研发项目和药房经营趋势等不一致主体
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 标记主体一致性失败
- **AND** 系统 MUST 降级为信息污染输出
- **AND** 系统 MUST 不生成单一公司投资建议

#### Scenario: 代码和交易所冲突降级

- **GIVEN** 召回证据中的公司代码、交易所或行业属性相互冲突
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 标记硬冲突
- **AND** 系统 MUST 禁止估值、盈利预测和投资建议输出

#### Scenario: 数据指标错配降级

- **GIVEN** 召回证据中的股价、市值、PE/PB、财报期、报告日期、预测年份或行业分类无法归属到同一主体和可解释时点
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 标记数据一致性失败
- **AND** 系统 MUST 禁止目标价、估值和盈利预测输出

#### Scenario: 引用跨主体降级

- **GIVEN** 推荐结论引用的 chunk 无法对应有效召回证据
- **OR** citation 使用 A 公司证据支撑 B 公司结论
- **WHEN** 系统构建推荐证据
- **THEN** 系统 MUST 标记引用完整性失败
- **AND** 系统 MUST 降级为无证据或交叉污染输出

#### Scenario: 证据一致时允许完整生成

- **GIVEN** 召回证据均指向同一上市公司主体
- **AND** citations 能对应到有效召回证据
- **AND** 证据相关性和数据一致性通过
- **WHEN** 系统构建推荐上下文
- **THEN** 系统 MAY 将证据交给模型生成完整投研响应

### Requirement: 推荐检索 MUST 按 PARENT 聚合生成上下文

系统 MUST 继续使用 CHILD chunk 作为 Milvus ANN 主召回单元，但在构建研报总结或推荐理由生成上下文时，MUST 按 `parentChunkUid` 将命中的 CHILD 聚合为 PARENT 证据组。同一 PARENT 在生成上下文中 MUST 最多出现一次，且系统 MUST 保留该 PARENT 下所有命中 CHILD 的明细用于引用、展示和诊断。

#### Scenario: 多个命中 CHILD 属于同一 PARENT

- **GIVEN** Milvus 返回多个 CHILD 召回结果
- **AND** 其中至少两个 CHILD 具有相同 `parentChunkUid`
- **WHEN** 系统构建推荐理由生成上下文
- **THEN** 系统 MUST 将这些 CHILD 聚合到同一个 PARENT 证据组
- **AND** 该 PARENT 上下文 MUST 最多进入生成上下文一次
- **AND** 系统 MUST 保留聚合组内的命中 CHILD 列表、分数和 chunkUid

#### Scenario: 命中 CHILD 缺少 parentChunkUid

- **GIVEN** Milvus 返回的 CHILD 候选缺少 `parentChunkUid`
- **WHEN** 系统构建推荐理由生成上下文
- **THEN** 系统 MUST 使用该 CHILD 文本作为兜底证据上下文
- **AND** 系统 MUST 不因缺少 PARENT 而丢弃该候选
- **AND** 系统 MUST 标记该证据未完成 PARENT 聚合

### Requirement: PARENT 证据组 MUST 按相关性和覆盖度排序

系统 MUST 对 PARENT 证据组进行稳定排序，以支持研报总结和推荐理由生成。排序 MUST 至少考虑命中 CHILD 数量、最高相关性分数、平均相关性分数和原始召回顺序。系统 MUST 优先选择覆盖度更高且相关性更强的 PARENT 证据组进入生成上下文。

#### Scenario: 同一 PARENT 命中多个 CHILD

- **GIVEN** PARENT A 命中 3 个 CHILD
- **AND** PARENT B 命中 1 个 CHILD
- **AND** 两个 PARENT 的最高相关性分数接近
- **WHEN** 系统排序 PARENT 证据组
- **THEN** PARENT A MUST 优先于 PARENT B
- **AND** 排序结果 MUST 可通过命中数量和相关性分数解释

#### Scenario: 命中数量相同但分数不同

- **GIVEN** PARENT A 和 PARENT B 命中 CHILD 数量相同
- **AND** PARENT A 的最高相关性分数高于 PARENT B
- **WHEN** 系统排序 PARENT 证据组
- **THEN** PARENT A MUST 优先于 PARENT B

### Requirement: 推荐生成上下文 MUST 受总 token 预算控制

系统 MUST 支持推荐 evidence context 的总 token 预算、单 PARENT token 上限和最大 PARENT 数量配置。系统构建生成上下文时 MUST 同时遵守这些预算，不得仅依赖单条 PARENT 截断上限导致多个大 PARENT 同时进入 Prompt。

#### Scenario: 聚合后的 PARENT 总上下文超过预算

- **GIVEN** 聚合后的 PARENT 证据组有 5 个
- **AND** 它们的上下文 token 总数超过配置的总 evidence token 上限
- **WHEN** 系统选择生成上下文
- **THEN** 系统 MUST 按 PARENT 证据组排序结果选择预算内的上下文
- **AND** 系统 MUST 不把所有 PARENT 无限制加入 Prompt
- **AND** 系统 MUST 记录被预算排除或截断的证据组

#### Scenario: PARENT 数量超过最大数量

- **GIVEN** 聚合后的 PARENT 证据组数量超过配置的最大 PARENT 数量
- **WHEN** 系统构建推荐理由生成上下文
- **THEN** 系统 MUST 只选择排序靠前且数量不超过配置上限的 PARENT 证据组
- **AND** 未选中的 PARENT 证据组 MAY 作为诊断信息保留

### Requirement: 超长 PARENT MUST 使用混合上下文策略

当 PARENT token 数超过配置的超长阈值或无法纳入总预算时，系统 MUST 不盲目使用整段 PARENT。系统 MUST 优先构建包含命中 CHILD 和同父相邻 CHILD 的覆盖窗口；预算允许时 MAY 补充 PARENT 的章节路径、标题或前部上下文，以保持总结型生成所需语义。

#### Scenario: PARENT 超过超长阈值

- **GIVEN** 某个 PARENT 的 token 数超过配置的超长阈值
- **AND** 该 PARENT 下有一个或多个命中 CHILD
- **WHEN** 系统构建该 PARENT 的生成上下文
- **THEN** 系统 MUST 优先包含命中的 CHILD
- **AND** 系统 MUST 在预算内补充同父相邻 CHILD
- **AND** 系统 MUST 标记该上下文由整段 PARENT 退化为 CHILD 覆盖窗口

#### Scenario: 小 PARENT 可直接使用

- **GIVEN** 某个 PARENT 的 token 数未超过单 PARENT 上限
- **AND** 总 evidence token 预算仍足够
- **WHEN** 系统构建该 PARENT 的生成上下文
- **THEN** 系统 MAY 使用完整 PARENT 作为上下文
- **AND** 系统 MUST 仍保留命中 CHILD 作为引用和定位依据

### Requirement: 推荐证据引用 MUST 保留 CHILD 粒度

系统使用 PARENT 证据组进行推荐理由生成时，MUST 保留命中 CHILD 的 chunkUid、sectionPath、页码或段落范围等可追溯信息。推荐输出和流式 evidence 事件 MUST 能指向实际命中的 CHILD，而不是只暴露被聚合后的 PARENT 文本。

#### Scenario: 生成上下文使用 PARENT

- **GIVEN** 系统选择完整 PARENT 作为生成上下文
- **AND** 该 PARENT 下有多个命中 CHILD
- **WHEN** 系统返回推荐证据或构建 citations
- **THEN** 系统 MUST 使用命中 CHILD 信息作为引用依据
- **AND** 系统 MUST 能展示 PARENT 与命中 CHILD 的对应关系

#### Scenario: 生成上下文使用 CHILD 覆盖窗口

- **GIVEN** 系统因 PARENT 过长使用 CHILD 覆盖窗口
- **WHEN** 系统返回推荐证据或构建 citations
- **THEN** 系统 MUST 标记上下文来源为 CHILD 窗口
- **AND** citations MUST 能对应到实际命中的 CHILD

