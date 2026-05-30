## ADDED Requirements

### Requirement: 异步入库阶段重构 MUST 保持阶段语义和诊断可追溯

系统 MUST 在内部重构后继续按 OCR、CHUNK、VECTOR 三个阶段推进异步入库任务。阶段执行 MUST 保持现有状态码、尝试次数、重试退避、阶段事件、模型名、输出数量和错误摘要语义。系统 MUST 允许内部使用阶段执行模板、阶段 handler、轻量阶段描述和策略组件降低分支复杂度，但 MUST NOT 改变上传接口、状态查询接口、质量观测接口或阶段事件对外含义。

#### Scenario: 成功任务按三阶段推进

- **GIVEN** 一篇报告已通过异步上传创建任务，且 OCR、CHUNK、VECTOR 阶段依赖均满足
- **WHEN** 系统依次执行 OCR、CHUNK、VECTOR 阶段
- **THEN** 每个阶段 MUST 在成功后记录 `SUCCEEDED` 状态
- **AND** 每个阶段 MUST 写入包含阶段名、尝试次数、模型名、耗时和输出数量的阶段事件
- **AND** 任务状态查询 MUST 继续返回与重构前等价的阶段状态和尝试次数

#### Scenario: 阶段失败保留重试和最终失败语义

- **GIVEN** 某个异步入库阶段执行时发生异常
- **WHEN** 异常被判定为可重试且尝试次数未达到上限
- **THEN** 系统 MUST 按退避策略设置下一次可执行时间
- **AND** 系统 MUST 保留错误码、错误摘要和本次失败阶段事件
- **AND** 系统 MUST NOT 将该任务伪装成成功

#### Scenario: 前置阶段未成功时后置阶段不可执行

- **GIVEN** OCR 阶段尚未成功或 CHUNK 阶段尚未成功
- **WHEN** 调度器拉取 CHUNK 或 VECTOR 阶段可执行任务
- **THEN** 系统 MUST 继续遵守前置阶段成功后才能执行后置阶段的约束
- **AND** VECTOR 阶段 MUST NOT 在没有可用 CHILD chunk 前被标记为成功入库

#### Scenario: 阶段处理中状态可被观测

- **GIVEN** 某个阶段即将执行 OCR、LLM 切片或 Milvus 写入等耗时外部动作
- **WHEN** 系统占用该阶段任务并开始处理
- **THEN** 系统 MUST 先持久化该阶段的 `PROCESSING` 状态
- **AND** 外部耗时动作 MUST NOT 阻止后续成功或失败状态被独立记录

### Requirement: 入库策略拆分 MUST 保持 chunk 过滤和向量入库行为

系统 MUST 在拆分 chunk 过滤、向量文档构建和批量写入边界后保持现有入库质量语义。Service 层 MUST 继续基于 `segmentType`、`sectionPath`、文本质量指标和财务表格豁免决定 chunk 是否进入 Milvus；向量阶段 MUST 只处理未向量化的 CHILD chunk，并 MUST 在 Milvus 写入成功后回写 `vectorStored=true`。

#### Scenario: 过滤策略保持原有过滤原因

- **GIVEN** 切片结果命中可过滤的 `segmentType`、声明类 `sectionPath`、过低 token、短文本、低汉字比例或高噪声比例
- **WHEN** 系统执行入库前过滤
- **THEN** 系统 MUST 记录与重构前等价的过滤原因
- **AND** 被过滤 chunk MUST NOT 写入 Milvus
- **AND** 过滤诊断 MUST 继续可按 `reportId` 查询

#### Scenario: 财务表格豁免不被策略拆分破坏

- **GIVEN** chunk 包含盈利预测、财务指标、EPS、P/E 或 P/B 等财务表格特征
- **WHEN** 系统执行低语义过滤判断
- **THEN** 系统 MUST 继续识别财务表格候选
- **AND** 系统 MUST NOT 仅因低汉字比例过滤该 chunk

#### Scenario: 向量阶段只处理未入库 CHILD

- **GIVEN** 一篇报告同时包含 PARENT chunk、已向量化 CHILD chunk 和未向量化 CHILD chunk
- **WHEN** 系统执行 VECTOR 阶段
- **THEN** 系统 MUST 只为未向量化 CHILD 构建向量文档
- **AND** 系统 MUST 在 Milvus 写入成功后将这些 CHILD 的 `vectorStored` 标记为 true
- **AND** 系统 MUST NOT 重复写入已向量化 CHILD

#### Scenario: metadata 构建保持兼容

- **GIVEN** 结构化 metadata 同步服务可用
- **WHEN** 系统为 CHILD chunk 构建 Milvus Document
- **THEN** 系统 MUST 优先使用结构化 metadata 构建逻辑
- **AND** 当结构化 metadata 服务不可用时，系统 MUST 继续使用包含 report、chunk、sectionPath、页码和标签占位字段的默认 metadata
