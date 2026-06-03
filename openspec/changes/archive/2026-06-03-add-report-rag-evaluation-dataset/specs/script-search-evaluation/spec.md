## ADDED Requirements

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
