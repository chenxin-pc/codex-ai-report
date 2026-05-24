## MODIFIED Requirements

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
