## ADDED Requirements

### Requirement: 评测运行 MUST 保存可复现运行快照

系统 MUST 为每次 eval run 保存 runId、corpusId、appCommit、promptVersion、promptHash、embeddingModel、llmModel、retrievalConfig、dictionaryVersion、开始时间、结束时间、状态和错误摘要。运行快照 MUST 足以解释同一 case 在不同配置下的分数变化。

#### Scenario: 启动 eval run

- **GIVEN** 评测 corpus 和启用的 eval case 已存在
- **WHEN** 用户或脚本启动 eval run
- **THEN** 系统 MUST 创建 eval run 记录
- **AND** 系统 MUST 保存模型、prompt、词典和检索配置快照

#### Scenario: 配置缺失

- **GIVEN** eval run 无法获取 appCommit 或 promptHash
- **WHEN** 系统创建运行快照
- **THEN** 系统 MUST 使用明确的 unknown 或 empty 标记
- **AND** 系统 MUST NOT 因非核心快照字段缺失而伪造版本值

### Requirement: Eval case run MUST 记录输入判定、输出和证据质量

系统 MUST 为每个 case 的一次执行保存 query、actualIntent、actualAnchors、actualOutputLevel、actualDegradationReasons、responseText、analysis、recommendation、risks、citations、evidenceQuality、状态和错误摘要。

#### Scenario: Case 执行成功

- **GIVEN** eval run 正在执行某个 eval case
- **WHEN** 推荐链路返回成功响应
- **THEN** 系统 MUST 保存 actualIntent 和 actualOutputLevel
- **AND** 系统 MUST 保存 evidenceQuality、analysis、recommendation、risks 和 citations
- **AND** 系统 MUST 将 analysis、recommendation 和 risks 合成为可导出的 responseText

#### Scenario: 推荐接口调用失败

- **GIVEN** eval run 正在执行某个 eval case
- **WHEN** 推荐接口或受控评测入口调用失败
- **THEN** 系统 MUST 保存 case run 失败状态和错误摘要
- **AND** 系统 MUST 按运行配置决定继续后续 case 或停止

### Requirement: 评测运行 MUST 记录检索上下文轨迹

系统 MUST 为 case run 记录 retrieved context。每条 retrieved context MUST 包含阶段、rank、reportId、chunkUid、parentChunkUid、sectionPath、score、relevanceScore、contextType、diagnosticOnly、truncated、hitCount、retrievedText 和 metadata 快照。阶段 MUST 至少支持 FINAL，并预留 INITIAL、FILTERED 和 DIAGNOSTIC。

#### Scenario: 记录最终证据

- **GIVEN** 推荐链路返回 Top5 证据
- **WHEN** eval case run 保存检索结果
- **THEN** 系统 MUST 为每条 Top5 创建 FINAL retrieved context
- **AND** 系统 MUST 保存 chunkUid、parentChunkUid、rank、score 和 retrievedText

#### Scenario: 记录诊断候选

- **GIVEN** 检索链路返回低相关或主题未覆盖候选
- **WHEN** 受控评测入口暴露诊断候选
- **THEN** 系统 MUST 将这些候选保存为 DIAGNOSTIC 阶段
- **AND** 系统 MUST 标记 diagnosticOnly=true

#### Scenario: 初召回轨迹暂不可用

- **GIVEN** 当前推荐接口只返回 FINAL Top5
- **WHEN** eval run 保存检索轨迹
- **THEN** 系统 MUST 成功保存 FINAL 阶段
- **AND** 系统 MUST 不因 INITIAL 或 FILTERED 缺失而判定 case run 失败

### Requirement: 评测运行 MUST 计算项目内自动判分结果

系统 MUST 基于 eval case 标准和 case run 结果计算自动判分。自动判分 MUST 至少覆盖 intentMatch、anchorMatch、outputLevelMatch、degradationReasonMatch、referenceContextHit、forbiddenContextHit、forbiddenClaimHit 和 evidenceQualityMatch。

#### Scenario: 标准证据被召回

- **GIVEN** eval case 关联了 required reference context
- **AND** case run 的 FINAL retrieved context 包含对应 chunkUid 或 parentChunkUid
- **WHEN** 系统计算自动判分
- **THEN** 系统 MUST 标记 referenceContextHit=true
- **AND** 系统 SHOULD 记录命中的 rank

#### Scenario: 命中禁止证据

- **GIVEN** eval case 标注了禁止命中的主题、公司或关键词
- **AND** case run 的 retrieved context metadata 或文本命中该禁止项
- **WHEN** 系统计算自动判分
- **THEN** 系统 MUST 标记 forbiddenContextHit=true
- **AND** 系统 MUST 将该 case 计为需要复核或失败

#### Scenario: 输出包含禁用结论

- **GIVEN** eval case 标注了 forbiddenClaims 或 forbiddenTerms
- **AND** responseText 包含对应禁用内容
- **WHEN** 系统计算自动判分
- **THEN** 系统 MUST 标记 forbiddenClaimHit=true
- **AND** 系统 MUST 在评测结果中保留命中的禁用项

### Requirement: Eval run MUST 支持人工复核字段但不依赖人工判分

系统 MUST 支持为 case run 或 retrieved context 保存人工复核字段，包括相关性等级、问题备注和建议处理方式。自动评测结果 MUST 不依赖人工复核字段存在。

#### Scenario: 自动评测无人工复核

- **GIVEN** eval run 完成后没有人工复核记录
- **WHEN** 系统生成自动评测结果
- **THEN** 系统 MUST 输出自动判分
- **AND** 系统 MUST 将人工复核状态标记为未复核

#### Scenario: 补充人工复核

- **GIVEN** eval run 已生成自动判分
- **WHEN** 用户补充某条 retrieved context 的人工相关性等级
- **THEN** 系统 MUST 保存人工复核字段
- **AND** 系统 MUST NOT 覆盖原始自动判分结果
