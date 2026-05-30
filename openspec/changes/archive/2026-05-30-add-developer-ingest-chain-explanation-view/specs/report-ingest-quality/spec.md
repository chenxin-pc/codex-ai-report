## MODIFIED Requirements

### Requirement: 质量数据 MUST 支持脚本按报告查询

系统 MUST 提供按 `reportId` 查询导入质量数据、阶段事件和阶段解释数据的能力，并支持按研报标题检索对应任务与阶段记录。阶段事件 MUST 包含耗时、模型信息、输入输出摘要、状态与错误信息。阶段解释数据 MUST 能表达 OCR、语义切片和向量入库各阶段的操作说明、执行前数据状态、执行后数据状态、读写数据、影响点和失败解释。质量数据 MUST 支持把 `report_document`、`report_ocr_page`、`report_paragraph_atom`、`report_chunk`、`report_chunk_diagnostic`、阶段事件和 CHILD 向量状态按 reportId 关联起来。

#### Scenario: 按 reportId 查询完整阶段链路

- **GIVEN** 一篇报告已提交导入任务
- **WHEN** 运维、脚本或观测页面按 `reportId` 查询阶段信息
- **THEN** 系统 MUST 返回 OCR、切片、向量化各阶段状态
- **AND** 每个阶段 MUST 包含开始时间、结束时间、耗时和模型标识
- **AND** 每个阶段 MUST 包含操作摘要、执行后数据摘要和后续影响说明

#### Scenario: 按标题检索阶段记录

- **GIVEN** 用户仅知道研报标题或标题关键词
- **WHEN** 用户在观测界面或查询接口按标题检索
- **THEN** 系统 MUST 能命中对应的任务与阶段事件记录
- **AND** 检索结果 MUST 可继续定位到报告级完整链路
- **AND** 当标题关键词命中多个报告时，系统 MUST 保留 reportId 和标题信息以便用户选择明确报告

#### Scenario: 导入失败时保留失败诊断

- **GIVEN** 一篇报告在 OCR、切片或向量化阶段失败
- **WHEN** 后续脚本、运维人员或开发者观测页面查询失败原因
- **THEN** 系统 MUST 提供失败阶段、错误摘要和重试次数
- **AND** 系统 MUST 不把失败导入伪装成成功导入
- **AND** 系统 MUST 说明失败阶段对后续阶段的影响

#### Scenario: 按 reportId 关联语义切片数据

- **GIVEN** 一篇报告已完成或部分完成语义切片
- **WHEN** 系统按 `reportId` 构建质量数据或阶段解释数据
- **THEN** 系统 MUST 能一次性关联该报告的 `report_chunk` 和 `report_chunk_diagnostic`
- **AND** 系统 MUST 能区分最终落库 chunk 与仅存在于 diagnostic 的过滤候选
- **AND** 系统 MUST 能通过 parentChunkUid 将 CHILD 与 PARENT 关联

#### Scenario: 查询向量入库前 CHILD 状态

- **GIVEN** 一篇报告已经产生 CHILD chunk
- **WHEN** 系统按 `reportId` 构建向量入库阶段数据
- **THEN** 系统 MUST 能统计 kept CHILD 候选数量
- **AND** 系统 MUST 能统计 vectorStored=true 和 vectorStored=false 的 CHILD 数量
- **AND** 系统 MUST 能返回 CHILD 与对应 PARENT 的定位信息
