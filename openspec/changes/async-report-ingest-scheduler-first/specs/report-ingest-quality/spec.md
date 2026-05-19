## MODIFIED Requirements

### Requirement: 质量数据 MUST 支持脚本按报告查询

系统 MUST 提供按 `reportId` 查询导入质量数据与阶段事件的能力，并支持按研报标题检索对应任务与阶段记录。阶段事件 MUST 包含耗时、模型信息、输入输出摘要、状态与错误信息。

#### Scenario: 按 reportId 查询完整阶段链路

- **GIVEN** 一篇报告已提交导入任务
- **WHEN** 运维或脚本按 `reportId` 查询阶段信息
- **THEN** 系统 MUST 返回 OCR、切片、向量化各阶段状态
- **AND** 每个阶段 MUST 包含开始时间、结束时间、耗时和模型标识

#### Scenario: 按标题检索阶段记录

- **GIVEN** 用户仅知道研报标题或标题关键词
- **WHEN** 用户在观测界面或查询接口按标题检索
- **THEN** 系统 MUST 能命中对应的任务与阶段事件记录
- **AND** 检索结果 MUST 可继续定位到报告级完整链路

#### Scenario: 导入失败时保留失败诊断

- **GIVEN** 一篇报告在 OCR、切片或向量化阶段失败
- **WHEN** 后续脚本或运维人员查询失败原因
- **THEN** 系统 MUST 提供失败阶段、错误摘要和重试次数
- **AND** 系统 MUST 不把失败导入伪装成成功导入

