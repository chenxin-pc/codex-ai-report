# script-search-evaluation Specification

## Purpose
TBD - created by archiving change script-report-ingest-analysis-pipeline. Update Purpose after archive.
## Requirements
### Requirement: 脚本 MUST 按配置 query 执行搜索评估

脚本 MUST 从配置文件读取搜索评估 query 集合，并在研报导入完成后逐个执行搜索/推荐评估。评估 MUST 调用后端推荐接口或受控评估入口，确保结果代表真实检索推荐链路。

#### Scenario: 配置 query 后执行评估

- **GIVEN** 脚本配置中包含多个评估 query
- **AND** 本次脚本运行已成功导入至少一篇研报
- **WHEN** 脚本进入搜索评估阶段
- **THEN** 脚本 MUST 对每个 query 调用搜索/推荐能力
- **AND** 脚本 MUST 记录每个 query 的输入、输出和执行状态

#### Scenario: 未配置 query

- **GIVEN** 脚本配置中没有评估 query
- **WHEN** 脚本进入搜索评估阶段
- **THEN** 脚本 MUST 跳过搜索评估
- **AND** 脚本 MUST 在运行结果中记录跳过原因

### Requirement: 搜索评估脚本 MUST 记录召回结果和推荐输出

脚本 MUST 收集 query、规范化 query、topK 召回结果、score 或 distance、报告标题、来源、sectionPath、chunkText、parent 上下文、analysis、recommendation、risks 和 citations。

#### Scenario: 搜索返回候选结果

- **GIVEN** 后端搜索能力对某个 query 返回 topK 结果
- **WHEN** 脚本记录评估结果
- **THEN** 脚本 MUST 保存每条候选结果的排序、分数、报告信息、chunk metadata 和文本摘要
- **AND** 脚本 MUST 保存推荐模型的结构化输出

#### Scenario: 搜索无结果

- **GIVEN** 后端搜索能力对某个 query 返回空结果
- **WHEN** 脚本记录评估结果
- **THEN** 脚本 MUST 保存空召回状态
- **AND** 脚本 MUST 保存推荐输出中的证据不足说明

### Requirement: 脚本 MUST 生成搜索评估 Excel

脚本 MUST 将搜索评估输入和输出整理为 Excel，文件 MUST 至少包含 queries、top_results、recommendations、manual_review 这些 sheet。manual_review sheet MUST 预留人工评价字段，便于判断相似性和建议质量。

#### Scenario: 生成搜索评估 Excel

- **GIVEN** 脚本已完成至少一个 query 的搜索评估
- **WHEN** 脚本生成搜索评估 Excel
- **THEN** queries sheet MUST 包含 query、执行时间、执行状态和错误摘要
- **AND** top_results sheet MUST 包含 query、排序、score、报告标题、sectionPath、chunkText 和 parent 上下文摘要
- **AND** recommendations sheet MUST 包含 query、analysis、recommendation、risks 和 citations
- **AND** manual_review sheet MUST 包含是否相关、相关等级、问题备注、建议处理方式等人工填写字段

#### Scenario: 搜索接口调用失败

- **GIVEN** 脚本正在执行某个 query 的搜索评估
- **WHEN** 后端搜索或推荐接口调用失败
- **THEN** 脚本 MUST 记录失败 query、错误摘要和失败阶段
- **AND** 脚本 MUST 继续执行其他 query，除非用户配置为遇错停止

### Requirement: 搜索评估脚本 MUST 支持 Eval case 数据源

搜索评估脚本 MUST 支持从 eval case 数据源读取评测 query 和标准字段。Eval case 数据源 MUST 至少提供 caseId、query、expectedIntent、expectedAnchors、expectedOutputLevel、expectedDegradationReasons、reference contexts 和 forbidden rules。未启用 eval case 数据源时，脚本 MUST 保持现有配置 query 行为。

#### Scenario: 使用 eval case 执行搜索评估

- **GIVEN** 配置中指定 eval corpus 或 eval case 文件
- **WHEN** 脚本执行搜索评估
- **THEN** 脚本 MUST 逐条读取启用的 eval case
- **AND** 脚本 MUST 使用 case query 调用推荐接口或受控评测入口
- **AND** 脚本 MUST 将 caseId 和标准字段写入运行结果

#### Scenario: 未配置 eval case 数据源

- **GIVEN** 配置中只包含原有 evaluation.queries
- **WHEN** 脚本执行搜索评估
- **THEN** 脚本 MUST 继续按原有 query 列表执行
- **AND** 脚本 MUST NOT 要求提供 eval case 标准字段

### Requirement: 搜索评估脚本 MUST 输出自动判分字段

当使用 eval case 数据源时，搜索评估脚本 MUST 输出自动判分字段。字段 MUST 至少包含 intentMatch、anchorMatch、outputLevelMatch、degradationReasonMatch、referenceContextHit、forbiddenContextHit、forbiddenClaimHit 和 needsManualReview。

#### Scenario: 生成包含自动判分的 Excel

- **GIVEN** 脚本已完成 eval case 搜索评估
- **WHEN** 脚本生成搜索评估 Excel
- **THEN** Excel MUST 包含 eval_cases 或等价 sheet 展示 case 标准
- **AND** Excel MUST 包含 auto_scores 或等价 sheet 展示自动判分
- **AND** manual_review sheet MUST 保留人工复核字段

#### Scenario: 自动判分不可计算

- **GIVEN** eval case 缺少 reference context
- **WHEN** 脚本计算 referenceContextHit
- **THEN** 脚本 MUST 将该指标标记为不可计算
- **AND** 脚本 MUST 继续计算 intent、outputLevel 和 forbiddenClaims 等可计算指标

### Requirement: 搜索评估脚本 MUST 保存完整评测结果文件

当使用 eval case 数据源时，搜索评估脚本 MUST 保存完整 JSON 结果文件，避免 Excel 文本截断影响后续 Ragas 导出和失败归因。Excel MAY 继续使用摘要或截断文本便于人工查看。

#### Scenario: 保存完整 JSON 结果

- **GIVEN** eval case 搜索评估已完成
- **WHEN** 脚本写入运行输出目录
- **THEN** 脚本 MUST 保存包含完整 query、retrieved contexts、response、evidenceQuality 和自动判分的 JSON 文件
- **AND** Excel 中的文本截断 MUST NOT 影响 JSON 文件中的完整文本

#### Scenario: 推荐响应为空

- **GIVEN** 推荐接口返回空响应或无法解析响应
- **WHEN** 脚本保存完整结果
- **THEN** 脚本 MUST 记录失败状态和错误摘要
- **AND** 脚本 MUST NOT 将空响应写成成功评测记录

