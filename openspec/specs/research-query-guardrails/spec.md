# research-query-guardrails Specification

## Purpose
TBD - created by archiving change add-research-query-guardrails. Update Purpose after archive.
## Requirements
### Requirement: 系统 MUST 对用户输入执行投研可分析性判定

系统 MUST 在推荐召回前对用户输入进行投研可分析性判定，并将输入归类为直接分析、主题分析或不可分析。判定 MUST 优先使用本地规则词典；当规则低置信或未命中时，系统 MAY 调用 LLM 兜底分类。不可分析输入 MUST 不进入召回和模型生成。

#### Scenario: 非投研问候输入被拒绝

- **GIVEN** 用户输入为“你好”
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MUST 判定输入为不可分析
- **AND** 系统 MUST 返回投研模式引导语
- **AND** 系统 MUST 不调用向量召回

#### Scenario: 明确标的输入进入完整分析

- **GIVEN** 用户输入包含明确公司名或股票代码
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MUST 判定输入为直接分析
- **AND** 系统 MUST 允许进入召回和投研推理链路

#### Scenario: 行业主题输入进入主题分析

- **GIVEN** 用户输入为“分析港口行业近期景气度”
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MUST 判定输入为主题分析
- **AND** 系统 MUST 允许进入召回链路
- **AND** 后续输出 MAY 推荐相关公司候选
- **AND** 后续输出 MUST 说明候选公司与主题的关联依据

#### Scenario: 规则高置信命中不调用 LLM 分类

- **GIVEN** 用户输入命中本地规则词典中的高置信拒绝词、股票代码或主题研究词
- **WHEN** 系统执行投研可分析性判定
- **THEN** 系统 MUST 直接返回对应判定结果
- **AND** 系统 MUST 不调用 LLM 兜底分类

#### Scenario: 规则低置信时调用 LLM 兜底分类

- **GIVEN** 用户输入未命中本地规则词典
- **AND** 规则判定置信度低于配置阈值
- **WHEN** 系统执行投研可分析性判定
- **THEN** 系统 MAY 调用 LLM 兜底分类
- **AND** LLM MUST 仅返回 intent、confidence、reason 和 normalizedQuery 等结构化字段
- **AND** LLM MUST 不生成投研分析正文

#### Scenario: LLM 低置信或失败时保守拒绝

- **GIVEN** 规则判定低置信
- **AND** LLM 兜底分类置信度低于配置阈值、返回无法解析或调用失败
- **WHEN** 系统处理推荐请求
- **THEN** 系统 MUST 返回投研模式引导语
- **AND** 系统 MUST 不调用向量召回

### Requirement: 系统 MUST 按输出等级控制投研结论强度

系统 MUST 根据输入判定和证据质量将输出划分为 L0 拒答、L1 信息不足或污染、L2 主题分析、L3 完整分析。证据质量 MUST 至少依据证据存在性、query 相关性、主体一致性、数据一致性和引用完整性判定。命中 L0、L1 或 L2 时，系统 MUST 禁止输出高确定性投资建议；L2 MAY 推荐相关公司候选，但 MUST 将其限定为研究对象推荐。

#### Scenario: L0 拒答输出

- **GIVEN** 输入被判定为不可分析
- **WHEN** 系统生成响应
- **THEN** 系统 MUST 返回不超过短段落长度的投研模式引导语
- **AND** 响应 MUST 不包含分析、推荐、风险长文

#### Scenario: L1 信息污染输出

- **GIVEN** 召回证据存在多标的混杂或代码冲突
- **WHEN** 系统生成响应
- **THEN** 系统 MUST 降级为 L1
- **AND** 系统 MUST 明确说明证据无法支撑一致投研结论
- **AND** 系统 MUST 请求用户补充标的或代码

#### Scenario: L2 主题分析输出

- **GIVEN** 输入被判定为主题分析
- **AND** 召回证据包含多个与主题相关的上市公司或行业片段
- **WHEN** 系统生成响应
- **THEN** 系统 MUST 输出行业或主题层分析
- **AND** 系统 MAY 推荐相关公司候选
- **AND** 系统 MUST 说明每个候选公司的主题关联依据
- **AND** 系统 MUST 不输出买卖、评级、目标价或仓位建议

#### Scenario: L3 完整分析输出

- **GIVEN** 输入被判定为直接分析
- **AND** 召回证据标的明确、引用有效且一致性通过
- **WHEN** 系统生成响应
- **THEN** 系统 MAY 输出完整投研分析
- **AND** 响应 MUST 包含分析、建议、风险和证据引用

### Requirement: 系统 MUST 对降级输出禁用高确定性推荐语句

当输出等级为 L0、L1 或 L2 时，系统 MUST 禁止输出买入、增持、目标价、明确推荐、仓位建议等高确定性投资建议语句。

#### Scenario: 主题分析允许相关公司候选但禁止投资动作建议

- **GIVEN** 输出等级为 L2
- **WHEN** 系统生成推荐文本
- **THEN** 文本 MUST 不包含买入、增持、目标价、明确推荐或仓位建议
- **AND** 文本 MAY 包含相关公司候选
- **AND** 文本 MUST 将候选限定为研究对象或关注清单

#### Scenario: 证据污染禁止合并结论

- **GIVEN** 输出等级为 L1
- **AND** 证据来自多个不一致主体
- **WHEN** 系统生成推荐文本
- **THEN** 文本 MUST 不将多个主体证据合并为单一公司结论
- **AND** 文本 MUST 列出主要冲突原因

