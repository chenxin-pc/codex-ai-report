## ADDED Requirements

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
